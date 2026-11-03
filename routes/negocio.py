"""Mi Negocio: vendedores del negocio (Negocio Plus) y el modo vendedor que solo registra ventas.

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import secrets
from datetime import datetime, timezone

from flask import Blueprint, g, jsonify, request
from werkzeug.security import generate_password_hash

from app import (  # noqa: E402
    DeviceToken, MAX_SELLERS, SharedState, User, _seller_json, _seller_owner,
    app, audit, business_rate, db, ensure_user_state, is_plus,
    limiter, normalize_shared_data, password_problem, require_auth,
)

bp = Blueprint("negocio", __name__)


@bp.get("/api/biz/sellers")
@require_auth()
def list_sellers():
    """Vendedores del negocio (Negocio Plus): cuentas que solo pueden registrar ventas."""
    if g.user.owner_id:
        return jsonify(error="Esta opción es solo para el dueño del negocio."), 403
    sellers = User.query.filter_by(owner_id=g.user.id).order_by(User.created_at.asc()).all()
    return jsonify(sellers=[_seller_json(u) for u in sellers], plus=is_plus(g.user), max=MAX_SELLERS)


@bp.post("/api/biz/sellers")
@limiter.limit("20 per hour")
@require_auth()
def create_seller():
    if g.user.owner_id:
        return jsonify(error="Esta opción es solo para el dueño del negocio."), 403
    if not is_plus(g.user):
        return jsonify(error="Agregar vendedores es parte de Negocio Plus.", code="plus_required"), 402
    if User.query.filter_by(owner_id=g.user.id, active=True).count() >= MAX_SELLERS:
        return jsonify(error=f"Puedes tener hasta {MAX_SELLERS} vendedores activos."), 409
    body = request.get_json(silent=True) or {}
    username = str(body.get("username", "")).strip().lower()
    password = str(body.get("password", ""))
    if len(username) < 3 or len(username) > 40 or not all(c.isalnum() or c in "._-" for c in username):
        return jsonify(error="El usuario debe tener entre 3 y 40 caracteres: letras, números, punto, guion o guion bajo."), 400
    if password_problem(password, username):
        return jsonify(error=password_problem(password, username)), 400
    if User.query.filter_by(username=username).first():
        return jsonify(error="Ese usuario ya existe. Elige otro."), 409
    seller = User(username=username, password_hash=generate_password_hash(password), role="member", must_change_password=False,
                  owner_id=g.user.id, signup_source="vendedor")
    db.session.add(seller)
    db.session.commit()
    audit("seller_created", f"Vendedor {username}", g.user.id)
    return jsonify(ok=True, seller=_seller_json(seller))


@bp.patch("/api/biz/sellers/<int:seller_id>")
@require_auth()
def update_seller(seller_id: int):
    seller = db.session.get(User, seller_id)
    if seller is None or seller.owner_id != g.user.id:
        return jsonify(error="Vendedor no encontrado."), 404
    body = request.get_json(silent=True) or {}
    if "password" in body:
        password = str(body.get("password") or "")
        if password_problem(password, seller.username):
            return jsonify(error=password_problem(password, seller.username)), 400
        seller.password_hash = generate_password_hash(password)
        DeviceToken.query.filter_by(user_id=seller.id, revoked_at=None).update({"revoked_at": datetime.now(timezone.utc)})
    if "active" in body:
        if not isinstance(body["active"], bool):
            return jsonify(error="active debe ser true o false."), 400
        if body["active"] and not seller.active and User.query.filter_by(owner_id=g.user.id, active=True).count() >= MAX_SELLERS:
            return jsonify(error=f"Puedes tener hasta {MAX_SELLERS} vendedores activos."), 409
        seller.active = body["active"]
        if not seller.active:
            DeviceToken.query.filter_by(user_id=seller.id, revoked_at=None).update({"revoked_at": datetime.now(timezone.utc)})
    db.session.commit()
    audit("seller_updated", f"Vendedor {seller.username}", g.user.id)
    return jsonify(ok=True, seller=_seller_json(seller))


@bp.get("/api/biz/seller")
@require_auth()
def seller_home():
    owner, error = _seller_owner()
    if error:
        return error
    data = normalize_shared_data(ensure_user_state(owner.id).data)
    mine = [x for x in data["sales"] if x.get("seller") == g.user.username][:200]
    products = [{"id": p["id"], "name": p["name"], "price": p["price"], "code": p["code"]} for p in data["products"] if not p["archived"]]
    return jsonify(business=data["business"]["name"] or owner.username, country=data["country"], rate=business_rate(data),
                   rateKind=data["business"]["rateKind"],
                   products=products, sales=mine)


@bp.post("/api/biz/seller/sale")
@limiter.limit("300 per hour")
@require_auth()
def seller_sale():
    """El vendedor registra una venta: se agrega a los datos del dueño (que la ve al sincronizar)."""
    owner, error = _seller_owner()
    if error:
        return error
    body = request.get_json(silent=True) or {}
    sale = {**{k: body.get(k) for k in ("amount", "currency", "method", "client", "phone", "credit", "dueDate", "note", "items", "concept")},
            "id": "v" + secrets.token_hex(8), "date": datetime.now(timezone.utc).isoformat(), "seller": g.user.username}
    record = db.session.query(SharedState).filter_by(user_id=owner.id).with_for_update().first()
    if record is None:
        db.session.rollback()
        record = ensure_user_state(owner.id)
        record = db.session.query(SharedState).filter_by(user_id=owner.id).with_for_update().first()
    data = normalize_shared_data(record.data)
    if sale.get("currency") in ("BS", "VES"):
        sale["rateUsed"] = business_rate(data)
    cleaned = normalize_shared_data({**data, "sales": [sale]})["sales"]
    if not cleaned or cleaned[0]["amount"] <= 0:
        db.session.rollback()
        return jsonify(error="Escribe un monto mayor a cero."), 400
    data["sales"] = [cleaned[0]] + data["sales"]
    record.data = normalize_shared_data(data)
    record.revision += 1
    record.updated_at = datetime.now(timezone.utc)
    record.updated_by = g.user.id
    db.session.commit()
    audit("seller_sale", f"Venta de {g.user.username}", g.user.id)
    return jsonify(ok=True, sale=cleaned[0])
