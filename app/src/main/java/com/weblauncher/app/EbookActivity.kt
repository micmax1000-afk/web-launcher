package com.weblauncher.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/** EPUB reader: chapters rendered by a WebView from the unzipped book, with themes, font size and read-aloud. */
class EbookActivity : AppCompatActivity() {
    private lateinit var book: Book
    private lateinit var web: WebView
    private lateinit var info: TextView
    private lateinit var listenBtn: TextView
    private var chapter = 0
    private var pendingRatio = 0f
    private lateinit var listenBar: LinearLayout
    private lateinit var lbInfo: TextView
    private lateinit var lbProgress: ProgressBar
    private lateinit var lbPlay: ImageView
    private lateinit var lbSpeed: TextView
    private lateinit var lbTimer: TextView
    internal var reader: BookReader? = null
    private var hlFirst: String? = null
    private var hlLast: String? = null
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val prefs by lazy { getSharedPreferences("wl_books", MODE_PRIVATE) }
    private var theme = 0
    private var size = 19

    private val themes = arrayOf(
        Triple("#ffffff", "#1e1e1e", false), Triple("#f4ecd8", "#4a3b2a", false), Triple("#121417", "#e4e6ea", true)
    )

    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/files/books/", WebViewAssetLoader.InternalStoragePathHandler(this, Ebooks.dir(this)))
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    /** Natural voice: the invisible Mia AI page that holds the Gemini key. */
    private val aiLazy = lazy { AiBridge(window.decorView.findViewById<ViewGroup>(android.R.id.content), loader) }
    internal val ai: AiBridge get() = aiLazy.value

    private val readerUi = object : BookReader.Ui {
        override fun stateChanged() { runOnUiThread { refreshListenBar() } }
        override fun highlight(first: String, last: String?) { runOnUiThread { hlFirst = first; hlLast = last; applyHighlight() } }
        override fun showChapter(index: Int) { runOnUiThread { if (index != chapter) goQuiet(index) } }
        override fun say(msg: String) { runOnUiThread { Toast.makeText(this@EbookActivity, msg, Toast.LENGTH_LONG).show() } }
        override fun bridge() = ai
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.applySavedTheme(this)
        setTheme(AppRepository.accentStyle(this))
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("book")
        book = Ebooks.load(this).firstOrNull { it.id == id } ?: run { finish(); return }
        theme = prefs.getInt("theme", if (AppRepository.isLightUi(this)) 0 else 2)
        size = prefs.getInt("size", 19)
        val (ch, ratio) = Ebooks.progress(this, book.id)
        chapter = ch.coerceIn(0, book.spine.lastIndex)
        pendingRatio = ratio

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val buttons = mutableListOf<TextView>()
        fun btn(label: String, desc: String, parent: LinearLayout = bar, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 17f; gravity = android.view.Gravity.CENTER; contentDescription = desc
            setPadding(dp(11), dp(8), dp(11), dp(8)); minWidth = dp(44)
            setOnClickListener { onClick() }
            buttons.add(this); parent.addView(this)
        }
        btn("←", "Chiudi") { finish() }
        bar.addView(TextView(this).apply {
            text = book.title; textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); buttons.add(this)
        })
        btn("A−", "Testo più piccolo") { size = (size - 1).coerceAtLeast(13); prefs.edit().putInt("size", size).apply(); applyStyle() }
        btn("A+", "Testo più grande") { size = (size + 1).coerceAtMost(32); prefs.edit().putInt("size", size).apply(); applyStyle() }
        btn("◐", "Colori") { theme = (theme + 1) % 3; prefs.edit().putInt("theme", theme).apply(); applyStyle(); paintChrome(root, buttons) }
        listenBtn = btn("🔊", "Leggi ad alta voce") { toggleListen() }
        btn("☰", "Capitoli") { showChapters() }
        web = WebView(this)
        AppRepository.restoreUiMode(this)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        btn("‹", "Capitolo precedente", bottom) { go(chapter - 1, 0f) }
        info = TextView(this).apply {
            textSize = 13f; gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            buttons.add(this)
        }
        bottom.addView(info)
        btn("›", "Capitolo successivo", bottom) { go(chapter + 1, 0f) }
        listenBar = buildListenBar()
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(listenBar)
        root.addView(bottom)
        setContentView(root)
        paintChrome(root, buttons)

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.textZoom = 100
        web.setBackgroundColor(android.graphics.Color.parseColor(themes[theme].first))
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return if (u.host == MiaAi.HOST) loader.shouldInterceptRequest(u) else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                if (u.host == MiaAi.HOST) {
                    val rel = u.path.orEmpty().substringAfter("/files/books/${book.id}/${book.root}")
                    val idx = book.spine.indexOfFirst { it.substringBefore('#') == rel }
                    if (idx >= 0 && idx != chapter) { go(idx, 0f); return true }
                    return false
                }
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                applyStyle()
                val r = pendingRatio
                pendingRatio = 0f
                if (r > 0f) web.postDelayed({
                    web.evaluateJavascript("window.scrollTo(0,(document.documentElement.scrollHeight-innerHeight)*$r)", null)
                }, 250)
                web.evaluateJavascript(HL_JS, null)
                if (reader?.active == true) applyHighlight()
            }
        }
        load()
    }

    private fun paintChrome(root: View, buttons: List<TextView>) {
        val (bg, fg, dark) = themes[theme]
        val bgc = android.graphics.Color.parseColor(bg); val fgc = android.graphics.Color.parseColor(fg)
        root.setBackgroundColor(bgc)
        buttons.forEach { it.setTextColor(fgc) }
        window.statusBarColor = bgc; window.navigationBarColor = bgc
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        if (::web.isInitialized) web.setBackgroundColor(bgc)
    }

    private fun applyStyle() {
        val (bg, fg, _) = themes[theme]
        val css = "html,body{background:$bg!important;color:$fg!important}" +
            "body{font-family:Georgia,serif!important;font-size:${size}px!important;line-height:1.6!important;" +
            "margin:0!important;padding:12px 20px 60px!important;max-width:none!important}" +
            "p,div,span,li{font-size:inherit!important;line-height:inherit!important;color:inherit!important}" +
            "img,svg{max-width:100%!important;height:auto!important}a{color:#4a7bd8!important}" +
            "h1,h2,h3{line-height:1.25!important;color:inherit!important}" +
            "::highlight(wl){background:#fde68a!important;color:#1e1e1e!important}"
        web.evaluateJavascript(
            "(function(){var s=document.getElementById('wl-css');if(!s){s=document.createElement('style');s.id='wl-css';" +
                "(document.head||document.documentElement).appendChild(s);}s.textContent=${org.json.JSONObject.quote(css)};})()", null)
    }

    private fun load() {
        val url = MiaAi.ORIGIN + "/files/books/${book.id}/${book.root}${book.spine[chapter]}"
        web.loadUrl(url)
        val pct = ((chapter + 1) * 100 / book.spine.size)
        info.text = "${book.titles.getOrElse(chapter) { "Capitolo ${chapter + 1}" }.take(40)}\n${chapter + 1} di ${book.spine.size} · $pct%"
    }

    private fun go(index: Int, ratio: Float) {
        if (index !in book.spine.indices) {
            if (index > book.spine.lastIndex) Toast.makeText(this, "Fine del libro 🎉", Toast.LENGTH_SHORT).show()
            return
        }
        val r = reader
        if (r != null && r.active) { r.jumpToChapter(index, ratio); return }
        chapter = index
        pendingRatio = ratio
        Ebooks.saveProgress(this, book.id, chapter, ratio)
        load()
    }

    /** Chapter changed by the reading itself: only the page follows. */
    private fun goQuiet(index: Int) {
        if (index !in book.spine.indices) return
        chapter = index; pendingRatio = 0f
        Ebooks.saveProgress(this, book.id, chapter, 0f)
        load()
    }

    private fun showChapters() {
        MaterialAlertDialogBuilder(this).setTitle("Capitoli")
            .setSingleChoiceItems(book.titles.mapIndexed { i, t -> "${i + 1}. $t" }.toTypedArray(), chapter) { d, w -> d.dismiss(); go(w, 0f) }
            .show()
    }

    // ---------- read aloud ----------

    private fun fmtSpeed(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.ITALIAN, "%.2f", v).trimEnd('0')).replace('.', ',') + "×"

    private fun buildListenBar(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val white = 0xFFFFFFFF.toInt()
        fun txt(t: String, size: Float, desc: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = size; setTextColor(white); gravity = android.view.Gravity.CENTER; contentDescription = desc
            setPadding(dp(6), dp(4), dp(6), dp(4)); setOnClickListener { onClick() }
        }
        fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 12.5f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(dp(13), dp(7), dp(13), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x26FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
        lbInfo = TextView(this).apply { textSize = 12.5f; setTextColor(0xFFCBD5E1.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        lbProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(white)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        }
        lbPlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause); setColorFilter(0xFF111111.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13)); contentDescription = "Pausa o riprendi"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(white) }
            setOnClickListener { reader?.toggle() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(txt("⏮", 24f, "Capitolo precedente") { reader?.prevChapter() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(txt("↺\nfrase", 11f, "Frase precedente") { reader?.prevSentence() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lbPlay, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(txt("↻\nfrase", 11f, "Frase successiva") { reader?.nextSentence() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(txt("⏭", 24f, "Capitolo successivo") { reader?.nextChapter() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val speeds = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
        lbSpeed = chip("1×") { val r = reader ?: return@chip; r.setSpeed(speeds.firstOrNull { it > r.speed + 0.01f } ?: speeds[0]) }
        lbTimer = chip("⏲ Timer") { showSleepTimer() }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            addView(lbSpeed); addView(lbTimer); addView(chip("🎙 Voce") { showVoicePage() })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF1E293B.toInt()) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) }
            addView(LinearLayout(this@EbookActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(lbInfo, LinearLayout.LayoutParams(0, -2, 1f))
                addView(txt("✕", 16f, "Smetti di leggere") { reader?.stop() })
            })
            addView(lbProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4); bottomMargin = dp(6) })
            addView(controls); addView(chips)
        }
    }

    private fun refreshListenBar() {
        if (!::listenBar.isInitialized) return
        val r = reader
        val on = r?.active == true
        listenBar.visibility = if (on) View.VISIBLE else View.GONE
        listenBtn.text = if (on && r!!.playing) "⏸" else "🔊"
        if (!on) {
            if (hlFirst != null) { hlFirst = null; hlLast = null; web.evaluateJavascript("window.CSS&&CSS.highlights&&CSS.highlights.clear()", null) }
            return
        }
        r!!
        lbInfo.text = if (r.preparing != null) r.preparing else if (r.loading) "Preparo la lettura…"
            else "Cap. ${r.chapter + 1} di ${book.spine.size} · ${(r.fraction() * 100).toInt()}% · −${r.minutesLeft()} min" + if (!r.playing) " · in pausa" else ""
        lbProgress.progress = (r.fraction() * 1000).toInt()
        lbPlay.setImageResource(if (r.playing) R.drawable.ic_pause else R.drawable.ic_play)
        lbSpeed.text = fmtSpeed(r.speed)
        lbTimer.text = when { r.sleepAtChapterEnd -> "⏲ fine cap."; r.sleepAt > 0 -> "⏲ ${((r.sleepAt - System.currentTimeMillis()) / 60_000) + 1} min"; else -> "⏲ Timer" }
    }

    private fun applyHighlight() {
        val f = hlFirst ?: return
        web.evaluateJavascript("window.wlHL&&wlHL(${org.json.JSONObject.quote(f)},${hlLast?.let { org.json.JSONObject.quote(it) } ?: "null"})", null)
    }

    private fun toggleListen() {
        val cur = reader
        if (cur != null && cur.active) { cur.toggle(); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        val r = cur ?: BookReader(applicationContext, book, readerUi).also { reader = it; ReadAloudService.current = it }
        // start from what is on the screen
        web.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-innerHeight;return h>0?scrollY/h:0})()") { v ->
            r.start(chapter, v?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f)
        }
    }

    private fun showSleepTimer() {
        val r = reader ?: return
        val mins = intArrayOf(0, 15, 30, 45, 60)
        MaterialAlertDialogBuilder(this).setTitle("Smetti di leggere tra")
            .setItems(arrayOf("Mai (spento)", "15 minuti", "30 minuti", "45 minuti", "60 minuti", "Alla fine del capitolo")) { _, w ->
                if (w == 5) r.sleepAtEndOfChapter() else r.setSleepMinutes(mins[w])
            }.show()
    }

    // ---------- voice page ----------

    private fun showVoicePage() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val prefs = ReadPrefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(6)) }
        var dlg: androidx.appcompat.app.AlertDialog? = null
        var pvTts: android.speech.tts.TextToSpeech? = null
        var pvMp: android.media.MediaPlayer? = null
        var pvVoices: List<android.speech.tts.Voice>? = null
        lateinit var render: () -> Unit
        fun applyChange() { reader?.settingsChanged() }
        fun section(t: String) = root.addView(TextView(this).apply { text = t; textSize = 12f; letterSpacing = 0.06f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF2563EB.toInt()); setPadding(0, dp(14), 0, dp(4)) })
        fun row(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) = root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(if (selected) dp(2) else 1, if (selected) 0xFF2563EB.toInt() else 0x33888888) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            addView(LinearLayout(this@EbookActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@EbookActivity).apply { text = title; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                if (sub != null) addView(TextView(this@EbookActivity).apply { text = sub; textSize = 12.5f; alpha = 0.7f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) addView(TextView(this@EbookActivity).apply { text = "✓"; textSize = 18f; setTextColor(0xFF2563EB.toInt()) })
            setOnClickListener { onClick() }
        })
        fun slider(label: String, valueText: String, progress: Int, max: Int, onChange: (Int) -> Unit) {
            val vt = TextView(this).apply { text = valueText; textSize = 13f; gravity = android.view.Gravity.END }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
                addView(TextView(this@EbookActivity).apply { text = label; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f)); addView(vt)
            })
            root.addView(SeekBar(this).apply {
                this.max = max; this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { onChange(p); vt.text = when (label) { "Velocità" -> fmtSpeed(prefs.speed); else -> String.format(Locale.ITALIAN, "%.2f", prefs.pitch).replace('.', ',') } } }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { applyChange() }
                })
            })
        }
        fun preview() {
            val sample = "Ciao! Questa è la voce con cui leggerò il tuo libro. Ti piace?"
            runCatching { pvMp?.release() }; pvMp = null
            if (prefs.mode == "gemini" || prefs.mode == "lira") {
                Toast.makeText(this, if (prefs.mode == "lira") "Preparo l'esempio (la prima volta scarica la voce)…" else "Preparo l'esempio…", Toast.LENGTH_SHORT).show()
                val played = { b64: String, mime: String ->
                    runCatching {
                        val f = File(cacheDir, "voice-sample.wav")
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        f.writeBytes(if (mime.contains("wav", true)) bytes else ReadText.pcmToWav(bytes, ReadText.rateOf(mime)))
                        runOnUiThread { val m = android.media.MediaPlayer(); m.setDataSource(f.path); m.setOnPreparedListener { it.start() }; m.prepareAsync(); pvMp = m }
                    }
                    Unit
                }
                val failed = { msg: String, code: String -> runOnUiThread { Toast.makeText(this, if (code == "nokey") "Serve la chiave Gemini in Mia AI" else "Non riesco: $msg".take(140), Toast.LENGTH_LONG).show() }; Unit }
                if (prefs.mode == "lira") ai.piper(sample, prefs.lVoice, played, failed) { p -> runOnUiThread { Toast.makeText(this, p, Toast.LENGTH_SHORT).show() } }
                else ai.tts(sample, prefs.gVoice, played, failed)
            } else {
                val t = pvTts ?: return
                t.language = Locale.ITALIAN; t.setSpeechRate(prefs.speed); t.setPitch(prefs.pitch)
                if (prefs.sysVoice.isNotBlank()) runCatching { t.voices?.firstOrNull { it.name == prefs.sysVoice }?.let { t.voice = it } }
                t.speak(sample, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sample")
            }
        }
        render = {
            root.removeAllViews()
            section("VOCE")
            row("📱 Voce del telefono (Google)", "Gratis · anche senza internet", prefs.mode == "system") { prefs.mode = "system"; applyChange(); render() }
            row("🎙 Voce Lira (Paola, Riccardo)", "Neurale · gratis · offline dopo il primo scarico", prefs.mode == "lira") { prefs.mode = "lira"; applyChange(); render() }
            row("✨ Voce naturale (Gemini)", "Più umana · usa la tua chiave Gemini · serve internet", prefs.mode == "gemini") {
                ai.hasGeminiKey { ok -> runOnUiThread {
                    if (!ok) MaterialAlertDialogBuilder(this).setTitle("Serve la chiave Gemini")
                        .setMessage("La voce naturale usa la chiave Gemini salvata in Mia AI. Aprila da Strumenti → Mia AI, inserisci la chiave nelle impostazioni, poi torna qui.").setPositiveButton("OK", null).show()
                    else { prefs.mode = "gemini"; applyChange(); render() }
                } }
            }
            if (prefs.mode == "system") {
                section("VOCI DEL TELEFONO")
                val vs = pvVoices
                if (vs == null) row("Cerco le voci…", null, false) {}
                else if (vs.isEmpty()) row("Nessuna voce italiana trovata", "Scaricane una dalle impostazioni di Android", false) {}
                else vs.forEachIndexed { i, v ->
                    val hq = v.quality >= android.speech.tts.Voice.QUALITY_HIGH
                    row("Voce ${i + 1}" + if (hq) " · alta qualità" else "", (if (v.isNetworkConnectionRequired) "Serve internet" else "Sul telefono") + " · ${v.name}", prefs.sysVoice == v.name) { prefs.sysVoice = v.name; applyChange(); render(); preview() }
                }
                row("⬇ Scarica altre voci", "Si apre Impostazioni di Android → Sintesi vocale", false) {
                    runCatching { startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure { runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } }
                }
            } else {
                if (prefs.mode == "lira") {
                    section("VOCE LIRA")
                    row("Paola · donna", "Più curata · la prima volta scarica circa 63 MB", prefs.lVoice == "it_IT-paola-medium") { prefs.lVoice = "it_IT-paola-medium"; applyChange(); render(); preview() }
                    row("Riccardo · uomo", "Più leggera e veloce · la prima volta scarica circa 28 MB", prefs.lVoice == "it_IT-riccardo-x_low") { prefs.lVoice = "it_IT-riccardo-x_low"; applyChange(); render(); preview() }
                } else {
                section("VOCE NATURALE")
                listOf(Triple("Sulafat", "calda", "donna"), Triple("Kore", "ferma", "donna"), Triple("Charon", "chiara", "uomo"), Triple("Orus", "decisa", "uomo")).forEach { (n, style, who) ->
                    row("$n · $style", "Voce di $who", prefs.gVoice == n) { prefs.gVoice = n; applyChange(); render(); preview() }
                }
                }
                section("PREPARA IN ANTICIPO")
                row("⏬ Prepara il capitolo adesso", "Così lo ascolti senza attese, anche a schermo spento", false) {
                    val r = reader ?: BookReader(applicationContext, book, readerUi).also { reader = it; ReadAloudService.current = it }
                    r.prepareChapter(chapter)
                }
                val mb = BookReader.savedAudioBytes(this) / 1_000_000.0
                section("AUDIO SALVATO")
                row("🗂 ${String.format(Locale.ITALIAN, "%.1f", mb)} MB sul telefono", "Il libro già ascoltato si riascolta senza internet. Tocca per cancellare.", false) {
                    MaterialAlertDialogBuilder(this).setTitle("Cancellare l'audio salvato?").setPositiveButton("Cancella") { _, _ -> BookReader.clearSavedAudio(this); render() }.setNegativeButton("Annulla", null).show()
                }
                root.addView(TextView(this).apply { text = "Preparo l'audio un pezzo alla volta mentre ascolti e lo tengo salvato. Sul telefono la voce Lira può essere più lenta della lettura: prepara il capitolo prima. Con Gemini il piano gratuito può avere limiti di uso. Se qualcosa non va passo alla voce del telefono."; textSize = 12f; alpha = 0.7f; setPadding(0, dp(8), 0, 0) })
            }
            section("VELOCITÀ E TONO")
            slider("Velocità", fmtSpeed(prefs.speed), ((prefs.speed - 0.6f) / 0.05f).toInt().coerceIn(0, 28), 28) { prefs.speed = 0.6f + it * 0.05f }
            if (prefs.mode == "system") slider("Tono", String.format(Locale.ITALIAN, "%.2f", prefs.pitch).replace('.', ','), ((prefs.pitch - 0.7f) / 0.05f).toInt().coerceIn(0, 14), 14) { prefs.pitch = 0.7f + it * 0.05f }
            root.addView(TextView(this).apply {
                text = "▶ Prova la voce"; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER; setPadding(dp(12), dp(13), dp(12), dp(13))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF2563EB.toInt()) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) }
                setOnClickListener { preview() }
            })
        }
        pvTts = android.speech.tts.TextToSpeech(this) { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) runOnUiThread {
                pvVoices = runCatching { pvTts?.voices?.filter { it.locale.language == "it" }?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name }) }.getOrNull() ?: emptyList()
                if (dlg?.isShowing == true) render()
            } else runOnUiThread { pvVoices = emptyList(); if (dlg?.isShowing == true) render() }
        }
        render()
        dlg = MaterialAlertDialogBuilder(this).setTitle("🎙 Voce di lettura").setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton("Fatto", null).create()
        dlg.setOnDismissListener { runCatching { pvTts?.shutdown() }; runCatching { pvMp?.release() } }
        dlg.show()
    }

    companion object {
        /** Highlights the sentence being read (CSS Custom Highlight API; selection as a fallback) and scrolls to it. */
        private const val HL_JS = """
(function(){
  if (window.wlHL) return;
  function norm(s){return String(s).replace(/[\s ]+/g,' ').trim();}
  function build(){
    var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),s=[],m=[],n,lastSp=true;
    while(n=tw.nextNode()){
      var p=n.parentNode&&n.parentNode.nodeName; if(p==='SCRIPT'||p==='STYLE')continue;
      var v=n.nodeValue;
      for(var i=0;i<v.length;i++){var ch=v[i]; if(/[\s ]/.test(ch)){ if(lastSp)continue; ch=' '; lastSp=true;} else lastSp=false; s.push(ch); m.push([n,i]);}
      if(!lastSp){s.push(' ');m.push([n,v.length]);lastSp=true;}
    }
    return {t:s.join(''),m:m,c:0};
  }
  window.wlHL=function(a,b){
    var d=window.__wl||(window.__wl=build()); a=norm(a); b=b?norm(b):a;
    var i=d.t.indexOf(a,Math.max(0,d.c-300)); if(i<0)i=d.t.indexOf(a); if(i<0)return 0;
    var j=d.t.indexOf(b,i); if(j<0)j=i; var e=Math.min(j+b.length-1,d.m.length-1); d.c=i;
    var r=document.createRange(); r.setStart(d.m[i][0],d.m[i][1]);
    var em=d.m[e]; r.setEnd(em[0],Math.min(em[1]+1,em[0].nodeValue.length));
    if(window.CSS&&CSS.highlights){CSS.highlights.set('wl',new Highlight(r));}else{var sel=getSelection();sel.removeAllRanges();sel.addRange(r);}
    var rect=r.getBoundingClientRect();
    if(rect.top<90||rect.bottom>innerHeight-90){window.scrollTo(0,window.scrollY+rect.top-innerHeight/3);}
    return 1;
  };
})();
"""
    }
    override fun onResume() {
        super.onResume()
        // the main screen pauses the timers of all WebViews when it stops: the reader must keep its own pages running
        if (::web.isInitialized) { web.onResume(); web.resumeTimers() }
    }

    override fun onPause() {
        super.onPause()
        if (!::web.isInitialized) return
        web.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-innerHeight;return h>0?scrollY/h:0})()") { r ->
            Ebooks.saveProgress(this, book.id, chapter, r?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f)
        }
    }

    override fun onDestroy() {
        reader?.release(); ReadAloudService.current = null
        if (aiLazy.isInitialized()) runCatching { ai.destroy() }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
