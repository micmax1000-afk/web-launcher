package com.weblauncher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the app alive while a book is read aloud with the screen off, and shows the controls in the notification. */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra("book").orEmpty(), intent?.getStringExtra("title").orEmpty(),
            intent?.getStringExtra("text").orEmpty(), intent?.getBooleanExtra("playing", true) != false)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 4417
        const val CHANNEL = "lettura"
        const val A_PLAY = "wl.read.PLAY"; const val A_NEXT = "wl.read.NEXT"; const val A_PREV = "wl.read.PREV"
        const val A_NEXT_CH = "wl.read.NEXTCH"; const val A_PREV_CH = "wl.read.PREVCH"; const val A_STOP = "wl.read.STOP"

        private fun action(c: Context, a: String, icon: Int, label: String): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(c, a.hashCode(), Intent(a).setPackage(c.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(icon, label, pi).build()
        }

        fun build(c: Context, bookId: String, title: String, text: String, playing: Boolean): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lettura ad alta voce", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(c, 1, Intent(c, EbookActivity::class.java).putExtra("book", bookId)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_nav_audio)
                .setContentTitle(title).setContentText(text).setOngoing(playing).setOnlyAlertOnce(true).setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(action(c, A_PREV_CH, android.R.drawable.ic_media_previous, "Capitolo precedente"))
                .addAction(action(c, A_PREV, android.R.drawable.ic_media_rew, "Frase precedente"))
                .addAction(action(c, A_PLAY, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausa" else "Riprendi"))
                .addAction(action(c, A_NEXT, android.R.drawable.ic_media_ff, "Frase successiva"))
                .addAction(action(c, A_NEXT_CH, android.R.drawable.ic_media_next, "Capitolo successivo"))
                .build()
        }

        fun start(c: Context, bookId: String, title: String, text: String, playing: Boolean) {
            runCatching {
                c.startForegroundService(Intent(c, ReadAloudService::class.java).putExtra("book", bookId).putExtra("title", title).putExtra("text", text).putExtra("playing", playing))
            }
        }
        fun update(c: Context, bookId: String, title: String, text: String, playing: Boolean) {
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, build(c, bookId, title, text, playing)) }
        }
        fun stop(c: Context) { runCatching { c.stopService(Intent(c, ReadAloudService::class.java)) } }

        /** The reader that receives the notification buttons (the one open now). */
        @Volatile var current: BookReader? = null
    }
}

class ReadAloudReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { intent.action?.let { ReadAloudService.current?.command(it) } }
}
