from __future__ import annotations

import json
import os
import secrets
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

# The cron imports Flask models but does not serve requests. Give its temporary
# Flask app a secret if the hosting platform sets RENDER but the cron environment
# does not share the web service's session secret.
os.environ.setdefault("APP_SECRET_KEY", "cron-only-" + secrets.token_urlsafe(48))

from pywebpush import WebPushException, webpush

from app import app, db, User, SharedState, PushSubscription, ReminderDelivery, resolve_planned_due_date

REMINDER_DAYS = {7, 4, 1, 0}


def _cuando(days: int, ve: bool) -> str:
    """Cuándo vence, dicho como se dice en Venezuela (o neutro en otros países)."""
    if days == 0:
        return "Vence hoy, ¡pendiente que no se te pase!" if ve else "Vence hoy."
    if days == 1:
        return "Vence mañana. Ve apartando los reales." if ve else "Vence mañana."
    return f"Vence en {days} días." if ve else f"Vence en {days} día(s)."


def eligible_events(state: dict, today: date):
    """Yield private payment reminder candidates for one user's financial state."""
    ve = str(state.get("country") or "VE").upper() == "VE"
    for item in state.get("plannedDebtPayments", []):
        if not isinstance(item, dict) or item.get("status") != "planned":
            continue
        due_date = resolve_planned_due_date(item, state, today)
        if not due_date:
            continue
        days = (due_date - today).days
        if days in REMINDER_DAYS:
            label = str(item.get("entity") or "Deuda")[:80]
            description = str(item.get("description") or "Pago planificado")[:100]
            body = f"{label}: {description}. " + _cuando(days, ve)
            yield f"debt:{item.get('id')}", due_date.isoformat(), days, "¡Epa! Se acerca un pago" if ve else "Próximo pago planificado", body

    payments = state.get("schoolPayments", [])
    for school in state.get("schoolExpenses", []):
        if not isinstance(school, dict) or not school.get("dueDate"):
            continue
        try:
            due_date = date.fromisoformat(str(school.get("dueDate"))[:10])
        except ValueError:
            continue
        paid = sum(float(p.get("amount", 0) or 0) for p in payments
                   if str(p.get("expenseId")) == str(school.get("id"))
                   and p.get("currency", school.get("currency")) == school.get("currency"))
        if max(0.0, float(school.get("amount", 0) or 0) - paid) <= 0.01:
            continue
        days = (due_date - today).days
        if days not in REMINDER_DAYS:
            continue
        label = str(school.get("student") or "Colegio")[:80]
        description = str(school.get("description") or "Pago escolar")[:100]
        body = f"{label}: {description}. " + _cuando(days, ve)
        yield f"school:{school.get('id')}", due_date.isoformat(), days, "Pendiente con el pago del colegio" if ve else "Recordatorio de pago escolar", body


def main() -> int:
    private_key = os.getenv("VAPID_PRIVATE_KEY", "").strip()
    claims_sub = os.getenv("VAPID_CLAIMS_SUB", "").strip()
    if not private_key or not claims_sub:
        print("Push omitido: configura VAPID_PRIVATE_KEY y VAPID_CLAIMS_SUB en el servicio cron.")
        return 0
    timezone_name = os.getenv("APP_TIMEZONE", "America/Caracas")
    try:
        today = datetime.now(ZoneInfo(timezone_name)).date()
    except Exception:
        print(f"APP_TIMEZONE inválida ({timezone_name}); se usará UTC.")
        today = datetime.now(timezone.utc).date()
    sent = 0
    with app.app_context():
        states = SharedState.query.filter(SharedState.user_id.isnot(None)).all()
        for state_record in states:
            user_id = int(state_record.user_id)
            owner = db.session.get(User, user_id)
            if owner is None or not owner.active:
                continue
            subscriptions = PushSubscription.query.filter_by(user_id=user_id).all()
            if not subscriptions:
                continue
            state = state_record.data if isinstance(state_record.data, dict) else {}
            for payment_key, due_date, days, title, body in eligible_events(state, today):
                exists = ReminderDelivery.query.filter_by(user_id=user_id, payment_key=payment_key, due_date=due_date, days_before=days).first()
                if exists:
                    continue
                delivered = False
                for sub in list(subscriptions):
                    try:
                        webpush(
                            subscription_info=sub.subscription_json,
                            data=json.dumps({"title": title, "body": body, "url": "/app", "tag": f"{payment_key}-{due_date}-{days}"}),
                            vapid_private_key=private_key,
                            vapid_claims={"sub": claims_sub},
                            ttl=86400,
                        )
                        delivered = True
                    except WebPushException as exc:
                        status_code = getattr(getattr(exc, "response", None), "status_code", None)
                        print(f"Push falló: user={user_id}, status={status_code}")
                        if status_code in {404, 410}:
                            db.session.delete(sub)
                    except Exception as exc:
                        print(f"Push falló: user={user_id}, error={type(exc).__name__}")
                if delivered:
                    db.session.add(ReminderDelivery(user_id=user_id, payment_key=payment_key, due_date=due_date, days_before=days))
                    db.session.commit()
                    sent += 1
                else:
                    db.session.commit()
        print(f"Recordatorios entregados a al menos un dispositivo: {sent}; fecha local: {today.isoformat()}.")
    return 0


def inactive_reminders() -> None:
    """Recordatorio diario a quienes no usan la app. Apagado por defecto: se enciende con INACTIVE_REMINDERS_AUTO=1."""
    if os.getenv("INACTIVE_REMINDERS_AUTO", "0") != "1":
        return
    import usage
    with app.app_context():
        r = usage.send_reminders(dry_run=False)
    print(f"Recordatorios por no usar la app: {r.get('sent', 0)} de {r.get('count', 0)}.")


if __name__ == "__main__":
    code = main()
    inactive_reminders()
    raise SystemExit(code)
