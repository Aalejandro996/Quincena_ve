package app.quincena

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Datos del usuario guardados en el teléfono (cifrados). Se editan sin conexión y se sincronizan con el
 * mismo protocolo de la web: se envían los datos, la copia base y la revisión, y el servidor mezcla.
 */
data class AppUpdate(val version: String, val notes: String, val url: String, val required: Boolean = false)

class Repo(ctx: Context) {
    private val store = SecureStore(ctx)
    val api = Api(store)
    private val sync = Mutex()

    private fun load(name: String): JsonObject? =
        store.get(name)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** Datos actuales. Es estado de Compose: las pantallas se redibujan solas al cambiar. */
    private var dataState by mutableStateOf(load("data") ?: JsonObject(emptyMap()))
    var data: JsonObject
        get() = dataState
        private set(v) { dataState = v; Loc.set(v.str("country")) }
    init { Loc.set(dataState.str("country")) }
    private var base: JsonObject = load("base") ?: data
    private var rev: Int = store.get("rev")?.toIntOrNull() ?: 0

    var loggedIn by mutableStateOf(api.token != null)
        private set
    var username by mutableStateOf(store.get("username") ?: "")
        private set
    /** Negocio Plus ("plus" o "free") y cuenta de vendedor (solo registra ventas del negocio de su dueño). */
    var plan by mutableStateOf(store.get("plan") ?: "free")
        private set
    var seller by mutableStateOf(store.get("seller") == "1")
        private set
    var business by mutableStateOf(store.get("business") ?: "")
        private set
    val plus get() = plan == "plus"

    private fun adoptUser(u: JsonObject?) {
        if (u == null) return
        plan = u.str("plan") ?: "free"; seller = u.bool("seller") == true; business = u.str("business") ?: ""
        store.put("plan", plan); store.put("seller", if (seller) "1" else null); store.put("business", business)
    }

    /** Vuelve a leer el plan y si es vendedor (el administrador pudo activar el Plus). */
    suspend fun refreshAccount() {
        val r = runCatching { api.call("GET", "/api/status") }.getOrNull() ?: return
        if (r.bool("authenticated") == true) adoptUser(r["user"] as? JsonObject)
    }

    suspend fun sellers(): JsonObject = api.call("GET", "/api/biz/sellers")
    suspend fun createSeller(user: String, pass: String) { api.call("POST", "/api/biz/sellers", buildJsonObject { put("username", user); put("password", pass) }) }
    suspend fun updateSeller(id: Int, body: JsonObject) { api.call("PATCH", "/api/biz/sellers/$id", body) }
    suspend fun sellerHome(): JsonObject = api.call("GET", "/api/biz/seller")
    suspend fun sellerSale(sale: JsonObject): JsonObject = api.call("POST", "/api/biz/seller/sale", sale)["sale"]!!.jsonObject

    private fun persist() { store.put("data", data.toString()); store.put("base", base.toString()); store.put("rev", rev.toString()) }

    /** Toma la respuesta del servidor. Si hubo cambios locales mientras viajaba, se conservan para el próximo envío. */
    private fun adopt(r: JsonObject, sent: JsonObject? = null) {
        val server = r["data"]!!.jsonObject
        base = server
        rev = r["revision"]!!.jsonPrimitive.int
        if (sent == null || data == sent) data = server
        persist()
    }

    private fun setToken(t: String?) { api.token = t; loggedIn = t != null }

    // ---------- Cuenta ----------

    suspend fun status(): JsonObject = api.call("GET", "/api/status")

    suspend fun login(user: String, pass: String) {
        val r = api.call("POST", "/api/mobile/login", buildJsonObject { put("username", user.trim().lowercase()); put("password", pass); put("device", Build.MODEL) })
        api.token = r["token"]!!.jsonPrimitive.content
        username = r["user"]?.jsonObject?.str("username") ?: user.trim().lowercase()
        store.put("username", username)
        adoptUser(r["user"] as? JsonObject)
        try { adopt(api.call("GET", "/api/state")) } catch (e: Exception) { api.token = null; throw e }
        loggedIn = true
        sync.withLock { if (runMaintenance()) guard { push() }; Unit }
    }

    /** Crea la cuenta (si el servidor tiene el registro abierto) y entra. */
    suspend fun register(user: String, email: String, pass: String, country: String = "VE", businessName: String = "", businessType: String = "", profession: String = "") {
        api.call("POST", "/api/register", buildJsonObject {
            put("username", user.trim().lowercase()); put("email", email.trim()); put("password", pass); put("accept_terms", true); put("country", country)
            if (businessName.isNotBlank()) { put("business_name", businessName.trim()); put("business_type", businessType); put("profession", profession) }
        })
        login(user, pass)
        // Al crear la cuenta ya aceptó los términos vigentes.
        edit { it["termsVersion"] = JsonPrimitive(TERMS_VERSION) }
    }

    suspend fun forgot(email: String) { api.call("POST", "/api/forgot-password", buildJsonObject { put("email", email.trim()) }) }

    /** Confirma el correo con el código del enlace que llegó por correo. */
    suspend fun verifyEmail(token: String) { api.call("POST", "/api/verify-email", buildJsonObject { put("token", token) }) }

    /** Elige una contraseña nueva con el enlace del correo. El servidor cierra la sesión en todos los teléfonos. */
    suspend fun resetPassword(token: String, new: String) {
        if (loggedIn) refresh()  // antes de que se cierre la sesión, sube lo que esté pendiente
        api.call("POST", "/api/reset-password", buildJsonObject { put("token", token); put("new_password", new) })
        // Si la sesión de este teléfono era de esa cuenta, el servidor ya la cerró: se borra la copia local.
        if (loggedIn && (runCatching { api.call("GET", "/api/email-status") }.exceptionOrNull() as? ApiError)?.status == 401) clearLocal()
    }

    suspend fun emailStatus(): Pair<String?, Boolean>? = runCatching {
        val r = api.call("GET", "/api/email-status")
        r.str("email") to (r.bool("verified") == true)
    }.getOrNull()

    suspend fun resendVerification() { api.call("POST", "/api/resend-verification") }
    suspend fun sendSuggestion(text: String, kind: String) {
        api.call("POST", "/api/suggestions", buildJsonObject { put("text", text); put("kind", kind); put("platform", "android") })
    }

    /** Cambia la contraseña. El servidor cierra la sesión en los demás teléfonos (este sigue abierto). */
    suspend fun changePassword(old: String, new: String) {
        api.call("POST", "/api/change-password", buildJsonObject { put("old_password", old); put("new_password", new) })
    }

    /** Cierra la sesión en los demás teléfonos. Devuelve cuántos se cerraron. */
    suspend fun revokeOthers(): Int =
        (api.call("POST", "/api/devices/revoke-others")["revoked"] as? JsonPrimitive)?.intOrNull ?: 0

    /** Registra en el servidor que aceptaste los términos vigentes. */
    suspend fun acceptTerms() { api.call("POST", "/api/accept-terms", buildJsonObject { put("version", TERMS_VERSION) }) }

    suspend fun logout() {
        runCatching { api.call("POST", "/api/mobile/logout") }
        clearLocal()
    }

    suspend fun deleteAccount(password: String) {
        api.call("DELETE", "/api/account", buildJsonObject { put("password", password) })
        clearLocal()
    }

    private fun clearLocal() {
        setToken(null)
        listOf("data", "base", "rev", "username", "plan", "seller", "business").forEach { store.put(it, null) }
        data = JsonObject(emptyMap()); base = data; rev = 0; username = ""; plan = "free"; seller = false; business = ""
    }

    // ---------- Sincronización ----------

    /** Trae lo último del servidor (o envía lo pendiente). Devuelve un mensaje para mostrar, o null si todo salió bien. */
    suspend fun refresh(): String? = sync.withLock {
        guard {
            if (data == base) adopt(api.call("GET", "/api/state")) else push()
            if (runMaintenance()) push()
        }
    }

    private fun runMaintenance(): Boolean {
        val m = data.toMutableMap()
        if (!maintain(m)) return false
        data = JsonObject(m); persist()
        return true
    }

    private suspend fun push() {
        val sent = data
        adopt(api.call("PUT", "/api/state", buildJsonObject { put("data", sent); put("base_data", base); put("base_revision", rev) }), sent)
    }

    private suspend fun guard(block: suspend () -> Unit): String? = try { block(); null }
    catch (e: Offline) { "Sin conexión. Guardaremos tus cambios cuando vuelva internet." }
    catch (e: ApiError) {
        if (e.status == 401) { clearLocal(); "Tu sesión terminó. Entra de nuevo." }
        else {
            runCatching { adopt(api.call("GET", "/api/state")) }
            if (e.status >= 500) "Algo salió mal. Inténtalo en unos minutos." else e.message
        }
    }

    /** Cambia los datos en el teléfono (funciona sin conexión) y trata de sincronizar. */
    suspend fun edit(change: (MutableMap<String, JsonElement>) -> Unit): String? {
        val m = data.toMutableMap()
        change(m)
        data = JsonObject(m); persist()
        return sync.withLock { guard { push() } }
    }

    /** Actualiza el tipo de cambio del país (BCV en Venezuela). Devuelve el texto para mostrar junto a la tasa. */
    /** [force] = botón Actualizar: el servidor vuelve a consultar las fuentes (como mucho una vez por minuto). */
    suspend fun updateRate(force: Boolean = false): String = try {
        val r = api.call("GET", "/api/rate?country=" + Loc.code + if (force) "&force=1" else "")
        val rate = r["rate"]!!.jsonPrimitive.double
        val market = (r["parallel"] as? JsonPrimitive)?.doubleOrNull
        val euro = (r["euro"] as? JsonPrimitive)?.doubleOrNull
        val today = java.time.LocalDate.now().toString()
        val last = data.arr("rateHistory").lastOrNull()
        val histOk = last != null && last.str("d") == today && last.num("b") == rate && (market == null || last.num("m") == market)
        if (rate != rateOf(data) || (market != null && market != marketRateOf(data)) || (euro != null && euro != data.num("euroRate")) || !histOk) edit {
            it["exchangeRate"] = JsonPrimitive(rate)
            if (market != null) it["marketRate"] = JsonPrimitive(market)
            if (euro != null) it["euroRate"] = JsonPrimitive(euro)
            addRateHistory(it, rate, market)
        }
        val day = dateOf(r.str("updated_at"))
        if (day != null) "actualizada " + shortDate(day) else "actualizada"
    } catch (e: Exception) { "sin actualizar" }

    /** Pregunta al servidor si hay una versión más nueva de la app que la instalada. */
    suspend fun checkUpdate(): AppUpdate? = runCatching {
        val r = api.call("GET", "/api/android-app")
        val code = (r["versionCode"] as? JsonPrimitive)?.intOrNull ?: 0
        val min = (r["minVersionCode"] as? JsonPrimitive)?.intOrNull ?: 0
        if (r.bool("available") == true && code > BuildConfig.VERSION_CODE)
            AppUpdate(r.str("version") ?: "nueva", r.str("notes") ?: "", BuildConfig.API_BASE + (r.str("url") ?: "/descargar/android"),
                required = min > BuildConfig.VERSION_CODE)
        else null
    }.getOrNull()

    // ---------- Preferencias del teléfono ----------

    fun pref(k: String): String? = store.get("pref_$k")
    fun setPref(k: String, v: String?) = store.put("pref_$k", v)
}
