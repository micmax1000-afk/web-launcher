package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class InstrumentsTest {
    @Test fun altimetro() {
        assertEquals(0.0, Instruments.altitude(1013.25), 0.01)
        assertEquals(111.0, Instruments.altitude(1000.0), 1.0)          // ~111 m a 1000 hPa
        assertEquals(1000.0, Instruments.altitude(898.75), 3.0)
        // taratura: a quota nota 240 m la pressione al mare ricalcolata riporta 240 m
        val p0 = Instruments.seaLevelFor(985.0, 240.0)
        assertEquals(240.0, Instruments.altitude(985.0, p0), 0.01)
    }

    @Test fun distanze() {
        assertEquals(125.0, Instruments.haversineKm(45.07, 7.69, 45.46, 9.19), 3.0)   // Torino–Milano
        assertEquals(0.0, Instruments.haversineKm(45.0, 7.0, 45.0, 7.0), 1e-9)
    }

    @Test fun fonometro() {
        assertEquals(0.0, Instruments.decibels(ShortArray(100), 100), 1e-9)
        val forte = ShortArray(100) { 32767 }
        assertEquals(94.0, Instruments.decibels(forte, 100), 0.1)
        val medio = ShortArray(100) { 328 }
        assertEquals(54.0, Instruments.decibels(medio, 100), 0.2)
        assertEquals("Conversazione", Instruments.soundLabel(54.0))
    }

    @Test fun contoDiviso() {
        val s = Instruments.split(100.0, 10.0, 4)
        assertEquals(10.0, s.tip, 1e-9); assertEquals(110.0, s.total, 1e-9); assertEquals(27.5, s.each, 1e-9)
        val r = Instruments.split(100.0, 10.0, 4, roundUp = true)
        assertEquals(28.0, r.each, 1e-9); assertEquals(112.0, r.total, 1e-9)
        assertEquals(50.0, Instruments.split(50.0, 0.0, 0).each, 1e-9)
    }

    @Test fun tabata() {
        val p = Instruments.tabata(10, 20, 10, 8)
        assertEquals(16, p.size)                                   // preparazione + 8 lavori + 7 riposi
        assertEquals("prepara", p.first().kind); assertEquals("lavoro", p.last().kind)
        assertEquals(10 + 8 * 20 + 7 * 10, p.sumOf { it.seconds })
        assertEquals("4:00", Instruments.fmtClock(240))
        assertEquals(listOf("lavoro"), Instruments.tabata(0, 30, 0, 1).map { it.kind })
    }

    @Test fun albaETramonto() {
        // Torino, 21 giugno 2026: alba ~05:44, tramonto ~21:20 (ora legale)
        val tz = TimeZone.getTimeZone("Europe/Rome")
        val day = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.JUNE, 21) }
        val (rise, set) = Instruments.sunTimes(45.07, 7.69, day)!!
        fun hm(t: Long) = Calendar.getInstance(tz).apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        assertEquals((5 * 60 + 44).toDouble(), hm(rise).toDouble(), 6.0)
        assertEquals((21 * 60 + 20).toDouble(), hm(set).toDouble(), 6.0)
        // 21 dicembre: giornata corta (~8h45)
        val w = Instruments.sunTimes(45.07, 7.69, Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.DECEMBER, 21) })!!
        assertEquals(8.75, (w.second - w.first) / 3_600_000.0, 0.15)
        // notte polare
        assertNull(Instruments.sunTimes(78.0, 15.0, Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.DECEMBER, 21) }))
    }

    @Test fun luna() {
        val utc = TimeZone.getTimeZone("UTC")
        // luna piena del 24 novembre 2026 (~14:53 UTC)
        val full = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 24, 15, 0) }.timeInMillis
        val (age, lit) = Instruments.moon(full)
        assertEquals(14.77, age, 0.8); assertTrue(lit > 0.98)
        assertEquals("🌕 Luna piena", Instruments.moonName(age))
        // luna nuova del 9 novembre 2026 (~07:02 UTC)
        val new = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 9, 7, 0) }.timeInMillis
        assertTrue(Instruments.moon(new).second < 0.02)
    }

    @Test fun ariaEPollini() {
        assertEquals("🟢 Buona", Instruments.aqiLabel(15))
        assertEquals("🟠 Scadente", Instruments.aqiLabel(75))
        assertEquals("assente", Instruments.pollenLabel(0.0)); assertEquals("alto", Instruments.pollenLabel(80.0))
    }

    @Test fun terremoti() {
        val text = "#EventID|Time|Latitude|Longitude|Depth/Km|Author|Catalog|Contributor|ContributorID|MagType|Magnitude|MagAuthor|EventLocationName|EventType\n" +
            "41234567|2026-11-09T18:22:31.250000|44.9120|7.3210|11.2|SURVEY-INGV||||ML|2.7|--|4 km W Pinerolo (TO)|earthquake\n" +
            "riga rotta\n"
        val q = Instruments.parseQuakes(text)
        assertEquals(1, q.size)
        assertEquals(2.7, q[0].mag, 1e-9); assertEquals("4 km W Pinerolo (TO)", q[0].place); assertEquals(11.2, q[0].depthKm, 1e-9)
        assertEquals(1794248551000L, q[0].time)
    }

    @Test fun rifiuti() {
        val m = mapOf(Calendar.TUESDAY to listOf("🟤 Umido", "🔵 Carta"), Calendar.FRIDAY to listOf("🟡 Plastica"))
        assertEquals(m, Waste.decode(Waste.encode(m)))
        val lunedi = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 9, 19, 0) }
        assertEquals(listOf("🟤 Umido", "🔵 Carta"), Waste.tomorrow(m, lunedi))
        assertTrue(Waste.tomorrow(m, Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 10, 19, 0) }).isEmpty())
        assertTrue(Waste.decode(null).isEmpty())
    }

    @Test fun riepilogoSettimanale() {
        val t = Evening.weeklyText(120.0, 100.0, listOf("🛒 Spesa" to 80.0, "⛽ Trasporti" to 40.0), 42000, 6000,
            listOf("Bollo auto (ven 13) 180,00 €"), listOf("Anna (tra 2 g)"))
        assertTrue(t.contains("Spese della settimana: 120,00 €"))
        assertTrue(t.contains("+20% rispetto alla scorsa"))
        assertTrue(t.contains("Spesa 80,00 €"))
        assertTrue(t.contains("42.000") && t.contains("media 6.000 al giorno"))
        assertTrue(t.contains("• Bollo auto"))
        assertTrue(t.contains("Compleanni: Anna"))
        assertTrue(Evening.weeklyText(0.0, 0.0, emptyList(), 0, 0, emptyList(), emptyList()).contains("Nessuna scadenza"))
    }

    @Test fun rifiutiDaPdf_tipiEDate() {
        assertEquals(listOf("🟤 Umido"), Waste.normalizeKinds("Organico"))
        assertEquals(listOf("🟢 Vetro", "🥫 Metalli"), Waste.normalizeKinds("Vetro e lattine"))
        assertEquals(listOf("⚪ Indifferenziato"), Waste.normalizeKinds("Secco residuo"))
        assertEquals(listOf("🟡 Plastica"), Waste.normalizeKinds("Multimateriale"))
        assertEquals("2026-11-10", Waste.parseDay("10/11/2026")); assertEquals("2026-11-10", Waste.parseDay("2026-11-10"))
        assertNull(Waste.parseDay("31/02/2026")); assertNull(Waste.parseDay("boh"))
    }

    @Test fun rifiutiDaPdf_rispostaDellAi() {
        val ai = "Ecco il risultato:\n```json\n{\"giorni\": {\"2026-11-12\": [\"Carta\", \"umido\"], \"12/11/2026\": [\"Vetro\"], \"2026-11-10\": \"Plastica\", \"2026-13-40\": [\"Carta\"]},\n" +
            "\"settimana\": {\"martedì\": [\"Umido\"], \"Sabato\": [\"Indifferenziato\"]}}\n```"
        val p = Waste.parseCalendar(ai)
        assertEquals(listOf("2026-11-10", "2026-11-12"), p.dates.keys.toList())          // ordinate, data falsa scartata
        assertEquals(listOf("🟤 Umido", "🔵 Carta", "🟢 Vetro"), p.dates["2026-11-12"])  // unite e ordinate
        assertEquals(mapOf(Calendar.TUESDAY to listOf("🟤 Umido"), Calendar.SATURDAY to listOf("⚪ Indifferenziato")), p.weekly)
        val arr = Waste.parseCalendar("""[{"data":"2026-01-02","tipi":["Carta"]},{"date":"03/01/2026","raccolte":"Umido"}]""")
        assertEquals(listOf("2026-01-02", "2026-01-03"), arr.dates.keys.toList())
        assertTrue(Waste.parseCalendar("non lo so").dates.isEmpty())
        assertTrue(Waste.parseCalendar("{rotto").dates.isEmpty())
        assertEquals(p.dates, Waste.decodeDates(Waste.encodeDates(p.dates)))
    }

    @Test fun rifiutiDaPdf_dateEGiorniFissi() {
        val dates = mapOf("2026-11-10" to listOf("🔵 Carta"), "2026-11-20" to listOf("🟤 Umido"))
        val weekly = mapOf(Calendar.WEDNESDAY to listOf("🟡 Plastica"))
        fun d(m: Int, day: Int) = Calendar.getInstance().apply { clear(); set(2026, m - 1, day, 19, 0) }
        assertEquals(listOf("🔵 Carta"), Waste.kindsOn(weekly, dates, d(11, 10)))
        assertTrue(Waste.kindsOn(weekly, dates, d(11, 11)).isEmpty())              // dentro il calendario: valgono solo le date (mercoledì escluso)
        assertEquals(listOf("🟡 Plastica"), Waste.kindsOn(weekly, dates, d(12, 2)))  // oltre la fine del PDF: torna al giorno fisso
        assertEquals(listOf("🔵 Carta"), Waste.tomorrow(weekly, d(11, 9), dates))
        assertEquals(listOf("2026-11-20"), Waste.upcoming(dates, d(11, 11), 5).map { it.first })
    }
}
