package com.weblauncher.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Podcast(val feedUrl: String, val title: String, val author: String, val artwork: String?)

data class Episode(
    val id: String,
    val podcastTitle: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val time: Long,
    val durationSec: Long,
    val artwork: String?,
    val description: String
)

data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String?,
    val tags: String,
    val state: String,
    val bitrate: Int
)

/** Positions, finished episodes and favourites, shared by the activity and the playback service. */
object AudioStore {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_audio", Context.MODE_PRIVATE)

    fun savePosition(c: Context, id: String, pos: Long, dur: Long) {
        val e = prefs(c).edit().putLong("pos_$id", pos).putLong("dur_$id", dur)
        if (dur > 0 && pos > dur - 30_000) e.putBoolean("done_$id", true)
        e.apply()
    }
    fun position(c: Context, id: String) = prefs(c).getLong("pos_$id", 0L)
    fun duration(c: Context, id: String) = prefs(c).getLong("dur_$id", 0L)
    fun isFinished(c: Context, id: String) = prefs(c).getBoolean("done_$id", false)
    fun markFinished(c: Context, id: String) { prefs(c).edit().putBoolean("done_$id", true).putLong("pos_$id", 0L).apply() }
}

class AudioRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("wl_audio", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val episodesFile get() = java.io.File(context.filesDir, "podcast_episodes.json")

    // ---------------- podcasts ----------------

    fun subscriptions(): List<Podcast> = try {
        gson.fromJson<List<Podcast>>(prefs.getString("subs", "[]"), object : TypeToken<List<Podcast>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun isSubscribed(feed: String) = subscriptions().any { it.feedUrl == feed }

    fun subscribe(p: Podcast) {
        val list = subscriptions().filter { it.feedUrl != p.feedUrl } + p
        prefs.edit().putString("subs", gson.toJson(list)).apply()
    }

    fun unsubscribe(feed: String) {
        prefs.edit().putString("subs", gson.toJson(subscriptions().filter { it.feedUrl != feed })).apply()
        saveEpisodes(cachedEpisodes().filter { it.feedUrl != feed })
    }

    fun cachedEpisodes(): List<Episode> = try {
        gson.fromJson<List<Episode>>(episodesFile.readText(), object : TypeToken<List<Episode>>() {}.type)
            ?.filterNotNull() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun saveEpisodes(list: List<Episode>) { runCatching { episodesFile.writeText(gson.toJson(list)) } }

    /** Blocking. Latest episodes of every subscription, newest first. */
    fun refreshEpisodes(): List<Episode> {
        val subs = subscriptions()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val all = try {
            pool.invokeAll(subs.map { p -> java.util.concurrent.Callable { runCatching { episodes(p.feedUrl).take(30) }.getOrDefault(emptyList()) } },
                60, java.util.concurrent.TimeUnit.SECONDS)
                .flatMap { f -> runCatching { if (f.isCancelled) emptyList() else f.get() }.getOrDefault(emptyList()) }
        } finally { pool.shutdownNow() }
        val result = if (all.isEmpty()) cachedEpisodes() else all.sortedByDescending { it.time }
        if (all.isNotEmpty()) saveEpisodes(result)
        return result
    }

    /** Apple Podcasts public directory: free, no account, used only to find RSS feeds. */
    fun search(term: String): List<Podcast> {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&country=it&limit=30&term=" + Uri.encode(term)
        val arr = org.json.JSONObject(String(download(url), Charsets.UTF_8)).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val feed = o.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Podcast(feed, o.optString("collectionName"), o.optString("artistName"),
                o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.takeIf { it.startsWith("http") })
        }
    }

    /** Blocking. Parses a podcast RSS feed. */
    fun episodes(feedUrl: String): List<Episode> {
        val bytes = download(feedUrl)
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(java.io.ByteArrayInputStream(bytes), null)
        runCatching { parser.defineEntityReplacementText("nbsp", " ") }
        var channelTitle = ""
        var channelImage: String? = null
        val out = mutableListOf<Episode>()
        var inItem = false
        var tag = ""
        var title = ""; var audio = ""; var date = ""; var dur = ""; var desc = ""; var guid = ""; var image: String? = null
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name.lowercase(Locale.ROOT)
                        when {
                            tag == "item" -> { inItem = true; title = ""; audio = ""; date = ""; dur = ""; desc = ""; guid = ""; image = null }
                            tag == "enclosure" && inItem -> {
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                val u = parser.getAttributeValue(null, "url")
                                if (u != null && (type.startsWith("audio") || type.isEmpty() || type.startsWith("video"))) audio = u
                            }
                            tag == "itunes:image" -> {
                                val h = parser.getAttributeValue(null, "href")
                                if (inItem) image = h else if (channelImage == null) channelImage = h
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text ?: ""
                        if (inItem) when (tag) {
                            "title" -> title += t
                            "pubdate" -> date += t
                            "itunes:duration" -> dur += t
                            "description", "itunes:summary" -> if (desc.length < 2000) desc += t
                            "guid" -> guid += t
                        } else when (tag) {
                            "title" -> if (channelTitle.isEmpty()) channelTitle = t.trim()
                            "url" -> if (channelImage == null && t.startsWith("http")) channelImage = t.trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", true) && inItem) {
                            inItem = false
                            if (audio.startsWith("http") && title.isNotBlank()) {
                                val key = guid.trim().ifBlank { audio }
                                out.add(Episode(
                                    id = "ep:" + Integer.toHexString((feedUrl + key).hashCode()),
                                    podcastTitle = channelTitle,
                                    feedUrl = feedUrl,
                                    title = clean(title),
                                    audioUrl = audio.replace("&amp;", "&"),
                                    time = parseDate(date.trim()),
                                    durationSec = parseDuration(dur.trim()),
                                    artwork = image ?: channelImage,
                                    description = clean(desc).take(600)
                                ))
                            }
                        }
                        tag = ""
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) { }
        return out
    }

    fun podcastInfo(feedUrl: String, fallback: Podcast?): Podcast {
        val eps = runCatching { episodes(feedUrl) }.getOrDefault(emptyList())
        val first = eps.firstOrNull()
        return Podcast(feedUrl, first?.podcastTitle?.ifBlank { null } ?: fallback?.title ?: "Podcast",
            fallback?.author.orEmpty(), fallback?.artwork ?: first?.artwork)
    }

    // ---------------- downloads ----------------

    fun localFile(e: Episode): java.io.File =
        java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS), e.id.removePrefix("ep:") + ".mp3")

    fun isDownloaded(e: Episode) = localFile(e).let { it.exists() && it.length() > 100_000 }

    // ---------------- radio (radio-browser.info, free and open) ----------------

    fun favoriteStations(): List<RadioStation> = try {
        gson.fromJson<List<RadioStation>>(prefs.getString("radio_favs", "[]"), object : TypeToken<List<RadioStation>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun toggleFavorite(s: RadioStation): Boolean {
        val list = favoriteStations().toMutableList()
        val had = list.removeAll { it.id == s.id }
        if (!had) list.add(s)
        prefs.edit().putString("radio_favs", gson.toJson(list)).apply()
        return !had
    }

    fun stations(query: String): List<RadioStation> {
        for (host in RADIO_HOSTS) {
            val res = runCatching {
                val arr = JSONArray(String(download("https://$host/json/stations/search?$query&hidebroken=true&limit=40"), Charsets.UTF_8))
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (!url.startsWith("http")) return@mapNotNull null
                    RadioStation(o.optString("stationuuid"), o.optString("name").trim(), url,
                        o.optString("favicon").takeIf { it.startsWith("http") }, o.optString("tags"), o.optString("state"), o.optInt("bitrate"))
                }.distinctBy { it.name.lowercase(Locale.ROOT) }
            }.getOrNull()
            if (res != null) return res
        }
        return emptyList()
    }

    fun nationalStations() = stations("countrycode=IT&order=clickcount&reverse=true")
    fun regionStations(names: List<String>): List<RadioStation> {
        for (n in names) {
            val r = stations("countrycode=IT&state=" + Uri.encode(n) + "&order=clickcount&reverse=true")
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }
    fun searchStations(term: String) = stations("name=" + Uri.encode(term) + "&order=clickcount&reverse=true")

    // ---------------- helpers ----------------

    private fun download(url: String): ByteArray {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "WebLauncher/5 (Android)")
            if (c.responseCode in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString(); c.disconnect()
            } else {
                try {
                    if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                    return c.inputStream.use { it.readBytes() }
                } finally { c.disconnect() }
            }
        }
        throw java.io.IOException("redirect")
    }

    private fun clean(s: String) = androidx.core.text.HtmlCompat.fromHtml(s.replace(Regex("<[^>]*>"), " "),
        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun parseDuration(s: String): Long {
        if (s.isBlank()) return 0
        val parts = s.split(":").mapNotNull { it.trim().toDoubleOrNull()?.toLong() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun parseDate(s: String): Long {
        for (p in arrayOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                val f = SimpleDateFormat(p, Locale.ENGLISH); f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(s)?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0L
    }

    companion object {
        private val RADIO_HOSTS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info")
        val REGIONS = listOf(
            "Piemonte" to listOf("Piemonte", "Piedmont"),
            "Lombardia" to listOf("Lombardia", "Lombardy"),
            "Umbria" to listOf("Umbria")
        )
    }
}
