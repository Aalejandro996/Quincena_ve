from __future__ import annotations

import sys
sys.modules.setdefault("app", sys.modules[__name__])  # "python app.py": las rutas importan este mismo módulo como "app"

import base64
import copy
import hmac
import json
import logging
import math
import os
import re
import secrets
import string
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlsplit
import hashlib
import smtplib
import ssl
import time
import threading
import urllib.error
import urllib.parse
import urllib.request
from email.message import EmailMessage
from email.utils import parseaddr
from html import escape as html_escape
from zoneinfo import ZoneInfo
from functools import wraps
from pathlib import Path

from sqlalchemy import inspect as sqlalchemy_inspect

from dotenv import load_dotenv
from flask import Flask, abort, g, jsonify, request, redirect, send_from_directory, session
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_sqlalchemy import SQLAlchemy
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.security import check_password_hash, generate_password_hash
from webauthn import (
    generate_registration_options, verify_registration_response,
    generate_authentication_options, verify_authentication_response,
    options_to_json, base64url_to_bytes,
)
from webauthn.helpers import bytes_to_base64url
from webauthn.helpers.structs import (
    AuthenticatorSelectionCriteria, AttestationConveyancePreference,
    PublicKeyCredentialDescriptor, ResidentKeyRequirement,
    UserVerificationRequirement,
)

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")
if os.getenv("RENDER") and not os.getenv("APP_SECRET_KEY"):
    raise RuntimeError("APP_SECRET_KEY must be configured in the production environment.")
if os.getenv("APP_SECRET_KEY") and ("REEMPLAZA" in os.getenv("APP_SECRET_KEY", "") or len(os.getenv("APP_SECRET_KEY", "")) < 32):
    raise RuntimeError("APP_SECRET_KEY must be a unique random value with at least 32 characters.")


def database_url() -> str:
    raw = os.getenv("DATABASE_URL", "sqlite:///control_financiero.db").strip()
    if raw.startswith("postgres://"):
        raw = "postgresql+psycopg://" + raw[len("postgres://"):]
    elif raw.startswith("postgresql://") and "+psycopg" not in raw:
        raw = "postgresql+psycopg://" + raw[len("postgresql://"):]
    return raw


app = Flask(__name__, static_folder="static", static_url_path="/static")
app.logger.setLevel(logging.INFO)  # en Render también se ven los avisos informativos (p. ej. de dónde salió la tasa)
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
app.config.update(
    SECRET_KEY=os.getenv("APP_SECRET_KEY") or secrets.token_hex(32),
    SQLALCHEMY_DATABASE_URI=database_url(),
    SQLALCHEMY_TRACK_MODIFICATIONS=False,
    # PostgreSQL de Render corta las conexiones inactivas: se prueba cada una antes de usarla y se renuevan cada 5 minutos,
    # así no salen errores "SSL error: bad record mac / unexpected eof" ni falla /healthz.
    SQLALCHEMY_ENGINE_OPTIONS={"pool_pre_ping": True, "pool_recycle": 300},
    # "__Host-" obliga a HTTPS, sin dominio y ruta "/": el navegador la protege mejor.
    SESSION_COOKIE_NAME="__Host-quincena" if os.getenv("COOKIE_SECURE", "true" if os.getenv("RENDER") else "false").lower() == "true" else "quincena_session",
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SECURE=os.getenv("COOKIE_SECURE", "true" if os.getenv("RENDER") else "false").lower() == "true",
    SESSION_COOKIE_SAMESITE="Strict",
    PERMANENT_SESSION_LIFETIME=timedelta(hours=12),
    MAX_CONTENT_LENGTH=2 * 1024 * 1024,
)
db = SQLAlchemy(app)
limiter = Limiter(get_remote_address, app=app, default_limits=[os.getenv("API_DEFAULT_LIMIT", "240 per minute")],
                  storage_uri=os.getenv("RATELIMIT_STORAGE_URI", "memory://"))

# Países soportados. "BS" sigue siendo el código interno de la moneda local (así los datos ya guardados no cambian):
# para cada persona significa "mi moneda local", y el país dice cuál es.
COUNTRIES = {
    "VE": {"name": "Venezuela", "cur": "VES", "sym": "Bs.", "plural": "bolívares", "dec": 2, "official": "BCV", "market": "USDT", "rate": 40.0},
    "CO": {"name": "Colombia", "cur": "COP", "sym": "COP $", "plural": "pesos colombianos", "dec": 0, "official": "TRM", "market": "", "rate": 4000.0},
    "CL": {"name": "Chile", "cur": "CLP", "sym": "CLP $", "plural": "pesos chilenos", "dec": 0, "official": "oficial", "market": "", "rate": 950.0},
    "PE": {"name": "Perú", "cur": "PEN", "sym": "S/", "plural": "soles", "dec": 2, "official": "oficial", "market": "", "rate": 3.7},
    "MX": {"name": "México", "cur": "MXN", "sym": "MX$", "plural": "pesos mexicanos", "dec": 2, "official": "oficial", "market": "", "rate": 18.0},
    "AR": {"name": "Argentina", "cur": "ARS", "sym": "AR$", "plural": "pesos argentinos", "dec": 2, "official": "oficial", "market": "", "rate": 1400.0},
}
DEFAULT_COUNTRY = "VE"


def clean_country(value) -> str:
    code = str(value or "").strip().upper()
    return code if code in COUNTRIES else DEFAULT_COUNTRY


SOURCE_UNSAFE = re.compile(r"[^a-z0-9._-]")


def clean_source(value, user_agent: str = "") -> str | None:
    """Origen del registro según los utm del enlace ("facebook/post1"). Sin campaña: "android" si viene de la app, o None."""
    parts = [SOURCE_UNSAFE.sub("", part.strip().lower().replace(" ", "-"))[:30] for part in str(value or "").split("/")[:4]]
    parts = [part for part in parts if part]
    if parts:
        return "/".join(parts)[:120]
    return "android" if "okhttp" in user_agent.lower() else None


DEFAULT_SHARED_DATA = {
    "country": DEFAULT_COUNTRY,  # país de la persona: define la moneda local, su tasa y cómo se muestran los montos
    "currency": "USD",
    "exchangeRate": 40.0,
    "marketRate": 0.0,  # dólar del mercado (USDT/paralelo) en Bs.; 0 = desconocido
    "euroRate": 0.0,  # euro oficial del BCV en Bs.; 0 = desconocido
    "bsSavings": 0.0,  # bolívares que la persona dice tener guardados (para el medidor de poder de compra)
    "rateHistory": [],  # [{"d": "AAAA-MM-DD", "b": tasa BCV, "m": tasa USDT}] un registro por día, máx. 120
    "accounts": [],  # cuentas: banco, efectivo, digital (Zelle, Binance…) con saldo inicial
    "transfers": [],  # movimientos entre cuentas (incluye cambios de $ a Bs.)
    "incomes": [],  # ingresos recibidos de verdad (cobros del sueldo, extras, devoluciones)
    "receivables": [],  # "Me deben": préstamos a otras personas
    "termsVersion": "",  # versión de términos aceptada
    "income": {"amount": 0, "currency": "USD"},  # compatibilidad con la versión anterior
    "incomeSources": [],  # sueldo, bono, cestatickets y otros ingresos programados
    "budgets": {"transporte": 0, "comida": 0, "fijo": 0, "hormiga": 0, "salud": 0, "hogar": 0, "ocio": 0, "educacion": 0, "deudas": 0},
    "expenses": [],
    "debts": [],
    "schoolExpenses": [],  # compromisos/cargos del colegio
    "schoolPayments": [],  # pagos parciales o totales de esos compromisos
    "plannedDebtPayments": [],  # agenda personal de pagos futuros de deuda
    "recurring": [],  # gastos fijos que se repiten cada mes
    "debtPlan": {"monthlyEssentials": 0.0, "monthlyCushion": 0.0, "paymentPercent": 60, "strategy": "avalanche", "configured": False},
    # Quincena VE Negocio: el dinero del negocio va aparte del de la casa (no entra en gastos ni presupuesto personales).
    "business": {"enabled": False, "name": "", "phone": ""},
    "sales": [],  # ventas; las de crédito son el fiado (credit) y se van pagando con bizPayments
    "bizPayments": [],  # abonos de clientes a ventas fiadas
    "bizExpenses": [],  # compras de mercancía y gastos del negocio; "retiro" = el dueño se paga a sí mismo
    "products": [],  # catálogo (Plus): precio en $, existencia contada en stockDate
    "cashClosings": [],  # cierres de caja: lo que se contó al final del día
}

BIZ_RATE_KINDS = ("bcv_usd", "bcv_eur", "usdt")
EXPENSE_METHODS = {"banco", "efectivo", "pago_movil", "tarjeta", "punto", "zelle", "binance"}
# Tipo de negocio: los de servicios (profesionales, salud, oficios, clases, creativos) hablan de cobros, servicios y clientes.
BIZ_TYPES = ("comercio", "comida", "belleza", "online", "profesional", "salud", "tecnico", "educacion", "creativo", "otro")
BIZ_PROFESSIONS = ("abogado", "contador", "administrador", "consultor", "arquitecto", "otro")


def business_rate(data: dict) -> float:
    """Bs. por unidad de la divisa con que trabaja el negocio (dólar BCV, euro BCV o USDT); si falta, el dólar BCV."""
    kind = (data.get("business") or {}).get("rateKind")
    chosen = data.get("euroRate") if kind == "bcv_eur" else data.get("marketRate") if kind == "usdt" else None
    return _rate_or_none(chosen) or data["exchangeRate"]


SALE_METHODS = "pago_movil|efectivo_bs|efectivo_usd|zelle|binance|punto|transferencia|otro"
BIZ_CATEGORIES = "mercancia|alquiler|servicios|sueldos|transporte|impuestos|retiro|otro"


class User(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(40), unique=True, nullable=False, index=True)
    password_hash = db.Column(db.String(255), nullable=False)
    email = db.Column(db.String(254), nullable=True, index=True)
    email_verified_at = db.Column(db.DateTime(timezone=True), nullable=True)
    role = db.Column(db.String(16), nullable=False, default="member")  # admin | member
    must_change_password = db.Column(db.Boolean, nullable=False, default=False)
    active = db.Column(db.Boolean, nullable=False, default=True)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    signup_source = db.Column(db.String(120), nullable=True)  # de qué publicación o anuncio llegó (utm); None = directo
    plan = db.Column(db.String(16), nullable=True)  # None/"free" o "plus" (Negocio Plus, lo activa un administrador)
    plan_until = db.Column(db.DateTime(timezone=True), nullable=True)  # vence el Plus; None = sin vencimiento
    owner_id = db.Column(db.Integer, nullable=True, index=True)  # cuenta de vendedor: id del dueño del negocio


class ProductLicense(db.Model):
    """Licencia individual que activó el titular de esta cuenta."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, unique=True, index=True)
    token = db.Column(db.Text, nullable=False)
    expires_at = db.Column(db.DateTime(timezone=True), nullable=False)
    updated_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class SharedState(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    # Cada fila contiene los datos financieros privados de exactamente una cuenta.
    # Nullable durante la migración de la antigua fila compartida; la migración
    # asigna esa información histórica al administrador original.
    user_id = db.Column(db.Integer, db.ForeignKey("user.id"), nullable=True)
    revision = db.Column(db.Integer, nullable=False, default=0)
    data = db.Column(db.JSON, nullable=False, default=lambda: copy.deepcopy(DEFAULT_SHARED_DATA))
    updated_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    updated_by = db.Column(db.Integer, db.ForeignKey("user.id"), nullable=True)


class AuditEvent(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id"), nullable=True, index=True)
    action = db.Column(db.String(64), nullable=False)
    detail = db.Column(db.String(255), nullable=True)
    ip_address = db.Column(db.String(64), nullable=True)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc), index=True)


class UserPasskey(db.Model):
    """Public WebAuthn credential registered by one private account."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="RESTRICT"), nullable=False, index=True)
    credential_id = db.Column(db.String(1024), nullable=False, unique=True)
    public_key = db.Column(db.Text, nullable=False)
    sign_count = db.Column(db.BigInteger, nullable=False, default=0)
    label = db.Column(db.String(80), nullable=False, default="Este dispositivo")
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))


class PushSubscription(db.Model):
    """Browser push endpoint scoped to the account that opted in."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, index=True)
    endpoint = db.Column(db.String(2048), nullable=False, unique=True)
    subscription_json = db.Column(db.JSON, nullable=False)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    updated_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class ReminderDelivery(db.Model):
    """Deduplication ledger: one reminder per payment/date/lead time/account."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, index=True)
    payment_key = db.Column(db.String(140), nullable=False)
    due_date = db.Column(db.String(10), nullable=False)
    days_before = db.Column(db.Integer, nullable=False)
    sent_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    __table_args__ = (db.UniqueConstraint("user_id", "payment_key", "due_date", "days_before", name="uq_reminder_delivery"),)


class PasswordReset(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = db.Column(db.String(64), nullable=False, unique=True)
    expires_at = db.Column(db.DateTime(timezone=True), nullable=False)
    used_at = db.Column(db.DateTime(timezone=True), nullable=True)

class DeviceToken(db.Model):
    """Sesión de larga duración para la app móvil (se guarda solo el hash del token)."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = db.Column(db.String(64), nullable=False, unique=True)
    label = db.Column(db.String(80), nullable=False, default="Android")
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    last_used_at = db.Column(db.DateTime(timezone=True), nullable=True)
    expires_at = db.Column(db.DateTime(timezone=True), nullable=False)
    revoked_at = db.Column(db.DateTime(timezone=True), nullable=True)


class Suggestion(db.Model):
    """Sugerencias que mandan las personas desde Más (web y app); el administrador las lee en /admin."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="SET NULL"), nullable=True, index=True)
    text = db.Column(db.String(1000), nullable=False)
    kind = db.Column(db.String(16), nullable=False, default="idea")  # idea | problema | otro
    platform = db.Column(db.String(16), nullable=True)  # web | android
    status = db.Column(db.String(16), nullable=False, default="nueva")  # nueva | vista | hecha
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc), index=True)


class EmailVerification(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = db.Column(db.String(64), nullable=False, unique=True)
    expires_at = db.Column(db.DateTime(timezone=True), nullable=False)
    used_at = db.Column(db.DateTime(timezone=True), nullable=True)


def migrate_user_email() -> None:
    """Añade user.email, user.email_verified_at y user.signup_source si faltan. Debe correr antes de cualquier consulta a User."""
    def columns() -> set:
        return {c["name"] for c in sqlalchemy_inspect(db.engine).get_columns("user")}

    for name, ddl in (("email", "VARCHAR(254)"), ("email_verified_at", "TIMESTAMP WITH TIME ZONE"), ("signup_source", "VARCHAR(120)"),
                      ("plan", "VARCHAR(16)"), ("plan_until", "TIMESTAMP WITH TIME ZONE"), ("owner_id", "INTEGER")):
        if name in columns():
            continue
        try:
            db.session.execute(db.text(f'ALTER TABLE "user" ADD COLUMN {name} {ddl}'))
            db.session.commit()
        except Exception:
            db.session.rollback()
            if name not in columns():  # si otro proceso la creó al mismo tiempo, no es un error
                raise


def ensure_user_state(user_id: int) -> SharedState:
    """Return only this account's private financial state, creating it if needed."""
    record = SharedState.query.filter_by(user_id=user_id).first()
    if record is not None:
        return record

    # Recovery for a legacy database whose original singleton row had no owner
    # because it was created before any user existed. Only the sole initial admin
    # may claim it; never hand an orphaned state to an arbitrary later member.
    user = db.session.get(User, user_id)
    orphan = SharedState.query.filter(SharedState.user_id.is_(None)).order_by(SharedState.id.asc()).first()
    if orphan is not None and user and user.role == "admin" and User.query.count() == 1:
        orphan.user_id = user_id
        try:
            db.session.commit()
            return orphan
        except Exception:
            db.session.rollback()
            record = SharedState.query.filter_by(user_id=user_id).first()
            if record is not None:
                return record
            raise

    record = SharedState(user_id=user_id, revision=0, data=copy.deepcopy(DEFAULT_SHARED_DATA), updated_by=user_id)
    db.session.add(record)
    try:
        db.session.commit()
    except Exception:
        # Multiple workers may initialize the same user simultaneously.
        db.session.rollback()
        record = SharedState.query.filter_by(user_id=user_id).first()
        if record is None:
            raise
    return record


def migrate_shared_state_schema() -> None:
    """Migrate the old single shared record without deleting financial data.

    The old app did not record which account owned each record. To avoid exposing
    that historical common pool to every new account, it is retained under the
    earliest administrator; every other account receives an empty private state.
    """
    inspector = sqlalchemy_inspect(db.engine)
    if "shared_state" not in inspector.get_table_names():
        return
    columns = {col["name"] for col in inspector.get_columns("shared_state")}
    if "user_id" not in columns:
        db.session.execute(db.text('ALTER TABLE shared_state ADD COLUMN user_id INTEGER REFERENCES "user" (id)'))
        db.session.commit()

    first_admin = User.query.filter_by(role="admin").order_by(User.created_at.asc(), User.id.asc()).first()
    if first_admin is None:
        first_admin = User.query.order_by(User.created_at.asc(), User.id.asc()).first()
    if first_admin is not None:
        db.session.execute(
            db.text("UPDATE shared_state SET user_id = :user_id WHERE user_id IS NULL"),
            {"user_id": first_admin.id},
        )
        db.session.commit()

    # Unique owner index prevents accidental duplicate state rows per account.
    db.session.execute(db.text(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_shared_state_user_id ON shared_state (user_id)"
    ))
    db.session.commit()


def audit(action: str, detail: str = "", user_id: int | None = None) -> None:
    try:
        db.session.add(AuditEvent(
            user_id=user_id if user_id is not None else session.get("uid"),
            action=action[:64], detail=detail[:255] if detail else None,
            ip_address=(request.remote_addr or "")[:64],  # ProxyFix ya tomó la IP real del proxy de Render; X-Forwarded-For lo escribe el cliente
        ))
        db.session.commit()
    except Exception:
        db.session.rollback()
        app.logger.exception("No se pudo guardar el evento de auditoría")


COMMON_PASSWORDS = {
    "123456789012", "1234567890123", "contraseña123", "contrasena123", "password1234", "password12345", "qwertyuiop12",
    "venezuela123", "venezuela1234", "123456789abc", "abc123456789", "quincena1234", "quincena12345", "000000000000",
    "111111111111", "iloveyou1234", "admin1234567", "administrador", "caracas12345", "123123123123", "qwerty123456",
}


def pw_stamp(user: "User") -> str:
    """Huella de la contraseña actual. Si la contraseña cambia, las sesiones web abiertas con la anterior dejan de valer."""
    return hashlib.sha256(("qz1:" + user.password_hash).encode()).hexdigest()[:20]


DUMMY_HASH = generate_password_hash("quincena-" + secrets.token_hex(12))


def verify_login(user: "User | None", password: str) -> bool:
    """Comprueba la contraseña tardando lo mismo exista o no el usuario (evita adivinar usuarios por el tiempo de respuesta)."""
    if len(password) > 1000:  # nadie escribe 1000 caracteres; evita gastar CPU con claves enormes
        return False
    ok = check_password_hash(user.password_hash if user else DUMMY_HASH, password)
    return bool(ok and user)


def _login_name_key() -> str:
    body = request.get_json(silent=True) or {}
    return "login:" + str(body.get("username", "")).strip().lower()[:40]


def _forgot_email_key() -> str:
    body = request.get_json(silent=True) or {}
    return "forgot:" + str(body.get("email", "")).strip().lower()[:120]


def password_problem(password: str, username: str = "") -> str | None:
    """Devuelve el motivo por el que una contraseña no sirve, o None si está bien."""
    if len(password) < 12 or len(password) > 200:
        return "Usa una contraseña de 12 a 200 caracteres."
    low = password.lower()
    if low in COMMON_PASSWORDS or len(set(password)) < 5:
        return "Esa contraseña es muy fácil de adivinar. Elige otra más única."
    if username and len(username) >= 3 and username.lower() in low:
        return "La contraseña no debe contener tu nombre de usuario."
    return None


def setup_required() -> bool:
    return db.session.query(User.id).first() is None


def _aware(value):
    return value.replace(tzinfo=timezone.utc) if value is not None and value.tzinfo is None else value


def user_from_bearer() -> User | None:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return None
    row = DeviceToken.query.filter_by(token_hash=hashlib.sha256(header[7:].strip().encode()).hexdigest(), revoked_at=None).first()
    now = datetime.now(timezone.utc)
    if row is None or _aware(row.expires_at) < now:
        return None
    user = db.session.get(User, row.user_id)
    if user is None or not user.active:
        return None
    if row.last_used_at is None or now - _aware(row.last_used_at) > timedelta(hours=1):
        row.last_used_at, row.expires_at = now, now + timedelta(days=90)  # la sesión se renueva al usarla
        db.session.commit()
    g.via_token = True
    return user


SESSION_IDLE = timedelta(minutes=int(os.getenv("SESSION_IDLE_MINUTES", "30") or 30))


def current_user() -> User | None:
    user_id = session.get("uid")
    if not user_id:
        return user_from_bearer()
    now = time.time()
    last = float(session.get("seen", now))
    if now - last > SESSION_IDLE.total_seconds():
        session.clear()  # inactiva demasiado tiempo: hay que volver a entrar
        return None
    if now - last > 60:
        session["seen"] = now
    user = db.session.get(User, user_id)
    if user is None or not user.active:
        session.clear()
        return None
    if session.get("ps") != pw_stamp(user):  # la contraseña cambió después de abrir esta sesión
        session.clear()
        return None
    return user


def current_bearer_hash() -> str | None:
    header = request.headers.get("Authorization", "")
    return hashlib.sha256(header[7:].strip().encode()).hexdigest() if header.startswith("Bearer ") else None


def revoke_other_devices(user_id: int) -> int:
    """Cierra la sesión de la app en todos los teléfonos menos el que hace la petición."""
    keep = current_bearer_hash()
    now = datetime.now(timezone.utc)
    q = DeviceToken.query.filter_by(user_id=user_id, revoked_at=None)
    if keep:
        q = q.filter(DeviceToken.token_hash != keep)
    rows = q.all()
    for row in rows:
        row.revoked_at = now
    return len(rows)


def csrf_ok() -> bool:
    token = session.get("csrf_token", "")
    supplied = request.headers.get("X-CSRF-Token", "")
    return bool(token and supplied and hmac.compare_digest(token, supplied))


_PRODUCT_LICENSE_KEY_CACHE = {"key": "", "until": 0.0}
_PRODUCT_LICENSE_KEY_LOCK = threading.Lock()


def product_license_public_key() -> str:
    key = os.getenv("PRODUCT_LICENSE_PUBLIC_KEY", "").strip()
    if key:
        return key
    with _PRODUCT_LICENSE_KEY_LOCK:
        if _PRODUCT_LICENSE_KEY_CACHE["until"] > time.monotonic():
            return _PRODUCT_LICENSE_KEY_CACHE["key"]
        server = os.getenv("PRODUCT_LICENSE_SERVER_URL", "https://erp-licencias.onrender.com").strip().rstrip("/")
        with urllib.request.urlopen(server + "/v1/clave-publica", timeout=5) as response:
            payload = json.loads(response.read(4096))
        key = str(payload.get("ed25519", "")).strip()
        if not key:
            raise ValueError("El Maestro no publicó su clave de verificación.")
        _PRODUCT_LICENSE_KEY_CACHE.update(key=key, until=time.monotonic() + 300)
        return key


def verify_product_license(token: str, user: User) -> tuple[dict | None, str | None]:
    """Comprueba licencia individual emitida y firmada por el Maestro de ERP."""
    try:
        encoded, signature = token.strip().split(".", 1)
        decode = lambda value: base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
        payload = decode(encoded)
        claims = json.loads(payload)
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
        Ed25519PublicKey.from_public_bytes(decode(product_license_public_key())).verify(decode(signature), payload)
        expires = int(claims.get("exp", 0))
        if claims.get("producto") != "control_financiero" or claims.get("tipo_sujeto") != "cuenta":
            return None, "license_product_mismatch"
        if claims.get("estado") != "activa" or expires <= int(time.time()):
            return None, "license_expired_or_inactive"
        if int((claims.get("limites") or {}).get("max_usuarios", 1)) != 1:
            return None, "license_account_limit_mismatch"
        email = (user.email or "").strip().casefold()
        subject = str(claims.get("sujeto", "")).strip().casefold()
        if not email or not user.email_verified_at or subject != email:
            return None, "license_subject_mismatch"
        expected = os.getenv("PRODUCT_LICENSE_SUBJECT", "").strip().casefold()
        if expected and expected != email:
            return None, "license_subject_mismatch"
        return claims, None
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        return None, "license_verification_unavailable"
    except Exception:
        return None, "invalid_license"


def license_required() -> bool:
    return os.getenv("CONTROL_FINANCIERO_LICENSE_ENFORCE", "0").strip().lower() in {"1", "true", "yes"}


def license_for_user(user: User) -> tuple[dict | None, str | None]:
    row = ProductLicense.query.filter_by(user_id=user.id).first()
    return verify_product_license(row.token, user) if row else (None, "license_missing")


def is_plus(user: "User") -> bool:
    """Negocio Plus activo: lo activa un administrador (tras el pago) y puede tener fecha de vencimiento."""
    if not user or (user.plan or "free") != "plus":
        return False
    until = user.plan_until
    if until is not None and until.tzinfo is None:
        until = until.replace(tzinfo=timezone.utc)
    return until is None or until > datetime.now(timezone.utc)


def user_payload(user: "User") -> dict:
    owner = db.session.get(User, user.owner_id) if user.owner_id else None
    business = ""
    if owner is not None:
        state = SharedState.query.filter_by(user_id=owner.id).first()
        business = str(((state.data or {}).get("business") or {}).get("name") or "") if state else ""
    return {"username": user.username, "role": user.role, "must_change_password": user.must_change_password,
            "plan": "plus" if is_plus(user) else "free", "plan_until": user.plan_until.isoformat() if user.plan_until else None,
            "seller": owner is not None, "owner": owner.username if owner is not None else None, "business": business or (owner.username if owner else "")}


def require_auth(admin: bool = False, allow_password_change: bool = False):
    def decorate(fn):
        @wraps(fn)
        def wrapped(*args, **kwargs):
            user = current_user()
            if user is None:
                return jsonify(error="Debes iniciar sesión.", code="unauthorized"), 401
            if admin and user.role != "admin":
                return jsonify(error="Esta acción requiere permisos de administrador."), 403
            if user.must_change_password and not allow_password_change:
                return jsonify(error="Debes cambiar tu contraseña antes de continuar.", code="password_change_required"), 428
            if license_required() and not request.path.startswith("/api/license"):
                claims, code = license_for_user(user)
                if not claims:
                    return jsonify(error="Activa una licencia para esta cuenta de Control financiero.", code=code), 402
            if request.method in {"POST", "PUT", "PATCH", "DELETE"} and not getattr(g, "via_token", False) and not csrf_ok():
                return jsonify(error="Token de seguridad vencido. Actualiza la página e inicia sesión nuevamente."), 403
            g.user = user
            return fn(*args, **kwargs)
        return wrapped
    return decorate


def _rate_or_none(value):
    """Tasa en Bs. por dólar válida, o None."""
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) and 0 < number < 10_000_000 else None


def _clean_rows(raw, limit: int, spec: dict) -> list:
    """Limpia una colección: solo campos conocidos, con su tipo. spec = {campo: tipo}; tipos: id, ref, str:N, num, pos, cur, date, enum:a|b, bool."""
    if not isinstance(raw, list):
        return []
    out = []
    for row in raw[:limit]:
        if not isinstance(row, dict):
            continue
        clean, ok = {}, True
        for field, kind in spec.items():
            value = row.get(field)
            if kind in ("id", "ref"):
                text = str(value or "")
                if re.fullmatch(r"[A-Za-z0-9_-]{1,100}", text):
                    clean[field] = text
                elif kind == "id":
                    ok = False
                else:
                    clean[field] = ""
            elif kind.startswith("str:"):
                clean[field] = str(value or "")[: int(kind[4:])]
            elif kind in ("num", "pos"):
                try:
                    number = float(value or 0)
                except (TypeError, ValueError, OverflowError):
                    number = 0.0
                if not math.isfinite(number):
                    number = 0.0
                number = max(-1_000_000_000_000, min(number, 1_000_000_000_000))
                if kind == "pos":
                    number = max(0.0, number)
                clean[field] = number
            elif kind == "cur":
                clean[field] = "BS" if value in ("BS", "VES") else "USD"
            elif kind == "date":
                clean[field] = str(value or "")[:40]
            elif kind.startswith("enum:"):
                options = kind[5:].split("|")
                clean[field] = value if value in options else options[0]
            elif kind == "bool":
                clean[field] = value is True
        if ok:
            out.append(clean)
    return out


def normalize_business(value: dict, result: dict) -> None:
    """Quincena VE Negocio: ventas, fiado, gastos del negocio, productos y cierres de caja."""
    raw = value.get("business") if isinstance(value.get("business"), dict) else {}
    result["business"] = {"enabled": raw.get("enabled") is True, "name": str(raw.get("name") or "")[:80], "phone": str(raw.get("phone") or "")[:30],
                          # Tasa con la que trabaja el negocio: dólar BCV, euro BCV o USDT (paralelo).
                          "rateKind": raw.get("rateKind") if raw.get("rateKind") in BIZ_RATE_KINDS else "bcv_usd",
                          "type": raw.get("type") if raw.get("type") in BIZ_TYPES else "comercio",
                          "profession": raw.get("profession") if raw.get("profession") in BIZ_PROFESSIONS else ""}
    sales = _clean_rows(value.get("sales"), 20000, {
        "id": "id", "date": "date", "amount": "pos", "currency": "cur", "method": f"enum:{SALE_METHODS}", "accountId": "ref",
        "client": "str:80", "phone": "str:30", "credit": "bool", "dueDate": "date", "note": "str:200", "rateUsed": "pos", "seller": "str:40",
        "concept": "str:120"})
    raw_sales = {str(r.get("id")): r for r in value.get("sales", []) if isinstance(r, dict)} if isinstance(value.get("sales"), list) else {}
    for sale in sales:
        raw_items = raw_sales.get(sale["id"], {}).get("items")
        items = [row for row in _clean_rows(raw_items[:50] if isinstance(raw_items, list) else [], 50,
                                            {"productId": "ref", "name": "str:80", "qty": "pos", "price": "pos"}) if row["qty"] > 0]
        if items:
            sale["items"] = items
        if not sale["rateUsed"]:
            sale.pop("rateUsed")
    result["sales"] = sales
    result["bizPayments"] = _clean_rows(value.get("bizPayments"), 20000, {
        "id": "id", "saleId": "ref", "date": "date", "amount": "pos", "currency": "cur", "method": f"enum:{SALE_METHODS}", "accountId": "ref", "rateUsed": "pos"})
    result["bizExpenses"] = _clean_rows(value.get("bizExpenses"), 20000, {
        "id": "id", "date": "date", "amount": "pos", "currency": "cur", "category": f"enum:{BIZ_CATEGORIES}", "description": "str:200",
        "supplier": "str:80", "method": f"enum:{SALE_METHODS}", "accountId": "ref", "rateUsed": "pos"})
    result["products"] = _clean_rows(value.get("products"), 2000, {
        "id": "id", "name": "str:80", "code": "str:40", "price": "pos", "cost": "pos", "stock": "num", "stockDate": "date",
        "track": "bool", "archived": "bool"})
    result["cashClosings"] = _clean_rows(value.get("cashClosings"), 3000, {
        "id": "id", "date": "date", "countedBs": "pos", "countedUsd": "pos", "expectedBs": "num", "expectedUsd": "num", "note": "str:200"})


def normalize_shared_data(value) -> dict:
    if not isinstance(value, dict):
        raise ValueError("Los datos deben tener formato de objeto JSON.")
    result = copy.deepcopy(DEFAULT_SHARED_DATA)
    result["country"] = clean_country(value.get("country"))
    default_rate = COUNTRIES[result["country"]]["rate"]
    # Moneda en que la persona ve los montos: $ (BCV), USDT, € (BCV) o la moneda local.
    result["currency"] = "BS" if value.get("currency") in ("BS", "VES") else value.get("currency") if value.get("currency") in ("USDT", "EUR") else "USD"
    try:
        rate = float(value.get("exchangeRate", default_rate))
    except (TypeError, ValueError):
        rate = default_rate
    result["exchangeRate"] = rate if math.isfinite(rate) and 0 < rate < 1_000_000 else default_rate
    result["marketRate"] = _rate_or_none(value.get("marketRate")) or 0.0
    result["euroRate"] = _rate_or_none(value.get("euroRate")) or 0.0
    try:
        savings = float(value.get("bsSavings") or 0)
    except (TypeError, ValueError, OverflowError):
        savings = 0.0
    result["bsSavings"] = max(0.0, min(savings, 1_000_000_000_000)) if math.isfinite(savings) else 0.0
    history, seen_days = [], set()
    raw_history = value.get("rateHistory") if isinstance(value.get("rateHistory"), list) else []
    for item in raw_history[-120:]:
        if not isinstance(item, dict):
            continue
        day, bcv = str(item.get("d") or ""), _rate_or_none(item.get("b"))
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day) or bcv is None or day in seen_days:
            continue
        seen_days.add(day)
        history.append({"d": day, "b": bcv, "m": _rate_or_none(item.get("m")) or 0.0})
    result["rateHistory"] = sorted(history, key=lambda x: x["d"])
    result["accounts"] = _clean_rows(value.get("accounts"), 50, {
        "id": "id", "name": "str:60", "kind": "enum:banco|efectivo|digital|cripto", "currency": "cur", "initial": "num", "archived": "bool"})
    result["transfers"] = _clean_rows(value.get("transfers"), 5000, {
        "id": "id", "date": "date", "fromId": "ref", "toId": "ref", "amountFrom": "pos", "amountTo": "pos", "note": "str:120"})
    result["incomes"] = _clean_rows(value.get("incomes"), 10000, {
        "id": "id", "date": "date", "amount": "pos", "currency": "cur", "name": "str:80", "sourceId": "ref", "accountId": "ref",
        "kind": "enum:extra|sueldo|cobro|negocio"})
    result["receivables"] = _clean_rows(value.get("receivables"), 2000, {
        "id": "id", "person": "str:80", "amount": "pos", "paid": "pos", "currency": "cur", "date": "date", "dueDate": "date", "note": "str:200", "accountId": "ref"})
    for r in result["receivables"]:
        r["paid"] = min(r["paid"], r["amount"])
    result["termsVersion"] = str(value.get("termsVersion") or "")[:20]
    result["onboarded"] = value.get("onboarded") is True  # ya vio el tutorial de bienvenida
    normalize_business(value, result)
    income = value.get("income") if isinstance(value.get("income"), dict) else {}
    try:
        income_amount = float(income.get("amount", 0))
    except (TypeError, ValueError):
        income_amount = 0
    if not math.isfinite(income_amount):
        income_amount = 0
    result["income"] = {"amount": max(0, min(income_amount, 1_000_000_000_000)), "currency": "BS" if income.get("currency") in ("BS", "VES") else "USD"}

    # Parámetros del planificador de deuda: importes mensuales expresados en USD.
    raw_plan = value.get("debtPlan") if isinstance(value.get("debtPlan"), dict) else {}
    def safe_plan_amount(name: str) -> float:
        try:
            amount = float(raw_plan.get(name, 0) or 0)
        except (TypeError, ValueError, OverflowError):
            amount = 0.0
        if not math.isfinite(amount):
            amount = 0.0
        return max(0.0, min(amount, 1_000_000_000_000))
    try:
        payment_percent = int(float(raw_plan.get("paymentPercent", 60)))
    except (TypeError, ValueError, OverflowError):
        payment_percent = 60
    result["debtPlan"] = {
        "monthlyEssentials": safe_plan_amount("monthlyEssentials"),
        "monthlyCushion": safe_plan_amount("monthlyCushion"),
        "paymentPercent": max(30, min(payment_percent, 85)),
        "strategy": raw_plan.get("strategy") if raw_plan.get("strategy") in {"avalanche", "snowball"} else "avalanche",
        "configured": raw_plan.get("configured", False) is True,
    }

    budgets = value.get("budgets") if isinstance(value.get("budgets"), dict) else {}
    for key in result["budgets"]:
        try:
            result["budgets"][key] = max(0, min(float(budgets.get(key, 0)), 1_000_000_000_000))
        except (TypeError, ValueError):
            result["budgets"][key] = 0
        if not math.isfinite(result["budgets"][key]):
            result["budgets"][key] = 0
    for key in ("expenses", "debts"):
        raw_rows = value.get(key, [])
        if not isinstance(raw_rows, list) or len(raw_rows) > 10000:
            raise ValueError(f"La colección {key} no es válida o supera el límite permitido.")
        clean_rows = []
        for row in raw_rows:
            if not isinstance(row, dict) or not row.get("id"):
                continue
            # Keep the existing app's record fields, but discard nested values / very long payloads.
            clean = {}
            for field, field_value in row.items():
                if field not in {"id", "amount", "paid", "currency", "category", "description", "method", "bank", "date", "type", "entity", "issuer", "creditLimit", "minPayment", "cutDate", "payBefore", "installments", "invoice", "interestRate", "interestPeriod", "interestKnown", "rateType", "rateUsed", "bcvRate", "marketRate", "accountId", "groupId"}:
                    continue
                if isinstance(field_value, (str, int, float, bool)) or field_value is None:
                    if isinstance(field_value, str):
                        clean[field] = field_value[:500]
                    else:
                        clean[field] = field_value
            try:
                clean["amount"] = max(0, min(float(row.get("amount", 0)), 1_000_000_000_000))
                clean["paid"] = max(0, min(float(row.get("paid", 0) or 0), 1_000_000_000_000))
            except (TypeError, ValueError, OverflowError):
                continue
            if not math.isfinite(clean["amount"]) or not math.isfinite(clean["paid"]):
                continue
            item_id = str(row["id"])
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id):
                continue
            clean["id"] = item_id
            clean["paid"] = min(clean["paid"], clean["amount"])
            clean["currency"] = "BS" if row.get("currency") in ("BS", "VES") else "USD"
            clean["date"] = str(row.get("date") or "")[:40]
            if key == "expenses":
                clean["category"] = row.get("category") if row.get("category") in {"comida", "transporte", "fijo", "hormiga", "salud", "hogar", "ocio", "educacion", "deudas"} else "fijo"
                clean["description"] = str(row.get("description") or "Gasto")[:250]
                clean["bank"] = str(row.get("bank") or "")[:120]
                clean["method"] = row.get("method") if row.get("method") in EXPENSE_METHODS else "banco"
                # Pago mixto: las partes de un mismo gasto pagado con varios métodos comparten groupId.
                group_id = str(row.get("groupId") or "")
                clean.pop("groupId", None)
                if re.fullmatch(r"[A-Za-z0-9_-]{1,100}", group_id):
                    clean["groupId"] = group_id
                # "Las tres tasas": a qué tasa se consiguieron los bolívares de este gasto.
                for rate_field in ("rateUsed", "bcvRate", "marketRate"):
                    clean.pop(rate_field, None)
                    rate_value = _rate_or_none(row.get(rate_field))
                    if rate_value is not None and clean["currency"] == "BS":
                        clean[rate_field] = rate_value
                account_id = str(row.get("accountId") or "")
                clean.pop("accountId", None)
                if re.fullmatch(r"[A-Za-z0-9_-]{1,100}", account_id):
                    clean["accountId"] = account_id
                clean.pop("rateType", None)
                if clean["currency"] == "BS" and row.get("rateType") in {"bcv", "usdt", "otra"} and "rateUsed" in clean:
                    clean["rateType"] = row.get("rateType")
            else:
                clean["type"] = row.get("type") if row.get("type") in {"tdc", "cashea", "prestamo", "otro"} else "otro"
                clean["entity"] = str(row.get("entity") or "Entidad")[:120]
                clean["description"] = str(row.get("description") or "Deuda")[:250]
                clean["invoice"] = str(row.get("invoice") or "")[:100]
                try:
                    clean["minPayment"] = max(0.0, min(float(row.get("minPayment") or 0), 1_000_000_000_000))
                except (TypeError, ValueError, OverflowError):
                    clean["minPayment"] = 0.0
                if not math.isfinite(clean["minPayment"]):
                    clean["minPayment"] = 0.0
                try:
                    clean["installments"] = max(0, min(int(float(row.get("installments") or 0)), 100000))
                except (TypeError, ValueError, OverflowError):
                    clean["installments"] = 0
                try:
                    clean["interestRate"] = max(0.0, min(float(row.get("interestRate") or 0), 10000))
                except (TypeError, ValueError, OverflowError):
                    clean["interestRate"] = 0.0
                if not math.isfinite(clean["interestRate"]):
                    clean["interestRate"] = 0.0
                clean["interestPeriod"] = row.get("interestPeriod") if row.get("interestPeriod") in {"mensual", "semanal"} else "mensual"
                clean["interestKnown"] = bool(row.get("interestKnown", float(clean["interestRate"] or 0) > 0))
                clean["cutDate"] = str(row.get("cutDate") or "")[:20]
                clean["payBefore"] = str(row.get("payBefore") or "")[:40]
                clean["issuer"] = str(row.get("issuer") or "")[:80]
                try:
                    clean["creditLimit"] = max(0.0, min(float(row.get("creditLimit") or 0), 1_000_000_000_000))
                except (TypeError, ValueError, OverflowError):
                    clean["creditLimit"] = 0.0
                if not math.isfinite(clean["creditLimit"]):
                    clean["creditLimit"] = 0.0
            clean_rows.append(clean)
        result[key] = clean_rows

    # Fuentes de ingresos: el monto es el importe por cada pago, no el total mensual.
    raw_sources = value.get("incomeSources", [])
    if not isinstance(raw_sources, list) or len(raw_sources) > 500:
        raise ValueError("La colección incomeSources no es válida.")
    clean_sources = []
    for row in raw_sources:
        if not isinstance(row, dict) or not row.get("id"):
            continue
        item_id = str(row.get("id"))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id):
            continue
        try:
            amount = float(row.get("amount", 0))
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(amount) or amount <= 0 or amount > 1_000_000_000_000:
            continue
        kind = row.get("kind") if row.get("kind") in {"sueldo", "bono", "cestatickets", "otro"} else "otro"
        frequency = row.get("frequency") if row.get("frequency") in {"semanal", "quincenal", "mensual", "ocasional"} else "quincenal"
        raw_days = row.get("payDays", [])
        if not isinstance(raw_days, list):
            raw_days = []
        days = []
        for day in raw_days[:2]:
            try:
                n = int(day)
            except (TypeError, ValueError, OverflowError):
                continue
            if 1 <= n <= 31 and n not in days:
                days.append(n)
        days.sort()
        if frequency == "semanal":  # payDays = [día de la semana que cobra]: 1 = lunes … 7 = domingo
            days = [days[0]] if days and 1 <= days[0] <= 7 else [5]
        elif frequency == "quincenal" and len(days) != 2:
            days = [15, 30]
        elif frequency == "mensual" and len(days) < 1:
            days = [30]
        elif frequency == "mensual":
            days = days[:1]
        else:  # ingreso ocasional: no se presume un importe mensual recurrente
            days = []
        clean_sources.append({
            "id": item_id, "kind": kind,
            "name": str(row.get("name") or "")[:120],
            "amount": amount,
            "currency": "BS" if row.get("currency") in ("BS", "VES") else "USD",
            "frequency": frequency, "payDays": days,
        })
    result["incomeSources"] = clean_sources

    # Gastos/compromisos escolares. Los pagos quedan en una colección separada
    # para conservar el historial de abonos sin modificar el compromiso original.
    raw_school = value.get("schoolExpenses", [])
    if not isinstance(raw_school, list) or len(raw_school) > 10000:
        raise ValueError("La colección schoolExpenses no es válida.")
    school_rows = []
    school_concepts = {"mensualidad", "matricula", "utiles", "uniforme", "transporte", "comedor", "actividad", "otro"}
    for row in raw_school:
        if not isinstance(row, dict) or not row.get("id"):
            continue
        item_id = str(row.get("id"))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id):
            continue
        try:
            amount = float(row.get("amount", 0))
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(amount) or amount <= 0 or amount > 1_000_000_000_000:
            continue
        concept = row.get("concept") if row.get("concept") in school_concepts else "otro"
        school_rows.append({
            "id": item_id,
            "student": str(row.get("student") or "")[:120],
            "concept": concept,
            "description": str(row.get("description") or "Gasto escolar")[:250],
            "amount": amount,
            "currency": "BS" if row.get("currency") in ("BS", "VES") else "USD",
            "date": str(row.get("date") or "")[:40],
            "dueDate": str(row.get("dueDate") or "")[:40],
            "notes": str(row.get("notes") or "")[:500],
        })
    result["schoolExpenses"] = school_rows

    raw_payments = value.get("schoolPayments", [])
    if not isinstance(raw_payments, list) or len(raw_payments) > 20000:
        raise ValueError("La colección schoolPayments no es válida.")
    payment_rows = []
    valid_methods = {"banco", "efectivo", "pago_movil", "tarjeta"}
    for row in raw_payments:
        if not isinstance(row, dict) or not row.get("id"):
            continue
        item_id = str(row.get("id"))
        expense_id = str(row.get("expenseId") or "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", expense_id):
            continue
        try:
            amount = float(row.get("amount", 0))
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(amount) or amount <= 0 or amount > 1_000_000_000_000:
            continue
        payment_rows.append({
            "id": item_id, "expenseId": expense_id, "amount": amount,
            "currency": "BS" if row.get("currency") in ("BS", "VES") else "USD",
            "date": str(row.get("date") or "")[:40],
            "method": row.get("method") if row.get("method") in valid_methods else "banco",
            "reference": str(row.get("reference") or "")[:120],
            "notes": str(row.get("notes") or "")[:250],
        })
    result["schoolPayments"] = payment_rows

    raw_planned = value.get("plannedDebtPayments", [])
    if not isinstance(raw_planned, list) or len(raw_planned) > 10000:
        raise ValueError("La colección plannedDebtPayments no es válida.")
    planned_rows = []
    valid_schedule_types = {"quincena1", "quincena2", "date"}
    valid_statuses = {"planned", "paid", "cancelled"}
    for row in raw_planned:
        if not isinstance(row, dict) or not row.get("id") or not row.get("debtId"):
            continue
        item_id, debt_id = str(row.get("id")), str(row.get("debtId"))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", debt_id):
            continue
        try:
            amount = float(row.get("amount", 0))
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(amount) or amount <= 0 or amount > 1_000_000_000_000:
            continue
        schedule_type = row.get("scheduleType") if row.get("scheduleType") in valid_schedule_types else "date"
        planned_rows.append({
            "id": item_id, "debtId": debt_id,
            "entity": str(row.get("entity") or "")[:120],
            "description": str(row.get("description") or "Pago de deuda")[:250],
            "amount": amount,
            "currency": "BS" if row.get("currency") in ("BS", "VES") else "USD",
            "scheduleType": schedule_type,
            "scheduledDate": str(row.get("scheduledDate") or "")[:40],
            "status": row.get("status") if row.get("status") in valid_statuses else "planned",
            "createdAt": str(row.get("createdAt") or "")[:40],
            "paidAt": str(row.get("paidAt") or "")[:40],
            "paymentChoice": row.get("paymentChoice") if row.get("paymentChoice") in {"minimum", "total", "custom"} else "custom",
            "auto": row.get("auto") is True,  # lo programó el plan de pagos ("Programar los pagos de este mes")
        })
    result["plannedDebtPayments"] = planned_rows
    raw_rec = value.get("recurring", [])
    if not isinstance(raw_rec, list) or len(raw_rec) > 500:
        raise ValueError("La colección recurring no es válida.")
    rec_rows = []
    for row in raw_rec:
        if not isinstance(row, dict) or not row.get("id"):
            continue
        item_id = str(row.get("id"))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", item_id):
            continue
        try:
            amount, day = float(row.get("amount", 0)), int(row.get("day", 1))
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(amount) or amount <= 0 or amount > 1_000_000_000_000:
            continue
        last = str(row.get("lastGenerated") or "")
        rec_rows.append({
            "id": item_id, "description": str(row.get("description") or "Gasto fijo")[:120], "amount": amount,
            "currency": "BS" if row.get("currency") in ("BS", "VES") else "USD",
            "category": row.get("category") if row.get("category") in {"comida", "transporte", "fijo", "hormiga", "salud", "hogar", "ocio", "educacion", "deudas"} else "fijo",
            "day": min(31, max(1, day)), "active": bool(row.get("active", True)),
            # auto: se anota solo el día indicado. manual: aparece en Compromisos y se marca al pagarlo.
            "mode": "manual" if row.get("mode") == "manual" else "auto",
            "lastGenerated": last if re.fullmatch(r"\d{4}-\d{2}", last) else "",
        })
    result["recurring"] = rec_rows
    return result


def resolve_planned_due_date(item: dict, data: dict, today: date | None = None) -> date | None:
    """Resolve an explicit scheduled date or align a quincena with configured income days."""
    try:
        today = today or datetime.now(ZoneInfo(os.getenv("APP_TIMEZONE", "America/Caracas"))).date()
    except Exception:
        today = today or datetime.now(timezone.utc).date()
    raw_date = str(item.get("scheduledDate") or "")[:10]
    if raw_date:
        try:
            return date.fromisoformat(raw_date)
        except ValueError:
            return None
    schedule_type = item.get("scheduleType")
    if schedule_type not in {"quincena1", "quincena2"}:
        return None
    pay_days = []
    for source in (data.get("incomeSources", []) if isinstance(data, dict) else []):
        if isinstance(source, dict) and source.get("frequency") == "quincenal":
            days = source.get("payDays") or []
            if isinstance(days, list) and len(days) >= 2:
                try:
                    pay_days.append(sorted(max(1, min(31, int(day))) for day in days[:2]))
                except (TypeError, ValueError):
                    pass
    if pay_days:
        pay_day = min(pair[0] for pair in pay_days) if schedule_type == "quincena1" else max(pair[1] for pair in pay_days)
    else:
        pay_day = 15 if schedule_type == "quincena1" else 30
    import calendar
    year, month = today.year, today.month
    candidate = date(year, month, min(pay_day, calendar.monthrange(year, month)[1]))
    if candidate < today:
        month += 1
        if month > 12:
            month, year = 1, year + 1
        candidate = date(year, month, min(pay_day, calendar.monthrange(year, month)[1]))
    return candidate


def webauthn_settings() -> tuple[str, str]:
    origin = (os.getenv("APP_PUBLIC_ORIGIN") or request.host_url.rstrip("/")).rstrip("/")
    parsed = urlsplit(origin)
    rp_id = os.getenv("WEBAUTHN_RP_ID") or parsed.hostname
    if not parsed.scheme or not parsed.hostname or not rp_id:
        raise ValueError("Configura APP_PUBLIC_ORIGIN y WEBAUTHN_RP_ID para usar acceso biométrico.")
    return rp_id, origin


def establish_user_session(user: User) -> dict:
    session.clear()
    session["uid"] = user.id
    session["ps"] = pw_stamp(user)
    session["csrf_token"] = secrets.token_urlsafe(32)
    session.permanent = True
    audit("passkey_login_succeeded", "Inicio de sesión mediante passkey", user.id)
    return {"ok": True, "csrf_token": session["csrf_token"], "user": user_payload(user)}


def validate_school_balances(data: dict) -> str | None:
    expenses = {str(item["id"]): item for item in data.get("schoolExpenses", [])}
    paid_by_expense = {}
    for payment in data.get("schoolPayments", []):
        expense = expenses.get(str(payment.get("expenseId")))
        if expense is None:
            return "Hay un pago escolar asociado a un gasto que no existe. Actualiza los datos e inténtalo de nuevo."
        if payment.get("currency") != expense.get("currency"):
            return "La moneda del pago debe coincidir con la moneda del gasto escolar."
        key = str(payment["expenseId"])
        paid_by_expense[key] = paid_by_expense.get(key, 0.0) + float(payment.get("amount", 0))
    for expense_id, total_paid in paid_by_expense.items():
        if total_paid > float(expenses[expense_id]["amount"]) + 0.01:
            return "El total de pagos supera el monto registrado para uno de los gastos escolares. Revisa el saldo antes de guardar."
    return None


def validate_account_balances(data: dict, previous: dict | None = None) -> str | None:
    """Reject new transactions that make a cash account's recorded balance negative."""
    rate = float(data.get("exchangeRate") or 1)
    def amount_in(value: float, source: str, target: str) -> float:
        if source == target:
            return value
        return value * rate if source == "USD" and target == "BS" else value / rate if source == "BS" and target == "USD" else value
    def balances(state: dict) -> dict[str, float]:
        result = {}
        for account in state.get("accounts", []):
            aid, currency = str(account.get("id") or ""), account.get("currency", "USD")
            if not aid:
                continue
            total = float(account.get("initial") or 0)
            for row in state.get("incomes", []):
                if str(row.get("accountId") or "") == aid:
                    total += amount_in(float(row.get("amount") or 0), row.get("currency", "USD"), currency)
            for collection in ("expenses", "receivables"):
                for row in state.get(collection, []):
                    if str(row.get("accountId") or "") == aid:
                        total -= amount_in(float(row.get("amount") or 0), row.get("currency", "USD"), currency)
            for row in state.get("transfers", []):
                if str(row.get("fromId") or "") == aid:
                    total -= float(row.get("amountFrom") or 0)
                if str(row.get("toId") or "") == aid:
                    total += float(row.get("amountTo") or 0)
            for row in state.get("sales", []):
                if str(row.get("accountId") or "") == aid and not row.get("credit"):
                    total += amount_in(float(row.get("amount") or 0), row.get("currency", "USD"), currency)
            for row in state.get("bizPayments", []):
                if str(row.get("accountId") or "") == aid:
                    total += amount_in(float(row.get("amount") or 0), row.get("currency", "USD"), currency)
            for row in state.get("bizExpenses", []):
                if str(row.get("accountId") or "") == aid:
                    total -= amount_in(float(row.get("amount") or 0), row.get("currency", "USD"), currency)
            result[aid] = total
        return result
    old = balances(previous or {})
    new = balances(data)
    labels = {str(a.get("id")): str(a.get("name") or "Cuenta") for a in data.get("accounts", [])}
    for aid, balance in new.items():
        if balance < -0.01 and balance < old.get(aid, 0.0) - 0.01:
            return f"La cuenta «{labels.get(aid, 'Cuenta')}» no tiene saldo suficiente para ese movimiento. Revisa el saldo y vuelve a intentar."
    return None


def same(a, b) -> bool:
    return a == b


def merge_array(base_list, incoming_list, current_list):
    base = {str(x.get("id")): x for x in base_list if isinstance(x, dict) and x.get("id") is not None}
    incoming = {str(x.get("id")): x for x in incoming_list if isinstance(x, dict) and x.get("id") is not None}
    current = {str(x.get("id")): x for x in current_list if isinstance(x, dict) and x.get("id") is not None}
    result = dict(current)
    conflicts = []
    all_ids = set(base) | set(incoming) | set(current)
    for item_id in all_ids:
        before, local, remote = base.get(item_id), incoming.get(item_id), current.get(item_id)
        local_changed = local != before
        remote_changed = remote != before
        if not local_changed:
            continue
        if remote_changed and local != remote:
            conflicts.append(item_id)
            continue
        if item_id in incoming:
            result[item_id] = local
        else:
            result.pop(item_id, None)
    # Keep newest-first ordering for the application's existing history views.
    merged_rows = list(result.values())
    merged_rows.sort(key=lambda row: str(row.get("date", "")), reverse=True)
    return merged_rows, conflicts


def three_way_merge(base_data, incoming_data, current_data):
    base = normalize_shared_data(base_data)
    incoming = normalize_shared_data(incoming_data)
    current = normalize_shared_data(current_data)
    merged = copy.deepcopy(current)
    conflicts = []
    for key in ("currency", "income", "budgets", "debtPlan", "bsSavings", "business"):
        if incoming[key] != base[key]:
            if current[key] != base[key] and current[key] != incoming[key]:
                conflicts.append(key)
            else:
                merged[key] = incoming[key]
    # Tasas e historial: gana el último que escribe (no es un conflicto real).
    for key in ("country", "exchangeRate", "marketRate", "euroRate", "rateHistory", "termsVersion"):
        if incoming[key] != base[key]:
            merged[key] = incoming[key]
    for key in ("expenses", "debts", "incomeSources", "schoolExpenses", "schoolPayments", "plannedDebtPayments", "recurring",
                "accounts", "transfers", "incomes", "receivables", "sales", "bizPayments", "bizExpenses", "products", "cashClosings"):
        merged[key], row_conflicts = merge_array(base[key], incoming[key], current[key])
        if row_conflicts:
            conflicts.extend([f"{key}:{x}" for x in row_conflicts])
    return merged, conflicts


@app.after_request
def add_security_headers(response):
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Robots-Tag", "noindex, nofollow, noarchive")
    if request.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Vary"] = "Cookie"
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    response.headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    response.headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
    response.headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")
    # The existing page uses inline templates/events and trusted CDN assets for its visual styling.
    response.headers.setdefault("Content-Security-Policy", "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data:; connect-src 'self' https://ve.dolarapi.com; script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com https://fonts.googleapis.com; font-src 'self' data: https://cdnjs.cloudflare.com https://fonts.gstatic.com")
    if os.getenv("RENDER") or request.is_secure:
        response.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    return response


@limiter.exempt
@app.get("/")
def index():
    """Sin sesión: la página de presentación (la que ven Google y los visitantes). Con sesión: la app, como siempre,
    para que las PWA instaladas y los enlaces viejos sigan abriendo directo."""
    page = "index.html" if current_user() is not None else "landing.html"
    response = send_from_directory(app.static_folder, page)
    response.headers["Cache-Control"] = "no-cache"
    response.headers["Vary"] = "Cookie"
    if page == "landing.html":  # sin esto la cabecera general «noindex» le pedía a Google NO indexar la portada
        response.headers["X-Robots-Tag"] = "index, follow, max-image-preview:large"
    return response


@limiter.exempt
@app.get("/app")
def web_app():
    response = send_from_directory(app.static_folder, "index.html")
    response.headers["Cache-Control"] = "no-cache"
    return response


MAINTENANCE_MODE = os.getenv("MAINTENANCE_MODE", "0") == "1"   # 1 = toda la web responde 503 «en mantenimiento»

# Rutas que solo pide un escáner de vulnerabilidades (Quincena no usa PHP, WordPress ni archivos ocultos): se cortan con un
# 404 inmediato en la capa WSGI, sin abrir sesión ni base de datos.
SCANNER_PATHS = re.compile(
    r"(^|/)\.(env|git|svn|hg|aws|ssh|htaccess|htpasswd|ds_store|idea|vscode|docker)"
    r"|wp-(admin|login|content|includes|json|config)|xmlrpc|phpmyadmin|pma/|myadmin|adminer|cgi-bin|/vendor/|/actuator"
    r"|server-status|/telescope|/solr|/boaform|/hnap1|/owa/|/autodiscover|\.(php\d?|asp|aspx|jsp|cgi|pl|sql|bak|old|swp|tar|gz|zip|7z|rar|ini|yml|yaml|log)$",
    re.IGNORECASE)


class ScannerFilter:
    def __init__(self, wsgi_app):
        self.wsgi_app = wsgi_app

    def __call__(self, environ, start_response):
        path = environ.get("PATH_INFO", "") or ""
        if (SCANNER_PATHS.search(path) and not path.startswith("/.well-known/")) or "\x00" in path or len(path) > 2048:
            start_response("404 Not Found", [("Content-Type", "text/plain; charset=utf-8"), ("Cache-Control", "no-store"),
                                             ("X-Content-Type-Options", "nosniff"), ("X-Robots-Tag", "noindex, nofollow")])
            return [b"No encontrado"]
        return self.wsgi_app(environ, start_response)


app.wsgi_app = ScannerFilter(app.wsgi_app)


@app.before_request
def maintenance_gate():
    if MAINTENANCE_MODE and request.path not in ("/healthz", "/robots.txt", "/favicon.ico") and not request.path.startswith("/static/"):
        return error_response(503)
    return None


@limiter.exempt
@app.get("/.well-known/security.txt")
def security_txt():
    """RFC 9116: a dónde se reporta una vulnerabilidad."""
    origin = public_origin() or request.url_root.rstrip("/")
    contact = os.getenv("SECURITY_CONTACT") or ("mailto:" + os.getenv("CONTACT_EMAIL", "aalejandro996@icloud.com"))
    expires = (date.today() + timedelta(days=180)).isoformat() + "T00:00:00.000Z"
    body = (f"Contact: {contact}\nExpires: {expires}\nPreferred-Languages: es, en\n"
            f"Canonical: {origin}/.well-known/security.txt\nPolicy: {origin}/terminos\n")
    return app.response_class(body, mimetype="text/plain")


@limiter.exempt
@app.get("/robots.txt")
def robots():
    origin = public_origin() or request.url_root.rstrip("/")
    if MAINTENANCE_MODE:
        return app.response_class("User-agent: *\nDisallow: /\n", mimetype="text/plain")
    body = (f"User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /app\nDisallow: /admin\nDisallow: /descargar/\n\n"
            f"# Quincena VE es un producto de Gigadevs Corp\nSitemap: {origin}/sitemap.xml\n")
    response = app.response_class(body, mimetype="text/plain")
    response.headers["Cache-Control"] = "public, max-age=3600"
    return response


@limiter.exempt
@app.get("/google65c83ff0e56e9e6a.html")
def google_site_verification():
    # Archivo con el que Google Search Console comprueba que el sitio es nuestro; debe quedarse publicado.
    return send_from_directory(app.static_folder, "google65c83ff0e56e9e6a.html", mimetype="text/html")


# Huella SHA-256 del certificado con que GitHub Actions firma el APK (llave nueva de oct. 2026). Si se cambia la llave,
# poner la nueva en ANDROID_CERT_SHA256 (varias separadas por coma) o los enlaces de los correos dejarán de abrir la app.
ANDROID_CERT_SHA256 = "74:35:96:63:3F:11:A8:AA:7A:E1:8D:EF:E2:C7:E6:55:0B:4F:39:9F:AD:7D:EC:F7:F0:2A:85:30:09:C3:D8:0A"


@limiter.exempt
@app.get("/.well-known/assetlinks.json")
def android_asset_links():
    """Android App Links: le prueba a Android que este sitio y la app app.quincena son del mismo dueño, para que los
    enlaces de los correos (/app#verify=…, /app#reset=…) abran la app si está instalada y la web si no."""
    prints = [f.strip().upper() for f in (os.getenv("ANDROID_CERT_SHA256") or ANDROID_CERT_SHA256).split(",") if f.strip()]
    response = jsonify([{"relation": ["delegate_permission/common.handle_all_urls"],
                         "target": {"namespace": "android_app", "package_name": "app.quincena", "sha256_cert_fingerprints": prints}}])
    response.headers["Cache-Control"] = "public, max-age=3600"
    return response


@limiter.exempt
@app.get("/sitemap.xml")
def sitemap():
    origin = public_origin() or request.url_root.rstrip("/")
    pages = [("/", "1.0", "weekly"), ("/terminos", "0.3", "yearly"), ("/privacidad", "0.3", "yearly"), ("/cookies", "0.2", "yearly")]
    lastmod = os.getenv("SITEMAP_LASTMOD") or date.today().isoformat()
    urls = "".join(f"<url><loc>{origin}{path}</loc><lastmod>{lastmod}</lastmod><changefreq>{freq}</changefreq><priority>{prio}</priority></url>"
                   for path, prio, freq in pages)
    return app.response_class(f'<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>', mimetype="application/xml")


@limiter.exempt
@app.get("/favicon.ico")
def favicon():
    # Los navegadores lo piden siempre en la raíz, aunque la página declare otro icono.
    response = send_from_directory(Path(app.static_folder) / "icons", "favicon.ico", mimetype="image/x-icon")
    response.headers["Cache-Control"] = "public, max-age=604800"
    return response


@limiter.exempt
@app.get("/service-worker.js")
def service_worker():
    response = send_from_directory(app.static_folder, "service-worker.js")
    response.headers["Service-Worker-Allowed"] = "/"
    response.headers["Cache-Control"] = "no-cache"
    return response


# ---- App Android: el archivo va en downloads/quincena.apk (fuera de /static para que el service worker no lo guarde) ----
APK_PATH = Path(__file__).resolve().parent / "downloads" / "quincena.apk"


def android_version() -> dict:
    """Versión del APK publicado: la escribe GitHub Actions en downloads/version.json (o se toma de variables)."""
    info = {"versionCode": int(os.getenv("ANDROID_APP_VERSION_CODE", "6") or 6), "version": os.getenv("ANDROID_APP_VERSION", "1.4.0"), "notes": ""}
    try:
        raw = json.loads((APK_PATH.parent / "version.json").read_text(encoding="utf-8"))
        info["versionCode"] = int(raw.get("versionCode", info["versionCode"]))
        info["version"] = str(raw.get("versionName") or info["version"])[:20]
        info["notes"] = str(raw.get("notes") or "")[:200]
    except (OSError, ValueError, TypeError):
        pass
    # Nunca mostrar mensajes técnicos de Git (Merge, Revert…) a los usuarios.
    if not info["notes"].strip() or re.match(r"(?i)^(merge|revert|apk |wip|fix\b|chore|update )", info["notes"].strip()):
        info["notes"] = "Mejoras de seguridad y correcciones."
    return info


def android_min_version_code() -> int:
    """Versión mínima de la app que puede usar el servidor. ANDROID_MIN_VERSION en Render:
    "latest" (por defecto) obliga a usar siempre la última publicada; un número fija esa versión; "0" u "off" lo apaga."""
    if not APK_PATH.is_file():
        return 0
    setting = os.getenv("ANDROID_MIN_VERSION", "latest").strip().lower()
    if setting in {"", "latest", "ultima", "última"}:
        return android_version()["versionCode"]
    if setting in {"0", "off", "no", "false"}:
        return 0
    try:
        return min(int(setting), android_version()["versionCode"])  # nunca exigir una versión que no existe
    except ValueError:
        return android_version()["versionCode"]


# Rutas que una app desactualizada sigue pudiendo usar: saber que hay versión nueva y descargarla.
UPDATE_EXEMPT_PATHS = {"/api/android-app", "/api/status", "/api/countries", "/api/rate"}


@app.before_request
def require_current_android_app():
    """La app Android manda X-App-Version; las versiones viejas (≤1.14) no lo mandan pero se identifican por okhttp.
    Si la versión es menor que la mínima, no se entregan datos y la app muestra el aviso de actualizar."""
    if not request.path.startswith("/api/") or request.path in UPDATE_EXEMPT_PATHS:
        return None
    raw = request.headers.get("X-App-Version")
    if raw is None and "okhttp" not in request.headers.get("User-Agent", "").lower():
        return None  # navegador: la web siempre está al día
    try:
        installed = int(raw) if raw is not None else 0
    except ValueError:
        installed = 0
    minimum = android_min_version_code()
    if installed >= minimum:
        return None
    info = android_version()
    return jsonify(error=f"Hay una versión nueva de Quincena VE ({info['version']}). Actualízala para seguir usándola: toca Actualizar en Inicio.",
                   code="update_required", minVersionCode=minimum, version=info["version"],
                   url=f"/descargar/android?v={info['versionCode']}"), 426


@app.get("/api/android-app")
def android_app_info():
    if not APK_PATH.is_file():
        return jsonify(available=False)
    info = android_version()
    return jsonify(available=True, version=info["version"], versionCode=info["versionCode"], notes=info["notes"],
                   minVersionCode=android_min_version_code(),
                   size_mb=round(APK_PATH.stat().st_size / 1048576, 1), url=f"/descargar/android?v={info['versionCode']}")


@app.get("/descargar/android")
def download_android():
    if not APK_PATH.is_file():
        abort(404)
    # El nombre lleva la versión (Quincena-1.7.apk) para no confundirlo con descargas viejas.
    version = re.sub(r"[^0-9A-Za-z.]", "", android_version()["version"]) or "nueva"
    return send_from_directory(APK_PATH.parent, APK_PATH.name, mimetype="application/vnd.android.package-archive",
                               as_attachment=True, download_name=f"Quincena-{version}.apk", max_age=0)


TERMS_VERSION = "2026-10-23"


@app.get("/terminos")
@app.get("/privacidad")
@app.get("/cookies")
def legal_pages():
    html = (Path(app.static_folder) / "legal.html").read_text(encoding="utf-8")
    html = html.replace("{{CONTACTO}}", html_escape(os.getenv("CONTACT_EMAIL", "el correo de contacto indicado en la app")))
    html = html.replace("{{VERSION}}", TERMS_VERSION).replace("{{SECCION}}", request.path.strip("/"))
    response = app.response_class(html, mimetype="text/html")
    response.headers["Cache-Control"] = "public, max-age=3600"
    response.headers["X-Robots-Tag"] = "index, follow"
    return response


@limiter.exempt
@app.get("/healthz")
def healthz():
    try:
        db.session.execute(db.text("SELECT 1"))
        return jsonify(ok=True)
    except Exception:
        app.logger.exception("Health check de base de datos falló")
        return jsonify(ok=False), 503


@app.get("/api/license/status")
@require_auth(allow_password_change=True)
def product_license_status():
    claims, code = license_for_user(g.user)
    return jsonify(active=bool(claims), required=license_required(), code=code,
                   product="control_financiero", expires_at=claims.get("exp") if claims else None)


@app.post("/api/license/activate")
@require_auth(allow_password_change=True)
@limiter.limit("10 per hour")
def activate_product_license():
    token = str((request.get_json(silent=True) or {}).get("token", "")).strip()
    if not token or len(token) > 32768:
        return jsonify(error="Pega el archivo de licencia completo.", code="invalid_license_format"), 400
    claims, code = verify_product_license(token, g.user)
    if not claims:
        return jsonify(error="La licencia no es válida para esta cuenta verificada.", code=code), 400
    expires = datetime.fromtimestamp(int(claims["exp"]), timezone.utc)
    row = ProductLicense.query.filter_by(user_id=g.user.id).first()
    if row is None:
        row = ProductLicense(user_id=g.user.id, token=token, expires_at=expires)
        db.session.add(row)
    else:
        row.token, row.expires_at = token, expires
    db.session.commit()
    audit("product_license_activated", f"Control financiero hasta {expires.date().isoformat()}", g.user.id)
    return jsonify(ok=True, active=True, expires_at=expires.isoformat())


@app.delete("/api/license")
@require_auth(allow_password_change=True)
def remove_product_license():
    ProductLicense.query.filter_by(user_id=g.user.id).delete()
    db.session.commit()
    audit("product_license_removed", "Licencia de Control financiero eliminada", g.user.id)
    return jsonify(ok=True)

EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[^@\s]{2,}$")


def registration_open() -> bool:
    return os.getenv("ALLOW_REGISTRATION", "false").lower() == "true" and not setup_required()


def mail_configured() -> bool:
    return bool(os.getenv("BREVO_API_KEY", "").strip() or (os.getenv("SMTP_USER") and os.getenv("SMTP_PASSWORD")))


def mail_problem() -> str | None:
    """Qué falta para que salgan los correos con enlace (verificación y recuperación), o None si está todo."""
    if not mail_configured():
        return "falta BREVO_API_KEY en Render"
    if os.getenv("BREVO_API_KEY", "").strip() and not parseaddr(os.getenv("MAIL_FROM", ""))[1]:
        return "falta MAIL_FROM (remitente verificado en Brevo, p. ej. Quincena VE <tu@correo.com>)"
    if not os.getenv("APP_PUBLIC_ORIGIN", "").strip() and os.getenv("RENDER"):
        return "falta APP_PUBLIC_ORIGIN (p. ej. https://control-financiero-wrm5.onrender.com)"
    return None


def brevo_send(api_key: str, to: str, subject: str, body: str, html_body: str | None = None, attachments: list | None = None) -> tuple[bool, str]:
    """Correo transaccional por la API HTTPS de Brevo (POST /v3/smtp/email). Devuelve (enviado, detalle)."""
    name, addr = parseaddr(os.getenv("MAIL_FROM", ""))
    if not addr:
        return False, "MAIL_FROM vacío o inválido: debe ser un remitente verificado en Brevo, p. ej. Quincena VE <tu@correo.com>."
    payload = {"sender": {"name": name or "Quincena VE", "email": addr}, "to": [{"email": to}], "subject": subject, "textContent": body}
    if html_body:
        payload["htmlContent"] = html_body
    if attachments:  # [(nombre, bytes)], p. ej. la factura en PDF
        payload["attachment"] = [{"name": name, "content": base64.b64encode(content).decode()} for name, content in attachments]
    req = urllib.request.Request("https://api.brevo.com/v3/smtp/email", data=json.dumps(payload).encode("utf-8"), method="POST",
                                 headers={"api-key": api_key, "Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            try:
                answer = json.loads(response.read() or b"{}")
            except Exception:
                answer = {}
            return 200 <= response.status < 300, f"HTTP {response.status}, messageId {answer.get('messageId', '?')}"
    except urllib.error.HTTPError as error:
        try:
            answer = json.loads(error.read() or b"{}")
        except Exception:
            answer = {}
        # Solo el código y el mensaje de Brevo (p. ej. «unauthorized: Key not found»), nunca la clave.
        detail = f"HTTP {error.code}: {str(answer.get('code', ''))[:60]}: {str(answer.get('message', ''))[:300]}"
        return False, detail.replace(api_key, "***") if api_key else detail
    except Exception as error:
        return False, f"No se pudo contactar a Brevo ({type(error).__name__})."


def send_mail(to: str, subject: str, body: str, html_body: str | None = None, attachments: list | None = None) -> bool:
    """Envía un correo. Prefiere la API HTTPS de Brevo (funciona en el plan gratis de Render, que bloquea SMTP);
    si no hay BREVO_API_KEY, usa SMTP de Gmail con contraseña de aplicación."""
    api_key = os.getenv("BREVO_API_KEY", "").strip()
    if api_key:
        sent, detail = brevo_send(api_key, to, subject, body, html_body, attachments)
        if not sent:
            app.logger.error("Brevo no envió el correo «%s»: %s", subject, detail)
        return sent
    smtp_user, smtp_password = os.getenv("SMTP_USER", ""), os.getenv("SMTP_PASSWORD", "")
    if not smtp_user or not smtp_password:
        return False
    message = EmailMessage()
    message["Subject"], message["To"] = subject, to
    message["From"] = os.getenv("MAIL_FROM") or f"Quincena VE <{smtp_user}>"
    message.set_content(body)
    if html_body:
        message.add_alternative(html_body, subtype="html")
    for name, content in attachments or []:
        message.add_attachment(content, maintype="application", subtype="pdf", filename=name)
    try:
        with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=10, context=ssl.create_default_context()) as smtp:
            smtp.login(smtp_user, smtp_password)
            smtp.send_message(message)
        return True
    except Exception:
        app.logger.warning("No se pudo enviar el correo por SMTP (Render gratis bloquea SMTP: usa BREVO_API_KEY).")
        return False


def send_mail_async(to: str, subject: str, body: str, html_body: str | None = None) -> None:
    """Envía en segundo plano: la respuesta tarda igual exista o no el correo (no se puede adivinar quién tiene cuenta)."""
    threading.Thread(target=lambda: send_mail(to, subject, body, html_body), daemon=True).start()


def public_origin() -> str:
    """Dirección pública para los enlaces de los correos. En producción solo la configurada: si se usara la cabecera Host
    que manda quien hace la petición, alguien podría mandar a la víctima un enlace a un sitio falso."""
    configured = os.getenv("APP_PUBLIC_ORIGIN", "").rstrip("/")
    if configured:
        return configured
    return "" if os.getenv("RENDER") else request.host_url.rstrip("/")


def notify_security(user: "User", subject: str, message: str) -> None:
    """Aviso de seguridad al correo confirmado de la persona (cambio de clave, nuevo dispositivo…)."""
    if not user.email or not user.email_verified_at or not mail_configured():
        return
    when = datetime.now(ZoneInfo(os.getenv("APP_TIMEZONE", "America/Caracas"))).strftime("%d/%m/%Y %H:%M")
    tail = ("Si usted no fue, restablezca su contraseña de inmediato desde la pantalla de acceso («¿Olvidaste tu contraseña?») "
            "y cierre las sesiones de sus otros dispositivos desde Más.")
    text = f"Estimado/a {user.username}:\n\n{message}\nFecha: {when} (hora de Caracas).\n\n{tail}\n\nAtentamente,\nEl equipo de Quincena VE"
    page = email_layout(subject, f'<p style="margin:0 0 16px">Estimado/a <b>{html_escape(user.username)}</b>:</p>'
                        f'<p style="margin:0 0 16px;padding:14px 16px;background:#BFF0D8;border-radius:12px;color:#0B3D2C">{html_escape(message)}<br>'
                        f'<span style="font-size:14px">Fecha: {when} (hora de Caracas).</span></p>'
                        f'<p style="margin:0 0 16px">{html_escape(tail)}</p><p style="margin:0">Atentamente,<br><b>El equipo de Quincena VE</b></p>', message)
    send_mail_async(user.email, subject, text, page)


def email_layout(title: str, inner_html: str, preheader: str = "") -> str:
    """Plantilla de todos los correos con la marca de Quincena VE: logo, franja con la bandera, verde de la marca y pie
    con la dirección del sitio. Tablas y estilos en línea para que se vea igual en Gmail, Outlook e iCloud."""
    origin = public_origin() if request else os.getenv("APP_PUBLIC_ORIGIN", "").rstrip("/")
    logo = (f'<img src="{html_escape(origin, quote=True)}/static/icons/icon-192.png" width="44" height="44" alt="" '
            'style="display:block;border:0;border-radius:12px">') if origin else ""
    site = (f'<a href="{html_escape(origin, quote=True)}" style="color:#0B6B4D;font-weight:bold;text-decoration:none">'
            f'{html_escape(origin.split("://")[-1])}</a> · ') if origin else ""
    font = "'Figtree',Arial,Helvetica,sans-serif"
    return (
        '<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        f'<title>{html_escape(title)}</title>'
        '<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@700&family=Figtree:wght@400;600;700&display=swap" rel="stylesheet">'
        f'</head><body style="margin:0;padding:0;background:#EEF5F0;font-family:{font};color:#12211B">'
        f'<div style="display:none;max-height:0;overflow:hidden;opacity:0">{html_escape(preheader)}</div>'
        '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#EEF5F0"><tr><td align="center" style="padding:28px 12px">'
        '<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #D5E3DA;border-radius:16px;overflow:hidden">'
        '<tr><td style="padding:0;font-size:0;line-height:0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>'
        '<td height="6" style="background:#FFCC00;width:33.33%"></td><td height="6" style="background:#00247D;width:33.34%"></td>'
        '<td height="6" style="background:#CF142B;width:33.33%"></td></tr></table></td></tr>'
        '<tr><td style="padding:22px 28px 18px;border-bottom:1px solid #E3ECE6"><table role="presentation" cellpadding="0" cellspacing="0"><tr>'
        f'<td style="padding-right:{12 if logo else 0}px">{logo}</td>'
        "<td style=\"font-family:'Bricolage Grotesque',Arial,Helvetica,sans-serif;font-size:22px;font-weight:bold;color:#0B6B4D\">Quincena VE 🇻🇪"
        f'<div style="font-family:{font};font-size:13px;font-weight:normal;color:#46564E">Tus gastos en dólares y bolívares</div></td>'
        '</tr></table></td></tr>'
        f'<tr><td style="padding:28px;font-size:16px;line-height:1.6">{inner_html}</td></tr>'
        '<tr><td style="padding:18px 28px;background:#F5FAF7;border-top:1px solid #E3ECE6;font-size:12px;line-height:1.6;color:#46564E">'
        'Por su seguridad, Quincena VE nunca le solicitará su contraseña por correo electrónico ni por teléfono. '
        'Este es un mensaje automático; por favor, no responda a este correo.<br>'
        f'{site}Hecho en Venezuela por Gigadevs</td></tr>'
        '</table></td></tr></table></body></html>'
    )


def email_button(link: str, label: str) -> str:
    return (f'<table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:4px auto 24px"><tr>'
            f'<td style="background:#0B6B4D;border-radius:12px"><a href="{html_escape(link, quote=True)}" '
            f'style="display:inline-block;padding:14px 30px;color:#ffffff;font-weight:bold;font-size:16px;text-decoration:none">{html_escape(label)}</a></td></tr></table>')


def reset_email(username: str, link: str, validity: str = "60 minutos", intro: str = "Hemos recibido una solicitud para restablecer la contraseña de su cuenta en Quincena VE.", cta: str = "Restablecer contraseña", ignore: str = "Si usted no realizó esta solicitud, puede ignorar este mensaje; su contraseña actual no será modificada.") -> tuple[str, str]:
    """Correo formal de restablecimiento de contraseña: (texto plano, HTML)."""
    text = (
        f"Estimado/a {username}:\n\n"
        f"{intro}\n\n"
        f"Para continuar, abra el siguiente enlace. Tendrá una validez de {validity} y solo podrá utilizarse una vez:\n\n{link}\n\n"
        f"{ignore}\n\n"
        "Por su seguridad, Quincena VE nunca le solicitará su contraseña por correo electrónico ni por teléfono.\n\n"
        "Atentamente,\nEl equipo de Quincena VE"
    )
    name, url = html_escape(username), html_escape(link, quote=True)
    inner = (
        f'<p style="margin:0 0 16px">Estimado/a <b>{name}</b>:</p>'
        f'<p style="margin:0 0 16px">{html_escape(intro)}</p>'
        f'<p style="margin:0 0 24px">Para continuar, presione el siguiente botón. El enlace tiene una validez de <b>{validity}</b> y solo podrá utilizarse una vez.</p>'
        + email_button(link, cta) +
        # Brevo cambia el enlace del botón por uno suyo de rastreo (sendibt3.com) y algunos teléfonos o redes lo bloquean
        # («No se puede acceder a este sitio»). La dirección escrita como texto no se cambia: el correo la vuelve tocable y abre directo.
        f'<p style="margin:0 0 16px;padding:14px 16px;background:#F5FAF7;border:1px solid #D5E3DA;border-radius:10px;font-size:14px;color:#46564E">Si al tocar el botón aparece «No se puede acceder a este sitio», toque o copie esta dirección:<br><span style="display:block;margin-top:8px;word-break:break-all;color:#0B6B4D;font-size:15px;font-weight:bold">{url}</span></p>'
        f'<p style="margin:0 0 16px">{html_escape(ignore)}</p>'
        '<p style="margin:0">Atentamente,<br><b>El equipo de Quincena VE</b></p>'
    )
    page = email_layout(cta, inner, intro)
    return text, page


def start_session(user: "User"):
    session.clear()
    session["uid"] = user.id
    session["ps"] = pw_stamp(user)
    session["csrf_token"] = secrets.token_urlsafe(32)
    session.permanent = True
    return jsonify(ok=True, csrf_token=session["csrf_token"], user=user_payload(user))


def send_verification(user: "User") -> bool:
    token = secrets.token_urlsafe(32)
    EmailVerification.query.filter_by(user_id=user.id, used_at=None).delete()
    db.session.add(EmailVerification(user_id=user.id, token_hash=hashlib.sha256(token.encode()).hexdigest(), expires_at=datetime.now(timezone.utc) + timedelta(hours=24)))
    db.session.commit()
    origin = public_origin()
    if not origin or not mail_configured():
        app.logger.error("Correo de verificación no enviado: %s.", mail_problem() or "sin dirección pública")
        return False
    # El código va tras "#": el navegador nunca lo envía al servidor, así no queda en registros ni en cabeceras Referer.
    text_body, html_body = reset_email(user.username, f"{origin}/app#verify={token}", "24 horas",
                                       "Gracias por crear su cuenta en Quincena VE. Para activar la recuperación de su contraseña, necesitamos confirmar su correo electrónico.",
                                       "Confirmar correo", "Si usted no creó esta cuenta, puede ignorar este mensaje.")
    send_mail_async(user.email, "Confirme su correo electrónico – Quincena VE", text_body, html_body)
    return True


MAX_SELLERS = 5


def _seller_json(u: "User") -> dict:
    return {"id": u.id, "username": u.username, "active": u.active, "created_at": u.created_at.isoformat() if u.created_at else None}


def _seller_owner():
    """Para una cuenta de vendedor: (dueño, estado del dueño) si el negocio puede recibir ventas, o una respuesta de error."""
    owner = db.session.get(User, g.user.owner_id) if g.user.owner_id else None
    if owner is None:
        return None, (jsonify(error="Esta cuenta no es de vendedor."), 403)
    if not owner.active or not is_plus(owner):
        return None, (jsonify(error="El negocio no tiene Negocio Plus activo. Avisa al dueño."), 402)
    return owner, None


_RATE_CACHE = {}  # por país: {"rate", "updated", "fetched", "parallel", "parallel_updated"}


def _http_json(url: str):
    request_obj = urllib.request.Request(url, headers={"User-Agent": "Quincena/1.0"})
    with urllib.request.urlopen(request_obj, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def _http_text(url: str, context: ssl.SSLContext | None = None) -> str:
    request_obj = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Quincena VE)"})
    with urllib.request.urlopen(request_obj, timeout=8, context=context) as response:
        return response.read().decode("utf-8", "replace")


_BCV_CONTEXT: ssl.SSLContext | None = None


def _load_certs(raw: bytes) -> list:
    """Certificados de un archivo de "CA Issuers": X.509 (DER o PEM) o un paquete PKCS#7 (.p7c), como el del BCV."""
    from cryptography import x509
    from cryptography.hazmat.primitives.serialization import pkcs7
    pem = raw.lstrip().startswith(b"-----")
    for load in ((x509.load_pem_x509_certificate,) if pem else (x509.load_der_x509_certificate,)):
        try:
            return [load(raw)]
        except ValueError:
            pass
    certs = pkcs7.load_pem_pkcs7_certificates(raw) if pem else pkcs7.load_der_pkcs7_certificates(raw)
    if not certs:
        raise ValueError("el archivo del intermedio no trae certificados")
    return list(certs)


def _bcv_context() -> ssl.SSLContext:
    """El sitio del BCV no envía su certificado intermedio y la verificación falla ("unable to get local issuer").
    Se descarga el intermedio desde la dirección que trae su propio certificado (AIA) y se suma a los de confianza
    solo para esta conexión: la cadena se sigue verificando completa hasta una raíz reconocida."""
    global _BCV_CONTEXT
    if _BCV_CONTEXT is not None:
        return _BCV_CONTEXT
    from cryptography import x509
    from cryptography.hazmat.primitives.serialization import Encoding
    from cryptography.x509.oid import AuthorityInformationAccessOID, ExtensionOID
    cert = x509.load_pem_x509_certificate(ssl.get_server_certificate(("www.bcv.org.ve", 443), timeout=8).encode())
    chain = []
    for _ in range(3):  # sube hasta tres niveles por si hay más de un intermedio
        try:
            aia = cert.extensions.get_extension_for_oid(ExtensionOID.AUTHORITY_INFORMATION_ACCESS).value
        except x509.ExtensionNotFound:
            break
        urls = [d.access_location.value for d in aia if d.access_method == AuthorityInformationAccessOID.CA_ISSUERS]
        if not urls or cert.issuer == cert.subject:
            break
        with urllib.request.urlopen(urllib.request.Request(urls[0], headers={"User-Agent": "Quincena/1.0"}), timeout=8) as response:
            raw = response.read()
        certs = _load_certs(raw)
        chain.extend(c.public_bytes(Encoding.PEM).decode() for c in certs)
        cert = certs[-1]
    if not chain:
        raise ValueError("el certificado del BCV no indica de dónde bajar su intermedio")
    context = ssl.create_default_context()
    context.load_verify_locations(cadata="".join(chain))
    _BCV_CONTEXT = context
    return context


def _day_of(value) -> str:
    """Fecha (AAAA-MM-DD) de una marca de tiempo ISO, o "" si no se entiende."""
    match = re.match(r"\s*(\d{4}-\d{2}-\d{2})", str(value or ""))
    return match.group(1) if match else ""


def _bcv_official() -> tuple[float, float | None, str]:
    """Tasas oficiales publicadas en bcv.org.ve: (Bs. por USD, Bs. por EUR o None, fecha valor ISO)."""
    try:
        page = _http_text("https://www.bcv.org.ve/")
    except urllib.error.URLError as error:
        if not isinstance(error.reason, ssl.SSLCertVerificationError):
            raise
        page = _http_text("https://www.bcv.org.ve/", context=_bcv_context())
    number = lambda key: re.search(rf'id="{key}".*?<strong>\s*([\d.,]+)\s*</strong>', page, re.S)
    found = number("dolar")
    if not found:
        raise ValueError("no encontré la tasa del dólar en la página del BCV")
    value = float(found.group(1).replace(".", "").replace(",", "."))
    euro = number("euro")
    when = re.search(r'Fecha Valor:.*?content="([^"]+)"', page, re.S)
    return value, (_rate_or_none(euro.group(1).replace(".", "").replace(",", ".")) if euro else None), (when.group(1) if when else "")


def _dolarapi(country: str) -> tuple[list, dict, float]:
    rows = _http_json(f"https://{country.lower()}.dolarapi.com/v1/dolares")
    row = next((item for item in rows if item.get("fuente") == "oficial"), rows[0])
    return rows, row, float(row.get("promedio") if row.get("promedio") is not None else row.get("venta"))


def fetch_rate(country: str = DEFAULT_COUNTRY, force: bool = False) -> dict:
    """Tasa oficial de la moneda local por 1 USD, con caché de 30 minutos y último valor conocido.

    Venezuela: la del BCV (bcv.org.ve) y la de dolarapi, y gana la de fecha más reciente (a igual fecha, el BCV), así
    una fuente caída o atrasada no deja la tasa vieja. Paralelo de Venezuela y oficial de Argentina: dolarapi. Los demás
    países: tipos de cambio diarios de open.er-api.com. Cada consulta queda en el registro de Render."""
    country = clean_country(country)
    cache = _RATE_CACHE.setdefault(country, {"rate": None, "updated": None, "fetched": 0.0, "parallel": None, "parallel_updated": None, "source": None,
                                             "euro": None, "euro_updated": None})
    now = time.time()
    # Caché de 30 minutos; "Actualizar" (force) vuelve a consultar, pero no más de una vez por minuto.
    if cache["rate"] and now - cache["fetched"] < (60 if force else 1800):
        return cache
    cache["fetched"] = now - 1500 if cache["rate"] else 0.0  # ante un fallo, se reintenta en ~5 minutos
    stamp = datetime.now(timezone.utc).isoformat()
    ok = lambda value: value is not None and math.isfinite(value) and 0 < value < 1_000_000
    if country in ("VE", "AR"):
        candidates, notes = [], []
        try:
            rows, row, value = _dolarapi(country)
            notes.append(f"dolarapi {value} ({row.get('fechaActualizacion') or 'sin fecha'})")
            if ok(value):
                candidates.append((_day_of(row.get("fechaActualizacion")), 0, value, row.get("fechaActualizacion") or stamp, "dolarapi"))
            market = next((item for item in rows if item.get("fuente") == "paralelo"), None) if country == "VE" else None
            market_value = _rate_or_none(market.get("promedio") if market and market.get("promedio") is not None else (market or {}).get("venta"))
            if market_value:
                cache.update(parallel=market_value, parallel_updated=market.get("fechaActualizacion") or stamp)
        except Exception as error:
            notes.append(f"dolarapi falló ({type(error).__name__}: {str(error)[:120]})")
        if country == "VE":
            try:
                value, euro, when = _bcv_official()
                notes.append(f"BCV {value}, euro {euro} (fecha valor {when or 'sin fecha'})")
                if ok(value):
                    candidates.append((_day_of(when), 1, value, when or stamp, "bcv"))
                if euro:
                    cache.update(euro=euro, euro_updated=when or stamp)
            except Exception as error:
                notes.append(f"BCV falló ({type(error).__name__}: {str(error)[:120]})")
            if not cache.get("euro") or (cache.get("euro_updated") or "") < stamp[:10]:
                try:  # euro de respaldo si la página del BCV no respondió
                    erows = _http_json("https://ve.dolarapi.com/v1/euros")
                    erow = next((item for item in erows if item.get("fuente") == "oficial"), None) if isinstance(erows, list) else None
                    evalue = _rate_or_none((erow or {}).get("promedio") if (erow or {}).get("promedio") is not None else (erow or {}).get("venta"))
                    if evalue and _day_of((erow or {}).get("fechaActualizacion")) >= _day_of(cache.get("euro_updated")):
                        cache.update(euro=evalue, euro_updated=(erow or {}).get("fechaActualizacion") or stamp)
                        notes.append(f"euro dolarapi {evalue}")
                except Exception as error:
                    notes.append(f"euro dolarapi falló ({type(error).__name__})")
        if candidates:
            day, _, value, updated, source = max(candidates)
            cache.update(rate=value, updated=updated, fetched=now, source=source)
            app.logger.info("Tasa %s: uso %s %s. Fuentes: %s.", country, source, value, "; ".join(notes))
        else:
            app.logger.error("Tasa %s sin actualizar, se mantiene %s. Fuentes: %s.", country, cache["rate"], "; ".join(notes))
        return cache
    try:
        data = _http_json("https://open.er-api.com/v6/latest/USD")
        value = float((data.get("rates") or {}).get(COUNTRIES[country]["cur"]))
        if ok(value):
            cache.update(rate=value, updated=stamp, fetched=now, source="open.er-api")
    except Exception as error:
        app.logger.error("Tasa %s sin actualizar, se mantiene %s (%s: %s).", country, cache["rate"], type(error).__name__, str(error)[:120])
    return cache


def fetch_bcv_rate() -> dict:
    return fetch_rate("VE")



# ---------------------------------------------------------------- Panel de administración (solo web): BD, usuarios,
# cobros de Negocio Plus, facturas en PDF por correo y búsqueda de pagos en Binance.

VE_TZ = timezone(timedelta(hours=-4))
BILLING_DEFAULTS = {"issuer_name": "Gigadevs", "issuer_rif": "27344270-3",
                    "issuer_address": "Av. Víctor Baptista, Urb. José Gregorio Hernández\nLos Teques, Miranda. Zona Postal 1201", "issuer_phone": "", "issuer_email": "",
                    "doc_title": "Factura", "series": "", "plus_price_usd": 0.0, "plus_days": 30, "iva_pct": 16.0, "igtf_pct": 3.0,
                    "footer": "", "imprenta": ""}
FOREIGN_METHODS = {"binance", "zelle", "efectivo_usd"}  # pagos en divisas: llevan IGTF
PLUS_METHODS = {"binance": "Binance (USDT)", "pago_movil": "Pago móvil", "transferencia": "Transferencia", "zelle": "Zelle",
                "efectivo_usd": "Efectivo $", "efectivo_bs": "Efectivo Bs.", "otro": "Otro"}


class AppSetting(db.Model):
    key = db.Column(db.String(64), primary_key=True)
    value = db.Column(db.JSON, nullable=False)


class PlusPayment(db.Model):
    """Un cobro de Negocio Plus y su factura (el número es correlativo y no se reutiliza, aunque se anule)."""
    id = db.Column(db.Integer, primary_key=True)
    number = db.Column(db.Integer, nullable=False, unique=True)
    series = db.Column(db.String(8), nullable=False, default="")
    user_id = db.Column(db.Integer, db.ForeignKey("user.id", ondelete="SET NULL"), nullable=True, index=True)
    amount_usd = db.Column(db.Float, nullable=False)  # precio antes de impuestos
    iva_pct = db.Column(db.Float, nullable=False, default=0)
    igtf_pct = db.Column(db.Float, nullable=False, default=0)
    rate = db.Column(db.Float, nullable=True)  # Bs. por $ (BCV) del día del cobro
    method = db.Column(db.String(20), nullable=False)
    reference = db.Column(db.String(120), nullable=True)
    binance_tx = db.Column(db.String(80), nullable=True, unique=True)  # una transacción de Binance paga un solo cobro
    days = db.Column(db.Integer, nullable=False)
    period_from = db.Column(db.DateTime(timezone=True), nullable=False)
    period_to = db.Column(db.DateTime(timezone=True), nullable=False)
    client = db.Column(db.JSON, nullable=False)  # {name, doc, address, phone, email}
    control_number = db.Column(db.String(40), nullable=True)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    created_by = db.Column(db.Integer, nullable=True)
    emailed_at = db.Column(db.DateTime(timezone=True), nullable=True)
    void_at = db.Column(db.DateTime(timezone=True), nullable=True)
    void_reason = db.Column(db.String(200), nullable=True)


def billing_settings() -> dict:
    row = db.session.get(AppSetting, "billing")
    return {**BILLING_DEFAULTS, **(row.value if row and isinstance(row.value, dict) else {})}


def _aware(when):
    return when if when is None or when.tzinfo else when.replace(tzinfo=timezone.utc)


def ve_money(value: float, decimals: int = 2) -> str:
    """1234.5 → 1.234,50 (formato de Venezuela)."""
    return f"{value:,.{decimals}f}".replace(",", "§").replace(".", ",").replace("§", ".")


def payment_totals(p: "PlusPayment") -> dict:
    """Igual que la facturación del ERP (Prov. SNAT/2011/00071, LIVA, Ley de IGTF): el precio se pacta en $ y se lleva a Bs.
    con la tasa BCV del día; el IVA se calcula sobre la base en Bs.; si se paga en divisas, el IGTF va APARTE del total."""
    from decimal import ROUND_HALF_UP, Decimal
    q2 = lambda v: Decimal(v).quantize(Decimal("0.01"), ROUND_HALF_UP)
    rate = Decimal(str(p.rate or 1))
    base_usd = q2(Decimal(str(p.amount_usd)))
    base_bs = q2(base_usd * rate)
    iva_bs = q2(base_bs * Decimal(str(p.iva_pct or 0)) / 100)
    total_bs = base_bs + iva_bs
    igtf_bs = q2(total_bs * Decimal(str(p.igtf_pct or 0)) / 100)
    pay_bs = total_bs + igtf_bs
    usd = lambda v: float(q2(v / rate))
    return {"rate": float(rate), "base_usd": float(base_usd), "base_bs": float(base_bs), "iva_bs": float(iva_bs), "total_bs": float(total_bs),
            "igtf_bs": float(igtf_bs), "pay_bs": float(pay_bs), "iva_usd": usd(iva_bs), "total_usd": usd(total_bs), "igtf_usd": usd(igtf_bs),
            "pay_usd": usd(pay_bs), "in_currency": p.method in FOREIGN_METHODS}


def payment_json(p: "PlusPayment", users: dict | None = None) -> dict:
    t = payment_totals(p)
    return {"id": p.id, "number": p.number, "series": p.series, "doc": doc_number(p), "user_id": p.user_id,
            "username": (users or {}).get(p.user_id), "amount_usd": p.amount_usd, "total": t["pay_usd"], "total_bs": t["pay_bs"], "igtf_bs": t["igtf_bs"],
            "rate": p.rate, "method": p.method, "method_label": PLUS_METHODS.get(p.method, p.method), "reference": p.reference,
            "binance_tx": p.binance_tx, "days": p.days, "period_from": _aware(p.period_from).isoformat(), "period_to": _aware(p.period_to).isoformat(),
            "client": p.client, "control_number": p.control_number, "created_at": _aware(p.created_at).isoformat(),
            "emailed_at": _aware(p.emailed_at).isoformat() if p.emailed_at else None,
            "void_at": _aware(p.void_at).isoformat() if p.void_at else None, "void_reason": p.void_reason}


def doc_number(p: "PlusPayment") -> str:
    return f"{p.series}-{p.number:08d}" if p.series else f"{p.number:08d}"


def _latin(text) -> str:
    """Las fuentes básicas del PDF usan Latin-1 (cubre á, ñ, ¿, °): lo demás (emojis, €) se reemplaza."""
    return str(text or "").replace("€", "EUR").replace("—", "-").replace("–", "-").replace("·", "-").encode("latin-1", "replace").decode("latin-1")


def invoice_pdf(p: "PlusPayment", cfg: dict) -> bytes:
    """Factura en PDF: emisor, número y control, adquirente, condiciones, detalle y totales como en el ERP."""
    from fpdf import FPDF
    t = payment_totals(p)
    green, green2, mint, soft, ink, mut, navy = (11, 107, 77), (18, 147, 106), (191, 240, 216), (243, 248, 245), (18, 33, 27), (70, 86, 78), (23, 37, 84)
    white = (255, 255, 255)
    pdf = FPDF(format="Letter", unit="mm")
    pdf.set_auto_page_break(False)
    pdf.add_page()
    L, W = 14, pdf.w - 28
    icons = Path(app.static_folder) / "icons"
    logo, giga = icons / "icon-192.png", icons / "gigadevs.png"
    when = _aware(p.created_at).astimezone(VE_TZ)
    title = cfg["doc_title"] or "Factura"

    def text(x, y, w, s, size=9, style="", color=ink, align="L", h=4.6):
        pdf.set_xy(x, y); pdf.set_font("Helvetica", style, size); pdf.set_text_color(*color)
        pdf.cell(w, h, _latin(s), align=align)

    # Banda superior con las dos marcas y franja tricolor
    pdf.set_fill_color(*green); pdf.rect(0, 0, pdf.w, 30, "F")
    pdf.set_fill_color(*green2); pdf.ellipse(pdf.w - 78, -52, 120, 81, "F")
    pdf.set_fill_color(*white); pdf.rect(L, 7, 16, 16, "F", round_corners=True, corner_radius=4)
    if logo.exists():
        pdf.image(str(logo), x=L + 1, y=8, w=14)
    text(L + 20, 8.5, 80, "Quincena VE", 19, "B", white, h=8)
    text(L + 20, 17, 80, "Negocio Plus", 9, "B", mint)
    if giga.exists():
        pdf.image(str(giga), x=L + W - 18, y=6, w=18)
    text(L + W - 80, 10, 60, "Una app de", 7.5, "", mint, "R")
    text(L + W - 80, 14.5, 60, "Gigadevs", 13, "B", white, "R", 6)
    for i, c in enumerate(((255, 204, 0), (0, 36, 125), (207, 20, 43))):
        pdf.set_fill_color(*c); pdf.rect(i * pdf.w / 3, 30, pdf.w / 3, 1.6, "F")

    # Emisor (izquierda) y documento (derecha)
    y = 39
    text(L, y, 110, cfg["issuer_name"] or "Gigadevs", 13, "B", ink, h=6); y += 6.5
    if cfg["issuer_rif"]:
        text(L, y, 110, f"RIF: {cfg['issuer_rif']}", 9, "B", ink); y += 4.8
    for line in str(cfg["issuer_address"] or "").splitlines():
        text(L, y, 110, line, 8.5, "", mut); y += 4.2
    contact = " - ".join(x for x in (cfg["issuer_phone"], cfg["issuer_email"]) if x)
    if contact:
        text(L, y, 110, contact, 8.5, "", mut); y += 4.2
    bx, bw = L + W - 70, 70
    pdf.set_fill_color(*soft); pdf.set_draw_color(*mint); pdf.rect(bx, 37, bw, 36, "DF", round_corners=True, corner_radius=3)
    text(bx + 5, 40, bw - 10, title.upper(), 13, "B", green, h=6)
    rows = [("N°", doc_number(p), "B"), ("N° de Control", p.control_number or "-", "B"), ("Fecha de emisión", when.strftime("%d/%m/%Y"), ""),
            ("Hora de emisión", when.strftime("%I:%M %p").replace("AM", "a. m.").replace("PM", "p. m."), "")]
    ry = 47.5
    for label, value, style in rows:
        text(bx + 5, ry, 30, label, 8.5, "", mut); text(bx + 35, ry, bw - 40, value, 8.5, style, ink, "R"); ry += 5.6

    # Adquirente y condiciones
    y = 80
    c = p.client or {}
    half = (W - 6) / 2
    for i, (head, lines) in enumerate((
            ("Adquirente", [c.get("name"), f"RIF/C.I.: {c.get('doc')}" if c.get("doc") else "", c.get("address"),
                            " - ".join(x for x in (c.get("phone"), c.get("email")) if x)]),
            ("Condiciones", ["Pago: Contado", f"Forma de pago: {PLUS_METHODS.get(p.method, p.method)}" + (f" - Ref. {p.reference}" if p.reference else ""),
                             f"Moneda: USD - Tasa BCV: {ve_money(t['rate'], 4)} Bs/USD", "Página 1 de 1"]))):
        x = L + i * (half + 6)
        pdf.set_draw_color(*mint); pdf.rect(x, y, half, 30, "D", round_corners=True, corner_radius=3)
        text(x + 4, y + 3, half - 8, head.upper(), 7.5, "B", green)
        ly = y + 8.5
        for j, line in enumerate(l for l in lines if l):
            text(x + 4, ly, half - 8, line, 9 if j == 0 and i == 0 else 8.5, "B" if j == 0 and i == 0 else "", ink if j == 0 else mut); ly += 4.6

    # Detalle
    y = 118
    cols = [("Código", 22, "L"), ("Descripción", W - 22 - 14 - 30 - 30, "L"), ("Cant.", 14, "R"), ("Precio unit. (USD)", 30, "R"), ("Monto (Bs)", 30, "R")]
    pdf.set_fill_color(*green); pdf.rect(L, y, W, 8, "F", round_corners=True, corner_radius=2)
    x = L
    for name, w, align in cols:
        text(x + 2, y + 1.8, w - 4, name, 8.5, "B", white, align); x += w
    y += 11
    start, end = _aware(p.period_from).astimezone(VE_TZ), _aware(p.period_to).astimezone(VE_TZ)
    values = ["QVE-PLUS", f"Suscripción Quincena VE Negocio Plus - {p.days} días", "1", ve_money(t["base_usd"]), ve_money(t["base_bs"])]
    x = L
    for (name, w, align), value in zip(cols, values):
        text(x + 2, y, w - 4, value, 9, "B" if name == "Descripción" else "", ink, align); x += w
    text(L + 24, y + 4.8, 120, f"Período: {start:%d/%m/%Y} al {end:%d/%m/%Y}", 8, "", mut)
    pdf.set_draw_color(*mint); pdf.line(L, y + 11, L + W, y + 11)

    # Marca de agua
    if logo.exists():
        with pdf.local_context(fill_opacity=0.06, stroke_opacity=0.06):
            pdf.image(str(logo), x=pdf.w / 2 - 28, y=142, w=56)

    # Totales (Bs y su equivalente en USD)
    ty = 196
    tx, tw = L + W - 100, 100
    lines = [(f"Base imponible alícuota general {ve_money(p.iva_pct or 0, 0)}%", t["base_bs"], t["base_usd"]),
             (f"IVA {ve_money(p.iva_pct or 0, 0)}%", t["iva_bs"], t["iva_usd"])]
    text(tx + 54, ty, 22, "Bs", 8.5, "B", mut, "R"); text(tx + 76, ty, 22, "USD", 8.5, "B", mut, "R")
    ly = ty + 5.5
    for label, bs, usd in lines:
        text(tx, ly, 54, label, 8.5, "", mut); text(tx + 54, ly, 22, ve_money(bs), 8.5, "", ink, "R"); text(tx + 76, ly, 22, ve_money(usd), 8.5, "", ink, "R"); ly += 5
    pdf.set_draw_color(*mint); pdf.line(tx, ly + 0.5, tx + tw, ly + 0.5); ly += 1.8
    text(tx, ly, 54, "TOTAL", 9.5, "B", ink); text(tx + 54, ly, 22, ve_money(t["total_bs"]), 9.5, "B", ink, "R"); text(tx + 76, ly, 22, ve_money(t["total_usd"]), 9.5, "B", ink, "R"); ly += 5.5
    if t["igtf_bs"]:
        text(tx, ly, 54, f"IGTF {ve_money(p.igtf_pct, 0)}% (pago en divisas)", 8.5, "", mut)
        text(tx + 54, ly, 22, ve_money(t["igtf_bs"]), 8.5, "", ink, "R"); text(tx + 76, ly, 22, ve_money(t["igtf_usd"]), 8.5, "", ink, "R"); ly += 5.5
    pdf.set_fill_color(*green); pdf.rect(tx, ly, tw, 10, "F", round_corners=True, corner_radius=2)
    text(tx + 4, ly + 2.6, 50, "TOTAL A PAGAR", 10, "B", white)
    text(tx + 54, ly + 2.6, 22, ve_money(t["pay_bs"]), 10, "B", white, "R"); text(tx + 76, ly + 2.6, 22, ve_money(t["pay_usd"]), 10, "B", white, "R")

    # Notas legales (izquierda de los totales)
    notes = [f"Operación pactada en USD; el equivalente en bolívares se calcula a la tasa de cambio oficial del BCV del día de emisión (Bs {ve_money(t['rate'], 4)})."]
    if t["igtf_bs"]:
        notes.append(f"IGTF {ve_money(p.igtf_pct, 0)}% sobre Bs {ve_money(t['total_bs'])} pagados en divisas, cobrado aparte del total.")
    if cfg.get("footer"):
        notes.append(cfg["footer"])
    pdf.set_xy(L, ty + 4); pdf.set_font("Helvetica", "", 7.3); pdf.set_text_color(*mut)
    pdf.multi_cell(W - tw - 8, 3.5, _latin("\n".join(notes)))

    if p.void_at:
        with pdf.local_context(fill_opacity=0.45):
            text(L, 150, W, "ANULADA", 54, "B", (207, 20, 43), "C", 22)

    # Pie
    pdf.set_draw_color(*mint); pdf.line(L, 252, L + W, 252)
    foot = cfg.get("imprenta") or "Documento emitido por Quincena VE."
    pdf.set_xy(L, 254); pdf.set_font("Helvetica", "", 7); pdf.set_text_color(*mut)
    pdf.multi_cell(W, 3.4, _latin(f"{foot}\n{cfg['issuer_name'] or 'Gigadevs'}{' - RIF ' + cfg['issuer_rif'] if cfg['issuer_rif'] else ''} - {title} {doc_number(p)} del {when:%d/%m/%Y}."), align="C")
    return bytes(pdf.output())


def send_invoice(p: "PlusPayment") -> bool:
    cfg = billing_settings()
    to = (p.client or {}).get("email") or ""
    if not EMAIL_RE.match(to):
        return False
    t = payment_totals(p)
    doc = doc_number(p)
    until = _aware(p.period_to).astimezone(VE_TZ).strftime("%d/%m/%Y")
    title = cfg["doc_title"] or "Factura"
    subject = f"{title} {doc} – Quincena VE Negocio Plus"
    name = html_escape((p.client or {}).get("name") or "")
    body = (f"Estimado/a {(p.client or {}).get('name') or ''}:\n\nGracias por adquirir Quincena VE Negocio Plus. Su plan está activo hasta el {until}.\n\n"
            f"Adjuntamos su {title.lower()} N° {doc} por Bs. {ve_money(t['pay_bs'])} (USD {ve_money(t['pay_usd'])})" +
            ".\n\nAtentamente,\nEl equipo de Quincena VE")
    row = lambda k, v: f'<tr><td style="padding:8px 0;color:#46564E;border-top:1px solid #E3ECE6">{k}</td><td align="right" style="padding:8px 0;font-weight:bold;border-top:1px solid #E3ECE6">{v}</td></tr>'
    inner = (f'<p style="margin:0 0 16px">Estimado/a <b>{name}</b>:</p>'
             f'<p style="margin:0 0 16px">Gracias por adquirir <b>Quincena VE Negocio Plus</b>. Su plan está activo hasta el <b>{until}</b>.</p>'
             '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:0 0 18px;font-size:15px">'
             + row(html_escape(title), doc) + row("Plan", f"Negocio Plus · {p.days} días") + row("Forma de pago", html_escape(PLUS_METHODS.get(p.method, p.method)))
             + row("Total a pagar", f"Bs. {ve_money(t['pay_bs'])} · USD {ve_money(t['pay_usd'])}") +
             '</table><p style="margin:0 0 16px">Adjuntamos el documento en PDF.</p><p style="margin:0">Atentamente,<br><b>El equipo de Quincena VE</b></p>')
    sent = send_mail(to, subject, body, email_layout(subject, inner, f"Su plan Negocio Plus está activo hasta el {until}."),
                     [(f"{title.replace(' ', '_')}_{doc}.pdf", invoice_pdf(p, cfg))])
    if sent:
        p.emailed_at = datetime.now(timezone.utc)
        db.session.commit()
    return sent


def binance_client():
    key, secret = os.getenv("BINANCE_API_KEY", "").strip(), os.getenv("BINANCE_API_SECRET", "").strip()
    return (key, secret) if key and secret else None


def binance_get(path: str, params: dict):
    """Consulta firmada (HMAC-SHA256) de solo lectura a la cuenta de Binance, igual que en el ERP."""
    key, secret = binance_client()
    params = {**{k: v for k, v in params.items() if v is not None}, "timestamp": int(time.time() * 1000), "recvWindow": 10000}
    query = urllib.parse.urlencode(params)
    signature = hmac.new(secret.encode(), query.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(f"https://api.binance.com{path}?{query}&signature={signature}", headers={"X-MBX-APIKEY": key, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.loads(response.read(2 * 1024 * 1024) or b"null")
    except urllib.error.HTTPError as error:
        try:
            msg = (json.loads(error.read(64 * 1024) or b"{}") or {}).get("msg", "")
        except Exception:
            msg = ""
        raise ValueError(f"Binance respondió {error.code}: {str(msg)[:120]}")


def binance_incoming(days: int = 7) -> list[dict]:
    """Ingresos en USDT recientes: Binance Pay y depósitos acreditados."""
    end = int(time.time() * 1000)
    start = end - days * 86400 * 1000
    out = []
    pay = binance_get("/sapi/v1/pay/transactions", {"startTime": start, "endTime": end, "limit": 100})
    for t in (pay.get("data") if isinstance(pay, dict) else None) or []:
        try:
            amount = float(t.get("amount"))
        except (TypeError, ValueError):
            continue
        if amount > 0 and t.get("currency") == "USDT" and t.get("transactionId"):
            out.append({"id": f"pay:{t['transactionId']}", "ts": int(t.get("transactionTime") or 0), "amount": amount, "origin": "Binance Pay",
                        "payer": str((t.get("payerInfo") or {}).get("name") or (t.get("payerInfo") or {}).get("binanceId") or "")[:60]})
    try:
        deposits = binance_get("/sapi/v1/capital/deposit/hisrec", {"coin": "USDT", "status": 1, "startTime": start, "endTime": end})
    except ValueError:
        deposits = []
    for d in deposits if isinstance(deposits, list) else []:
        try:
            amount = float(d.get("amount"))
        except (TypeError, ValueError):
            continue
        if amount > 0 and d.get("txId"):
            out.append({"id": f"dep:{d['txId']}"[:80], "ts": int(d.get("insertTime") or 0), "amount": amount, "origin": f"Depósito {d.get('network', '')}".strip(), "payer": ""})
    used = {r[0] for r in db.session.query(PlusPayment.binance_tx).filter(PlusPayment.binance_tx.isnot(None)).all()}
    for item in out:
        item["used"] = item["id"] in used
    return sorted(out, key=lambda i: -i["ts"])


ERROR_PAGES = {
    400: ("Solicitud no válida", "La petición llegó incompleta o con datos que no se pueden procesar.", "Vuelve a la página anterior e inténtalo de nuevo."),
    401: ("Sesión requerida", "Para ver esto necesitas iniciar sesión.", "Entra a la app con tu correo y tu clave."),
    403: ("Acceso denegado", "No tienes permiso para ver esta página.", "Si crees que es un error, escríbenos."),
    404: ("Página no encontrada", "La dirección no existe o cambió.", "Revisa el enlace o vuelve al inicio."),
    405: ("Acción no permitida", "Esta dirección no acepta ese tipo de solicitud.", "Usa los botones de la app en lugar de escribir la dirección."),
    413: ("Archivo demasiado grande", "La solicitud supera el tamaño máximo permitido.", "Reduce el archivo e inténtalo de nuevo."),
    429: ("Demasiados intentos", "Recibimos muchas solicitudes seguidas desde tu conexión y las frenamos por seguridad.", "Espera unos minutos e inténtalo de nuevo."),
    500: ("Algo salió mal", "Ocurrió un error inesperado en el servidor. Tus datos están a salvo.", "Inténtalo de nuevo en unos minutos; si se repite, escríbenos con el código de referencia."),
    503: ("Estamos en mantenimiento", "Quincena VE se está actualizando.", "Vuelve en unos minutos: tus datos están seguros."),
}
_ERROR_TEMPLATE = None


def error_response(code: int, ref: str | None = None):
    """API → JSON (como siempre, la app lo muestra). Web → página propia de la marca, sin trazas ni detalles internos."""
    global _ERROR_TEMPLATE
    title, message, advice = ERROR_PAGES.get(code, ERROR_PAGES[500])
    if request.path.startswith("/api/"):
        text = {413: "La solicitud supera el tamaño máximo permitido.", 429: "Demasiados intentos. Espera unos minutos e inténtalo de nuevo.",
                404: "No encontrado.", 405: "Método no permitido.", 500: "Algo salió mal en el servidor. Inténtalo de nuevo en unos minutos."}.get(code, message)
        body = {"error": text}
        if ref:
            body["ref"] = ref
        response = jsonify(body)
    else:
        try:
            if _ERROR_TEMPLATE is None:
                _ERROR_TEMPLATE = (Path(app.static_folder) / "error.html").read_text(encoding="utf-8")
            html = _ERROR_TEMPLATE
        except OSError:
            html = "<!doctype html><title>{{CODIGO}}</title><h1>{{CODIGO}} · {{TITULO}}</h1><p>{{MENSAJE}}</p>{{REF}}<p><a href='/'>Inicio</a></p>"
        html = (html.replace("{{CODIGO}}", str(code)).replace("{{TITULO}}", html_escape(title)).replace("{{MENSAJE}}", html_escape(message))
                .replace("{{CONSEJO}}", html_escape(advice))
                .replace("{{REF}}", f'<p class="ref">Código de referencia: <code>{html_escape(ref)}</code></p>' if ref else ""))
        response = app.response_class(html, mimetype="text/html")
        response.headers["Cache-Control"] = "no-store"
    response.status_code = code
    response.headers["X-Robots-Tag"] = "noindex, nofollow"
    if code == 503:
        response.headers["Retry-After"] = "300"
    return response


def _make_error_handler(code):
    def handler(_error):
        return error_response(code)
    return handler


for _code in (400, 401, 403, 404, 405, 413, 429, 503):
    app.register_error_handler(_code, _make_error_handler(_code))


@app.errorhandler(500)
def server_error(error):
    ref = secrets.token_hex(4).upper()
    try:
        db.session.rollback()
    except Exception:  # noqa: BLE001 — la base puede ser justo lo que falló
        pass
    original = getattr(error, "original_exception", None) or error
    app.logger.error("Error interno ref=%s %s %s", ref, request.method, request.path, exc_info=original)  # detalle solo en el registro
    return error_response(500, ref)


# ---- Módulos de rutas (blueprints) ----
from routes.auth import bp as _auth_bp  # noqa: E402
app.register_blueprint(_auth_bp)
from routes.cuentas import bp as _cuentas_bp  # noqa: E402
app.register_blueprint(_cuentas_bp)
from routes.gastos import bp as _gastos_bp  # noqa: E402
app.register_blueprint(_gastos_bp)
from routes.negocio import bp as _negocio_bp  # noqa: E402
app.register_blueprint(_negocio_bp)
from routes.notificaciones import bp as _notificaciones_bp  # noqa: E402
app.register_blueprint(_notificaciones_bp)
from routes.admin import bp as _admin_bp  # noqa: E402
app.register_blueprint(_admin_bp)

with app.app_context():
    db.create_all()
    migrate_user_email()
    migrate_shared_state_schema()
    db.engine.dispose()  # cada proceso de gunicorn abre sus propias conexiones

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=int(os.getenv("PORT", "5000")), debug=False)
