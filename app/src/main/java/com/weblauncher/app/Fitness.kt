package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** One recorded GPS point. */
data class Pt(val lat: Double, val lon: Double, val t: Long, val alt: Double = 0.0, val hr: Int = 0)

/** Accumulates a GPS track: filters bad fixes and measures distance, moving time, top speed and climb. Pure (unit-tested). */
class Track {
    val points = mutableListOf<Pt>()
    var distanceM = 0.0; private set
    var movingMs = 0L; private set
    var maxKmh = 0.0; private set
    var gainM = 0.0; private set
    private var lastAlt = Double.NaN

    /** Returns true if the point was used. [maxSpeedMs] drops impossible jumps (a bad fix). */
    fun add(lat: Double, lon: Double, timeMs: Long, accuracyM: Float, alt: Double = 0.0, maxSpeedMs: Double = 14.0, hr: Int = 0): Boolean {
        if (accuracyM > 30f) return false
        val last = points.lastOrNull()
        if (last == null) { points += Pt(lat, lon, timeMs, alt, hr); lastAlt = alt; return true }
        val dt = timeMs - last.t
        if (dt <= 0) return false
        val d = Instruments.haversineKm(last.lat, last.lon, lat, lon) * 1000
        val speed = d / (dt / 1000.0)
        if (speed > maxSpeedMs) return false                       // teleport: ignore
        if (d < 3.0) return false                                  // standing still / GPS noise
        distanceM += d
        if (speed >= 0.4) movingMs += dt
        maxKmh = maxOf(maxKmh, speed * 3.6)
        if (alt != 0.0 && !lastAlt.isNaN() && alt - lastAlt > 0.8) gainM += alt - lastAlt
        if (alt != 0.0) lastAlt = alt
        points += Pt(lat, lon, timeMs, alt, hr)
        return true
    }

    fun avgKmh() = if (movingMs > 0) distanceM / (movingMs / 1000.0) * 3.6 else 0.0
}

data class Workout(
    val id: String, val kind: String, val start: Long, val end: Long, val distanceM: Double, val movingMs: Long,
    val kcal: Double, val gainM: Double, val maxKmh: Double,
    val avgHr: Int = 0, val maxHr: Int = 0, val startLat: Double = 0.0, val startLon: Double = 0.0
)

data class Ex(val name: String, val sets: Int, val reps: Int, val restSec: Int, val kg: Double = 0.0, val seconds: Int = 0)
data class Plan(val id: String, val name: String, val exercises: List<Ex>)
/** A finished activity for the weekly goal: GPS workout, plan session or manual entry. */
data class Act(val id: String, val kind: String, val title: String, val start: Long, val durationMin: Int, val distanceM: Double = 0.0, val kcal: Double = 0.0)

object Fitness {
    val KINDS = listOf("corsa" to "🏃 Corsa", "camminata" to "🚶 Camminata", "bici" to "🚴 Bici")

    /** Metabolic equivalent for the activity at an average speed (km/h): rough Compendium values. */
    fun met(kind: String, speedKmh: Double): Double = when (kind) {
        "corsa" -> when { speedKmh < 8 -> 7.0; speedKmh < 9.7 -> 9.0; speedKmh < 11.3 -> 10.5; speedKmh < 12.9 -> 11.5; else -> 12.5 }
        "camminata" -> when { speedKmh < 3.2 -> 2.5; speedKmh < 4.8 -> 3.0; speedKmh < 5.6 -> 3.5; speedKmh < 6.4 -> 4.5; else -> 6.0 }
        "bici" -> when { speedKmh < 16 -> 4.0; speedKmh < 19 -> 6.8; speedKmh < 22 -> 8.0; speedKmh < 25 -> 10.0; else -> 12.0 }
        else -> 4.0
    }
    fun kcal(kind: String, speedKmh: Double, weightKg: Double, movingMs: Long): Double =
        met(kind, speedKmh) * (if (weightKg > 0) weightKg else 70.0) * movingMs / 3_600_000.0

    /** "5:30" per km (running/walking pace). */
    fun pace(speedKmh: Double): String {
        if (speedKmh < 1.0) return "—"
        val minPerKm = 60.0 / speedKmh
        val m = minPerKm.toInt(); val s = Math.round((minPerKm - m) * 60).toInt()
        return if (s == 60) "${m + 1}:00" else "%d:%02d".format(m, s)
    }
    fun dist(m: Double) = if (m < 1000) "%.0f m".format(Locale.ITALIAN, m) else "%.2f km".format(Locale.ITALIAN, m / 1000)
    fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    // ---------- weekly goal and streak ----------

    /** Index of the Monday-based week that contains [t]. */
    fun weekIndex(t: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Math.floorDiv(Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay() + 3, 7L)

    fun weekCount(times: List<Long>, now: Long = System.currentTimeMillis()): Int {
        val w = weekIndex(now); return times.count { weekIndex(it) == w }
    }

    /** Consecutive weeks that reached [goal] sessions. The current week counts only if it is already reached. */
    fun streakWeeks(times: List<Long>, goal: Int, now: Long = System.currentTimeMillis()): Int {
        if (goal <= 0) return 0
        val counts = times.groupingBy { weekIndex(it) }.eachCount()
        val cur = weekIndex(now)
        var w = if ((counts[cur] ?: 0) >= goal) cur else cur - 1
        var n = 0
        while ((counts[w] ?: 0) >= goal) { n++; w-- }
        return n
    }

    /** Sessions from different sources that happened within [windowMs] of each other count once (the watch and the phone both logged it). */
    fun dedupe(times: List<Long>, windowMs: Long = 90 * 60_000L): List<Long> {
        val out = mutableListOf<Long>()
        times.sorted().forEach { t -> if (out.isEmpty() || t - out.last() > windowMs) out += t }
        return out
    }

    /** Workouts of the same kind that start in the same place (within [startM]) and have a similar length: "the same route". */
    fun similar(all: List<Workout>, w: Workout, startOf: (Workout) -> Pair<Double, Double>?, startM: Double = 300.0, tol: Double = 0.15): List<Workout> {
        val s0 = startOf(w) ?: return emptyList()
        return all.filter { o ->
            o.id != w.id && o.kind == w.kind && w.distanceM > 0 && Math.abs(o.distanceM - w.distanceM) <= w.distanceM * tol &&
                startOf(o)?.let { Instruments.haversineKm(s0.first, s0.second, it.first, it.second) * 1000 <= startM } == true
        }.sortedByDescending { it.start }
    }
    fun paceSec(w: Workout) = if (w.distanceM > 0) (w.movingMs / 1000.0 / (w.distanceM / 1000.0)).toInt() else 0

    // ---------- storage ----------

    private fun prefs(c: Context) = c.getSharedPreferences("wl_fitness", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "workouts").apply { mkdirs() }

    fun workouts(c: Context): List<Workout> = runCatching {
        Gson().fromJson<List<Workout>>(File(dir(c), "index.json").readText(), object : TypeToken<List<Workout>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().sortedByDescending { it.start }
    fun saveWorkout(c: Context, w: Workout, points: List<Pt>) {
        runCatching { File(dir(c), "${w.id}.json").writeText(Gson().toJson(points)) }
        runCatching { File(dir(c), "index.json").writeText(Gson().toJson(workouts(c).filter { it.id != w.id } + w)) }
        addAct(c, Act(w.id, "gps", KINDS.firstOrNull { it.first == w.kind }?.second ?: w.kind, w.start, ((w.end - w.start) / 60000).toInt(), w.distanceM, w.kcal))
    }
    fun points(c: Context, id: String): List<Pt> = runCatching {
        Gson().fromJson<List<Pt>>(File(dir(c), "$id.json").readText(), object : TypeToken<List<Pt>>() {}.type)
    }.getOrNull().orEmpty()
    fun deleteWorkout(c: Context, id: String) {
        runCatching { File(dir(c), "$id.json").delete() }
        runCatching { File(dir(c), "index.json").writeText(Gson().toJson(workouts(c).filter { it.id != id })) }
        saveActs(c, acts(c).filter { it.id != id })
    }

    fun acts(c: Context): List<Act> = runCatching {
        Gson().fromJson<List<Act>>(prefs(c).getString("acts", null), object : TypeToken<List<Act>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull()
    private fun saveActs(c: Context, l: List<Act>) = prefs(c).edit().putString("acts", Gson().toJson(l.sortedByDescending { it.start }.take(800))).apply()
    fun addAct(c: Context, a: Act) = saveActs(c, acts(c).filter { it.id != a.id } + a)
    fun removeAct(c: Context, id: String) = saveActs(c, acts(c).filter { it.id != id })

    fun weeklyGoal(c: Context) = prefs(c).getInt("weekly_goal", 3)
    fun setWeeklyGoal(c: Context, n: Int) = prefs(c).edit().putInt("weekly_goal", n.coerceIn(1, 14)).apply()

    /** Times of every activity this week and before: the phone's own plus the watch's (Health Connect), counted once. */
    fun allTimes(c: Context): List<Long> = dedupe(acts(c).map { it.start } + (Hc.cached(c)?.exercises?.map { it.start } ?: emptyList()))

    // ---------- plans ----------

    val PRESETS = listOf(
        Plan("preset-corpo-libero", "Corpo libero (principiante)", listOf(
            Ex("Squat", 3, 12, 45), Ex("Flessioni (anche sulle ginocchia)", 3, 8, 45), Ex("Affondi alternati", 3, 10, 45),
            Ex("Ponte per i glutei", 3, 12, 30), Ex("Plank", 3, 0, 30, seconds = 30))),
        Plan("preset-core", "Addominali e schiena", listOf(
            Ex("Crunch", 3, 15, 30), Ex("Bicicletta in aria", 3, 20, 30), Ex("Superman", 3, 12, 30),
            Ex("Plank laterale (per lato)", 2, 0, 30, seconds = 25), Ex("Plank", 3, 0, 30, seconds = 40))),
        Plan("preset-mobilita", "Mobilità del mattino (5 minuti)", listOf(
            Ex("Rotazioni delle spalle", 1, 15, 10), Ex("Gatto-cammello", 1, 12, 10), Ex("Affondo con rotazione", 2, 6, 10),
            Ex("Squat profondo tenuto", 1, 0, 10, seconds = 30), Ex("Allungamento dei fianchi", 1, 0, 10, seconds = 30)))
    )
    fun plans(c: Context): List<Plan> = runCatching {
        Gson().fromJson<List<Plan>>(prefs(c).getString("plans", null), object : TypeToken<List<Plan>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().ifEmpty { PRESETS }
    fun savePlans(c: Context, l: List<Plan>) = prefs(c).edit().putString("plans", Gson().toJson(l)).apply()
    fun newPlanId() = UUID.randomUUID().toString()

    private val EX_LINE = Regex("^(.+?)\\s+(\\d{1,2})\\s*[x×]\\s*(\\d{1,3})\\s*(s|sec)?(?:\\s+riposo\\s+(\\d{1,3}))?(?:\\s+(\\d+(?:[.,]\\d+)?)\\s*kg)?$", RegexOption.IGNORE_CASE)
    /** "Squat 3x12 riposo 45" / "Plank 3x30s riposo 30" / "Panca 4x8 riposo 90 40kg" -> exercises. Lines that do not fit are ignored. */
    fun parseExercises(text: String): List<Ex> = text.lines().mapNotNull { l ->
        val m = EX_LINE.find(l.trim()) ?: return@mapNotNull null
        val timed = m.groupValues[4].isNotEmpty(); val n = m.groupValues[3].toInt()
        Ex(m.groupValues[1].trim(), m.groupValues[2].toInt(), if (timed) 0 else n, m.groupValues[5].ifEmpty { "45" }.toInt(), m.groupValues[6].replace(',', '.').toDoubleOrNull() ?: 0.0, if (timed) n else 0)
    }
    fun exerciseText(e: Ex) = "${e.name} ${e.sets}x" + (if (e.seconds > 0) "${e.seconds}s" else "${e.reps}") + " riposo ${e.restSec}" + (if (e.kg > 0) " ${if (e.kg % 1.0 == 0.0) e.kg.toInt() else e.kg}kg" else "")

    /** Total number of sets and a rough duration (work 40 s per set unless timed, plus the rests). */
    fun planMinutes(p: Plan): Int = Math.round(p.exercises.sumOf { e -> e.sets * ((if (e.seconds > 0) e.seconds else 40) + e.restSec) } / 60.0).toInt().coerceAtLeast(1)
}
