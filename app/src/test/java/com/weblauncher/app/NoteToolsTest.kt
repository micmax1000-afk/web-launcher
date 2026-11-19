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
        val l = Note("2", "PIN banca\n12345", 1, locked = true)
        assertTrue(NoteTools.matches(l, "banca", null)); assertFalse(NoteTools.matches(l, "12345", null))
    }

    @Test fun ordine() {
        val l = listOf(Note("a", "b", 3, color = 2), Note("b", "z", 1, pinned = true), Note("c", "a", 2, color = 0))
        assertEquals(listOf("b", "a", "c"), NoteTools.sorted(l).map { it.id })
        assertEquals(listOf("b", "c", "a"), NoteTools.sorted(l, "title").map { it.id })
        assertEquals(listOf("b", "c", "a"), NoteTools.sorted(l, "color").map { it.id })
    }

    @Test fun spunteNellaNota() {
        val (p, it) = NoteTools.split("Vacanza\nPartenza sabato\n☑ albergo\n☐ crema")
        assertEquals("Vacanza\nPartenza sabato", p); assertEquals(listOf(true to "albergo", false to "crema"), it)
        assertEquals("Vacanza\n☐ latte", NoteTools.join(" Vacanza ", listOf(false to "latte", false to " ")))
        assertTrue(NoteTools.isPureList(Note("1", "Spesa\n☐ latte", 0)))
        assertFalse(NoteTools.isPureList(Note("1", "Vacanza\nPartenza sabato\n☐ crema", 0)))
        assertFalse(NoteTools.isPureList(Note("1", "Spesa\n☐ latte", 0, photos = listOf("x.jpg"))))
        assertFalse(NoteTools.isPureList(Note("1", "solo testo", 0)))
    }

    @Test fun titoloEFoto() {
        assertEquals("Spesa", NoteTools.title(Note("1", "\n  Spesa  \nlatte", 0)))
        assertEquals("Foto", NoteTools.title(Note("1", "", 0, photo = "x.jpg")))
        assertEquals("latte", NoteTools.title(Note("1", "☐ latte", 0)))
        assertEquals("Nota vocale", NoteTools.title(Note("1", "", 0, audio = "a.aac")))
        assertEquals(listOf("b.jpg", "a.jpg"), NoteTools.photosOf(Note("1", "", 0, photo = "a.jpg", photos = listOf("b.jpg", "a.jpg"))))
    }

    @Test fun cestino() {
        val day = 86_400_000L
        val n = Note("1", "x", 0, deletedAt = 1_000L)
        assertTrue(NoteTools.isTrashed(n)); assertFalse(NoteTools.expired(n, 1_000L + 6 * day)); assertTrue(NoteTools.expired(n, 1_000L + 8 * day))
        assertEquals(5, NoteTools.daysLeft(n, 1_000L + 2 * day + 10))
    }

    @Test fun vecchieNoteSenzaCampiNuovi() {
        val l = com.google.gson.Gson().fromJson<List<Note>>("[{\"id\":\"1\",\"text\":\"ciao\",\"time\":5}]",
            object : com.google.gson.reflect.TypeToken<List<Note>>() {}.type)
        assertFalse(l[0].pinned); assertFalse(l[0].archived); assertFalse(l[0].locked)
        assertEquals(null, l[0].label); assertEquals(null, l[0].deletedAt); assertTrue(NoteTools.photosOf(l[0]).isEmpty())
        assertEquals("0:42", NoteTools.clock(42_000))
    }
}
