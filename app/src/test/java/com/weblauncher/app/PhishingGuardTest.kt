package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PhishingGuardTest {
    @Test fun dominioRegistrato() {
        assertEquals("poste.it", PhishingGuard.registered("www.poste.it"))
        assertEquals("amazon.co.uk", PhishingGuard.registered("www.amazon.co.uk"))
        assertEquals("agenziaentrate.gov.it", PhishingGuard.registered("www.agenziaentrate.gov.it"))
        assertEquals("example.com", PhishingGuard.registered("example.com."))
    }
}
