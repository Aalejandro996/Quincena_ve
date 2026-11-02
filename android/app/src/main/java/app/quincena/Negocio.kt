package app.quincena

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

// Quincena VE Negocio, igual que static/negocio.js en la web: ventas, fiado, gastos del negocio, caja y ganancia del mes;
// con Negocio Plus, productos e inventario, recibo, Excel para el contador y vendedores. El dinero del negocio va aparte
// de la quincena personal: no entra en gastos ni presupuesto.

const val BIZ_WA = "584242588321"
val SALE_METHODS = linkedMapOf(
    "pago_movil" to "Pago móvil", "efectivo_bs" to "Efectivo Bs.", "efectivo_usd" to "Efectivo $", "zelle" to "Zelle",
    "binance" to "Binance", "punto" to "Punto de venta", "transferencia" to "Transferencia", "otro" to "Otro",
)
val BIZ_CATS = linkedMapOf(
    "mercancia" to "Mercancía", "alquiler" to "Alquiler", "servicios" to "Servicios", "sueldos" to "Sueldos",
    "transporte" to "Transporte", "impuestos" to "Impuestos", "otro" to "Otro",
)
// Tipos de negocio. Los de servicios (profesionales, salud, oficios, clases, creativos) hablan de cobros, servicios y clientes.
val BIZ_TYPES = linkedMapOf("comercio" to "Bodega o tienda", "comida" to "Comida y bebidas", "belleza" to "Belleza y estética", "online" to "Ventas por redes",
    "profesional" to "Servicios profesionales", "salud" to "Salud (médico, odontólogo, psicólogo…)", "tecnico" to "Oficios y técnicos",
    "educacion" to "Clases y tutorías", "creativo" to "Diseño, fotografía y tecnología", "otro" to "Otro")
val BIZ_PROFS = linkedMapOf("" to "—", "abogado" to "Abogado(a)", "contador" to "Contador(a)", "administrador" to "Administrador(a)",
    "consultor" to "Consultor(a)", "arquitecto" to "Arquitecto(a) o ingeniero(a)", "otro" to "Otra profesión")
private val SERVICE_TYPES = setOf("profesional", "salud", "tecnico", "educacion", "creativo")
/** Conceptos sugeridos al cobrar, según la profesión o el tipo de servicio (igual que la web). */
private val BIZ_CONCEPTS = mapOf(
    "abogado" to listOf("Consulta legal", "Redacción de documento", "Poder", "Representación en juicio", "Asesoría mensual"),
    "contador" to listOf("Declaración de ISLR", "Declaración de IVA", "Contabilidad mensual", "Libros contables", "Estados financieros"),
    "administrador" to listOf("Administración mensual", "Nómina", "Gestión de cobranza", "Trámites"),
    "consultor" to listOf("Consultoría", "Asesoría por hora", "Informe"), "arquitecto" to listOf("Proyecto", "Planos", "Inspección", "Asesoría técnica"),
    "salud" to listOf("Consulta", "Control", "Tratamiento", "Procedimiento"), "tecnico" to listOf("Visita técnica", "Reparación", "Instalación", "Mantenimiento"),
    "educacion" to listOf("Clase", "Mensualidad", "Curso", "Tutoría"), "creativo" to listOf("Diseño", "Sesión de fotos", "Desarrollo web", "Mantenimiento mensual"),
    "profesional" to listOf("Consulta", "Asesoría", "Servicio"),
)
fun bizType(d: JsonObject) = d.obj("business").str("type")?.takeIf { it in BIZ_TYPES } ?: "comercio"
fun bizSvc(d: JsonObject) = bizType(d) in SERVICE_TYPES
fun bizConcepts(d: JsonObject): List<String> {
    val prof = d.obj("business").str("profession").orEmpty()
    return BIZ_CONCEPTS[if (bizType(d) == "profesional" && prof in BIZ_CONCEPTS) prof else bizType(d)] ?: emptyList()
}
/** Palabras de la pantalla según el tipo: ventas y fiado (comercio) o cobros y por cobrar (servicios). */
class BizV(val sale: String, val sales: String, val n1: String, val nn: String, val today: String, val add: String, val neu: String, val credit: String,
    val creditBox: String, val prods: String, val ofToday: String, val none: String, val first: String, val noCredit: String, val receipt: String, val thanks: String)
fun bizV(d: JsonObject) = if (bizSvc(d)) BizV("Cobro", "Cobros", "cobro", "cobros", "Cobraste hoy", "+ Cobro", "Nuevo cobro", "Por cobrar",
    "Me paga después (queda por cobrar)", "Servicios", "Cobros de hoy", "Aún no hay cobros.", "Registra tu primer cobro con el botón + Cobro.",
    "Nadie te debe. Cuando un cliente te pague después, marca \"Me paga después\" en el cobro.", "Recibo de honorarios", "¡Gracias por su confianza!")
else BizV("Venta", "Ventas", "venta", "ventas", "Vendiste hoy", "+ Venta", "Nueva venta", "Fiado", "Es fiado (me paga después)", "Productos",
    "Ventas de hoy", "Aún no hay ventas.", "Registra tu primera venta con el botón + Venta.", "Nadie te debe. Cuando vendas fiado, marca \"Es fiado\" en la venta.",
    "Recibo de venta", "¡Gracias por tu compra!")
private fun bizSubs(d: JsonObject): List<Pair<String, String>> { val v = bizV(d)
    return listOf("hoy" to "Hoy", "ventas" to v.sales, "fiado" to v.credit, "clientes" to "Clientes", "gastos" to "Gastos", "mes" to "Mes", "productos" to v.prods, "equipo" to "Equipo") }
private val PLUS_SUBS = setOf("productos", "equipo")

// ---------- Tasa del negocio ----------

/** Tasa con la que trabaja el negocio: dólar BCV, euro BCV o USDT (paralelo). */
val BIZ_RATES = linkedMapOf("bcv_usd" to "BCV dólar", "bcv_eur" to "BCV euro", "usdt" to "USDT")
fun bizKind(d: JsonObject) = d.obj("business").str("rateKind")?.takeIf { it in BIZ_RATES } ?: "bcv_usd"
fun bizRateOf(d: JsonObject, k: String) = when (k) { "bcv_eur" -> d.num("euroRate"); "usdt" -> marketRateOf(d); else -> rateOf(d) }
fun bizRate(d: JsonObject) = bizRateOf(d, bizKind(d)).takeIf { it > 0 } ?: rateOf(d)
fun bizSym(d: JsonObject) = if (bizKind(d) == "bcv_eur") "€" else "$"
fun bizCurName(d: JsonObject) = if (bizKind(d) == "bcv_eur") "euros" else "dólares"
fun divisaLabel(sym: String) = if (sym == "€") "Euros (€)" else "Dólares ($)"

/** Como Fmt, pero la divisa del negocio puede ser el euro (€). */
class BizFmt(val hide: Boolean, val bs: Boolean, val rate: Double, val sym: String = "$") {
    private val base = Fmt(hide, bs, rate)
    fun show(u: Double) = if (hide) "••••" else if (bs) base.show(u) else sym + " " + base.plain(u)
    fun orig(a: Double, cur: String?) = if (cur == "BS") base.orig(a, "BS") else if (hide) "••••" else sym + " " + base.plain(a)
    fun plain(v: Double) = base.plain(v)
}

fun bizFmt(st: AppState) = BizFmt(st.hide, st.fmt.bs, bizRate(st.repo.data), bizSym(st.repo.data))

// ---------- Cálculos ----------

fun bizOn(d: JsonObject) = d.obj("business").bool("enabled") == true
fun bizUsd(d: JsonObject, x: JsonObject): Double {
    if (x.str("currency") != "BS") return x.num("amount")
    val r = x.num("rateUsed").takeIf { it > 0 } ?: bizRate(d).takeIf { it > 0 } ?: 1.0
    return x.num("amount") / r
}
fun salePaid(d: JsonObject, s: JsonObject) = d.arr("bizPayments").filter { it.str("saleId") == s.id }.sumOf { bizUsd(d, it) }
fun salePending(d: JsonObject, s: JsonObject) = if (s.bool("credit") == true) max(0.0, bizUsd(d, s) - salePaid(d, s)) else 0.0
fun activeProducts(d: JsonObject) = d.arr("products").filter { it.bool("archived") != true }.sortedBy { (it.str("name") ?: "").lowercase() }

/** Existencia: lo contado en stockDate menos lo vendido desde entonces. */
fun stockOf(d: JsonObject, p: JsonObject): Double {
    var n = p.num("stock")
    val since = p.str("stockDate").orEmpty()
    for (s in d.arr("sales")) if (since.isEmpty() || (s.str("date") ?: "") >= since)
        for (it in s.arr("items")) if (it.str("productId") == p.id) n -= it.num("qty")
    return n
}

data class BizDay(val sold: Double, val n: Int, val inn: Map<String, Double>, val cashBs: Double, val cashUsd: Double)

/** Lo vendido en el día, lo cobrado por medio de pago y el efectivo que debería haber en caja. */
fun bizDay(d: JsonObject, day: LocalDate): BizDay {
    var sold = 0.0; var n = 0; var cashBs = 0.0; var cashUsd = 0.0
    val inn = linkedMapOf<String, Double>()
    fun add(x: JsonObject, sign: Int) {
        val m = x.str("method") ?: "otro"
        if (sign > 0) inn[m] = (inn[m] ?: 0.0) + bizUsd(d, x)
        val r = x.num("rateUsed").takeIf { it > 0 } ?: bizRate(d)
        if (m == "efectivo_bs") cashBs += sign * (if (x.str("currency") == "BS") x.num("amount") else x.num("amount") * r)
        if (m == "efectivo_usd") cashUsd += sign * (if (x.str("currency") == "BS") bizUsd(d, x) else x.num("amount"))
    }
    for (s in d.arr("sales")) if (localDay(s.str("date")) == day) { sold += bizUsd(d, s); n++; if (s.bool("credit") != true) add(s, 1) }
    for (p in d.arr("bizPayments")) if (localDay(p.str("date")) == day) add(p, 1)
    for (e in d.arr("bizExpenses")) if (localDay(e.str("date")) == day) add(e, -1)
    return BizDay(sold, n, inn, cashBs, cashUsd)
}

data class BizMonth(val sales: Double, val n: Int, val cogs: Double, val buy: Double, val exp: Double, val ret: Double, val pending: Double) {
    val profit get() = sales - buy - exp
}

fun bizMonth(d: JsonObject, ym: YearMonth): BizMonth {
    var sales = 0.0; var n = 0; var cogs = 0.0; var buy = 0.0; var exp = 0.0; var ret = 0.0
    val prods = d.arr("products").associateBy { it.id }
    for (s in d.arr("sales")) {
        val day = localDay(s.str("date")) ?: continue
        if (YearMonth.from(day) != ym) continue
        sales += bizUsd(d, s); n++
        for (it in s.arr("items")) cogs += (prods[it.str("productId")]?.num("cost") ?: 0.0) * it.num("qty")
    }
    for (e in d.arr("bizExpenses")) {
        val day = localDay(e.str("date")) ?: continue
        if (YearMonth.from(day) != ym) continue
        when (e.str("category")) { "retiro" -> ret += bizUsd(d, e); "mercancia" -> buy += bizUsd(d, e); else -> exp += bizUsd(d, e) }
    }
    return BizMonth(sales, n, cogs, buy, exp, ret, d.arr("sales").sumOf { salePending(d, it) })
}

/** Saldo de las cuentas: ventas de contado y abonos entran, gastos del negocio salen (si se eligió cuenta). */
fun bizAccFlow(d: JsonObject, a: JsonObject): Double {
    val cur = a.str("currency")
    var b = 0.0
    for (s in d.arr("sales")) if (s.str("accountId") == a.id && s.bool("credit") != true) b += conv(d, s.num("amount"), s.str("currency"), cur)
    for (p in d.arr("bizPayments")) if (p.str("accountId") == a.id) b += conv(d, p.num("amount"), p.str("currency"), cur)
    for (e in d.arr("bizExpenses")) if (e.str("accountId") == a.id) b -= conv(d, e.num("amount"), e.str("currency"), cur)
    return b
}

fun waLink(phone: String?, text: String): String {
    var p = phone.orEmpty().filter(Char::isDigit)
    if (p.startsWith("0")) p = "58" + p.drop(1)
    return "https://wa.me/$p?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun dayText(s: String?) = localDay(s)?.let { shortDate(it) } ?: ""

fun receiptText(d: JsonObject, s: JsonObject): String {
    val f = BizFmt(false, false, bizRate(d), bizSym(d))
    val b = d.obj("business")
    val items = s.arr("items").joinToString("\n") { "${inputMoney(it.num("qty"))} × ${it.str("name")}: ${f.orig(it.num("price") * it.num("qty"), "USD")}" }
    val pend = salePending(d, s)
    return buildString {
        append(b.str("name")?.ifBlank { null } ?: "Mi negocio").append("\n").append(bizV(d).receipt).append(" · ").append(dayText(s.str("date"))).append('\n')
        s.str("client")?.takeIf { it.isNotBlank() }?.let { append("Cliente: ").append(it).append('\n') }
        s.str("concept")?.takeIf { it.isNotBlank() }?.let { append("Concepto: ").append(it).append('\n') }
        if (items.isNotEmpty()) append(items).append('\n')
        append("Total: ").append(f.orig(s.num("amount"), s.str("currency")))
        if (s.str("currency") == "BS") append(" (").append(f.orig(bizUsd(d, s), "USD")).append(")")
        append("\nPago: ").append(if (s.bool("credit") == true) bizV(d).credit.lowercase() else SALE_METHODS[s.str("method")] ?: "")
        if (pend > .005) append("\nPor pagar: ").append(f.orig(pend, "USD"))
        append("\n").append(bizV(d).thanks)
        b.str("phone")?.takeIf { it.isNotBlank() }?.let { append("\nWhatsApp: ").append(it) }
        append("\n\nNo es factura fiscal.")
    }
}

/** Ventas, abonos y gastos del mes separados por punto y coma: lo abre Excel. */
fun bizCsv(d: JsonObject, ym: YearMonth): String {
    fun cell(v: String) = if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    fun r2(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',')
    fun inMonth(x: JsonObject) = localDay(x.str("date"))?.let { YearMonth.from(it) == ym } == true
    val dv = if (bizSym(d) == "€") "EUR" else "USD"
    fun cur(x: JsonObject) = if (x.str("currency") == "BS") "Bs" else dv
    fun rate(x: JsonObject) = x.num("rateUsed").takeIf { it > 0 }?.let { r2(it) } ?: ""
    val rows = mutableListOf(listOf("Tipo", "Fecha", "Detalle", "Medio", "Moneda", "Monto", "Tasa", "Monto en $dv", "Vendedor"))
    for (s in d.arr("sales")) if (inMonth(s)) rows.add(listOf(
        if (s.bool("credit") == true) "Venta fiada" else "Venta", localDay(s.str("date")).toString(),
        listOfNotNull(s.str("client"), s.arr("items").joinToString(" + ") { "${inputMoney(it.num("qty"))} ${it.str("name")}" }, s.str("note")).filter { it.isNotBlank() }.joinToString(" · "),
        SALE_METHODS[s.str("method")] ?: "", cur(s), r2(s.num("amount")), rate(s), r2(bizUsd(d, s)), s.str("seller") ?: ""))
    for (p in d.arr("bizPayments")) if (inMonth(p)) rows.add(listOf(
        "Abono de fiado", localDay(p.str("date")).toString(), d.arr("sales").firstOrNull { it.id == p.str("saleId") }?.str("client") ?: "",
        SALE_METHODS[p.str("method")] ?: "", cur(p), r2(p.num("amount")), rate(p), r2(bizUsd(d, p)), ""))
    for (e in d.arr("bizExpenses")) if (inMonth(e)) rows.add(listOf(
        if (e.str("category") == "retiro") "Retiro del dueño" else "Gasto: ${BIZ_CATS[e.str("category")] ?: ""}", localDay(e.str("date")).toString(),
        listOfNotNull(e.str("supplier"), e.str("description")).filter { it.isNotBlank() }.joinToString(" · "),
        SALE_METHODS[e.str("method")] ?: "", cur(e), r2(e.num("amount")), rate(e), r2(bizUsd(d, e)), ""))
    return "﻿" + rows.joinToString("\r\n") { r -> r.joinToString(";") { cell(it) } }
}

// ---------- Pantalla Negocio ----------

@Composable
private fun Hero(label: String, big: String, small: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(pal.g).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(loc(label), color = Color.White)
        Big(big, color = Color.White)
        Text(loc(small), color = Color.White.copy(alpha = .9f), fontSize = 14.sp)
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Color.Unspecified) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(loc(label), Modifier.weight(1f)); B(value, color = color) }

@Composable
fun PlusLock(st: AppState, what: String) {
    val ctx = LocalContext.current
    val repo = st.repo
    QCard {
        B("$what es parte de Negocio Plus")
        Mut("Negocio Plus suma catálogo de productos con inventario, recibos para WhatsApp, Excel para tu contador y vendedores que registran ventas desde su teléfono.")
        Btn("Pedir Negocio Plus por WhatsApp", { openUrl(ctx, waLink(BIZ_WA, "Hola, quiero activar Negocio Plus en Quincena VE. Mi usuario es ${repo.username}")) })
    }
}

@Composable
fun GigadevsCard() {
    val ctx = LocalContext.current
    QCard {
        B("¿Tu negocio creció?")
        Mut("Gigadevs, los creadores de Quincena VE, hacen sistemas administrativos a la medida, tiendas en línea y páginas web.")
        Btn("Hablar con Gigadevs", { openUrl(ctx, waLink(BIZ_WA, "Hola Gigadevs, uso Quincena VE Negocio y quiero información de un sistema para mi negocio.")) }, outline = true)
    }
}

@Composable
fun BizMasCard(st: AppState) {
    val d = st.repo.data
    val b = d.obj("business")
    QCard {
        if (bizOn(d)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                B("Mi negocio")
                if (st.repo.plus) Pill("Plus", pal.gs, pal.g)
            }
            Mut("${b.str("name")?.ifBlank { null } ?: "Sin nombre"} · las ventas y gastos del negocio van aparte de tu quincena.")
            Btn("Configurar el negocio", { st.open("bizconf") }, outline = true)
        } else {
            B("¿Tienes un negocio?")
            Mut("Activa el modo Negocio: ventas en \$ y Bs. con la tasa del día, fiado, gastos del negocio, cierre de caja y ganancia del mes. Aparte de tu quincena.")
            Btn("Activar mi negocio", { st.open("bizconf") })
        }
    }
}

@Composable
fun Negocio(st: AppState) {
    val b = st.repo.data.obj("business")
    Head(st)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(b.str("name")?.ifBlank { null } ?: "Mi negocio", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Link("Configurar", { st.open("bizconf") })
    }
    Chips {
        bizSubs(st.repo.data).forEach { (k, l) -> Chip(if (k in PLUS_SUBS && !st.repo.plus) "$l · Plus" else l, st.bizSub == k) { st.bizSub = k } }
    }
    when (st.bizSub) {
        "ventas" -> BizVentas(st)
        "fiado" -> BizFiado(st)
        "clientes" -> BizClientes(st)
        "gastos" -> BizGastos(st)
        "mes" -> BizMes(st)
        "productos" -> if (st.repo.plus) BizProductos(st) else PlusLock(st, if (bizSvc(st.repo.data)) "El catálogo de servicios" else "El catálogo de productos")
        "equipo" -> if (st.repo.plus) BizEquipo(st) else PlusLock(st, "Agregar vendedores")
        else -> BizHoy(st)
    }
}

@Composable
private fun RowScope.SaleRow(st: AppState, s: JsonObject, actions: Boolean) {
    val d = st.repo.data
    val f = bizFmt(st)
    val ctx = LocalContext.current
    val pend = salePending(d, s)
    val items = s.arr("items").joinToString(", ") { "${inputMoney(it.num("qty"))} ${it.str("name")}" }
    Column(Modifier.weight(1f)) {
        val v = bizV(d)
        B(if (s.bool("credit") == true) v.credit + (s.str("client")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
          else s.str("client")?.takeIf { it.isNotBlank() } ?: SALE_METHODS[s.str("method")] ?: v.sale)
        Mut(listOfNotNull(s.str("concept")?.takeIf { it.isNotBlank() }, dayText(s.str("date")), SALE_METHODS[s.str("method")], items.ifBlank { null },
            s.str("seller")?.takeIf { it.isNotBlank() }?.let { "vendió $it" }, if (pend > .005) "debe ${f.show(pend)}" else null,
            s.str("note")?.takeIf { it.isNotBlank() }).joinToString(" · "))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Amt(f.orig(s.num("amount"), s.str("currency")))
        if (actions) {
            if (st.repo.plus) Link("Recibo", { openUrl(ctx, waLink(s.str("phone"), receiptText(d, s))) })
            Link("✕", { st.act("Venta eliminada", undo = true) { it.removeRow("sales", s.id) } })
        }
    }
}

@Composable
private fun BizHoy(st: AppState) {
    val d = st.repo.data
    val f = bizFmt(st)
    val today = LocalDate.now()
    val t = bizDay(d, today)
    val last = d.arr("cashClosings").firstOrNull { localDay(it.str("date")) == today }
    val sales = d.arr("sales").filter { localDay(it.str("date")) == today }
    val other = if (f.bs) BizFmt(f.hide, false, f.rate, f.sym).show(t.sold) else BizFmt(f.hide, true, f.rate, f.sym).show(t.sold)
    val v = bizV(d)
    Hero(v.today, f.show(t.sold), "${t.n} ${if (t.n == 1) v.n1 else v.nn} · $other a la tasa ${BIZ_RATES[bizKind(d)]}")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(v.add, { st.open("bizventa") }, Modifier.weight(1f))
        Btn("Gasto del negocio", { st.open("bizgasto") }, Modifier.weight(1f), outline = true)
    }
    QCard {
        B("Cobrado hoy")
        if (t.inn.isEmpty()) Mut("Todavía no has cobrado nada hoy.")
        t.inn.entries.sortedByDescending { it.value }.forEach { (m, u) -> Line(SALE_METHODS[m] ?: m, f.show(u)) }
    }
    QCard {
        B("Caja de hoy")
        Line("Efectivo en Bs. que debería haber", f.orig(t.cashBs, "BS"))
        Line("Efectivo en dólares", f.orig(t.cashUsd, "USD"))
        if (last != null) Mut("Cerraste la caja: contaste ${f.orig(last.num("countedBs"), "BS")} y ${f.orig(last.num("countedUsd"), "USD")}.")
        Btn(if (last != null) "Volver a contar" else "Cerrar la caja", { st.open("bizcierre") }, outline = true)
    }
    QCard {
        B(v.ofToday)
        Items(sales, v.first) { s -> SaleRow(st, s, true) }
    }
}

@Composable
private fun BizVentas(st: AppState) {
    val all = st.repo.data.arr("sales").sortedByDescending { it.str("date") ?: "" }.take(150)
    val v = bizV(st.repo.data)
    Btn(v.add, { st.open("bizventa") })
    QCard { Items(all, v.none) { s -> SaleRow(st, s, true) } }
}

@Composable
private fun BizFiado(st: AppState) {
    val d = st.repo.data
    val f = bizFmt(st)
    val ctx = LocalContext.current
    val rf = BizFmt(false, false, bizRate(d), bizSym(d))
    val open = d.arr("sales").filter { salePending(d, it) > .005 }.sortedBy { it.str("dueDate")?.ifBlank { null } ?: it.str("date") ?: "" }
    val name = d.obj("business").str("name")?.ifBlank { null } ?: "mi negocio"
    QCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { B("Te deben tus clientes"); B(f.show(open.sumOf { salePending(d, it) }), color = pal.g) }
        Items(open, bizV(d).noCredit) { s ->
            val p = salePending(d, s)
            Column(Modifier.weight(1f)) {
                B(s.str("client")?.ifBlank { null } ?: "Cliente")
                Mut("Debe ${f.show(p)} de ${f.orig(s.num("amount"), s.str("currency"))} · ${dayText(s.str("date"))}" +
                    (dateOf(s.str("dueDate"))?.let { " · paga el ${shortDate(it)}" } ?: ""))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!s.str("phone").isNullOrBlank()) Link("Recordar", {
                    val client = s.str("client")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                    openUrl(ctx, waLink(s.str("phone"), "Hola$client, te recuerdo el pago pendiente de ${rf.orig(p, "USD")} (${rf.orig(p * bizRate(d), "BS")} a la tasa ${BIZ_RATES[bizKind(d)]} de hoy) en $name. ¡Gracias!"))
                })
                Btn("Abonó", { st.open("bizabono", s.id) }, small = true)
            }
        }
    }
}

/** Clientes: lo cobrado a cada uno y lo que te debe (útil para profesionales con clientes fijos). */
@Composable
private fun BizClientes(st: AppState) {
    val d = st.repo.data
    val f = bizFmt(st)
    val ctx = LocalContext.current
    val v = bizV(d)
    val rf = BizFmt(false, false, bizRate(d), bizSym(d))
    class C(val name: String, var total: Double = 0.0, var pend: Double = 0.0, var n: Int = 0, var last: String = "", var phone: String = "")
    val m = linkedMapOf<String, C>()
    for (x in d.arr("sales")) {
        val c = x.str("client")?.trim().orEmpty().ifBlank { null } ?: continue
        val r = m.getOrPut(c.lowercase()) { C(c) }
        r.total += bizUsd(d, x); r.pend += salePending(d, x); r.n++
        if ((x.str("date") ?: "") > r.last) r.last = x.str("date") ?: ""
        x.str("phone")?.takeIf { it.isNotBlank() }?.let { r.phone = it }
    }
    val list = m.values.sortedWith(compareByDescending<C> { it.pend }.thenByDescending { it.total })
    val bname = d.obj("business").str("name")?.ifBlank { null } ?: "nosotros"
    QCard {
        B("Tus clientes")
        Mut("Lo que te han pagado y lo que te deben, por cliente. Se arma solo con el nombre del cliente en cada ${v.n1}.")
        Items(list, "Escribe el nombre del cliente al registrar cada ${v.n1} y aquí verás su historial.") { r ->
            Column(Modifier.weight(1f)) {
                B(r.name)
                Mut("${r.n} ${if (r.n == 1) v.n1 else v.nn} · ${f.show(r.total)} en total · último ${dayText(r.last)}")
                if (r.pend > .005) Text("Debe ${f.show(r.pend)}", color = pal.bad, fontSize = 14.sp)
            }
            if (r.pend > .005 && r.phone.isNotBlank()) Link("Recordar", {
                openUrl(ctx, waLink(r.phone, "Hola ${r.name}, le recuerdo el saldo pendiente de ${rf.orig(r.pend, "USD")} con $bname. ¡Gracias!"))
            })
        }
    }
}

@Composable
private fun BizGastos(st: AppState) {
    val d = st.repo.data
    val f = bizFmt(st)
    val ym = YearMonth.now()
    val rows = d.arr("bizExpenses").filter { localDay(it.str("date"))?.let { x -> YearMonth.from(x) == ym } == true }.sortedByDescending { it.str("date") ?: "" }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("+ Gasto", { st.open("bizgasto") }, Modifier.weight(1f))
        Btn("Me pago a mí", { st.open("bizretiro") }, Modifier.weight(1f), outline = true)
    }
    QCard {
        B("Gastos de este mes")
        Items(rows, "Anota aquí la mercancía que compras, el alquiler, los servicios y los sueldos.") { e ->
            val ret = e.str("category") == "retiro"
            Column(Modifier.weight(1f)) {
                B(if (ret) "Te pagaste" else e.str("description")?.ifBlank { null } ?: BIZ_CATS[e.str("category")] ?: "Gasto")
                Mut(listOfNotNull(dayText(e.str("date")), if (ret) "Pasó a tu quincena" else BIZ_CATS[e.str("category")], e.str("supplier")?.ifBlank { null }).joinToString(" · "))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Amt(f.orig(e.num("amount"), e.str("currency")))
                Link("✕", { st.act("Gasto eliminado", undo = true) { it.removeRow("bizExpenses", e.id) } })
            }
        }
    }
}

@Composable
private fun BizMes(st: AppState) {
    val d = st.repo.data
    val f = bizFmt(st)
    val ctx = LocalContext.current
    val ym = YearMonth.now()
    val m = bizMonth(d, ym)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bizCsv(d, ym).toByteArray()) } }
            .onSuccess { st.toast("Listo: se guardó el Excel del mes.") }.onFailure { st.toast("No pudimos guardar el archivo.") }
    }
    Hero("Ganancia de ${monthName(ym)}", f.show(m.profit), "${bizV(d).sales} menos compras y gastos, en ${bizCurName(d)} a la tasa de cada día")
    QCard {
        Line("${bizV(d).sales} (${m.n})", f.show(m.sales))
        if (m.buy > 0 || !bizSvc(d)) Line(if (bizSvc(d)) "Compras de materiales" else "Compras de mercancía", f.show(-m.buy))
        Line("Otros gastos del negocio", f.show(-m.exp))
        if (m.cogs > 0) Mut("Costo de lo vendido según tus productos: ${f.show(m.cogs)}")
        HorizontalDivider(color = pal.line)
        Line("Ganancia", f.show(m.profit), pal.g)
        Line("Te pagaste a ti", f.show(m.ret))
        Line(if (bizSvc(d)) "Por cobrar" else "Fiado por cobrar", f.show(m.pending))
    }
    if (st.repo.plus) QCard {
        B("Para tu contador")
        Mut("Guarda las ventas, abonos y gastos del mes en un archivo que abre Excel.")
        Btn("Guardar Excel del mes", { save.launch("${(d.obj("business").str("name")?.ifBlank { null } ?: "negocio").replace(Regex("[^\\w-]+"), "-")}-$ym.csv") }, outline = true)
    } else PlusLock(st, "El Excel para tu contador")
    Mut("Quincena VE lleva tus cuentas; no emite facturas fiscales.")
    GigadevsCard()
}

@Composable
private fun BizProductos(st: AppState) {
    val d = st.repo.data
    val f = BizFmt(st.hide, false, bizRate(d), bizSym(d))
    Btn("+ Producto", { st.open("bizprod") })
    QCard {
        Items(activeProducts(d), "Agrega lo que vendes con su precio en ${bizCurName(d)}: al vender lo tocas y se suma solo, en ${bizSym(d)} o en Bs.") { p ->
            val stock = stockOf(d, p)
            Column(Modifier.weight(1f)) {
                B(p.str("name") ?: "")
                Text(loc("${f.orig(p.num("price"), "USD")} · ${f.orig(p.num("price") * bizRate(d), "BS")}" +
                    (if (p.bool("track") == true) " · quedan ${inputMoney(Math.round(stock * 100) / 100.0).ifEmpty { "0" }}" else "")),
                    color = if (p.bool("track") == true && stock <= 0) pal.bad else pal.mut, fontSize = 14.sp)
            }
            Link("Editar", { st.open("bizprod", p.id) })
        }
    }
}

@Composable
private fun BizEquipo(st: AppState) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.sellersVer) {
        try { data = st.repo.sellers(); error = null }
        catch (e: ApiError) { error = e.message } catch (e: Offline) { error = "Sin conexión. Revisa tu internet e inténtalo de nuevo." }
    }
    val r = data
    if (r == null) { Mut(error ?: "Cargando…"); return }
    QCard {
        B("Vendedores")
        Mut("Cada vendedor entra con su usuario en la web o la app y solo puede registrar ventas. Tú las ves aquí con su nombre. Máximo ${r.num("max").toInt()}.")
        Items(r.arr("sellers"), "") { v ->
            val active = v.bool("active") == true
            Column(Modifier.weight(1f)) { B(v.str("username") ?: ""); Mut(if (active) "Activo" else "Sin acceso") }
            Link("Nueva clave", { st.open("bizvpass", v.num("id").toInt().toString()) })
            Link(if (active) "Quitar acceso" else "Dar acceso", {
                st.scope.launch {
                    try { st.repo.updateSeller(v.num("id").toInt(), buildJsonObject { put("active", !active) }); st.sellersVer++; st.toast(if (active) "Le quitaste el acceso." else "Ya tiene acceso.") }
                    catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") } catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
                }
            })
        }
        Btn("+ Agregar vendedor", { st.open("bizvend") }, outline = true)
    }
}

// ---------- Formularios ----------

@Composable
private fun BizTitle(t: String) = Title(t, 28.sp, pal.ink)

@Composable
private fun BizFooter(st: AppState, onSave: () -> Unit, label: String = "Guardar") {
    Btn(label, onSave)
    Btn("Cancelar", { st.close() }, outline = true)
}

private fun money(s: String) = parseMoney(s)?.takeIf { it > 0 }
private fun bizId(p: String) = p + System.currentTimeMillis().toString(36) + (1000..9999).random()
private val METHOD_OPTS get() = SALE_METHODS.entries.map { it.key to it.value }

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = pal.g))
        Text(loc(text), Modifier.weight(1f))
    }
}

/** Campos de una venta: los usa el dueño (ventana) y el vendedor (su pantalla). Devuelve la venta lista para guardar. */
@Composable
fun SaleEditor(
    products: List<JsonObject>, rate: Double, cart: Map<String, Double>, onCart: (Map<String, Double>) -> Unit,
    accPicker: (@Composable (String, (String) -> Unit) -> Unit)?, initAmount: String = "", initCur: String = "BS", initNote: String = "",
    saveLabel: String = "Guardar", onCancel: (() -> Unit)? = null, sym: String = "$", toast: (String) -> Unit,
    svc: Boolean = false, concepts: List<String> = emptyList(), clients: List<String> = emptyList(), onSave: (JsonObject) -> Unit,
) {
    var concept by remember { mutableStateOf("") }
    val f = BizFmt(false, false, rate, sym)
    var amount by remember { mutableStateOf(initAmount) }
    var cur by remember { mutableStateOf(initCur) }
    var method by remember { mutableStateOf("pago_movil") }
    var credit by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(initNote) }
    var acc by remember { mutableStateOf("") }
    fun total() = cart.entries.sumOf { (id, q) -> (products.firstOrNull { it.id == id }?.num("price") ?: 0.0) * q }
    fun refill(c: String, crt: Map<String, Double>) {
        val t = crt.entries.sumOf { (id, q) -> (products.firstOrNull { it.id == id }?.num("price") ?: 0.0) * q }
        if (t > 0) amount = inputMoney(Math.round((if (c == "BS") t * rate else t) * 100) / 100.0)
    }
    if (products.isNotEmpty()) {
        Text(loc("Productos (toca para sumar)"), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Chips { products.forEach { p -> Chip("${p.str("name")} · ${f.orig(p.num("price"), "USD")}", (cart[p.id] ?: 0.0) > 0) {
            val n = cart + (p.id to (cart[p.id] ?: 0.0) + 1); onCart(n); refill(cur, n)
        } } }
        val lines = cart.filter { it.value > 0 }
        if (lines.isNotEmpty()) QCard {
            lines.forEach { (id, q) ->
                val p = products.firstOrNull { it.id == id } ?: return@forEach
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${inputMoney(q)} × ${p.str("name")}", Modifier.weight(1f))
                    Amt(f.orig(p.num("price") * q, "USD"))
                    Link("−", { val n = cart + (id to max(0.0, q - 1)); onCart(n); refill(cur, n) })
                }
            }
            Line("Total", f.orig(total(), "USD"))
        }
    }
    if (svc || concepts.isNotEmpty()) {
        Field("Concepto${if (svc) "" else " (opcional)"}", concept, { concept = it.take(120) }, placeholder = concepts.firstOrNull() ?: "")
        if (concepts.isNotEmpty()) Chips { concepts.forEach { c -> Chip(c, concept == c) { concept = c } } }
    }
    MoneyField("Monto", amount) { amount = it }
    Select("Moneda", listOf("BS" to "Bolívares (Bs.)", "USD" to divisaLabel(sym)), cur) { cur = it; refill(it, cart) }
    Select("¿Cómo te pagaron?", METHOD_OPTS, method) { method = it }
    CheckRow(if (svc) "Me paga después (queda por cobrar)" else "Es fiado (me paga después)", credit) { credit = it }
    Field("Cliente${if (credit) "" else " (opcional)"}", client, { client = it.take(80) })
    if (client.length >= 1) clients.filter { it.contains(client, true) && !it.equals(client, true) }.take(4).takeIf { it.isNotEmpty() }?.let { m ->
        Chips { m.forEach { c -> Chip(c, false) { client = c } } }
    }
    Field("Teléfono del cliente (opcional, para WhatsApp)", phone, { phone = it.take(30) }, placeholder = "0414 000 0000", kind = KeyboardType.Phone)
    if (credit) DateField("¿Para cuándo paga?", due, { due = it }, optional = true)
    Field("Nota (opcional)", note, { note = it.take(200) })
    accPicker?.invoke(acc) { acc = it }
    Btn(saveLabel, {
        val a = money(amount) ?: return@Btn toast("Escribe un monto mayor a cero.")
        if (credit && client.isBlank()) return@Btn toast("Escribe el nombre del cliente que te debe.")
        if (svc && concept.isBlank()) return@Btn toast("Escribe el concepto del servicio.")
        val items = cart.filter { it.value > 0 }.mapNotNull { (id, q) ->
            products.firstOrNull { it.id == id }?.let { p -> row("productId" to id, "name" to p.str("name"), "qty" to q, "price" to p.num("price")) }
        }
        onSave(row("id" to bizId("v"), "date" to nowIso(), "amount" to a, "currency" to cur, "method" to method, "credit" to credit,
            "client" to client.trim().ifBlank { null }, "concept" to concept.trim().ifBlank { null }, "phone" to phone.trim().ifBlank { null }, "dueDate" to due.ifBlank { null }.takeIf { credit },
            "note" to note.trim().ifBlank { null }, "accountId" to acc.ifBlank { null }, "rateUsed" to (if (cur == "BS") rate else null),
            "items" to items.ifEmpty { null }))
    })
    if (onCancel != null) Btn("Cancelar", onCancel, outline = true)
}

@Composable
fun VentaForm(st: AppState, arg: String?) {
    val d = st.repo.data
    // "sms:<monto>:<moneda>": venta abierta desde el mensaje de un pago móvil recibido.
    val sms = arg?.takeIf { it.startsWith("sms:") }?.split(":")
    val v = bizV(d)
    BizTitle(v.neu)
    SaleEditor(
        if (st.repo.plus) activeProducts(d) else emptyList(), bizRate(d), st.cart, { st.cart = it },
        { v, on -> AccSelect(st, v, "¿A qué cuenta entró? (opcional)", on) },
        initAmount = sms?.getOrNull(1)?.toDoubleOrNull()?.let { inputMoney(it) } ?: "", initCur = sms?.getOrNull(2) ?: "BS",
        initNote = if (sms != null) "Pago móvil recibido" else "", onCancel = { st.close() }, sym = bizSym(d), toast = { st.toast(it) },
        svc = bizSvc(d), concepts = bizConcepts(d), clients = d.arr("sales").mapNotNull { it.str("client")?.ifBlank { null } }.distinct(),
    ) { sale ->
        st.close(); st.cart = emptyMap()
        val credit = sale.bool("credit") == true
        st.act((if (credit) (if (bizSvc(d)) "Por cobrar anotado: " else "Fiado anotado: ") else (if (bizSvc(d)) "Cobro registrado: " else "Venta registrada: ")) + bizFmt(st).orig(sale.num("amount"), sale.str("currency")), undo = true) { it.addFirst("sales", sale) }
    }
}

@Composable
fun BizConfForm(st: AppState) {
    val b = st.repo.data.obj("business")
    val on = bizOn(st.repo.data)
    var name by remember { mutableStateOf(b.str("name") ?: "") }
    var phone by remember { mutableStateOf(b.str("phone") ?: "") }
    var kind by remember { mutableStateOf(bizKind(st.repo.data)) }
    var type by remember { mutableStateOf(bizType(st.repo.data)) }
    var prof by remember { mutableStateOf(b.str("profession").orEmpty()) }
    val rf = Fmt(false, false, 1.0)
    BizTitle("Mi negocio")
    Field("Nombre del negocio", name, { name = it.take(80) }, placeholder = "Bodega Ana")
    Select("¿Qué tipo de negocio es?", BIZ_TYPES.entries.map { it.key to it.value }, type) { type = it }
    Select("Si das servicios profesionales, ¿cuál es tu profesión?", BIZ_PROFS.entries.map { it.key to it.value }, prof) { prof = it }
    Field("WhatsApp del negocio (opcional, sale en los recibos)", phone, { phone = it.take(30) }, kind = KeyboardType.Phone)
    Select("¿Con qué tasa trabaja tu negocio?", BIZ_RATES.entries.map { (k, l) ->
        k to l + (bizRateOf(st.repo.data, k).takeIf { it > 0 }?.let { " · Bs. ${rf.plain(it)}" } ?: " · aún sin dato") }, kind) { kind = it }
    Mut("Las ventas, los precios y los totales del negocio se convierten con esta tasa. Con el euro BCV los montos se muestran en €.")
    Mut("Las ventas y gastos del negocio van aparte: no se mezclan con tu quincena ni tu presupuesto. Quincena VE no emite facturas fiscales.")
    if (on) OutlineDanger("Ocultar el modo Negocio") {
        st.close(); st.go("mas")
        st.act("Modo Negocio oculto. Tus datos del negocio se guardan.") { it["business"] = row("enabled" to false, "name" to name.trim(), "phone" to phone.trim(), "rateKind" to kind, "type" to type, "profession" to prof) }
    }
    BizFooter(st, {
        if (name.isBlank()) return@BizFooter st.toast("Escribe el nombre del negocio.")
        st.close(); st.go("negocio"); st.bizSub = "hoy"
        st.act(if (on) "Guardado" else "Listo: tu negocio está activo.") { it["business"] = row("enabled" to true, "name" to name.trim(), "phone" to phone.trim(), "rateKind" to kind, "type" to type, "profession" to prof) }
    }, if (on) "Guardar" else "Activar mi negocio")
}

@Composable
fun BizGastoForm(st: AppState) {
    var amount by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("BS") }
    var cat by remember { mutableStateOf("mercancia") }
    var method by remember { mutableStateOf("pago_movil") }
    var supplier by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var acc by remember { mutableStateOf("") }
    BizTitle("Gasto del negocio")
    MoneyField("Monto", amount) { amount = it }
    Select("Moneda", listOf("BS" to "Bolívares (Bs.)", "USD" to divisaLabel(bizSym(st.repo.data))), cur) { cur = it }
    Select("Tipo", BIZ_CATS.entries.map { it.key to it.value }, cat) { cat = it }
    Select("¿Cómo pagaste?", METHOD_OPTS, method) { method = it }
    Field("Proveedor (opcional)", supplier, { supplier = it.take(80) })
    Field("Nota (opcional)", note, { note = it.take(200) })
    AccSelect(st, acc, "¿De qué cuenta salió? (opcional)") { acc = it }
    BizFooter(st, {
        val a = money(amount) ?: return@BizFooter st.toast("Escribe un monto mayor a cero.")
        st.close()
        st.act("Gasto del negocio anotado") { m ->
            m.addFirst("bizExpenses", row("id" to bizId("g"), "date" to nowIso(), "amount" to a, "currency" to cur, "category" to cat, "method" to method,
                "supplier" to supplier.trim().ifBlank { null }, "description" to note.trim().ifBlank { null }, "accountId" to acc.ifBlank { null },
                "rateUsed" to (if (cur == "BS") bizRate(m.data()) else null)))
        }
    })
}

@Composable
fun BizRetiroForm(st: AppState) {
    var amount by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("USD") }
    var method by remember { mutableStateOf("efectivo_usd") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    BizTitle("Me pago a mí")
    Mut("Pasa dinero del negocio a tu quincena personal: sale del negocio como \"te pagaste\" y entra como ingreso tuyo.")
    MoneyField("Monto", amount) { amount = it }
    Select("Moneda", listOf("USD" to divisaLabel(bizSym(st.repo.data)), "BS" to "Bolívares (Bs.)"), cur) { cur = it }
    Select("¿Cómo lo sacaste?", METHOD_OPTS, method) { method = it }
    AccSelect(st, from, "¿De qué cuenta sale? (opcional)") { from = it }
    AccSelect(st, to, "¿A qué cuenta tuya entra? (opcional)") { to = it }
    BizFooter(st, {
        val a = money(amount) ?: return@BizFooter st.toast("Escribe un monto mayor a cero.")
        val id = bizId("r")
        st.close()
        st.act("Listo: pasó a tu quincena") { m ->
            m.addFirst("bizExpenses", row("id" to id, "date" to nowIso(), "amount" to a, "currency" to cur, "category" to "retiro", "method" to method,
                "description" to "Me pagué", "accountId" to from.ifBlank { null }, "rateUsed" to (if (cur == "BS") bizRate(m.data()) else null)))
            // Con el euro BCV la divisa del negocio son euros: a la quincena entra su equivalente en dólares.
            val personal = if (cur == "USD" && bizKind(m.data()) == "bcv_eur") Math.round(a * bizRate(m.data()) / (rateOf(m.data()).takeIf { it > 0 } ?: 1.0) * 100) / 100.0 else a
            m.addFirst("incomes", row("id" to id + "_in", "date" to nowIso(), "amount" to personal, "currency" to cur, "name" to "Me pagué del negocio",
                "kind" to "negocio", "accountId" to to.ifBlank { null }))
        }
    })
}

@Composable
fun BizCierreForm(st: AppState) {
    val d = st.repo.data
    val f = BizFmt(false, false, bizRate(d), bizSym(d))
    val t = bizDay(d, LocalDate.now())
    var bs by remember { mutableStateOf("") }
    var usd by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    BizTitle("Cerrar la caja")
    Mut("Según lo anotado deberías tener ${f.orig(t.cashBs, "BS")} y ${f.orig(t.cashUsd, "USD")} en efectivo.")
    MoneyField("Efectivo en Bs. que contaste", bs) { bs = it }
    MoneyField("Efectivo en dólares que contaste", usd) { usd = it }
    Field("Nota (opcional)", note, { note = it.take(200) })
    BizFooter(st, {
        val cb = parseMoney(bs) ?: 0.0
        val cu = parseMoney(usd) ?: 0.0
        val db = cb - t.cashBs
        val du = cu - t.cashUsd
        val msg = if (abs(db) < .01 && abs(du) < .01) "La caja cuadra." else "Diferencia: ${f.orig(db, "BS")} y ${f.orig(du, "USD")}."
        st.close()
        st.act("Caja cerrada. $msg") { m ->
            m.addFirst("cashClosings", row("id" to bizId("k"), "date" to nowIso(), "countedBs" to cb, "countedUsd" to cu,
                "expectedBs" to t.cashBs, "expectedUsd" to t.cashUsd, "note" to note.trim()))
        }
    })
}

@Composable
fun BizAbonoForm(st: AppState, saleId: String) {
    val d = st.repo.data
    val s = d.arr("sales").firstOrNull { it.id == saleId } ?: return
    val f = BizFmt(false, false, bizRate(d), bizSym(d))
    val pend = salePending(d, s)
    val c0 = s.str("currency") ?: "USD"
    var amount by remember { mutableStateOf(inputMoney(Math.round((if (c0 == "BS") pend * bizRate(d) else pend) * 100) / 100.0)) }
    var cur by remember { mutableStateOf(c0) }
    var method by remember { mutableStateOf(if (c0 == "BS") "pago_movil" else "efectivo_usd") }
    var acc by remember { mutableStateOf("") }
    BizTitle("${s.str("client")?.ifBlank { null } ?: "El cliente"} abonó")
    Mut("Debe ${f.orig(pend, "USD")} (${f.orig(pend * bizRate(d), "BS")} a la tasa de hoy).")
    MoneyField("Monto", amount) { amount = it }
    Select("Moneda", listOf("BS" to "Bolívares (Bs.)", "USD" to divisaLabel(bizSym(st.repo.data))), cur) { cur = it }
    Select("¿Cómo te pagó?", METHOD_OPTS, method) { method = it }
    AccSelect(st, acc, "¿A qué cuenta entró? (opcional)") { acc = it }
    BizFooter(st, {
        val a = money(amount) ?: return@BizFooter st.toast("Escribe un monto mayor a cero.")
        val u = if (cur == "BS") a / (bizRate(d).takeIf { it > 0 } ?: 1.0) else a
        if (u > pend + .01) return@BizFooter st.toast("Es más de lo que debe. Baja el monto.")
        st.close()
        st.act("Abono anotado") { m ->
            m.addFirst("bizPayments", row("id" to bizId("a"), "saleId" to s.id, "date" to nowIso(), "amount" to a, "currency" to cur, "method" to method,
                "accountId" to acc.ifBlank { null }, "rateUsed" to (if (cur == "BS") bizRate(m.data()) else null)))
        }
    })
}

@Composable
fun BizProdForm(st: AppState, id: String?) {
    val d = st.repo.data
    val p = d.arr("products").firstOrNull { it.id == id }
    var name by remember { mutableStateOf(p?.str("name") ?: "") }
    var price by remember { mutableStateOf(inputMoney(p?.num("price"))) }
    var cost by remember { mutableStateOf(inputMoney(p?.num("cost"))) }
    var track by remember { mutableStateOf(p?.bool("track") == true) }
    var stock by remember { mutableStateOf(if (p != null && p.bool("track") == true) inputMoney(Math.round(stockOf(d, p) * 100) / 100.0) else "") }
    BizTitle(if (p == null) "Nuevo producto" else "Editar producto")
    Field("Nombre", name, { name = it.take(80) })
    MoneyField("Precio de venta en ${bizCurName(d)}", price) { price = it }
    MoneyField("Lo que te cuesta en ${bizCurName(d)} (opcional, para saber tu ganancia)", cost) { cost = it }
    CheckRow("Llevar la cuenta de cuántos quedan", track) { track = it }
    if (track) MoneyField("¿Cuántos tienes ahora?", stock) { stock = it }
    if (p != null) OutlineDanger("Quitar del catálogo") { st.close(); st.act("Producto quitado del catálogo") { m -> m.updateRow("products", p.id) { it.plusFields("archived" to true) } } }
    BizFooter(st, {
        if (name.isBlank()) return@BizFooter st.toast("Escribe el nombre del producto.")
        val pr = parseMoney(price)?.takeIf { it >= 0 } ?: return@BizFooter st.toast("Escribe el precio en dólares.")
        val co = parseMoney(cost) ?: 0.0
        val st0 = parseMoney(stock) ?: 0.0
        val newStock = track && (p == null || p.bool("track") != true || abs(st0 - stockOf(d, p)) > 0.0001)
        val fields = arrayOf<Pair<String, Any?>>("name" to name.trim(), "price" to pr, "cost" to co, "track" to track)
        val stockFields = if (newStock) arrayOf<Pair<String, Any?>>("stock" to st0, "stockDate" to nowIso()) else emptyArray<Pair<String, Any?>>()
        st.close()
        st.act(if (p == null) "Producto agregado" else "Producto actualizado") { m ->
            if (p == null) m.setRows("products", m.rows("products") + row("id" to bizId("p"), *fields, *stockFields))
            else m.updateRow("products", p.id) { it.plusFields(*fields, *stockFields) }
        }
    })
}

@Composable
fun BizVendForm(st: AppState) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BizTitle("Nuevo vendedor")
    Mut("Crea su usuario y su contraseña y dáselos. Solo podrá registrar ventas de tu negocio.")
    Field("Usuario", user, { user = it.trim().lowercase().take(40) })
    Field("Contraseña (mínimo 12 caracteres)", pass, { pass = it })
    Btn("Crear vendedor", {
        if (user.length < 3) return@Btn st.toast("El usuario debe tener al menos 3 caracteres.")
        if (pass.length < 12) return@Btn st.toast("Usa una contraseña de al menos 12 caracteres.")
        busy = true
        st.scope.launch {
            try { st.repo.createSeller(user, pass); st.close(); st.sellersVer++; st.toast("Vendedor creado. Dale su usuario y contraseña.") }
            catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") } catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
            busy = false
        }
    }, enabled = !busy)
    Btn("Cancelar", { st.close() }, outline = true)
}

@Composable
fun BizVPassForm(st: AppState, sellerId: String) {
    var pass by remember { mutableStateOf("") }
    BizTitle("Nueva contraseña")
    Field("Contraseña nueva para el vendedor (mínimo 12 caracteres)", pass, { pass = it })
    Mut("Se cierra su sesión en sus teléfonos.")
    BizFooter(st, {
        if (pass.length < 12) return@BizFooter st.toast("Usa una contraseña de al menos 12 caracteres.")
        st.scope.launch {
            try { st.repo.updateSeller(sellerId.toInt(), buildJsonObject { put("password", pass) }); st.close(); st.toast("Contraseña cambiada.") }
            catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") } catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
        }
    })
}

// ---------- Modo vendedor ----------

/** La cuenta de un vendedor solo registra ventas del negocio del dueño. */
@Composable
fun SellerScreen(st: AppState, resumes: Int) {
    val repo = st.repo
    var home by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cart by remember { mutableStateOf(mapOf<String, Double>()) }
    var mine by remember { mutableStateOf(listOf<JsonObject>()) }
    var formKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumes) {
        try { val h = repo.sellerHome(); home = h; mine = h.arr("sales"); error = null }
        catch (e: ApiError) { error = e.message } catch (e: Offline) { error = "Sin conexión. Revisa tu internet e inténtalo de nuevo." }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Quincena VE")
                    Text(repo.business.ifBlank { "Negocio" }, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Mut("Vendedor: ${repo.username}")
                }
                Link("Salir", { st.scope.launch { repo.logout() } })
            }
            val h = home
            if (h == null) {
                if (error != null) QCard { B("No puedes vender ahora"); Mut(error ?: "") } else Mut("Cargando…")
                return@Column
            }
            val rate = h.num("rate")
            val f = BizFmt(false, false, rate, if (h.str("rateKind") == "bcv_eur") "€" else "$")
            val today = LocalDate.now()
            val todays = mine.filter { localDay(it.str("date")) == today }
            val tot = todays.sumOf { if (it.str("currency") == "BS") it.num("amount") / (it.num("rateUsed").takeIf { r -> r > 0 } ?: rate.takeIf { r -> r > 0 } ?: 1.0) else it.num("amount") }
            Hero("Tus ventas de hoy", f.orig(tot, "USD"), "${todays.size} ${if (todays.size == 1) "venta" else "ventas"} · tasa ${f.orig(rate, "BS")}")
            QCard {
                B("Nueva venta")
                key(formKey) {
                    SaleEditor(h.arr("products"), rate, cart, { cart = it }, null, saveLabel = "Registrar venta", sym = f.sym, toast = { st.toast(it) }) { sale ->
                        st.scope.launch {
                            try {
                                val saved = repo.sellerSale(sale.plusFields("id" to null, "date" to null, "rateUsed" to null, "accountId" to null))
                                mine = listOf(saved) + mine; cart = emptyMap(); formKey++
                                st.toast("Venta registrada: ${f.orig(saved.num("amount"), saved.str("currency"))}")
                            } catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") } catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
                        }
                    }
                }
            }
            QCard {
                B("Registradas hoy")
                Items(todays, "Aún no registras ventas hoy.") { s ->
                    Column(Modifier.weight(1f)) {
                        B(s.str("client")?.ifBlank { null } ?: SALE_METHODS[s.str("method")] ?: "Venta")
                        val time = runCatching { java.time.Instant.parse(s.str("date")).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }.getOrNull() ?: LocalTime.MIDNIGHT
                        Mut("${time.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${if (s.bool("credit") == true) "fiado" else SALE_METHODS[s.str("method")] ?: ""}")
                    }
                    Amt(f.orig(s.num("amount"), s.str("currency")))
                }
            }
        }
    }
}
