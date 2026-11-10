package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun togliMarkdownLinkEmoji() {
        val raw = "## Titolo\n\nEcco **la risposta** con un [sito](https://example.com/x) e un link https://a.b/c 🎉\n\n- primo punto\n- secondo punto\n\n```kotlin\nval x = 1\n```\nFine."
        val t = SpeechText.clean(raw)
        assertFalse(t.contains("*")); assertFalse(t.contains("#")); assertFalse(t.contains("http")); assertFalse(t.contains("🎉"))
        assertFalse(t.contains("val x"))
        assertTrue(t.contains("la risposta")); assertTrue(t.contains("sito")); assertTrue(t.contains("link"))
        assertTrue(t.contains("primo punto") && t.contains("secondo punto")); assertTrue(t.contains("codice omesso"))
        assertTrue(t.endsWith("Fine."))
    }

    @Test fun testoNormaleResta() {
        assertEquals("Ciao, come stai? Tutto bene.", SpeechText.clean("Ciao, come stai?\nTutto bene."))
        assertEquals("Un titolo. Poi il testo.", SpeechText.clean("Un titolo\nPoi il testo."))
    }

    @Test fun vuoto() {
        assertEquals("", SpeechText.clean("   "))
        assertEquals("", SpeechText.clean("🎉🎉"))
        assertTrue(SpeechText.chunks("", 100).isEmpty())
    }

    @Test fun pezziNonTagliatiAMeta() {
        val s = "Prima frase abbastanza lunga. Seconda frase altrettanto lunga! Terza frase? Quarta frase finale."
        val c = SpeechText.chunks(s, 45)
        assertTrue(c.all { it.length <= 45 })
        assertEquals(s.replace(" ", ""), c.joinToString("").replace(" ", ""))   // niente si perde
        assertTrue(c.dropLast(1).all { it.last() in ".!?" })                    // si taglia a fine frase
    }

    @Test fun parolaLunghissimaOFraseSenzaPunteggiatura() {
        val s = ("parola ").repeat(50).trim()
        val c = SpeechText.chunks(s, 60)
        assertTrue(c.all { it.length <= 60 })
        assertTrue(c.all { !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals(s, c.joinToString(" "))
    }

    @Test fun emojiInTestaAlleEtichette() {
        assertEquals("💰" to "Spese", EmojiText.split("💰 Spese"))
        assertEquals("⏱" to "Tabata", EmojiText.split("⏱ Tabata"))
        assertEquals("✏️" to "Tara con una quota", EmojiText.split("✏️ Tara con una quota"))
        assertEquals(null to "Nessuna emoji", EmojiText.split("Nessuna emoji"))
        assertEquals(null to "🎉", EmojiText.split("🎉"))                 // solo emoji: resta intera
        assertEquals("Report privacy", EmojiText.strip("🛡 Report privacy"))
        assertEquals("Rifiuta i banner", EmojiText.strip("🍪 Rifiuta i banner"))
    }
}
