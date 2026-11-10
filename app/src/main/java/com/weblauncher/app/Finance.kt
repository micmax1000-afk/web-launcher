package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Income, recurring entries, account balances and the 730 (Italian tax return) deductions. */
object Finance {
    data class Income(val id: String, val amount: Double, val desc: String, val time: Long)
    /** Added automatically every month on [day]. kind = "spesa" | "entrata". [lastKey] = last month applied (yyyyMM). */
    data class Recurring(val id: String, val kind: String, val amount: Double, val desc: String, val cat: String, val day: Int, var lastKey: Int)
    data class Account(val id: String, val name: String, val balance: Double, val updated: Long)

    // ---------- pure logic (unit-tested) ----------

    fun monthKey(c: Calendar) = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1

    /** Months (yyyyMM) after [lastKey] up to [now] whose [day] has already come. */
    fun dueMonths(lastKey: Int, day: Int, now: Calendar): List<Int> {
        val out = mutableListOf<Int>()
        val c = Calendar.getInstance().apply { clear(); set(lastKey / 100, lastKey % 100 - 1, 1); add(Calendar.MONTH, 1) }
        var guard = 0
        while (monthKey(c) <= monthKey(now) && guard++ < 36) {
            val k = monthKey(c)
            val effectiveDay = day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH))
            if (k < monthKey(now) || now.get(Calendar.DAY_OF_MONTH) >= effectiveDay) out += k
            c.add(Calendar.MONTH, 1)
        }
        return out
    }

    /** Health costs: 19% of the part above the €129.11 deductible. */
    const val FRANCHIGIA = 129.11
    fun healthDeduction(total: Double) = if (total <= FRANCHIGIA) 0.0 else (total - FRANCHIGIA) * 0.19

    private val deductibleWords = listOf("farmacia", "medic", "dentist", "visita", "ticket", "analisi", "ottico", "occhiali",
        "lenti", "fisioterap", "ospedal", "specialist", "veterinar", "ortodon", "psicolog", "esami")

    /** Likely 730 expense: health category or a health/vet keyword; [overrides] = ids toggled by hand. */
    fun isDeductible(e: Expense, overrides: Set<String>): Boolean {
        val auto = e.cat.contains("Salute") || deductibleWords.any { e.desc.lowercase(Locale.ITALIAN).contains(it) }
        return if (e.id in overrides) !auto else auto
    }

    // ---------- storage ----------

    private fun prefs(c: Context) = c.getSharedPreferences("wl_finance", Context.MODE_PRIVATE)
    private inline fun <reified T> load(c: Context, key: String): MutableList<T> = runCatching {
        Gson().fromJson<MutableList<T>>(prefs(c).getString(key, null), object : TypeToken<MutableList<T>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    private fun save(c: Context, key: String, v: Any) = prefs(c).edit().putString(key, Gson().toJson(v)).apply()

    fun incomes(c: Context): MutableList<Income> = load(c, "incomes")
    fun saveIncomes(c: Context, l: List<Income>) = save(c, "incomes", l)
    fun recurring(c: Context): MutableList<Recurring> = load(c, "recurring")
    fun saveRecurring(c: Context, l: List<Recurring>) = save(c, "recurring", l)
    fun accounts(c: Context): MutableList<Account> = load(c, "accounts")
    fun saveAccounts(c: Context, l: List<Account>) = save(c, "accounts", l)
    fun overrides(c: Context): Set<String> = prefs(c).getStringSet("deduct_overrides", emptySet()).orEmpty()
    fun toggleDeductible(c: Context, id: String) {
        val s = overrides(c).toMutableSet(); if (!s.add(id)) s.remove(id)
        prefs(c).edit().putStringSet("deduct_overrides", s).apply()
    }

    /** Adds the recurring expenses/income that came due since the last time (call at start-up). */
    fun applyRecurring(c: Context, now: Calendar = Calendar.getInstance()): Int {
        val list = recurring(c)
        var added = 0
        val exp = Expenses.load(c); val inc = incomes(c)
        list.forEach { r ->
            dueMonths(r.lastKey, r.day, now).forEach { k ->
                val day = Calendar.getInstance().apply { clear(); set(k / 100, k % 100 - 1, 1) }
                day.set(Calendar.DAY_OF_MONTH, r.day.coerceAtMost(day.getActualMaximum(Calendar.DAY_OF_MONTH))); day.set(Calendar.HOUR_OF_DAY, 9)
                if (r.kind == "entrata") inc += Income(UUID.randomUUID().toString(), r.amount, "🔁 " + r.desc, day.timeInMillis)
                else exp += Expense(UUID.randomUUID().toString(), r.amount, "🔁 " + r.desc, r.cat, day.timeInMillis)
                r.lastKey = k; added++
            }
        }
        if (added > 0) { Expenses.save(c, exp); saveIncomes(c, inc); saveRecurring(c, list) }
        return added
    }
}

/** Birthdays saved in the phone's contacts. */
object Birthdays {
    data class Person(val name: String, val month: Int, val day: Int, val year: Int?, val contactId: Long, val manualId: String? = null) {
        /** Days until the next birthday (0 = today). */
        fun daysFrom(now: Calendar): Int {
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val today = next.timeInMillis
            next.set(Calendar.MONTH, month - 1); next.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(next.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (next.timeInMillis < today) next.add(Calendar.YEAR, 1)
            return ((next.timeInMillis - today + 3_600_000L) / 86_400_000L).toInt()
        }
        /** Age reached at the next birthday (or today). */
        fun turning(now: Calendar): Int? = year?.let { y ->
            val thisYear = (now.clone() as Calendar).apply { set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, 1) }
            val passed = month - 1 < now.get(Calendar.MONTH) || (month - 1 == now.get(Calendar.MONTH) && day < now.get(Calendar.DAY_OF_MONTH))
            thisYear.get(Calendar.YEAR) + (if (passed) 1 else 0) - y
        }
    }

    /** "1985-03-14", "--03-14" (no year), "14/03/1985". */
    fun parseDate(s: String): Triple<Int, Int, Int?>? {
        Regex("^(\\d{4}|-)-?(\\d{2})-(\\d{2})").find(s.trim())?.let { m ->
            return Triple(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toIntOrNull())
        }
        Regex("^(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{4}))?").find(s.trim())?.let { m ->
            return Triple(m.groupValues[2].toInt(), m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull())
        }
        return null
    }

    // ---------- birthdays typed in the app ----------

    /** A real calendar day (29 Feb only if the year is a leap year, or unknown). */
    fun validDate(day: Int, month: Int, year: Int?): Boolean {
        if (month !in 1..12 || day < 1) return false
        if (year != null && year !in 1900..2100) return false
        val c = Calendar.getInstance().apply { clear(); set(year ?: 2000, month - 1, 1) }   // 2000 is a leap year: 29 Feb is fine when no year
        return day <= c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private data class Stored(val id: String, val name: String, val month: Int, val day: Int, val year: Int?)
    private fun prefs(c: Context) = c.getSharedPreferences("wl_birthdays", Context.MODE_PRIVATE)

    fun encodeManual(list: List<Person>): String =
        com.google.gson.Gson().toJson(list.map { Stored(it.manualId ?: java.util.UUID.randomUUID().toString(), it.name, it.month, it.day, it.year) })
    fun decodeManual(s: String?): List<Person> = runCatching {
        com.google.gson.Gson().fromJson<List<Stored>>(s, object : com.google.gson.reflect.TypeToken<List<Stored>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().map { Person(it.name, it.month, it.day, it.year, -1L, it.id) }

    fun manual(c: Context): List<Person> = decodeManual(prefs(c).getString("list", null))
    private fun saveManual(c: Context, list: List<Person>) = prefs(c).edit().putString("list", encodeManual(list)).apply()

    /** Adds a new birthday, or replaces the one with [id]. Returns false if the date is not valid. */
    fun saveManual(c: Context, id: String?, name: String, month: Int, day: Int, year: Int?): Boolean {
        if (name.isBlank() || !validDate(day, month, year)) return false
        val list = manual(c).filter { it.manualId != id }
        saveManual(c, list + Person(name.trim(), month, day, year, -1L, id ?: java.util.UUID.randomUUID().toString()))
        return true
    }
    fun removeManual(c: Context, id: String) = saveManual(c, manual(c).filter { it.manualId != id })

    fun hasContactsPermission(c: Context) = androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_CONTACTS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Typed-in birthdays plus the ones in the phone's contacts (if allowed). The typed-in one wins when both exist. */
    fun load(c: Context): List<Person> {
        val all = manual(c) + fromContacts(c)
        return all.distinctBy { Triple(it.name.trim().lowercase(Locale.ITALIAN), it.month, it.day) }
    }

    private fun fromContacts(c: Context): List<Person> {
        if (!hasContactsPermission(c)) return emptyList()
        val out = mutableListOf<Person>()
        runCatching {
            val d = android.provider.ContactsContract.Data.CONTENT_URI
            c.contentResolver.query(d, arrayOf(android.provider.ContactsContract.Data.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Event.START_DATE, android.provider.ContactsContract.Data.CONTACT_ID),
                "${android.provider.ContactsContract.Data.MIMETYPE}=? AND ${android.provider.ContactsContract.CommonDataKinds.Event.TYPE}=?",
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val (m, dd, y) = parseDate(cur.getString(1) ?: continue) ?: continue
                    if (m in 1..12 && dd in 1..31) out += Person(name, m, dd, y?.takeIf { it in 1900..2100 }, cur.getLong(2))
                }
            }
        }
        return out
    }

    /** Upcoming within [days], soonest first. */
    fun upcoming(c: Context, days: Int, now: Calendar = Calendar.getInstance()) =
        load(c).map { it to it.daysFrom(now) }.filter { it.second <= days }.sortedBy { it.second }
}

/** Pure part of the «put it in the calendar» voice command (unit-tested). */
object CalendarVoice {
    /** Returns (phrase without the command words, whether the calendar was named) or null if it is not a calendar command. */
    fun clean(t: String): Pair<String, Boolean>? {
        if (!Regex("^(metti|segna|aggiungi|inserisci|crea|fissa|appuntamento|evento)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(sveglia|timer|musica|canzone|radio|podcast|torcia|lista|spesa)\\b").containsMatchIn(t)) return null
        val hasCal = Regex("\\b(calendario|agenda|appuntamento|evento)\\b").containsMatchIn(t)
        val cleaned = t.replace(Regex("^(metti|segna|aggiungi|inserisci|crea|fissa)\\s+"), "")
            .replace(Regex("^(un |l')?(appuntamento|evento)\\s*(con |per |dal |dalla |da )?"), "")
            .replace(Regex("^(in|nel|al|sul|nell')\\s*(calendario|agenda)\\s*"), "")
            .replace(Regex("\\s+(in|nel|al|sul)\\s+(calendario|agenda)\\b"), "")
        return cleaned to hasCal
    }
}
