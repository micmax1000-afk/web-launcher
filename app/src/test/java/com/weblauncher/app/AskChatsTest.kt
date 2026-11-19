package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskChatsTest {
    private fun chat(id: String, t: Long = 0) = AskChats.Chat(id, "t$id", t, listOf(AskChats.Msg("user", "ciao"), AskChats.Msg("assistant", "salve")))

    @Test fun salvataggioERilettura() {
        val l = listOf(chat("a", 1), chat("b", 2))
        assertEquals(l, AskChats.decode(AskChats.encode(l)))
        assertTrue(AskChats.decode(null).isEmpty()); assertTrue(AskChats.decode("rotto{").isEmpty())
    }

    @Test fun laNuovaVaInTestaESostituisce() {
        val l = AskChats.upsert(listOf(chat("a"), chat("b")), chat("b", 9))
        assertEquals(listOf("b", "a"), l.map { it.id }); assertEquals(9L, l[0].time)
    }

    @Test fun massimoTrenta() {
        var l = emptyList<AskChats.Chat>()
        for (i in 1..40) l = AskChats.upsert(l, chat("c$i", i.toLong()))
        assertEquals(30, l.size); assertEquals("c40", l[0].id)
    }

    @Test fun titolo() {
        assertEquals("Che spese ho fatto?", AskChats.titleOf("  Che spese   ho fatto? "))
        assertEquals("Conversazione", AskChats.titleOf("  "))
        assertTrue(AskChats.titleOf("a".repeat(100)).length <= 41)
    }
}
