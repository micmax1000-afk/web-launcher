package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTextTest {
    @Test fun paragraphsFromXhtml() {
        val html = "<html><head><title>X</title><style>p{}</style></head><body><h1>Capitolo&nbsp;3</h1><p>La mattina&#8230; <i>dopo</i> il paese.</p><p>&laquo;Ciao&raquo; &amp; fine<sup>1</sup></p><p>* * *</p><script>x()</script></body></html>"
        assertEquals(listOf("Capitolo 3", "La mattina… dopo il paese.", "«Ciao» & fine"), ReadText.paragraphs(html))
    }
    @Test fun splitsSentences() {
        assertEquals(listOf("Marta restò in silenzio.", "Non aveva mai visto il lago così fermo!", "Scese in cucina."),
            ReadText.sentences("Marta restò in silenzio. Non aveva mai visto il lago così fermo! Scese in cucina."))
    }
    @Test fun dialogueAndAbbreviationsStayTogether() {
        assertEquals(listOf("«Hai dormito?» chiese la vecchia senza voltarsi.", "Marta scosse la testa."),
            ReadText.sentences("«Hai dormito?» chiese la vecchia senza voltarsi. Marta scosse la testa."))
        assertEquals(listOf("Il sig. Rossi arrivò alle 5.", "Poi partì."), ReadText.sentences("Il sig. Rossi arrivò alle 5. Poi partì."))
        assertEquals(1, ReadText.sentences("Il dott. Bianchi, l'ing. Verdi e il prof. Neri uscirono insieme.").size)
    }
    @Test fun longSentenceIsCutAtACommaAndNothingIsLost() {
        val s = (1..60).joinToString(", ") { "parola$it" } + "."
        val parts = ReadText.splitLong(s, 120)
        assertTrue(parts.all { it.length <= 120 })
        assertEquals(s.replace(Regex("\\s+"), " "), parts.joinToString(" "))
    }
    @Test fun chunksRespectTheLimit() {
        val s = List(30) { "Frase numero $it con un po' di testo per allungare." }
        val ch = ReadText.chunks(s, 300)
        assertTrue(ch.size > 1)
        assertEquals((0 until 30).toList(), ch.flatMap { it.toList() })
        assertTrue(ch.all { r -> s.subList(r.first, r.last + 1).sumOf { it.length + 1 } <= 300 + s[r.last].length })
        assertEquals(emptyList<IntRange>(), ReadText.chunks(emptyList()))
    }
    @Test fun timeLeft() {
        assertEquals(10, ReadText.minutesLeft(9000, 1f))
        assertEquals(5, ReadText.minutesLeft(9000, 2f))
        assertEquals(0, ReadText.minutesLeft(0, 1f))
    }
    @Test fun wavHeader() {
        val pcm = ByteArray(480) { it.toByte() }
        val w = ReadText.pcmToWav(pcm, 24000)
        assertEquals(44 + 480, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(24000, (w[24].toInt() and 0xFF) or ((w[25].toInt() and 0xFF) shl 8))
        assertEquals(480, (w[40].toInt() and 0xFF) or ((w[41].toInt() and 0xFF) shl 8))
        assertEquals(24000, ReadText.rateOf("audio/L16;codec=pcm;rate=24000")); assertEquals(16000, ReadText.rateOf("audio/L16;rate=16000"))
    }
}
