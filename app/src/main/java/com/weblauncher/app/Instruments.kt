package com.weblauncher.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Pure calculations behind the measuring tools (unit-tested, no Android classes). */
object Instruments {
    // ---------- altimeter ----------

    /** Barometric altitude (m) from pressure (hPa) and the sea-level pressure of the day. */
    fun altitude(pressure: Double, seaLevel: Double = 1013.25): Double = 44330.0 * (1 - (pressure / seaLevel).pow(1 / 5.255))

    /** Sea-level pressure that makes [pressure] correspond to a known [altitude]: calibration by hand. */
    fun seaLevelFor(pressure: Double, altitude: Double): Double = pressure / (1 - altitude / 44330.0).pow(5.255)

    // ---------- distances ----------

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------- sound ----------

    /** Approximate dB(SPL) from 16-bit samples; phones are not calibrated, so it is an indication. */
    fun decibels(samples: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / n)
        return if (rms < 1) 0.0 else (20 * log10(rms / 32768.0) + 94).coerceIn(0.0, 130.0)
    }

    fun soundLabel(db: Double) = when {
        db < 30 -> "Silenzio"; db < 45 -> "Stanza tranquilla"; db < 60 -> "Conversazione"; db < 70 -> "Ufficio, TV"
        db < 80 -> "Traffico"; db < 90 -> "Rumoroso: a lungo affatica"; else -> "Molto forte: può danneggiare l'udito"
    }

    // ---------- tip & split ----------

    data class Split(val tip: Double, val total: Double, val each: Double)
    fun split(bill: Double, tipPct: Double, people: Int, roundUp: Boolean = false): Split {
        val tip = bill * tipPct / 100
        val total = bill + tip
        var each = total / people.coerceAtLeast(1)
        if (roundUp) each = kotlin.math.ceil(each - 1e-9)
        return Split(tip, if (roundUp) each * people.coerceAtLeast(1) else total, each)
    }

    // ---------- Tabata / interval timer ----------

    data class Phase(val kind: String, val seconds: Int, val round: Int)
    /** kind: "prepara" | "lavoro" | "riposo". No rest after the last round. */
    fun tabata(prepare: Int, work: Int, rest: Int, rounds: Int): List<Phase> {
        val out = mutableListOf<Phase>()
        if (prepare > 0) out += Phase("prepara", prepare, 0)
        for (r in 1..rounds) {
            out += Phase("lavoro", work, r)
            if (r < rounds && rest > 0) out += Phase("riposo", rest, r)
        }
        return out
    }
    fun fmtClock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    // ---------- sun & moon ----------

    /** Sunrise and sunset (local millis) for a date and place; null in polar day/night. NOAA's simplified method. */
    fun sunTimes(lat: Double, lon: Double, day: Calendar): Pair<Long, Long>? {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
        }
        val n = utc.get(Calendar.DAY_OF_YEAR)
        val gamma = 2 * PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val cosH = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val midnight = utc.timeInMillis - 12 * 3_600_000L
        val rise = 720 - 4 * (lon + ha) - eqTime   // minutes UTC
        val set = 720 - 4 * (lon - ha) - eqTime
        return (midnight + (rise * 60_000).toLong()) to (midnight + (set * 60_000).toLong())
    }

    /** Moon age in days (0 = new, ~14.77 = full) and illuminated fraction 0..1. */
    fun moon(timeMillis: Long): Pair<Double, Double> {
        val synodic = 29.530588853
        val newMoon = 947182440000L // 2000-01-06 18:14 UTC
        val age = (((timeMillis - newMoon) / 86_400_000.0) % synodic + synodic) % synodic
        return age to (1 - cos(2 * PI * age / synodic)) / 2
    }
    fun moonName(age: Double) = when {
        age < 1.85 -> "🌑 Luna nuova"; age < 5.54 -> "🌒 Falce crescente"; age < 9.23 -> "🌓 Primo quarto"
        age < 12.92 -> "🌔 Gibbosa crescente"; age < 16.61 -> "🌕 Luna piena"; age < 20.30 -> "🌖 Gibbosa calante"
        age < 23.99 -> "🌗 Ultimo quarto"; age < 27.68 -> "🌘 Falce calante"; else -> "🌑 Luna nuova"
    }
    /** Days until the next full moon. */
    fun daysToFullMoon(age: Double): Double { val full = 29.530588853 / 2; return if (age <= full) full - age else 29.530588853 - age + full }

    // ---------- air quality ----------

    /** European AQI bands. */
    fun aqiLabel(aqi: Int) = when {
        aqi <= 20 -> "🟢 Buona"; aqi <= 40 -> "🟢 Discreta"; aqi <= 60 -> "🟡 Moderata"; aqi <= 80 -> "🟠 Scadente"
        aqi <= 100 -> "🔴 Molto scadente"; else -> "🟣 Pessima"
    }
    fun pollenLabel(grains: Double) = when { grains < 1 -> "assente"; grains < 10 -> "basso"; grains < 50 -> "medio"; else -> "alto" }

    // ---------- earthquakes (INGV, text format) ----------

    data class Quake(val time: Long, val lat: Double, val lon: Double, val depthKm: Double, val mag: Double, val place: String)
    /** INGV fdsnws "format=text": #EventID|Time|Latitude|Longitude|Depth/Km|…|MagType|Magnitude|MagAuthor|EventLocationName|EventType */
    fun parseQuakes(text: String): List<Quake> {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 13) return@mapNotNull null
            runCatching {
                Quake(f.parse(p[1].take(19))!!.time, p[2].toDouble(), p[3].toDouble(), p[4].toDoubleOrNull() ?: 0.0, p[10].toDouble(), p[12].trim())
            }.getOrNull()
        }.toList()
    }

    // ---------- ruler ----------

    /** Pixels per millimetre from the screen's real density (dots per inch). */
    fun pxPerMm(dpi: Float) = dpi / 25.4f

    /** Tilt of the phone's long edge from the horizontal, in degrees, from the accelerometer. */
    fun tiltDegrees(x: Float, y: Float): Double = Math.toDegrees(atan2(x.toDouble(), y.toDouble()))
}

/** Rubbish collection calendar: which kinds are collected on each weekday (Calendar.MONDAY…). */
object Waste {
    val KINDS = listOf("🟤 Umido", "⚪ Indifferenziato", "🟡 Plastica", "🔵 Carta", "🟢 Vetro", "🌿 Verde", "🥫 Metalli")
    val DAYS = listOf(Calendar.MONDAY to "Lunedì", Calendar.TUESDAY to "Martedì", Calendar.WEDNESDAY to "Mercoledì", Calendar.THURSDAY to "Giovedì",
        Calendar.FRIDAY to "Venerdì", Calendar.SATURDAY to "Sabato", Calendar.SUNDAY to "Domenica")

    /** "2:🟤 Umido,🔵 Carta;5:🟡 Plastica" <-> map */
    fun decode(s: String?): Map<Int, List<String>> = s.orEmpty().split(';').mapNotNull { e ->
        val d = e.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        d to e.substringAfter(':').split(',').filter { it.isNotBlank() }
    }.filter { it.second.isNotEmpty() }.toMap()
    fun encode(m: Map<Int, List<String>>) = m.filter { it.value.isNotEmpty() }.entries.joinToString(";") { "${it.key}:${it.value.joinToString(",")}" }

    /** What to put out for the day after [now]. */
    fun tomorrow(m: Map<Int, List<String>>, now: Calendar): List<String> {
        val t = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return m[t.get(Calendar.DAY_OF_WEEK)].orEmpty()
    }
}
