package app.quincena

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Resumen de los términos (versión completa en /terminos, /privacidad y /cookies de la web).
// BORRADOR: revisar con un abogado antes de publicar.
val LEGAL = listOf(
    "Qué es Quincena VE" to "Una herramienta gratuita para organizar tu dinero. No ofrece asesoría financiera, legal ni contable: sus cálculos son estimaciones y las decisiones son tuyas.",
    "Qué datos guardamos" to "Tu usuario, tu correo y la información financiera que tú registras: ingresos, gastos, cuentas, deudas, préstamos, pagos y compromisos. Nunca registres claves bancarias, PIN ni datos de tarjetas.",
    "Para qué los usamos" to "Solo para que Quincena VE funcione: mostrarte tus cuentas, sincronizarlas, enviarte avisos y recuperar tu contraseña. No vendemos tus datos ni los usamos para publicidad.",
    "Quién más interviene" to "El servidor donde se aloja Quincena VE, el servicio de correo para recuperar tu contraseña y servicios públicos de tipo de cambio según tu país, que no reciben tus datos.",
    "Seguridad" to "Conexión cifrada, contraseña guardada con hash y, en el teléfono, datos cifrados con una llave que no sale del equipo. Puedes exigir huella o rostro y cerrar la sesión en tus otros teléfonos desde Más.",
    "Tus derechos" to "Puedes ver, corregir, descargar y eliminar tus datos cuando quieras desde Más (Constitución de Venezuela, artículos 28 y 60).",
    "Tu cuenta" to "Debes tener 18 años o más. Eres responsable de tu contraseña y de lo que registras.",
    "Cambios" to "Si cambiamos estos términos, te lo avisaremos en la app y te pediremos aceptarlos de nuevo.",
)

@Composable
fun LegalText() {
    LEGAL.forEach { (t, b) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { B(t); Mut(b) }
    }
    Mut("Lee la versión completa:")
    LegalLinks()
    Text(copyright(), fontSize = 13.sp, color = pal.mut)
}

fun friendly(e: Exception): String = when (e) {
    is kotlinx.coroutines.CancellationException -> "Se interrumpió. Inténtalo de nuevo."
    is Offline -> "Sin conexión. Revisa tu internet e inténtalo de nuevo."
    is ApiError -> e.message ?: "El servidor respondió con error ${e.status}."
    else -> "Error en la app: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}"
}

/** Entrar, crear cuenta, recuperar contraseña y términos (igual que authView() en la web). */
@Composable
fun AuthScreen(st: AppState) {
    var mode by remember { mutableStateOf("login") }
    var back by remember { mutableStateOf("login") }
    var regOpen by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var accept by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf(guessCountry()) }
    var bizName by remember { mutableStateOf("") }
    var bizType by remember { mutableStateOf("comercio") }
    var bizProf by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val p = pal

    LaunchedEffect(Unit) { regOpen = runCatching { st.repo.status().bool("registration_open") == true }.getOrDefault(false) }

    fun submit(block: suspend () -> Unit) {
        busy = true; error = null
        st.scope.launch {
            try { block() } catch (e: Exception) { error = friendly(e) }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(top = 56.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mode == "legal") {
                Title("Términos y privacidad", 28.sp)
                LegalText()
                Btn("Volver", { mode = back })
            } else {
            Title("Quincena VE 🇻🇪", 36.sp)
            Mut(when (mode) {
                "registro" -> "Crea tu cuenta gratis."
                "negocio" -> "Crea la cuenta de tu negocio: ventas o cobros, fiado, clientes y caja en \$ y Bs. Para comercios, emprendedores y profesionales. Gratis."
                "olvide" -> "Te enviaremos un enlace para elegir una contraseña nueva."
                else -> "Tus finanzas, solo para ti."
            })
            when (mode) {
                "registro", "negocio" -> {
                    if (mode == "negocio") {
                        Field("Nombre del negocio o de tu oficina", bizName, { bizName = it.take(80) }, placeholder = "Bodega Ana, Escritorio Pérez…")
                        Select("¿A qué te dedicas?", BIZ_TYPES.entries.map { it.key to it.value }, bizType) { bizType = it }
                        if (bizType == "profesional") Select("Tu profesión", BIZ_PROFS.entries.map { it.key to it.value }, bizProf) { bizProf = it }
                    }
                    Field(if (mode == "negocio") "Tu usuario" else "Usuario", user, { user = it })
                    Field("Correo", email, { email = it }, kind = KeyboardType.Email)
                    Select("¿En qué país vives?", COUNTRIES.map { it.code to it.name }, country) { country = it }
                    Field("Contraseña (mínimo 12 caracteres)", pass, { pass = it }, password = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(accept, { accept = it }, colors = CheckboxDefaults.colors(checkedColor = p.g))
                        Text("Acepto los términos y la política de privacidad", Modifier.weight(1f))
                    }
                }
                "olvide" -> Field("Correo", email, { email = it }, kind = KeyboardType.Email)
                else -> {
                    Field("Usuario", user, { user = it })
                    Field("Contraseña", pass, { pass = it }, password = true)
                }
            }
            error?.let { Text(it, color = p.bad, fontWeight = FontWeight.SemiBold) }
            // Render (plan gratis) se duerme: avisa si tarda, para que nadie piense que falló.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(busy) { slow = false; if (busy) { kotlinx.coroutines.delay(4000); slow = true } }
            if (busy && slow) Mut("Conectando… la primera vez puede tardar hasta un minuto mientras el servidor despierta.")
            when (mode) {
                "registro", "negocio" -> Btn(if (mode == "negocio") "Crear la cuenta del negocio" else "Crear mi cuenta", {
                    when {
                        mode == "negocio" && bizName.isBlank() -> error = "Escribe el nombre del negocio."
                        user.isBlank() || email.isBlank() -> error = "Completa usuario y correo."
                        pass.length < 12 -> error = "Usa una contraseña de al menos 12 caracteres."
                        pass.lowercase().contains(user.trim().lowercase()) -> error = "La contraseña no debe contener tu nombre de usuario."
                        !accept -> error = "Debes aceptar los términos y la política de privacidad para crear tu cuenta."
                        else -> submit {
                            st.repo.register(user, email, pass, country, if (mode == "negocio") bizName else "", bizType, bizProf)
                            if (mode == "negocio") { st.tab = "negocio"; st.bizSub = "hoy" }
                        }
                    }
                }, enabled = !busy)
                "olvide" -> Btn("Enviarme el enlace", {
                    if (email.isBlank()) error = "Escribe tu correo."
                    else submit {
                        st.repo.forgot(email); mode = "login"
                        st.toast("Si el correo existe, te enviamos un enlace. Ábrelo en este teléfono para elegir tu contraseña.")
                    }
                }, enabled = !busy)
                else -> Btn("Entrar", { submit { st.repo.login(user, pass); pass = "" } }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty())
            }
            Column(horizontalAlignment = Alignment.Start) {
                if (mode == "login") {
                    Link("¿Olvidaste tu contraseña?", { error = null; mode = "olvide" })
                    if (regOpen) Link("Crear una cuenta", { error = null; mode = "registro" })
                    if (regOpen) Link("¿Tienes un negocio? Crea su cuenta", { error = null; mode = "negocio" })
                } else {
                    if (mode == "registro") Link("¿Tienes un negocio? Crea su cuenta", { error = null; mode = "negocio" })
                    if (mode == "negocio") Link("¿Es para ti? Cuenta personal", { error = null; mode = "registro" })
                    Link(if (mode == "registro" || mode == "negocio") "Ya tengo cuenta" else "Volver", { error = null; mode = "login" })
                }
                Link("Leer términos y privacidad", { back = mode; mode = "legal" })
            }
            Text(copyright(), Modifier.fillMaxWidth(), fontSize = 12.sp, color = p.mut, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Nueva contraseña desde el enlace del correo (igual que #reset= en la web). */
@Composable
fun ResetScreen(st: AppState, token: String, onDone: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val p = pal
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(top = 56.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Title("Quincena VE 🇻🇪", 36.sp)
            Mut("Elige tu contraseña nueva. Al guardarla se cerrará la sesión en todos tus teléfonos.")
            Field("Contraseña nueva (mínimo 12 caracteres)", pass, { pass = it }, password = true)
            Field("Repite la contraseña", pass2, { pass2 = it }, password = true)
            error?.let { Text(it, color = p.bad, fontWeight = FontWeight.SemiBold) }
            Btn("Guardar contraseña", {
                when {
                    pass.length < 12 -> error = "Usa una contraseña de al menos 12 caracteres."
                    pass != pass2 -> error = "Las contraseñas no coinciden."
                    else -> {
                        busy = true; error = null
                        st.scope.launch {
                            try {
                                st.repo.resetPassword(token, pass)
                                onDone()
                                st.toast("¡Listo! Entra con tu contraseña nueva.")
                            } catch (e: Exception) { error = friendly(e) }
                            busy = false
                        }
                    }
                }
            }, enabled = !busy)
            Link("Cancelar", onDone)
        }
    }
}
