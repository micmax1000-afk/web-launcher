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
    fun fmtPct(p: Double) = String.format(Locale.ITALIAN, "%+.2f%%", p)

    private fun JsonObject.str(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asDouble

    // ---------- network (call off the main thread) ----------

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json,text/xml,*/*")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    fun search(q: String): List<Asset> =
        parseSearch(get("https://query2.finance.yahoo.com/v1/finance/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") +
            "&quotesCount=12&newsCount=0&lang=it-IT&region=IT"))

    fun chart(symbol: String, range: String = "1d", interval: String = "5m"): Chart? {
        val path = "/v8/finance/chart/" + java.net.URLEncoder.encode(symbol, "UTF-8") + "?range=$range&interval=$interval&includePrePost=false"
        return runCatching { parseChart(get("https://query1.finance.yahoo.com$path")) }.getOrNull()
            ?: parseChart(get("https://query2.finance.yahoo.com$path"))
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

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (alerts(c).none { !it.done }) { wm.cancelUniqueWork("wl_stocks"); return }
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
        return Result.success()
    }
}
