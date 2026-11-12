package com.weblauncher.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A dark version of the main colour of a cover, for the background of the player. */
internal fun coverTint(b: Bitmap): Int {
    val px = runCatching { Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0) }.getOrDefault(0xFF5B3B8F.toInt())
    val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
    hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f); hsv[2] = hsv[2].coerceIn(0.28f, 0.5f)
    return Color.HSVToColor(hsv)
}

internal fun fmtMs(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Where the music comes from, for the header of the player. */
internal fun NowView.sourceLabel() = when {
    source == "local" && sub.startsWith("Radio") -> "Radio"
    source == "local" -> "Podcast"
    else -> sub.substringAfterLast(" · ", "").ifBlank { "Musica" }
}

/** Asks for a sleep timer and applies it. */
internal fun MainActivity.sleepTimerDialog(after: () -> Unit) {
    val opts = arrayOf("Spento", "15 minuti", "30 minuti", "45 minuti", "60 minuti", "90 minuti")
    val mins = intArrayOf(0, 15, 30, 45, 60, 90)
    MaterialAlertDialogBuilder(this).setTitle("Spegni la musica tra").setItems(opts) { _, w ->
        uiHandler.removeCallbacks(sleepRunnable); sleepAt = 0L
        if (mins[w] > 0) { sleepAt = System.currentTimeMillis() + mins[w] * 60_000L; uiHandler.postDelayed(sleepRunnable, mins[w] * 60_000L); toast("⏲ Si ferma tra ${mins[w]} minuti") }
        after()
    }.show()
}

/** The only player of the app: podcasts, radio, YouTube Music and phone music look and work the same. */
internal fun MainActivity.showPlayerSheet() = showNowPlayingFull()

internal fun MainActivity.showNowPlayingFull() {
    if (nowPlaying() == null) { toast("Niente in riproduzione"); return }
    miniClosedFor = null
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val h = Handler(Looper.getMainLooper())
    val white = 0xFFFFFFFF.toInt(); val soft = 0xFFB8BCC8.toInt()
    var tint = 0xFF5B2A86.toInt()
    var tintKey: Any? = null
    var lastSource = ""
    val root = FrameLayout(this)
    fun paintBg() { root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tint, 0xFF140E1C.toInt(), 0xFF0B0A10.toInt())) }
    paintBg()

    // ----- header -----
    val close = TextView(this).apply { text = "⌄"; textSize = 30f; setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); contentDescription = "Chiudi"; setOnClickListener { dlg.dismiss() } }
    val srcLine = TextView(this).apply { textSize = 10.5f; letterSpacing = 0.12f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER; text = "IN RIPRODUZIONE DA" }
    val srcName = TextView(this).apply { textSize = 13.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); gravity = Gravity.CENTER }
    val clock = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.END }
    val more = TextView(this).apply { text = "⋯"; textSize = 26f; setTextColor(white); gravity = Gravity.END; setPadding(dp(10), 0, dp(6), 0); contentDescription = "Altro" }
    val moreBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; minimumWidth = dp(56); addView(more); addView(clock) }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(close, LinearLayout.LayoutParams(dp(56), -2))
        addView(LinearLayout(this@showNowPlayingFull).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(srcLine); addView(srcName) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(moreBox, LinearLayout.LayoutParams(dp(56), -2))
    }

    // ----- cover, title, favourite -----
    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; setBackgroundColor(0xFF000000.toInt())
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) } }
        elevation = dp(10).toFloat()
    }
    val title = TextView(this).apply { textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    val sub = TextView(this).apply { textSize = 15f; setTextColor(soft); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val live = TextView(this).apply { text = "● IN DIRETTA"; textSize = 12.5f; letterSpacing = 0.08f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFFFCA5A5.toInt()); setPadding(0, dp(6), 0, 0) }
    val heart = TextView(this).apply { text = "♡"; textSize = 28f; setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(4), dp(4)); contentDescription = "Preferito" }
    val titleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@showNowPlayingFull).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(sub); addView(live) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(heart)
    }

    // ----- seek bar -----
    val seek = SeekBar(this).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(white); thumbTintList = android.content.res.ColorStateList.valueOf(white)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
    }
    val tPos = TextView(this).apply { textSize = 11.5f; setTextColor(soft) }
    val tDur = TextView(this).apply { textSize = 11.5f; setTextColor(soft); gravity = Gravity.END }
    val times = LinearLayout(this).apply { addView(tPos, LinearLayout.LayoutParams(0, -2, 1f)); addView(tDur, LinearLayout.LayoutParams(0, -2, 1f)) }
    val seekBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(seek, LinearLayout.LayoutParams(-1, -2)); addView(times, LinearLayout.LayoutParams(-1, -2)) }

    // ----- controls -----
    lateinit var refresh: () -> Unit
    fun txt(t: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = size; setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { onClick(); h.postDelayed({ refresh() }, 500) }
    }
    val back15 = txt("↺\n15", 15f) { nowPlaying()?.let { playerSeekTo(it.posMs - 15_000) } }
    val prev = txt("⏮", 30f) { musicAction("prev") }
    val play = TextView(this).apply {
        text = "⏸"; textSize = 32f; setTextColor(0xFF111111.toInt()); gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(white) }
        setOnClickListener { musicAction("toggle"); h.postDelayed({ refresh() }, 500) }
    }
    val next = txt("⏭", 30f) { musicAction("next") }
    val fwd15 = txt("↻\n15", 15f) { nowPlaying()?.let { playerSeekTo(it.posMs + 15_000) } }
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(back15, LinearLayout.LayoutParams(0, -2, 1f)); addView(prev, LinearLayout.LayoutParams(0, -2, 1f))
        addView(play, LinearLayout.LayoutParams(dp(78), dp(78)))
        addView(next, LinearLayout.LayoutParams(0, -2, 1f)); addView(fwd15, LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ----- chips: lyrics, speed, timer, queue -----
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    fun chip(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x26FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); marginEnd = dp(5) }
        setOnClickListener { onClick(this) }
        chips.addView(this)
    }
    var lyricsOn = false
    lateinit var showLyrics: (Boolean) -> Unit
    val lyricsChip = chip("📝 Testo") { showLyrics(!lyricsOn) }
    val speeds = floatArrayOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
    fun fmtSpeed(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "×"
    val speedChip = chip("1×") { v ->
        val n = nowPlaying() ?: return@chip
        val cur = playerSpeed(n) ?: return@chip
        val s = speeds.firstOrNull { it > cur + 0.01f } ?: speeds[0]
        setPlayerSpeed(n, s); v.text = fmtSpeed(s)
    }
    val timerChip = chip("⏲ Timer") { sleepTimerDialog { refresh() } }
    val queueChip = chip("📋 Coda") { dlg.dismiss(); showQueuePage() }

    // ----- volume slider (media volume) -----
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    val vol = SeekBar(this).apply {
        max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        progressTintList = android.content.res.ColorStateList.valueOf(white); thumbTintList = android.content.res.ColorStateList.valueOf(white)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    val volRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@showNowPlayingFull).apply { text = "🔈"; textSize = 16f; setTextColor(soft) })
        addView(vol, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@showNowPlayingFull).apply { text = "🔊"; textSize = 16f; setTextColor(soft) })
    }

    // ----- lyrics -----
    var lyricsKey = ""
    var lyricsResult: Lyrics.Result? = null
    var lyricsLoading = false
    var lyricsActive = -2
    val lyricsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(120)) }
    val lyricsScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(lyricsBox); visibility = View.GONE }
    val lyricViews = mutableListOf<TextView>()
    fun lyricsMessage(emoji: String, head: String, body: String, button: String? = null, action: (() -> Unit)? = null) {
        lyricsBox.removeAllViews(); lyricViews.clear()
        lyricsBox.addView(TextView(this).apply { text = emoji; textSize = 48f; gravity = Gravity.CENTER; setPadding(0, dp(50), 0, 0) })
        lyricsBox.addView(TextView(this).apply { text = head; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
        lyricsBox.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(soft); gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0) })
        if (button != null) lyricsBox.addView(TextView(this).apply {
            text = button; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); gravity = Gravity.CENTER; setPadding(dp(18), dp(11), dp(18), dp(11))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x26FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) }
            setOnClickListener { action?.invoke() }
        })
    }
    fun renderLyrics() {
        val r = lyricsResult
        lyricsActive = -2
        when {
            lyricsLoading || r == null -> lyricsMessage("📝", "Cerco il testo…", "Un attimo.")
            r is Lyrics.Result.Instrumental -> lyricsMessage("🎼", "Brano strumentale", "Non ci sono parole da leggere.")
            r is Lyrics.Result.None -> lyricsMessage("📝", "Testo non disponibile", "Non l'ho trovato per questo brano.\nCapita con i brani meno noti e con le registrazioni dal vivo.", "🔴 Cerca in YouTube Music") { dlg.dismiss(); openYtMusic() }
            r is Lyrics.Result.Plain -> {
                lyricsBox.removeAllViews(); lyricViews.clear()
                lyricsBox.addView(TextView(this).apply { text = r.text; textSize = 19f; setTextColor(white); setLineSpacing(0f, 1.3f); setPadding(dp(14), 0, dp(14), 0) })
            }
            r is Lyrics.Result.Synced -> {
                lyricsBox.removeAllViews(); lyricViews.clear()
                r.lines.forEach { l ->
                    val tv = TextView(this).apply {
                        text = l.text.ifBlank { "♪" }; textSize = 23f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(white); alpha = 0.38f
                        setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { playerSeekTo(l.timeMs) }
                    }
                    lyricViews += tv; lyricsBox.addView(tv)
                }
            }
        }
    }
    fun loadLyrics(n: NowView) {
        val artist = n.sub.substringBeforeLast(" · ", "").ifBlank { "" }
        val key = n.title + "|" + artist
        if (key == lyricsKey && lyricsResult != null) return
        lyricsKey = key; lyricsResult = null; lyricsLoading = true; renderLyrics()
        io.execute {
            val res = Lyrics.fetch(this, n.title, artist, n.durMs / 1000)
            runOnUiThread { if (lyricsKey == key) { lyricsResult = res; lyricsLoading = false; renderLyrics() } }
        }
    }
    fun updateLyricsPosition(n: NowView) {
        val r = lyricsResult as? Lyrics.Result.Synced ?: return
        val i = Lyrics.activeIndex(r.lines, n.posMs + 300)
        if (i == lyricsActive) return
        lyricsActive = i
        lyricViews.forEachIndexed { k, tv -> tv.animate().alpha(if (k == i) 1f else if (k == i + 1) 0.62f else 0.38f).setDuration(200).start() }
        if (i >= 0 && i < lyricViews.size) {
            val tv = lyricViews[i]
            lyricsScroll.smoothScrollTo(0, (tv.top - lyricsScroll.height / 3).coerceAtLeast(0))
        }
    }

    // ----- layout (portrait / landscape) -----
    val coverBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var landscape: Boolean? = null
    lateinit var swipeArea: View
    fun build() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (land == landscape) return
        landscape = land
        root.removeAllViews()
        listOf(header, cover, titleRow, seekBox, controls, chips, volRow, lyricsScroll).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val w = resources.displayMetrics.widthPixels; val hh = resources.displayMetrics.heightPixels
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        if (!land) {
            val size = (w * 0.74).toInt().coerceAtMost((hh * 0.4).toInt())
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(18))
                addView(header, LinearLayout.LayoutParams(-1, dp(56)).apply { marginStart = -dp(10); marginEnd = -dp(10) })
                addView(cover, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })
                addView(lyricsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(titleRow, lp(22)); addView(seekBox, lp(14)); addView(controls, lp(6)); addView(chips, lp(14)); addView(volRow, lp(18))
            }
            root.addView(col, FrameLayout.LayoutParams(-1, -1))
        } else {
            val size = (hh * 0.62).toInt()
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(cover, LinearLayout.LayoutParams(size, size)) }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(18), dp(8))
                addView(header, LinearLayout.LayoutParams(-1, dp(48)))
                addView(lyricsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(titleRow, lp(4)); addView(seekBox, lp(8)); addView(controls, lp(2)); addView(chips, lp(6)); addView(volRow, lp(6))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(8), 0, dp(8))
                addView(left, LinearLayout.LayoutParams(size + dp(24), -1)); addView(right, LinearLayout.LayoutParams(0, -1, 1f))
            }
            root.addView(row, FrameLayout.LayoutParams(-1, -1))
        }
        cover.tag = null
        showLyrics(lyricsOn)
    }
    showLyrics = { on ->
        lyricsOn = on
        val land = landscape == true
        lyricsScroll.visibility = if (on) View.VISIBLE else View.GONE
        // with the lyrics open the cover gives room: it stays only as a small picture in landscape
        cover.visibility = if (on && !land) View.GONE else View.VISIBLE
        lyricsChip.text = if (on) "🖼 Copertina" else "📝 Testo"
        if (on) nowPlaying()?.let { loadLyrics(it) }
    }

    // ----- menu ⋯ -----
    more.setOnClickListener {
        val n = nowPlaying() ?: return@setOnClickListener
        val fav = n.source == "web" && n.url.isNotBlank() && WebMedia.isFavorite(this, n.url)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (n.source == "web" && n.url.isNotBlank()) items += (if (fav) "⭐ Togli dai preferiti" else "⭐ Aggiungi ai preferiti") to { heart.performClick() }
        items += "⏲ Spegni la musica tra…" to { sleepTimerDialog { refresh() } }
        if (n.source == "web" && Music.webFromMusic) items += "🔴 Apri YouTube Music" to { dlg.dismiss(); openYtMusic() }
        if (AudioQueue.list(this).isNotEmpty()) items += "📋 Coda di ascolto" to { dlg.dismiss(); showQueuePage() }
        items += "🔗 Condividi" to {
            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, n.title + (n.sub.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") + n.url.takeIf { it.startsWith("http") }?.let { "\n$it" }.orEmpty())
            }, "Condividi"))
        }
        MaterialAlertDialogBuilder(this).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }
    heart.setOnClickListener {
        val n = nowPlaying() ?: return@setOnClickListener
        when {
            n.source == "web" && n.url.isNotBlank() -> {
                val on = WebMedia.toggleFavorite(this, WebMedia.Item(n.url, n.title, n.sub.substringBeforeLast(" · ", ""), n.artUrl.orEmpty(), n.music, System.currentTimeMillis()))
                toast(if (on) "⭐ Aggiunto ai preferiti" else "Tolto dai preferiti")
            }
            n.source == "local" && n.sub.startsWith("Radio") -> {
                val id = controller?.currentMediaItem?.mediaId?.removePrefix("radio:")
                val st = audio.favoriteStations().firstOrNull { it.id == id } ?: runCatching {
                    com.google.gson.Gson().fromJson<List<RadioStation>>(getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE).getString("recentRadio", null), object : com.google.gson.reflect.TypeToken<List<RadioStation>>() {}.type)
                }.getOrNull().orEmpty().filterNotNull().firstOrNull { it.id == id }
                if (st != null) toast(if (audio.toggleFavorite(st)) "⭐ Radio tra le preferite" else "Tolta dalle preferite")
            }
        }
        refresh()
    }

    // ----- seek by touch -----
    var dragging = false
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) nowPlaying()?.let { if (it.durMs > 0) tPos.text = fmtMs(it.durMs * p / 1000) } }
        override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
        override fun onStopTrackingTouch(sb: SeekBar?) { dragging = false; nowPlaying()?.let { if (it.durMs > 0) playerSeekTo(it.durMs * (sb?.progress ?: 0) / 1000) } }
    })

    // ----- refresh (every second) -----
    refresh = {
        clock.text = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date())
        val n = nowPlaying()
        if (n == null) { title.text = "Niente in riproduzione"; sub.text = ""; play.text = "▶" }
        else {
            val radio = n.source == "local" && n.sub.startsWith("Radio")
            srcName.text = n.sourceLabel()
            title.text = n.title
            sub.text = n.sub.substringBeforeLast(" · ", n.sub).let { if (radio) it.removePrefix("Radio").trim(' ', '·').ifBlank { "Radio" } else it }
            showArtPublic(cover, n)
            play.text = if (n.playing) "⏸" else "▶"
            val isLive = isLive(n)
            live.isVisible = isLive && radio
            seekBox.isVisible = !isLive; back15.isVisible = !isLive; fwd15.isVisible = !isLive
            if (!isLive && !dragging) { seek.progress = (n.posMs * 1000 / n.durMs).toInt().coerceIn(0, 1000); tPos.text = fmtMs(n.posMs); tDur.text = "−" + fmtMs((n.durMs - n.posMs).coerceAtLeast(0)) }
            val c = controller
            val nextOk = n.source != "local" || (c?.hasNextMediaItem() == true || c?.hasPreviousMediaItem() == true)
            prev.isVisible = nextOk; next.isVisible = nextOk
            val sp = playerSpeed(n)
            speedChip.isVisible = sp != null && !isLive; if (sp != null) speedChip.text = fmtSpeed(sp)
            lyricsChip.isVisible = n.source == "web" && n.music
            queueChip.isVisible = n.source == "local" && !radio && AudioQueue.list(this).isNotEmpty()
            val canHeart = (n.source == "web" && n.url.isNotBlank()) || radio
            heart.isVisible = canHeart
            heart.text = when {
                n.source == "web" && WebMedia.isFavorite(this, n.url) -> "♥"
                radio && audio.favoriteStations().any { it.id == controller?.currentMediaItem?.mediaId?.removePrefix("radio:") } -> "♥"
                else -> "♡"
            }
            if (lyricsOn && n.source == "web" && n.music) { loadLyrics(n); updateLyricsPosition(n) }
            else if (lyricsOn && !(n.source == "web" && n.music)) showLyrics(false)
            // background: tint from the cover, otherwise a colour per source
            val bmp = (cover.drawable as? BitmapDrawable)?.bitmap
            if (bmp != null && n.artUrl != null && tintKey !== bmp) { tintKey = bmp; tint = coverTint(bmp); paintBg() }
            else if (bmp == null || n.artUrl == null) {
                val src = n.source + radio
                if (src != lastSource || tintKey != null) { lastSource = src; tintKey = null; tint = if (radio) 0xFF0F5C3C.toInt() else if (n.source == "local") 0xFF1B3F86.toInt() else 0xFF5B2A86.toInt(); paintBg() }
            }
        }
        timerChip.text = if (sleepAt > 0) "⏲ ${((sleepAt - System.currentTimeMillis()) / 60_000) + 1} min" else "⏲ Timer"
        vol.progress = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    // swipe down on the top part closes the player
    var downY = 0f; var downX = 0f
    val swipe = View.OnTouchListener { _, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX }
            MotionEvent.ACTION_UP -> if (ev.rawY - downY > dp(90) && Math.abs(ev.rawX - downX) < (ev.rawY - downY)) dlg.dismiss()
        }
        false
    }
    header.setOnTouchListener(swipe); cover.setOnTouchListener(swipe); titleRow.setOnTouchListener(swipe)

    val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val before = landscape; build(); if (before != landscape) refresh() }
    build()
    dlg.setContentView(root)
    dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    playerSheetUpdate = { refresh() }
    dlg.setOnDismissListener { h.removeCallbacksAndMessages(null); playerSheetUpdate = null }
    dlg.show()
    refresh()
    h.postDelayed(tick, 1000)
}
