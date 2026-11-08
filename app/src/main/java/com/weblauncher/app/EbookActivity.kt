package com.weblauncher.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
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
    private var tts: TextToSpeech? = null
    private var speaking = false
    private val prefs by lazy { getSharedPreferences("wl_books", MODE_PRIVATE) }
    private var theme = 0
    private var size = 19

    private val themes = arrayOf(
        Triple("#ffffff", "#1e1e1e", false), Triple("#f4ecd8", "#4a3b2a", false), Triple("#121417", "#e4e6ea", true)
    )

    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/files/books/", WebViewAssetLoader.InternalStoragePathHandler(this, Ebooks.dir(this)))
            .build()
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
        listenBtn = btn("🔊", "Ascolta") { toggleListen() }
        btn("☰", "Capitoli") { showChapters() }
        web = WebView(this)
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
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
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
                if (speaking) readChapter()
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
            "h1,h2,h3{line-height:1.25!important;color:inherit!important}"
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
        chapter = index
        pendingRatio = ratio
        Ebooks.saveProgress(this, book.id, chapter, ratio)
        load()
    }

    private fun showChapters() {
        MaterialAlertDialogBuilder(this).setTitle("Capitoli")
            .setSingleChoiceItems(book.titles.mapIndexed { i, t -> "${i + 1}. $t" }.toTypedArray(), chapter) { d, w -> d.dismiss(); go(w, 0f) }
            .show()
    }

    // ---------- read aloud, chapter after chapter ----------

    private fun toggleListen() {
        if (speaking) { speaking = false; tts?.stop(); listenBtn.text = "🔊"; return }
        speaking = true
        listenBtn.text = "⏹"
        if (tts == null) {
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.ITALIAN
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == "last" && speaking) runOnUiThread { if (chapter < book.spine.lastIndex) go(chapter + 1, 0f) else toggleListen() }
                        }
                    })
                    readChapter()
                } else { speaking = false; listenBtn.text = "🔊"; Toast.makeText(this, "Lettura vocale non disponibile", Toast.LENGTH_SHORT).show() }
            }
        } else readChapter()
    }

    private fun readChapter() {
        web.evaluateJavascript("document.body?document.body.innerText:''") { raw ->
            val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                .replace(Regex("\\s+"), " ").trim()
            val engine = tts ?: return@evaluateJavascript
            if (text.isEmpty()) { engine.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "last"); return@evaluateJavascript }
            val max = TextToSpeech.getMaxSpeechInputLength() - 1
            val parts = text.chunked(max)
            parts.forEachIndexed { i, p ->
                engine.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::web.isInitialized) return
        web.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-innerHeight;return h>0?scrollY/h:0})()") { r ->
            Ebooks.saveProgress(this, book.id, chapter, r?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
