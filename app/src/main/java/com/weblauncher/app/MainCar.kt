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
internal fun MainActivity.showCarMode() {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val h = Handler(Looper.getMainLooper())
    val base = 0xFF0B0D10.toInt()
    var tint = 0xFF3B2F5C.toInt()
    val root = FrameLayout(this)
    fun paintBg() { root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tint, base, base)) }
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
            dockItem("🎶", "Sorgenti") { carSources(dlg) },
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
        listOf<View>(clock, source, exit, cover, title, sub, bar, tPos, tDur, prev, play, next, dock).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        val w = resources.displayMetrics.widthPixels; val hh = resources.displayMetrics.heightPixels
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(clock, LinearLayout.LayoutParams(dp(80), -2)); addView(source, LinearLayout.LayoutParams(0, -2, 1f)); addView(exit)
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
    dlg.setContentView(root)
    dlg.window?.apply {
        addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusBarColor = base; navigationBarColor = base
    }
    dlg.setOnDismissListener { h.removeCallbacksAndMessages(null) }
    dlg.show()
    h.post(tick)
}

/** Car mode → «Sorgenti»: where to listen from, in big rows. */
private fun MainActivity.carSources(car: android.app.Dialog) {
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
    fun web(app: String) { dlg.dismiss(); car.dismiss(); openMusicApp(app); toast("Scegli cosa ascoltare, poi riapri la Modalità auto") }
    row("🔴", "YouTube Music", "Dentro Web Launcher") { web("ytmusic") }
    row("▶️", "YouTube", "Dentro Web Launcher") { web("youtube") }
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
