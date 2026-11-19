package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteToolsTest {
    @Test fun cercaEEtichetta() {
        val n = Note("1", "Ricetta torta di mele\n3 mele", 1, label = "🏠 Casa")
        assertTrue(NoteTools.matches(n, "", null)); assertTrue(NoteTools.matches(n, "MELE", null))
        assertTrue(NoteTools.matches(n, "", "🏠 Casa")); assertFalse(NoteTools.matches(n, "", "💼 Lavoro"))
        assertFalse(NoteTools.matches(n, "pizza", null))
    }

    @Test fun primaLeFissate() {
        val l = listOf(Note("a", "a", 3), Note("b", "b", 1, pinned = true), Note("c", "c", 2))
        assertEquals(listOf("b", "a", "c"), NoteTools.sorted(l).map { it.id })
    }

    @Test fun titolo() {
        assertEquals("Spesa", NoteTools.title(Note("1", "\n  Spesa  \nlatte", 0)))
        assertEquals("Foto", NoteTools.title(Note("1", "", 0, photo = "x.jpg")))
    }

    @Test fun vecchieNoteSenzaCampiNuovi() {
        val l = com.google.gson.Gson().fromJson<List<Note>>("[{\"id\":\"1\",\"text\":\"ciao\",\"time\":5}]",
            object : com.google.gson.reflect.TypeToken<List<Note>>() {}.type)
        assertFalse(l[0].pinned); assertEquals(null, l[0].label); assertEquals(null, l[0].color)
    }
}
