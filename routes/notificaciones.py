"""Notificaciones: suscripción a los avisos push del navegador (recordatorios de pagos).

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import os

from flask import Blueprint, g, jsonify, request

from app import (  # noqa: E402
    PushSubscription, app, audit, db, require_auth,
)

bp = Blueprint("notificaciones", __name__)


@bp.get("/api/push/public-key")
def push_public_key():
    key = os.getenv("VAPID_PUBLIC_KEY", "").strip()
    if not key:
        return jsonify(error="Las notificaciones push aún no están configuradas en el servidor."), 503
    return jsonify(public_key=key)


@bp.post("/api/push/subscribe")
@require_auth()
def push_subscribe():
    body = request.get_json(silent=True) or {}
    subscription = body.get("subscription")
    if not isinstance(subscription, dict):
        return jsonify(error="La suscripción de notificaciones no es válida."), 400
    endpoint = str(subscription.get("endpoint") or "")
    keys = subscription.get("keys") if isinstance(subscription.get("keys"), dict) else {}
    if not endpoint.startswith("https://") or len(endpoint) > 2048 or not keys.get("p256dh") or not keys.get("auth"):
        return jsonify(error="El navegador no entregó una suscripción push válida."), 400
    record = PushSubscription.query.filter_by(endpoint=endpoint).first()
    if record is None:
        record = PushSubscription(user_id=g.user.id, endpoint=endpoint, subscription_json=subscription)
        db.session.add(record)
    else:
        record.user_id = g.user.id
        record.subscription_json = subscription
    db.session.commit()
    audit("payment_push_enabled", "Recordatorios push habilitados en un dispositivo", g.user.id)
    return jsonify(ok=True, message="Recordatorios habilitados para este dispositivo.")


@bp.delete("/api/push/subscribe")
@require_auth()
def push_unsubscribe():
    body = request.get_json(silent=True) or {}
    endpoint = str(body.get("endpoint") or "")
    if not endpoint:
        return jsonify(error="Falta el endpoint del dispositivo."), 400
    record = PushSubscription.query.filter_by(endpoint=endpoint, user_id=g.user.id).first()
    if record:
        db.session.delete(record)
        db.session.commit()
    return jsonify(ok=True)
