package app.quincena

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Ventanas que suben desde abajo, como .sheet en la web. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(st: AppState) {
    val s = st.sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { st.close() }, sheetState = state, containerColor = pal.bg.copy(alpha = .96f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        key(s) {
            Column(
                Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (s.kind) {
                    "gasto", "editar" -> GastoForm(st, s.arg)
                    "ingreso", "editari" -> IngresoForm(st, s.arg)
                    "deuda", "editard" -> DeudaForm(st, s.arg)
                    "abono" -> AbonoForm(st, s.arg!!)
                    "pago", "editarp" -> PagoForm(st, s.arg)
                    "presupuesto" -> PresupuestoForm(st)
                    "plan" -> PlanForm(st)
                    "recurrente", "editarr" -> RecurrenteForm(st, s.arg)
                    "compromiso", "editarc" -> CompromisoForm(st, s.arg)
                    "cpago" -> CPagoForm(st, s.arg!!)
                    "rpago" -> RPagoForm(st, s.arg!!)
                    "sugerencia" -> SugerenciaForm(st)
                    "cuotas" -> CuotasForm(st)
                    "bolsa" -> BolsaForm(st)
                    "pais" -> PaisForm(st)
                    "caja" -> CajaSheet(st)
                    "info:tasas", "info:poder", "info:camino", "info:pres" -> InfoSheet(st, s.kind.removePrefix("info:"))
                    "cobro" -> CobroForm(st, s.arg!!)
                    "ingresox", "editin" -> IngresoExtraForm(st, s.arg)
                    "cuenta", "editacc" -> CuentaForm(st, s.arg)
                    "transfer" -> TransferForm(st)
                    "prestamo", "editrec" -> PrestamoForm(st, s.arg)
                    "mepago" -> MePagoForm(st, s.arg!!)
                    "clave" -> ClaveForm(st)
                    "borrar" -> BorrarForm(st)
                    "legal" -> LegalSheet(st)
                    "bizconf" -> BizConfForm(st)
                    "bizventa" -> VentaForm(st, s.arg)
                    "bizgasto" -> BizGastoForm(st)
                    "bizretiro" -> BizRetiroForm(st)
                    "bizcierre" -> BizCierreForm(st)
                    "bizabono" -> BizAbonoForm(st, s.arg!!)
                    "bizprod" -> BizProdForm(st, s.arg)
                    "bizvend" -> BizVendForm(st)
                    "bizvpass" -> BizVPassForm(st, s.arg!!)
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(t: String) = Title(t, 28.sp, pal.ink)

@Composable
private fun Footer(st: AppState, onSave: () -> Unit, label: String = "Guardar", enabled: Boolean = true) {
    Btn(label, onSave, enabled = enabled)
    Btn("Cancelar", { st.close() }, outline = true)
}

private fun AppState.badAmount() = toast("Escribe un monto mayor a cero.")
private fun pos(s: String) = parseMoney(s)?.takeIf { it > 0 }

// ---------- Gasto ----------

@Composable
fun GastoForm(st: AppState, id: String?) {
    val e = st.repo.data.arr("expenses").firstOrNull { it.id == id }
    // Gasto detectado en una notificación del banco: llega con el monto y la nota ya escritos.
    val det = st.detected.firstOrNull { "det:" + it.id == id }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var amount by remember { mutableStateOf(inputMoney(e?.num("amount") ?: det?.amount)) }
    // Un gasto nuevo arranca con la última moneda, categoría y tasa que usaste.
    var cur by remember { mutableStateOf(e?.str("currency") ?: det?.currency ?: st.repo.pref("last_cur") ?: "USD") }
    var cat by remember { mutableStateOf(e?.str("category") ?: st.repo.pref("last_cat")?.takeIf { it in CATS } ?: "comida") }
    var note by remember { mutableStateOf(e?.str("description") ?: det?.note ?: "") }
    var rateType by remember { mutableStateOf(e?.str("rateType") ?: (if (e == null) st.repo.pref("last_rt") else null) ?: "bcv") }
    var rateOther by remember { mutableStateOf(if (e?.str("rateType") == "otra") inputMoney(e?.num("rateUsed")) else "") }
    var acc by remember { mutableStateOf(e?.str("accountId") ?: st.repo.pref("last_acc")?.takeIf { accById(st.repo.data, it) != null } ?: "") }
    val origDay = e?.let { localDay(it) }?.toString() ?: ""
    // Pago mixto (solo al crear): partes con método, monto, moneda y cuenta.
    var mixed by remember { mutableStateOf(false) }
    val mm = remember { mutableStateListOf("pago_movil", "efectivo", "banco") }
    val ma = remember { mutableStateListOf("", "", "") }
    val mc = remember { mutableStateListOf("BS", "USD", "USD") }
    val macc = remember { mutableStateListOf("", "", "") }
    var day by remember { mutableStateOf(origDay.ifEmpty { LocalDate.now().toString() }) }
    SheetTitle(if (e == null) "Nuevo gasto" else "Editar gasto")
    if (!mixed) {
        MoneyField("Monto", amount) { amount = it }
        CurrencySelect(cur) { cur = it }
        if (cur == "BS") RatePicker(st.repo.data, rateType, { rateType = it }, rateOther, { rateOther = it })
    }
    if (e == null) Link(if (mixed) "Pagué con un solo método" else "¿Pagaste con varios métodos? Divide el pago", { mixed = !mixed })
    if (mixed) Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 2.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        B("¿Cómo pagaste?")
        Mut("Ej.: parte por pago móvil y parte en efectivo en dólares. Deja vacías las filas que no uses.")
        (0..2).forEach { j ->
            Select("Método ${j + 1}", EXP_METHODS.entries.map { it.key to it.value }, mm[j]) { mm[j] = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { MoneyField("Monto", ma[j]) { ma[j] = it } }
                Box(Modifier.weight(1f)) { CurrencySelect(mc[j]) { mc[j] = it } }
            }
            if (st.repo.data.arr("accounts").any { it.bool("archived") != true }) AccSelect(st, macc[j], "Cuenta (opcional)") { macc[j] = it }
        }
        val tot = (0..2).sumOf { j -> parseMoney(ma[j])?.takeIf { it > 0 }?.let { usdOf(st.repo.data, it, mc[j]) } ?: 0.0 }
        B("Total: ${st.fmt.show(tot)}")
    }
    DateField("Fecha", day, { day = it })
    CategoryChips(cat) { cat = it }
    AccSelect(st, acc) { acc = it }
    Field(if (e == null) "Nota (opcional)" else "Nota", note, { note = it })
    if (e != null) OutlineDanger("Eliminar este gasto") { st.close(); st.act("Eliminado", undo = true) { it.removeRow("expenses", e.id) } }
    Footer(st, {
        val chosen = dateOf(day) ?: LocalDate.now()
        if (mixed) {
            val parts = (0..2).mapNotNull { j -> parseMoney(ma[j])?.takeIf { it > 0 }?.let { j to it } }
            if (parts.size < 2) return@Footer st.toast("Escribe al menos dos montos para dividir el pago.")
            if (chosen.isAfter(LocalDate.now())) return@Footer st.toast("La fecha no puede ser futura.")
            val iso = if (chosen == LocalDate.now()) nowIso() else chosen.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
            val gid = newId()
            st.repo.setPref("last_cat", cat)
            st.close()
            st.act("Gasto registrado en ${parts.size} pagos") { m ->
                parts.forEachIndexed { i, (j, a) ->
                    var rf = rateFields(m.data(), mc[j], "bcv", "")
                    if (chosen != LocalDate.now()) rf = dayRates(m.data(), chosen.toString(), rf)
                    m.addFirst("expenses", row("id" to "${gid}_p$i", "groupId" to gid, "amount" to a, "currency" to mc[j], "category" to cat, "description" to note.trim(),
                        "method" to mm[j], "bank" to "", "date" to iso, "accountId" to macc[j].ifEmpty { null }).plusFields(*rf))
                }
            }
            quinciAfterGasto(st, cat)
            return@Footer
        }
        val a = pos(amount) ?: return@Footer st.badAmount()
        if (chosen.isAfter(LocalDate.now())) return@Footer st.toast("La fecha no puede ser futura.")
        // Hoy guarda la hora real; otro día, el mediodía de ese día.
        val iso = if (chosen == LocalDate.now()) nowIso() else chosen.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
        val moved = e != null && day != origDay
        if (det != null) { Detected.remove(ctx, det.id); st.detected = Detected.load(ctx) }
        if (e == null) { st.repo.setPref("last_cur", cur); st.repo.setPref("last_cat", cat); st.repo.setPref("last_rt", rateType); st.repo.setPref("last_acc", acc) }
        st.close()
        st.act("Guardado") { m ->
            var rf = rateFields(m.data(), cur, rateType, rateOther)
            // Un gasto en Bs. de otro día usa la tasa BCV de ese día si la tenemos guardada.
            if ((e == null && chosen != LocalDate.now()) || moved) rf = dayRates(m.data(), chosen.toString(), rf)
            if (e == null) m.addFirst("expenses", row("id" to newId(), "amount" to a, "currency" to cur, "category" to cat, "description" to note.trim(),
                "method" to "efectivo", "bank" to "", "date" to iso, "accountId" to acc.ifEmpty { null }).plusFields(*rf))
            else m.updateRow("expenses", e.id) {
                // Al editar se conserva la tasa BCV del día en que se registró el gasto.
                val keepBcv = it.num("bcvRate").takeIf { b -> b > 0 && cur == "BS" && !moved }
                it.plusFields("amount" to a, "currency" to cur, "category" to cat, "description" to note.trim(), "accountId" to acc.ifEmpty { null }, *rf)
                    .let { r -> if (keepBcv != null) r.plusFields("bcvRate" to keepBcv) else r }
                    .let { r -> if (moved) r.plusFields("date" to iso) else r }
            }
        }
        if (e == null) quinciAfterGasto(st, cat)
    })
}

/** Métodos de pago de un gasto (pago mixto), igual que la web. */
val EXP_METHODS = linkedMapOf("pago_movil" to "Pago móvil", "efectivo" to "Efectivo", "banco" to "Transferencia", "tarjeta" to "Tarjeta",
    "punto" to "Punto de venta", "zelle" to "Zelle", "binance" to "Binance")

/** "¿A qué tasa conseguiste esos bolívares?" — BCV, USDT u otra. */
@Composable
fun RatePicker(d: JsonObject, type: String, onType: (String) -> Unit, other: String, onOther: (String) -> Unit) {
    val f = Fmt(false, false, 1.0)
    val market = marketRateOf(d)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("¿A qué tasa conseguiste esos bolívares?", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Chips {
            Chip("BCV · ${f.plain(rateOf(d))}", type == "bcv") { onType("bcv") }
            if (market > 0) Chip("USDT · ${f.plain(market)}", type == "usdt") { onType("usdt") }
            val cambio = lastCambio(d)
            if (cambio > 0) Chip("Tu cambio · ${f.plain(cambio)}", type == "cambio") { onType("cambio") }
            Chip("Otra", type == "otra") { onType("otra") }
        }
        if (type == "otra") Field("Tasa (Bs. por dólar)", other, onOther, placeholder = "0,00", kind = KeyboardType.Decimal)
        Mut("Así calculamos cuánto te costó de verdad en dólares.")
    }
}

// ---------- Ingreso ----------

@Composable
fun IngresoForm(st: AppState, id: String?) {
    val s = st.repo.data.arr("incomeSources").firstOrNull { it.id == id }
    val prevDays = (s?.get("payDays") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
    var name by remember { mutableStateOf(s?.str("name") ?: "") }
    var amount by remember { mutableStateOf(inputMoney(s?.num("amount"))) }
    var cur by remember { mutableStateOf(s?.str("currency") ?: "USD") }
    var freq by remember { mutableStateOf(s?.str("frequency") ?: "quincenal") }
    var day by remember { mutableStateOf(if (s?.str("frequency") == "mensual") prevDays.firstOrNull()?.toString() ?: "" else "") }
    var wday by remember { mutableStateOf(if (s?.str("frequency") == "semanal") (prevDays.firstOrNull() ?: 5).toString() else "5") }
    SheetTitle(if (s == null) "Nuevo ingreso" else "Editar ingreso")
    Field("Nombre", name, { name = it }, placeholder = "Sueldo")
    MoneyField("Monto", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    Select("Frecuencia", listOf("semanal" to "Cada semana", "quincenal" to "Cada quincena", "mensual" to "Una vez al mes", "ocasional" to "Solo una vez"), freq) { freq = it }
    if (freq == "semanal") Select("Día en que cobras", WEEKDAYS, wday) { wday = it }
    if (freq == "mensual") Field("Día del mes en que cobras", day, { day = it.filter(Char::isDigit).take(2) }, placeholder = "1", kind = KeyboardType.Number)
    if (s != null) OutlineDanger("Eliminar este ingreso") { st.close(); st.act("Ingreso eliminado", undo = true) { it.removeRow("incomeSources", s.id) } }
    Footer(st, {
        if (name.isBlank()) return@Footer st.toast("Escribe un nombre, por ejemplo Sueldo.")
        val a = pos(amount) ?: return@Footer st.badAmount()
        val days = when (freq) {
            "semanal" -> listOf((wday.toIntOrNull() ?: 5).coerceIn(1, 7))
            "mensual" -> listOf((day.toIntOrNull() ?: prevDays.firstOrNull() ?: 1).coerceIn(1, 31))
            "quincenal" -> if (s?.str("frequency") == "quincenal" && prevDays.size == 2) prevDays else listOf(15, 30)
            else -> prevDays.ifEmpty { listOf(1) }
        }
        st.close()
        st.act("Guardado") { m ->
            if (s == null) m.addFirst("incomeSources", row("id" to newId(), "kind" to "salario", "name" to name.trim(), "amount" to a, "currency" to cur,
                "frequency" to freq, "payDays" to days))
            else m.updateRow("incomeSources", s.id) { it.plusFields("name" to name.trim(), "amount" to a, "currency" to cur, "frequency" to freq, "payDays" to days) }
        }
    })
}

// ---------- Deuda ----------

@Composable
fun DeudaForm(st: AppState, id: String?) {
    val x = st.repo.data.arr("debts").firstOrNull { it.id == id }
    var entity by remember { mutableStateOf(x?.str("entity") ?: "") }
    var amount by remember { mutableStateOf(inputMoney(x?.num("amount"))) }
    var cur by remember { mutableStateOf(x?.str("currency") ?: "USD") }
    var paid by remember { mutableStateOf(inputMoney(x?.num("paid"))) }
    var desc by remember { mutableStateOf(x?.str("description") ?: "") }
    var minPay by remember { mutableStateOf(inputMoney(x?.num("minPayment"))) }
    var rate by remember { mutableStateOf(inputMoney(x?.num("interestRate"))) }
    SheetTitle(if (x == null) "Nueva deuda" else "Editar deuda")
    Field("¿A quién le debes?", entity, { entity = it })
    MoneyField("Monto total", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    if (x != null) MoneyField("Ya pagado", paid) { paid = it }
    Field("Detalle (opcional)", desc, { desc = it })
    MoneyField("Pago mínimo al mes (opcional)", minPay) { minPay = it }
    Field("Interés mensual % (si lo sabes)", rate, { rate = it }, kind = KeyboardType.Decimal)
    if (x != null) OutlineDanger("Eliminar esta deuda") {
        st.close()
        st.act("Deuda eliminada", undo = true) { m ->
            m.removeRow("debts", x.id)
            m.setRows("plannedDebtPayments", m.rows("plannedDebtPayments").filter { it.str("debtId") != x.id })
        }
    }
    Footer(st, {
        if (entity.isBlank()) return@Footer st.toast("Escribe a quién le debes.")
        val a = pos(amount) ?: return@Footer st.badAmount()
        val mp = pos(minPay)
        val ir = pos(rate)
        st.close()
        st.act("Guardado") { m ->
            val interest: Array<Pair<String, Any?>> =
                if (ir != null) arrayOf("interestRate" to ir, "interestKnown" to true, "interestPeriod" to "mensual")
                else arrayOf("interestRate" to null, "interestKnown" to false)
            if (x == null) {
                val r = row("id" to newId(), "type" to "otro", "amount" to a, "currency" to cur, "entity" to entity.trim(), "description" to desc.trim(),
                    "paid" to 0, "date" to nowIso(), "minPayment" to mp)
                m.addFirst("debts", r.plusFields(*interest))
            } else m.updateRow("debts", x.id) {
                it.plusFields("entity" to entity.trim(), "description" to desc.trim(), "amount" to a, "currency" to cur,
                    "paid" to min(a, max(0.0, parseMoney(paid) ?: 0.0)), "minPayment" to mp, *interest)
            }
        }
    })
}

@Composable
fun AbonoForm(st: AppState, id: String) {
    val x = st.repo.data.arr("debts").firstOrNull { it.id == id } ?: return
    var amount by remember { mutableStateOf("") }
    SheetTitle("Abonar a ${x.str("entity") ?: "la deuda"}")
    MoneyField("Monto del abono", amount) { amount = it }
    Footer(st, {
        val a = pos(amount) ?: return@Footer st.badAmount()
        st.close()
        st.act("Guardado") { m ->
            m.updateRow("debts", x.id) { it.plusFields("paid" to min(it.num("amount"), it.num("paid") + a)) }
            m.addFirst("expenses", row("id" to newId() + "_abono", "amount" to a, "currency" to (x.str("currency") ?: "USD"), "category" to "deudas",
                "description" to "Abono a ${x.str("entity") ?: "deuda"}", "method" to "banco", "bank" to "", "date" to nowIso()))
        }
        val left = x.num("amount") - min(x.num("amount"), x.num("paid") + a)
        val who = x.str("entity") ?: "tu deuda"
        if (left <= .005) st.quinci("celebra", "¡Terminaste de pagar «$who»! Una menos.", "confeti")
        else st.quinci("feliz", "¡Abono registrado! A «$who» le falta ${Fmt(false, false, 1.0).orig(left, x.str("currency"))}.", "cartera", who)
    })
}

// ---------- Pagos programados ----------

@Composable
fun PagoForm(st: AppState, id: String?) {
    val d = st.repo.data
    val p = d.arr("plannedDebtPayments").firstOrNull { it.id == id }
    val debts = d.arr("debts")
    var debtId by remember { mutableStateOf(debts.firstOrNull()?.id ?: "") }
    var amount by remember { mutableStateOf(inputMoney(p?.num("amount"))) }
    var cur by remember { mutableStateOf(p?.str("currency") ?: debts.firstOrNull()?.str("currency") ?: "USD") }
    var date by remember { mutableStateOf(p?.str("scheduledDate") ?: LocalDate.now().toString()) }
    var rep by remember { mutableStateOf("0") }
    var times by remember { mutableStateOf("3") }
    SheetTitle(if (p == null) "Programar pago" else "Editar pago")
    if (p == null) Select("Deuda", debts.map { it.id to (it.str("entity") ?: "Deuda") }, debtId) { debtId = it }
    MoneyField("Monto", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    DateField(if (p == null) "Fecha (del primer pago)" else "Fecha", date, { date = it })
    if (p == null) {
        Select("Repetir", listOf("0" to "Solo una vez", "m" to "Cada mes", "q" to "Cada quincena"), rep) { rep = it }
        if (rep != "0") Field("¿Cuántas veces en total?", times, { times = it.filter(Char::isDigit).take(2) }, kind = KeyboardType.Number)
    }
    if (p != null) OutlineDanger("Cancelar este pago") {
        st.close(); st.act("Pago cancelado", undo = true) { m -> m.updateRow("plannedDebtPayments", p.id) { it.plusFields("status" to "cancelled") } }
    }
    Footer(st, {
        val a = pos(amount) ?: return@Footer st.badAmount()
        if (dateOf(date) == null) return@Footer st.toast("Elige la fecha del pago.")
        st.close()
        st.act("Guardado") { m ->
            if (p != null) m.updateRow("plannedDebtPayments", p.id) { it.plusFields("amount" to a, "currency" to cur, "scheduledDate" to date) }
            else {
                val x = m.rows("debts").firstOrNull { it.id == debtId } ?: return@act
                val n = if (rep == "0") 1 else (times.toIntOrNull() ?: 1).coerceIn(1, 36)
                val base = newId()
                val first = dateOf(date)!!
                val nuevos = (0 until n).map { i ->
                    row("id" to (if (n > 1) "${base}_r${i + 1}" else base), "debtId" to x["id"], "entity" to (x.str("entity") ?: ""),
                        "description" to (if (n > 1) "Pago ${i + 1} de $n" else (x.str("description") ?: "")),
                        "amount" to a, "currency" to cur, "scheduleType" to "date",
                        "scheduledDate" to (if (n > 1) stepDate(first, rep, i) else first).toString(), "status" to "planned")
                }
                m.setRows("plannedDebtPayments", nuevos + m.rows("plannedDebtPayments"))
            }
        }
    })
}

// ---------- Presupuesto y plan ----------

@Composable
fun PresupuestoForm(st: AppState) {
    val b = st.repo.data.obj("budgets")
    val values = remember { mutableStateMapOf<String, String>().apply { CATS.keys.forEach { put(it, inputMoney(b.num(it))) } } }
    val sg = remember { sugeridos(st.repo.data) }
    SheetTitle("Mi presupuesto")
    Mut("Cuánto quieres gastar al mes, en dólares. En Inicio lo vemos por quincena (la mitad).${if (sg.isNotEmpty()) " Los sugeridos salen de tu promedio de los últimos 3 meses." else ""}")
    if (sg.isNotEmpty()) Btn("Usar los sugeridos", {
        sg.forEach { (k, v) -> if ((values[k] ?: "").isBlank()) values[k] = v.toString() }
        st.toast("Listo: revisa los montos y toca Guardar.")
    }, small = true, outline = true, full = true)
    CATS.forEach { (k, l) -> Field("$l (USD al mes)" + (sg[k]?.let { " · sugerido $$it" } ?: ""), values[k] ?: "", { values[k] = it }, placeholder = "0", kind = KeyboardType.Decimal) }
    Footer(st, {
        st.close()
        st.act("Guardado") { m ->
            val old = (m["budgets"] as? JsonObject) ?: JsonObject(emptyMap())
            m["budgets"] = JsonObject(old + CATS.keys.associateWith { JsonPrimitive(max(0.0, parseMoney(values[it] ?: "") ?: 0.0)) })
        }
    })
}

@Composable
fun PlanForm(st: AppState) {
    val s = st.repo.data.obj("debtPlan")
    val guess = livingGuess(st.repo.data)
    var ess by remember { mutableStateOf(inputMoney(s.num("monthlyEssentials").takeIf { it > 0 } ?: guess.takeIf { it > 0 })) }
    var cushion by remember { mutableStateOf(inputMoney(s.num("monthlyCushion"))) }
    var pct by remember { mutableStateOf((s.num("paymentPercent").takeIf { it > 0 } ?: 60.0).toInt().toString()) }
    var strategy by remember { mutableStateOf(if (s.str("strategy") == "snowball") "snowball" else "avalanche") }
    SheetTitle("Mi plan de deudas")
    MoneyField("Lo que necesito para vivir al mes (USD)", ess) { ess = it }
    if (guess > 0) Mut("Según tus gastos de los últimos meses (sin pagos de deudas): \$ ${Fmt(false, false, 1.0).plain(guess)} al mes.")
    val inc = monthlyIncome(st.repo.data)
    Mut(if (inc > 0) "Tus ingresos del mes: \$ ${Fmt(false, false, 1.0).plain(inc)}." else "Agrega tu ingreso para calcular el plan.")
    MoneyField("Colchón para imprevistos al mes (USD)", cushion) { cushion = it }
    Field("Qué parte de lo que me sobra uso para deudas (30 a 85 %)", pct, { pct = it.filter(Char::isDigit).take(2) }, kind = KeyboardType.Number)
    Select("Orden de pago", listOf("avalanche" to "Primero la deuda con más interés", "snowball" to "Primero la deuda más pequeña"), strategy) { strategy = it }
    Footer(st, {
        st.close()
        st.act("Guardado") { m ->
            m["debtPlan"] = row(
                "monthlyEssentials" to max(0.0, parseMoney(ess) ?: 0.0), "monthlyCushion" to max(0.0, parseMoney(cushion) ?: 0.0),
                "paymentPercent" to (pct.toIntOrNull() ?: 60).coerceIn(30, 85), "strategy" to strategy, "configured" to true,
            )
        }
        val pl = debtPlan(st.repo.data)
        pl.months?.let { st.quinci("celebra", "¡Plan listo! Si lo sigues, terminas tus deudas en $it ${if (it == 1) "mes" else "meses"}.", "confeti") }
    })
}

// ---------- Gastos fijos ----------

@Composable
fun RecurrenteForm(st: AppState, id: String?) {
    val r = st.repo.data.arr("recurring").firstOrNull { it.id == id }
    var desc by remember { mutableStateOf(r?.str("description") ?: "") }
    var amount by remember { mutableStateOf(inputMoney(r?.num("amount"))) }
    var cur by remember { mutableStateOf(r?.str("currency") ?: "USD") }
    var cat by remember { mutableStateOf(r?.str("category") ?: "fijo") }
    var day by remember { mutableStateOf((r?.num("day")?.toInt() ?: 1).toString()) }
    var active by remember { mutableStateOf(if (r?.bool("active") == false) "0" else "1") }
    var mode by remember { mutableStateOf(if (r?.str("mode") == "manual") "manual" else "auto") }
    SheetTitle(if (r == null) "Nuevo gasto fijo" else "Editar gasto fijo")
    Field("Descripción", desc, { desc = it }, placeholder = "Alquiler, internet…")
    MoneyField("Monto", amount) { amount = it }
    CurrencySelect(cur) { cur = it }
    CategoryChips(cat) { cat = it }
    Field("Día del mes", day, { day = it.filter(Char::isDigit).take(2) }, kind = KeyboardType.Number)
    Select("¿Cómo se registra?", listOf("auto" to "Se anota solo ese día", "manual" to "Me lo recuerda y lo marco cuando pague"), mode) { mode = it }
    if (r != null) {
        Select("Estado", listOf("1" to "Activo", "0" to "En pausa"), active) { active = it }
        OutlineDanger("Eliminar este gasto fijo") { st.close(); st.act("Gasto fijo eliminado", undo = true) { it.removeRow("recurring", r.id) } }
    }
    Footer(st, {
        if (desc.isBlank()) return@Footer st.toast("Escribe una descripción.")
        val a = pos(amount) ?: return@Footer st.badAmount()
        val dd = (day.toIntOrNull() ?: 1).coerceIn(1, 31)
        val dup = if (r == null) dupOf(st.repo.data, desc, a, cur) else ""
        if (dup.isNotEmpty() && st.confirmDup != dup) { st.confirmDup = dup; return@Footer st.toast("$dup. Si lo agregas otra vez se contará dos veces. Toca Guardar de nuevo para agregarlo igual.") }
        st.confirmDup = ""
        st.close()
        st.act("Guardado") { m ->
            if (r == null) m.addFirst("recurring", row("id" to newId(), "description" to desc.trim(), "amount" to a, "currency" to cur, "category" to cat,
                "day" to dd, "active" to true, "lastGenerated" to "", "mode" to mode))
            else m.updateRow("recurring", r.id) { it.plusFields("description" to desc.trim(), "amount" to a, "currency" to cur, "category" to cat, "day" to dd, "active" to (active != "0"), "mode" to mode) }
            maintain(m) // registra de una vez el gasto de este mes si ya pasó el día
        }
    })
}

// ---------- Compromisos (colegio, alquiler…) ----------

@Composable
fun CompromisoForm(st: AppState, id: String?) {
    val d = st.repo.data
    val x = d.arr("schoolExpenses").firstOrNull { it.id == id }
    val paidSoFar = if (x != null) schoolPaid(d, x.id) else 0.0
    val hasPays = x != null && d.arr("schoolPayments").any { it.str("expenseId") == x.id }
    var desc by remember { mutableStateOf(x?.str("description") ?: "") }
    var student by remember { mutableStateOf(x?.str("student") ?: "") }
    var concept by remember { mutableStateOf(x?.str("concept") ?: "mensualidad") }
    var amount by remember { mutableStateOf(inputMoney(x?.num("amount"))) }
    var cur by remember { mutableStateOf(x?.str("currency") ?: "USD") }
    var due by remember { mutableStateOf(x?.str("dueDate") ?: "") }
    var repeat by remember { mutableStateOf("0") }
    SheetTitle(if (x == null) "Nuevo pago único" else "Editar compromiso")
    Field("Descripción", desc, { desc = it }, placeholder = "Mensualidad del colegio")
    Field("¿Para quién? (opcional)", student, { student = it })
    Select("Tipo", CONCEPTS, concept) { concept = it }
    MoneyField("Monto", amount) { amount = it }
    if (!hasPays) CurrencySelect(cur) { cur = it }
    DateField("Vence (opcional)", due, { due = it }, optional = true)
    if (x == null) Select("¿Se repite cada mes?", listOf("0" to "No, es un pago único", "1" to "Sí, todos los meses (queda como pago fijo)"), repeat) { repeat = it }
    if (x != null) OutlineDanger("Eliminar este compromiso") {
        st.close()
        st.act("Compromiso eliminado", undo = true) { m ->
            m.removeRow("schoolExpenses", x.id)
            m.setRows("schoolPayments", m.rows("schoolPayments").filter { it.str("expenseId") != x.id })
        }
    }
    Footer(st, {
        if (desc.isBlank()) return@Footer st.toast("Escribe una descripción.")
        val a = pos(amount) ?: return@Footer st.badAmount()
        if (x != null && a < paidSoFar - .01) return@Footer st.toast("El monto no puede ser menor a lo ya pagado.")
        val dup = if (x == null) dupOf(st.repo.data, desc, a, cur) else ""
        if (dup.isNotEmpty() && st.confirmDup != dup) { st.confirmDup = dup; return@Footer st.toast("$dup. Si lo agregas otra vez se contará dos veces. Toca Guardar de nuevo para agregarlo igual.") }
        st.confirmDup = ""
        st.close()
        st.act("Guardado") { m ->
            // Un compromiso que se repite cada mes es un pago fijo (manual): así no queda registrado dos veces.
            if (x == null && repeat == "1") m.addFirst("recurring", row("id" to newId(), "description" to desc.trim() + (if (student.isNotBlank()) " · " + student.trim() else ""),
                "amount" to a, "currency" to cur, "category" to (when (concept) { "transporte" -> "transporte"; "otro" -> "fijo"; else -> "educacion" }),
                "day" to (dateOf(due)?.dayOfMonth ?: LocalDate.now().dayOfMonth), "active" to true, "lastGenerated" to "", "mode" to "manual"))
            else if (x == null) m.addFirst("schoolExpenses", row("id" to newId(), "student" to student.trim(), "concept" to concept, "description" to desc.trim(),
                "amount" to a, "currency" to cur, "date" to LocalDate.now().toString(), "dueDate" to due, "notes" to ""))
            else m.updateRow("schoolExpenses", x.id) {
                val upd = it.plusFields("description" to desc.trim(), "student" to student.trim(), "concept" to concept, "amount" to a, "dueDate" to due)
                if (hasPays) upd else upd.plusFields("currency" to cur)
            }
        }
    })
}

/** Pagar un pago fijo manual: se anota como gasto de este mes (rec_<id>_<AAAA-MM>) y queda pagado. */
@Composable
fun RPagoForm(st: AppState, id: String) {
    val r = st.repo.data.arr("recurring").firstOrNull { it.id == id } ?: return
    var amount by remember { mutableStateOf(inputMoney(r.num("amount"))) }
    SheetTitle("Pagar ${r.str("description") ?: ""}")
    Mut("Se anota como gasto de este mes y queda marcado como pagado.")
    MoneyField("Monto (${if (r.str("currency") == "BS") Loc.cur.short else "$"})", amount) { amount = it }
    Footer(st, {
        val a = pos(amount) ?: return@Footer st.badAmount()
        val ym = java.time.YearMonth.now()
        val cur = r.str("currency") ?: "USD"
        st.close()
        st.act("Pago registrado") { m ->
            val bcv = rateOf(m.data())
            m.addFirst("expenses", row("id" to "rec_${r.id}_" + String.format(java.util.Locale.US, "%04d-%02d", ym.year, ym.monthValue), "amount" to a,
                "currency" to cur, "category" to (r.str("category") ?: "fijo"), "description" to (r.str("description") ?: ""), "method" to "efectivo", "bank" to "",
                "date" to nowIso(), "bcvRate" to (if (cur == "BS") bcv else null), "rateType" to (if (cur == "BS") "bcv" else null), "rateUsed" to (if (cur == "BS") bcv else null)))
        }
    })
}

@Composable
fun SugerenciaForm(st: AppState) {
    var kind by remember { mutableStateOf("idea") }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SheetTitle("Enviar sugerencia")
    Mut("Tu mensaje le llega al equipo de Quincena VE con tu usuario, para poder responderte.")
    Select("Tipo", listOf("idea" to "Una idea o mejora", "problema" to "Algo no funciona", "otro" to "Otro"), kind) { kind = it }
    Field("Tu mensaje", text, { text = it.take(1000) }, placeholder = "Me gustaría que…")
    Footer(st, {
        if (text.trim().length < 5) return@Footer st.toast("Cuéntanos un poco más (mínimo 5 letras).")
        busy = true
        st.scope.launch {
            try { st.repo.sendSuggestion(text.trim(), kind); st.close(); st.toast("¡Gracias! Recibimos tu sugerencia.") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { st.toast(friendly(e)) }
            busy = false
        }
    }, "Enviar", enabled = !busy)
}

@Composable
fun CPagoForm(st: AppState, id: String) {
    val d = st.repo.data
    val x = d.arr("schoolExpenses").firstOrNull { it.id == id } ?: return
    val left = schoolLeft(d, x)
    var amount by remember { mutableStateOf(inputMoney(left)) }
    var method by remember { mutableStateOf("banco") }
    var reference by remember { mutableStateOf("") }
    SheetTitle("Pagar compromiso")
    Mut("Falta ${Fmt(false, false, 1.0).orig(left, x.str("currency"))} de ${x.str("description") ?: "este compromiso"}.")
    MoneyField("Monto", amount) { amount = it }
    Select("Forma de pago", listOf("banco" to "Transferencia", "pago_movil" to "Pago móvil", "efectivo" to "Efectivo", "tarjeta" to "Tarjeta"), method) { method = it }
    Field("Referencia (opcional)", reference, { reference = it })
    Footer(st, {
        val a = pos(amount) ?: return@Footer st.badAmount()
        if (a > left + .01) return@Footer st.toast("El pago supera lo que falta. Baja el monto.")
        val pid = newId()
        val today = LocalDate.now().toString()
        st.close()
        st.act("Guardado") { m ->
            m.addFirst("schoolPayments", row("id" to pid, "expenseId" to x["id"], "amount" to a, "currency" to (x.str("currency") ?: "USD"), "date" to today,
                "method" to method, "reference" to reference.trim(), "notes" to ""))
            m.addFirst("expenses", row("id" to pid + "_school", "amount" to a, "currency" to (x.str("currency") ?: "USD"), "category" to "fijo",
                "description" to "Pago: ${x.str("description") ?: ""}", "method" to (if (method == "efectivo" || method == "pago_movil") method else "banco"),
                "bank" to reference.trim(), "date" to nowIso()))
        }
    })
}

// ---------- Cuenta ----------

@Composable
fun BorrarForm(st: AppState) {
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SheetTitle("Eliminar mi cuenta")
    Mut("Se borrarán para siempre tu cuenta y todos tus datos. Escribe tu contraseña para confirmar.")
    Field("Contraseña", pass, { pass = it }, password = true)
    Btn("Eliminar para siempre", {
        busy = true
        st.scope.launch {
            try { st.repo.deleteAccount(pass); st.close(); st.go("inicio"); st.toast("Tu cuenta fue eliminada.") }
            catch (e: ApiError) { st.toast(e.message ?: "Algo salió mal.") }
            catch (e: Offline) { st.toast("Sin conexión. Revisa tu internet e inténtalo de nuevo.") }
            busy = false
        }
    }, color = pal.bad, enabled = !busy && pass.isNotEmpty())
    Btn("Cancelar", { st.close() }, outline = true)
}

@Composable
fun LegalSheet(st: AppState) {
    SheetTitle("Términos y privacidad")
    LegalText()
    Btn("Volver", { st.close() })
}
