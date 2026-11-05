package app.quincena

import kotlinx.serialization.json.JsonObject
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

// Reglas de Quinci: observaciones personalizadas con los datos de cada persona, pensadas para el día a día del venezolano.
// No hay inteligencia artificial ni se envía nada fuera de la cuenta: todo se calcula en el teléfono.
// Es la misma lógica de la web (static/quinci_reglas.js). Si se cambia una regla, cambiarla en los dos lados.

/** Una observación: [pri] importancia (0–100) y [cd] días de descanso después de mostrarse. */
data class QRule(val id: String, val pri: Int, val cd: Int, val mood: String, val prop: String, val label: String, val text: String,
                 val btn: String? = null, val go: ((AppState) -> Unit)? = null)

/** Lo que las reglas necesitan saber de la quincena de hoy. */
class QCtx(val d: JsonObject, val f: Fmt) {
    val today: LocalDate = LocalDate.now()
    val cur: Period = currentPeriod()
    val prev: Period = cur.step(-1)
    val ym: YearMonth = YearMonth.from(today)
    val day = today.dayOfMonth
    val last = ym.lengthOfMonth()
    val daysLeft = maxOf(1, ChronoUnit.DAYS.between(today, cur.to).toInt() + 1)
    val month0 = today.monthValue - 1  // 0 = enero, como en la web
    val dow: DayOfWeek = today.dayOfWeek
    val list = exps(cur.from, cur.to)
    val month = exps(ym.atDay(1), ym.atEndOfMonth())
    val inc = periodIncome(d, cur)
    val spent = list.sumOf { u(it) }
    val free = inc - spent - dueIn(d, cur.from, cur.to)
    val cat = byCat(list)
    val prevCat = byCat(exps(prev.from, prev.to))
    val ve = (d.str("country") ?: "VE") == "VE"

    fun exps(a: LocalDate, b: LocalDate) = d.arr("expenses").filter { e -> localDay(e.str("date"))?.let { !it.isBefore(a) && !it.isAfter(b) } == true }
    fun u(e: JsonObject) = usdOf(d, e.num("amount"), e.str("currency"))
    fun byCat(l: List<JsonObject>): Map<String, Double> { val m = mutableMapOf<String, Double>(); for (e in l) { val k = e.str("category") ?: "fijo"; m[k] = (m[k] ?: 0.0) + u(e) }; return m }
    fun left(x: JsonObject) = maxOf(0.0, x.num("amount") - x.num("paid"))
    fun V(a: String, b: String) = if (ve) a else b
    fun show(v: Double) = f.show(v)
    fun money(a: Double, c: String?) = f.orig(a, c)
}

private fun low(t: String?) = Normalizer.normalize(t.orEmpty().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
private fun has(t: String?, words: List<String>) = words.any { low(t).contains(it) }
private fun plural(n: Int, s: String, p: String) = "$n ${if (n == 1) s else p}"
private fun sumU(c: QCtx, l: List<JsonObject>) = l.sumOf { c.u(it) }

private val QRULES: List<(QCtx) -> QRule?> = listOf<(QCtx) -> QRule?>(
    // ───────── Cobro y fin de quincena
    { c ->
        val fix = c.d.arr("recurring").filter { it.bool("active") != false }.sumOf { c.u(it) } / 2
        if (c.d.arr("incomeSources").none { it.str("frequency") == "quincenal" } || !(c.day == 15 || c.day == c.last || c.day == 30)) null
        else QRule("cobro_hoy", 72, 1, "celebra", "confeti", "", c.V("¡Hoy es día de cobro! Apenas te caiga, aparta primero lo de los fijos${if (fix > 0) " (unos ${c.show(fix)})" else ""} y lo de las deudas. Lo que quede es lo tuyo.",
            "¡Hoy es día de cobro! Aparta primero lo de los fijos${if (fix > 0) " (unos ${c.show(fix)})" else ""} y lo de las deudas."))
    },
    { c ->
        if (c.d.arr("incomeSources").none { it.str("frequency") == "quincenal" } || !(c.day == 14 || c.day == c.last - 1)) null
        else QRule("cobro_manana", 50, 1, "pensando", "calendario", if (c.day == 14) "15" else c.last.toString(),
            c.V("Mañana cobras. Aguanta un poquito y no gastes hoy en nada que pueda esperar.", "Mañana cobras. Evita hoy los gastos que puedan esperar."))
    },
    { c ->
        val p = if (c.inc > 0) c.spent / c.inc else 0.0
        if (c.inc <= 0 || c.day - (if (c.cur.half == 1) 1 else 16) > 3 || p < .5) null
        else QRule("arranque_fuerte", 74, 3, "preocupado", "lupa", "", c.V("En los primeros días ya se fue el ${(p * 100).roundToInt()} % de la quincena. ¡Epa! Pisa el freno, que todavía falta un trecho para el próximo cobro.",
            "En los primeros días ya se fue el ${(p * 100).roundToInt()} % de la quincena. Conviene frenar un poco."), "Ver mis gastos") { it.go("movimientos") }
    },
    { c ->
        if (c.inc <= 0 || c.free <= 0 || c.daysLeft < 3 || c.daysLeft > 12) null
        else QRule("por_dia", 34, 3, "pensando", "", "", c.V("Para llegar al cobro te tocan unos ${c.show(c.free / c.daysLeft)} por día (${plural(c.daysLeft, "día", "días")}). Si hoy gastas menos, mañana tienes más.",
            "Para llegar al cobro te tocan unos ${c.show(c.free / c.daysLeft)} por día durante ${plural(c.daysLeft, "día", "días")}."))
    },
    { c ->
        if (c.inc <= 0 || c.daysLeft > 4 || c.free < 0 || c.free > c.inc * .12) null
        else QRule("cierre_apretado", 62, 2, "preocupado", "", "", c.V("Quedan ${plural(c.daysLeft, "día", "días")} para cobrar y te quedan ${c.show(c.free)}. Toca estirar: solo lo necesario hasta el cobro.",
            "Quedan ${plural(c.daysLeft, "día", "días")} para cobrar y te quedan ${c.show(c.free)}. Solo lo necesario hasta entonces."))
    },
    { c ->
        if (c.inc <= 0 || c.daysLeft > 5 || c.free < c.inc * .2) null
        else QRule("sobra_fondito", 40, 7, "celebra", "", "", c.V("¡Qué nota! Vas cerrando la quincena con ${c.show(c.free)}. Si apartas ${c.show(c.free * .5)} para tu fondito de emergencia, ni lo vas a sentir.",
            "Vas cerrando la quincena con ${c.show(c.free)}. Si apartas ${c.show(c.free * .5)} para emergencias, ni lo vas a notar."))
    },
    { c ->
        if (c.d.arr("incomeSources").none { it.str("frequency") == "semanal" } || c.dow != DayOfWeek.MONDAY) null
        else QRule("semanal", 18, 14, "feliz", "", "", "Cobras por semana: piensa cada semana como tu mini quincena y reparte lo de los fijos entre las cuatro.")
    },

    // ───────── Categorías
    { c ->
        val best = CATS.keys.filter { it != "deudas" && it != "fijo" }.mapNotNull { k ->
            val a = c.cat[k] ?: 0.0; val b = c.prevCat[k] ?: 0.0
            if (b >= 5 && a > b * 1.3 && a - b >= 10) Triple(k, a, a - b) else null
        }.maxByOrNull { it.third }
        if (best == null) null
        else QRule("sube_${best.first}", 56, 4, "alerta", "lupa", "", c.V("Esta quincena llevas ${c.show(best.second)} en ${CATS[best.first]}: ${c.show(best.third)} más que la quincena pasada. Échale un ojo a qué se fue.",
            "Esta quincena llevas ${c.show(best.second)} en ${CATS[best.first]}: ${c.show(best.third)} más que la anterior."), "Ver mis gastos") { it.go("movimientos") }
    },
    { c ->
        val best = if (c.daysLeft > 3) null else CATS.keys.filter { it != "deudas" && it != "fijo" }.mapNotNull { k ->
            val a = c.cat[k] ?: 0.0; val b = c.prevCat[k] ?: 0.0
            if (b >= 20 && a < b * .7) k to (b - a) else null
        }.maxByOrNull { it.second }
        if (best == null) null
        else QRule("baja_${best.first}", 42, 7, "celebra", "confeti", "", c.V("¡Qué nota! Gastaste ${c.show(best.second)} menos en ${CATS[best.first]} que la quincena pasada. Así es que se hace.",
            "Gastaste ${c.show(best.second)} menos en ${CATS[best.first]} que la quincena pasada. ¡Bien hecho!"))
    },
    { c ->
        val top = c.cat.filterKeys { it != "deudas" && it != "fijo" }.maxByOrNull { it.value }
        if (c.spent < 30 || top == null || top.value / c.spent < .45) null
        else QRule("top_${top.key}", 30, 7, "pensando", "", "", "Casi la mitad de lo que gastas esta quincena se va en ${CATS[top.key] ?: "una sola cosa"} (${c.show(top.value)}). Si recortas un poquito ahí, se nota en todo lo demás.")
    },
    { c ->
        val small = c.list.filter { it.str("category") == "hormiga" || (c.u(it) > 0 && c.u(it) < 3 && it.str("category") != "deudas") }
        if (small.size < 8) null
        else QRule("hormiga", 50, 5, "pensando", "lupa", "", c.V("Llevas ${small.size} gastos chiquitos esta quincena (el cafecito, el pasaje, la chuchería…) que suman ${c.show(sumU(c, small))}. Uno a uno no se sienten; juntos, sí.",
            "Llevas ${small.size} gastos pequeños esta quincena que suman ${c.show(sumU(c, small))}. Uno a uno no se sienten; juntos, sí."))
    },
    { c ->
        val l = c.month.filter { has(it.str("description"), listOf("delivery", "yummy", "pedidosya", "pedidos ya", "rappi", "uber eats")) }
        if (l.size < 3 || sumU(c, l) < 15) null
        else QRule("delivery", 46, 7, "pensando", "", "", c.V("Este mes llevas ${c.show(sumU(c, l))} en delivery (${plural(l.size, "pedido", "pedidos")}). Cocinar en casa unos días a la semana te ahorra un realero.",
            "Este mes llevas ${c.show(sumU(c, l))} en delivery (${plural(l.size, "pedido", "pedidos")}). Cocinar en casa algunos días ahorra bastante."))
    },
    { c ->
        val l = c.month.filter { has(it.str("description"), listOf("taxi", "ridery", "yummy rides", "uber", "mototaxi", "indrive", "cabify")) }
        if (l.size < 4 || sumU(c, l) < 15) null
        else QRule("taxis", 38, 7, "pensando", "", "", c.V("Este mes llevas ${c.show(sumU(c, l))} en taxis y carreritas (${plural(l.size, "viaje", "viajes")}). Si alguno lo puedes hacer en camionetica o en metro, ahí hay un ahorro.",
            "Este mes llevas ${c.show(sumU(c, l))} en taxis y apps de transporte (${plural(l.size, "viaje", "viajes")})."))
    },
    { c ->
        val l = c.month.filter { has(it.str("description"), listOf("gasolina", "combustible", "bomba", "gasoil")) }
        if (l.size < 2) null
        else QRule("gasolina", 22, 14, "feliz", "", "", c.V("Este mes van ${c.show(sumU(c, l))} en gasolina. Llevar la cuenta te ayuda a saber cuánto apartar cada quincena para la bomba.",
            "Este mes van ${c.show(sumU(c, l))} en combustible. Así sabes cuánto apartar cada quincena."))
    },
    { c ->
        val wk = setOf(c.today.minusDays(2), c.today.minusDays(1))
        val l = c.d.arr("expenses").filter { it.str("category") == "ocio" && localDay(it.str("date"))?.let { day -> day in wk } == true }
        if (c.dow != DayOfWeek.MONDAY || sumU(c, l) < 15) null
        else QRule("finde", 36, 7, "pensando", "", "", c.V("El fin de semana se fueron ${c.show(sumU(c, l))} en salidas. ¡Bien que lo disfrutaste! Ahora toca una semanita tranquila para compensar.",
            "El fin de semana se fueron ${c.show(sumU(c, l))} en salidas. Esta semana conviene compensar."))
    },
    { c ->
        if (c.dow != DayOfWeek.FRIDAY || c.inc <= 0) null
        else QRule("viernes", 20, 7, "feliz", "", "", c.V("¡Llegó el viernes! Si vas a salir, ponte un tope antes de arrancar. Así lo disfrutas sin ratón financiero el lunes.",
            "Llegó el viernes. Si vas a salir, ponte un tope antes de empezar."))
    },
    { c ->
        val big = c.list.filter { it.str("category") != "deudas" && it.str("category") != "fijo" && !it.id.startsWith("rec_") }.maxByOrNull { c.u(it) }
        if (c.inc <= 0 || big == null || c.u(big) < c.inc * .25) null
        else QRule("grande_${big.id}", 44, 30, "alerta", "", "", "El gasto «${big.str("description")?.takeIf { it.isNotBlank() } ?: CATS[big.str("category")] ?: "sin nota"}» (${c.money(big.num("amount"), big.str("currency"))}) se llevó buena parte de la quincena. Si no estaba planificado, ajusta lo demás para que te alcance.")
    },
    { c ->
        val com = c.cat["comida"] ?: 0.0
        if (com < 40 || c.d.obj("budgets").num("comida") > 0) null
        else QRule("comida_sin_pres", 33, 10, "pensando", "", "", c.V("Llevas ${c.show(com)} en comida esta quincena y no tiene presupuesto. Ponle un tope y haz la compra con lista: en el mercado es donde más se va el real.",
            "Llevas ${c.show(com)} en comida esta quincena sin presupuesto. Ponle un tope."), "Poner presupuesto") { it.open("presupuesto") }
    },

    // ───────── Bolívares y tasas
    { c ->
        val bs = bsBalance(c.d); val r = rateOf(c.d)
        if (!c.ve || bs == null || r <= 0 || bs / r < 25) null
        else QRule("bs_guardados", 40, 5, "pensando", "cartera", "Bs.", "Tienes ${c.money(bs, "BS")} en bolívares (unos ${c.show(bs / r)}). Planifica en qué los vas a usar pronto: guardados mucho tiempo rinden menos.", "¿Pagar en $ o Bs.?") { it.open("caja") }
    },
    { c ->
        val r = rateOf(c.d); val m = marketRateOf(c.d)
        val g = if (r > 0 && m > 0) (m / r - 1) * 100 else 0.0
        if (!c.ve || g < 8) null
        else QRule("brecha", 32, 4, "pensando", "", "", "Hoy el USDT está ${g.roundToInt()} % por encima del BCV. Antes de pagar algo en bolívares, compara cuánto te cuesta de verdad en dólares.", "Calcular") { it.open("caja") }
    },
    { c ->
        val t = tasasDelMes(c.d)
        if (!c.ve || t.n < 3 || t.tagged == 0 || abs(t.diff) < 3) null
        else if (t.diff > 0) QRule("tasa_bien", 30, 10, "celebra", "", "", "Este mes tus compras en bolívares te salieron ${c.show(t.diff)} más baratas de lo que marca la tasa BCV, porque conseguiste los bolívares a mejor tasa. ¡Pilas así!")
        else QRule("tasa_mal", 34, 10, "alerta", "", "", "Este mes tus compras en bolívares te costaron ${c.show(-t.diff)} más de lo que marca la tasa BCV. Revisa dónde y a qué tasa consigues tus bolívares.")
    },
    { c ->
        val t = tasasDelMes(c.d)
        if (!c.ve || marketRateOf(c.d) <= 0 || t.n < 5 || t.tagged > 0) null
        else QRule("tasa_sin_marcar", 22, 14, "pensando", "", "", "Cuando anotes un gasto en bolívares, dime a qué tasa los conseguiste (BCV, USDT o tu cambio). Así te digo cuánto te costó de verdad en dólares.")
    },

    // ───────── Deudas, tarjetas y Cashea
    { c ->
        val t = c.d.arr("debts").filter { it.str("type") == "tdc" && it.num("creditLimit") > 0 }.maxByOrNull { c.left(it) / it.num("creditLimit") }
        val p = t?.let { c.left(it) / it.num("creditLimit") } ?: 0.0
        if (t == null || p < .7) null
        else {
            val n = t.str("issuer") ?: t.str("entity") ?: "tu tarjeta"
            QRule("tdc_uso_${t.id}", 58, 5, "alerta", "cartera", n, "«$n» está al ${(p * 100).roundToInt()} % del límite. Úsala menos hasta bajarla: mientras más alta, más intereses pagas.", "Ver deudas") { it.go("deudas") }
        }
    },
    { c ->
        val t = c.d.arr("debts").filter { x -> x.str("type") == "tdc" && c.left(x) > .005 && dateOf(x.str("payBefore"))?.let { !it.isBefore(c.today) && !it.isAfter(c.today.plusDays(5)) } == true }
            .minByOrNull { it.str("payBefore") ?: "" }
        if (t == null) null
        else {
            val n = t.str("issuer") ?: t.str("entity") ?: "tu tarjeta"
            val dd = dateOf(t.str("payBefore"))!!.dayOfMonth
            val min = if (t.num("minPayment") > 0) ": el mínimo es ${c.money(t.num("minPayment"), t.str("currency"))}" else ""
            QRule("tdc_fecha_${t.id}${t.str("payBefore")}", 66, 1, "alerta", "calendario", dd.toString(),
                c.V("Paga «$n» antes del $dd$min. Si pagas más del mínimo, mejor: así no te comen los intereses.", "Paga «$n» antes del día $dd$min."))
        }
    },
    { c ->
        val l = c.d.arr("debts").filter { c.left(it) > .005 && has("${it.str("entity")} ${it.str("description")} ${it.str("issuer")}", listOf("cashea")) }
        if (!c.ve || l.size < 2) null
        else QRule("cashea", 52, 7, "pensando", "cartera", "Cashea", "Tienes ${l.size} compras abiertas en Cashea por ${c.show(l.sumOf { usdOf(c.d, c.left(it), it.str("currency")) })}. Son cómodas, pero las cuotas se van sumando: termina algunas antes de abrir otra.", "Ver deudas") { it.go("deudas") }
    },
    { c ->
        val l = c.d.arr("debts").filter { c.left(it) > .005 }
        val s = l.minByOrNull { usdOf(c.d, c.left(it), it.str("currency")) }
        if (l.size < 4 || s == null) null
        else QRule("muchas_deudas", 44, 7, "pensando", "cartera", s.str("entity") ?: "", "Tienes ${l.size} deudas abiertas. Una forma de agarrar impulso: termina primero la más pequeña («${s.str("entity") ?: "la menor"}», ${c.money(c.left(s), s.str("currency"))}) y sigue con la próxima.", "Ver mi plan") { it.go("deudas") }
    },
    { c ->
        if (debtTotals(c.d).remaining <= .005 || debtPlan(c.d).configured) null
        else QRule("sin_plan", 46, 5, "pensando", "", "", c.V("Tienes deudas pero todavía no armas tu plan de pagos. En un minuto te digo en cuántos meses sales de eso, pana.",
            "Tienes deudas sin plan de pagos. En un minuto te digo en cuántos meses terminas."), "Armar mi plan") { it.go("deudas") }
    },
    { c ->
        val t = debtTotals(c.d)
        if (t.total < 20 || t.paid / t.total < .5 || t.paid >= t.total - .005) null
        else QRule("mitad_deudas", 38, 14, "celebra", "confeti", "", c.V("¡Qué nota! Ya pagaste el ${(t.paid / t.total * 100).roundToInt()} % de tus deudas. Falta menos de lo que ya hiciste.",
            "Ya pagaste el ${(t.paid / t.total * 100).roundToInt()} % de tus deudas. Falta menos de lo que ya hiciste."))
    },
    { c ->
        val t = c.d.arr("debts").firstOrNull { it.str("type") == "tdc" && c.left(it) > .005 && it.bool("interestKnown") != true }
        if (t == null) null
        else QRule("tdc_sin_interes", 24, 21, "pensando", "", "", "No sé cuánto interés cobra «${t.str("issuer") ?: t.str("entity") ?: "tu tarjeta"}». Si lo anotas (sale en el estado de cuenta), tu plan de pagos sale más exacto.")
    },

    // ───────── Me deben
    { c ->
        val r = c.d.arr("receivables").filter { x -> c.left(x) > .005 && dateOf(x.str("dueDate"))?.isBefore(c.today) == true }.maxByOrNull { usdOf(c.d, c.left(it), it.str("currency")) }
        if (r == null) null
        else {
            val due = dateOf(r.str("dueDate"))!!
            val days = ChronoUnit.DAYS.between(due, c.today).toInt()
            val who = r.str("person")?.takeIf { it.isNotBlank() } ?: "Alguien"
            QRule("me_deben_${r.id}", 54, 3, "alerta", "calendario", due.dayOfMonth.toString(), c.V("$who te debe ${c.money(c.left(r), r.str("currency"))} desde hace ${plural(days, "día", "días")}. Échale un recordatorio por WhatsApp, con cariño pero sin pena.",
                "$who te debe ${c.money(c.left(r), r.str("currency"))} desde hace ${plural(days, "día", "días")}. Mándale un recordatorio."))
        }
    },
    { c ->
        val l = c.d.arr("receivables").filter { c.left(it) > .005 }
        val s = l.sumOf { usdOf(c.d, c.left(it), it.str("currency")) }
        if (l.isEmpty() || c.inc <= 0 || s < c.inc * .3) null
        else QRule("me_deben_total", 36, 7, "pensando", "", "", c.V("Entre todos te deben ${c.show(s)}. Cobrar eso es casi como una quincena extra: no lo dejes enfriar.", "En total te deben ${c.show(s)}. Cobrar eso es casi una quincena extra."))
    },

    // ───────── Presupuesto, fijos y suscripciones
    { c ->
        val b = c.d.obj("budgets")
        if (CATS.keys.any { b.num(it) > 0 } || c.d.arr("expenses").size < 10) null
        else QRule("sin_presupuesto", 42, 7, "pensando", "", "", "Ya llevas varios gastos anotados. Ponle un tope a cada cosa (comida, transporte, salidas) y te aviso antes de que te pases.", "Poner presupuesto") { it.open("presupuesto") }
    },
    { c ->
        val b = c.d.obj("budgets"); val ks = CATS.keys.filter { b.num(it) > 0 }
        if (ks.size < 2 || c.daysLeft > 4 || !ks.all { (c.cat[it] ?: 0.0) < b.num(it) * .5 * .6 }) null
        else QRule("bajo_presupuesto", 34, 14, "celebra", "confeti", "", c.V("¡Qué nota! Vas por debajo del presupuesto en todo esta quincena. Lo que te sobre, al fondito.", "Vas por debajo del presupuesto en todo esta quincena. ¡Muy bien!"))
    },
    { c ->
        val inc = monthlyIncome(c.d); val fx = c.d.arr("recurring").filter { it.bool("active") != false }.sumOf { c.u(it) }
        if (inc <= 0 || fx / inc < .45) null
        else QRule("fijos_pesados", 40, 14, "pensando", "", "", "Tus gastos fijos se comen el ${(fx / inc * 100).roundToInt()} % de tu sueldo. Revisa si hay alguno que puedas bajar: el plan del teléfono, el internet o alguna suscripción.", "Ver fijos") { it.go("pagos") }
    },
    { c ->
        val words = listOf("netflix", "spotify", "disney", "hbo", " max", "prime", "youtube", "apple", "icloud", "deezer", "crunchyroll", "paramount", "chatgpt", "canva")
        val l = c.d.arr("recurring").filter { it.bool("active") != false && has(" " + (it.str("description") ?: ""), words) }
        if (l.size < 2) null
        else QRule("suscripciones", 36, 21, "pensando", "lupa", "", "Pagas ${l.size} suscripciones (${l.take(3).joinToString(", ") { it.str("description") ?: "" }}) por ${c.show(sumU(c, l))} al mes. ¿Las usas todas? Pausar una ya es un ahorro.")
    },

    // ───────── Ingresos
    { c ->
        val s = extraIn(c.d, c.ym.atDay(1), c.ym.atEndOfMonth())
        if (s < 10) null
        else QRule("extra_mes", 32, 10, "feliz", "", "", c.V("Este mes te entraron ${c.show(s)} extra. Aparta una parte antes de que se te diluya en el día a día.", "Este mes recibiste ${c.show(s)} extra. Aparta una parte antes de que se diluya."))
    },
    { c ->
        if (c.d.arr("incomeSources").isNotEmpty() || c.d.arr("expenses").size < 5) null
        else QRule("sin_ingreso", 48, 5, "pensando", "", "", "Ya anotas tus gastos, pero no sé cuánto cobras. Agrega tu ingreso y te digo cuánto te queda de verdad en la quincena.", "Agregar ingreso") { it.open("ingreso") }
    },

    // ───────── Formas de pago y cuentas
    { c ->
        val ef = c.month.count { it.str("method") == "efectivo" }
        if (c.month.size < 10 || ef.toDouble() / c.month.size < .7) null
        else QRule("efectivo", 24, 21, "pensando", "", "", "Casi todo lo pagas en efectivo. Anota apenas pagues: el efectivo es el que más se escapa sin darnos cuenta.")
    },
    { c ->
        val n = c.month.count { it.str("method") == "pago_movil" }
        if (!c.ve || n < 15) null
        else QRule("pago_movil", 20, 21, "pensando", "", "", "Llevas $n pagos móviles este mes. Antes de confirmar, revisa teléfono, cédula y banco, y guarda la referencia por si hay que reclamar.")
    },
    { c ->
        val used = c.month.any { it.str("method") == "zelle" || it.str("method") == "binance" }
        if (!used || accounts(c.d).any { it.str("kind") == "digital" || it.str("kind") == "cripto" }) null
        else QRule("zelle_cuenta", 26, 21, "pensando", "", "", "Pagas con Zelle o Binance, pero no tienes esas cuentas registradas. Agrégalas y sabrás cuánto tienes en cada lado.", "Agregar cuenta") { it.open("cuenta") }
    },

    // ───────── Hábitos
    { c ->
        val l = c.d.arr("expenses")
        val last = l.mapNotNull { localDay(it.str("date")) }.maxOrNull()
        val days = last?.let { ChronoUnit.DAYS.between(it, c.today).toInt() } ?: 0
        if (l.size < 5 || days < 3) null
        else QRule("sin_anotar", 47, 2, "pensando", "", "", c.V("Hace ${plural(days, "día", "días")} que no anotas nada. ¿De verdad no gastaste o se te pasó? Ponte al día, que así es que cuadran las cuentas.",
            "Hace ${plural(days, "día", "días")} que no anotas nada. ¿Se te pasó? Ponte al día."), "Anotar gasto") { it.open("gasto") }
    },

    // ───────── Temporadas del año (Venezuela)
    { c ->
        if (!c.ve || !(c.month0 == 10 || c.month0 == 11)) null
        else QRule("diciembre", 40, 10, "feliz", "calendario", "Dic", "Se vienen las hallacas, los estrenos y los regalos. Si te pagan aguinaldos o utilidades, separa una parte para enero, que es el mes más largo del año.")
    },
    { c ->
        if (!c.ve || c.month0 != 0 || c.day > 20) null
        else QRule("enero", 40, 10, "pensando", "", "", "Enero es largo y la primera quincena tarda en llegar. Cuida lo que quedó de diciembre y no te metas en cuotas nuevas este mes.")
    },
    { c ->
        val school = c.d.arr("schoolExpenses").isNotEmpty() || c.d.arr("expenses").any { it.str("category") == "educacion" }
        if (!c.ve || !(c.month0 == 7 || (c.month0 == 8 && c.day <= 20)) || !school) null
        else QRule("clases", 44, 10, "pensando", "calendario", "Sep", "Se acerca el inicio de clases: útiles, uniformes y la inscripción. Anótalos en Pagos como compromisos y ve apartando un poquito cada quincena.", "Anotar compromiso") { it.open("compromiso") }
    },
    { c ->
        if (!c.ve || c.month0 !in 1..3 || c.day > 10) null
        else QRule("feriados", 18, 30, "feliz", "", "", "Si piensas viajar en Carnaval o Semana Santa, empieza a apartar desde ya: pasajes, posada y comida suben en esas fechas.")
    },
    { c ->
        if (!c.ve || c.month0 != 4 || c.day > 10) null
        else QRule("madres", 22, 30, "feliz", "", "", "El segundo domingo de mayo es el Día de las Madres. Planifica el detalle con tiempo y no a última hora, que es cuando sale más caro.")
    },
)

/** Cuántas reglas hay (para pruebas y para la pantalla «Más»). */
val QUINCI_RULE_COUNT get() = QRULES.size

private fun seenMap(st: AppState): MutableMap<String, String> =
    (st.repo.pref("qrseen") ?: "").split(";").mapNotNull { p -> p.split("=").takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap().toMutableMap()

/** Observaciones que aplican hoy, sin las que están descansando, de la más importante a la menos. */
fun quinciRules(st: AppState): List<QRule> {
    val c = QCtx(st.repo.data, st.fmt)
    val seen = seenMap(st)
    val today = c.today
    return QRULES.mapNotNull { f -> runCatching { f(c) }.getOrNull() }.filter { r ->
        val last = seen[r.id]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        last == null || last == today || ChronoUnit.DAYS.between(last, today) >= r.cd
    }.sortedByDescending { it.pri }
}

fun quinciMarkSeen(st: AppState, id: String) {
    val m = seenMap(st)
    val today = LocalDate.now().toString()
    if (m[id] == today) return
    m[id] = today
    st.repo.setPref("qrseen", m.entries.toList().takeLast(120).joinToString(";") { "${it.key}=${it.value}" })
}
