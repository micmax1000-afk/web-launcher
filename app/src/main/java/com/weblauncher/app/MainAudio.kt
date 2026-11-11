package com.weblauncher.app

import android.content.Context.DOWNLOAD_SERVICE
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.MODE_PRIVATE
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

// ======================= Podcasts & radio =======================

internal fun MainActivity.setupAudio() {
    val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
    val future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
    controllerFuture = future
    future.addListener({
        controller = runCatching { future.get() }.getOrNull()
        controller?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                updateMiniPlayer()
                playerSheetUpdate?.invoke()
            }
        })
        updateMiniPlayer()
    }, ContextCompat.getMainExecutor(this))
    val palette = AppRepository.palette(this)
    findViewById<com.google.android.material.card.MaterialCardView>(R.id.miniPlayer).apply {
        setCardBackgroundColor(palette.elevated); strokeColor = palette.stroke
        setOnClickListener { showPlayerSheet() }
    }
    findViewById<TextView>(R.id.miniTitle).setTextColor(palette.text)
    findViewById<TextView>(R.id.miniSub).setTextColor(palette.textMuted)
    findViewById<android.widget.ImageButton>(R.id.miniPlay).apply {
        setColorFilter(palette.text)
        setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    }
    findViewById<android.widget.ImageButton>(R.id.miniClose).apply {
        setColorFilter(palette.textMuted)
        setOnClickListener { controller?.run { stop(); clearMediaItems() }; updateMiniPlayer() }
    }
    findViewById<View>(R.id.audioPanel).setBackgroundColor(palette.bg)
    findViewById<TextView>(R.id.audioHeading).setTextColor(palette.text)
    findViewById<View>(R.id.btnAudioSearch).setOnClickListener {
        when (audioTab) { "radio" -> showRadioSearch(); "video" -> addChannelDialog(); "music" -> searchMusic(); else -> showPodcastSearch() }
    }
    val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
    refresh.setOnRefreshListener {
        when (audioTab) {
            "radio" -> { radioCache.clear(); refresh.isRefreshing = false; renderAudio() }
            "video" -> refreshYtVideos()
            "music" -> { songs = null; refresh.isRefreshing = false; renderAudio() }
            else -> refreshEpisodes()
        }
    }
}

internal fun MainActivity.updateMiniPlayer() {
    val c = controller
    val card = findViewById<View>(R.id.miniPlayer)
    val item = c?.currentMediaItem
    val show = c != null && item != null && c.playbackState != androidx.media3.common.Player.STATE_IDLE
    card.isVisible = show
    if (!show) return
    val md = item!!.mediaMetadata
    findViewById<TextView>(R.id.miniTitle).text = md.title ?: ""
    findViewById<TextView>(R.id.miniSub).text = when {
        c!!.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> "Caricamento…"
        else -> md.artist ?: ""
    }
    findViewById<android.widget.ImageButton>(R.id.miniPlay).setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    val art = findViewById<ImageView>(R.id.miniArt)
    if (art.tag != md.artworkUri) {
        art.tag = md.artworkUri
        art.load(md.artworkUri) { transformations(coil.transform.RoundedCornersTransformation(16f)); error(R.drawable.ic_nav_audio) }
    }
}

internal fun MainActivity.ensureNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !getSharedPreferences("wl_audio", MODE_PRIVATE).getBoolean("asked_notif", false)
    ) {
        getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply()
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun MainActivity.playMedia(id: String, uri: String, title: String, sub: String, art: String?, start: Long) {
    val c = controller ?: run { toast("Lettore non pronto, riprova tra un attimo"); return }
    ensureNotificationPermission()
    val md = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(title).setArtist(sub).setIsPlayable(true)
        .apply { art?.let { setArtworkUri(Uri.parse(it)) } }
        .build()
    val item = androidx.media3.common.MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(md).build()
    c.setMediaItem(item, start)
    c.setPlaybackSpeed(if (id.startsWith("radio:")) 1f else getSharedPreferences("wl_audio", MODE_PRIVATE).getFloat("speed", 1f))
    c.prepare()
    c.play()
    updateMiniPlayer()
}

internal fun MainActivity.playEpisode(e: Episode, showPlayer: Boolean = false) {
    val local = audio.localFile(e)
    val uri = if (audio.isDownloaded(e) && !isDownloading(e)) Uri.fromFile(local).toString() else e.audioUrl
    val start = if (AudioStore.isFinished(this, e.id)) 0L else AudioStore.position(this, e.id)
    playMedia(e.id, uri, e.title, e.podcastTitle, e.artwork, start)
    AudioStore.addRecent(this, e)
    if (currentTab == TAB_AUDIO && audioTab == "podcast") renderAudio()
    if (showPlayer) uiHandler.postDelayed({ if (!isFinishing) showPlayerSheet() }, 350)   // the player opens on top, so you see what is playing
}

internal fun MainActivity.playRadio(s: RadioStation) {
    playMedia("radio:" + s.id, s.url, s.name, "Radio" + (s.state.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), s.favicon, 0L)
}

internal fun MainActivity.isDownloading(e: Episode): Boolean {
    val dmId = getSharedPreferences("wl_audio", MODE_PRIVATE).getLong("dl_" + e.id, -1L)
    if (dmId < 0) return false
    val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
    val status = runCatching {
        dm.query(android.app.DownloadManager.Query().setFilterById(dmId))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)) else -1
        }
    }.getOrNull() ?: -1
    val running = status == android.app.DownloadManager.STATUS_RUNNING || status == android.app.DownloadManager.STATUS_PENDING ||
        status == android.app.DownloadManager.STATUS_PAUSED
    if (!running) getSharedPreferences("wl_audio", MODE_PRIVATE).edit().remove("dl_" + e.id).apply()
    return running
}

internal fun MainActivity.downloadEpisode(e: Episode) {
    try {
        val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
        val req = android.app.DownloadManager.Request(Uri.parse(e.audioUrl))
            .setTitle(e.title)
            .setDescription(e.podcastTitle)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_PODCASTS, audio.localFile(e).name)
        val id = dm.enqueue(req)
        getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putLong("dl_" + e.id, id).apply()
        toast("Download avviato: lo trovi anche offline")
    } catch (_: Exception) { toast("Download non riuscito") }
}

internal fun MainActivity.onAudioShown() {
    if (audioTab == "podcast" && episodes.isEmpty()) episodes = audio.cachedEpisodes()
    renderAudio()
    val last = getSharedPreferences("wl_audio", MODE_PRIVATE).getLong("refreshed", 0L)
    if (audioTab == "podcast" && audio.subscriptions().isNotEmpty() && System.currentTimeMillis() - last > 3600_000L) refreshEpisodes()
}

internal fun MainActivity.refreshEpisodes() {
    val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
    refresh.isRefreshing = true
    Thread {
        val list = runCatching { audio.refreshEpisodes() }.getOrDefault(emptyList())
        runOnUiThread {
            refresh.isRefreshing = false
            episodes = list
            getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putLong("refreshed", System.currentTimeMillis()).apply()
            if (currentTab == TAB_AUDIO) renderAudio()
        }
    }.start()
}

internal fun MainActivity.fmtDur(sec: Long): String = when {
    sec <= 0 -> ""
    sec >= 3600 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "${(sec / 60).coerceAtLeast(1)} min"
}

/** Row with artwork, two lines and optional trailing buttons. */
internal fun MainActivity.mediaRow(
    parent: android.widget.LinearLayout, art: String?, title: String, sub: String, progress: String? = null,
    trailing: List<Pair<Int, () -> Unit>> = emptyList(), onLong: (() -> Unit)? = null, onClick: () -> Unit
) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(8), 0, dp(8))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    row.addView(ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(palette.iconTile) }
        load(art) { transformations(coil.transform.RoundedCornersTransformation(24f)); error(R.drawable.ic_nav_audio) }
    })
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    box.addView(TextView(this).apply {
        text = title; textSize = 15f; setTextColor(palette.text); maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD)
    })
    box.addView(TextView(this).apply {
        text = sub; textSize = 12.5f; setTextColor(palette.textMuted); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
    })
    if (!progress.isNullOrBlank()) box.addView(TextView(this).apply {
        text = progress; textSize = 12f
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
    })
    row.addView(box)
    trailing.forEach { (icon, action) ->
        row.addView(android.widget.ImageButton(this).apply {
            setImageResource(icon); setColorFilter(palette.text); background = null
            setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { action() }
        })
    }
    parent.addView(row)
}

internal fun MainActivity.episodeProgress(e: Episode): String {
    if (AudioStore.isFinished(this, e.id)) return "✓ Ascoltato"
    val pos = AudioStore.position(this, e.id)
    val dur = AudioStore.duration(this, e.id).takeIf { it > 0 } ?: (e.durationSec * 1000)
    val dl = if (isDownloading(e)) "⬇ in download" else if (audio.isDownloaded(e)) "⬇ offline" else ""
    val left = if (pos > 30_000 && dur > pos) "⏵ ${fmtDur((dur - pos) / 1000)} rimasti" else ""
    return listOf(left, dl).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun MainActivity.episodeRow(parent: android.widget.LinearLayout, e: Episode, showPodcast: Boolean = true) {
    val sub = listOfNotNull(
        e.podcastTitle.takeIf { showPodcast && it.isNotBlank() },
        e.time.takeIf { it > 0 }?.let { NewsAdapter.ago(it) },
        fmtDur(e.durationSec).ifBlank { null }
    ).joinToString(" · ")
    mediaRow(parent, e.artwork, e.title, sub, episodeProgress(e),
        trailing = listOf(R.drawable.ic_play to { playEpisode(e, showPlayer = true) }),
        onLong = { episodeMenu(e) }) { playEpisode(e, showPlayer = true) }
}

internal fun MainActivity.episodeMenu(e: Episode) {
    val downloaded = audio.isDownloaded(e) && !isDownloading(e)
    val opts = arrayOf(
        "▶ Riproduci",
        if (downloaded) "Elimina download" else "⬇ Scarica per ascoltare offline",
        if (AudioStore.isFinished(this, e.id)) "Segna come da ascoltare" else "Segna come ascoltato",
        "Descrizione", "Condividi"
    )
    MaterialAlertDialogBuilder(this).setTitle(e.title).setItems(opts) { _, w ->
        when (w) {
            0 -> playEpisode(e, showPlayer = true)
            1 -> if (downloaded) { audio.localFile(e).delete(); toast("Download eliminato"); renderAudio() } else downloadEpisode(e)
            2 -> {
                if (AudioStore.isFinished(this, e.id)) getSharedPreferences("wl_audio", MODE_PRIVATE).edit().remove("done_" + e.id).apply()
                else AudioStore.markFinished(this, e.id)
                renderAudio()
            }
            3 -> MaterialAlertDialogBuilder(this).setTitle(e.title).setMessage(e.description.ifBlank { "Nessuna descrizione" })
                .setPositiveButton("OK", null).show()
            4 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${e.podcastTitle} — ${e.title}\n${e.audioUrl}")
            }, "Condividi episodio"))
        }
    }.show()
}

internal fun MainActivity.renderAudio() {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val tabs = findViewById<android.widget.LinearLayout>(R.id.audioTabs)
    tabs.removeAllViews()
    listOf("podcast" to "🎙 Podcast", "radio" to "📻 Radio", "music" to "🎵 Musica", "video" to "▶ Video").forEach { (key, label) ->
        val active = key == audioTab
        tabs.addView(TextView(this).apply {
            text = label; textSize = 14f
            setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d; setColor(if (active) accent else palette.card)
                setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { audioTab = key; onAudioShown() }
        })
    }
    val list = findViewById<android.widget.LinearLayout>(R.id.audioList)
    list.removeAllViews()
    if (audioTab == "radio") { renderRadio(list); return }
    if (audioTab == "video") { renderVideos(list); return }
    if (audioTab == "music") { renderMusic(list); return }

    // continua ad ascoltare: gli episodi iniziati e non finiti (anche di podcast non seguiti)
    val resume = AudioStore.recent(this).filter { !AudioStore.isFinished(this, it.id) }.take(4)
    if (resume.isNotEmpty()) {
        sectionLabel(list, "CONTINUA AD ASCOLTARE")
        resume.forEach { episodeRow(list, it) }
    }
    val subs = audio.subscriptions()
    if (subs.isEmpty()) {
        list.addView(TextView(this).apply {
            text = "Nessun podcast seguito.\nCerca per nome, apri un podcast e tocca «Segui»: lo ritrovi qui con i nuovi episodi."
            textSize = 15f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
            setPadding(dp(20), dp(30), dp(20), dp(16))
        })
        sectionLabel(list, "PROVA A CERCARE")
        val flow = com.google.android.material.chip.ChipGroup(this).apply { setPadding(dp(6), 0, dp(6), 0) }
        listOf("Il Post", "Rai Radio 3", "Storie", "Geopolitica", "Tecnologia", "Cucina", "Storia", "Calcio", "Economia", "Wired")
            .forEach { term ->
                flow.addView(com.google.android.material.chip.Chip(this).apply {
                    text = term
                    setOnClickListener { showPodcastSearch(term) }
                })
            }
        list.addView(flow)
        return
    }
    sectionLabel(list, "I MIEI PODCAST")
    val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    subs.forEach { p ->
        val cell = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(92), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { showPodcastDetail(p) }
        }
        cell.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(92), dp(92))
            load(p.artwork) { transformations(coil.transform.RoundedCornersTransformation(28f)); error(R.drawable.ic_nav_audio) }
        })
        cell.addView(TextView(this).apply {
            text = p.title; textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text); setPadding(0, dp(4), 0, 0)
        })
        row.addView(cell)
    }
    list.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setPadding(dp(6), 0, 0, 0)
        addView(row)
    })
    sectionLabel(list, "NUOVI EPISODI")
    if (episodes.isEmpty()) {
        list.addView(TextView(this).apply {
            text = "Trascina in giù per caricare gli episodi"; textSize = 14f; setTextColor(palette.textMuted)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        })
    }
    episodes.take(60).forEach { episodeRow(list, it) }
}

internal fun MainActivity.renderRadio(list: android.widget.LinearLayout) {
    val favs = audio.favoriteStations()
    fun stationRows(target: android.widget.LinearLayout, stations: List<RadioStation>) {
        stations.forEach { s ->
            val fav = favs.any { it.id == s.id }
            val sub = listOfNotNull(
                s.state.takeIf { it.isNotBlank() },
                s.tags.split(",").take(3).joinToString(", ").takeIf { it.isNotBlank() },
                s.bitrate.takeIf { it > 0 }?.let { "$it kbps" }
            ).joinToString(" · ")
            mediaRow(target, s.favicon, s.name, sub, null,
                trailing = listOf((if (fav) R.drawable.ic_web_star_filled else R.drawable.ic_web_star) to {
                    val now = audio.toggleFavorite(s)
                    toast(if (now) "Aggiunta alle preferite" else "Tolta dalle preferite")
                    renderAudio()
                })) { playRadio(s) }
        }
    }
    if (favs.isNotEmpty()) { sectionLabel(list, "⭐ PREFERITE"); stationRows(list, favs) }
    // optional region filter
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    val chipRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding((6 * d).toInt(), (10 * d).toInt(), 0, (4 * d).toInt()) }
    (listOf("Tutte") + AudioRepository.REGIONS.map { it.first }).forEach { r ->
        val active = r == radioRegion
        chipRow.addView(TextView(this).apply {
            text = r; textSize = 13f
            setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d; setColor(if (active) accent else palette.card)
                setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { radioRegion = r; renderAudio() }
        })
    }
    list.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
    val label = radioRegion
    sectionLabel(list, if (label == "Tutte") "TUTTE LE RADIO · LE PIÙ ASCOLTATE" else label.uppercase())
    val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
    list.addView(box)
    val cached = radioCache[label]
    if (cached != null) {
        if (cached.isEmpty()) box.addView(TextView(this).apply {
            text = "Nessuna stazione trovata. Prova «Cerca» con il nome della radio."; textSize = 13f
            setTextColor(palette.textMuted); setPadding(20, 10, 10, 10)
        })
        stationRows(box, cached)
    } else {
        box.addView(TextView(this).apply { text = "Caricamento…"; textSize = 13f; setTextColor(palette.textMuted); setPadding(20, 10, 10, 10) })
        Thread {
            val res = runCatching {
                if (label == "Tutte") audio.nationalStations()
                else audio.regionStations(AudioRepository.REGIONS.first { it.first == label }.second)
            }.getOrDefault(emptyList())
            runOnUiThread {
                radioCache[label] = res
                if (currentTab == TAB_AUDIO && audioTab == "radio") renderAudio()
            }
        }.start()
    }
}

internal fun MainActivity.searchField(root: android.widget.LinearLayout, hint: String, initial: String?, onSearch: (String) -> Unit): EditText {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    val et = EditText(this).apply {
        this.hint = hint
        setText(initial.orEmpty())
        setSingleLine(); textSize = 16f
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setTextColor(palette.text); setHintTextColor(palette.textMuted)
        setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24 * d; setColor(palette.iconTile); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())
            .apply { setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()) }
        setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                val q = v.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                    onSearch(q)
                }
                true
            } else false
        }
    }
    root.addView(et, 1)
    return et
}

internal fun MainActivity.showPodcastSearch(initial: String? = null) {
    openPage("Cerca podcast") { root, body, _ ->
        fun run(q: String) {
            body.removeAllViews()
            emptyLabel(body, "Cerco «$q»…")
            Thread {
                val res = runCatching { audio.search(q) }.getOrNull()
                runOnUiThread {
                    body.removeAllViews()
                    if (res == null) { emptyLabel(body, "Ricerca non riuscita: controlla la connessione"); return@runOnUiThread }
                    if (res.isEmpty()) { emptyLabel(body, "Nessun podcast trovato"); return@runOnUiThread }
                    res.forEach { p ->
                        mediaRow(body, p.artwork, p.title, p.author, if (audio.isSubscribed(p.feedUrl)) "✓ Lo segui" else null) { showPodcastDetail(p) }
                    }
                }
            }.start()
        }
        val et = searchField(root, "Nome del podcast o argomento", initial) { run(it) }
        if (initial != null) run(initial) else { emptyLabel(body, "Catalogo gratuito di Apple Podcasts:\nnon serve un account."); et.requestFocus() }
    }
}

internal fun MainActivity.showPodcastDetail(p: Podcast) {
    val subscribed = audio.isSubscribed(p.feedUrl)
    openPage(p.title.take(28), if (subscribed) "Non seguire" else "Segui", { dlg ->
        if (audio.isSubscribed(p.feedUrl)) { audio.unsubscribe(p.feedUrl); episodes = audio.cachedEpisodes(); toast("Non segui più ${p.title}") }
        else { audio.subscribe(p); toast("Ora segui ${p.title}"); refreshEpisodes() }
        dlg.dismiss()
        if (currentTab == TAB_AUDIO) renderAudio()
    }) { _, body, _ ->
        val d = resources.displayMetrics.density
        body.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams((160 * d).toInt(), (160 * d).toInt()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = (6 * d).toInt()
            }
            load(p.artwork) { transformations(coil.transform.RoundedCornersTransformation(40f)); error(R.drawable.ic_nav_audio) }
        })
        body.addView(TextView(this).apply {
            text = p.author; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTextColor(AppRepository.palette(this@showPodcastDetail).textMuted); setPadding(0, (8 * d).toInt(), 0, (6 * d).toInt())
        })
        val listBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        body.addView(listBox)
        emptyLabel(listBox, "Carico gli episodi…")
        Thread {
            val eps = runCatching { audio.episodes(p.feedUrl) }.getOrNull()
            runOnUiThread {
                listBox.removeAllViews()
                if (eps.isNullOrEmpty()) { emptyLabel(listBox, "Nessun episodio disponibile"); return@runOnUiThread }
                sectionLabel(listBox, "EPISODI · ${eps.size}")
                eps.take(100).forEach { episodeRow(listBox, it.copy(artwork = it.artwork ?: p.artwork), showPodcast = false) }
            }
        }.start()
    }
}

internal fun MainActivity.showRadioSearch() {
    openPage("Cerca radio") { root, body, _ ->
        val et = searchField(root, "Nome della radio, es. RTL, Deejay, Rai", null) { q ->
            body.removeAllViews()
            emptyLabel(body, "Cerco «$q»…")
            Thread {
                val res = runCatching { audio.searchStations(q) }.getOrDefault(emptyList())
                runOnUiThread {
                    body.removeAllViews()
                    if (res.isEmpty()) { emptyLabel(body, "Nessuna radio trovata"); return@runOnUiThread }
                    res.forEach { s ->
                        mediaRow(body, s.favicon, s.name, listOf(s.state, s.tags.split(",").take(2).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                            trailing = listOf(R.drawable.ic_web_star to {
                                toast(if (audio.toggleFavorite(s)) "Aggiunta alle preferite" else "Tolta dalle preferite")
                            })) { playRadio(s) }
                    }
                }
            }.start()
        }
        emptyLabel(body, "Catalogo libero Radio Browser,\nsenza account.")
        et.requestFocus()
    }
}

internal fun MainActivity.fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

internal fun MainActivity.showPlayerSheet() {
    val c = controller ?: return
    if (c.currentMediaItem == null) return
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(8), dp(24), dp(24))
    }
    val art = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(230), dp(230)).apply { bottomMargin = dp(16) }
    }
    val title = TextView(this).apply {
        textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
        gravity = android.view.Gravity.CENTER; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val sub = TextView(this).apply { textSize = 14f; setTextColor(palette.textMuted); gravity = android.view.Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) }
    val seek = android.widget.SeekBar(this).apply {
        max = 1000
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(accent)
    }
    val times = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    val tPos = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted); layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
    val tDur = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted) }
    times.addView(tPos); times.addView(tDur)
    val controls = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
    }
    fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(palette.text); gravity = android.view.Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { onClick() }
    }
    val back = textBtn("↺ 15") { c.seekBack() }
    val play = android.widget.ImageButton(this).apply {
        setColorFilter(android.graphics.Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(18), 0, dp(18), 0) }
        setOnClickListener { if (c.isPlaying) c.pause() else c.play() }
    }
    val fwd = textBtn("30 ↻") { c.seekForward() }
    controls.addView(back); controls.addView(play); controls.addView(fwd)
    val extras = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
    fun chip(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(accent); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
        layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) }
        setOnClickListener { onClick(this) }
        extras.addView(this)
    }
    val speeds = floatArrayOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
    val speedChip = chip("1x") { v ->
        val cur = c.playbackParameters.speed
        val next = speeds.firstOrNull { it > cur + 0.01f } ?: speeds[0]
        c.setPlaybackSpeed(next)
        getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putFloat("speed", next).apply()
        v.text = (if (next % 1f == 0f) next.toInt().toString() else next.toString()) + "x"
    }
    val sleepChip = chip("⏾ Timer") { v ->
        val opts = arrayOf("Spento", "15 minuti", "30 minuti", "45 minuti", "60 minuti")
        MaterialAlertDialogBuilder(this).setTitle("Spegni dopo").setItems(opts) { _, w ->
            uiHandler.removeCallbacks(sleepRunnable)
            sleepAt = 0L
            if (w > 0) {
                val ms = w * 15 * 60_000L
                sleepAt = System.currentTimeMillis() + ms
                uiHandler.postDelayed(sleepRunnable, ms)
            }
            v.text = if (sleepAt > 0) "⏾ ${w * 15} min" else "⏾ Timer"
        }.show()
    }
    if (sleepAt > 0) sleepChip.text = "⏾ ${((sleepAt - System.currentTimeMillis()) / 60_000) + 1} min"
    root.addView(art); root.addView(title); root.addView(sub); root.addView(seek); root.addView(times); root.addView(controls); root.addView(extras)
    var dragging = false
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser && c.duration > 0) tPos.text = fmtTime(c.duration * p / 1000)
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { dragging = true }
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
            dragging = false
            if (c.duration > 0) c.seekTo(c.duration * (sb?.progress ?: 0) / 1000)
        }
    })
    var lastArt: Uri? = null
    fun update() {
        val md = c.currentMediaItem?.mediaMetadata ?: return
        title.text = md.title ?: ""
        sub.text = md.artist ?: ""
        if (md.artworkUri != lastArt) {
            lastArt = md.artworkUri
            art.load(md.artworkUri) { transformations(coil.transform.RoundedCornersTransformation(48f)); error(R.drawable.ic_nav_audio) }
        }
        play.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val live = c.isCurrentMediaItemLive || c.duration <= 0 || c.currentMediaItem?.mediaId?.startsWith("radio:") == true
        seek.isVisible = !live; times.isVisible = !live; back.isVisible = !live; fwd.isVisible = !live; speedChip.isVisible = !live
        if (!live && !dragging) {
            seek.progress = (c.currentPosition * 1000 / c.duration).toInt()
            tPos.text = fmtTime(c.currentPosition)
            tDur.text = "−" + fmtTime(c.duration - c.currentPosition)
        }
        val sp = c.playbackParameters.speed
        speedChip.text = (if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()) + "x"
    }
    val ticker = object : Runnable { override fun run() { update(); uiHandler.postDelayed(this, 500) } }
    playerSheetUpdate = { update() }
    sheet.setOnDismissListener { uiHandler.removeCallbacks(ticker); playerSheetUpdate = null }
    sheet.setContentView(android.widget.ScrollView(this).apply { addView(root) })
    sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    sheet.behavior.skipCollapsed = true
    update()
    uiHandler.post(ticker)
    sheet.show()
}

internal fun MainActivity.musicPerm() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun MainActivity.loadSongs(): List<Song> = runCatching {
    val out = mutableListOf<Song>()
    contentResolver.query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.TITLE, android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM, android.provider.MediaStore.Audio.Media.ALBUM_ID, android.provider.MediaStore.Audio.Media.DURATION),
        android.provider.MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + android.provider.MediaStore.Audio.Media.DURATION + " > 30000", null,
        android.provider.MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { c ->
        while (c.moveToNext()) out.add(Song(c.getLong(0), c.getString(1) ?: "Brano", c.getString(2)?.takeIf { it != "<unknown>" } ?: "Artista sconosciuto",
            c.getString(3) ?: "", c.getLong(4), c.getLong(5)))
    }
    out
}.getOrDefault(emptyList())

internal fun MainActivity.albumArt(albumId: Long) = "content://media/external/audio/albumart/$albumId"

internal fun MainActivity.playSongs(list: List<Song>, index: Int, shuffle: Boolean = false) {
    val c = controller ?: run { toast("Lettore non pronto"); return }
    ensureNotificationPermission()
    val items = list.map { s ->
        androidx.media3.common.MediaItem.Builder().setMediaId("music:${s.id}")
            .setUri(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                .setArtworkUri(Uri.parse(albumArt(s.albumId))).setIsPlayable(true).build()).build()
    }
    c.setMediaItems(items, index, 0L)
    c.shuffleModeEnabled = shuffle
    c.setPlaybackSpeed(1f)
    c.prepare(); c.play()
    updateMiniPlayer()
}

internal fun MainActivity.renderMusic(list: android.widget.LinearLayout) {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    if (musicFilter == null) {
        musicSections(list, leave = {}, rerender = { renderAudio() })
        sectionLabel(list, "SUL TELEFONO")
    }
    if (ContextCompat.checkSelfPermission(this, musicPerm()) != PackageManager.PERMISSION_GRANTED) {
        list.addView(TextView(this).apply {
            text = "Ascolta gli MP3 e la musica salvata sul telefono, anche a schermo spento."; textSize = 14f; setTextColor(palette.textMuted)
            gravity = android.view.Gravity.CENTER; setPadding(dp(20), dp(8), dp(20), dp(8))
        })
        list.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Consenti l'accesso alla musica"; isAllCaps = false; setOnClickListener { musicPermission.launch(musicPerm()) }
        })
        return
    }
    val all = songs ?: loadSongs().also { songs = it }
    if (all.isEmpty()) { hintLabel(list, "Nessuna canzone salvata sul telefono (cartella Music)."); return }
    val tabs = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), 0, dp(4)) }
    listOf("songs" to "Brani", "albums" to "Album", "artists" to "Artisti").forEach { (k, l) ->
        val active = k == musicView && musicFilter == null
        tabs.addView(TextView(this).apply {
            text = l; textSize = 13f; setTextColor(if (active) accent else palette.text)
            if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { musicView = k; musicFilter = null; renderAudio() }
        })
    }
    tabs.addView(TextView(this).apply {
        text = "🔀 Casuale"; textSize = 13f; setTextColor(accent); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { playSongs(all, (all.indices).random(), shuffle = true) }
    })
    list.addView(tabs)
    val filter = musicFilter
    if (filter != null) {
        val shown = all.filter { if (filter.first == "album") it.album == filter.second else it.artist == filter.second }
        list.addView(TextView(this).apply {
            text = "‹  ${filter.second} · ${shown.size} brani"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
            setPadding(dp(10), dp(8), 0, dp(4)); setOnClickListener { musicFilter = null; renderAudio() }
        })
        shown.forEachIndexed { i, s -> mediaRow(list, albumArt(s.albumId), s.title, "${s.artist} · ${fmtTime(s.duration)}") { playSongs(shown, i) } }
        return
    }
    when (musicView) {
        "albums" -> all.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (album, ss) ->
            mediaRow(list, albumArt(ss.first().albumId), album.ifBlank { "Senza album" }, "${ss.first().artist} · ${ss.size} brani") { musicFilter = "album" to album; renderAudio() }
        }
        "artists" -> all.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (artist, ss) ->
            mediaRow(list, albumArt(ss.first().albumId), artist, "${ss.size} brani") { musicFilter = "artist" to artist; renderAudio() }
        }
        else -> all.take(500).forEachIndexed { i, s -> mediaRow(list, albumArt(s.albumId), s.title, "${s.artist} · ${fmtTime(s.duration)}") { playSongs(all, i) } }
    }
}

internal fun MainActivity.searchMusic() {
    val all = songs ?: loadSongs().also { songs = it }
    openPage("Cerca musica") { root, body, _ ->
        val et = searchField(root, "Titolo, artista o album", null) { q ->
            body.removeAllViews()
            val ql = q.lowercase(Locale.ITALIAN)
            val res = all.filter { it.title.lowercase(Locale.ITALIAN).contains(ql) || it.artist.lowercase(Locale.ITALIAN).contains(ql) || it.album.lowercase(Locale.ITALIAN).contains(ql) }
            if (res.isEmpty()) emptyLabel(body, "Nessun brano")
            res.forEachIndexed { i, s -> mediaRow(body, albumArt(s.albumId), s.title, "${s.artist} · ${s.album}") { playSongs(res, i) } }
        }
        et.requestFocus()
    }
}
