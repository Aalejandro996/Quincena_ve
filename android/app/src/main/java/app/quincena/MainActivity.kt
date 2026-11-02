package app.quincena

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

class LockState { var locked by mutableStateOf(false) }

class MainActivity : FragmentActivity() {
    private lateinit var repo: Repo
    private val lock = LockState()
    private var resumes by mutableIntStateOf(0)
    private var stoppedAt = 0L
    private var prompting = false
    private lateinit var prompt: BiometricPrompt
    /** Texto compartido desde otra app (por ejemplo, el SMS del banco). */
    private var shared by mutableStateOf<String?>(null)

    /** Acción al abrir: "gasto" desde el atajo del ícono o el recordatorio diario. */
    private var startAction by mutableStateOf<String?>(null)

    /** Enlace de un correo: "verify" o "reset" con su código (va tras "#", como en la web). */
    private var link by mutableStateOf<Pair<String, String>?>(null)

    private fun readShared(i: android.content.Intent?) {
        if (i?.action == ACTION_NUEVO_GASTO) { startAction = "gasto"; return }
        if (i?.action == android.content.Intent.ACTION_VIEW) { link = emailLink(i.data); return }
        if (i == null || i.action != android.content.Intent.ACTION_SEND) return
        if (i.type?.startsWith("text/") != true) return
        shared = i.getStringExtra(android.content.Intent.EXTRA_TEXT)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShared(intent)
    }

    private val lockOn get() = repo.loggedIn && repo.pref("lock") == "1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repo(applicationContext)
        lock.locked = lockOn
        if (repo.loggedIn && repo.pref("avisos") == "1") Reminders.ensure(applicationContext)
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; lock.locked = false }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
        })
        readShared(intent)
        setContent { App(repo, lock, resumes, shared, { shared = null }, startAction, { startAction = null }, link, { link = null }) { unlock() } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        // Si la app estuvo en segundo plano más de 1 minuto, vuelve a pedir la huella.
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 60_000 && lockOn) lock.locked = true
        if (lock.locked) unlock()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        if (prompting) return
        if (!lockOn) { lock.locked = false; return }
        prompting = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear Quincena VE")
                .setSubtitle("Usa tu huella, tu rostro o el bloqueo del teléfono")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }
}

/** Lee /app#verify=… o /app#reset=… (los enlaces de los correos). */
fun emailLink(uri: android.net.Uri?): Pair<String, String>? {
    val frag = uri?.fragment ?: return null
    val kind = frag.substringBefore('=')
    val token = frag.substringAfter('=', "").substringBefore('&')
    return if (kind in setOf("verify", "reset") && token.length in 20..200 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) kind to token else null
}

data class Sheet(val kind: String, val arg: String? = null)

/** Estado de la pantalla (lo que en la web era el objeto S). */
class AppState(val repo: Repo, val scope: CoroutineScope, val snack: SnackbarHostState) {
    var tab by mutableStateOf("inicio")
    var sheet by mutableStateOf<Sheet?>(null)
    var per by mutableStateOf(currentPeriod())
    var hide by mutableStateOf(repo.pref("hide") == "1")
    var rateInfo by mutableStateOf<String?>(null)
    var detected by mutableStateOf<List<Det>>(emptyList())
    var update by mutableStateOf<AppUpdate?>(null)
    var mail by mutableStateOf<Pair<String?, Boolean>?>(null)
    var movSub by mutableStateOf("gastos")
    var pagosSub by mutableStateOf("pagos")
    var q by mutableStateOf("")
    var mon by mutableStateOf("")
    var fcat by mutableStateOf("")
    var deudasSub by mutableStateOf("debo")
    var calYm by mutableStateOf(java.time.YearMonth.now())
    var calDay by mutableIntStateOf(0)
    var bizSub by mutableStateOf("hoy")
    var rating by mutableStateOf(false)
    var cart by mutableStateOf(mapOf<String, Double>())
    var sellersVer by mutableIntStateOf(0)
    var tour by mutableIntStateOf(-1)  // paso del tutorial de bienvenida (-1 = cerrado)
    var confirmDup by mutableStateOf("")  // aviso de duplicado ya mostrado: el segundo Guardar lo agrega igual
    var pop by mutableStateOf<QSay?>(null)  // reacción de Quinci que aparece un momento
    fun quinci(mood: String, text: String, prop: String = "", label: String = "") { pop = QSay(mood, prop, label, text, System.nanoTime()) }

    val fmt: Fmt get() {
        val c = repo.data.str("currency") ?: "USD"
        return Fmt(hide, c == "BS", rateOf(repo.data), c, when (c) { "USDT" -> marketRateOf(repo.data); "EUR" -> repo.data.num("euroRate"); else -> 0.0 })
    }

    fun open(kind: String, arg: String? = null) { sheet = Sheet(kind, arg) }
    fun close() { sheet = null }
    fun go(t: String) { tab = t; sheet = null }

    fun toggleHide() { hide = !hide; repo.setPref("hide", if (hide) "1" else "0") }
    fun setCurrency(c: String) = act(null) { it["currency"] = JsonPrimitive(c) }

    fun toast(msg: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(msg, actionLabel = if (undo != null) "Deshacer" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    /** Aplica un cambio al instante, avisa y sincroniza. Con [undo] el aviso ofrece "Deshacer". */
    fun act(msg: String?, undo: Boolean = false, change: (MutableMap<String, JsonElement>) -> Unit) {
        val before = repo.data
        var keys: Set<String> = emptySet()
        scope.launch {
            val err = repo.edit { m -> change(m); keys = (before.keys + m.keys).filter { before[it] != m[it] }.toSet() }
            if (err != null) toast(err)
        }
        if (msg != null) toast(msg, if (undo) ({ restore(before, keys) }) else null)
    }

    private fun restore(before: JsonObject, keys: Set<String>) = act(null) { m ->
        for (k in keys) { val v = before[k]; if (v == null) m.remove(k) else m[k] = v }
    }
}

@Composable
fun App(
    repo: Repo, lock: LockState, resumes: Int, shared: String?, onShared: () -> Unit,
    startAction: String?, onStartAction: () -> Unit,
    link: Pair<String, String>?, onLink: () -> Unit, unlock: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val st = remember { AppState(repo, scope, snack) }
    LaunchedEffect(resumes) { st.update = repo.checkUpdate(); if (repo.loggedIn) repo.refreshAccount() }
    LaunchedEffect(link) {
        if (link == null || link.first != "verify") return@LaunchedEffect
        val token = link.second
        // onLink() borra el enlace y eso cancela este efecto: la confirmación corre aparte para no cortarse a mitad.
        scope.launch {
            try {
                repo.verifyEmail(token)
                st.toast("¡Listo! Tu correo quedó confirmado.")
                if (repo.loggedIn) st.mail = repo.emailStatus()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { st.toast(friendly(e)) }
        }
        onLink()
    }
    QuincenaTheme {
        val p = pal
        Box(Modifier.fillMaxSize()) {
            GlassBackground()
            when {
                UpdateGate.required || st.update?.required == true -> ForceUpdateScreen(st.update)
                lock.locked -> LockScreen(unlock)
                link != null && link.first == "reset" -> ResetScreen(st, link.second, onLink)
                !repo.loggedIn -> AuthScreen(st)
                repo.seller -> SellerScreen(st, resumes)
                else -> Main(st, resumes, shared, onShared, startAction, onStartAction)
            }
            if (repo.loggedIn && !repo.seller) QuinciPopHost(st, Modifier.align(Alignment.BottomCenter))
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp, start = 16.dp, end = 16.dp)) {
                Snackbar(it, shape = RoundedCornerShape(14.dp), containerColor = p.ink, contentColor = p.bg, actionColor = p.g)
            }
        }
    }
}

@Composable
fun LockScreen(unlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title("Quincena VE 🇻🇪", 36.sp)
        Mut("Tus finanzas, solo para ti.")
        Btn("Desbloquear", unlock)
    }
}

/** Pantalla que no se puede saltar cuando el servidor exige una versión más nueva de la app. */
@Composable
fun ForceUpdateScreen(u: AppUpdate?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val url = u?.url ?: (BuildConfig.API_BASE + "/descargar/android")
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title("Quincena VE 🇻🇪", 36.sp)
        B("Actualiza para continuar")
        Mut("Hay una versión nueva${u?.let { " (${it.version})" } ?: ""} y esta ya no es compatible. Toca Actualizar, abre el archivo descargado e instálalo encima: no pierdes tus datos.")
        u?.notes?.takeIf { it.isNotBlank() }?.let { Mut(it) }
        Btn("Actualizar", {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Mut("Versión instalada: ${BuildConfig.VERSION_NAME}")
    }
}

private val TABS: List<Triple<String, String, ImageVector>> = listOf(
    Triple("inicio", "Inicio", IconInicio), Triple("movimientos", "Movimientos", IconMov),
    Triple("deudas", "Deudas", IconDeudas), Triple("pagos", "Pagos", IconPagos), Triple("mas", "Más", IconMas),
)

@Composable
fun Main(
    st: AppState, resumes: Int, shared: String? = null, onShared: () -> Unit = {},
    startAction: String? = null, onStartAction: () -> Unit = {},
) {
    val p = pal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(startAction) {
        if (startAction == "gasto") { st.go("inicio"); st.open("gasto"); onStartAction() }
    }
    LaunchedEffect(shared) {
        if (shared != null) { handleBankText(st, ctx, shared); onShared() }
    }
    LaunchedEffect(resumes) {
        st.detected = Detected.load(ctx)
        st.repo.refresh()?.let { st.toast(it) }
        if (!st.repo.loggedIn) return@LaunchedEffect
        st.rateInfo = st.repo.updateRate()
        st.mail = st.repo.emailStatus()
        if (st.tour < 0 && tourDue(st.repo.data)) st.tour = 0
    }
    BackHandler(enabled = st.tab != "inicio") { st.go("inicio") }

    // La barra y el botón + flotan sobre el contenido (vidrio): el contenido pasa por debajo y se deja ver.
    Box(Modifier.fillMaxSize()) {
        key(st.tab) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (st.tab) {
                        "inicio" -> Inicio(st)
                        "movimientos" -> Movimientos(st)
                        "deudas" -> Deudas(st)
                        "pagos" -> Pagos(st)
                        "negocio" -> if (bizOn(st.repo.data)) Negocio(st) else Inicio(st)
                        else -> Mas(st)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(
                Modifier.padding(end = 20.dp, bottom = 12.dp).size(60.dp)
                    .shadow(14.dp, CircleShape, spotColor = p.g, ambientColor = p.g).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF12936A), Color(0xFF0B6B4D))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .6f), Color.White.copy(alpha = .05f))), CircleShape)
                    .clickable { st.open(if (st.tab == "negocio") "bizventa" else "gasto") },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = Color.White, fontSize = 32.sp) }
            NavBar(st)
        }
        if (st.tour >= 0) TourOverlay(st)
    }
    SheetHost(st)
    TermsDialog(st)
}

@Composable
fun NavBar(st: AppState) {
    val p = pal
    // Con el modo Negocio hay 6 pestañas: Negocio va después de Inicio y las etiquetas se achican para caber.
    val tabs = if (bizOn(st.repo.data)) TABS.take(1) + Triple("negocio", "Negocio", IconNegocio) + TABS.drop(1) else TABS
    val six = tabs.size > 5
    // Barra flotante de vidrio, como en iOS: cápsula translúcida y la pestaña activa resaltada.
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), 18.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEach { (k, label, icon) ->
                val on = st.tab == k
                val c = if (on) p.g else p.mut
                Column(
                    Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(26.dp))
                        .then(if (on) Modifier.background(p.g.copy(alpha = .13f)).border(1.dp, p.g.copy(alpha = .25f), RoundedCornerShape(26.dp)) else Modifier)
                        .clickable { st.go(k) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(icon, null, Modifier.size(24.dp), tint = c)
                    Text(label, fontSize = if (six) 9.5.sp else 11.sp, letterSpacing = (-0.2).sp, color = c, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Encabezado de cada pantalla: nombre, quincena actual, ocultar montos y $ / Bs. */
@Composable
fun Head(st: AppState) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Title("Quincena VE")
            Mut("${if (today.dayOfMonth <= 15) "1.ª" else "2.ª"} quincena de ${monthName(java.time.YearMonth.from(today))}")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Link(if (st.hide) "Mostrar" else "Ocultar", st::toggleHide)
            // $ (BCV), USDT y € solo en Venezuela y cuando conocemos esas tasas; siempre la moneda local.
            val d = st.repo.data
            val ve = Loc.cur.code == "VE"
            val opts = listOf("USD" to "$") + (if (ve && marketRateOf(d) > 0) listOf("USDT" to "USDT") else emptyList()) +
                (if (ve && d.num("euroRate") > 0) listOf("EUR" to "€") else emptyList()) + listOf("BS" to Loc.cur.short)
            val cur = d.str("currency")?.takeIf { c -> opts.any { it.first == c } } ?: "USD"
            Seg(opts, cur, st::setCurrency)
        }
    }
}
