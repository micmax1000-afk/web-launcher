package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTest {
    private val soccer = """{"events":[
      {"id":"1","date":"2026-11-09T19:45Z","name":"Milan at Inter","competitions":[{"id":"1","date":"2026-11-09T19:45Z",
        "status":{"type":{"state":"post","completed":true,"shortDetail":"FT"}},
        "competitors":[{"homeAway":"home","winner":true,"score":"2","team":{"displayName":"Internazionale","shortDisplayName":"Inter"}},
                       {"homeAway":"away","winner":false,"score":"1","team":{"displayName":"AC Milan","shortDisplayName":"Milan"}}]}]},
      {"id":"2","date":"2026-11-12T17:30Z","name":"Roma at Juventus","competitions":[{"id":"2","date":"2026-11-12T17:30Z",
        "status":{"type":{"state":"pre","shortDetail":"12/11 - 18:30"}},
        "competitors":[{"homeAway":"home","score":"0","team":{"displayName":"Juventus"}},{"homeAway":"away","score":"0","team":{"displayName":"AS Roma","shortDisplayName":"Roma"}}]}]},
      {"id":"3","date":"2026-11-10T19:45Z","competitions":[{"id":"3","status":{"type":{"state":"in","shortDetail":"63'"}},
        "competitors":[{"homeAway":"away","score":{"displayValue":"0"},"team":{"displayName":"Napoli"}},{"homeAway":"home","score":{"displayValue":"1"},"team":{"displayName":"Lazio"}}]}]}
    ]}"""

    @Test fun calcio() {
        val m = Sports.parseScoreboard(soccer, "ita.1")
        assertEquals(3, m.size)
        assertEquals("Inter 2 – 1 Milan", Sports.line(m[0])); assertTrue(m[0].done && m[0].homeWin)
        assertEquals("Lazio 1 – 0 Napoli", Sports.line(m[1])); assertTrue(m[1].live); assertEquals("63'", m[1].detail)
        assertEquals("Juventus – Roma", Sports.line(m[2]))
        assertEquals(emptyList<Sports.Match>(), Sports.parseScoreboard("non json", "x"))
        assertEquals(emptyList<Sports.Match>(), Sports.parseScoreboard("{}", "x"))
    }

    @Test fun tennisEFormula1() {
        val tennis = """{"events":[{"id":"t","name":"Nitto ATP Finals","groupings":[{"competitions":[{"id":"a","date":"2026-11-10T13:00Z",
          "status":{"type":{"state":"post","shortDetail":"Final"}},
          "competitors":[{"winner":true,"athlete":{"displayName":"Jannik Sinner","shortName":"J. Sinner"},"linescores":[{"value":6.0},{"value":6.0}]},
                         {"winner":false,"athlete":{"displayName":"Carlos Alcaraz","shortName":"C. Alcaraz"},"linescores":[{"value":4.0},{"value":3.0}]}]}]}]}]}"""
        val t = Sports.parseScoreboard(tennis, "atp")
        assertEquals(1, t.size); assertEquals("J. Sinner 6 6 – 4 3 C. Alcaraz", Sports.line(t[0])); assertTrue(t[0].homeWin)
        val f1 = """{"events":[{"id":"f","name":"Gran Premio del Brasile","date":"2026-11-08T17:00Z","competitions":[{"id":"r",
          "status":{"type":{"state":"post","shortDetail":"Final"}},
          "competitors":[{"order":1,"winner":true,"athlete":{"displayName":"Lando Norris","shortName":"L. Norris"}},{"order":2,"athlete":{"displayName":"Max Verstappen"}},{"order":3,"athlete":{"displayName":"Charles Leclerc"}}]}]}]}"""
        val r = Sports.parseScoreboard(f1, "f1")
        assertEquals(1, r.size); assertTrue(r[0].single); assertEquals("Gran Premio del Brasile", Sports.line(r[0])); assertEquals("Vince L. Norris", r[0].detail)
    }

    @Test fun classifica() {
        val json = """{"children":[{"name":"Serie A","standings":{"entries":[
          {"team":{"displayName":"AC Milan","shortDisplayName":"Milan"},"stats":[{"name":"rank","value":2.0},{"name":"points","displayValue":"25"},{"name":"gamesPlayed","displayValue":"11"},{"name":"wins","displayValue":"8"},{"name":"losses","displayValue":"2"}]},
          {"team":{"displayName":"Napoli"},"stats":[{"name":"rank","value":1.0},{"name":"points","displayValue":"28"},{"name":"gamesPlayed","displayValue":"11"}]}]}}]}"""
        val t = Sports.parseStandings(json)
        assertEquals(1, t.size); assertEquals("Napoli", t[0].rows[0].team); assertEquals("28", t[0].rows[0].points)
        assertEquals("Milan", t[0].rows[1].team); assertEquals("8-2", t[0].rows[1].extra); assertEquals("11", t[0].rows[1].played)
        assertTrue(Sports.scoreboardUrl(Sports.league("ita.1")!!, java.util.Date(0), java.util.Date(0)).startsWith("https://site.api.espn.com/apis/site/v2/sports/soccer/ita.1/scoreboard?dates="))
    }

    @Test fun gialloZafferano() {
        assertEquals("https://www.giallozafferano.it/ricerca-ricette/torta%20di%20mele/", GialloZafferano.searchUrl(" torta di mele "))
    }
}
