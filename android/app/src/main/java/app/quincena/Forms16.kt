package app.quincena

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

// Formularios de la 1.6 (los mismos de la web).

@Composable
private fun Title16(t: String) = Title(t, 28.sp, pal.ink)

@Composable
private fun Footer16(st: AppState, onSave: () -> Unit, label: String = "Guardar") {
    Btn(label, onSave)
    Btn("Cancelar", { st.close() }, outline = true)
}

private fun pos16(s: String) = parseMoney(s)?.takeIf { it > 0 }
private fun AppState.bad16() = toast("Escribe un monto mayor a cero.")

/** "¿De qué cuenta salió?" — solo aparece si tienes cuentas. */
@Composable
fun AccSelect(st: AppState, value: String, label: String = "¿De qué cuenta salió?", onChange: (String) -> Unit) {
    val accs = accounts(st.repo.data)
    if (accs.isEmpty()) return
    Select(label, listOf("" to "Sin cuenta") + accs.map { it.id to accLabel(it) }, value, onChange)
}

// ---------- Ingresos recibidos ----------

@Composable
fun CobroForm(st: AppState, id: String) {
    val s = st.repo.data.arr("incomeSources").firstOrNull { it.id == id } ?: return
    var amount by remember { mutableStateOf(inputMoney(s.num("amount"))) }
    var cur by remember { mutableStateOf(if (s.str("currency") == "BS") "BS" else "USD") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var acc by remember { mutableStateOf("") }
    Title16("Me pagaron")
    Mut(s.str("name") ?: "Ingreso")
    MoneyField("Monto recibido", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    DateField("Fecha", date, { date = it })
    AccSelect(st, acc, "¿A qué cuenta entró?") { acc = it }
    Footer16(st, {
        val a = pos16(amount) ?: return@Footer16 st.bad16()
        st.close()
        st.act("Guardado") { m ->
            m.addFirst("incomes", row("id" to newId(), "date" to date, "amount" to a, "currency" to cur, "name" to (s.str("name") ?: "Ingreso"),
                "sourceId" to s.id, "accountId" to acc, "kind" to "sueldo"))
        }
    })
}

@Composable
fun IngresoExtraForm(st: AppState, id: String?) {
    val x = st.repo.data.arr("incomes").firstOrNull { it.id == id }
    var name by remember { mutableStateOf(x?.str("name") ?: "") }
    var amount by remember { mutableStateOf(inputMoney(x?.num("amount"))) }
    var cur by remember { mutableStateOf(if (x?.str("currency") == "BS") "BS" else "USD") }
    var date by remember { mutableStateOf(localDay(x?.str("date"))?.toString() ?: LocalDate.now().toString()) }
    var acc by remember { mutableStateOf(x?.str("accountId") ?: "") }
    Title16(if (x == null) "Ingreso extra" else "Editar ingreso")
    Field(if (x == null) "¿De dónde viene?" else "Nombre", name, { name = it }, placeholder = "Trabajo extra, venta, regalo…")
    MoneyField("Monto", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    DateField("Fecha", date, { date = it })
    AccSelect(st, acc, "¿A qué cuenta entró?") { acc = it }
    if (x != null) OutlineDanger("Eliminar este ingreso") { st.close(); st.act("Ingreso eliminado", undo = true) { it.removeRow("incomes", x.id) } }
    Footer16(st, {
        val a = pos16(amount) ?: return@Footer16 st.bad16()
        val nm = name.trim().ifEmpty { if (x == null) "Ingreso extra" else "Ingreso" }
        st.close()
        st.act("Guardado") { m ->
            if (x == null) m.addFirst("incomes", row("id" to newId(), "date" to date, "amount" to a, "currency" to cur, "name" to nm,
                "sourceId" to "", "accountId" to acc, "kind" to "extra"))
            else m.updateRow("incomes", x.id) { it.plusFields("name" to nm, "amount" to a, "currency" to cur, "date" to date, "accountId" to acc) }
        }
    })
}

// ---------- Cuentas ----------

@Composable
fun CuentaForm(st: AppState, id: String?) {
    val d = st.repo.data
    val a = accById(d, id)
    var name by remember { mutableStateOf(a?.str("name") ?: "") }
    var kind by remember { mutableStateOf(a?.str("kind") ?: "banco") }
    var cur by remember { mutableStateOf(if (a?.str("currency") == "BS") "BS" else "USD") }
    var amount by remember { mutableStateOf(if (a != null) inputMoney(Math.round(accBalance(d, a) * 100) / 100.0) else "") }
    var adjustmentDate by remember { mutableStateOf(LocalDate.now().toString()) }
    Title16(if (a == null) "Nueva cuenta" else "Editar cuenta")
    Field("Nombre", name, { name = it }, placeholder = "Banesco, Efectivo, Binance…")
    Select("Tipo", ACC_KINDS.toList(), kind) { kind = it }
    if (a == null) CurrencySelect(cur) { cur = it }
    MoneyField(if (a == null) "¿Cuánto tienes hoy?" else "Saldo actual (${if (cur == "BS") "Bs." else "$"})", amount) { amount = it }
    if (a == null) Mut("Para USDT usa Dólares: se cuenta 1 a 1.") else {
        DateField("Fecha del ajuste", adjustmentDate, { adjustmentDate = it })
        Mut("La diferencia se registra como ingreso o gasto en esta cuenta.")
    }
    if (a != null) OutlineDanger("Quitar esta cuenta") {
        st.close(); st.act("Cuenta quitada", undo = true) { m -> m.updateRow("accounts", a.id) { it.plusFields("archived" to true) } }
    }
    Footer16(st, {
        if (name.isBlank()) return@Footer16 st.toast("Escribe un nombre para la cuenta.")
        val want = parseMoney(amount)
        val parsedAdjustmentDate = dateOf(adjustmentDate)
        if (a != null && (parsedAdjustmentDate == null || parsedAdjustmentDate.isAfter(LocalDate.now())))
            return@Footer16 st.toast("Elige una fecha válida, de hoy o anterior.")
        val delta = if (a != null && want != null) want - accBalance(d, a) else 0.0
        if (a != null && delta < -.005 && -delta > accBalance(d, a) + .005)
            return@Footer16 st.toast("El saldo no alcanza para registrar esta disminución como gasto.")
        st.close()
        st.act("Guardado") { m ->
            if (a == null) m.setRows("accounts", m.rows("accounts") + row("id" to newId(), "name" to name.trim(), "kind" to kind, "currency" to cur,
                "initial" to (want ?: 0.0), "archived" to false))
            else m.updateRow("accounts", a.id) {
                it.plusFields("name" to name.trim(), "kind" to kind)
            }
            if (a != null && abs(delta) > .005) {
                val id = newId()
                if (delta > 0) m.addFirst("incomes", row("id" to id, "date" to adjustmentDate, "amount" to delta, "currency" to cur,
                    "name" to "Ajuste de saldo: ${name.trim()}", "sourceId" to "", "accountId" to a.id, "kind" to "extra"))
                else m.addFirst("expenses", row("id" to id, "amount" to -delta, "currency" to cur, "category" to "fijo", "description" to "Ajuste de saldo: ${name.trim()}",
                    "method" to "banco", "bank" to "", "date" to adjustmentDate, "accountId" to a.id))
            }
        }
    })
}

@Composable
fun TransferForm(st: AppState) {
    val d = st.repo.data
    val accs = accounts(d)
    var from by remember { mutableStateOf(accs.firstOrNull()?.id ?: "") }
    var to by remember { mutableStateOf(accs.getOrNull(1)?.id ?: "") }
    var amount by remember { mutableStateOf("") }
    var amountTo by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    val opts = accs.map { it.id to accLabel(it) }
    val fa = accById(d, from)
    val ta = accById(d, to)
    Title16("Mover o cambiar dinero")
    Select("Sale de", opts, from) { from = it }
    MoneyField("Monto que sale", amount) { amount = it }
    Select("Llega a", opts, to) { to = it }
    if (fa != null && ta != null && fa.str("currency") != ta.str("currency"))
        MoneyField("Monto que llega (${if (ta.str("currency") == "BS") "Bs." else "$"})", amountTo) { amountTo = it }
    DateField("Fecha", date, { date = it })
    Field("Nota (opcional)", note, { note = it }, placeholder = "Vendí USDT, retiro…")
    Mut("Si cambias $ por Bs., esa tasa queda guardada y la puedes usar al registrar gastos (\"Tu cambio\").")
    Footer16(st, {
        val a = pos16(amount) ?: return@Footer16 st.bad16()
        if (from == to || fa == null || ta == null) return@Footer16 st.toast("Elige dos cuentas distintas.")
        if (a > accBalance(d, fa) + .005) return@Footer16 st.toast("La cuenta «${fa.str("name") ?: "Cuenta"}» no tiene saldo suficiente para mover ese monto.")
        val t = pos16(amountTo)
        st.close()
        st.act("Guardado") { m ->
            m.addFirst("transfers", row("id" to newId(), "date" to date, "fromId" to from, "toId" to to, "amountFrom" to a,
                "amountTo" to (t ?: conv(m.data(), a, fa.str("currency"), ta.str("currency"))), "note" to note.trim()))
        }
    })
}

// ---------- Me deben ----------

@Composable
fun PrestamoForm(st: AppState, id: String?) {
    val x = st.repo.data.arr("receivables").firstOrNull { it.id == id }
    var person by remember { mutableStateOf(x?.str("person") ?: "") }
    var amount by remember { mutableStateOf(inputMoney(x?.num("amount"))) }
    var cur by remember { mutableStateOf(if (x?.str("currency") == "BS") "BS" else "USD") }
    var date by remember { mutableStateOf(x?.str("date")?.take(10) ?: LocalDate.now().toString()) }
    var due by remember { mutableStateOf(x?.str("dueDate") ?: "") }
    var note by remember { mutableStateOf(x?.str("note") ?: "") }
    var acc by remember { mutableStateOf(x?.str("accountId") ?: "") }
    Title16(if (x == null) "Anotar un préstamo" else "Editar préstamo")
    Field("¿A quién le prestaste?", person, { person = it })
    MoneyField(if (x == null) "Monto" else "Monto prestado", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    if (x == null) DateField("Fecha del préstamo", date, { date = it })
    DateField("¿Para cuándo te paga? (opcional)", due, { due = it }, optional = true)
    Field("Nota (opcional)", note, { note = it })
    if (x == null) AccSelect(st, acc, "¿De qué cuenta salió el dinero?") { acc = it }
    if (x != null) OutlineDanger("Eliminar este préstamo") { st.close(); st.act("Préstamo eliminado", undo = true) { it.removeRow("receivables", x.id) } }
    Footer16(st, {
        if (person.isBlank()) return@Footer16 st.toast("Escribe a quién le prestaste.")
        val a = pos16(amount) ?: return@Footer16 st.bad16()
        if (x != null && a < x.num("paid") - .01) return@Footer16 st.toast("El monto no puede ser menor a lo que ya te pagó.")
        st.close()
        st.act("Guardado") { m ->
            if (x == null) m.addFirst("receivables", row("id" to newId(), "person" to person.trim(), "amount" to a, "paid" to 0, "currency" to cur,
                "date" to date, "dueDate" to due, "note" to note.trim(), "accountId" to acc))
            else m.updateRow("receivables", x.id) { it.plusFields("person" to person.trim(), "amount" to a, "currency" to cur, "dueDate" to due, "note" to note.trim()) }
        }
    })
}

@Composable
fun MePagoForm(st: AppState, id: String) {
    val x = st.repo.data.arr("receivables").firstOrNull { it.id == id } ?: return
    val left = x.num("amount") - x.num("paid")
    var amount by remember { mutableStateOf(inputMoney(Math.round(left * 100) / 100.0)) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var acc by remember { mutableStateOf("") }
    Title16("${x.str("person") ?: ""} te pagó")
    MoneyField("Monto", amount) { amount = it }
    DateField("Fecha", date, { date = it })
    AccSelect(st, acc, "¿A qué cuenta entró?") { acc = it }
    Footer16(st, {
        val a = pos16(amount) ?: return@Footer16 st.bad16()
        if (a > left + .01) return@Footer16 st.toast("Es más de lo que te debe. Baja el monto.")
        st.close()
        st.act("Guardado") { m ->
            m.updateRow("receivables", x.id) { it.plusFields("paid" to min(it.num("amount"), it.num("paid") + a)) }
            m.addFirst("incomes", row("id" to newId(), "date" to date, "amount" to a, "currency" to (x.str("currency") ?: "USD"),
                "name" to "Pago de ${x.str("person") ?: ""}", "sourceId" to "", "accountId" to acc, "kind" to "cobro"))
        }
    })
}

// ---------- Contraseña ----------

@Composable
fun ClaveForm(st: AppState) {
    var old by remember { mutableStateOf("") }
    var nueva by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Title16("Cambiar contraseña")
    Field("Contraseña actual", old, { old = it }, password = true)
    Field("Contraseña nueva (mínimo 12 caracteres)", nueva, { nueva = it }, password = true)
    Field("Repite la nueva", conf, { conf = it }, password = true)
    Mut("Al cambiarla, cerramos la sesión en tus otros teléfonos y en la web.")
    Btn("Cambiar contraseña", {
        when {
            nueva.length < 12 -> st.toast("Usa una contraseña de al menos 12 caracteres.")
            nueva != conf -> st.toast("Las contraseñas nuevas no coinciden.")
            else -> {
                busy = true
                st.scope.launch {
                    try { st.repo.changePassword(old, nueva); st.close(); st.toast("Contraseña cambiada. Cerramos tu sesión en los otros teléfonos.") }
                    catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") }
                    catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
                    busy = false
                }
            }
        }
    }, enabled = !busy && old.isNotEmpty() && nueva.isNotEmpty())
    Btn("Cancelar", { st.close() }, outline = true)
}
