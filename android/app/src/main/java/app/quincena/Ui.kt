package app.quincena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------- Colores y letras de la web ----------

data class Pal(
    val bg: Color, val ink: Color, val mut: Color, val g: Color, val gs: Color, val line: Color,
    val warn: Color, val bad: Color, val soft: Color, val card: Color,
)
val LightPal = Pal(Color(0xFFF3F6F2), Color(0xFF12211B), Color(0xFF46564E), Color(0xFF0B6B4D), Color(0xFFE3F1EA), Color(0xFFD5DDD7),
    Color(0xFFB25E00), Color(0xFFB3261E), Color(0xFFFBE7BF), Color.White)
val DarkPal = Pal(Color(0xFF0E1512), Color(0xFFEAF1EC), Color(0xFFA9B8AF), Color(0xFF4FC79C), Color(0xFF173226), Color(0xFF2A3A32),
    Color(0xFFF0A84A), Color(0xFFFF8A80), Color(0xFF3A2D12), Color(0xFF16211C))
val LocalPal = staticCompositionLocalOf { LightPal }
val HeroNeg = Color(0xFF9C2B24)

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal), Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold), Font(R.font.figtree_bold, FontWeight.Bold),
)
val Bricolage = FontFamily(Font(R.font.bricolage_semibold, FontWeight.SemiBold), Font(R.font.bricolage_bold, FontWeight.Bold))

@Composable
fun QuincenaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(primary = p.g, onPrimary = Color.White, background = p.bg, surface = p.card, onSurface = p.ink, error = p.bad)
    else lightColorScheme(primary = p.g, onPrimary = Color.White, background = p.bg, surface = p.card, onSurface = p.ink, error = p.bad)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPal provides p,
            LocalTextStyle provides TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 22.sp, color = p.ink),
            content = content,
        )
    }
}

val pal: Pal @Composable get() = LocalPal.current

// ---------- Liquid Glass: superficies translúcidas con brillo en el borde, sobre un fondo con luz ----------

val isDark: Boolean @Composable get() = LocalPal.current === DarkPal

/** Relleno del vidrio: deja ver el fondo con color de la marca. */
val glassFill: Color @Composable get() = if (isDark) Color(0xFF16211C).copy(alpha = .58f) else Color.White.copy(alpha = .62f)

/** Borde con reflejo: más brillante arriba, como el vidrio de iOS. */
val glassEdge: Brush @Composable get() = Brush.verticalGradient(
    if (isDark) listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .04f), Color.White.copy(alpha = .10f))
    else listOf(Color.White, Color.White.copy(alpha = .35f), Color(0xFFD5DDD7).copy(alpha = .7f)),
)

/** Superficie de vidrio: sombra suave, relleno translúcido, reflejo superior y borde brillante. */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 10.dp, fill: Color = glassFill): Modifier {
    val dark = isDark
    return this
        .shadow(elevation, shape, ambientColor = Color(0xFF0B6B4D).copy(alpha = if (dark) .5f else .18f), spotColor = Color(0xFF0B6B4D).copy(alpha = if (dark) .5f else .22f))
        .clip(shape)
        .background(fill)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) .07f else .45f), .45f to Color.Transparent))
        .border(1.dp, glassEdge, shape)
}

/** Barra fina arriba mientras hay peticiones al servidor (aparece si tardan más de 250 ms). */
@Composable
fun NetBar(modifier: Modifier = Modifier) {
    val busy = Net.busy > 0
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(250); show = true } else show = false }
    if (show) LinearProgressIndicator(modifier.fillMaxWidth().statusBarsPadding().height(3.dp), color = pal.g, trackColor = Color.Transparent)
}

/** Fondo de la app: el color de siempre con manchas de luz verde y menta, para que el vidrio se note. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val p = pal
    val dark = isDark
    Box(modifier.fillMaxSize().background(p.bg).drawBehind {
        val w = size.width; val h = size.height
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(x, y), radius = r), r, Offset(x, y))
        blob(Color(0xFF12936A).copy(alpha = if (dark) .30f else .22f), w * .05f, h * .08f, w * .85f)
        blob(Color(0xFFBFF0D8).copy(alpha = if (dark) .10f else .65f), w * .95f, h * .32f, w * .75f)
        blob(Color(0xFF0B6B4D).copy(alpha = if (dark) .26f else .14f), w * .2f, h * .78f, w * .9f)
        blob(Color(0xFFBFF0D8).copy(alpha = if (dark) .08f else .5f), w * .9f, h * .98f, w * .7f)
    })
}

// ---------- Textos ----------

@Composable fun Title(text: String, size: TextUnit = 22.sp, color: Color = pal.g) =
    Text(loc(text), fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.15f, color = color)

@Composable fun Big(text: String, size: TextUnit = 48.sp, color: Color = Color.Unspecified) =
    Text(loc(text), fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.1f, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable fun Mut(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(loc(text), modifier, color = pal.mut, fontSize = 14.sp, lineHeight = 19.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable fun B(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) =
    Text(loc(text), modifier, fontWeight = FontWeight.Bold, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable fun Amt(text: String) = Text(text, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)

// ---------- Bloques ----------

@Composable
fun QCard(modifier: Modifier = Modifier, bg: Color = pal.card, border: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    // Tarjeta normal = vidrio. Las de color (portada, avisos) quedan sólidas, con el mismo reflejo.
    val solid = bg != pal.card
    Column(
        modifier.fillMaxWidth()
            .then(if (solid) Modifier.shadow(if (border) 8.dp else 12.dp, shape, spotColor = bg.copy(alpha = .45f)).clip(shape).background(bg)
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = .16f), .5f to Color.Transparent))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .45f), Color.White.copy(alpha = .05f))), shape)
            else Modifier.glass(shape))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, outline: Boolean = false,
    color: Color = pal.g, enabled: Boolean = true, full: Boolean = !small, loading: Boolean = false,
) {
    val shape = CircleShape
    Box(
        (if (full) modifier.fillMaxWidth() else modifier)
            .heightIn(min = if (small) 40.dp else 48.dp)
            .clip(shape)
            .then(if (outline) Modifier.background(glassFill).border(1.5.dp, color, shape)
                  else Modifier.background(Brush.verticalGradient(listOf(lerp(color, Color.White, .14f), color)))
                      .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.White.copy(alpha = .04f))), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Mientras guarda o se conecta, el botón muestra una ruedita en vez del texto.
        if (loading) CircularProgressIndicator(Modifier.size(if (small) 18.dp else 22.dp), color = if (outline) color else Color.White, strokeWidth = 2.5.dp)
        else Text(loc(text), color = (if (outline) color else Color.White).copy(alpha = if (enabled) 1f else .6f),
            fontWeight = FontWeight.SemiBold, fontSize = if (small) 14.sp else 16.sp, maxLines = 1)
    }
}

@Composable
fun Link(text: String, onClick: () -> Unit, color: Color = pal.g) {
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(loc(text), color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val p = pal
    Box(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (on) p.gs.copy(alpha = .9f) else glassFill)
            .border(1.dp, if (on) SolidColor(p.g) else glassEdge, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(loc(text), fontWeight = FontWeight.SemiBold, color = if (on) p.g else p.ink, maxLines = 1) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(modifier: Modifier = Modifier, center: Boolean = false, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun Bar(fraction: Double, color: Color = pal.g, track: Color = Color(0x407F7F7F), height: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** Lista con línea entre filas, como .item en la web. */
@Composable
fun <T> Items(list: List<T>, empty: String, item: @Composable RowScope.(T) -> Unit) {
    if (list.isEmpty()) { Mut(empty); return }
    Column {
        list.forEachIndexed { i, x ->
            if (i > 0) HorizontalDivider(color = pal.line, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { item(x) }
        }
    }
}

@Composable
fun Seg(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    val p = pal
    Row(Modifier.glass(CircleShape, 4.dp).padding(3.dp)) {
        options.forEach { (v, l) ->
            val on = v == value
            Box(
                Modifier.height(36.dp).widthIn(min = 44.dp).clip(CircleShape).background(if (on) p.g else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else p.ink) }
        }
    }
}

// ---------- Campos de formulario ----------

@Composable
private fun FieldLabel(text: String) = Text(loc(text), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = pal.g, unfocusedBorderColor = pal.line, focusedContainerColor = glassFill, unfocusedContainerColor = glassFill,
    cursorColor = pal.g, focusedTextColor = pal.ink, unfocusedTextColor = pal.ink,
)

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "",
    kind: KeyboardType = KeyboardType.Text, password: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            placeholder = { if (placeholder.isNotEmpty()) Text(loc(placeholder), color = LocalPal.current.mut) },
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else kind),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
fun MoneyField(label: String, value: String, onChange: (String) -> Unit) =
    Field(label, value, onChange, placeholder = "0,00", kind = KeyboardType.Decimal)

/** Lista desplegable (como <select> en la web). */
@Composable
fun Select(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val p = pal
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(glassFill)
                    .border(1.dp, glassEdge, RoundedCornerShape(16.dp)).clickable { expanded = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(loc(options.firstOrNull { it.first == value }?.second ?: "Elegir…"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾", color = p.mut)
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(loc(l)) }, onClick = { onChange(v); expanded = false }) }
            }
        }
    }
}

@Composable
fun CurrencySelect(value: String, onChange: (String) -> Unit) = Select("Moneda", CURRENCIES, value, onChange)

@Composable
fun CategoryChips(value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Categoría")
        Chips { CATS.forEach { (k, l) -> Chip(l, k == value) { onChange(k) } } }
    }
}

/** Campo de fecha (AAAA-MM-DD) con calendario. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, optional: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val p = pal
    val d = dateOf(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(glassFill)
                .border(1.dp, glassEdge, RoundedCornerShape(16.dp)).clickable { expanded = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(d?.let { longDate(it) + " de " + it.year } ?: "Elegir fecha", Modifier.weight(1f), color = if (d == null) p.mut else p.ink)
            if (optional && d != null) Link("Quitar", { onChange("") })
        }
    }
    if (expanded) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (d ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { expanded = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    expanded = false
                }) { Text("Listo", color = p.g) }
            },
            dismissButton = { TextButton({ expanded = false }) { Text("Cancelar", color = p.g) } },
        ) { DatePicker(st) }
    }
}

// ---------- Íconos de la barra inferior (mismos trazos SVG de la web) ----------

private fun strokeIcon(vararg paths: String): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (d in paths) b.addPath(
        pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter,
    )
    return b.build()
}

val IconInicio = strokeIcon("M4 11l8-7 8 7v9H4z")
val IconNegocio = strokeIcon("M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6")
val IconMov = strokeIcon("M4 7h16M4 12h16M4 17h10")
val IconDeudas = strokeIcon("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V8a2 2 0 0 1 2 -2z", "M3 10h18")
val IconPagos = strokeIcon("M6 5h12a3 3 0 0 1 3 3v9a3 3 0 0 1 -3 3H6a3 3 0 0 1 -3 -3V8a3 3 0 0 1 3 -3z", "M3 10h18M8 3v4M16 3v4")
val IconMas = strokeIcon(
    "M3.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z",
    "M17.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z",
)

@Composable
fun OutlineDanger(text: String, onClick: () -> Unit) = Btn(text, onClick, outline = true, color = pal.bad)

