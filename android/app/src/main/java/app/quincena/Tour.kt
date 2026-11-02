package app.quincena

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Tutorial de bienvenida con Quinci (igual que static/tour.js): la primera vez deja la quincena lista paso a paso
// (ingreso, pagos fijos, deudas, cómo ver los montos y un recorrido). Con negocio, suma el paso del negocio.
// Cada "Siguiente" guarda lo que se llenó; "Saltar" o terminar marca onboarded para no volver a mostrarlo.

private val TOUR_FIJOS = listOf("Alquiler" to "hogar", "Internet" to "fijo", "Luz y agua" to "fijo", "Teléfono" to "fijo", "Condominio" to "hogar", "Colegio" to "educacion")

private fun tourSteps(d: JsonObject) = listOf("hola") + (if (bizOn(d)) listOf("negocio") else emptyList()) +
    listOf("ingreso", "fijos", "deudas", "moneda", "recorrido", "listo")

/** Cuenta nueva (sin datos) que todavía no vio el tutorial. */
fun tourDue(d: JsonObject): Boolean = d.bool("onboarded") != true && d.arr("incomeSources").isEmpty() && d.arr("expenses").isEmpty() &&
    d.arr("debts").isEmpty() && d.arr("recurring").isEmpty() && d.arr("sales").isEmpty()

private fun nrmT(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()

@Composable
fun TourOverlay(st: AppState) {
    val d = st.repo.data
    val steps = tourSteps(d)
    val i = st.tour.coerceIn(0, steps.size - 1)
    val k = steps[i]
    val p = pal
    fun end(msg: String) { st.tour = -1; st.act(msg) { it["onboarded"] = JsonPrimitive(true) } }
    BackHandler { if (i > 0 && k != "listo") st.tour = i - 1 else end("Puedes ver el tutorial cuando quieras en Más.") }

    // Campos de cada paso (se guardan al tocar Siguiente)
    val b = d.obj("business")
    var type by remember { mutableStateOf(bizType(d)) }
    var prof by remember { mutableStateOf(b.str("profession").orEmpty()) }
    var rk by remember { mutableStateOf(bizKind(d)) }
    var incName by remember { mutableStateOf("Sueldo") }
    var incAmount by remember { mutableStateOf("") }
    var incCur by remember { mutableStateOf("USD") }
    var freq by remember { mutableStateOf("quincenal") }
    var wday by remember { mutableStateOf("5") }
    var incId by remember { mutableStateOf<String?>(null) }
    val fixAmt = remember { mutableStateListOf("", "", "", "", "", "") }
    val fixDay = remember { mutableStateListOf("", "", "", "", "", "") }
    var fixCur by remember { mutableStateOf("USD") }
    val debtEnt = remember { mutableStateListOf("", "", "") }
    val debtAmt = remember { mutableStateListOf("", "", "") }
    val debtMin = remember { mutableStateListOf("", "", "") }
    var debtCur by remember { mutableStateOf("USD") }
    val curOpts = listOf("USD" to "Dólares ($)", "BS" to "${Loc.cur.plural.replaceFirstChar { it.uppercase() }} (${Loc.cur.short})")

    fun saveStep() {
        val id = newId()
        when (k) {
            "negocio" -> st.act(null) { m -> m["business"] = JsonObject(m.data().obj("business") + mapOf("type" to JsonPrimitive(type), "profession" to JsonPrimitive(prof), "rateKind" to JsonPrimitive(rk))) }
            "ingreso" -> parseMoney(incAmount)?.takeIf { it > 0 }?.let { a ->
                val days: List<Int> = when (freq) { "semanal" -> listOf(wday.toIntOrNull() ?: 5); "quincenal" -> listOf(15, 30); else -> listOf(1) }
                val r = row("kind" to "salario", "name" to incName.trim().ifBlank { "Sueldo" }, "amount" to a, "currency" to incCur, "frequency" to freq,
                    "payDays" to JsonArray(days.map { JsonPrimitive(it) }))
                val old = incId
                // Si vuelve atrás y cambia el monto, se corrige el mismo ingreso en vez de crear otro.
                st.act(null) { m -> if (old != null && m.rows("incomeSources").any { it.id == old }) m.updateRow("incomeSources", old) { it.plusFields(*r.entries.map { e -> e.key to e.value }.toTypedArray()) }
                    else m.addFirst("incomeSources", r.plusFields("id" to id)) }
                if (old == null) incId = id
            }
            "fijos" -> st.act(null) { m ->
                val have = m.rows("recurring").map { nrmT(it.str("description").orEmpty()) }.toSet()
                val add = TOUR_FIJOS.mapIndexedNotNull { j, (name, cat) ->
                    val a = parseMoney(fixAmt[j])?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                    if (nrmT(name) in have) return@mapIndexedNotNull null
                    row("id" to "${id}_f$j", "description" to name, "amount" to a, "currency" to fixCur, "category" to cat,
                        "day" to (fixDay[j].toIntOrNull() ?: 1).coerceIn(1, 31), "active" to true, "lastGenerated" to "", "mode" to "auto")
                }
                if (add.isNotEmpty()) { m.setRows("recurring", m.rows("recurring") + add); maintain(m) }
            }
            "deudas" -> st.act(null) { m ->
                val have = m.rows("debts").map { nrmT(it.str("entity").orEmpty()) }.toSet()
                (0..2).forEach { j ->
                    val ent = debtEnt[j].trim()
                    val a = parseMoney(debtAmt[j])?.takeIf { it > 0 }
                    if (ent.isEmpty() || a == null || nrmT(ent) in have) return@forEach
                    val mn = parseMoney(debtMin[j])?.takeIf { it > 0 }
                    m.addFirst("debts", row("id" to "${id}_d$j", "type" to (if (ent.contains("cashea", true)) "cashea" else "otro"), "amount" to a, "currency" to debtCur,
                        "entity" to ent.take(120), "description" to "", "paid" to 0.0, "date" to nowIso(), "minPayment" to mn, "interestKnown" to false))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).clickable(enabled = false) {}, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.94f).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(p.bg)
                .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Paso ${i + 1} de ${steps.size}", Modifier.weight(1f), color = p.mut, fontSize = 13.sp)
                if (k != "listo") Link("Saltar tutorial", { end("Puedes ver el tutorial cuando quieras en Más.") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { j -> Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (j == i) p.g else if (j < i) p.gs else p.line)) }
            }
            @Composable fun Say(mood: String, prop: String, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuinciFace(mood, prop, "", 76.dp)
                Column(Modifier.weight(1f)) { Text("Quinci", color = p.g, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(text, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            when (k) {
                "hola" -> {
                    Say("feliz", "", "¡Hola${st.repo.username.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}! Soy Quinci y te voy a acompañar con tu dinero. En unos 2 minutos dejamos tu quincena lista: tu ingreso, tus pagos fijos y tus deudas.")
                    listOf("💵 Sabes cuánto te queda hasta el próximo cobro, en $ y en ${Loc.cur.short}", "📅 Te aviso antes de que venza un pago",
                        "📉 Armamos un plan para salir de deudas").plus(if (bizOn(d)) listOf("🏪 Y llevamos tu negocio aparte") else emptyList()).forEach { Text(it) }
                    Mut("Puedes saltar cualquier paso y llenarlo después.")
                }
                "negocio" -> {
                    Say("feliz", "", "Tu negocio «${b.str("name").orEmpty()}» ya está creado. Cuéntame a qué te dedicas: si das servicios (abogado, contador, médico, técnico…) verás cobros, clientes y por cobrar en vez de ventas y fiado.")
                    Select("¿A qué te dedicas?", BIZ_TYPES.entries.map { it.key to it.value }, type) { type = it }
                    Select("Si das servicios profesionales, tu profesión", BIZ_PROFS.entries.map { it.key to it.value }, prof) { prof = it }
                    Select("¿Con qué tasa trabajas?", BIZ_RATES.entries.map { it.key to it.value }, rk) { rk = it }
                    Mut("El dinero del negocio va aparte de tu quincena. Cuando te pagues a ti, usa «Me pago a mí» y entra como ingreso tuyo.")
                }
                "ingreso" -> {
                    Say("pensando", "", "¿Cuánto cobras y cada cuánto? Con esto calculo cuánto te queda en cada quincena.")
                    Field("¿Cómo se llama tu ingreso?", incName, { incName = it.take(80) })
                    MoneyField("Monto que cobras cada vez", incAmount) { incAmount = it }
                    Select("Moneda", curOpts, incCur) { incCur = it }
                    Select("¿Cada cuánto cobras?", listOf("quincenal" to "Cada quincena (15 y 30)", "semanal" to "Cada semana", "mensual" to "Una vez al mes"), freq) { freq = it }
                    if (freq == "semanal") Select("¿Qué día?", listOf("1" to "Lunes", "2" to "Martes", "3" to "Miércoles", "4" to "Jueves", "5" to "Viernes", "6" to "Sábado", "7" to "Domingo"), wday) { wday = it }
                }
                "fijos" -> {
                    Say("pensando", "calendario", "Ahora tus pagos fijos de cada mes. Escribe el monto y el día de los que tengas; deja vacíos los demás. Se anotan solos ese día.")
                    TOUR_FIJOS.forEachIndexed { j, (name, _) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.weight(2f)) { MoneyField(name, fixAmt[j]) { fixAmt[j] = it } }
                            Box(Modifier.weight(1f)) { Field("Día", fixDay[j], { fixDay[j] = it.filter(Char::isDigit).take(2) }, placeholder = "1", kind = KeyboardType.Number) }
                        }
                    }
                    Select("Moneda", curOpts, fixCur) { fixCur = it }
                    Mut("Matrícula, útiles u otros pagos que no se repiten los agregas en Pagos › Compromisos.")
                }
                "deudas" -> {
                    Say("preocupado", "cartera", "¿Le debes a alguien? Cashea, tarjeta, un préstamo, un familiar… Anótalo y te ayudo a armar un plan para salir.")
                    (0..2).forEach { j ->
                        Field(if (j == 0) "¿A quién le debes?" else "Otra deuda (opcional)", debtEnt[j], { debtEnt[j] = it }, placeholder = listOf("Cashea", "Tarjeta", "Préstamo")[j])
                        if (debtEnt[j].isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { MoneyField("Debes", debtAmt[j]) { debtAmt[j] = it } }
                            Box(Modifier.weight(1f)) { MoneyField("Cuota al mes", debtMin[j]) { debtMin[j] = it } }
                        }
                    }
                    Select("Moneda", curOpts, debtCur) { debtCur = it }
                    Mut("¿No debes nada? ¡Excelente! Toca Siguiente.")
                }
                "moneda" -> {
                    Say("feliz", "", "¿Cómo prefieres ver tus montos? Todo se convierte solo con la tasa del día. Lo cambias cuando quieras arriba, junto a Ocultar.")
                    val ve = Loc.cur.code == "VE"
                    val opts = listOf("USD" to (if (ve) "$ Dólar BCV" else "$ Dólar")) + (if (ve && marketRateOf(d) > 0) listOf("USDT" to "USDT") else emptyList()) +
                        (if (ve && d.num("euroRate") > 0) listOf("EUR" to "€ Euro BCV") else emptyList()) + listOf("BS" to Loc.cur.short)
                    val cur = d.str("currency") ?: "USD"
                    Chips { opts.forEach { (v, l) -> Chip(l, cur == v) { st.setCurrency(v) } } }
                    Mut("Cuando anotes un gasto en ${Loc.cur.short}, puedes decir a qué tasa conseguiste esos ${Loc.cur.plural} (BCV, USDT u otra) y ves cuánto te costó de verdad.")
                }
                "recorrido" -> {
                    Say("celebra", "", "Te enseño dónde está cada cosa:")
                    (listOf("🏠 Inicio" to "Cuánto te queda esta quincena, tus próximos pagos y mis avisos.") +
                        (if (bizOn(d)) listOf("🏪 Negocio" to (if (bizSvc(d)) "Cobros, clientes, por cobrar" else "Ventas, fiado") + ", gastos del negocio, caja y ganancia del mes.") else emptyList()) +
                        listOf("📋 Movimientos" to "Tus gastos por día, ingresos, cuentas y gastos fijos.", "💳 Deudas" to "Lo que debes, lo que te deben y tu plan para salir de deudas.",
                            "📅 Pagos" to "Calendario, pagos programados y compromisos de cada mes.", "⋯ Más" to "Tu presupuesto, país, avisos, sugerencias y tus datos.",
                            "➕ El botón +" to "Anota un gasto en 2 segundos. Si te equivocas de día, puedes cambiar la fecha.",
                            "📲 Desde el banco" to "Comparte el mensaje del banco o del pago móvil con Quincena VE y el gasto se llena solo.")).forEach { (t, x) ->
                        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 2.dp).padding(12.dp)) { B(t); Mut(x) }
                    }
                }
                else -> {
                    val cp = summarize(d, currentPeriod())
                    Say("celebra", "confeti", "¡Listo! Tu quincena quedó configurada." + (if (cp.income > 0) " Por ahora te quedan ${st.fmt.show(cp.free)} en este periodo." else "") +
                        " Anota tus gastos apenas los hagas y yo te aviso cómo vas.")
                    Btn("Anotar mi primer gasto", { end("¡Bienvenido a Quincena VE!"); st.open("gasto") })
                    if (bizOn(d)) Btn(bizV(d).add, { end("¡Bienvenido a Quincena VE!"); st.go("negocio"); st.open("bizventa") }, outline = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i > 0 && k != "listo") Btn("Atrás", { st.tour = i - 1 }, Modifier.weight(1f), outline = true)
                Btn(when (k) { "hola" -> "Empezar"; "listo" -> "Ir al inicio"; else -> "Siguiente" }, {
                    saveStep()
                    if (k == "listo") end("¡Bienvenido a Quincena VE!") else st.tour = i + 1
                }, Modifier.weight(2f))
            }
        }
    }
}
