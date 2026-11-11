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

internal fun MainActivity.openExternally() {
    val url = webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Nessuna pagina aperta"); return }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    try { startActivity(Intent.createChooser(intent, "Apri con")) }
    catch (_: Exception) { toast("Nessun browser trovato") }
}

internal fun MainActivity.shareCurrentLink() {
    val url = webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Nessuna pagina aperta"); return }
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, webView.title.orEmpty())
    }, "Condividi link"))
}

internal fun MainActivity.showFindDialog() {
    val et = TextInputEditText(this).apply { hint = "Testo da cercare"; setSingleLine() }
    MaterialAlertDialogBuilder(this)
        .setTitle("Trova nella pagina")
        .setView(et)
        .setPositiveButton("Trova") { _, _ ->
            val q = et.text?.toString().orEmpty()
            if (q.isNotBlank()) webView.findAllAsync(q)
        }
        .setNeutralButton("Successivo") { _, _ -> webView.findNext(true) }
        .setNegativeButton("Chiudi") { _, _ -> webView.clearMatches() }
        .show()
}

internal fun MainActivity.noteUse(item: AppItem) {
    item.openCount += 1
    val idx = apps.indexOfFirst { it.id == item.id }
    if (idx > 0) {
        val prev = (idx - 1 downTo 0).firstOrNull {
            apps[it].pinned == item.pinned && apps[it].category == item.category
        }
        if (prev != null) {
            val moved = apps.removeAt(idx)
            apps.add(prev, moved)
        }
    }
    persistApps()
    refreshList()
}

internal fun MainActivity.showCategoryMenu(name: String) {
    if (name == LauncherAdapter.MOST_USED) {
        toast("Si aggiorna da sola in base a quanto usi le app")
        return
    }
    if (name == "Preferiti") {
        toast("I preferiti si gestiscono tenendo premuta l'icona")
        return
    }
    val options = arrayOf("Rinomina", "Elimina", "Sposta su", "Sposta giù")
    MaterialAlertDialogBuilder(this).setTitle(name).setItems(options) { _, which ->
        when (which) {
            0 -> {
                val et = TextInputEditText(this).apply { setText(name); setSingleLine() }
                MaterialAlertDialogBuilder(this).setTitle("Rinomina").setView(et)
                    .setPositiveButton("Salva") { _, _ ->
                        val next = et.text?.toString()?.trim().orEmpty()
                        if (next.isBlank()) return@setPositiveButton
                        apps.filter { it.category == name }.forEach { it.category = next }
                        repo.renameCategory(name, next)
                        categories = repo.loadCategories()
                        persistApps(); refreshList()
                    }.setNegativeButton("Annulla", null).show()
            }
            1 -> {
                apps.filter { it.category == name }.forEach { it.category = "Utility" }
                if ("Utility" !in categories) categories.add("Utility")
                categories.remove(name)
                repo.saveCategories(categories)
                repo.deleteCategory(name)
                categories = repo.loadCategories()
                if ("Utility" !in categories) {
                    categories.add("Utility")
                    repo.saveCategories(categories)
                }
                persistApps(); refreshList(); toast("App spostate in Utility")
            }
            2 -> { repo.moveCategory(name, -1); categories = repo.loadCategories(); refreshList() }
            3 -> { repo.moveCategory(name, 1); categories = repo.loadCategories(); refreshList() }
        }
    }.show()
}

internal fun MainActivity.pinToPhoneHome(item: AppItem) {
    if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
        toast("Questo telefono non permette i collegamenti")
        return
    }
    val launch = Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra("shortcut_url", item.url)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    toast("Preparo l'icona…")
    val local = item.iconPath?.let { java.io.File(it) }?.takeIf { it.exists() }
    val host = try { Uri.parse(item.url).host } catch (_: Exception) { null }
    val source: Any? = local ?: host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=256" }
    fun pin(icon: androidx.core.graphics.drawable.IconCompat) {
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "wl_${item.id}")
            .setShortLabel(item.name.take(24))
            .setIcon(icon)
            .setIntent(launch)
            .build()
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, info, null)
        toast("Conferma il collegamento se il telefono lo chiede")
    }
    val fallback = { androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher) }
    if (source == null) { pin(fallback()); return }
    val request = coil.request.ImageRequest.Builder(this)
        .data(source)
        .size(192)
        .allowHardware(false)
        .target(
            onSuccess = { drawable ->
                val bmp = runCatching { shortcutBitmap(drawable) }.getOrNull()
                pin(bmp?.let { androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(it) } ?: fallback())
            },
            onError = { pin(fallback()) }
        )
        .build()
    coil.Coil.imageLoader(this).enqueue(request)
}

/** Site icon centred on a white adaptive-icon canvas (108dp grid, icon inside the 66dp safe zone). */
internal fun MainActivity.shortcutBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
    val size = 432
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val inner = (size * 0.46f).toInt()
    val left = (size - inner) / 2
    drawable.setBounds(left, left, left + inner, left + inner)
    drawable.draw(canvas)
    return out
}

internal fun MainActivity.updateBrowserBody() {
    recentsPanel.isVisible = !browserHasPage
    webView.isVisible = browserHasPage
    if (!browserHasPage) renderRecents()
    updateStar()
}

internal fun MainActivity.isRealPageTitle(title: String, url: String): Boolean {
    if (title.isBlank() || url.isBlank() || url == "about:blank") return false
    if (title == "about:blank" || title == "about:srcdoc") return false
    return true
}

internal fun MainActivity.recentLabel(site: RecentSite): String {
    if (isRealPageTitle(site.title, site.url)) return site.title
    return try {
        Uri.parse(site.url).host?.removePrefix("www.") ?: site.url
    } catch (_: Exception) {
        site.url
    }
}

/** The empty browser: a clean start page. The list of recent pages has its own page (menu ⋮ → Recenti). */
internal fun MainActivity.renderRecents() {
    recentsList.removeAllViews()
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    recentsList.addView(TextView(this).apply {
        text = "🔎"; textSize = 44f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(56), 0, dp(8))
    })
    recentsList.addView(TextView(this).apply {
        text = "Scrivi un indirizzo o cerca nella barra qui sopra"; textSize = 16f; gravity = android.view.Gravity.CENTER
        setTextColor(palette.textMuted); setPadding(dp(16), 0, dp(16), dp(20))
    })
    val n = repo.loadRecents().size
    if (n > 0) recentsList.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "🕘 Recenti ($n)"; isAllCaps = false
        layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        setOnClickListener { showRecentsPage() }
    })
}

internal fun MainActivity.showRecentsPage() {
    openPage("Recenti") { _, body, dialog ->
        fun r() { renderRecentsInto(body, open = { url -> dialog.dismiss(); openInBrowser(url) }, rerender = { r() }) }
        r()
        dialog.setOnDismissListener { if (!browserHasPage) renderRecents() }
    }
}

internal fun MainActivity.renderRecentsInto(recentsList: android.widget.LinearLayout, open: (String) -> Unit, rerender: () -> Unit) {
    recentsList.removeAllViews()
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val items = repo.loadRecents()
    val header = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(this).apply {
        text = "Recenti"
        setTextColor(AppRepository.palette(this@renderRecentsInto).text)
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setPadding(dp(4), dp(8), dp(4), dp(12))
    })
    if (items.isNotEmpty()) {
        header.addView(TextView(this).apply {
            text = "Svuota"
            setTextColor(accent)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(4), dp(12))
            setOnClickListener {
                confirmClear("Eliminare tutti i recenti?") {
                    repo.clearRecents()
                    rerender()
                    toast("Recenti eliminati")
                }
            }
        })
    }
    recentsList.addView(header)
    if (items.isEmpty()) {
        recentsList.addView(TextView(this).apply {
            text = "Nessuna pagina ancora."
            setTextColor(AppRepository.palette(this@renderRecentsInto).textMuted)
            textSize = 14f
            setPadding(dp(4), 0, dp(4), 0)
        })
        return
    }
    items.forEach { site ->
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(AppRepository.palette(this@renderRecentsInto).card)
            strokeWidth = dp(1)
            strokeColor = AppRepository.palette(this@renderRecentsInto).stroke
            cardElevation = 0f
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { open(site.url) }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(this@renderRecentsInto)
                    .setTitle(recentLabel(site))
                    .setItems(arrayOf("Apri", "Elimina")) { dlg, which ->
                        if (which == 0) open(site.url)
                        else {
                            repo.removeRecent(site.url)
                            rerender()
                        }
                        dlg.dismiss()
                    }
                    .show()
                true
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(4), dp(10))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = recentLabel(site)
            setTextColor(AppRepository.palette(this@renderRecentsInto).text)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        box.addView(TextView(this).apply {
            text = site.url
            setTextColor(AppRepository.palette(this@renderRecentsInto).textMuted)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(box)
        row.addView(TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(AppRepository.palette(this@renderRecentsInto).textMuted)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "Elimina dai recenti"
            setOnClickListener {
                repo.removeRecent(site.url)
                rerender()
            }
        })
        card.addView(row)
        recentsList.addView(card)
    }
}

internal fun MainActivity.applyReorder(fromId: String, toId: String) {
    val fromIdx = apps.indexOfFirst { it.id == fromId }
    val toIdx = apps.indexOfFirst { it.id == toId }
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
    val item = apps.removeAt(fromIdx)
    apps.add(toIdx, item)
    persistApps()
}

internal fun MainActivity.refreshList() {
    adapter.submit(apps, categories, searchInput.text?.toString().orEmpty(), repo.showMostUsed())
    findViewById<View>(R.id.emptyState).isVisible = apps.isEmpty()
}

internal fun MainActivity.updateWebHint(q: String) {
    val text = q.trim()
    val aiHint = findViewById<TextView>(R.id.aiSearchHint)
    if (text.isEmpty()) {
        webSearchHint.visibility = View.GONE
        aiHint.visibility = View.GONE
    } else {
        webSearchHint.visibility = View.VISIBLE
        webSearchHint.text = "Cerca con ${repo.getSearchEngine().label}: $text"
        aiHint.visibility = if (looksLikeUrl(text)) View.GONE else View.VISIBLE
        aiHint.text = "✨ Chiedi all'AI: $text"
    }
}

internal fun MainActivity.runSearchFromBox() {
    val q = searchInput.text?.toString()?.trim().orEmpty()
    if (q.isEmpty()) return
    val match = findAppByName(q)
    when {
        match != null -> openApp(match)
        looksLikeUrl(q) -> openInBrowser(normalizeUrl(q))
        else -> openInBrowser(searchUrl(q))
    }
    searchInput.setText("")
    refreshList()
}

internal fun MainActivity.handleVoiceText(text: String) {
    toast("Hai detto: $text")
    if (systemVoiceCommand(text)) return
    if (musicVoice(text)) return
    if (Regex("^(ho\\s+)?(speso|pagato)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
        Expenses.parseVoice(text)?.let { (amount, desc) -> addExpenseDialog(amount, desc); return }
    }
    if (Regex("(ho parcheggiato|parcheggiato qui|salva (il )?parcheggio)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { parkHere(); return }
    if (Regex("(dove ho parcheggiato|portami alla macchina|portami all'auto)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        Places.parking(this)?.let { navigateTo(it.lat, it.lon, true) } ?: toast("Nessun parcheggio salvato"); return
    }
    Regex("^aggiungi\\s+(.+?)\\s+(?:alla|nella|in)\\s+(lista[\\w\\s]*)$", RegexOption.IGNORE_CASE).find(text.trim())?.let { m ->
        val listName = m.groupValues[2].trim().let { if (it.equals("lista", true)) "Lista della spesa" else it }
        addToShoppingList(m.groupValues[1], listName); return
    }
    if (Regex("^(ricordami|ricorda|promemoria|avvisami)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) {
        createReminderFromText(text, fromVoice = true); return
    }
    val fallback = {
        val match = findAppByName(text, voice = true)
        when {
            match != null -> openApp(match)
            looksLikeUrl(text) -> openInBrowser(normalizeUrl(text))
            else -> openInBrowser(searchUrl(text))
        }
    }
    val words = text.trim().split(Regex("\\s+")).size
    if (looksLikeQuestion(text) && ai.supported() && findAppByName(text) == null) { askByVoice(text); return }
    val simple = findAppByName(text) != null || looksLikeUrl(text) || words <= 3
    if (repo.aiVoice() && !simple) interpretVoice(text, fallback) else fallback()
}

/** Exact name first; then a single partial match; for voice also "apri X" and names inside the phrase. */
internal fun MainActivity.findAppByName(raw: String, voice: Boolean = false): AppItem? {
    val q = raw.trim().lowercase(Locale.ROOT)
        .removePrefix("apri ").removePrefix("vai su ").removePrefix("open ").trim()
    if (q.isEmpty()) return null
    apps.firstOrNull { it.name.lowercase(Locale.ROOT) == q }?.let { return it }
    val partial = apps.filter { it.name.lowercase(Locale.ROOT).startsWith(q) }
    if (partial.size == 1) return partial.first()
    if (voice) {
        return apps.filter { it.name.length >= 3 && q.contains(it.name.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.name.length }
    }
    return null
}

internal fun MainActivity.requestMicAndListen() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        startVoiceRecognition()
    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}

internal fun MainActivity.startVoiceRecognition() {
    if (!isSpeechAvailable()) { toast(getString(R.string.no_speech)); return }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toString())
        putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }
    try { speechLauncher.launch(intent) } catch (_: ActivityNotFoundException) { toast(getString(R.string.no_speech)) }
}

internal fun MainActivity.isSpeechAvailable(): Boolean =
    packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

internal fun MainActivity.looksLikeUrl(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.contains(' ')) return false
    if (t.startsWith("http://", true) || t.startsWith("https://", true)) return true
    if (t.startsWith("localhost", true)) return true
    val host = t.substringBefore('/').substringBefore(':')
    return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
}

internal fun MainActivity.normalizeUrl(raw: String): String {
    val u = raw.trim()
    return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
}

internal fun MainActivity.searchUrl(query: String): String = repo.getSearchEngine().buildUrl(query)

internal fun MainActivity.showAppMenu(item: AppItem) {
    val options = arrayOf(
        "Sposta",
        if (item.pinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
        "Modifica",
        "Cambia icona",
        "Collegamento sul telefono",
        if (item.locked) "Rimuovi blocco impronta" else "Blocca con impronta",
        "Elimina",
        if (item.desktop) "🖥 Versione desktop: sì (tocca per togliere)" else "🖥 Apri sempre in versione desktop",
        if (item.folder.isNullOrBlank()) "📁 Metti in una cartella" else "📁 Togli dalla cartella «${item.folder}»"
    )
    MaterialAlertDialogBuilder(this).setTitle(item.name).setItems(options) { _, which ->
        when (which) {
            0 -> adapter.startDrag(item.id)
            1 -> {
                item.pinned = !item.pinned
                persistApps(); refreshList()
                toast(if (item.pinned) "Aggiunta ai preferiti" else "Rimossa dai preferiti")
            }
            2 -> showAddEditDialog(item)
            3 -> { iconTargetId = item.id; iconPicker.launch("image/*") }
            4 -> pinToPhoneHome(item)
            5 -> if (item.locked) {
                authenticate("Rimuovi blocco da ${item.name}", strict = false) {
                    item.locked = false; persistApps(); refreshList(); toast("Blocco rimosso")
                }
            } else {
                authenticate("Blocca ${item.name}") {
                    item.locked = true; persistApps(); refreshList()
                    toast("${item.name} chiederà l'impronta")
                }
            }
            8 -> if (!item.folder.isNullOrBlank()) { item.folder = null; persistApps(); refreshList() } else chooseFolder(item)
            7 -> {
                item.desktop = !item.desktop; persistApps()
                toast(if (item.desktop) "${item.name} si aprirà in versione desktop" else "${item.name} si aprirà in versione mobile")
            }
            6 -> MaterialAlertDialogBuilder(this).setMessage("Eliminare \"${item.name}\"?")
                .setPositiveButton("Elimina") { _, _ ->
                    item.iconPath?.let { runCatching { java.io.File(it).delete() } }
                    apps.removeAll { it.id == item.id }; persistApps(); refreshList(); toast("Eliminata")
                }.setNegativeButton("Annulla", null).show()
        }
    }.show()
}

internal fun MainActivity.showAddEditDialog(existing: AppItem?) {
    val padding = (20 * resources.displayMetrics.density).toInt()
    val container = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(padding, padding / 2, padding, 0)
    }
    fun field(hint: String, value: String): TextInputEditText {
        val til = TextInputLayout(this).apply {
            this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val et = TextInputEditText(til.context).apply { setText(value); setSingleLine() }
        til.addView(et); container.addView(til); return et
    }
    val nameEt = field("Nome", existing?.name.orEmpty())
    val urlEt = field("URL", existing?.url.orEmpty())
    val catEt = field("Categoria", existing?.category ?: categories.firstOrNull().orEmpty())
    MaterialAlertDialogBuilder(this)
        .setTitle(if (existing == null) "Aggiungi Web App" else "Modifica / Sposta")
        .setView(container)
        .setPositiveButton("Salva") { _, _ ->
            val name = nameEt.text?.toString()?.trim().orEmpty()
            val url = urlEt.text?.toString()?.trim().orEmpty()
            val cat = catEt.text?.toString()?.trim().orEmpty().ifBlank { "Utility" }
            if (name.isBlank() || url.isBlank()) { toast("Nome e URL obbligatori"); return@setPositiveButton }
            if (cat !in categories) { categories.add(cat); repo.saveCategories(categories) }
            if (existing == null) {
                apps.add(AppItem(UUID.randomUUID().toString(), name, normalizeUrl(url), cat, false, apps.size))
                toast("App aggiunta")
            } else {
                existing.name = name; existing.url = normalizeUrl(url); existing.category = cat
                toast("App aggiornata")
            }
            persistApps(); refreshList()
        }.setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.cleanTitle(raw: String, url: String): String {
    val host = try { Uri.parse(url).host?.removePrefix("www.")?.substringBefore('.') } catch (_: Exception) { null }
        ?.replaceFirstChar { it.uppercase() }
    val parts = raw.split(" - ", " | ", " – ", " — ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
    val last = parts.lastOrNull()
    return when {
        parts.isEmpty() -> host ?: "Sito"
        raw.length <= 22 -> raw.trim()
        last != null && parts.size > 1 && last.length <= 22 -> last
        parts.first().length <= 22 -> parts.first()
        else -> host ?: parts.first().take(22)
    }
}

internal fun MainActivity.isSaved(url: String?): Boolean {
    val u = url?.trimEnd('/')?.lowercase() ?: return false
    return apps.any { it.url.trimEnd('/').lowercase() == u }
}

internal fun MainActivity.updateStar() {
    renderTabStrip()
}

internal fun MainActivity.currentTabIsBrowser() = browserHasPage && webView.url?.startsWith("http") == true

internal fun MainActivity.offerAddCurrentPage() {
    val url = webView.url?.trim().orEmpty()
    if (url.isBlank() || url == "about:blank" || !url.startsWith("http")) {
        toast("Nessuna pagina valida da aggiungere")
        return
    }
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val title = cleanTitle(webView.title?.trim().orEmpty(), url)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(22), dp(6), dp(22), dp(4))
    }
    val nameEt = TextInputEditText(this).apply {
        setText(title)
        setSingleLine()
        setSelection(text?.length ?: 0)
        setTextColor(palette.text)
    }
    val til = TextInputLayout(this).apply {
        hint = "Nome"
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        addView(nameEt)
    }
    root.addView(til)
    root.addView(TextView(this).apply {
        text = try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
        setTextColor(palette.textMuted)
        textSize = 13f
        setPadding(dp(4), dp(8), dp(4), dp(18))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    fun actionBtn(label: String, filled: Boolean): com.google.android.material.button.MaterialButton {
        val b = if (filled) com.google.android.material.button.MaterialButton(this)
            else com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        b.cornerRadius = dp(22)
        b.minHeight = dp(52)
        if (!filled) {
            b.setTextColor(accent)
            b.strokeColor = android.content.res.ColorStateList.valueOf(accent)
            b.strokeWidth = dp(1)
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            b.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.star))
        }
        val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = if (filled) dp(10) else 0
        b.layoutParams = lp
        return b
    }
    val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    val homeBtn = actionBtn("Home", true)
    val favBtn = actionBtn("Preferiti", false).apply { setIconResource(R.drawable.ic_web_star_filled) }
    row.addView(homeBtn)
    row.addView(favBtn)
    root.addView(row)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle("Salva pagina")
        .setView(root)
        .setNegativeButton("Annulla", null)
        .create()
    homeBtn.setOnClickListener {
        val name = nameEt.text?.toString()?.trim().orEmpty().ifBlank { title }
        dialog.dismiss()
        addCurrentAsApp(name, url, pinned = false)
    }
    favBtn.setOnClickListener {
        val name = nameEt.text?.toString()?.trim().orEmpty().ifBlank { title }
        dialog.dismiss()
        addCurrentAsApp(name, url, pinned = true)
    }
    dialog.show()
}

internal fun MainActivity.addCurrentAsApp(name: String, url: String, pinned: Boolean) {
    val normalized = normalizeUrl(url)
    val existing = apps.firstOrNull { it.url.equals(normalized, ignoreCase = true) }
    if (existing != null) {
        if (pinned && !existing.pinned) {
            existing.pinned = true
            persistApps()
            refreshList()
            toast("Già presente — aggiunta ai preferiti")
        } else toast("Questa pagina è già tra le app")
        return
    }
    val cat = "Utility"
    if (cat !in categories) {
        categories.add(cat)
        repo.saveCategories(categories)
    }
    apps.add(AppItem(UUID.randomUUID().toString(), name.take(40), normalized, cat, pinned, apps.size))
    persistApps()
    refreshList()
    updateStar()
    toast(if (pinned) "Aggiunta ai preferiti" else "Aggiunta alla home")
}
