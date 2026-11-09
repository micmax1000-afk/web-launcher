package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParcelsTest {
    @Test fun riconosceCodici() {
        assertTrue(Parcels.looksLikeTracking("1Z999AA10123456784"))
        assertTrue(Parcels.looksLikeTracking("RR123456789IT"))
        assertTrue(Parcels.looksLikeTracking("TBA123456789012"))
    }

    @Test fun ignoraTelefoniEParole() {
        assertFalse(Parcels.looksLikeTracking("3331234567"))
        assertFalse(Parcels.looksLikeTracking("+393331234567"))
        assertFalse(Parcels.looksLikeTracking("ciao come stai"))
    }

    @Test fun corrieri() {
        assertEquals("Amazon", Parcels.carrier("TBA123456789012").first)
        assertEquals("UPS", Parcels.carrier("1z999aa10123456784").first)
        assertEquals("Poste Italiane", Parcels.carrier("RR123456789IT").first)
        assertEquals("DHL", Parcels.carrier("1234567890").first)
        assertEquals("Posta internazionale", Parcels.carrier("LX123456789CN").first)
    }
}
