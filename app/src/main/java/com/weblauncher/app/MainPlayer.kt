package com.weblauncher.app

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ======================= One player for everything =======================

private fun MainActivity.localActive() = controller?.let { it.currentMediaItem != null && it.playbackState != androidx.media3.common.Player.STATE_IDLE } == true

/** Live radio and anything without a length cannot be moved forward or back. */
private fun MainActivity.isLive(n: NowView): Boolean =
    n.durMs <= 0 || (n.source == "local" && (controller?.isCurrentMediaItemLive == true || controller?.currentMediaItem?.mediaId?.startsWith("radio:") == true))

internal fun MainActivity.playerSeekTo(ms: Long) {
    val n = nowPlaying() ?: return
    when (n.source) {
        "local" -> controller?.seekTo(ms.coerceAtLeast(0))
        "web" -> runCatching { activeWeb().evaluateJavascript("(function(t){var v=document.querySelector('video');if(v)v.currentTime=t;})(${ms.coerceAtLeast(0) / 1000.0})", null) }
        else -> Music.current(this)?.controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
    }
}

/** Speed: podcasts (own player) and YouTube pages; null when the source does not allow it. */
private fun MainActivity.playerSpeed(n: NowView): Float? = when (n.source) {
    "local" -> controller?.playbackParameters?.speed
    "web" -> if (!n.music) getSharedPreferences("wl_music", android.content.Context.MODE_PRIVATE).getFloat("webSpeed", 1f) else null
    else -> null
}
private fun MainActivity.setPlayerSpeed(n: NowView, s: Float) {
    when (n.source) {
        "local" -> { controller?.setPlaybackSpeed(s); getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE).edit().putFloat("speed", s).apply() }
        "web" -> { getSharedPreferences("wl_music", android.content.Context.MODE_PRIVATE).edit().putFloat("webSpeed", s).apply()
            runCatching { activeWeb().evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.playbackRate=$s;})()", null) } }
    }
}

/** «Solo audio» for YouTube videos: the picture is hidden and the lowest quality is used (less data); the sound goes on. */
internal fun MainActivity.applyAudioOnly(on: Boolean) {
    val js = if (on) "(function(){var d=document.documentElement;if(!document.getElementById('wl-audio-css')){var s=document.createElement('style');s.id='wl-audio-css';" +
        "s.textContent='html.wl-audio video{opacity:0!important}';(document.head||d).appendChild(s);}d.classList.add('wl-audio');" +
        "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');try{p&&p.setPlaybackQualityRange&&p.setPlaybackQualityRange('tiny','tiny');}catch(e){}})()"
    else "(function(){document.documentElement.classList.remove('wl-audio');})()"
    runCatching { webView.evaluateJavascript(js, null) }
}

private fun sourceBadge(n: NowView) = when (n.source) {
    "web" -> if (n.music) "🔴 YouTube Music" else "▶️ YouTube"
    "local" -> if (n.sub.startsWith("Radio")) "📻 Radio" else "🎙 Ascolta"
    else -> "🎵 " + n.sub.substringAfterLast(" · ", "Musica")
}

/** The small bar at the bottom of every screen, for whatever is playing. */
internal fun MainActivity.updateMiniPlayer() {
    val card = findViewById<View>(R.id.miniPlayer) ?: return
    if (inPip) { card.isVisible = false; return }
    val n = runCatching { nowPlaying() }.getOrNull()
    // on the Browser with the YouTube page in front, the page itself is the player
    val hidden = n == null || (n.source == "web" && !Music.webFromMusic && currentTab == MainActivity.TAB_BROWSER) ||
        (n.source == "web" && Music.webFromMusic && currentTab == MainActivity.TAB_AUDIO && audioTab == "music") || (n.source == "web" && miniClosedFor == n.title)
    card.isVisible = !hidden
    if (hidden) return
    findViewById<TextView>(R.id.miniTitle).text = n!!.title
    findViewById<TextView>(R.id.miniSub).text = if (n.source == "local" && controller?.playbackState == androidx.media3.common.Player.STATE_BUFFERING) "Caricamento…"
        else listOf(n.sub.substringBeforeLast(" · ", n.sub), sourceBadge(n).substringAfter(' ')).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    findViewById<android.widget.ImageButton>(R.id.miniPlay).setImageResource(if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
    val art = findViewById<ImageView>(R.id.miniArt)
    val key = n.artUrl ?: n.artBmp?.hashCode()?.toString() ?: ""
    if (art.tag != key) {
        art.tag = key
        when { n.artBmp != null -> art.setImageBitmap(n.artBmp); n.artUrl != null -> art.load(n.artUrl) { transformations(coil.transform.RoundedCornersTransformation(16f)); error(R.drawable.ic_nav_audio) }; else -> art.setImageResource(R.drawable.ic_nav_audio) }
    }
}

internal fun MainActivity.closeMiniPlayer() {
    val n = nowPlaying()
    when (n?.source) {
        "local" -> controller?.run { stop(); clearMediaItems() }
        "web" -> { musicAction("pause"); miniClosedFor = n.title }
        else -> musicAction("pause")
    }
    updateMiniPlayer()
}

/** The big player, the same for podcasts, radio, phone music, YouTube, YouTube Music and other apps. */
internal fun MainActivity.showPlayerSheet() {
    if (nowPlaying() == null) { toast("Niente in riproduzione"); return }
    miniClosedFor = null
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(6), dp(22), dp(22)) }
    val badge = TextView(this).apply {
        textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(12), dp(4), dp(12), dp(4))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
    }
    val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) } } }
    val title = TextView(this).apply { textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); gravity = Gravity.CENTER; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    val sub = TextView(this).apply { textSize = 14f; setTextColor(palette.textMuted); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) }
    val seek = android.widget.SeekBar(this).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(accent); thumbTintList = android.content.res.ColorStateList.valueOf(accent) }
    val tPos = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted) }
    val tDur = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted); gravity = Gravity.END }
    val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(tPos, LinearLayout.LayoutParams(0, -2, 1f)); addView(tDur, LinearLayout.LayoutParams(0, -2, 1f)) }
    lateinit var update: () -> Unit
    fun btn(t: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = size; setTextColor(palette.text); gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick(); uiHandler.postDelayed({ update() }, 400) }
    }
    val back15 = btn("↺15", 16f) { nowPlaying()?.let { playerSeekTo(it.posMs - 15_000) } }
    val prev = btn("⏮", 28f) { musicAction("prev") }
    val play = android.widget.ImageButton(this).apply {
        setColorFilter(android.graphics.Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(18), dp(18), dp(18), dp(18))
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        setOnClickListener { musicAction("toggle"); uiHandler.postDelayed({ update() }, 400) }
    }
    val next = btn("⏭", 28f) { musicAction("next") }
    val fwd15 = btn("15↻", 16f) { nowPlaying()?.let { playerSeekTo(it.posMs + 15_000) } }
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
        addView(back15); addView(prev); addView(play, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(10), 0, dp(10), 0) }); addView(next); addView(fwd15)
    }
    val extras = com.google.android.material.chip.ChipGroup(this).apply { isSingleLine = false; chipSpacingHorizontal = dp(6) }
    fun chip(label: String, onClick: (com.google.android.material.chip.Chip) -> Unit) = com.google.android.material.chip.Chip(this).apply {
        text = label; setOnClickListener { onClick(this) }; extras.addView(this)
    }
    val speeds = floatArrayOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
    fun fmtSpeed(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "×"
    val speedChip = chip("⏩ 1×") { v ->
        val n = nowPlaying() ?: return@chip
        val cur = playerSpeed(n) ?: return@chip
        val s = speeds.firstOrNull { it > cur + 0.01f } ?: speeds[0]
        setPlayerSpeed(n, s); v.text = "⏩ " + fmtSpeed(s)
    }
    val timerChip = chip("⏲ Timer") { v ->
        val opts = arrayOf("Spento", "15 minuti", "30 minuti", "45 minuti", "60 minuti", "90 minuti")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        MaterialAlertDialogBuilder(this).setTitle("Spegni la musica tra").setItems(opts) { _, w ->
            uiHandler.removeCallbacks(sleepRunnable); sleepAt = 0L
            if (mins[w] > 0) { sleepAt = System.currentTimeMillis() + mins[w] * 60_000L; uiHandler.postDelayed(sleepRunnable, mins[w] * 60_000L); toast("⏲ Si ferma tra ${mins[w]} minuti") }
            update()
        }.show()
    }
    val favChip = chip("☆ Preferito") { v ->
        val n = nowPlaying() ?: return@chip
        val on = WebMedia.toggleFavorite(this, WebMedia.Item(n.url, n.title, n.sub.substringBeforeLast(" · ", ""), n.artUrl.orEmpty(), n.music, System.currentTimeMillis()))
        toast(if (on) "⭐ Aggiunto ai preferiti (Ascolta → Musica)" else "Tolto dai preferiti"); update()
    }
    val audioChip = chip("🎧 Solo audio") { _ ->
        val on = !WebMedia.audioOnly(this); WebMedia.setAudioOnly(this, on); applyAudioOnly(on)
        toast(if (on) "🎧 Solo audio: il video non si vede, consumi meno dati" else "Video di nuovo visibile"); update()
    }
    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    chip("🔊 Volume") { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI) }
    chip("⛶ Schermo intero") { sheet.dismiss(); showNowPlayingFull() }
    if (nowPlaying()?.source == "local" || AudioQueue.list(this).isNotEmpty()) chip("📋 Coda (${AudioQueue.list(this).size})") { sheet.dismiss(); showQueuePage() }
    if (nowPlaying()?.let { it.source == "web" && Music.webFromMusic } == true) chip("🔴 Apri YouTube Music") { sheet.dismiss(); openYtMusic() }
    else if (currentTab != MainActivity.TAB_BROWSER) chip("🌐 Apri la pagina") { sheet.dismiss(); showTab(MainActivity.TAB_BROWSER) }.also { it.tag = "page" }

    root.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
    root.addView(art, LinearLayout.LayoutParams(dp(230), dp(230)).apply { bottomMargin = dp(14) })
    root.addView(title); root.addView(sub)
    root.addView(seek, LinearLayout.LayoutParams(-1, -2)); root.addView(times, LinearLayout.LayoutParams(-1, -2))
    root.addView(controls); root.addView(extras)
    var dragging = false
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) nowPlaying()?.let { if (it.durMs > 0) tPos.text = fmtTime(it.durMs * p / 1000) } }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { dragging = true }
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { dragging = false; nowPlaying()?.let { if (it.durMs > 0) playerSeekTo(it.durMs * (sb?.progress ?: 0) / 1000) } }
    })
    update = {
        val n = nowPlaying()
        if (n == null) { title.text = "Niente in riproduzione"; sub.text = ""; play.setImageResource(R.drawable.ic_play) }
        else {
            badge.text = sourceBadge(n)
            title.text = n.title; sub.text = n.sub.substringBeforeLast(" · ", n.sub)
            showArtPublic(art, n)
            play.setImageResource(if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
            val live = isLive(n)
            seek.isVisible = !live; times.isVisible = !live; back15.isVisible = !live; fwd15.isVisible = !live
            if (!live && !dragging) { seek.progress = (n.posMs * 1000 / n.durMs).toInt().coerceIn(0, 1000); tPos.text = fmtTime(n.posMs); tDur.text = "−" + fmtTime((n.durMs - n.posMs).coerceAtLeast(0)) }
            // prev/next only where they mean something: not for a single podcast episode or a radio
            val c = controller
            val nextOk = n.source != "local" || (c?.hasNextMediaItem() == true || c?.hasPreviousMediaItem() == true)
            prev.isVisible = nextOk; next.isVisible = nextOk
            val sp = playerSpeed(n)
            speedChip.isVisible = sp != null && !live; if (sp != null) speedChip.text = "⏩ " + fmtSpeed(sp)
            favChip.isVisible = n.source == "web" && n.url.isNotBlank()
            favChip.text = if (WebMedia.isFavorite(this, n.url)) "⭐ Preferito" else "☆ Preferito"
            audioChip.isVisible = n.source == "web" && !n.music
            audioChip.text = if (WebMedia.audioOnly(this)) "🎧 Solo audio ✓" else "🎧 Solo audio"
        }
        timerChip.text = if (sleepAt > 0) "⏲ ${((sleepAt - System.currentTimeMillis()) / 60_000) + 1} min" else "⏲ Timer"
    }
    val ticker = object : Runnable { override fun run() { update(); uiHandler.postDelayed(this, 1000) } }
    playerSheetUpdate = { update() }
    sheet.setOnDismissListener { uiHandler.removeCallbacks(ticker); playerSheetUpdate = null }
    sheet.setContentView(android.widget.ScrollView(this).apply { addView(root) })
    sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    sheet.behavior.skipCollapsed = true
    update(); sheet.show(); uiHandler.post(ticker)
}

// ======================= Ascolta: first screen =======================

/** First screen of Ascolta: «Continua» (what you were listening to), three big buttons, one search. */
internal fun MainActivity.renderAudioHome(list: LinearLayout) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    continueCard()?.let { list.addView(it) }
    fun big(icon: String, label: String, sub: String, onClick: () -> Unit) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(16), dp(18))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(5), dp(6), dp(5)) }
        addView(TextView(this@renderAudioHome).apply { text = icon; textSize = 32f; setPadding(0, 0, dp(16), 0) })
        addView(LinearLayout(this@renderAudioHome).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@renderAudioHome).apply { text = label; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text) })
            addView(TextView(this@renderAudioHome).apply { text = sub; textSize = 13.5f; setTextColor(palette.textMuted) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@renderAudioHome).apply { text = "›"; textSize = 26f; setTextColor(palette.textMuted) })
        setOnClickListener { onClick() }
    })
    val subs = audio.subscriptions().size
    val newEps = episodes.count { System.currentTimeMillis() - it.time < 3 * 86_400_000L && !AudioStore.isFinished(this, it.id) }
    big("🎙", "Podcast", when { subs == 0 -> "Cerca e segui i tuoi podcast"; newEps > 0 -> "$newEps episodi nuovi"; else -> "I tuoi $subs podcast" }) { audioTab = "podcast"; onAudioShown() }
    val favs = audio.favoriteStations().size
    big("📻", "Radio", if (favs > 0) "Le tue $favs radio preferite" else "Le radio italiane in diretta") { audioTab = "radio"; onAudioShown() }
    big("🎵", "Musica", "YouTube Music, qui dentro") { openYtMusic() }
    // one search box, with the microphone
    list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(12), dp(6), dp(10)) }
        addView(TextView(this@renderAudioHome).apply { text = "🔎  Cerca"; textSize = 15f; setTextColor(palette.textMuted); setOnClickListener { audioSearchDialog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@renderAudioHome).apply { text = "🎤"; textSize = 22f; setPadding(dp(12), dp(2), dp(12), dp(2)); contentDescription = "Cerca a voce"; setOnClickListener { requestMicAndListen() } })
    })
}

/** The «Continua» card: what is playing now, otherwise the last thing you listened to. Null if there is nothing. */
private fun MainActivity.continueCard(): View? {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val now = runCatching { nowPlaying() }.getOrNull()
    var label = "CONTINUA"; var title: String; var sub: String; var art: String? = null; var bmp: android.graphics.Bitmap? = null
    var playing = false
    val onPlay: () -> Unit; val onOpen: () -> Unit
    if (now != null) {
        label = if (now.playing) "IN ASCOLTO" else "IN PAUSA"
        title = now.title; sub = now.sub; art = now.artUrl; bmp = now.artBmp; playing = now.playing
        onPlay = { musicAction("toggle"); uiHandler.postDelayed({ if (currentTab == MainActivity.TAB_AUDIO) renderAudio() }, 600) }
        onOpen = { if (now.source == "web" && Music.webFromMusic) openYtMusic() else showPlayerSheet() }
    } else {
        val ep = AudioStore.recent(this).firstOrNull { !AudioStore.isFinished(this, it.id) }
        val radio = runCatching {
            com.google.gson.Gson().fromJson<List<RadioStation>>(getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE).getString("recentRadio", null), object : com.google.gson.reflect.TypeToken<List<RadioStation>>() {}.type)
        }.getOrNull().orEmpty().filterNotNull().firstOrNull()
        val song = WebMedia.recents(this).firstOrNull { (it.music || it.url.contains("music.youtube.com")) && Music.cleanTitle(it.title).isNotBlank() }
        val kind = AudioStore.last(this).takeIf { k -> (k == "podcast" && ep != null) || (k == "radio" && radio != null) || (k == "music" && song != null) }
            ?: when { ep != null -> "podcast"; radio != null -> "radio"; song != null -> "music"; else -> return null }
        when (kind) {
            "podcast" -> { val e = ep!!; title = e.title; sub = e.podcastTitle + episodeProgress(e).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(); art = e.artwork
                onPlay = { playEpisode(e) }; onOpen = { playEpisode(e, showPlayer = true) } }
            "radio" -> { val r = radio!!; title = r.name; sub = "Radio in diretta"; art = r.favicon
                onPlay = { playRadio(r) }; onOpen = { playRadio(r) } }
            else -> { val m = song!!; title = Music.cleanTitle(m.title); sub = m.sub.ifBlank { "YouTube Music" }; art = m.art.ifBlank { null }
                onPlay = { openYtMusic(m.url) }; onOpen = { openYtMusic(m.url) } }
        }
    }
    val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) } } }
    when { bmp != null -> cover.setImageBitmap(bmp); art != null -> cover.load(art) { error(R.drawable.ic_nav_audio) }; else -> cover.setImageResource(R.drawable.ic_nav_audio) }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(12), dp(14))
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2563EB.toInt(), 0xFF1E3A8A.toInt())).apply { cornerRadius = dp(22).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(4), dp(6), dp(10)) }
        addView(cover, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(LinearLayout(this@continueCard).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
            addView(TextView(this@continueCard).apply { text = label; textSize = 11.5f; letterSpacing = 0.08f; setTextColor(0xCCFFFFFF.toInt()) })
            addView(TextView(this@continueCard).apply { text = title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@continueCard).apply { text = sub; textSize = 13f; setTextColor(0xDDFFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@continueCard).apply {
            text = if (playing) "⏸" else "▶"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(0xFF1E3A8A.toInt())
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
            contentDescription = if (playing) "Pausa" else "Riproduci"
            setOnClickListener { onPlay() }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        setOnClickListener { onOpen() }
    }
}

/** One search for everything: music (YouTube Music), podcasts, radio. */
internal fun MainActivity.audioSearchDialog() {
    val et = field("Cosa vuoi ascoltare?", null)
    val box = form(et)
    val chips = com.google.android.material.chip.ChipGroup(this)
    lateinit var dlg: androidx.appcompat.app.AlertDialog
    WebMedia.searches(this).take(6).forEach { q -> chips.addView(com.google.android.material.chip.Chip(this).apply { text = "🕘 $q"; setOnClickListener { et.setText(q); et.setSelection(q.length) } }) }
    box.addView(chips)
    fun q() = et.text.toString().trim().takeIf { it.isNotEmpty() }
    dlg = MaterialAlertDialogBuilder(this).setTitle("🔎 Cerca").setMessage("Scrivi e scegli dove cercare").setView(box)
        .setPositiveButton("🎵 Musica") { _, _ -> q()?.let { searchMusic(it, "ytmusic") } }
        .setNeutralButton("📻 Radio") { _, _ -> q()?.let { showRadioSearch(it) } }
        .setNegativeButton("🎙 Podcast") { _, _ -> q()?.let { showPodcastSearch(it) } }.create()
    dlg.show()
}

/** The compact «now playing» card: cover, title, artist, play/pause and full screen. */
internal fun MainActivity.playingCard(n: NowView): View {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    showArtPublic(cover, n)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(8), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(4), dp(8), dp(10)) }
        addView(cover, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(LinearLayout(this@playingCard).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0)
            addView(TextView(this@playingCard).apply { text = n.title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@playingCard).apply { text = n.sub; textSize = 13f; setTextColor(palette.textMuted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@playingCard).apply { text = if (n.playing) "⏸" else "▶"; textSize = 30f; setTextColor(palette.text); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { musicAction("toggle"); uiHandler.postDelayed({ renderAudio() }, 600) } })
        addView(TextView(this@playingCard).apply { text = "⛶"; textSize = 24f; setTextColor(palette.text); setPadding(dp(8), dp(4), dp(8), dp(4)); contentDescription = "Schermo intero"; setOnClickListener { showNowPlayingFull() } })
        setOnClickListener { showPlayerSheet() }
    }
}

/** Search box with the recent searches as chips. */
internal fun MainActivity.musicSearchDialog() {
    val et = field("Cerca su YouTube Music", null)
    val box = form(et)
    val chips = com.google.android.material.chip.ChipGroup(this)
    lateinit var dlg: androidx.appcompat.app.AlertDialog
    WebMedia.searches(this).forEach { q -> chips.addView(com.google.android.material.chip.Chip(this).apply { text = "🕘 $q"; setOnClickListener { dlg.dismiss(); searchMusic(q, "ytmusic") } }) }
    box.addView(chips)
    dlg = MaterialAlertDialogBuilder(this).setTitle("🔎 Cerca").setView(box)
        .setPositiveButton("Cerca") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { searchMusic(it, "ytmusic") } }
        .setNegativeButton("Annulla", null).create()
    dlg.show()
}

/** In Ascolta: a YouTube link copied elsewhere is offered once («Ascoltare questo?»). */
internal fun MainActivity.checkYoutubeClip() {
    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
    val link = WebMedia.youtubeLink(text)?.takeIf { it.contains("music.youtube.com") } ?: return
    if (link.hashCode() == toolsPrefs.getInt("lastYtClip", 0)) return
    toolsPrefs.edit().putInt("lastYtClip", link.hashCode()).apply()
    com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), "🔗 Hai copiato un link di YouTube Music. Ascoltarlo?", 9000)
        .setAction("Ascolta") { openMusicLink(link) }.show()
}
