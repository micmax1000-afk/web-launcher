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
            maxSpeedMs = if (WorkoutState.kind == "bici") 25.0 else 14.0, hr = HrBand.current())
    }
    private var coach: Coach? = null
    private var voiceKm = false
    private var iv = Triple(0, 0, 0)
    private var zoneTarget = 0
    private var maxHr = 190
    private var lastKm = 0
    private var lastPhase = ""
    private var lastZoneAlert = 0L
    private var secs = 0

    private fun say(t: String) { coach?.say(t) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!WorkoutState.running) return
            secs++
            val elapsed = (WorkoutState.elapsedMs() / 1000).toInt()
            val t = WorkoutState.track
            // intervals
            if (iv.third > 0) {
                val pos = Intervals.at(elapsed, iv.first, iv.second, iv.third)
                val key = if (pos.done) "fine" else "${pos.round}${pos.hard}"
                if (key != lastPhase) {
                    lastPhase = key
                    vibrateNow(this@WorkoutService, if (pos.hard) 600 else 300)
                    if (pos.done) say("Intervalli finiti. Bravo!")
                    else say(Intervals.words(WorkoutState.kind, pos.hard) + (if (pos.hard) ". Ripetizione ${pos.round} di ${iv.third}" else "") + ". " + Say.duration(pos.remaining))
                } else if (!pos.done && pos.remaining in 1..3 && secs % 1 == 0) { /* beep sets come from vibration only */ }
            }
            // each kilometre
            val km = (t.distanceM / 1000).toInt()
            if (voiceKm && km > lastKm) {
                lastKm = km
                val hr = HrBand.current()
                say("${if (km == 1) "Un chilometro" else "$km chilometri"}. Tempo ${Say.duration((WorkoutState.elapsedMs() / 1000).toInt())}. Passo ${Say.pace(t.avgKmh())}." + if (hr > 0) " Battito $hr." else "")
            }
            // stay in the target heart-rate zone
            val hrNow = HrBand.current()
            if (zoneTarget > 0 && hrNow > 0 && System.currentTimeMillis() - lastZoneAlert > 45_000 && secs > 60) {
                val z = HrZones.zone(hrNow, maxHr)
                if (z < zoneTarget) { lastZoneAlert = System.currentTimeMillis(); say("Battito basso, $hrNow. Accelera un po'."); vibrateNow(this@WorkoutService, 200) }
                else if (z > zoneTarget) { lastZoneAlert = System.currentTimeMillis(); say("Battito alto, $hrNow. Rallenta."); vibrateNow(this@WorkoutService, 500) }
            }
            if (secs % 5 == 0) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF, build()) }
            handler.postDelayed(this, 1000)
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
        voiceKm = WorkoutOpts.voiceKm(this); iv = WorkoutOpts.intervals(this); zoneTarget = WorkoutOpts.zoneTarget(this); maxHr = WorkoutOpts.maxHr(this)
        lastKm = 0; lastPhase = ""; secs = 0; lastZoneAlert = 0L
        if (voiceKm || iv.third > 0 || zoneTarget > 0) coach = Coach(this)
        HrBand.autoConnect(this); HrBand.startRecording()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("allenamento", "Allenamento in corso", NotificationManager.IMPORTANCE_LOW))
        val n = build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF, n)
        WorkoutState.running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) { finish(save = false); return START_NOT_STICKY }
        coach?.say("Allenamento iniziato. Buona corsa!")
        handler.postDelayed(ticker, 1000)
        return START_STICKY
    }

    private fun musicIntent(action: String, code: Int) = PendingIntent.getBroadcast(this, code, Intent(this, MusicKeyReceiver::class.java).putExtra("a", action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(): Notification {
        val t = WorkoutState.track
        val open = PendingIntent.getActivity(this, 7820, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "workout")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 7821, Intent(this, WorkoutService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "allenamento").setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("Allenamento in corso · " + Fitness.clock(WorkoutState.elapsedMs()))
            .setContentText(Fitness.dist(t.distanceM) + " · " + Fitness.pace(t.avgKmh()) + " /km" + HrBand.current().let { if (it > 0) " · ❤ $it" else "" })
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "⏯ Musica", musicIntent("toggle", 7822)).addAction(0, "⏭ Avanti", musicIntent("next", 7823)).addAction(0, "Termina", stop).build()
    }

    /** Stops tracking; a workout with some distance is saved. */
    private fun finish(save: Boolean = true) {
        val was = WorkoutState.running
        WorkoutState.running = false
        handler.removeCallbacks(ticker)
        runCatching { lm?.removeUpdates(listener) }
        val hrSamples = if (was) HrBand.stopRecording() else emptyList()
        if (was && save) {
            val t = WorkoutState.track
            if (t.distanceM >= 30) {
                val id = UUID.randomUUID().toString()
                val end = System.currentTimeMillis()
                val p = Food.profile(this)
                val kcal = if (hrSamples.size >= 20) Hr.kcalFromSamples(hrSamples, p.sex, WorkoutState.weightKg, WorkoutOpts.age(this))
                           else Fitness.kcal(WorkoutState.kind, t.avgKmh(), WorkoutState.weightKg, t.movingMs)
                val first = t.points.firstOrNull()
                val w = Workout(id, WorkoutState.kind, WorkoutState.start, end, t.distanceM, t.movingMs, kcal, t.gainM, t.maxKmh, Hr.avg(hrSamples), Hr.max(hrSamples), first?.lat ?: 0.0, first?.lon ?: 0.0)
                Fitness.saveWorkout(this, w, t.points.toList())
                WorkoutState.savedId = id
                val label = Fitness.KINDS.firstOrNull { it.first == w.kind }?.second?.substringAfter(' ') ?: w.kind
                Thread { HcWrite.save(applicationContext, w.kind, label, w.start, end, w.distanceM, kcal, hrSamples) }.start()
                coach?.say("Allenamento finito. ${Fitness.dist(t.distanceM).replace(".", ",")} in ${Say.duration(t.movingMs.div(1000).toInt())}.")
            }
        }
        handler.postDelayed({ coach?.shutdown(); coach = null }, 6000)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { handler.removeCallbacks(ticker); if (WorkoutState.running) { HrBand.stopRecording() }; runCatching { lm?.removeUpdates(listener) }; super.onDestroy() }

    companion object { const val ACTION_STOP = "com.weblauncher.app.WORKOUT_STOP"; const val NOTIF = 7820 }
}
