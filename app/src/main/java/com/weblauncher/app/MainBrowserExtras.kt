package com.weblauncher.app

import android.content.Context.INPUT_METHOD_SERVICE
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

// ======================= Apps with fingerprint lock =======================

internal fun MainActivity.openApp(item: AppItem) {
    val host = hostOf(normalizeUrl(item.url))
    if (host != null && limitFor(host) > 0) {
        val used = usageMap(today)[host] ?: 0L
        val allowed = (limitFor(host) + usagePrefs.getInt("extra_${today}_$host", 0)) * 60_000L
        if (used >= allowed) {
            MaterialAlertDialogBuilder(this).setTitle("⏱ Limite raggiunto")
                .setMessage("Oggi hai già usato ${item.name} per ${used / 60_000} minuti (limite ${limitFor(host)} min).")
                .setPositiveButton("Ok") { _, _ -> }
                .setNegativeButton("Apri per 15 min") { _, _ ->
                    usagePrefs.edit().putInt("extra_${today}_$host", usagePrefs.getInt("extra_${today}_$host", 0) + 15).apply()
                    noteUse(item); openInBrowser(normalizeUrl(item.url))
                }.show()
            return
        }
    }
    val launch = {
        noteUse(item)
        openInBrowser(normalizeUrl(item.url))
    }
    if (item.locked) authenticate("Apri ${item.name}", strict = false) { launch() } else launch()
}

internal fun MainActivity.hostOf(url: String?): String? = try {
    Uri.parse(url.orEmpty()).host?.lowercase()?.removePrefix("www.")
} catch (_: Exception) { null }

internal fun MainActivity.isLockedUrl(url: String?): Boolean {
    val host = hostOf(url) ?: return false
    return apps.any { a ->
        if (!a.locked) return@any false
        val h = hostOf(normalizeUrl(a.url)) ?: return@any false
        host == h || host.endsWith(".$h")
    }
}

internal fun MainActivity.authenticate(title: String, strict: Boolean = true, onFail: () -> Unit = {}, onOk: () -> Unit) {
    val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val can = androidx.biometric.BiometricManager.from(this).canAuthenticate(auth)
    if (can != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
        if (strict) {
            toast("Imposta prima un blocco schermo (PIN o impronta) nel telefono")
            onFail()
        } else {
            toast("Blocco schermo non attivo: app aperta senza impronta")
            onOk()
        }
        return
    }
    val prompt = androidx.biometric.BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                onOk()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail()
            }
        }
    )
    val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("Usa impronta, volto o PIN")
        .setAllowedAuthenticators(auth)
        .build()
    prompt.authenticate(info)
}

// ======================= Hide bars while scrolling =======================

internal fun MainActivity.onWebScroll(y: Int, oldY: Int) {
    if (!hideBarsOn || currentTab != TAB_BROWSER || webAddress.isFocused || customView != null) return
    val d = resources.displayMetrics.density
    val dy = y - oldY
    if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) scrollAccum = 0
    scrollAccum += dy
    when {
        !barsHidden && scrollAccum > 40 * d && y > 140 * d -> hideBars()
        barsHidden && (scrollAccum < -40 * d || y < 8 * d) -> showBars()
    }
}

internal fun MainActivity.hideBars() {
    barsHidden = true
    scrollAccum = 0
    browserToolbar.isVisible = false
    bottomBar.isVisible = false
}

internal fun MainActivity.showBars() {
    scrollAccum = 0
    if (!barsHidden) return
    barsHidden = false
    browserToolbar.isVisible = true
    bottomBar.isVisible = true
}

// ======================= Address bar suggestions =======================

internal fun MainActivity.leaveAddressBar() {
    webAddress.clearFocus()
    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    imm.hideSoftInputFromWindow(webAddress.windowToken, 0)
    hideSuggestions()
}

internal fun MainActivity.hideSuggestions() {
    if (!suggestPanel.isVisible) return
    suggestPanel.isVisible = false
    findViewById<View>(R.id.webViewHost).isVisible = true
    updateBrowserBody()
}

internal fun MainActivity.suggestEndpoint(): String? = when (repo.getSearchEngine()) {
    SearchEngine.GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&hl=it&ie=utf-8&oe=utf-8&q="
    SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?type=list&q="
    SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query="
    else -> null
}

internal fun MainActivity.showSuggestions(q: String, token: Int) {
    if (token != suggestToken || !webAddress.isFocused) return
    val lq = q.lowercase(Locale.ROOT)
    val appHits = apps.filter {
        it.name.lowercase(Locale.ROOT).contains(lq) || it.url.lowercase(Locale.ROOT).contains(lq)
    }.take(3)
    renderSuggestions(q, appHits, emptyList(), emptyList())
    val appUrls = appHits.map { normalizeUrl(it.url) }.toSet()
    val endpoint = suggestEndpoint()
    val wantRemote = repo.searchSuggestions() && endpoint != null && !looksLikeUrl(q)
    Thread {
        val histHits = repo.loadHistory().filter {
            it.title.lowercase(Locale.ROOT).contains(lq) || it.url.lowercase(Locale.ROOT).contains(lq)
        }.distinctBy { it.url }.filter { it.url !in appUrls }.take(4)
        runOnUiThread {
            if (token == suggestToken && webAddress.isFocused) renderSuggestions(q, appHits, histHits, emptyList())
        }
        if (!wantRemote) return@Thread
        val remote = try {
            val conn = java.net.URL(endpoint + Uri.encode(q)).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("User-Agent", mobileUserAgent)
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val arr = org.json.JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() && it != q }.take(5)
        } catch (_: Exception) { emptyList() }
        runOnUiThread {
            if (token == suggestToken && webAddress.isFocused) renderSuggestions(q, appHits, histHits, remote)
        }
    }.start()
}

internal fun MainActivity.renderSuggestions(q: String, appHits: List<AppItem>, histHits: List<HistoryEntry>, remote: List<String>) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    suggestPanel.setBackgroundColor(palette.bg)
    suggestList.removeAllViews()
    fun row(icon: Int, title: String, sub: String?, tint: Int = palette.textMuted, onClick: () -> Unit) {
        val r = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { leaveAddressBar(); onClick() }
        }
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(tint)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(18) }
        })
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(palette.text)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(palette.textMuted)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        r.addView(box)
        suggestList.addView(r)
    }
    if (looksLikeUrl(q)) row(R.drawable.ic_web_forward, q, "Vai al sito") { openInBrowser(normalizeUrl(q)) }
    else {
        row(R.drawable.ic_search, q, "Cerca con ${repo.getSearchEngine().label}") { openInBrowser(searchUrl(q)) }
        row(R.drawable.ic_ai, "Chiedi all'AI", q, com.google.android.material.color.MaterialColors.getColor(
            this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())) { askAi(q) }
    }
    appHits.forEach { a ->
        row(R.drawable.ic_web_star_filled, a.name, displayUrl(normalizeUrl(a.url)),
            ContextCompat.getColor(this, R.color.star)) { openApp(a) }
    }
    histHits.forEach { h -> row(R.drawable.ic_web_history, recentLabel(RecentSite(h.title, h.url)), displayUrl(h.url)) { openInBrowser(h.url) } }
    remote.forEach { r -> row(R.drawable.ic_search, r, null) { openInBrowser(searchUrl(r)) } }
    suggestPanel.isVisible = true
    recentsPanel.isVisible = false
    findViewById<View>(R.id.webViewHost).isVisible = false
}

// ======================= Default browser =======================

internal fun MainActivity.isDefaultBrowser(): Boolean = try {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        rm?.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) == true
    } else {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName == packageName
    }
} catch (_: Exception) { false }

internal fun MainActivity.requestDefaultBrowser() {
    if (isDefaultBrowser()) { toast("Web Launcher è già il browser predefinito"); return }
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                defaultBrowserLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER))
                return
            }
        }
        defaultBrowserLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    } catch (_: Exception) {
        toast("Impostazioni Android → App → App predefinite → Browser")
    }
}

// ======================= Translate / text size =======================

internal fun MainActivity.translateCurrentPage() {
    val url = webView.url?.takeIf { it.startsWith("http") } ?: return
    if (url.contains("translate.goog") || url.contains("translate.google")) { toast("Pagina già tradotta"); return }
    openInBrowser("https://translate.google.com/translate?sl=auto&tl=it&u=" + Uri.encode(url))
}

internal fun MainActivity.showTextZoomDialog() {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val start = (repo.textZoom() / 10 * 10).coerceIn(50, 200)
    val label = TextView(this).apply {
        text = "$start%"
        textSize = 18f
        gravity = android.view.Gravity.CENTER
        setTextColor(AppRepository.palette(this@showTextZoomDialog).text)
    }
    val slider = com.google.android.material.slider.Slider(this).apply {
        valueFrom = 50f; valueTo = 200f; stepSize = 10f; value = start.toFloat()
        addOnChangeListener { _, v, _ ->
            label.text = "${v.toInt()}%"
            webView.settings.textZoom = v.toInt()
        }
    }
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
        addView(label)
        addView(slider)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Dimensione testo")
        .setView(box)
        .setPositiveButton("OK") { _, _ ->
            repo.setTextZoom(slider.value.toInt())
            if (currentTab == TAB_SETTINGS) renderSettings()
        }
        .setNeutralButton("100%") { _, _ ->
            repo.setTextZoom(100); webView.settings.textZoom = 100
            if (currentTab == TAB_SETTINGS) renderSettings()
        }
        .setNegativeButton("Annulla") { _, _ -> webView.settings.textZoom = repo.textZoom() }
        .setOnCancelListener { webView.settings.textZoom = repo.textZoom() }
        .show()
}
