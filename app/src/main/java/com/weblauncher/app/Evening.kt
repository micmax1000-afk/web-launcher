package com.weblauncher.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale

/** One alarm at 19:00: tomorrow's rubbish collection, and on Sundays the summary of the week. */
object Evening {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_evening", Context.MODE_PRIVATE)
    fun waste(c: Context) = Waste.decode(prefs(c).getString("waste", null))
    fun setWaste(c: Context, m: Map<Int, List<String>>) { prefs(c).edit().putString("waste", Waste.encode(m)).apply(); schedule(c) }
    /** Exact collection dates read from the town's PDF calendar. */
    fun wasteDates(c: Context) = Waste.decodeDates(prefs(c).getString("wasteDates", null))
    fun setWasteDates(c: Context, m: Map<String, List<String>>) { prefs(c).edit().putString("wasteDates", Waste.encodeDates(m)).apply(); schedule(c) }
    fun wastePdf(c: Context) = java.io.File(java.io.File(c.filesDir, "waste").apply { mkdirs() }, "calendario.pdf")
    fun weekly(c: Context) = prefs(c).getBoolean("weekly", false)
    fun setWeekly(c: Context, on: Boolean) { prefs(c).edit().putBoolean("weekly", on).apply(); schedule(c) }
    fun lastWeekly(c: Context) = prefs(c).getString("weeklyText", null)

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7800, Intent(c, EveningReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (waste(c).isEmpty() && wasteDates(c).isEmpty() && !weekly(c)) return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }

    /** Text of the weekly summary (last 7 days, today included). Pure given the data: unit-tested. */
    fun weeklyText(spent: Double, spentBefore: Double, topCats: List<Pair<String, Double>>, steps: Int, stepGoal: Int,
                   upcoming: List<String>, birthdays: List<String>): String = buildString {
        append("💰 Spese della settimana: ${Expenses.fmt(spent)}")
        if (spentBefore > 0) {
            val pct = (spent - spentBefore) / spentBefore * 100
            append(" (" + (if (pct >= 0) "+" else "−") + String.format(Locale.ITALIAN, "%.0f", kotlin.math.abs(pct)) + "% rispetto alla scorsa)")
        }
        if (topCats.isNotEmpty()) append("\n   " + topCats.joinToString(" · ") { "${it.first.substringAfter(' ')} ${Expenses.fmt(it.second)}" })
        if (steps > 0) append("\n👟 Passi: ${String.format(Locale.ITALIAN, "%,d", steps)} (media ${String.format(Locale.ITALIAN, "%,d", steps / 7)} al giorno" +
            (if (stepGoal > 0) ", obiettivo ${String.format(Locale.ITALIAN, "%,d", stepGoal)}" else "") + ")")
        if (upcoming.isNotEmpty()) { append("\n⚠️ In arrivo nei prossimi 7 giorni:"); upcoming.forEach { append("\n   • $it") } }
        if (birthdays.isNotEmpty()) append("\n🎂 Compleanni: " + birthdays.joinToString(", "))
        if (upcoming.isEmpty() && birthdays.isEmpty()) append("\n✅ Nessuna scadenza nei prossimi 7 giorni")
    }

    fun composeWeekly(c: Context): String {
        val now = System.currentTimeMillis(); val week = 7 * 86_400_000L
        val all = Expenses.load(c)
        val cur = all.filter { it.time in (now - week)..now }
        val before = all.filter { it.time in (now - 2 * week) until (now - week) }.sumOf { it.amount }
        val cats = cur.groupBy { it.cat }.mapValues { it.value.sumOf { e -> e.amount } }.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val steps = (0..6).sumOf { Steps.day(c, it) }
        val df = java.text.SimpleDateFormat("EEE d", Locale.ITALIAN)
        val up = Deadlines.load(c).filter { it.kind != Deadlines.WARRANTY && it.date in now..(now + week) }.sortedBy { it.date }
            .map { "${it.title} (${df.format(java.util.Date(it.date))})" + if (it.amount > 0) " ${Expenses.fmt(it.amount)}" else "" }
        val bd = runCatching { Birthdays.upcoming(c, 7) }.getOrDefault(emptyList()).map { "${it.first.name} (${if (it.second == 0) "oggi" else "tra ${it.second} g"})" }
        return weeklyText(cur.sumOf { it.amount }, before, cats, steps, Steps.goal(c), up, bd)
    }

    fun notify(c: Context, id: Int, title: String, text: String, widget: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sera", "Promemoria della sera", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, widget)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "sera").setSmallIcon(R.drawable.ic_nav_home).setContentTitle(title)
            .setContentText(text.lines().first()).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(id, n) }
    }

    fun fire(c: Context) {
        val now = Calendar.getInstance()
        val out = Waste.tomorrow(waste(c), now, wasteDates(c))
        if (out.isNotEmpty()) notify(c, 7801, "🗑 Domani passa la raccolta", "Stasera metti fuori: " + out.joinToString(", "), "waste")
        val low = Meds.lowStock(c)
        if (low.isNotEmpty()) notify(c, 7803, "💊 Scorte di farmaci", low.joinToString("\n") { it.name + if (it.stock <= 0) ": finito" else ": restano ${it.stock} (~${Meds.daysLeft(it)} giorni)" }, "meds")
        if (weekly(c) && now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            val t = runCatching { composeWeekly(c) }.getOrNull() ?: return
            prefs(c).edit().putString("weeklyText", t).apply()
            notify(c, 7802, "📊 La tua settimana", t, "weekly")
        }
    }
}

class EveningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        Thread { runCatching { Evening.fire(context) }; Evening.schedule(context); pr.finish() }.start()
    }
}
