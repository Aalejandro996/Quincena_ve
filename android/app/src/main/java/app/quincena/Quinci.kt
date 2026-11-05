package app.quincena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.roundToInt

// Quinci: la mascota de Quincena VE. Una moneda con su hojita verde que reacciona a lo que pasa con tu dinero.
// El dibujo es el mismo de la web (static/quinci.js), en una cuadrícula de 120 × 120.

data class QSay(val mood: String, val prop: String, val label: String, val text: String, val key: Long = 0)

private val Ink = Color(0xFF3B2A05)
private val Rim = Color(0xFFC98A00)

@Composable
fun QuinciFace(mood: String, prop: String = "", label: String = "", size: Dp = 84.dp) {
    val tm = rememberTextMeasurer()
    val t = rememberInfiniteTransition(label = "quinci")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mood == "celebra") 900 else 1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by t.animateFloat(.97f, 1.035f, infiniteRepeatable(tween(if (mood == "celebra") 720 else 2100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val dy = if (mood == "celebra") -8f * bob else -4f * bob
    val tilt = when (mood) { "preocupado", "alerta" -> (bob - .5f) * 7f; "pensando" -> (bob - .5f) * 4f; "celebra" -> (bob - .5f) * 5f; else -> 0f }
    val scale = if (mood == "celebra" || mood == "alerta") pulse else 1f
    Canvas(Modifier.size(size).graphicsLayer { translationY = dy * density; rotationZ = tilt; scaleX = scale; scaleY = scale }) {
        val k = this.size.width / 120f
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun quad(path: Path, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) { path.moveTo(x0 * k, y0 * k); path.quadraticBezierTo(cx * k, cy * k, x1 * k, y1 * k) }
        fun stroke(w: Float) = Stroke(w * k, cap = StrokeCap.Round)
        fun oval(cx: Float, cy: Float, rx: Float, ry: Float, c: Color) = drawOval(c, o(cx - rx, cy - ry), Size(2 * rx * k, 2 * ry * k))
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Color = Ink) = drawLine(c, o(x0, y0), o(x1, y1), w * k, StrokeCap.Round)

        // Sombra, hojita y brazos
        drawOval(Color(0xFF0B6B4D).copy(alpha = .15f), o(32f, 107.5f), Size(56 * k, 9 * k))
        Path().apply { moveTo(60 * k, 22 * k); quadraticBezierTo(58 * k, 12 * k, 66 * k, 6 * k); quadraticBezierTo(76 * k, 8 * k, 74 * k, 18 * k); quadraticBezierTo(68 * k, 24 * k, 60 * k, 22 * k); close() }
            .let { drawPath(it, Color(0xFF12936A)) }
        Path().apply { quad(this, 61f, 21f, 65f, 15f, 70f, 10f) }.let { drawPath(it, Color(0xFF0B6B4D), style = stroke(1.6f)) }
        Path().apply {
            if (mood == "celebra") { quad(this, 24f, 58f, 14f, 48f, 16f, 36f); quad(this, 96f, 58f, 106f, 48f, 104f, 36f) }
            else { quad(this, 22f, 66f, 14f, 70f, 13f, 78f); quad(this, 98f, 66f, 107f, 68f, 110f, 75f) }
        }.let { drawPath(it, Rim, style = stroke(5f)) }

        // Cuerpo de moneda
        drawCircle(Brush.radialGradient(0f to Color(0xFFFFE27A), .6f to Color(0xFFFFC530), 1f to Color(0xFFE9A100), center = o(52f, 50f), radius = 56 * k), 40 * k, o(60f, 62f))
        drawCircle(Rim, 40 * k, o(60f, 62f), style = Stroke(2.5f * k))
        drawCircle(Color(0xFFFFE9A0).copy(alpha = .8f), 33 * k, o(60f, 62f), style = Stroke(2 * k))
        drawCircle(Color(0xFFF59A8B).copy(alpha = .55f), 5 * k, o(40f, 69f))
        drawCircle(Color(0xFFF59A8B).copy(alpha = .55f), 5 * k, o(80f, 69f))

        // Ojos
        when (mood) {
            "celebra" -> Path().apply { quad(this, 41f, 57f, 47f, 49f, 53f, 57f); quad(this, 67f, 57f, 73f, 49f, 79f, 57f) }.let { drawPath(it, Ink, style = stroke(3.4f)) }
            "alerta" -> {
                for (x in listOf(47f, 73f)) { drawCircle(Color.White, 7 * k, o(x, 56f)); drawCircle(Ink, 7 * k, o(x, 56f), style = Stroke(2 * k)); drawCircle(Ink, 3.6f * k, o(x, 56f)) }
                line(40f, 45f, 52f, 43f, 3f); line(80f, 45f, 68f, 43f, 3f)
            }
            "preocupado" -> {
                oval(47f, 58f, 4.6f, 6f, Ink); oval(73f, 58f, 4.6f, 6f, Ink)
                drawCircle(Color.White, 1.6f * k, o(48.5f, 55.5f)); drawCircle(Color.White, 1.6f * k, o(74.5f, 55.5f))
                line(40f, 47f, 52f, 51f, 3f); line(80f, 47f, 68f, 51f, 3f)
            }
            "pensando" -> {
                oval(49f, 55f, 4.6f, 6f, Ink); oval(75f, 55f, 4.6f, 6f, Ink)
                drawCircle(Color.White, 1.6f * k, o(51f, 52f)); drawCircle(Color.White, 1.6f * k, o(77f, 52f))
                Path().apply { quad(this, 42f, 46f, 48f, 42f, 54f, 45f) }.let { drawPath(it, Ink, style = stroke(3f)) }
            }
            else -> {
                oval(47f, 56f, 5f, 6.5f, Ink); oval(73f, 56f, 5f, 6.5f, Ink)
                drawCircle(Color.White, 1.8f * k, o(49f, 53.5f)); drawCircle(Color.White, 1.8f * k, o(75f, 53.5f))
            }
        }
        // Boca
        when (mood) {
            "celebra" -> {
                Path().apply { moveTo(48 * k, 67 * k); lineTo(72 * k, 67 * k); quadraticBezierTo(70 * k, 80 * k, 60 * k, 80 * k); quadraticBezierTo(50 * k, 80 * k, 48 * k, 67 * k); close() }
                    .let { drawPath(it, Color(0xFF7A2E12)) }
                Path().apply { moveTo(53 * k, 75 * k); quadraticBezierTo(60 * k, 79 * k, 67 * k, 75 * k); close() }.let { drawPath(it, Color(0xFFF07A6A)) }
            }
            "preocupado" -> Path().apply { quad(this, 50f, 76f, 55f, 70f, 60f, 76f); quadraticBezierTo(65 * k, 82 * k, 70 * k, 76 * k) }.let { drawPath(it, Ink, style = stroke(3.2f)) }
            "alerta" -> oval(60f, 74f, 5f, 6f, Color(0xFF7A2E12))
            "pensando" -> line(53f, 73f, 66f, 73f, 3.2f)
            else -> Path().apply { quad(this, 50f, 70f, 60f, 79f, 70f, 70f) }.let { drawPath(it, Ink, style = stroke(3.4f)) }
        }
        if (mood == "preocupado") Path().apply { moveTo(86 * k, 40 * k); quadraticBezierTo(90 * k, 47 * k, 86 * k, 49 * k); quadraticBezierTo(82 * k, 47 * k, 86 * k, 40 * k); close() }
            .let { drawPath(it, Color(0xFF7CC6F2)) }

        // Objetos
        fun label(text: String, x: Float, y: Float, sz: Float, c: Color) {
            if (text.isEmpty()) return
            val r = tm.measure(text, TextStyle(fontSize = (sz * k / density / fontScale).sp, fontWeight = FontWeight.Bold, color = c, fontFamily = Figtree))
            drawText(r, topLeft = Offset(x * k - r.size.width / 2f, y * k - r.size.height * .78f))
        }
        when (prop) {
            "cartera" -> rotate(-8f, o(104f, 88f)) {
                drawRoundRect(Color(0xFF6B3F1D), o(82f, 74f), Size(40 * k, 28 * k), CornerRadius(6 * k))
                drawRoundRect(Color(0xFF8A5428), o(82f, 74f), Size(40 * k, 9 * k), CornerRadius(4 * k))
                drawCircle(Color(0xFFE7C27A), 3 * k, o(114f, 90f))
                label(label.take(12), 101f, 94f, 7.5f, Color(0xFFFFE9C2))
            }
            "calendario" -> {
                drawRoundRect(Color.White, o(90f, 70f), Size(28 * k, 28 * k), CornerRadius(5 * k))
                drawRoundRect(Color(0xFFD5DDD7), o(90f, 70f), Size(28 * k, 28 * k), CornerRadius(5 * k), style = Stroke(1.5f * k))
                drawRoundRect(Color(0xFFCF142B), o(90f, 70f), Size(28 * k, 9 * k), CornerRadius(4 * k))
                label(label.ifEmpty { "!" }.take(3), 104f, 94f, 12f, Color(0xFF12211B))
            }
            "confeti" -> {
                confetti(k)
            }
            "lupa" -> {
                drawCircle(Color(0xFFE3F1EA), 9 * k, o(104f, 84f)); drawCircle(Color(0xFF46564E), 9 * k, o(104f, 84f), style = Stroke(3 * k))
                line(110f, 91f, 117f, 99f, 4f, Color(0xFF46564E))
            }
        }
    }
}

private fun DrawScope.confetti(k: Float) {
    val bits = listOf(Triple(12f, 12f, Color(0xFFFFCC00)) to 25f, Triple(100f, 10f, Color(0xFF00247D)) to -30f, Triple(20f, 96f, Color(0xFFCF142B)) to 40f,
        Triple(96f, 100f, Color(0xFFFFCC00)) to -20f)
    for ((b, deg) in bits) withTransform({ rotate(deg, Offset((b.first + 3) * k, (b.second + 1.5f) * k)) }) {
        drawRoundRect(b.third, Offset(b.first * k, b.second * k), Size(6 * k, 3 * k), CornerRadius(1 * k))
    }
    drawCircle(Color(0xFF12936A), 2.5f * k, Offset(106 * k, 30 * k))
    drawCircle(Color(0xFFCF142B), 2.5f * k, Offset(14 * k, 36 * k))
}

data class QNow(val mood: String, val prop: String, val label: String, val text: String, val btn: String? = null, val go: ((AppState) -> Unit)? = null)

/** Qué dice Quinci ahora: el primer caso que aplique, de lo más urgente a lo más alegre (igual que la web). */
fun quinciNow(st: AppState): QNow {
    val d = st.repo.data
    val f = st.fmt
    val today = LocalDate.now()
    if (d.arr("incomeSources").isEmpty() && d.arr("expenses").isEmpty())
        return QNow("feliz", "", "", "¡Hola! Soy Quinci y te acompaño con tu quincena. Empecemos: agrega tu ingreso.", "Agregar ingreso") { it.open("ingreso") }
    val per = currentPeriod()
    val cp = summarize(d, per)
    if (cp.income > 0 && cp.free < 0)
        return QNow("preocupado", "lupa", "", "Este periodo sale más de lo que entra: te faltan ${f.show(-cp.free)}. Busquemos qué recortar.", "Ver mis gastos") { it.go("movimientos") }
    val qs = comprometido(d)
    val inc = qs.sumOf { it.inc }
    val pct = if (inc > 0) (qs.sumOf { it.com } / inc * 100).roundToInt() else -1
    if (pct >= 60) {
        val big = d.arr("debts").filter { it.num("amount") - it.num("paid") > .005 }.maxByOrNull { usdOf(d, it.num("amount") - it.num("paid"), it.str("currency")) }
        val name = big?.str("entity") ?: "Deudas"
        return QNow("preocupado", "cartera", name, "Tu sueldo de las próximas quincenas se va en $pct % en deudas y pagos" +
            (if (big != null) ". La más pesada: «$name»" else "") + ". Armemos un plan.", "Ver mi plan") { it.go("deudas") }
    }
    data class Soon(val day: LocalDate, val what: String, val amount: String)
    val soon = mutableListOf<Soon>()
    val in3 = today.plusDays(3)
    fun near(x: LocalDate?) = x != null && !x.isBefore(today) && !x.isAfter(in3)
    for (p in d.arr("plannedDebtPayments")) if (p.str("status") == "planned" && near(dateOf(p.str("scheduledDate"))))
        soon += Soon(dateOf(p.str("scheduledDate"))!!, p.str("entity") ?: "un pago", f.orig(p.num("amount"), p.str("currency")))
    val ym = YearMonth.now()
    for (r in d.arr("recurring")) if (r.bool("active") != false && r.str("mode") == "manual" && !recPaid(d, r, ym) && near(recDay(r, ym)))
        soon += Soon(recDay(r, ym), r.str("description") ?: "un pago fijo", f.orig(r.num("amount"), r.str("currency")))
    for (x in d.arr("schoolExpenses")) if (near(dateOf(x.str("dueDate"))) && schoolLeft(d, x) > .005)
        soon += Soon(dateOf(x.str("dueDate"))!!, x.str("description") ?: "un compromiso", f.orig(schoolLeft(d, x), x.str("currency")))
    soon.sortBy { it.day }
    soon.firstOrNull()?.let { s ->
        val whenTxt = when (s.day) { today -> "Hoy"; today.plusDays(1) -> "Mañana"; else -> "El " + s.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("es")) }
        val more = if (soon.size > 1) " y ${soon.size - 1} pago${if (soon.size > 2) "s" else ""} más" else ""
        return QNow("alerta", "calendario", s.day.dayOfMonth.toString(), "$whenTxt vence «${s.what}» (${s.amount})$more. ¡Que no te agarre desprevenido!", "Ver pagos") { it.go("pagos") }
    }
    val b = d.obj("budgets")
    val fr = if (per.half == 0) 1.0 else .5
    val cat = spentByCat(d, per.from, per.to)
    CATS.keys.filter { b.num(it) > 0 && (cat[it] ?: 0.0) > b.num(it) * fr }.maxByOrNull { (cat[it] ?: 0.0) - b.num(it) * fr }?.let { k ->
        return QNow("alerta", "", "", "Te pasaste del presupuesto de ${CATS[k]} por ${f.show((cat[k] ?: 0.0) - b.num(k) * fr)}. Frenemos un poquito ahí.", "Ver presupuesto") { it.open("info:pres") }
    }
    val days = d.arr("expenses").mapNotNull { localDay(it) }.toSet()
    var streak = 0
    for (i in 0 until 60) { if (today.minusDays(i.toLong()) in days) streak++ else if (i > 0 || streak > 0) break }
    if (streak >= 3) return QNow("celebra", "confeti", "", "¡Llevas $streak días seguidos anotando tus gastos! Así se controla la quincena.")
    if (today !in days && LocalTime.now().hour >= 18) return QNow("pensando", "", "", "¿Gastaste algo hoy? Anótalo en 2 segundos y no se te olvida.", "Anotar gasto") { it.open("gasto") }
    val plan = debtPlan(d)
    if (debtTotals(d).remaining > .005 && plan.configured && plan.months != null)
        return QNow("feliz", "", "", "Vas en camino: si sigues el plan, terminas tus deudas en ${plan.months} ${if (plan.months == 1) "mes" else "meses"}.")
    return QNow("feliz", "", "", if (cp.income > 0) "¡Vas bien! Te quedan ${f.show(cp.free)} en este periodo." else "Todo en orden por aquí. ¡Sigue anotando!")
}

@Composable
fun QuinciCard(st: AppState) {
    val today = LocalDate.now().toString()
    if (st.repo.pref("quinci_off") == today) return
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    val q = quinciNow(st)
    QCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuinciFace(q.mood, q.prop, q.label, 80.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quinci", Modifier.weight(1f), color = pal.g, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("×", Modifier.clickable { st.repo.setPref("quinci_off", today); hidden = true }.padding(horizontal = 6.dp), color = pal.mut, fontSize = 20.sp)
                }
                Text(q.text, fontSize = 15.sp, lineHeight = 20.sp)
                if (q.btn != null && q.go != null) Btn(q.btn, { q.go.invoke(st) }, small = true)
            }
        }
    }
}

/** Reacción de Quinci: aparece abajo unos segundos (tras anotar un gasto, abonar, crear el plan…). */
@Composable
fun QuinciPopHost(st: AppState, modifier: Modifier = Modifier) {
    val say = st.pop
    var shown by remember { mutableStateOf<QSay?>(null) }
    LaunchedEffect(say) {
        if (say == null) return@LaunchedEffect
        shown = say
        delay(4200)
        if (st.pop == say) st.pop = null
    }
    AnimatedVisibility(say != null, modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 100.dp),
        enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        val s = shown ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 16.dp).clickable { st.pop = null }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuinciFace(s.mood, s.prop, s.label, 60.dp)
            Text(s.text, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Después de anotar un gasto: si con él te pasaste del presupuesto, lo dice; si no, te anima. */
fun quinciAfterGasto(st: AppState, cat: String) {
    st.scope.launch {
        delay(350)  // deja que el cambio se aplique primero
        val d = st.repo.data
        val per = currentPeriod()
        val lim = d.obj("budgets").num(cat) * (if (per.half == 0) 1.0 else .5)
        val spent = spentByCat(d, per.from, per.to)[cat] ?: 0.0
        when {
            lim > 0 && spent > lim -> st.quinci("alerta", "Uy, con este gasto pasaste tu presupuesto de ${CATS[cat]}.")
            lim > 0 && spent > lim * .8 -> st.quinci("pensando", "Ojo: ya usaste el ${(spent / lim * 100).roundToInt()} % de tu presupuesto de ${CATS[cat]}.")
            else -> {
                val cp = summarize(d, per)
                st.quinci("feliz", if (cp.income > 0) "¡Anotado! Te quedan ${st.fmt.show(cp.free)} en este periodo." else "¡Anotado! Así no se te escapa nada.")
            }
        }
    }
}
