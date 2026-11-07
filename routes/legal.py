"""Documentos legales de Quincena VE aprobados por la abogada de Gigadevs: la persona los acepta al ingresar (términos y privacidad)
y al suscribirse (contrato). La aceptación se registra en la Consola Maestra de Gigadevs y se guarda aquí como copia de la evidencia."""
from datetime import datetime, timezone

from flask import Blueprint, g, jsonify, request

import gigadevs as GD
from app import LegalAcceptance, app, audit, db, limiter, require_auth  # noqa: E402

bp = Blueprint("legal", __name__)
PERFILES = ("usuario", "responsable")


def _ip():
    return (request.headers.get("X-Forwarded-For") or request.remote_addr or "").split(",")[0].strip()[:64]


@bp.get("/api/legal/pending")
@require_auth(allow_password_change=True)
def pendientes():
    perfil = request.args.get("perfil") if request.args.get("perfil") in PERFILES else "usuario"
    if not GD.configurado(): return jsonify(pending=[], online=False)
    try:
        pend = GD.legal_pendientes(g.user.username, perfil)
    except GD.ErrorGigadevs as e:          # la consola no responde: no se bloquea el uso
        app.logger.warning("Legal: no se pudo consultar documentos pendientes: %s", e)
        return jsonify(pending=[], online=False)
    return jsonify(online=True, pending=[{"id": p["id"], "kind": p["tipo"], "title": p["titulo"], "version": p["version"], "sha256": p["huella"],
                                          "sections": p["documento"]["secciones"],
                                          "lawyer": ({"name": p["documento"]["abogado"]["nombre"], "inpre": p["documento"]["abogado"]["inpreabogado"]}
                                                     if p["documento"].get("abogado") else None)} for p in pend])


@bp.post("/api/legal/accept")
@require_auth(allow_password_change=True)
@limiter.limit("20 per hour")
def aceptar():
    d = request.get_json(silent=True) or {}
    perfil = d.get("perfil") if d.get("perfil") in PERFILES else "usuario"
    ids = {int(x) for x in (d.get("ids") or []) if str(x).isdigit()}
    try:
        pend = GD.legal_pendientes(g.user.username, perfil)
    except GD.ErrorGigadevs as e:
        return jsonify(error=str(e)), 502
    if not pend: return jsonify(ok=True, accepted=0)
    if ids != {p["id"] for p in pend}: return jsonify(error="Marca la casilla de cada documento para aceptarlo."), 400
    u, ip, ua = g.user, _ip(), (request.user_agent.string or "")[:300]
    try:
        GD.legal_aceptar(documentos=sorted(ids), usuario=u.username, nombre=u.username, sujeto=(u.email or "").lower() or None,
                         organizacion=None, rol="titular" if not u.owner_id else "vendedor", ip=ip, agente=ua)
    except GD.ErrorGigadevs as e:
        return jsonify(error=str(e)), 502
    for p in pend:
        db.session.add(LegalAcceptance(user_id=u.id, document_id=p["id"], kind=p["tipo"], version=p["version"], sha256=p["huella"], ip=ip,
                                       user_agent=ua, accepted_at=datetime.now(timezone.utc)))
    db.session.commit()
    audit("legal_accepted", ", ".join(f"{p['tipo']} v{p['version']}" for p in pend), u.id)
    return jsonify(ok=True, accepted=len(pend))
