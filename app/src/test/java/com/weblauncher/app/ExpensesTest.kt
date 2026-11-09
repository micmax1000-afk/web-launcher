package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpensesTest {
    @Test fun spesaAVoce() {
        assertEquals(12.0 to "Pizza", Expenses.parseVoice("speso 12 euro pizza"))
        assertEquals(12.5 to "Supermercato", Expenses.parseVoice("ho speso 12,50 al supermercato"))
        assertEquals(45.0 to "Benzina", Expenses.parseVoice("45 euro benzina"))
        assertNull(Expenses.parseVoice("domani alle 9 dentista"))
    }

    @Test fun categorie() {
        assertEquals("🛒 Spesa", Expenses.guess("Esselunga"))
        assertEquals("⛽ Trasporti", Expenses.guess("benzina"))
        assertEquals("🍕 Ristoranti e bar", Expenses.guess("pizza con amici"))
        assertEquals("📦 Altro", Expenses.guess("xyz"))
    }
}
