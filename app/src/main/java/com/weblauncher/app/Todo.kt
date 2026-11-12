package com.weblauncher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/** Everything «to do» in one list: appointments, reminders, medicines, deadlines, visits, birthdays, waste. Used by the page and the widgets. */
object Todo {
    data class Item(val time: Long, val allDay: Boolean, val text: String, val icon: String, val kind: String, val id: String = "", val extra: String = "")

    fun startOfDay(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun calendar(c: Context, from: Long, to: Long): List<Item> {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
            c.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.EVENT_ID), null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                val out = mutableListOf<Item>()
                while (cur.moveToNext()) {
                    val allDay = cur.getInt(2) == 1
                    var begin = cur.getLong(1)
                    if (allDay) begin = Calendar.getInstance().apply {
                        val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = begin }
                        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                    }.timeInMillis
                    out += Item(begin, allDay, cur.getString(0)?.takeIf { it.isNotBlank() } ?: "(senza titolo)", "📅", "cal", cur.getLong(3).toString())
                }
                out
            }
        }.getOrNull().orEmpty()
    }

    /** The list for the next [days] days, in order. Medicines not taken yet and overdue reminders come first for today. */
    fun items(c: Context, days: Int = 14, now: Long = System.currentTimeMillis()): List<Item> {
        val start = startOfDay(now); val end = start + days * 86_400_000L
        val out = mutableListOf<Item>()
        out += calendar(c, now - 2 * 3_600_000L, end)
        Reminders.load(c).filter { !it.done && it.at in (start - 86_400_000L)..end && !(it.at < now - 6 * 3_600_000L) }.forEach { r ->
            out += Item(r.at, false, r.text, if (r.repeat != null) "🔁" else "⏰", "rem", r.id.toString())
        }
        // medicines: today's doses not taken yet (the n-th dose of the day is taken if n < doses taken)
        Meds.load(c).filter { it.times.isNotEmpty() }.forEach { m ->
            val taken = Meds.takenToday(c, m.id)
            m.times.forEachIndexed { i, hhmm ->
                if (i >= taken) {
                    val (h, mi) = hhmm.split(':').map { it.toInt() }
                    val t = Calendar.getInstance().apply { timeInMillis = start; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi) }.timeInMillis
                    out += Item(t, false, "${m.name}" + if (m.dose.isNotBlank()) " (${m.dose})" else "", "💊", "med", m.id, hhmm)
                }
            }
        }
        Deadlines.load(c).forEach { d ->
            val within = when (d.kind) { Deadlines.WARRANTY -> false; else -> d.date in (start - 86_400_000L)..end }
            if (within) out += Item(d.date, true, when (d.kind) { Deadlines.SUBSCRIPTION -> "${d.title} si rinnova"; else -> "${d.title} scade" }, "⚠️", "dead", d.id, d.kind)
        }
        Visits.load(c).filter { it.time in now..end }.forEach { out += Item(it.time, false, it.title + if (it.place.isNotBlank()) " · ${it.place}" else "", "🩺", "visit", it.id) }
        runCatching { Birthdays.upcoming(c, days) }.getOrDefault(emptyList()).forEach { (p, d) ->
            out += Item(start + d * 86_400_000L, true, "Compleanno di ${p.name}", "🎂", "bday")
        }
        runCatching {
            for (d in 0..1) {
                val cal = Calendar.getInstance().apply { timeInMillis = start + d * 86_400_000L }
                val k = Waste.kindsOn(Evening.waste(c), Evening.wasteDates(c), cal)
                if (k.isNotEmpty()) out += Item(start + d * 86_400_000L, true, (if (d == 0) "Oggi" else "Domani") + " raccolta: " + k.joinToString(", "), "🗑", "waste")
            }
        }
        Places.parking(c)?.takeIf { it.until > now && it.until < end }?.let { out += Item(it.until, false, "Scade il parcheggio", "🚗", "park") }
        return out.sortedWith(compareBy<Item> { it.time }.thenBy { !it.allDay })
    }

    /** Marks a reminder as done: a repeating one moves to its next time, the others disappear. */
    fun doneReminder(c: Context, id: Int) {
        val r = Reminders.load(c).firstOrNull { it.id == id } ?: return
        if (r.repeat != null) {
            val again = r.copy(at = Reminders.next(r.at, r.repeat), done = false)
            Reminders.save(c, Reminders.load(c).map { if (it.id == id) again else it }); Reminders.schedule(c, again)
        } else Reminders.remove(c, id)
    }

    /** One short line for widgets and lists: "14:30 Dentista". */
    fun line(i: Item, now: Long = System.currentTimeMillis()): String {
        val start = startOfDay(now)
        val day = when {
            i.time < start -> "ieri "
            i.time >= start + 2 * 86_400_000L -> java.text.SimpleDateFormat("EEE d ", java.util.Locale.ITALIAN).format(java.util.Date(i.time))
            i.time >= start + 86_400_000L -> "domani "
            else -> ""
        }
        val hm = if (i.allDay) "" else java.text.SimpleDateFormat("HH:mm ", java.util.Locale.ITALIAN).format(java.util.Date(i.time))
        return "${i.icon} $day$hm${i.text}"
    }
}
