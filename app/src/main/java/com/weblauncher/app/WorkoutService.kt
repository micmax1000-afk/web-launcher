package com.weblauncher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.UUID

/** The workout being recorded: shared between the service (writes) and the screen (reads). */
object WorkoutState {
    @Volatile var running = false
    @Volatile var kind = "corsa"
    @Volatile var start = 0L
    @Volatile var track = Track()
    @Volatile var lastFixAt = 0L
    @Volatile var savedId: String? = null
    @Volatile var weightKg = 0.0
    fun elapsedMs() = if (running) System.currentTimeMillis() - start else 0L
}

/** Records a GPS workout in the background, with an ongoing notification (required by Android for location in the background). */
class WorkoutService : Service() {
    private var lm: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener = LocationListener { l: Location ->
        WorkoutState.lastFixAt = System.currentTimeMillis()
        WorkoutState.track.add(l.latitude, l.longitude, l.time, if (l.hasAccuracy()) l.accuracy else 5f, if (l.hasAltitude()) l.altitude else 0.0,
            maxSpeedMs = if (WorkoutState.kind == "bici") 25.0 else 14.0)
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!WorkoutState.running) return
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF, build()) }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        if (WorkoutState.running) return START_STICKY
        WorkoutState.kind = intent?.getStringExtra("kind") ?: "corsa"
        WorkoutState.start = System.currentTimeMillis()
        WorkoutState.track = Track()
        WorkoutState.savedId = null
        WorkoutState.weightKg = Food.lastWeight(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("allenamento", "Allenamento in corso", NotificationManager.IMPORTANCE_LOW))
        val n = build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF, n)
        WorkoutState.running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) { finish(save = false); return START_NOT_STICKY }
        handler.postDelayed(ticker, 5000)
        return START_STICKY
    }

    private fun build(): Notification {
        val t = WorkoutState.track
        val open = PendingIntent.getActivity(this, 7820, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "workout")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 7821, Intent(this, WorkoutService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "allenamento").setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("Allenamento in corso · " + Fitness.clock(WorkoutState.elapsedMs()))
            .setContentText(Fitness.dist(t.distanceM) + " · " + Fitness.pace(t.avgKmh()) + " /km")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0, "Termina", stop).build()
    }

    /** Stops tracking; a workout with some distance is saved. */
    private fun finish(save: Boolean = true) {
        val was = WorkoutState.running
        WorkoutState.running = false
        handler.removeCallbacks(ticker)
        runCatching { lm?.removeUpdates(listener) }
        if (was && save) {
            val t = WorkoutState.track
            if (t.distanceM >= 30) {
                val id = UUID.randomUUID().toString()
                val kcal = Fitness.kcal(WorkoutState.kind, t.avgKmh(), WorkoutState.weightKg, t.movingMs)
                Fitness.saveWorkout(this, Workout(id, WorkoutState.kind, WorkoutState.start, System.currentTimeMillis(), t.distanceM, t.movingMs, kcal, t.gainM, t.maxKmh), t.points.toList())
                WorkoutState.savedId = id
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { handler.removeCallbacks(ticker); runCatching { lm?.removeUpdates(listener) }; super.onDestroy() }

    companion object { const val ACTION_STOP = "com.weblauncher.app.WORKOUT_STOP"; const val NOTIF = 7820 }
}
