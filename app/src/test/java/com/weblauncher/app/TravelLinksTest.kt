package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelLinksTest {
    private val q = TravelLinks.Query("Torino", "Londra", "2026-12-10", "2026-12-15", 2)

    @Test fun googleVoli() {
        val u = TravelLinks.googleFlights(q)
        assertTrue(u.startsWith("https://www.google.com/travel/flights?"))
        assertTrue(u.contains("Voli+da+Torino+a+Londra+il+2026-12-10+ritorno+2026-12-15+per+2+adulti"))
        assertTrue(TravelLinks.googleFlights(q.copy(back = null, people = 1)).contains("solo+andata+per+1+adulto"))
    }

    @Test fun skyscannerConCodici() {
        assertEquals("https://www.skyscanner.it/trasporti/voli/trn/lon/261210/261215/?adultsv2=2&rtn=1",
            TravelLinks.skyscanner(q.copy(from = "TRN", to = "lon")))
        assertEquals("https://www.skyscanner.it/", TravelLinks.skyscanner(q))
    }

    @Test fun hotel() {
        assertEquals("https://www.booking.com/searchresults.it.html?ss=Londra&checkin=2026-12-10&checkout=2026-12-15&group_adults=2&no_rooms=1&group_children=0",
            TravelLinks.booking(q))
        assertEquals("https://www.airbnb.it/s/New%20York/homes?adults=2&checkin=2026-12-10&checkout=2026-12-15",
            TravelLinks.airbnb(q.copy(to = "New York")))
    }

    @Test fun treni() {
        assertEquals("https://www.thetrainline.com/it/orari-treni/torino-a-reggio-emilia",
            TravelLinks.trainline(q.copy(to = "Reggio Emìlia")))
    }
}
