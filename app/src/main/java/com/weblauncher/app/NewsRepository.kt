package com.weblauncher.app

import android.content.Context
import android.util.Xml
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class NewsSource(
    val id: String,
    val name: String,
    val cat: String,
    val url: String,
    val alt: List<String>? = null,
    val on: Boolean = false,
    val custom: Boolean = false
)

data class NewsItem(
    val id: String,
    val title: String,
    val link: String,
    val sourceId: String,
    val source: String,
    val cat: String,
    val time: Long,
    val image: String?,
    val summary: String,
    var also: List<String>? = emptyList()
)

/** RSS / Atom news: catalogue in assets, user choices in prefs, last results cached on disk. */
class NewsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("wl_news", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cacheFile get() = java.io.File(context.filesDir, "news_cache.json")

    val catalog: List<NewsSource> by lazy {
        try {
            val json = context.assets.open("news/sources.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val type = object : TypeToken<List<NewsSource>>() {}.type
            gson.fromJson<List<NewsSource>>(json, type)
        } catch (_: Exception) { emptyList() }
    }

    fun customSources(): List<NewsSource> = try {
        val type = object : TypeToken<List<NewsSource>>() {}.type
        gson.fromJson<List<NewsSource>>(prefs.getString(KEY_CUSTOM, "[]"), type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun allSources(): List<NewsSource> = catalog + customSources()

    fun enabledIds(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, null) ?: catalog.filter { it.on }.map { it.id }.toSet()

    fun setEnabled(id: String, on: Boolean) {
        val set = enabledIds().toMutableSet()
        if (on) set.add(id) else set.remove(id)
        prefs.edit().putStringSet(KEY_ENABLED, set).apply()
    }

    fun addCustom(name: String, url: String): NewsSource {
        val src = NewsSource("c_" + Integer.toHexString(url.hashCode()), name, CUSTOM_CAT, url, null, true, true)
        val list = customSources().filter { it.url != url } + src
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(list)).apply()
        setEnabled(src.id, true)
        return src
    }

    fun setEnabledIds(ids: Set<String>) { prefs.edit().putStringSet(KEY_ENABLED, ids).apply() }

    fun setCustomSources(list: List<NewsSource>) { prefs.edit().putString(KEY_CUSTOM, gson.toJson(list)).apply() }

    fun removeCustom(id: String) {
        prefs.edit().putString(KEY_CUSTOM, gson.toJson(customSources().filter { it.id != id })).apply()
        setEnabled(id, false)
    }

    fun cached(): Pair<List<NewsItem>, Long> = try {
        val type = object : TypeToken<List<NewsItem>>() {}.type
        val list = gson.fromJson<List<NewsItem>>(cacheFile.readText(), type) ?: emptyList()
        // old or partial cache entries may miss fields (Gson ignores Kotlin defaults): fill them in
        list.filter { it != null && !(it.title as String?).isNullOrBlank() && !(it.link as String?).isNullOrBlank() }
            .map {
                it.copy(
                    id = (it.id as String?) ?: it.link.hashCode().toString(),
                    sourceId = (it.sourceId as String?) ?: "",
                    source = (it.source as String?) ?: "",
                    cat = (it.cat as String?) ?: "In primo piano",
                    summary = (it.summary as String?) ?: "",
                    also = it.also ?: emptyList()
                )
            } to prefs.getLong(KEY_UPDATED, 0L)
    } catch (_: Exception) { emptyList<NewsItem>() to 0L }

    /** Blocking: call from a background thread. Returns (items, failed source names). */
    fun refresh(): Pair<List<NewsItem>, List<String>> {
        val enabled = enabledIds()
        val sources = allSources().filter { it.id in enabled }
        if (sources.isEmpty()) return emptyList<NewsItem>() to emptyList()
        val pool = Executors.newFixedThreadPool(6)
        val failed = mutableListOf<String>()
        val all = mutableListOf<NewsItem>()
        try {
            val futures = pool.invokeAll(sources.map { s -> Callable { s to fetchSource(s) } }, 40, TimeUnit.SECONDS)
            futures.forEachIndexed { i, f ->
                val res = runCatching { if (f.isCancelled) null else f.get() }.getOrNull()
                if (res == null || res.second.isEmpty()) failed.add(sources[i].name) else all.addAll(res.second)
            }
        } finally { pool.shutdownNow() }
        val items = group(all.sortedByDescending { it.time }).take(MAX_ITEMS)
        if (items.isNotEmpty()) {
            runCatching { cacheFile.writeText(gson.toJson(items)) }
            prefs.edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
        }
        return items to failed
    }

    private fun fetchSource(s: NewsSource): List<NewsItem> {
        val remembered = prefs.getString("url_" + s.id, null)
        val urls = (listOfNotNull(remembered) + s.url + (s.alt ?: emptyList())).distinct()
        for (u in urls) {
            val items = runCatching { parseFeed(download(u), s) }.getOrNull()
            if (!items.isNullOrEmpty()) {
                if (u != remembered) prefs.edit().putString("url_" + s.id, u).apply()
                return items.take(PER_SOURCE)
            }
        }
        return emptyList()
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.6")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else return c
        }
        throw java.io.IOException("Troppi reindirizzamenti")
    }

    private fun download(url: String, limit: Int = 4_000_000): ByteArray {
        val c = open(url, "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || out.size() > limit) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    // ---------------- parsing ----------------

    fun parseFeed(bytes: ByteArray, s: NewsSource): List<NewsItem> {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(java.io.ByteArrayInputStream(bytes), null)
        for ((k, v) in ENTITIES) runCatching { parser.defineEntityReplacementText(k, v) }

        val out = mutableListOf<NewsItem>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var desc = ""; var content = ""; var image: String? = null
        var tag = ""
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name.lowercase(Locale.ROOT)
                        when {
                            tag == "item" || tag == "entry" -> {
                                inItem = true
                                title = ""; link = ""; date = ""; desc = ""; content = ""; image = null
                            }
                            inItem && tag == "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (href != null && (rel == null || rel == "alternate") && link.isEmpty()) link = href
                            }
                            inItem && (tag == "enclosure" || tag == "media:content" || tag == "media:thumbnail") -> {
                                val u = parser.getAttributeValue(null, "url")
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                val medium = parser.getAttributeValue(null, "medium").orEmpty()
                                val looksImage = type.startsWith("image") || medium == "image" || tag == "media:thumbnail" ||
                                    (type.isEmpty() && u != null && Regex("\\.(jpe?g|png|webp|gif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u))
                                if (u != null && looksImage && image == null) image = u
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                        val t = parser.text ?: ""
                        when (tag) {
                            "title" -> title += t
                            "link" -> if (link.isEmpty() || link.isBlank()) link = (link + t).trim()
                            "pubdate", "published", "updated", "dc:date" -> if (date.isBlank()) date += t
                            "description", "summary" -> desc += t
                            "content:encoded", "content" -> content += t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase(Locale.ROOT)
                        if ((name == "item" || name == "entry") && inItem) {
                            inItem = false
                            val cleanTitle = cleanText(title)
                            val url = link.trim()
                            if (cleanTitle.isNotEmpty() && url.startsWith("http")) {
                                val img = image ?: firstImage(desc) ?: firstImage(content)
                                out.add(NewsItem(
                                    id = Integer.toHexString(url.hashCode()),
                                    title = cleanTitle,
                                    link = url,
                                    sourceId = s.id,
                                    source = s.name,
                                    cat = s.cat,
                                    time = parseDate(date.trim()),
                                    image = img?.replace("&amp;", "&")?.let { if (it.startsWith("//")) "https:$it" else it },
                                    summary = cleanText(desc.ifBlank { content }).take(260)
                                ))
                            }
                        }
                        tag = ""
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {
            // malformed tail: keep what was parsed
        }
        return out
    }

    private fun firstImage(html: String): String? =
        Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") || it.startsWith("//") }

    private fun cleanText(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        val decoded = androidx.core.text.HtmlCompat.fromHtml(noTags, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        return decoded.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun parseDate(s: String): Long {
        val now = System.currentTimeMillis()
        if (s.isBlank()) return now
        val text = s.replace(Regex("\\s+"), " ").trim()
        for (locale in arrayOf(Locale.ENGLISH, Locale.ITALIAN)) for (p in DATE_PATTERNS) {
            try {
                val f = SimpleDateFormat(p, locale)
                f.timeZone = TimeZone.getTimeZone("UTC")
                f.isLenient = true
                val t = f.parse(text)?.time ?: continue
                return if (t > now + 10 * 60_000L) now else t
            } catch (_: Exception) { }
        }
        return now
    }

    /** Same story from several outlets -> one card with "anche su ...". */
    private fun group(sorted: List<NewsItem>): List<NewsItem> {
        val tokens = sorted.map { tokenize(it.title) }
        val used = BooleanArray(sorted.size)
        val out = mutableListOf<NewsItem>()
        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val base = sorted[i]
            val others = mutableListOf<String>()
            if (tokens[i].size >= 3) for (j in i + 1 until sorted.size) {
                if (used[j] || sorted[j].sourceId == base.sourceId) continue
                if (kotlin.math.abs(sorted[j].time - base.time) > 36 * 3600_000L) continue
                val a = tokens[i]; val b = tokens[j]
                val inter = a.count { it in b }
                if (inter >= 3 && inter.toDouble() / (a.size + b.size - inter) >= 0.42) {
                    used[j] = true
                    if (sorted[j].source !in others && sorted[j].source != base.source) others.add(sorted[j].source)
                }
            }
            out.add(base.copy(also = others, image = base.image))
        }
        return out
    }

    private fun tokenize(title: String): Set<String> =
        java.text.Normalizer.normalize(title.lowercase(Locale.ITALIAN), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in STOP }
            .toSet()

    // ---------------- discovery & article text ----------------

    /** Finds the RSS/Atom feed of a website. Blocking. Returns (title, feedUrl) or null. */
    fun discover(input: String): Pair<String, String>? {
        val start = if (input.startsWith("http")) input else "https://$input"
        val probe = NewsSource("probe", "", CUSTOM_CAT, start)
        val bytes = runCatching { download(start, 2_000_000) }.getOrNull()
        if (bytes != null) {
            if (parseFeed(bytes, probe).isNotEmpty()) return feedTitle(bytes, start) to start
            val html = String(bytes, Charsets.UTF_8)
            val links = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
                .filter { Regex("application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .mapNotNull { Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                .map { URL(URL(start), it.replace("&amp;", "&")).toString() }
                .toList()
            for (u in links) {
                val b = runCatching { download(u) }.getOrNull() ?: continue
                if (parseFeed(b, probe).isNotEmpty()) return feedTitle(b, u) to u
            }
        }
        val root = runCatching { URL(start).let { "${it.protocol}://${it.host}" } }.getOrNull() ?: return null
        for (path in listOf("/feed", "/rss", "/feed/", "/rss.xml", "/feed.xml", "/index.xml", "/atom.xml")) {
            val u = root + path
            val b = runCatching { download(u) }.getOrNull() ?: continue
            if (parseFeed(b, probe).isNotEmpty()) return feedTitle(b, u) to u
        }
        return null
    }

    private fun feedTitle(bytes: ByteArray, url: String): String {
        val head = String(bytes, 0, minOf(bytes.size, 6000), Charsets.UTF_8)
        val t = Regex("<title[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head)?.groupValues?.get(1)?.let { cleanText(it) }
        return t?.takeIf { it.isNotBlank() }?.take(40)
            ?: (runCatching { URL(url).host.removePrefix("www.") }.getOrNull() ?: "Fonte")
    }

    /** Readable text of an article page (best effort, for AI summaries). Blocking. */
    fun articleText(url: String): String {
        val bytes = runCatching { download(url, 3_000_000) }.getOrNull() ?: return ""
        var html = String(bytes, Charsets.UTF_8)
        html = html.replace(Regex("<(script|style|noscript|svg|nav|header|footer|aside|form)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        val article = Regex("<article[\\s\\S]*?</article>", RegexOption.IGNORE_CASE).findAll(html)
            .maxByOrNull { it.value.length }?.value ?: html
        val paragraphs = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).findAll(article)
            .map { cleanText(it.groupValues[1]) }.filter { it.length > 40 }.toList()
        val text = if (paragraphs.size >= 3) paragraphs.joinToString("\n\n") else cleanText(article)
        return text.take(20000)
    }

    companion object {
        const val CUSTOM_CAT = "Le mie fonti"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_UPDATED = "updated"
        private const val PER_SOURCE = 30
        private const val MAX_ITEMS = 600
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val CATEGORY_ORDER = listOf(
            "In primo piano", "Piemonte", "Lombardia", "Umbria", "Economia", "Sport", "Tecnologia",
            "Scienza", "Esteri", "Cultura", "Motori", "Viaggi", "Internazionali", CUSTOM_CAT
        )
        private val DATE_PATTERNS = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
        )
        private val ENTITIES = mapOf(
            "nbsp" to " ", "egrave" to "è", "eacute" to "é", "agrave" to "à", "ograve" to "ò", "ugrave" to "ù",
            "igrave" to "ì", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "hellip" to "…",
            "ndash" to "–", "mdash" to "—", "laquo" to "«", "raquo" to "»", "euro" to "€", "copy" to "©"
        )
        private val STOP = setOf(
            "della", "delle", "dello", "degli", "nella", "nelle", "nello", "negli", "sulla", "sulle", "dopo",
            "come", "anche", "sono", "stato", "stata", "questo", "questa", "quando", "perché", "perche", "ecco",
            "oggi", "alla", "alle", "allo", "agli", "dalla", "dalle", "dagli", "contro", "senza", "tutto", "tutti",
            "with", "from", "that", "this", "have", "will", "after", "about", "video", "foto", "news", "ultime", "notizie"
        )
    }
}
