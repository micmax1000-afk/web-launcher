package com.weblauncher.app

/** Prepares AI answers and articles to be read aloud (pure, unit-tested). */
object SpeechText {
    private val emoji = Regex("[\\p{So}\\p{Cs}\\u200d\\ufe0f\\u20e3]")

    /** No markdown, code blocks, links or emoji: only what sounds natural when spoken. */
    fun clean(raw: String): String {
        var t = raw
        t = t.replace(Regex("```[\\s\\S]*?```"), " (codice omesso). ")
        t = t.replace(Regex("`([^`]*)`"), "$1")
        t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")                    // images
        t = t.replace(Regex("\\[([^\\]]+)]\\((?:[^)]*)\\)"), "$1")               // [text](url) -> text
        t = t.replace(Regex("https?://\\S+"), "link")
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                      // headings
        t = t.replace(Regex("(?m)^\\s*[-*•]\\s+"), ". ")                         // bullets become a pause
        t = t.replace(Regex("(?m)^\\s*>+\\s?"), "")
        t = t.replace(Regex("(?m)^\\s*\\|?[-:| ]{3,}\\|?\\s*$"), " ")             // table rules
        t = t.replace(Regex("[*_~|]+"), " ")
        t = emoji.replace(t, " ")
        t = t.replace(Regex("(?<=[.!?…:;,])[ \\t]*\\n+\\s*"), " ")                 // already punctuated: just a space
        t = t.replace(Regex("\\s*\\n+\\s*"), ". ")
        t = t.replace(Regex("\\.(\\s*\\.)+"), ".")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.trimStart('.', ' ')
    }

    /** Pieces of at most [max] characters, cut at sentence ends (or spaces) so the voice never stops mid-word. */
    fun chunks(text: String, max: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val limit = max.coerceAtLeast(40)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur = StringBuilder() }
        for (sentence in text.split(Regex("(?<=[.!?…;:])\\s+"))) {
            var s = sentence
            while (s.length > limit) {
                val cut = s.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                if (cur.length + cut + 1 > limit) flush()
                cur.append(s, 0, cut).append(' '); flush()
                s = s.substring(cut).trimStart()
            }
            if (cur.length + s.length + 1 > limit) flush()
            cur.append(s).append(' ')
        }
        flush()
        return out
    }
}

/** Leading emoji of a label ("💰 Spese" → "💰" + "Spese"): rows show them in a coloured tile, settings drop them. */
object EmojiText {
    private val leading = Regex("^([\\p{So}\\p{Cs}\\u200d\\ufe0f\\u20e3]+)\\s*")

    /** (emoji or null, the rest). A label that is only an emoji is left whole. */
    fun split(label: String): Pair<String?, String> {
        val m = leading.find(label) ?: return null to label
        val rest = label.substring(m.range.last + 1)
        return if (rest.isBlank()) null to label else m.groupValues[1] to rest
    }
    fun strip(label: String) = split(label).second
}
