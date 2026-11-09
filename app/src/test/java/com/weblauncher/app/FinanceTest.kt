package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FinanceTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 10, 0) }

    @Test fun mesiDaRegistrare() {
        // ultima registrazione settembre, affitto il 5, oggi 9 novembre → ottobre e novembre
        assertEquals(listOf(202610, 202611), Finance.dueMonths(202609, 5, day(2026, 11, 9)))
        // stipendio il 27: novembre non ancora
        assertEquals(listOf(202610), Finance.dueMonths(202609, 27, day(2026, 11, 9)))
        // giorno 31 a febbraio = ultimo del mese
        assertEquals(listOf(202702), Finance.dueMonths(202701, 31, day(2027, 2, 28)))
        // passaggio d'anno
        assertEquals(listOf(202612, 202701), Finance.dueMonths(202611, 1, day(2027, 1, 2)))
        assertTrue(Finance.dueMonths(202611, 5, day(2026, 11, 9)).isEmpty())
    }

    @Test fun detrazione730() {
        assertEquals(0.0, Finance.healthDeduction(100.0), 1e-9)
        assertEquals((1000 - 129.11) * 0.19, Finance.healthDeduction(1000.0), 1e-9)
    }

    @Test fun speseDetraibili() {
        val farmacia = Expense("1", 12.0, "Farmacia comunale", "📦 Altro", 0)
        val salute = Expense("2", 80.0, "Visita", "💊 Salute", 0)
        val pizza = Expense("3", 20.0, "Pizza", "🍕 Ristoranti e bar", 0)
        assertTrue(Finance.isDeductible(farmacia, emptySet()))
        assertTrue(Finance.isDeductible(salute, emptySet()))
        assertFalse(Finance.isDeductible(pizza, emptySet()))
        assertFalse(Finance.isDeductible(farmacia, setOf("1")))   // esclusa a mano
        assertTrue(Finance.isDeductible(pizza, setOf("3")))       // inclusa a mano
    }

    @Test fun dateDiNascita() {
        assertEquals(Triple(3, 14, 1985), Birthdays.parseDate("1985-03-14"))
        assertEquals(Triple(3, 14, null), Birthdays.parseDate("--03-14"))
        assertEquals(Triple(3, 14, 1985), Birthdays.parseDate("14/03/1985"))
        assertNull(Birthdays.parseDate("boh"))
    }

    @Test fun prossimoCompleanno() {
        val now = day(2026, 11, 9)
        val oggi = Birthdays.Person("Anna", 11, 9, 1990, 1)
        val domani = Birthdays.Person("Luca", 11, 10, null, 2)
        val passato = Birthdays.Person("Marco", 11, 1, 2000, 3)
        assertEquals(0, oggi.daysFrom(now)); assertEquals(36, oggi.turning(now))
        assertEquals(1, domani.daysFrom(now)); assertNull(domani.turning(now))
        assertEquals(357, passato.daysFrom(now)); assertEquals(27, passato.turning(now))
    }

    @Test fun calendarioAVoce() {
        assertEquals("dentista martedì alle 10" to false, CalendarVoice.clean("metti dentista martedì alle 10"))
        assertEquals("cena da luca sabato alle 20" to true, CalendarVoice.clean("aggiungi al calendario cena da luca sabato alle 20"))
        assertEquals("commercialista il 12/12 alle 15" to true, CalendarVoice.clean("appuntamento dal commercialista il 12/12 alle 15"))
        assertNull(CalendarVoice.clean("metti la sveglia alle 7"))
        assertNull(CalendarVoice.clean("che tempo fa domani"))
    }
}
