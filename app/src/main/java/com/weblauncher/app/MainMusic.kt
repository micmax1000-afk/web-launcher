package com.weblauncher.app

import android.content.Intent
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
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Music.packageOf(Music.appFor(url))?.takeIf { Music.installed(this, it) }?.let { i.setPackage(it) }
    try { startActivity(i) } catch (_: Exception) { if (url.startsWith("http")) openInBrowser(url) else toast("Non trovo l'app per aprire questo link") }
}

internal fun MainActivity.openMusicApp(app: String?) {
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

/** A small player: cover, title, artist and three buttons. Refreshes itself while it is on screen. */
class MusicBar(private val a: MainActivity, private val large: Boolean = false) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val h = Handler(Looper.getMainLooper())
    private val palette = AppRepository.palette(a)
    private val cover = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(dp(if (large) 84 else 44), dp(if (large) 84 else 44)); setBackgroundColor(0x22888888) }
    private val title = TextView(a).apply { textSize = if (large) 17f else 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = TextView(a).apply { textSize = if (large) 14f else 12f; setTextColor(palette.textMuted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private fun btn(t: String, action: String) = TextView(a).apply {
        text = t; textSize = if (large) 30f else 22f; gravity = Gravity.CENTER; setTextColor(palette.text); setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { Music.control(a, action); h.postDelayed({ refresh() }, 400) }
    }
    private val prev = btn("⏮", "prev"); private val play = btn("⏸", "toggle"); private val next = btn("⏭", "next")
    private val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(6) }; addView(title); addView(sub) }
    private val headRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(cover); addView(texts) }
    private val controls = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; addView(prev); addView(play); addView(next) }
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
        if (!Music.hasAccess(a)) {
            title.text = "🎵 Comandi della musica"; sub.text = "Tocca qui per mostrare anche il brano"; cover.visibility = View.GONE; controls.visibility = View.VISIBLE; play.text = "⏯"
            view.setOnClickListener { a.askMusicAccess() }; return
        }
        val n = Music.current(a)
        cover.visibility = View.VISIBLE; controls.visibility = View.VISIBLE
        if (n == null) {
            title.text = "🎵 Nessuna musica in corso"; sub.text = Music.defaultApp(a)?.let { "Tocca per aprire ${Music.appName(it)}" } ?: "Avvia Spotify o YouTube Music"; cover.visibility = View.GONE; controls.visibility = View.VISIBLE; play.text = "⏯"
            view.setOnClickListener { Music.defaultApp(a)?.let { a.openMusicApp(it) } }; return
        }
        title.text = n.title; sub.text = listOf(n.artist, n.app).filter { it.isNotBlank() }.joinToString(" · ")
        if (n.art != null) cover.setImageBitmap(n.art) else { cover.setImageDrawable(null) }
        play.text = if (n.playing) "⏸" else "▶"
        view.setOnClickListener(null); view.isClickable = false
    }
}

internal fun MainActivity.showMusicPage() {
    openPage("Musica") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        fun render() {
            body.removeAllViews()
            sectionLabel(body, "ORA IN ASCOLTO")
            body.addView(MusicBar(this, large = true).view)
            if (!Music.hasAccess(this)) hintLabel(body, "I tasti funzionano anche senza permessi. Per vedere titolo, artista e copertina tocca la scheda qui sopra.")

            sectionLabel(body, "APRI L'APP")
            var any = false
            listOf("spotify", "ytmusic").forEach { app ->
                if (Music.installed(this, Music.packageOf(app)!!)) { any = true; listRow(body, "${if (app == "spotify") "🟢" else "🔴"} ${Music.appName(app)}", if (Music.preferred(this) == app) "App preferita per le ricerche" else "Tocca per aprire, tieni premuto per sceglierla come preferita",
                    onClick = { openMusicApp(app) }, onLong = { Music.setPreferred(this, app); toast("${Music.appName(app)}: app preferita"); render() }) }
            }
            if (!any) hintLabel(body, "Né Spotify né YouTube Music risultano installate su questo telefono.")

            sectionLabel(body, "CERCA E METTI")
            listRow(body, "🎤 Di' cosa vuoi ascoltare", "Per esempio «metti musica per correre» o «ascolta Vasco Rossi su Spotify»", onClick = { dialog.dismiss(); requestMicAndListen() })
            listRow(body, "🔎 Scrivi cosa cercare", "Artista, canzone, genere o umore", onClick = {
                val et = field("Cosa vuoi ascoltare?", null)
                MaterialAlertDialogBuilder(this).setTitle("Cerca nella musica").setView(form(et)).setPositiveButton("Cerca") { _, _ -> if (et.text.toString().isNotBlank()) searchMusic(et.text.toString().trim(), null) }.setNegativeButton("Annulla", null).show()
            })
            listRow(body, "⏯ Comandi vocali", "«pausa», «prossima canzone», «canzone precedente», «riprendi la musica»", onClick = {})

            sectionLabel(body, "LE MIE PLAYLIST")
            Music.playlists(this).forEach { p ->
                listRow(body, (if (Music.appFor(p.url) == "spotify") "🟢 " else if (Music.appFor(p.url) == "ytmusic") "🔴 " else "🎵 ") + p.name, Music.appName(Music.appFor(p.url)) + " · tocca per aprirla", onClick = { openMusicLink(p.url) },
                    onRemove = { Music.removePlaylist(this, p.id); render() })
            }
            listRow(body, "➕ Aggiungi playlist o album", "Incolla il link da Spotify o YouTube Music (Condividi → Copia link)", onClick = {
                val clip = (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                val name = field("Nome (es. Corsa)", null); val url = field("Link", clip.takeIf { Music.appFor(it) != null })
                MaterialAlertDialogBuilder(this).setTitle("Nuova playlist").setView(form(name, url)).setPositiveButton("Salva") { _, _ ->
                    val u = url.text.toString().trim()
                    if (name.text.toString().isBlank() || (!u.startsWith("http") && !u.startsWith("spotify:"))) toast("Scrivi un nome e incolla un link valido")
                    else { Music.addPlaylist(this, name.text.toString().trim(), u); render() }
                }.setNegativeButton("Annulla", null).show()
            })
            hintLabel(body, "Il catalogo resta dentro Spotify e YouTube Music: qui comandi quello che già suona e apri con un tocco le tue playlist.")
        }
        render()
    }
}

/** Opens a search in the chosen music app. */
internal fun MainActivity.searchMusic(query: String, appHint: String?) {
    val app = appHint ?: Music.defaultApp(this) ?: return toast("Installa Spotify o YouTube Music per ascoltare")
    if (Music.packageOf(app) != null && !Music.installed(this, Music.packageOf(app)!!)) {
        val other = listOf("spotify", "ytmusic").firstOrNull { it != app && Music.installed(this, Music.packageOf(it)!!) }
        if (other == null) return toast("${Music.appName(app)} non è installata")
        return searchMusic(query, other)
    }
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
