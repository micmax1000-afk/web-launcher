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

// ======================= News aggregator =======================

internal fun MainActivity.setupNews() {
    newsAdapter = NewsAdapter(
        onClick = { openInBrowser(it.link) },
        onLong = { showNewsMenu(it) }
    )
    val list = findViewById<RecyclerView>(R.id.newsList)
    list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
    list.adapter = newsAdapter
    val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.newsRefresh)
    refresh.setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
    refresh.setOnRefreshListener { refreshNews() }
    refresh.setColorSchemeColors(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
    findViewById<View>(R.id.btnNewsSources).setOnClickListener { showNewsSources() }
    findViewById<View>(R.id.btnNewsBrief).setOnClickListener { newsBriefing() }
    val palette = AppRepository.palette(this)
    findViewById<View>(R.id.newsPanel).setBackgroundColor(palette.bg)
    findViewById<TextView>(R.id.newsHeading).setTextColor(palette.text)
    findViewById<TextView>(R.id.newsUpdated).setTextColor(palette.textMuted)
    findViewById<TextView>(R.id.newsEmpty).setTextColor(palette.textMuted)
    findViewById<android.widget.ImageButton>(R.id.btnNewsSources).setColorFilter(palette.text)
}

internal fun MainActivity.onNewsShown() {
    if (newsItems.isEmpty()) {
        val (cached, updated) = news.cached()
        newsItems = cached
        newsUpdatedAt = updated
        renderNews()
    }
    if (!newsLoading && System.currentTimeMillis() - newsUpdatedAt > 15 * 60_000L) refreshNews()
}

internal fun MainActivity.refreshNews() {
    if (newsLoading) return
    newsLoading = true
    val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.newsRefresh)
    refresh.isRefreshing = true
    findViewById<TextView>(R.id.newsUpdated).text = "Aggiornamento…"
    Thread {
        val (items, failed) = runCatching { news.refresh() }.getOrElse { emptyList<NewsItem>() to emptyList() }
        runOnUiThread {
            newsLoading = false
            refresh.isRefreshing = false
            if (items.isNotEmpty()) {
                newsItems = items
                newsUpdatedAt = System.currentTimeMillis()
                runCatching { NewsWidget.save(this, items) }
            } else if (newsItems.isEmpty() && news.enabledIds().isNotEmpty()) {
                toast("Nessuna notizia: controlla la connessione")
            }
            if (failed.isNotEmpty() && items.isNotEmpty()) {
                toast("Non raggiungibili: " + failed.take(3).joinToString(", ") + if (failed.size > 3) "…" else "")
            }
            renderNews()
        }
    }.start()
}

internal fun MainActivity.showCrashReport(report: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Si è verificato un errore")
        .setMessage("L'ultima volta qualcosa non ha funzionato. Copia il rapporto e mandalo per farlo correggere.\n\n" +
            report.lines().take(6).joinToString("\n"))
        .setPositiveButton("Copia rapporto") { _, _ -> copyText("Rapporto errore", report); toast("Rapporto copiato"); CrashReporter.clear(this) }
        .setNeutralButton("Condividi") { _, _ ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
            }, "Invia rapporto"))
            CrashReporter.clear(this)
        }
        .setNegativeButton("Chiudi") { _, _ -> CrashReporter.clear(this) }
        .show()
}

internal fun MainActivity.renderNews() {
    try { renderNewsUnsafe() } catch (e: Exception) {
        CrashReporter.log(this, "notizie", e)
        toast("Errore nelle notizie: rapporto salvato")
    }
}

internal fun MainActivity.renderNewsUnsafe() {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val cats = NewsRepository.CATEGORY_ORDER.filter { c -> newsItems.any { it.cat == c } }
    if (newsCat != NEWS_ALL && newsCat !in cats) newsCat = NEWS_ALL
    val chips = findViewById<android.widget.LinearLayout>(R.id.newsChips)
    chips.removeAllViews()
    (listOf(NEWS_ALL) + cats).forEach { c ->
        val active = c == newsCat
        chips.addView(TextView(this).apply {
            text = c
            textSize = 14f
            setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(if (active) accent else palette.card)
                setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                newsCat = c
                renderNews()
                this@renderNewsUnsafe.findViewById<RecyclerView>(R.id.newsList).scrollToPosition(0)
            }
        })
    }
    val shown = if (newsCat == NEWS_ALL) newsItems else newsItems.filter { it.cat == newsCat }
    newsAdapter.submit(shown)
    val empty = findViewById<TextView>(R.id.newsEmpty)
    empty.isVisible = shown.isEmpty() && !newsLoading
    empty.text = if (news.enabledIds().isEmpty()) "Nessuna fonte attiva.\nTocca ⚙ in alto per sceglierle."
        else "Trascina in giù per caricare le notizie"
    if (!newsLoading) findViewById<TextView>(R.id.newsUpdated).text =
        if (newsUpdatedAt > 0) "Aggiornate ${NewsAdapter.ago(newsUpdatedAt)} · ${news.enabledIds().size} fonti" else ""
}

internal fun MainActivity.showNewsMenu(item: NewsItem) {
    MaterialAlertDialogBuilder(this)
        .setTitle(item.title)
        .setItems(arrayOf("✨ Riassumi con AI", "Apri in nuova scheda", "Condividi", "Copia link", "Nascondi ${item.source}",
            "📖 Leggi senza pubblicità", "🔊 Ascolta", "🔖 Leggi dopo")) { _, w ->
            when (w) {
                5, 6 -> {
                    toast("Carico l'articolo…")
                    Thread {
                        val text = runCatching { news.articleText(item.link) }.getOrDefault("")
                        runOnUiThread {
                            if (text.length < 300) { toast("Articolo non leggibile (forse a pagamento): lo apro"); openInBrowser(item.link) }
                            else showReader(ReaderDoc(item.title, item.source, item.link, item.image, text), listen = w == 6)
                        }
                    }.start()
                }
                7 -> saveNewsForLater(item)
                0 -> summarizeNews(item)
                1 -> openInNewTab(item.link)
                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.link}")
                }, "Condividi notizia"))
                3 -> copyText("Link", item.link)
                4 -> {
                    news.setEnabled(item.sourceId, false)
                    newsItems = newsItems.filter { it.sourceId != item.sourceId }
                    renderNews()
                    toast("${item.source} nascosta. La riattivi da ⚙")
                }
            }
        }
        .show()
}

internal fun MainActivity.summarizeNews(item: NewsItem) {
    if (!aiReady()) return
    toast("✨ Leggo l'articolo…")
    Thread {
        val text = runCatching { news.articleText(item.link) }.getOrDefault("")
        runOnUiThread {
            val body = if (text.length > 400) text else "${item.summary}\n\n(Testo completo non disponibile: forse è a pagamento.)"
            showAiSheet(
                title = "Riassunto · ${item.source}",
                context = pageContext(item.title, item.link, body),
                firstQuestion = "Riassumi questa notizia in italiano: una frase iniziale e 3-5 punti chiave. Se il testo è incompleto, dillo.",
                showFirstQuestion = false
            )
        }
    }.start()
}

internal fun MainActivity.newsBriefing() {
    if (!aiReady()) return
    if (newsItems.isEmpty()) { toast("Prima carica le notizie"); return }
    val since = System.currentTimeMillis() - 24 * 3600_000L
    val pool = newsItems.filter { it.time >= since }.ifEmpty { newsItems }
    val top = pool.sortedWith(compareByDescending<NewsItem> { (it.also ?: emptyList()).size }.thenByDescending { it.time }).take(45)
    val lines = top.joinToString("\n") { n ->
        val also = n.also ?: emptyList()
        "- [${n.cat}] ${n.title} (${n.source}${if (also.isNotEmpty()) ", anche su " + also.joinToString(", ") else ""})"
    }
    showAiSheet(
        title = "Il punto della giornata",
        context = "Ecco i titoli delle notizie più recenti da varie testate italiane (tra parentesi le fonti; " +
            "più fonti = notizia più importante):\n$lines\n\n",
        firstQuestion = "Fammi il punto della giornata in italiano: 6 punti sulle notizie più importanti, dalla più rilevante, " +
            "una o due frasi ciascuno. Poi una riga con le notizie locali di Piemonte, Lombardia e Umbria se presenti. " +
            "Usa solo queste informazioni.",
        showFirstQuestion = false
    )
}

internal fun MainActivity.showNewsSources() {
    openPage("Fonti notizie", "Aggiungi", { _ -> addNewsSourceDialog() }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val enabled = news.enabledIds()
            body.addView(com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Cerca le notizie dei siti nella tua home"
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                setOnClickListener { discoverHomeFeeds { render() } }
            })
            val all = news.allSources()
            var regionsHeader = false
            NewsRepository.CATEGORY_ORDER.forEach { cat ->
                val group = all.filter { it.cat == cat }
                if (group.isEmpty()) return@forEach
                val on = group.count { it.id in enabled }
                val isRegion = cat in NewsRepository.REGIONS
                if (isRegion) {
                    if (!regionsHeader) { sectionLabel(body, "REGIONI · tocca per aprire"); regionsHeader = true }
                    val open = cat in openRegions
                    body.addView(TextView(this).apply {
                        text = (if (open) "▾  " else "▸  ") + cat + if (on > 0) "   ·  $on attive" else ""
                        textSize = 16f; setTextColor(palette.text)
                        if (on > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setOnClickListener { if (open) openRegions.remove(cat) else openRegions.add(cat); render() }
                    })
                    if (!open) return@forEach
                } else sectionLabel(body, "$cat  ·  $on/${group.size}")
                group.forEach { s ->
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minimumHeight = dp(54)
                        setPadding(dp(10), 0, dp(4), 0)
                    }
                    val box = android.widget.CheckBox(this).apply {
                        isChecked = s.id in enabled
                        text = s.name
                        textSize = 16f
                        setTextColor(palette.text)
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnCheckedChangeListener { _, checked ->
                            news.setEnabled(s.id, checked)
                            newsUpdatedAt = 0L
                        }
                    }
                    row.addView(box)
                    if (s.custom) row.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_web_close)
                        setColorFilter(palette.textMuted)
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), dp(52))
                        contentDescription = "Elimina fonte"
                        setOnClickListener { news.removeCustom(s.id); render() }
                    })
                    body.addView(row)
                }
            }
        }
        render()
        dialog.setOnDismissListener {
            newsItems = newsItems.filter { it.sourceId in news.enabledIds() }
            renderNews()
            if (currentTab == TAB_NEWS && newsUpdatedAt == 0L) refreshNews()
        }
    }
}

internal fun MainActivity.addNewsSourceDialog(onAdded: () -> Unit = {}) {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply {
        hint = "Città, argomento o sito (es. Alba, Juventus, ilpost.it)"
        setSingleLine()
        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val box = android.widget.FrameLayout(this).apply {
        setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
        addView(et)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Aggiungi fonte")
        .setMessage("• Una città o un argomento: notizie da Google News\n• L'indirizzo di un sito: trovo da solo il suo feed")
        .setView(box)
        .setPositiveButton("Cerca") { _, _ ->
            val input = et.text?.toString()?.trim().orEmpty()
            if (input.isEmpty()) return@setPositiveButton
            if (!input.contains('.') && !input.startsWith("http")) {
                val name = input.replaceFirstChar { it.uppercase() }
                val url = "https://news.google.com/rss/search?q=" + Uri.encode("$input when:2d") + "&hl=it&gl=IT&ceid=IT:it"
                news.addCustom("$name (Google News)", url)
                newsUpdatedAt = 0L
                toast("Aggiunte le notizie su $name")
                onAdded()
                if (currentTab == TAB_NEWS) refreshNews()
                return@setPositiveButton
            }
            toast("Cerco il feed…")
            Thread {
                val found = runCatching { news.discover(input) }.getOrNull()
                runOnUiThread {
                    if (found == null) toast("Nessun feed trovato per questo sito")
                    else {
                        news.addCustom(found.first, found.second)
                        newsUpdatedAt = 0L
                        toast("Aggiunta: ${found.first}")
                        onAdded()
                    }
                }
            }.start()
        }
        .setNegativeButton("Annulla", null)
        .show()
}

/** Looks for RSS feeds of the sites saved in the home and lets the user pick which to add. */
internal fun MainActivity.discoverHomeFeeds(onDone: () -> Unit) {
    val known = news.allSources().mapNotNull { hostOf(it.url) }.toSet()
    val candidates = apps.filter { !MiaAi.isMiaUrl(it.url) }
        .filter { a -> hostOf(normalizeUrl(a.url))?.let { h -> known.none { k -> k.endsWith(h) || h.endsWith(k) } } == true }
        .take(25)
    if (candidates.isEmpty()) { toast("Nessun sito nuovo nella home da controllare"); return }
    toast("Controllo ${candidates.size} siti…")
    Thread {
        val found = candidates.mapNotNull { a ->
            runCatching { news.discover(normalizeUrl(a.url)) }.getOrNull()?.let { a.name to it.second }
        }
        runOnUiThread {
            if (found.isEmpty()) { toast("Nessun feed trovato nei siti della home"); return@runOnUiThread }
            val checked = BooleanArray(found.size) { true }
            MaterialAlertDialogBuilder(this)
                .setTitle("Feed trovati (${found.size})")
                .setMultiChoiceItems(found.map { it.first }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Aggiungi") { _, _ ->
                    found.forEachIndexed { i, (name, url) -> if (checked[i]) news.addCustom(name, url) }
                    newsUpdatedAt = 0L
                    onDone()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }.start()
}
