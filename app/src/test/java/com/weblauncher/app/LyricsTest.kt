package com.weblauncher.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTest {
    private val lrc = "[ti:Someone]\n[00:12.50] Prima riga\n[00:20.00]Seconda\n[01:05.5]Terza\n[01:30.123][02:00.00]Ritornello\n[03:00.00]"

    @Test fun parsesSyncedLines() {
        val l = Lyrics.parseLrc(lrc)
        assertEquals(6, l.size)
        assertEquals(12_500L, l[0].timeMs); assertEquals("Prima riga", l[0].text)
        assertEquals(65_500L, l[2].timeMs)
        assertEquals(90_123L, l[3].timeMs); assertEquals("Ritornello", l[3].text)
        assertEquals(120_000L, l[4].timeMs); assertEquals("Ritornello", l[4].text)   // same text, second timestamp
        assertEquals("", l[5].text)
    }
    @Test fun findsTheLineBeingSung() {
        val l = Lyrics.parseLrc(lrc)
        assertEquals(-1, Lyrics.activeIndex(l, 5_000))
        assertEquals(0, Lyrics.activeIndex(l, 12_500))
        assertEquals(1, Lyrics.activeIndex(l, 30_000))
        assertEquals(5, Lyrics.activeIndex(l, 999_999))
        assertEquals(-1, Lyrics.activeIndex(emptyList(), 1000))
    }
    @Test fun cleansTitlesFromYouTube() {
        assertEquals("Adele" to "Someone Like You", Lyrics.cleanQuery("Adele - Someone Like You (Official Video)", ""))
        assertEquals("Adele" to "Someone Like You", Lyrics.cleanQuery("Someone Like You", "Adele"))
        assertEquals("Vasco Rossi" to "Albachiara", Lyrics.cleanQuery("Albachiara [Official Audio]", "Vasco Rossi - Topic"))
        assertEquals("Mengoni" to "Guerriero", Lyrics.cleanQuery("Guerriero (Testo/Lyrics)", "Mengoni"))
        assertEquals("Elisa" to "Luce", Lyrics.cleanQuery("Luce ft. Qualcuno | YouTube Music", "Elisa"))
    }
    @Test fun picksTheRightCandidate() {
        val arr = JSONArray("""[
          {"trackName":"X live","duration":300,"plainLyrics":"a","syncedLyrics":null},
          {"trackName":"X","duration":201,"plainLyrics":"b","syncedLyrics":"[00:01.00]x"},
          {"trackName":"X","duration":203,"plainLyrics":"c","syncedLyrics":null}]""")
        assertEquals("b", Lyrics.pick(arr, 200)!!.getString("plainLyrics"))
        assertNull(Lyrics.pick(arr, 100))                       // nothing close in length
        assertNotNull(Lyrics.pick(arr, 0))                      // length unknown: take the best
    }
}
