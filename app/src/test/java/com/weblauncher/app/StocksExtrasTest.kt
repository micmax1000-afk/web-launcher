package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StocksExtrasTest {
    private val milano = Stocks.MARKETS.first { it.symbol == "FTSEMIB.MI" }
    @Test fun marketHours() {
        assertTrue(Stocks.isOpen(milano, Instant.parse("2026-11-12T10:00:00Z"))!!)    // Thu 11:00 Rome
        assertFalse(Stocks.isOpen(milano, Instant.parse("2026-11-12T17:00:00Z"))!!)   // 18:00 Rome
        assertFalse(Stocks.isOpen(milano, Instant.parse("2026-11-14T10:00:00Z"))!!)   // Saturday
        assertNull(Stocks.isOpen(Stocks.MARKETS.first { it.symbol == "GC=F" }))
    }
    @Test fun pctAlerts() {
        val up = Stocks.Alert("1", "X", "X", true, 0.0, pct = 3.0)
        assertTrue(Stocks.triggeredPct(up, 3.2, "d1"))
        assertFalse(Stocks.triggeredPct(up, 2.0, "d1"))
        assertFalse(Stocks.triggeredPct(up.copy(firedDay = "d1"), 5.0, "d1"))
        assertTrue(Stocks.triggeredPct(Stocks.Alert("2", "X", "X", false, 0.0, pct = -3.0), -4.0, "d1"))
        assertFalse(Stocks.triggered(up, 1000.0))   // a % alert is not a price alert
    }
    @Test fun taxes() {
        assertEquals(26.0, Stocks.taxOnGain(100.0, false), 1e-9)
        assertEquals(12.5, Stocks.taxOnGain(100.0, true), 1e-9)
        assertEquals(0.0, Stocks.taxOnGain(-50.0, false), 1e-9)
    }
    @Test fun savingsPlan() {
        val zero = Stocks.savingsPlan(100.0, 10, 0.0)
        assertEquals(12000.0, zero.invested, 1e-6); assertEquals(12000.0, zero.value, 1e-6)
        val p = Stocks.savingsPlan(100.0, 10, 4.0)
        assertTrue(p.value in 14500.0..14900.0)
        assertTrue(p.net < p.value)
    }
    @Test fun dividends() {
        val json = """{"chart":{"result":[{"meta":{},"events":{"dividends":{"1":{"amount":0.5,"date":1700000000},"2":{"amount":0.5,"date":1715000000}}}}]}}"""
        val l = Stocks.parseDividends(json)
        assertEquals(2, l.size); assertEquals(1700000000000L, l[0].time)
        val (year, next) = Stocks.dividendSummary(l, 1716000000000L)
        assertEquals(1.0, year, 1e-9)
        assertEquals(1715000000000L + 15000000000L, next)
    }
    @Test fun csv() {
        val out = Stocks.csv(listOf(Stocks.Holding("ENI.MI", "Eni", 10.0, 12.5)), mapOf("ENI.MI" to Stocks.Quote(14.0, 1.0, "EUR", 0)))
        val line = out.lines()[1]
        assertTrue(line, line.startsWith("ENI.MI;\"Eni\";10;12,5;14;EUR;140;15;3,9"))
    }
    @Test fun normalize() {
        val (a, b) = Stocks.normalize(listOf(10.0, 11.0, 12.0), listOf(200.0, 180.0))
        assertEquals(listOf(100.0, 120.0), a); assertEquals(listOf(100.0, 90.0), b)
    }
    @Test fun newsLinks() {
        val xml = "<rss><item><title>Eni sale - Il Sole 24 Ore</title><link>https://n.it/1</link><source url='x'>Il Sole 24 Ore</source></item></rss>"
        val n = Stocks.parseNews(xml).single()
        assertEquals("Eni sale", n.title); assertEquals("https://n.it/1", n.link); assertEquals("Il Sole 24 Ore", n.source)
    }
}
