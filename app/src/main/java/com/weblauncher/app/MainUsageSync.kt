package com.weblauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.weblauncher.app.MainActivity.CatalogApp
import com.weblauncher.app.MainActivity.Companion.AI_SYSTEM
import com.weblauncher.app.MainActivity.Companion.DESKTOP_UA
import com.weblauncher.app.MainActivity.Companion.EXTRA_OPEN_PLAYER
import com.weblauncher.app.MainActivity.Companion.EXTRA_WIDGET
import com.weblauncher.app.MainActivity.Companion.MENU_AI
import com.weblauncher.app.MainActivity.Companion.NEWS_ALL
import com.weblauncher.app.MainActivity.Companion.STATE_TAB
import com.weblauncher.app.MainActivity.Companion.SUGGEST_TAG
import com.weblauncher.app.MainActivity.Companion.TAB_AUDIO
import com.weblauncher.app.MainActivity.Companion.TAB_BROWSER
import com.weblauncher.app.MainActivity.Companion.TAB_HOME
import com.weblauncher.app.MainActivity.Companion.TAB_NEWS
import com.weblauncher.app.MainActivity.Companion.TAB_SETTINGS
import com.weblauncher.app.MainActivity.Companion.TAB_TOOLS
import com.weblauncher.app.MainActivity.ReaderDoc
import com.weblauncher.app.MainActivity.Song
import com.weblauncher.app.MainActivity.TodayEntry
import com.weblauncher.app.MainActivity.WebTab
import com.weblauncher.app.MainActivity.YtChannel
import java.util.Locale
import java.util.UUID

internal fun MainActivity.usageMap(day: String): MutableMap<String, Long> = runCatching {
    val o = org.json.JSONObject(usagePrefs.getString("d_$day", "{}") ?: "{}")
    o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
}.getOrDefault(mutableMapOf())

internal fun MainActivity.flushUsage() {
    val host = usageHost ?: return
    val now = System.currentTimeMillis()
    val ms = now - usageStart
    usageStart = now
    if (ms in 1000..(6 * 3600_000L)) {
        val day = today
        val map = usageMap(day)
        map[host] = (map[host] ?: 0L) + ms
        usagePrefs.edit().putString("d_$day", org.json.JSONObject(map as Map<*, *>).toString()).apply()
        checkUsageLimit(host, map[host] ?: 0L)
    }
}

/** Called whenever the visible site may change. */
internal fun MainActivity.trackUsage() {
    flushUsage()
    val url = webView.url
    val visible = currentTab == TAB_BROWSER && browserHasPage && !isFinishing && hasWindowFocusOrResumed
    usageHost = if (visible && url != null && url.startsWith("http") && !MiaAi.isMiaUrl(url) && !isPdfViewer(url)) hostOf(url) else null
    usageStart = System.currentTimeMillis()
}

internal fun MainActivity.limitFor(host: String): Int = usagePrefs.getInt("limit_$host", 0)

internal fun MainActivity.checkUsageLimit(host: String, usedMs: Long) {
    val limit = limitFor(host)
    if (limit <= 0 || !hasWindowFocusOrResumed || isFinishing) return
    val extra = usagePrefs.getInt("extra_${today}_$host", 0)
    if (usedMs < (limit + extra) * 60_000L) return
    if (usagePrefs.getBoolean("warned_${today}_${host}_$extra", false)) return
    usagePrefs.edit().putBoolean("warned_${today}_${host}_$extra", true).apply()
    MaterialAlertDialogBuilder(this)
        .setTitle("⏱ Tempo scaduto")
        .setMessage("Oggi hai usato $host per ${usedMs / 60_000} minuti (limite: $limit min).")
        .setPositiveButton("Chiudi") { _, _ -> closeCurrentTab(); showTab(TAB_HOME) }
        .setNegativeButton("Ancora 15 min") { _, _ -> usagePrefs.edit().putInt("extra_${today}_$host", extra + 15).apply() }
        .setCancelable(false)
        .show()
}

internal fun MainActivity.showUsagePage() {
    flushUsage()
    openPage("Tempo di utilizzo") { _, body, _ ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun render() {
            body.removeAllViews()
            val map = usageMap(today)
            val total = map.values.sum()
            body.addView(TextView(this).apply {
                text = "Oggi: ${fmtDur(total / 1000).ifBlank { "meno di 1 min" }}"
                textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
                setPadding((10 * d).toInt(), (8 * d).toInt(), 0, (4 * d).toInt())
            })
            // last 7 days
            val cal = java.util.Calendar.getInstance()
            val fmt = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT)
            val week = (6 downTo 0).map { back ->
                val c = cal.clone() as java.util.Calendar
                c.add(java.util.Calendar.DAY_OF_YEAR, -back)
                java.text.SimpleDateFormat("EEE", Locale.ITALIAN).format(c.time).take(3) to usageMap(fmt.format(c.time)).values.sum()
            }
            val maxDay = week.maxOf { it.second }.coerceAtLeast(1)
            val chart = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.BOTTOM
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            }
            week.forEachIndexed { i, (name, ms) ->
                val col = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, (110 * d).toInt(), 1f)
                }
                col.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 6 * d; setColor(if (i == 6) accent else androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x66))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams((18 * d).toInt(), ((80 * d) * ms / maxDay).toInt().coerceAtLeast((2 * d).toInt()))
                })
                col.addView(TextView(this).apply { text = name; textSize = 11f; setTextColor(palette.textMuted); setPadding(0, (4 * d).toInt(), 0, 0) })
                chart.addView(col)
            }
            body.addView(chart)
            sectionLabel(body, "OGGI PER SITO · tocca per un limite")
            if (map.isEmpty()) { emptyLabel(body, "Nessun sito usato oggi"); return }
            map.entries.sortedByDescending { it.value }.forEach { (host, ms) ->
                val limit = limitFor(host)
                listRow(body, host, fmtDur(ms / 1000).ifBlank { "<1 min" } + if (limit > 0) " · limite $limit min" else "",
                    onClick = {
                        val opts = arrayOf("Nessun limite", "15 min", "30 min", "45 min", "1 ora", "2 ore")
                        val values = intArrayOf(0, 15, 30, 45, 60, 120)
                        MaterialAlertDialogBuilder(this).setTitle("Limite giornaliero · $host")
                            .setItems(opts) { _, w ->
                                usagePrefs.edit().putInt("limit_$host", values[w]).apply()
                                render()
                            }.show()
                    })
            }
        }
        render()
    }
}

internal fun MainActivity.fullBackupJson(): String {
    val gson = com.google.gson.Gson()
    val now = System.currentTimeMillis()
    return gson.toJson(mapOf(
        "apps" to apps,
        "categories" to categories,
        "searchEngine" to repo.getSearchEngine().name,
        "savedAt" to now,
        "device" to (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL),
        "notes" to notes.load(),
        "readLater" to readLater.load(),
        "newsEnabled" to news.enabledIds().toList(),
        "newsCustom" to news.customSources(),
        "podcasts" to audio.subscriptions(),
        "radioFavs" to audio.favoriteStations(),
        "weather" to mapOf(
            "city" to weatherPrefs.getString("city", null),
            "lat" to weatherPrefs.getFloat("lat", Float.NaN).takeIf { !it.isNaN() },
            "lon" to weatherPrefs.getFloat("lon", Float.NaN).takeIf { !it.isNaN() }
        )
    ))
}

/** Applies the extra sections of a backup (notes, read later, news, podcasts...). */
internal fun MainActivity.applyBackupExtras(o: org.json.JSONObject) {
    val gson = com.google.gson.Gson()
    fun <T> list(key: String, type: java.lang.reflect.Type): List<T>? =
        o.optJSONArray(key)?.let { runCatching { gson.fromJson<List<T>>(it.toString(), type) }.getOrNull() }
    list<Note>("notes", object : com.google.gson.reflect.TypeToken<List<Note>>() {}.type)?.let { notes.save(it) }
    list<ReadLaterItem>("readLater", object : com.google.gson.reflect.TypeToken<List<ReadLaterItem>>() {}.type)?.let { readLater.save(it) }
    list<String>("newsEnabled", object : com.google.gson.reflect.TypeToken<List<String>>() {}.type)?.let { news.setEnabledIds(it.toSet()) }
    list<NewsSource>("newsCustom", object : com.google.gson.reflect.TypeToken<List<NewsSource>>() {}.type)?.let { news.setCustomSources(it) }
    list<Podcast>("podcasts", object : com.google.gson.reflect.TypeToken<List<Podcast>>() {}.type)?.let { audio.setSubscriptions(it) }
    list<RadioStation>("radioFavs", object : com.google.gson.reflect.TypeToken<List<RadioStation>>() {}.type)?.let { audio.setFavoriteStations(it) }
    o.optJSONObject("weather")?.let { w ->
        val city = w.optString("city").takeIf { it.isNotBlank() && it != "null" }
        if (city != null && w.has("lat")) setWeatherPlace(city, w.optDouble("lat"), w.optDouble("lon"))
    }
    newsItems = emptyList(); newsUpdatedAt = 0L; episodes = emptyList()
}

internal fun MainActivity.applyFullBackup(o: org.json.JSONObject) {
    val gson = com.google.gson.Gson()
    val appType = object : com.google.gson.reflect.TypeToken<MutableList<AppItem>>() {}.type
    val loaded = runCatching { gson.fromJson<MutableList<AppItem>>(o.optJSONArray("apps")?.toString() ?: "[]", appType) }.getOrNull()
        ?.filter { it != null && !it.name.isNullOrBlank() && !it.url.isNullOrBlank() }
    if (!loaded.isNullOrEmpty() || o.has("apps")) {
        apps = (loaded ?: emptyList()).map { it.copy(iconPath = it.iconPath?.takeIf { p -> java.io.File(p).exists() }) }.toMutableList()
        val cats = runCatching { gson.fromJson<MutableList<String>>(o.optJSONArray("categories")?.toString() ?: "[]",
            object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type) }.getOrNull()
        categories = (cats ?: mutableListOf("Utility")).filterNotNull().toMutableList()
        apps.map { it.category }.distinct().filter { it !in categories }.forEach { categories.add(it) }
        repo.saveApps(apps); repo.saveCategories(categories)
    }
    o.optString("searchEngine").takeIf { it.isNotBlank() }?.let { repo.setSearchEngine(SearchEngine.fromName(it)) }
    applyBackupExtras(o)
    refreshList()
}

/** On return to the app: if the Drive backup was written by another device, offer to update. */
internal fun MainActivity.checkSyncFromOtherDevice() {
    val raw = repo.getAutoBackupUri() ?: return
    if (System.currentTimeMillis() - syncPrefs.getLong("checked", 0L) < 60_000L) return
    syncPrefs.edit().putLong("checked", System.currentTimeMillis()).apply()
    io.execute {
        val text = runCatching {
            contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return@execute
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return@execute
        val savedAt = o.optLong("savedAt", 0L)
        val mine = syncPrefs.getLong("written", 0L)
        val applied = syncPrefs.getLong("applied", 0L)
        if (savedAt <= mine + 2000 || savedAt <= applied) return@execute
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle("🔄 Dati più recenti")
                .setMessage("Il backup su Drive è stato aggiornato da «${o.optString("device", "un altro dispositivo")}» " +
                    "(${NewsAdapter.ago(savedAt)}). Vuoi caricare app, note, notizie e podcast da lì?")
                .setPositiveButton("Aggiorna") { _, _ ->
                    applyFullBackup(o)
                    syncPrefs.edit().putLong("applied", savedAt).putLong("written", savedAt).apply()
                    toast("Dati aggiornati")
                }
                .setNegativeButton("Non ora") { _, _ -> syncPrefs.edit().putLong("applied", savedAt).apply() }
                .show()
        }
    }
}

internal fun MainActivity.showSyncHelp() {
    MaterialAlertDialogBuilder(this)
        .setTitle("🔄 Sincronizza tra dispositivi")
        .setMessage(
            "Usa lo stesso file di backup su Google Drive su tutti i dispositivi.\n\n" +
            "1. Sul primo: «Crea il file» e salvalo su Drive.\n" +
            "2. Sugli altri: «Collega un file esistente» e scegli lo stesso file.\n\n" +
            "Le modifiche (app, note, leggi dopo, notizie, podcast, meteo) vengono salvate quando esci dall'app; " +
            "quando apri l'app su un altro dispositivo ti propone di aggiornarla."
        )
        .setPositiveButton("Crea il file") { _, _ -> startAutoBackupSetup() }
        .setNeutralButton("Collega un file esistente") { _, _ -> syncOpenLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        .setNegativeButton("Chiudi", null)
        .show()
}
