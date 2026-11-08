package com.weblauncher.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipInputStream

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val root: String,          // folder of the OPF inside the book, e.g. "OEBPS/"
    val spine: List<String>,   // chapter hrefs relative to root, reading order
    val titles: List<String>,  // chapter titles (same size as spine)
    val cover: String?,        // href relative to root
    val added: Long
)

object Ebooks {
    fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    private fun store(c: Context) = File(dir(c), "library.json")

    fun load(c: Context): MutableList<Book> = runCatching {
        Gson().fromJson<MutableList<Book>>(store(c).readText(), object : TypeToken<MutableList<Book>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()

    fun save(c: Context, list: List<Book>) { runCatching { store(c).writeText(Gson().toJson(list)) } }

    fun delete(c: Context, id: String) {
        File(dir(c), id).deleteRecursively()
        save(c, load(c).filter { it.id != id })
        c.getSharedPreferences("wl_books", Context.MODE_PRIVATE).edit().remove("ch_$id").remove("pos_$id").apply()
    }

    fun progress(c: Context, id: String): Pair<Int, Float> {
        val p = c.getSharedPreferences("wl_books", Context.MODE_PRIVATE)
        return p.getInt("ch_$id", 0) to p.getFloat("pos_$id", 0f)
    }

    fun saveProgress(c: Context, id: String, chapter: Int, ratio: Float) {
        c.getSharedPreferences("wl_books", Context.MODE_PRIVATE).edit().putInt("ch_$id", chapter).putFloat("pos_$id", ratio)
            .putLong("last_$id", System.currentTimeMillis()).apply()
    }

    /** Blocking: unzips an EPUB into the app's private folder and reads its structure. */
    fun import(c: Context, uri: Uri): Book {
        val id = "b" + System.currentTimeMillis()
        val out = File(dir(c), id).apply { mkdirs() }
        val canonical = out.canonicalPath + File.separator
        c.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                var total = 0L
                while (e != null) {
                    val f = File(out, e.name)
                    if (!f.canonicalPath.startsWith(canonical)) throw SecurityException("Percorso non valido nel file")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { o -> total += zip.copyTo(o) }
                        if (total > 300L * 1024 * 1024) throw IllegalStateException("Libro troppo grande")
                    }
                    e = zip.nextEntry
                }
            }
        }
        try {
            val opfPath = rootfile(File(out, "META-INF/container.xml")) ?: throw IllegalStateException("Non è un EPUB valido")
            val opf = File(out, opfPath)
            val root = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            File(out, "META-INF/encryption.xml").takeIf { it.exists() }?.readText()?.let { enc ->
                val algos = Regex("Algorithm=\"([^\"]+)\"").findAll(enc).map { it.groupValues[1] }.toList()
                // font obfuscation is harmless; anything else means DRM-protected content
                if (algos.any { !it.contains("idpf.org/2008/embedding") && !it.contains("ns.adobe.com/pdf/enc#RC") })
                    throw IllegalStateException("Libro protetto (DRM): non si può aprire")
            }
            val info = parseOpf(opf)
            if (info.spine.isEmpty()) throw IllegalStateException("Il libro non ha capitoli leggibili")
            val titles = info.spine.mapIndexed { i, href -> info.toc[href.substringBefore('#')] ?: "Capitolo ${i + 1}" }
            val book = Book(id, info.title.ifBlank { "Senza titolo" }, info.author, root, info.spine, titles, info.cover, System.currentTimeMillis())
            save(c, load(c) + book)
            return book
        } catch (t: Throwable) {
            out.deleteRecursively(); throw t
        }
    }

    private fun parser(f: File): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(f.inputStream(), null)
    }

    private fun rootfile(container: File): String? {
        if (!container.exists()) return null
        val p = parser(container)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("rootfile")) return p.getAttributeValue(null, "full-path")
        }
        return null
    }

    private class OpfInfo(val title: String, val author: String, val spine: List<String>, val toc: Map<String, String>, val cover: String?)

    private fun parseOpf(opf: File): OpfInfo {
        val p = parser(opf)
        var title = ""; var author = ""; var tag = ""
        val manifest = HashMap<String, Triple<String, String, String>>() // id -> href, type, properties
        val spineIds = mutableListOf<String>()
        var coverId: String? = null
        var tocId: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.substringAfter(':').lowercase()
                    when (tag) {
                        "item" -> manifest[p.getAttributeValue(null, "id") ?: ""] = Triple(
                            p.getAttributeValue(null, "href") ?: "", p.getAttributeValue(null, "media-type") ?: "",
                            p.getAttributeValue(null, "properties") ?: "")
                        "itemref" -> if (p.getAttributeValue(null, "linear") != "no") spineIds.add(p.getAttributeValue(null, "idref") ?: "")
                        "meta" -> if (p.getAttributeValue(null, "name") == "cover") coverId = p.getAttributeValue(null, "content")
                        "spine" -> tocId = p.getAttributeValue(null, "toc")
                    }
                }
                XmlPullParser.TEXT -> {
                    if (tag == "title" && title.isEmpty()) title = p.text.trim()
                    if (tag == "creator" && author.isEmpty()) author = p.text.trim()
                }
                XmlPullParser.END_TAG -> tag = ""
            }
        }
        val spine = spineIds.mapNotNull { manifest[it]?.first?.takeIf { h -> h.isNotBlank() } }
        val cover = manifest.values.firstOrNull { it.third.contains("cover-image") }?.first ?: coverId?.let { manifest[it]?.first }
        val dir = opf.parentFile!!
        val toc = HashMap<String, String>()
        runCatching {
            manifest.values.firstOrNull { it.third.contains("nav") }?.let { navToc(File(dir, decode(it.first)), it.first, toc) }
            if (toc.isEmpty()) (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.second.contains("ncx") })
                ?.let { ncxToc(File(dir, decode(it.first)), it.first, toc) }
        }
        return OpfInfo(title, author, spine, toc, cover)
    }

    private fun decode(h: String) = runCatching { java.net.URLDecoder.decode(h, "UTF-8") }.getOrDefault(h)

    /** Paths in the TOC are relative to the TOC file: make them relative to the OPF folder. */
    private fun rel(tocHref: String, target: String): String {
        val base = tocHref.substringBeforeLast('/', "")
        val raw = (if (base.isEmpty()) target else "$base/$target").substringBefore('#')
        val parts = mutableListOf<String>()
        raw.split('/').forEach { if (it == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) } else if (it != "." && it.isNotEmpty()) parts.add(it) }
        return parts.joinToString("/")
    }

    private fun navToc(f: File, href: String, out: MutableMap<String, String>) {
        val html = f.readText()
        Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val key = rel(href, m.groupValues[1])
            val t = m.groupValues[2].replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty() && key !in out) out[key] = t
        }
    }

    private fun ncxToc(f: File, href: String, out: MutableMap<String, String>) {
        val p = parser(f)
        var label = ""; var inText = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                    "navpoint" -> label = ""
                    "text" -> inText = true
                    "content" -> {
                        val key = rel(href, p.getAttributeValue(null, "src") ?: "")
                        if (label.isNotBlank() && key !in out) out[key] = label.trim()
                    }
                }
                XmlPullParser.TEXT -> if (inText) label += p.text
                XmlPullParser.END_TAG -> if (p.name.equals("text", true)) inText = false
            }
        }
    }
}
