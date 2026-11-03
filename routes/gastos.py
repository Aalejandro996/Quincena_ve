"""Gastos y datos de la quincena: leer y guardar el estado (con mezcla de cambios), importar un respaldo, países y tasa del día.

Rutas movidas desde app.py sin cambiar su comportamiento ni sus URLs. El núcleo (configuración, modelos,
seguridad y funciones compartidas) sigue en app.py; lo que las pruebas reemplazan se usa como core.<nombre>."""
import copy
from datetime import datetime, timezone

from flask import Blueprint, g, jsonify, request

import app as core
from app import (  # noqa: E402
    COUNTRIES, DEFAULT_COUNTRY, DEFAULT_SHARED_DATA, SharedState, app, audit,
    clean_country, db, ensure_user_state, limiter, normalize_shared_data, require_auth,
    three_way_merge, validate_school_balances,
)

bp = Blueprint("gastos", __name__)


@bp.get("/api/state")
@require_auth()
def get_state():
    record = ensure_user_state(g.user.id)
    return jsonify(data=normalize_shared_data(record.data), revision=record.revision, updated_at=record.updated_at.isoformat() if record.updated_at else None)


@bp.put("/api/state")
@limiter.limit("120 per minute")
@require_auth()
def put_state():
    body = request.get_json(silent=True) or {}
    if "data" not in body or "base_data" not in body or "base_revision" not in body:
        return jsonify(error="Faltan los datos base para sincronizar de forma segura."), 400
    try:
        incoming = normalize_shared_data(body["data"])
        base_data = normalize_shared_data(body["base_data"])
        base_revision = int(body["base_revision"])
    except (ValueError, TypeError, OverflowError):
        return jsonify(error="Los datos enviados no son válidos."), 400
    record = db.session.query(SharedState).filter_by(user_id=g.user.id).with_for_update().first()
    if record is None:
        record = SharedState(user_id=g.user.id, revision=0, data=copy.deepcopy(DEFAULT_SHARED_DATA), updated_by=g.user.id)
        db.session.add(record)
        db.session.flush()
    current = normalize_shared_data(record.data)
    if record.revision == base_revision:
        merged, conflicts = incoming, []
    else:
        merged, conflicts = three_way_merge(base_data, incoming, current)
    # Every authenticated account owns this row, so each user can manage their
    # own income, expenses, debts, school records and settings. Account
    # administration remains separately restricted by require_auth(admin=True).
    if conflicts:
        db.session.rollback()
        latest = ensure_user_state(g.user.id)
        return jsonify(error="Otra persona modificó los mismos datos. Se cargó la versión más reciente; revisa y vuelve a aplicar tu cambio.", conflicts=conflicts, data=normalize_shared_data(latest.data), revision=latest.revision), 409
    school_error = validate_school_balances(merged)
    if school_error:
        db.session.rollback()
        return jsonify(error=school_error), 400
    record.data = merged
    record.revision += 1
    record.updated_at = datetime.now(timezone.utc)
    record.updated_by = g.user.id
    db.session.commit()
    audit("shared_data_updated", f"Revisión {record.revision}", g.user.id)
    return jsonify(ok=True, data=normalize_shared_data(record.data), revision=record.revision, updated_at=record.updated_at.isoformat())


@bp.post("/api/import-legacy")
@require_auth()
def import_legacy_state():
    body = request.get_json(silent=True) or {}
    try:
        incoming = normalize_shared_data(body.get("data"))
    except (ValueError, TypeError, OverflowError):
        return jsonify(error="El archivo de respaldo no contiene datos válidos."), 400
    record = db.session.query(SharedState).filter_by(user_id=g.user.id).with_for_update().first()
    if record is None:
        record = SharedState(user_id=g.user.id, revision=0, data=copy.deepcopy(DEFAULT_SHARED_DATA), updated_by=g.user.id)
        db.session.add(record)
        db.session.flush()
    current = normalize_shared_data(record.data)
    school_error = validate_school_balances(incoming)
    if school_error:
        db.session.rollback()
        return jsonify(error=school_error), 400
    financially_empty = (
        not current["expenses"] and not current["debts"] and not current["incomeSources"] and
        not current["schoolExpenses"] and not current["schoolPayments"] and not current["plannedDebtPayments"] and
        float(current["income"].get("amount", 0) or 0) == 0 and
        all(float(value or 0) == 0 for value in current["budgets"].values()) and
        not current["debtPlan"].get("configured", False)
    )
    incoming_has_data = bool(incoming["expenses"] or incoming["debts"] or incoming["incomeSources"] or incoming["schoolExpenses"] or incoming["schoolPayments"] or incoming["plannedDebtPayments"] or float(incoming["income"].get("amount", 0) or 0) or any(float(v or 0) for v in incoming["budgets"].values()) or incoming["debtPlan"].get("configured", False))
    if not financially_empty:
        db.session.rollback()
        return jsonify(error="La nube ya contiene información. Por seguridad, la importación de un respaldo antiguo solo se permite cuando ingresos, presupuestos, gastos y deudas están vacíos."), 409
    if not incoming_has_data:
        return jsonify(error="El respaldo no contiene registros financieros para importar."), 400
    record.data = incoming
    record.revision += 1
    record.updated_at = datetime.now(timezone.utc)
    record.updated_by = g.user.id
    db.session.commit()
    audit("legacy_data_imported", f"Importación de respaldo, revisión {record.revision}", g.user.id)
    return jsonify(ok=True, data=normalize_shared_data(record.data), revision=record.revision, updated_at=record.updated_at.isoformat())


@bp.get("/api/countries")
def list_countries():
    return jsonify(countries=COUNTRIES, default=DEFAULT_COUNTRY)


@bp.get("/api/rate")
@require_auth()
def get_rate():
    country = clean_country(request.args.get("country"))
    cached = core.fetch_rate(country, force=request.args.get("force") == "1")
    if not cached["rate"]:
        return jsonify(error=f"No pudimos obtener el tipo de cambio ({COUNTRIES[country]['official']}) ahora. Inténtalo en unos minutos."), 503
    return jsonify(country=country, rate=cached["rate"], updated_at=cached["updated"], source=cached.get("source"),
                   parallel=cached.get("parallel"), parallel_updated_at=cached.get("parallel_updated"),
                   euro=cached.get("euro"), euro_updated_at=cached.get("euro_updated"))
