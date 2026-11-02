"""Prueba en navegador del tutorial de bienvenida con Quinci (cuenta personal y de un profesional) y de Mi Negocio para
profesionales: cobros con concepto, por cobrar y la vista de Clientes."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PORT = 5093
tmp = tempfile.mkdtemp()
env = dict(os.environ, DATABASE_URL=f"sqlite:///{tmp}/x.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
           COOKIE_SECURE="false", RATELIMIT_STORAGE_URI="memory://", API_DEFAULT_LIMIT="5000 per minute", BREVO_API_KEY="")
srv = subprocess.Popen([sys.executable, "-c", f"import app; app.app.run(port={PORT})"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
fails = []
SHOTS = os.getenv("SHOTS")


def check(name, cond, extra=""):
    print("  ok  " if cond else "  FALLA", name, "" if cond else extra)
    if not cond:
        fails.append(name)


def fake_rate(route):
    route.fulfill(status=200, content_type="application/json", body=json.dumps({"country": "VE", "rate": 100.0, "updated_at": "2026-10-27T12:00:00Z", "parallel": 125.0, "euro": 110.0}))


text = lambda page: page.evaluate("document.body.innerText")
state = lambda page: page.evaluate("async () => (await (await fetch('/api/state')).json()).data")
shot = lambda page, n: SHOTS and page.screenshot(path=os.path.join(SHOTS, n + ".png"))
nxt = lambda page: (page.click("#f-tour button.btn:not(.o):not([type=button])"), page.wait_for_timeout(500))


def new_page(b):
    ctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE", viewport={"width": 420, "height": 860})
    ctx.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
    page = ctx.new_page()
    page.route("**/api/rate*", fake_rate)
    page.on("dialog", lambda d: d.accept())
    errs = []
    page.on("pageerror", lambda e: errs.append(str(e)))
    return page, errs


try:
    for _ in range(80):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/healthz", timeout=1); break
        except Exception:
            time.sleep(0.25)
    urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/api/setup", method="POST", headers={"Content-Type": "application/json"},
                           data=json.dumps({"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"}).encode()))
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        print("== Tutorial de una cuenta personal ==")
        page, errs = new_page(b)
        page.goto("/app#registro"); page.wait_for_timeout(900)
        page.fill("input[name=username]", "luis"); page.fill("input[name=email]", "luis@x.com"); page.fill("input[name=password]", "Zorro-Plata-55231x")
        page.check("input[name=accept]"); page.get_by_role("button", name="Crear mi cuenta").click()
        page.wait_for_selector("#f-tour", timeout=15000); page.wait_for_timeout(400)
        check("al registrarse aparece el tutorial con Quinci", "Soy Quinci" in text(page) and page.locator("#f-tour svg.quinci").count() == 1)
        shot(page, "t1-hola")
        check("dice cuántos pasos son", "Paso 1 de 7" in text(page), text(page)[:200])
        nxt(page)
        page.fill("#f-tour [name=amount]", "300"); page.select_option("#f-tour [name=frequency]", "quincenal"); shot(page, "t2-ingreso"); nxt(page)
        page.fill("#f-tour [name=fx0]", "120"); page.fill("#f-tour [name=fd0]", "28"); page.fill("#f-tour [name=fx1]", "25"); shot(page, "t3-fijos"); nxt(page)
        page.fill("#f-tour [name=de0]", "Cashea"); page.fill("#f-tour [name=da0]", "200"); page.fill("#f-tour [name=dm0]", "40"); shot(page, "t4-deudas"); nxt(page)
        page.click("[data-tcur=USDT]"); shot(page, "t5-moneda"); nxt(page)
        check("el recorrido explica cada pestaña y el botón +", all(w in text(page) for w in ("Inicio", "Movimientos", "Deudas", "Pagos", "El botón +"))); shot(page, "t6-recorrido"); nxt(page)
        check("al final Quinci celebra y dice cuánto queda", "Tu quincena quedó configurada" in text(page) and "Te quedan" in text(page)); shot(page, "t7-listo")
        page.click("[data-tfin=gasto]"); page.wait_for_timeout(500)
        check("Anotar mi primer gasto abre el formulario", page.locator("#f-sheet[data-k=gasto]").count() == 1)
        page.click("#x"); page.wait_for_timeout(1500)
        s = state(page)
        check("guardó el ingreso quincenal", len(s["incomeSources"]) == 1 and s["incomeSources"][0]["amount"] == 300 and s["incomeSources"][0]["frequency"] == "quincenal", s["incomeSources"])
        check("guardó los pagos fijos (alquiler día 28, internet)", sorted((r["description"], r["day"]) for r in s["recurring"]) == [("Alquiler", 28), ("Internet", 1)], s["recurring"])
        check("guardó la deuda con su cuota", s["debts"][0]["entity"] == "Cashea" and s["debts"][0]["minPayment"] == 40)
        check("guardó cómo ver los montos y que ya lo vio", s["currency"] == "USDT" and s["onboarded"] is True)
        page.reload(); page.wait_for_selector("nav [data-tab]"); page.wait_for_timeout(800)
        check("no vuelve a salir al recargar", page.locator("#f-tour").count() == 0)
        page.click("nav [data-tab=mas]"); page.wait_for_timeout(300); page.click("[data-tour]"); page.wait_for_timeout(300)
        check("se puede ver otra vez desde Más", page.locator("#f-tour").count() == 1)
        page.click("[data-tskip]"); page.wait_for_timeout(300)
        check("sin errores (personal)", not errs, errs)

        print("== Tutorial y negocio de una abogada ==")
        page, errs = new_page(b)
        page.goto("/app#negocio"); page.wait_for_timeout(900)
        page.fill("input[name=business_name]", "Escritorio Pérez"); page.select_option("select[name=business_type]", "profesional")
        page.select_option("select[name=profession]", "abogado")
        page.fill("input[name=username]", "abogada"); page.fill("input[name=email]", "ab@x.com"); page.fill("input[name=password]", "Zorro-Plata-55231x")
        page.check("input[name=accept]"); page.get_by_role("button", name="Crear la cuenta del negocio").click()
        page.wait_for_selector("#f-tour", timeout=15000); page.wait_for_timeout(400)
        check("el tutorial del negocio tiene 8 pasos", "Paso 1 de 8" in text(page))
        nxt(page)
        check("el paso del negocio ya trae la profesión", page.input_value("#f-tour [name=type]") == "profesional" and page.input_value("#f-tour [name=profession]") == "abogado")
        shot(page, "t8-negocio")
        page.click("[data-tskip]"); page.wait_for_timeout(600)
        t = text(page)
        check("habla de cobros y por cobrar", "Cobraste hoy" in t and "+ Cobro" in t and "Por cobrar" in t, t[:400])
        page.click("[data-sheet=bizventa] >> nth=0"); page.wait_for_timeout(300)
        check("el cobro sugiere conceptos de abogado", page.locator("[data-biz='concept:Consulta legal']").count() == 1)
        page.fill("#f-sheet [name=amount]", "50"); page.click("#f-sheet button.btn:not(.o):has-text('Guardar')"); page.wait_for_timeout(400)
        check("pide el concepto del servicio", "concepto" in text(page).lower())
        page.click("[data-biz='concept:Consulta legal']"); page.check("#f-sheet [name=credit]"); page.fill("#f-sheet [name=client]", "Sr. Gómez")
        page.fill("#f-sheet [name=phone]", "04141234567"); page.click("#f-sheet button.btn:not(.o):has-text('Guardar')"); page.wait_for_timeout(700)
        s = state(page)
        check("guarda el cobro con concepto y por cobrar", s["sales"][0]["concept"] == "Consulta legal" and s["sales"][0]["credit"] is True, s["sales"])
        page.click("[data-biz='sub:clientes']"); page.wait_for_timeout(300)
        t = text(page)
        check("Clientes muestra lo que debe el Sr. Gómez", "Sr. Gómez" in t and "debe" in t and page.locator("text=Recordar").count() >= 1, t[:500])
        shot(page, "t9-clientes")
        page.click("[data-biz='sub:fiado']"); page.wait_for_timeout(300)
        check("Por cobrar lista al cliente", "Sr. Gómez" in text(page))
        check("sin errores (abogada)", not errs, errs)
        b.close()
finally:
    srv.terminate()
print(f"\n{'Todo correcto' if not fails else str(len(fails)) + ' con falla'}")
sys.exit(1 if fails else 0)
