package com.weblauncher.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {
    @Test fun bloccaPubblicitaESottodomini() {
        assertTrue(AdBlocker.isBlocked("doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("stats.g.doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("WWW.TABOOLA.COM"))
    }

    @Test fun nonBloccaSitiNormali() {
        assertFalse(AdBlocker.isBlocked("www.google.com"))
        assertFalse(AdBlocker.isBlocked("repubblica.it"))
        assertFalse(AdBlocker.isBlocked(null))
        assertFalse(AdBlocker.isBlocked("net"))
    }
}
