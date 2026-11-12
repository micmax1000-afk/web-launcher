package com.weblauncher.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicWebTest {
    @Test fun staysInsideTheMusicPage() {
        assertTrue(isMusicPageHost("https://music.youtube.com/watch?v=abc"))
        assertTrue(isMusicPageHost("https://accounts.google.com/ServiceLogin"))
        assertTrue(isMusicPageHost("https://consent.youtube.com/m?continue=x"))
    }
    @Test fun otherSitesGoToTheBrowser() {
        assertFalse(isMusicPageHost("https://m.youtube.com/watch?v=abc"))
        assertFalse(isMusicPageHost("https://www.google.com/search?q=x"))
        assertFalse(isMusicPageHost("https://example.com/"))
        assertFalse(isMusicPageHost(null))
    }
}
