package com.weblauncher.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Song lyrics from LRCLIB (free community database, no account): synchronized when available, plain otherwise. */
object Lyrics {
    data class Line(val timeMs: Long, val text: String)

    sealed class Result {
        data class Synced(val lines: List<Line>) : Result()
        data class Plain(val text: String) : Result()
        object Instrumental : Result()
        object None : Result()
    }

    // ---------- pure helpers (tested) ----------

    /** «[01:23.45] text» → lines sorted by time; empty text lines are kept (instrumental breaks). */
    fun parseLrc(lrc: String): List<Line> {
        val out = mutableListOf<Line>()
        val tag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        lrc.lineSequence().forEach { raw ->
            val stamps = tag.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val text = raw.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let { if (it.isEmpty()) 0L else (it.padEnd(3, '0').take(3)).toLong() }
                out += Line(min * 60_000 + sec * 1000 + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [posMs], or -1 before the first line. */
    fun activeIndex(lines: List<Line>, posMs: Long): Int {
        if (lines.isEmpty() || posMs < lines.first().timeMs) return -1
        var lo = 0; var hi = lines.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (lines[mid].timeMs <= posMs) lo = mid else hi = mid - 1 }
        return lo
    }

    private val NOISE = Regex(
        "\\s*[(\\[][^)\\]]*(official|video|audio|lyrics?|testo|live|hd|4k|remaster(ed)?|clip|visualizer|m/v|mv)[^)\\]]*[)\\]]|\\s*-\\s*topic\\s*$|\\s*\\|.*$",
        RegexOption.IGNORE_CASE)

    /** (artist, title) cleaned for the search; splits «Artist - Title» when the artist is missing. */
    fun cleanQuery(title: String, artist: String): Pair<String, String> {
        var t = NOISE.replace(title, "").trim()
        var a = NOISE.replace(artist, "").trim().replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        // «Artista - Titolo» in the title
        val split = Regex("^(.{2,60}?)\\s+[-–—]\\s+(.{2,})$").find(t)
        if (split != null && (a.isBlank() || t.startsWith(a, ignoreCase = true) || a.startsWith(split.groupValues[1], ignoreCase = true))) {
            a = split.groupValues[1].trim(); t = split.groupValues[2].trim()
        }
        t = t.replace(Regex("\\s+(feat\\.?|ft\\.?|con)\\s+.*$", RegexOption.IGNORE_CASE), "").trim()
        return a to t
    }

    private fun parse(json: JSONObject): Result {
        if (json.optBoolean("instrumental")) return Result.Instrumental
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) parseLrc(synced).takeIf { it.size >= 3 }?.let { return Result.Synced(it) }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return if (plain != null) Result.Plain(plain.trim()) else Result.None
    }

    /** Picks the best candidate of a search: duration within 4 s when known, synced ones first. */
    fun pick(arr: JSONArray, durSec: Long): JSONObject? {
        val c = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val close = if (durSec > 0) c.filter { Math.abs(it.optDouble("duration", -100.0) - durSec) <= 4 } else c
        return (close.ifEmpty { if (durSec > 0) emptyList() else c })
            .sortedByDescending { (if (it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" }) 2 else 0) + if (it.optString("plainLyrics").let { s -> s.isNotBlank() && s != "null" }) 1 else 0 }
            .firstOrNull()
    }

    // ---------- network + cache ----------

    private const val BASE = "https://lrclib.net/api"
    private fun key(artist: String, title: String) = MessageDigest.getInstance("MD5").digest("${artist.lowercase()}|${title.lowercase()}".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    private fun http(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "WebLauncher (lyrics)")
        if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** Looks the lyrics up (blocking: call from a background thread). Remembered on the phone for next time. */
    fun fetch(c: Context, title: String, artist: String, durSec: Long): Result {
        val (a, t) = cleanQuery(title, artist)
        if (t.isBlank()) return Result.None
        val dir = File(c.cacheDir, "lyrics").apply { mkdirs() }
        val f = File(dir, key(a, t) + ".json")
        runCatching { if (f.exists()) return parse(JSONObject(f.readText())) }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var body = http("$BASE/get?artist_name=${enc(a)}&track_name=${enc(t)}" + if (durSec > 0) "&duration=$durSec" else "")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (body == null) {
            val arr = http("$BASE/search?q=${enc("$a $t".trim())}")?.let { runCatching { JSONArray(it) }.getOrNull() }
            body = arr?.let { pick(it, durSec) }
        }
        if (body == null) return Result.None
        runCatching { f.writeText(body.toString()) }
        return parse(body)
    }
}
