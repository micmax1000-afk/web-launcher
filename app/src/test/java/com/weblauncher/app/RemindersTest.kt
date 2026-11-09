package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RemindersTest {
    /** Lunedì 9 novembre 2026, ore 10:00 */
    private fun now() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 9, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    @Test fun domaniAlle9() {
        val r = Reminders.parse("ricordami domani alle 9 di chiamare Marco", now())!!
        assertEquals("Chiamare marco", r.text)
        val c = cal(r.at)
        assertEquals(10, c.get(Calendar.DAY_OF_MONTH)); assertEquals(9, c.get(Calendar.HOUR_OF_DAY)); assertEquals(0, c.get(Calendar.MINUTE))
        assertNull(r.repeat)
    }

    @Test fun traVentiMinuti() {
        val r = Reminders.parse("tra 20 minuti spegnere il forno", now())!!
        assertEquals("Spegnere il forno", r.text)
        assertEquals(now().timeInMillis + 20 * 60_000, r.at)
    }

    @Test fun giornoDellaSettimana() {
        val r = Reminders.parse("venerdì alle 18:30 palestra", now())!!
        assertEquals("Palestra", r.text)
        val c = cal(r.at)
        assertEquals(Calendar.FRIDAY, c.get(Calendar.DAY_OF_WEEK)); assertEquals(13, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, c.get(Calendar.HOUR_OF_DAY)); assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test fun pomeriggioSenzaMattina() {
        val c = cal(Reminders.parse("oggi alle 3 dentista", now())!!.at)
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun ricorrenti() {
        assertEquals("day", Reminders.parse("ogni giorno alle 8 medicina", now())!!.repeat)
        assertEquals("week", Reminders.parse("ogni lunedì alle 20 spazzatura", now())!!.repeat)
        assertEquals("weekdays", Reminders.parse("dal lunedì al venerdì alle 7:30 sveglia", now())!!.repeat)
    }

    @Test fun senzaOrarioNonEUnPromemoria() {
        assertNull(Reminders.parse("comprare il latte", now()))
    }

    @Test fun prossimaRipetizione() {
        val sabato = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 14, 8, 0, 0) }.timeInMillis
        val n = cal(Reminders.next(sabato, "weekdays", sabato))
        assertEquals(Calendar.MONDAY, n.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, n.get(Calendar.DAY_OF_MONTH))
    }
}
