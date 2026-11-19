package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfAiToolsTest {
    @Test fun subjectLine() {
        val (s, b) = AiTools.splitSubject("Oggetto: Disdetta abbonamento\n\nGentile palestra,\nscrivo per…")
        assertEquals("Disdetta abbonamento", s)
        assertTrue(b.startsWith("Gentile palestra"))
        val (s2, b2) = AiTools.splitSubject("**Oggetto:** Reclamo\nTesto")
        assertEquals("Reclamo", s2); assertEquals("Testo", b2)
        assertNull(AiTools.splitSubject("Ciao Marco!").first)
    }

    @Test fun jsonArrayFromAnswer() {
        assertEquals(listOf("Ciao", "Mondo"), AiTools.stringArray("```json\n[\"Ciao\", \"Mondo\"]\n```"))
        assertNull(AiTools.stringArray("non so"))
    }

    @Test fun batchesKeepEverything() {
        val texts = List(130) { "x".repeat(50) }
        val b = AiTools.batches(texts, maxChars = 1000, maxItems = 60)
        assertEquals(0, b.first().first); assertEquals(129, b.last().last)
        assertTrue(b.all { it.count() <= 20 })
        assertEquals(130, b.sumOf { it.count() })
        assertEquals(listOf(0..0), AiTools.batches(listOf("y".repeat(5000))))
        assertTrue(AiTools.batches(emptyList()).isEmpty())
    }

    @Test fun foreignLanguage() {
        assertTrue(AiTools.isForeign("en-US")); assertTrue(AiTools.isForeign("de"))
        assertFalse(AiTools.isForeign("it-IT")); assertFalse(AiTools.isForeign("")); assertFalse(AiTools.isForeign(null)); assertFalse(AiTools.isForeign("und"))
    }

    @Test fun writePromptMentionsWhat() {
        val p = AiTools.writePrompt("Disdetta", "Deciso", " disdire la palestra ")
        assertTrue(p.contains("«disdire la palestra»")); assertTrue(p.contains("disdetta")); assertTrue(p.contains("deciso"))
    }
}
