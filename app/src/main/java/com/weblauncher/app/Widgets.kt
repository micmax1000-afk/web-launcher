package com.weblauncher.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private fun launch(context: Context, code: Int, build: Intent.() -> Unit): PendingIntent {
    val i = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        build()
    }
    return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Search bar for the phone's home screen: search, voice, news. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(context.packageName, R.layout.widget_search)
        v.setOnClickPendingIntent(R.id.wRoot, launch(context, 101) { putExtra(MainActivity.EXTRA_WIDGET, "search") })
        v.setOnClickPendingIntent(R.id.wSearch, launch(context, 101) { putExtra(MainActivity.EXTRA_WIDGET, "search") })
        v.setOnClickPendingIntent(R.id.wMic, launch(context, 102) { putExtra(MainActivity.EXTRA_WIDGET, "voice") })
        v.setOnClickPendingIntent(R.id.wNews, launch(context, 103) { putExtra(MainActivity.EXTRA_WIDGET, "news") })
        manager.updateAppWidget(ids, v)
    }
}

/** Latest headlines; refreshed every time the news are updated in the app. */
class NewsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        data class Headline(val title: String, val link: String, val source: String)

        fun save(context: Context, items: List<NewsItem>) {
            val top = items.sortedWith(compareByDescending<NewsItem> { (it.also ?: emptyList()).size.coerceAtMost(3) }
                .thenByDescending { it.time }).take(4).map { Headline(it.title, it.link, it.source) }
            context.getSharedPreferences("wl_news", Context.MODE_PRIVATE).edit()
                .putString("widget", Gson().toJson(top)).putLong("widget_time", System.currentTimeMillis()).apply()
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, NewsWidget::class.java))
            if (ids.isNotEmpty()) render(context, m, ids)
        }

        fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences("wl_news", Context.MODE_PRIVATE)
            val list = runCatching {
                Gson().fromJson<List<Headline>>(prefs.getString("widget", "[]"), object : TypeToken<List<Headline>>() {}.type)
            }.getOrNull().orEmpty()
            val v = RemoteViews(context.packageName, R.layout.widget_news)
            val time = prefs.getLong("widget_time", 0L)
            v.setTextViewText(R.id.nHeader, "📰 Notizie" + if (time > 0) " · " + NewsAdapter.ago(time) else "")
            v.setOnClickPendingIntent(R.id.nRoot, launch(context, 200) { putExtra(MainActivity.EXTRA_WIDGET, "news") })
            v.setOnClickPendingIntent(R.id.nHeader, launch(context, 200) { putExtra(MainActivity.EXTRA_WIDGET, "news") })
            val rows = intArrayOf(R.id.n1, R.id.n2, R.id.n3, R.id.n4)
            rows.forEachIndexed { i, id ->
                val h = list.getOrNull(i)
                v.setTextViewText(id, if (h == null) (if (i == 0) "Apri le Notizie per caricarle" else "") else "${h.title}  · ${h.source}")
                if (h != null) v.setOnClickPendingIntent(id, launch(context, 210 + i) { putExtra("shortcut_url", h.link) })
            }
            manager.updateAppWidget(ids, v)
        }
    }
}

/** Watchlist on the phone's home screen: price and change of the first 5 symbols. */
class StocksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        Stocks.schedule(context)
        val pr = goAsync()
        Thread { runCatching { Stocks.refreshAll(context) }; pr.finish() }.start()
    }
    override fun onDisabled(context: Context) { Stocks.schedule(context) }

    companion object {
        fun present(c: Context) = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, StocksWidget::class.java)).isNotEmpty()

        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, StocksWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }

        fun render(c: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(c.packageName, R.layout.widget_stocks)
            val watch = Stocks.watch(c); val quotes = Stocks.quotes(c)
            val newest = quotes.values.maxOfOrNull { it.time } ?: 0L
            v.setTextViewText(R.id.sHeader, "📈 Borsa" + if (newest > 0) " · " + NewsAdapter.ago(newest) else "")
            v.setOnClickPendingIntent(R.id.sRoot, launch(c, 300) { putExtra(MainActivity.EXTRA_WIDGET, "stocks") })
            val names = intArrayOf(R.id.s1n, R.id.s2n, R.id.s3n, R.id.s4n, R.id.s5n)
            val vals = intArrayOf(R.id.s1v, R.id.s2v, R.id.s3v, R.id.s4v, R.id.s5v)
            names.indices.forEach { i ->
                val a = watch.getOrNull(i); val q = a?.let { quotes[it.symbol] }
                v.setTextViewText(names[i], a?.name?.substringBefore(" (")?.take(22) ?: if (i == 0) "Aggiungi titoli in Strumenti → Borsa" else "")
                v.setTextViewText(vals[i], q?.let { Stocks.fmtPrice(it.price) + "  " + Stocks.fmtPct(it.pct) } ?: "")
                v.setTextColor(vals[i], if (q == null) 0xFFB0B8C8.toInt() else if (q.pct >= 0) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
            }
            manager.updateAppWidget(ids, v)
        }
    }
}

/** «Oggi»: what is to do today and tomorrow, from the same list as the «Da fare» page. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, TodayWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }
        fun render(c: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(c.packageName, R.layout.widget_today)
            val now = System.currentTimeMillis()
            val items = runCatching { Todo.items(c, 2, now) }.getOrDefault(emptyList())
                .filter { !(it.time < now - 3_600_000L && it.kind == "cal") }
            v.setTextViewText(R.id.tHeader, "✅ Da fare · " + java.text.SimpleDateFormat("EEEE d MMM", java.util.Locale.ITALIAN).format(java.util.Date(now)))
            v.setOnClickPendingIntent(R.id.tRoot, launch(c, 400) { putExtra(MainActivity.EXTRA_WIDGET, "todo") })
            val ids5 = intArrayOf(R.id.t1, R.id.t2, R.id.t3, R.id.t4, R.id.t5)
            ids5.forEachIndexed { i, id ->
                v.setTextViewText(id, items.getOrNull(i)?.let { Todo.line(it, now) } ?: if (i == 0) "Niente da fare 🙂" else "")
            }
            if (items.size > 5) v.setTextViewText(R.id.t5, "+ altri ${items.size - 4} · tocca per vedere tutto")
            manager.updateAppWidget(ids, v)
        }
    }
}

/** The shopping list: the things still to buy. */
class ShopWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, ShopWidget::class.java))
            if (ids.isNotEmpty()) render(c, m, ids)
        }
        fun render(c: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(c.packageName, R.layout.widget_shop)
            val note = runCatching { JsonStore.notes(c).load() }.getOrDefault(emptyList()).firstOrNull { it.text.lines().first().trim().equals("Lista della spesa", true) }
            val items = note?.let { n -> n.text.lines().drop(1).mapNotNull { l -> l.trim().takeIf { it.startsWith("☐") }?.removePrefix("☐")?.trim()?.takeIf { it.isNotEmpty() } } }.orEmpty()
            v.setTextViewText(R.id.pHeader, "🛒 Spesa" + if (items.isNotEmpty()) " · ${items.size}" else "")
            v.setOnClickPendingIntent(R.id.pRoot, launch(c, 410) { putExtra(MainActivity.EXTRA_WIDGET, "shopping") })
            intArrayOf(R.id.p1, R.id.p2, R.id.p3, R.id.p4, R.id.p5, R.id.p6).forEachIndexed { i, id ->
                v.setTextViewText(id, items.getOrNull(i)?.let { "☐ $it" } ?: if (i == 0) "La lista è vuota: tocca per aggiungere" else "")
            }
            if (items.size > 6) v.setTextViewText(R.id.p6, "+ altri ${items.size - 5}")
            manager.updateAppWidget(ids, v)
        }
    }
}

/** Four buttons: note, shopping, reminder, photo of a document. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(context.packageName, R.layout.widget_quick)
        v.setOnClickPendingIntent(R.id.qNote, launch(context, 421) { putExtra(MainActivity.EXTRA_WIDGET, "new_note") })
        v.setOnClickPendingIntent(R.id.qShop, launch(context, 422) { putExtra(MainActivity.EXTRA_WIDGET, "quick_shop") })
        v.setOnClickPendingIntent(R.id.qRem, launch(context, 423) { putExtra(MainActivity.EXTRA_WIDGET, "quick_reminder") })
        v.setOnClickPendingIntent(R.id.qScan, launch(context, 424) { putExtra(MainActivity.EXTRA_WIDGET, "scan") })
        manager.updateAppWidget(ids, v)
    }
}
