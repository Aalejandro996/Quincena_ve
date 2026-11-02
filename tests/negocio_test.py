"""Pruebas de Quincena VE Negocio en el servidor: datos, mezcla, plan Plus y vendedores. Sin red."""
import os
import sys
import tempfile

tmp = tempfile.mkdtemp()
os.environ.update(DATABASE_URL=f"sqlite:///{tmp}/t.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
                  COOKIE_SECURE="false", API_DEFAULT_LIMIT="5000 per minute", RATELIMIT_STORAGE_URI="memory://", BREVO_API_KEY="", SMTP_USER="", SMTP_PASSWORD="")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import app as A  # noqa: E402

ok = fail = 0


def check(name, cond, extra=""):
    global ok, fail
    if cond:
        ok += 1; print("  ok  ", name)
    else:
        fail += 1; print("  FALLA", name, extra)


A.send_verification = lambda user: False
A.notify_security = lambda *a, **k: None


class C:
    def __init__(self):
        self.c, self.csrf = A.app.test_client(), None

    def req(self, method, path, body=None, ip="198.51.100.9"):
        h = {"X-Forwarded-For": ip, **({"X-CSRF-Token": self.csrf} if self.csrf and method != "GET" else {})}
        r = self.c.open(path, method=method, json=body, headers=h)
        j = r.get_json(silent=True) or {}
        if j.get("csrf_token"):
            self.csrf = j["csrf_token"]
        return r.status_code, j


admin = C()
admin.req("POST", "/api/setup", {"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"})
ana = C()
code, j = ana.req("POST", "/api/register", {"username": "ana", "email": "ana@x.com", "password": "Zorro-Plata-55231x", "accept_terms": True}, ip="198.51.100.20")
check("se registra la dueña", code == 200, j)
check("una cuenta nueva es plan free y no es vendedora", j["user"]["plan"] == "free" and j["user"]["seller"] is False, j)

print("== Datos del negocio ==")
code, st = ana.req("GET", "/api/state")
data, rev = st["data"], st["revision"]
check("el estado trae el negocio apagado por defecto", data["business"] == {"enabled": False, "name": "", "phone": "", "rateKind": "bcv_usd", "type": "comercio", "profession": ""} and data["sales"] == [])
new = {**data, "business": {"enabled": True, "name": "Bodega Ana", "phone": "04140000000"},
       "products": [{"id": "p1", "name": "Harina", "price": 1.5, "cost": 1.1, "stock": 20, "track": True}],
       "sales": [{"id": "s1", "date": "2026-10-27T12:00:00Z", "amount": 3, "currency": "USD", "method": "efectivo_usd", "items": [{"productId": "p1", "name": "Harina", "qty": 2, "price": 1.5}]},
                 {"id": "s2", "date": "2026-10-27T13:00:00Z", "amount": 900, "currency": "BS", "method": "pago_movil", "rateUsed": 450, "credit": True, "client": "María"}],
       "bizPayments": [{"id": "b1", "saleId": "s2", "date": "2026-10-27T14:00:00Z", "amount": 450, "currency": "BS", "method": "pago_movil"}],
       "bizExpenses": [{"id": "g1", "date": "2026-10-27T10:00:00Z", "amount": 20, "currency": "USD", "category": "mercancia", "supplier": "Mayorista"},
                       {"id": "g2", "date": "2026-10-27T18:00:00Z", "amount": 5, "currency": "USD", "category": "chatarra"}],
       "cashClosings": [{"id": "k1", "date": "2026-10-27T20:00:00Z", "countedBs": 450, "countedUsd": 3}],
       "incomes": [{"id": "i1", "date": "2026-10-27T20:00:00Z", "amount": 5, "currency": "USD", "kind": "negocio", "name": "Me pagué del negocio"}]}
code, j = ana.req("PUT", "/api/state", {"data": new, "base_data": data, "base_revision": rev})
d = j["data"]
check("guarda el negocio", code == 200 and d["business"]["name"] == "Bodega Ana" and d["business"]["enabled"] is True, j)
check("guarda ventas con sus productos", [x["id"] for x in d["sales"]] == ["s1", "s2"] and d["sales"][0]["items"][0]["qty"] == 2)
check("la venta fiada queda como crédito", d["sales"][1]["credit"] is True and d["sales"][1]["client"] == "María")
check("guarda abonos, gastos, productos y cierres", len(d["bizPayments"]) == 1 and len(d["bizExpenses"]) == 2 and len(d["products"]) == 1 and len(d["cashClosings"]) == 1)
check("una categoría desconocida cae en mercancía (la primera)", d["bizExpenses"][1]["category"] == "mercancia")
check("el ingreso de tipo negocio se conserva (Me pago a mí)", d["incomes"][0]["kind"] == "negocio")
check("los gastos del negocio no se mezclan con los personales", d["expenses"] == [])
base, rev = d, j["revision"]

print("== Mezcla entre dos teléfonos ==")
tel1 = {**base, "sales": [{"id": "s3", "date": "2026-10-28T09:00:00Z", "amount": 4, "currency": "USD", "method": "zelle"}] + base["sales"]}
code, j = ana.req("PUT", "/api/state", {"data": tel1, "base_data": base, "base_revision": rev})
tel2 = {**base, "bizExpenses": [{"id": "g3", "date": "2026-10-28T09:30:00Z", "amount": 2, "currency": "USD", "category": "servicios"}] + base["bizExpenses"]}
code, j = ana.req("PUT", "/api/state", {"data": tel2, "base_data": base, "base_revision": rev})
check("dos teléfonos con revisión vieja se mezclan sin conflicto", code == 200 and {"s3", "s1", "s2"} <= {x["id"] for x in j["data"]["sales"]} and "g3" in {x["id"] for x in j["data"]["bizExpenses"]}, j)

print("== Negocio Plus ==")
code, j = ana.req("POST", "/api/biz/sellers", {"username": "pedro", "password": "Cobre-Rio-77341x"})
check("sin Plus no puede crear vendedores (402)", code == 402 and j.get("code") == "plus_required", j)
with A.app.app_context():
    ana_id = A.User.query.filter_by(username="ana").first().id
code, j = ana.req("PATCH", f"/api/users/{ana_id}", {"plan": "plus", "days": 30})
check("una dueña no puede activarse el Plus a sí misma", code == 403)
code, j = admin.req("PATCH", f"/api/users/{ana_id}", {"plan": "plus", "days": 30})
check("el administrador activa Plus por 30 días", code == 200 and j["plan"] == "plus" and j["plan_until"], j)
code, j = ana.req("GET", "/api/status")
check("el estado de la cuenta dice plus", j["user"]["plan"] == "plus", j)
code, j = admin.req("GET", "/api/users")
check("la lista de usuarios muestra el plan", any(u["username"] == "ana" and u["plan"] == "plus" for u in j["users"]))
code, j = admin.req("PATCH", f"/api/users/{ana_id}", {"plan": "gold"})
check("un plan desconocido se rechaza", code == 400)

print("== Vendedores ==")
code, j = ana.req("POST", "/api/biz/sellers", {"username": "pedro", "password": "corta"})
check("contraseña débil se rechaza", code == 400)
code, j = ana.req("POST", "/api/biz/sellers", {"username": "pedro", "password": "Cobre-Rio-77341x"})
check("con Plus crea un vendedor", code == 200 and j["seller"]["username"] == "pedro", j)
pedro_id = j["seller"]["id"]
code, j = ana.req("POST", "/api/biz/sellers", {"username": "ana", "password": "Cobre-Rio-77341x"})
check("no repite usuarios", code == 409)
code, j = ana.req("GET", "/api/biz/sellers")
check("lista sus vendedores", code == 200 and [s["username"] for s in j["sellers"]] == ["pedro"] and j["plus"] is True, j)

pedro = C()
code, j = pedro.req("POST", "/api/login", {"username": "pedro", "password": "Cobre-Rio-77341x"}, ip="198.51.100.30")
check("el vendedor entra y se identifica como vendedor del negocio", code == 200 and j["user"]["seller"] is True and j["user"]["owner"] == "ana" and j["user"]["business"] == "Bodega Ana", j)
code, j = pedro.req("GET", "/api/biz/seller")
check("ve el nombre del negocio, la tasa y los productos", code == 200 and j["business"] == "Bodega Ana" and j["products"][0]["name"] == "Harina" and j["rate"] > 0, j)
check("no ve el costo de los productos", "cost" not in j["products"][0])
code, j = pedro.req("POST", "/api/biz/seller/sale", {"amount": 0, "currency": "USD", "method": "efectivo_usd"})
check("venta sin monto se rechaza", code == 400)
code, j = pedro.req("POST", "/api/biz/seller/sale", {"amount": 450, "currency": "BS", "method": "pago_movil", "client": "Luis",
                                                    "items": [{"productId": "p1", "name": "Harina", "qty": 1, "price": 1.5}]})
check("registra una venta en Bs. con la tasa del negocio y su nombre", code == 200 and j["sale"]["seller"] == "pedro" and j["sale"]["rateUsed"] > 0, j)
code, st = ana.req("GET", "/api/state")
check("la dueña ve la venta del vendedor en sus datos", st["data"]["sales"][0]["seller"] == "pedro" and st["data"]["sales"][0]["items"][0]["qty"] == 1)
code, j = pedro.req("GET", "/api/biz/seller")
check("el vendedor ve solo sus ventas", [x["seller"] for x in j["sales"]] == ["pedro"])
code, j = pedro.req("GET", "/api/biz/sellers")
check("un vendedor no puede gestionar vendedores", code == 403)
code, j = pedro.req("PATCH", f"/api/users/{pedro_id}", {"plan": "plus"})
check("un vendedor no puede tocar planes", code == 403)
otro = C()
otro.req("POST", "/api/register", {"username": "otro", "email": "otro@x.com", "password": "Zorro-Plata-55231x", "accept_terms": True}, ip="198.51.100.40")
code, j = otro.req("PATCH", f"/api/biz/sellers/{pedro_id}", {"active": False})
check("otra persona no puede tocar a sus vendedores", code == 404)
code, j = otro.req("POST", "/api/biz/seller/sale", {"amount": 5, "currency": "USD", "method": "zelle"})
check("una cuenta normal no puede vender por el negocio", code == 403)

print("== Plus vencido y vendedor desactivado ==")
with A.app.app_context():
    u = A.db.session.get(A.User, ana_id); u.plan_until = A.datetime.now(A.timezone.utc) - A.timedelta(days=1); A.db.session.commit()
code, j = pedro.req("POST", "/api/biz/seller/sale", {"amount": 5, "currency": "USD", "method": "zelle"})
check("con el Plus vencido el vendedor no puede vender (402)", code == 402, j)
code, j = ana.req("GET", "/api/status")
check("la dueña vuelve a free al vencer", j["user"]["plan"] == "free")
admin.req("PATCH", f"/api/users/{ana_id}", {"plan": "plus"})
code, j = ana.req("PATCH", f"/api/biz/sellers/{pedro_id}", {"active": False})
check("la dueña desactiva al vendedor", code == 200 and j["seller"]["active"] is False)
code, j = pedro.req("GET", "/api/biz/seller")
check("el vendedor desactivado pierde el acceso", code == 401)
code, j = ana.req("PATCH", f"/api/biz/sellers/{pedro_id}", {"active": True, "password": "Nueva-Clave-99123x"})
p2 = C()
code, j = p2.req("POST", "/api/login", {"username": "pedro", "password": "Nueva-Clave-99123x"}, ip="198.51.100.31")
check("la dueña lo reactiva con contraseña nueva", code == 200, j)

print("== Tasa del negocio ==")
d0 = A.normalize_shared_data({"exchangeRate": 200, "marketRate": 300, "euroRate": 230, "business": {"enabled": True, "rateKind": "bcv_eur"}})
check("el negocio guarda la tasa con que trabaja", d0["business"]["rateKind"] == "bcv_eur" and A.business_rate(d0) == 230)
check("USDT usa la tasa paralela", A.business_rate({**d0, "business": {"rateKind": "usdt"}}) == 300)
check("una tasa desconocida vuelve a dólar BCV", A.normalize_shared_data({"business": {"rateKind": "oro"}})["business"]["rateKind"] == "bcv_usd")
check("si falta el euro, usa el dólar BCV", A.business_rate({**d0, "euroRate": 0}) == 200)

print("== Registro de un negocio ==")
neg = C()
code, j = neg.req("POST", "/api/register", {"username": "luis", "email": "luis@x.com", "password": "Zorro-Plata-55231x", "accept_terms": True,
                                           "business_name": "  Panadería Luis  "}, ip="198.51.100.50")
code, st = neg.req("GET", "/api/state")
check("crear la cuenta del negocio la deja con el modo Negocio activo y su nombre", st["data"]["business"] == {"enabled": True, "name": "Panadería Luis", "phone": "", "rateKind": "bcv_usd", "type": "comercio", "profession": ""}, st["data"]["business"])
code, st = otro.req("GET", "/api/state")
check("una cuenta personal nace sin negocio", st["data"]["business"]["enabled"] is False)

print("== Eliminar la cuenta de la dueña ==")
code, j = ana.req("DELETE", "/api/account", {"password": "Zorro-Plata-55231x"})
with A.app.app_context():
    check("sus vendedores quedan desactivados", A.db.session.get(A.User, pedro_id).active is False)

print("== Profesionales ==")
pro = C()
code, j = pro.req("POST", "/api/register", {"username": "abogada", "email": "ab@x.com", "password": "Zorro-Plata-55231x", "accept_terms": True,
                                            "business_name": "Escritorio Pérez", "business_type": "profesional", "profession": "abogado"}, ip="198.51.100.61")
code, st = pro.req("GET", "/api/state")
check("una abogada crea su cuenta de servicios profesionales", st["data"]["business"]["type"] == "profesional" and st["data"]["business"]["profession"] == "abogado", st["data"]["business"])
nd = {**st["data"], "sales": [{"id": "h1", "date": "2026-10-27T12:00:00Z", "amount": 50, "currency": "USD", "method": "zelle", "client": "Sr. Gómez", "concept": "Consulta legal", "credit": True}]}
code, j = pro.req("PUT", "/api/state", {"data": nd, "base_data": st["data"], "base_revision": st["revision"]})
check("el cobro guarda el concepto del servicio", code == 200 and j["data"]["sales"][0]["concept"] == "Consulta legal", j)
bad = {**j["data"], "business": {**j["data"]["business"], "type": "pirata", "profession": "astronauta"}}
code, j = pro.req("PUT", "/api/state", {"data": bad, "base_data": j["data"], "base_revision": j["revision"]})
check("tipos desconocidos se limpian", j["data"]["business"]["type"] == "comercio" and j["data"]["business"]["profession"] == "", j["data"]["business"])

print(f"\n{ok} pruebas correctas, {fail} con falla")
sys.exit(1 if fail else 0)
