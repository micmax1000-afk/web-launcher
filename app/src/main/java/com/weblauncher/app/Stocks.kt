package com.weblauncher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Stocks, ETFs, indices, currencies, crypto, gold: quotes from Yahoo Finance (free, no account). */
object Stocks {
    data class Asset(val symbol: String, val name: String, val type: String = "", val exchange: String = "")
    data class Chart(
        val symbol: String, val name: String, val currency: String, val price: Double, val previous: Double,
        val times: List<Long>, val closes: List<Double>, val exchange: String
    ) {
        val change get() = price - previous
        val changePct get() = if (previous != 0.0) (price - previous) / previous * 100 else 0.0
    }
    data class Alert(val id: String, val symbol: String, val name: String, val above: Boolean, val price: Double, var done: Boolean = false)
    /** Shares owned: quantity and average purchase price (in the asset's currency). */
    data class Holding(val symbol: String, val name: String, val qty: Double, val cost: Double)
    data class Quote(val price: Double, val pct: Double, val currency: String, val time: Long)
    /** Portfolio totals for one currency. */
    data class Total(val currency: String, val value: Double, val invested: Double) {
        val gain get() = value - invested
        val gainPct get() = if (invested > 0) gain / invested * 100 else 0.0
    }

    /** Groups by currency (no exchange-rate guesswork): value at current prices vs money invested. */
    fun portfolio(holdings: List<Holding>, quotes: Map<String, Quote>): List<Total> =
        holdings.mapNotNull { h -> quotes[h.symbol]?.let { q -> Triple(q.currency, h.qty * q.price, h.qty * h.cost) } }
            .groupBy { it.first }
            .map { (cur, l) -> Total(cur, l.sumOf { it.second }, l.sumOf { it.third }) }
            .sortedByDescending { it.value }

    /** One line for the morning brief: FTSE MIB and the first symbols of the watchlist. */
    fun briefLine(items: List<Pair<String, Double>>): String? =
        items.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "📈 ") { (n, p) -> "$n ${fmtPct(p)}" }

    /** Quick picks shown when the list is empty or when adding. */
    val PRESETS = listOf(
        Asset("FTSEMIB.MI", "FTSE MIB (Borsa Italiana)", "INDEX"), Asset("^GSPC", "S&P 500", "INDEX"),
        Asset("^IXIC", "Nasdaq", "INDEX"), Asset("^STOXX50E", "Euro Stoxx 50", "INDEX"),
        Asset("EURUSD=X", "Euro / Dollaro", "CURRENCY"), Asset("GC=F", "Oro", "FUTURE"),
        Asset("BTC-EUR", "Bitcoin (€)", "CRYPTOCURRENCY"), Asset("BZ=F", "Petrolio Brent", "FUTURE")
    )

    val RANGES = listOf("1G" to ("1d" to "5m"), "5G" to ("5d" to "30m"), "1M" to ("1mo" to "1d"),
        "6M" to ("6mo" to "1d"), "1A" to ("1y" to "1d"), "5A" to ("5y" to "1wk"))

    // ---------- pure parsing (unit-tested) ----------

    fun parseSearch(json: String): List<Asset> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("quotes").mapNotNull { e ->
            val o = e.asJsonObject
            val sym = o.str("symbol") ?: return@mapNotNull null
            val type = o.str("quoteType").orEmpty()
            if (type !in setOf("EQUITY", "ETF", "INDEX", "MUTUALFUND", "CURRENCY", "CRYPTOCURRENCY", "FUTURE")) return@mapNotNull null
            Asset(sym, o.str("longname") ?: o.str("shortname") ?: sym, type, o.str("exchDisp").orEmpty())
        }
    }.getOrDefault(emptyList())

    fun parseChart(json: String): Chart? = runCatching {
        val r = JsonParser.parseString(json).asJsonObject.getAsJsonObject("chart").getAsJsonArray("result")[0].asJsonObject
        val meta = r.getAsJsonObject("meta")
        val ts = r.getAsJsonArray("timestamp")?.map { it.asLong * 1000 } ?: emptyList()
        val rawCloses = r.getAsJsonObject("indicators").getAsJsonArray("quote")[0].asJsonObject.getAsJsonArray("close")
        val pairs = ts.indices.mapNotNull { i ->
            val c = rawCloses?.get(i)?.takeIf { !it.isJsonNull }?.asDouble ?: return@mapNotNull null
            ts[i] to c
        }
        val price = meta.num("regularMarketPrice") ?: pairs.lastOrNull()?.second ?: return null
        val prev = meta.num("chartPreviousClose") ?: meta.num("previousClose") ?: pairs.firstOrNull()?.second ?: price
        Chart(meta.str("symbol").orEmpty(), meta.str("longName") ?: meta.str("shortName") ?: meta.str("symbol").orEmpty(),
            meta.str("currency").orEmpty(), price, prev, pairs.map { it.first }, pairs.map { it.second },
            meta.str("fullExchangeName") ?: meta.str("exchangeName").orEmpty())
    }.getOrNull()

    /** Titles from a Google News RSS feed. */
    fun parseNewsTitles(xml: String, max: Int = 8): List<String> =
        Regex("<item>.*?<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1].replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim() }
            .filter { it.isNotEmpty() }.take(max).toList()

    /** True when the alert should fire for this price. */
    fun triggered(a: Alert, price: Double) = !a.done && (if (a.above) price >= a.price else price <= a.price)

    fun fmtPrice(v: Double, currency: String = ""): String {
        val dec = if (v < 10) 4 else 2
        val s = String.format(Locale.ITALIAN, "%,.${dec}f", v)
        val sym = when (currency) { "EUR" -> " €"; "USD" -> " $"; "GBP" -> " £"; "" -> ""; else -> " $currency" }
        return s + sym
    }
    fun fmtNum(v: Double): String = Calc.fmt(v)
    fun fmtPct(p: Double) = String.format(Locale.ITALIAN, "%+.2f%%", p)

    private fun JsonObject.str(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asDouble

    // ---------- network (call off the main thread) ----------

    /** Why a Yahoo Finance request failed: [kind] = rete | limite | nontrovato | server | dati. */
    class StockError(val kind: String, message: String, val http: Int = 0) : Exception(message)

    /** The last failure, to explain «n.d.» to the user. Cleared by a successful request. */
    @Volatile var lastError: StockError? = null

    fun describe(e: Throwable?): String = when ((e as? StockError)?.kind) {
        "rete" -> "Nessuna connessione a Yahoo Finance: controlla internet"
        "limite" -> "Yahoo Finance ha limitato le richieste: riprova tra qualche minuto"
        "nontrovato" -> "Titolo non trovato su Yahoo Finance"
        "dati" -> "Yahoo Finance non ha mandato dati per questo titolo (simbolo non valido?)"
        "server" -> "Yahoo Finance non risponde (errore ${(e as StockError).http})"
        else -> "Quotazioni non disponibili al momento"
    }

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json,text/xml,*/*")
        c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
        try {
            val code = c.responseCode
            when {
                code == 429 -> throw StockError("limite", "HTTP 429", 429)
                code == 404 -> throw StockError("nontrovato", "HTTP 404", 404)
                code !in 200..299 -> throw StockError("server", "HTTP $code", code)
            }
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: StockError) { throw e
        } catch (e: java.io.IOException) { throw StockError("rete", "${e.javaClass.simpleName}: ${e.message}")
        } finally { c.disconnect() }
    }

    /** Yahoo Finance request with a second host and a pause before retrying when it is busy. Throws [StockError]. */
    private fun <T> yahoo(path: String, hosts: List<String>, parse: (String) -> T?): T {
        var last: StockError? = null
        for ((i, h) in hosts.withIndex()) {
            try {
                val r = parse(get("https://$h.finance.yahoo.com$path")) ?: throw StockError("dati", "risposta senza dati")
                lastError = null
                return r
            } catch (e: StockError) {
                last = e
                if (e.kind == "nontrovato" || e.kind == "dati") break           // retrying will not help
                if (i < hosts.lastIndex) Thread.sleep(if (e.kind == "limite") 1200L else 400L)
            }
        }
        lastError = last
        throw last!!
    }

    fun search(q: String): List<Asset> =
        yahoo("/v1/finance/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&quotesCount=12&newsCount=0&lang=it-IT&region=IT",
            listOf("query2", "query1", "query2")) { parseSearch(it) }

    fun chart(symbol: String, range: String = "1d", interval: String = "5m"): Chart =
        yahoo("/v8/finance/chart/" + java.net.URLEncoder.encode(symbol, "UTF-8") + "?range=$range&interval=$interval&includePrePost=false",
            listOf("query1", "query2", "query1")) { parseChart(it) }

    /** For the «Prova la connessione» button: what each Yahoo host answers right now. */
    fun diagnose(): String = buildString {
        for (h in listOf("query1", "query2")) {
            val t0 = System.currentTimeMillis()
            try {
                val ch = parseChart(get("https://$h.finance.yahoo.com/v8/finance/chart/%5EGSPC?range=1d&interval=5m"))
                append("✅ $h: ").append(if (ch != null) "S&P 500 ${fmtPrice(ch.price, ch.currency)}" else "risposta senza dati").append(" (${System.currentTimeMillis() - t0} ms)\n")
            } catch (e: StockError) { append("❌ $h: ${describe(e)}\n    dettaglio: ${e.message}\n") }
        }
        try {
            val r = parseSearch(get("https://query2.finance.yahoo.com/v1/finance/search?q=eni&quotesCount=5&newsCount=0"))
            append("✅ ricerca titoli: ${r.size} risultati\n")
        } catch (e: StockError) { append("❌ ricerca titoli: ${describe(e)}\n    dettaglio: ${e.message}\n") }
        append("\nOra: ").append(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALIAN).format(java.util.Date()))
    }

    fun news(name: String): List<String> = runCatching {
        parseNewsTitles(get("https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("$name borsa", "UTF-8") + "&hl=it&gl=IT&ceid=IT:it"))
    }.getOrDefault(emptyList())

    // ---------- storage ----------

    private fun prefs(c: Context) = c.getSharedPreferences("wl_stocks", Context.MODE_PRIVATE)
    fun watch(c: Context): MutableList<Asset> = runCatching {
        Gson().fromJson<MutableList<Asset>>(prefs(c).getString("watch", null), object : TypeToken<MutableList<Asset>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun saveWatch(c: Context, l: List<Asset>) = prefs(c).edit().putString("watch", Gson().toJson(l)).apply()
    fun alerts(c: Context): MutableList<Alert> = runCatching {
        Gson().fromJson<MutableList<Alert>>(prefs(c).getString("alerts", null), object : TypeToken<MutableList<Alert>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun saveAlerts(c: Context, l: List<Alert>) { prefs(c).edit().putString("alerts", Gson().toJson(l)).apply(); schedule(c) }
    fun holdings(c: Context): MutableList<Holding> = runCatching {
        Gson().fromJson<MutableList<Holding>>(prefs(c).getString("holdings", null), object : TypeToken<MutableList<Holding>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun saveHoldings(c: Context, l: List<Holding>) = prefs(c).edit().putString("holdings", Gson().toJson(l)).apply()

    /** Last known quotes, for the widget and the portfolio without waiting for the network. */
    fun quotes(c: Context): MutableMap<String, Quote> = runCatching {
        Gson().fromJson<MutableMap<String, Quote>>(prefs(c).getString("quotes", null), object : TypeToken<MutableMap<String, Quote>>() {}.type)
    }.getOrNull() ?: mutableMapOf()
    fun remember(c: Context, symbol: String, ch: Chart) {
        val q = quotes(c); q[symbol] = Quote(ch.price, ch.changePct, ch.currency, System.currentTimeMillis())
        prefs(c).edit().putString("quotes", Gson().toJson(q)).apply()
    }

    /** Refreshes the quotes of the watchlist (network). */
    fun refreshAll(c: Context) {
        watch(c).forEach { a -> runCatching { chart(a.symbol) }.getOrNull()?.let { remember(c, a.symbol, it) } }
        StocksWidget.update(c)
    }

    /** For the morning brief (network, background thread). */
    fun briefText(c: Context): String? {
        val list = (listOf(Asset("FTSEMIB.MI", "FTSE MIB")) + watch(c).filter { it.symbol != "FTSEMIB.MI" }).take(5)
        if (watch(c).isEmpty()) return null
        val items = list.mapNotNull { a -> runCatching { chart(a.symbol) }.getOrNull()?.let { remember(c, a.symbol, it); a.name.substringBefore(" (").take(18) to it.changePct } }
        return briefLine(items)
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (alerts(c).none { !it.done } && !StocksWidget.present(c)) { wm.cancelUniqueWork("wl_stocks"); return }
        wm.enqueueUniquePeriodicWork("wl_stocks", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StocksWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun notify(c: Context, a: Alert, price: Double, currency: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("borsa", "Borsa", NotificationManager.IMPORTANCE_DEFAULT))
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WIDGET, "stocks")
        val pi = PendingIntent.getActivity(c, a.id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "borsa").setSmallIcon(R.drawable.ic_nav_news)
            .setContentTitle("📈 ${a.name}: ${fmtPrice(price, currency)}")
            .setContentText("Ha ${if (a.above) "superato" else "toccato al ribasso"} ${fmtPrice(a.price, currency)}")
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { nm.notify(a.id.hashCode(), n) }
    }
}

/** Every ~30 minutes: checks the price alerts and notifies each one once. */
class StocksWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = applicationContext
        val list = Stocks.alerts(c)
        var changed = false
        list.filter { !it.done }.groupBy { it.symbol }.forEach { (sym, alerts) ->
            val ch = runCatching { Stocks.chart(sym) }.getOrNull() ?: return@forEach
            alerts.forEach { a -> if (Stocks.triggered(a, ch.price)) { Stocks.notify(c, a, ch.price, ch.currency); a.done = true; changed = true } }
        }
        if (changed) Stocks.saveAlerts(c, list)
        if (StocksWidget.present(c)) Stocks.refreshAll(c)
        return Result.success()
    }
}
