"""Prueba en navegador real de Quincena VE Negocio: activar, vender, fiar, abonar, gastos, caja, me pago a mí,
productos e inventario, recibo, Excel, vendedor y modo vendedor."""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PORT = 5083
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
    route.fulfill(status=200, content_type="application/json", body=json.dumps({"country": "VE", "rate": 100.0, "updated_at": "2026-10-27T12:00:00Z", "parallel": 120.0, "euro": 110.0}))


def text(page):
    return page.evaluate("document.body.innerText")


def state(page):
    return page.evaluate("async () => (await (await fetch('/api/state')).json()).data")


def sheet_save(page, label="Guardar"):
    page.click(f"#f-sheet button.btn:not(.o):has-text('{label}')")
    page.wait_for_timeout(700)


try:
    for _ in range(80):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/healthz", timeout=1)
            break
        except Exception:
            time.sleep(0.25)
    urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/api/setup", method="POST", headers={"Content-Type": "application/json"},
                           data=json.dumps({"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"}).encode()))
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        ctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE")
        ctx.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.route("**/api/rate*", fake_rate)
        page.on("dialog", lambda d: d.dismiss())
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

        print("== Activar el negocio ==")
        check("sin negocio no hay pestaña Negocio", page.locator("nav [data-tab=negocio]").count() == 0)
        page.click("nav [data-tab=mas]"); page.wait_for_timeout(300)
        check("Más ofrece activar el negocio", "¿Tienes un negocio?" in text(page))
        page.click("[data-biz='conf']"); page.fill("#f-sheet [name=name]", "Bodega Ana"); sheet_save(page, "Activar mi negocio")
        check("aparece la pestaña Negocio y se abre", page.locator("nav [data-tab=negocio]").count() == 1 and "Vendiste hoy" in text(page))
        check("el botón + registra ventas en Negocio", page.get_attribute(".fab", "data-sheet") == "bizventa")

        print("== Vender ==")
        page.click("[data-sheet=bizventa] >> nth=0"); page.fill("#f-sheet [name=amount]", "500"); page.select_option("#f-sheet [name=method]", "pago_movil"); sheet_save(page)
        page.click("[data-sheet=bizventa] >> nth=0"); page.fill("#f-sheet [name=amount]", "10"); page.select_option("#f-sheet [name=currency]", "USD"); page.select_option("#f-sheet [name=method]", "efectivo_usd"); sheet_save(page)
        t = text(page)
        check("vendiste hoy = 500 Bs (5 $) + 10 $ = 15 $", "$ 15,00" in t, t[:300])
        check("cobrado hoy por medio de pago", "Pago móvil" in t and "Efectivo $" in t)
        check("efectivo en dólares esperado en caja = 10 $", "$ 10,00" in t)
        page.click("[data-sheet=bizventa] >> nth=0"); page.fill("#f-sheet [name=amount]", "8"); page.select_option("#f-sheet [name=currency]", "USD")
        page.check("#f-sheet [name=credit]"); sheet_save(page)
        check("fiado sin cliente se rechaza", page.locator("#f-sheet").count() == 1 and "nombre del cliente" in text(page))
        page.fill("#f-sheet [name=client]", "María"); page.fill("#f-sheet [name=phone]", "0414 123 4567"); sheet_save(page)
        d = state(page)
        check("se guardaron 3 ventas, una fiada a María", len(d["sales"]) == 3 and d["sales"][0]["credit"] and d["sales"][0]["client"] == "María", d["sales"])
        check("la venta en Bs. guarda la tasa del día", any(s["currency"] == "BS" and s["rateUsed"] == 100 for s in d["sales"]))

        print("== Fiado ==")
        page.click("[data-biz='sub:fiado']"); page.wait_for_timeout(200)
        t = text(page)
        check("María debe 8 $", "María" in t and "$ 8,00" in t)
        check("botón Recordar abre WhatsApp con su número", "wa.me/584141234567" in page.inner_html("#app"))
        page.click("[data-biz^='abono:']"); page.fill("#f-sheet [name=amount]", "3"); page.select_option("#f-sheet [name=currency]", "USD"); sheet_save(page)
        check("tras abonar 3 $ debe 5 $", "$ 5,00" in text(page))
        page.click("[data-biz^='abono:']"); page.fill("#f-sheet [name=amount]", "50"); page.select_option("#f-sheet [name=currency]", "USD"); sheet_save(page)
        check("abono mayor a la deuda se rechaza", "más de lo que debe" in text(page))
        page.click("#x")

        print("== Gastos, me pago a mí y caja ==")
        page.click("[data-biz='sub:gastos']"); page.wait_for_timeout(200)
        page.click("[data-sheet=bizgasto] >> nth=0"); page.fill("#f-sheet [name=amount]", "4"); page.select_option("#f-sheet [name=currency]", "USD")
        page.select_option("#f-sheet [name=category]", "mercancia"); page.select_option("#f-sheet [name=method]", "efectivo_usd"); sheet_save(page)
        page.click("[data-sheet=bizretiro]"); page.fill("#f-sheet [name=amount]", "2"); sheet_save(page)
        d = state(page)
        check("gasto y retiro en el negocio, no en gastos personales", len(d["bizExpenses"]) == 2 and d["expenses"] == [], d["bizExpenses"])
        check("me pago a mí crea un ingreso personal del negocio", d["incomes"][0]["kind"] == "negocio" and d["incomes"][0]["amount"] == 2)
        page.click("[data-biz='sub:hoy']"); page.wait_for_timeout(200)
        check("caja en $: 10 vendido + 3 abono − 4 mercancía − 2 retiro = 7 $", "$ 7,00" in text(page), text(page)[:600])
        page.click("[data-sheet=bizcierre]"); page.fill("#f-sheet [name=countedUsd]", "7"); page.fill("#f-sheet [name=countedBs]", "0"); sheet_save(page)
        check("al cerrar la caja dice si cuadra", "Cerraste la caja" in text(page))

        print("== Mes ==")
        page.click("[data-biz='sub:mes']"); page.wait_for_timeout(200)
        t = text(page)
        check("ganancia = 23 $ en ventas − 4 $ de mercancía = 19 $", "$ 19,00" in t, t[:500])
        check("sin Plus, el Excel pide Negocio Plus", "parte de Negocio Plus" in t)
        check("aparece la tarjeta de Gigadevs", "¿Tu negocio creció?" in t)
        check("aclara que no es factura fiscal", "no emite facturas fiscales" in t)

        print("== Negocio Plus ==")
        page.click("[data-biz='sub:productos']"); page.wait_for_timeout(200)
        check("sin Plus, Productos muestra cómo suscribirse", "Suscribirme a Negocio Plus" in text(page))
        actx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE")
        actx.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
        ap = actx.new_page(); ap.on("pageerror", lambda e: errors.append(str(e))); ap.route("**/api/rate*", fake_rate)
        ap.goto("/app"); ap.wait_for_timeout(800)
        ap.fill("input[name=username]", "admin"); ap.fill("input[name=password]", "Zeta-Roble-9182x"); ap.keyboard.press("Enter")
        ap.wait_for_selector("nav [data-tab]"); ap.wait_for_timeout(600)
        if ap.locator("[data-tskip]").count():
            ap.click("[data-tskip]"); ap.wait_for_timeout(400)
        for t in ("Acepto", "Aceptar"):
            if ap.get_by_role("button", name=t).count():
                ap.get_by_role("button", name=t).first.click(); ap.wait_for_timeout(400)
        ap.click("nav [data-tab=mas]"); ap.wait_for_timeout(300)
        check("el administrador ve Administrar Negocio Plus en Más", "Administrar Negocio Plus" in text(ap))
        ap.click("[data-sheet=bizadmin]"); ap.fill("#f-sheet [name=username]", "noexiste"); sheet_save(ap, "Aplicar")
        check("con un usuario que no existe avisa", "No existe una cuenta" in text(ap))
        ap.fill("#f-sheet [name=username]", "ana"); ap.fill("#f-sheet [name=days]", "30"); sheet_save(ap, "Aplicar")
        check("activa Plus a ana por 30 días", "Plus activo para ana hasta" in text(ap), text(ap)[-200:])
        actx.close()
        page.reload(); page.wait_for_selector("nav [data-tab]"); page.wait_for_timeout(800)
        page.click("nav [data-tab=negocio]"); page.click("[data-biz='sub:productos']"); page.wait_for_timeout(200)
        page.click("[data-biz='prod:']"); page.fill("#f-sheet [name=name]", "Harina"); page.fill("#f-sheet [name=price]", "1,5"); page.fill("#f-sheet [name=cost]", "1")
        page.check("#f-sheet [name=track]"); page.fill("#f-sheet [name=stock]", "20"); sheet_save(page)
        check("producto con precio en $ y Bs.", "Harina" in text(page) and "$ 1,50" in text(page) and "Bs. 150,00" in text(page))
        page.click("[data-sheet=bizventa] >> nth=0") if page.locator("[data-sheet=bizventa]").count() > 1 else page.click(".fab")
        page.click("[data-biz^='add:']"); page.click("[data-biz^='add:']"); page.wait_for_timeout(100)
        check("tocar el producto dos veces llena el monto en Bs. (2 × 1,5 $ × 100)", page.input_value("#f-sheet [name=amount]") == "300", page.input_value("#f-sheet [name=amount]"))
        page.select_option("#f-sheet [name=currency]", "USD"); page.wait_for_timeout(100)
        check("al cambiar a dólares el monto se recalcula a 3", page.input_value("#f-sheet [name=amount]") == "3", page.input_value("#f-sheet [name=amount]"))
        page.fill("#f-sheet [name=phone]", "04241112233"); sheet_save(page)
        page.click("[data-biz='sub:productos']"); page.wait_for_timeout(200)
        check("el inventario baja a 18", "quedan 18" in text(page), text(page)[-300:])
        page.click("[data-biz='sub:ventas']"); page.wait_for_timeout(200)
        with ctx.expect_page() as popup:
            page.click("[data-biz^='recibo:'] >> nth=0")
        url = urllib.parse.unquote_plus(popup.value.url)
        check("el recibo abre WhatsApp al cliente con el detalle y 'No es factura fiscal'", "584241112233" in url and "2 × Harina" in url and "No es factura fiscal" in url, url[:300])
        popup.value.close()
        page.click("[data-biz='sub:mes']"); page.wait_for_timeout(200)
        with page.expect_download() as dl:
            page.click("[data-biz=excel]")
        path = dl.value.path(); csv = open(path, encoding="utf-8-sig").read()
        check("el Excel trae ventas, abonos y gastos del mes", csv.startswith("Tipo;Fecha") and csv.count("Venta") >= 4 and "Abono de fiado" in csv and "Retiro del dueño" in csv, csv[:300])
        check("con Plus, Mes muestra el costo de lo vendido", "Costo de lo vendido" in text(page))

        print("== Vendedores ==")
        page.click("[data-biz='sub:equipo']"); page.wait_for_timeout(600)
        page.click("[data-sheet=bizvend]"); page.fill("#f-sheet [name=username]", "pedro"); page.fill("#f-sheet [name=password]", "Cobre-Rio-77341x"); sheet_save(page, "Crear vendedor")
        page.wait_for_timeout(600)
        check("el vendedor aparece en Equipo", "pedro" in text(page) and "Activo" in text(page))

        ctx2 = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE")
        ctx2.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
        sp = ctx2.new_page(); sp.on("pageerror", lambda e: errors.append(str(e))); sp.route("**/api/rate*", fake_rate)
        sp.goto("/app"); sp.wait_for_timeout(900)
        sp.fill("input[name=username]", "pedro"); sp.fill("input[name=password]", "Cobre-Rio-77341x"); sp.keyboard.press("Enter")
        sp.wait_for_selector("#f-sell", timeout=15000); sp.wait_for_timeout(500)
        t = text(sp)
        check("el vendedor ve solo la pantalla de ventas del negocio", "Bodega Ana" in t and "Vendedor: pedro" in t and sp.locator("nav").count() == 0, t[:300])
        sp.click("#f-sell [data-biz^='add:']"); sp.wait_for_timeout(100)
        sp.select_option("#f-sell [name=method]", "pago_movil"); sp.click("#f-sell button.btn"); sp.wait_for_timeout(900)
        check("registra la venta y la ve en su lista", "Tus ventas de hoy" in text(sp) and "$ 1,50" in text(sp), text(sp)[:400])
        page.reload(); page.wait_for_selector("nav [data-tab]"); page.wait_for_timeout(800)
        page.click("nav [data-tab=negocio]"); page.click("[data-biz='sub:ventas']"); page.wait_for_timeout(200)
        check("la dueña ve la venta con el nombre del vendedor", "vendió pedro" in text(page))
        print("== Registro de un negocio desde la presentación ==")
        nctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}", timezone_id="America/Caracas", locale="es-VE")
        nctx.add_init_script("try{localStorage.setItem('qck','1');localStorage.setItem('qapk','1')}catch(e){}")
        npg = nctx.new_page(); npg.on("pageerror", lambda e: errors.append(str(e))); npg.route("**/api/rate*", fake_rate)
        npg.goto("/"); npg.wait_for_timeout(400)
        check("la presentación tiene la sección Para tu negocio", "Tu negocio, en $ y en Bs." in text(npg) and "abogados" in text(npg).lower())
        npg.click("a[href='/app#negocio'] >> nth=0"); npg.wait_for_selector("[name=business_name]", timeout=10000)
        check("el botón abre 'Crea la cuenta de tu negocio'", "Crea la cuenta de tu negocio" in text(npg))
        npg.fill("[name=business_name]", "Panadería Luis"); npg.fill("input[name=username]", "luis"); npg.fill("input[name=email]", "luis@x.com")
        npg.fill("input[name=password]", "Zorro-Plata-55231x"); npg.check("input[name=accept]")
        npg.get_by_role("button", name="Crear la cuenta del negocio").click()
        npg.wait_for_selector("nav [data-tab=negocio]", timeout=15000); npg.wait_for_timeout(600)
        if npg.locator("[data-tskip]").count():
            npg.click("[data-tskip]"); npg.wait_for_timeout(400)
        check("entra directo a la pestaña Negocio con su nombre", "Panadería Luis" in text(npg) and "Vendiste hoy" in text(npg), text(npg)[:300])
        npg.click("[data-biz=conf] >> nth=0"); npg.wait_for_timeout(200)
        check("la configuración ofrece BCV dólar, BCV euro y USDT con su valor", all(x in text(npg) for x in ("BCV euro · Bs. 110,00", "USDT · Bs. 120,00", "BCV dólar · Bs. 100,00")), text(npg)[:600])
        npg.select_option("#f-sheet [name=rateKind]", "bcv_eur"); sheet_save(npg)
        npg.click("#app .btn[data-sheet=bizventa]"); npg.fill("#f-sheet [name=amount]", "10"); npg.select_option("#f-sheet [name=currency]", "USD"); sheet_save(npg)
        t = text(npg)
        check("con el euro BCV los montos salen en € y en Bs. a 110", "€ 10,00" in t and "Bs. 1.100,00" in t and "a la tasa BCV euro" in t, t[:400])
        npg.click("[data-biz='sub:gastos']"); npg.click("[data-sheet=bizretiro]"); npg.fill("#f-sheet [name=amount]", "10"); sheet_save(npg)
        inc = state(npg)["incomes"][0]
        check("me pago 10 € entra a la quincena como 11 $ (110/100)", inc["amount"] == 11 and inc["currency"] == "USD", inc)
        npg.click("nav [data-tab=inicio]"); npg.wait_for_timeout(200)
        check("Inicio muestra el euro BCV y el botón Actualizar", "€ Bs. 110,00" in text(npg) and npg.locator("#rfx").count() == 1)
        npg.click("#rfx"); npg.wait_for_timeout(500)
        check("Actualizar pide la tasa de nuevo y lo confirma", "Tasa BCV: Bs. 100,00" in text(npg), text(npg)[-300:])
        nctx.close()
        lctx = b.new_context(base_url=f"http://127.0.0.1:{PORT}"); lp = lctx.new_page(); lp.goto("/app"); lp.wait_for_timeout(800)
        check("la pantalla de entrada ofrece crear la cuenta de un negocio", "¿Tienes un negocio?" in text(lp))
        lctx.close()
        check("sin errores de JavaScript", not errors, errors)
        b.close()
finally:
    srv.terminate()

print(f"\n{'Todo bien' if not fails else str(len(fails)) + ' con falla: ' + ', '.join(fails)}")
sys.exit(1 if fails else 0)
