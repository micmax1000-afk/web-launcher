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
import com.google.android.material.button.MaterialButton
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
            if (q.isNotEmpty()) openInBrowser("https://www.google.com/search?q=" + Uri.encode(q))
        }

        findViewById<View>(R.id.btnMic).setOnClickListener { requestMicAndListen() }
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { showAddEditDialog(null) }
        findViewById<View>(R.id.btnWebBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.btnWebForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btnWebRefresh).setOnClickListener { webView.reload() }
        findViewById<View>(R.id.btnWebClose).setOnClickListener {
            webView.loadUrl("about:blank")
            browserHasPage = false
            webTitle.text = ""
            showTab(TAB_HOME)
        }
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
            onLongClick = { item, _ -> showAppMenu(item) },
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
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString + " WebLauncher/4"

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
        findViewById<View>(R.id.navVoice).setOnClickListener { requestMicAndListen() }
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
        repo.saveApps(apps)
    }

    private fun refreshList() {
        adapter.submit(apps, categories, searchInput.text?.toString().orEmpty())
    }

    private fun updateWebHint(q: String) {
        val t = q.trim()
        if (t.isEmpty()) webSearchHint.visibility = View.GONE
        else { webSearchHint.visibility = View.VISIBLE; webSearchHint.text = "Cerca sul web: $t" }
    }

    private fun runSearchFromBox() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        if (q.contains('.') && !q.contains(' ')) openInBrowser(normalizeUrl(q))
        else openInBrowser("https://www.google.com/search?q=" + Uri.encode(q))
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
            else -> openInBrowser("https://www.google.com/search?q=" + Uri.encode(text))
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

    private fun showAppMenu(item: AppItem) {
        val options = arrayOf(
            if (item.pinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            "Modifica / Sposta categoria", "Elimina"
        )
        MaterialAlertDialogBuilder(this).setTitle(item.name).setItems(options) { _, which ->
            when (which) {
                0 -> { item.pinned = !item.pinned; repo.saveApps(apps); refreshList()
                    toast(if (item.pinned) "Aggiunta ai preferiti" else "Rimossa dai preferiti") }
                1 -> showAddEditDialog(item)
                2 -> MaterialAlertDialogBuilder(this).setMessage("Eliminare \"${item.name}\"?")
                    .setPositiveButton("Elimina") { _, _ ->
                        apps.removeAll { it.id == item.id }; repo.saveApps(apps); refreshList(); toast("Eliminata")
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
                repo.saveApps(apps); refreshList()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun showSettings() {
        val items = arrayOf("Esporta backup (JSON)", "Reset app predefinite", "Info voce / microfono",
            "Suggerimento: trascina le icone per riordinarle")
        MaterialAlertDialogBuilder(this).setTitle("Impostazioni").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val json = com.google.gson.Gson().toJson(mapOf("apps" to apps, "categories" to categories))
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"; putExtra(Intent.EXTRA_TEXT, json)
                    }, "Esporta backup"))
                }
                1 -> MaterialAlertDialogBuilder(this).setMessage("Ripristinare le app predefinite?")
                    .setPositiveButton("Reset") { _, _ ->
                        apps = repo.resetDefaults(); categories = repo.loadCategories(); refreshList(); toast("App ripristinate")
                    }.setNegativeButton("Annulla", null).show()
                2 -> MaterialAlertDialogBuilder(this).setTitle("Microfono")
                    .setMessage("Voce nativa Android.\nImpostazioni → App → Web Launcher → Microfono → Consentire")
                    .setPositiveButton("OK", null).show()
                3 -> toast("Tieni premuto e trascina un'icona per spostarla")
            }
        }.setNegativeButton("Chiudi", null).show()
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_BROWSER = 1
    }
}
