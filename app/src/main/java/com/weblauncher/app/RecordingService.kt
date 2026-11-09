package com.weblauncher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/** Voice recorder that keeps recording with the screen off (foreground service, microphone type). */
class RecordingService : Service() {
    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRecording(); return START_NOT_STICKY }
        if (recorder != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Registrazione", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "recorder")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_mic)
            .setContentTitle("● Registrazione in corso")
            .setContentText("Tocca per aprire, oppure ferma")
            .setUsesChronometer(true).setWhen(System.currentTimeMillis())
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Ferma", stop)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, n)
        val file = File(dir(this), "rec_${System.currentTimeMillis()}.aac")
        try {
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(40000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = file
            startedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            file.delete()
            stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun stopRecording() {
        runCatching { recorder?.stop() }.onFailure { current?.delete() }
        runCatching { recorder?.release() }
        recorder = null
        current = null
        startedAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "registrazione"
        private const val ID = 4242
        @Volatile var current: File? = null
        @Volatile var startedAt = 0L
        var listener: (() -> Unit)? = null
        fun dir(c: Context) = File(c.filesDir, "recordings").apply { mkdirs() }
        fun isRecording() = startedAt > 0L
    }
}
