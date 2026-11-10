package com.weblauncher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit

/**
 * Background checks every ~2 hours: new podcast episodes, new videos of followed channels,
 * news about the user's keywords (Google News). Only new items are notified.
 */
class AlertsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val c = context
    private val prefs = context.getSharedPreferences("wl_alerts", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        val first = !prefs.getBoolean("initialized", false)
        runCatching { if (prefs.getBoolean("podcasts", true)) podcasts(first) }
        runCatching { if (prefs.getBoolean("youtube", true)) youtube(first) }
        runCatching { keywords(first) }
        runCatching { if (prefs.getBoolean("quakes", false)) quakes() }
        prefs.edit().putBoolean("initialized", true).putLong("lastRun", System.currentTimeMillis()).apply()
        return Result.success()
    }

    private fun get(url: String): ByteArray {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) WebLauncher")
        conn.setRequestProperty("Cookie", "SOCS=CAI")
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }

    /** Magnitude 3+ within 100 km of the weather city, notified once each. */
    private fun quakes() {
        val w = c.getSharedPreferences("wl_weather", Context.MODE_PRIVATE)
        val lat = w.getFloat("lat", Float.NaN); val lon = w.getFloat("lon", Float.NaN)
        if (lat.isNaN()) return
        val last = prefs.getLong("quakeLast", System.currentTimeMillis() - 6 * 3600_000L)
        val list = Instruments.parseQuakes(get("https://webservices.ingv.it/fdsnws/event/1/query?format=text&minmag=3&limit=30&orderby=time").toString(Charsets.UTF_8))
        val near = list.filter { it.time > last && Instruments.haversineKm(lat.toDouble(), lon.toDouble(), it.lat, it.lon) <= 100 }
        near.forEach { q ->
            val km = Instruments.haversineKm(lat.toDouble(), lon.toDouble(), q.lat, q.lon)
            notify("🌍 Terremoto magnitudo %.1f".format(q.mag), "${q.place} · a %.0f km da te · %s".format(km, NewsAdapter.ago(q.time)), "quakes", null)
        }
        (list.maxOfOrNull { it.time } ?: last).let { prefs.edit().putLong("quakeLast", maxOf(it, last)).apply() }
    }

    private fun podcasts(first: Boolean) {
        val repo = AudioRepository(c)
        repo.subscriptions().forEach { p ->
            val latest = runCatching { repo.episodes(p.feedUrl).maxByOrNull { it.time } }.getOrNull() ?: return@forEach
            val key = "pod_" + p.feedUrl.hashCode()
            if (prefs.getString(key, null) != latest.id) {
                if (!first && prefs.contains(key)) notify("🎧 ${p.title}", latest.title, "podcast", null)
                prefs.edit().putString(key, latest.id).apply()
            }
        }
    }

    private fun youtube(first: Boolean) {
        val chans = runCatching {
            Gson().fromJson<List<MainActivity.YtChannel>>(c.getSharedPreferences("wl_yt", Context.MODE_PRIVATE).getString("channels", "[]"),
                object : TypeToken<List<MainActivity.YtChannel>>() {}.type)
        }.getOrNull().orEmpty()
        val news = NewsRepository(c)
        chans.forEach { ch ->
            val items = runCatching { news.parseFeed(get("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch.id), NewsSource(ch.id, ch.name, "Video", "")) }
                .getOrNull()?.filter { !it.link.contains("/shorts/") } ?: return@forEach
            val latest = items.maxByOrNull { it.time } ?: return@forEach
            val key = "yt_" + ch.id
            if (prefs.getString(key, null) != latest.id) {
                if (!first && prefs.contains(key)) notify("▶ ${ch.name}", latest.title, null, latest.link)
                prefs.edit().putString(key, latest.id).apply()
            }
        }
    }

    private fun keywords(first: Boolean) {
        val words = prefs.getStringSet("keywords", emptySet()).orEmpty()
        val news = NewsRepository(c)
        words.forEach { w ->
            val url = "https://news.google.com/rss/search?q=" + Uri.encode("$w when:1d") + "&hl=it&gl=IT&ceid=IT:it"
            val items = runCatching { news.parseFeed(get(url), NewsSource("kw", w, "Avvisi", url)) }.getOrNull() ?: return@forEach
            val key = "kw_" + w.lowercase()
            val seen = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
            val fresh = items.filter { it.id !in seen }.sortedByDescending { it.time }
            if (!first && seen.isNotEmpty()) fresh.take(2).forEach { notify("📰 $w", it.title, null, it.link) }
            seen.addAll(items.map { it.id })
            prefs.edit().putStringSet(key, seen.toList().takeLast(300).toSet()).apply()
        }
    }

    private fun notify(title: String, text: String, widget: String?, url: String?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avvisi", NotificationManager.IMPORTANCE_DEFAULT))
        val id = (title + text).hashCode()
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (url != null) i.putExtra("shortcut_url", url)
        if (widget != null) i.putExtra(MainActivity.EXTRA_WIDGET, widget)
        val pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_news)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { nm.notify(id, n) }
    }

    companion object {
        private const val CHANNEL = "avvisi"
        private const val WORK = "wl_alerts"

        fun schedule(c: Context) {
            val p = c.getSharedPreferences("wl_alerts", Context.MODE_PRIVATE)
            val any = p.getBoolean("podcasts", true) || p.getBoolean("youtube", true) || p.getStringSet("keywords", emptySet()).orEmpty().isNotEmpty() || p.getBoolean("quakes", false)
            val wm = WorkManager.getInstance(c)
            if (!p.getBoolean("enabled", true) || !any) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<AlertsWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(c: Context) {
            WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<AlertsWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
