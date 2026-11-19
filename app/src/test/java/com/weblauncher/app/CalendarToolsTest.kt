package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CalendarToolsTest {
    private fun day(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test fun luneditDellaSettimana() {
        assertEquals(day(2026, 11, 16, 0), CalendarTools.weekStart(day(2026, 11, 19)))   // mercoledì → lunedì 16
        assertEquals(day(2026, 11, 16, 0), CalendarTools.weekStart(day(2026, 11, 22)))   // domenica → lunedì 16
        assertEquals(day(2026, 11, 16, 0), CalendarTools.weekStart(day(2026, 11, 16, 23)))
    }

    @Test fun grigliaDelMese() {
        val g = CalendarTools.monthGrid(day(2026, 11, 19))     // novembre 2026: inizia di domenica
        assertEquals(0, g.size % 7)
        assertEquals(day(2026, 10, 26, 0), g.first())
        assertTrue(g.contains(day(2026, 11, 30, 0)))
        assertEquals(42, g.size)
        assertTrue(CalendarTools.sameMonth(g[6], day(2026, 11, 1))); assertFalse(CalendarTools.sameMonth(g[0], day(2026, 11, 1)))
    }

    @Test fun compleanni() {
        assertTrue(CalendarTools.birthdayOn(11, 20, day(2026, 11, 20)))
        assertFalse(CalendarTools.birthdayOn(11, 20, day(2026, 11, 21)))
        assertTrue(CalendarTools.birthdayOn(2, 29, day(2027, 2, 28)))      // anno non bisestile
        assertTrue(CalendarTools.birthdayOn(2, 29, day(2028, 2, 29)))
    }

    @Test fun cambioOra() {
        val d = CalendarTools.days(day(2026, 10, 19, 0), 7)       // contiene il 25 ottobre (fine ora legale)
        assertEquals(7, d.size); assertEquals(day(2026, 10, 25, 0), d[6])
    }
}
