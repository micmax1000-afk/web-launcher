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
    if (Music.appFor(url) == "ytmusic" && Music.ytInApp(this)) { openYtMusic(url); return }
    if (Music.appFor(url) == "youtube") { openInBrowser(url); return }
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Music.packageOf(Music.appFor(url))?.takeIf { Music.installed(this, it) }?.let { i.setPackage(it) }
    try { startActivity(i) } catch (_: Exception) { if (url.startsWith("http")) openInBrowser(url) else toast("Non trovo l'app per aprire questo link") }
}

internal fun MainActivity.openMusicApp(app: String?) {
    if (app == "youtube") { openInBrowser("https://m.youtube.com/"); return }
    if (app == "ytmusic" && Music.ytInApp(this)) { openYtMusic(); return }
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
        return NowView(w.title.ifBlank { "In riproduzione" }, listOf(w.artist, if (w.music) "YouTube Music" else "YouTube").filter { it.isNotBlank() }.joinToString(" · "), w.art.takeIf { it.startsWith("http") }, null, w.playing, "web", w.posMs + if (w.playing) System.currentTimeMillis() - w.at else 0L, w.durMs, runCatching { activeWeb().url }.getOrNull().orEmpty(), w.music)
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



/** The «Musica» tab: what is playing, your playlists, then the songs on the phone. */
internal fun MainActivity.musicSections(body: LinearLayout, leave: () -> Unit, rerender: () -> Unit) {
    runCatching { nowPlaying() }.getOrNull()?.let { body.addView(playingCard(it)) }
    sectionLabel(body, "LE MIE PLAYLIST")
    Music.playlists(this).forEach { p ->
        val app = Music.appFor(p.url)
        listRow(body, (when (app) { "spotify" -> "🟢 "; "ytmusic" -> "🔴 "; "youtube" -> "▶️ "; else -> "🎵 " }) + p.name, Music.appName(app),
            onClick = { if (app == "youtube" || app == "ytmusic" && Music.ytInApp(this)) leave(); openMusicLink(p.url) },
            onRemove = { Music.removePlaylist(this, p.id); rerender() })
    }
    listRow(body, "➕ Aggiungi playlist o album", "Incolla il link (Condividi → Copia link)", onClick = {
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
internal fun MainActivity.showMusicPage() { openYtMusic() }

/** Opens a search in the chosen music app. */
internal fun MainActivity.searchMusic(query: String, appHint: String?) {
    WebMedia.addSearch(this, query)
    var app = appHint ?: Music.defaultApp(this)
    if (app == "spotify" && !Music.installed(this, Music.SPOTIFY)) app = "ytmusic"
    if (app == "youtube" && appHint == null) app = "ytmusic"   // Ascolta keeps only YouTube Music
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
    Music.webControl = { action -> runOnUiThread { runCatching { activeWeb().evaluateJavascript(Music.controlJs(action), null) } } }
    val h = Handler(Looper.getMainLooper())
    h.post(object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            runCatching { pollWebMusic() }
            h.postDelayed(this, 2000)
        }
    })
}

/** Reads what plays in the YouTube Music page (Ascolta) and in the Browser; the one that is playing wins. */
private fun MainActivity.pollWebMusic() {
    if (Music.demo) { Music.web = Music.web?.copy(at = System.currentTimeMillis(), posMs = (Music.web?.posMs ?: 0) + 2000); updateMiniPlayer(); return }
    val mw = musicWeb?.takeIf { YouTube.isYouTube(it.url) }
    val bw = webView.takeIf { YouTube.isYouTube(it.url) }
    if (mw == null && bw == null) {
        if (Music.web != null) { Music.web = null; updateMiniPlayer(); musicBarUpdate?.invoke() }
        return
    }
    var fromMusic: WebPlaying? = null; var fromBrowser: WebPlaying? = null
    var pending = (if (mw != null) 1 else 0) + (if (bw != null) 1 else 0)
    fun done() {
        if (--pending > 0) return
        val useMusic = when {
            fromMusic?.playing == true -> true
            fromBrowser?.playing == true -> false
            fromMusic != null && fromMusic!!.title.isNotBlank() -> true
            else -> false
        }
        val st = if (useMusic) fromMusic else fromBrowser
        Music.webFromMusic = useMusic && st != null
        Music.web = st
        val url = (if (useMusic) mw?.url else bw?.url).orEmpty()
        if (st != null && st.playing && Music.cleanTitle(st.title).isNotBlank()) {
            WebMedia.addRecent(this, WebMedia.Item(url, st.title, st.artist, st.art, st.music || useMusic, System.currentTimeMillis()))
            if (useMusic) AudioStore.setLast(this, "music")
        }
        if (st != null && !useMusic && !st.music && WebMedia.audioOnly(this)) applyAudioOnly(true)
        updateMiniPlayer()
        musicBarUpdate?.invoke()
    }
    mw?.evaluateJavascript(Music.STATE_JS) { raw -> fromMusic = Music.parseWebState(raw)?.let { it.copy(music = true) }; done() }
    bw?.evaluateJavascript(Music.STATE_JS) { raw -> fromBrowser = Music.parseWebState(raw); done() }
}
