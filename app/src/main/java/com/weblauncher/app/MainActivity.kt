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
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var repo: AppRepository
    private lateinit var adapter: LauncherAdapter
    private lateinit var searchInput: EditText
    private lateinit var webSearchHint: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var homePanel: View
    private lateinit var browserPanel: View
    private lateinit var webView: WebView
    private lateinit var webTitle: TextView
    private lateinit var webProgress: ProgressBar
    private lateinit var iconHome: ImageView
    private lateinit var labelHome: TextView
    private lateinit var iconBrowser: ImageView
    private lateinit var labelBrowser: TextView

    private var apps: MutableList<AppItem> = mutableListOf()
    private var categories: MutableList<String> = mutableListOf()
    private var currentTab = TAB_HOME
    private var browserHasPage = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (spoken.isNotEmpty()) handleVoiceText(spoken)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition() else toast(getString(R.string.mic_denied))
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeBackupToUri(uri)
    }

    private val autoBackupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { }
        repo.setAutoBackupUri(uri.toString())
        writeBackupToUri(uri, quiet = true)
        toast("Backup automatico attivo")
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readBackupFromUri(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = AppRepository(this)
        apps = repo.loadApps()
        categories = repo.loadCategories()

        homePanel = findViewById(R.id.homePanel)
        browserPanel = findViewById(R.id.browserPanel)
        searchInput = findViewById(R.id.searchInput)
        webSearchHint = findViewById(R.id.webSearchHint)
        recycler = findViewById(R.id.recycler)
        webView = findViewById(R.id.webView)
        webTitle = findViewById(R.id.webTitle)
        webProgress = findViewById(R.id.webProgress)
        iconHome = findViewById(R.id.iconHome)
        labelHome = findViewById(R.id.labelHome)
        iconBrowser = findViewById(R.id.iconBrowser)
        labelBrowser = findViewById(R.id.labelBrowser)

        setupWebView()
        setupLauncher()
        setupNav()
        setupBack()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateWebHint(s?.toString().orEmpty())
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearchFromBox(); true } else false
        }
        webSearchHint.setOnClickListener {
            val q = searchInput.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) openInBrowser(searchUrl(q))
        }

        findViewById<View>(R.id.btnMic).setOnClickListener { requestMicAndListen() }
        findViewById<View>(R.id.btnWebBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.btnWebForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btnWebRefresh).setOnClickListener { webView.reload() }
        findViewById<View>(R.id.btnWebClose).setOnClickListener {
            webView.loadUrl("about:blank")
            browserHasPage = false
            webTitle.text = ""
            showTab(TAB_HOME)
        }
        findViewById<View>(R.id.btnWebAdd).setOnClickListener { offerAddCurrentPage() }
        findViewById<View>(R.id.btnWebExternal).setOnClickListener {
            val url = webView.url
            if (!url.isNullOrBlank() && url != "about:blank") {
                try { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
                catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        showTab(TAB_HOME)
    }

    private fun setupLauncher() {
        adapter = LauncherAdapter(
            onClick = { openInBrowser(normalizeUrl(it.url)) },
            onMenu = { item -> showAppMenu(item) },
            onReorder = { fromId, toId -> applyReorder(fromId, toId) }
        )
        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = adapter.spanSizeLookup()
        recycler.layoutManager = glm
        recycler.adapter = adapter
        adapter.itemTouchHelper().attachToRecyclerView(recycler)
        refreshList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        s.saveFormData = true
        s.userAgentString = s.userAgentString + " WebLauncher/4.3"
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true } catch (_: Exception) { true }
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                webProgress.isVisible = true; webProgress.progress = 0
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                webProgress.isVisible = false
                webTitle.text = view?.title ?: url.orEmpty()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                webProgress.progress = newProgress
                webProgress.isVisible = newProgress < 100
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                webTitle.text = title.orEmpty()
            }
        }
    }

    private fun setupNav() {
        findViewById<View>(R.id.navHome).setOnClickListener { showTab(TAB_HOME) }
        findViewById<View>(R.id.navBrowser).setOnClickListener {
            if (browserHasPage) showTab(TAB_BROWSER) else toast("Nessuna pagina aperta — tocca un'app")
        }
        findViewById<View>(R.id.navSettings).setOnClickListener { showSettings() }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentTab == TAB_BROWSER && webView.canGoBack() -> webView.goBack()
                    currentTab == TAB_BROWSER -> showTab(TAB_HOME)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        homePanel.isVisible = tab == TAB_HOME
        browserPanel.isVisible = tab == TAB_BROWSER
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        iconHome.setColorFilter(if (tab == TAB_HOME) accent else muted)
        labelHome.setTextColor(if (tab == TAB_HOME) accent else muted)
        iconBrowser.setColorFilter(if (tab == TAB_BROWSER) accent else muted)
        labelBrowser.setTextColor(if (tab == TAB_BROWSER) accent else muted)
    }

    private fun openInBrowser(url: String) {
        browserHasPage = true
        showTab(TAB_BROWSER)
        webView.loadUrl(url)
        webTitle.text = url
    }

    private fun applyReorder(fromId: String, toId: String) {
        val fromIdx = apps.indexOfFirst { it.id == fromId }
        val toIdx = apps.indexOfFirst { it.id == toId }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val item = apps.removeAt(fromIdx)
        apps.add(toIdx, item)
        persistApps()
    }

    private fun refreshList() {
        adapter.submit(apps, categories, searchInput.text?.toString().orEmpty())
    }

    private fun updateWebHint(q: String) {
        val text = q.trim()
        if (text.isEmpty()) webSearchHint.visibility = View.GONE
        else {
            webSearchHint.visibility = View.VISIBLE
            webSearchHint.text = "Cerca con ${repo.getSearchEngine().label}: $text"
        }
    }

    private fun runSearchFromBox() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        if (q.contains('.') && !q.contains(' ')) openInBrowser(normalizeUrl(q))
        else openInBrowser(searchUrl(q))
        searchInput.setText("")
        refreshList()
    }

    private fun handleVoiceText(text: String) {
        toast("Hai detto: $text")
        val match = apps.firstOrNull {
            it.name.contains(text, ignoreCase = true) || text.contains(it.name, ignoreCase = true)
        }
        when {
            match != null -> openInBrowser(normalizeUrl(match.url))
            text.contains('.') && !text.contains(' ') -> openInBrowser(normalizeUrl(text))
            else -> openInBrowser(searchUrl(text))
        }
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startVoiceRecognition()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceRecognition() {
        if (!isSpeechAvailable()) { toast(getString(R.string.no_speech)); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { speechLauncher.launch(intent) } catch (_: ActivityNotFoundException) { toast(getString(R.string.no_speech)) }
    }

    private fun isSpeechAvailable(): Boolean =
        packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

    private fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun searchUrl(query: String): String = repo.getSearchEngine().buildUrl(query)

    private fun showAppMenu(item: AppItem) {
        val options = arrayOf(
            if (item.pinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            "Modifica / Sposta categoria", "Elimina"
        )
        MaterialAlertDialogBuilder(this).setTitle(item.name).setItems(options) { _, which ->
            when (which) {
                0 -> { item.pinned = !item.pinned; persistApps(); refreshList()
                    toast(if (item.pinned) "Aggiunta ai preferiti" else "Rimossa dai preferiti") }
                1 -> showAddEditDialog(item)
                2 -> MaterialAlertDialogBuilder(this).setMessage("Eliminare \"${item.name}\"?")
                    .setPositiveButton("Elimina") { _, _ ->
                        apps.removeAll { it.id == item.id }; persistApps(); refreshList(); toast("Eliminata")
                    }.setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    private fun showAddEditDialog(existing: AppItem?) {
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

    private fun showSettings() {
        val engine = repo.getSearchEngine()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val scroll = android.widget.ScrollView(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun section(title: String) {
            root.addView(TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(dp(4), dp(14), dp(4), dp(6))
            })
        }
        fun row(label: String, sub: String? = null, onClick: () -> Unit) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card))
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@MainActivity, R.color.stroke)
                cardElevation = 0f
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setOnClickListener { onClick() }
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            box.addView(TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 15f
            })
            if (!sub.isNullOrBlank()) {
                box.addView(TextView(this).apply {
                    text = sub
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                })
            }
            card.addView(box)
            root.addView(card)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Impostazioni")
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .create()
        section("APP")
        row("Aggiungi web app", "Nome, URL e categoria") { dialog.dismiss(); showAddEditDialog(null) }
        row("Motore di ricerca", engine.label) { dialog.dismiss(); showSearchEnginePicker() }
        section("PRIVACY")
        row("Password e compilazione", "Autofill di Android") { dialog.dismiss(); showPasswordInfo() }
        row("Cancella cronologia") { dialog.dismiss(); confirmClear("Cancellare la cronologia del browser?") { webView.clearHistory(); toast("Cronologia cancellata") } }
        row("Cancella cookie") { dialog.dismiss(); confirmClear("Cancellare tutti i cookie?") { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); toast("Cookie cancellati") } }
        row("Cancella cache") { dialog.dismiss(); confirmClear("Cancellare la cache?") { webView.clearCache(true); toast("Cache cancellata") } }
        row("Cancella tutti i dati", "Cronologia, cookie, cache") { dialog.dismiss(); confirmClear("Cancellare tutti i dati di navigazione?") { clearAllBrowsingData(); toast("Dati cancellati") } }
        section("BACKUP")
        val autoOn = repo.getAutoBackupUri() != null
        row(
            if (autoOn) "Backup automatico su Drive" else "Attiva backup automatico su Drive",
            if (autoOn) "Attivo — si aggiorna da solo" else "Scegli un file su Drive, una volta"
        ) {
            dialog.dismiss()
            if (autoOn) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Backup automatico")
                    .setMessage("Ogni modifica viene scritta sul file Drive che hai scelto.")
                    .setPositiveButton("Cambia file") { _, _ -> startAutoBackupSetup() }
                    .setNeutralButton("Disattiva") { _, _ ->
                        repo.setAutoBackupUri(null)
                        toast("Backup automatico disattivato")
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            } else startAutoBackupSetup()
        }
        row("Backup su file / Drive") { dialog.dismiss(); exportLauncher.launch("web-launcher-backup.json") }
        row("Ripristina da file / Drive") { dialog.dismiss(); importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        row("Condividi backup") { dialog.dismiss();
            val json = com.google.gson.Gson().toJson(mapOf("apps" to apps, "categories" to categories))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_TEXT, json)
            }, "Condividi backup"))
        }
        row("Reset app predefinite") {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this).setMessage("Ripristinare le app predefinite?")
                .setPositiveButton("Reset") { _, _ ->
                    apps = repo.resetDefaults(); categories = repo.loadCategories(); refreshList(); toast("App ripristinate")
                }.setNegativeButton("Annulla", null).show()
        }
        section("AIUTO")
        row("Microfono", "Permesso voce") {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this).setTitle("Microfono")
                .setMessage("Voce nativa Android.\nImpostazioni → App → Web Launcher → Microfono → Consentire")
                .setPositiveButton("OK", null).show()
        }
        row("Sposta le icone", "Tieni premuto e trascina") { dialog.dismiss(); toast("Tieni premuto un'icona e trascinala") }
        scroll.addView(root)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun confirmClear(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton("Cancella") { _, _ -> action() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun clearAllBrowsingData() {
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        try {
            WebViewDatabase.getInstance(this).clearFormData()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        } catch (_: Exception) { }
    }

    private fun showPasswordInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Password e login")
            .setMessage(
                "Web Launcher usa l'Autofill di Android.\n\n" +
                "• Al login di un sito, il sistema può proporre di salvare la password.\n\n" +
                "• Per gestire le password: Impostazioni Android → Autocompletamento.\n\n" +
                "• Per cancellare i dati form: Impostazioni → Cancella tutti i dati di navigazione."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Apri Autofill") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE))
                } catch (_: Exception) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    catch (_: Exception) { toast("Apri Impostazioni → Autocompletamento") }
                }
            }
            .show()
    }

    private fun offerAddCurrentPage() {
        val url = webView.url?.trim().orEmpty()
        if (url.isBlank() || url == "about:blank" || !url.startsWith("http")) {
            toast("Nessuna pagina valida da aggiungere")
            return
        }
        val title = webView.title?.trim().orEmpty().ifBlank {
            try { Uri.parse(url).host ?: "Sito" } catch (_: Exception) { "Sito" }
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val nameEt = TextInputEditText(this).apply {
            setText(title)
            setSingleLine()
            setSelection(text?.length ?: 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
        }
        val til = TextInputLayout(this).apply {
            hint = "Nome sulla home"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(nameEt)
        }
        root.addView(til)
        root.addView(TextView(this).apply {
            text = url
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 12f
            setPadding(dp(2), dp(10), dp(2), dp(14))
            maxLines = 2
        })
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun actionBtn(label: String, filled: Boolean): com.google.android.material.button.MaterialButton {
            val b = com.google.android.material.button.MaterialButton(this)
            b.text = label
            b.isAllCaps = false
            b.cornerRadius = dp(16)
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = if (filled) dp(8) else 0
            b.layoutParams = lp
            if (!filled) b.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card))
            return b
        }
        val homeBtn = actionBtn("Home", true)
        val favBtn = actionBtn("Preferiti", false)
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

    private fun addCurrentAsApp(name: String, url: String, pinned: Boolean) {
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
        toast(if (pinned) "Aggiunta ai preferiti" else "Aggiunta alla home")
    }

    private fun persistApps() {
        repo.saveApps(apps)
        autoBackupNow()
    }

    private fun autoBackupNow() {
        val raw = repo.getAutoBackupUri() ?: return
        writeBackupToUri(Uri.parse(raw), quiet = true)
    }

    private fun startAutoBackupSetup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "web-launcher-backup.json")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        autoBackupLauncher.launch(intent)
    }

    private fun writeBackupToUri(uri: Uri, quiet: Boolean = false) {
        try {
            val json = com.google.gson.Gson().toJson(
                mapOf(
                    "apps" to apps,
                    "categories" to categories,
                    "searchEngine" to repo.getSearchEngine().name
                )
            )
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            } ?: run {
                if (!quiet) toast("Impossibile scrivere il file")
                else repo.setAutoBackupUri(null)
                return
            }
            if (!quiet) toast("Backup salvato (puoi scegliere Drive)")
        } catch (e: Exception) {
            if (quiet) repo.setAutoBackupUri(null)
            else toast("Errore backup: ${e.message}")
        }
    }

    private fun readBackupFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: run { toast("Impossibile leggere il file"); return }
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val map = com.google.gson.Gson().fromJson<Map<String, Any>>(text, type)
                ?: run { toast("File non valido"); return }
            val appsJson = com.google.gson.Gson().toJson(map["apps"])
            val catsJson = com.google.gson.Gson().toJson(map["categories"])
            val appType = object : com.google.gson.reflect.TypeToken<MutableList<AppItem>>() {}.type
            val catType = object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type
            val loadedApps = com.google.gson.Gson().fromJson<MutableList<AppItem>>(appsJson, appType)
            val loadedCats = com.google.gson.Gson().fromJson<MutableList<String>>(catsJson, catType)
            if (loadedApps.isNullOrEmpty()) { toast("Nessuna app nel backup"); return }
            MaterialAlertDialogBuilder(this)
                .setTitle("Ripristina backup")
                .setMessage("Sostituire le ${apps.size} app attuali con le ${loadedApps.size} del backup?")
                .setPositiveButton("Ripristina") { _, _ ->
                    apps = loadedApps
                    categories = loadedCats ?: repo.loadCategories()
                    persistApps()
                    repo.saveCategories(categories)
                    (map["searchEngine"] as? String)?.let { repo.setSearchEngine(SearchEngine.fromName(it)) }
                    refreshList()
                    toast("Backup ripristinato")
                }
                .setNegativeButton("Annulla", null)
                .show()
        } catch (e: Exception) {
            toast("Errore ripristino: ${e.message}")
        }
    }

    private fun showSearchEnginePicker() {
        val engines = SearchEngine.entries
        val labels = engines.map { it.label }.toTypedArray()
        val current = engines.indexOf(repo.getSearchEngine()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Motore di ricerca")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                repo.setSearchEngine(engines[which])
                toast("Motore: ${engines[which].label}")
                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_BROWSER = 1
    }
}
