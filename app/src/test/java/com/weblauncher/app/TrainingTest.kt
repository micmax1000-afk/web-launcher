package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TrainingTest {
    @Test fun fasciaBattito() {
        assertEquals(72, Hr.parse(byteArrayOf(0x00, 72)))                          // 8 bit
        assertEquals(300 - 44, Hr.parse(byteArrayOf(0x01, 0x00.toByte(), 0x01)).let { 256 })   // 16 bit: 0x0100 = 256
        assertEquals(150, Hr.parse(byteArrayOf(0x10, 0x96.toByte(), 0x20, 0x03)))  // 8 bit con RR dopo
        assertEquals(0, Hr.parse(byteArrayOf(0x00)))
    }

    @Test fun zone() {
        assertEquals(190, HrZones.maxHr(26))                                       // 208 - 18,2
        assertEquals(0, HrZones.zone(80, 190)); assertEquals(2, HrZones.zone(125, 190)); assertEquals(5, HrZones.zone(180, 190))
        assertEquals(0, HrZones.zone(0, 190))
        assertEquals(114 to 133, HrZones.range(2, 190))
    }

    @Test fun calorieDalBattito() {
        val m = Hr.kcalPerMin("m", 140, 75.0, 40); val f = Hr.kcalPerMin("f", 140, 60.0, 40)
        assertTrue(m in 9.0..14.0); assertTrue(f in 6.0..12.0)
        assertEquals(0.0, Hr.kcalPerMin("m", 30, 70.0, 40), 0.001)
        val s = (0..59).map { it * 1000L to 140 }                                  // un minuto
        assertEquals(Hr.kcalPerMin("m", 140, 75.0, 40), Hr.kcalFromSamples(s, "m", 75.0, 40), 0.3)
        assertEquals(140, Hr.avg(s)); assertEquals(140, Hr.max(s))
        assertEquals("Ottimo", Hr.recoveryLabel(30)); assertTrue(Hr.recoveryLabel(5).startsWith("Basso"))
    }

    @Test fun intervalli() {
        assertEquals(Intervals.Pos(true, 60, 1, false), Intervals.at(0, 60, 120, 8))
        assertEquals(Intervals.Pos(false, 120, 1, false), Intervals.at(60, 60, 120, 8))
        assertEquals(Intervals.Pos(true, 55, 2, false), Intervals.at(185, 60, 120, 8))
        assertTrue(Intervals.at(180 * 8, 60, 120, 8).done)
        assertEquals("Cammina", Intervals.words("corsa", false)); assertEquals("Spingi", Intervals.words("bici", true))
    }

    @Test fun parlato() {
        assertEquals("un minuto", Say.duration(60)); assertEquals("5 minuti e 30 secondi", Say.duration(330)); assertEquals("45 secondi", Say.duration(45))
        assertEquals("5 e 30 al chilometro", Say.pace(60.0 / 5.5)); assertEquals("6 minuti al chilometro", Say.pace(10.0))
    }

    @Test fun gpx() {
        val w = Workout("1", "corsa", 1_700_000_000_000, 1_700_000_600_000, 1500.0, 590_000, 100.0, 0.0, 12.0)
        val x = Gpx.build(w, listOf(Pt(45.0, 9.0, 1_700_000_000_000, 120.0, 140), Pt(45.001, 9.0, 1_700_000_060_000)), "Corsa & prova")
        assertTrue(x.startsWith("<?xml")); assertTrue(x.contains("<trkpt lat=\"45.000000\" lon=\"9.000000\">")); assertTrue(x.contains("<gpxtpx:hr>140</gpxtpx:hr>"))
        assertTrue(x.contains("Corsa &amp; prova")); assertTrue(x.contains("<time>2023-11-14T22:13:20Z</time>")); assertEquals(1, Regex("<gpxtpx:hr>").findAll(x).count())
    }

    @Test fun stessoPercorso() {
        fun w(id: String, d: Double, kind: String = "corsa") = Workout(id, kind, 0, 0, d, 1_800_000, 0.0, 0.0, 0.0)
        val here = 45.0 to 9.0
        val start = mapOf("a" to here, "b" to (45.001 to 9.0), "c" to (45.1 to 9.0), "d" to here, "e" to here)
        val all = listOf(w("a", 5000.0), w("b", 5200.0), w("c", 5000.0), w("d", 8000.0), w("e", 5000.0, "bici"))
        val sim = Fitness.similar(all, all[0], { start[it.id] })
        assertEquals(listOf("b"), sim.map { it.id })          // c troppo lontano, d troppo lungo, e un'altra attività
        assertEquals(360, Fitness.paceSec(w("a", 5000.0)))
    }

    @Test fun sintomiEReferto() {
        val l = listOf(Symptom("1", "Mal di testa", 6, "dopo pranzo", 1_700_000_000_000), Symptom("2", "Mal di testa", 4, "", 1_700_100_000_000))
        val lines = Symptoms.reportLines(l)
        assertTrue(lines[0].startsWith("Mal di testa: 2 volte")); assertTrue(lines[0].contains("5,0/10")); assertEquals(3, lines.size)
        val d = Report.Data("A", 0, 1, emptyList(), emptyList(), emptyList(), 0, 0, 0, 0, emptyList(), "", "", lines)
        assertTrue(Report.sections(d).any { it.first == "Sintomi annotati" })
    }

    @Test fun promemoriaSettimanale() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 10, 12, 0, 0) }.timeInMillis    // martedì
        val next = Calendar.getInstance().apply { timeInMillis = nextWeekday(Calendar.MONDAY, "08:00", now) }
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK)); assertEquals(16, next.get(Calendar.DAY_OF_MONTH)); assertEquals(8, next.get(Calendar.HOUR_OF_DAY))
        val same = Calendar.getInstance().apply { timeInMillis = nextWeekday(Calendar.TUESDAY, "18:00", now) }
        assertEquals(10, same.get(Calendar.DAY_OF_MONTH))
        assertFalse(nextWeekday(Calendar.TUESDAY, "08:00", now) <= now)
    }
}

class MusicTest {
    @Test fun appDalLink() {
        assertEquals("spotify", Music.appFor("https://open.spotify.com/playlist/37i9dQ")); assertEquals("spotify", Music.appFor("spotify:playlist:abc"))
        assertEquals("ytmusic", Music.appFor("https://music.youtube.com/playlist?list=PL1")); assertEquals("ytmusic", Music.appFor("https://youtu.be/abc"))
        assertEquals(null, Music.appFor("https://example.com"))
    }

    @Test fun comandiVocali() {
        assertEquals(MusicCmd.Control("pause"), Music.parseVoice("Pausa")); assertEquals(MusicCmd.Control("pause"), Music.parseVoice("ferma la musica"))
        assertEquals(MusicCmd.Control("play"), Music.parseVoice("riprendi la musica")); assertEquals(MusicCmd.Control("next"), Music.parseVoice("prossima canzone"))
        assertEquals(MusicCmd.Control("prev"), Music.parseVoice("canzone precedente"))
        assertEquals(MusicCmd.Find("musica per correre", null), Music.parseVoice("Metti musica per correre"))
        assertEquals(MusicCmd.Find("vasco rossi", "spotify"), Music.parseVoice("ascolta Vasco Rossi su Spotify"))
        assertEquals(MusicCmd.Find("la mia playlist", "ytmusic"), Music.parseVoice("metti la mia playlist su YouTube Music"))
        assertEquals(MusicCmd.Find("coldplay", null), Music.parseVoice("riproduci coldplay"))
        // non devono essere scambiati per musica
        assertEquals(null, Music.parseVoice("metti dentista martedì alle 10")); assertEquals(null, Music.parseVoice("metti la sveglia alle 7"))
        assertEquals(null, Music.parseVoice("apri il calendario")); assertEquals(null, Music.parseVoice("metti"))
    }

    @Test fun lettoreDentroLApp() {
        val raw = "\"{\\\"t\\\":\\\"Albachiara\\\",\\\"a\\\":\\\"Vasco Rossi\\\",\\\"art\\\":\\\"https://x/y.jpg\\\",\\\"p\\\":true,\\\"has\\\":true}\""
        val w = Music.parseWebState(raw, 5)!!
        assertEquals("Albachiara", w.title); assertEquals("Vasco Rossi", w.artist); assertTrue(w.playing); assertEquals(5L, w.at)
        assertEquals(null, Music.parseWebState("\"{}\"")); assertEquals(null, Music.parseWebState("null")); assertEquals(null, Music.parseWebState(null))
        assertEquals("https://music.youtube.com/search?q=musica%20per%20correre", Music.searchUri("ytmusic", "musica per correre"))
        assertTrue(Music.controlJs("next'); alert(1); ('").endsWith("('nextalert')"))
    }
}
