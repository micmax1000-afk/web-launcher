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

    // 1) the player bar (only when YouTube Music is playing something)
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(6), dp(8)); setBackgroundColor(0xFF1E293B.toInt())
        setOnClickListener { showNowPlayingFull() }
    }
    val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } } }
    val title = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val sub = TextView(this).apply { textSize = 12f; setTextColor(0xFFB8BCC8.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    fun ctl(t: String, action: String) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { musicAction(action); uiHandler.postDelayed({ musicBarUpdate?.invoke() }, 500) }
    }
    val play = ctl("⏸", "toggle")
    bar.addView(cover, LinearLayout.LayoutParams(dp(42), dp(42)))
    bar.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0); addView(title); addView(sub) }, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(ctl("⏮", "prev")); bar.addView(play); bar.addView(ctl("⏭", "next"))
    box.addView(bar, LinearLayout.LayoutParams(-1, -2))

    // 2) shortcuts
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    fun chip(t: String, onClick: () -> Unit) = chips.addView(TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(palette.text); setPadding(dp(13), dp(7), dp(13), dp(7))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    })
    chip("🎤 Voce") { requestMicAndListen() }
    chip("⭐ Preferiti") { musicFavoritesSheet() }
    chip("🕘 Recenti") { musicFavoritesSheet() }
    chip("🔊 Volume") {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI)
    }
    box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2))

    // 3) page buttons: back, reload, address, favourite
    val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(4)) }
    fun navBtn(t: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 19f; setTextColor(palette.text); gravity = Gravity.CENTER; contentDescription = desc
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { onClick() }
    }
    val address = TextView(this).apply {
        textSize = 12.5f; setTextColor(palette.textMuted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(palette.card) }
        setOnClickListener { musicSearchDialog() }
    }
    val star = navBtn("☆", "Preferito") {
        val url = w.url.orEmpty()
        if (!url.contains("music.youtube.com")) return@navBtn
        val st = Music.web
        val t = (if (Music.webFromMusic) st?.title else null)?.let { Music.cleanTitle(it) }?.ifBlank { null } ?: Music.cleanTitle(w.title.orEmpty()).ifBlank { "YouTube Music" }
        val on = WebMedia.toggleFavorite(this, WebMedia.Item(url, t, st?.artist.orEmpty(), st?.art.orEmpty(), true, System.currentTimeMillis()))
        toast(if (on) "⭐ Aggiunto ai preferiti" else "Tolto dai preferiti"); musicBarUpdate?.invoke()
    }
    nav.addView(navBtn("‹", "Indietro") { if (w.canGoBack()) w.goBack() })
    nav.addView(navBtn("⟳", "Ricarica") { w.reload() })
    nav.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
    nav.addView(navBtn("🏠", "Pagina iniziale") { w.loadUrl(YT_MUSIC_HOME) })
    nav.addView(star)
    box.addView(nav, LinearLayout.LayoutParams(-1, -2))

    // 4) the page itself
    box.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))

    val update = {
        val n = runCatching { nowPlaying() }.getOrNull()
        val mine = n != null && n.source == "web" && Music.webFromMusic && n.title.isNotBlank()
        bar.isVisible = mine
        if (mine) {
            title.text = n!!.title; sub.text = n.sub.ifBlank { "YouTube Music" }
            play.text = if (n.playing) "⏸" else "▶"
            showArtPublic(cover, n!!)
        }
        val u = w.url.orEmpty()
        address.text = "🔎  " + Uri.parse(u).let { (it.host.orEmpty() + it.path.orEmpty()).removeSuffix("/") }.ifBlank { "music.youtube.com" }
        star.text = if (WebMedia.isFavorite(this, u)) "⭐" else "☆"
    }
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
