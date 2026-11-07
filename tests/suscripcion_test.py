"""Suscripción (Negocio Plus) pagada desde la app con Ávila Pay a través de la Consola Maestra de Gigadevs. Sin red: la consola
se simula y firma la suscripción con una clave de prueba."""
import base64
import hashlib
import hmac
import json
import os
import sys
import tempfile
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

PRIV = Ed25519PrivateKey.generate()
PUB = base64.urlsafe_b64encode(PRIV.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode().rstrip("=")
SECRETO = "q" * 40
tmp = tempfile.mkdtemp()
os.environ.update(DATABASE_URL=f"sqlite:///{tmp}/t.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
                  COOKIE_SECURE="false", API_DEFAULT_LIMIT="5000 per minute", RATELIMIT_STORAGE_URI="memory://", BREVO_API_KEY="", SMTP_USER="",
                  SMTP_PASSWORD="", GIGADEVS_SYNC_SECRET=SECRETO, PRODUCT_LICENSE_PUBLIC_KEY=PUB, APP_PUBLIC_ORIGIN="https://quincena.test")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import app as A  # noqa: E402
import gigadevs as GD  # noqa: E402

ok = fail = 0


def check(name, cond, extra=""):
    global ok, fail
    if cond:
        ok += 1; print("  ok  ", name)
    else:
        fail += 1; print("  FALLA", name, extra)


A.send_verification = lambda user: False
A.notify_security = lambda *a, **k: None
A.fetch_rate = lambda country="VE", force=False: {"rate": 40.0}
import routes.suscripcion as RS  # noqa: E402
RS.fetch_rate = A.fetch_rate


def token(sujeto, dias=30):
    c = json.dumps({"producto": "control_financiero", "tipo_sujeto": "cuenta", "sujeto": sujeto, "estado": "activa", "plan": "plus",
                    "exp": int(time.time()) + dias * 86400, "limites": {"max_usuarios": 1}}, sort_keys=True, separators=(",", ":")).encode()
    b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=").decode()
    return f"{b(c)}.{b(PRIV.sign(c))}"


class Consola:
    """Simula la consola de Gigadevs: verifica la firma de cada petición como el servidor de licencias."""
    def __init__(self):
        self.ordenes, self.vistos = {}, []

    def __call__(self, metodo, ruta, cuerpo, cab):
        p = dict(x.split("=", 1) for x in cab["X-Gigadevs-Firma"].split(","))
        base = "\n".join([p["t"], p["n"], metodo, ruta, hashlib.sha256(cuerpo).hexdigest()])
        if not hmac.compare_digest(hmac.new(SECRETO.encode(), base.encode(), hashlib.sha256).hexdigest(), p["f"]): return 401, b'{"message":"No autorizado"}'
        self.vistos.append((metodo, ruta, json.loads(cuerpo) if cuerpo else None))
        if ruta == "/sync/v1/control_financiero/planes":
            return 200, json.dumps({"planes": [{"codigo": "plus", "nombre": "Negocio Plus", "precios": {"mensual": "5.00", "anual": "50.00"}}],
                                    "metodos": {"bolivares": [{"codigo": "pago_movil", "nombre": "Pago Móvil"}], "dolares": [{"codigo": "zelle", "nombre": "Zelle"}]}}).encode()
        if ruta == "/sync/v1/control_financiero/checkout":
            d = json.loads(cuerpo); ref = f"OP2611{len(self.ordenes) + 1:06X}"
            self.ordenes[ref] = {"estado": "Pendiente", "sujeto": d["referencia"]}
            return 201, json.dumps({"ref": ref, "checkout_url": f"https://comercio.avilapay.com/pay/cs_{ref}", "monto": "209.43", "moneda": "VES"}).encode()
        ref = ruta.rsplit("/", 1)[1]
        o = self.ordenes.get(ref)
        if o is None: return 404, b'{"message":"Orden inexistente"}'
        r = {"ref": ref, "estado": o["estado"]}
        if o["estado"] == "Pagada": r.update(token=token(o["sujeto"]), vence_en=int(time.time()) + 30 * 86400)
        return 200, json.dumps(r).encode()


consola = Consola()
GD.TRANSPORTE = consola


class C:
    def __init__(self):
        self.c, self.csrf = A.app.test_client(), None

    def req(self, method, path, body=None, ip="198.51.100.21"):
        h = {"X-Forwarded-For": ip, **({"X-CSRF-Token": self.csrf} if self.csrf and method != "GET" else {})}
        r = self.c.open(path, method=method, json=body, headers=h)
        j = r.get_json(silent=True) or {}
        if j.get("csrf_token"): self.csrf = j["csrf_token"]
        return r.status_code, j


admin = C()
admin.req("POST", "/api/setup", {"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"})
u = C()
st, j = u.req("POST", "/api/register", {"username": "ana", "email": "ana@correo.test", "password": "Zorro-Plata-55231x", "accept_terms": True}, ip="198.51.100.20")
check("registro", st in (200, 201), j)
st, j = u.req("GET", "/api/subscription")
check("sin correo verificado no se paga", st == 200 and j["online"] and not j["email_verified"] and not j["active"], j)
st, j = u.req("POST", "/api/subscription/checkout", {"plan": "plus", "period": "mensual", "method": "pago_movil"})
check("checkout exige correo verificado", st == 400, j)
with A.app.app_context():
    usr = A.User.query.filter_by(username="ana").first()
    usr.email, usr.email_verified_at = "ana@correo.test", A.datetime.now(A.timezone.utc)
    A.db.session.commit()
st, j = u.req("GET", "/api/subscription")
check("planes y formas de pago desde la consola", j["plans"][0]["precios"]["anual"] == "50.00" and j["methods"]["dolares"][0]["codigo"] == "zelle", j)
st, j = u.req("POST", "/api/subscription/checkout", {"plan": "plus", "period": "mensual", "method": "pago_movil", "document": "v-12345678-0"})
check("checkout devuelve el enlace de Ávila Pay", st == 200 and j["url"].startswith("https://comercio.avilapay.com/pay/"), j)
_, _, enviado = consola.vistos[-1]
check("se envía correo, plan, tasa BCV, RIF y regreso", enviado["referencia"] == "ana@correo.test" and enviado["tasa_bcv"] == "40.0"
      and enviado["documento"] == "V-12345678-0" and enviado["retorno"] == "https://quincena.test/?suscripcion=1", enviado)
ref = j["ref"]
st, j = u.req("POST", "/api/subscription/refresh", {})
check("pago aún pendiente: sin Plus", st == 200 and not j["activated"] and not j["active"] and j["orders"][ref] == "Pendiente", j)
consola.ordenes[ref]["estado"] = "Pagada"
st, j = u.req("POST", "/api/subscription/refresh", {})
check("pagado: Negocio Plus se activa solo", st == 200 and j["activated"] and j["active"] and j["plan_until"], j)
st, j = u.req("GET", "/api/subscription")
check("ya no queda pago pendiente", j["active"] and j["pending"] == [], j)
with A.app.app_context():
    check("la cuenta queda en Plus", A.is_plus(A.User.query.filter_by(username="ana").first()))
st, j = u.req("POST", "/api/subscription/checkout", {"plan": "plus", "period": "anual", "method": "zelle"})
check("en dólares no se pide tasa", st == 200 and "tasa_bcv" not in consola.vistos[-1][2], consola.vistos[-1])
GD.TRANSPORTE = lambda *a: (_ for _ in ()).throw(GD.ErrorGigadevs("El servicio de pagos no respondió."))
st, j = u.req("POST", "/api/subscription/checkout", {"plan": "plus", "period": "mensual", "method": "pago_movil"})
check("si la consola no responde se avisa sin romper", st == 502 and "no respondió" in j.get("error", ""), j)

print(f"\n{ok} ok, {fail} fallas")
sys.exit(1 if fail else 0)
