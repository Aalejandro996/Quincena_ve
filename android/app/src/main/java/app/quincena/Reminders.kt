package app.quincena

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Tipos de aviso que la persona puede prender o apagar desde Más. */
val AVISO_TIPOS = listOf(
    "pagos" to "Pagos y compromisos",
    "deben" to "Cobros de «Me deben»",
    "pres" to "Presupuesto al 80 %",
    "upd" to "Nueva versión de la app",
)

/**
 * Avisos que calcula el propio teléfono (sin Firebase, sin claves y sin el cron de Render):
 * pagos 7, 4 y 1 día antes y el mismo día, cobros de «Me deben», presupuesto al 80 % y versión nueva.
 * Se revisan cada pocas horas y nunca de noche.
 */
object Reminders {
    const val CHANNEL = "pagos"
    const val CHANNEL_NEWS = "novedades"
    private const val WORK = "recordatorios"
    private const val SCHEDULE = "6h-v1" // al cambiar esto, los teléfonos que ya tenían avisos pasan al nuevo ritmo

    fun enable(ctx: Context) {
        channel(ctx)
        schedule(ctx, force = true)
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }

    /** Se llama al abrir la app: si ya tenía los avisos activos con el ritmo viejo (1 vez al día), los pasa al nuevo. */
    fun ensure(ctx: Context) {
        val p = ctx.getSharedPreferences("quincena_avisos", Context.MODE_PRIVATE)
        if (p.getString("schedule", null) != SCHEDULE) schedule(ctx, force = true)
    }

    private fun schedule(ctx: Context, force: Boolean) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK,
            if (force) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        ctx.getSharedPreferences("quincena_avisos", Context.MODE_PRIVATE).edit().putString("schedule", SCHEDULE).apply()
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        ctx.getSharedPreferences("quincena_avisos", Context.MODE_PRIVATE).edit().remove("schedule").apply()
    }

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de pago", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_NEWS, "Novedades y presupuesto", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SecureStore(ctx)
        if (store.get("token") == null) return Result.success()
        // De noche (10 pm a 7 am) no se avisa; lo pendiente sale en la primera revisión de la mañana.
        val now = LocalTime.now()
        if (now.hour >= 22 || now.hour < 7) return Result.success()
        // Intenta leer lo último del servidor (solo lectura); si no hay internet usa lo guardado en el teléfono.
        val d: JsonObject = runCatching { Api(store).call("GET", "/api/state")["data"]!!.jsonObject }.getOrNull()
            ?: store.get("data")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return Result.success()

        fun on(tipo: String) = store.get("pref_av_$tipo") != "0"
        val sent = ctx.getSharedPreferences("quincena_avisos", Context.MODE_PRIVATE)
        // Marca el aviso como enviado solo si de verdad se pudo mostrar (si faltaba el permiso, se reintenta).
        fun once(key: String, title: String, body: String, news: Boolean = false): Boolean {
            if (sent.getBoolean(key, false)) return false
            if (notify(ctx, key.hashCode(), title, body, news)) { sent.edit().putBoolean(key, true).apply(); return true }
            return false
        }

        val f = Fmt(false, false, rateOf(d))
        // En Venezuela los avisos hablan en criollo; en otros países, neutro.
        val ve = (d.str("country") ?: "VE") == "VE"
        val today = LocalDate.now()

        // 1) Pagos programados y compromisos: 7, 4 y 1 día antes y el mismo día.
        if (on("pagos")) {
            data class Due(val key: String, val title: String, val amount: String, val days: Int)
            val items = mutableListOf<Due>()
            for (p in d.arr("plannedDebtPayments")) if (p.str("status") == "planned") {
                val date = dateOf(p.str("scheduledDate")) ?: continue
                items += Due("debt_${p.id}_$date", p.str("entity") ?: p.str("description") ?: "Pago de deuda", f.orig(p.num("amount"), p.str("currency")), daysUntil(date))
            }
            for (card in d.arr("debts")) if (card.str("type") == "tdc" && card.num("minPayment") > 0) {
                val statement = dateOf(card.str("payBefore")) ?: continue
                val ym = java.time.YearMonth.from(today)
                var due = ym.atDay(statement.dayOfMonth.coerceAtMost(ym.lengthOfMonth()))
                if (due.isBefore(today)) {
                    val next = ym.plusMonths(1)
                    due = next.atDay(statement.dayOfMonth.coerceAtMost(next.lengthOfMonth()))
                }
                val scheduled = d.arr("plannedDebtPayments").any { it.str("debtId") == card.id && it.str("status") == "planned" && (it.str("scheduledDate") ?: "").startsWith(ym.toString()) }
                if (!scheduled) items += Due("card_${card.id}_$due", "Pago de tarjeta: ${card.str("issuer") ?: card.str("entity") ?: "Tarjeta"}", f.orig(card.num("minPayment"), card.str("currency")), daysUntil(due))
            }
            for (x in d.arr("schoolExpenses")) {
                val date = dateOf(x.str("dueDate")) ?: continue
                val left = schoolLeft(d, x)
                if (left <= 0.005) continue
                items += Due("school_${x.id}_$date", x.str("description") ?: "Compromiso", f.orig(left, x.str("currency")), daysUntil(date))
            }
            for (it in items) {
                if (it.days !in 0..7) continue
                val bucket = listOf(0, 1, 4, 7).first { b -> b >= it.days }
                val body = if (ve) when (it.days) { 0 -> "¡Pendiente! Vence hoy: ${it.amount}. Que no se te pase."; 1 -> "Vence mañana: ${it.amount}. Ve apartando los reales."; else -> "Vence en ${it.days} días: ${it.amount}." }
                    else when (it.days) { 0 -> "Vence hoy: ${it.amount}"; 1 -> "Vence mañana: ${it.amount}"; else -> "Vence en ${it.days} días: ${it.amount}" }
                once("${it.key}_$bucket", it.title, body)
            }
        }

        // 2) «Me deben»: el día del cobro y, si no han pagado, a los 3 días ya vencido.
        if (on("deben")) for (r in d.arr("receivables")) {
            val left = r.num("amount") - r.num("paid")
            if (left <= 0.005) continue
            val date = dateOf(r.str("dueDate")) ?: continue
            val days = daysUntil(date)
            val who = r.str("person")?.takeIf { it.isNotBlank() } ?: "Alguien"
            val amount = f.orig(left, r.str("currency"))
            when {
                days == 0 -> once("rec_${r.id}_${date}_0", "Hoy te toca cobrar", if (ve) "$who te debe $amount. ¡Recuérdaselo!" else "$who te debe $amount.")
                days == 1 -> once("rec_${r.id}_${date}_1", "Mañana te toca cobrar", "$who te debe $amount.")
                days <= -3 -> once("rec_${r.id}_${date}_late", if (ve) "Ese cobro se está atrasando" else "Cobro atrasado",
                    "$who te debe $amount desde el ${shortDate(date)}." + if (ve) " Échale un recordatorio." else "")
            }
        }

        // 3) Presupuesto: al llegar al 80 % de una categoría en la quincena (una vez por categoría y quincena).
        if (on("pres")) {
            val per = currentPeriod()
            val spent = spentByCat(d, per.from, per.to)
            val budgets = d.obj("budgets")
            val fr = 0.5 // el presupuesto es mensual y se avisa por quincena
            for (k in CATS.keys) {
                val b = budgets.num(k) * fr
                if (b <= 0) continue
                val pct = (spent[k] ?: 0.0) / b * 100
                if (pct >= 80) {
                    val over = pct >= 100
                    val base = "pres_${per.ym}_${per.half}_$k"
                    val key = "${base}_${if (over) 100 else 80}"
                    // Si ya pasó del 100 %, el aviso del 80 % deja de hacer falta.
                    if (over) sent.edit().putBoolean("${base}_80", true).apply()
                    val title = if (over) (if (ve) "¡Epa! ${CATS[k]}: te pasaste del presupuesto" else "${CATS[k]}: te pasaste del presupuesto")
                        else (if (ve) "Pendiente: ${CATS[k]} va al ${pct.toInt()} % del presupuesto" else "${CATS[k]}: vas al ${pct.toInt()} % del presupuesto")
                    once(key, title, "Llevas ${f.show(spent[k] ?: 0.0)} de ${f.show(b)} esta quincena.", news = true)
                }
            }
        }

        // 4) Versión nueva de la app (una sola vez por versión).
        if (on("upd")) {
            val r = runCatching { Api(store).call("GET", "/api/android-app") }.getOrNull()
            val code = (r?.get("versionCode") as? JsonPrimitive)?.intOrNull ?: 0
            if (r != null && r.bool("available") == true && code > BuildConfig.VERSION_CODE) {
                val notes = r.str("notes")?.takeIf { it.isNotBlank() } ?: "Abre Quincena VE y toca Actualizar."
                once("upd_$code", "Quincena VE ${r.str("version") ?: "nueva"} disponible", notes, news = true)
            }
        }
        // Limpia avisos viejos para que el archivo no crezca sin fin.
        val old = today.minusDays(120).toString()
        sent.all.keys.filter { it.startsWith("debt_") || it.startsWith("school_") || it.startsWith("rec_") }
            .filter { k -> Regex("""\d{4}-\d{2}-\d{2}""").find(k)?.value?.let { it < old } == true }
            .forEach { sent.edit().remove(it).apply() }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, id: Int, title: String, body: String, news: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        Reminders.channel(ctx)
        val tap = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, if (news) Reminders.CHANNEL_NEWS else Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_quincena)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
        return true
    }
}
