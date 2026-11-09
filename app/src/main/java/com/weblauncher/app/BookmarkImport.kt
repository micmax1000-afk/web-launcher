package com.weblauncher.app

/** A bookmark read from a Chrome / Firefox "bookmarks.html" export (Netscape format). */
data class ImportedBookmark(val title: String, val url: String, val folder: String)

object BookmarkImport {
    private val tagRegex = Regex("""<(/?)(H3|A|DL)\b([^>]*)>([^<]*)""", RegexOption.IGNORE_CASE)
    private val hrefRegex = Regex("""HREF\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<ImportedBookmark> {
        val out = mutableListOf<ImportedBookmark>()
        val folders = ArrayDeque<String>()
        var pendingFolder: String? = null
        for (m in tagRegex.findAll(html)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].uppercase()
            val attrs = m.groupValues[3]
            val text = decode(m.groupValues[4]).trim()
            when (tag) {
                "H3" -> if (!closing) pendingFolder = text
                "DL" -> if (!closing) {
                    folders.addLast(pendingFolder ?: folders.lastOrNull() ?: "")
                    pendingFolder = null
                } else if (folders.isNotEmpty()) folders.removeLast()
                "A" -> if (!closing) {
                    val url = hrefRegex.find(attrs)?.groupValues?.get(1)?.let { decode(it) } ?: continue
                    if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                    val folder = folders.lastOrNull { it.isNotBlank() && !isRootFolder(it) } ?: "Importati"
                    out.add(ImportedBookmark(text.ifBlank { url }, url, folder))
                }
            }
        }
        return out.distinctBy { it.url.trimEnd('/').lowercase() }
    }

    private fun isRootFolder(name: String): Boolean {
        val n = name.lowercase()
        return n in setOf(
            "bookmarks", "bookmarks bar", "barra dei preferiti", "preferiti", "other bookmarks",
            "altri preferiti", "mobile bookmarks", "preferiti mobile", "bookmarks toolbar", "bookmarks menu"
        )
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
