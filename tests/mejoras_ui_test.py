"""Prueba en navegador de las mejoras pedidas por los clientes: gasto en otro día, ver montos en $/USDT/€/Bs.,
pagos fijos y compromisos sin duplicar, plan de pagos, Quinci (la mascota) y Enviar sugerencia."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import date, timedelta

from playwright.sync_api import sync_playwright

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PORT = 5091
tmp = tempfile.mkdtemp()
env = dict(os.environ, DATABASE_URL=f"sqlite:///{tmp}/x.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
           COOKIE_SECURE="false", RATELIMIT_STORAGE_URI="memory://", API_DEFAULT_LIMIT="5000 per minute", BREVO_API_KEY="")
srv = subprocess.Popen([sys.executable, "-c", f"import app; app.app.run(port={PORT})"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
fails = []


def check(name, cond, extra=""):
    print("  ok  " if cond else "  FALLA", name, "" if cond else extra)
    if not cond:
        fails.append(name)


def fake_rate(route):
    route.fulfill(status=200, content_type="application/json", body=json.dumps({"country": "VE", "rate": 100.0, "updated_at": "2026-10-27T12:00:00Z", "parallel": 125.0, "euro": 110.0}))


text = lambda page: page.evaluate("document.body.innerText")
state = lambda page: page.evaluate("async () => (await (await fetch('/api/state')).json()).data")


SHOTS = os.getenv("SHOTS")


def shot(page, name):
    if SHOTS:
        page.screenshot(path=os.path.join(SHOTS, name + ".png"), full_page=True)


def save(page, label="Guardar"):
    page.click(f"#f-sheet button.btn:not(.o):has-text('{label}')")
    page.wait_for_timeout(700)


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
        ctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE")
        ctx.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
        page = ctx.new_page()
        errors, dialogs = [], []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.route("**/api/rate*", fake_rate)
        answer = {"v": False}
        page.on("dialog", lambda d: (dialogs.append(d.message), d.accept() if answer["v"] else d.dismiss()))
        page.goto("/app"); page.wait_for_timeout(1000)
        page.get_by_text("Crea una gratis", exact=False).first.click()
        page.fill("input[name=username]", "ana"); page.fill("input[name=email]", "ana@x.com"); page.fill("input[name=password]", "Zorro-Plata-55231x")
        page.check("input[name=accept]"); page.get_by_role("button", name="Crear mi cuenta").click()
        page.wait_for_selector("nav [data-tab]", timeout=15000); page.wait_for_timeout(900)
        if page.locator("[data-tskip]").count():
            page.click("[data-tskip]"); page.wait_for_timeout(500)  # el tutorial de bienvenida se prueba en tour_ui_test.py
        for t in ("Acepto", "Aceptar"):
            if page.get_by_role("button", name=t).count():
                page.get_by_role("button", name=t).first.click(); page.wait_for_timeout(400)

        print("== Quinci, la mascota ==")
        shot(page, "1-quinci-hola")
        check("Quinci saluda a una cuenta nueva", page.locator(".qcard svg.quinci").count() == 1 and "Soy Quinci" in text(page), text(page)[:300])

        print("== Ingreso y gasto en otro día ==")
        page.click("[data-sheet=ingreso] >> nth=0"); page.fill("#f-sheet [name=name]", "Sueldo"); page.fill("#f-sheet [name=amount]", "400"); save(page)
        page.goto("/app"); page.wait_for_timeout(900)
        page.click("[data-sheet=gasto] >> nth=0")
        today = page.evaluate("ymd(new Date())")
        check("el gasto trae la fecha de hoy y no deja fechas futuras", page.input_value("#f-sheet [name=date]") == today and page.get_attribute("#f-sheet [name=date]", "max") == today)
        old = (date.fromisoformat(today) - timedelta(days=3)).isoformat()
        page.fill("#f-sheet [name=amount]", "20"); page.fill("#f-sheet [name=date]", old); page.fill("#f-sheet [name=description]", "Mercado del sábado"); save(page)
        e = state(page)["expenses"][0]
        check("el gasto queda en el día elegido", e["date"][:10] in (old, (date.fromisoformat(old) + timedelta(days=1)).isoformat()) and e["description"] == "Mercado del sábado", e)
        check("Quinci reacciona al anotar el gasto", page.locator(".qpop").count() == 1)
        page.wait_for_timeout(500); shot(page, "2-quinci-pop")
        page.click("nav [data-tab=movimientos]"); page.wait_for_timeout(400)
        page.click(f"[data-edit='{e['id']}']"); page.wait_for_timeout(300)
        check("al editar se ve la fecha guardada", page.input_value("#f-sheet [name=date]") == old)
        page.click("#x"); page.wait_for_timeout(200)

        print("== Ver montos en $, USDT, € o Bs. ==")
        page.click("nav [data-tab=inicio]"); page.wait_for_timeout(300)
        labels = page.locator(".head .seg button").all_inner_texts()
        check("el selector ofrece $, USDT, € y Bs.", labels == ["$", "USDT", "€", "Bs."], labels)
        page.click(".head .seg [data-cur=USDT]"); page.wait_for_timeout(300)
        t = text(page)
        check("en USDT: 400 $ BCV × 100 / 125 = USDT 320", "USDT 320,00" in t, t[:500])
        page.click(".head .seg [data-cur=EUR]"); page.wait_for_timeout(300)
        check("en euros: 400 × 100 / 110 = € 363,64", "€ 363,64" in text(page))
        page.wait_for_timeout(1500)
        check("la preferencia se guarda", state(page)["currency"] == "EUR")
        page.click(".head .seg [data-cur=USD]"); page.wait_for_timeout(300)

        print("== Pagos fijos y compromisos sin duplicar ==")
        page.click("nav [data-tab=pagos]"); page.wait_for_timeout(300)
        page.click("[data-psub=compromisos]"); page.wait_for_timeout(300)
        page.click("[data-sheet=recurrente]")
        page.fill("#f-sheet [name=description]", "Alquiler"); page.fill("#f-sheet [name=amount]", "120"); page.fill("#f-sheet [name=day]", "28")
        page.select_option("#f-sheet [name=mode]", "manual"); save(page)
        r = state(page)["recurring"][0]
        check("pago fijo manual guardado", r["mode"] == "manual" and r["day"] == 28, r)
        check("aparece en Compromisos › Cada mes con botón Pagar", "Cada mes" in text(page) and page.locator("[data-rpago]").count() == 1)
        page.click("[data-sheet=compromiso]"); page.fill("#f-sheet [name=description]", "Pago del alquiler"); page.fill("#f-sheet [name=amount]", "120"); save(page)
        check("avisa que ya existe como pago fijo y no lo duplica", any("pago fijo" in m for m in dialogs) and not state(page)["schoolExpenses"], dialogs)
        page.click("#x"); page.wait_for_timeout(200)
        page.click("[data-sheet=compromiso]"); page.fill("#f-sheet [name=description]", "Mensualidad colegio"); page.fill("#f-sheet [name=amount]", "50")
        page.select_option("#f-sheet [name=repeat]", "1"); save(page)
        s = state(page)
        check("un compromiso que se repite queda como pago fijo (no duplicado)", not s["schoolExpenses"] and any(x["description"] == "Mensualidad colegio" and x["mode"] == "manual" for x in s["recurring"]))
        n_before = len(s["expenses"])
        page.click("[data-rpago] >> nth=0"); save(page)
        s = state(page)
        key = today[:7]
        shot(page, "3-compromisos")
        check("pagar crea el gasto del mes y queda pagado", len(s["expenses"]) == n_before + 1 and s["expenses"][0]["id"].endswith("_" + key) and "Pagado este mes" in text(page), s["expenses"][0])
        page.click("nav [data-tab=movimientos]"); page.wait_for_timeout(300); page.click("[data-sub=fijos]"); page.wait_for_timeout(300)
        check("Movimientos › Fijos muestra los mismos pagos", "Alquiler" in text(page) and "Mensualidad colegio" in text(page) and "lo marcas al pagar" in text(page))

        print("== Plan de pagos ==")
        page.click("nav [data-tab=deudas]"); page.wait_for_timeout(300)
        for ent, amt, mp, rate_ in (("Cashea", "300", "30", ""), ("Tarjeta", "500", "25", "5")):
            page.click("[data-sheet=deuda] >> nth=0"); page.fill("#f-sheet [name=entity]", ent); page.fill("#f-sheet [name=amount]", amt); save(page)
            d = state(page)["debts"][0]
            page.click(f"[data-editd='{d['id']}']"); page.fill("#f-sheet [name=minPayment]", mp)
            if rate_:
                page.fill("#f-sheet [name=interestRate]", rate_)
            save(page)
        page.click("[data-sheet=plan] >> nth=0"); page.fill("#f-sheet [name=monthlyEssentials]", "150"); save(page)
        t = text(page); shot(page, "4-plan")
        check("el plan dice cuánto pagar al mes y por quincena", "Por quincena" in t and "Al mes" in t, t[:900])
        check("el plan muestra el orden de pago y cuándo termina cada deuda", "Orden de pago" in t and "La terminas en" in t)
        check("compara con la otra estrategia", "Si pagas primero" in t)
        page.click("[data-planmes]"); page.wait_for_timeout(700)
        pl = [p for p in state(page)["plannedDebtPayments"] if p.get("auto")]
        check("programa los pagos de este mes", len(pl) >= 1 and all(p["status"] == "planned" for p in pl), pl)
        check("no deja programarlo dos veces", page.locator("[data-planmes]").count() == 0)

        print("== Quinci con deudas ==")
        page.click("nav [data-tab=inicio]"); page.wait_for_timeout(400)
        check("Quinci habla de las deudas o los pagos", page.locator(".qcard").count() == 1 and any(w in text(page) for w in ("deudas", "vence", "Vas en camino")), text(page)[:600])
        shot(page, "5-quinci-deudas")
        page.click("[data-quincix]"); page.wait_for_timeout(300)
        check("se puede ocultar por hoy", page.locator(".qcard").count() == 0)

        print("== Enviar sugerencia ==")
        page.click("nav [data-tab=mas]"); page.wait_for_timeout(300)
        page.click("[data-sheet=sugerencia]"); page.select_option("#f-sheet [name=kind]", "idea")
        page.fill("#f-sheet [name=text]", "Quiero ver mis gastos en euros"); save(page, "Enviar")
        check("se envía y da las gracias", "Gracias" in text(page))
        check("sin errores en la página", not errors, errors)

        adm = b.new_context(base_url=f"http://127.0.0.1:{PORT}").new_page()
        adm.goto("/app"); adm.wait_for_timeout(500)
        adm.evaluate("""async()=>{const r=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'Zeta-Roble-9182x'})});return r.status}""")
        adm.goto("/admin"); adm.wait_for_timeout(900)
        adm.click("[data-tab=sugerencias]"); adm.wait_for_timeout(700)
        check("el admin ve la sugerencia con el usuario", "Quiero ver mis gastos en euros" in text(adm) and "ana" in text(adm))
        adm.select_option("select[data-sug]", "hecha"); adm.wait_for_timeout(600)
        check("el admin la marca como hecha", "Estado guardado" in text(adm))
        b.close()
finally:
    srv.terminate()
print(f"\n{'Todo correcto' if not fails else str(len(fails)) + ' con falla'}")
sys.exit(1 if fails else 0)
