package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AudioExtrasTest {
    @Test fun voiceLatestEpisode() {
        assertEquals(AudioCmd.LatestEpisode("il post"), AudioVoice.parse("Metti l'ultimo episodio di Il Post"))
        assertEquals(AudioCmd.LatestEpisode("morning"), AudioVoice.parse("fammi sentire l'ultima puntata di morning".replace("ultima", "ultimo")))
        assertEquals(AudioCmd.LatestEpisode("muschio selvaggio"), AudioVoice.parse("metti il podcast muschio selvaggio"))
    }
    @Test fun voiceRadio() {
        assertEquals(AudioCmd.Radio("radio 3"), AudioVoice.parse("metti Radio 3"))
        assertEquals(AudioCmd.Radio("rtl 102.5"), AudioVoice.parse("accendi rtl 102.5"))
        assertEquals(AudioCmd.News, AudioVoice.parse("metti il notiziario"))
        assertNull(AudioVoice.parse("metti una canzone di Vasco"))
    }
    @Test fun bestMatch() {
        val names = listOf("Rai Radio 3", "Radio Deejay", "Rai Radio 1")
        assertEquals("Rai Radio 3", AudioVoice.best(names, "radio tre") { it })
        assertEquals("Radio Deejay", AudioVoice.best(names, "deejay") { it })
    }
    @Test fun opmlRoundTrip() {
        val xml = Opml.build(listOf(Podcast("https://a.it/feed?x=1&y=2", "Storie & Co", "", null), Podcast("https://b.it/rss", "B", "", null)))
        val back = Opml.parse(xml)
        assertEquals(listOf("Storie & Co" to "https://a.it/feed?x=1&y=2", "B" to "https://b.it/rss"), back)
    }
    @Test fun opmlFromOtherApps() {
        val xml = "<opml><body><outline text='Gruppo'><outline type=\"rss\" text=\"Il Post\" xmlUrl=\"https://x.it/f.xml\" /></outline></body></opml>"
        assertEquals(listOf("Il Post" to "https://x.it/f.xml"), Opml.parse(xml))
    }
    @Test fun alarmSkipsWeekend() {
        val fri = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 13, 8, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis   // Friday 08:00
        val next = Calendar.getInstance().apply { timeInMillis = RadioAlarm.next(fri, 7, 0, true) }
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        val any = Calendar.getInstance().apply { timeInMillis = RadioAlarm.next(fri, 7, 0, false) }
        assertEquals(Calendar.SATURDAY, any.get(Calendar.DAY_OF_WEEK))
        assertTrue(RadioAlarm.next(fri, 9, 0, true) - fri == 3600_000L)
    }
}
