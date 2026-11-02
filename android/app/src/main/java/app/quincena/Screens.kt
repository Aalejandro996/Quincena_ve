package app.quincena

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.min

/** Texto con partes en negrita: rich("Si pagas ", "$ 10" to true, " al mes"). */
@Composable
fun Rich(vararg parts: Any) = Text(buildAnnotatedString {
    for (x in parts) when (x) {
        is Pair<*, *> -> if (x.second == true) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(x.first.toString()) } else append(x.first.toString())
        else -> append(x.toString())
    }
})

private fun freqLabel(f: String?) = when (f) { "semanal" -> "Cada semana"; "quincenal" -> "Cada quincena"; "mensual" -> "Una vez al mes"; "ocasional" -> "Solo una vez"; else -> f ?: "" }
private fun meses(n: Int) = "$n ${if (n == 1) "mes" else "meses"}"

// =====================================================================
// INICIO
// =====================================================================

@Composable
fun Inicio(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val c = monthSummary(d)
    val per = st.per
    val cp = summarize(d, per)
    val plan = debtPlan(d)

    Head(st)
    UpdateCard(st)

    // Confirmar correo (compacto)
    val mail = st.mail
    val email = mail?.first
    if (email != null && mail?.second == false) QCard(bg = p.soft) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { B("Confirma tu correo"); Mut("Lo necesitas para recuperar tu contraseña.") }
            Btn("Reenviar", {
                st.scope.launch {
                    try { st.repo.resendVerification(); st.toast("Listo, te enviamos el enlace a $email. Revisa también el spam.") }
                    catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") }
                    catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
                }
            }, small = true, outline = true)
        }
    }

    // Empecemos en 3 pasos (solo al principio)
    val n = d.arr("expenses").size + d.arr("incomeSources").size + d.arr("debts").size
    val steps = listOf(
        Triple(d.arr("incomeSources").isNotEmpty(), "Agrega tu ingreso", "ingreso"),
        Triple(d.obj("budgets").keys.any { d.obj("budgets").num(it) > 0 }, "Define tu presupuesto", "presupuesto"),
        Triple(d.arr("expenses").isNotEmpty(), "Registra tu primer gasto", "gasto"),
    )
    if (n < 3 && steps.any { !it.first }) QCard {
        B("Empecemos en 3 pasos")
        steps.forEachIndexed { i, (done, label, kind) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (done) "✓" else "${i + 1}", Modifier.width(24.dp), fontWeight = FontWeight.Bold, color = p.g)
                Text(label, Modifier.weight(1f), color = if (done) p.mut else p.ink, textDecoration = if (done) TextDecoration.LineThrough else null)
                if (!done) Btn("Hacer", { st.open(kind) }, small = true)
            }
        }
    }

    QuinciCard(st)
    DetectadosCard(st)
    QuickRow(st)
    SemanaCard(st)

    // Tarjeta principal, con el selector de periodo adentro
    val heroBg = if (cp.free < 0) HeroNeg else p.g
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(heroBg).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProvideTextStyle(LocalTextStyle.current.copy(color = Color.White)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroButton("‹") { st.per = per.step(-1) }
                Text(per.label(), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
                HeroButton("›") { st.per = per.step(1) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (cp.free < 0) "Te faltan" else "Te quedan")
                Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .18f)).padding(3.dp)) {
                    listOf(1 to "1.ª", 2 to "2.ª", 0 to "Mes").forEach { (h, l) ->
                        val on = per.half == h
                        Box(
                            Modifier.height(30.dp).widthIn(min = 42.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                                .clickable { st.per = per.copy(half = h) }.padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(l, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) heroBg else Color.White) }
                    }
                }
            }
            val amount = f.show(abs(cp.free))
            Big(amount, size = when { amount.length <= 12 -> 42.sp; amount.length <= 16 -> 34.sp; else -> 28.sp })
            Bar(if (cp.income > 0) cp.spent / cp.income else 0.0, color = Color.White, track = Color.White.copy(alpha = .28f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Gastado ${f.show(cp.spent)}", fontSize = 14.sp)
                Text("Ingresos ${f.show(cp.income)}", fontSize = 14.sp)
            }
            if (cp.due > 0) Text("Ya descontado: ${f.show(cp.due)} en pagos de este periodo", fontSize = 13.sp)
            val mk = marketRateOf(d)
            val eu = d.num("euroRate")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(loc("BCV Bs. ${f.plain(rateOf(d))}${if (mk > 0) " · USDT Bs. ${f.plain(mk)}" else ""}${if (eu > 0) " · € Bs. ${f.plain(eu)}" else ""}" +
                    (st.rateInfo?.let { " · $it" } ?: "")), Modifier.weight(1f), fontSize = 12.5.sp, color = Color.White.copy(alpha = .8f), maxLines = 2)
                // Botón discreto para pedir la tasa al momento.
                Text(if (st.rating) "Actualizando…" else "Actualizar", Modifier.padding(start = 8.dp).clickable(enabled = !st.rating) {
                    st.scope.launch {
                        st.rating = true
                        val info = st.repo.updateRate(force = true)
                        st.rateInfo = info; st.rating = false
                        st.toast(if (info == "sin actualizar") "No pudimos actualizar la tasa. Inténtalo en un minuto." else "Tasa ${Loc.cur.official}: ${Loc.cur.sym} ${f.local(rateOf(st.repo.data))} ($info)")
                    }
                }, fontSize = 12.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
            }
        }
    }

    // Resumen en mosaicos: cada uno lleva al detalle
    val tiles = mutableListOf<TileData>()
    val accs = accounts(d)
    if (accs.isNotEmpty()) tiles += TileData("Tu dinero", "≈ " + f.show(totalUsd(d)), "${accs.size} ${if (accs.size == 1) "cuenta" else "cuentas"}") {
        st.movSub = "cuentas"; st.go("movimientos")
    }
    val teDeben = d.arr("receivables").filter { it.num("paid") < it.num("amount") }.sumOf { usdOf(d, it.num("amount") - it.num("paid"), it.str("currency")) }
    if (teDeben > .005) tiles += TileData("Te deben", f.show(teDeben), "toca para ver quién", p.g) { st.deudasSub = "medeben"; st.go("deudas") }
    d.arr("plannedDebtPayments").filter { it.str("status") == "planned" && dateOf(it.str("scheduledDate")) != null }
        .minByOrNull { it.str("scheduledDate")!! }?.let { next ->
            val days = daysUntil(dateOf(next.str("scheduledDate"))!!)
            val cuando = when { days < 0 -> "venció"; days == 0 -> "hoy"; days == 1 -> "mañana"; else -> "en $days días" }
            tiles += TileData("Próximo pago", f.orig(next.num("amount"), next.str("currency")), "${next.str("entity") ?: "Pago"} · $cuando",
                if (days <= 1) p.bad else Color.Unspecified) { st.pagosSub = "pagos"; st.go("pagos") }
        }
    val qs = comprometido(d)
    val qInc = qs.sumOf { it.inc }
    val qCom = qs.sumOf { it.com }
    if (qInc > 0 && qCom > 0) {
        val pct = Math.round(qCom / qInc * 100).toInt()
        tiles += TileData("Sueldo comprometido", "$pct %", "próximas 4 quincenas", if (pct >= 70) p.bad else if (pct >= 40) p.warn else p.g) { st.pagosSub = "pagos"; st.go("pagos") }
    }
    if (c.debt > .005) {
        val months = if (plan.configured && plan.months != null) plan.months else c.months
        tiles += TileData("Deudas", f.show(c.debt), if (months != null) "${meses(months)} para salir" else "ver tu plan") { st.open("info:camino") }
    }
    val mk = marketRateOf(d)
    if (mk > 0 && rateOf(d) > 0) {
        val t = tasasDelMes(d)
        tiles += if (t.tagged > 0 && abs(t.diff) > .005)
            TileData("Tus tasas", (if (t.diff > 0) "+" else "−") + f.orig(abs(t.diff), "USD"), if (t.diff > 0) "ahorraste este mes" else "pagaste de más",
                if (t.diff > 0) p.g else p.bad) { st.open("info:tasas") }
        else {
            val gap = Math.round((mk / rateOf(d) - 1) * 100)
            TileData("Brecha BCV / USDT", "${if (gap > 0) "+" else ""}$gap %", "toca para ver tu costo real") { st.open("info:tasas") }
        }
    }
    val sv = savingsBs(d)
    if (sv > 0) {
        val ref = rateRef(d)
        tiles += if (ref != null && rateOf(d) > 0) {
            val loss = sv / ref.second - sv / rateOf(d)
            TileData("Tus bolívares", (if (loss > 0) "−" else "") + f.orig(abs(loss), "USD"), "de valor desde ${shortDate(ref.first)}",
                if (loss > 0) p.bad else Color.Unspecified) { st.open("info:poder") }
        } else TileData("Tus bolívares", "Bs. " + (if (st.hide) "••••" else f.plain(sv)), "midiendo su valor…") { st.open("info:poder") }
    }
    tiles += TileData("Calculadora", "$ o Bs.", "¿qué te conviene?") { st.open("caja") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    TuMesCard(st)
}

data class TileData(val k: String, val v: String, val s: String, val color: Color = Color.Unspecified, val onClick: () -> Unit)

@Composable
fun Tile(t: TileData, modifier: Modifier = Modifier) {
    val p = pal
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.heightIn(min = 82.dp).glass(shape, 6.dp).clickable(onClick = t.onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(loc(t.k), fontSize = 12.5.sp, color = p.mut, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(loc(t.v), fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = if (t.color == Color.Unspecified) p.ink else t.color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(loc(t.s), fontSize = 12.5.sp, color = p.mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HeroButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 22.sp, color = Color.White)
    }
}

/** Filas de presupuesto: las 3 más usadas, o todas. */
@Composable
fun PresRows(st: AppState, all: Boolean) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val cat = periodCats(d, st.per)
    val fr = if (st.per.half == 0) 1.0 else 0.5 // el presupuesto es mensual: por quincena se usa la mitad
    val b = d.obj("budgets")
    var ks = CATS.keys.filter { b.num(it) > 0 }.map { k -> Triple(k, cat[k] ?: 0.0, min(100.0, (cat[k] ?: 0.0) / (b.num(k) * fr) * 100)) }
    if (!all) ks = ks.sortedByDescending { it.third }.take(3)
    ks.forEach { (k, sp, pct) ->
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(CATS[k]!!, fontSize = 14.sp)
                Mut("${f.show(sp)} de ${f.show(b.num(k) * fr)}")
            }
            Bar(pct / 100, color = if (pct >= 95) p.bad else if (pct >= 80) p.warn else p.g, height = 6.dp)
        }
    }
}

/** "Tu mes": en qué se va el dinero y el presupuesto, en una sola tarjeta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuMesCard(st: AppState) {
    val d = st.repo.data
    val cc = periodCats(d, st.per)
    val tot = cc.values.sum()
    val nb = CATS.keys.count { d.obj("budgets").num(it) > 0 }
    if (tot <= 0 && nb == 0) return
    val ks = CATS.keys.filter { (cc[it] ?: 0.0) > 0 }.sortedByDescending { cc[it] }
    QCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            B(if (st.per.half == 0) "Tu mes" else "Tu quincena")
            if (nb > 3) Link("Ver todo", { st.open("info:pres") })
        }
        if (tot > 0) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                ks.forEach { k -> Box(Modifier.weight((cc[k]!! / tot).toFloat()).fillMaxHeight().background(Color(CAT_COLORS[k]!!))) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ks.take(4).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(CAT_COLORS[k]!!)))
                        Text(CATS[k]!!, fontSize = 13.sp)
                        Text("${Math.round(cc[k]!! / tot * 100)} %", fontSize = 13.sp, color = pal.mut)
                    }
                }
                if (ks.size > 4) Text("+${ks.size - 4} más", fontSize = 13.sp, color = pal.mut)
            }
        }
        if (nb > 0) PresRows(st, false) else Mut("Define tu presupuesto en Más › Mi presupuesto para ver cuánto te queda por categoría.")
    }
}

/** "Tu camino para salir de deudas" (se abre desde el mosaico Deudas). */
@Composable
fun CaminoCard(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val c = monthSummary(d)
    val plan = debtPlan(d)
    val dt = debtTotals(d)
    if (!(dt.total > 0 && c.debt > .005)) { Mut("No tienes deudas pendientes."); return }
    QCard(bg = p.gs) {
        B("Tu camino para salir de deudas")
        val months = if (plan.configured && plan.months != null) plan.months else c.months
        val pay = if (plan.configured && plan.rec > 0) plan.rec else c.pay
        if (months != null) Column {
            Big(meses(months), size = 36.sp)
            Mut("Aprox. ${monthName(YearMonth.now().plusMonths(months.toLong()), true)}${if (pay > 0) " · pagando ${f.show(pay)} al mes" else ""}")
        } else Text(if (c.left < 0) "Este mes tus gastos superan tus ingresos. Revisemos juntos dónde ajustar." else "Agrega tus ingresos y el pago mínimo de cada deuda para calcular cuándo terminas.")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bar(dt.paid / dt.total)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mut("Pagado ${f.show(dt.paid)}")
                Mut("Falta ${f.show(c.debt)}")
            }
        }
        Btn("Ver plan", { st.go("deudas") }, small = true, outline = true, full = true)
    }
}

/** Ventana de detalle que abren los mosaicos. */
@Composable
fun InfoSheet(st: AppState, k: String) {
    when (k) {
        "tasas" -> TasasCard(st)
        "poder" -> PoderCard(st)
        "camino" -> CaminoCard(st)
        "pres" -> QCard {
            B("Tu presupuesto")
            PresRows(st, true)
            Btn("Editar presupuesto", { st.open("presupuesto") }, small = true, outline = true, full = true)
        }
    }
    Btn("Cerrar", { st.close() }, outline = true)
}

/** "Tus tasas este mes": brecha BCV/USDT y cuánto ahorraste o perdiste por la tasa. */
@Composable
fun TasasCard(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    val m = marketRateOf(d)
    val b = rateOf(d)
    if (m <= 0 || b <= 0) return
    val t = tasasDelMes(d)
    val gap = Math.round((m / b - 1) * 100)
    QCard {
        B("Tus tasas este mes")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("BCV Bs. ${f.plain(b)}", p.gs, p.g)
            Pill("USDT Bs. ${f.plain(m)}", p.soft, p.warn)
            Mut("Brecha ${if (gap > 0) "+" else ""}$gap %")
        }
        when {
            t.n == 0 -> Mut("Cuando registres un gasto en bolívares, marca a qué tasa los conseguiste y aquí verás cuánto te costó de verdad.")
            t.tagged == 0 -> Mut("Este mes registraste ${t.n} ${if (t.n == 1) "gasto" else "gastos"} en bolívares, todos a tasa BCV. Si cambiaste dólares por USDT u otra tasa, márcalo en el gasto para ver tu costo real.")
            else -> {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(loc("Al BCV equivalen a")); Amt(f.orig(t.atBcv, "USD")) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Con las tasas que usaste"); Amt(f.orig(t.real, "USD")) }
                }
                when {
                    t.diff > .005 -> Pill("Te ahorraste ${f.orig(t.diff, "USD")} por la tasa", p.gs, p.g)
                    t.diff < -.005 -> Pill("Te costó ${f.orig(-t.diff, "USD")} de más por la tasa", p.bad.copy(alpha = .15f), p.bad)
                    else -> Pill("Sin diferencia por la tasa", p.soft, p.warn)
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(loc(text), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// =====================================================================
// MOVIMIENTOS
// =====================================================================

@Composable
fun Movimientos(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    Head(st)
    Chips {
        Chip("Gastos", st.movSub == "gastos") { st.movSub = "gastos" }
        Chip("Ingresos", st.movSub == "ingresos") { st.movSub = "ingresos" }
        Chip("Cuentas", st.movSub == "cuentas") { st.movSub = "cuentas" }
        Chip("Fijos", st.movSub == "fijos") { st.movSub = "fijos" }
    }
    when (st.movSub) {
        "fijos" -> {
            QCard {
                Items(d.arr("recurring"), "Aquí van los gastos que se repiten cada mes, como alquiler o internet. Se registran solos el día que indiques o, si prefieres, los marcas al pagarlos.") { r ->
                    Column(Modifier.weight(1f)) {
                        B(r.str("description") ?: "Gasto fijo")
                        Mut("${CATS[r.str("category")] ?: "Otros"} · el día ${r.num("day").toInt()} de cada mes · ${if (r.str("mode") == "manual") "lo marcas al pagar" else "se anota solo"}${if (r.bool("active") == false) " · en pausa" else ""}")
                    }
                    Amt(f.orig(r.num("amount"), r.str("currency")))
                    Link("Editar", { st.open("editarr", r.id) })
                }
            }
            Mut("También los ves en Pagos › Compromisos, junto a los pagos únicos.")
            Btn("Agregar gasto fijo", { st.open("recurrente") })
        }
        "ingresos" -> IngresosView(st)
        "cuentas" -> CuentasView(st)
        else -> GastosView(st)
    }
}

private fun monthShort(ym: String): String = runCatching {
    val y = YearMonth.parse(ym)
    y.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("es")).trimEnd('.') + " " + y.year
}.getOrDefault(ym)

/** Lista desplegable compacta, sin título. */
@Composable
fun SelectBox(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val p = pal
    Box {
        TextButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = p.card),
            border = androidx.compose.foundation.BorderStroke(1.dp, p.line)) {
            Text(options.firstOrNull { it.first == value }?.second ?: "Todos", Modifier.weight(1f), color = p.ink, maxLines = 1)
            Text("▾", color = p.mut)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); expanded = false }) }
        }
    }
}

// =====================================================================
// DEUDAS
// =====================================================================

@Composable
fun Deudas(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    val plan = debtPlan(d)
    Head(st)
    Chips {
        Chip("Debo", st.deudasSub != "medeben") { st.deudasSub = "debo" }
        Chip("Me deben", st.deudasSub == "medeben") { st.deudasSub = "medeben" }
    }
    if (st.deudasSub == "medeben") { MeDebenView(st); return }
    PlanCard(st, plan)
    QCard {
        Items(d.arr("debts"), "No tienes deudas registradas.") { x ->
            val amount = x.num("amount")
            val paid = x.num("paid")
            Column(Modifier.weight(1f)) {
                B(x.str("entity") ?: "Deuda")
                Mut("${x.str("description")?.let { "$it " } ?: ""}Debes ${f.orig(amount - paid, x.str("currency"))} de ${f.orig(amount, x.str("currency"))}" +
                    (if (amount - paid > .005) payoffText(d, x, plan) else ""))
                Spacer(Modifier.height(6.dp))
                Bar(if (amount > 0) paid / amount else 0.0)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Link("Editar", { st.open("editard", x.id) })
                Btn("Abonar", { st.open("abono", x.id) }, small = true)
            }
        }
    }
    Btn("Agregar deuda", { st.open("deuda") })
}

private fun mesDe(n: Int): String = YearMonth.now().plusMonths(n.toLong()).let { monthName(it, true) }

/** Próximo día de cobro (15 o fin de mes, o el día del sueldo mensual): ahí se programan los pagos del plan. */
fun nextPayday(d: JsonObject): LocalDate {
    val t = LocalDate.now()
    val src = d.arr("incomeSources").firstOrNull { it.str("frequency") == "mensual" || it.str("frequency") == "quincenal" }
    val ym = YearMonth.from(t)
    val days = if (src?.str("frequency") == "mensual") listOf(((src["payDays"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonPrimitive)?.content?.toIntOrNull() ?: ym.lengthOfMonth())
        else listOf(15, ym.lengthOfMonth())
    val next = days.map { min(it, ym.lengthOfMonth()) }.sorted().firstOrNull { it >= t.dayOfMonth }
    return if (next != null) ym.atDay(next) else ym.plusMonths(1).let { it.atDay(min(days[0], it.lengthOfMonth())) }
}

/** Plan de pagos: cuánto al mes y por quincena, orden con lo que toca a cada deuda, comparación y programar el mes. */
@Composable
fun PlanCard(st: AppState, plan: Plan) {
    val d = st.repo.data
    val f = st.fmt
    val p = pal
    QCard {
        B("Tu plan para salir de deudas")
        when {
            !plan.configured -> {
                Mut("Cuéntanos cuánto necesitas para vivir y te decimos cuánto puedes pagar cada quincena sin ahogarte, en qué orden y cuándo terminas.")
                Btn("Crear mi plan", { st.open("plan") }); return@QCard
            }
            plan.over -> { Text("Tus cuotas mínimas (${f.show(plan.minT)} al mes) superan lo que puedes pagar (${f.show(plan.cap)}). Revisa tus gastos, sube tus ingresos o negocia plazos más largos.") }
            plan.months == null -> Rich("Pagas ", f.show(plan.rec) to true, " al mes. Agrega el pago mínimo de cada deuda (Editar) para calcular el orden y cuándo terminas.")
            else -> {
                Big(meses(plan.months), 34.sp)
                Mut("Terminas en ${mesDe(plan.months)} · intereses estimados ${f.show(plan.interest)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Al mes" to plan.rec, "Por quincena" to plan.rec / 2).forEach { (k, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(p.gs).padding(12.dp)) { Mut(k); B(f.show(v)) }
                    }
                }
                val names = mapOf("avalanche" to "primero la de más interés", "snowball" to "primero la más pequeña")
                B("Orden de pago · ${names[plan.strategy]}")
                val open = d.arr("debts").filter { it.num("amount") - it.num("paid") > .005 }.sortedBy { plan.payoff[it.id] ?: 999 }
                open.forEachIndexed { i, x ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(p.g), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Column(Modifier.weight(1f)) {
                            B(x.str("entity") ?: "Deuda")
                            Mut(plan.payoff[x.id]?.let { "La terminas en ${mesDe(it)}" } ?: "Más de 50 meses")
                        }
                        Column(horizontalAlignment = Alignment.End) { Amt(f.show(plan.first[x.id] ?: 0.0)); Text("este mes", fontSize = 12.sp, color = p.mut) }
                    }
                }
                val alt = debtPlan(d, if (plan.strategy == "snowball") "avalanche" else "snowball")
                alt.months?.let { am ->
                    val better = alt.interest < plan.interest - .5 || (abs(alt.interest - plan.interest) <= .5 && am < plan.months)
                    Mut("Si pagas ${names[alt.strategy]}: ${meses(am)} e intereses de ${f.show(alt.interest)}." + if (better) " Te conviene más." else " Tu plan actual es el mejor.")
                    if (better) Link("Cambiar a ${names[alt.strategy]}", { st.act("Plan actualizado") { m -> m["debtPlan"] = JsonObject(m.data().obj("debtPlan") + ("strategy" to JsonPrimitive(alt.strategy))) } })
                }
                val ym = YearMonth.now().toString()
                val planned = d.arr("plannedDebtPayments").any { it.str("status") == "planned" && it.bool("auto") == true && (it.str("scheduledDate") ?: "").startsWith(ym) }
                if (planned) Mut("Ya programaste los pagos de este mes. Míralos en Pagos.")
                else Btn("Programar los pagos de este mes", {
                    val day = nextPayday(d)
                    val list = open.filter { (plan.first[it.id] ?: 0.0) > .005 }
                    val rate = rateOf(d)
                    st.act("${list.size} ${if (list.size == 1) "pago programado" else "pagos programados"} para el ${shortDate(day)}") { m ->
                        list.forEach { x ->
                            val a = plan.first[x.id] ?: 0.0
                            val amt = Math.round((if (x.str("currency") == "BS") a * rate else a) * 100) / 100.0
                            m.setRows("plannedDebtPayments", m.rows("plannedDebtPayments") + row("id" to "pl${System.currentTimeMillis()}_${x.id}", "debtId" to x.id,
                                "entity" to (x.str("entity") ?: ""), "description" to "Pago del plan", "amount" to amt, "currency" to (x.str("currency") ?: "USD"),
                                "scheduleType" to "date", "scheduledDate" to day.toString(), "status" to "planned", "createdAt" to nowIso(), "paidAt" to "",
                                "paymentChoice" to "custom", "auto" to true))
                        }
                    }
                })
            }
        }
        Btn(if (plan.configured) "Ajustar plan" else "Crear mi plan", { st.open("plan") }, outline = true)
    }
}

// =====================================================================
// PAGOS
// =====================================================================

@Composable
fun Pagos(st: AppState) {
    val d = st.repo.data
    val f = st.fmt
    Head(st)
    Calendario(st)
    Chips {
        Chip("Próximos pagos", st.pagosSub == "pagos") { st.pagosSub = "pagos" }
        Chip("Compromisos", st.pagosSub == "compromisos") { st.pagosSub = "compromisos" }
    }
    if (st.pagosSub == "compromisos") {
        // Cada mes: los mismos pagos fijos de Movimientos › Fijos, con su estado de este mes.
        val ym = java.time.YearMonth.now()
        QCard {
            B("Cada mes")
            Mut("Alquiler, internet, mensualidades… Son los mismos de Movimientos › Fijos.")
            Items(d.arr("recurring").filter { it.bool("active") != false }.sortedBy { it.num("day") }, "No tienes pagos fijos.") { r ->
                val paid = recPaid(d, r, ym)
                val day = r.num("day").toInt()
                Column(Modifier.weight(1f)) {
                    B(r.str("description") ?: "Pago fijo")
                    Mut(when {
                        paid -> "Pagado este mes"
                        r.str("mode") == "manual" -> if (day < LocalDate.now().dayOfMonth) "Venció el día $day" else "Vence el día $day"
                        else -> "Se anota solo el día $day"
                    })
                }
                Amt(f.orig(r.num("amount"), r.str("currency")))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Link("Editar", { st.open("editarr", r.id) })
                    if (!paid && r.str("mode") == "manual") Btn("Pagar", { st.open("rpago", r.id) }, small = true)
                }
            }
        }
        QCard {
            B("Pagos únicos")
            Items(d.arr("schoolExpenses"), "Matrícula, útiles, uniformes y otros pagos que no se repiten.") { x ->
                val left = schoolLeft(d, x)
                Column(Modifier.weight(1f)) {
                    B(x.str("description") ?: "Compromiso")
                    val due = dateOf(x.str("dueDate"))
                    val who = listOfNotNull(x.str("student")?.takeIf { it.isNotBlank() }, due?.let { "vence ${shortDate(it)}" }).joinToString(" · ")
                    if (who.isNotEmpty()) Mut(who.replaceFirstChar { it.uppercase() })
                    Mut(if (left > 0) "Falta ${f.orig(left, x.str("currency"))} de ${f.orig(x.num("amount"), x.str("currency"))}" else "Pagado")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Link("Editar", { st.open("editarc", x.id) })
                    if (left > 0) Btn("Pagar", { st.open("cpago", x.id) }, small = true)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Pago fijo mensual", { st.open("recurrente") }, Modifier.weight(1f), outline = true)
            Btn("Pago único", { st.open("compromiso") }, Modifier.weight(1f))
        }
    } else {
        val ps = d.arr("plannedDebtPayments").filter { it.str("status") == "planned" }.sortedBy { it.str("scheduledDate") ?: "" }
        QCard {
            Items(ps, "No tienes pagos programados.") { pp ->
                Column(Modifier.weight(1f)) {
                    B(pp.str("entity") ?: pp.str("description") ?: "Pago")
                    val cuota = pp.str("description")?.takeIf { it.startsWith("Cuota ") }
                    dateOf(pp.str("scheduledDate"))?.let { Mut(longDate(it) + (cuota?.let { c -> " · $c" } ?: "")) }
                }
                Amt(f.orig(pp.num("amount"), pp.str("currency")))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Link("Editar", { st.open("editarp", pp.id) })
                    Btn("Ya pagué", { payPlanned(st, pp) }, small = true)
                }
            }
        }
        Btn("Programar un pago", { if (d.arr("debts").isEmpty()) st.toast("Primero agrega una deuda en Deudas.") else st.open("pago") })
        ComprometidoCard(st)
    }
}

/** "Ya pagué": marca el pago, lo descuenta de la deuda y lo registra como gasto (igual que la web). */
fun payPlanned(st: AppState, pp: JsonObject) = st.act("Pago registrado") { m ->
    val rate = rateOf(m.data())
    val usdAmt = usdOf(m.data(), pp.num("amount"), pp.str("currency"))
    m.updateRow("plannedDebtPayments", pp.id) { it.plusFields("status" to "paid") }
    m.updateRow("debts", pp.str("debtId") ?: "") { x ->
        val add = usdAmt * (if (x.str("currency") == "BS") rate else 1.0)
        x.plusFields("paid" to min(x.num("amount"), x.num("paid") + add))
    }
    m.addFirst("expenses", row(
        "id" to "${pp.id}_paid_${System.currentTimeMillis()}", "amount" to (pp["amount"] ?: 0), "currency" to (pp.str("currency") ?: "USD"),
        "category" to "deudas", "description" to "Pago a ${pp.str("entity") ?: "deuda"}", "method" to "banco", "bank" to "", "date" to nowIso(),
    ))
}

// =====================================================================
// MÁS
// =====================================================================

@Composable
fun Mas(st: AppState) {
    val ctx = LocalContext.current
    val repo = st.repo
    var lockOn by remember { mutableStateOf(repo.pref("lock") == "1") }
    var avisosOn by remember { mutableStateOf(repo.pref("avisos") == "1") }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val text = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), repo.data)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onSuccess { st.toast("Listo: tus datos se guardaron.") }.onFailure { st.toast("No pudimos guardar el archivo.") }
    }
    fun turnOnAvisos() { Reminders.enable(ctx); repo.setPref("avisos", "1"); avisosOn = true; st.toast("Listo: te avisaremos de pagos, cobros, presupuesto y versiones nuevas.") }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) turnOnAvisos() else st.toast("Activa las notificaciones en los ajustes del teléfono.")
    }

    Head(st)
    BizMasCard(st)
    QCard {
        B("¿Primera vez aquí?")
        Mut("Quinci te muestra paso a paso cómo dejar tu quincena lista.")
        Btn("Ver el tutorial", { st.tour = 0 }, outline = true)
    }
    QCard {
        B("¿Qué mejorarías?")
        Mut("Cuéntanos qué te gustaría ver en Quincena VE o qué no funciona bien. Lo leemos todo.")
        Btn("Enviar sugerencia", { st.open("sugerencia") }, outline = true)
    }
    PaisMasCard(st)
    QCard {
        B("Avisos")
        Mut("Te avisamos desde tu teléfono cada pocas horas, nunca de noche.")
        Btn(if (avisosOn) "Desactivar avisos de pago" else "Activar avisos de pago", {
            if (avisosOn) { Reminders.disable(ctx); repo.setPref("avisos", null); avisosOn = false; st.toast("Avisos desactivados.") }
            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            else turnOnAvisos()
        }, outline = true)
        if (avisosOn) {
            AVISO_TIPOS.forEach { (k, label) ->
                var on by remember { mutableStateOf(repo.pref("av_$k") != "0") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 15.sp)
                    androidx.compose.material3.Switch(on, { on = it; repo.setPref("av_$k", if (it) null else "0") },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = pal.g))
                }
            }
            Mut("Pagos: 7, 4 y 1 día antes y el mismo día. Me deben: el día del cobro y si pasan 3 días sin pagar. Presupuesto: al llegar al 80 % de una categoría en la quincena. Versión nueva: una vez por versión.")
        }
        HorizontalDivider(color = pal.line)
        HabitoSection(st)
    }
    DetectarCard(st)
    QCard {
        B("Seguridad")
        Btn(if (lockOn) "Quitar entrada con huella o rostro" else "Activar entrada con huella o rostro", {
            if (lockOn) { repo.setPref("lock", null); lockOn = false; st.toast("Listo: ya no se pedirá la huella.") }
            else {
                val can = BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                if (can == BiometricManager.BIOMETRIC_SUCCESS) { repo.setPref("lock", "1"); lockOn = true; st.toast("Listo: Quincena VE pedirá tu huella o rostro al abrirse.") }
                else st.toast("Configura una huella, rostro o bloqueo de pantalla en tu teléfono primero.")
            }
        }, outline = true)
        Btn("Cambiar contraseña", { st.open("clave") }, outline = true)
        Btn("Cerrar sesión en mis otros teléfonos", {
            st.scope.launch {
                try {
                    val n = repo.revokeOthers()
                    st.toast(if (n > 0) "Listo: cerramos la sesión en $n ${if (n == 1) "teléfono" else "teléfonos"}." else "No tenías sesión abierta en otros teléfonos.")
                } catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") }
                catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
            }
        }, outline = true)
    }
    QCard {
        B(repo.username.ifEmpty { "Mi cuenta" })
        Mut("Quincena VE ${BuildConfig.VERSION_NAME}")
        Btn("Descargar mis datos", { save.launch("quincena-mis-datos.json") }, outline = true)
        Btn("Cerrar sesión", {
            st.scope.launch { Reminders.disable(ctx); repo.setPref("avisos", null); repo.setPref("lock", null); st.go("inicio"); repo.logout() }
        }, outline = true)
        OutlineDanger("Eliminar mi cuenta") { st.open("borrar") }
    }
    LegalCard()
}
