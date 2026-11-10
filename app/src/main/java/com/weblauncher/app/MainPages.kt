package com.weblauncher.app

import android.content.Context.AUDIO_SERVICE

import android.content.Context.DOWNLOAD_SERVICE
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

// ======================= Full-screen pages: history, downloads =======================

internal fun MainActivity.openPage(
    title: String,
    actionLabel: String? = null,
    onAction: ((androidx.appcompat.app.AppCompatDialog) -> Unit)? = null,
    build: (root: android.widget.LinearLayout, body: android.widget.LinearLayout, dialog: androidx.appcompat.app.AppCompatDialog) -> Unit
) {
    AppRepository.restoreUiMode(this)
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(palette.bg)
    }
    val header = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(12), dp(8))
    }
    header.addView(android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_web_back)
        setColorFilter(palette.text)
        background = null
        contentDescription = "Indietro"
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { dialog.dismiss() }
    })
    header.addView(TextView(this).apply {
        text = title
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(palette.text)
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    if (actionLabel != null && onAction != null) header.addView(TextView(this).apply {
        text = actionLabel
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(accent)
        setPadding(dp(12), dp(10), dp(8), dp(10))
        setOnClickListener { onAction(dialog) }
    })
    root.addView(header)
    val body = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(24))
    }
    root.addView(android.widget.ScrollView(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(body)
    })
    build(root, body, dialog)
    dialog.setContentView(root)
    dialog.window?.apply {
        setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg))
        statusBarColor = palette.bg
        navigationBarColor = palette.bg
        // dark icons on a light page, light icons on a dark one (they were white on light: the clock was unreadable)
        val ctl = androidx.core.view.WindowInsetsControllerCompat(this, decorView)
        ctl.isAppearanceLightStatusBars = AppRepository.isLightUi(this@openPage)
        ctl.isAppearanceLightNavigationBars = AppRepository.isLightUi(this@openPage)
    }
    dialog.show()
}

internal fun MainActivity.listRow(
    body: android.widget.LinearLayout, title: String, sub: String?,
    onClick: () -> Unit, onLong: (() -> Unit)? = null, onRemove: (() -> Unit)? = null
) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val r = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(10), dp(6), 0, dp(6))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    // a leading emoji goes in a tinted tile, like the tiles of Strumenti: same look on every row, in colour
    val (rowIcon, shownTitle) = EmojiText.split(title)
    val accentColor = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    if (rowIcon != null) r.addView(TextView(this).apply {
        text = rowIcon; textSize = 19f; gravity = android.view.Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor((accentColor and 0x00FFFFFF) or 0x1F000000) }
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
    })
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    box.addView(TextView(this).apply {
        text = shownTitle; textSize = 16f; setTextColor(palette.text)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    })
    if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(palette.textMuted)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, 0)
    })
    r.addView(box)
    if (onRemove != null) r.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_web_close)
        setColorFilter(palette.textMuted)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), dp(52))
        contentDescription = "Elimina"
        setOnClickListener { onRemove() }
    })
    body.addView(r)
}

internal fun MainActivity.sectionLabel(body: android.widget.LinearLayout, text: String) {
    val d = resources.displayMetrics.density
    body.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
        setPadding((10 * d).toInt(), (18 * d).toInt(), 0, (4 * d).toInt())
    })
}

/** A short note under a list (not an empty state): small, left-aligned, no big gap above. */
internal fun MainActivity.hintLabel(body: android.widget.LinearLayout, text: String) {
    val d = resources.displayMetrics.density
    body.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(AppRepository.palette(this@hintLabel).textMuted)
        setLineSpacing(0f, 1.08f)
        setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
    })
}

internal fun MainActivity.emptyLabel(body: android.widget.LinearLayout, text: String) {
    val d = resources.displayMetrics.density
    body.addView(TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        setTextColor(AppRepository.palette(this@emptyLabel).textMuted)
        setPadding(0, (60 * d).toInt(), 0, 0)
    })
}

internal fun MainActivity.dayLabel(time: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = time
    val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    return when (day) {
        today -> "Oggi"
        yesterday -> "Ieri"
        else -> java.text.SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(java.util.Date(time))
            .replaceFirstChar { it.uppercase() }
    }
}

internal fun MainActivity.showHistoryPage() {
    openPage("Cronologia", "Cancella tutto", { dlg ->
        confirmClear("Cancellare tutta la cronologia?") {
            clearHistoryAndRecents(); dlg.dismiss(); toast("Cronologia cancellata")
        }
    }) { root, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val search = EditText(this).apply {
            hint = "Cerca nella cronologia"
            setSingleLine()
            textSize = 16f
            setTextColor(palette.text)
            setHintTextColor(palette.textMuted)
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(palette.iconTile)
                setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt()
            ).apply { setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()) }
        }
        root.addView(search, 1)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN)
        fun render() {
            body.removeAllViews()
            val q = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val list = repo.loadHistory().filter {
                q.isEmpty() || it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q)
            }.take(300)
            if (list.isEmpty()) { emptyLabel(body, if (q.isEmpty()) "La cronologia è vuota" else "Nessun risultato"); return }
            var lastDay = ""
            list.forEach { h ->
                val day = dayLabel(h.time)
                if (day != lastDay) { sectionLabel(body, day); lastDay = day }
                listRow(body, recentLabel(RecentSite(h.title, h.url)),
                    "${timeFmt.format(java.util.Date(h.time))} · ${displayUrl(h.url)}",
                    onClick = { dialog.dismiss(); openInBrowser(h.url) },
                    onLong = { showLinkMenu(h.url) },
                    onRemove = { repo.removeHistory(h.url); render() })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        render()
    }
}

internal fun MainActivity.showDownloadsPage() {
    val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
    openPage("Download", "Cartella", { _ ->
        try { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        catch (_: Exception) { toast("Apri l'app File → Download") }
    }) { _, body, _ ->
        fun open(id: Long) {
            val uri = dm.getUriForDownloadedFile(id) ?: run { toast("File non trovato"); return }
            val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
            if (mime.contains("pdf")) { openPdfFromUri(uri); return }
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { toast("Nessuna app per aprire questo file") }
        }
        fun share(id: Long) {
            val uri = dm.getUriForDownloadedFile(id) ?: return
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Condividi file"))
        }
        fun render() {
            body.removeAllViews()
            data class Dl(val id: Long, val title: String, val status: Int, val size: Long, val done: Long, val time: Long)
            val items = mutableListOf<Dl>()
            try {
                dm.query(android.app.DownloadManager.Query())?.use { c ->
                    val iId = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
                    val iTitle = c.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
                    val iStatus = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                    val iSize = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val iDone = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val iTime = c.getColumnIndex(android.app.DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                    while (c.moveToNext()) {
                        items.add(Dl(c.getLong(iId), c.getString(iTitle).orEmpty(), c.getInt(iStatus),
                            c.getLong(iSize), c.getLong(iDone), c.getLong(iTime)))
                    }
                }
            } catch (_: Exception) { }
            if (items.isEmpty()) { emptyLabel(body, "Nessun download"); return }
            var lastDay = ""
            items.sortedByDescending { it.time }.forEach { dl ->
                val day = dayLabel(dl.time)
                if (day != lastDay) { sectionLabel(body, day); lastDay = day }
                val size = android.text.format.Formatter.formatShortFileSize(this, dl.size.coerceAtLeast(0))
                val status = when (dl.status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> size
                    android.app.DownloadManager.STATUS_FAILED -> "Non riuscito"
                    android.app.DownloadManager.STATUS_PAUSED -> "In pausa"
                    else -> if (dl.size > 0) "In corso · ${dl.done * 100 / dl.size}%" else "In corso"
                }
                val ok = dl.status == android.app.DownloadManager.STATUS_SUCCESSFUL
                listRow(body, dl.title.ifBlank { "File" }, status,
                    onClick = { if (ok) open(dl.id) else toast(status) },
                    onLong = {
                        if (ok) MaterialAlertDialogBuilder(this).setTitle(dl.title)
                            .setItems(arrayOf("Apri", "Condividi")) { _, w -> if (w == 0) open(dl.id) else share(dl.id) }
                            .show()
                    },
                    onRemove = {
                        confirmClear("Eliminare \"${dl.title}\" dal telefono?") { dm.remove(dl.id); render() }
                    })
            }
        }
        render()
    }
}

// ======================= Import bookmarks from Chrome =======================

internal fun MainActivity.showBookmarkImportHelp() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Importa preferiti da Chrome")
        .setMessage(
            "Chrome sul telefono non esporta i preferiti, serve un computer:\n\n" +
            "1. Sul computer apri Chrome (con lo stesso account Google)\n" +
            "2. Menu ⋮ → Preferiti ed elenchi → Gestione preferiti\n" +
            "3. Menu ⋮ in alto a destra → Esporta preferiti\n" +
            "4. Metti il file sul telefono (Drive, email, cavo)\n" +
            "5. Tocca \"Scegli file\" e seleziona il file .html\n\n" +
            "Poi scegli tu quali siti aggiungere."
        )
        .setPositiveButton("Scegli file") { _, _ ->
            bookmarkLauncher.launch(arrayOf("text/html", "text/*", "application/octet-stream", "*/*"))
        }
        .setNegativeButton("Annulla", null)
        .show()
}

internal fun MainActivity.importBookmarks(uri: Uri) {
    val html = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) { null } ?: run { toast("Impossibile leggere il file"); return }
    val all = BookmarkImport.parse(html)
    if (all.isEmpty()) { toast("Nessun preferito trovato nel file"); return }
    val existing = apps.map { normalizeUrl(it.url).trimEnd('/').lowercase() }.toSet()
    val fresh = all.filter { it.url.trimEnd('/').lowercase() !in existing }
    if (fresh.isEmpty()) { toast("Sono già tutti nella home"); return }
    val labels = fresh.map { "${cleanTitle(it.title, it.url)}  ·  ${it.folder}" }.toTypedArray()
    val checked = BooleanArray(fresh.size) { true }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle("Scegli i siti (${fresh.size})")
        .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        .setPositiveButton("Importa", null)
        .setNeutralButton("Nessuno", null)
        .setNegativeButton("Annulla", null)
        .create()
    dialog.setOnShowListener {
        val neutral = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
        neutral.setOnClickListener {
            val select = checked.none { it }
            checked.indices.forEach { i -> checked[i] = select; dialog.listView.setItemChecked(i, select) }
            neutral.text = if (select) "Nessuno" else "Tutti"
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var added = 0
            fresh.forEachIndexed { i, b ->
                if (!checked[i]) return@forEachIndexed
                val cat = b.folder.take(30)
                if (cat !in categories) categories.add(cat)
                apps.add(AppItem(UUID.randomUUID().toString(), cleanTitle(b.title, b.url).take(40),
                    normalizeUrl(b.url), cat, false, apps.size))
                added++
            }
            repo.saveCategories(categories)
            persistApps(); refreshList()
            dialog.dismiss()
            toast(if (added == 0) "Nessun sito importato" else "Importati $added siti")
        }
    }
    dialog.show()
}

internal fun MainActivity.addMiaToHome() {
    if (apps.any { MiaAi.isMiaUrl(it.url) }) { toast("Mia AI è già nella home"); return }
    val id = UUID.randomUUID().toString()
    val icon = try {
        val dir = java.io.File(filesDir, "icons").apply { mkdirs() }
        val dest = java.io.File(dir, id)
        assets.open("mia-ai/icon.png").use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.absolutePath
    } catch (_: Exception) { null }
    val cat = "AI"
    if (cat !in categories) { categories.add(0, cat); repo.saveCategories(categories) }
    apps.add(AppItem(id, MiaAi.NAME, MiaAi.URL, cat, true, apps.size, icon))
    persistApps(); refreshList()
    toast("Mia AI aggiunta ai preferiti")
}

/** Native voice input + text-to-speech for Mia AI, reachable only from its private origin. */
internal fun MainActivity.setupMiaBridge() {
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
    val origins = setOf(MiaAi.ORIGIN)
    androidx.webkit.WebViewCompat.addWebMessageListener(webView, MiaAi.BRIDGE, origins) { _, message, sourceOrigin, isMainFrame, reply ->
        if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != MiaAi.ORIGIN) return@addWebMessageListener
        val m = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
        when (m.optString("t")) {
            "listen" -> {
                speechReply = reply
                if (!isSpeechAvailable()) {
                    runCatching { reply.postMessage("{\"t\":\"error\",\"error\":\"not-allowed\"}") }
                    toast(getString(R.string.no_speech))
                    return@addWebMessageListener
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, m.optString("lang", "it-IT"))
                    putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
                }
                try { webSpeechLauncher.launch(intent) } catch (_: Exception) {
                    runCatching { reply.postMessage("{\"t\":\"error\",\"error\":\"not-allowed\"}") }
                }
            }
            "speak" -> {
                val id = m.optInt("id")
                speakFromWeb = true
                speakReply = reply
                speak(m.optString("text"), m.optDouble("rate", 1.0).toFloat()) { speaking ->
                    // tell the page when the voice really starts and ends (ignored by the page if a newer reading exists)
                    runCatching { speakReply?.postMessage(org.json.JSONObject().put("t", if (speaking) "speakstart" else "speakend").put("id", id).toString()) }
                }
            }
            "stop" -> stopSpeaking()
        }
    }
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, MiaAi.script(AppRepository.isLightUi(this)), origins)
    }
}

/**
 * Reads [text] aloud. [onState] is told `true` when the voice starts and `false` when it ends, is stopped or fails
 * (only for the latest reading: starting a new one supersedes the previous).
 */
internal fun MainActivity.speak(text: String, rate: Float, onState: ((Boolean) -> Unit)? = null) {
    val clean = SpeechText.clean(text)
    if (clean.isBlank()) { onState?.invoke(false); return }
    speakGen++                       // anything still speaking or queued is superseded
    speakState = onState
    pendingSpeech = clean to rate
    if (tts == null) { initTts(); return }
    if (ttsReady) { pendingSpeech = null; speakNow(clean, rate) }
}

/** Stops the voice now and tells whoever was listening. Safe to call at any time. */
internal fun MainActivity.stopSpeaking() {
    speakGen++
    pendingSpeech = null
    speakFromWeb = false
    runCatching { tts?.stop() }
    val s = speakState; speakState = null
    s?.invoke(false)
}

private fun MainActivity.speakFailed(message: String) {
    val s = speakState; speakState = null
    s?.invoke(false)
    toast(message)
}

private fun MainActivity.initTts() {
    var engineRef: android.speech.tts.TextToSpeech? = null
    engineRef = android.speech.tts.TextToSpeech(this) { status ->
        val engine = engineRef
        if (status != android.speech.tts.TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false; tts = null; pendingSpeech = null
            runCatching { engine?.shutdown() }
            speakFailed("La sintesi vocale del telefono non è disponibile (Impostazioni → Sintesi vocale)")
            return@TextToSpeech
        }
        runCatching {
            engine.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        val lang = engine.setLanguage(Locale.ITALIAN)
        if (lang == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || lang == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) offerItalianVoice()
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            private fun mine(id: String?) = id != null && id.startsWith("g${speakGen}_")
            override fun onStart(id: String?) { if (mine(id)) runOnUiThread { speakState?.invoke(true) } }
            override fun onDone(id: String?) { if (mine(id) && id!!.endsWith("_last")) runOnUiThread { val s = speakState; speakState = null; s?.invoke(false) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                if (mine(id)) runOnUiThread { speakFailed("Non riesco a leggere ad alta voce: controlla il volume e la voce di sintesi del telefono") }
            }
            override fun onStop(id: String?, interrupted: Boolean) {
                if (mine(id) && id!!.endsWith("_last")) runOnUiThread { val s = speakState; speakState = null; s?.invoke(false) }
            }
        })
        ttsReady = true
        pendingSpeech?.let { (t, r) -> pendingSpeech = null; speakNow(t, r) }
    }
    tts = engineRef
}

private fun MainActivity.offerItalianVoice() {
    if (ttsWarned) return
    ttsWarned = true
    runOnUiThread {
        MaterialAlertDialogBuilder(this).setTitle("Voce italiana mancante")
            .setMessage("Il telefono non ha la voce italiana per la lettura: senza, l'audio può non partire o suonare male. Puoi installarla dalle impostazioni di sintesi vocale.")
            .setPositiveButton("Installa") { _, _ ->
                runCatching { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    .onFailure { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }
            }
            .setNegativeButton("Più tardi", null).show()
    }
}

internal fun MainActivity.speakNow(text: String, rate: Float) {
    val engine = tts ?: return
    val gen = speakGen
    val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
    if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) toast("Il volume dei contenuti multimediali è a zero: alzalo con i tasti del volume")
    engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
    // the engine limits the length of one utterance: speak long answers in pieces cut at the end of a sentence
    val parts = SpeechText.chunks(text, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 1)
    parts.forEachIndexed { i, part ->
        val id = "g${gen}_" + if (i == parts.lastIndex) "last" else i.toString()
        val rc = engine.speak(part, if (i == 0) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD, null, id)
        if (rc == android.speech.tts.TextToSpeech.ERROR) { speakFailed("Non riesco a leggere ad alta voce: controlla la sintesi vocale del telefono"); return }
    }
}
