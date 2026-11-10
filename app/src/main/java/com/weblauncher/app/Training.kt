package com.weblauncher.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Heart-rate zones from the maximum heart rate. */
object HrZones {
    val NAMES = listOf("Sotto la zona 1", "Zona 1 · riscaldamento", "Zona 2 · base (brucia-grassi)", "Zona 3 · aerobica", "Zona 4 · soglia", "Zona 5 · massimo")
    val COLORS = intArrayOf(0xFF9CA3AF.toInt(), 0xFF38BDF8.toInt(), 0xFF16A34A.toInt(), 0xFFF59E0B.toInt(), 0xFFF97316.toInt(), 0xFFDC2626.toInt())
    /** Tanaka: 208 − 0,7 × età (più realistica di 220 − età). */
    fun maxHr(age: Int) = Math.round(208 - 0.7 * age).toInt()
    fun zone(bpm: Int, max: Int): Int {
        if (bpm <= 0 || max <= 0) return 0
        val p = bpm * 100.0 / max
        return when { p < 50 -> 0; p < 60 -> 1; p < 70 -> 2; p < 80 -> 3; p < 90 -> 4; else -> 5 }
    }
    /** bpm range of a zone (1..5). */
    fun range(zone: Int, max: Int): Pair<Int, Int> {
        val lo = 40 + zone * 10; val hi = if (zone >= 5) 100 else 50 + zone * 10
        return Math.round(max * lo / 100.0).toInt() to Math.round(max * hi / 100.0).toInt()
    }
}

/** Heart-rate strap data and calories from heart rate. Pure. */
object Hr {
    /** Bluetooth "Heart Rate Measurement" (0x2A37): flag bit 0 = value is 16 bit. */
    fun parse(v: ByteArray): Int {
        if (v.size < 2) return 0
        return if (v[0].toInt() and 1 == 0) v[1].toInt() and 0xFF
        else if (v.size >= 3) (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8) else 0
    }

    /** Keytel et al.: kcal per minute from heart rate (sex "m" / "f"; unknown averages the two formulas). */
    fun kcalPerMin(sex: String, hr: Int, weightKg: Double, age: Int): Double {
        val m = (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age) / 4.184
        val f = (-20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age) / 4.184
        return maxOf(0.0, when (sex) { "m" -> m; "f" -> f; else -> (m + f) / 2 })
    }
    /** Calories over a series of (time, bpm); gaps longer than 10 s count as 10 s; readings under 60 bpm are ignored. */
    fun kcalFromSamples(samples: List<Pair<Long, Int>>, sex: String, weightKg: Double, age: Int): Double {
        var kcal = 0.0
        samples.forEachIndexed { i, (t, hr) ->
            val next = samples.getOrNull(i + 1)?.first ?: (t + 1000)
            val dt = minOf(next - t, 10_000L).coerceAtLeast(0) / 60_000.0
            if (hr >= 60) kcal += kcalPerMin(sex, hr, if (weightKg > 0) weightKg else 70.0, age) * dt
        }
        return kcal
    }
    fun avg(samples: List<Pair<Long, Int>>) = samples.map { it.second }.filter { it > 30 }.let { if (it.isEmpty()) 0 else it.average().toInt() }
    fun max(samples: List<Pair<Long, Int>>) = samples.maxOfOrNull { it.second } ?: 0

    /** Heart rate recovery: how much it falls in one minute after effort. */
    fun recoveryLabel(drop: Int) = when { drop >= 25 -> "Ottimo"; drop >= 18 -> "Buono"; drop >= 12 -> "Nella media"; else -> "Basso: con più allenamento migliora" }
}

/** Run/walk intervals for a GPS workout. */
object Intervals {
    data class Preset(val label: String, val runS: Int, val walkS: Int, val rounds: Int)
    val PRESETS = listOf(
        Preset("Principiante: 1 min di corsa, 2 di camminata, ×8", 60, 120, 8),
        Preset("Intermedio: 3 min di corsa, 1 di camminata, ×6", 180, 60, 6),
        Preset("Avanzato: 5 min di corsa, 1 di camminata, ×5", 300, 60, 5),
        Preset("Ripetute: 30 s forte, 90 s piano, ×10", 30, 90, 10)
    )
    data class Pos(val hard: Boolean, val remaining: Int, val round: Int, val done: Boolean)
    fun at(elapsedSec: Int, hardS: Int, easyS: Int, rounds: Int): Pos {
        val cycle = (hardS + easyS).coerceAtLeast(1)
        if (elapsedSec >= cycle * rounds) return Pos(false, 0, rounds, true)
        val r = elapsedSec / cycle; val i = elapsedSec % cycle
        return if (i < hardS) Pos(true, hardS - i, r + 1, false) else Pos(false, cycle - i, r + 1, false)
    }
    fun words(kind: String, hard: Boolean) = when (kind) {
        "camminata" -> if (hard) "Passo veloce" else "Passo lento"
        "bici" -> if (hard) "Spingi" else "Pedala piano"
        else -> if (hard) "Corri" else "Cammina"
    }
}

/** Numbers and times as they should be spoken. */
object Say {
    fun duration(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        val mm = if (m == 1) "un minuto" else "$m minuti"
        return when { m == 0 -> "$s secondi"; s == 0 -> mm; else -> "$mm e $s secondi" }
    }
    fun pace(speedKmh: Double): String {
        if (speedKmh < 1.0) return "non disponibile"
        val minPerKm = 60.0 / speedKmh; var m = minPerKm.toInt(); var s = Math.round((minPerKm - m) * 60).toInt()
        if (s == 60) { m++; s = 0 }
        return if (s == 0) "$m minuti al chilometro" else "$m e $s al chilometro"
    }
}

/** A voice that talks to the athlete (own engine: works in the background service and in the screens). */
class Coach(c: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<Pair<String, Boolean>>()
    init {
        tts = TextToSpeech(c.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.ITALIAN
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                }
                ready = true
                pending.toList().forEach { (t, f) -> say(t, f) }; pending.clear()
            }
        }
    }
    fun say(text: String, flush: Boolean = false) {
        if (!ready) { pending += text to flush; return }
        runCatching { tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) }
    }
    fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null }
}

fun vibrateNow(c: Context, ms: Long = 400) {
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** GPX export of a workout (opens in Strava, Komoot, Garmin Connect…). */
object Gpx {
    fun build(w: Workout, pts: List<Pt>, name: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Web Launcher\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("<metadata><time>${iso.format(Date(w.start))}</time></metadata>\n<trk><name>${esc(name)}</name><type>${esc(w.kind)}</type><trkseg>\n")
        pts.forEach { p ->
            sb.append("<trkpt lat=\"${"%.6f".format(Locale.ROOT, p.lat)}\" lon=\"${"%.6f".format(Locale.ROOT, p.lon)}\">")
            if (p.alt != 0.0) sb.append("<ele>${"%.1f".format(Locale.ROOT, p.alt)}</ele>")
            sb.append("<time>${iso.format(Date(p.t))}</time>")
            if (p.hr > 0) sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${p.hr}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }
}

data class Symptom(val id: String, val what: String, val level: Int, val note: String, val time: Long)

/** Symptom journal: what, how strong (1–10), when. */
object Symptoms {
    val COMMON = listOf("Mal di testa", "Mal di schiena", "Mal di stomaco", "Nausea", "Stanchezza", "Vertigini", "Dolore al petto", "Palpitazioni", "Tosse", "Febbre", "Insonnia", "Dolore articolare")
    private fun prefs(c: Context) = c.getSharedPreferences("wl_care", Context.MODE_PRIVATE)
    fun load(c: Context): List<Symptom> = runCatching {
        Gson().fromJson<List<Symptom>>(prefs(c).getString("symptoms", null), object : TypeToken<List<Symptom>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().sortedByDescending { it.time }
    fun add(c: Context, s: Symptom) = prefs(c).edit().putString("symptoms", Gson().toJson((load(c) + s).take(1000))).apply()
    fun remove(c: Context, id: String) = prefs(c).edit().putString("symptoms", Gson().toJson(load(c).filter { it.id != id })).apply()
    fun newId() = UUID.randomUUID().toString()

    /** For the report: the count of each symptom, then the most recent entries. */
    fun reportLines(list: List<Symptom>): List<String> {
        if (list.isEmpty()) return emptyList()
        val day = SimpleDateFormat("d MMM", Locale.ITALIAN)
        val counts = list.groupBy { it.what }.map { (k, v) -> "$k: ${v.size} volte (intensità media ${"%.1f".format(Locale.ITALIAN, v.map { it.level }.average())}/10)" }
        return counts + list.take(10).map { "${day.format(Date(it.time))}: ${it.what} ${it.level}/10" + if (it.note.isNotBlank()) " — ${it.note}" else "" }
    }
}

/** The next time that falls on [dow] (Calendar.MONDAY…) at "HH:MM". */
fun nextWeekday(dow: Int, hhmm: String, now: Long = System.currentTimeMillis()): Long {
    val (h, m) = hhmm.split(':').map { it.toInt() }
    val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    var guard = 0
    while ((c.get(Calendar.DAY_OF_WEEK) != dow || c.timeInMillis <= now) && guard++ < 14) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

/** Settings of the workout recorder. */
object WorkoutOpts {
    private fun p(c: Context) = c.getSharedPreferences("wl_fitness", Context.MODE_PRIVATE)
    fun voiceKm(c: Context) = p(c).getBoolean("voice_km", true)
    fun setVoiceKm(c: Context, on: Boolean) = p(c).edit().putBoolean("voice_km", on).apply()
    fun voicePlans(c: Context) = p(c).getBoolean("voice_plans", true)
    fun setVoicePlans(c: Context, on: Boolean) = p(c).edit().putBoolean("voice_plans", on).apply()
    fun voiceTabata(c: Context) = p(c).getBoolean("voice_tabata", true)
    fun setVoiceTabata(c: Context, on: Boolean) = p(c).edit().putBoolean("voice_tabata", on).apply()
    /** 0 = off, 1..5 = stay in this zone. */
    fun zoneTarget(c: Context) = p(c).getInt("zone_target", 0)
    fun setZoneTarget(c: Context, z: Int) = p(c).edit().putInt("zone_target", z).apply()
    /** hard seconds, easy seconds, rounds; rounds 0 = intervals off. */
    fun intervals(c: Context) = Triple(p(c).getInt("iv_hard", 0), p(c).getInt("iv_easy", 0), p(c).getInt("iv_rounds", 0))
    fun setIntervals(c: Context, hard: Int, easy: Int, rounds: Int) = p(c).edit().putInt("iv_hard", hard).putInt("iv_easy", easy).putInt("iv_rounds", rounds).apply()
    fun maxHr(c: Context): Int {
        val custom = p(c).getInt("max_hr", 0); if (custom > 0) return custom
        val by = Food.profile(c).birthYear
        return HrZones.maxHr(if (by > 0) Nutrition.ageFrom(by) else 40)
    }
    fun setMaxHr(c: Context, v: Int) = p(c).edit().putInt("max_hr", v).apply()
    fun age(c: Context) = Food.profile(c).birthYear.let { if (it > 0) Nutrition.ageFrom(it) else 40 }
}
