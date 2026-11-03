"""Autenticación: configuración inicial, entrar y salir (web y app), passkeys, registro, verificar el correo y recuperar la contraseña.

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import copy
import hashlib
import hmac
import json
import os
import secrets
import time
from datetime import datetime, timedelta, timezone

from flask import Blueprint, g, jsonify, request, session
from webauthn import base64url_to_bytes, generate_authentication_options, generate_registration_options, options_to_json, verify_authentication_response, verify_registration_response
from webauthn.helpers import bytes_to_base64url
from webauthn.helpers.structs import AttestationConveyancePreference, AuthenticatorSelectionCriteria, PublicKeyCredentialDescriptor, ResidentKeyRequirement, UserVerificationRequirement
from werkzeug.security import generate_password_hash

import app as core
from app import (  # noqa: E402
    BIZ_PROFESSIONS, BIZ_TYPES, COUNTRIES, DEFAULT_SHARED_DATA, DeviceToken, EMAIL_RE,
    EmailVerification, PasswordReset, User, UserPasskey, _forgot_email_key, _login_name_key,
    app, audit, clean_country, clean_source, current_user, db,
    ensure_user_state, establish_user_session, limiter, mail_configured, mail_problem, password_problem,
    public_origin, pw_stamp, registration_open, require_auth, reset_email, send_mail_async,
    setup_required, start_session, user_payload, verify_login, webauthn_settings,
)

bp = Blueprint("auth", __name__)


@bp.get("/api/status")
def status():
    required = setup_required()
    user = current_user()
    if user and not session.get("csrf_token"):
        session["csrf_token"] = secrets.token_urlsafe(32)
    return jsonify(
        setup_required=required,
        authenticated=bool(user),
        csrf_token=session.get("csrf_token"),
        user=user_payload(user) if user else None,
        app_name="Quincena VE", registration_open=registration_open(),
    )


@bp.post("/api/setup")
@limiter.limit("5 per hour")
def setup_admin():
    if not setup_required():
        return jsonify(error="La configuración inicial ya se completó."), 409
    configured_token = os.getenv("BOOTSTRAP_TOKEN", "")
    if not configured_token or len(configured_token) < 24:
        return jsonify(error="Falta configurar BOOTSTRAP_TOKEN con al menos 24 caracteres en el entorno."), 503
    body = request.get_json(silent=True) or {}
    supplied = str(body.get("bootstrap_token", ""))
    username = str(body.get("username", "")).strip().lower()
    password = str(body.get("password", ""))
    if not hmac.compare_digest(supplied, configured_token):
        audit("setup_failed", "Token inicial inválido", user_id=None)
        return jsonify(error="El código inicial no es correcto."), 403
    if len(username) < 3 or len(username) > 40 or not all(c.isalnum() or c in "._-" for c in username):
        return jsonify(error="El usuario debe tener entre 3 y 40 caracteres: letras, números, punto, guion o guion bajo."), 400
    if password_problem(password, username):
        return jsonify(error=password_problem(password, username)), 400
    if User.query.first() is not None:
        return jsonify(error="La configuración inicial ya fue completada por otro usuario."), 409
    user = User(username=username, password_hash=generate_password_hash(password), role="admin", must_change_password=False)
    db.session.add(user)
    db.session.flush()
    # Never reset any financial state while creating the first account. If a
    # migrated orphan exists it will be claimed by ensure_user_state below.
    db.session.commit()
    ensure_user_state(user.id)
    session.clear()
    session["uid"] = user.id
    session["ps"] = pw_stamp(user)
    session["csrf_token"] = secrets.token_urlsafe(32)
    session.permanent = True
    audit("setup_completed", "Cuenta administradora inicial creada", user.id)
    return jsonify(ok=True, csrf_token=session["csrf_token"], user={"username": user.username, "role": user.role, "must_change_password": False})


@bp.post("/api/login")
@limiter.limit("10 per minute")
@limiter.limit("30 per hour", key_func=_login_name_key)  # frena también los ataques repartidos entre muchas IP
def login():
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    password = str(body.get("password", ""))
    user = User.query.filter_by(username=username, active=True).first()
    if not verify_login(user, password):
        # No se guarda lo que se escribió si el usuario no existe: a veces la gente pega aquí su contraseña.
        audit("login_failed", f"Usuario: {user.username}" if user else "Usuario inexistente", user_id=user.id if user else None)
        return jsonify(error="Usuario o contraseña incorrectos."), 401
    session.clear()
    session["uid"] = user.id
    session["ps"] = pw_stamp(user)
    session["seen"] = time.time()
    session["csrf_token"] = secrets.token_urlsafe(32)
    session.permanent = True
    audit("login_succeeded", "Inicio de sesión correcto", user.id)
    return jsonify(ok=True, csrf_token=session["csrf_token"], user=user_payload(user))


@bp.get("/api/passkeys/status")
@require_auth()
def passkeys_status():
    credentials = UserPasskey.query.filter_by(user_id=g.user.id).order_by(UserPasskey.created_at.asc()).all()
    return jsonify(available=True, credentials=[{"id": item.id, "label": item.label, "created_at": item.created_at.isoformat()} for item in credentials])


@bp.post("/api/passkeys/register/options")
@limiter.limit("5 per hour")
@require_auth()
def passkey_registration_options():
    if g.user.must_change_password:
        return jsonify(error="Cambia primero la contraseña temporal."), 428
    try:
        rp_id, _origin = webauthn_settings()
        existing = UserPasskey.query.filter_by(user_id=g.user.id).all()
        options = generate_registration_options(
            rp_id=rp_id, rp_name="Control Financiero",
            user_id=str(g.user.id).encode("utf-8"),
            user_name=g.user.username, user_display_name=g.user.username,
            attestation=AttestationConveyancePreference.NONE,
            authenticator_selection=AuthenticatorSelectionCriteria(
                resident_key=ResidentKeyRequirement.PREFERRED,
                user_verification=UserVerificationRequirement.REQUIRED,
            ),
            exclude_credentials=[PublicKeyCredentialDescriptor(id=base64url_to_bytes(item.credential_id)) for item in existing],
        )
        session["passkey_register_challenge"] = bytes_to_base64url(options.challenge)
        session["passkey_register_user_id"] = g.user.id
        return jsonify(options=json.loads(options_to_json(options)))
    except Exception:
        app.logger.exception("No se pudieron generar opciones de registro WebAuthn")
        return jsonify(error="No se pudo iniciar la configuración biométrica. Verifica el origen HTTPS de la aplicación."), 503


@bp.post("/api/passkeys/register/verify")
@limiter.limit("5 per hour")
@require_auth()
def passkey_registration_verify():
    if session.get("passkey_register_user_id") != g.user.id:
        return jsonify(error="La solicitud de registro venció. Inténtalo otra vez."), 400
    challenge_text = session.pop("passkey_register_challenge", None)
    session.pop("passkey_register_user_id", None)
    body = request.get_json(silent=True) or {}
    credential = body.get("credential")
    if not challenge_text or not isinstance(credential, dict):
        return jsonify(error="Falta la credencial de seguridad o el reto venció."), 400
    try:
        rp_id, origin = webauthn_settings()
        verification = verify_registration_response(
            credential=credential,
            expected_challenge=base64url_to_bytes(challenge_text),
            expected_rp_id=rp_id, expected_origin=origin,
            require_user_verification=True,
        )
        credential_id = bytes_to_base64url(verification.credential_id)
        existing = UserPasskey.query.filter_by(credential_id=credential_id).first()
        if existing and existing.user_id != g.user.id:
            return jsonify(error="Esta credencial ya está asociada a otra cuenta."), 409
        if existing:
            existing.public_key = bytes_to_base64url(verification.credential_public_key)
            existing.sign_count = int(verification.sign_count or 0)
            existing.label = str(body.get("label") or existing.label or "Este dispositivo")[:80]
        else:
            db.session.add(UserPasskey(
                user_id=g.user.id, credential_id=credential_id,
                public_key=bytes_to_base64url(verification.credential_public_key),
                sign_count=int(verification.sign_count or 0),
                label=str(body.get("label") or "Este dispositivo")[:80],
            ))
        db.session.commit()
        audit("passkey_registered", "Acceso biométrico registrado", g.user.id)
        return jsonify(ok=True, message="Acceso biométrico configurado en este dispositivo.")
    except Exception:
        db.session.rollback()
        app.logger.exception("Falló la verificación de registro WebAuthn para usuario %s", g.user.id)
        return jsonify(error="No se pudo verificar el registro biométrico. Vuelve a intentarlo."), 400


@bp.delete("/api/passkeys/<int:credential_id>")
@require_auth()
def passkey_delete(credential_id: int):
    item = UserPasskey.query.filter_by(id=credential_id, user_id=g.user.id).first()
    if item is None:
        return jsonify(error="Credencial no encontrada para esta cuenta."), 404
    db.session.delete(item)
    db.session.commit()
    audit("passkey_removed", "Acceso biométrico eliminado", g.user.id)
    return jsonify(ok=True)


@bp.post("/api/passkeys/login/options")
@limiter.limit("10 per minute")
def passkey_login_options():
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    user = User.query.filter_by(username=username, active=True).first()
    if not user or user.must_change_password:
        return jsonify(error="Inicia sesión con tu contraseña para terminar la configuración de la cuenta."), 401
    credentials = UserPasskey.query.filter_by(user_id=user.id).all()
    if not credentials:
        return jsonify(error="Esta cuenta aún no tiene acceso biométrico registrado. Entra con contraseña y actívalo desde la aplicación."), 404
    try:
        rp_id, _origin = webauthn_settings()
        options = generate_authentication_options(
            rp_id=rp_id,
            allow_credentials=[PublicKeyCredentialDescriptor(id=base64url_to_bytes(item.credential_id)) for item in credentials],
            user_verification=UserVerificationRequirement.REQUIRED,
        )
        session["passkey_login_challenge"] = bytes_to_base64url(options.challenge)
        session["passkey_login_user_id"] = user.id
        return jsonify(options=json.loads(options_to_json(options)))
    except Exception:
        app.logger.exception("No se pudieron generar opciones de autenticación WebAuthn")
        return jsonify(error="No se pudo iniciar el acceso biométrico. Verifica la configuración HTTPS."), 503


@bp.post("/api/passkeys/login/verify")
@limiter.limit("10 per minute")
def passkey_login_verify():
    challenge_text = session.pop("passkey_login_challenge", None)
    user_id = session.pop("passkey_login_user_id", None)
    body = request.get_json(silent=True) or {}
    credential = body.get("credential")
    if not challenge_text or not user_id or not isinstance(credential, dict):
        return jsonify(error="La solicitud biométrica venció. Vuelve a intentarlo."), 400
    credential_id = str(credential.get("rawId") or credential.get("id") or "")
    passkey = UserPasskey.query.filter_by(user_id=user_id, credential_id=credential_id).first()
    user = db.session.get(User, user_id)
    if not passkey or not user or not user.active or user.must_change_password:
        return jsonify(error="No se pudo validar la credencial para esta cuenta."), 401
    try:
        rp_id, origin = webauthn_settings()
        verification = verify_authentication_response(
            credential=credential,
            expected_challenge=base64url_to_bytes(challenge_text),
            expected_rp_id=rp_id, expected_origin=origin,
            credential_public_key=base64url_to_bytes(passkey.public_key),
            credential_current_sign_count=int(passkey.sign_count or 0),
            require_user_verification=True,
        )
        passkey.sign_count = int(verification.new_sign_count or 0)
        db.session.commit()
        return jsonify(establish_user_session(user))
    except Exception:
        db.session.rollback()
        app.logger.warning("Falló autenticación WebAuthn", exc_info=True)
        return jsonify(error="No se pudo verificar la huella, rostro o passkey. Usa contraseña o vuelve a intentarlo."), 401


@bp.post("/api/register")
@limiter.limit("5 per hour")
def register():
    if not registration_open():
        return jsonify(error="El registro no está disponible por ahora."), 403
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    email = str(body.get("email", "")).strip().lower()
    password = str(body.get("password", ""))
    if body.get("accept_terms") is not True:
        return jsonify(error="Debes aceptar los términos y la política de privacidad para crear tu cuenta."), 400
    if len(username) < 3 or len(username) > 40 or not all(c.isalnum() or c in "._-" for c in username):
        return jsonify(error="El usuario debe tener entre 3 y 40 caracteres: letras, números, punto, guion o guion bajo."), 400
    if len(email) > 254 or not EMAIL_RE.match(email):
        return jsonify(error="Escribe un correo válido."), 400
    if password_problem(password, username):
        return jsonify(error=password_problem(password, username)), 400
    if User.query.filter_by(username=username).first():
        return jsonify(error="Ese usuario ya existe. Elige otro."), 409
    if User.query.filter(db.func.lower(User.email) == email).first():
        return jsonify(error="Ya existe una cuenta con ese correo."), 409
    source = clean_source(body.get("source"), request.headers.get("User-Agent", ""))
    user = User(username=username, email=email, password_hash=generate_password_hash(password), role="member", must_change_password=False, signup_source=source)
    db.session.add(user)
    db.session.commit()
    record = ensure_user_state(user.id)
    country = clean_country(body.get("country"))
    record.data = {**copy.deepcopy(DEFAULT_SHARED_DATA), "country": country, "exchangeRate": COUNTRIES[country]["rate"]}
    # Registro "Crea la cuenta de tu negocio": la cuenta nace con el modo Negocio activo.
    business_name = str(body.get("business_name") or "").strip()[:80]
    if business_name:
        btype = body.get("business_type") if body.get("business_type") in BIZ_TYPES else "comercio"
        prof = body.get("profession") if btype == "profesional" and body.get("profession") in BIZ_PROFESSIONS else ""
        record.data["business"] = {"enabled": True, "name": business_name, "phone": "", "type": btype, "profession": prof}
    db.session.commit()
    audit("account_registered", f"Cuenta creada ({country}, origen {source or 'directo'}{', negocio' if business_name else ''}); aceptó términos, privacidad y cookies (versión 2026-10-23)", user.id)
    core.send_verification(user)
    return start_session(user)


@bp.post("/api/verify-email")
@limiter.limit("20 per hour")
def verify_email():
    token = str((request.get_json(silent=True) or {}).get("token", ""))
    row = EmailVerification.query.filter_by(token_hash=hashlib.sha256(token.encode()).hexdigest()).first()
    now = datetime.now(timezone.utc)
    expires = (row.expires_at.replace(tzinfo=timezone.utc) if row.expires_at.tzinfo is None else row.expires_at) if row else None
    user = db.session.get(User, row.user_id) if row else None
    if not row or row.used_at is not None or expires < now or not user:
        return jsonify(error="El enlace venció o ya se usó. Pide uno nuevo desde la app."), 400
    user.email_verified_at = now
    row.used_at = now
    db.session.commit()
    audit("email_verified", "Correo confirmado", user.id)
    return jsonify(ok=True)


@bp.post("/api/mobile/login")
@limiter.limit("10 per minute")
@limiter.limit("30 per hour", key_func=_login_name_key)
def mobile_login():
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    user = User.query.filter_by(username=username, active=True).first()
    if not verify_login(user, str(body.get("password", ""))):
        audit("login_failed", f"App móvil. Usuario: {user.username}" if user else "App móvil. Usuario inexistente", user_id=user.id if user else None)
        return jsonify(error="Usuario o contraseña incorrectos."), 401
    if user.must_change_password:
        return jsonify(error="Cambia tu contraseña desde la versión web antes de usar la app."), 428
    token, now = secrets.token_urlsafe(48), datetime.now(timezone.utc)
    db.session.add(DeviceToken(user_id=user.id, token_hash=hashlib.sha256(token.encode()).hexdigest(), label=str(body.get("device") or "Android")[:80],
                               last_used_at=now, expires_at=now + timedelta(days=90)))
    db.session.commit()
    audit("login_succeeded", "Inicio de sesión desde la app móvil", user.id)
    core.notify_security(user, "Nuevo inicio de sesión en su cuenta – Quincena VE",
                    f"Se inició sesión en su cuenta desde la app de Android ({str(body.get('device') or 'dispositivo').strip()[:60]}).")
    return jsonify(ok=True, token=token, user=user_payload(user))


@bp.post("/api/mobile/logout")
@require_auth()
def mobile_logout():
    header = request.headers.get("Authorization", "")
    row = DeviceToken.query.filter_by(token_hash=hashlib.sha256(header[7:].strip().encode()).hexdigest()).first() if header.startswith("Bearer ") else None
    if row is not None:
        row.revoked_at = datetime.now(timezone.utc)
        db.session.commit()
    return jsonify(ok=True)


@bp.post("/api/forgot-password")
@limiter.limit("5 per hour")
@limiter.limit("3 per hour", key_func=_forgot_email_key)  # nadie puede llenar de correos la bandeja de otra persona
def forgot_password():
    email = str((request.get_json(silent=True) or {}).get("email", "")).strip().lower()
    user = User.query.filter(db.func.lower(User.email) == email, User.active.is_(True), User.email_verified_at.isnot(None)).first() if EMAIL_RE.match(email) else None
    if user:
        PasswordReset.query.filter_by(user_id=user.id, used_at=None).delete()
        token = secrets.token_urlsafe(32)
        db.session.add(PasswordReset(user_id=user.id, token_hash=hashlib.sha256(token.encode()).hexdigest(), expires_at=datetime.now(timezone.utc) + timedelta(minutes=60)))
        db.session.commit()
        origin = public_origin()
        if origin and mail_configured():
            text_body, html_body = reset_email(user.username, f"{origin}/app#reset={token}")
            send_mail_async(email, "Restablecimiento de contraseña – Quincena VE", text_body, html_body)
            audit("password_reset_requested", "Enlace solicitado", user.id)
        else:
            app.logger.error("Recuperación de clave no enviada: %s.", mail_problem() or "sin dirección pública")
            audit("password_reset_requested", "No se pudo enviar: el correo no está configurado", user.id)
    return jsonify(ok=True)  # misma respuesta exista o no el correo


@bp.post("/api/reset-password")
@limiter.limit("10 per hour")
def reset_password():
    body = request.get_json(silent=True) or {}
    new_password = str(body.get("new_password", ""))
    if password_problem(new_password):
        return jsonify(error=password_problem(new_password)), 400
    row = PasswordReset.query.filter_by(token_hash=hashlib.sha256(str(body.get("token", "")).encode()).hexdigest()).first()
    now = datetime.now(timezone.utc)
    expires = row.expires_at.replace(tzinfo=timezone.utc) if row and row.expires_at.tzinfo is None else (row.expires_at if row else None)
    user = db.session.get(User, row.user_id) if row else None
    if not row or row.used_at is not None or expires < now or not user or not user.active:
        return jsonify(error="El enlace venció o ya se usó. Pide uno nuevo."), 400
    user.password_hash = generate_password_hash(new_password)
    user.must_change_password = False
    row.used_at = now
    DeviceToken.query.filter_by(user_id=user.id, revoked_at=None).update({"revoked_at": now})
    db.session.commit()
    session.clear()
    audit("password_reset_done", "Contraseña restablecida por correo", user.id)
    core.notify_security(user, "Su contraseña fue restablecida – Quincena VE", "La contraseña de su cuenta fue restablecida con el enlace enviado a su correo. Se cerró la sesión en todos sus dispositivos.")
    return jsonify(ok=True)


@bp.post("/api/logout")
@require_auth(allow_password_change=True)
def logout():
    audit("logout", "Sesión cerrada", g.user.id)
    session.clear()
    return jsonify(ok=True)
