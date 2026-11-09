package com.weblauncher.app

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

internal fun MainActivity.isPdfViewer(url: String?) = url != null && url.startsWith(MiaAi.ORIGIN + "/assets/pdf/viewer.html")

internal fun MainActivity.pdfName(url: String?): String =
    runCatching { Uri.parse(url).getQueryParameter("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PDF"

internal fun MainActivity.showPdf(file: java.io.File, name: String, src: String?) {
    val viewer = MiaAi.ORIGIN + "/assets/pdf/viewer.html?file=" + Uri.encode("/files/pdf/" + file.name) +
        "&name=" + Uri.encode(name) + (src?.let { "&src=" + Uri.encode(it) } ?: "")
    if (webTabs.getOrNull(webTabIndex)?.url?.let { it.startsWith("http") } == true) openNewTab(false)
    openInBrowser(viewer)
}

internal fun MainActivity.displayName(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"

internal fun MainActivity.openPdfFromUri(uri: Uri, name: String? = null) {
    val title = name ?: displayName(uri)
    toast("Apro $title…")
    io.execute {
        val dest = java.io.File(pdfDir, Integer.toHexString(uri.toString().hashCode()) + ".pdf")
        val ok = runCatching {
            contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        runOnUiThread { if (ok) showPdf(dest, title, null) else toast("Impossibile aprire il PDF") }
    }
}

internal fun MainActivity.openPdfFromUrl(url: String, userAgent: String?, name: String) {
    toast("Apro il PDF…")
    Thread {
        val dest = java.io.File(pdfDir, Integer.toHexString(url.hashCode()) + ".pdf")
        val ok = runCatching {
            var current = url
            var conn: java.net.HttpURLConnection? = null
            for (i in 0 until 5) {
                val c = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", userAgent ?: mobileUserAgent)
                CookieManager.getInstance().getCookie(current)?.let { c.setRequestProperty("Cookie", it) }
                if (c.responseCode in 300..399) {
                    current = java.net.URL(java.net.URL(current), c.getHeaderField("Location")).toString()
                    c.disconnect()
                } else { conn = c; break }
            }
            val c = conn ?: throw java.io.IOException("redirect")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            val head = dest.inputStream().use { s -> ByteArray(5).also { s.read(it) } }
            if (String(head) != "%PDF-") throw java.io.IOException("not a pdf")
        }.isSuccess
        runOnUiThread {
            if (ok) showPdf(dest, name, url)
            else startDownload(url, userAgent, null, "application/pdf", save = true)
        }
    }.start()
}

internal fun MainActivity.extractReader(onDoc: (ReaderDoc) -> Unit) {
    val url = webView.url
    if (url == null || !browserHasPage) { toast("Apri prima una pagina"); return }
    if (isPdfViewer(url)) {
        webView.evaluateJavascript("window.__pdfText||''") { raw ->
            val t = jsString(raw).replace(Regex("\\[Pagina (\\d+)]"), "## Pagina $1").trim()
            if (t.length < 40) toast("Questo PDF non contiene testo (è una scansione)")
            else onDoc(ReaderDoc(pdfName(url), "PDF", url, null, t))
        }
        return
    }
    val js = """
        (function(){
          function vis(e){ var s=getComputedStyle(e); return s.display!=='none' && s.visibility!=='hidden'; }
          var c=[].slice.call(document.querySelectorAll('article,main,[itemprop=articleBody],.article-body,.article__body,.entry-content,.post-content,.story-content,#content,.content'));
          c.push(document.body);
          function score(el){ var ps=el.querySelectorAll('p'), t=0; for(var i=0;i<ps.length;i++){ var s=(ps[i].innerText||'').trim(); if(s.length>60) t+=s.length; } return t; }
          var best=document.body, bs=-1; c.forEach(function(x){ var s=score(x); if(s>bs){ bs=s; best=x; } });
          var out=[], seen={};
          best.querySelectorAll('h1,h2,h3,p,li,blockquote,pre').forEach(function(e){
            if(!vis(e) || e.closest('nav,footer,aside,form,figure figcaption')) return;
            var s=(e.innerText||'').replace(/\s+/g,' ').trim(); if(!s || seen[s]) return;
            if(e.tagName==='P' && s.length<25) return; if(e.tagName==='LI' && s.length<15) return;
            seen[s]=1; out.push((/^H\d/.test(e.tagName)?'## ':'')+(e.tagName==='LI'?'• ':'')+s);
          });
          function meta(p){ var m=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]'); return m?m.content:''; }
          return JSON.stringify({ title: meta('og:title')||document.title, img: meta('og:image'),
            site: meta('og:site_name')||location.hostname.replace(/^www\./,''), text: out.join('\n\n').substring(0,80000) });
        })()
    """.trimIndent()
    webView.evaluateJavascript(js) { raw ->
        val o = runCatching { org.json.JSONObject(jsString(raw)) }.getOrNull()
        val text = o?.optString("text").orEmpty()
        if (o == null || text.length < 200) { toast("Modalità lettura non disponibile per questa pagina"); return@evaluateJavascript }
        onDoc(ReaderDoc(o.optString("title").ifBlank { webView.title.orEmpty() }, o.optString("site"), url,
            o.optString("img").takeIf { it.startsWith("http") }, text))
    }
}

internal fun MainActivity.openReaderMode(listen: Boolean = false) = extractReader { showReader(it, listen = listen) }

internal fun MainActivity.showReader(doc: ReaderDoc, saved: ReadLaterItem? = null, listen: Boolean = false) {
    val prefs = getSharedPreferences("wl_prefs", MODE_PRIVATE)
    var theme = prefs.getInt("reader_theme", if (AppRepository.isLightUi(this)) 0 else 2)
    var size = prefs.getFloat("reader_size", 19f)
    val themes = arrayOf(
        intArrayOf(0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt(), 0xFF6B6B6B.toInt()),
        intArrayOf(0xFFF4ECD8.toInt(), 0xFF4A3B2A.toInt(), 0xFF8A7458.toInt()),
        intArrayOf(0xFF121417.toInt(), 0xFFE4E6EA.toInt(), 0xFF9AA0A8.toInt())
    )
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
    val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
    val bar = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    val content = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(60))
    }
    val scroll = android.widget.ScrollView(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    }
    val siteTv = TextView(this).apply { textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); text = doc.site.uppercase() }
    val titleTv = TextView(this).apply {
        text = doc.title; textSize = size + 7; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(14)); setLineSpacing(0f, 1.1f)
    }
    val img = ImageView(this).apply {
        adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(210)).apply { bottomMargin = dp(16) }
        visibility = View.GONE
    }
    if (doc.image != null) { img.visibility = View.VISIBLE; img.load(doc.image) { crossfade(true); listener(onError = { _, _ -> img.visibility = View.GONE }) } }
    val bodyTv = TextView(this).apply {
        text = formatAi(doc.text.replace("\n\n", "\n\n​"))
        typeface = android.graphics.Typeface.SERIF
        setLineSpacing(0f, 1.45f)
        setTextIsSelectable(true)
    }
    content.addView(siteTv); content.addView(titleTv); content.addView(img); content.addView(bodyTv)
    val buttons = mutableListOf<android.widget.TextView>()
    var speaking = false
    fun applyStyle() {
        val (bg, fg, muted) = Triple(themes[theme][0], themes[theme][1], themes[theme][2])
        root.setBackgroundColor(bg)
        siteTv.setTextColor(muted); titleTv.setTextColor(fg); bodyTv.setTextColor(fg)
        titleTv.textSize = size + 7; bodyTv.textSize = size
        buttons.forEach { it.setTextColor(fg) }
        dialog.window?.statusBarColor = bg
        dialog.window?.navigationBarColor = bg
        dialog.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = theme != 2
        }
    }
    fun barBtn(label: String, desc: String, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 17f; gravity = android.view.Gravity.CENTER
        contentDescription = desc
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minWidth = dp(44)
        setOnClickListener { onClick(this) }
        buttons.add(this); bar.addView(this)
    }
    barBtn("←", "Chiudi") { dialog.dismiss() }
    bar.addView(View(this).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f) })
    barBtn("A−", "Testo più piccolo") { size = (size - 1).coerceAtLeast(14f); prefs.edit().putFloat("reader_size", size).apply(); applyStyle() }
    barBtn("A+", "Testo più grande") { size = (size + 1).coerceAtMost(30f); prefs.edit().putFloat("reader_size", size).apply(); applyStyle() }
    barBtn("◐", "Colori") { theme = (theme + 1) % 3; prefs.edit().putInt("reader_theme", theme).apply(); applyStyle() }
    val listenBtn = barBtn("🔊", "Ascolta") { b ->
        if (speaking) { tts?.stop(); speaking = false; b.text = "🔊" }
        else { speak(doc.title + ".\n" + doc.text, prefs.getFloat("reader_rate", 1f)); speaking = true; b.text = "⏹" }
    }
    barBtn("⋮", "Altro") { b ->
        val opts = mutableListOf("✨ Riassumi", "✨ Traduci in italiano", "🔊 Velocità di lettura", "Condividi")
        if (saved == null) opts.add(0, "🔖 Leggi dopo") else opts.add("Apri l'originale")
        MaterialAlertDialogBuilder(this).setItems(opts.toTypedArray()) { _, w ->
            when (opts[w]) {
                "🔖 Leggi dopo" -> saveForLater(doc)
                "✨ Riassumi" -> if (aiReady()) showAiSheet("Riassunto", pageContext(doc.title, doc.url, doc.text.take(24000)),
                    "Riassumi questo testo in italiano: una frase iniziale, poi 5-8 punti chiave.", false)
                "✨ Traduci in italiano" -> translateText(doc.title, doc.url, doc.text)
                "Condividi" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${doc.title}\n${doc.url}")
                }, "Condividi"))
                "Apri l'originale" -> { dialog.dismiss(); openInBrowser(doc.url) }
                "🔊 Velocità di lettura" -> {
                    val rates = floatArrayOf(0.8f, 1f, 1.2f, 1.5f, 1.8f)
                    val cur = rates.indexOfFirst { it == prefs.getFloat("reader_rate", 1f) }.coerceAtLeast(1)
                    MaterialAlertDialogBuilder(this).setTitle("Velocità di lettura")
                        .setSingleChoiceItems(arrayOf("Lenta", "Normale", "Un po' veloce", "Veloce", "Molto veloce"), cur) { dl, w ->
                            prefs.edit().putFloat("reader_rate", rates[w]).apply(); dl.dismiss()
                            if (speaking) { speak(doc.title + ".\n" + doc.text, rates[w]) }
                        }.show()
                }
            }
        }.show()
    }
    root.addView(bar); root.addView(scroll)
    dialog.setContentView(root)
    dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    dialog.setOnDismissListener { tts?.stop() }
    applyStyle()
    dialog.show()
    if (listen) listenBtn.performClick()
}

internal fun MainActivity.translateText(title: String, url: String, text: String) {
    if (!aiReady()) return
    showAiSheet(
        title = "Traduzione",
        context = pageContext(title, url, text.take(16000)),
        firstQuestion = "Traduci in italiano il testo della pagina, mantenendo titoli e paragrafi. Scrivi solo la traduzione.",
        showFirstQuestion = false
    )
}

internal fun MainActivity.translatePageWithAi() = extractReader { translateText(it.title, it.url, it.text) }

internal fun MainActivity.saveForLater(doc: ReaderDoc) {
    val list = readLater.load()
    list.removeAll { it.url == doc.url }
    list.add(0, ReadLaterItem(UUID.randomUUID().toString(), doc.url, doc.title, doc.site, doc.image, doc.text, System.currentTimeMillis()))
    io.execute { readLater.save(list) }
    toast("🔖 Salvato in Leggi dopo (anche offline)")
}

internal fun MainActivity.saveCurrentForLater() = extractReader { saveForLater(it) }

internal fun MainActivity.saveNewsForLater(item: NewsItem) {
    toast("Salvo l'articolo…")
    Thread {
        val text = runCatching { news.articleText(item.link) }.getOrDefault("").ifBlank { item.summary }
        runOnUiThread { saveForLater(ReaderDoc(item.title, item.source, item.link, item.image, text)) }
    }.start()
}

internal fun MainActivity.showReadLaterPage() {
    openPage("Leggi dopo", "Svuota letti", { dlg ->
        val list = readLater.load()
        if (list.none { it.read }) toast("Nessun articolo letto")
        else confirmClear("Eliminare gli articoli già letti?") { readLater.save(list.filter { !it.read }); dlg.dismiss(); showReadLaterPage() }
    }) { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = readLater.load()
            val offline = loadOfflinePages().size
            if (offline > 0) listRow(body, "📥 Pagine complete offline", "$offline pagine salvate con immagini", onClick = { showOfflinePages() })
            if (list.isEmpty()) {
                emptyLabel(body, "Nessun articolo salvato.\nNel browser: menu ⋮ → 🔖 Leggi dopo")
                return
            }
            listOf(false to "DA LEGGERE", true to "LETTI").forEach { (read, label) ->
                val group = list.filter { it.read == read }
                if (group.isEmpty()) return@forEach
                sectionLabel(body, "$label · ${group.size}")
                group.forEach { item ->
                    listRow(body, item.title, "${item.site} · ${NewsAdapter.ago(item.time)} · ${(item.text.length / 1200).coerceAtLeast(1)} min",
                        onClick = {
                            if (!item.read) { item.read = true; readLater.save(list) }
                            showReader(ReaderDoc(item.title, item.site, item.url, item.image, item.text), item)
                            render()
                        },
                        onLong = {
                            MaterialAlertDialogBuilder(this).setTitle(item.title)
                                .setItems(arrayOf("Apri l'originale", if (item.read) "Segna da leggere" else "Segna come letto", "🔊 Ascolta")) { _, w ->
                                    when (w) {
                                        0 -> { dialog.dismiss(); openInBrowser(item.url) }
                                        1 -> { item.read = !item.read; readLater.save(list); render() }
                                        2 -> showReader(ReaderDoc(item.title, item.site, item.url, item.image, item.text), item, listen = true)
                                    }
                                }.show()
                        },
                        onRemove = { list.remove(item); readLater.save(list); render() })
                }
            }
        }
        render()
    }
}

internal fun MainActivity.showNotesPage() {
    openPage("Note", "Nuova", { _ ->
        MaterialAlertDialogBuilder(this).setItems(arrayOf("📝 Nota", "☑ Lista con spunte", "🛒 Lista della spesa")) { _, w ->
            when (w) { 0 -> editNote(null); 1 -> showChecklist(null, "Lista"); else -> showChecklist(null, "Lista della spesa") }
        }.show()
    }) { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = notes.load().sortedByDescending { it.time }
            if (list.isEmpty()) { emptyLabel(body, "Nessuna nota.\nTocca «Nuova» in alto: puoi anche dettarla."); return }
            list.forEach { n ->
                val lines = n.text.trim().lines().filter { it.isNotBlank() }
                listRow(body, lines.firstOrNull()?.take(80) ?: "Nota",
                    "${NewsAdapter.ago(n.time)}${if (lines.size > 1) " · " + lines.drop(1).joinToString(" ").take(80) else ""}",
                    onClick = { if (isChecklist(n.text)) showChecklist(n.id) else editNote(n) { render() } },
                    onLong = {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text)
                        }, "Condividi nota"))
                    },
                    onRemove = { confirmClear("Eliminare la nota?") { notes.save(notes.load().filter { it.id != n.id }); render() } })
            }
        }
        render()
        notesRefresh = { render() }
        dialog.setOnDismissListener { notesRefresh = null }
    }
}

internal fun MainActivity.editNote(existing: Note?, onSaved: () -> Unit = { notesRefresh?.invoke() }) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    var saved = false
    openPage(if (existing == null) "Nuova nota" else "Nota", "Salva", { dlg -> dlg.dismiss() }) { _, body, dialog ->
        val et = EditText(this).apply {
            setText(existing?.text.orEmpty())
            textSize = 17f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            hint = "Scrivi o detta la tua nota…"
            gravity = android.view.Gravity.TOP
            minLines = 10
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        }
        val actions = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((6 * d).toInt(), (8 * d).toInt(), 0, (4 * d).toInt())
        }
        fun action(label: String, onClick: () -> Unit) = actions.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label; isAllCaps = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { onClick() }
        })
        action("🎤 Detta") {
            noteDictationTarget = et
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            }
            try { noteSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
        }
        action("✨ Riordina") {
            val text = et.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || !aiReady()) return@action
            toast("✨ Riordino…")
            ai.ask(listOf("user" to "Riordina questa nota in italiano: correggi gli errori, dai una struttura chiara " +
                "(titolo breve sulla prima riga, poi elenchi puntati con «- » dove servono). Non aggiungere informazioni. " +
                "Rispondi solo con la nota.\n\n$text"), null, object : AiBridge.Listener {
                override fun onDone(text: String, model: String) { et.setText(text.trim()); et.setSelection(et.text.length) }
                override fun onError(message: String, noKey: Boolean) { toast(message) }
            })
        }
        action("Condividi") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, et.text?.toString().orEmpty())
            }, "Condividi nota"))
        }
        body.addView(actions)
        body.addView(et)
        dialog.setOnDismissListener {
            if (saved) return@setOnDismissListener
            saved = true
            val text = et.text?.toString()?.trim().orEmpty()
            val list = notes.load()
            if (existing == null) {
                if (text.isNotEmpty()) list.add(Note(UUID.randomUUID().toString(), text, System.currentTimeMillis()))
            } else {
                val n = list.firstOrNull { it.id == existing.id }
                if (n != null && n.text != text) {
                    if (text.isEmpty()) list.remove(n) else { n.text = text; n.time = System.currentTimeMillis() }
                }
            }
            notes.save(list)
            onSaved()
        }
        if (existing == null) et.requestFocus()
    }
}

// ======================= QR scanner (Google code scanner, no camera permission) =======================

internal fun MainActivity.scanQr() {
    try {
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { b -> handleQr(b.rawValue.orEmpty(), b.url?.url) }
            .addOnFailureListener { toast("Scanner non disponibile: servono i Google Play Services aggiornati") }
    } catch (_: Throwable) {
        toast("Scanner non disponibile su questo telefono")
    }
}

internal fun MainActivity.handleQr(raw: String, url: String?) {
    if (raw.isBlank()) return
    val link = url ?: raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val b = MaterialAlertDialogBuilder(this).setTitle(if (link != null) "Link nel QR code" else "Testo nel QR code")
        .setMessage(raw.take(1000))
        .setNegativeButton("Copia") { _, _ -> copyText("QR", raw) }
    if (link != null) b.setPositiveButton("Apri") { _, _ -> openInBrowser(link) }
    else b.setPositiveButton("Cerca") { _, _ -> openInBrowser(searchUrl(raw)) }
    b.setNeutralButton("Condividi") { _, _ ->
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, raw)
        }, "Condividi"))
    }
    b.show()
}

internal fun MainActivity.renderHomeTools() {
    val row = findViewById<android.widget.LinearLayout>(R.id.homeTools)
    row.removeAllViews()
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun chip(label: String, onClick: () -> Unit) = row.addView(TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(palette.text)
        setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * d).toInt() }
        setOnClickListener { onClick() }
    })
    chip("📰 Notizie") { showTab(TAB_NEWS) }
    chip("📝 Note") { showNotesPage() }
    chip("🔖 Leggi dopo") { showReadLaterPage() }
    chip("🎧 Podcast") { audioTab = "podcast"; showTab(TAB_AUDIO) }
    chip("📻 Radio") { audioTab = "radio"; showTab(TAB_AUDIO) }
    chip("📷 QR") { scanQr() }
    chip("📚 Catalogo") { showCatalogPage() }
    chip("📄 Scanner") { scanDocument() }
    chip("🔦 Utilità") { showUtilitiesPage() }
    chip("📖 Libri") { showBooksPage() }
    chip("🎙 Registra") { showRecorderPage() }
    chip("💳 Carte") { showCardsPage() }
    chip("🎫 Biglietti") { showWalletPage() }
    chip("⏰ Promemoria") { showRemindersPage() }
    chip("📦 Pacchi") { showParcelsPage() }
    chip("⏱ Tempo") { showUsagePage() }
    chip("📄 PDF") { pdfPicker.launch(arrayOf("application/pdf")) }
}
