package com.weblauncher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Med(val id: String, val name: String, val dose: String, val times: List<String>, val stock: Int, val perDose: Int = 1, val reminderIds: List<Int> = emptyList())

/** Medicines: reminders at fixed times and a count of what is left. */
object Meds {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_care", Context.MODE_PRIVATE)
    fun load(c: Context): List<Med> = runCatching {
        Gson().fromJson<List<Med>>(prefs(c).getString("meds", null), object : TypeToken<List<Med>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull()
    private fun save(c: Context, l: List<Med>) = prefs(c).edit().putString("meds", Gson().toJson(l)).apply()

    /** "8, 20:30 , 13.00" -> ["08:00","13:00","20:30"]; invalid items are dropped. */
    fun parseTimes(raw: String): List<String> = raw.split(',', ';', ' ').mapNotNull { t ->
        val m = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$").find(t.trim()) ?: return@mapNotNull null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else null
    }.distinct().sorted()

    fun perDay(m: Med) = m.times.size.coerceAtLeast(1) * m.perDose.coerceAtLeast(1)
    fun daysLeft(m: Med) = m.stock / perDay(m)
    fun isLow(m: Med, days: Int = 5) = m.stock > 0 && daysLeft(m) <= days || m.stock == 0 && m.times.isNotEmpty()

    /** The next time (millis) at HH:MM after [now]. */
    fun nextAt(hhmm: String, now: Long = System.currentTimeMillis()): Long {
        val (h, m) = hhmm.split(':').map { it.toInt() }
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Next dose time of the day, as text ("20:30" or "domani 08:00"). */
    fun nextDose(m: Med, now: Calendar = Calendar.getInstance()): String? {
        if (m.times.isEmpty()) return null
        val cur = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        return m.times.firstOrNull { it > cur } ?: ("domani " + m.times.first())
    }

    fun takenToday(c: Context, id: String) = prefs(c).getInt("taken_${id}_${Food.dayKey(System.currentTimeMillis())}", 0)
    /** «Ho preso la dose»: counts it and takes the pills out of the stock. */
    fun markTaken(c: Context, id: String) {
        val m = load(c).firstOrNull { it.id == id } ?: return
        prefs(c).edit().putInt("taken_${id}_${Food.dayKey(System.currentTimeMillis())}", takenToday(c, id) + 1).apply()
        save(c, load(c).map { if (it.id == id) it.copy(stock = (it.stock - it.perDose).coerceAtLeast(0)) else it })
    }

    fun upsert(c: Context, m: Med) {
        val old = load(c).firstOrNull { it.id == m.id }
        old?.reminderIds?.forEach { Reminders.remove(c, it) }
        val ids = m.times.map { t -> Reminders.add(c, "💊 Prendi ${m.name}" + if (m.dose.isNotBlank()) " (${m.dose})" else "", nextAt(t), "day").id }
        save(c, load(c).filter { it.id != m.id } + m.copy(reminderIds = ids))
    }
    fun remove(c: Context, id: String) {
        load(c).firstOrNull { it.id == id }?.reminderIds?.forEach { Reminders.remove(c, it) }
        save(c, load(c).filter { it.id != id })
    }
    fun newId() = UUID.randomUUID().toString()

    /** Medicines running out (for the evening notification). */
    fun lowStock(c: Context) = load(c).filter { it.times.isNotEmpty() && it.stock >= 0 && daysLeft(it) <= 5 }
}

data class EmergencyCard(
    val name: String = "", val birth: String = "", val blood: String = "", val allergies: String = "", val conditions: String = "",
    val medicines: String = "", val contact1: String = "", val phone1: String = "", val contact2: String = "", val phone2: String = "", val notes: String = ""
)

/** Emergency card, optionally shown on the lock screen (anyone can read it: it is the point, and the user chooses it). */
object Emergency {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_care", Context.MODE_PRIVATE)
    fun load(c: Context): EmergencyCard = runCatching { Gson().fromJson(prefs(c).getString("emergency", null), EmergencyCard::class.java) }.getOrNull() ?: EmergencyCard()
    fun save(c: Context, e: EmergencyCard) { prefs(c).edit().putString("emergency", Gson().toJson(e)).apply(); sync(c) }
    fun onLockScreen(c: Context) = prefs(c).getBoolean("emergency_lock", false)
    fun setOnLockScreen(c: Context, on: Boolean) { prefs(c).edit().putBoolean("emergency_lock", on).apply(); sync(c) }

    val BLOOD = listOf("0+", "0−", "A+", "A−", "B+", "B−", "AB+", "AB−")

    fun isEmpty(e: EmergencyCard) = e == EmergencyCard()

    /** Readable lines for the card, the notification and the share text. */
    fun lines(e: EmergencyCard): List<String> = buildList {
        if (e.name.isNotBlank()) add(e.name + if (e.birth.isNotBlank()) " · nato/a il ${e.birth}" else "")
        if (e.blood.isNotBlank()) add("Gruppo sanguigno: ${e.blood}")
        if (e.allergies.isNotBlank()) add("⚠️ Allergie: ${e.allergies}")
        if (e.conditions.isNotBlank()) add("Patologie: ${e.conditions}")
        if (e.medicines.isNotBlank()) add("Farmaci: ${e.medicines}")
        if (e.contact1.isNotBlank() || e.phone1.isNotBlank()) add("📞 ${listOf(e.contact1, e.phone1).filter { it.isNotBlank() }.joinToString(": ")}")
        if (e.contact2.isNotBlank() || e.phone2.isNotBlank()) add("📞 ${listOf(e.contact2, e.phone2).filter { it.isNotBlank() }.joinToString(": ")}")
        if (e.notes.isNotBlank()) add(e.notes)
    }

    /** Posts or removes the lock-screen notification according to the setting. Safe to call at any time (also after a reboot). */
    fun sync(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val e = load(c)
        if (!onLockScreen(c) || isEmpty(e)) { nm.cancel(7810); return }
        nm.createNotificationChannel(NotificationChannel("emergenza", "Scheda d'emergenza", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
        val open = PendingIntent.getActivity(c, 7810, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "emergency")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = lines(e).joinToString("\n")
        val n = NotificationCompat.Builder(c, "emergenza").setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("🆘 Scheda d'emergenza").setContentText(lines(e).firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
        runCatching { nm.notify(7810, n) }
    }
}

data class Visit(val id: String, val title: String, val time: Long, val place: String = "", val note: String = "", val reminderIds: List<Int> = emptyList())

/** Medical appointments with reminders the evening before and two hours before. */
object Visits {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_care", Context.MODE_PRIVATE)
    fun load(c: Context): List<Visit> = runCatching {
        Gson().fromJson<List<Visit>>(prefs(c).getString("visits", null), object : TypeToken<List<Visit>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().sortedBy { it.time }
    private fun save(c: Context, l: List<Visit>) = prefs(c).edit().putString("visits", Gson().toJson(l)).apply()

    /** When to remind: 18:00 the day before and 2 h before; only moments still in the future. */
    fun reminderTimes(time: Long, now: Long = System.currentTimeMillis()): List<Long> {
        val evening = Calendar.getInstance().apply { timeInMillis = time; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return listOf(evening, time - 2 * 3_600_000L).filter { it > now }.distinct()
    }

    fun upsert(c: Context, v: Visit) {
        load(c).firstOrNull { it.id == v.id }?.reminderIds?.forEach { Reminders.remove(c, it) }
        val f = SimpleDateFormat("EEE d MMM 'alle' HH:mm", Locale.ITALIAN)
        val ids = reminderTimes(v.time).map { at -> Reminders.add(c, "🩺 ${v.title} — ${f.format(Date(v.time))}" + if (v.place.isNotBlank()) " · ${v.place}" else "", at).id }
        save(c, load(c).filter { it.id != v.id } + v.copy(reminderIds = ids))
    }
    fun remove(c: Context, id: String) {
        load(c).firstOrNull { it.id == id }?.reminderIds?.forEach { Reminders.remove(c, it) }
        save(c, load(c).filter { it.id != id })
    }
    fun newId() = UUID.randomUUID().toString()
}

/** The text of the report for the doctor: sections of lines (the PDF is drawn from this). */
object Report {
    data class Data(
        val name: String, val from: Long, val to: Long,
        val weights: List<Pair<Long, Double>>, val pressures: List<Triple<Long, Int, Int>>, val glucose: List<Pair<Long, Double>>,
        val stepsAvg: Long, val sleepAvgMin: Long, val restHrAvg: Long, val workouts: Int,
        val medicines: List<String>, val allergies: String, val conditions: String,
        val symptoms: List<String> = emptyList()
    )

    fun sections(d: Data): List<Pair<String, List<String>>> {
        val day = SimpleDateFormat("d MMM", Locale.ITALIAN); val full = SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN)
        val out = mutableListOf<Pair<String, List<String>>>()
        out += "Periodo" to listOf("Dal ${full.format(Date(d.from))} al ${full.format(Date(d.to))}")
        if (d.allergies.isNotBlank() || d.conditions.isNotBlank() || d.medicines.isNotEmpty()) out += "Dati clinici" to buildList {
            if (d.allergies.isNotBlank()) add("Allergie: ${d.allergies}")
            if (d.conditions.isNotBlank()) add("Patologie: ${d.conditions}")
            if (d.medicines.isNotEmpty()) add("Farmaci: " + d.medicines.joinToString("; "))
        }
        if (d.pressures.isNotEmpty()) {
            val s = d.pressures.map { it.second }; val di = d.pressures.map { it.third }
            out += "Pressione arteriosa (mmHg)" to (listOf("${d.pressures.size} misurazioni · media ${s.average().toInt()}/${di.average().toInt()} · massima ${s.max()}/${di.max()} · minima ${s.min()}/${di.min()}") +
                d.pressures.takeLast(12).map { "${day.format(Date(it.first))}: ${it.second}/${it.third}" })
        }
        if (d.weights.isNotEmpty()) {
            val w = d.weights.map { it.second }
            out += "Peso (kg)" to listOf("Da %.1f a %.1f (%+.1f)".format(Locale.ITALIAN, w.first(), w.last(), w.last() - w.first()) + " · ${w.size} misurazioni")
        }
        if (d.glucose.isNotEmpty()) {
            val g = d.glucose.map { it.second }
            out += "Glicemia (mg/dL)" to (listOf("${g.size} misurazioni · media ${g.average().toInt()} · massima ${g.max().toInt()} · minima ${g.min().toInt()}") +
                d.glucose.takeLast(12).map { "${day.format(Date(it.first))}: ${it.second.toInt()}" })
        }
        val activity = buildList {
            if (d.stepsAvg > 0) add("Passi: media ${String.format(Locale.ITALIAN, "%,d", d.stepsAvg)} al giorno")
            if (d.sleepAvgMin > 0) add("Sonno: media ${Hc.duration(d.sleepAvgMin)} a notte")
            if (d.restHrAvg > 0) add("Battito a riposo: media ${d.restHrAvg} bpm")
            if (d.workouts > 0) add("Allenamenti: ${d.workouts}")
        }
        if (activity.isNotEmpty()) out += "Attività e riposo" to activity
        if (d.symptoms.isNotEmpty()) out += "Sintomi annotati" to d.symptoms
        if (out.size == 1) out += "Dati" to listOf("Nessuna misurazione nel periodo.")
        return out
    }
}
