"""Cuenta de la persona: teléfonos conectados, aceptar términos, confirmar el correo, cambiar la contraseña y eliminar la cuenta.

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import secrets
from datetime import datetime, timezone

from flask import Blueprint, g, jsonify, request, session
from werkzeug.security import check_password_hash, generate_password_hash

import app as core
from app import (  # noqa: E402
    AuditEvent, DeviceToken, EmailVerification, PasswordReset, PushSubscription, ReminderDelivery,
    SharedState, Suggestion, TERMS_VERSION, User, UserPasskey, _aware,
    app, audit, current_bearer_hash, db, limiter, password_problem,
    pw_stamp, require_auth, revoke_other_devices,
)

bp = Blueprint("cuentas", __name__)


@bp.get("/api/devices")
@require_auth()
def list_devices():
    keep = current_bearer_hash()
    now = datetime.now(timezone.utc)
    rows = DeviceToken.query.filter_by(user_id=g.user.id, revoked_at=None).order_by(DeviceToken.last_used_at.desc()).all()
    return jsonify(devices=[{
        "id": r.id, "label": r.label, "this": r.token_hash == keep,
        "last_used_at": r.last_used_at.isoformat() if r.last_used_at else None,
    } for r in rows if _aware(r.expires_at) > now])


@bp.post("/api/devices/revoke-others")
@limiter.limit("10 per hour")
@require_auth()
def revoke_devices():
    n = revoke_other_devices(g.user.id)
    db.session.commit()
    audit("devices_revoked", f"Sesión cerrada en {n} dispositivo(s)", g.user.id)
    if n:
        core.notify_security(g.user, "Se cerraron sesiones en sus dispositivos – Quincena VE", f"Se cerró la sesión de Quincena VE en {n} dispositivo(s) de su cuenta, a petición suya.")
    return jsonify(ok=True, revoked=n)


@bp.post("/api/accept-terms")
@require_auth()
def accept_terms():
    version = str((request.get_json(silent=True) or {}).get("version", ""))[:20]
    if version != TERMS_VERSION:
        return jsonify(error="Versión de términos no válida."), 400
    audit("terms_accepted", f"Aceptó términos, privacidad y cookies (versión {version})", g.user.id)
    return jsonify(ok=True, version=version)


@bp.get("/api/email-status")
@require_auth()
def email_status():
    return jsonify(email=g.user.email, verified=bool(g.user.email_verified_at))


@bp.post("/api/resend-verification")
@limiter.limit("3 per hour")
@require_auth()
def resend_verification():
    if not g.user.email or g.user.email_verified_at:
        return jsonify(error="Tu correo ya está confirmado."), 400
    return jsonify(ok=True, sent=core.send_verification(g.user))


@bp.post("/api/change-password")
@require_auth(allow_password_change=True)
def change_password():
    body = request.get_json(silent=True) or {}
    old_password = str(body.get("old_password", ""))
    new_password = str(body.get("new_password", ""))
    if not check_password_hash(g.user.password_hash, old_password):
        return jsonify(error="La contraseña actual no coincide."), 400
    if password_problem(new_password, g.user.username):
        return jsonify(error=password_problem(new_password, g.user.username)), 400
    if new_password == old_password:
        return jsonify(error="La nueva contraseña debe ser diferente."), 400
    g.user.password_hash = generate_password_hash(new_password)
    g.user.must_change_password = False
    revoke_other_devices(g.user.id)
    db.session.commit()
    if not getattr(g, "via_token", False):
        session["ps"] = pw_stamp(g.user)  # esta sesión sigue abierta; las demás sesiones web quedan cerradas
    session["csrf_token"] = secrets.token_urlsafe(32)
    audit("password_changed", "Contraseña actualizada; se cerró la sesión en los demás dispositivos", g.user.id)
    core.notify_security(g.user, "Su contraseña fue cambiada – Quincena VE", "La contraseña de su cuenta fue cambiada. Se cerró la sesión en sus demás dispositivos.")
    return jsonify(ok=True, csrf_token=session["csrf_token"], user={"username": g.user.username, "role": g.user.role, "must_change_password": False})


@bp.delete("/api/account")
@limiter.limit("5 per hour")
@require_auth()
def delete_account():
    body = request.get_json(silent=True) or {}
    user = g.user
    if not check_password_hash(user.password_hash, str(body.get("password", ""))):
        return jsonify(error="La contraseña no coincide."), 400
    if user.role == "admin" and User.query.filter_by(role="admin", active=True).count() <= 1:
        return jsonify(error="Eres la única persona administradora. Crea otra antes de eliminar esta cuenta."), 409
    uid = user.id
    PasswordReset.query.filter_by(user_id=uid).delete()
    EmailVerification.query.filter_by(user_id=uid).delete()
    DeviceToken.query.filter_by(user_id=uid).delete()
    UserPasskey.query.filter_by(user_id=uid).delete()
    PushSubscription.query.filter_by(user_id=uid).delete()
    User.query.filter_by(owner_id=uid).update({"active": False})
    ReminderDelivery.query.filter_by(user_id=uid).delete()
    Suggestion.query.filter_by(user_id=uid).update({"user_id": None})
    SharedState.query.filter_by(user_id=uid).delete()
    SharedState.query.filter_by(updated_by=uid).update({"updated_by": None})
    AuditEvent.query.filter_by(user_id=uid).update({"user_id": None})
    db.session.delete(user)
    db.session.commit()
    session.clear()
    audit("account_deleted", f"Cuenta eliminada (id {uid})", None)
    return jsonify(ok=True)
