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
import androidx.core.view.WindowCompat
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
    private lateinit var recentsPanel: View
    private lateinit var recentsList: android.widget.LinearLayout
    private lateinit var webTitle: TextView
    private lateinit var webAddress: EditText
    private lateinit var tabStrip: android.widget.LinearLayout
    private var mobileUserAgent: String = ""
    private val appVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    private val webTabs = mutableListOf(WebTab())
    private var webTabIndex = 0
    private var iconTargetId: String? = null
    private lateinit var webProgress: ProgressBar
    private lateinit var iconHome: ImageView
    private lateinit var labelHome: TextView
    private lateinit var iconBrowser: ImageView
    private lateinit var labelBrowser: TextView
    private lateinit var iconSettings: ImageView
    private lateinit var labelSettings: TextView

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

    // --- Web features: file upload, permissions, fullscreen video ---
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingPermissionResult: ((Map<String, Boolean>) -> Unit)? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val sitePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cb = pendingPermissionResult
        pendingPermissionResult = null
        cb?.invoke(granted)
    }

    private fun withAndroidPermissions(perms: List<String>, onResult: (Boolean) -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onResult(true); return }
        if (pendingPermissionResult != null) { onResult(false); return }
        pendingPermissionResult = { res -> onResult(missing.all { res[it] == true }) }
        sitePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun siteName(origin: String?): String = try {
        Uri.parse(origin.orEmpty()).host?.removePrefix("www.") ?: "Il sito"
    } catch (_: Exception) { "Il sito" }

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = iconTargetId ?: return@registerForActivityResult
        val item = apps.firstOrNull { it.id == id } ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            val dir = java.io.File(filesDir, "icons").apply { mkdirs() }
            val dest = java.io.File(dir, id)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return@registerForActivityResult
            item.iconPath = dest.absolutePath
            persistApps()
            refreshList()
            toast("Icona aggiornata")
        } catch (e: Exception) {
            toast("Icona non salvata")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.applySavedTheme(this)
        setTheme(AppRepository.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // WebView must be created after the layout, then the theme restored.
        // Creating it inside XML flips light mode back to the system night palette
        // for every view inflated afterwards (cards and bottom bar).
        webView = WebView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        findViewById<android.widget.FrameLayout>(R.id.webViewHost).addView(webView)
        AppRepository.restoreUiMode(this)
        val palette = AppRepository.palette(this)
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg
        findViewById<View>(android.R.id.content).setBackgroundColor(palette.bg)
        findViewById<View>(R.id.bottomBarSurface).background =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f * resources.displayMetrics.density
                setColor(palette.elevated)
                setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), palette.stroke)
            }

        repo = AppRepository(this)
        apps = repo.loadApps()
        categories = repo.loadCategories()

        homePanel = findViewById(R.id.homePanel)
        browserPanel = findViewById(R.id.browserPanel)
        searchInput = findViewById(R.id.searchInput)
        webSearchHint = findViewById(R.id.webSearchHint)
        recycler = findViewById(R.id.recycler)
        recentsPanel = findViewById(R.id.recentsPanel)
        recentsList = findViewById(R.id.recentsList)
        webTitle = findViewById(R.id.webTitle)
        webAddress = findViewById(R.id.webAddress)
        tabStrip = findViewById(R.id.tabStrip)
        webProgress = findViewById(R.id.webProgress)
        iconHome = findViewById(R.id.iconHome)
        labelHome = findViewById(R.id.labelHome)
        iconBrowser = findViewById(R.id.iconBrowser)
        labelBrowser = findViewById(R.id.labelBrowser)
        iconSettings = findViewById(R.id.iconSettings)
        labelSettings = findViewById(R.id.labelSettings)

        styleChrome(palette)
        findViewById<View>(R.id.btnEmptyAdd).setOnClickListener { showAddEditDialog(null) }
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
        findViewById<View>(R.id.btnWebAdd).setOnClickListener { offerAddCurrentPage() }
        findViewById<View>(R.id.btnWebMenu).setOnClickListener { showBrowserMenu() }
        findViewById<View>(R.id.btnNewTab).setOnClickListener { openNewTab(privateMode = false) }
        webAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                goAddress()
                true
            } else false
        }
        renderTabStrip()
        showTab(TAB_HOME)
        openShortcut(intent)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
    }

    /** Colours from the runtime palette: WebView can flip resource colours to the system theme. */
    private fun styleChrome(palette: AppRepository.Companion.UiPalette) {
        val d = resources.displayMetrics.density
        findViewById<View>(R.id.browserToolbar).setBackgroundColor(palette.elevated)
        findViewById<View>(R.id.tabScroller).setBackgroundColor(palette.elevated)
        listOf(R.id.btnWebBack, R.id.btnWebForward, R.id.btnWebRefresh, R.id.btnWebAdd, R.id.btnWebMenu).forEach {
            findViewById<android.widget.ImageButton>(it).setColorFilter(palette.text)
        }
        webAddress.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f * d
            setColor(palette.iconTile)
            setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        webAddress.setTextColor(palette.text)
        webAddress.setHintTextColor(palette.textMuted)
        findViewById<TextView>(R.id.emptyTitle).setTextColor(palette.text)
        findViewById<TextView>(R.id.emptyText).setTextColor(palette.textMuted)
    }

    private fun setupLauncher() {
        adapter = LauncherAdapter(
            onClick = {
                noteUse(it)
                openInBrowser(normalizeUrl(it.url))
            },
            onMenu = { item -> showAppMenu(item) },
            onHeader = { name -> showCategoryMenu(name) },
            onReorder = { fromId, toId -> applyReorder(fromId, toId) }
        )
        val glm = GridLayoutManager(this, 4)
        glm.spanSizeLookup = adapter.spanSizeLookup()
        recycler.layoutManager = glm
        recycler.adapter = adapter
        adapter.attachTo(recycler)
        refreshList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        mobileUserAgent = s.userAgentString
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setGeolocationEnabled(true)
        s.allowFileAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        s.saveFormData = true
        s.userAgentString = mobileUserAgent + " WebLauncher/$appVersion"
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !repo.blockThirdPartyCookies())

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

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
                val pageUrl = url.orEmpty()
                val safeTitle = view?.title?.trim().orEmpty().takeIf { isRealPageTitle(it, pageUrl) }
                if (safeTitle != null) webTitle.text = safeTitle
                val tab = webTabs.getOrNull(webTabIndex)
                if (tab != null) {
                    if (safeTitle != null) tab.title = safeTitle
                    if (pageUrl.startsWith("http")) tab.url = pageUrl
                }
                if (!webAddress.isFocused && pageUrl.startsWith("http")) webAddress.setText(pageUrl)
                if (pageUrl.startsWith("http")) {
                    if (tab?.privateMode != true && safeTitle != null) repo.addRecent(safeTitle, pageUrl)
                    browserHasPage = true
                    updateBrowserBody()
                    renderTabStrip()
                    updateStar()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                webProgress.progress = newProgress
                webProgress.isVisible = newProgress < 100
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                val pageUrl = view?.url.orEmpty()
                val safeTitle = title?.trim().orEmpty().takeIf { isRealPageTitle(it, pageUrl) } ?: return
                webTitle.text = safeTitle
                val tab = webTabs.getOrNull(webTabIndex) ?: return
                tab.title = safeTitle
                if (!tab.privateMode && pageUrl.startsWith("http")) repo.addRecent(safeTitle, pageUrl)
                renderTabStrip()
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: android.webkit.ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val pick = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }
                    if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(Intent.createChooser(pick, "Scegli file"))
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    toast("Nessuna app per scegliere i file")
                    false
                }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                val req = request ?: return
                val wanted = req.resources.filter {
                    it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (wanted.isEmpty()) { req.deny(); return }
                val label = wanted.joinToString(" e ") {
                    if (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) "fotocamera" else "microfono"
                }
                val androidPerms = wanted.map {
                    if (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA
                    else Manifest.permission.RECORD_AUDIO
                }
                runOnUiThread {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(siteName(req.origin?.toString()))
                        .setMessage("Vuole usare $label.")
                        .setPositiveButton("Consenti") { _, _ ->
                            withAndroidPermissions(androidPerms) { ok ->
                                if (ok) req.grant(wanted.toTypedArray()) else {
                                    req.deny(); toast("Permesso negato da Android")
                                }
                            }
                        }
                        .setNegativeButton("Blocca") { _, _ -> req.deny() }
                        .setOnCancelListener { req.deny() }
                        .show()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                val cb = callback ?: return
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(siteName(origin))
                    .setMessage("Vuole conoscere la tua posizione.")
                    .setPositiveButton("Consenti") { _, _ ->
                        withAndroidPermissions(listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )) { ok ->
                            // coarse-only is still fine: the site gets an approximate position
                            val any = ok || ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            cb.invoke(origin, any, false)
                            if (!any) toast("Posizione negata da Android")
                        }
                    }
                    .setNegativeButton("Blocca") { _, _ -> cb.invoke(origin, false, false) }
                    .setOnCancelListener { cb.invoke(origin, false, false) }
                    .show()
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                (window.decorView as android.widget.FrameLayout).addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                view.setBackgroundColor(android.graphics.Color.BLACK)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as android.widget.FrameLayout).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:")) {
            toast("Questo sito crea il file dentro la pagina: non scaricabile qui")
            return
        }
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (url.startsWith("data:")) {
            saveDataUrl(url, name)
            return
        }
        val enqueue = {
            try {
                val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                    setTitle(name)
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                toast("Download avviato: $name")
            } catch (e: Exception) {
                toast("Download non riuscito")
            }
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            withAndroidPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { ok ->
                if (ok) enqueue() else toast("Serve il permesso archiviazione per scaricare")
            }
        } else enqueue()
    }

    private fun saveDataUrl(dataUrl: String, fallbackName: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) { toast("File non valido"); return }
            val meta = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            val bytes = if (meta.endsWith(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                else Uri.decode(payload).toByteArray()
            val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val name = if (ext != null && !fallbackName.contains('.')) "$fallbackName.$ext" else fallbackName
            pendingDataDownload = bytes
            dataDownloadLauncher.launch(name)
        } catch (_: Exception) {
            toast("Download non riuscito")
        }
    }

    private var pendingDataDownload: ByteArray? = null
    private val dataDownloadLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val bytes = pendingDataDownload
        pendingDataDownload = null
        if (uri == null || bytes == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            toast("File salvato")
        } catch (_: Exception) { toast("File non salvato") }
    }

    private fun setupNav() {
        findViewById<View>(R.id.navHome).setOnClickListener { showTab(TAB_HOME) }
        findViewById<View>(R.id.navBrowser).setOnClickListener { showTab(TAB_BROWSER) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showSettings() }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
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
        if (tab == TAB_BROWSER) updateBrowserBody()
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val muted = AppRepository.palette(this).textMuted
        iconHome.setColorFilter(if (tab == TAB_HOME) accent else muted)
        labelHome.setTextColor(if (tab == TAB_HOME) accent else muted)
        iconBrowser.setColorFilter(if (tab == TAB_BROWSER) accent else muted)
        labelBrowser.setTextColor(if (tab == TAB_BROWSER) accent else muted)
        iconSettings.setColorFilter(muted)
        labelSettings.setTextColor(muted)
    }

    private fun openInBrowser(url: String) {
        if (webTabs.isEmpty()) webTabs.add(WebTab())
        val tab = webTabs[webTabIndex]
        tab.url = url
        tab.state = null
        browserHasPage = true
        showTab(TAB_BROWSER)
        if (!webAddress.isFocused) webAddress.setText(url)
        applyTabChrome()
        webView.loadUrl(url)
        updateBrowserBody()
        renderTabStrip()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openShortcut(intent)
    }

    private fun openShortcut(intent: Intent?) {
        val url = intent?.getStringExtra("shortcut_url") ?: return
        if (url.isNotBlank()) openInBrowser(normalizeUrl(url))
    }

    private fun goAddress() {
        val raw = webAddress.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val url = if (looksLikeUrl(raw)) normalizeUrl(raw) else searchUrl(raw)
        openInBrowser(url)
    }

    private fun currentTab(): WebTab = webTabs[webTabIndex]

    private fun saveCurrentTab() {
        if (webTabs.isEmpty()) return
        val tab = webTabs[webTabIndex]
        val bundle = Bundle()
        webView.saveState(bundle)
        tab.state = bundle
        webView.url?.let { if (it != "about:blank") tab.url = it }
        webView.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
    }

    private fun openNewTab(privateMode: Boolean) {
        saveCurrentTab()
        webTabs.add(WebTab(privateMode = privateMode, title = if (privateMode) "Senza cronologia" else "Nuova scheda"))
        webTabIndex = webTabs.lastIndex
        browserHasPage = false
        webAddress.setText("")
        webView.loadUrl("about:blank")
        applyTabChrome()
        showTab(TAB_BROWSER)
        updateBrowserBody()
        renderTabStrip()
        if (privateMode) toast("Senza cronologia: le pagine non finiscono nei recenti. I login restano attivi.")
    }

    private fun closeCurrentTab() {
        if (webTabs.isEmpty()) return
        val closing = webTabs[webTabIndex]
        if (closing.privateMode) {
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
        }
        webTabs.removeAt(webTabIndex)
        if (webTabs.isEmpty()) webTabs.add(WebTab())
        webTabIndex = webTabIndex.coerceAtMost(webTabs.lastIndex)
        restoreTab(webTabs[webTabIndex])
        renderTabStrip()
    }

    private fun closeTabAt(index: Int) {
        if (index !in webTabs.indices) return
        if (index == webTabIndex) { closeCurrentTab(); return }
        webTabs.removeAt(index)
        if (index < webTabIndex) webTabIndex -= 1
        renderTabStrip()
    }

    private fun switchToTab(index: Int) {
        if (index == webTabIndex || index !in webTabs.indices) return
        saveCurrentTab()
        webTabIndex = index
        restoreTab(webTabs[index])
        renderTabStrip()
    }

    private fun restoreTab(tab: WebTab) {
        applyTabChrome()
        val restored = tab.state?.let { webView.restoreState(it) } != null
        if (!restored) {
            if (tab.url.isNotBlank()) webView.loadUrl(tab.url) else webView.loadUrl("about:blank")
        }
        browserHasPage = tab.url.startsWith("http")
        if (!webAddress.isFocused) webAddress.setText(if (tab.url.startsWith("http")) tab.url else "")
        showTab(TAB_BROWSER)
        updateBrowserBody()
    }

    private fun applyTabChrome() {
        val tab = webTabs.getOrNull(webTabIndex) ?: return
        val s = webView.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else mobileUserAgent + " WebLauncher/$appVersion"
        s.cacheMode = if (tab.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    private fun renderTabStrip() {
        tabStrip.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val palette = AppRepository.palette(this)
        webTabs.forEachIndexed { index, tab ->
            val active = index == webTabIndex
            val fg = if (active) accent else palette.textMuted
            val chip = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(2), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (active) androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x2A) else palette.card)
                    setStroke(dp(1), if (active) androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x66) else palette.stroke)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginEnd = dp(6) }
                setOnClickListener { switchToTab(index) }
                setOnLongClickListener {
                    closeTabAt(index)
                    true
                }
            }
            chip.addView(TextView(this).apply {
                val raw = if (tab.privateMode) "Senza cronologia" else tab.title.ifBlank { "Scheda" }
                val label = if (raw == "about:blank") "Nuova scheda" else raw
                text = if (label.length > 18) label.take(17).trimEnd() + "…" else label
                textSize = 13f
                setTextColor(fg)
                if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
            })
            chip.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_web_close)
                setColorFilter(fg)
                setPadding(dp(6), dp(6), dp(8), dp(6))
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(30), dp(30))
                contentDescription = "Chiudi scheda"
                setOnClickListener { closeTabAt(index) }
            })
            tabStrip.addView(chip)
        }
    }

    private fun showBrowserMenu() {
        val tab = currentTab()
        val options = arrayOf(
            "Nuova scheda",
            "Scheda senza cronologia",
            "Trova nella pagina",
            if (tab.desktop) "Versione mobile" else "Versione desktop",
            "Svuota recenti",
            "Chiudi scheda",
            "Apri in Chrome / altro browser",
            "Condividi link"
        )
        MaterialAlertDialogBuilder(this).setTitle("Browser").setItems(options) { _, which ->
            when (which) {
                0 -> openNewTab(false)
                1 -> openNewTab(true)
                2 -> showFindDialog()
                3 -> {
                    tab.desktop = !tab.desktop
                    applyTabChrome()
                    webView.reload()
                    toast(if (tab.desktop) "Versione desktop" else "Versione mobile")
                }
                4 -> confirmClear("Eliminare tutti i recenti?") {
                    repo.clearRecents()
                    if (!browserHasPage) renderRecents()
                    toast("Recenti eliminati")
                }
                5 -> closeCurrentTab()
                6 -> openExternally()
                7 -> shareCurrentLink()
            }
        }.show()
    }

    private fun openExternally() {
        val url = webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Nessuna pagina aperta"); return }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try { startActivity(Intent.createChooser(intent, "Apri con")) }
        catch (_: Exception) { toast("Nessun browser trovato") }
    }

    private fun shareCurrentLink() {
        val url = webView.url?.takeIf { it.startsWith("http") } ?: run { toast("Nessuna pagina aperta"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, webView.title.orEmpty())
        }, "Condividi link"))
    }

    private fun showFindDialog() {
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

    private fun noteUse(item: AppItem) {
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

    private fun showCategoryMenu(name: String) {
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

    private fun pinToPhoneHome(item: AppItem) {
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
    private fun shortcutBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
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

    private fun updateBrowserBody() {
        recentsPanel.isVisible = !browserHasPage
        webView.isVisible = browserHasPage
        if (!browserHasPage) renderRecents()
        updateStar()
    }

    private fun isRealPageTitle(title: String, url: String): Boolean {
        if (title.isBlank() || url.isBlank() || url == "about:blank") return false
        if (title == "about:blank" || title == "about:srcdoc") return false
        return true
    }

    private fun recentLabel(site: RecentSite): String {
        if (isRealPageTitle(site.title, site.url)) return site.title
        return try {
            Uri.parse(site.url).host?.removePrefix("www.") ?: site.url
        } catch (_: Exception) {
            site.url
        }
    }

    private fun renderRecents() {
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
            setTextColor(AppRepository.palette(this@MainActivity).text)
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
                        renderRecents()
                        toast("Recenti eliminati")
                    }
                }
            })
        }
        recentsList.addView(header)
        if (items.isEmpty()) {
            recentsList.addView(TextView(this).apply {
                text = "Nessuna pagina ancora. Apri un'app dalla Home."
                setTextColor(AppRepository.palette(this@MainActivity).textMuted)
                textSize = 14f
                setPadding(dp(4), 0, dp(4), 0)
            })
            return
        }
        items.forEach { site ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                setCardBackgroundColor(AppRepository.palette(this@MainActivity).card)
                strokeWidth = dp(1)
                strokeColor = AppRepository.palette(this@MainActivity).stroke
                cardElevation = 0f
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setOnClickListener { openInBrowser(site.url) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(recentLabel(site))
                        .setItems(arrayOf("Apri", "Elimina")) { dlg, which ->
                            if (which == 0) openInBrowser(site.url)
                            else {
                                repo.removeRecent(site.url)
                                renderRecents()
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
                setTextColor(AppRepository.palette(this@MainActivity).text)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            box.addView(TextView(this).apply {
                text = site.url
                setTextColor(AppRepository.palette(this@MainActivity).textMuted)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(box)
            row.addView(TextView(this).apply {
                text = "×"
                textSize = 20f
                setTextColor(AppRepository.palette(this@MainActivity).textMuted)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = "Elimina dai recenti"
                setOnClickListener {
                    repo.removeRecent(site.url)
                    renderRecents()
                }
            })
            card.addView(row)
            recentsList.addView(card)
        }
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
        findViewById<View>(R.id.emptyState).isVisible = apps.isEmpty()
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
        val match = findAppByName(q)
        when {
            match != null -> { noteUse(match); openInBrowser(normalizeUrl(match.url)) }
            looksLikeUrl(q) -> openInBrowser(normalizeUrl(q))
            else -> openInBrowser(searchUrl(q))
        }
        searchInput.setText("")
        refreshList()
    }

    private fun handleVoiceText(text: String) {
        toast("Hai detto: $text")
        val match = findAppByName(text, voice = true)
        when {
            match != null -> { noteUse(match); openInBrowser(normalizeUrl(match.url)) }
            looksLikeUrl(text) -> openInBrowser(normalizeUrl(text))
            else -> openInBrowser(searchUrl(text))
        }
    }

    /** Exact name first; then a single partial match; for voice also "apri X" and names inside the phrase. */
    private fun findAppByName(raw: String, voice: Boolean = false): AppItem? {
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

    private fun looksLikeUrl(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || t.contains(' ')) return false
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return true
        if (t.startsWith("localhost", true)) return true
        val host = t.substringBefore('/').substringBefore(':')
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    private fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun searchUrl(query: String): String = repo.getSearchEngine().buildUrl(query)

    private fun showAppMenu(item: AppItem) {
        val options = arrayOf(
            "Sposta",
            if (item.pinned) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            "Modifica",
            "Cambia icona",
            "Collegamento sul telefono",
            "Elimina"
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
                5 -> MaterialAlertDialogBuilder(this).setMessage("Eliminare \"${item.name}\"?")
                    .setPositiveButton("Elimina") { _, _ ->
                        item.iconPath?.let { runCatching { java.io.File(it).delete() } }
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
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(dp(4), dp(14), dp(4), dp(6))
            })
        }
        fun row(label: String, sub: String? = null, onClick: () -> Unit) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                setCardBackgroundColor(AppRepository.palette(this@MainActivity).card)
                strokeWidth = dp(1)
                strokeColor = AppRepository.palette(this@MainActivity).stroke
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
                setTextColor(AppRepository.palette(this@MainActivity).text)
                textSize = 15f
            })
            if (!sub.isNullOrBlank()) {
                box.addView(TextView(this).apply {
                    text = sub
                    setTextColor(AppRepository.palette(this@MainActivity).textMuted)
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
        val themeLabel = when (repo.getThemeMode()) {
            "light" -> "Chiaro"
            "system" -> "Sistema"
            else -> "Scuro"
        }
        row("Tema", themeLabel) { dialog.dismiss(); showThemePicker() }
        val accentLabel = when (repo.getAccent()) {
            "amber" -> "Ambra"
            "green" -> "Verde"
            else -> "Blu"
        }
        row("Colore", accentLabel) { dialog.dismiss(); showAccentPicker() }
        row("Svuota home", "Elimina tutte le app salvate") {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this).setMessage("Eliminare tutte le app dalla home? Puoi recuperarle solo da un backup.")
                .setPositiveButton("Svuota") { _, _ ->
                    apps.forEach { a -> a.iconPath?.let { runCatching { java.io.File(it).delete() } } }
                    apps = repo.resetDefaults(); categories = repo.loadCategories(); autoBackupNow(); refreshList(); toast("Home svuotata")
                }.setNegativeButton("Annulla", null).show()
        }
        section("PRIVACY")
        val block3p = repo.blockThirdPartyCookies()
        row("Cookie di terze parti", if (block3p) "Bloccati (consigliato)" else "Consentiti") {
            dialog.dismiss()
            val next = !block3p
            repo.setBlockThirdPartyCookies(next)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !next)
            toast(if (next) "Cookie di terze parti bloccati" else "Cookie di terze parti consentiti: serve se un login non funziona")
        }
        row("Password e compilazione", "Autofill di Android") { dialog.dismiss(); showPasswordInfo() }
        row("Cancella cronologia", "Recenti e cronologia del browser") { dialog.dismiss(); confirmClear("Cancellare cronologia e recenti?") { clearHistoryAndRecents(); toast("Cronologia cancellata") } }
        row("Cancella cookie") { dialog.dismiss(); confirmClear("Cancellare tutti i cookie?") { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); toast("Cookie cancellati") } }
        row("Cancella cache") { dialog.dismiss(); confirmClear("Cancellare la cache?") { webView.clearCache(true); toast("Cache cancellata") } }
        row("Cancella tutti i dati", "Recenti, cronologia, cookie, cache") { dialog.dismiss(); confirmClear("Cancellare tutti i dati di navigazione?") { clearAllBrowsingData(); toast("Dati cancellati") } }
        section("DATI")
        val autoOn = repo.getAutoBackupUri() != null
        row("Backup", if (autoOn) "Automatico attivo · file, Drive o condividi" else "File, Drive o condivisione") {
            dialog.dismiss()
            showBackupMenu()
        }
        row("Ripristina", "Da un file di backup") {
            dialog.dismiss()
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
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

    private fun showBackupMenu() {
        val autoOn = repo.getAutoBackupUri() != null
        val options = arrayOf(
            "Salva su file o Drive",
            "Condividi",
            if (autoOn) "Backup automatico: attivo" else "Attiva backup automatico"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Backup")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("web-launcher-backup.json")
                    1 -> shareBackup()
                    2 -> if (autoOn) showAutoBackupManager() else startAutoBackupSetup()
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun showAutoBackupManager() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Backup automatico")
            .setMessage("Ogni modifica viene scritta sul file che hai scelto, anche su Drive.")
            .setPositiveButton("Cambia file") { _, _ -> startAutoBackupSetup() }
            .setNeutralButton("Disattiva") { _, _ ->
                repo.setAutoBackupUri(null)
                toast("Backup automatico disattivato")
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun backupJson(): String = com.google.gson.Gson().toJson(
        mapOf(
            "apps" to apps,
            "categories" to categories,
            "searchEngine" to repo.getSearchEngine().name
        )
    )

    private fun shareBackup() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, backupJson())
            putExtra(Intent.EXTRA_SUBJECT, "web-launcher-backup.json")
        }, "Condividi backup"))
    }

    private fun confirmClear(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton("Cancella") { _, _ -> action() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun clearHistoryAndRecents() {
        webView.clearHistory()
        repo.clearRecents()
        if (!browserHasPage) renderRecents()
    }

    private fun clearAllBrowsingData() {
        clearHistoryAndRecents()
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

    /** "Agenzia ANSA: ultime notizie ... - ANSA" -> "ANSA"; falls back to the host name. */
    private fun cleanTitle(raw: String, url: String): String {
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

    private fun isSaved(url: String?): Boolean {
        val u = url?.trimEnd('/')?.lowercase() ?: return false
        return apps.any { it.url.trimEnd('/').lowercase() == u }
    }

    private fun updateStar() {
        val saved = currentTabIsBrowser() && isSaved(webView.url)
        val star = findViewById<android.widget.ImageButton>(R.id.btnWebAdd)
        star.setImageResource(if (saved) R.drawable.ic_web_star_filled else R.drawable.ic_web_star)
        star.setColorFilter(if (saved) ContextCompat.getColor(this, R.color.star) else AppRepository.palette(this).text)
    }

    private fun currentTabIsBrowser() = browserHasPage && webView.url?.startsWith("http") == true

    private fun offerAddCurrentPage() {
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
        updateStar()
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
            val json = backupJson()
            (contentResolver.openOutputStream(uri, "wt") ?: contentResolver.openOutputStream(uri))?.use { os ->
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
            @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
            val loadedApps = com.google.gson.Gson().fromJson<MutableList<AppItem>?>(appsJson, appType)
                ?.filter { it != null && !it.name.isNullOrBlank() && !it.url.isNullOrBlank() }
                ?.map {
                    it.copy(
                        id = it.id?.takeIf { id -> id.isNotBlank() } ?: UUID.randomUUID().toString(),
                        url = normalizeUrl(it.url),
                        category = it.category?.takeIf { c -> c.isNotBlank() } ?: "Utility",
                        iconPath = it.iconPath?.takeIf { p -> java.io.File(p).exists() }
                    )
                }?.toMutableList()
            val loadedCats = com.google.gson.Gson().fromJson<MutableList<String>>(catsJson, catType)
            if (loadedApps.isNullOrEmpty()) { toast("Nessuna app nel backup"); return }
            MaterialAlertDialogBuilder(this)
                .setTitle("Ripristina backup")
                .setMessage("Sostituire le ${apps.size} app attuali con le ${loadedApps.size} del backup?")
                .setPositiveButton("Ripristina") { _, _ ->
                    apps = loadedApps
                    categories = (loadedCats?.filterNotNull()?.filter { it.isNotBlank() }?.toMutableList()
                        ?: repo.loadCategories())
                    loadedApps.map { it.category }.distinct().filter { it !in categories }.forEach { categories.add(it) }
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


    private fun showThemePicker() {
        val modes = arrayOf("dark", "light", "system")
        val labels = arrayOf("Scuro", "Chiaro", "Sistema")
        val current = modes.indexOf(repo.getThemeMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                repo.setThemeMode(modes[which])
                AppRepository.applySavedTheme(this)
                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showAccentPicker() {
        val modes = arrayOf("blue", "amber", "green")
        val labels = arrayOf("Blu", "Ambra", "Verde")
        val current = modes.indexOf(repo.getAccent()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Colore")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                repo.setAccent(modes[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private class WebTab(
        var title: String = "Nuova scheda",
        var url: String = "",
        var state: Bundle? = null,
        var privateMode: Boolean = false,
        var desktop: Boolean = false
    )

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_BROWSER = 1
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
