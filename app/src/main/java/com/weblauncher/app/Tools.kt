package com.weblauncher.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.Locale

// ======================= Calculator & unit converter =======================

object Calc {
    private val nf = java.text.DecimalFormat("#,##0.########", java.text.DecimalFormatSymbols(Locale.ITALIAN))

    fun fmt(v: Double): String = if (v.isNaN() || v.isInfinite()) "?" else nf.format(Math.round(v * 1e8) / 1e8)

    /** "15% di 230", "230 + 15%", "(2+3)*4", "10 km in miglia". Null when the text is not a calculation. */
    fun evaluate(raw: String): String? {
        val s = raw.trim().lowercase(Locale.ITALIAN).replace("×", "*").replace("÷", "/").replace(" x ", " * ")
        Regex("^([\\d.,]+)\\s*%\\s*(?:di|of|del|della)\\s*([\\d.,]+)$").find(s)?.let {
            val p = num(it.groupValues[1]) ?: return null; val b = num(it.groupValues[2]) ?: return null
            return "${fmt(p)}% di ${fmt(b)} = ${fmt(b * p / 100)}"
        }
        Regex("^([\\d.,]+)\\s*([+-])\\s*([\\d.,]+)\\s*%$").find(s)?.let {
            val b = num(it.groupValues[1]) ?: return null; val p = num(it.groupValues[3]) ?: return null
            val r = if (it.groupValues[2] == "+") b * (1 + p / 100) else b * (1 - p / 100)
            return "= ${fmt(r)}"
        }
        unit(s)?.let { return it }
        if (!Regex("^[\\d\\s.,+\\-*/^()%]+$").matches(s)) return null
        if (!Regex("\\d\\s*[+\\-*/^%]\\s*[\\d(]").containsMatchIn(s.replace(Regex("\\s+"), ""))) return null
        return runCatching { "= " + fmt(Parser(s.replace(Regex("\\s+"), "")).parse()) }.getOrNull()
    }

    fun num(t: String): Double? {
        var x = t.trim()
        if (x.contains(',') && x.contains('.')) x = x.replace(".", "").replace(',', '.')
        else x = x.replace(',', '.')
        return x.toDoubleOrNull()
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException(); return v }
        fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) { val op = s[i++]; val r = pow(); v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r } }; return v }
        fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else if (i < s.length && s[i] == '+') { i++; unary() } else atom()
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            return num(s.substring(st, i)) ?: throw IllegalArgumentException()
        }
    }

    private val units = mapOf(
        "km" to ("len" to 1000.0), "chilometri" to ("len" to 1000.0), "m" to ("len" to 1.0), "metri" to ("len" to 1.0),
        "cm" to ("len" to 0.01), "mm" to ("len" to 0.001), "mi" to ("len" to 1609.344), "miglia" to ("len" to 1609.344),
        "ft" to ("len" to 0.3048), "piedi" to ("len" to 0.3048), "in" to ("len" to 0.0254), "pollici" to ("len" to 0.0254),
        "kg" to ("mass" to 1.0), "chili" to ("mass" to 1.0), "g" to ("mass" to 0.001), "grammi" to ("mass" to 0.001),
        "lb" to ("mass" to 0.45359237), "libbre" to ("mass" to 0.45359237), "oz" to ("mass" to 0.0283495), "once" to ("mass" to 0.0283495),
        "l" to ("vol" to 1.0), "litri" to ("vol" to 1.0), "ml" to ("vol" to 0.001), "gal" to ("vol" to 3.78541), "galloni" to ("vol" to 3.78541)
    )

    private fun unit(s: String): String? {
        val m = Regex("^([\\d.,]+)\\s*(°?[a-zà-ù]+)\\s+(?:in|a|to)\\s+(°?[a-zà-ù]+)$").find(s) ?: return null
        val v = num(m.groupValues[1]) ?: return null
        val from = m.groupValues[2].removePrefix("°"); val to = m.groupValues[3].removePrefix("°")
        val temp = setOf("c", "celsius", "f", "fahrenheit")
        if (from in temp && to in temp) {
            val r = if (from.startsWith("c") && to.startsWith("f")) v * 9 / 5 + 32 else if (from.startsWith("f") && to.startsWith("c")) (v - 32) * 5 / 9 else v
            return "${fmt(v)}°${from.first().uppercase()} = ${fmt(r)}°${to.first().uppercase()}"
        }
        val a = units[from] ?: return null; val b = units[to] ?: return null
        if (a.first != b.first) return null
        return "${fmt(v)} $from = ${fmt(v * a.second / b.second)} $to"
    }

    private val currencies = mapOf(
        "euro" to "EUR", "eur" to "EUR", "€" to "EUR", "dollari" to "USD", "dollaro" to "USD", "usd" to "USD", "$" to "USD",
        "sterline" to "GBP", "sterlina" to "GBP", "gbp" to "GBP", "£" to "GBP", "franchi" to "CHF", "chf" to "CHF",
        "yen" to "JPY", "jpy" to "JPY", "zloty" to "PLN", "pln" to "PLN", "corone" to "SEK", "sek" to "SEK"
    )

    /** "50 dollari in euro" -> (50, USD, EUR). */
    fun currencyQuery(raw: String): Triple<Double, String, String>? {
        val m = Regex("^([\\d.,]+)\\s*([a-z€$£]+)\\s+(?:in|a|to)\\s+([a-z€$£]+)$").find(raw.trim().lowercase(Locale.ITALIAN)) ?: return null
        val v = num(m.groupValues[1]) ?: return null
        val f = currencies[m.groupValues[2]] ?: return null; val t = currencies[m.groupValues[3]] ?: return null
        return if (f == t) null else Triple(v, f, t)
    }

    /** Blocking: ECB rates through frankfurter (free, no key). */
    fun convert(v: Double, from: String, to: String): String? = runCatching {
        val json = java.net.URL("https://api.frankfurter.app/latest?amount=$v&from=$from&to=$to").openStream()
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val r = org.json.JSONObject(json).getJSONObject("rates").getDouble(to)
        "${fmt(v)} $from = ${fmt(Math.round(r * 100) / 100.0)} $to"
    }.getOrNull()
}

// ======================= Parcels =======================

object Parcels {
    data class Parcel(val code: String, val label: String, val carrier: String, val time: Long)

    fun looksLikeTracking(raw: String): Boolean {
        val c = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
        if (!Regex("^[A-Z0-9]{9,30}$").matches(c)) return false
        if (Regex("^(\\+?39)?3\\d{8,9}$").matches(c)) return false
        val digits = c.count { it.isDigit() }
        return digits >= 8 && (c.any { it.isLetter() } || c.length >= 10)
    }

    fun carrier(raw: String): Pair<String, String> {
        val c = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
        return when {
            Regex("^TBA\\d{12}$").matches(c) -> "Amazon" to "https://track.amazon.it/tracking/$c"
            Regex("^1Z[0-9A-Z]{16}$").matches(c) -> "UPS" to "https://www.ups.com/track?loc=it_IT&tracknum=$c"
            Regex("^[A-Z]{2}\\d{9}IT$").matches(c) -> "Poste Italiane" to "https://www.poste.it/cerca/index.html#/risultati-spedizioni/$c"
            Regex("^\\d{10}$").matches(c) -> "DHL" to "https://www.dhl.com/it-it/home/tracking/tracking-express.html?submit=1&tracking-id=$c"
            Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$").matches(c) -> "Posta internazionale" to "https://t.17track.net/it#nums=$c"
            else -> "Corriere (riconoscimento automatico)" to "https://t.17track.net/it#nums=$c"
        }
    }

    fun load(c: Context): MutableList<Parcel> = runCatching {
        Gson().fromJson<MutableList<Parcel>>(c.getSharedPreferences("wl_tools", Context.MODE_PRIVATE).getString("parcels", "[]"),
            object : TypeToken<MutableList<Parcel>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun save(c: Context, list: List<Parcel>) {
        c.getSharedPreferences("wl_tools", Context.MODE_PRIVATE).edit().putString("parcels", Gson().toJson(list)).apply()
    }
}

// ======================= Phishing guard =======================

object PhishingGuard {
    private val brands = mapOf(
        "poste" to setOf("poste.it", "posteitaliane.it", "postepay.it", "bancopostaimpresaonline.it"),
        "postepay" to setOf("poste.it", "postepay.it"),
        "bancoposta" to setOf("poste.it"),
        "inps" to setOf("inps.it"),
        "agenziaentrate" to setOf("agenziaentrate.gov.it", "agenziaentrate.it"),
        "intesa" to setOf("intesasanpaolo.com", "intesasanpaolo.it"),
        "intesasanpaolo" to setOf("intesasanpaolo.com", "intesasanpaolo.it"),
        "unicredit" to setOf("unicredit.it", "unicredit.eu", "unicreditgroup.eu"),
        "bper" to setOf("bper.it"),
        "fineco" to setOf("finecobank.com", "fineco.it"),
        "mediolanum" to setOf("bancamediolanum.it"),
        "bnl" to setOf("bnl.it"),
        "amazon" to setOf("amazon.it", "amazon.com", "amazon.de", "amazon.fr", "amazon.es", "amazon.co.uk", "amazonaws.com",
            "media-amazon.com", "ssl-images-amazon.com", "amazon.dev"),
        "paypal" to setOf("paypal.com", "paypal.it", "paypalobjects.com", "paypal.me"),
        "brt" to setOf("brt.it", "mybrt.it"),
        "gls" to setOf("gls-group.com", "gls-italy.com", "gls-group.eu"),
        "dhl" to setOf("dhl.com", "dhl.it", "dhl.de"),
        "sda" to setOf("sda.it"),
        "netflix" to setOf("netflix.com", "nflxext.com", "nflximg.net", "nflxvideo.net"),
        "apple" to setOf("apple.com", "icloud.com", "apple.news"),
        "icloud" to setOf("icloud.com", "apple.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "outlook.com", "office.com", "microsoftonline.com", "msn.com"),
        "nexi" to setOf("nexi.it", "nexigroup.com"),
        "satispay" to setOf("satispay.com"),
        "enel" to setOf("enel.it", "enel.com"),
        "vodafone" to setOf("vodafone.it", "vodafone.com"),
        "windtre" to setOf("windtre.it"),
        "iliad" to setOf("iliad.it"),
        "fastweb" to setOf("fastweb.it"),
        "spid" to setOf("spid.gov.it", "gov.it"),
        "aruba" to setOf("aruba.it", "pec.it"),
        "telepass" to setOf("telepass.com", "telepass.it"),
        "autostrade" to setOf("autostrade.it")
    )
    private val names = mapOf(
        "poste" to "Poste Italiane", "postepay" to "Postepay", "bancoposta" to "BancoPosta", "inps" to "INPS",
        "agenziaentrate" to "Agenzia delle Entrate", "intesa" to "Intesa Sanpaolo", "intesasanpaolo" to "Intesa Sanpaolo"
    )
    private val shortExact = setOf("brt", "gls", "dhl", "sda", "bnl", "inps", "bper", "enel", "spid", "nexi", "apple", "poste")
    private val secondLevel = setOf("co.uk", "com.au", "gov.it", "com.br", "co.jp", "org.uk", "gov.uk")
    private val riskyTld = setOf("top", "xyz", "click", "shop", "icu", "cyou", "buzz", "live", "online", "site", "info", "rest", "support", "help", "monster", "sbs", "cfd")
    private val bait = listOf("login", "verifica", "sicurezza", "account", "aggiorna", "conferma", "rimborso", "pacco",
        "consegna", "sblocca", "bloccato", "accesso", "pagamento", "fattura", "premio")

    fun registered(host: String): String {
        val l = host.lowercase(Locale.ROOT).trimEnd('.').split('.')
        if (l.size <= 2) return l.joinToString(".")
        val last2 = l.takeLast(2).joinToString(".")
        return if (last2 in secondLevel) l.takeLast(3).joinToString(".") else last2
    }

    /** A short Italian warning, or null if the address looks fine. */
    fun check(url: String): String? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val reg = registered(host)
        if (host.contains("xn--")) return "L'indirizzo usa caratteri speciali che possono imitare lettere normali ($host)."
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) return "Il sito è un indirizzo numerico ($host): i siti veri usano un nome."
        val tokens = host.split('.', '-').filter { it.isNotEmpty() }
        for ((brand, official) in brands) {
            val hit = tokens.any { t -> t == brand || (brand !in shortExact && brand.length >= 5 && t.startsWith(brand) && t.length <= brand.length + 9) }
            if (hit && official.none { reg == it || host == it || host.endsWith(".$it") }) {
                val name = names[brand] ?: brand.replaceFirstChar { it.uppercase() }
                return "Questo sito ($host) usa il nome «$name» ma non è il sito ufficiale (${official.first()}). Potrebbe essere una truffa."
            }
        }
        val tld = host.substringAfterLast('.')
        val baitCount = bait.count { host.contains(it) || (uri.path ?: "").lowercase(Locale.ROOT).contains(it) }
        if (tld in riskyTld && baitCount >= 1 && host.count { it == '-' } >= 2) {
            return "L'indirizzo ($host) ha le caratteristiche tipiche dei siti truffa (dominio .$tld con parole come «verifica» o «accesso»)."
        }
        return null
    }
}

// ======================= Reminders =======================

data class Reminder(val id: Int, val text: String, val at: Long, var done: Boolean = false, val repeat: String? = null)

data class ReminderParse(val text: String, val at: Long, val repeat: String?)

object Reminders {
    private const val CHANNEL = "promemoria"
    private fun prefs(c: Context) = c.getSharedPreferences("wl_tools", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Reminder> = runCatching {
        Gson().fromJson<MutableList<Reminder>>(prefs(c).getString("reminders", "[]"), object : TypeToken<MutableList<Reminder>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun save(c: Context, list: List<Reminder>) { prefs(c).edit().putString("reminders", Gson().toJson(list)).apply() }

    val REPEAT_LABELS = mapOf("day" to "ogni giorno", "weekdays" to "dal lunedì al venerdì", "week" to "ogni settimana", "month" to "ogni mese")

    /** Next occurrence after now for a recurring reminder. */
    fun next(at: Long, repeat: String, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        var guard = 0
        while ((c.timeInMillis <= now || (repeat == "weekdays" && c.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY))) && guard++ < 1000) {
            when (repeat) {
                "week" -> c.add(Calendar.DAY_OF_YEAR, 7)
                "month" -> c.add(Calendar.MONTH, 1)
                else -> c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    fun add(c: Context, text: String, at: Long, repeat: String? = null): Reminder {
        val r = Reminder((System.nanoTime() % Int.MAX_VALUE).toInt().let { if (it < 0) -it else it }, text, at, false, repeat)
        save(c, load(c) + r)
        schedule(c, r)
        return r
    }

    fun remove(c: Context, id: Int) {
        save(c, load(c).filter { it.id != id })
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id, ""))
    }

    private fun pending(c: Context, id: Int, text: String): PendingIntent =
        PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, r: Reminder) {
        if (r.done || r.at < System.currentTimeMillis() - 60_000) return
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, r.id, r.text)
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    fun rescheduleAll(c: Context) = load(c).forEach { schedule(c, it) }

    fun notify(c: Context, id: Int, text: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Promemoria", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "reminders")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("⏰ Promemoria")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(id, n) }
        val list = load(c)
        val r = list.firstOrNull { it.id == id }
        if (r?.repeat != null) {
            val again = r.copy(at = next(r.at, r.repeat), done = false)
            save(c, list.map { if (it.id == id) again else it })
            schedule(c, again)
        } else save(c, list.map { if (it.id == id) it.copy(done = true) else it })
    }

    /**
     * Italian phrases: "ricordami domani alle 9 di chiamare Marco", "tra 20 minuti spegnere il forno",
     * "venerdì alle 18:30 palestra", "il 12/11 alle 10 dentista". Returns (text, time) or null.
     */
    fun parse(raw: String, now: Calendar = Calendar.getInstance()): ReminderParse? {
        var s = " " + raw.trim().lowercase(Locale.ITALIAN) + " "
        var repeat: String? = null
        var monthDay = 0
        var defaultHour = 9
        Regex("\\b(?:il|ogni)\\s+(\\d{1,2})\\s+(?:di ogni|del|di)\\s+mese\\b").find(s)?.let { m ->
            repeat = "month"; monthDay = m.groupValues[1].toInt(); s = s.replace(m.value, " ")
        }
        Regex("\\b(dal lunedì al venerdì|dal lunedi al venerdi|nei giorni feriali|giorni feriali|ogni giorno feriale)\\b").find(s)?.let { m ->
            repeat = "weekdays"; s = s.replace(m.value, " ")
        }
        Regex("\\b(ogni giorno|tutti i giorni|ogni mattina|tutte le mattine|ogni sera|tutte le sere)\\b").find(s)?.let { m ->
            repeat = "day"; if (m.value.contains("sera")) defaultHour = 20 else if (m.value.contains("mattin")) defaultHour = 8
            s = s.replace(m.value, " ")
        }
        Regex("\\bogni settimana\\b").find(s)?.let { m -> repeat = "week"; s = s.replace(m.value, " ") }
        Regex("\\bogni\\s+(?=(lunedi|lunedì|martedi|martedì|mercoledi|mercoledì|giovedi|giovedì|venerdi|venerdì|sabato|domenica)\\b)").find(s)?.let { m ->
            repeat = "week"; s = s.replaceFirst(m.value, " ")
        }
        Regex("\\bogni mese\\b").find(s)?.let { m -> repeat = "month"; s = s.replace(m.value, " ") }
        s = s.replace(Regex("^\\s*(ricordami|ricorda(mi)?|promemoria|avvisami)\\s+(di\\s+|che\\s+)?"), " ")
        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        var found = false
        Regex("\\b(?:tra|fra)\\s+(\\d+|un|una|mezz'?ora)\\s*'?\\s*(minuti|minuto|min|ore|ora|giorni|giorno)?\\b").find(s)?.let { m ->
            val n = when (m.groupValues[1]) { "un", "una" -> 1; "mezz'ora", "mezzora" -> 30; else -> m.groupValues[1].toInt() }
            val u = m.groupValues[2]
            when {
                m.groupValues[1].startsWith("mezz") -> cal.add(Calendar.MINUTE, 30)
                u.startsWith("or") -> cal.add(Calendar.HOUR_OF_DAY, n)
                u.startsWith("giorn") -> cal.add(Calendar.DAY_OF_YEAR, n)
                else -> cal.add(Calendar.MINUTE, n)
            }
            s = s.replace(m.value, " "); found = true
        }
        if (!found) {
            var dayChosen = monthDay > 0
            if (monthDay > 0) {
                cal.set(Calendar.DAY_OF_MONTH, monthDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            Regex("\\b(oggi|stasera|domani|dopodomani)\\b").find(s)?.let { m ->
                when (m.groupValues[1]) { "domani" -> cal.add(Calendar.DAY_OF_YEAR, 1); "dopodomani" -> cal.add(Calendar.DAY_OF_YEAR, 2) }
                if (m.groupValues[1] == "stasera") { cal.set(Calendar.HOUR_OF_DAY, 20); cal.set(Calendar.MINUTE, 0) }
                s = s.replace(m.value, " "); dayChosen = true; found = m.groupValues[1] == "stasera"
            }
            val dayIndex = mapOf("dom" to 1, "lun" to 2, "mar" to 3, "mer" to 4, "gio" to 5, "ven" to 6, "sab" to 7)
            Regex("\\b(lunedi|lunedì|martedi|martedì|mercoledi|mercoledì|giovedi|giovedì|venerdi|venerdì|sabato|domenica)\\b").find(s)?.let { m ->
                val target = dayIndex[m.groupValues[1].take(3)] ?: return@let
                var diff = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0) diff = 7
                cal.add(Calendar.DAY_OF_YEAR, diff)
                s = s.replace(m.value, " "); dayChosen = true
            }
            Regex("\\b(?:il\\s+)?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b").find(s)?.let { m ->
                cal.set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
                cal.set(Calendar.MONTH, m.groupValues[2].toInt() - 1)
                m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { y -> cal.set(Calendar.YEAR, if (y < 100) 2000 + y else y) }
                s = s.replace(m.value, " "); dayChosen = true
            }
            Regex("\\b(?:alle|all'|a|ore|per le)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\b").find(s)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 1..7 && !s.contains("mattina")) h += 12
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                if (!dayChosen && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
                s = s.replace(m.value, " "); found = true
            }
            if (dayChosen && !found) { cal.set(Calendar.HOUR_OF_DAY, defaultHour); cal.set(Calendar.MINUTE, 0); found = true }
            if (!found && repeat != null) { cal.set(Calendar.HOUR_OF_DAY, defaultHour); cal.set(Calendar.MINUTE, 0); found = true }
        }
        if (!found) return null
        val text = s.replace(Regex("\\b(di mattina|del mattino|di sera|mattina|pomeriggio)\\b"), " ")
            .replace(Regex("^\\s*(di|che|a|per)\\s+"), " ").replace(Regex("\\s+"), " ").trim()
            .replaceFirstChar { it.uppercase() }
        if (text.isEmpty()) return null
        val r = repeat
        val at = if (r != null) next(cal.timeInMillis, r, now.timeInMillis) else cal.timeInMillis
        if (at < now.timeInMillis - 60_000) return null
        return ReminderParse(text, at, r)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") {
            Reminders.rescheduleAll(context); return
        }
        Reminders.notify(context, intent.getIntExtra("id", 0), intent.getStringExtra("text").orEmpty())
    }
}
