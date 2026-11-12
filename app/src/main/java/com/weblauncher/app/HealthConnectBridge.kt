package com.weblauncher.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.aggregate.AggregateMetric
import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Health Connect (read only): the phone's shared store for health data. Watch and fitness apps write steps, heart rate,
 * sleep, weight and workouts into it; Web Launcher reads them to build the daily summary and the charts.
 * Everything stays on the phone.
 */
object Hc {
    /** 0 = no data. */
    data class Day(
        val date: String, val steps: Long = 0, val distanceM: Double = 0.0, val activeKcal: Double = 0.0,
        val sleepMin: Long = 0, val deepMin: Long = 0, val remMin: Long = 0,
        val hrAvg: Long = 0, val hrMin: Long = 0, val hrMax: Long = 0, val restHr: Long = 0, val weightKg: Double = 0.0
    )
    data class Exercise(val start: Long, val end: Long, val type: String, val title: String)
    data class Snapshot(
        val time: Long, val days: List<Day>, val exercises: List<Exercise>,
        val spo2: Double = 0.0, val spo2Time: Long = 0, val systolic: Int = 0, val diastolic: Int = 0, val bpTime: Long = 0,
        val glucoseMgDl: Double = 0.0, val glucoseTime: Long = 0, val sources: List<String> = emptyList(),
        /** data label (e.g. "Passi") -> packages that wrote it in the period */
        val bySource: Map<String, List<String>> = emptyMap()
    )

    private val RECORDS: List<KClass<out Record>> = listOf(
        StepsRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class, SleepSessionRecord::class, WeightRecord::class,
        ExerciseSessionRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, OxygenSaturationRecord::class,
        BloodPressureRecord::class, BloodGlucoseRecord::class
    )
    val PERMISSIONS: Set<String> = RECORDS.map { HealthPermission.getReadPermission(it) }.toSet()

    const val PACKAGE = "com.google.android.apps.healthdata"
    val SDK_AVAILABLE get() = HealthConnectClient.SDK_AVAILABLE
    fun status(c: Context): Int = HealthConnectClient.getSdkStatus(c)

    // ---------- pure helpers (unit-tested) ----------

    /** Average of the non-zero values (a day without data does not count). */
    fun avg(days: List<Day>, pick: (Day) -> Double): Double {
        val v = days.map(pick).filter { it > 0 }
        return if (v.isEmpty()) 0.0 else v.average()
    }

    fun duration(min: Long): String = if (min <= 0) "—" else if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)} min"

    /** The last [n] days that exist in [days], oldest first. */
    fun last(days: List<Day>, n: Int) = days.sortedBy { it.date }.takeLast(n)

    fun today(s: Snapshot?, now: LocalDate = LocalDate.now()): Day? = s?.days?.firstOrNull { it.date == now.toString() }

    /** Sleep of the most recent night that has any. */
    fun lastNight(s: Snapshot?): Day? = s?.days?.sortedByDescending { it.date }?.firstOrNull { it.sleepMin > 0 }

    /** A friendly name for the common apps that write into Health Connect (unknown ones keep the package name). */
    fun sourceName(pkg: String) = when {
        pkg.contains("xiaomi.wearable") || pkg.contains("mi.health") -> "App dell'orologio Xiaomi"
        pkg.contains("huawei") -> "Huawei Salute"
        pkg.contains("withings") -> "Withings"
        pkg.contains("polar") -> "Polar Flow"
        pkg.contains("ouraring") || pkg.contains(".oura") -> "Oura"
        pkg.contains("suunto") -> "Suunto"
        pkg.contains("coros") -> "COROS"
        pkg.contains("whoop") -> "WHOOP"
        pkg.contains("healthdata") -> "Health Connect"
        pkg.contains("weblauncher") -> "Web Launcher"
        pkg.contains("google.android.apps.fitness") -> "Google Fit"
        pkg.contains("samsung") -> "Samsung Health"
        pkg.contains("fitbit") -> "Fitbit"
        pkg.contains("garmin") -> "Garmin Connect"
        pkg.contains("zepp") || pkg.contains("huami") -> "Zepp"
        pkg.contains("strava") -> "Strava"
        else -> pkg
    }

    fun exerciseLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Corsa"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Camminata"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Bici"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Nuoto"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Escursione"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Pesi"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Ellittica"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Canottaggio"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Scale"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Danza"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Calcio"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        else -> "Allenamento"
    }

    // ---------- storage of the last reading ----------

    private fun prefs(c: Context) = c.getSharedPreferences("wl_hc", Context.MODE_PRIVATE)
    fun cached(c: Context): Snapshot? = runCatching { Gson().fromJson(prefs(c).getString("snap", null), Snapshot::class.java) }.getOrNull()
    fun save(c: Context, s: Snapshot) = prefs(c).edit().putString("snap", Gson().toJson(s)).apply()

    // ---------- reading (suspend: call from a background thread with runBlocking) ----------

    suspend fun granted(c: Context): Set<String> = HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions()

    fun refreshBlocking(c: Context, days: Int = 30): Snapshot? = runCatching {
        kotlinx.coroutines.runBlocking { snapshot(c, days) }.also { save(c, it) }
    }.onFailure { android.util.Log.w("WL_HC", "lettura non riuscita", it) }.getOrNull()

    suspend fun snapshot(c: Context, days: Int = 30): Snapshot {
        val hc = HealthConnectClient.getOrCreate(c)
        val granted = hc.permissionController.getGrantedPermissions()
        fun has(r: KClass<out Record>) = HealthPermission.getReadPermission(r) in granted
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val firstDay = today.minusDays((days - 1).toLong())
        val start = firstDay.atStartOfDay(); val end = today.plusDays(1).atStartOfDay()
        val range = TimeRangeFilter.between(start, end)
        val instantRange = TimeRangeFilter.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant())
        val byDay = linkedMapOf<String, Day>()
        for (i in 0 until days) byDay[firstDay.plusDays(i.toLong()).toString()] = Day(firstDay.plusDays(i.toLong()).toString())

        // daily totals / averages
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (has(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (has(DistanceRecord::class)) metrics += DistanceRecord.DISTANCE_TOTAL
        if (has(ActiveCaloriesBurnedRecord::class)) metrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (has(HeartRateRecord::class)) { metrics += HeartRateRecord.BPM_AVG; metrics += HeartRateRecord.BPM_MIN; metrics += HeartRateRecord.BPM_MAX }
        if (has(RestingHeartRateRecord::class)) metrics += RestingHeartRateRecord.BPM_AVG
        if (has(WeightRecord::class)) metrics += WeightRecord.WEIGHT_AVG
        if (metrics.isNotEmpty()) {
            hc.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1))).forEach { g ->
                val key = g.startTime.toLocalDate().toString()
                val d = byDay[key] ?: return@forEach
                val r = g.result
                byDay[key] = d.copy(
                    steps = r[StepsRecord.COUNT_TOTAL] ?: 0L,
                    distanceM = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
                    activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
                    hrAvg = r[HeartRateRecord.BPM_AVG] ?: 0L, hrMin = r[HeartRateRecord.BPM_MIN] ?: 0L, hrMax = r[HeartRateRecord.BPM_MAX] ?: 0L,
                    restHr = r[RestingHeartRateRecord.BPM_AVG] ?: 0L,
                    weightKg = r[WeightRecord.WEIGHT_AVG]?.inKilograms ?: 0.0
                )
            }
        }

        // sleep: a night belongs to the day it ends (a sleep that crosses midnight must not be split)
        if (has(SleepSessionRecord::class)) {
            hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, instantRange, pageSize = 400)).records.forEach { s ->
                val key = s.endTime.atZone(zone).toLocalDate().toString()
                val d = byDay[key] ?: return@forEach
                val total = java.time.Duration.between(s.startTime, s.endTime).toMinutes()
                var deep = 0L; var rem = 0L; var awake = 0L
                s.stages.forEach { st ->
                    val m = java.time.Duration.between(st.startTime, st.endTime).toMinutes()
                    when (st.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                        SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += m
                    }
                }
                byDay[key] = d.copy(sleepMin = d.sleepMin + (total - awake).coerceAtLeast(0), deepMin = d.deepMin + deep, remMin = d.remMin + rem)
            }
        }

        // workouts
        val exercises = if (has(ExerciseSessionRecord::class))
            hc.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, instantRange, ascendingOrder = false, pageSize = 100)).records
                .map { Exercise(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), exerciseLabel(it.exerciseType), it.title.orEmpty()) }
        else emptyList()

        // latest single measurements
        val wide = TimeRangeFilter.between(Instant.now().minus(java.time.Duration.ofDays(30)), Instant.now().plusSeconds(3600))
        var spo2 = 0.0; var spo2Time = 0L; var sys = 0; var dia = 0; var bpTime = 0L; var glucose = 0.0; var glucoseTime = 0L
        if (has(OxygenSaturationRecord::class)) hc.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, wide, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.let {
            spo2 = it.percentage.value; spo2Time = it.time.toEpochMilli()
        }
        if (has(BloodPressureRecord::class)) hc.readRecords(ReadRecordsRequest(BloodPressureRecord::class, wide, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.let {
            sys = it.systolic.inMillimetersOfMercury.toInt(); dia = it.diastolic.inMillimetersOfMercury.toInt(); bpTime = it.time.toEpochMilli()
        }
        if (has(BloodGlucoseRecord::class)) hc.readRecords(ReadRecordsRequest(BloodGlucoseRecord::class, wide, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.let {
            glucose = it.level.inMilligramsPerDeciliter; glucoseTime = it.time.toEpochMilli()
        }

        // which apps wrote each kind of data (the "Le mie sorgenti" page)
        val bySource = linkedMapOf<String, MutableSet<String>>()
        fun note(label: String, pkg: String) { if (pkg.isNotBlank()) bySource.getOrPut(label) { linkedSetOf() } += pkg }
        listOf(
            Triple("Passi", StepsRecord.COUNT_TOTAL, StepsRecord::class),
            Triple("Battito", HeartRateRecord.BPM_AVG, HeartRateRecord::class),
            Triple("Distanza", DistanceRecord.DISTANCE_TOTAL, DistanceRecord::class),
            Triple("Calorie attive", ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, ActiveCaloriesBurnedRecord::class),
            Triple("Peso", WeightRecord.WEIGHT_AVG, WeightRecord::class)
        ).forEach { (label, m, rec) ->
            if (has(rec)) runCatching { hc.aggregate(AggregateRequest(setOf(m), range)).dataOrigins.forEach { note(label, it.packageName) } }
        }
        suspend fun origins(label: String, rec: KClass<out Record>) {
            if (has(rec)) runCatching {
                hc.readRecords(ReadRecordsRequest(rec, wide, pageSize = 200)).records.forEach { note(label, it.metadata.dataOrigin.packageName) }
            }
        }
        origins("Sonno", SleepSessionRecord::class)
        origins("Allenamenti", ExerciseSessionRecord::class)
        origins("Ossigeno", OxygenSaturationRecord::class)
        origins("Pressione", BloodPressureRecord::class)
        origins("Glicemia", BloodGlucoseRecord::class)
        val sources = bySource.values.flatten().distinct()
        return Snapshot(System.currentTimeMillis(), byDay.values.toList(), exercises, spo2, spo2Time, sys, dia, bpTime, glucose, glucoseTime, sources, bySource.mapValues { it.value.toList() })
    }
}
