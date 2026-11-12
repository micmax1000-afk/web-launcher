package com.weblauncher.app

import android.content.Intent
import coil.load
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Opens a link in the music app (Spotify / YouTube Music open their own links); falls back to the browser. */
internal fun MainActivity.openMusicLink(url: String) {
    if (Music.appFor(url) == "youtube" || Music.appFor(url) == "ytmusic" && Music.ytInApp(this)) { openInBrowser(url); return }
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Music.packageOf(Music.appFor(url))?.takeIf { Music.installed(this, it) }?.let { i.setPackage(it) }
    try { startActivity(i) } catch (_: Exception) { if (url.startsWith("http")) openInBrowser(url) else toast("Non trovo l'app per aprire questo link") }
}

internal fun MainActivity.openMusicApp(app: String?) {
    if (app == "youtube") { openInBrowser("https://m.youtube.com/"); return }
    if (app == "ytmusic" && Music.ytInApp(this)) { openInBrowser("https://music.youtube.com/"); return }
    val pkg = Music.packageOf(app) ?: return toast("Nessuna app di musica trovata")
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: return toast("${Music.appName(app)} non è installata")
    startActivity(i)
}

internal fun MainActivity.askMusicAccess() {
    MaterialAlertDialogBuilder(this).setTitle("Mostrare il brano in corso")
        .setMessage("I tasti pausa, avanti e indietro funzionano già, anche a schermo spento. Il permesso «Accesso alle notifiche» serve solo per vedere titolo, artista e copertina: non legge le altre notifiche.\n\nSe Android risponde «accesso negato»:\n1. tocca «Info app»;\n2. in alto a destra tocca i tre puntini ⋮;\n3. scegli «Consenti impostazioni con restrizioni» e conferma;\n4. torna qui e tocca «Accesso notifiche», poi attiva Web Launcher.")
        .setPositiveButton("Info app") { _, _ -> runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }.onFailure { toast("Impostazioni non disponibili") } }
        .setNeutralButton("Accesso notifiche") { _, _ -> runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { toast("Impostazioni non disponibili") } }
        .setNegativeButton("Non ora", null).show()
}

/** What is playing now, wherever it comes from: YouTube / YouTube Music in the app's browser, the app's own player, or another app. */
data class NowView(val title: String, val sub: String, val artUrl: String?, val artBmp: android.graphics.Bitmap?, val playing: Boolean, val source: String, val posMs: Long = 0, val durMs: Long = 0, val url: String = "", val music: Boolean = true)

internal fun MainActivity.nowPlaying(): NowView? {
    val w = Music.web
    if (w != null && Music.webActive())
        return NowView(w.title.ifBlank { "Video in riproduzione" }, listOf(w.artist, if (w.music) "YouTube Music" else "YouTube").filter { it.isNotBlank() }.joinToString(" · "), w.art.takeIf { it.startsWith("http") }, null, w.playing, "web", w.posMs + if (w.playing) System.currentTimeMillis() - w.at else 0L, w.durMs, runCatching { webView.url }.getOrNull().orEmpty(), w.music)
    val c = controller
    val item = c?.currentMediaItem
    if (c != null && item != null && c.playbackState != androidx.media3.common.Player.STATE_IDLE) {
        val md = item.mediaMetadata
        return NowView(md.title?.toString().orEmpty().ifBlank { "In riproduzione" }, md.artist?.toString().orEmpty(), md.artworkUri?.toString(), null, c.isPlaying, "local", c.currentPosition, c.duration.takeIf { it > 0 } ?: 0L)
    }
    val n = Music.current(this) ?: return null
    return NowView(n.title, listOf(n.artist, n.app).filter { it.isNotBlank() }.joinToString(" · "), null, n.art, n.playing, "system", n.posMs, n.durMs)
}

/** Pause / play / next / previous on whatever is playing. */
internal fun MainActivity.musicAction(action: String) {
    val w = Music.webControl
    if (w != null && Music.webActive()) { w(action); return }
    val c = controller
    if (c != null && c.currentMediaItem != null && c.playbackState != androidx.media3.common.Player.STATE_IDLE) {
        when (action) { "toggle" -> if (c.isPlaying) c.pause() else c.play(); "pause" -> c.pause(); "play" -> c.play(); "next" -> c.seekToNext(); "prev" -> c.seekToPrevious() }
        return
    }
    Music.control(this, action)
}

/** Shows a cover (bitmap or link) in [iv], loading links only when they change. */
private fun MainActivity.showArt(iv: ImageView, n: NowView?) {
    val key = n?.artUrl ?: n?.artBmp?.hashCode()?.toString() ?: ""
    if (iv.tag == key) return
    iv.tag = key
    when {
        n?.artBmp != null -> iv.setImageBitmap(n.artBmp)
        n?.artUrl != null -> iv.load(n.artUrl) { error(R.drawable.ic_nav_audio) }
        else -> iv.setImageResource(R.drawable.ic_nav_audio)
    }
}

/** A small player: cover, title, artist and three buttons. Refreshes itself while it is on screen. */
class MusicBar(private val a: MainActivity, private val large: Boolean = false) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val h = Handler(Looper.getMainLooper())
    private val palette = AppRepository.palette(a)
    private val cover = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(dp(if (large) 84 else 44), dp(if (large) 84 else 44)) }
    private val title = TextView(a).apply { textSize = if (large) 17f else 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = TextView(a).apply { textSize = if (large) 14f else 12f; setTextColor(palette.textMuted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private fun btn(t: String, action: String) = TextView(a).apply {
        text = t; textSize = if (large) 30f else 22f; gravity = Gravity.CENTER; setTextColor(palette.text); setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { a.musicAction(action); h.postDelayed({ refresh() }, 500) }
    }
    private val prev = btn("⏮", "prev"); private val play = btn("⏸", "toggle"); private val next = btn("⏭", "next")
    private val full = TextView(a).apply {
        text = "⛶"; textSize = if (large) 26f else 20f; gravity = Gravity.CENTER; setTextColor(palette.text); setPadding(dp(10), dp(4), dp(6), dp(4))
        contentDescription = "Schermo intero"; setOnClickListener { a.showNowPlayingFull() }
    }
    private val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(6) }; addView(title); addView(sub) }
    private val headRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(cover); addView(texts) }
    private val controls = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; addView(prev); addView(play); addView(next); if (large) addView(full) }
    val view = LinearLayout(a).apply {
        orientation = if (large) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.card) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) }
        if (large) { addView(headRow); addView(controls) } else { addView(headRow, LinearLayout.LayoutParams(0, -2, 1f)); addView(controls) }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 2000) } }
            override fun onViewAttachedToWindow(v: View) { h.post(tick) }
            override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(tick) }
        })
    }

    fun refresh() {
        val n = a.nowPlaying()
        controls.visibility = View.VISIBLE
        if (n == null) {
            cover.visibility = View.GONE; play.text = "⏯"
            if (!Music.hasAccess(a)) { title.text = "🎵 Nessuna musica dall'app"; sub.text = "Apri YouTube Music o YouTube qui sotto: titolo e tasti compaiono qui" }
            else { title.text = "🎵 Nessuna musica in corso"; sub.text = "Tocca per aprire ${Music.appName(Music.defaultApp(a))}" }
            view.setOnClickListener { a.openMusicApp(Music.defaultApp(a)) }; return
        }
        cover.visibility = View.VISIBLE
        a.showArtPublic(cover, n)
        title.text = n.title; sub.text = n.sub
        play.text = if (n.playing) "⏸" else "▶"
        view.setOnClickListener { a.showNowPlayingFull() }
    }
}

internal fun MainActivity.showArtPublic(iv: ImageView, n: NowView?) = showArt(iv, n)

/** Where the music comes from, for the car screen header. */
internal fun NowView.sourceLabel() = when (source) { "local" -> "Web Launcher"; else -> sub.substringAfterLast(" · ", "") }

/** Full screen «now playing», for the car: big cover, big text, huge buttons, the screen stays on. */
internal fun MainActivity.showNowPlayingFull() {
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val h = Handler(Looper.getMainLooper())
    val root = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
    val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) } } }
    val title = TextView(this).apply { textSize = 30f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
    val sub = TextView(this).apply { textSize = 20f; setTextColor(0xFFB8BCC8.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10)) }
    fun big(t: String, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = size.toFloat(); gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x22FFFFFF) }
        setOnClickListener { onClick() }
    }
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    lateinit var refresh: () -> Unit
    val play = big("⏸", 46) { musicAction("toggle"); h.postDelayed({ refresh() }, 500) }
    val prev = big("⏮", 34) { musicAction("prev"); h.postDelayed({ refresh() }, 600) }
    val next = big("⏭", 34) { musicAction("next"); h.postDelayed({ refresh() }, 600) }
    val volDown = big("−", 40) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI) }
    val volUp = big("+", 40) { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI) }
    val close = TextView(this).apply { text = "✕"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(18), dp(14), dp(18), dp(14)); setOnClickListener { dlg.dismiss() } }
    var landscape: Boolean? = null
    fun build() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (land == landscape) return
        landscape = land
        root.removeAllViews()
        listOf(cover, title, sub, play, prev, next, volDown, volUp).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val w = resources.displayMetrics.widthPixels; val hh = resources.displayMetrics.heightPixels
        val coverSize = if (land) (hh * 0.62).toInt() else (w * 0.78).toInt()
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(prev, LinearLayout.LayoutParams(dp(82), dp(82)).apply { marginEnd = dp(14) })
            addView(play, LinearLayout.LayoutParams(dp(110), dp(110)))
            addView(next, LinearLayout.LayoutParams(dp(82), dp(82)).apply { marginStart = dp(14) }) }
        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0)
            addView(volDown, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(40) }); addView(volUp, LinearLayout.LayoutParams(dp(64), dp(64))) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(title); addView(sub); addView(btnRow); addView(volRow) }
        val main = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), dp(24))
            addView(cover, LinearLayout.LayoutParams(coverSize, coverSize).apply { if (land) marginEnd = dp(32) else bottomMargin = dp(24) })
            addView(info, if (land) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(main, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(close, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        cover.tag = null
    }
    refresh = {
        val n = nowPlaying()
        if (n == null) { title.text = "Nessuna musica in corso"; sub.text = "Avvia YouTube Music o YouTube"; play.text = "▶"; showArt(cover, null) }
        else { title.text = n.title; sub.text = n.sub; play.text = if (n.playing) "⏸" else "▶"; showArt(cover, n) }
    }
    val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val before = landscape; build(); if (before != landscape) refresh() }
    build()
    dlg.setContentView(root)
    dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    dlg.setOnDismissListener { h.removeCallbacksAndMessages(null) }
    dlg.show()
    h.post(tick)
}

/** The music player sections (used in Ascolta → Musica). [leave] is called before opening something in the browser. */
internal fun MainActivity.musicSections(body: LinearLayout, leave: () -> Unit, rerender: () -> Unit) {
    sectionLabel(body, "ORA IN ASCOLTO")
    body.addView(MusicBar(this, large = true).view)
    listRow(body, "🚗 Schermo intero", "Copertina e tasti grandi, lo schermo resta acceso: comodo in auto", onClick = { showNowPlayingFull() })

    sectionLabel(body, "APRI")
    val ytApp = Music.installed(this, Music.YTMUSIC)
    listRow(body, "🔴 YouTube Music", if (Music.ytInApp(this)) "Qui dentro, anche a schermo spento" + (if (ytApp) ". Tieni premuto per usare l'app separata" else "") else "Apre l'app YouTube Music. Tieni premuto per usarlo dentro Web Launcher",
        onClick = { leave(); openMusicApp("ytmusic") }, onLong = { if (ytApp) { Music.setYtInApp(this, !Music.ytInApp(this)); rerender() } })
    listRow(body, "▶️ YouTube", "Video e musica, qui dentro: pubblicità saltate e audio anche a schermo spento" + if (Music.preferred(this) == "youtube") " · usato per le ricerche" else "",
        onClick = { leave(); openMusicApp("youtube") }, onLong = { Music.setPreferred(this, if (Music.preferred(this) == "youtube") "ytmusic" else "youtube"); toast(if (Music.preferred(this) == "youtube") "Le ricerche vanno su YouTube" else "Le ricerche vanno su YouTube Music"); rerender() })
    if (Music.installed(this, Music.SPOTIFY)) listRow(body, "🟢 Spotify", if (Music.preferred(this) == "spotify") "Usata per le ricerche: tieni premuto per tornare a YouTube Music" else "Tocca per aprire, tieni premuto per usarla nelle ricerche",
        onClick = { openMusicApp("spotify") }, onLong = { Music.setPreferred(this, if (Music.preferred(this) == "spotify") "ytmusic" else "spotify"); rerender() })

    sectionLabel(body, "CERCA E METTI")
    listRow(body, "🎤 Di' cosa vuoi ascoltare", "«metti musica per correre», «ascolta Vasco Rossi», «pausa», «prossima canzone»", onClick = { requestMicAndListen() })
    listRow(body, "🔎 Scrivi cosa cercare", "Artista, canzone, genere o umore", onClick = {
        val et = field("Cosa vuoi ascoltare?", null)
        MaterialAlertDialogBuilder(this).setTitle("Cerca nella musica").setView(form(et)).setPositiveButton("Cerca") { _, _ -> if (et.text.toString().isNotBlank()) { leave(); searchMusic(et.text.toString().trim(), null) } }.setNegativeButton("Annulla", null).show()
    })

    sectionLabel(body, "LE MIE PLAYLIST")
    Music.playlists(this).forEach { p ->
        val app = Music.appFor(p.url)
        listRow(body, (when (app) { "spotify" -> "🟢 "; "ytmusic" -> "🔴 "; "youtube" -> "▶️ "; else -> "🎵 " }) + p.name, Music.appName(app) + " · tocca per aprirla",
            onClick = { if (app == "youtube" || app == "ytmusic" && Music.ytInApp(this)) leave(); openMusicLink(p.url) },
            onRemove = { Music.removePlaylist(this, p.id); rerender() })
    }
    listRow(body, "➕ Aggiungi playlist o album", "Incolla il link da YouTube Music, YouTube o Spotify (Condividi → Copia link)", onClick = {
        val clip = (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
        val name = field("Nome (es. Corsa)", null); val url = field("Link", clip.takeIf { Music.appFor(it) != null })
        MaterialAlertDialogBuilder(this).setTitle("Nuova playlist").setView(form(name, url)).setPositiveButton("Salva") { _, _ ->
            val u = url.text.toString().trim()
            if (name.text.toString().isBlank() || (!u.startsWith("http") && !u.startsWith("spotify:"))) toast("Scrivi un nome e incolla un link valido")
            else { Music.addPlaylist(this, name.text.toString().trim(), u); rerender() }
        }.setNegativeButton("Annulla", null).show()
    })
}

/** Strumenti → Musica and the voice/test shortcut: the player lives in Ascolta → Musica. */
internal fun MainActivity.showMusicPage() {
    audioTab = "music"; showTab(MainActivity.TAB_AUDIO); onAudioShown()
}

/** Opens a search in the chosen music app. */
internal fun MainActivity.searchMusic(query: String, appHint: String?) {
    WebMedia.addSearch(this, query)
    var app = appHint ?: Music.defaultApp(this)
    if (app == "spotify" && !Music.installed(this, Music.SPOTIFY)) app = "ytmusic"
    toast("🎵 Cerco «$query» su ${Music.appName(app)}")
    openMusicLink(Music.searchUri(app, query))
}

/** Voice: «metti musica per correre», «pausa», «prossima canzone»… Returns true if it was a music command. */
internal fun MainActivity.musicVoice(text: String): Boolean {
    val cmd = Music.parseVoice(text) ?: return false
    when (cmd) {
        is MusicCmd.Control -> {
            Music.control(this, cmd.action)
        }
        is MusicCmd.Find -> searchMusic(cmd.query, cmd.app)
    }
    return true
}

/** Keeps [Music.web] up to date while the Browser is on YouTube / YouTube Music, and lets the rest of the app command that player. */
internal fun MainActivity.setupWebMusic() {
    Music.webControl = { action -> runOnUiThread { runCatching { webView.evaluateJavascript(Music.controlJs(action), null) } } }
    val h = Handler(Looper.getMainLooper())
    h.post(object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            runCatching {
                if (YouTube.isYouTube(webView.url)) webView.evaluateJavascript(Music.STATE_JS) { raw ->
                    val st = Music.parseWebState(raw); Music.web = st
                    val url = webView.url.orEmpty()
                    if (st != null && st.playing && st.title.isNotBlank()) WebMedia.addRecent(this@setupWebMusic, WebMedia.Item(url, st.title, st.artist, st.art, st.music, System.currentTimeMillis()))
                    if (st != null && !st.music && WebMedia.audioOnly(this@setupWebMusic)) applyAudioOnly(true)
                    updateMiniPlayer()
                } else if (Music.web != null) { Music.web = null; updateMiniPlayer() }
            }
            h.postDelayed(this, 2000)
        }
    })
}
