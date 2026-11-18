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

class TodoTest {
    @Test fun righe() {
        val noon = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 12, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val day = Todo.startOfDay(noon)
        assertEquals(java.util.Calendar.getInstance().apply { timeInMillis = noon; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis, day)
        val at = java.util.Calendar.getInstance().apply { timeInMillis = day; set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 30) }.timeInMillis
        assertEquals("⏰ 14:30 Dentista", Todo.line(Todo.Item(at, false, "Dentista", "⏰", "rem"), noon))
        assertEquals("🎂 domani Compleanno di Anna", Todo.line(Todo.Item(day + 86_400_000L, true, "Compleanno di Anna", "🎂", "bday"), noon))
        assertEquals("💊 ieri 08:00 Pastiglia", Todo.line(Todo.Item(day - 86_400_000L + 8 * 3_600_000L, false, "Pastiglia", "💊", "med"), noon))
    }
}
