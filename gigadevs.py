"""Conexión con la Consola Maestra de Gigadevs (servidor de licencias de Contax) para la suscripción de Quincena VE.

La persona elige su plan en la app y paga con Ávila Pay; el cobro, la suscripción y la factura los lleva la consola de Gigadevs.
Cada petición va firmada con HMAC-SHA256 y el secreto de Quincena (GIGADEVS_SYNC_SECRET = SYNC_SECRET_CONTROL_FINANCIERO en el
servidor de licencias), con hora y nonce de un solo uso. Sin secreto configurado, la suscripción en línea queda apagada."""
import hashlib
import hmac
import json
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

PRODUCTO = "control_financiero"


class ErrorGigadevs(Exception):
    pass

class _SinRedirecciones(urllib.request.HTTPRedirectHandler):
    """Nunca seguir redirecciones: la firma no debe viajar a otro servidor."""
    def redirect_request(self, *a, **k): return None


_ABRIDOR = urllib.request.build_opener(_SinRedirecciones)


def respuesta_valida(secreto, cabecera, nonce, estado, cuerpo, ahora=None, tolerancia=300) -> bool:
    """La consola firma cada respuesta (X-Gigadevs-Respuesta) ligada al nonce de la petición: así se sabe que respondió ella, para
    ESTA petición, y que nadie alteró ni repitió la respuesta en el camino."""
    partes = dict(x.split("=", 1) for x in (cabecera or "").split(",") if "=" in x)
    t, f = partes.get("t", ""), partes.get("f", "")
    if not (t.isdigit() and len(f) == 64) or abs((ahora or time.time()) - int(t)) > tolerancia: return False
    base = "\n".join([t, nonce, str(estado), hashlib.sha256(cuerpo or b"").hexdigest()])
    return hmac.compare_digest(hmac.new(secreto.encode(), base.encode(), hashlib.sha256).hexdigest(), f)


def _verificar(res, cab):
    """(estado, cuerpo). Por HTTP real siempre llegan las cabeceras y la firma es obligatoria."""
    estado, cuerpo = res[0], res[1]
    if len(res) > 2:
        nonce = dict(x.split("=", 1) for x in cab["X-Gigadevs-Firma"].split(","))["n"]
        firma_r = {k.lower(): v for k, v in (res[2] or {}).items()}.get("x-gigadevs-respuesta")
        if not respuesta_valida(os.environ["GIGADEVS_SYNC_SECRET"], firma_r, nonce, estado, cuerpo):
            raise ErrorGigadevs("La respuesta de la consola de Gigadevs no trae una firma válida (posible suplantación); se descartó")
    return estado, cuerpo



def configurado() -> bool:
    return len(os.getenv("GIGADEVS_SYNC_SECRET", "")) >= 32


def firma(secreto: str, metodo: str, ruta: str, cuerpo: bytes, t=None) -> str:
    t, n = str(int(t if t is not None else time.time())), secrets.token_hex(16)
    base = "\n".join([t, n, metodo.upper(), ruta, hashlib.sha256(cuerpo).hexdigest()])
    return f"t={t},n={n},f={hmac.new(secreto.encode(), base.encode(), hashlib.sha256).hexdigest()}"


def _http(metodo, url, cuerpo, cab):
    req = urllib.request.Request(url, cuerpo or None, cab, method=metodo)
    try:
        with _ABRIDOR.open(req, timeout=20) as r:  # noqa: S310 (https fijo, sin redirecciones)
            return r.status, r.read(4 * 1024 * 1024), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(65536), dict(e.headers or {})
    except (urllib.error.URLError, OSError) as e:
        raise ErrorGigadevs("No pudimos contactar el servicio de pagos. Intenta de nuevo en unos minutos.") from e


TRANSPORTE = None   # las pruebas lo reemplazan: (metodo, ruta, cuerpo, cabeceras) -> (estado, bytes)


def peticion(metodo, ruta, datos=None):
    if not configurado(): raise ErrorGigadevs("La suscripción en línea aún no está disponible.")
    base = os.getenv("GIGADEVS_SYNC_URL", "https://erp-licencias.onrender.com").strip().rstrip("/")
    if not base.startswith("https://"): raise ErrorGigadevs("GIGADEVS_SYNC_URL debe usar https")
    cuerpo = json.dumps(datos).encode() if datos is not None else b""
    cab = {"X-Gigadevs-Firma": firma(os.environ["GIGADEVS_SYNC_SECRET"], metodo, ruta, cuerpo), "Accept": "application/json"}
    if datos is not None: cab["Content-Type"] = "application/json"
    estado, resp = _verificar((TRANSPORTE or (lambda m, r, c, h: _http(m, base + r, c, h)))(metodo, ruta, cuerpo, cab), cab)
    try:
        d = json.loads(resp or b"{}")
    except ValueError:
        d = {}
    if estado >= 400: raise ErrorGigadevs(str(d.get("message") or "El servicio de pagos no respondió.")[:200])
    return d


def planes() -> dict:
    return peticion("GET", f"/sync/v1/{PRODUCTO}/planes")


def checkout(**datos) -> dict:
    return peticion("POST", f"/sync/v1/{PRODUCTO}/checkout", datos)


def orden(ref: str) -> dict:
    return peticion("GET", f"/sync/v1/{PRODUCTO}/ordenes/{urllib.parse.quote(ref)}")


def legal_pendientes(usuario: str, perfil: str = "usuario") -> list:
    """Documentos legales vigentes de Quincena VE (aprobados por la abogada de Gigadevs) que esta persona aún no aceptó."""
    q = urllib.parse.urlencode({"usuario": usuario, "perfil": perfil})
    return peticion("GET", f"/sync/v1/{PRODUCTO}/legal/pendientes?{q}").get("pendientes") or []


def legal_aceptar(**datos) -> list:
    return peticion("POST", f"/sync/v1/{PRODUCTO}/legal/aceptar", datos).get("aceptaciones") or []
