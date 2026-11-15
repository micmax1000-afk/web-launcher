package com.weblauncher.app

import android.annotation.SuppressLint
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ======================= Ascolta → Musica: YouTube Music in its own page =======================

const val YT_MUSIC_HOME = "https://music.youtube.com/"

/** Pages that stay inside the music page (YouTube Music and the Google sign-in); anything else opens in the Browser. */
fun isMusicPageHost(url: String?): Boolean {
    val u = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
    val host = u.host?.lowercase() ?: return false
    val path = u.path.orEmpty()
    return host == "music.youtube.com" || host == "accounts.google.com" || host == "accounts.youtube.com" ||
        host == "consent.youtube.com" || host == "consent.google.com" || host == "www.youtube.com" && path.startsWith("/signin")
}

/** The YouTube Music page, created once and kept alive (so the music does not stop when you go elsewhere in the app). */
@SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.musicWebView(): WebView {
    musicWeb?.let { return it }
    val w = WebView(this)
    w.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        userAgentString = android.webkit.WebSettings.getDefaultUserAgent(this@musicWebView)
        loadWithOverviewMode = true; useWideViewPort = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
    w.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (!u.startsWith("http") || isMusicPageHost(u)) return false
            if (!request.isForMainFrame) return false
            openInBrowser(u); return true
        }
        override fun onPageFinished(view: WebView, url: String?) { musicBarUpdate?.invoke() }
    }
    w.webChromeClient = WebChromeClient()
    if (ytDocStartSupported()) runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, YouTube.script(ytConfig()), YouTube.ORIGINS) }
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching {
        androidx.webkit.WebViewCompat.addWebMessageListener(w, YouTube.BRIDGE, YouTube.ORIGINS) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            val m = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            if (m.optString("t") == "state") musicWebPlaying = m.optBoolean("playing")
        }
    }
    w.loadUrl(YT_MUSIC_HOME)
    musicWeb = w
    return w
}

/** The page that plays the music now: the YouTube Music page in Ascolta, or the Browser. */
internal fun MainActivity.activeWeb(): WebView = if (Music.webFromMusic) musicWeb ?: webView else webView

/** Opens YouTube Music inside Ascolta (optionally at [url]). */
internal fun MainActivity.openYtMusic(url: String? = null) {
    audioTab = "music"
    val w = musicWebView()
    if (url != null && url != w.url) w.loadUrl(url)
    if (currentTab != MainActivity.TAB_AUDIO) showTab(MainActivity.TAB_AUDIO) else renderAudio()
}

/** Builds the Musica screen: player bar, shortcuts, page buttons and the YouTube Music page. */
internal fun MainActivity.renderMusicBox(box: LinearLayout) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    box.removeAllViews()
    val w = musicWebView()
    (w.parent as? android.view.ViewGroup)?.removeView(w)

    // one thin row of buttons: the rest of the screen is YouTube Music (it has its own player bar at the bottom)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
    fun btn(t: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(palette.text); gravity = Gravity.CENTER; contentDescription = desc
        setPadding(dp(9), dp(6), dp(9), dp(6)); setOnClickListener { onClick() }
        row.addView(this)
    }
    fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(palette.text); setPadding(dp(12), dp(6), dp(12), dp(6))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) }
        setOnClickListener { onClick() }
        row.addView(this)
    }
    pill("‹ Ascolta") { audioTab = "foryou"; onAudioShown() }
    btn("‹", "Pagina precedente") { if (w.canGoBack()) w.goBack() }
    btn("⟳", "Ricarica") { w.reload() }
    btn("🏠", "Pagina iniziale di YouTube Music") { w.loadUrl(YT_MUSIC_HOME) }
    val star = btn("☆", "Preferito") {
        val url = w.url.orEmpty()
        if (!url.contains("music.youtube.com")) return@btn
        val st = Music.web
        val t = (if (Music.webFromMusic) st?.title else null)?.let { Music.cleanTitle(it) }?.ifBlank { null } ?: Music.cleanTitle(w.title.orEmpty()).ifBlank { "YouTube Music" }
        val on = WebMedia.toggleFavorite(this, WebMedia.Item(url, t, st?.artist.orEmpty(), st?.art.orEmpty(), true, System.currentTimeMillis()))
        toast(if (on) "⭐ Aggiunto ai preferiti" else "Tolto dai preferiti"); musicBarUpdate?.invoke()
    }
    btn("⭐", "Preferiti e recenti") { musicFavoritesSheet() }
    btn("🎤", "Chiedi a voce") { requestMicAndListen() }
    btn("⛶", "Lettore a schermo intero") { if (nowPlaying() != null) showNowPlayingFull() else toast("Fai partire prima una canzone") }
    box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2))

    // the page itself
    box.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))

    val update = { star.text = if (WebMedia.isFavorite(this, w.url.orEmpty())) "★" else "☆" }
    musicBarUpdate = { if (currentTab == MainActivity.TAB_AUDIO && audioTab == "music") update() }
    update()
}

/** Favourites and recents of YouTube Music, as a sheet over the page: one tap and it plays. */
internal fun MainActivity.musicFavoritesSheet() {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val d = resources.displayMetrics.density
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (24 * d).toInt()) }
    fun isMusic(i: WebMedia.Item) = i.music || i.url.contains("music.youtube.com")
    val favs = WebMedia.favorites(this).filter { isMusic(it) }
    val recents = WebMedia.recents(this).filter { r -> isMusic(r) && Music.cleanTitle(r.title).isNotBlank() && favs.none { WebMedia.key(it.url) == WebMedia.key(r.url) } }
    fun open(url: String) { sheet.dismiss(); openYtMusic(url) }
    sectionLabel(body, "⭐ PREFERITI")
    if (favs.isEmpty()) hintLabel(body, "Ancora nessuno: mentre ascolti tocca ☆ accanto all'indirizzo.")
    favs.take(20).forEach { i -> mediaRow(body, i.art.ifBlank { null }, Music.cleanTitle(i.title).ifBlank { i.title }, i.sub.ifBlank { "YouTube Music" },
        onLong = { MaterialAlertDialogBuilder(this).setTitle(i.title).setItems(arrayOf("Togli dai preferiti")) { _, _ -> WebMedia.toggleFavorite(this, i); sheet.dismiss(); musicFavoritesSheet() }.show() }) { open(i.url) } }
    sectionLabel(body, "🕘 ASCOLTATI DI RECENTE")
    if (recents.isEmpty()) hintLabel(body, "Qui ritrovi quello che hai ascoltato.")
    recents.take(15).forEach { i -> mediaRow(body, i.art.ifBlank { null }, Music.cleanTitle(i.title), listOf(i.sub, NewsAdapter.ago(i.time)).filter { it.isNotBlank() }.joinToString(" · "),
        onLong = { MaterialAlertDialogBuilder(this).setTitle(i.title).setItems(arrayOf("⭐ Aggiungi ai preferiti", "Togli dai recenti")) { _, w ->
            if (w == 0) WebMedia.toggleFavorite(this, i) else WebMedia.removeRecent(this, i.url); sheet.dismiss(); musicFavoritesSheet() }.show() }) { open(i.url) } }
    sheet.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(body) })
    sheet.show()
}
