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
