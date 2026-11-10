package com.weblauncher.app

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

// ======================= AI (provider and key from Mia AI) =======================

internal fun MainActivity.aiReady(): Boolean {
    if (!ai.supported()) { toast("Aggiorna \"Android System WebView\" dal Play Store per usare l'AI"); return false }
    return true
}

/** Markdown subset -> styled text (bold, code, headings, bullet lists). */
internal fun MainActivity.formatAi(md: String): CharSequence {
    val html = md.trim().lines().joinToString("<br>") { raw ->
        var line = android.text.TextUtils.htmlEncode(raw)
        val heading = Regex("^#{1,6}\\s+(.*)").find(line)
        if (heading != null) line = "<b>${heading.groupValues[1]}</b>"
        line = line.replace(Regex("^\\s*[-*•]\\s+"), "&nbsp;&nbsp;• ")
        line = line.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        line = line.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "<i>$1</i>")
        line = line.replace(Regex("`([^`]+)`"), "<tt>$1</tt>")
        line
    }
    return androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
}

internal fun MainActivity.jsString(raw: String?): String =
    runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }.getOrNull().orEmpty()

/** Visible text of the current page (trimmed), for summaries and questions. */
internal fun MainActivity.readPage(onText: (title: String, url: String, text: String) -> Unit) {
    val url = webView.url?.takeIf { it.startsWith("http") || MiaAi.isMiaUrl(it) }
    if (url == null || !browserHasPage) { toast("Apri prima una pagina"); return }
    val js = "(function(){if(window.__pdfText!==undefined)return window.__pdfText.substring(0,24000);" +
        "var b=document.body;var t=b?(b.innerText||''):'';" +
        "t=t.replace(/\\n{3,}/g,'\\n\\n');return t.substring(0,24000);})()"
    webView.evaluateJavascript(js) { raw ->
        val text = jsString(raw).trim()
        if (text.length < 40) toast("Questa pagina ha poco testo da leggere")
        else onText(webView.title.orEmpty(), url, text)
    }
}

internal fun MainActivity.summarizePage() {
    if (!aiReady()) return
    readPage { title, url, text ->
        showAiSheet(
            title = "Riassunto",
            context = pageContext(title, url, text),
            firstQuestion = "Riassumi questa pagina in italiano: una frase iniziale, poi 5-8 punti chiave. Niente introduzioni.",
            showFirstQuestion = false
        )
    }
}

internal fun MainActivity.askThePage() {
    if (!aiReady()) return
    readPage { title, url, text ->
        showAiSheet(title = "Chiedi alla pagina", context = pageContext(title, url, text), firstQuestion = null,
            hint = "Es. quanto costa? quali sono gli orari?")
    }
}

internal fun MainActivity.pageContext(title: String, url: String, text: String) =
    "Ecco il contenuto della pagina web «$title» ($url). Rispondi usando queste informazioni; " +
        "se la risposta non c'è, dillo.\n\n--- PAGINA ---\n$text\n--- FINE PAGINA ---\n\n"

internal fun MainActivity.askAi(question: String) {
    if (!aiReady()) return
    showAiSheet(title = "Risposta AI", context = null, firstQuestion = question)
}

internal fun MainActivity.explainSelection(sel: String) {
    if (!aiReady()) return
    showAiSheet(
        title = "Spiegazione",
        context = null,
        firstQuestion = "Spiega in modo semplice, in italiano, questo testo. Se non è in italiano, prima traducilo.\n\n«${sel.take(4000)}»",
        showFirstQuestion = false,
        quote = sel.take(300)
    )
}

internal fun MainActivity.showAiSheet(
    title: String,
    context: String?,
    firstQuestion: String?,
    showFirstQuestion: Boolean = true,
    hint: String = "Fai un'altra domanda…",
    quote: String? = null,
    firstAttachments: List<Triple<String, String, String>> = emptyList(),
    previewAnswer: String? = null            // only for the automatic test: a ready-made long answer
) {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val history = mutableListOf<Pair<String, String>>()
    var running = 0

    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(18), dp(4), dp(18), dp(12))
    }
    val head = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))
    }
    head.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_ai); setColorFilter(accent)
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
    })
    val headBox = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headBox.addView(TextView(this).apply {
        text = title; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
    })
    val modelLabel = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted) }
    headBox.addView(modelLabel)
    head.addView(headBox)
    // the sheet can no longer be swiped away (it closed while scrolling): an explicit close button instead
    head.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_web_close); setColorFilter(palette.textMuted)
        setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Chiudi"
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { sheet.dismiss() }
    })
    root.addView(head)
    ai.status { ok, label -> modelLabel.text = if (ok) label else "Da configurare in Mia AI" }

    val chat = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
    // NestedScrollView (not ScrollView): inside a bottom sheet the content scrolls by itself, the sheet does not move
    val scroll = androidx.core.widget.NestedScrollView(this).apply {
        isFillViewport = false
        addView(chat)
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    root.addView(scroll)
    if (quote != null) chat.addView(TextView(this).apply {
        text = "«$quote»"; textSize = 14f; setTextColor(palette.textMuted)
        setTypeface(typeface, android.graphics.Typeface.ITALIC)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12f * d; setColor(palette.iconTile) }
    })

    val input = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(palette.text); setHintTextColor(palette.textMuted)
        maxLines = 4
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22f * d; setColor(palette.iconTile); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val sendBtn = android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_web_forward)
        setColorFilter(android.graphics.Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
        contentDescription = "Invia"
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
    }
    val inputRow = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(input); addView(sendBtn)
    }
    root.addView(inputRow)

    fun bubble(text: CharSequence, user: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = if (user) 14f else 16f
        setLineSpacing(0f, 1.15f)
        setTextColor(if (user) palette.textMuted else palette.text)
        if (user) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextIsSelectable(!user)
        setPadding(0, dp(if (user) 14 else 6), 0, dp(6))
        chat.addView(this)
    }

    fun actions(answer: String) {
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun chip(label: String, onClick: () -> Unit) = row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * d; setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        })
        chip("Copia") { copyText("Risposta AI", answer) }
        chip("Condividi") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, answer)
            }, "Condividi risposta"))
        }
        chip("Leggi") { speak(answer, 1f) }
        chip("📝 Nota") {
            notes.save(notes.load() + Note(UUID.randomUUID().toString(), title + "\n" + answer, System.currentTimeMillis()))
            toast("Salvato nelle Note")
        }
        chat.addView(row)
    }

    fun send(question: String, visible: Boolean) {
        if (question.isBlank() || running != 0) return
        val content = if (history.isEmpty() && context != null) context + question else question
        history.add("user" to content)
        if (visible) bubble(question, true)
        val answerView = bubble("…", false)
        input.setText("")
        sendBtn.alpha = 0.4f
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        val atts = if (history.size == 1) firstAttachments else emptyList()
        running = ai.ask(history, AI_SYSTEM, object : AiBridge.Listener {
            override fun onPartial(text: String) {
                answerView.text = formatAi(text)
            }
            override fun onDone(text: String, model: String) {
                running = 0; sendBtn.alpha = 1f
                history.add("assistant" to text)
                answerView.text = formatAi(text)
                if (model.isNotBlank()) modelLabel.text = model
                actions(text)
                // show the START of the answer (the old code jumped to the bottom of a long text)
                scroll.post { scroll.smoothScrollTo(0, (answerView.top - dp(8)).coerceAtLeast(0)) }
            }
            override fun onError(message: String, noKey: Boolean) {
                running = 0; sendBtn.alpha = 1f
                history.removeAt(history.lastIndex)
                answerView.text = message
                answerView.setTextColor(ContextCompat.getColor(this@showAiSheet, R.color.danger))
                if (noKey) chat.addView(com.google.android.material.button.MaterialButton(this@showAiSheet).apply {
                    text = "Configura in Mia AI"
                    isAllCaps = false
                    setOnClickListener { sheet.dismiss(); openMiaAi() }
                })
            }
        }, atts)
    }
    sendBtn.setOnClickListener { send(input.text?.toString()?.trim().orEmpty(), true) }
    input.setOnEditorActionListener { _, id, _ ->
        if (id == EditorInfo.IME_ACTION_SEND) { send(input.text?.toString()?.trim().orEmpty(), true); true } else false
    }
    sheet.setOnDismissListener { if (running != 0) ai.cancel(running); stopSpeaking() }
    sheet.setContentView(root)
    root.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.78f).toInt()
    root.minimumHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
    sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    sheet.behavior.skipCollapsed = true
    sheet.behavior.isDraggable = false             // dragging used to close the sheet while the user was trying to scroll
    sheet.setCanceledOnTouchOutside(false)         // a stray tap outside must not cancel the answer
    sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    sheet.show()
    if (previewAnswer != null) {                   // automatic test: long text, then report scrolling and whether it stayed open
        bubble("Domanda di prova", true)
        bubble(formatAi(previewAnswer), false)
        var maxY = 0
        scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, y, _, _ -> maxY = maxOf(maxY, y) })
        uiHandler.postDelayed({ android.util.Log.i(SelfTest.TAG, "AI_SHEET shown=${sheet.isShowing} maxScrollY=$maxY") }, 15000)
    }
    if (firstQuestion != null) send(firstQuestion, showFirstQuestion)
    else if (previewAnswer == null) input.requestFocus()
}

internal fun MainActivity.openMiaAi() {
    if (apps.none { MiaAi.isMiaUrl(it.url) }) addMiaToHome()
    openInBrowser(MiaAi.URL)
}

internal fun MainActivity.interpretVoice(text: String, fallback: () -> Unit) {
    if (!ai.supported()) { fallback(); return }
    toast("✨ Sto capendo…")
    val appList = apps.take(60).joinToString("\n") { "- ${it.name}: ${normalizeUrl(it.url)}" }
    val prompt = "Comando vocale: «$text»\n\nApp salvate:\n$appList\n\n" +
        "Rispondi SOLO con un oggetto JSON, senza altro testo. Azioni possibili:\n" +
        "{\"action\":\"open_url\",\"url\":\"...\"} per aprire un sito o una ricerca dentro un sito " +
        "(es. YouTube: https://www.youtube.com/results?search_query=..., Amazon: https://www.amazon.it/s?k=..., " +
        "Maps: https://www.google.com/maps/search/...). Usa l'indirizzo delle app salvate quando corrisponde.\n" +
        "{\"action\":\"search\",\"query\":\"...\"} per una ricerca web\n" +
        "{\"action\":\"ask\",\"question\":\"...\"} per una domanda a cui rispondere a parole\n" +
        "{\"action\":\"summarize\"} per riassumere la pagina aperta\n" +
        "{\"action\":\"new_tab\"}, {\"action\":\"close_all_tabs\"}, {\"action\":\"history\"}, " +
        "{\"action\":\"downloads\"}, {\"action\":\"settings\"}, {\"action\":\"reminder\",\"text\":\"...\"} per un promemoria (testo con quando), {\"action\":\"news\"} per le notizie, {\"action\":\"radio\"}, {\"action\":\"podcast\"}, " +
        "{\"action\":\"briefing\"} per il riassunto delle notizie del giorno"
    ai.ask(listOf("user" to prompt), "Sei l'interprete dei comandi vocali di un browser. Rispondi solo con JSON valido.",
        object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val json = Regex("\\{[\\s\\S]*\\}").find(text)?.value
                val o = runCatching { org.json.JSONObject(json ?: "") }.getOrNull()
                if (o == null) { fallback(); return }
                when (o.optString("action")) {
                    "open_url" -> {
                        val url = o.optString("url").takeIf { it.startsWith("http") } ?: return fallback()
                        val app = apps.firstOrNull { a -> hostOf(normalizeUrl(a.url)) == hostOf(url) }
                        if (app != null && app.locked) authenticate("Apri ${app.name}", strict = false) { openInBrowser(url) }
                        else openInBrowser(url)
                    }
                    "search" -> openInBrowser(searchUrl(o.optString("query").ifBlank { text }))
                    "ask" -> askAi(o.optString("question").ifBlank { text })
                    "summarize" -> { showTab(TAB_BROWSER); summarizePage() }
                    "new_tab" -> openNewTab(false)
                    "close_all_tabs" -> {
                        webTabs.clear(); webTabs.add(WebTab()); webTabIndex = 0
                        restoreTab(webTabs[0]); renderTabStrip(); toast("Schede chiuse")
                    }
                    "history" -> showHistoryPage()
                    "downloads" -> showDownloadsPage()
                    "settings" -> showTab(TAB_SETTINGS)
                    "news" -> showTab(TAB_NEWS)
                    "reminder" -> createReminderFromText(o.optString("text").ifBlank { text })
                    "radio" -> { audioTab = "radio"; showTab(TAB_AUDIO) }
                    "podcast" -> { audioTab = "podcast"; showTab(TAB_AUDIO) }
                    "briefing" -> { showTab(TAB_NEWS); newsBriefing() }
                    else -> fallback()
                }
            }
            override fun onError(message: String, noKey: Boolean) { fallback() }
        })
}
