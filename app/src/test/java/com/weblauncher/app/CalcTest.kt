package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcTest {
    @Test fun operazioni() {
        assertEquals("= 20", Calc.evaluate("(2+3)*4"))
        assertEquals("= 2,5", Calc.evaluate("5 / 2"))
        assertEquals("= 1.024", Calc.evaluate("2^10"))
        assertEquals("= -3", Calc.evaluate("-5+2"))
    }

    @Test fun percentuali() {
        assertEquals("15% di 230 = 34,5", Calc.evaluate("15% di 230"))
        assertEquals("= 264,5", Calc.evaluate("230 + 15%"))
        assertEquals("= 80", Calc.evaluate("100 - 20%"))
    }

    @Test fun unita() {
        assertEquals("10 km = 6,21371192 miglia", Calc.evaluate("10 km in miglia"))
        assertEquals("100°C = 212°F", Calc.evaluate("100 c in f"))
        assertNull("massa e lunghezza non si convertono", Calc.evaluate("10 kg in metri"))
    }

    @Test fun testoNormaleNonECalcolo() {
        assertNull(Calc.evaluate("meteo torino"))
        assertNull(Calc.evaluate("2026"))
        assertNull(Calc.evaluate("(2+3"))
    }

    @Test fun numeriAllItaliana() {
        assertEquals(1234.5, Calc.num("1.234,5")!!, 1e-9)
        assertEquals(12.5, Calc.num("12,5")!!, 1e-9)
    }

    @Test fun valute() {
        assertEquals(Triple(50.0, "USD", "EUR"), Calc.currencyQuery("50 dollari in euro"))
        assertNull(Calc.currencyQuery("50 euro in euro"))
        assertNull(Calc.currencyQuery("50 banane in euro"))
    }
}
