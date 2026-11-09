package com.weblauncher.app

import android.content.Context.LOCATION_SERVICE
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

internal fun MainActivity.weatherInfo(code: Int): Pair<String, String> = when (code) {
    0 -> "☀️" to "Sereno"
    1 -> "🌤" to "Poco nuvoloso"
    2 -> "⛅" to "Parz. nuvoloso"
    3 -> "☁️" to "Coperto"
    45, 48 -> "🌫" to "Nebbia"
    in 51..57 -> "🌦" to "Pioviggine"
    in 61..67 -> "🌧" to "Pioggia"
    in 71..77 -> "❄️" to "Neve"
    in 80..82 -> "🌦" to "Rovesci"
    85, 86 -> "🌨" to "Neve"
    in 95..99 -> "⛈" to "Temporale"
    else -> "🌡" to ""
}

/** Weather line of the «Oggi» card. */
internal fun MainActivity.renderWeatherRow(card: android.widget.LinearLayout) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val city = weatherPrefs.getString("city", null)
    val data = weatherPrefs.getString("data", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    card.setOnClickListener { if (city == null) setupWeather() else showTodayDetail() }
    if (city == null || data == null) {
        card.addView(TextView(this).apply {
            text = if (city == null) "☀️  Aggiungi il meteo della tua città" else "⏳  Meteo di $city…"
            textSize = 15f; setTextColor(palette.text)
        })
        return
    }
    val cur = data.optJSONObject("current")
    val daily = data.optJSONObject("daily")
    val (emoji, label) = weatherInfo(cur?.optInt("weather_code") ?: -1)
    card.addView(TextView(this).apply { text = emoji; textSize = 30f; setPadding(0, 0, dp(12), 0) })
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
    }
    val temp = cur?.optDouble("temperature_2m")?.let { Math.round(it) }
    box.addView(TextView(this).apply {
        text = "${temp ?: "–"}° · $label"; textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
    })
    val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.let { Math.round(it) }
    val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.let { Math.round(it) }
    val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0)
    box.addView(TextView(this).apply {
        text = listOfNotNull(city, if (max != null && min != null) "$max° / $min°" else null, rain?.takeIf { it >= 20 }?.let { "💧 $it%" })
            .joinToString(" · ")
        textSize = 12.5f; setTextColor(palette.textMuted)
    })
    card.addView(box)
    val days = daily?.optJSONArray("time")
    val dayFmt = java.text.SimpleDateFormat("EEE", Locale.ITALIAN)
    val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    for (i in 1..3) {
        val t = days?.optString(i) ?: continue
        val dayName = runCatching { dayFmt.format(parse.parse(t)!!).replaceFirstChar { it.uppercase() }.take(3) }.getOrDefault("")
        val e = weatherInfo(daily.optJSONArray("weather_code")?.optInt(i) ?: -1).first
        val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(i)?.let { Math.round(it) }
        card.addView(TextView(this).apply {
            text = "$dayName\n$e\n${hi ?: "–"}°"
            textSize = 11.5f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
            setPadding(dp(6), 0, dp(6), 0)
        })
    }
}

internal fun MainActivity.calendarEvents(from: Long, to: Long): List<TodayEntry> {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString()).appendPath(to.toString()).build()
        contentResolver.query(uri, arrayOf(
            android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.EVENT_ID
        ), null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            val out = mutableListOf<TodayEntry>()
            while (c.moveToNext()) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(senza titolo)"
                val allDay = c.getInt(2) == 1
                var begin = c.getLong(1)
                if (allDay) begin = java.util.Calendar.getInstance().apply {
                    val u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = begin }
                    clear(); set(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH), u.get(java.util.Calendar.DAY_OF_MONTH))
                }.timeInMillis
                val id = c.getLong(3)
                out.add(TodayEntry(begin, allDay, title, "📅") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, id))) }
                })
            }
            out
        }
    }.getOrNull().orEmpty()
}

/** Today and tomorrow: calendar, reminders, deadlines and renewals. */
internal fun MainActivity.todayEntries(): List<TodayEntry> {
    val start = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val end = start + 2 * 86_400_000L
    val now = System.currentTimeMillis()
    val out = mutableListOf<TodayEntry>()
    out += calendarEvents(start, end)
    Reminders.load(this).filter { !it.done && it.at in now..end }.forEach { r ->
        out += TodayEntry(r.at, false, r.text, if (r.repeat != null) "🔁" else "⏰") { showRemindersPage() }
    }
    Deadlines.load(this).forEach { d ->
        val days = (d.date - now) / 86_400_000L
        val near = when (d.kind) { Deadlines.SUBSCRIPTION -> days in 0..3; Deadlines.WARRANTY -> false; else -> d.date in now..(now + 7 * 86_400_000L) }
        if (near) out += TodayEntry(minOf(maxOf(d.date, start), end - 1), true,
            when (d.kind) { Deadlines.SUBSCRIPTION -> "${d.title} si rinnova ${daysLeft(d.date)}"; else -> "${d.title} scade ${daysLeft(d.date)}" },
            "⚠️") { showDeadlinesPage(d.kind) }
    }
    runCatching { Birthdays.upcoming(this, 1) }.getOrDefault(emptyList()).forEach { (p, days) ->
        out += TodayEntry(start + days * 86_400_000L, true, "Compleanno di ${p.name}" + (p.turning(java.util.Calendar.getInstance())?.let { " ($it)" } ?: ""), "🎂") { showBirthdaysPage() }
    }
    Places.parking(this)?.takeIf { it.until > now }?.let { p ->
        out += TodayEntry(p.until, false, "Scade il parcheggio", "🚗") { showPlacesPage() }
    }
    return out.filter { it.time < end }.sortedWith(compareBy<TodayEntry> { it.time >= start + 86_400_000L }.thenBy { !it.allDay }.thenBy { it.time })
}

internal fun MainActivity.renderWeather() {
    val card = findViewById<android.widget.LinearLayout>(R.id.weatherCard)
    card.removeAllViews()
    card.orientation = android.widget.LinearLayout.VERTICAL
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    card.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 20 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
    }
    val showWeather = !weatherPrefs.getBoolean("hidden", false)
    val showToday = homePrefs.getBoolean("today", true)
    if (showWeather) {
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        renderWeatherRow(row)
        card.addView(row)
    }
    var lines = 0
    if (showToday) {
        val entries = runCatching { todayEntries() }.getOrDefault(emptyList())
        val tomorrow = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); add(java.util.Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val tf = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN)
        entries.take(3).forEach { e ->
            card.addView(TextView(this).apply {
                val whenTxt = when {
                    e.time >= tomorrow -> "domani"
                    e.allDay -> "oggi"
                    else -> tf.format(java.util.Date(e.time))
                }
                text = "${e.icon}  $whenTxt  ${e.text}"
                textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(palette.text)
                setPadding(dp(2), dp(if (lines == 0 && showWeather) 10 else 4), 0, 0)
                setOnClickListener { e.action?.invoke() ?: showTodayDetail() }
            })
            lines++
        }
        if (entries.size > 3) card.addView(TextView(this).apply {
            text = "+ altri ${entries.size - 3}"; textSize = 13f; setPadding(dp(2), dp(4), 0, 0)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
            setOnClickListener { showTodayDetail() }
        })
        val hasCal = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!hasCal && !homePrefs.getBoolean("askedCal", false)) card.addView(TextView(this).apply {
            text = "📅  Collega il calendario per vedere gli appuntamenti  ›"; textSize = 13f; setPadding(dp(2), dp(8), 0, 0)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
            setOnClickListener { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
            setOnLongClickListener { homePrefs.edit().putBoolean("askedCal", true).apply(); renderWeather(); true }
        })
    }
    card.isVisible = card.childCount > 0
    card.setOnClickListener { showTodayDetail() }
}

internal fun MainActivity.showTodayDetail() {
    val entries = runCatching { todayEntries() }.getOrDefault(emptyList())
    val tomorrow = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    val tf = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN)
    val today = java.text.SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(java.util.Date()).replaceFirstChar { it.uppercase() }
    val labels = mutableListOf<String>(); val actions = mutableListOf<(() -> Unit)?>()
    var tomorrowHeader = false
    entries.forEach { e ->
        if (e.time >= tomorrow && !tomorrowHeader) { labels += "— DOMANI —"; actions += null; tomorrowHeader = true }
        labels += "${e.icon}  ${if (e.allDay) "" else tf.format(java.util.Date(e.time)) + "  "}${e.text}"; actions += e.action
    }
    if (labels.isEmpty()) labels += "Niente in programma: giornata libera 🙂".also { actions += null }
    val b = MaterialAlertDialogBuilder(this).setTitle("Oggi · $today")
        .setItems(labels.toTypedArray()) { _, w -> actions.getOrNull(w)?.invoke() }
        .setNegativeButton("Chiudi", null)
    if (weatherPrefs.getString("data", null) != null) b.setNeutralButton("🌦 Meteo") { _, _ -> showWeatherDetail() }
    else b.setNeutralButton("🌦 Aggiungi meteo") { _, _ -> setupWeather() }
    b.show()
}

internal fun MainActivity.refreshWeather(force: Boolean = false) {
    val lat = weatherPrefs.getFloat("lat", Float.NaN)
    val lon = weatherPrefs.getFloat("lon", Float.NaN)
    if (lat.isNaN() || weatherPrefs.getBoolean("hidden", false)) return
    if (!force && System.currentTimeMillis() - weatherPrefs.getLong("time", 0L) < 30 * 60_000L) return
    Thread {
        val json = runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=7"
            java.net.URL(url).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        runOnUiThread {
            if (json != null) weatherPrefs.edit().putString("data", json).putLong("time", System.currentTimeMillis()).apply()
            renderWeather()
        }
    }.start()
}

@SuppressLint("MissingPermission")
internal fun MainActivity.useMyLocationForWeather() {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        return
    }
    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    val loc = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (loc == null) { toast("Posizione non disponibile: scrivi il nome della città"); return }
    Thread {
        @Suppress("DEPRECATION")
        val name = runCatching {
            android.location.Geocoder(this, Locale.ITALIAN).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
        }.getOrNull() ?: "La tua zona"
        runOnUiThread { setWeatherPlace(name, loc.latitude, loc.longitude) }
    }.start()
}

internal fun MainActivity.setWeatherPlace(name: String, lat: Double, lon: Double) {
    weatherPrefs.edit().putString("city", name).putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
        .putBoolean("hidden", false).remove("data").putLong("time", 0L).apply()
    renderWeather()
    refreshWeather(force = true)
    if (currentTab == TAB_SETTINGS) renderSettings()
}

internal fun MainActivity.setupWeather() {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply { hint = "Es. Torino, Bergamo, Perugia"; setSingleLine() }
    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
    MaterialAlertDialogBuilder(this)
        .setTitle("Meteo: la tua città")
        .setView(box)
        .setPositiveButton("Cerca") { _, _ ->
            val q = et.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) return@setPositiveButton
            Thread {
                val results = runCatching {
                    val json = java.net.URL("https://geocoding-api.open-meteo.com/v1/search?count=6&language=it&name=" + Uri.encode(q))
                        .openStream().use { it.readBytes().toString(Charsets.UTF_8) }
                    val arr = org.json.JSONObject(json).optJSONArray("results") ?: org.json.JSONArray()
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Triple(listOf(o.optString("name"), o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                            o.optDouble("latitude"), o.optDouble("longitude")) to o.optString("name")
                    }
                }.getOrDefault(emptyList())
                runOnUiThread {
                    if (results.isEmpty()) { toast("Città non trovata"); return@runOnUiThread }
                    MaterialAlertDialogBuilder(this).setTitle("Quale?")
                        .setItems(results.map { it.first.first }.toTypedArray()) { _, w ->
                            val (t, name) = results[w]
                            setWeatherPlace(name, t.second, t.third)
                        }.show()
                }
            }.start()
        }
        .setNeutralButton("Usa la mia posizione") { _, _ -> useMyLocationForWeather() }
        .setNegativeButton("Annulla", null)
        .show()
}

internal fun MainActivity.showWeatherDetail() {
    val data = weatherPrefs.getString("data", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return
    val cur = data.optJSONObject("current")
    val daily = data.optJSONObject("daily") ?: return
    val sb = StringBuilder()
    cur?.let {
        sb.append("Percepita ${Math.round(it.optDouble("apparent_temperature"))}° · vento ${Math.round(it.optDouble("wind_speed_10m"))} km/h · umidità ${it.optInt("relative_humidity_2m")}%\n\n")
    }
    val fmt = java.text.SimpleDateFormat("EEEE d", Locale.ITALIAN)
    val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val days = daily.optJSONArray("time") ?: return
    for (i in 0 until days.length()) {
        val name = if (i == 0) "Oggi" else runCatching { fmt.format(parse.parse(days.getString(i))!!).replaceFirstChar { it.uppercase() } }.getOrDefault("")
        val (e, l) = weatherInfo(daily.optJSONArray("weather_code")?.optInt(i) ?: -1)
        val hi = Math.round(daily.optJSONArray("temperature_2m_max")?.optDouble(i) ?: 0.0)
        val lo = Math.round(daily.optJSONArray("temperature_2m_min")?.optDouble(i) ?: 0.0)
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0
        sb.append("$e  $name: $l, $hi° / $lo°${if (rain >= 20) " · 💧$rain%" else ""}\n")
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Meteo · ${weatherPrefs.getString("city", "")}")
        .setMessage(sb.toString().trim())
        .setPositiveButton("OK", null)
        .setNeutralButton("Cambia città") { _, _ -> setupWeather() }
        .setNegativeButton("Nascondi") { _, _ -> weatherPrefs.edit().putBoolean("hidden", true).apply(); renderWeather(); toast("Meteo nascosto: lo riattivi dalle Impostazioni") }
        .show()
}
