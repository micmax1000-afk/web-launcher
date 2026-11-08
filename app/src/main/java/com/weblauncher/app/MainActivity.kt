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
import coil.load
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
    private lateinit var tabCount: TextView
    private lateinit var suggestPanel: View
    private lateinit var suggestList: android.widget.LinearLayout
    private lateinit var browserToolbar: View
    private lateinit var bottomBar: View
    @Volatile private var blockAdsOn = true
    private var hideBarsOn = true
    private var barsHidden = false
    private var scrollAccum = 0
    private var suggestToken = 0
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var relockPending = false
    /** Disk work (history, backup) off the UI thread: keeps scrolling and page loads smooth. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastRecorded = ""
    private val ai by lazy { AiBridge(findViewById(android.R.id.content), assetLoader) }
    private val assetLoader by lazy {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/pdf/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, pdfDir))
            .build()
    }
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, Float>? = null
    private var speechReply: androidx.webkit.JavaScriptReplyProxy? = null

    private val webSpeechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val reply = speechReply ?: return@registerForActivityResult
        speechReply = null
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        val msg = org.json.JSONObject()
        if (result.resultCode == RESULT_OK && !text.isNullOrEmpty()) msg.put("t", "result").put("text", text)
        else msg.put("t", "error").put("error", "no-speech")
        runCatching { reply.postMessage(msg.toString()) }
    }
    private lateinit var addressIcon: ImageView
    private lateinit var settingsPanel: View
    private lateinit var settingsList: android.widget.LinearLayout
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
    private val news by lazy { NewsRepository(this) }
    private lateinit var newsAdapter: NewsAdapter
    private var newsItems: List<NewsItem> = emptyList()
    private var newsUpdatedAt = 0L
    private var newsLoading = false
    private var newsCat = NEWS_ALL

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

    private val bookmarkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBookmarks(uri)
    }

    private val defaultBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (currentTab == TAB_SETTINGS) renderSettings() }

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
        tabCount = findViewById(R.id.tabCount)
        suggestPanel = findViewById(R.id.suggestPanel)
        suggestList = findViewById(R.id.suggestList)
        browserToolbar = findViewById(R.id.browserToolbar)
        bottomBar = findViewById(R.id.bottomBar)
        addressIcon = findViewById(R.id.addressIcon)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsList = findViewById(R.id.settingsList)
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
        setupNews()
        renderHomeTools()
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
        findViewById<View>(R.id.aiSearchHint).setOnClickListener {
            val q = searchInput.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) { searchInput.setText(""); askAi(q) }
        }
        webSearchHint.setOnClickListener {
            val q = searchInput.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) openInBrowser(searchUrl(q))
        }

        findViewById<View>(R.id.btnMic).setOnClickListener { requestMicAndListen() }
        findViewById<View>(R.id.btnWebHome).setOnClickListener { showTab(TAB_HOME) }
        findViewById<View>(R.id.btnWebMenu).setOnClickListener { showBrowserMenu(it) }
        findViewById<View>(R.id.btnTabs).setOnClickListener { showTabSwitcher() }
        webAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (!webAddress.isFocused) return
                val q = e?.toString()?.trim().orEmpty()
                if (q.isEmpty() || q == webView.url) { hideSuggestions(); return }
                val token = ++suggestToken
                uiHandler.removeCallbacksAndMessages(SUGGEST_TAG)
                uiHandler.postAtTime({ showSuggestions(q, token) }, SUGGEST_TAG, android.os.SystemClock.uptimeMillis() + 150)
            }
        })
        webAddress.setOnFocusChangeListener { _, focused ->
            if (!focused) hideSuggestions() else showBars()
            val url = webView.url?.takeIf { it.startsWith("http") && browserHasPage }
            if (focused) {
                webAddress.setText(url.orEmpty())
                webAddress.selectAll()
            } else {
                webAddress.setText(url?.let { displayUrl(it) }.orEmpty())
            }
        }
        webAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                goAddress()
                true
            } else false
        }
        restoreSavedTabs()
        renderTabStrip()
        showTab(when (savedInstanceState?.getInt(STATE_TAB)) {
            TAB_SETTINGS -> TAB_SETTINGS
            TAB_NEWS -> TAB_NEWS
            else -> TAB_HOME
        })
        if (savedInstanceState == null) openShortcut(intent)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
    }

    /** Colours from the runtime palette: WebView can flip resource colours to the system theme. */
    private fun styleChrome(palette: AppRepository.Companion.UiPalette) {
        val d = resources.displayMetrics.density
        findViewById<View>(R.id.browserToolbar).setBackgroundColor(palette.elevated)
        listOf(R.id.btnWebHome, R.id.btnWebMenu).forEach {
            findViewById<android.widget.ImageButton>(it).setColorFilter(palette.text)
        }
        tabCount.setTextColor(palette.text)
        tabCount.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 5f * d
            setStroke((2 * d).toInt(), palette.text)
        }
        addressIcon.setColorFilter(palette.textMuted)
        findViewById<View>(R.id.addressPill).background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22f * d
            setColor(palette.iconTile)
            setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        webAddress.background = null
        webAddress.setTextColor(palette.text)
        webAddress.setHintTextColor(palette.textMuted)
        findViewById<TextView>(R.id.emptyTitle).setTextColor(palette.text)
        findViewById<TextView>(R.id.emptyText).setTextColor(palette.textMuted)
    }

    private fun setupLauncher() {
        adapter = LauncherAdapter(
            onClick = { openApp(it) },
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
        // dark background while a page loads: no white flash with the dark theme
        webView.setBackgroundColor(if (AppRepository.isLightUi(this)) android.graphics.Color.WHITE else AppRepository.palette(this).bg)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.OFF_SCREEN_PRERASTER)) {
            androidx.webkit.WebSettingsCompat.setOffscreenPreRaster(s, true)
        }
        s.textZoom = repo.textZoom()
        blockAdsOn = repo.blockAds()
        hideBarsOn = repo.hideBarsOnScroll()
        s.allowFileAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        s.saveFormData = true
        s.userAgentString = mobileUserAgent + " WebLauncher/$appVersion"
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !repo.blockThirdPartyCookies())

        applyDarkSites()
        setupMiaBridge()
        webView.setOnScrollChangeListener { _, _, y, _, oldY -> onWebScroll(y, oldY) }
        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val extra = hit.extra
            if (extra.isNullOrBlank() || !extra.startsWith("http")) return@setOnLongClickListener false
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> { showLinkMenu(extra); true }
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> { showImageMenu(extra); true }
                else -> false
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("wl://save")) {
                    Uri.parse(url).getQueryParameter("u")?.let { startDownload(it, null, null, "application/pdf", save = true) }
                    return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true } catch (_: Exception) { true }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (request == null) return null
                if (request.url?.host == MiaAi.HOST) return assetLoader.shouldInterceptRequest(request.url)
                if (!blockAdsOn) return null
                return AdBlocker.intercept(request.url?.host, request.isForMainFrame)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                AdBlocker.resetCount()
                showBars()
                if (MiaAi.isMiaUrl(url) &&
                    !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
                ) view?.evaluateJavascript(MiaAi.SPEECH_SHIM, null)
                webProgress.visibility = View.VISIBLE; webProgress.progress = 0
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                webProgress.visibility = View.INVISIBLE
                val pageUrl = url.orEmpty()
                val safeTitle = view?.title?.trim().orEmpty().takeIf { isRealPageTitle(it, pageUrl) }
                if (safeTitle != null) webTitle.text = safeTitle
                val tab = webTabs.getOrNull(webTabIndex)
                if (tab != null) {
                    if (safeTitle != null) tab.title = safeTitle
                    if (pageUrl.startsWith("http")) tab.url = pageUrl
                }
                if (!webAddress.isFocused && pageUrl.startsWith("http")) webAddress.setText(displayUrl(pageUrl))
                if (pageUrl.startsWith("http")) {
                    if (tab?.privateMode != true && safeTitle != null && !isLockedUrl(pageUrl)) {
                        recordVisit(safeTitle, pageUrl)
                    }
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
                webProgress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                val pageUrl = view?.url.orEmpty()
                val safeTitle = title?.trim().orEmpty().takeIf { isRealPageTitle(it, pageUrl) } ?: return
                webTitle.text = safeTitle
                val tab = webTabs.getOrNull(webTabIndex) ?: return
                tab.title = safeTitle
                if (!tab.privateMode && pageUrl.startsWith("http") && !isLockedUrl(pageUrl)) {
                    recordVisit(safeTitle, pageUrl)
                }
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

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, save: Boolean = false) {
        if (url.startsWith("blob:")) {
            toast("Questo sito crea il file dentro la pagina: non scaricabile qui")
            return
        }
        val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (url.startsWith("data:")) {
            saveDataUrl(url, name)
            return
        }
        val isPdf = mimeType?.contains("pdf", true) == true || name.endsWith(".pdf", true) ||
            Uri.parse(url).path?.endsWith(".pdf", true) == true
        if (isPdf && !save && url.startsWith("http")) {
            openPdfFromUrl(url, userAgent, name)
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
        findViewById<View>(R.id.navNews).setOnClickListener { showTab(TAB_NEWS) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showTab(TAB_SETTINGS) }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    currentTab == TAB_BROWSER && webView.canGoBack() -> webView.goBack()
                    currentTab == TAB_BROWSER -> showTab(TAB_HOME)
                    currentTab == TAB_SETTINGS -> showTab(TAB_HOME)
                    currentTab == TAB_NEWS -> showTab(TAB_HOME)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        showBars()
        hideSuggestions()
        homePanel.isVisible = tab == TAB_HOME
        browserPanel.isVisible = tab == TAB_BROWSER
        settingsPanel.isVisible = tab == TAB_SETTINGS
        findViewById<View>(R.id.newsPanel).isVisible = tab == TAB_NEWS
        if (tab == TAB_SETTINGS) renderSettings()
        if (tab == TAB_NEWS) onNewsShown()
        if (tab == TAB_BROWSER) { ensureTabLoaded(); updateBrowserBody() }
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val muted = AppRepository.palette(this).textMuted
        iconHome.setColorFilter(if (tab == TAB_HOME) accent else muted)
        labelHome.setTextColor(if (tab == TAB_HOME) accent else muted)
        iconBrowser.setColorFilter(if (tab == TAB_BROWSER) accent else muted)
        labelBrowser.setTextColor(if (tab == TAB_BROWSER) accent else muted)
        findViewById<ImageView>(R.id.iconNews).setColorFilter(if (tab == TAB_NEWS) accent else muted)
        findViewById<TextView>(R.id.labelNews).setTextColor(if (tab == TAB_NEWS) accent else muted)
        iconSettings.setColorFilter(if (tab == TAB_SETTINGS) accent else muted)
        labelSettings.setTextColor(if (tab == TAB_SETTINGS) accent else muted)
    }

    private fun openInBrowser(url: String) {
        if (webTabs.isEmpty()) webTabs.add(WebTab())
        val tab = webTabs[webTabIndex]
        tab.url = url
        tab.state = null
        browserHasPage = true
        showTab(TAB_BROWSER)
        if (!webAddress.isFocused) webAddress.setText(displayUrl(url))
        applyTabChrome()
        webView.loadUrl(url)
        updateBrowserBody()
        renderTabStrip()
    }

    /** Tabs restored from the last session load only when the browser is opened. */
    private fun ensureTabLoaded() {
        val tab = webTabs.getOrNull(webTabIndex) ?: return
        val loaded = webView.url
        if (tab.url.startsWith("http") && (loaded.isNullOrBlank() || loaded == "about:blank") && tab.state == null) {
            applyTabChrome()
            browserHasPage = true
            webView.loadUrl(tab.url)
            if (!webAddress.isFocused) webAddress.setText(displayUrl(tab.url))
        }
    }

    private fun restoreSavedTabs() {
        val (saved, active) = repo.loadTabs()
        if (saved.isEmpty()) return
        webTabs.clear()
        saved.forEach { webTabs.add(WebTab(title = it.title, url = it.url, desktop = it.desktop)) }
        webTabIndex = active.coerceIn(0, webTabs.lastIndex)
        browserHasPage = true
    }

    private fun persistTabs() {
        if (!::repo.isInitialized) return
        runCatching { saveCurrentTab() }
        val keep = webTabs.withIndex().filter {
            !it.value.privateMode && it.value.url.startsWith("http") && !isLockedUrl(it.value.url)
        }
        val active = keep.indexOfFirst { it.index == webTabIndex }.coerceAtLeast(0)
        repo.saveTabs(keep.map { SavedTab(it.value.url, it.value.title, it.value.desktop) }, active)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onStop() {
        super.onStop()
        if (::webView.isInitialized && isLockedUrl(webView.url)) relockPending = true
    }

    override fun onStart() {
        super.onStart()
        if (!relockPending) return
        relockPending = false
        if (!isLockedUrl(webView.url)) return
        webView.visibility = View.INVISIBLE
        authenticate("Sblocca la pagina", strict = false, onFail = {
            closeCurrentTab()
            showTab(TAB_HOME)
            webView.visibility = View.VISIBLE
        }) { webView.visibility = View.VISIBLE }
    }

    override fun onPause() {
        super.onPause()
        persistTabs()
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openShortcut(intent)
    }

    private fun openShortcut(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file") &&
            (intent.type?.contains("pdf") == true || data.path?.endsWith(".pdf", true) == true)
        ) { openPdfFromUri(data); return }
        val external = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        val url = intent?.getStringExtra("shortcut_url") ?: external ?: return
        if (url.isBlank()) return
        if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false)
        openInBrowser(normalizeUrl(url))
    }

    private fun goAddress() {
        val raw = webAddress.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        leaveAddressBar()
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
        if (!webAddress.isFocused) webAddress.setText(if (tab.url.startsWith("http")) displayUrl(tab.url) else "")
        showTab(TAB_BROWSER)
        updateBrowserBody()
    }

    private fun applyTabChrome() {
        val tab = webTabs.getOrNull(webTabIndex) ?: return
        val s = webView.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else mobileUserAgent + " WebLauncher/$appVersion"
        s.cacheMode = if (tab.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    private fun displayUrl(url: String): String = if (MiaAi.isMiaUrl(url)) MiaAi.NAME
        else if (isPdfViewer(url)) "PDF · " + pdfName(url) else try {
        val u = Uri.parse(url)
        val host = u.host?.removePrefix("www.") ?: url
        if (u.scheme == "http") "⚠ $host" else host
    } catch (_: Exception) { url }

    /** Tab counter (Chrome style) + lock/search icon in the address bar. */
    private fun renderTabStrip() {
        tabCount.text = if (webTabs.size > 99) ":D" else webTabs.size.toString()
        val url = webView.url.orEmpty()
        addressIcon.setImageResource(
            if (browserHasPage && url.startsWith("https://")) R.drawable.ic_web_lock else R.drawable.ic_search
        )
    }

    private fun showTabSwitcher() {
        saveCurrentTab()
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, dp(12))
        }
        header.addView(TextView(this).apply {
            text = if (webTabs.size == 1) "1 scheda" else "${webTabs.size} schede"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.text)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Nuova"
            isAllCaps = false
            cornerRadius = dp(20)
            setIconResource(R.drawable.ic_web_add)
            setOnClickListener { sheet.dismiss(); openNewTab(false) }
        })
        root.addView(header)
        webTabs.forEachIndexed { index, tab ->
            val active = index == webTabIndex
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(palette.card)
                strokeWidth = dp(if (active) 2 else 1)
                strokeColor = if (active) accent else palette.stroke
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    sheet.dismiss()
                    if (index == webTabIndex) showTab(TAB_BROWSER) else switchToTab(index)
                }
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(6), dp(14))
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = when {
                tab.title.isNotBlank() && tab.title != "about:blank" -> tab.title
                else -> "Nuova scheda"
            }
            box.addView(TextView(this).apply {
                text = (if (tab.privateMode) "🕶 " else "") + title
                textSize = 16f
                setTextColor(palette.text)
                if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (tab.url.startsWith("http")) box.addView(TextView(this).apply {
                text = displayUrl(tab.url)
                textSize = 13f
                setTextColor(palette.textMuted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(box)
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_web_close)
                setColorFilter(palette.textMuted)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                contentDescription = "Chiudi scheda"
                setOnClickListener { sheet.dismiss(); closeTabAt(index); showTabSwitcher() }
            })
            card.addView(row)
            root.addView(card)
        }
        if (webTabs.size > 1) root.addView(com.google.android.material.button.MaterialButton(
            this, null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            text = "Chiudi tutte le schede"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
            setOnClickListener {
                sheet.dismiss()
                webTabs.clear()
                webTabs.add(WebTab())
                webTabIndex = 0
                restoreTab(webTabs[0])
                renderTabStrip()
            }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun showBrowserMenu(anchor: View) {
        val tab = currentTab()
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val hasPage = browserHasPage && webView.url?.startsWith("http") == true
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(palette.elevated)
                setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            elevation = 12f * d
        }
        val scroller = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        val maxH = resources.displayMetrics.heightPixels - dp(120)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(272), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = android.widget.PopupWindow(scroller, dp(272), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 12f * d
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Top icon row, like Chrome: back, forward, star, share, refresh
        val icons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        fun iconBtn(res: Int, desc: String, enabled: Boolean, tint: Int = palette.text, action: () -> Unit) {
            icons.addView(android.widget.ImageButton(this).apply {
                setImageResource(res)
                setColorFilter(tint)
                background = null
                scaleType = android.widget.ImageView.ScaleType.CENTER
                contentDescription = desc
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.3f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(52), 1f)
                setOnClickListener { popup.dismiss(); action() }
            })
        }
        val saved = hasPage && isSaved(webView.url)
        iconBtn(R.drawable.ic_web_back, "Indietro", webView.canGoBack()) { webView.goBack() }
        iconBtn(R.drawable.ic_web_forward, "Avanti", webView.canGoForward()) { webView.goForward() }
        iconBtn(
            if (saved) R.drawable.ic_web_star_filled else R.drawable.ic_web_star, "Salva pagina", hasPage,
            if (saved) ContextCompat.getColor(this, R.color.star) else palette.text
        ) { offerAddCurrentPage() }
        iconBtn(R.drawable.ic_web_share, "Condividi", hasPage) { shareCurrentLink() }
        iconBtn(R.drawable.ic_web_refresh, "Ricarica", hasPage) { webView.reload() }
        root.addView(icons)
        root.addView(View(this).apply {
            setBackgroundColor(palette.stroke)
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1))
        })

        fun item(label: String, enabled: Boolean = true, checked: Boolean? = null, action: () -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(14), 0)
                minimumHeight = dp(50)
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { popup.dismiss(); action() }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(palette.text)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (checked != null) row.addView(android.widget.CheckBox(this).apply {
                isChecked = checked
                isClickable = false
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            })
            root.addView(row)
        }
        item("Nuova scheda") { openNewTab(false) }
        item("Nuova scheda senza cronologia") { openNewTab(true) }
        item("Schede aperte (${webTabs.size})") { showTabSwitcher() }
        item("✨ Riassumi pagina", hasPage) { summarizePage() }
        item("✨ Chiedi alla pagina", hasPage) { askThePage() }
        item("📖 Modalità lettura", hasPage) { openReaderMode() }
        item("🔊 Ascolta la pagina", hasPage) { openReaderMode(listen = true) }
        item("🔖 Leggi dopo", hasPage) { saveCurrentForLater() }
        item("✨ Traduci con AI", hasPage) { translatePageWithAi() }
        item("Cronologia") { showHistoryPage() }
        item("Download") { showDownloadsPage() }
        item("Trova nella pagina", hasPage) { showFindDialog() }
        item("Traduci con Google", hasPage) { translateCurrentPage() }
        item("📷 Scansiona QR code") { scanQr() }
        item("Dimensione testo (${repo.textZoom()}%)") { showTextZoomDialog() }
        item("Sito desktop", hasPage, tab.desktop) {
            tab.desktop = !tab.desktop
            applyTabChrome()
            webView.reload()
        }
        item("Apri in Chrome / altro browser", hasPage) { openExternally() }
        item("Chiudi scheda") { closeCurrentTab() }
        item("Impostazioni") { showTab(TAB_SETTINGS) }
        if (blockAdsOn && hasPage) root.addView(TextView(this).apply {
            text = "🛡 ${AdBlocker.blockedCount} pubblicità e tracker bloccati"
            textSize = 13f
            setTextColor(palette.textMuted)
            setPadding(dp(20), dp(8), dp(14), dp(4))
        })
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(272), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (root.measuredHeight > maxH) popup.height = maxH
        popup.showAsDropDown(anchor, -dp(228), -anchor.height + dp(4))
    }

    private fun applyDarkSites() {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                webView.settings,
                repo.darkSites() && !AppRepository.isLightUi(this)
            )
        }
    }

    private fun openInNewTab(url: String) {
        openNewTab(false)
        openInBrowser(url)
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Copiato")
    }

    private fun showLinkMenu(url: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(displayUrl(url))
            .setItems(arrayOf("Apri in nuova scheda", "Apri senza cronologia", "Copia link", "Condividi link", "Apri in Chrome / altro browser")) { _, which ->
                when (which) {
                    0 -> openInNewTab(url)
                    1 -> { openNewTab(true); openInBrowser(url) }
                    2 -> copyText("Link", url)
                    3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
                    }, "Condividi link"))
                    4 -> try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Apri con"))
                    } catch (_: Exception) { toast("Nessun browser trovato") }
                }
            }
            .show()
    }

    private fun showImageMenu(url: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Immagine")
            .setItems(arrayOf("Apri immagine in nuova scheda", "Scarica immagine", "Copia indirizzo immagine")) { _, which ->
                when (which) {
                    0 -> openInNewTab(url)
                    1 -> startDownload(url, webView.settings.userAgentString, null, null)
                    2 -> copyText("Immagine", url)
                }
            }
            .show()
    }

    // ======================= Apps with fingerprint lock =======================

    private fun openApp(item: AppItem) {
        val launch = {
            noteUse(item)
            openInBrowser(normalizeUrl(item.url))
        }
        if (item.locked) authenticate("Apri ${item.name}", strict = false) { launch() } else launch()
    }

    private fun hostOf(url: String?): String? = try {
        Uri.parse(url.orEmpty()).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) { null }

    private fun isLockedUrl(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return apps.any { a ->
            if (!a.locked) return@any false
            val h = hostOf(normalizeUrl(a.url)) ?: return@any false
            host == h || host.endsWith(".$h")
        }
    }

    /**
     * Fingerprint / face / PIN prompt. strict = true refuses when the phone has no screen lock
     * (used to turn a lock on); otherwise the action runs anyway with a warning.
     */
    private fun authenticate(title: String, strict: Boolean = true, onFail: () -> Unit = {}, onOk: () -> Unit) {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = androidx.biometric.BiometricManager.from(this).canAuthenticate(auth)
        if (can != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            if (strict) {
                toast("Imposta prima un blocco schermo (PIN o impronta) nel telefono")
                onFail()
            } else {
                toast("Blocco schermo non attivo: app aperta senza impronta")
                onOk()
            }
            return
        }
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    onOk()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFail()
                }
            }
        )
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Usa impronta, volto o PIN")
            .setAllowedAuthenticators(auth)
            .build()
        prompt.authenticate(info)
    }

    // ======================= Hide bars while scrolling =======================

    private fun onWebScroll(y: Int, oldY: Int) {
        if (!hideBarsOn || currentTab != TAB_BROWSER || webAddress.isFocused || customView != null) return
        val d = resources.displayMetrics.density
        val dy = y - oldY
        if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) scrollAccum = 0
        scrollAccum += dy
        when {
            !barsHidden && scrollAccum > 40 * d && y > 140 * d -> hideBars()
            barsHidden && (scrollAccum < -40 * d || y < 8 * d) -> showBars()
        }
    }

    private fun hideBars() {
        barsHidden = true
        scrollAccum = 0
        browserToolbar.isVisible = false
        bottomBar.isVisible = false
    }

    private fun showBars() {
        scrollAccum = 0
        if (!barsHidden) return
        barsHidden = false
        browserToolbar.isVisible = true
        bottomBar.isVisible = true
    }

    // ======================= Address bar suggestions =======================

    private fun leaveAddressBar() {
        webAddress.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(webAddress.windowToken, 0)
        hideSuggestions()
    }

    private fun hideSuggestions() {
        if (!suggestPanel.isVisible) return
        suggestPanel.isVisible = false
        findViewById<View>(R.id.webViewHost).isVisible = true
        updateBrowserBody()
    }

    private fun suggestEndpoint(): String? = when (repo.getSearchEngine()) {
        SearchEngine.GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&hl=it&ie=utf-8&oe=utf-8&q="
        SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?type=list&q="
        SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query="
        else -> null
    }

    private fun showSuggestions(q: String, token: Int) {
        if (token != suggestToken || !webAddress.isFocused) return
        val lq = q.lowercase(Locale.ROOT)
        val appHits = apps.filter {
            it.name.lowercase(Locale.ROOT).contains(lq) || it.url.lowercase(Locale.ROOT).contains(lq)
        }.take(3)
        renderSuggestions(q, appHits, emptyList(), emptyList())
        val appUrls = appHits.map { normalizeUrl(it.url) }.toSet()
        val endpoint = suggestEndpoint()
        val wantRemote = repo.searchSuggestions() && endpoint != null && !looksLikeUrl(q)
        Thread {
            val histHits = repo.loadHistory().filter {
                it.title.lowercase(Locale.ROOT).contains(lq) || it.url.lowercase(Locale.ROOT).contains(lq)
            }.distinctBy { it.url }.filter { it.url !in appUrls }.take(4)
            runOnUiThread {
                if (token == suggestToken && webAddress.isFocused) renderSuggestions(q, appHits, histHits, emptyList())
            }
            if (!wantRemote) return@Thread
            val remote = try {
                val conn = java.net.URL(endpoint + Uri.encode(q)).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.setRequestProperty("User-Agent", mobileUserAgent)
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val arr = org.json.JSONArray(body).getJSONArray(1)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() && it != q }.take(5)
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (token == suggestToken && webAddress.isFocused) renderSuggestions(q, appHits, histHits, remote)
            }
        }.start()
    }

    private fun renderSuggestions(q: String, appHits: List<AppItem>, histHits: List<HistoryEntry>, remote: List<String>) {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        suggestPanel.setBackgroundColor(palette.bg)
        suggestList.removeAllViews()
        fun row(icon: Int, title: String, sub: String?, tint: Int = palette.textMuted, onClick: () -> Unit) {
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { leaveAddressBar(); onClick() }
            }
            r.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(tint)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(18) }
            })
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(TextView(this).apply {
                text = title; textSize = 16f; setTextColor(palette.text)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
                text = sub; textSize = 13f; setTextColor(palette.textMuted)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            r.addView(box)
            suggestList.addView(r)
        }
        if (looksLikeUrl(q)) row(R.drawable.ic_web_forward, q, "Vai al sito") { openInBrowser(normalizeUrl(q)) }
        else {
            row(R.drawable.ic_search, q, "Cerca con ${repo.getSearchEngine().label}") { openInBrowser(searchUrl(q)) }
            row(R.drawable.ic_ai, "Chiedi all'AI", q, com.google.android.material.color.MaterialColors.getColor(
                this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())) { askAi(q) }
        }
        appHits.forEach { a ->
            row(R.drawable.ic_web_star_filled, a.name, displayUrl(normalizeUrl(a.url)),
                ContextCompat.getColor(this, R.color.star)) { openApp(a) }
        }
        histHits.forEach { h -> row(R.drawable.ic_web_history, recentLabel(RecentSite(h.title, h.url)), displayUrl(h.url)) { openInBrowser(h.url) } }
        remote.forEach { r -> row(R.drawable.ic_search, r, null) { openInBrowser(searchUrl(r)) } }
        suggestPanel.isVisible = true
        recentsPanel.isVisible = false
        findViewById<View>(R.id.webViewHost).isVisible = false
    }

    // ======================= Default browser =======================

    private fun isDefaultBrowser(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            rm?.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) == true
        } else {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName == packageName
        }
    } catch (_: Exception) { false }

    private fun requestDefaultBrowser() {
        if (isDefaultBrowser()) { toast("Web Launcher è già il browser predefinito"); return }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    defaultBrowserLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER))
                    return
                }
            }
            defaultBrowserLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            toast("Impostazioni Android → App → App predefinite → Browser")
        }
    }

    // ======================= Translate / text size =======================

    private fun translateCurrentPage() {
        val url = webView.url?.takeIf { it.startsWith("http") } ?: return
        if (url.contains("translate.goog") || url.contains("translate.google")) { toast("Pagina già tradotta"); return }
        openInBrowser("https://translate.google.com/translate?sl=auto&tl=it&u=" + Uri.encode(url))
    }

    private fun showTextZoomDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val start = (repo.textZoom() / 10 * 10).coerceIn(50, 200)
        val label = TextView(this).apply {
            text = "$start%"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(AppRepository.palette(this@MainActivity).text)
        }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 50f; valueTo = 200f; stepSize = 10f; value = start.toFloat()
            addOnChangeListener { _, v, _ ->
                label.text = "${v.toInt()}%"
                webView.settings.textZoom = v.toInt()
            }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(label)
            addView(slider)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Dimensione testo")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                repo.setTextZoom(slider.value.toInt())
                if (currentTab == TAB_SETTINGS) renderSettings()
            }
            .setNeutralButton("100%") { _, _ ->
                repo.setTextZoom(100); webView.settings.textZoom = 100
                if (currentTab == TAB_SETTINGS) renderSettings()
            }
            .setNegativeButton("Annulla") { _, _ -> webView.settings.textZoom = repo.textZoom() }
            .setOnCancelListener { webView.settings.textZoom = repo.textZoom() }
            .show()
    }

    // ======================= Full-screen pages: history, downloads =======================

    private fun openPage(
        title: String,
        actionLabel: String? = null,
        onAction: ((androidx.appcompat.app.AppCompatDialog) -> Unit)? = null,
        build: (root: android.widget.LinearLayout, body: android.widget.LinearLayout, dialog: androidx.appcompat.app.AppCompatDialog) -> Unit
    ) {
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
        }
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(12), dp(8))
        }
        header.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_web_back)
            setColorFilter(palette.text)
            background = null
            contentDescription = "Indietro"
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { dialog.dismiss() }
        })
        header.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.text)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (actionLabel != null && onAction != null) header.addView(TextView(this).apply {
            text = actionLabel
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(accent)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            setOnClickListener { onAction(dialog) }
        })
        root.addView(header)
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        root.addView(android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(body)
        })
        build(root, body, dialog)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg))
            statusBarColor = palette.bg
            navigationBarColor = palette.bg
        }
        dialog.show()
    }

    private fun listRow(
        body: android.widget.LinearLayout, title: String, sub: String?,
        onClick: () -> Unit, onLong: (() -> Unit)? = null, onRemove: (() -> Unit)? = null
    ) {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val r = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(10), dp(6), 0, dp(6))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(palette.text)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(palette.textMuted)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(box)
        if (onRemove != null) r.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_web_close)
            setColorFilter(palette.textMuted)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(52), dp(52))
            contentDescription = "Elimina"
            setOnClickListener { onRemove() }
        })
        body.addView(r)
    }

    private fun sectionLabel(body: android.widget.LinearLayout, text: String) {
        val d = resources.displayMetrics.density
        body.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
            setPadding((10 * d).toInt(), (18 * d).toInt(), 0, (4 * d).toInt())
        })
    }

    private fun emptyLabel(body: android.widget.LinearLayout, text: String) {
        val d = resources.displayMetrics.density
        body.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(AppRepository.palette(this@MainActivity).textMuted)
            setPadding(0, (60 * d).toInt(), 0, 0)
        })
    }

    private fun dayLabel(time: Long): String {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = time
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        return when (day) {
            today -> "Oggi"
            yesterday -> "Ieri"
            else -> java.text.SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(java.util.Date(time))
                .replaceFirstChar { it.uppercase() }
        }
    }

    private fun showHistoryPage() {
        openPage("Cronologia", "Cancella tutto", { dlg ->
            confirmClear("Cancellare tutta la cronologia?") {
                clearHistoryAndRecents(); dlg.dismiss(); toast("Cronologia cancellata")
            }
        }) { root, body, dialog ->
            val palette = AppRepository.palette(this)
            val d = resources.displayMetrics.density
            val search = EditText(this).apply {
                hint = "Cerca nella cronologia"
                setSingleLine()
                textSize = 16f
                setTextColor(palette.text)
                setHintTextColor(palette.textMuted)
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24 * d
                    setColor(palette.iconTile)
                    setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt()
                ).apply { setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()) }
            }
            root.addView(search, 1)
            val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN)
            fun render() {
                body.removeAllViews()
                val q = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val list = repo.loadHistory().filter {
                    q.isEmpty() || it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q)
                }.take(300)
                if (list.isEmpty()) { emptyLabel(body, if (q.isEmpty()) "La cronologia è vuota" else "Nessun risultato"); return }
                var lastDay = ""
                list.forEach { h ->
                    val day = dayLabel(h.time)
                    if (day != lastDay) { sectionLabel(body, day); lastDay = day }
                    listRow(body, recentLabel(RecentSite(h.title, h.url)),
                        "${timeFmt.format(java.util.Date(h.time))} · ${displayUrl(h.url)}",
                        onClick = { dialog.dismiss(); openInBrowser(h.url) },
                        onLong = { showLinkMenu(h.url) },
                        onRemove = { repo.removeHistory(h.url); render() })
                }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
            render()
        }
    }

    private fun showDownloadsPage() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
        openPage("Download", "Cartella", { _ ->
            try { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            catch (_: Exception) { toast("Apri l'app File → Download") }
        }) { _, body, _ ->
            fun open(id: Long) {
                val uri = dm.getUriForDownloadedFile(id) ?: run { toast("File non trovato"); return }
                val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
                if (mime.contains("pdf")) { openPdfFromUri(uri); return }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { toast("Nessuna app per aprire questo file") }
            }
            fun share(id: Long) {
                val uri = dm.getUriForDownloadedFile(id) ?: return
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Condividi file"))
            }
            fun render() {
                body.removeAllViews()
                data class Dl(val id: Long, val title: String, val status: Int, val size: Long, val done: Long, val time: Long)
                val items = mutableListOf<Dl>()
                try {
                    dm.query(android.app.DownloadManager.Query())?.use { c ->
                        val iId = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
                        val iTitle = c.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
                        val iStatus = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                        val iSize = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val iDone = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val iTime = c.getColumnIndex(android.app.DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                        while (c.moveToNext()) {
                            items.add(Dl(c.getLong(iId), c.getString(iTitle).orEmpty(), c.getInt(iStatus),
                                c.getLong(iSize), c.getLong(iDone), c.getLong(iTime)))
                        }
                    }
                } catch (_: Exception) { }
                if (items.isEmpty()) { emptyLabel(body, "Nessun download"); return }
                var lastDay = ""
                items.sortedByDescending { it.time }.forEach { dl ->
                    val day = dayLabel(dl.time)
                    if (day != lastDay) { sectionLabel(body, day); lastDay = day }
                    val size = android.text.format.Formatter.formatShortFileSize(this, dl.size.coerceAtLeast(0))
                    val status = when (dl.status) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> size
                        android.app.DownloadManager.STATUS_FAILED -> "Non riuscito"
                        android.app.DownloadManager.STATUS_PAUSED -> "In pausa"
                        else -> if (dl.size > 0) "In corso · ${dl.done * 100 / dl.size}%" else "In corso"
                    }
                    val ok = dl.status == android.app.DownloadManager.STATUS_SUCCESSFUL
                    listRow(body, dl.title.ifBlank { "File" }, status,
                        onClick = { if (ok) open(dl.id) else toast(status) },
                        onLong = {
                            if (ok) MaterialAlertDialogBuilder(this).setTitle(dl.title)
                                .setItems(arrayOf("Apri", "Condividi")) { _, w -> if (w == 0) open(dl.id) else share(dl.id) }
                                .show()
                        },
                        onRemove = {
                            confirmClear("Eliminare \"${dl.title}\" dal telefono?") { dm.remove(dl.id); render() }
                        })
                }
            }
            render()
        }
    }

    // ======================= Import bookmarks from Chrome =======================

    private fun showBookmarkImportHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Importa preferiti da Chrome")
            .setMessage(
                "Chrome sul telefono non esporta i preferiti, serve un computer:\n\n" +
                "1. Sul computer apri Chrome (con lo stesso account Google)\n" +
                "2. Menu ⋮ → Preferiti ed elenchi → Gestione preferiti\n" +
                "3. Menu ⋮ in alto a destra → Esporta preferiti\n" +
                "4. Metti il file sul telefono (Drive, email, cavo)\n" +
                "5. Tocca \"Scegli file\" e seleziona il file .html\n\n" +
                "Poi scegli tu quali siti aggiungere."
            )
            .setPositiveButton("Scegli file") { _, _ ->
                bookmarkLauncher.launch(arrayOf("text/html", "text/*", "application/octet-stream", "*/*"))
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun importBookmarks(uri: Uri) {
        val html = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null } ?: run { toast("Impossibile leggere il file"); return }
        val all = BookmarkImport.parse(html)
        if (all.isEmpty()) { toast("Nessun preferito trovato nel file"); return }
        val existing = apps.map { normalizeUrl(it.url).trimEnd('/').lowercase() }.toSet()
        val fresh = all.filter { it.url.trimEnd('/').lowercase() !in existing }
        if (fresh.isEmpty()) { toast("Sono già tutti nella home"); return }
        val labels = fresh.map { "${cleanTitle(it.title, it.url)}  ·  ${it.folder}" }.toTypedArray()
        val checked = BooleanArray(fresh.size) { true }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Scegli i siti (${fresh.size})")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Importa", null)
            .setNeutralButton("Nessuno", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            val neutral = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            neutral.setOnClickListener {
                val select = checked.none { it }
                checked.indices.forEach { i -> checked[i] = select; dialog.listView.setItemChecked(i, select) }
                neutral.text = if (select) "Nessuno" else "Tutti"
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var added = 0
                fresh.forEachIndexed { i, b ->
                    if (!checked[i]) return@forEachIndexed
                    val cat = b.folder.take(30)
                    if (cat !in categories) categories.add(cat)
                    apps.add(AppItem(UUID.randomUUID().toString(), cleanTitle(b.title, b.url).take(40),
                        normalizeUrl(b.url), cat, false, apps.size))
                    added++
                }
                repo.saveCategories(categories)
                persistApps(); refreshList()
                dialog.dismiss()
                toast(if (added == 0) "Nessun sito importato" else "Importati $added siti")
            }
        }
        dialog.show()
    }

    private fun addMiaToHome() {
        if (apps.any { MiaAi.isMiaUrl(it.url) }) { toast("Mia AI è già nella home"); return }
        val id = UUID.randomUUID().toString()
        val icon = try {
            val dir = java.io.File(filesDir, "icons").apply { mkdirs() }
            val dest = java.io.File(dir, id)
            assets.open("mia-ai/icon.png").use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.absolutePath
        } catch (_: Exception) { null }
        val cat = "AI"
        if (cat !in categories) { categories.add(0, cat); repo.saveCategories(categories) }
        apps.add(AppItem(id, MiaAi.NAME, MiaAi.URL, cat, true, apps.size, icon))
        persistApps(); refreshList()
        toast("Mia AI aggiunta ai preferiti")
    }

    /** Native voice input + text-to-speech for Mia AI, reachable only from its private origin. */
    private fun setupMiaBridge() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origins = setOf(MiaAi.ORIGIN)
        androidx.webkit.WebViewCompat.addWebMessageListener(webView, MiaAi.BRIDGE, origins) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != MiaAi.ORIGIN) return@addWebMessageListener
            val m = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            when (m.optString("t")) {
                "listen" -> {
                    speechReply = reply
                    if (!isSpeechAvailable()) {
                        runCatching { reply.postMessage("{\"t\":\"error\",\"error\":\"not-allowed\"}") }
                        toast(getString(R.string.no_speech))
                        return@addWebMessageListener
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, m.optString("lang", "it-IT"))
                        putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
                    }
                    try { webSpeechLauncher.launch(intent) } catch (_: Exception) {
                        runCatching { reply.postMessage("{\"t\":\"error\",\"error\":\"not-allowed\"}") }
                    }
                }
                "speak" -> speak(m.optString("text"), m.optDouble("rate", 1.0).toFloat())
                "stop" -> tts?.stop()
            }
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, MiaAi.SPEECH_SHIM, origins)
        }
    }

    private fun speak(text: String, rate: Float) {
        if (text.isBlank()) return
        val clean = text.replace(Regex("[*_#`>|]"), " ").replace(Regex("\\s+"), " ").trim()
        if (tts == null) {
            pendingSpeech = clean to rate
            tts = android.speech.tts.TextToSpeech(this) { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.ITALIAN
                    pendingSpeech?.let { (t, r) -> speakNow(t, r) }
                } else toast("Sintesi vocale non disponibile")
                pendingSpeech = null
            }
            return
        }
        if (ttsReady) speakNow(clean, rate) else pendingSpeech = clean to rate
    }

    private fun speakNow(text: String, rate: Float) {
        val engine = tts ?: return
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        // TTS has a per-utterance length limit: speak long answers in chunks
        val max = android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 1
        text.chunked(max).forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD, null, "wl_$i")
        }
    }

    override fun onDestroy() {
        io.shutdown()
        runCatching { ai.destroy() }
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ======================= AI (provider and key from Mia AI) =======================

    private fun aiReady(): Boolean {
        if (!ai.supported()) { toast("Aggiorna \"Android System WebView\" dal Play Store per usare l'AI"); return false }
        return true
    }

    /** Markdown subset -> styled text (bold, code, headings, bullet lists). */
    private fun formatAi(md: String): CharSequence {
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

    private fun jsString(raw: String?): String =
        runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }.getOrNull().orEmpty()

    /** Visible text of the current page (trimmed), for summaries and questions. */
    private fun readPage(onText: (title: String, url: String, text: String) -> Unit) {
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

    private fun summarizePage() {
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

    private fun askThePage() {
        if (!aiReady()) return
        readPage { title, url, text ->
            showAiSheet(title = "Chiedi alla pagina", context = pageContext(title, url, text), firstQuestion = null,
                hint = "Es. quanto costa? quali sono gli orari?")
        }
    }

    private fun pageContext(title: String, url: String, text: String) =
        "Ecco il contenuto della pagina web «$title» ($url). Rispondi usando queste informazioni; " +
            "se la risposta non c'è, dillo.\n\n--- PAGINA ---\n$text\n--- FINE PAGINA ---\n\n"

    private fun askAi(question: String) {
        if (!aiReady()) return
        showAiSheet(title = "Risposta AI", context = null, firstQuestion = question)
    }

    private fun explainSelection(sel: String) {
        if (!aiReady()) return
        showAiSheet(
            title = "Spiegazione",
            context = null,
            firstQuestion = "Spiega in modo semplice, in italiano, questo testo. Se non è in italiano, prima traducilo.\n\n«${sel.take(4000)}»",
            showFirstQuestion = false,
            quote = sel.take(300)
        )
    }

    /**
     * Bottom sheet chat: the page context (if any) is prepended to the first question,
     * follow-up questions keep the whole conversation.
     */
    private fun showAiSheet(
        title: String,
        context: String?,
        firstQuestion: String?,
        showFirstQuestion: Boolean = true,
        hint: String = "Fai un'altra domanda…",
        quote: String? = null
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
        root.addView(head)
        ai.status { ok, label -> modelLabel.text = if (ok) label else "Da configurare in Mia AI" }

        val chat = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
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
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onError(message: String, noKey: Boolean) {
                    running = 0; sendBtn.alpha = 1f
                    history.removeAt(history.lastIndex)
                    answerView.text = message
                    answerView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                    if (noKey) chat.addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                        text = "Configura in Mia AI"
                        isAllCaps = false
                        setOnClickListener { sheet.dismiss(); openMiaAi() }
                    })
                }
            })
        }
        sendBtn.setOnClickListener { send(input.text?.toString()?.trim().orEmpty(), true) }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { send(input.text?.toString()?.trim().orEmpty(), true); true } else false
        }
        sheet.setOnDismissListener { if (running != 0) ai.cancel(running); tts?.stop() }
        sheet.setContentView(root)
        root.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        root.minimumHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
        if (firstQuestion != null) send(firstQuestion, showFirstQuestion)
        else input.requestFocus()
    }

    private fun openMiaAi() {
        if (apps.none { MiaAi.isMiaUrl(it.url) }) addMiaToHome()
        openInBrowser(MiaAi.URL)
    }

    /** Text selection toolbar inside pages: adds "Chiedi all'AI". */
    override fun onActionModeStarted(mode: android.view.ActionMode) {
        super.onActionModeStarted(mode)
        if (currentTab != TAB_BROWSER || !::webView.isInitialized || !webView.hasFocus()) return
        mode.menu.add(android.view.Menu.NONE, MENU_AI, 0, "Chiedi all'AI").setOnMenuItemClickListener {
            webView.evaluateJavascript("window.getSelection().toString()") { raw ->
                val sel = jsString(raw).trim()
                mode.finish()
                if (sel.isNotEmpty()) explainSelection(sel)
            }
            true
        }
    }

    /**
     * Voice: short phrases keep the instant behaviour; longer commands are interpreted
     * by the AI ("apri YouTube e cerca ricette di pasta", "riassumi la pagina"...).
     */
    private fun interpretVoice(text: String, fallback: () -> Unit) {
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
            "{\"action\":\"downloads\"}, {\"action\":\"settings\"}, {\"action\":\"news\"} per le notizie, " +
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
                        "briefing" -> { showTab(TAB_NEWS); newsBriefing() }
                        else -> fallback()
                    }
                }
                override fun onError(message: String, noKey: Boolean) { fallback() }
            })
    }

    // ======================= News aggregator =======================

    private fun setupNews() {
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

    private fun onNewsShown() {
        if (newsItems.isEmpty()) {
            val (cached, updated) = news.cached()
            newsItems = cached
            newsUpdatedAt = updated
            renderNews()
        }
        if (!newsLoading && System.currentTimeMillis() - newsUpdatedAt > 15 * 60_000L) refreshNews()
    }

    private fun refreshNews() {
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

    private fun renderNews() {
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
                    findViewById<RecyclerView>(R.id.newsList).scrollToPosition(0)
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

    private fun showNewsMenu(item: NewsItem) {
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

    private fun summarizeNews(item: NewsItem) {
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

    private fun newsBriefing() {
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

    private fun showNewsSources() {
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
                NewsRepository.CATEGORY_ORDER.forEach { cat ->
                    val group = all.filter { it.cat == cat }
                    if (group.isEmpty()) return@forEach
                    val on = group.count { it.id in enabled }
                    sectionLabel(body, "$cat  ·  $on/${group.size}")
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

    private fun addNewsSourceDialog(onAdded: () -> Unit = {}) {
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply {
            hint = "es. ilpost.it o indirizzo del feed"
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Aggiungi fonte")
            .setMessage("Scrivi l'indirizzo di un sito: trovo da solo il suo feed di notizie.")
            .setView(box)
            .setPositiveButton("Cerca") { _, _ ->
                val input = et.text?.toString()?.trim().orEmpty()
                if (input.isEmpty()) return@setPositiveButton
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
    private fun discoverHomeFeeds(onDone: () -> Unit) {
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

    // ======================= PDF viewer (pdf.js, served from assets) =======================

    private val pdfDir by lazy { java.io.File(cacheDir, "pdf").apply { mkdirs() } }

    private fun isPdfViewer(url: String?) = url != null && url.startsWith(MiaAi.ORIGIN + "/assets/pdf/viewer.html")

    private fun pdfName(url: String?): String =
        runCatching { Uri.parse(url).getQueryParameter("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PDF"

    private fun showPdf(file: java.io.File, name: String, src: String?) {
        val viewer = MiaAi.ORIGIN + "/assets/pdf/viewer.html?file=" + Uri.encode("/files/pdf/" + file.name) +
            "&name=" + Uri.encode(name) + (src?.let { "&src=" + Uri.encode(it) } ?: "")
        if (webTabs.getOrNull(webTabIndex)?.url?.let { it.startsWith("http") } == true) openNewTab(false)
        openInBrowser(viewer)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"

    private fun openPdfFromUri(uri: Uri, name: String? = null) {
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

    private fun openPdfFromUrl(url: String, userAgent: String?, name: String) {
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

    // ======================= Reader mode =======================

    data class ReaderDoc(val title: String, val site: String, val url: String, val image: String?, val text: String)

    private fun extractReader(onDoc: (ReaderDoc) -> Unit) {
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

    private fun openReaderMode(listen: Boolean = false) = extractReader { showReader(it, listen = listen) }

    private fun showReader(doc: ReaderDoc, saved: ReadLaterItem? = null, listen: Boolean = false) {
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
            else { speak(doc.title + ".\n" + doc.text, 1f); speaking = true; b.text = "⏹" }
        }
        barBtn("⋮", "Altro") { b ->
            val opts = mutableListOf("✨ Riassumi", "✨ Traduci in italiano", "Condividi")
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

    private fun translateText(title: String, url: String, text: String) {
        if (!aiReady()) return
        showAiSheet(
            title = "Traduzione",
            context = pageContext(title, url, text.take(16000)),
            firstQuestion = "Traduci in italiano il testo della pagina, mantenendo titoli e paragrafi. Scrivi solo la traduzione.",
            showFirstQuestion = false
        )
    }

    private fun translatePageWithAi() = extractReader { translateText(it.title, it.url, it.text) }

    // ======================= Read later =======================

    private val readLater by lazy { JsonStore.readLater(this) }

    private fun saveForLater(doc: ReaderDoc) {
        val list = readLater.load()
        list.removeAll { it.url == doc.url }
        list.add(0, ReadLaterItem(UUID.randomUUID().toString(), doc.url, doc.title, doc.site, doc.image, doc.text, System.currentTimeMillis()))
        io.execute { readLater.save(list) }
        toast("🔖 Salvato in Leggi dopo (anche offline)")
    }

    private fun saveCurrentForLater() = extractReader { saveForLater(it) }

    private fun saveNewsForLater(item: NewsItem) {
        toast("Salvo l'articolo…")
        Thread {
            val text = runCatching { news.articleText(item.link) }.getOrDefault("").ifBlank { item.summary }
            runOnUiThread { saveForLater(ReaderDoc(item.title, item.source, item.link, item.image, text)) }
        }.start()
    }

    private fun showReadLaterPage() {
        openPage("Leggi dopo", "Svuota letti", { dlg ->
            val list = readLater.load()
            if (list.none { it.read }) toast("Nessun articolo letto")
            else confirmClear("Eliminare gli articoli già letti?") { readLater.save(list.filter { !it.read }); dlg.dismiss(); showReadLaterPage() }
        }) { _, body, dialog ->
            fun render() {
                body.removeAllViews()
                val list = readLater.load()
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

    // ======================= Notes =======================

    private val notes by lazy { JsonStore.notes(this) }
    private var noteDictationTarget: EditText? = null
    private val noteSpeechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val et = noteDictationTarget ?: return@registerForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (r.resultCode == RESULT_OK && text.isNotEmpty()) {
            val cur = et.text?.toString().orEmpty()
            et.setText(if (cur.isBlank()) text.replaceFirstChar { it.uppercase() } else cur.trimEnd() + "\n" + text)
            et.setSelection(et.text.length)
        }
    }

    private fun showNotesPage() {
        openPage("Note", "Nuova", { _ -> editNote(null) }) { _, body, dialog ->
            fun render() {
                body.removeAllViews()
                val list = notes.load().sortedByDescending { it.time }
                if (list.isEmpty()) { emptyLabel(body, "Nessuna nota.\nTocca «Nuova» in alto: puoi anche dettarla."); return }
                list.forEach { n ->
                    val lines = n.text.trim().lines().filter { it.isNotBlank() }
                    listRow(body, lines.firstOrNull()?.take(80) ?: "Nota",
                        "${NewsAdapter.ago(n.time)}${if (lines.size > 1) " · " + lines.drop(1).joinToString(" ").take(80) else ""}",
                        onClick = { editNote(n) { render() } },
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

    private var notesRefresh: (() -> Unit)? = null

    private fun editNote(existing: Note?, onSaved: () -> Unit = { notesRefresh?.invoke() }) {
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

    private fun scanQr() {
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this).startScan()
                .addOnSuccessListener { b -> handleQr(b.rawValue.orEmpty(), b.url?.url) }
                .addOnFailureListener { toast("Scanner non disponibile: servono i Google Play Services aggiornati") }
        } catch (_: Throwable) {
            toast("Scanner non disponibile su questo telefono")
        }
    }

    private fun handleQr(raw: String, url: String?) {
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

    // ======================= Home tools row =======================

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPdfFromUri(uri)
    }

    private fun renderHomeTools() {
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
        chip("📷 QR") { scanQr() }
        chip("📄 PDF") { pdfPicker.launch(arrayOf("application/pdf")) }
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
        adapter.submit(apps, categories, searchInput.text?.toString().orEmpty(), repo.showMostUsed())
        findViewById<View>(R.id.emptyState).isVisible = apps.isEmpty()
    }

    private fun updateWebHint(q: String) {
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

    private fun runSearchFromBox() {
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

    private fun handleVoiceText(text: String) {
        toast("Hai detto: $text")
        val fallback = {
            val match = findAppByName(text, voice = true)
            when {
                match != null -> openApp(match)
                looksLikeUrl(text) -> openInBrowser(normalizeUrl(text))
                else -> openInBrowser(searchUrl(text))
            }
        }
        val words = text.trim().split(Regex("\\s+")).size
        val simple = findAppByName(text) != null || looksLikeUrl(text) || words <= 3
        if (repo.aiVoice() && !simple) interpretVoice(text, fallback) else fallback()
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
            if (item.locked) "Rimuovi blocco impronta" else "Blocca con impronta",
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
                6 -> MaterialAlertDialogBuilder(this).setMessage("Eliminare \"${item.name}\"?")
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

    /** Full-page settings (bottom-nav tab), grouped cards with large rows. */
    private fun renderSettings() {
        settingsList.removeAllViews()
        val engine = repo.getSearchEngine()
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        settingsPanel.setBackgroundColor(palette.bg)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        settingsList.addView(TextView(this).apply {
            text = "Impostazioni"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.text)
            setPadding(dp(4), dp(12), dp(4), dp(8))
        })
        var group: android.widget.LinearLayout? = null
        fun section(title: String) {
            settingsList.addView(TextView(this).apply {
                text = title
                setTextColor(accent)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.06f
                setPadding(dp(8), dp(20), dp(8), dp(8))
            })
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(palette.card)
                strokeWidth = dp(1)
                strokeColor = palette.stroke
            }
            val inner = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            card.addView(inner)
            settingsList.addView(card)
            group = inner
        }
        fun row(label: String, sub: String? = null, value: String? = null, danger: Boolean = false,
                switch: Boolean? = null, onClick: () -> Unit) {
            val g = group ?: return
            if (g.childCount > 0) g.addView(View(this).apply {
                setBackgroundColor(palette.stroke)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)
                ).apply { marginStart = dp(18) }
            })
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(18), dp(12), dp(14), dp(12))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(if (danger) ContextCompat.getColor(this@MainActivity, R.color.danger) else palette.text)
            })
            if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
                text = sub
                textSize = 14f
                setTextColor(palette.textMuted)
                setPadding(0, dp(3), 0, 0)
            })
            r.addView(box)
            if (value != null) r.addView(TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(accent)
                setPadding(dp(10), 0, dp(4), 0)
            })
            if (switch != null) {
                val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                    isChecked = switch
                    isClickable = false
                    isFocusable = false
                }
                r.addView(sw)
            } else if (!danger) r.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_web_forward)
                setColorFilter(palette.textMuted)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
            })
            r.setOnClickListener { onClick() }
            g.addView(r)
        }

        section("GENERALE")
        row("Aggiungi web app", "Nome, indirizzo e categoria") { showAddEditDialog(null) }
        row("Browser predefinito", "Apri qui i link di WhatsApp, email…", value = if (isDefaultBrowser()) "Sì" else "No") { requestDefaultBrowser() }
        row("Motore di ricerca", value = engine.label) { showSearchEnginePicker() }
        row("Suggerimenti di ricerca", "Mentre scrivi l'indirizzo (dal motore scelto)", switch = repo.searchSuggestions()) {
            repo.setSearchSuggestions(!repo.searchSuggestions()); renderSettings()
        }
        row("Nascondi barre scorrendo", "Più spazio, ma lo scorrimento è meno fluido", switch = repo.hideBarsOnScroll()) {
            repo.setHideBarsOnScroll(!repo.hideBarsOnScroll()); hideBarsOn = repo.hideBarsOnScroll(); renderSettings()
        }
        row("Dimensione testo", value = "${repo.textZoom()}%") { showTextZoomDialog() }
        row("Cronologia", "Tutte le pagine visitate, con ricerca") { showHistoryPage() }
        row("Download", "File scaricati") { showDownloadsPage() }
        val themeLabel = when (repo.getThemeMode()) {
            "light" -> "Chiaro"
            "system" -> "Sistema"
            else -> "Scuro"
        }
        row("Tema", value = themeLabel) { showThemePicker() }
        val accentLabel = when (repo.getAccent()) {
            "amber" -> "Ambra"
            "green" -> "Verde"
            else -> "Blu"
        }
        row("Colore", value = accentLabel) { showAccentPicker() }
        row("Siti in modalità scura", "Scurisce i siti chiari quando il tema è scuro", switch = repo.darkSites()) {
            repo.setDarkSites(!repo.darkSites())
            applyDarkSites()
            if (browserHasPage) webView.reload()
            renderSettings()
        }

        section("INTELLIGENZA ARTIFICIALE")
        val aiRowSub = "Usa provider, chiave e modello di Mia AI"
        row("Assistente AI", aiRowSub, value = "Configura") { openMiaAi() }
        row("Comandi vocali con AI", "Es. «apri YouTube e cerca ricette di pasta»", switch = repo.aiVoice()) {
            repo.setAiVoice(!repo.aiVoice()); renderSettings()
        }
        row("Cosa sa fare", "Riassumi, chiedi alla pagina, spiega il testo selezionato…") {
            MaterialAlertDialogBuilder(this).setTitle("✨ AI nel launcher")
                .setMessage(
                    "• Menu ⋮ → Riassumi pagina\n" +
                    "• Menu ⋮ → Chiedi alla pagina\n" +
                    "• Scrivi una domanda nella ricerca → «Chiedi all'AI»\n" +
                    "• Seleziona del testo in una pagina → «Chiedi all'AI»\n" +
                    "• Microfono: comandi lunghi, es. «apri Amazon e cerca cuffie bluetooth»\n\n" +
                    "Il testo viene inviato al provider scelto in Mia AI solo quando usi queste funzioni."
                )
                .setPositiveButton("OK", null).show()
        }

        section("STRUMENTI")
        row("📝 Note", "Scrivi o detta, riordina con l'AI") { showNotesPage() }
        row("🔖 Leggi dopo", "Articoli salvati, leggibili offline") { showReadLaterPage() }
        row("📷 Scansiona QR code") { scanQr() }
        row("📄 Apri un PDF", "Dal telefono o da Drive") { pdfPicker.launch(arrayOf("application/pdf")) }

        section("NOTIZIE")
        row("Fonti notizie", "${news.enabledIds().size} attive su ${news.allSources().size}") { showNewsSources() }
        row("Aggiungi una fonte", "Da qualsiasi sito con notizie") { addNewsSourceDialog() }

        section("APP INTEGRATE")
        val miaOnHome = apps.any { MiaAi.isMiaUrl(it.url) }
        row("Mia AI", "Chat AI personale: Gemini, OpenRouter, Groq, Ollama…",
            value = if (miaOnHome) "Apri" else "Aggiungi") {
            if (miaOnHome) openInBrowser(MiaAi.URL) else { addMiaToHome(); renderSettings() }
        }

        section("PRIVACY E SICUREZZA")
        row("Blocca pubblicità e tracker", "Pagine più veloci, meno dati consumati", switch = repo.blockAds()) {
            repo.setBlockAds(!repo.blockAds()); blockAdsOn = repo.blockAds()
            if (browserHasPage) webView.reload()
            renderSettings()
        }
        val block3p = repo.blockThirdPartyCookies()
        row("Blocca cookie di terze parti", "Disattiva solo se un login non funziona", switch = block3p) {
            val next = !block3p
            repo.setBlockThirdPartyCookies(next)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !next)
            renderSettings()
        }
        row("Password e compilazione", "Autofill di Android") { showPasswordInfo() }
        row("Cancella cronologia", "Recenti e cronologia del browser") { confirmClear("Cancellare cronologia e recenti?") { clearHistoryAndRecents(); toast("Cronologia cancellata") } }
        row("Cancella cookie", "Ti disconnette dai siti") { confirmClear("Cancellare tutti i cookie?") { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); toast("Cookie cancellati") } }
        row("Cancella cache") { confirmClear("Cancellare la cache?") { webView.clearCache(true); toast("Cache cancellata") } }
        row("Cancella tutti i dati", "Recenti, cronologia, cookie, cache", danger = true) { confirmClear("Cancellare tutti i dati di navigazione?") { clearAllBrowsingData(); toast("Dati cancellati") } }

        section("BACKUP")
        val autoOn = repo.getAutoBackupUri() != null
        row("Backup", if (autoOn) "Automatico attivo" else "Su file, Drive o condivisione") { showBackupMenu() }
        row("Importa preferiti da Chrome", "Da un file bookmarks.html") { showBookmarkImportHelp() }
        row("Ripristina", "Da un file di backup") {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        section("HOME")
        row("Mostra \"Più usate\"", "In cima alla home, quando hai almeno 6 app", switch = repo.showMostUsed()) {
            repo.setShowMostUsed(!repo.showMostUsed()); refreshList(); renderSettings()
        }
        row("Sposta le icone", "Tieni premuta un'app → Sposta") { toast("Tieni premuta un'app e scegli Sposta") }
        row("Microfono", "Come attivare il permesso voce") {
            MaterialAlertDialogBuilder(this).setTitle("Microfono")
                .setMessage("Voce nativa Android.\nImpostazioni → App → Web Launcher → Microfono → Consenti")
                .setPositiveButton("OK", null).show()
        }
        row("Svuota home", "Elimina tutte le app salvate", danger = true) {
            MaterialAlertDialogBuilder(this).setMessage("Eliminare tutte le app dalla home? Puoi recuperarle solo da un backup.")
                .setPositiveButton("Svuota") { _, _ ->
                    apps.forEach { a -> a.iconPath?.let { runCatching { java.io.File(it).delete() } } }
                    apps = repo.resetDefaults(); categories = repo.loadCategories(); autoBackupNow(); refreshList(); toast("Home svuotata")
                }.setNegativeButton("Annulla", null).show()
        }

        settingsList.addView(TextView(this).apply {
            text = "Web Launcher $appVersion"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(palette.textMuted)
            setPadding(0, dp(24), 0, dp(8))
        })
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
        repo.clearHistory()
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
        renderTabStrip()
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
        val json = backupJson()
        io.execute {
            try {
                val uri = Uri.parse(raw)
                (contentResolver.openOutputStream(uri, "wt") ?: contentResolver.openOutputStream(uri))
                    ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: repo.setAutoBackupUri(null)
            } catch (_: Exception) { repo.setAutoBackupUri(null) }
        }
    }

    private fun recordVisit(title: String, url: String) {
        if (isPdfViewer(url)) return
        val key = "$url|$title"
        if (key == lastRecorded) return
        lastRecorded = key
        io.execute {
            repo.addRecent(title, url)
            repo.addHistory(title, url)
        }
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
                if (currentTab == TAB_SETTINGS) renderSettings()
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
        private const val TAB_SETTINGS = 2
        private const val STATE_TAB = "current_tab"
        private const val TAB_NEWS = 3
        private const val NEWS_ALL = "Tutte"
        private val SUGGEST_TAG = Any()
        private const val MENU_AI = 0x5A1
        private const val AI_SYSTEM =
            "Sei l'assistente di un browser per smartphone. Rispondi in italiano, in modo chiaro e conciso, " +
                "con elenchi puntati quando aiutano. Niente preamboli."
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
