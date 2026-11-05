package app.quincena

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// =====================================================================
// 1.6: cuentas, ingresos recibidos, movimientos por día, presupuesto por quincena,
// calendario de pagos, "Me deben", seguridad, términos y copyright (igual que la web).
// =====================================================================

const val TERMS_VERSION = "2026-10-23"
val ACC_KINDS = linkedMapOf("banco" to "Banco", "efectivo" to "Efectivo", "digital" to "Digital (Zelle, PayPal…)", "cripto" to "Cripto (USDT…)")
val KIND_IN = mapOf("sueldo" to "Cobro", "extra" to "Ingreso extra", "cobro" to "Te devolvieron", "negocio" to "Del negocio")

fun copyright(): String {
    val y = LocalDate.now().year
    return "© ${if (y > 2026) "2026–$y" else "2026"} Quincena VE. Todos los derechos reservados."
}

/** Convierte un monto entre $ y Bs. con la tasa BCV. */
fun conv(d: JsonObject, a: Double, from: String?, to: String?): Double {
    val f = if (from == "BS") "BS" else "USD"
    val t = if (to == "BS") "BS" else "USD"
    if (f == t) return a
    val r = rateOf(d).takeIf { it > 0 } ?: 1.0
    return if (t == "USD") a / r else a * r
}

/** Día local de una fecha guardada (AAAA-MM-DD o ISO con hora en UTC). */
fun localDay(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    if (s.length > 10 && s.contains('T')) runCatching { return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDate() }
    return dateOf(s)
}

fun dayLabel(k: LocalDate): String {
    val t = LocalDate.now()
    if (k == t) return "Hoy"
    if (k == t.minusDays(1)) return "Ayer"
    val s = "${k.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es"))} ${shortDate(k)}"
    return s.replaceFirstChar { it.uppercase() }
}

// ---------- Cuentas ----------

fun accounts(d: JsonObject) = d.arr("accounts").filter { it.bool("archived") != true }
fun accById(d: JsonObject, id: String?) = if (id.isNullOrEmpty()) null else d.arr("accounts").firstOrNull { it.id == id }

fun accBalance(d: JsonObject, a: JsonObject): Double {
    val cur = a.str("currency")
    var b = a.num("initial")
    for (x in d.arr("incomes")) if (x.str("accountId") == a.id) b += conv(d, x.num("amount"), x.str("currency"), cur)
    for (e in d.arr("expenses")) if (e.str("accountId") == a.id) b -= conv(d, e.num("amount"), e.str("currency"), cur)
    for (r in d.arr("receivables")) if (r.str("accountId") == a.id) b -= conv(d, r.num("amount"), r.str("currency"), cur)
    for (t in d.arr("transfers")) {
        if (t.str("fromId") == a.id) b -= t.num("amountFrom")
        if (t.str("toId") == a.id) b += t.num("amountTo")
    }
    return b + bizAccFlow(d, a)
}

/** Comprueba que los gastos y movimientos no dejen negativa una cuenta seleccionada. */
fun accountSpendError(d: JsonObject, parts: List<Triple<String, Double, String>>, excludeId: String? = null): String? {
    val grouped = parts.filter { it.first.isNotBlank() && it.second > 0 }.groupBy { it.first }
    for ((id, rows) in grouped) {
        val a = accById(d, id) ?: continue
        var available = accBalance(d, a)
        if (!excludeId.isNullOrBlank()) d.arr("expenses").firstOrNull { it.id == excludeId && it.str("accountId") == id }?.let {
            available += conv(d, it.num("amount"), it.str("currency"), a.str("currency"))
        }
        val needed = rows.sumOf { conv(d, it.second, it.third, a.str("currency")) }
        if (needed > available + .005) return "La cuenta «${a.str("name") ?: "Cuenta"}» no tiene saldo suficiente. Disponible: ${Fmt(false, false, 1.0).orig(max(0.0, available), a.str("currency"))}."
    }
    return null
}

fun totalUsd(d: JsonObject) = accounts(d).sumOf { usdOf(d, accBalance(d, it), it.str("currency")) }

/** Saldo en bolívares de las cuentas en Bs. (null si no hay cuentas en Bs.). */
fun bsBalance(d: JsonObject): Double? {
    val bs = accounts(d).filter { it.str("currency") == "BS" }
    return if (bs.isEmpty()) null else max(0.0, bs.sumOf { accBalance(d, it) })
}

/** Bolívares guardados: de las cuentas en Bs., o el monto escrito a mano. */
fun savingsBs(d: JsonObject) = bsBalance(d) ?: d.num("bsSavings")

/** "Tu cambio": la tasa de la última vez que cambiaste $ por Bs. (últimos 45 días). */
fun lastCambio(d: JsonObject): Double {
    val lim = LocalDate.now().minusDays(45)
    val t = d.arr("transfers").filter { t ->
        val f = accById(d, t.str("fromId"))
        val to = accById(d, t.str("toId"))
        f != null && to != null && f.str("currency") != "BS" && to.str("currency") == "BS" && t.num("amountFrom") > 0 &&
            (localDay(t.str("date"))?.isBefore(lim) == false)
    }.maxByOrNull { it.str("date") ?: "" } ?: return 0.0
    return t.num("amountTo") / t.num("amountFrom")
}

fun accLabel(a: JsonObject) = "${a.str("name") ?: "Cuenta"} (${if (a.str("currency") == "BS") Loc.cur.sym else "$"})"
fun accName(d: JsonObject, id: String?) = accById(d, id)?.let { " · " + (it.str("name") ?: "") } ?: ""

// ---------- Ingresos recibidos ----------

/** Ingresos extra (no sueldo) recibidos entre dos fechas, en dólares. */
fun extraIn(d: JsonObject, from: LocalDate, to: LocalDate): Double =
    d.arr("incomes").filter { it.str("sourceId").isNullOrEmpty() && it.str("kind") == "extra" && inRange(it.str("date"), from, to) }
        .sumOf { usdOf(d, it.num("amount"), it.str("currency")) }

// ---------- Presupuesto por quincena ----------

fun periodCats(d: JsonObject, p: Period): Map<String, Double> = spentByCat(d, p.from, p.to)

/** Presupuesto sugerido: promedio de los últimos 3 meses, redondeado a 5. */
fun sugeridos(d: JsonObject): Map<String, Int> {
    val now = YearMonth.now()
    val keys = (1..3).map { now.minusMonths(it.toLong()).toString() }
    val sum = mutableMapOf<String, Double>()
    for (e in d.arr("expenses")) {
        val k = e.str("date")?.take(7) ?: continue
        if (k in keys) { val c = e.str("category") ?: "fijo"; sum[c] = (sum[c] ?: 0.0) + usdOf(d, e.num("amount"), e.str("currency")) }
    }
    return sum.mapValues { (ceil(it.value / 3 / 5) * 5).toInt() }.filter { it.value > 0 }
}

// ---------- Calendario y pagos repetidos ----------

data class CalEv(val text: String, val kind: String) // kind: pago, compromiso, fijo, cobro

fun calEvents(d: JsonObject, ym: YearMonth, f: Fmt): Map<Int, List<CalEv>> {
    val ev = mutableMapOf<Int, MutableList<CalEv>>()
    fun add(day: Int, t: String, k: String) { ev.getOrPut(day) { mutableListOf() }.add(CalEv(t, k)) }
    val last = ym.lengthOfMonth()
    fun inMonth(s: String?) = dateOf(s)?.takeIf { YearMonth.from(it) == ym }
    for (p in d.arr("plannedDebtPayments")) if (p.str("status") == "planned") inMonth(p.str("scheduledDate"))?.let {
        add(it.dayOfMonth, "${p.str("entity")?.takeIf { e -> e.isNotBlank() } ?: p.str("description") ?: "Pago"} · ${f.orig(p.num("amount"), p.str("currency"))}", "pago")
    }
    for (card in d.arr("debts")) if (card.str("type") == "tdc" && card.num("minPayment") > 0) {
        val date = card.str("payBefore")?.take(10)
        val hasScheduled = d.arr("plannedDebtPayments").any { it.str("debtId") == card.id && it.str("status") == "planned" && (it.str("scheduledDate") ?: "").startsWith(ym.toString()) }
        val day = date?.takeLast(2)?.toIntOrNull()
        if (day != null && !hasScheduled) add(min(day, last), "${card.str("issuer")?.takeIf { it.isNotBlank() } ?: card.str("entity") ?: "Tarjeta"} · mínimo ${f.orig(card.num("minPayment"), card.str("currency"))}", "pago")
    }
    for (x in d.arr("schoolExpenses")) inMonth(x.str("dueDate"))?.let {
        val left = schoolLeft(d, x)
        if (left > 0) add(it.dayOfMonth, "${x.str("description") ?: "Compromiso"} · ${f.orig(left, x.str("currency"))}", "compromiso")
    }
    for (r in d.arr("recurring")) if (r.bool("active") != false)
        add(min(r.num("day").toInt().coerceAtLeast(1), last), "${r.str("description") ?: "Gasto fijo"} · ${f.orig(r.num("amount"), r.str("currency"))} (fijo)", "fijo")
    for (s in d.arr("incomeSources")) {
        val days = (s["payDays"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
        val list = when (s.str("frequency")) {
            "semanal" -> (1..last).filter { ym.atDay(it).dayOfWeek.value == (days.firstOrNull() ?: 5) }
            "quincenal" -> if (days.size == 2) days else listOf(15, 30)
            "mensual" -> listOf(days.firstOrNull() ?: 1)
            else -> emptyList()
        }
        for (dd in list) add(min(dd, last), "Cobras: ${s.str("name") ?: "Ingreso"} · ${f.orig(s.num("amount"), s.str("currency"))}", "cobro")
    }
    for (x in d.arr("receivables")) if (x.num("paid") < x.num("amount")) inMonth(x.str("dueDate"))?.let {
        add(it.dayOfMonth, "Te paga ${x.str("person") ?: ""} · ${f.orig(x.num("amount") - x.num("paid"), x.str("currency"))}", "cobro")
    }
    return ev
}

/** Fecha número [i] de un pago que se repite: "m" cada mes, "q" cada quincena (15 y fin de mes). */
fun stepDate(first: LocalDate, freq: String, i: Int): LocalDate {
    if (freq == "m") {
        val ym = YearMonth.from(first).plusMonths(i.toLong())
        return ym.atDay(min(first.dayOfMonth, ym.lengthOfMonth()))
    }
    var x = first
    repeat(i) { x = if (x.dayOfMonth <= 15) YearMonth.from(x).atEndOfMonth() else YearMonth.from(x).plusMonths(1).atDay(15) }
    return x
}

// ---------- Deudas: fecha estimada para terminar ----------

fun payoffText(d: JsonObject, x: JsonObject, plan: Plan): String {
    var m = plan.payoff[x.id]
    if (m == null && x.num("minPayment") > 0) m = ceil((x.num("amount") - x.num("paid")) / x.num("minPayment")).toInt()
    if (m == null || m <= 0 || m > 600) return ""
    val ym = YearMonth.now().plusMonths(m.toLong())
    return " · terminas aprox. en ${ym.month.getDisplayName(TextStyle.SHORT, Locale("es")).trimEnd('.')} ${ym.year}"
}

// =====================================================================
// PANTALLAS
// =====================================================================

private fun Pal.evColor(k: String) = when (k) { "pago" -> bad; "compromiso" -> warn; "fijo" -> mut; else -> g }

/** Mis cuentas: total, saldo de cada cuenta y movimientos entre cuentas. */
@Composable
fun CuentasView(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val accs = accounts(d)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.g).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Tienes en total", color = Color.White)
        Big("≈ " + f.show(totalUsd(d)), size = 38.sp, color = Color.White)
        Text(loc("${accs.size} ${if (accs.size == 1) "cuenta" else "cuentas"} · los bolívares se convierten a tasa BCV"), fontSize = 13.sp, color = Color.White.copy(alpha = .85f))
    }
    QCard {
        Items(accs, "Agrega tus cuentas (banco, efectivo, Zelle, Binance…) para saber cuánto tienes y dónde. Cada gasto o ingreso puede salir o entrar de una cuenta.") { a ->
            val b = accBalance(d, a)
            Column(Modifier.weight(1f)) {
                B(a.str("name") ?: "Cuenta")
                Mut((ACC_KINDS[a.str("kind")] ?: "Cuenta") + if (a.str("currency") == "BS") " · ≈ ${f.orig(usdOf(d, b, "BS"), "USD")}" else "")
            }
            Text(f.orig(b, a.str("currency")), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, color = if (b < 0) p.bad else Color.Unspecified)
            Link("Editar", { st.open("editacc", a.id) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Agregar cuenta", { st.open("cuenta") }, Modifier.weight(1f), small = true, outline = true, full = false)
            if (accs.size > 1) Btn("Mover o cambiar", { st.open("transfer") }, Modifier.weight(1f), small = true, full = false)
        }
    }
    val cards = d.arr("debts").filter { it.str("type") == "tdc" }
    QCard {
        B("Tarjetas de crédito")
        Items(cards, "Registra el saldo y las fechas que aparecen en el estado de cuenta. Se incluyen en Deudas y en el plan de pagos.") { card ->
            val used = max(0.0, card.num("amount") - card.num("paid"))
            val limit = card.num("creditLimit")
            Column(Modifier.weight(1f)) {
                B(card.str("issuer")?.takeIf { it.isNotBlank() } ?: card.str("entity") ?: "Tarjeta")
                Mut("Usado ${f.orig(used, card.str("currency"))}" + if (limit > 0) " · disponible ${f.orig(max(0.0, limit - used), card.str("currency"))}" else "")
                Mut(listOfNotNull(card.str("cutDate")?.takeIf { it.isNotBlank() }?.let { "Corte ${it.take(10)}" }, card.str("payBefore")?.takeIf { it.isNotBlank() }?.let { "Pago antes de ${it.take(10)}" }).joinToString(" · "))
            }
            Link("Editar", { st.open("editard", card.id) })
        }
        Btn("Agregar tarjeta", { st.open("deuda", "tdc") }, small = true, outline = true, full = true)
    }
    val tr = d.arr("transfers").sortedByDescending { it.str("date") ?: "" }.take(20)
    if (tr.isNotEmpty()) QCard {
        B("Entre cuentas")
        Items(tr, "") { t ->
            val fa = accById(d, t.str("fromId"))
            val ta = accById(d, t.str("toId"))
            val tasa = if (fa != null && ta != null && fa.str("currency") != ta.str("currency") && t.num("amountFrom") > 0 && t.num("amountTo") > 0)
                " · tasa " + f.plain(if (fa.str("currency") != "BS") t.num("amountTo") / t.num("amountFrom") else t.num("amountFrom") / t.num("amountTo")) else ""
            Column(Modifier.weight(1f)) {
                B("${fa?.str("name") ?: "¿?"} → ${ta?.str("name") ?: "¿?"}")
                Mut("${localDay(t.str("date"))?.let { shortDate(it) } ?: ""}$tasa${t.str("note")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}")
            }
            Amt(f.orig(t.num("amountFrom"), fa?.str("currency")))
            Link("Borrar", { st.act("Movimiento borrado", undo = true) { it.removeRow("transfers", t.id) } })
        }
    }
}

/** Ingresos: fijos (con "Me pagaron") y recibidos (con "Ingreso extra"). */
@Composable
fun IngresosView(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val freq = mapOf("semanal" to "Cada semana", "quincenal" to "Cada quincena", "mensual" to "Una vez al mes", "ocasional" to "Solo una vez")
    QCard {
        B("Tus ingresos fijos")
        Items(d.arr("incomeSources"), "Agrega tu sueldo u otros ingresos para ver cuánto te queda.") { s ->
            val last = d.arr("incomes").filter { it.str("sourceId") == s.id }.maxByOrNull { it.str("date") ?: "" }
            Column(Modifier.weight(1f)) {
                B(s.str("name") ?: "Ingreso")
                Mut("${freq[s.str("frequency")] ?: s.str("frequency") ?: ""} · ${f.orig(s.num("amount"), s.str("currency"))}" +
                    (last?.let { l -> localDay(l.str("date"))?.let { " · último cobro ${shortDate(it)}" } } ?: ""))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Link("Editar", { st.open("editari", s.id) })
                Btn("Me pagaron", { st.open("cobro", s.id) }, small = true)
            }
        }
        Btn("Agregar ingreso fijo", { st.open("ingreso") }, small = true, outline = true, full = true)
    }
    val inc = d.arr("incomes").sortedByDescending { it.str("date") ?: "" }
    val ym = YearMonth.now()
    val mt = inc.filter { it.str("kind") != "cobro" && it.str("date")?.take(7) == ym.toString() }.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }
    QCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { B("Recibidos"); Mut("Este mes ${f.show(mt)}") }
        Items(inc.take(60), "Cuando te paguen, toca \"Me pagaron\". Para cualquier otro ingreso usa \"Ingreso extra\".") { x ->
            Column(Modifier.weight(1f)) {
                B(x.str("name") ?: "Ingreso")
                Mut("${KIND_IN[x.str("kind")] ?: "Ingreso"} · ${localDay(x.str("date"))?.let { shortDate(it) } ?: ""}${accName(d, x.str("accountId"))}")
            }
            Text("+" + f.orig(x.num("amount"), x.str("currency")), fontWeight = FontWeight.Bold, color = p.g, maxLines = 1, softWrap = false)
            Link("Editar", { st.open("editin", x.id) })
        }
        Btn("Registrar ingreso extra", { st.open("ingresox") })
    }
}

/** Gastos agrupados por día, con filtro por categoría y total. */
@Composable
fun GastosView(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val now = YearMonth.now().toString()
    val months = (listOf(now) + d.arr("expenses").mapNotNull { it.str("date")?.take(7) }.filter { it.length == 7 }).distinct().sortedDescending()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            st.q, { st.q = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors(),
            placeholder = { Text("Buscar gasto", color = pal.mut) },
        )
        Box(Modifier.width(150.dp)) {
            SelectBox(listOf("" to "Todos") + months.map { it to monthShortLabel(it) }, st.mon) { st.mon = it }
        }
    }
    val inMon = d.arr("expenses").filter { st.mon.isEmpty() || it.str("date")?.take(7) == st.mon }
    val cats = inMon.mapNotNull { it.str("category") }.distinct().filter { it in CATS }
    val fc = st.fcat.takeIf { it in cats } ?: ""
    if (cats.size > 1) Chips {
        Chip("Todas", fc.isEmpty()) { st.fcat = "" }
        cats.forEach { c -> Chip(CATS[c]!!, fc == c) { st.fcat = c } }
    }
    val qq = st.q.trim().lowercase()
    val list = inMon.filter { e ->
        (fc.isEmpty() || e.str("category") == fc) &&
            (qq.isEmpty() || (e.str("description") ?: "").lowercase().contains(qq) || (CATS[e.str("category")] ?: "").lowercase().contains(qq))
    }
    if (list.isNotEmpty()) {
        val total = list.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }
        Mut("${list.size} ${if (list.size == 1) "gasto" else "gastos"}${if (fc.isNotEmpty()) " en ${CATS[fc]}" else ""} · Total ${f.show(total)}")
    }
    val groups = list.take(200).groupBy { localDay(it.str("date")) }
    if (groups.isEmpty()) QCard { Mut(if (d.arr("expenses").isNotEmpty()) "No hay gastos con ese filtro." else "Aún no registras gastos. Toca + para agregar el primero.") }
    groups.forEach { (day, items) ->
        QCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                B(day?.let { dayLabel(it) } ?: "Sin fecha")
                Mut(f.show(items.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }))
            }
            // Pago mixto: las partes con el mismo groupId se muestran como un solo gasto con sus métodos.
            val rows = items.groupBy { it.str("groupId") ?: it.id }.values.toList()
            Items(rows, "") { parts ->
                if (parts.size > 1) {
                    val e0 = parts.first()
                    Column(Modifier.weight(1f)) {
                        B(e0.str("description")?.takeIf { it.isNotBlank() } ?: CATS[e0.str("category")] ?: "Gasto")
                        Mut("${CATS[e0.str("category")] ?: "Otros"} · pagado con ${parts.size} métodos")
                        Chips { parts.forEach { pt -> Chip("${EXP_METHODS[pt.str("method")] ?: "Otro"} · ${f.orig(pt.num("amount"), pt.str("currency"))}", false) { st.open("editar", pt.id) } } }
                    }
                    Amt(f.show(parts.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }))
                    return@Items
                }
                val e = parts.first()
                Column(Modifier.weight(1f)) {
                    B(e.str("description")?.takeIf { it.isNotBlank() } ?: CATS[e.str("category")] ?: "Gasto")
                    val rt = e.str("rateType")
                    val tasa = if (e.str("currency") == "BS" && rt != null && rt != "bcv") " · tasa ${RATE_LABELS[rt] ?: rt} ${f.plain(e.num("rateUsed"))}" else ""
                    Mut("${CATS[e.str("category")] ?: "Otros"}$tasa${accName(d, e.str("accountId"))}")
                }
                Amt(f.orig(e.num("amount"), e.str("currency")))
                Link("Editar", { st.open("editar", e.id) })
            }
        }
    }
}

private fun monthShortLabel(ym: String): String = runCatching {
    val y = YearMonth.parse(ym)
    y.month.getDisplayName(TextStyle.SHORT, Locale("es")).trimEnd('.') + " " + y.year
}.getOrDefault(ym)

/** Me deben: préstamos a familiares o amigos. */
@Composable
fun MeDebenView(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val rs = d.arr("receivables").sortedBy { if (it.num("paid") >= it.num("amount")) 1 else 0 }
    val tot = rs.filter { it.num("paid") < it.num("amount") }.sumOf { usdOf(d, it.num("amount") - it.num("paid"), it.str("currency")) }
    QCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { B("Te deben"); B(f.show(tot), color = p.g) }
        Items(rs, "Anota aquí lo que le prestas a familiares o amigos, y lo que te van devolviendo.") { x ->
            val left = x.num("amount") - x.num("paid")
            Column(Modifier.weight(1f)) {
                B(x.str("person") ?: "")
                val due = dateOf(x.str("dueDate"))
                Mut((if (left > .005) "Te debe ${f.orig(left, x.str("currency"))} de ${f.orig(x.num("amount"), x.str("currency"))}" else "Ya te pagó todo") +
                    (if (due != null && left > .005) " · para el ${shortDate(due)}" else "") +
                    (x.str("note")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                Spacer(Modifier.height(6.dp))
                Bar(if (x.num("amount") > 0) x.num("paid") / x.num("amount") else 0.0)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Link("Editar", { st.open("editrec", x.id) })
                if (left > .005) Btn("Me pagó", { st.open("mepago", x.id) }, small = true)
            }
        }
    }
    Btn("Anotar un préstamo", { st.open("prestamo") })
}

/** Calendario del mes con pagos, compromisos, gastos fijos y cobros. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Calendario(st: AppState) {
    val d = st.repo.data
    val p = pal
    val ym = st.calYm
    val ev = calEvents(d, ym, st.fmt)
    val today = LocalDate.now()
    val first = ym.atDay(1).dayOfWeek.value - 1 // lunes = 0
    val last = ym.lengthOfMonth()
    QCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Link("‹", { st.calYm = ym.minusMonths(1); st.calDay = 0 })
            Text("${monthName(ym)} de ${ym.year}", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Link("›", { st.calYm = ym.plusMonths(1); st.calDay = 0 })
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "M", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp, color = p.mut, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        }
        val cells = List(first) { 0 } + (1..last).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == 0) Spacer(Modifier.weight(1f).height(44.dp))
                    else {
                        val isToday = ym == YearMonth.from(today) && day == today.dayOfMonth
                        val sel = st.calDay == day
                        Column(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) p.gs else Color.Transparent)
                                .clickable { st.calDay = if (sel) 0 else day },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("$day", fontSize = 14.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, color = if (isToday) p.g else p.ink)
                            Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                (ev[day] ?: emptyList()).map { it.kind }.distinct().take(3).forEach { k ->
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(p.evColor(k)))
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (st.calDay > 0) {
            val list = ev[st.calDay] ?: emptyList()
            if (list.isEmpty()) Mut("Nada para ese día.")
            list.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp).size(9.dp).clip(CircleShape).background(p.evColor(e.kind)))
                    Text(e.text, fontSize = 14.sp)
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Toca un día.", fontSize = 13.sp, color = p.mut)
                listOf("pago" to "pagos", "compromiso" to "compromisos", "fijo" to "fijos", "cobro" to "cobros").forEach { (k, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(p.evColor(k)))
                        Text(l, fontSize = 13.sp, color = p.mut)
                    }
                }
            }
        }
    }
}

// ---------- Legal, seguridad y términos ----------

fun openWeb(ctx: android.content.Context, path: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.API_BASE + path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun LegalLinks(center: Boolean = false) {
    val ctx = LocalContext.current
    Chips(center = center) {
        Link("Términos", { openWeb(ctx, "/terminos") })
        Link("Privacidad", { openWeb(ctx, "/privacidad") })
        Link("Cookies", { openWeb(ctx, "/cookies") })
    }
}

@Composable
fun LegalCard() {
    QCard {
        B("Legal")
        LegalLinks()
        Text(copyright(), fontSize = 13.sp, color = pal.mut)
    }
}

/** Pide aceptar los términos nuevos (una vez por versión). */
@Composable
fun TermsDialog(st: AppState) {
    val d = st.repo.data
    if (!st.repo.loggedIn || d.isEmpty() || d.str("termsVersion") == TERMS_VERSION) return
    val nuevo = d.arr("expenses").isEmpty() && d.arr("incomeSources").isEmpty() && d.arr("debts").isEmpty()
    var busy by remember { mutableStateOf(false) }
    val p = pal
    AlertDialog(
        onDismissRequest = {},
        containerColor = p.card,
        title = { Text(if (nuevo) "Antes de empezar" else "Actualizamos los términos", fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Mut(if (nuevo) "Para usar Quincena VE, revisa y acepta:" else "Para seguir usando Quincena VE, revisa y acepta los cambios:")
                listOf(
                    "Explicamos mejor qué datos guardamos y para qué. No los vendemos ni mostramos publicidad.",
                    "En la app tus datos se guardan cifrados en el teléfono.",
                    "Puedes cerrar la sesión en tus otros teléfonos desde Más.",
                ).forEach { t ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("✓", color = p.g, fontWeight = FontWeight.Bold); Text(t, fontSize = 15.sp) }
                }
                LegalLinks(center = true)
            }
        },
        confirmButton = {
            Btn("Acepto", {
                busy = true
                st.scope.launch {
                    try { st.repo.acceptTerms(); st.act(null) { it["termsVersion"] = kotlinx.serialization.json.JsonPrimitive(TERMS_VERSION) } }
                    catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") }
                    catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
                    busy = false
                }
            }, enabled = !busy)
        },
    )
}
