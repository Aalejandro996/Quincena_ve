"""Uso real de Quincena VE: quién usa la app, quién no, con qué versión, y recordatorios para quienes la dejaron.

Estados de cada cuenta personal (no cuenta vendedores ni administradores):
- «activa»: la usó en los últimos ACTIVE_DAYS días (5).
- «nunca_uso»: se registró y no anotó nada (ni ingreso, ni gasto, ni deuda, ni venta) desde ese día.
- «inactiva»: la usó alguna vez, pero hace más de ACTIVE_DAYS días que no entra.
Aparte se cuenta quién tiene la app Android vieja: esas versiones ya no funcionan (el servidor responde «actualiza»,
ver require_current_android_app en app.py) y el recordatorio le dice que la actualice.

Los recordatorios salen por correo (Brevo) y por aviso del navegador si la persona lo activó. A una misma cuenta no se
le escribe más de una vez cada NUDGE_EVERY_DAYS días (7)."""
import json
import os
from datetime import datetime, timedelta, timezone
from html import escape

from flask import has_request_context

from app import (APK_PATH, DeviceToken, PushSubscription, SharedState, User, _aware, android_version, app, audit, db, email_layout,
                 mail_configured, public_origin, send_mail)

ACTIVE_DAYS = int(os.getenv("ACTIVE_DAYS", "5") or 5)
NUDGE_EVERY_DAYS = int(os.getenv("NUDGE_EVERY_DAYS", "7") or 7)
DATA_KEYS = ("expenses", "incomeSources", "incomes", "debts", "recurring", "receivables", "sales", "accounts")


def _has_data(state) -> bool:
    data = state.data if state is not None and isinstance(state.data, dict) else {}
    return any(len(data.get(k) or []) for k in DATA_KEYS)


def classify(now: datetime | None = None) -> dict:
    """Cuenta por cuenta: estado, última vez que la usó, desde dónde y si su app Android está vieja."""
    now = now or datetime.now(timezone.utc)
    latest = android_version()["versionCode"] if APK_PATH.is_file() else 0  # sin APK publicado no hay «versión vieja»
    states = {s.user_id: s for s in SharedState.query.filter(SharedState.user_id.isnot(None)).all()}
    devices = {}
    for t in DeviceToken.query.filter(DeviceToken.revoked_at.is_(None)).all():
        if t.last_used_at and (t.user_id not in devices or _aware(t.last_used_at) > devices[t.user_id]):
            devices[t.user_id] = _aware(t.last_used_at)
    push = {p.user_id for p in PushSubscription.query.all()}
    rows = []
    for u in User.query.filter(User.role == "member", User.owner_id.is_(None)).all():
        st = states.get(u.id)
        seen = [x for x in (_aware(u.last_seen_at) if u.last_seen_at else None, devices.get(u.id),
                            _aware(st.updated_at) if st is not None and st.updated_at and _has_data(st) else None) if x]
        last = max(seen) if seen else None
        created = _aware(u.created_at) or now
        has = _has_data(st)
        if not has:
            status = "nunca_uso"
        elif last and now - last <= timedelta(days=ACTIVE_DAYS):
            status = "activa"
        else:
            status = "inactiva"
        old_app = bool(u.last_platform == "android" and latest and (u.app_version or 0) < latest)
        days_idle = (now - (last or created)).days
        rows.append({"id": u.id, "username": u.username, "email": u.email, "verified": bool(u.email_verified_at), "active": u.active,
                     "status": status, "created_at": created.isoformat(), "last_seen": last.isoformat() if last else None, "days_idle": days_idle,
                     "platform": u.last_platform, "app_version": u.app_version, "old_app": old_app, "push": u.id in push,
                     "nudged_at": _aware(u.nudge_at).isoformat() if u.nudge_at else None,
                     # Le toca recordatorio: no lo usó desde el día que se registró (y ya pasó ese día) o lleva más de 5 días sin entrar.
                     "due": bool(u.active and (u.email or u.id in push) and (status == "inactiva" or (status == "nunca_uso" and created.date() < now.date()))
                                 and (not u.nudge_at or now - _aware(u.nudge_at) >= timedelta(days=NUDGE_EVERY_DAYS)))})
    count = lambda f: sum(1 for r in rows if f(r))
    return {
        "summary": {"total": len(rows), "activa": count(lambda r: r["status"] == "activa"), "inactiva": count(lambda r: r["status"] == "inactiva"),
                    "nunca_uso": count(lambda r: r["status"] == "nunca_uso"), "android": count(lambda r: r["platform"] == "android"),
                    "web": count(lambda r: r["platform"] == "web"), "old_app": count(lambda r: r["old_app"]), "due": count(lambda r: r["due"]),
                    "latest_version": latest, "active_days": ACTIVE_DAYS, "nudge_every_days": NUDGE_EVERY_DAYS},
        "users": sorted(rows, key=lambda r: (r["status"] != "inactiva", r["status"] != "nunca_uso", -r["days_idle"])),
    }


def message(row: dict, origin: str) -> tuple[str, str, str, str]:
    """(asunto, texto, html, aviso corto) para una cuenta, según por qué le toca el recordatorio."""
    name = row["username"]
    url = f"{origin}/app" if origin else "/app"
    if row["status"] == "nunca_uso":
        subject = "¿Arrancamos con tu quincena? 🪙"
        lines = [f"¡Epa, {name}! Te registraste en Quincena VE, pero todavía no has anotado nada.",
                 "Empezar toma 2 minutos: agrega lo que cobras, tus gastos fijos y tu primer gasto. Quinci te dice al momento cuánto te queda hasta el próximo cobro, en dólares y en bolívares."]
        short = "Te registraste pero no has anotado nada. Empieza en 2 minutos y mira cuánto te queda hasta el cobro."
    else:
        subject = "Tu quincena te está esperando 🪙"
        lines = [f"¡Epa, {name}! Hace {row['days_idle']} días que no pasas por Quincena VE.",
                 "Ponte al día con lo que gastaste estos días: así sabes cuánto te queda de verdad y Quinci te avisa antes de que se te pase un pago."]
        short = f"Hace {row['days_idle']} días que no anotas. Ponte al día y mira cuánto te queda."
    if row["old_app"]:
        lines.append("Ojo: tu app de Android está desactualizada y ya no funciona. Ábrela y toca «Actualizar», o descarga la nueva versión desde la página.")
        short += " Tu app necesita actualizarse."
    text = "\n\n".join(lines + [f"Abrir Quincena VE: {url}", "Si ya no quieres usar la app, puedes ignorar este correo."])
    body = "".join(f'<p style="margin:0 0 14px;font-size:16px;line-height:1.55">{escape(x)}</p>' for x in lines)
    body += (f'<p style="margin:22px 0"><a href="{escape(url, quote=True)}" style="background:#0B6B4D;color:#ffffff;text-decoration:none;'
             'font-weight:bold;padding:13px 22px;border-radius:999px;display:inline-block">Abrir Quincena VE</a></p>'
             '<p style="margin:0;font-size:13px;color:#46564E">Si ya no quieres usar la app, puedes ignorar este correo.</p>')
    return subject, text, email_layout(subject, body, short), short


def send_reminders(dry_run: bool = True, admin_id: int | None = None, limit: int = 200) -> dict:
    """Envía el recordatorio a quienes les toca. Con dry_run solo dice a quiénes les llegaría."""
    report = classify()
    due = [r for r in report["users"] if r["due"]][:limit]
    if dry_run:
        return {"dry_run": True, "count": len(due), "users": [{"username": r["username"], "status": r["status"], "days_idle": r["days_idle"],
                                                              "email": bool(r["email"]), "push": r["push"], "old_app": r["old_app"]} for r in due]}
    origin = public_origin() if has_request_context() else os.getenv("APP_PUBLIC_ORIGIN", "").rstrip("/")
    vapid_key, vapid_sub = os.getenv("VAPID_PRIVATE_KEY", "").strip(), os.getenv("VAPID_CLAIMS_SUB", "").strip()
    mail_ok = mail_configured()
    sent = mailed = pushed = 0
    for r in due:
        subject, text, html, short = message(r, origin)
        ok = False
        if r["email"] and mail_ok:
            ok = send_mail(r["email"], subject, text, html) or ok
            mailed += 1 if ok else 0
        if r["push"] and vapid_key and vapid_sub:
            try:
                from pywebpush import webpush
                for sub in PushSubscription.query.filter_by(user_id=r["id"]).all():
                    try:
                        webpush(subscription_info=sub.subscription_json, data=json.dumps({"title": "Quincena VE", "body": short, "url": "/app", "tag": "volver"}),
                                vapid_private_key=vapid_key, vapid_claims={"sub": vapid_sub}, ttl=86400)
                        ok, pushed = True, pushed + 1
                    except Exception:
                        pass
            except Exception:
                pass
        if ok:
            user = db.session.get(User, r["id"])
            if user is not None:
                user.nudge_at = datetime.now(timezone.utc)
                db.session.commit()
            sent += 1
    audit("usage_reminders_sent", f"Recordatorios por no usar la app: {sent} de {len(due)} (correo {mailed}, aviso {pushed})", admin_id)
    return {"dry_run": False, "count": len(due), "sent": sent, "mailed": mailed, "pushed": pushed, "mail_configured": mail_ok}
