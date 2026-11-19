package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteoAlertsTest {
    private val json = """{"daily":{"time":["2026-11-19","2026-11-20"],"weather_code":[95,3],"precipitation_sum":[12.0,25.5],
        "wind_gusts_10m_max":[30.0,72.0],"temperature_2m_min":[5.0,-3.0]}}"""

    @Test fun allerte() {
        val a = MeteoAlerts.check(json, "Milano")
        assertEquals(listOf("temporali", "pioggia", "vento", "gelo"), a.map { it.kind })
        assertTrue(a[0].title.startsWith("⛈ Oggi")); assertTrue(a[1].title.contains("Domani"))
        assertTrue(a[2].text.contains("72"))
    }

    @Test fun niente() {
        assertTrue(MeteoAlerts.check("""{"daily":{"time":["x"],"weather_code":[1],"precipitation_sum":[0],"wind_gusts_10m_max":[10],"temperature_2m_min":[8]}}""", "X").isEmpty())
        assertTrue(MeteoAlerts.check("rotto", "X").isEmpty())
    }

    @Test fun oreDiSilenzio() {
        assertTrue(MeteoAlerts.inQuiet(23 * 60, 22 * 60 + 30, 7 * 60)); assertTrue(MeteoAlerts.inQuiet(6 * 60, 22 * 60 + 30, 7 * 60))
        assertFalse(MeteoAlerts.inQuiet(12 * 60, 22 * 60 + 30, 7 * 60)); assertFalse(MeteoAlerts.inQuiet(12 * 60, 0, 0))
        assertTrue(MeteoAlerts.inQuiet(14 * 60, 13 * 60, 15 * 60))
    }
}
