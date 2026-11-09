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

// ======================= Recipes =======================

internal fun MainActivity.addItemsToShoppingList(items: List<String>) {
    val name = "Lista della spesa"
    val list = notes.load()
    var n = list.firstOrNull { isChecklist(it.text) && it.text.lines().first().trim().equals(name, true) }
    if (n == null) { n = Note(UUID.randomUUID().toString(), name, System.currentTimeMillis()); list.add(n) }
    n.text = (n.text.trimEnd() + "\n" + items.filter { it.isNotBlank() }.joinToString("\n") { "☐ " + it.trim().replaceFirstChar { c -> c.uppercase() } }).trim()
    n.time = System.currentTimeMillis()
    notes.save(list)
    toast("🛒 ${items.size} ingredienti nella lista della spesa")
}

internal fun MainActivity.saveRecipeFromPage() {
    if (!aiReady()) return
    val url = webView.url ?: return
    webView.evaluateJavascript("(function(){var m=document.querySelector('meta[property=\"og:image\"]');return m?m.content:''})()") { rawImg ->
        val img = jsString(rawImg).takeIf { it.startsWith("http") }
        readPage { title, _, text ->
            toast("✨ Preparo la scheda della ricetta…")
            ai.ask(listOf("user" to "Dalla pagina estrai la ricetta e rispondi SOLO con JSON: {\"titolo\":\"...\",\"porzioni\":\"...\"," +
                "\"tempo\":\"...\",\"ingredienti\":[\"200 g di farina\",...],\"passaggi\":[\"...\",...]}. Passaggi brevi e chiari, in italiano. " +
                "Se non c'è una ricetta rispondi {\"errore\":\"nessuna ricetta\"}.\n\n--- PAGINA «$title» ---\n${text.take(20000)}"),
                "Rispondi solo con JSON valido.", object : AiBridge.Listener {
                    override fun onDone(text: String, model: String) {
                        val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                        val ing = o?.optJSONArray("ingredienti")
                        val steps = o?.optJSONArray("passaggi")
                        if (o == null || ing == null || ing.length() == 0) { toast("In questa pagina non trovo una ricetta"); return }
                        val r = Recipe(UUID.randomUUID().toString(), o.optString("titolo").ifBlank { title }, url, img,
                            o.optString("porzioni"), o.optString("tempo"),
                            (0 until ing.length()).map { ing.optString(it) }, (0 until (steps?.length() ?: 0)).map { steps!!.optString(it) },
                            System.currentTimeMillis())
                        Recipes.save(this@saveRecipeFromPage, listOf(r) + Recipes.load(this@saveRecipeFromPage).filter { it.url != url })
                        showRecipe(r)
                    }
                    override fun onError(message: String, noKey: Boolean) { toast(message) }
                })
        }
    }
}

internal fun MainActivity.showRecipesPage() {
    openPage("Ricette") { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = Recipes.load(this)
            if (list.isEmpty()) { emptyLabel(body, "Nessuna ricetta.\nApri una ricetta in un sito → menu ⋮ → «🍝 Salva ricetta»."); return }
            list.forEach { r ->
                mediaRow(body, r.image, r.title, listOf(r.time, r.servings.takeIf { it.isNotBlank() }?.let { "$it porzioni" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "${r.ingredients.size} ingredienti" },
                    null, trailing = listOf(R.drawable.ic_web_close to {
                        confirmClear("Eliminare «${r.title}»?") { Recipes.save(this, Recipes.load(this).filter { it.id != r.id }); render() }
                    })) { showRecipe(r) }
            }
        }
        render()
    }
}

internal fun MainActivity.showRecipe(r: Recipe) {
    openPage(r.title.take(30)) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        r.image?.let { img -> body.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(190)).apply { setMargins(dp(6), 0, dp(6), dp(8)) }
            load(img) { transformations(coil.transform.RoundedCornersTransformation(32f)) }
        }) }
        body.addView(TextView(this).apply {
            text = r.title; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); setPadding(dp(8), 0, dp(8), dp(4))
        })
        body.addView(TextView(this).apply {
            text = listOf(r.time, r.servings.takeIf { it.isNotBlank() }?.let { "$it porzioni" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 14f; setTextColor(palette.textMuted); setPadding(dp(8), 0, dp(8), dp(8))
        })
        val btns = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
        btns.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "👨‍🍳 Modalità cucina"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { cookingMode(r) }
        })
        btns.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🛒 Alla lista"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                val checked = BooleanArray(r.ingredients.size) { true }
                MaterialAlertDialogBuilder(this@showRecipe).setTitle("Cosa ti manca?")
                    .setMultiChoiceItems(r.ingredients.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("Aggiungi") { _, _ -> addItemsToShoppingList(r.ingredients.filterIndexed { i, _ -> checked[i] }) }
                    .setNegativeButton("Annulla", null).show()
            }
        })
        body.addView(btns)
        sectionLabel(body, "INGREDIENTI · ${r.ingredients.size}")
        r.ingredients.forEach { ing ->
            body.addView(android.widget.CheckBox(this).apply { text = ing; textSize = 16f; setTextColor(palette.text); setPadding(dp(4), dp(2), 0, dp(2)) })
        }
        sectionLabel(body, "PROCEDIMENTO")
        r.steps.forEachIndexed { i, st ->
            body.addView(TextView(this).apply {
                text = "${i + 1}.  $st"; textSize = 16f; setTextColor(palette.text); setLineSpacing(0f, 1.2f); setPadding(dp(10), dp(6), dp(8), dp(6))
            })
        }
        body.addView(TextView(this).apply {
            text = "Fonte: ${displayUrl(r.url)} ›"; textSize = 13f; setPadding(dp(10), dp(14), 0, 0)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
            setOnClickListener { dialog.dismiss(); openInBrowser(r.url) }
        })
    }
}

internal fun MainActivity.cookingMode(r: Recipe) {
    if (r.steps.isEmpty()) { toast("Questa ricetta non ha passaggi"); return }
    var step = 0
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF111317.toInt()); setPadding(dp(22), dp(20), dp(22), dp(20))
    }
    val counter = TextView(this).apply { textSize = 16f; setTextColor(0xFF9AA0A8.toInt()) }
    val stepText = TextView(this).apply { textSize = 28f; setTextColor(android.graphics.Color.WHITE); setLineSpacing(0f, 1.25f) }
    val scroll = android.widget.ScrollView(this).apply { layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f); addView(stepText) }
    fun show() {
        counter.text = "Passaggio ${step + 1} di ${r.steps.size} · ${r.title}"
        stepText.text = r.steps[step]
        speak(r.steps[step], 1f)
    }
    val nav = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    fun big(label: String, on: () -> Unit) = nav.addView(com.google.android.material.button.MaterialButton(this).apply {
        text = label; textSize = 20f; isAllCaps = false
        layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { on() }
    })
    big("‹ Indietro") { if (step > 0) { step--; show() } }
    big("🔊") { speak(r.steps[step], 1f) }
    big("Avanti ›") { if (step < r.steps.lastIndex) { step++; show() } else { tts?.stop(); dialog.dismiss(); toast("Buon appetito! 🍽") } }
    root.addView(counter); root.addView(scroll); root.addView(nav)
    dialog.setContentView(root)
    dialog.window?.apply {
        setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    dialog.setOnDismissListener { tts?.stop() }
    dialog.show()
    show()
}

// ======================= Background alerts =======================

internal fun MainActivity.showAlertsPage() {
    val p = getSharedPreferences("wl_alerts", MODE_PRIVATE)
    openPage("Avvisi", "Controlla ora", { _ -> AlertsWorker.runNow(this); toast("Controllo in corso…") }) { _, body, _ ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            fun sw(label: String, sub: String, key: String) = body.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                text = "$label\n$sub"; textSize = 15f; setTextColor(palette.text); isChecked = p.getBoolean(key, true)
                setPadding(dp(10), dp(10), dp(6), dp(10))
                setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply(); AlertsWorker.schedule(this@showAlertsPage) }
            })
            sw("Avvisi attivi", "Controllo ogni 2 ore circa, con internet", "enabled")
            sw("🎧 Nuovi episodi", "Dei podcast che segui (${audio.subscriptions().size})", "podcasts")
            sw("▶ Nuovi video", "Dei canali YouTube che segui (${ytChannels().size})", "youtube")
            sectionLabel(body, "📰 NOTIZIE SU…")
            val words = p.getStringSet("keywords", emptySet()).orEmpty().sorted()
            if (words.isEmpty()) body.addView(TextView(this).apply {
                text = "Nessun argomento. Es. «sciopero treni», «nome del tuo paese», «bonus»."; textSize = 14f; setTextColor(palette.textMuted); setPadding(dp(10), dp(4), 0, dp(4))
            })
            words.forEach { w ->
                listRow(body, w, "Notifica quando escono notizie", onClick = {}, onRemove = {
                    p.edit().putStringSet("keywords", words.toSet() - w).apply(); AlertsWorker.schedule(this); render()
                })
            }
            body.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "➕ Aggiungi argomento"; isAllCaps = false
                setOnClickListener {
                    val et = TextInputEditText(this@showAlertsPage).apply { hint = "Argomento o parola"; setSingleLine() }
                    val box = android.widget.FrameLayout(this@showAlertsPage).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
                    MaterialAlertDialogBuilder(this@showAlertsPage).setTitle("Avvisami di notizie su…").setView(box)
                        .setPositiveButton("Aggiungi") { _, _ ->
                            val w = et.text?.toString()?.trim().orEmpty()
                            if (w.isNotEmpty()) {
                                p.edit().putStringSet("keywords", p.getStringSet("keywords", emptySet()).orEmpty() + w).apply()
                                ensureNotificationPermission(); AlertsWorker.schedule(this@showAlertsPage); render()
                            }
                        }.setNegativeButton("Annulla", null).show()
                }
            })
            val last = p.getLong("lastRun", 0L)
            body.addView(TextView(this).apply {
                text = (if (last > 0) "Ultimo controllo: ${NewsAdapter.ago(last)}. " else "") +
                    "Android decide l'orario preciso per risparmiare batteria: un avviso può arrivare anche un'ora dopo."
                textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(10), dp(16), dp(10), 0)
            })
        }
        render()
    }
    ensureNotificationPermission()
    AlertsWorker.schedule(this)
}

internal fun MainActivity.goHome() {
    val addr = carPrefs.getString("home", null)
    if (addr.isNullOrBlank()) { setHomeAddress { goHome() }; return }
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(addr))).setPackage("com.google.android.apps.maps")
    try { startActivity(app) } catch (_: Exception) { showTab(TAB_BROWSER); openInBrowser("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(addr)) }
}

internal fun MainActivity.setHomeAddress(then: (() -> Unit)? = null) {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply { hint = "Via, numero, città"; setSingleLine(); setText(carPrefs.getString("home", "")) }
    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
    MaterialAlertDialogBuilder(this).setTitle("🏠 Indirizzo di casa").setView(box)
        .setPositiveButton("Salva") { _, _ ->
            val a = et.text?.toString()?.trim().orEmpty()
            if (a.isNotEmpty()) { carPrefs.edit().putString("home", a).apply(); then?.invoke() }
        }.setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.showCarMode() {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0D10.toInt()); setPadding(dp(14), dp(18), dp(14), dp(14))
    }
    val head = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    val clock = TextView(this).apply { textSize = 34f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
    head.addView(clock)
    head.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "Esci"; textSize = 18f; isAllCaps = false; setTextColor(android.graphics.Color.WHITE); setOnClickListener { dialog.dismiss() }
    })
    root.addView(head)
    val now = TextView(this).apply { textSize = 18f; setTextColor(0xFFB8C0CC.toInt()); maxLines = 2; setPadding(dp(4), dp(8), dp(4), dp(10)) }
    root.addView(now)
    val grid = android.widget.GridLayout(this).apply { columnCount = 2; layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f) }
    fun tile(emoji: String, label: String, color: Int, onClick: () -> Unit) = grid.addView(android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 24 * d; setColor(color) }
        layoutParams = android.widget.GridLayout.LayoutParams().apply {
            width = 0; height = 0
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
        addView(TextView(this@showCarMode).apply { text = emoji; textSize = 40f; gravity = android.view.Gravity.CENTER })
        addView(TextView(this@showCarMode).apply { text = label; textSize = 19f; setTextColor(android.graphics.Color.WHITE); gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        setOnClickListener { onClick() }
    })
    val dark = 0xFF1C2230.toInt()
    tile("🎤", "Comando vocale", accent) { requestMicAndListen() }
    tile("⏯", "Play / Pausa", dark) {
        val c = controller
        if (c?.currentMediaItem != null) { if (c.isPlaying) c.pause() else c.play() }
        else audio.favoriteStations().firstOrNull()?.let { playRadio(it) } ?: toast("Scegli una radio preferita in Ascolta")
    }
    tile("📻", "Radio preferita", dark) {
        val favs = audio.favoriteStations()
        if (favs.isEmpty()) toast("Aggiungi una radio preferita (⭐) in Ascolta → Radio")
        else MaterialAlertDialogBuilder(this).setItems(favs.map { it.name }.toTypedArray()) { _, w -> playRadio(favs[w]) }.show()
    }
    tile("🎧", "Riprendi podcast", dark) {
        val eps = if (episodes.isEmpty()) audio.cachedEpisodes() else episodes
        val e = eps.filter { AudioStore.position(this, it.id) > 0 && !AudioStore.isFinished(this, it.id) }.maxByOrNull { it.time }
            ?: eps.firstOrNull { !AudioStore.isFinished(this, it.id) }
        if (e != null) playEpisode(e) else toast("Nessun episodio: segui un podcast in Ascolta")
    }
    tile("🏠", "Portami a casa", dark) { goHome() }
    tile("🚗", "Ho parcheggiato", dark) { parkHere() }
    root.addView(grid)
    val tick = object : Runnable {
        override fun run() {
            clock.text = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN).format(java.util.Date())
            val md = controller?.currentMediaItem?.mediaMetadata
            now.text = if (md != null) (if (controller?.isPlaying == true) "▶ " else "⏸ ") + (md.title ?: "") + (md.artist?.let { " · $it" } ?: "") else "Niente in riproduzione"
            uiHandler.postDelayed(this, 1000)
        }
    }
    dialog.setContentView(root)
    dialog.window?.apply {
        setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusBarColor = 0xFF0B0D10.toInt(); navigationBarColor = 0xFF0B0D10.toInt()
    }
    dialog.setOnDismissListener { uiHandler.removeCallbacks(tick) }
    dialog.show()
    uiHandler.post(tick)
}

// ======================= Share to Web Launcher =======================

internal fun MainActivity.handleShare(intent: Intent): Boolean {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
    val type = intent.type.orEmpty()
    if (type.startsWith("text/")) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        if (text.isEmpty()) return true
        val link = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')')
        if (link != null) {
            MaterialAlertDialogBuilder(this).setTitle(subject.ifBlank { hostOf(link) ?: "Link" })
                .setItems(arrayOf("🌐 Apri qui", "🔖 Leggi dopo", "📝 Salva come nota", "✨ Riassumi con AI")) { _, w ->
                    when (w) {
                        0 -> { if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false); openInBrowser(link) }
                        1 -> {
                            toast("Salvo l'articolo…")
                            Thread {
                                val body = runCatching { news.articleText(link) }.getOrDefault("")
                                runOnUiThread { saveForLater(ReaderDoc(subject.ifBlank { text.substringBefore(link).trim().ifBlank { hostOf(link) ?: link } }, hostOf(link) ?: "", link, null, body.ifBlank { text })) }
                            }.start()
                        }
                        2 -> { notes.save(notes.load() + Note(UUID.randomUUID().toString(), listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n"), System.currentTimeMillis())); toast("📝 Nota salvata") }
                        3 -> { showTab(TAB_BROWSER); openInBrowser(link); uiHandler.postDelayed({ summarizePage() }, 4000) }
                    }
                }.show()
        } else {
            MaterialAlertDialogBuilder(this).setTitle(text.take(60))
                .setItems(arrayOf("📝 Salva come nota", "⏰ Crea un promemoria", "🛒 Aggiungi alla lista della spesa", "✨ Chiedi all'AI", "🔐 Salva nella cassaforte")) { _, w ->
                    when (w) {
                        0 -> { notes.save(notes.load() + Note(UUID.randomUUID().toString(), listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n"), System.currentTimeMillis())); toast("📝 Nota salvata") }
                        1 -> createReminderFromText(text)
                        2 -> addItemsToShoppingList(splitItems(text))
                        3 -> if (aiReady()) showAiSheet("Testo condiviso", "Testo:\n$text\n\n", null, hint = "Cosa vuoi sapere? Es. riassumi, rispondi, traduci")
                        4 -> authenticate("Cassaforte", strict = true) { saveVault(subject.ifBlank { text.take(30) }, "text", text.toByteArray(Charsets.UTF_8)) }
                    }
                }.show()
        }
        return true
    }
    val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else listOfNotNull(@Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    if (uris.isEmpty()) return true
    if (type.contains("pdf")) {
        MaterialAlertDialogBuilder(this).setTitle(displayName(uris[0]))
            .setItems(arrayOf("📄 Apri", "🎫 Salva nei biglietti e documenti")) { _, w ->
                if (w == 0) openPdfFromUri(uris[0])
                else io.execute {
                    val bytes = runCatching { contentResolver.openInputStream(uris[0])?.use { it.readBytes() } }.getOrNull()
                    runOnUiThread { if (bytes != null) askWalletTitle(displayName(uris[0]).substringBeforeLast('.')) { t -> addToWallet(t, "pdf", null, bytes, "pdf") } }
                }
            }.show()
        return true
    }
    // images: copy them where the app (and the AI bridge) can read them
    io.execute {
        val files = uris.take(10).mapIndexedNotNull { i, u ->
            runCatching {
                val bmp = contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                val sc = minOf(1f, 1800f / maxOf(bmp.width, bmp.height))
                val small = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                java.io.File(scanDir, "share_${System.currentTimeMillis()}_$i.jpg").also { f -> f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            }.getOrNull()
        }
        runOnUiThread {
            if (files.isEmpty()) { toast("Immagine non leggibile"); return@runOnUiThread }
            MaterialAlertDialogBuilder(this).setTitle(if (files.size == 1) "Foto" else "${files.size} foto")
                .setItems(arrayOf("🎫 Biglietti e documenti", "🔐 Cassaforte", "🧾 È uno scontrino o una bolletta", "📆 Ha una scadenza (l'AI legge la data)", "✨ Chiedi all'AI")) { _, w ->
                    when (w) {
                        0 -> askWalletTitle("Documento") { t -> files.forEachIndexed { i, f -> addToWallet(if (files.size > 1) "$t ${i + 1}" else t, "image", f, ext = "jpg") } }
                        1 -> authenticate("Cassaforte", strict = true) { askVaultTitle { t -> files.forEachIndexed { i, f -> saveVault(if (files.size > 1) "$t ${i + 1}" else t, "photo", f.readBytes()) } } }
                        2 -> readReceipt(files, null)
                        3 -> deadlineFromImage(files.first())
                        4 -> if (aiReady()) showAiSheet("Foto", null, "Descrivi cosa c'è nell'immagine e, se c'è del testo, riportalo.", false,
                            firstAttachments = files.take(4).map { Triple("image", "image/jpeg", "/files/scan/" + it.name) })
                    }
                }.show()
        }
    }
    return true
}

internal fun MainActivity.lockEnabled() = lockPrefs.getBoolean("enabled", false)

internal fun MainActivity.showLockOverlay() {
    if (lockOverlay != null) return
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    val v = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        setBackgroundColor(palette.bg); isClickable = true; isFocusable = true; elevation = 100f
        addView(TextView(this@showLockOverlay).apply { text = "🔒"; textSize = 56f; gravity = android.view.Gravity.CENTER })
        addView(TextView(this@showLockOverlay).apply {
            text = "Web Launcher è bloccato"; textSize = 18f; setTextColor(palette.text); gravity = android.view.Gravity.CENTER
            setPadding(0, (12 * d).toInt(), 0, (20 * d).toInt())
        })
        addView(com.google.android.material.button.MaterialButton(this@showLockOverlay).apply {
            text = "Sblocca"; isAllCaps = false; setOnClickListener { unlockApp() }
        })
    }
    (findViewById<android.view.ViewGroup>(android.R.id.content)).addView(v, android.view.ViewGroup.LayoutParams(-1, -1))
    lockOverlay = v
}

internal fun MainActivity.unlockApp() {
    authenticate("Sblocca Web Launcher", strict = false) {
        lockOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        lockOverlay = null
    }
}

internal fun MainActivity.toggleAppLock() {
    if (lockEnabled()) {
        authenticate("Disattiva il blocco", strict = false) { lockPrefs.edit().putBoolean("enabled", false).apply(); renderSettings(); toast("Blocco disattivato") }
    } else {
        authenticate("Attiva il blocco dell'app", strict = true) { lockPrefs.edit().putBoolean("enabled", true).apply(); renderSettings(); toast("🔒 L'app chiederà l'impronta") }
    }
}

internal fun MainActivity.showFullBackupMenu() {
    val last = syncPrefs.getLong("fullLast", 0L)
    val auto = syncPrefs.getString("fullUri", null) != null
    MaterialAlertDialogBuilder(this).setTitle("📦 Backup completo")
        .setMessage("Tutto in un file: app, note, liste, libri, registrazioni, biglietti, garanzie, spese, ricette e impostazioni (la cassaforte no, per sicurezza)." +
            (if (last > 0) "\n\nUltimo: ${NewsAdapter.ago(last)}" else "") + if (auto) "\nAutomatico ogni notte: attivo" else "")
        .setPositiveButton(if (auto) "Salva ora" else "Salva su Drive o file") { _, _ ->
            if (auto) {
                io.execute {
                    val ok = runCatching {
                        val u = Uri.parse(syncPrefs.getString("fullUri", null))
                        (contentResolver.openOutputStream(u, "wt") ?: contentResolver.openOutputStream(u))!!.use { FullBackup.write(this, it) }
                    }.isSuccess
                    if (ok) syncPrefs.edit().putLong("fullLast", System.currentTimeMillis()).apply()
                    runOnUiThread { toast(if (ok) "✓ Backup completo aggiornato" else "File non raggiungibile: scegline un altro") }
                }
            } else {
                MaterialAlertDialogBuilder(this).setMessage("Vuoi che si aggiorni da solo ogni notte?")
                    .setPositiveButton("Sì, ogni notte") { _, _ -> pendingFullAuto = true; fullBackupCreate.launch("web-launcher-completo.zip") }
                    .setNegativeButton("Solo adesso") { _, _ -> pendingFullAuto = false; fullBackupCreate.launch("web-launcher-completo.zip") }.show()
            }
        }
        .setNeutralButton("Ripristina") { _, _ -> fullBackupRestore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        .setNegativeButton(if (auto) "Spegni automatico" else "Chiudi") { _, _ ->
            if (auto) { syncPrefs.edit().remove("fullUri").apply(); FullBackup.schedule(this); toast("Backup automatico spento") }
        }.show()
}

// ======================= Morning brief UI =======================

internal fun MainActivity.showBrief(text: String?) {
    val t = text ?: Brief.lastText(this) ?: run { toast("Nessun buongiorno ancora"); return }
    MaterialAlertDialogBuilder(this).setTitle("☀️ Buongiorno").setMessage(t)
        .setPositiveButton("🔊 Ascolta") { _, _ -> speak(t.replace("•", ","), 1f) }
        .setNegativeButton("Chiudi") { _, _ -> tts?.stop() }
        .setOnCancelListener { tts?.stop() }.show()
    speak(t.replace("•", ","), 1f)
}

internal fun MainActivity.briefSettings() {
    val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
        .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
        .setHour(Brief.hour(this)).setMinute(Brief.minute(this)).setTitleText("Ora del buongiorno").build()
    picker.addOnPositiveButtonClickListener {
        ensureNotificationPermission()
        Brief.set(this, true, picker.hour, picker.minute)
        toast("☀️ Buongiorno ogni giorno alle %02d:%02d".format(picker.hour, picker.minute))
        if (currentTab == TAB_SETTINGS) renderSettings()
    }
    picker.show(supportFragmentManager, "brief")
}

internal fun MainActivity.previewBrief() {
    toast("Preparo il riepilogo…")
    io.execute {
        val t = runCatching { Brief.compose(this) }.getOrDefault("Buongiorno!")
        runOnUiThread { showBrief(t) }
    }
}

internal fun MainActivity.showStepsPage() {
    if (!Steps.available(this)) { toast("Questo telefono non ha il contapassi"); return }
    val granted = android.os.Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    if (!granted) stepsPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) else { Steps.schedule(this); io.execute { Steps.sampleNow(this); runOnUiThread { stepsRefresh?.invoke() } } }
    openPage("Passi", "Obiettivo", { _ ->
        val vals = intArrayOf(4000, 6000, 8000, 10000, 12000)
        MaterialAlertDialogBuilder(this).setTitle("Obiettivo giornaliero")
            .setSingleChoiceItems(vals.map { String.format(Locale.ITALIAN, "%,d passi", it) }.toTypedArray(), vals.indexOf(Steps.goal(this)).coerceAtLeast(0)) { dl, w ->
                Steps.setGoal(this, vals[w]); dl.dismiss(); stepsRefresh?.invoke()
            }.show()
    }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val today = Steps.today(this); val goal = Steps.goal(this)
            body.addView(TextView(this).apply {
                text = String.format(Locale.ITALIAN, "%,d", today); textSize = 54f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER; setTextColor(palette.text); setPadding(0, dp(12), 0, 0)
            })
            body.addView(TextView(this).apply {
                text = "passi oggi · obiettivo ${String.format(Locale.ITALIAN, "%,d", goal)}" + if (today >= goal) " ✓ 🎉" else ""
                textSize = 14f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
            })
            body.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = goal; progress = today.coerceAtMost(goal); progressTintList = android.content.res.ColorStateList.valueOf(accent)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(14)).apply { setMargins(dp(24), dp(14), dp(24), dp(6)) }
            })
            body.addView(TextView(this).apply {
                text = "≈ ${String.format(Locale.ITALIAN, "%.1f", today * 0.00075)} km"; textSize = 13f
                gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
            })
            sectionLabel(body, "ULTIMI 7 GIORNI")
            val week = (6 downTo 0).map { back ->
                java.text.SimpleDateFormat("EEE", Locale.ITALIAN).format(java.util.Date(System.currentTimeMillis() - back * 86_400_000L)).take(3) to Steps.day(this, back)
            }
            val maxV = maxOf(week.maxOf { it.second }, goal)
            val chart = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.BOTTOM; setPadding(dp(10), dp(8), dp(10), 0)
            }
            week.forEachIndexed { i, (name, v) ->
                chart.addView(android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(150), 1f)
                    addView(TextView(this@showStepsPage).apply { text = if (v >= 1000) "${v / 1000}k" else v.toString(); textSize = 10f; setTextColor(palette.textMuted) })
                    addView(View(this@showStepsPage).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 6 * d; setColor(if (v >= goal) 0xFF2FBF71.toInt() else if (i == 6) accent else androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x77))
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), (dp(110) * v / maxV).coerceAtLeast(dp(2)))
                    })
                    addView(TextView(this@showStepsPage).apply { text = name; textSize = 11f; setTextColor(palette.textMuted); setPadding(0, dp(4), 0, 0) })
                })
            }
            body.addView(chart)
            body.addView(TextView(this).apply {
                text = "Usa il contapassi del telefono: niente account e niente app. I passi si aggiornano circa ogni ora e quando apri questa pagina."
                textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(10), dp(16), dp(10), 0)
            })
        }
        render()
        stepsRefresh = { render() }
        dialog.setOnDismissListener { stepsRefresh = null }
    }
}

internal fun MainActivity.addMeasureDialog(type: String, onSaved: () -> Unit) {
    val (label, unit, two) = Health.TYPES[type] ?: return
    val d = resources.displayMetrics.density
    fun num(h: String) = TextInputEditText(this).apply {
        hint = h; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    val a = num(if (two) "Massima ($unit)" else "Valore ($unit)")
    val b = num("Minima ($unit)")
    val note = TextInputEditText(this).apply { hint = "Nota (facoltativa)"; setSingleLine() }
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
        addView(a); if (two) addView(b); addView(note)
    }
    MaterialAlertDialogBuilder(this).setTitle(label).setView(box)
        .setPositiveButton("Salva") { _, _ ->
            val v1 = Calc.num(a.text?.toString().orEmpty())
            val v2 = if (two) Calc.num(b.text?.toString().orEmpty()) else null
            if (v1 == null || (two && v2 == null)) { toast("Valore non valido"); return@setPositiveButton }
            Health.save(this, Health.load(this) + Measure(UUID.randomUUID().toString(), type, v1, v2, System.currentTimeMillis(), note.text?.toString()?.trim().orEmpty()))
            onSaved()
        }.setNegativeButton("Annulla", null).show()
    a.requestFocus()
}

internal fun MainActivity.showHealthPage() {
    openPage("Salute", "Aggiungi", { _ -> addMeasureDialog(healthType) { healthRefresh?.invoke() } }) { root, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tabs = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, 1)
        fun render() {
            tabs.removeAllViews()
            Health.TYPES.forEach { (k, v) ->
                val active = k == healthType
                tabs.addView(TextView(this).apply {
                    text = v.first; textSize = 13f; setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 16 * d; setColor(if (active) accent else palette.card); setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    setOnClickListener { healthType = k; render() }
                })
            }
            body.removeAllViews()
            val (label, unit, two) = Health.TYPES[healthType]!!
            val list = Health.load(this).filter { it.type == healthType }.sortedBy { it.time }
            if (list.isEmpty()) { emptyLabel(body, "Nessuna misurazione di ${label.substringAfter(' ').lowercase()}.\nTocca «Aggiungi»."); }
            else {
                val last = list.takeLast(30)
                body.addView(TextView(this).apply {
                    text = Health.fmt(list.last()); textSize = 34f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER; setTextColor(palette.text); setPadding(0, dp(6), 0, 0)
                })
                body.addView(TextView(this).apply {
                    text = "ultima · " + NewsAdapter.ago(list.last().time) + if (list.size > 1) " · media ${String.format(Locale.ITALIAN, "%.1f", last.map { it.v1 }.average())}" +
                        (if (two) "/" + String.format(Locale.ITALIAN, "%.1f", last.mapNotNull { it.v2 }.average()) else "") else ""
                    textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
                })
                if (last.size >= 2) body.addView(LineChartView(this).apply {
                    series1 = last.map { it.v1 }; series2 = if (two) last.mapNotNull { it.v2 } else null
                    color1 = if (two) 0xFFE53935.toInt() else accent; color2 = accent; textColor = palette.textMuted
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(180)).apply { setMargins(dp(6), dp(12), dp(6), dp(4)) }
                })
                sectionLabel(body, "MISURAZIONI")
                val f = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
                list.reversed().take(60).forEach { m ->
                    listRow(body, Health.fmt(m), f.format(java.util.Date(m.time)) + if (m.note.isNotBlank()) " · ${m.note}" else "",
                        onClick = {}, onRemove = { Health.save(this, Health.load(this).filter { it.id != m.id }); render() })
                }
            }
            val btns = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(dp(6), dp(14), dp(6), 0) }
            btns.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "⏰ Promemoria"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                setOnClickListener {
                    val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                        .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H).setHour(8).setMinute(0)
                        .setTitleText("Ogni giorno alle…").build()
                    picker.addOnPositiveButtonClickListener {
                        val at = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, picker.hour); set(java.util.Calendar.MINUTE, picker.minute); set(java.util.Calendar.SECOND, 0) }.timeInMillis
                        confirmReminder("Misurare: ${label.substringAfter(' ').lowercase()}", Reminders.next(at, "day"), "day")
                    }
                    picker.show(supportFragmentManager, "hr")
                }
            })
            btns.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📤 Per il medico"; isAllCaps = false; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    val f = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN)
                    val report = Health.TYPES.entries.joinToString("\n\n") { (k, v) ->
                        val ms = Health.load(this@showHealthPage).filter { it.type == k }.sortedBy { it.time }.takeLast(60)
                        if (ms.isEmpty()) "" else v.first.substringAfter(' ') + ":\n" + ms.joinToString("\n") { f.format(java.util.Date(it.time)) + "  " + Health.fmt(it) + if (it.note.isNotBlank()) " (${it.note})" else "" }
                    }.trim()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report); putExtra(Intent.EXTRA_SUBJECT, "Misurazioni") }, "Condividi"))
                }
            })
            body.addView(btns)
        }
        render()
        healthRefresh = { render() }
        dialog.setOnDismissListener { healthRefresh = null }
    }
}
