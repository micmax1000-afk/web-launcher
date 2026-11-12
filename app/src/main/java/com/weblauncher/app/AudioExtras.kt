package com.weblauncher.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Per-podcast settings: speed, seconds to skip at the start (intro) and at the end (outro). */
object PodcastPrefs {
    data class Opts(val speed: Float = 0f, val introSec: Int = 0, val outroSec: Int = 0)
    private fun prefs(c: Context) = c.getSharedPreferences("wl_audio", Context.MODE_PRIVATE)
    private fun key(feed: String) = "pod_" + feed.hashCode()
    fun get(c: Context, feed: String): Opts = runCatching { Gson().fromJson(prefs(c).getString(key(feed), null), Opts::class.java) }.getOrNull() ?: Opts()
    fun set(c: Context, feed: String, o: Opts) { prefs(c).edit().putString(key(feed), Gson().toJson(o)).apply() }
    /** The speed to use for this podcast (its own, or the general one). */
    fun speed(c: Context, feed: String) = get(c, feed).speed.takeIf { it > 0f } ?: prefs(c).getFloat("speed", 1f)
    /** Where to start: the saved position, or after the intro for a new episode. */
    fun startAt(c: Context, e: Episode): Long {
        val saved = if (AudioStore.isFinished(c, e.id)) 0L else AudioStore.position(c, e.id)
        val intro = get(c, e.feedUrl).introSec * 1000L
        return if (saved < intro) intro else saved
    }
}

/** «Ascolta dopo»: episodes that play one after the other (kept also when the app is closed). */
object AudioQueue {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_audio", Context.MODE_PRIVATE)
    fun list(c: Context): List<Episode> = runCatching {
        Gson().fromJson<List<Episode>>(prefs(c).getString("queue", null), object : TypeToken<List<Episode>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull()
    fun save(c: Context, l: List<Episode>) { prefs(c).edit().putString("queue", Gson().toJson(l.distinctBy { it.id })).apply() }
    fun add(c: Context, e: Episode, first: Boolean = false) = save(c, if (first) listOf(e) + list(c).filter { it.id != e.id } else list(c).filter { it.id != e.id } + e)
    fun remove(c: Context, id: String) = save(c, list(c).filter { it.id != id })
    fun clear(c: Context) = save(c, emptyList())
    fun pop(c: Context): Episode? { val l = list(c); val e = l.firstOrNull() ?: return null; save(c, l.drop(1)); return e }
    fun move(c: Context, id: String, up: Boolean) {
        val l = list(c).toMutableList(); val i = l.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t; save(c, l)
    }
}

/** OPML: the standard file to move the podcast list between apps. */
object Opml {
    /** (title, feed url) of every outline with an xmlUrl. */
    fun parse(xml: String): List<Pair<String, String>> =
        Regex("<outline\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { m ->
            val tag = m.value
            fun attr(n: String) = Regex("\\b$n\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                ?: Regex("\\b$n\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            val url = attr("xmlUrl")?.let(::unescape)?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            (attr("title") ?: attr("text"))?.let(::unescape).orEmpty() to url
        }.distinctBy { it.second }.toList()

    fun build(list: List<Podcast>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>Podcast di Web Launcher</title></head>\n<body>\n")
        list.forEach { append("  <outline type=\"rss\" text=\"${esc(it.title)}\" title=\"${esc(it.title)}\" xmlUrl=\"${esc(it.feedUrl)}\"/>\n") }
        append("</body>\n</opml>\n")
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&amp;", "&")
}

/** Spoken requests for podcasts and radio («metti l'ultimo episodio di Il Post», «metti Radio 3»). */
sealed class AudioCmd {
    data class LatestEpisode(val podcast: String) : AudioCmd()
    data class Radio(val station: String) : AudioCmd()
    object News : AudioCmd()
}

object AudioVoice {
    private val verbs = "(?:metti|fammi sentire|fai partire|riproduci|ascolta|ascoltare|voglio sentire|accendi)"
    fun parse(raw: String): AudioCmd? {
        val t = raw.trim().lowercase(Locale.ITALIAN).trimEnd('.', '!', '?')
        if (Regex("^$verbs?\\s*(?:il |le )?(?:notiziario|notizie|giornale radio)(?: di oggi| del mattino)?$").matches(t)) return AudioCmd.News
        Regex("^$verbs\\s+(?:l'|lo |l )?(?:ultimo|nuovo)\\s+(?:episodio|puntata)\\s+(?:di|del|della|dei|delle)\\s+(.+)$").find(t)?.let { return AudioCmd.LatestEpisode(it.groupValues[1].trim()) }
        Regex("^$verbs\\s+(?:il |la )?podcast\\s+(?:di |del |della )?(.+)$").find(t)?.let { return AudioCmd.LatestEpisode(it.groupValues[1].trim()) }
        Regex("^$verbs\\s+(?:la )?(radio\\s*.+)$").find(t)?.let { return AudioCmd.Radio(it.groupValues[1].trim()) }
        Regex("^$verbs\\s+(?:la )?(?:stazione|emittente)\\s+(.+)$").find(t)?.let { return AudioCmd.Radio(it.groupValues[1].trim()) }
        Regex("^$verbs\\s+((?:rtl|deejay|virgin|capital|m2o|rds|kiss kiss|rai radio|radio)\\b.*)$").find(t)?.let { return AudioCmd.Radio(it.groupValues[1].trim()) }
        return null
    }

    /** Best match by name: exact, then «contains», then the most shared words. */
    fun <T> best(items: List<T>, query: String, name: (T) -> String): T? {
        val q = norm(query)
        if (q.isBlank()) return null
        items.firstOrNull { norm(name(it)) == q }?.let { return it }
        items.firstOrNull { norm(name(it)).contains(q) || q.contains(norm(name(it))) && norm(name(it)).length > 3 }?.let { return it }
        val words = q.split(' ').filter { it.length > 1 }.toSet()
        return items.map { it to norm(name(it)).split(' ').count { w -> w in words } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
    fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.ITALIAN), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace("tre", "3").replace("due", "2").replace("uno", "1")
}

/** New episodes downloaded on Wi-Fi only; episodes already heard are deleted. */
class PodcastWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!PodcastAuto.enabled(c)) return Result.success()
        runCatching { PodcastAuto.run(c) }
        return Result.success()
    }
}

object PodcastAuto {
    private const val WORK = "wl_podcast_auto"
    private fun prefs(c: Context) = c.getSharedPreferences("wl_audio", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("autoDl", false)
    fun cleanup(c: Context) = prefs(c).getBoolean("autoClean", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("autoDl", on).apply(); schedule(c) }
    fun setCleanup(c: Context, on: Boolean) { prefs(c).edit().putBoolean("autoClean", on).apply() }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!enabled(c)) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PodcastWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build())
    }

    /** Deletes the files of episodes already heard; returns how many. */
    fun deleteHeard(c: Context): Int {
        val repo = AudioRepository(c)
        val known = (repo.cachedEpisodes() + AudioStore.recent(c)).distinctBy { it.id }
        var n = 0
        known.filter { AudioStore.isFinished(c, it.id) && repo.isDownloaded(it) && AudioQueue.list(c).none { q -> q.id == it.id } }
            .forEach { if (repo.localFile(it).delete()) n++ }
        return n
    }

    fun run(c: Context) {
        val repo = AudioRepository(c)
        val eps = repo.refreshEpisodes()
        if (cleanup(c)) deleteHeard(c)
        val since = System.currentTimeMillis() - 3 * 86_400_000L
        // the newest unheard episode of each podcast, if published in the last 3 days
        eps.groupBy { it.feedUrl }.values.mapNotNull { l -> l.maxByOrNull { it.time } }
            .filter { it.time >= since && !AudioStore.isFinished(c, it.id) && !repo.isDownloaded(it) && !prefs(c).contains("dl_" + it.id) }
            .take(6).forEach { download(c, it, wifiOnly = true) }
    }

    fun download(c: Context, e: Episode, wifiOnly: Boolean = false): Boolean = runCatching {
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val req = android.app.DownloadManager.Request(Uri.parse(e.audioUrl))
            .setTitle(e.title).setDescription(e.podcastTitle)
            .setNotificationVisibility(if (wifiOnly) android.app.DownloadManager.Request.VISIBILITY_HIDDEN else android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setDestinationInExternalFilesDir(c, android.os.Environment.DIRECTORY_PODCASTS, AudioRepository(c).localFile(e).name)
        prefs(c).edit().putLong("dl_" + e.id, dm.enqueue(req)).apply()
        true
    }.getOrDefault(false)
}

/** Wake up with the radio: an alarm clock that starts a favourite station (falls back to the alarm sound). */
object RadioAlarm {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_radio_alarm", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun hour(c: Context) = prefs(c).getInt("h", 7)
    fun minute(c: Context) = prefs(c).getInt("m", 0)
    fun weekdaysOnly(c: Context) = prefs(c).getBoolean("weekdays", false)
    fun station(c: Context): RadioStation? = runCatching { Gson().fromJson(prefs(c).getString("station", null), RadioStation::class.java) }.getOrNull()
    fun set(c: Context, on: Boolean, h: Int, m: Int, weekdays: Boolean, s: RadioStation?) {
        prefs(c).edit().putBoolean("on", on).putInt("h", h).putInt("m", m).putBoolean("weekdays", weekdays)
            .putString("station", s?.let { Gson().toJson(it) }).apply()
        schedule(c)
    }

    /** The next time the alarm rings, from [now]. */
    fun next(now: Long, h: Int, m: Int, weekdays: Boolean): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        while (weekdays && (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7301, Intent(c, RadioAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun canExact(c: Context) = android.os.Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (!enabled(c)) return
        val at = next(System.currentTimeMillis(), hour(c), minute(c), weekdaysOnly(c))
        val show = PendingIntent.getActivity(c, 7302, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            if (canExact(c)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        }
    }
}

class RadioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        RadioAlarm.schedule(c)    // the next one
        val s = RadioAlarm.station(c)
        val pr = goAsync()
        // make sure it can be heard
        runCatching {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) < max * 4 / 10) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max * 4 / 10, 0)
        }
        fun ring() {
            runCatching {
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                android.media.RingtoneManager.getRingtone(c, uri)?.play()
            }
        }
        if (s == null) { ring(); pr.finish(); return }
        val token = androidx.media3.session.SessionToken(c, ComponentName(c, PlaybackService::class.java))
        val future = androidx.media3.session.MediaController.Builder(c, token).buildAsync()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        future.addListener({
            val ctl = runCatching { future.get() }.getOrNull()
            if (ctl == null) { ring(); pr.finish(); return@addListener }
            val md = androidx.media3.common.MediaMetadata.Builder().setTitle("⏰ " + s.name).setArtist("Sveglia con la radio")
                .apply { s.favicon?.let { setArtworkUri(Uri.parse(it)) } }.build()
            ctl.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("radio:" + s.id).setUri(s.url).setMediaMetadata(md).build())
            ctl.setPlaybackSpeed(1f); ctl.prepare(); ctl.play()
            h.postDelayed({
                if (ctl.playerError != null || (!ctl.isPlaying && ctl.playbackState != androidx.media3.common.Player.STATE_BUFFERING)) ring()
                ctl.release(); pr.finish()
            }, 8000)
        }, androidx.core.content.ContextCompat.getMainExecutor(c))
    }
}
