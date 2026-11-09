package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StocksTest {
    private val chartJson = """
    {"chart":{"result":[{"meta":{"currency":"EUR","symbol":"ENI.MI","exchangeName":"MIL","fullExchangeName":"Milan",
      "regularMarketPrice":14.5,"chartPreviousClose":14.0,"longName":"Eni S.p.A."},
      "timestamp":[1731000000,1731000300,1731000600],
      "indicators":{"quote":[{"close":[14.1,null,14.5]}]}}],"error":null}}
    """.trimIndent()

    @Test fun leggeIlGrafico() {
        val c = Stocks.parseChart(chartJson)!!
        assertEquals("ENI.MI", c.symbol)
        assertEquals("Eni S.p.A.", c.name)
        assertEquals(14.5, c.price, 1e-9)
        assertEquals(listOf(14.1, 14.5), c.closes)            // i valori mancanti vengono saltati
        assertEquals(3.5714, c.changePct, 1e-3)
        assertEquals("Milan", c.exchange)
        assertNull(Stocks.parseChart("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
    }

    @Test fun leggeLaRicerca() {
        val json = """{"quotes":[
          {"symbol":"ENI.MI","shortname":"ENI","longname":"Eni S.p.A.","quoteType":"EQUITY","exchDisp":"Milan"},
          {"symbol":"XYZ","shortname":"Opzione","quoteType":"OPTION"},
          {"symbol":"VWCE.DE","shortname":"Vanguard FTSE All-World","quoteType":"ETF","exchDisp":"XETRA"}]}"""
        val r = Stocks.parseSearch(json)
        assertEquals(listOf("ENI.MI", "VWCE.DE"), r.map { it.symbol })
        assertEquals("Eni S.p.A.", r[0].name)
        assertTrue(Stocks.parseSearch("non json").isEmpty())
    }

    @Test fun titoliDelleNotizie() {
        val xml = "<rss><channel><title>Google News</title><item><title>Eni sale del 3% dopo i conti</title></item>" +
            "<item><title><![CDATA[Petrolio &amp; gas in rialzo]]></title></item></channel></rss>"
        assertEquals(listOf("Eni sale del 3% dopo i conti", "Petrolio & gas in rialzo"), Stocks.parseNewsTitles(xml))
    }

    @Test fun avvisi() {
        val sopra = Stocks.Alert("1", "ENI.MI", "Eni", above = true, price = 15.0)
        assertFalse(Stocks.triggered(sopra, 14.9))
        assertTrue(Stocks.triggered(sopra, 15.0))
        val sotto = Stocks.Alert("2", "ENI.MI", "Eni", above = false, price = 14.0)
        assertTrue(Stocks.triggered(sotto, 13.8))
        assertFalse(Stocks.triggered(sotto.copy(done = true), 13.8))
    }

    @Test fun formati() {
        assertEquals("14,50 €", Stocks.fmtPrice(14.5, "EUR"))
        assertEquals("1,0842", Stocks.fmtPrice(1.0842))
        assertEquals("+3,57%", Stocks.fmtPct(3.5714))
        assertEquals("-1,20%", Stocks.fmtPct(-1.2))
    }

    @Test fun portafoglio() {
        val h = listOf(Stocks.Holding("ENI.MI", "Eni", 100.0, 13.0), Stocks.Holding("ISP.MI", "Intesa", 200.0, 4.0),
            Stocks.Holding("AAPL", "Apple", 10.0, 150.0), Stocks.Holding("XXX", "Senza prezzo", 5.0, 1.0))
        val q = mapOf("ENI.MI" to Stocks.Quote(14.5, 1.0, "EUR", 0), "ISP.MI" to Stocks.Quote(3.5, -1.0, "EUR", 0),
            "AAPL" to Stocks.Quote(200.0, 0.5, "USD", 0))
        val t = Stocks.portfolio(h, q)
        assertEquals(listOf("EUR", "USD"), t.map { it.currency })          // valute separate, la più grande prima
        val eur = t[0]
        assertEquals(2150.0, eur.value, 1e-9)                               // 100*14,5 + 200*3,5
        assertEquals(2100.0, eur.invested, 1e-9)
        assertEquals(50.0, eur.gain, 1e-9)
        assertEquals(500.0, t[1].gain, 1e-9)
        assertTrue(Stocks.portfolio(emptyList(), q).isEmpty())
    }

    @Test fun rigaDelBuongiorno() {
        assertEquals("📈 FTSE MIB +0,40% · Eni -1,25%", Stocks.briefLine(listOf("FTSE MIB" to 0.4, "Eni" to -1.25)))
        assertNull(Stocks.briefLine(emptyList()))
    }
}
