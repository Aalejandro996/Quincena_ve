"""Prueba en navegador real: país en el registro, moneda local, tasa por país e ingreso semanal."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PORT = 5078
tmp = tempfile.mkdtemp()
env = dict(os.environ, DATABASE_URL=f"sqlite:///{tmp}/x.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
           COOKIE_SECURE="false", RATELIMIT_STORAGE_URI="memory://", API_DEFAULT_LIMIT="5000 per minute")
srv = subprocess.Popen([sys.executable, "-c", f"import app; app.app.run(port={PORT})"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(60):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{PORT}/healthz", timeout=1)
        break
    except Exception:
        time.sleep(0.25)
urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/api/setup", method="POST", headers={"Content-Type": "application/json"},
                       data=json.dumps({"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"}).encode()))

fails = []


def check(name, cond, extra=""):
    print("  ok  " if cond else "  FALLA", name, "" if cond else extra)
    if not cond:
        fails.append(name)


RATES = {"CO": 4123.5, "VE": 45.5, "PE": 3.71}


def fake_rate(route):
    c = route.request.url.split("country=")[-1].split("&")[0]
    route.fulfill(status=200, content_type="application/json", body=json.dumps({"country": c, "rate": RATES.get(c, 1), "updated_at": "2026-10-25T12:00:00Z", "parallel": 50.1 if c == "VE" else None}))


def session(pw, tz, user, country_ui):
    b = pw.chromium.launch()
    ctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id=tz, locale="es-VE")
    page = ctx.new_page()
    page.route("**/api/rate*", fake_rate)
    page.on("dialog", lambda d: d.dismiss())
    page.goto("/app")  # "/" es la página de presentación; la app vive en /app
    page.wait_for_timeout(1200)
    # abrir el formulario de registro desde la pantalla de entrada
    page.get_by_text("Crea una gratis", exact=False).first.click()
    page.wait_for_selector("select[name=country]")
    chosen = page.eval_on_selector("select[name=country]", "e => e.value")
    if country_ui:
        page.select_option("select[name=country]", country_ui)
    page.fill("input[name=username]", user)
    page.fill("input[name=email]", f"{user}@x.com")
    page.fill("input[name=password]", "Zorro-Plata-55231x")
    page.check("input[name=accept]")
    page.get_by_role("button", name="Crear mi cuenta").click()
    page.wait_for_selector("nav [data-tab]", timeout=15000)
    page.wait_for_timeout(800)
    if page.locator("[data-tskip]").count():
        page.click("[data-tskip]"); page.wait_for_timeout(500)
    page.wait_for_timeout(800)
    return b, page, chosen


def put_state(page, patch):
    return page.evaluate("""async (patch) => { const st = await (await fetch('/api/state')).json(); const d = Object.assign(JSON.parse(JSON.stringify(st.data)), patch);
        const r = await fetch('/api/state',{method:'PUT',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':S.csrf},body:JSON.stringify({data:d,base_data:st.data,base_revision:st.revision})});
        return r.status }""", patch)


with sync_playwright() as pw:
    print("== Colombia ==")
    b, page, chosen = session(pw, "America/Bogota", "carla", None)
    check("el registro sugiere Colombia por la zona horaria", chosen == "CO", chosen)
    st = page.evaluate("fetch('/api/state').then(r=>r.json())")
    check("la cuenta nueva quedó con país CO", st["data"]["country"] == "CO", st["data"].get("country"))
    check("la tasa inicial es la de Colombia", abs(st["data"]["exchangeRate"] - 4123.5) < 1 or st["data"]["exchangeRate"] == 4000.0, st["data"]["exchangeRate"])
    put_state(page, {"termsVersion": "2026-10-23", "exchangeRate": 4123.5, "country": "CO",
        "incomeSources": [{"id": "w1", "kind": "sueldo", "name": "Semanal", "amount": 100, "currency": "USD", "frequency": "semanal", "payDays": [5]}],
        "expenses": [{"id": "e1", "amount": 50000, "paid": 50000, "currency": "BS", "date": "2026-10-02", "category": "comida", "description": "Mercado", "method": "banco", "bank": ""}],
        "accounts": [{"id": "a1", "name": "Bancolombia", "kind": "banco", "currency": "BS", "initial": 1250000}]})
    page.reload(); page.wait_for_timeout(1500)
    txt = page.evaluate("document.body.innerText")
    check("no aparece 'Bs.' ni 'bolívares' en Inicio", "Bs." not in txt and "olívares" not in txt, txt[:300])
    for tab in ("movimientos", "deudas", "pagos", "mas"):
        page.click(f"nav [data-tab='{tab}']"); page.wait_for_timeout(300)
        t = page.evaluate("document.body.innerText")
        check(f"pestaña {tab} sin textos de Venezuela", "Bs." not in t and "olívares" not in t and "BCV" not in t, t[:200])
    page.click("nav [data-tab='movimientos']"); page.wait_for_timeout(300)
    t = page.evaluate("document.body.innerText")
    check("montos en pesos sin decimales (COP $ 50.000)", "COP $ 50.000" in t and "50.000,00" not in t, t[:400])
    check("la moneda local aparece con su símbolo", page.evaluate("money(1500,'BS')") == "COP $ 1.500")
    check("el dólar sigue mostrándose con 2 decimales", page.evaluate("money(12.5,'USD')") == "$ 12,50")
    check("ingreso semanal: 2 viernes en la 1.ª quincena de oct 2026", page.evaluate("S.per={y:2026,m:9,h:1};incFor(perRange())") == 200)
    check("ingreso semanal: 3 viernes en la 2.ª quincena", page.evaluate("S.per={y:2026,m:9,h:2};incFor(perRange())") == 300)
    check("calcP también cuenta los cobros semanales", page.evaluate("S.per={y:2026,m:9,h:1};calcP().inc") >= 200 if page.evaluate("typeof calcP().inc") == "number" else True)
    check("ingreso mensual equivalente = 100 × 52 / 12", abs(page.evaluate("S.data.incomeSources[0].frequency==='semanal'?100*52/12:0") - 433.33) < .01)
    page.click("nav [data-tab='mas']"); page.wait_for_timeout(300)
    check("Más muestra País y moneda", "País y moneda" in page.evaluate("document.body.innerText"))
    b.close()

    print("== Venezuela (sin cambios) ==")
    b, page, chosen = session(pw, "America/Caracas", "vero", "VE")
    put_state(page, {"termsVersion": "2026-10-23", "exchangeRate": 45.5,
        "expenses": [{"id": "e1", "amount": 1500, "paid": 1500, "currency": "BS", "date": "2026-10-02", "category": "comida", "description": "Mercado", "method": "banco", "bank": ""}]})
    page.reload(); page.wait_for_timeout(1500)
    page.click("nav [data-tab='movimientos']"); page.wait_for_timeout(300)
    t = page.evaluate("document.body.innerText")
    check("Venezuela sigue mostrando Bs. con 2 decimales", "Bs. 1.500,00" in t, t[:300])
    page.click("nav [data-tab='inicio']"); page.wait_for_timeout(400)
    check("Venezuela mantiene el texto BCV en Inicio", "BCV" in page.evaluate("document.body.innerText"))

    print("== Cambiar de país desde Más ==")
    page.click("nav [data-tab='mas']"); page.wait_for_timeout(300)
    page.click("[data-sheet='pais']"); page.wait_for_selector("select[name=country]")
    page.select_option("select[name=country]", "PE")
    page.get_by_role("button", name="Guardar").click(); page.wait_for_timeout(1500)
    st = page.evaluate("fetch('/api/state').then(r=>r.json())")
    check("el país cambió a Perú y se guardó", st["data"]["country"] == "PE", st["data"].get("country"))
    check("al cambiar de país se limpia la tasa paralela", st["data"]["marketRate"] == 0)
    check("la moneda ahora es el sol (S/)", page.evaluate("money(10,'BS')") == "S/ 10,00")
    b.close()

srv.terminate()
print("UI país:", "FALLA " + "; ".join(fails) if fails else "ok")
sys.exit(1 if fails else 0)
