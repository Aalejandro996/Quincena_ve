"""Administración: panel /admin, usuarios, registros, cobros de Negocio Plus con factura, Binance y sugerencias.

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import math
import os
import re
import secrets
import string
from datetime import datetime, timedelta, timezone
from email.utils import parseaddr

from flask import Blueprint, abort, g, jsonify, redirect, request, send_from_directory
from werkzeug.security import generate_password_hash

import app as core
from app import (  # noqa: E402
    AppSetting, AuditEvent, BILLING_DEFAULTS, DeviceToken, EMAIL_RE, EmailVerification,
    FOREIGN_METHODS, PLUS_METHODS, PasswordReset, PlusPayment, PushSubscription, ReminderDelivery,
    SharedState, Suggestion, User, UserPasskey, VE_TZ, _aware,
    _rate_or_none, app, audit, billing_settings, binance_client, binance_incoming,
    brevo_send, current_user, db, doc_number, email_layout, invoice_pdf,
    is_plus, limiter, mail_configured, mail_problem, payment_json, payment_totals,
    require_auth, send_invoice,
)

bp = Blueprint("admin", __name__)


@bp.get("/api/users")
@require_auth(admin=True)
def list_users():
    users = User.query.order_by(User.created_at.asc()).all()
    return jsonify(users=[{"id": u.id, "username": u.username, "role": u.role, "active": u.active, "must_change_password": u.must_change_password, "created_at": u.created_at.isoformat() if u.created_at else None, "signup_source": u.signup_source,
                         "plan": "plus" if is_plus(u) else "free", "plan_until": u.plan_until.isoformat() if u.plan_until else None, "owner_id": u.owner_id} for u in users])


@bp.get("/api/signups")
@require_auth(admin=True)
def signups_by_source():
    """Cuentas nuevas por origen (publicación o anuncio), para saber qué funciona antes de pagar publicidad."""
    since = datetime.now(timezone.utc) - timedelta(days=7)
    rows = {}
    for source, created_at in db.session.query(User.signup_source, User.created_at).filter(User.role == "member").all():
        row = rows.setdefault(source or "directo", {"source": source or "directo", "total": 0, "last_7_days": 0})
        row["total"] += 1
        if created_at and (created_at if created_at.tzinfo else created_at.replace(tzinfo=timezone.utc)) >= since:
            row["last_7_days"] += 1
    by_source = sorted(rows.values(), key=lambda r: (-r["total"], r["source"]))
    return jsonify(total=sum(r["total"] for r in by_source), by_source=by_source)


@bp.post("/api/mail-test")
@require_auth(admin=True)
@limiter.limit("10 per hour")
def mail_test():
    """Solo administrador: envía un correo de prueba y devuelve la configuración detectada y la respuesta de Brevo."""
    to = str((request.get_json(silent=True) or {}).get("to") or g.user.email or "").strip().lower()
    if not EMAIL_RE.match(to):
        return jsonify(error="Indica un correo válido en «to»."), 400
    name, addr = parseaddr(os.getenv("MAIL_FROM", ""))
    api_key = os.getenv("BREVO_API_KEY", "").strip()
    config = {"brevo_api_key": bool(api_key), "smtp": bool(os.getenv("SMTP_USER") and os.getenv("SMTP_PASSWORD")),
              "mail_from": f"{name} <{addr}>" if addr else None, "app_public_origin": os.getenv("APP_PUBLIC_ORIGIN", "").rstrip("/") or None,
              "problem": mail_problem()}
    subject, body = "Correo de prueba – Quincena VE", "Si recibe este mensaje, el envío de correos de Quincena VE funciona."
    page = email_layout(subject, '<p style="margin:0 0 16px"><b>¡Funciona!</b></p><p style="margin:0">Si recibe este mensaje, '
                        'el envío de correos de Quincena VE está bien configurado.</p>', body)
    if api_key:
        sent, detail = brevo_send(api_key, to, subject, body, page)
    else:
        sent = core.send_mail(to, subject, body, page)
        detail = "Enviado por SMTP." if sent else "No se pudo enviar por SMTP (revisa SMTP_USER y SMTP_PASSWORD)."
    if not sent:
        app.logger.error("Correo de prueba no enviado: %s", detail)
    audit("mail_test", f"Correo de prueba {'enviado' if sent else 'fallido'}", g.user.id)
    return jsonify(sent=sent, detail=detail, config=config), (200 if sent else 502)


@bp.post("/api/users")
@require_auth(admin=True)
def create_user():
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    role = str(body.get("role", "member"))
    if len(username) < 3 or len(username) > 40 or not all(c.isalnum() or c in "._-" for c in username):
        return jsonify(error="El usuario debe tener entre 3 y 40 caracteres: letras, números, punto, guion o guion bajo."), 400
    if role not in {"admin", "member"}:
        return jsonify(error="Rol no válido."), 400
    if User.query.filter_by(username=username).first():
        return jsonify(error="Ese nombre de usuario ya existe."), 409
    alphabet = string.ascii_letters + string.digits + "!@#$%_-"
    temporary_password = "".join(secrets.choice(alphabet) for _ in range(18))
    user = User(username=username, password_hash=generate_password_hash(temporary_password), role=role, must_change_password=True, active=True)
    db.session.add(user)
    db.session.commit()
    audit("user_created", f"Cuenta {username} ({role}) creada", g.user.id)
    return jsonify(ok=True, user={"id": user.id, "username": user.username, "role": user.role}, temporary_password=temporary_password), 201


@bp.patch("/api/users/<int:user_id>")
@require_auth(admin=True)
def change_user_status(user_id: int):
    body = request.get_json(silent=True) or {}
    user = db.session.get(User, user_id)
    if not user:
        return jsonify(error="Usuario no encontrado."), 404
    if "plan" in body:
        plan = body.get("plan")
        if plan not in ("free", "plus"):
            return jsonify(error="El plan debe ser free o plus."), 400
        try:
            days = int(body.get("days") or 0)
        except (TypeError, ValueError):
            return jsonify(error="days debe ser un número de días."), 400
        if days < 0 or days > 3660:
            return jsonify(error="days debe estar entre 0 y 3660 (0 = sin vencimiento)."), 400
        user.plan = plan
        user.plan_until = datetime.now(timezone.utc) + timedelta(days=days) if plan == "plus" and days else None
        db.session.commit()
        audit("plan_changed", f"Cuenta {user.username}: {plan}" + (f" por {days} días" if plan == "plus" and days else ""), g.user.id)
        if "active" not in body:
            return jsonify(ok=True, plan=plan, plan_until=user.plan_until.isoformat() if user.plan_until else None)
    active = body.get("active")
    if not isinstance(active, bool):
        return jsonify(error="Se requiere el estado active true/false."), 400
    if user.id == g.user.id and not active:
        return jsonify(error="No puedes desactivar tu propia cuenta."), 400
    if user.role == "admin" and user.active and not active:
        other_admin = User.query.filter_by(role="admin", active=True).filter(User.id != user.id).count()
        if other_admin == 0:
            return jsonify(error="No puedes desactivar al último administrador."), 400
    user.active = active
    db.session.commit()
    audit("user_activated" if active else "user_deactivated", f"Cuenta {user.username}", g.user.id)
    return jsonify(ok=True)


@bp.post("/api/users/<int:user_id>/reset-password")
@require_auth(admin=True)
def reset_user_password(user_id: int):
    user = db.session.get(User, user_id)
    if not user or not user.active:
        return jsonify(error="Usuario activo no encontrado."), 404
    alphabet = string.ascii_letters + string.digits + "!@#$%_-"
    temporary_password = "".join(secrets.choice(alphabet) for _ in range(18))
    user.password_hash = generate_password_hash(temporary_password)
    user.must_change_password = True
    DeviceToken.query.filter_by(user_id=user.id, revoked_at=None).update({"revoked_at": datetime.now(timezone.utc)})
    db.session.commit()
    core.notify_security(user, "Un administrador restableció su contraseña – Quincena VE", "Un administrador de Quincena VE restableció la contraseña de su cuenta. Deberá cambiarla al entrar.")
    audit("password_reset_by_admin", f"Contraseña restablecida para {user.username}", g.user.id)
    return jsonify(ok=True, temporary_password=temporary_password)


@bp.get("/api/audit-log")
@require_auth(admin=True)
def audit_log():
    events = AuditEvent.query.order_by(AuditEvent.created_at.desc()).limit(100).all()
    users = {u.id: u.username for u in User.query.filter(User.id.in_([e.user_id for e in events if e.user_id is not None])).all()}
    return jsonify(events=[{"action": e.action, "detail": e.detail, "username": users.get(e.user_id, "Sistema"), "created_at": e.created_at.isoformat() if e.created_at else None} for e in events])


@bp.get("/admin")
def admin_page():
    """Panel de administración: solo en la web y solo para administradores (el resto vuelve a la app)."""
    user = current_user()
    if user is None or user.role != "admin":
        return redirect("/app")
    response = send_from_directory(app.static_folder, "admin.html")
    response.headers["Cache-Control"] = "no-store"
    return response


@bp.get("/api/admin/overview")
@require_auth(admin=True)
def admin_overview():
    now = datetime.now(timezone.utc)
    users = User.query.all()
    states = {s.user_id: s for s in SharedState.query.all()}
    members = [u for u in users if u.role == "member" and not u.owner_id]
    created = lambda u: _aware(u.created_at) or now
    month_start = now.astimezone(VE_TZ).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    payments = PlusPayment.query.filter(PlusPayment.void_at.is_(None)).all()
    month = [p for p in payments if _aware(p.created_at) >= month_start]
    tables, size = [], None
    for model in (User, SharedState, AuditEvent, PlusPayment, Suggestion, DeviceToken, PushSubscription, EmailVerification, PasswordReset, UserPasskey, ReminderDelivery, AppSetting):
        tables.append({"name": model.__tablename__, "rows": db.session.query(model).count()})
    if db.engine.dialect.name == "postgresql":
        try:
            size = db.session.execute(db.text("select pg_database_size(current_database())")).scalar()
            for t in tables:
                t["bytes"] = db.session.execute(db.text("select pg_total_relation_size(:t)"), {"t": f'"{t["name"]}"'}).scalar()
        except Exception:
            db.session.rollback()
    return jsonify(
        users={"total": len(members), "active": sum(u.active for u in members), "verified": sum(bool(u.email_verified_at) for u in members),
               "plus": sum(is_plus(u) for u in members), "sellers": sum(bool(u.owner_id) for u in users), "admins": sum(u.role == "admin" for u in users),
               "business": sum(bool(((states.get(u.id).data if states.get(u.id) else {}) or {}).get("business", {}).get("enabled")) for u in members),
               "new_7": sum(created(u) >= now - timedelta(days=7) for u in members), "new_30": sum(created(u) >= now - timedelta(days=30) for u in members),
               "active_7": sum(bool(states.get(u.id) and _aware(states[u.id].updated_at) >= now - timedelta(days=7)) for u in members)},
        income={"month_usd": round(sum(payment_totals(p)["pay_usd"] for p in month), 2), "month_count": len(month),
                "total_usd": round(sum(payment_totals(p)["pay_usd"] for p in payments), 2), "total_count": len(payments)},
        database={"engine": db.engine.dialect.name, "bytes": size, "tables": tables},
        mail={"configured": mail_configured(), "problem": mail_problem()}, binance={"configured": bool(binance_client())},
        suggestions={"new": Suggestion.query.filter_by(status="nueva").count(), "total": Suggestion.query.count()})


@bp.get("/api/admin/users")
@require_auth(admin=True)
def admin_users():
    states = {s.user_id: s for s in SharedState.query.all()}
    out = []
    for u in User.query.order_by(User.created_at.desc()).all():
        st = states.get(u.id)
        biz = ((st.data if st else {}) or {}).get("business") or {}
        out.append({"id": u.id, "username": u.username, "email": u.email, "verified": bool(u.email_verified_at), "role": u.role, "active": u.active,
                    "plan": "plus" if is_plus(u) else "free", "plan_until": _aware(u.plan_until).isoformat() if u.plan_until else None,
                    "owner_id": u.owner_id, "business": biz.get("name") if biz.get("enabled") else None, "country": ((st.data if st else {}) or {}).get("country"),
                    "created_at": _aware(u.created_at).isoformat() if u.created_at else None, "source": u.signup_source,
                    "last_activity": _aware(st.updated_at).isoformat() if st and st.updated_at else None})
    return jsonify(users=out)


@bp.post("/api/admin/users/<int:user_id>/resend-verification")
@require_auth(admin=True)
@limiter.limit("20 per hour")
def admin_resend_verification(user_id: int):
    user = db.session.get(User, user_id)
    if not user or not user.email:
        return jsonify(error="Esa cuenta no tiene correo."), 404
    if user.email_verified_at:
        return jsonify(error="Ese correo ya está confirmado."), 400
    sent = core.send_verification(user)
    audit("verification_resent_by_admin", f"Correo de confirmación reenviado a {user.username}", g.user.id)
    return jsonify(ok=True, sent=sent)


@bp.get("/api/admin/settings")
@require_auth(admin=True)
def admin_get_settings():
    return jsonify(billing=billing_settings(), binance={"configured": bool(binance_client()), "pay_id": os.getenv("BINANCE_PAY_ID", "")})


@bp.put("/api/admin/settings")
@require_auth(admin=True)
def admin_put_settings():
    body = (request.get_json(silent=True) or {}).get("billing") or {}
    clean = {}
    for key, default in BILLING_DEFAULTS.items():
        value = body.get(key, billing_settings()[key])
        if isinstance(default, (int, float)) and not isinstance(default, bool):
            try:
                value = float(value)
            except (TypeError, ValueError):
                return jsonify(error=f"«{key}» debe ser un número."), 400
            if not math.isfinite(value) or value < 0 or value > (3660 if key == "plus_days" else 100 if key.endswith("_pct") else 100000):
                return jsonify(error=f"«{key}» está fuera de rango."), 400
            value = int(value) if isinstance(default, int) else value
        else:
            value = str(value or "").strip()[:400 if key in ("issuer_address", "footer", "imprenta") else 120]
        clean[key] = value
    clean["series"] = re.sub(r"[^A-Za-z0-9]", "", clean["series"])[:8]
    row = db.session.get(AppSetting, "billing") or AppSetting(key="billing", value={})
    row.value = clean
    db.session.add(row)
    db.session.commit()
    audit("billing_settings_changed", "Datos de facturación actualizados", g.user.id)
    return jsonify(ok=True, billing=clean)


@bp.get("/api/admin/payments")
@require_auth(admin=True)
def admin_payments():
    payments = PlusPayment.query.order_by(PlusPayment.number.desc()).limit(500).all()
    users = {u.id: u.username for u in User.query.filter(User.id.in_([p.user_id for p in payments if p.user_id])).all()}
    return jsonify(payments=[payment_json(p, users) for p in payments])


@bp.post("/api/admin/payments")
@require_auth(admin=True)
def admin_create_payment():
    """Registra un cobro de Negocio Plus: activa (o alarga) el Plus, crea la factura y se la envía por correo al cliente."""
    body = request.get_json(silent=True) or {}
    cfg = billing_settings()
    user = User.query.filter_by(username=str(body.get("username", "")).strip().lower()).first()
    if not user or user.owner_id:
        return jsonify(error="No existe una cuenta con ese usuario (las de vendedor no compran Plus)."), 404
    try:
        amount = float(body.get("amount_usd", cfg["plus_price_usd"]))
        days = int(body.get("days") or cfg["plus_days"])
    except (TypeError, ValueError):
        return jsonify(error="Revisa el monto y los días."), 400
    if not math.isfinite(amount) or amount <= 0 or amount > 100000 or days < 1 or days > 3660:
        return jsonify(error="El monto debe ser mayor que 0 y los días entre 1 y 3660."), 400
    method = str(body.get("method", ""))
    if method not in PLUS_METHODS:
        return jsonify(error="Elige la forma de pago."), 400
    tx = str(body.get("binance_tx") or "").strip()[:80] or None
    if tx and PlusPayment.query.filter_by(binance_tx=tx).first():
        return jsonify(error="Ese pago de Binance ya se usó en otra factura."), 409
    c = body.get("client") if isinstance(body.get("client"), dict) else {}
    client = {"name": str(c.get("name") or user.username).strip()[:120], "doc": str(c.get("doc") or "").strip()[:30],
              "address": str(c.get("address") or "").strip()[:200], "phone": str(c.get("phone") or "").strip()[:30],
              "email": str(c.get("email") or user.email or "").strip().lower()[:254]}
    if client["email"] and not EMAIL_RE.match(client["email"]):
        return jsonify(error="El correo del cliente no es válido."), 400
    now = datetime.now(timezone.utc)
    current_end = _aware(user.plan_until)
    start = current_end if user.plan == "plus" and current_end and current_end > now else now
    rate = _rate_or_none(body.get("rate")) or _rate_or_none(core.fetch_rate("VE").get("rate"))
    number = (db.session.query(db.func.max(PlusPayment.number)).scalar() or 0) + 1
    p = PlusPayment(number=number, series=cfg["series"], user_id=user.id, amount_usd=round(amount, 2), iva_pct=cfg["iva_pct"], igtf_pct=cfg["igtf_pct"] if method in FOREIGN_METHODS else 0,
                    rate=rate, method=method, reference=str(body.get("reference") or "").strip()[:120] or None, binance_tx=tx, days=days,
                    period_from=start, period_to=start + timedelta(days=days), client=client,
                    control_number=str(body.get("control_number") or "").strip()[:40] or None, created_by=g.user.id)
    user.plan, user.plan_until = "plus", p.period_to
    db.session.add(p)
    db.session.commit()
    audit("plus_payment", f"Cobro {doc_number(p)}: {user.username}, $ {amount:.2f}, {days} días", g.user.id)
    sent = send_invoice(p) if body.get("send_email", True) else False
    return jsonify(ok=True, payment=payment_json(p, {user.id: user.username}), emailed=sent), 201


def _payment_or_404(payment_id: int):
    p = db.session.get(PlusPayment, payment_id)
    if not p:
        abort(404)
    return p


@bp.get("/api/admin/payments/<int:payment_id>/pdf")
@require_auth(admin=True)
def admin_payment_pdf(payment_id: int):
    p = _payment_or_404(payment_id)
    response = app.response_class(invoice_pdf(p, billing_settings()), mimetype="application/pdf")
    response.headers["Content-Disposition"] = f'inline; filename="Factura_{doc_number(p)}.pdf"'
    response.headers["Cache-Control"] = "no-store"
    return response


@bp.post("/api/suggestions")
@require_auth()
@limiter.limit("10 per hour")
def send_suggestion():
    body = request.get_json(silent=True) or {}
    text = re.sub(r"\s+", " ", str(body.get("text") or "")).strip()
    if len(text) < 5:
        return jsonify(error="Cuéntanos un poco más (mínimo 5 letras)."), 400
    kind = body.get("kind") if body.get("kind") in ("idea", "problema", "otro") else "idea"
    platform = body.get("platform") if body.get("platform") in ("web", "android") else ("android" if getattr(g, "via_token", False) else "web")
    db.session.add(Suggestion(user_id=g.user.id, text=text[:1000], kind=kind, platform=platform))
    db.session.commit()
    audit("suggestion_sent", f"Sugerencia ({kind}, {platform})", g.user.id)
    return jsonify(ok=True), 201


@bp.get("/api/admin/suggestions")
@require_auth(admin=True)
def admin_suggestions():
    rows = Suggestion.query.order_by(Suggestion.created_at.desc()).limit(500).all()
    users = {u.id: (u.username, u.email) for u in User.query.filter(User.id.in_([r.user_id for r in rows if r.user_id])).all()}
    return jsonify(suggestions=[{"id": r.id, "text": r.text, "kind": r.kind, "platform": r.platform, "status": r.status,
                                 "username": users.get(r.user_id, (None, None))[0], "email": users.get(r.user_id, (None, None))[1],
                                 "created_at": _aware(r.created_at).isoformat()} for r in rows],
                   new=sum(r.status == "nueva" for r in rows))


@bp.patch("/api/admin/suggestions/<int:suggestion_id>")
@require_auth(admin=True)
def admin_suggestion_status(suggestion_id: int):
    row = db.session.get(Suggestion, suggestion_id)
    if not row:
        return jsonify(error="No encontrada."), 404
    status = (request.get_json(silent=True) or {}).get("status")
    if status not in ("nueva", "vista", "hecha"):
        return jsonify(error="Estado no válido."), 400
    row.status = status
    db.session.commit()
    return jsonify(ok=True)


@bp.get("/api/admin/sample-invoice")
@require_auth(admin=True)
def admin_sample_invoice():
    """Factura de muestra con los datos de facturación guardados (no se guarda ni usa número)."""
    cfg, now = billing_settings(), datetime.now(timezone.utc)
    p = PlusPayment(number=0, series=cfg["series"], amount_usd=cfg["plus_price_usd"] or 1, iva_pct=cfg["iva_pct"], igtf_pct=cfg["igtf_pct"],
                    rate=_rate_or_none(core.fetch_rate("VE").get("rate")), method="binance", reference="MUESTRA", days=cfg["plus_days"],
                    period_from=now, period_to=now + timedelta(days=cfg["plus_days"]), created_at=now,
                    client={"name": "Cliente de muestra", "doc": "V-00000000", "address": "Dirección del cliente", "phone": "", "email": ""})
    response = app.response_class(invoice_pdf(p, cfg), mimetype="application/pdf")
    response.headers["Content-Disposition"] = 'inline; filename="Factura_muestra.pdf"'
    response.headers["Cache-Control"] = "no-store"
    return response


@bp.post("/api/admin/payments/<int:payment_id>/email")
@require_auth(admin=True)
@limiter.limit("30 per hour")
def admin_payment_email(payment_id: int):
    p = _payment_or_404(payment_id)
    email = str((request.get_json(silent=True) or {}).get("email") or "").strip().lower()
    if email:
        if not EMAIL_RE.match(email):
            return jsonify(error="Correo no válido."), 400
        p.client = {**(p.client or {}), "email": email}
        db.session.commit()
    if not (p.client or {}).get("email"):
        return jsonify(error="El cobro no tiene correo del cliente."), 400
    sent = send_invoice(p)
    return (jsonify(ok=True), 200) if sent else (jsonify(error="No se pudo enviar el correo. Revisa la configuración de Brevo."), 502)


@bp.post("/api/admin/payments/<int:payment_id>/void")
@require_auth(admin=True)
def admin_payment_void(payment_id: int):
    p = _payment_or_404(payment_id)
    if p.void_at:
        return jsonify(error="Ya está anulada."), 400
    p.void_at = datetime.now(timezone.utc)
    p.void_reason = str((request.get_json(silent=True) or {}).get("reason") or "").strip()[:200] or None
    user = db.session.get(User, p.user_id) if p.user_id else None
    # Si era el último cobro de la cuenta, el Plus vuelve a terminar donde terminaba antes de este cobro.
    if user and user.plan_until and _aware(user.plan_until) == _aware(p.period_to):
        user.plan_until = _aware(p.period_from)
        if user.plan_until <= datetime.now(timezone.utc):
            user.plan, user.plan_until = "free", None
    db.session.commit()
    audit("plus_payment_void", f"Cobro {doc_number(p)} anulado", g.user.id)
    return jsonify(ok=True, payment=payment_json(p))


@bp.get("/api/admin/binance")
@require_auth(admin=True)
@limiter.limit("30 per hour")
def admin_binance():
    if not binance_client():
        return jsonify(error="Binance no está configurado: faltan BINANCE_API_KEY y BINANCE_API_SECRET en Render."), 400
    try:
        days = min(max(int(request.args.get("days", 7)), 1), 30)
        return jsonify(items=binance_incoming(days))
    except ValueError as error:
        return jsonify(error=str(error)), 502
    except Exception as error:
        app.logger.warning("Binance no respondió: %s", type(error).__name__)
        return jsonify(error="No se pudo consultar Binance. Inténtalo en unos minutos."), 502
