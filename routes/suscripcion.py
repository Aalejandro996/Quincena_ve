"""Suscripción de Quincena VE (Negocio Plus) pagada desde la app con Ávila Pay.

La persona elige su plan y su forma de pago; la Consola Maestra de Gigadevs crea el cobro en Ávila Pay y devuelve el enlace
seguro de pago. Al volver (o cuando Ávila Pay avisa), la app consulta la orden: si está pagada, recibe la suscripción firmada,
la verifica y activa Negocio Plus hasta su vencimiento. El administrador puede seguir activando Plus a mano."""
import os
import re
import time
from datetime import datetime, timedelta, timezone

from flask import Blueprint, g, jsonify, request

import gigadevs as GD
from app import (  # noqa: E402
    SubscriptionOrder, app, apply_subscription, audit, db, fetch_rate, is_plus, limiter, require_auth,
)

bp = Blueprint("suscripcion", __name__)
_PLANES = {"datos": None, "hasta": 0.0}
RE_DOC = re.compile(r"^[VEJPG]-?\d{6,9}-?\d?$")


def _planes():
    if _PLANES["hasta"] > time.monotonic(): return _PLANES["datos"]
    datos = GD.planes()
    _PLANES.update(datos=datos, hasta=time.monotonic() + 300)
    return datos


def _origen():
    return (os.getenv("APP_PUBLIC_ORIGIN") or request.host_url).rstrip("/")


@bp.get("/api/subscription")
@require_auth()
def estado():
    u = g.user
    resp = {"active": is_plus(u), "plan_until": u.plan_until.isoformat() if u.plan_until else None, "online": GD.configurado(),
            "email_verified": bool(u.email and u.email_verified_at), "seller": bool(u.owner_id), "plans": [], "methods": {"bolivares": [], "dolares": []},
            "pending": [o.ref for o in SubscriptionOrder.query.filter_by(user_id=u.id, status="Pendiente").all()]}
    if GD.configurado() and not u.owner_id:
        try:
            d = _planes(); resp.update(plans=d.get("planes") or [], methods=d.get("metodos") or resp["methods"])
        except GD.ErrorGigadevs as e:
            resp["error"] = str(e)
    return jsonify(resp)


@bp.post("/api/subscription/checkout")
@require_auth()
@limiter.limit("10 per hour")
def pagar():
    u = g.user
    if u.owner_id: return jsonify(error="Las cuentas de vendedor no compran la suscripción: la paga el dueño del negocio."), 403
    if not (u.email and u.email_verified_at): return jsonify(error="Primero confirma el correo de tu cuenta."), 400
    d = request.get_json(silent=True) or {}
    plan, periodo, metodo = str(d.get("plan") or "")[:32], str(d.get("period") or "mensual"), str(d.get("method") or "")[:20]
    if periodo not in ("mensual", "anual", "unico"): return jsonify(error="Elige el período."), 400
    doc = str(d.get("document") or "").strip().upper().replace(" ", "")[:15]
    if doc and not RE_DOC.match(doc): return jsonify(error="Escribe tu RIF o cédula como V-12345678-9, o déjalo vacío."), 400
    if doc and re.fullmatch(r"[VEJPG]-?\d{8}-?\d", doc) is None: doc = ""        # la factura necesita RIF completo; sin él, se factura después
    try:   # el contrato de suscripción vigente (aprobado por la abogada) se acepta antes de pagar
        if any(p["tipo"] == "contrato" for p in GD.legal_pendientes(u.username, "responsable")):
            return jsonify(error="Antes de pagar, acepta el contrato de suscripción.", code="legal_required"), 409
    except GD.ErrorGigadevs:
        pass
    datos = {"referencia": u.email, "nombre": u.username, "correo": u.email, "plan": plan, "periodo": periodo, "metodo": metodo,
             "retorno": f"{_origen()}/?suscripcion=1"}
    if doc: datos["documento"] = doc
    try:
        if metodo not in ("zelle", "tarjeta", "billetera"):
            tasa = (fetch_rate("VE") or {}).get("rate")
            if not tasa: return jsonify(error="No tenemos la tasa BCV en este momento. Intenta en unos minutos."), 503
            datos["tasa_bcv"] = str(tasa)
        r = GD.checkout(**datos)
    except GD.ErrorGigadevs as e:
        return jsonify(error=str(e)), 502
    db.session.add(SubscriptionOrder(user_id=u.id, ref=r["ref"], plan=plan))
    db.session.commit()
    audit("subscription_checkout", f"Orden {r['ref']}: {plan} {periodo}, {r['moneda']} {r['monto']}", u.id)
    return jsonify(ok=True, url=r["checkout_url"], ref=r["ref"], amount=r["monto"], currency=r["moneda"])


@bp.post("/api/subscription/refresh")
@require_auth()
@limiter.limit("30 per hour")
def actualizar():
    """Consulta los pagos pendientes de esta cuenta y, si alguno se pagó, activa la suscripción."""
    u = g.user
    limite = datetime.now(timezone.utc) - timedelta(days=3)
    activada, estados = None, {}
    for o in SubscriptionOrder.query.filter_by(user_id=u.id, status="Pendiente").order_by(SubscriptionOrder.id.desc()).limit(5).all():
        creada = o.created_at.replace(tzinfo=timezone.utc) if o.created_at.tzinfo is None else o.created_at
        try:
            r = GD.orden(o.ref)
        except GD.ErrorGigadevs as e:
            estados[o.ref] = str(e); continue
        estados[o.ref] = r.get("estado")
        if r.get("estado") == "Pagada" and r.get("token"):
            claims, code = apply_subscription(u, r["token"])
            if claims:
                o.status, activada = "Pagada", claims
                audit("subscription_paid", f"Orden {o.ref}: Negocio Plus hasta {datetime.fromtimestamp(int(claims['exp']), timezone.utc).date()}", u.id)
            else:
                app.logger.warning("Suscripción %s pagada pero no válida para la cuenta: %s", o.ref, code)
        elif r.get("estado") == "Anulada" or creada < limite:
            o.status = "Anulada"
    db.session.commit()
    return jsonify(ok=True, active=is_plus(u), plan_until=u.plan_until.isoformat() if u.plan_until else None,
                   activated=bool(activada), orders=estados)
