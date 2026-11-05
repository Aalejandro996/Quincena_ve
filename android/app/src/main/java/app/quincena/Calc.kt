package app.quincena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

val ES = Locale("es", "VE")

val CATS = linkedMapOf(
    "comida" to "Comida", "transporte" to "Transporte", "fijo" to "Gastos fijos", "hormiga" to "Gastos pequeños",
    "salud" to "Salud", "hogar" to "Hogar", "ocio" to "Ocio", "educacion" to "Educación", "deudas" to "Pagos de deudas",
)
val CAT_COLORS = mapOf(
    "comida" to 0xFF0B6B4DL, "transporte" to 0xFF2F6FDEL, "fijo" to 0xFFB25E00L, "hormiga" to 0xFFB3261EL,
    "salud" to 0xFF8E3FBFL, "hogar" to 0xFF2E8B9AL, "ocio" to 0xFFC2185BL, "educacion" to 0xFF5D6B1FL, "deudas" to 0xFF6B4EA2L,
)
val CONCEPTS = listOf(
    "mensualidad" to "Mensualidad", "matricula" to "Matrícula", "utiles" to "Útiles", "uniforme" to "Uniforme",
    "transporte" to "Transporte", "comedor" to "Comedor", "actividad" to "Actividad", "otro" to "Otro",
)
val CURRENCIES = listOf("USD" to "Dólares ($)", "BS" to "Bolívares (Bs.)")

// ---------- País y moneda local ----------
/** "BS" es el código interno de la moneda local ("mi moneda"); el país dice cuál es (Bs., pesos, soles…). */
class Country(val code: String, val name: String, val sym: String, val short: String, val plural: String, val dec: Int, val official: String, val rate: Double)

val COUNTRIES = listOf(
    Country("VE", "Venezuela", "Bs.", "Bs.", "bolívares", 2, "BCV", 40.0),
    Country("CO", "Colombia", "COP $", "COP", "pesos colombianos", 0, "TRM", 4000.0),
    Country("CL", "Chile", "CLP $", "CLP", "pesos chilenos", 0, "oficial", 950.0),
    Country("PE", "Perú", "S/", "S/", "soles", 2, "oficial", 3.7),
    Country("MX", "México", "MX$", "MXN", "pesos mexicanos", 2, "oficial", 18.0),
    Country("AR", "Argentina", "AR$", "ARS", "pesos argentinos", 2, "oficial", 1400.0),
)

/** País actual: es estado de Compose, así las pantallas se redibujan solas al cambiarlo. */
object Loc {
    var code by mutableStateOf("VE")
    val cur: Country get() = COUNTRIES.firstOrNull { it.code == code } ?: COUNTRIES[0]
    fun set(c: String?) { val n = if (COUNTRIES.any { it.code == c }) c!! else "VE"; if (n != code) code = n }
}

/** Adapta los textos escritos para Venezuela (Bs., bolívares, BCV) al país de la persona. En Venezuela no cambia nada. */
fun loc(s: String): String {
    val c = Loc.cur
    if (c.code == "VE") return s
    val pl = c.plural.replaceFirstChar { it.uppercase() }
    return s.replace("Bolívares", pl).replace("bolívares", c.plural).replace("Bs.", c.sym).replace(Regex("\\bBs\\b"), c.sym).replace(Regex("\\bBCV\\b"), c.official)
}

/** País sugerido por la zona horaria del teléfono. */
fun guessCountry(): String {
    val z = java.util.TimeZone.getDefault().id
    return when {
        z.contains("Bogota") -> "CO"
        z.contains("Santiago") || z.contains("Punta_Arenas") -> "CL"
        z.contains("Lima") -> "PE"
        Regex("Mexico|Cancun|Monterrey|Merida|Tijuana|Hermosillo|Chihuahua|Mazatlan").containsMatchIn(z) -> "MX"
        z.contains("Argentina") -> "AR"
        else -> "VE"
    }
}

val WEEKDAYS = listOf("1" to "Lunes", "2" to "Martes", "3" to "Miércoles", "4" to "Jueves", "5" to "Viernes", "6" to "Sábado", "7" to "Domingo")

/** Cobros de un ingreso semanal que caen entre [from] y [to] (dow: 1 = lunes … 7 = domingo). */
fun weekdayCount(dow: Int, from: LocalDate, to: LocalDate): Int {
    var n = 0
    var d = from
    while (!d.isAfter(to)) { if (d.dayOfWeek.value == dow) n++; d = d.plusDays(1) }
    return n
}

// ---------- Lectura y escritura de JSON ----------

fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())
val JsonObject.id: String get() = str("id") ?: ""

fun el(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { el(it) })
    else -> JsonPrimitive(v.toString())
}

/** Crea una fila nueva. Los valores null se omiten. */
fun row(vararg p: Pair<String, Any?>): JsonObject = JsonObject(p.filter { it.second != null }.associate { it.first to el(it.second) })

/** Copia la fila cambiando campos. Un valor null borra el campo. */
fun JsonObject.plusFields(vararg p: Pair<String, Any?>): JsonObject {
    val m = toMutableMap()
    for ((k, v) in p) if (v == null) m.remove(k) else m[k] = el(v)
    return JsonObject(m)
}

fun MutableMap<String, JsonElement>.rows(k: String): MutableList<JsonObject> =
    (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }?.toMutableList() ?: mutableListOf()
fun MutableMap<String, JsonElement>.setRows(k: String, v: List<JsonObject>) { this[k] = JsonArray(v) }
fun MutableMap<String, JsonElement>.addFirst(k: String, r: JsonObject) = setRows(k, listOf(r) + rows(k))
fun MutableMap<String, JsonElement>.updateRow(k: String, id: String, f: (JsonObject) -> JsonObject) =
    setRows(k, rows(k).map { if (it.id == id) f(it) else it })
fun MutableMap<String, JsonElement>.removeRow(k: String, id: String) = setRows(k, rows(k).filter { it.id != id })
fun MutableMap<String, JsonElement>.data() = JsonObject(this)

fun newId() = "q" + System.currentTimeMillis()
fun nowIso(): String = Instant.now().toString()

// ---------- Formato ----------

/** Formato de montos: oculta, cambia de moneda y usa coma decimal, igual que la web. */
class Fmt(val hide: Boolean, val bs: Boolean, val rate: Double, val mode: String = "USD", val alt: Double = 0.0) {
    private fun n(v: Double) = String.format(ES, "%,.2f", v)
    private fun nl(v: Double) = String.format(ES, "%,." + Loc.cur.dec + "f", v)
    /** Totales en $ BCV vistos en $, USDT, € (con su tasa en Bs., [alt]) o la moneda local. */
    fun show(usd: Double) = if (hide) "••••" else if (bs) Loc.cur.sym + " " + nl(usd * rate)
        else if (mode == "USDT" && alt > 0) "USDT " + n(usd * rate / alt)
        else if (mode == "EUR" && alt > 0) "€ " + n(usd * rate / alt) else "\$ " + n(usd)
    fun orig(a: Double, cur: String?) = if (hide) "••••" else if (cur == "BS") Loc.cur.sym + " " + nl(a) else "\$ " + n(a)
    fun plain(v: Double) = n(v)
    /** Monto en moneda local (sin decimales en pesos colombianos y chilenos). */
    fun local(v: Double) = nl(v)
}

/** Lee montos escritos como 12.5, 12,50 o 1.234,56. */
fun parseMoney(s: String): Double? {
    var t = s.trim().replace(" ", "")
    t = if (t.contains(',')) t.replace(".", "").replace(',', '.')
    else if (Regex("^\\d{1,3}(\\.\\d{3})+$").matches(t)) t.replace(".", "") else t
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Monto para precargar un campo: 12,5 (vacío si no hay valor). */
fun inputMoney(v: Double?): String {
    if (v == null || v == 0.0) return ""
    val s = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    return s.replace('.', ',')
}

fun monthName(ym: YearMonth, withYear: Boolean = false): String {
    val m = ym.month.getDisplayName(TextStyle.FULL, Locale("es"))
    return if (withYear) "$m de ${ym.year}" else m
}
fun shortDate(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale("es")).trimEnd('.')}"
fun longDate(d: LocalDate) = "${d.dayOfMonth} de ${d.month.getDisplayName(TextStyle.FULL, Locale("es"))}"

// ---------- Periodos ----------

data class Period(val ym: YearMonth, val half: Int) {
    val from: LocalDate get() = ym.atDay(if (half == 2) 16 else 1)
    val to: LocalDate get() = if (half == 1) ym.atDay(15) else ym.atEndOfMonth()
    fun step(d: Int): Period {
        if (half == 0) return Period(ym.plusMonths(d.toLong()), 0)
        var h = half + d
        var m = ym
        if (h > 2) { h = 1; m = m.plusMonths(1) }
        if (h < 1) { h = 2; m = m.minusMonths(1) }
        return Period(m, h)
    }
    fun label(): String {
        val month = ym.month.getDisplayName(TextStyle.FULL, Locale("es"))
        val year = if (ym.year != LocalDate.now().year) " ${ym.year}" else ""
        return (if (half > 0) "$half.ª quincena de " else "") + month + year
    }
}

fun currentPeriod(): Period {
    val t = LocalDate.now()
    return Period(YearMonth.from(t), if (t.dayOfMonth <= 15) 1 else 2)
}

// ---------- Cálculos (los mismos de la web) ----------

fun rateOf(d: JsonObject): Double = d.num("exchangeRate")

// ---------- Las tres tasas ----------

val RATE_LABELS = mapOf("bcv" to "BCV", "usdt" to "USDT", "otra" to "otra tasa")
fun marketRateOf(d: JsonObject): Double = d.num("marketRate")

/** Costo real en USD: si fue en Bs., se divide entre la tasa a la que se consiguieron esos bolívares. */
fun realUsd(d: JsonObject, e: JsonObject): Double {
    if (e.str("currency") != "BS") return e.num("amount")
    val r = e.num("rateUsed").takeIf { it > 0 } ?: e.num("bcvRate").takeIf { it > 0 } ?: rateOf(d).takeIf { it > 0 } ?: 1.0
    return e.num("amount") / r
}
fun bcvUsd(d: JsonObject, e: JsonObject): Double {
    if (e.str("currency") != "BS") return e.num("amount")
    val r = e.num("bcvRate").takeIf { it > 0 } ?: rateOf(d).takeIf { it > 0 } ?: 1.0
    return e.num("amount") / r
}

data class Tasas(val n: Int, val atBcv: Double, val real: Double, val tagged: Int) { val diff get() = atBcv - real }
fun tasasDelMes(d: JsonObject): Tasas {
    val ym = YearMonth.now()
    val ex = d.arr("expenses").filter { it.str("currency") == "BS" && inRange(it.str("date"), ym.atDay(1), ym.atEndOfMonth()) }
    return Tasas(ex.size, ex.sumOf { bcvUsd(d, it) }, ex.sumOf { realUsd(d, it) }, ex.count { (it.str("rateType") ?: "bcv") != "bcv" })
}

/** Campos de tasa para guardar en un gasto (null = se borra el campo). */
fun rateFields(d: JsonObject, cur: String, type: String, other: String): Array<Pair<String, Any?>> {
    if (cur != "BS") return arrayOf("rateType" to null, "rateUsed" to null, "bcvRate" to null, "marketRate" to null)
    val bcv = rateOf(d)
    val market = marketRateOf(d)
    val cambio = lastCambio(d)
    if (type == "cambio" && cambio > 0) return arrayOf("rateType" to "otra", "rateUsed" to cambio, "bcvRate" to bcv, "marketRate" to market.takeIf { it > 0 })
    val o = parseMoney(other)?.takeIf { it > 0 }
    val t = when { type == "usdt" && market > 0 -> "usdt"; type == "otra" && o != null -> "otra"; else -> "bcv" }
    val used = when (t) { "usdt" -> market; "otra" -> o!!; else -> bcv }
    return arrayOf("rateType" to t, "rateUsed" to used, "bcvRate" to bcv, "marketRate" to market.takeIf { it > 0 })
}

fun usdOf(d: JsonObject, a: Double, cur: String?): Double {
    val r = rateOf(d)
    return if (cur == "BS") a / (if (r > 0) r else 1.0) else a
}

fun dateOf(s: String?): LocalDate? = s?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
fun inRange(s: String?, from: LocalDate, to: LocalDate): Boolean {
    val x = dateOf(s) ?: return false
    return !x.isBefore(from) && !x.isAfter(to)
}
fun daysUntil(d: LocalDate) = ChronoUnit.DAYS.between(LocalDate.now(), d).toInt()

fun monthlyIncome(d: JsonObject): Double {
    val srcs = d.arr("incomeSources")
    if (srcs.isEmpty()) {
        val i = d.obj("income")
        return usdOf(d, i.num("amount"), i.str("currency"))
    }
    return srcs.sumOf { s ->
        val a = usdOf(d, s.num("amount"), s.str("currency"))
        when (s.str("frequency")) { "semanal" -> a * 52 / 12; "quincenal" -> a * 2; "mensual" -> a; else -> 0.0 }
    }
}

fun periodIncome(d: JsonObject, p: Period): Double {
    val srcs = d.arr("incomeSources")
    val extra = extraIn(d, p.from, p.to)
    if (srcs.isEmpty()) return monthlyIncome(d) * (if (p.half == 0) 1.0 else 0.5) + extra
    var inc = extra
    for (s in srcs) {
        val a = usdOf(d, s.num("amount"), s.str("currency"))
        when (s.str("frequency")) {
            "semanal" -> {
                val dow = (s["payDays"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull ?: 5
                inc += a * weekdayCount(dow, p.from, p.to)
            }
            "quincenal" -> inc += a * (if (p.half == 0) 2 else 1)
            "mensual" -> {
                val day = (s["payDays"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull ?: 1
                if (p.half == 0 || (p.half == 1) == (day <= 15)) inc += a
            }
        }
    }
    return inc
}

fun spentIn(d: JsonObject, from: LocalDate, to: LocalDate): Double =
    d.arr("expenses").filter { inRange(it.str("date"), from, to) }.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }

fun spentByCat(d: JsonObject, from: LocalDate, to: LocalDate): Map<String, Double> {
    val m = mutableMapOf<String, Double>()
    for (e in d.arr("expenses")) if (inRange(e.str("date"), from, to)) {
        val k = e.str("category") ?: "fijo"
        m[k] = (m[k] ?: 0.0) + usdOf(d, e.num("amount"), e.str("currency"))
    }
    return m
}

fun schoolPaid(d: JsonObject, expenseId: String) = d.arr("schoolPayments").filter { it.str("expenseId") == expenseId }.sumOf { it.num("amount") }
fun schoolLeft(d: JsonObject, x: JsonObject) = max(0.0, x.num("amount") - schoolPaid(d, x.id))

/** Con un gasto en Bs. de otro día, usa la tasa BCV (y USDT) de ese día si está en el historial. */
fun dayRates(d: JsonObject, day: String, rf: Array<Pair<String, Any?>>): Array<Pair<String, Any?>> {
    val h = (d["rateHistory"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("d") == day } ?: return rf
    val m = rf.toMap().toMutableMap()
    val old = m["bcvRate"] as? Double ?: return rf
    val b = h.num("b").takeIf { it > 0 } ?: return rf
    val mk = h.num("m").takeIf { it > 0 }
    m["bcvRate"] = b
    if (m["rateType"] == "bcv" && m["rateUsed"] == old) m["rateUsed"] = b
    if (m["rateType"] == "usdt" && mk != null) m["rateUsed"] = mk
    if (mk != null) m["marketRate"] = mk
    return m.toList().toTypedArray()
}

/** Gastos para vivir: promedio de los últimos 3 meses con gastos, sin los pagos de deudas. */
fun livingGuess(d: JsonObject): Double {
    val now = YearMonth.now()
    val vals = (1..3).map { i ->
        val ym = now.minusMonths(i.toLong())
        d.arr("expenses").filter { it.str("category") != "deudas" && inRange(it.str("date"), ym.atDay(1), ym.atEndOfMonth()) }
            .sumOf { usdOf(d, it.num("amount"), it.str("currency")) }
    }.filter { it > 0 }
    return if (vals.isEmpty()) 0.0 else Math.round(vals.average()).toDouble()
}

private fun nrm(t: String?) = java.text.Normalizer.normalize(t.orEmpty().lowercase(), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

/** Evita anotar lo mismo como pago fijo y como compromiso (se contaría dos veces). Devuelve el aviso o "". */
fun dupOf(d: JsonObject, desc: String, amt: Double, cur: String): String {
    val n = nrm(desc)
    fun hit(t: String?, a: Double, c: String?): Boolean {
        val m = nrm(t)
        return m.isNotEmpty() && n.isNotEmpty() && (m == n || (n.length >= 4 && m.contains(n)) || (m.length >= 4 && n.contains(m)) || (abs(a - amt) < .005 && c == cur && amt > 0))
    }
    d.arr("recurring").firstOrNull { hit(it.str("description"), it.num("amount"), it.str("currency")) }?.let { return "«${it.str("description")}» ya está como pago fijo de cada mes" }
    d.arr("schoolExpenses").firstOrNull { schoolLeft(d, it) > .005 && hit(it.str("description"), it.num("amount"), it.str("currency")) }
        ?.let { return "«${it.str("description")}» ya está como compromiso" }
    return ""
}

/** Un pago fijo ya está pagado este mes si existe su gasto rec_<id>_<AAAA-MM>. */
fun recPaid(d: JsonObject, r: JsonObject, ym: YearMonth): Boolean {
    val id = "rec_${r.id}_" + String.format(Locale.US, "%04d-%02d", ym.year, ym.monthValue)
    return d.arr("expenses").any { it.id == id }
}
fun recDay(r: JsonObject, ym: YearMonth): LocalDate = ym.atDay(min(r.num("day").toInt().coerceAtLeast(1), ym.lengthOfMonth()))

fun dueIn(d: JsonObject, from: LocalDate, to: LocalDate, withFixed: Boolean = true): Double {
    var due = 0.0
    // Pagos fijos del periodo que todavía no se anotaron (los automáticos se anotan solos ese día; los manuales al pagarlos).
    val ym = YearMonth.from(from)
    if (withFixed) for (r in d.arr("recurring")) if (r.bool("active") != false) {
        val day = recDay(r, ym)
        if (!day.isBefore(from) && !day.isAfter(to) && !recPaid(d, r, ym)) due += usdOf(d, r.num("amount"), r.str("currency"))
    }
    for (p in d.arr("plannedDebtPayments")) if (p.str("status") == "planned" && inRange(p.str("scheduledDate"), from, to)) {
        due += usdOf(d, p.num("amount"), p.str("currency"))
    }
    for (card in d.arr("debts")) if (card.str("type") == "tdc" && card.num("minPayment") > 0) {
        val month = YearMonth.from(from)
        val day = localDay(card.str("payBefore"))?.dayOfMonth ?: continue
        val dueDate = month.atDay(day.coerceAtMost(month.lengthOfMonth()))
        val hasScheduled = d.arr("plannedDebtPayments").any { it.str("debtId") == card.id && it.str("status") == "planned" && inRange(it.str("scheduledDate"), month.atDay(1), month.atEndOfMonth()) }
        if (!hasScheduled && !dueDate.isBefore(from) && !dueDate.isAfter(to)) due += usdOf(d, card.num("minPayment"), card.str("currency"))
    }
    for (x in d.arr("schoolExpenses")) if (inRange(x.str("dueDate"), from, to)) {
        due += usdOf(d, schoolLeft(d, x), x.str("currency"))
    }
    return due
}

data class Summary(val income: Double, val spent: Double, val due: Double) { val free get() = income - spent - due }
fun summarize(d: JsonObject, p: Period) = Summary(periodIncome(d, p), spentIn(d, p.from, p.to), dueIn(d, p.from, p.to))

data class DebtTotals(val total: Double, val paid: Double) { val remaining get() = total - paid }
fun debtTotals(d: JsonObject): DebtTotals {
    val ds = d.arr("debts")
    return DebtTotals(ds.sumOf { usdOf(d, it.num("amount"), it.str("currency")) }, ds.sumOf { usdOf(d, it.num("paid"), it.str("currency")) })
}

/** Resumen del mes en curso (igual que calc() en la web). */
data class Month(val inc: Double, val spent: Double, val cat: Map<String, Double>, val debt: Double, val pay: Double) {
    val left get() = inc - spent
    val months: Int? get() = if (debt > 0 && pay > 0) ceil(debt / pay).toInt() else null
}
fun monthSummary(d: JsonObject): Month {
    val ym = YearMonth.now()
    val inc = monthlyIncome(d) + extraIn(d, ym.atDay(1), ym.atEndOfMonth())
    val cat = spentByCat(d, ym.atDay(1), ym.atEndOfMonth())
    val spent = cat.values.sum()
    val debt = debtTotals(d).remaining
    val pct = d.obj("debtPlan")["paymentPercent"]?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 60.0
    return Month(inc, spent, cat, debt, max(0.0, inc - spent) * pct / 100)
}

/** Simulador "Pagar deudas sin ahogarte": avalancha o bola de nieve, igual que plan() en la web. */
data class Plan(val configured: Boolean, val rec: Double, val over: Boolean, val months: Int?, val interest: Double, val strategy: String,
    val payoff: Map<String, Int> = emptyMap(), val first: Map<String, Double> = emptyMap(), val cap: Double = 0.0, val minT: Double = 0.0)

/** [strat] permite comparar con la otra estrategia sin cambiar el plan guardado. */
fun debtPlan(d: JsonObject, strat: String? = null): Plan {
    val s = d.obj("debtPlan")
    val inc = monthlyIncome(d)
    val ess = max(0.0, s.num("monthlyEssentials"))
    val cu = max(0.0, s.num("monthlyCushion"))
    val cap = max(0.0, inc - ess - cu)
    val pp = (s.num("paymentPercent").takeIf { it > 0 } ?: 60.0).coerceIn(30.0, 85.0)

    class B(val id: String, var bal: Double, val min: Double, val mr: Double, val known: Boolean)
    val ds = d.arr("debts").map { x ->
        val cur = x.str("currency")
        val bal = max(0.0, usdOf(d, x.num("amount") - x.num("paid"), cur))
        val mn = min(bal, max(0.0, usdOf(d, x.num("minPayment"), cur)))
        val ik = x.bool("interestKnown")
        val known = ik == true || (x["interestKnown"] == null && x.num("interestRate") > 0)
        val r = if (known) max(0.0, x.num("interestRate")) / 100 else 0.0
        val mr = if (!known) 0.0 else if (x.str("interestPeriod") == "semanal") (1 + r).pow(52.0 / 12) - 1 else r
        B(x.id, bal, mn, mr, known)
    }.filter { it.bal > .005 }

    val minT = ds.sumOf { it.min }
    val rec = min(cap, max(minT, cap * pp / 100))
    val strategy = strat ?: if (s.str("strategy") == "snowball") "snowball" else "avalanche"
    val configured = s.bool("configured") == true
    val over = minT > cap + .01
    if (!configured || ds.isEmpty() || ds.any { it.min <= .005 } || over || rec <= 0) return Plan(configured, rec, over, null, 0.0, strategy, cap = cap, minT = minT)

    val b = ds.map { B(it.id, it.bal, it.min, it.mr, it.known) }
    val payoff = mutableMapOf<String, Int>()
    val first = mutableMapOf<String, Double>()  // lo que toca pagar a cada deuda este mes
    val total = b.sumOf { it.bal }
    var m = 0
    var int = 0.0
    while (b.any { it.bal > .02 } && m < 600) {
        m++
        var it2 = 0.0
        for (x in b) { val i = x.bal * x.mr; x.bal += i; it2 += i }
        int += it2
        var bud = rec
        for (x in b) {
            if (x.bal <= .02) continue
            val p = minOf(x.min, x.bal, bud); x.bal -= p; bud -= p
            if (m == 1) first[x.id] = (first[x.id] ?: 0.0) + p
        }
        val open = b.filter { it.bal > .02 }
        val o = if (strategy == "snowball") open.sortedBy { it.bal }
        else open.sortedWith(compareByDescending<B> { if (it.known) it.mr else -1.0 }.thenBy { it.bal })
        for (x in o) {
            if (bud <= .005) break
            val p = min(x.bal, bud); x.bal -= p; bud -= p
            if (m == 1) first[x.id] = (first[x.id] ?: 0.0) + p
        }
        for (x in b) if (x.bal <= .02 && x.id !in payoff) payoff[x.id] = m
        if (m > 12 && b.sumOf { it.bal } >= total) break
    }
    return if (b.none { it.bal > .02 }) Plan(true, rec, false, m, int, strategy, payoff, first, cap, minT) else Plan(true, rec, false, null, 0.0, strategy, payoff, first, cap, minT)
}

// ---------- Mantenimiento al cargar (igual que load() + genRecurring() en la web) ----------

/** Pasa abonos viejos a "Pagos de deudas" y registra los gastos fijos del mes. Devuelve true si cambió algo. */
fun maintain(m: MutableMap<String, JsonElement>): Boolean {
    var changed = false
    val debtLike = Regex("_abono$|_paid_")
    val ex = m.rows("expenses").map { e ->
        if (e.str("category") == "fijo" && (debtLike.containsMatchIn(e.id) || (e.str("description") ?: "").let { it.startsWith("Abono a ") || it.startsWith("Pago a ") })) {
            changed = true; e.plusFields("category" to "deudas")
        } else e
    }.toMutableList()

    val now = java.time.LocalDateTime.now()
    val cur = now.year * 12 + now.monthValue - 1
    val rec = m.rows("recurring").map { r ->
        if (r.bool("active") == false || r.str("mode") == "manual") return@map r
        val lg = r.str("lastGenerated").orEmpty()
        var last = if (lg.length >= 7) (lg.take(4).toIntOrNull() ?: 0) * 12 + (lg.substring(5, 7).toIntOrNull() ?: 1) - 1 else cur - 1
        last = max(last, cur - 24)
        var out = r
        for (i in last + 1..cur) {
            val y = i / 12
            val mo = i % 12 + 1
            val ym = YearMonth.of(y, mo)
            val day = min(r.num("day").toInt().coerceAtLeast(1), ym.lengthOfMonth())
            val dt = ym.atDay(day).atTime(12, 0)
            if (dt.isAfter(now)) break
            val key = String.format(Locale.US, "%04d-%02d", y, mo)
            ex.add(0, row(
                "id" to "rec_${r.id}_$key", "amount" to (r["amount"] ?: JsonPrimitive(0)), "currency" to (r["currency"] ?: JsonPrimitive("USD")),
                "category" to (r["category"] ?: JsonPrimitive("fijo")), "description" to (r["description"] ?: JsonPrimitive("")),
                "method" to "efectivo", "bank" to "", "date" to dt.atZone(ZoneId.systemDefault()).toInstant().toString(),
            ))
            out = out.plusFields("lastGenerated" to key)
            changed = true
        }
        out
    }
    if (changed) { m.setRows("expenses", ex); m.setRows("recurring", rec) }
    return changed
}
