package com.weblauncher.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant

/** Writes finished workouts into Health Connect, so Mi Fitness and other apps can see them (optional, off by default). */
object HcWrite {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class), HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class), HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    )
    private fun prefs(c: Context) = c.getSharedPreferences("wl_fitness", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("hc_write", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hc_write", on).apply()

    fun type(kind: String) = when (kind) {
        "corsa" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "camminata" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "bici" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "forza" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** Best effort: returns false (silently) if the setting is off, permission is missing or Health Connect is not there. */
    fun save(c: Context, kind: String, title: String, start: Long, end: Long, distanceM: Double, kcal: Double, hr: List<Pair<Long, Int>>): Boolean {
        if (!enabled(c) || Hc.status(c) != Hc.SDK_AVAILABLE || end <= start) return false
        return runCatching {
            kotlinx.coroutines.runBlocking {
                val client = HealthConnectClient.getOrCreate(c)
                if (!client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) return@runBlocking false
                val s = Instant.ofEpochMilli(start); val e = Instant.ofEpochMilli(end)
                val list = mutableListOf<Record>(ExerciseSessionRecord(startTime = s, startZoneOffset = null, endTime = e, endZoneOffset = null, exerciseType = type(kind), title = title))
                if (distanceM > 0) list += DistanceRecord(startTime = s, startZoneOffset = null, endTime = e, endZoneOffset = null, distance = Length.meters(distanceM))
                if (kcal > 0) list += TotalCaloriesBurnedRecord(startTime = s, startZoneOffset = null, endTime = e, endZoneOffset = null, energy = Energy.kilocalories(kcal))
                val samples = hr.filter { it.first in start..end }.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.first), it.second.toLong()) }
                if (samples.isNotEmpty()) list += HeartRateRecord(startTime = s, startZoneOffset = null, endTime = e, endZoneOffset = null, samples = samples.takeLast(2000))
                client.insertRecords(list); true
            }
        }.onFailure { android.util.Log.w("WL_HC", "scrittura non riuscita", it) }.getOrDefault(false)
    }
}
