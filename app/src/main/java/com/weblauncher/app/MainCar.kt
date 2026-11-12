package com.weblauncher.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A dark version of the main colour of a cover, for the background of the car screen. */
internal fun coverTint(b: Bitmap): Int {
    val px = runCatching { Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0) }.getOrDefault(0xFF5B3B8F.toInt())
    val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
    hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f); hsv[2] = hsv[2].coerceIn(0.28f, 0.5f)
    return Color.HSVToColor(hsv)
}

internal fun fmtMs(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Car mode, Spotify style: big cover and controls, the background takes the colour of the cover, a dock of shortcuts. */
internal fun MainActivity.showCarMode(startUrl: String? = null) {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val h = Handler(Looper.getMainLooper())
    val base = 0xFF0B0D10.toInt()
    var tint = 0xFF3B2F5C.toInt()
    val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val root = FrameLayout(this)          // the «Musica» tab
    fun paintBg() { outer.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tint, base, base)) }
    paintBg()
    val white = Color.WHITE; val muted = 0xFFC8C3DD.toInt()

    val clock = TextView(this).apply { textSize = 22f; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD) }
    val source = TextView(this).apply { textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER; maxLines = 1; letterSpacing = 0.05f }
    val exit = TextView(this).apply { text = "✕"; textSize = 26f; setTextColor(white); setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { dlg.dismiss() } }
    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat()) } }
        elevation = dp(12).toFloat()
    }
    val title = TextView(this).apply { textSize = 26f; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    val sub = TextView(this).apply { textSize = 17f; setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(10)) }
    val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(white); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
    }
    val tPos = TextView(this).apply { textSize = 12f; setTextColor(muted) }
    val tDur = TextView(this).apply { textSize = 12f; setTextColor(muted); gravity = Gravity.END }
    lateinit var refresh: () -> Unit
    var openWebInCar: ((String) -> Unit)? = null
    fun ctl(t: String, size: Float, action: String, big: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; gravity = Gravity.CENTER; setTextColor(if (big) Color.BLACK else white)
        if (big) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(white) }
        setOnClickListener { musicAction(action); h.postDelayed({ refresh() }, 500) }
    }
    val prev = ctl("⏮", 38f, "prev"); val play = ctl("▶", 40f, "toggle", big = true); val next = ctl("⏭", 38f, "next")
    fun dockItem(icon: String, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x22FFFFFF) }
        addView(TextView(this@showCarMode).apply { text = icon; textSize = 26f; gravity = Gravity.CENTER })
        addView(TextView(this@showCarMode).apply { text = label; textSize = 12f; setTextColor(white); gravity = Gravity.CENTER })
        setOnClickListener { onClick() }
    }
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    val dock = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(
            dockItem("🎤", "Voce") { requestMicAndListen() },
            dockItem("🎶", "Sorgenti") { carSources { url -> openWebInCar?.invoke(url) } },
            dockItem("🏠", "Casa") { goHome() },
            dockItem("📞", "Chiama") { runCatching { startActivity(Intent(Intent.ACTION_DIAL)) } },
            dockItem("🔊", "Volume") { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI) }
        ).forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }) }
    }

    var landscape: Boolean? = null
    fun build() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land == landscape) return
        landscape = land
        root.removeAllViews()
        listOf<View>(clock, source, cover, title, sub, bar, tPos, tDur, prev, play, next, dock).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val w = resources.displayMetrics.widthPixels; val hh = resources.displayMetrics.heightPixels
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(clock, LinearLayout.LayoutParams(dp(80), -2)); addView(source, LinearLayout.LayoutParams(0, -2, 1f)); addView(View(this@showCarMode), LinearLayout.LayoutParams(dp(80), 1))
        }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tPos, LinearLayout.LayoutParams(0, -2, 1f)); addView(tDur, LinearLayout.LayoutParams(0, -2, 1f)) }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            addView(prev, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(play, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginStart = dp(26); marginEnd = dp(26) })
            addView(next, LinearLayout.LayoutParams(dp(76), dp(76)))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(sub); addView(bar, LinearLayout.LayoutParams(-1, dp(6))); addView(times); addView(controls)
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)) }
        main.addView(head)
        if (land) {
            val size = (hh * 0.55).toInt()
            main.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(cover, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(28) })
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val size = minOf(w - dp(64), (hh * 0.42).toInt())
            main.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
            main.addView(cover, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) })
            main.addView(info)
            main.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        main.addView(dock)
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        cover.tag = null
    }
    var tintKey: Any? = null
    refresh = {
        clock.text = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date())
        val n = nowPlaying()
        if (n == null) {
            title.text = "Nessuna musica"; sub.text = "Tocca «Sorgenti» per scegliere cosa ascoltare"; source.text = "MODALITÀ AUTO"
            play.text = "▶"; bar.progress = 0; tPos.text = ""; tDur.text = ""
            showArtPublic(cover, null)
        } else {
            title.text = n.title; sub.text = n.sub.substringBeforeLast(" · ", n.sub).ifBlank { n.sub }
            source.text = "IN RIPRODUZIONE" + n.sourceLabel().takeIf { it.isNotBlank() }?.let { " · ${it.uppercase(Locale.ITALIAN)}" }.orEmpty()
            play.text = if (n.playing) "⏸" else "▶"
            if (n.durMs > 0) { bar.progress = (n.posMs * 1000 / n.durMs).toInt().coerceIn(0, 1000); tPos.text = fmtMs(n.posMs); tDur.text = fmtMs(n.durMs) }
            else { bar.progress = 0; tPos.text = if (n.source == "local" && n.posMs > 0) fmtMs(n.posMs) else ""; tDur.text = "" }
            showArtPublic(cover, n)
        }
        // background colour from the cover (once per cover)
        val bmp = (cover.drawable as? BitmapDrawable)?.bitmap
        if (bmp != null && tintKey !== bmp) { tintKey = bmp; tint = coverTint(bmp); paintBg() }
        else if (n == null && tintKey != null) { tintKey = null; tint = 0xFF3B2F5C.toInt(); paintBg() }
    }
    val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val before = landscape; build(); if (before != landscape) refresh() }
    build()

    // ---------- tabs: Musica · Web · Mappe ----------
    val webHost = webView.parent as? ViewGroup
    val webIndex = webHost?.indexOfChild(webView) ?: 0
    val webParams = webView.layoutParams
    val webWasVisible = webView.visibility
    val content = FrameLayout(this)
    val webPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    val mapsPane = android.widget.ScrollView(this).apply { visibility = View.GONE }
    content.addView(root, FrameLayout.LayoutParams(-1, -1)); content.addView(webPane, FrameLayout.LayoutParams(-1, -1)); content.addView(mapsPane, FrameLayout.LayoutParams(-1, -1))

    // small player shown under Web and Mappe
    val miniTitle = TextView(this).apply { textSize = 15f; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val miniSub = TextView(this).apply { textSize = 12f; setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val miniCover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val miniPlay = TextView(this).apply { textSize = 30f; setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(8), 0); setOnClickListener { musicAction("toggle"); h.postDelayed({ refresh() }, 500) } }
    val miniNext = TextView(this).apply { text = "⏭"; textSize = 26f; setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { musicAction("next"); h.postDelayed({ refresh() }, 600) } }
    val mini = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(10), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF2A2340.toInt()) }
        addView(miniCover, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@showCarMode).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); addView(miniTitle); addView(miniSub) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(miniPlay); addView(miniNext)
    }
    val musicRefresh = refresh
    refresh = {
        musicRefresh()
        val n = nowPlaying()
        miniTitle.text = n?.title ?: "Nessuna musica"; miniSub.text = n?.sub.orEmpty(); miniPlay.text = if (n?.playing == true) "⏸" else "▶"
        showArtPublic(miniCover, n)
    }

    // --- Web: favourite sites, and the page itself inside the car screen ---
    val carPrefsWeb = getSharedPreferences("wl_car", android.content.Context.MODE_PRIVATE)
    fun sites(): List<Pair<String, String>> = carPrefsWeb.getString("sites", null)?.split('\n')?.mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        ?: listOf("🗺️ Google Maps" to "https://www.google.com/maps", "🔴 YouTube Music" to "https://music.youtube.com/", "▶️ YouTube" to "https://m.youtube.com/", "📻 RaiPlay Sound" to "https://www.raiplaysound.it/")
    fun saveSites(l: List<Pair<String, String>>) = carPrefsWeb.edit().putString("sites", l.joinToString("\n") { it.first + "|" + it.second }).apply()
    val favGrid = android.widget.GridLayout(this).apply { columnCount = 2 }
    val favScroll = android.widget.ScrollView(this).apply { addView(favGrid) }
    val webFrame = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.WHITE) }
    val addr = TextView(this).apply { textSize = 14f; setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF1C2230.toInt()) } }
    val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
    lateinit var showFavs: () -> Unit
    fun showPage(url: String?) {
        if (url != null) openInBrowser(url)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webFrame.addView(webView, FrameLayout.LayoutParams(-1, -1)); webView.visibility = View.VISIBLE
        favScroll.visibility = View.GONE; webFrame.visibility = View.VISIBLE; addr.visibility = View.VISIBLE; navRow.visibility = View.VISIBLE
    }
    fun askSearch() {
        val et = field("Cerca o scrivi un indirizzo", null)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Cerca").setView(form(et)).setPositiveButton("Vai") { _, _ ->
            val q = et.text.toString().trim(); if (q.isNotEmpty()) showPage(if (looksLikeUrl(q)) normalizeUrl(q) else searchUrl(q))
        }.setNegativeButton("Annulla", null).show()
    }
    fun tileView(label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null) = TextView(this).apply {
        text = label.replaceFirst(" ", "\n"); textSize = 18f; setTextColor(white); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF1C2230.toInt()) }
        layoutParams = android.widget.GridLayout.LayoutParams().apply { width = 0; height = dp(112); columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f); setMargins(dp(6), dp(6), dp(6), dp(6)) }
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    showFavs = {
        favGrid.removeAllViews()
        if (browserHasPage) favGrid.addView(tileView("↩️ Torna alla pagina", { showPage(null) }))
        sites().forEach { (name, url) ->
            favGrid.addView(tileView(name, { showPage(url) }, {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(name).setItems(arrayOf("Togli dai preferiti dell'auto")) { _, _ -> saveSites(sites().filter { it.second != url }); showFavs() }.show()
            }))
        }
        favGrid.addView(tileView("🔍 Cerca", { askSearch() }))
        favGrid.addView(tileView("➕ Aggiungi sito", {
            val n = field("Nome (es. Radio Deejay)", null); val u = field("Indirizzo (es. deejay.it)", null)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Sito per l'auto").setView(form(n, u)).setPositiveButton("Salva") { _, _ ->
                val name = n.text.toString().trim(); val url = u.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) toast("Scrivi nome e indirizzo") else { saveSites(sites() + ("🌐 $name" to normalizeUrl(url))); showFavs() }
            }.setNegativeButton("Annulla", null).show()
        }))
        webFrame.visibility = View.GONE; addr.visibility = View.GONE; navRow.visibility = View.GONE; favScroll.visibility = View.VISIBLE
    }
    fun navBtn(t: String, onClick: () -> Unit) = navRow.addView(TextView(this).apply {
        text = t; textSize = 26f; setTextColor(white); gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF1C2230.toInt()) }
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
    navBtn("‹") { if (webView.canGoBack()) webView.goBack() else showFavs() }
    navBtn("⟳") { webView.reload() }
    navBtn("⭐") { showFavs() }
    navBtn("🔍") { askSearch() }
    webPane.setPadding(dp(10), 0, dp(10), 0)
    webPane.addView(addr, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    webPane.addView(favScroll, LinearLayout.LayoutParams(-1, 0, 1f)); webPane.addView(webFrame, LinearLayout.LayoutParams(-1, 0, 1f)); webPane.addView(navRow)

    // --- Mappe: big rows that start the navigation ---
    val mapsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12)) }
    fun mapRow(label: String, sub: String?, onClick: () -> Unit) = mapsList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(84); setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF1C2230.toInt()) }
        addView(TextView(this@showCarMode).apply { text = label; textSize = 21f; setTextColor(white) })
        if (sub != null) addView(TextView(this@showCarMode).apply { text = sub; textSize = 14f; setTextColor(0xFF9AA3B8.toInt()) })
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    fun navTo(q: String) {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(q))).setPackage("com.google.android.apps.maps")
        try { startActivity(i) } catch (_: Exception) { showPage("https://www.google.com/maps/dir/?api=1&destination=" + android.net.Uri.encode(q)) }
    }
    fun nearby(q: String) {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(q))).setPackage("com.google.android.apps.maps")
        try { startActivity(i) } catch (_: Exception) { showPage("https://www.google.com/maps/search/" + android.net.Uri.encode(q)) }
    }
    mapRow("🏠 Portami a casa", carPrefs.getString("home", null) ?: "Tocca per impostare l'indirizzo") { goHome() }
    mapRow("📍 Cerca una destinazione", "Scrivi un indirizzo o un luogo") {
        val et = field("Dove vuoi andare?", null)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Destinazione").setView(form(et)).setPositiveButton("Naviga") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { navTo(it) } }.setNegativeButton("Annulla", null).show()
    }
    mapRow("🚗 Dove ho parcheggiato", null) { Places.parking(this)?.let { navigateTo(it.lat, it.lon, true) } ?: toast("Nessun parcheggio salvato") }
    mapRow("⛽ Distributore vicino", null) { nearby("distributore di benzina") }
    mapRow("🅿️ Parcheggio vicino", null) { nearby("parcheggio") }
    mapRow("🔌 Colonnina di ricarica", null) { nearby("colonnina ricarica auto elettrica") }
    mapsPane.addView(mapsList)

    // --- the tab row ---
    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(4), dp(6)) }
    val tabViews = mutableListOf<TextView>()
    fun select(i: Int) {
        root.visibility = if (i == 0) View.VISIBLE else View.GONE
        webPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        mapsPane.visibility = if (i == 2) View.VISIBLE else View.GONE
        mini.visibility = if (i == 0) View.GONE else View.VISIBLE
        tabViews.forEachIndexed { k, v ->
            v.setTextColor(if (k == i) Color.BLACK else white); v.setTypeface(null, if (k == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            v.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (k == i) white else 0xFF1C2230.toInt()) }
        }
        if (i == 1 && webFrame.visibility != View.VISIBLE) showFavs()
    }
    listOf("🎵 Musica", "🌐 Web", "🧭 Mappe").forEachIndexed { i, label ->
        val tv = TextView(this).apply { text = label; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { select(i) } }
        tabViews += tv; tabs.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
    }
    tabs.addView(exit)
    openWebInCar = { url -> select(1); showPage(url) }
    outer.addView(tabs); outer.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
    outer.addView(mini, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(6), dp(10), dp(10)) })
    select(0)
    val addrTick = object : Runnable { override fun run() { if (addr.visibility == View.VISIBLE) addr.text = "🔒 " + (webView.url?.let { displayUrl(it) } ?: ""); h.postDelayed(this, 1500) } }
    h.post(addrTick)

    dlg.setContentView(outer)
    dlg.setOnKeyListener { _, code, ev ->            // «indietro» del telefono: prima la pagina, poi i preferiti, poi si esce
        if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP && webPane.visibility == View.VISIBLE && webFrame.visibility == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack() else showFavs(); true
        } else false
    }
    dlg.window?.apply {
        addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusBarColor = base; navigationBarColor = base
        setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    dlg.setOnDismissListener {
        h.removeCallbacksAndMessages(null)
        // the page goes back to the Browser, where it was
        if (webView.parent !== webHost) { (webView.parent as? ViewGroup)?.removeView(webView); webHost?.addView(webView, webIndex.coerceAtMost(webHost.childCount), webParams) }
        webView.visibility = webWasVisible
        runCatching { updateBrowserBody() }
    }
    dlg.show()
    h.post(tick)
    startUrl?.let { u -> h.postDelayed({ openWebInCar?.invoke(u) }, 600) }
}

/** Car mode → «Sorgenti»: where to listen from, in big rows. */
private fun MainActivity.carSources(openWeb: (String) -> Unit) {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
    list.addView(TextView(this).apply {
        text = "‹  Sorgenti"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dp(14))
        setOnClickListener { dlg.dismiss() }
    })
    fun row(icon: String, label: String, sub: String?, onClick: () -> Unit) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(86); setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF1C2230.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        addView(TextView(this@carSources).apply { text = icon; textSize = 32f; setPadding(0, 0, dp(16), 0) })
        addView(LinearLayout(this@carSources).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@carSources).apply { text = label; textSize = 21f; setTextColor(Color.WHITE) })
            if (sub != null) addView(TextView(this@carSources).apply { text = sub; textSize = 14f; setTextColor(0xFF9AA3B8.toInt()); maxLines = 1 })
        })
        setOnClickListener { onClick() }
    })
    // web sources need the screen to choose a song: close the car screen, the music then shows up here again
    fun web(url: String) { dlg.dismiss(); openWeb(url) }
    row("🔴", "YouTube Music", "Si apre qui, nella scheda Web") { web("https://music.youtube.com/") }
    row("▶️", "YouTube", "Si apre qui, nella scheda Web") { web("https://m.youtube.com/") }
    if (Music.installed(this, Music.SPOTIFY)) row("🟢", "Spotify", "Si apre l'app") { dlg.dismiss(); openMusicApp("spotify") }
    audio.favoriteStations().take(4).forEach { s -> row("📻", s.name, "Radio") { playRadio(s); dlg.dismiss() } }
    if (audio.favoriteStations().isEmpty()) row("📻", "Radio", "Aggiungi le preferite (⭐) in Ascolta → Radio") { toast("Aggiungi una radio preferita in Ascolta → Radio") }
    val eps = if (episodes.isEmpty()) audio.cachedEpisodes() else episodes
    val resume = (AudioStore.recent(this) + eps).firstOrNull { !AudioStore.isFinished(this, it.id) }
    if (resume != null) row("🎙", "Riprendi podcast", resume.title) { playEpisode(resume); dlg.dismiss() }
    if (androidx.core.content.ContextCompat.checkSelfPermission(this, musicPerm()) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        row("🎧", "Musica del telefono", "In ordine casuale") {
            val all = songs ?: loadSongs().also { songs = it }
            if (all.isEmpty()) toast("Nessuna canzone sul telefono") else { playSongs(all, all.indices.random(), shuffle = true); dlg.dismiss() }
        }
    }
    dlg.setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(0xFF0B0D10.toInt()); addView(list) })
    dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    dlg.show()
}
