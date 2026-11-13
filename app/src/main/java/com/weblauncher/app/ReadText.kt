package com.weblauncher.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns the XHTML of a chapter into sentences to read aloud (pure functions, unit-tested). */
object ReadText {
    private val ABBR = setOf("sig", "sigg", "sig.ra", "dott", "dr", "dott.ssa", "prof", "ing", "avv", "pag", "pagg", "ecc", "es", "cfr", "mr", "mrs", "ms", "vs", "art", "arch", "geom", "rag", "on", "gen", "col", "cap", "fig", "tel", "ca", "etc", "p", "pp", "vol", "ss")

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "laquo" to "«", "raquo" to "»",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "egrave" to "è", "eacute" to "é",
        "agrave" to "à", "ograve" to "ò", "ugrave" to "ù", "igrave" to "ì", "shy" to "")

    fun decodeEntities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") -> g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTITIES[g] ?: m.value
        }
    }

    private fun collapse(s: String) = s.replace(Regex("[\\s\\u00A0]+"), " ").trim()

    /** Visible text of an XHTML chapter, one string per paragraph. */
    fun paragraphs(html: String): List<String> {
        var t = html
        t = Regex("<head\\b.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(t, " ")
        t = Regex("<(script|style|rt|sup)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(t, " ")
        t = Regex("<br\\s*/?>|</(p|div|h[1-6]|li|tr|blockquote|section|figcaption|dd|dt)>", RegexOption.IGNORE_CASE).replace(t, "\n")
        t = Regex("<[^>]*>").replace(t, "")
        return decodeEntities(t).split('\n').map { collapse(it) }.filter { p -> p.any { it.isLetterOrDigit() } }
    }

    private fun lastWord(s: String) = s.trimEnd().substringAfterLast(' ').substringAfterLast('\'').substringAfterLast('’').trim('«', '"', '“', '(', '—', '–').lowercase()

    /** Splits a paragraph into sentences; dialogue («Hai dormito?» chiese…) and abbreviations (sig. Rossi) are not cut. */
    fun sentences(paragraph: String, max: Int = 280): List<String> {
        val t = collapse(paragraph)
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0; var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < t.length && (t[j] == '.' || t[j] == '!' || t[j] == '?' || t[j] == '…')) j++
                while (j < t.length && t[j] in "\"»”’')]") j++
                if (j < t.length && t[j] == ' ') {
                    val next = t.substring(j + 1).firstOrNull { it != ' ' }
                    val w = lastWord(t.substring(start, i))
                    val abbr = c == '.' && (w in ABBR || (w.length == 1 && w[0].isLetter()))
                    val upperNext = next != null && (next.isUpperCase() || next in "«\"“‘(—–0123456789")
                    if (!abbr && upperNext) { out += t.substring(start, j).trim(); start = j + 1; i = j + 1; continue }
                }
                i = j; continue
            }
            i++
        }
        if (start < t.length) out += t.substring(start).trim()
        return out.filter { it.isNotBlank() }.flatMap { splitLong(it, max) }
    }

    /** A very long sentence is cut at the last comma, semicolon or colon (or space) before [max] characters. */
    fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>(); var rest = s
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "))
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut < 1) cut = max - 1
            out += rest.substring(0, cut + 1).trim(); rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun chapterSentences(html: String): List<String> = paragraphs(html).flatMap { sentences(it) }

    /** Groups sentences (as index ranges) into pieces of at most [maxChars] characters, for the natural voice. */
    fun chunks(sentences: List<String>, maxChars: Int = 1100): List<IntRange> {
        if (sentences.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>(); var first = 0; var len = 0
        sentences.forEachIndexed { i, s ->
            if (len > 0 && len + s.length + 1 > maxChars) { out += first until i; first = i; len = 0 }
            len += s.length + 1
        }
        out += first..sentences.lastIndex
        return out
    }

    /** Minutes still to read at [speed] (about 900 characters per minute at normal speed). */
    fun minutesLeft(charsLeft: Long, speed: Float): Int = Math.ceil(charsLeft / (900.0 * speed.coerceAtLeast(0.3f))).toInt().coerceAtLeast(0)

    /** Raw 16-bit mono PCM → WAV file bytes. */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 24000): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    /** «audio/L16;codec=pcm;rate=24000» → 24000 */
    fun rateOf(mime: String): Int = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
}
