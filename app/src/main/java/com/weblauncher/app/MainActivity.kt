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
            .addPathHandler("/files/wallet/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, walletDir))
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
    private val audio by lazy { AudioRepository(this) }
    private var controller: androidx.media3.session.MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    private var audioTab = "podcast"
    private var episodes: List<Episode> = emptyList()
    private val radioCache = HashMap<String, List<RadioStation>>()

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
        syncPrefs.edit().remove("hash").apply()
        autoBackupNow()
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
        CrashReporter.install(this)
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
        setupAudio()
        renderHomeTools()
        renderWeather()
        uiHandler.postDelayed({ runCatching { autoCleanup() } }, 8000)
        CrashReporter.pending(this)?.let { report -> uiHandler.postDelayed({ showCrashReport(report) }, 600) }
        setupNav()
        setupBack()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateWebHint(s?.toString().orEmpty())
                updateCalcHint(s?.toString().orEmpty())
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
            TAB_AUDIO -> TAB_AUDIO
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
        setupYouTube()
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
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (request?.isForMainFrame == true && !guardUrl(url) { view?.loadUrl(url) }) return true
                    return false
                }
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
                if (YouTube.isYouTube(url) && !ytDocStartSupported()) view?.evaluateJavascript(YouTube.script(ytConfig()), null)
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
                    trackUsage()
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
                if (req.resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    // protected video/music (Netflix, Prime Video, Disney+, Spotify, RaiPlay): allowed like in Chrome
                    req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    return
                }
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
        findViewById<View>(R.id.navAudio).setOnClickListener { showTab(TAB_AUDIO) }
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
                    currentTab == TAB_AUDIO -> showTab(TAB_HOME)
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
        findViewById<View>(R.id.audioPanel).isVisible = tab == TAB_AUDIO
        if (tab == TAB_AUDIO) onAudioShown()
        if (tab == TAB_HOME) refreshWeather()
        if (::webView.isInitialized) trackUsage()
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
        findViewById<ImageView>(R.id.iconAudio).setColorFilter(if (tab == TAB_AUDIO) accent else muted)
        findViewById<TextView>(R.id.labelAudio).setTextColor(if (tab == TAB_AUDIO) accent else muted)
        findViewById<TextView>(R.id.labelNews).setTextColor(if (tab == TAB_NEWS) accent else muted)
        iconSettings.setColorFilter(if (tab == TAB_SETTINGS) accent else muted)
        labelSettings.setTextColor(if (tab == TAB_SETTINGS) accent else muted)
    }

    private fun openInBrowser(url: String) {
        if (!guardUrl(url) { openInBrowser(url) }) return
        if (webTabs.isEmpty()) webTabs.add(WebTab())
        val tab = webTabs[webTabIndex]
        tab.url = url
        tab.state = null
        appForUrl(url)?.let { tab.desktop = it.desktop }
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

    override fun onResume() {
        super.onResume()
        hasWindowFocusOrResumed = true
        if (::webView.isInitialized) trackUsage()
        checkSyncFromOtherDevice()
        if (currentTab == TAB_HOME) refreshWeather()
    }

    override fun onPause() {
        super.onPause()
        hasWindowFocusOrResumed = false
        flushUsage()
        usageHost = null
        autoBackupNow()
        persistTabs()
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) { showTab(TAB_AUDIO); showPlayerSheet(); return }
        openShortcut(intent)
    }

    private fun openShortcut(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_WIDGET)) {
            "search" -> {
                intent.removeExtra(EXTRA_WIDGET)
                showTab(TAB_HOME)
                searchInput.requestFocus()
                searchInput.post {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                return
            }
            "voice" -> { intent.removeExtra(EXTRA_WIDGET); requestMicAndListen(); return }
            "news" -> { intent.removeExtra(EXTRA_WIDGET); showTab(TAB_NEWS); return }
            "reminders" -> { intent.removeExtra(EXTRA_WIDGET); showRemindersPage(); return }
        }
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
        if (YouTube.isYouTube(webView.url)) {
            item("✨ Riassumi video") { summarizeVideo() }
            item("✨ Chiedi al video") { askVideo() }
            item("🎬 Finestrella") { enterVideoPip() }
            item("⏩ Velocità (${ytPrefs.getFloat("speed", 1f)}x)") { ytSpeedDialog() }
            item("▶ Segui il canale") { followCurrentChannel() }
            item("⚙ Opzioni YouTube") { ytOptionsDialog() }
        }
        if (hostOf(webView.url) == "mail.google.com") item("📧 Riassumi email") { summarizeEmail() }
        item("✨ Riassumi pagina", hasPage) { summarizePage() }
        item("✨ Chiedi alla pagina", hasPage) { askThePage() }
        item("📖 Modalità lettura", hasPage) { openReaderMode() }
        item("🔊 Ascolta la pagina", hasPage) { openReaderMode(listen = true) }
        item("🔖 Leggi dopo", hasPage) { saveCurrentForLater() }
        item("✨ Traduci con AI", hasPage) { translatePageWithAi() }
        item("✨ Scrivi con AI", hasPage) { writeWithAi() }
        item("🛒 Confronta prezzi", hasPage) { comparePrices() }
        item("Cronologia") { showHistoryPage() }
        item("Download") { showDownloadsPage() }
        item("Trova nella pagina", hasPage) { showFindDialog() }
        item("Traduci con Google", hasPage) { translateCurrentPage() }
        item("📷 Scansiona QR code") { scanQr() }
        item("📸 Screenshot", hasPage) { screenshotPage() }
        item("📄 Salva come PDF", hasPage) { savePageAsPdf() }
        item("🕵️ Apri nel profilo 2", hasPage) {
            startActivity(Intent(this, IsolatedBrowserActivity::class.java).putExtra(IsolatedBrowserActivity.EXTRA_URL, webView.url))
        }
        item("Dimensione testo (${repo.textZoom()}%)") { showTextZoomDialog() }
        item("Sito desktop", hasPage, tab.desktop) {
            tab.desktop = !tab.desktop
            applyTabChrome()
            webView.reload()
            val app = appForUrl(webView.url)
            if (app != null && app.desktop != tab.desktop) {
                MaterialAlertDialogBuilder(this).setMessage("Usare sempre la versione ${if (tab.desktop) "desktop" else "mobile"} per ${app.name}?")
                    .setPositiveButton("Sì") { _, _ -> app.desktop = tab.desktop; persistApps() }
                    .setNegativeButton("Solo ora", null).show()
            }
        }
        item("🎫 Salva nei biglietti", hasPage) { saveCurrentToWallet() }
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
        val host = hostOf(normalizeUrl(item.url))
        if (host != null && limitFor(host) > 0) {
            val used = usageMap(today)[host] ?: 0L
            val allowed = (limitFor(host) + usagePrefs.getInt("extra_${today}_$host", 0)) * 60_000L
            if (used >= allowed) {
                MaterialAlertDialogBuilder(this).setTitle("⏱ Limite raggiunto")
                    .setMessage("Oggi hai già usato ${item.name} per ${used / 60_000} minuti (limite ${limitFor(host)} min).")
                    .setPositiveButton("Ok") { _, _ -> }
                    .setNegativeButton("Apri per 15 min") { _, _ ->
                        usagePrefs.edit().putInt("extra_${today}_$host", usagePrefs.getInt("extra_${today}_$host", 0) + 15).apply()
                        noteUse(item); openInBrowser(normalizeUrl(item.url))
                    }.show()
                return
            }
        }
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
        controllerFuture?.let { androidx.media3.session.MediaController.releaseFuture(it) }
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
        mode.menu.add(android.view.Menu.NONE, MENU_AI + 1, 1, "✨ Riscrivi").setOnMenuItemClickListener {
            mode.finish(); writeWithAi(); true
        }
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

    private fun showCrashReport(report: String) {
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

    private fun renderNews() {
        try { renderNewsUnsafe() } catch (e: Exception) {
            CrashReporter.log(this, "notizie", e)
            toast("Errore nelle notizie: rapporto salvato")
        }
    }

    private fun renderNewsUnsafe() {
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
                    this@MainActivity.findViewById<RecyclerView>(R.id.newsList).scrollToPosition(0)
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
        chip("🎧 Podcast") { audioTab = "podcast"; showTab(TAB_AUDIO) }
        chip("📻 Radio") { audioTab = "radio"; showTab(TAB_AUDIO) }
        chip("📷 QR") { scanQr() }
        chip("💳 Carte") { showCardsPage() }
        chip("🎫 Biglietti") { showWalletPage() }
        chip("⏰ Promemoria") { showRemindersPage() }
        chip("📦 Pacchi") { showParcelsPage() }
        chip("⏱ Tempo") { showUsagePage() }
        chip("📄 PDF") { pdfPicker.launch(arrayOf("application/pdf")) }
    }

    // ======================= Podcasts & radio =======================

    private fun setupAudio() {
        val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : androidx.media3.common.Player.Listener {
                override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                    updateMiniPlayer()
                    playerSheetUpdate?.invoke()
                }
            })
            updateMiniPlayer()
        }, ContextCompat.getMainExecutor(this))
        val palette = AppRepository.palette(this)
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.miniPlayer).apply {
            setCardBackgroundColor(palette.elevated); strokeColor = palette.stroke
            setOnClickListener { showPlayerSheet() }
        }
        findViewById<TextView>(R.id.miniTitle).setTextColor(palette.text)
        findViewById<TextView>(R.id.miniSub).setTextColor(palette.textMuted)
        findViewById<android.widget.ImageButton>(R.id.miniPlay).apply {
            setColorFilter(palette.text)
            setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        }
        findViewById<android.widget.ImageButton>(R.id.miniClose).apply {
            setColorFilter(palette.textMuted)
            setOnClickListener { controller?.run { stop(); clearMediaItems() }; updateMiniPlayer() }
        }
        findViewById<View>(R.id.audioPanel).setBackgroundColor(palette.bg)
        findViewById<TextView>(R.id.audioHeading).setTextColor(palette.text)
        findViewById<View>(R.id.btnAudioSearch).setOnClickListener {
            when (audioTab) { "radio" -> showRadioSearch(); "video" -> addChannelDialog(); else -> showPodcastSearch() }
        }
        val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
        refresh.setOnRefreshListener {
            when (audioTab) {
                "radio" -> { radioCache.clear(); refresh.isRefreshing = false; renderAudio() }
                "video" -> refreshYtVideos()
                else -> refreshEpisodes()
            }
        }
    }

    private fun updateMiniPlayer() {
        val c = controller
        val card = findViewById<View>(R.id.miniPlayer)
        val item = c?.currentMediaItem
        val show = c != null && item != null && c.playbackState != androidx.media3.common.Player.STATE_IDLE
        card.isVisible = show
        if (!show) return
        val md = item!!.mediaMetadata
        findViewById<TextView>(R.id.miniTitle).text = md.title ?: ""
        findViewById<TextView>(R.id.miniSub).text = when {
            c!!.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> "Caricamento…"
            else -> md.artist ?: ""
        }
        findViewById<android.widget.ImageButton>(R.id.miniPlay).setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val art = findViewById<ImageView>(R.id.miniArt)
        if (art.tag != md.artworkUri) {
            art.tag = md.artworkUri
            art.load(md.artworkUri) { transformations(coil.transform.RoundedCornersTransformation(16f)); error(R.drawable.ic_nav_audio) }
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("wl_audio", MODE_PRIVATE).getBoolean("asked_notif", false)
        ) {
            getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun playMedia(id: String, uri: String, title: String, sub: String, art: String?, start: Long) {
        val c = controller ?: run { toast("Lettore non pronto, riprova tra un attimo"); return }
        ensureNotificationPermission()
        val md = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title).setArtist(sub).setIsPlayable(true)
            .apply { art?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = androidx.media3.common.MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(md).build()
        c.setMediaItem(item, start)
        c.setPlaybackSpeed(if (id.startsWith("radio:")) 1f else getSharedPreferences("wl_audio", MODE_PRIVATE).getFloat("speed", 1f))
        c.prepare()
        c.play()
        updateMiniPlayer()
    }

    private fun playEpisode(e: Episode) {
        val local = audio.localFile(e)
        val uri = if (audio.isDownloaded(e) && !isDownloading(e)) Uri.fromFile(local).toString() else e.audioUrl
        val start = if (AudioStore.isFinished(this, e.id)) 0L else AudioStore.position(this, e.id)
        playMedia(e.id, uri, e.title, e.podcastTitle, e.artwork, start)
    }

    private fun playRadio(s: RadioStation) {
        playMedia("radio:" + s.id, s.url, s.name, "Radio" + (s.state.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), s.favicon, 0L)
    }

    private fun isDownloading(e: Episode): Boolean {
        val dmId = getSharedPreferences("wl_audio", MODE_PRIVATE).getLong("dl_" + e.id, -1L)
        if (dmId < 0) return false
        val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
        val status = runCatching {
            dm.query(android.app.DownloadManager.Query().setFilterById(dmId))?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)) else -1
            }
        }.getOrNull() ?: -1
        val running = status == android.app.DownloadManager.STATUS_RUNNING || status == android.app.DownloadManager.STATUS_PENDING ||
            status == android.app.DownloadManager.STATUS_PAUSED
        if (!running) getSharedPreferences("wl_audio", MODE_PRIVATE).edit().remove("dl_" + e.id).apply()
        return running
    }

    private fun downloadEpisode(e: Episode) {
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val req = android.app.DownloadManager.Request(Uri.parse(e.audioUrl))
                .setTitle(e.title)
                .setDescription(e.podcastTitle)
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_PODCASTS, audio.localFile(e).name)
            val id = dm.enqueue(req)
            getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putLong("dl_" + e.id, id).apply()
            toast("Download avviato: lo trovi anche offline")
        } catch (_: Exception) { toast("Download non riuscito") }
    }

    private fun onAudioShown() {
        if (audioTab == "podcast" && episodes.isEmpty()) episodes = audio.cachedEpisodes()
        renderAudio()
        val last = getSharedPreferences("wl_audio", MODE_PRIVATE).getLong("refreshed", 0L)
        if (audioTab == "podcast" && audio.subscriptions().isNotEmpty() && System.currentTimeMillis() - last > 3600_000L) refreshEpisodes()
    }

    private fun refreshEpisodes() {
        val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
        refresh.isRefreshing = true
        Thread {
            val list = runCatching { audio.refreshEpisodes() }.getOrDefault(emptyList())
            runOnUiThread {
                refresh.isRefreshing = false
                episodes = list
                getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putLong("refreshed", System.currentTimeMillis()).apply()
                if (currentTab == TAB_AUDIO) renderAudio()
            }
        }.start()
    }

    private fun fmtDur(sec: Long): String = when {
        sec <= 0 -> ""
        sec >= 3600 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        else -> "${(sec / 60).coerceAtLeast(1)} min"
    }

    /** Row with artwork, two lines and optional trailing buttons. */
    private fun mediaRow(
        parent: android.widget.LinearLayout, art: String?, title: String, sub: String, progress: String? = null,
        trailing: List<Pair<Int, () -> Unit>> = emptyList(), onLong: (() -> Unit)? = null, onClick: () -> Unit
    ) {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), 0, dp(8))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        row.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(palette.iconTile) }
            load(art) { transformations(coil.transform.RoundedCornersTransformation(24f)); error(R.drawable.ic_nav_audio) }
        })
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 15f; setTextColor(palette.text); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = sub; textSize = 12.5f; setTextColor(palette.textMuted); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        })
        if (!progress.isNullOrBlank()) box.addView(TextView(this).apply {
            text = progress; textSize = 12f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
        })
        row.addView(box)
        trailing.forEach { (icon, action) ->
            row.addView(android.widget.ImageButton(this).apply {
                setImageResource(icon); setColorFilter(palette.text); background = null
                setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { action() }
            })
        }
        parent.addView(row)
    }

    private fun episodeProgress(e: Episode): String {
        if (AudioStore.isFinished(this, e.id)) return "✓ Ascoltato"
        val pos = AudioStore.position(this, e.id)
        val dur = AudioStore.duration(this, e.id).takeIf { it > 0 } ?: (e.durationSec * 1000)
        val dl = if (isDownloading(e)) "⬇ in download" else if (audio.isDownloaded(e)) "⬇ offline" else ""
        val left = if (pos > 30_000 && dur > pos) "⏵ ${fmtDur((dur - pos) / 1000)} rimasti" else ""
        return listOf(left, dl).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun episodeRow(parent: android.widget.LinearLayout, e: Episode, showPodcast: Boolean = true) {
        val sub = listOfNotNull(
            e.podcastTitle.takeIf { showPodcast && it.isNotBlank() },
            e.time.takeIf { it > 0 }?.let { NewsAdapter.ago(it) },
            fmtDur(e.durationSec).ifBlank { null }
        ).joinToString(" · ")
        mediaRow(parent, e.artwork, e.title, sub, episodeProgress(e),
            trailing = listOf(R.drawable.ic_play to { playEpisode(e) }),
            onLong = { episodeMenu(e) }) { playEpisode(e) }
    }

    private fun episodeMenu(e: Episode) {
        val downloaded = audio.isDownloaded(e) && !isDownloading(e)
        val opts = arrayOf(
            "▶ Riproduci",
            if (downloaded) "Elimina download" else "⬇ Scarica per ascoltare offline",
            if (AudioStore.isFinished(this, e.id)) "Segna come da ascoltare" else "Segna come ascoltato",
            "Descrizione", "Condividi"
        )
        MaterialAlertDialogBuilder(this).setTitle(e.title).setItems(opts) { _, w ->
            when (w) {
                0 -> playEpisode(e)
                1 -> if (downloaded) { audio.localFile(e).delete(); toast("Download eliminato"); renderAudio() } else downloadEpisode(e)
                2 -> {
                    if (AudioStore.isFinished(this, e.id)) getSharedPreferences("wl_audio", MODE_PRIVATE).edit().remove("done_" + e.id).apply()
                    else AudioStore.markFinished(this, e.id)
                    renderAudio()
                }
                3 -> MaterialAlertDialogBuilder(this).setTitle(e.title).setMessage(e.description.ifBlank { "Nessuna descrizione" })
                    .setPositiveButton("OK", null).show()
                4 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${e.podcastTitle} — ${e.title}\n${e.audioUrl}")
                }, "Condividi episodio"))
            }
        }.show()
    }

    private fun renderAudio() {
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tabs = findViewById<android.widget.LinearLayout>(R.id.audioTabs)
        tabs.removeAllViews()
        listOf("podcast" to "🎙 Podcast", "radio" to "📻 Radio", "video" to "▶ Video").forEach { (key, label) ->
            val active = key == audioTab
            tabs.addView(TextView(this).apply {
                text = label; textSize = 14f
                setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (active) android.graphics.Color.WHITE else palette.text)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18 * d; setColor(if (active) accent else palette.card)
                    setStroke(d.toInt().coerceAtLeast(1), if (active) accent else palette.stroke)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { audioTab = key; onAudioShown() }
            })
        }
        val list = findViewById<android.widget.LinearLayout>(R.id.audioList)
        list.removeAllViews()
        if (audioTab == "radio") { renderRadio(list); return }
        if (audioTab == "video") { renderVideos(list); return }

        val subs = audio.subscriptions()
        if (subs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nessun podcast seguito.\nCerca per nome: è gratis e senza account."
                textSize = 15f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
                setPadding(dp(20), dp(30), dp(20), dp(16))
            })
            sectionLabel(list, "PROVA A CERCARE")
            val flow = com.google.android.material.chip.ChipGroup(this).apply { setPadding(dp(6), 0, dp(6), 0) }
            listOf("Il Post", "Rai Radio 3", "Storie", "Geopolitica", "Tecnologia", "Cucina", "Storia", "Calcio", "Economia", "Wired")
                .forEach { term ->
                    flow.addView(com.google.android.material.chip.Chip(this).apply {
                        text = term
                        setOnClickListener { showPodcastSearch(term) }
                    })
                }
            list.addView(flow)
            return
        }
        sectionLabel(list, "I MIEI PODCAST")
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        subs.forEach { p ->
            val cell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(92), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { showPodcastDetail(p) }
            }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(92), dp(92))
                load(p.artwork) { transformations(coil.transform.RoundedCornersTransformation(28f)); error(R.drawable.ic_nav_audio) }
            })
            cell.addView(TextView(this).apply {
                text = p.title; textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.text); setPadding(0, dp(4), 0, 0)
            })
            row.addView(cell)
        }
        list.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(6), 0, 0, 0)
            addView(row)
        })
        sectionLabel(list, "NUOVI EPISODI")
        if (episodes.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Trascina in giù per caricare gli episodi"; textSize = 14f; setTextColor(palette.textMuted)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        }
        episodes.take(60).forEach { episodeRow(list, it) }
    }

    private fun renderRadio(list: android.widget.LinearLayout) {
        val favs = audio.favoriteStations()
        fun stationRows(target: android.widget.LinearLayout, stations: List<RadioStation>) {
            stations.forEach { s ->
                val fav = favs.any { it.id == s.id }
                val sub = listOfNotNull(
                    s.state.takeIf { it.isNotBlank() },
                    s.tags.split(",").take(3).joinToString(", ").takeIf { it.isNotBlank() },
                    s.bitrate.takeIf { it > 0 }?.let { "$it kbps" }
                ).joinToString(" · ")
                mediaRow(target, s.favicon, s.name, sub, null,
                    trailing = listOf((if (fav) R.drawable.ic_web_star_filled else R.drawable.ic_web_star) to {
                        val now = audio.toggleFavorite(s)
                        toast(if (now) "Aggiunta alle preferite" else "Tolta dalle preferite")
                        renderAudio()
                    })) { playRadio(s) }
            }
        }
        if (favs.isNotEmpty()) { sectionLabel(list, "PREFERITE"); stationRows(list, favs) }
        val sections = listOf("Nazionali" to listOf("__IT__")) + AudioRepository.REGIONS
        sections.forEach { (label, names) ->
            sectionLabel(list, label.uppercase())
            val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            list.addView(box)
            val cached = radioCache[label]
            if (cached != null) {
                if (cached.isEmpty()) box.addView(TextView(this).apply {
                    text = "Nessuna stazione trovata"; textSize = 13f
                    setTextColor(AppRepository.palette(this@MainActivity).textMuted); setPadding(20, 10, 10, 10)
                })
                stationRows(box, cached.take(if (label == "Nazionali") 25 else 15))
            } else {
                box.addView(TextView(this).apply {
                    text = "Caricamento…"; textSize = 13f
                    setTextColor(AppRepository.palette(this@MainActivity).textMuted); setPadding(20, 10, 10, 10)
                })
                Thread {
                    val res = runCatching {
                        if (names.first() == "__IT__") audio.nationalStations() else audio.regionStations(names)
                    }.getOrDefault(emptyList())
                    runOnUiThread {
                        radioCache[label] = res
                        if (currentTab == TAB_AUDIO && audioTab == "radio") renderAudio()
                    }
                }.start()
            }
        }
    }

    private fun searchField(root: android.widget.LinearLayout, hint: String, initial: String?, onSearch: (String) -> Unit): EditText {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val et = EditText(this).apply {
            this.hint = hint
            setText(initial.orEmpty())
            setSingleLine(); textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d; setColor(palette.iconTile); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())
                .apply { setMargins((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt()) }
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    val q = v.text?.toString()?.trim().orEmpty()
                    if (q.isNotEmpty()) {
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                        onSearch(q)
                    }
                    true
                } else false
            }
        }
        root.addView(et, 1)
        return et
    }

    private fun showPodcastSearch(initial: String? = null) {
        openPage("Cerca podcast") { root, body, _ ->
            fun run(q: String) {
                body.removeAllViews()
                emptyLabel(body, "Cerco «$q»…")
                Thread {
                    val res = runCatching { audio.search(q) }.getOrNull()
                    runOnUiThread {
                        body.removeAllViews()
                        if (res == null) { emptyLabel(body, "Ricerca non riuscita: controlla la connessione"); return@runOnUiThread }
                        if (res.isEmpty()) { emptyLabel(body, "Nessun podcast trovato"); return@runOnUiThread }
                        res.forEach { p ->
                            mediaRow(body, p.artwork, p.title, p.author, if (audio.isSubscribed(p.feedUrl)) "✓ Lo segui" else null) { showPodcastDetail(p) }
                        }
                    }
                }.start()
            }
            val et = searchField(root, "Nome del podcast o argomento", initial) { run(it) }
            if (initial != null) run(initial) else { emptyLabel(body, "Catalogo gratuito di Apple Podcasts:\nnon serve un account."); et.requestFocus() }
        }
    }

    private fun showPodcastDetail(p: Podcast) {
        val subscribed = audio.isSubscribed(p.feedUrl)
        openPage(p.title.take(28), if (subscribed) "Non seguire" else "Segui", { dlg ->
            if (audio.isSubscribed(p.feedUrl)) { audio.unsubscribe(p.feedUrl); episodes = audio.cachedEpisodes(); toast("Non segui più ${p.title}") }
            else { audio.subscribe(p); toast("Ora segui ${p.title}"); refreshEpisodes() }
            dlg.dismiss()
            if (currentTab == TAB_AUDIO) renderAudio()
        }) { _, body, _ ->
            val d = resources.displayMetrics.density
            body.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.LinearLayout.LayoutParams((160 * d).toInt(), (160 * d).toInt()).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = (6 * d).toInt()
                }
                load(p.artwork) { transformations(coil.transform.RoundedCornersTransformation(40f)); error(R.drawable.ic_nav_audio) }
            })
            body.addView(TextView(this).apply {
                text = p.author; textSize = 14f; gravity = android.view.Gravity.CENTER
                setTextColor(AppRepository.palette(this@MainActivity).textMuted); setPadding(0, (8 * d).toInt(), 0, (6 * d).toInt())
            })
            val listBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            body.addView(listBox)
            emptyLabel(listBox, "Carico gli episodi…")
            Thread {
                val eps = runCatching { audio.episodes(p.feedUrl) }.getOrNull()
                runOnUiThread {
                    listBox.removeAllViews()
                    if (eps.isNullOrEmpty()) { emptyLabel(listBox, "Nessun episodio disponibile"); return@runOnUiThread }
                    sectionLabel(listBox, "EPISODI · ${eps.size}")
                    eps.take(100).forEach { episodeRow(listBox, it.copy(artwork = it.artwork ?: p.artwork), showPodcast = false) }
                }
            }.start()
        }
    }

    private fun showRadioSearch() {
        openPage("Cerca radio") { root, body, _ ->
            val et = searchField(root, "Nome della radio, es. RTL, Deejay, Rai", null) { q ->
                body.removeAllViews()
                emptyLabel(body, "Cerco «$q»…")
                Thread {
                    val res = runCatching { audio.searchStations(q) }.getOrDefault(emptyList())
                    runOnUiThread {
                        body.removeAllViews()
                        if (res.isEmpty()) { emptyLabel(body, "Nessuna radio trovata"); return@runOnUiThread }
                        res.forEach { s ->
                            mediaRow(body, s.favicon, s.name, listOf(s.state, s.tags.split(",").take(2).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                                trailing = listOf(R.drawable.ic_web_star to {
                                    toast(if (audio.toggleFavorite(s)) "Aggiunta alle preferite" else "Tolta dalle preferite")
                                })) { playRadio(s) }
                        }
                    }
                }.start()
            }
            emptyLabel(body, "Catalogo libero Radio Browser,\nsenza account.")
            et.requestFocus()
        }
    }

    private var playerSheetUpdate: (() -> Unit)? = null
    private var sleepAt = 0L
    private val sleepRunnable = Runnable { controller?.pause(); sleepAt = 0L; toast("Timer: riproduzione in pausa") }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun showPlayerSheet() {
        val c = controller ?: return
        if (c.currentMediaItem == null) return
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(230), dp(230)).apply { bottomMargin = dp(16) }
        }
        val title = TextView(this).apply {
            textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
            gravity = android.view.Gravity.CENTER; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply { textSize = 14f; setTextColor(palette.textMuted); gravity = android.view.Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) }
        val seek = android.widget.SeekBar(this).apply {
            max = 1000
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        val times = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val tPos = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted); layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
        val tDur = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted) }
        times.addView(tPos); times.addView(tDur)
        val controls = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 16f; setTextColor(palette.text); gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { onClick() }
        }
        val back = textBtn("↺ 15") { c.seekBack() }
        val play = android.widget.ImageButton(this).apply {
            setColorFilter(android.graphics.Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(18), 0, dp(18), 0) }
            setOnClickListener { if (c.isPlaying) c.pause() else c.play() }
        }
        val fwd = textBtn("30 ↻") { c.seekForward() }
        controls.addView(back); controls.addView(play); controls.addView(fwd)
        val extras = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        fun chip(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(accent); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) }
            setOnClickListener { onClick(this) }
            extras.addView(this)
        }
        val speeds = floatArrayOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
        val speedChip = chip("1x") { v ->
            val cur = c.playbackParameters.speed
            val next = speeds.firstOrNull { it > cur + 0.01f } ?: speeds[0]
            c.setPlaybackSpeed(next)
            getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putFloat("speed", next).apply()
            v.text = (if (next % 1f == 0f) next.toInt().toString() else next.toString()) + "x"
        }
        val sleepChip = chip("⏾ Timer") { v ->
            val opts = arrayOf("Spento", "15 minuti", "30 minuti", "45 minuti", "60 minuti")
            MaterialAlertDialogBuilder(this).setTitle("Spegni dopo").setItems(opts) { _, w ->
                uiHandler.removeCallbacks(sleepRunnable)
                sleepAt = 0L
                if (w > 0) {
                    val ms = w * 15 * 60_000L
                    sleepAt = System.currentTimeMillis() + ms
                    uiHandler.postDelayed(sleepRunnable, ms)
                }
                v.text = if (sleepAt > 0) "⏾ ${w * 15} min" else "⏾ Timer"
            }.show()
        }
        if (sleepAt > 0) sleepChip.text = "⏾ ${((sleepAt - System.currentTimeMillis()) / 60_000) + 1} min"
        root.addView(art); root.addView(title); root.addView(sub); root.addView(seek); root.addView(times); root.addView(controls); root.addView(extras)
        var dragging = false
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && c.duration > 0) tPos.text = fmtTime(c.duration * p / 1000)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                dragging = false
                if (c.duration > 0) c.seekTo(c.duration * (sb?.progress ?: 0) / 1000)
            }
        })
        var lastArt: Uri? = null
        fun update() {
            val md = c.currentMediaItem?.mediaMetadata ?: return
            title.text = md.title ?: ""
            sub.text = md.artist ?: ""
            if (md.artworkUri != lastArt) {
                lastArt = md.artworkUri
                art.load(md.artworkUri) { transformations(coil.transform.RoundedCornersTransformation(48f)); error(R.drawable.ic_nav_audio) }
            }
            play.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            val live = c.isCurrentMediaItemLive || c.duration <= 0 || c.currentMediaItem?.mediaId?.startsWith("radio:") == true
            seek.isVisible = !live; times.isVisible = !live; back.isVisible = !live; fwd.isVisible = !live; speedChip.isVisible = !live
            if (!live && !dragging) {
                seek.progress = (c.currentPosition * 1000 / c.duration).toInt()
                tPos.text = fmtTime(c.currentPosition)
                tDur.text = "−" + fmtTime(c.duration - c.currentPosition)
            }
            val sp = c.playbackParameters.speed
            speedChip.text = (if (sp % 1f == 0f) sp.toInt().toString() else sp.toString()) + "x"
        }
        val ticker = object : Runnable { override fun run() { update(); uiHandler.postDelayed(this, 500) } }
        playerSheetUpdate = { update() }
        sheet.setOnDismissListener { uiHandler.removeCallbacks(ticker); playerSheetUpdate = null }
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        update()
        uiHandler.post(ticker)
        sheet.show()
    }

    // ======================= Weather (Open-Meteo: free, no key) =======================

    private val weatherPrefs by lazy { getSharedPreferences("wl_weather", MODE_PRIVATE) }

    private fun weatherInfo(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Sereno"
        1 -> "🌤" to "Poco nuvoloso"
        2 -> "⛅" to "Parz. nuvoloso"
        3 -> "☁️" to "Coperto"
        45, 48 -> "🌫" to "Nebbia"
        in 51..57 -> "🌦" to "Pioviggine"
        in 61..67 -> "🌧" to "Pioggia"
        in 71..77 -> "❄️" to "Neve"
        in 80..82 -> "🌦" to "Rovesci"
        85, 86 -> "🌨" to "Neve"
        in 95..99 -> "⛈" to "Temporale"
        else -> "🌡" to ""
    }

    private fun renderWeather() {
        val card = findViewById<android.widget.LinearLayout>(R.id.weatherCard)
        card.removeAllViews()
        if (weatherPrefs.getBoolean("hidden", false)) { card.isVisible = false; return }
        card.isVisible = true
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        card.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
        }
        val city = weatherPrefs.getString("city", null)
        val data = weatherPrefs.getString("data", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        card.setOnClickListener { if (city == null) setupWeather() else showWeatherDetail() }
        if (city == null || data == null) {
            card.addView(TextView(this).apply {
                text = if (city == null) "☀️  Aggiungi il meteo della tua città" else "⏳  Meteo di $city…"
                textSize = 15f; setTextColor(palette.text)
            })
            return
        }
        val cur = data.optJSONObject("current")
        val daily = data.optJSONObject("daily")
        val (emoji, label) = weatherInfo(cur?.optInt("weather_code") ?: -1)
        card.addView(TextView(this).apply { text = emoji; textSize = 30f; setPadding(0, 0, dp(12), 0) })
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        val temp = cur?.optDouble("temperature_2m")?.let { Math.round(it) }
        box.addView(TextView(this).apply {
            text = "${temp ?: "–"}° · $label"; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
        })
        val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.let { Math.round(it) }
        val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.let { Math.round(it) }
        val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0)
        box.addView(TextView(this).apply {
            text = listOfNotNull(city, if (max != null && min != null) "$max° / $min°" else null, rain?.takeIf { it >= 20 }?.let { "💧 $it%" })
                .joinToString(" · ")
            textSize = 12.5f; setTextColor(palette.textMuted)
        })
        card.addView(box)
        val days = daily?.optJSONArray("time")
        val dayFmt = java.text.SimpleDateFormat("EEE", Locale.ITALIAN)
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        for (i in 1..3) {
            val t = days?.optString(i) ?: continue
            val dayName = runCatching { dayFmt.format(parse.parse(t)!!).replaceFirstChar { it.uppercase() }.take(3) }.getOrDefault("")
            val e = weatherInfo(daily.optJSONArray("weather_code")?.optInt(i) ?: -1).first
            val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(i)?.let { Math.round(it) }
            card.addView(TextView(this).apply {
                text = "$dayName\n$e\n${hi ?: "–"}°"
                textSize = 11.5f; gravity = android.view.Gravity.CENTER; setTextColor(palette.textMuted)
                setPadding(dp(6), 0, dp(6), 0)
            })
        }
    }

    private fun refreshWeather(force: Boolean = false) {
        val lat = weatherPrefs.getFloat("lat", Float.NaN)
        val lon = weatherPrefs.getFloat("lon", Float.NaN)
        if (lat.isNaN() || weatherPrefs.getBoolean("hidden", false)) return
        if (!force && System.currentTimeMillis() - weatherPrefs.getLong("time", 0L) < 30 * 60_000L) return
        Thread {
            val json = runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                    "&timezone=auto&forecast_days=7"
                java.net.URL(url).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            runOnUiThread {
                if (json != null) weatherPrefs.edit().putString("data", json).putLong("time", System.currentTimeMillis()).apply()
                renderWeather()
            }
        }.start()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) useMyLocationForWeather() else toast("Posizione negata: scrivi il nome della città")
    }

    @SuppressLint("MissingPermission")
    private fun useMyLocationForWeather() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val loc = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) { toast("Posizione non disponibile: scrivi il nome della città"); return }
        Thread {
            @Suppress("DEPRECATION")
            val name = runCatching {
                android.location.Geocoder(this, Locale.ITALIAN).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
            }.getOrNull() ?: "La tua zona"
            runOnUiThread { setWeatherPlace(name, loc.latitude, loc.longitude) }
        }.start()
    }

    private fun setWeatherPlace(name: String, lat: Double, lon: Double) {
        weatherPrefs.edit().putString("city", name).putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat())
            .putBoolean("hidden", false).remove("data").putLong("time", 0L).apply()
        renderWeather()
        refreshWeather(force = true)
        if (currentTab == TAB_SETTINGS) renderSettings()
    }

    private fun setupWeather() {
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { hint = "Es. Torino, Bergamo, Perugia"; setSingleLine() }
        val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Meteo: la tua città")
            .setView(box)
            .setPositiveButton("Cerca") { _, _ ->
                val q = et.text?.toString()?.trim().orEmpty()
                if (q.isEmpty()) return@setPositiveButton
                Thread {
                    val results = runCatching {
                        val json = java.net.URL("https://geocoding-api.open-meteo.com/v1/search?count=6&language=it&name=" + Uri.encode(q))
                            .openStream().use { it.readBytes().toString(Charsets.UTF_8) }
                        val arr = org.json.JSONObject(json).optJSONArray("results") ?: org.json.JSONArray()
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            Triple(listOf(o.optString("name"), o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                                o.optDouble("latitude"), o.optDouble("longitude")) to o.optString("name")
                        }
                    }.getOrDefault(emptyList())
                    runOnUiThread {
                        if (results.isEmpty()) { toast("Città non trovata"); return@runOnUiThread }
                        MaterialAlertDialogBuilder(this).setTitle("Quale?")
                            .setItems(results.map { it.first.first }.toTypedArray()) { _, w ->
                                val (t, name) = results[w]
                                setWeatherPlace(name, t.second, t.third)
                            }.show()
                    }
                }.start()
            }
            .setNeutralButton("Usa la mia posizione") { _, _ -> useMyLocationForWeather() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showWeatherDetail() {
        val data = weatherPrefs.getString("data", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return
        val cur = data.optJSONObject("current")
        val daily = data.optJSONObject("daily") ?: return
        val sb = StringBuilder()
        cur?.let {
            sb.append("Percepita ${Math.round(it.optDouble("apparent_temperature"))}° · vento ${Math.round(it.optDouble("wind_speed_10m"))} km/h · umidità ${it.optInt("relative_humidity_2m")}%\n\n")
        }
        val fmt = java.text.SimpleDateFormat("EEEE d", Locale.ITALIAN)
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val days = daily.optJSONArray("time") ?: return
        for (i in 0 until days.length()) {
            val name = if (i == 0) "Oggi" else runCatching { fmt.format(parse.parse(days.getString(i))!!).replaceFirstChar { it.uppercase() } }.getOrDefault("")
            val (e, l) = weatherInfo(daily.optJSONArray("weather_code")?.optInt(i) ?: -1)
            val hi = Math.round(daily.optJSONArray("temperature_2m_max")?.optDouble(i) ?: 0.0)
            val lo = Math.round(daily.optJSONArray("temperature_2m_min")?.optDouble(i) ?: 0.0)
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0
            sb.append("$e  $name: $l, $hi° / $lo°${if (rain >= 20) " · 💧$rain%" else ""}\n")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Meteo · ${weatherPrefs.getString("city", "")}")
            .setMessage(sb.toString().trim())
            .setPositiveButton("OK", null)
            .setNeutralButton("Cambia città") { _, _ -> setupWeather() }
            .setNegativeButton("Nascondi") { _, _ -> weatherPrefs.edit().putBoolean("hidden", true).apply(); renderWeather(); toast("Meteo nascosto: lo riattivi dalle Impostazioni") }
            .show()
    }

    // ======================= Usage time & daily limits =======================

    private val usagePrefs by lazy { getSharedPreferences("wl_usage", MODE_PRIVATE) }
    private var usageHost: String? = null
    private var usageStart = 0L
    private val today get() = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(java.util.Date())

    private fun usageMap(day: String): MutableMap<String, Long> = runCatching {
        val o = org.json.JSONObject(usagePrefs.getString("d_$day", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun flushUsage() {
        val host = usageHost ?: return
        val now = System.currentTimeMillis()
        val ms = now - usageStart
        usageStart = now
        if (ms in 1000..(6 * 3600_000L)) {
            val day = today
            val map = usageMap(day)
            map[host] = (map[host] ?: 0L) + ms
            usagePrefs.edit().putString("d_$day", org.json.JSONObject(map as Map<*, *>).toString()).apply()
            checkUsageLimit(host, map[host] ?: 0L)
        }
    }

    /** Called whenever the visible site may change. */
    private fun trackUsage() {
        flushUsage()
        val url = webView.url
        val visible = currentTab == TAB_BROWSER && browserHasPage && !isFinishing && hasWindowFocusOrResumed
        usageHost = if (visible && url != null && url.startsWith("http") && !MiaAi.isMiaUrl(url) && !isPdfViewer(url)) hostOf(url) else null
        usageStart = System.currentTimeMillis()
    }

    private var hasWindowFocusOrResumed = false

    private fun limitFor(host: String): Int = usagePrefs.getInt("limit_$host", 0)

    private fun checkUsageLimit(host: String, usedMs: Long) {
        val limit = limitFor(host)
        if (limit <= 0 || !hasWindowFocusOrResumed || isFinishing) return
        val extra = usagePrefs.getInt("extra_${today}_$host", 0)
        if (usedMs < (limit + extra) * 60_000L) return
        if (usagePrefs.getBoolean("warned_${today}_${host}_$extra", false)) return
        usagePrefs.edit().putBoolean("warned_${today}_${host}_$extra", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("⏱ Tempo scaduto")
            .setMessage("Oggi hai usato $host per ${usedMs / 60_000} minuti (limite: $limit min).")
            .setPositiveButton("Chiudi") { _, _ -> closeCurrentTab(); showTab(TAB_HOME) }
            .setNegativeButton("Ancora 15 min") { _, _ -> usagePrefs.edit().putInt("extra_${today}_$host", extra + 15).apply() }
            .setCancelable(false)
            .show()
    }

    private fun showUsagePage() {
        flushUsage()
        openPage("Tempo di utilizzo") { _, body, _ ->
            val palette = AppRepository.palette(this)
            val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
            val d = resources.displayMetrics.density
            fun render() {
                body.removeAllViews()
                val map = usageMap(today)
                val total = map.values.sum()
                body.addView(TextView(this).apply {
                    text = "Oggi: ${fmtDur(total / 1000).ifBlank { "meno di 1 min" }}"
                    textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
                    setPadding((10 * d).toInt(), (8 * d).toInt(), 0, (4 * d).toInt())
                })
                // last 7 days
                val cal = java.util.Calendar.getInstance()
                val fmt = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT)
                val week = (6 downTo 0).map { back ->
                    val c = cal.clone() as java.util.Calendar
                    c.add(java.util.Calendar.DAY_OF_YEAR, -back)
                    java.text.SimpleDateFormat("EEE", Locale.ITALIAN).format(c.time).take(3) to usageMap(fmt.format(c.time)).values.sum()
                }
                val maxDay = week.maxOf { it.second }.coerceAtLeast(1)
                val chart = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.BOTTOM
                    setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                }
                week.forEachIndexed { i, (name, ms) ->
                    val col = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, (110 * d).toInt(), 1f)
                    }
                    col.addView(View(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 6 * d; setColor(if (i == 6) accent else androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x66))
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams((18 * d).toInt(), ((80 * d) * ms / maxDay).toInt().coerceAtLeast((2 * d).toInt()))
                    })
                    col.addView(TextView(this).apply { text = name; textSize = 11f; setTextColor(palette.textMuted); setPadding(0, (4 * d).toInt(), 0, 0) })
                    chart.addView(col)
                }
                body.addView(chart)
                sectionLabel(body, "OGGI PER SITO · tocca per un limite")
                if (map.isEmpty()) { emptyLabel(body, "Nessun sito usato oggi"); return }
                map.entries.sortedByDescending { it.value }.forEach { (host, ms) ->
                    val limit = limitFor(host)
                    listRow(body, host, fmtDur(ms / 1000).ifBlank { "<1 min" } + if (limit > 0) " · limite $limit min" else "",
                        onClick = {
                            val opts = arrayOf("Nessun limite", "15 min", "30 min", "45 min", "1 ora", "2 ore")
                            val values = intArrayOf(0, 15, 30, 45, 60, 120)
                            MaterialAlertDialogBuilder(this).setTitle("Limite giornaliero · $host")
                                .setItems(opts) { _, w ->
                                    usagePrefs.edit().putInt("limit_$host", values[w]).apply()
                                    render()
                                }.show()
                        })
                }
            }
            render()
        }
    }

    // ======================= Sync between devices (backup file on Drive) =======================

    private val syncPrefs by lazy { getSharedPreferences("wl_sync", MODE_PRIVATE) }

    private fun fullBackupJson(): String {
        val gson = com.google.gson.Gson()
        val now = System.currentTimeMillis()
        return gson.toJson(mapOf(
            "apps" to apps,
            "categories" to categories,
            "searchEngine" to repo.getSearchEngine().name,
            "savedAt" to now,
            "device" to (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL),
            "notes" to notes.load(),
            "readLater" to readLater.load(),
            "newsEnabled" to news.enabledIds().toList(),
            "newsCustom" to news.customSources(),
            "podcasts" to audio.subscriptions(),
            "radioFavs" to audio.favoriteStations(),
            "weather" to mapOf(
                "city" to weatherPrefs.getString("city", null),
                "lat" to weatherPrefs.getFloat("lat", Float.NaN).takeIf { !it.isNaN() },
                "lon" to weatherPrefs.getFloat("lon", Float.NaN).takeIf { !it.isNaN() }
            )
        ))
    }

    /** Applies the extra sections of a backup (notes, read later, news, podcasts...). */
    private fun applyBackupExtras(o: org.json.JSONObject) {
        val gson = com.google.gson.Gson()
        fun <T> list(key: String, type: java.lang.reflect.Type): List<T>? =
            o.optJSONArray(key)?.let { runCatching { gson.fromJson<List<T>>(it.toString(), type) }.getOrNull() }
        list<Note>("notes", object : com.google.gson.reflect.TypeToken<List<Note>>() {}.type)?.let { notes.save(it) }
        list<ReadLaterItem>("readLater", object : com.google.gson.reflect.TypeToken<List<ReadLaterItem>>() {}.type)?.let { readLater.save(it) }
        list<String>("newsEnabled", object : com.google.gson.reflect.TypeToken<List<String>>() {}.type)?.let { news.setEnabledIds(it.toSet()) }
        list<NewsSource>("newsCustom", object : com.google.gson.reflect.TypeToken<List<NewsSource>>() {}.type)?.let { news.setCustomSources(it) }
        list<Podcast>("podcasts", object : com.google.gson.reflect.TypeToken<List<Podcast>>() {}.type)?.let { audio.setSubscriptions(it) }
        list<RadioStation>("radioFavs", object : com.google.gson.reflect.TypeToken<List<RadioStation>>() {}.type)?.let { audio.setFavoriteStations(it) }
        o.optJSONObject("weather")?.let { w ->
            val city = w.optString("city").takeIf { it.isNotBlank() && it != "null" }
            if (city != null && w.has("lat")) setWeatherPlace(city, w.optDouble("lat"), w.optDouble("lon"))
        }
        newsItems = emptyList(); newsUpdatedAt = 0L; episodes = emptyList()
    }

    private fun applyFullBackup(o: org.json.JSONObject) {
        val gson = com.google.gson.Gson()
        val appType = object : com.google.gson.reflect.TypeToken<MutableList<AppItem>>() {}.type
        val loaded = runCatching { gson.fromJson<MutableList<AppItem>>(o.optJSONArray("apps")?.toString() ?: "[]", appType) }.getOrNull()
            ?.filter { it != null && !it.name.isNullOrBlank() && !it.url.isNullOrBlank() }
        if (!loaded.isNullOrEmpty() || o.has("apps")) {
            apps = (loaded ?: emptyList()).map { it.copy(iconPath = it.iconPath?.takeIf { p -> java.io.File(p).exists() }) }.toMutableList()
            val cats = runCatching { gson.fromJson<MutableList<String>>(o.optJSONArray("categories")?.toString() ?: "[]",
                object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type) }.getOrNull()
            categories = (cats ?: mutableListOf("Utility")).filterNotNull().toMutableList()
            apps.map { it.category }.distinct().filter { it !in categories }.forEach { categories.add(it) }
            repo.saveApps(apps); repo.saveCategories(categories)
        }
        o.optString("searchEngine").takeIf { it.isNotBlank() }?.let { repo.setSearchEngine(SearchEngine.fromName(it)) }
        applyBackupExtras(o)
        refreshList()
    }

    /** On return to the app: if the Drive backup was written by another device, offer to update. */
    private fun checkSyncFromOtherDevice() {
        val raw = repo.getAutoBackupUri() ?: return
        if (System.currentTimeMillis() - syncPrefs.getLong("checked", 0L) < 60_000L) return
        syncPrefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        io.execute {
            val text = runCatching {
                contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@execute
            val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return@execute
            val savedAt = o.optLong("savedAt", 0L)
            val mine = syncPrefs.getLong("written", 0L)
            val applied = syncPrefs.getLong("applied", 0L)
            if (savedAt <= mine + 2000 || savedAt <= applied) return@execute
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("🔄 Dati più recenti")
                    .setMessage("Il backup su Drive è stato aggiornato da «${o.optString("device", "un altro dispositivo")}» " +
                        "(${NewsAdapter.ago(savedAt)}). Vuoi caricare app, note, notizie e podcast da lì?")
                    .setPositiveButton("Aggiorna") { _, _ ->
                        applyFullBackup(o)
                        syncPrefs.edit().putLong("applied", savedAt).putLong("written", savedAt).apply()
                        toast("Dati aggiornati")
                    }
                    .setNegativeButton("Non ora") { _, _ -> syncPrefs.edit().putLong("applied", savedAt).apply() }
                    .show()
            }
        }
    }

    private val syncOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { toast("Questo file non può essere aggiornato: scegline uno su Drive"); return@registerForActivityResult }
        repo.setAutoBackupUri(uri.toString())
        syncPrefs.edit().putLong("written", 0L).putLong("applied", 0L).putLong("checked", 0L).apply()
        toast("Collegato: controllo i dati…")
        checkSyncFromOtherDevice()
    }

    private fun showSyncHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Sincronizza tra dispositivi")
            .setMessage(
                "Usa lo stesso file di backup su Google Drive su tutti i dispositivi.\n\n" +
                "1. Sul primo: «Crea il file» e salvalo su Drive.\n" +
                "2. Sugli altri: «Collega un file esistente» e scegli lo stesso file.\n\n" +
                "Le modifiche (app, note, leggi dopo, notizie, podcast, meteo) vengono salvate quando esci dall'app; " +
                "quando apri l'app su un altro dispositivo ti propone di aggiornarla."
            )
            .setPositiveButton("Crea il file") { _, _ -> startAutoBackupSetup() }
            .setNeutralButton("Collega un file esistente") { _, _ -> syncOpenLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    // ======================= YouTube =======================

    private val ytPrefs by lazy { getSharedPreferences("wl_yt", MODE_PRIVATE) }
    private var ytScript: androidx.webkit.ScriptHandler? = null
    private var ytPlaying = false
    private var ytTranscriptCallback: ((org.json.JSONObject) -> Unit)? = null
    private var inPip = false

    private fun ytConfig() = YouTube.Config(
        skipAds = ytPrefs.getBoolean("skipAds", true),
        sponsorBlock = ytPrefs.getBoolean("sponsorBlock", true),
        clean = ytPrefs.getBoolean("clean", false),
        background = ytPrefs.getBoolean("background", true),
        speed = ytPrefs.getFloat("speed", 1f),
        quality = ytPrefs.getString("quality", "auto") ?: "auto"
    )

    private fun ytDocStartSupported() =
        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)

    /** (Re)installs the YouTube script with the current options. */
    private fun installYouTubeScript() {
        if (!ytDocStartSupported()) return
        runCatching { ytScript?.remove() }
        ytScript = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, YouTube.script(ytConfig()), YouTube.ORIGINS)
        }.getOrNull()
    }

    private fun setupYouTube() {
        installYouTubeScript()
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
        androidx.webkit.WebViewCompat.addWebMessageListener(webView, YouTube.BRIDGE, YouTube.ORIGINS) { _, message, origin, isMainFrame, _ ->
            if (!isMainFrame || !YouTube.isYouTube(origin.toString())) return@addWebMessageListener
            val m = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            when (m.optString("t")) {
                "sb" -> fetchSponsorSegments(m.optString("id"))
                "state" -> ytPlaying = m.optBoolean("playing")
                "transcript" -> {
                    val cb = ytTranscriptCallback
                    ytTranscriptCallback = null
                    m.optJSONObject("data")?.let { cb?.invoke(it) }
                }
            }
        }
    }

    private fun fetchSponsorSegments(id: String) {
        if (!Regex("[\\w-]{11}").matches(id)) return
        Thread {
            val segs = runCatching {
                val cats = Uri.encode("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\"]")
                val c = java.net.URL("https://sponsor.ajay.app/api/skipSegments?videoID=$id&categories=$cats")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                if (c.responseCode != 200) "[]" else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.let { body ->
                    val arr = org.json.JSONArray(body)
                    val out = org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val seg = o.optJSONArray("segment") ?: continue
                        out.put(org.json.JSONObject().put("start", seg.optDouble(0)).put("end", seg.optDouble(1))
                            .put("category", o.optString("category")))
                    }
                    out.toString()
                }
            }.getOrDefault("[]")
            runOnUiThread {
                if (YouTube.isYouTube(webView.url)) webView.evaluateJavascript("window.__wlSegments&&__wlSegments('$id',$segs)", null)
            }
        }.start()
    }

    private fun withVideoInfo(onData: (org.json.JSONObject) -> Unit) {
        if (!YouTube.isYouTube(webView.url)) { toast("Apri un video di YouTube"); return }
        toast("Leggo il video…")
        ytTranscriptCallback = onData
        webView.evaluateJavascript("window.__wlTranscript ? (__wlTranscript(), 1) : 0") { r ->
            if (r != "1") { ytTranscriptCallback = null; toast("Ricarica la pagina di YouTube e riprova") }
        }
        uiHandler.postDelayed({
            if (ytTranscriptCallback === onData) { ytTranscriptCallback = null; toast("Il video non ha risposto, riprova") }
        }, 15_000)
    }

    private fun videoContext(o: org.json.JSONObject): String {
        val transcript = o.optString("transcript")
        return "Video YouTube «${o.optString("title")}» del canale ${o.optString("channel")}.\n" +
            (if (transcript.isNotBlank()) "--- TRASCRIZIONE (con minuti) ---\n$transcript\n--- FINE ---\n"
             else "Trascrizione non disponibile. Descrizione del video:\n${o.optString("description")}\n") + "\n"
    }

    private fun summarizeVideo() {
        if (!aiReady()) return
        withVideoInfo { o ->
            val has = o.optString("transcript").isNotBlank()
            showAiSheet(
                title = "Riassunto video",
                context = videoContext(o),
                firstQuestion = if (has) "Riassumi il video in italiano: una frase iniziale e i punti principali, " +
                    "ognuno con il minuto [mm:ss] in cui inizia." else
                    "Non c'è la trascrizione: dimmi di cosa parla il video in base a titolo e descrizione, e avvisa che è un riassunto parziale.",
                showFirstQuestion = false
            )
        }
    }

    private fun askVideo() {
        if (!aiReady()) return
        withVideoInfo { o ->
            showAiSheet(title = "Chiedi al video", context = videoContext(o), firstQuestion = null,
                hint = "Es. a che minuto spiega…? quali prodotti consiglia?")
        }
    }

    private fun followCurrentChannel() {
        webView.evaluateJavascript("window.__wlChannel ? __wlChannel() : '{}'") { raw ->
            val o = runCatching { org.json.JSONObject(jsString(raw)) }.getOrNull()
            val id = o?.optString("id").orEmpty()
            if (!id.startsWith("UC")) { toast("Apri un video o la pagina del canale"); return@evaluateJavascript }
            addYtChannel(YtChannel(id, o?.optString("name").orEmpty().ifBlank { "Canale" }))
        }
    }

    private fun ytSpeedDialog() {
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = speeds.map { (if (it % 1f == 0f) it.toInt().toString() else it.toString()) + "x" }.toTypedArray()
        val cur = speeds.indexOfFirst { kotlin.math.abs(it - ytPrefs.getFloat("speed", 1f)) < 0.01f }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Velocità video")
            .setSingleChoiceItems(labels, cur) { d, w ->
                ytPrefs.edit().putFloat("speed", speeds[w]).apply()
                webView.evaluateJavascript("window.__wlSetSpeed&&__wlSetSpeed(${speeds[w]})", null)
                installYouTubeScript()
                d.dismiss()
            }.show()
    }

    private fun ytQualityDialog() {
        val values = arrayOf("auto", "small", "medium", "large", "hd720", "hd1080")
        val labels = arrayOf("Automatica", "240p (meno dati)", "360p", "480p", "720p", "1080p")
        val cur = values.indexOf(ytPrefs.getString("quality", "auto")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Qualità preferita")
            .setSingleChoiceItems(labels, cur) { d, w ->
                ytPrefs.edit().putString("quality", values[w]).apply()
                installYouTubeScript()
                d.dismiss()
                if (currentTab == TAB_SETTINGS) renderSettings()
                toast("Vale dai prossimi video (se YouTube lo permette)")
            }.show()
    }

    private fun ytOptionsDialog() {
        val keys = arrayOf("skipAds", "sponsorBlock", "clean", "background", "autoPip")
        val labels = arrayOf("⏩ Salta automaticamente gli annunci", "⏭ SponsorBlock (salta sponsor e sigle)",
            "🔇 Interfaccia pulita (niente Shorts e consigliati)", "🎧 Audio a schermo spento", "🎬 Finestrella quando esci dall'app")
        val defaults = booleanArrayOf(true, true, false, true, true)
        val checked = BooleanArray(keys.size) { ytPrefs.getBoolean(keys[it], defaults[it]) }
        MaterialAlertDialogBuilder(this).setTitle("YouTube")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Salva") { _, _ ->
                val e = ytPrefs.edit()
                keys.forEachIndexed { i, k -> e.putBoolean(k, checked[i]) }
                e.apply()
                installYouTubeScript()
                if (YouTube.isYouTube(webView.url)) webView.reload()
                if (currentTab == TAB_SETTINGS) renderSettings()
            }
            .setNeutralButton("Qualità") { _, _ -> ytQualityDialog() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ---------- picture-in-picture ----------

    private fun canPip() = android.os.Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterVideoPip() {
        if (!canPip()) { toast("La finestrella non è supportata su questo telefono"); return }
        if (!YouTube.isYouTube(webView.url)) { toast("Funziona con i video di YouTube"); return }
        webView.evaluateJavascript("window.__wlPip&&__wlPip(true)", null)
        try {
            val params = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            webView.evaluateJavascript("window.__wlPip&&__wlPip(false)", null)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (currentTab == TAB_BROWSER && ytPlaying && YouTube.isYouTube(webView.url) &&
            ytPrefs.getBoolean("autoPip", true) && canPip() && !inPip
        ) enterVideoPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        browserToolbar.isVisible = !inPip
        bottomBar.isVisible = !inPip
        webProgress.visibility = if (inPip) View.GONE else View.INVISIBLE
        if (inPip) findViewById<View>(R.id.miniPlayer).isVisible = false else updateMiniPlayer()
        webView.evaluateJavascript("window.__wlPip&&__wlPip(${inPip})", null)
    }

    // ---------- followed channels (public RSS, no account) ----------

    data class YtChannel(val id: String, val name: String)

    private fun ytChannels(): List<YtChannel> = runCatching {
        com.google.gson.Gson().fromJson<List<YtChannel>>(ytPrefs.getString("channels", "[]"),
            object : com.google.gson.reflect.TypeToken<List<YtChannel>>() {}.type)
    }.getOrNull().orEmpty()

    private fun saveYtChannels(list: List<YtChannel>) {
        ytPrefs.edit().putString("channels", com.google.gson.Gson().toJson(list)).apply()
    }

    private fun addYtChannel(c: YtChannel) {
        if (ytChannels().any { it.id == c.id }) { toast("Segui già ${c.name}"); return }
        saveYtChannels(ytChannels() + c)
        ytVideos = emptyList()
        toast("▶ Ora segui ${c.name}: lo trovi in Ascolta → Video")
    }

    private var ytVideos: List<NewsItem> = emptyList()

    private fun refreshYtVideos() {
        val chans = ytChannels()
        val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
        if (chans.isEmpty()) { refresh.isRefreshing = false; return }
        refresh.isRefreshing = true
        Thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val all = try {
                pool.invokeAll(chans.map { ch ->
                    java.util.concurrent.Callable {
                        runCatching {
                            val c = java.net.URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch.id).openConnection() as java.net.HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 10000
                            c.setRequestProperty("Cookie", "SOCS=CAI")
                            val bytes = c.inputStream.use { it.readBytes() }
                            news.parseFeed(bytes, NewsSource(ch.id, ch.name, "Video", "")).take(15)
                        }.getOrDefault(emptyList())
                    }
                }, 40, java.util.concurrent.TimeUnit.SECONDS).flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
            } finally { pool.shutdownNow() }
            runOnUiThread {
                refresh.isRefreshing = false
                if (all.isNotEmpty()) {
                    ytVideos = all.filter { !it.link.contains("/shorts/") }.sortedByDescending { it.time }
                    ytPrefs.edit().putLong("refreshed", System.currentTimeMillis()).apply()
                }
                if (currentTab == TAB_AUDIO && audioTab == "video") renderAudio()
            }
        }.start()
    }

    private fun renderVideos(list: android.widget.LinearLayout) {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val chans = ytChannels()
        if (chans.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nessun canale seguito.\n\nApri un video su YouTube → menu ⋮ → «Segui il canale», " +
                    "oppure tocca «Cerca» e scrivi il nome del canale (es. @nomecanale).\n\n" +
                    "Vedrai solo i video dei tuoi canali: niente consigliati, niente Shorts."
                textSize = 15f; setTextColor(palette.textMuted); setPadding(dp(14), dp(24), dp(14), dp(16))
            })
            return
        }
        if (ytVideos.isEmpty() && System.currentTimeMillis() - ytPrefs.getLong("refreshed", 0L) > 60_000L) refreshYtVideos()
        sectionLabel(list, "CANALI · ${chans.size}")
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        chans.forEach { ch ->
            row.addView(TextView(this).apply {
                text = ch.name; textSize = 13f; setTextColor(palette.text)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                setOnClickListener { openInBrowser("https://m.youtube.com/channel/${ch.id}/videos") }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle(ch.name)
                        .setItems(arrayOf("Apri il canale", "Non seguire più")) { _, w ->
                            if (w == 0) openInBrowser("https://m.youtube.com/channel/${ch.id}/videos")
                            else { saveYtChannels(ytChannels().filter { it.id != ch.id }); ytVideos = ytVideos.filter { it.sourceId != ch.id }; renderAudio() }
                        }.show()
                    true
                }
            })
        }
        list.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(dp(6), 0, 0, 0); addView(row) })
        sectionLabel(list, "ULTIMI VIDEO")
        if (ytVideos.isEmpty()) {
            list.addView(TextView(this).apply { text = "Trascina in giù per caricare"; textSize = 14f; setTextColor(palette.textMuted); setPadding(dp(10), dp(8), 0, 0) })
        }
        ytVideos.take(60).forEach { v ->
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { openInBrowser(v.link) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle(v.title)
                        .setItems(arrayOf("Apri", "Apri in nuova scheda", "Condividi", "🔖 Leggi dopo (link)")) { _, w ->
                            when (w) {
                                0 -> openInBrowser(v.link)
                                1 -> openInNewTab(v.link)
                                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${v.title}\n${v.link}")
                                }, "Condividi video"))
                                3 -> saveForLater(ReaderDoc(v.title, v.source, v.link, v.image, v.summary.ifBlank { v.title }))
                            }
                        }.show()
                    true
                }
            }
            r.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(12) }
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(palette.iconTile) }
                load(v.image) { transformations(coil.transform.RoundedCornersTransformation(20f)) }
            })
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            }
            box.addView(TextView(this).apply {
                text = v.title; textSize = 14.5f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = "${v.source} · ${NewsAdapter.ago(v.time)}"; textSize = 12f; setTextColor(palette.textMuted); setPadding(0, dp(3), 0, 0)
            })
            r.addView(box)
            list.addView(r)
        }
    }

    private fun addChannelDialog() {
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { hint = "@nomecanale o link del canale"; setSingleLine() }
        val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
        MaterialAlertDialogBuilder(this).setTitle("Segui un canale YouTube").setView(box)
            .setPositiveButton("Cerca") { _, _ ->
                val raw = et.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return@setPositiveButton
                val url = when {
                    raw.startsWith("http") -> raw.replace("://youtube.com", "://www.youtube.com").replace("://m.youtube.com", "://www.youtube.com")
                    raw.startsWith("@") -> "https://www.youtube.com/$raw"
                    raw.startsWith("UC") && raw.length == 24 -> "https://www.youtube.com/channel/$raw"
                    else -> "https://www.youtube.com/@" + raw.replace(" ", "")
                }
                toast("Cerco il canale…")
                Thread {
                    val found = runCatching {
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 12000
                        c.setRequestProperty("Cookie", "SOCS=CAI")
                        c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        val html = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        val id = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
                            ?: Regex("\"(?:externalId|channelId|browseId)\":\"(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
                        val name = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        if (id == null) null else YtChannel(id, androidx.core.text.HtmlCompat.fromHtml(name ?: "Canale", 0).toString())
                    }.getOrNull()
                    runOnUiThread {
                        if (found == null) toast("Canale non trovato: prova dal video, menu ⋮ → Segui il canale")
                        else { addYtChannel(found); if (currentTab == TAB_AUDIO) { audioTab = "video"; refreshYtVideos(); renderAudio() } }
                    }
                }.start()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ======================= Calculator in search =======================

    private var calcToken = 0

    private fun updateCalcHint(q: String) {
        val hint = findViewById<TextView>(R.id.calcHint)
        val text = q.trim()
        val token = ++calcToken
        val local = if (text.isEmpty()) null else Calc.evaluate(text)
        val reminder = if (text.startsWith("ricordami", true) || text.startsWith("promemoria", true)) "⏰ Crea promemoria: $text" else null
        val parcel = if (Parcels.looksLikeTracking(text)) "📦 Traccia pacco ${text.uppercase()}" else null
        val shown = local?.let { "🧮 $it" } ?: reminder ?: parcel
        hint.isVisible = shown != null
        hint.text = shown
        hint.setOnClickListener {
            when {
                local != null -> copyText("Risultato", local.substringAfter("= ").trim())
                reminder != null -> { searchInput.setText(""); createReminderFromText(text) }
                parcel != null -> { searchInput.setText(""); trackParcel(text) }
            }
        }
        val cur = if (local == null && reminder == null) Calc.currencyQuery(text) else null
        if (cur != null) {
            hint.isVisible = true
            hint.text = "💱 Calcolo il cambio…"
            Thread {
                val r = Calc.convert(cur.first, cur.second, cur.third)
                runOnUiThread {
                    if (token != calcToken) return@runOnUiThread
                    hint.text = r?.let { "💱 $it" } ?: "💱 Cambio non disponibile (connessione?)"
                    hint.setOnClickListener { r?.let { copyText("Cambio", it.substringAfter("= ")) } }
                }
            }.start()
        }
    }

    // ======================= Smart clipboard =======================

    private val toolsPrefs by lazy { getSharedPreferences("wl_tools", MODE_PRIVATE) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentTab == TAB_HOME && toolsPrefs.getBoolean("smartClipboard", true)) checkClipboard()
    }

    private fun checkClipboard() {
        val bar = findViewById<TextView>(R.id.clipBar)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty() || text.length > 5000) return
        val hash = text.hashCode()
        if (hash == toolsPrefs.getInt("lastClip", 0)) return
        val urlMatch = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ';')
        val phone = Regex("^(\\+39\\s?)?[03]\\d[\\d\\s]{6,11}$").matches(text) && !Parcels.looksLikeTracking(text.replace(" ", ""))
        val action: Pair<String, () -> Unit>? = when {
            urlMatch != null -> ("📋 Apri il link copiato: " + (hostOf(urlMatch) ?: urlMatch)) to { openInBrowser(urlMatch) }
            Parcels.looksLikeTracking(text) -> "📦 Traccia il pacco copiato: ${text.uppercase()}" to { trackParcel(text) }
            Regex("^(\\+39\\s?)?3\\d{2}[\\s]?\\d{6,7}$").matches(text) || (phone && text.length <= 16) ->
                "📞 Chiama $text" to { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + text.replace(" ", "")))) }
            text.length > 400 -> "✨ Riassumi il testo copiato" to {
                if (aiReady()) showAiSheet("Riassunto", "Testo:\n$text\n\n", "Riassumi questo testo in italiano in pochi punti.", false)
            }
            else -> null
        }
        if (action == null) return
        bar.text = action.first + "   ✕"
        bar.isVisible = true
        bar.setOnClickListener { v ->
            toolsPrefs.edit().putInt("lastClip", hash).apply()
            v.isVisible = false
            action.second()
        }
        bar.setOnLongClickListener { v -> toolsPrefs.edit().putInt("lastClip", hash).apply(); v.isVisible = false; true }
    }

    // ======================= Parcels =======================

    private fun trackParcel(raw: String) {
        val code = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
        val (carrier, url) = Parcels.carrier(code)
        val list = Parcels.load(this)
        if (list.none { it.code == code }) {
            list.add(0, Parcels.Parcel(code, "", carrier, System.currentTimeMillis()))
            Parcels.save(this, list)
        }
        toast("📦 $carrier")
        openInBrowser(url)
    }

    private fun showParcelsPage() {
        openPage("Pacchi", "Aggiungi", { _ ->
            val d = resources.displayMetrics.density
            val et = TextInputEditText(this).apply { hint = "Codice di spedizione"; setSingleLine() }
            val name = TextInputEditText(this).apply { hint = "Cosa è (facoltativo)"; setSingleLine() }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et); addView(name)
            }
            MaterialAlertDialogBuilder(this).setTitle("Traccia un pacco").setView(box)
                .setPositiveButton("Traccia") { _, _ ->
                    val code = et.text?.toString()?.trim().orEmpty()
                    if (code.length < 6) return@setPositiveButton
                    val c = code.uppercase(Locale.ROOT).replace(" ", "")
                    val list = Parcels.load(this).filter { it.code != c }.toMutableList()
                    list.add(0, Parcels.Parcel(c, name.text?.toString()?.trim().orEmpty(), Parcels.carrier(c).first, System.currentTimeMillis()))
                    Parcels.save(this, list)
                    trackParcel(c)
                }.setNegativeButton("Annulla", null).show()
        }) { _, body, dialog ->
            fun render() {
                body.removeAllViews()
                val list = Parcels.load(this)
                if (list.isEmpty()) { emptyLabel(body, "Nessun pacco.\nCopia un codice di spedizione: la home te lo propone da sola."); return }
                list.forEach { p ->
                    listRow(body, p.label.ifBlank { p.code }, "${p.carrier}${if (p.label.isNotBlank()) " · ${p.code}" else ""} · aggiunto ${NewsAdapter.ago(p.time)}",
                        onClick = { dialog.dismiss(); openInBrowser(Parcels.carrier(p.code).second) },
                        onRemove = { Parcels.save(this, Parcels.load(this).filter { it.code != p.code }); render() })
                }
            }
            render()
        }
    }

    // ======================= Reminders =======================

    private fun createReminderFromText(raw: String, fromVoice: Boolean = false) {
        val parsed = Reminders.parse(raw)
        if (parsed != null) { confirmReminder(parsed.first, parsed.second); return }
        if (!ai.supported()) { toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»"); return }
        toast("✨ Capisco il promemoria…")
        val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy HH:mm", Locale.ITALIAN).format(java.util.Date())
        ai.ask(listOf("user" to "Adesso è $now. Estrai il promemoria da: «$raw». Rispondi SOLO con JSON " +
            "{\"text\":\"cosa ricordare\",\"datetime\":\"yyyy-MM-ddTHH:mm\"}."), "Rispondi solo con JSON valido.",
            object : AiBridge.Listener {
                override fun onDone(text: String, model: String) {
                    val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                    val at = runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).parse(o!!.getString("datetime"))!!.time
                    }.getOrNull()
                    if (o == null || at == null) toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»")
                    else confirmReminder(o.optString("text").ifBlank { raw }, at)
                }
                override fun onError(message: String, noKey: Boolean) { toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»") }
            })
    }

    private fun confirmReminder(text: String, at: Long) {
        ensureNotificationPermission()
        Reminders.add(this, text, at)
        val fmt = java.text.SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN).format(java.util.Date(at))
        toast("⏰ $fmt: $text")
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() && !toolsPrefs.getBoolean("askedExact", false)) {
            toolsPrefs.edit().putBoolean("askedExact", true).apply()
            MaterialAlertDialogBuilder(this).setTitle("Promemoria puntuali")
                .setMessage("Per avvisarti all'ora esatta Android chiede il permesso «Sveglie e promemoria». Senza, l'avviso può arrivare con qualche minuto di ritardo.")
                .setPositiveButton("Apri impostazioni") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                }.setNegativeButton("Va bene così", null).show()
        }
    }

    private fun showRemindersPage() {
        openPage("Promemoria", "Nuovo", { _ ->
            val d = resources.displayMetrics.density
            val et = TextInputEditText(this).apply { hint = "Es. domani alle 9 chiamare il dentista" }
            val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
            MaterialAlertDialogBuilder(this).setTitle("Nuovo promemoria").setView(box)
                .setPositiveButton("Crea") { _, _ -> et.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { createReminderFromText(it) } }
                .setNegativeButton("Annulla", null).show()
        }) { _, body, _ ->
            val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
            fun render() {
                body.removeAllViews()
                val list = Reminders.load(this).sortedBy { it.at }
                if (list.isEmpty()) { emptyLabel(body, "Nessun promemoria.\nDi' o scrivi «ricordami domani alle 9 di…»"); return }
                val now = System.currentTimeMillis()
                listOf("IN ARRIVO" to list.filter { it.at > now && !it.done }, "PASSATI" to list.filter { it.at <= now || it.done }.reversed())
                    .forEach { (label, group) ->
                        if (group.isEmpty()) return@forEach
                        sectionLabel(body, label)
                        group.forEach { r ->
                            listRow(body, r.text, fmt.format(java.util.Date(r.at)).replaceFirstChar { it.uppercase() },
                                onClick = {}, onRemove = { Reminders.remove(this, r.id); render() })
                        }
                    }
            }
            render()
        }
    }

    // ======================= Screenshot / save as PDF =======================

    private fun savePageAsPdf() {
        val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
        val name = (webView.title ?: "Pagina").take(60)
        pm.print(name, webView.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        toast("Scegli «Salva come PDF» come stampante")
    }

    private fun screenshotPage() {
        val bmp = runCatching {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { webView.draw(android.graphics.Canvas(it)) }
        }.getOrNull() ?: run { toast("Screenshot non riuscito"); return }
        io.execute {
            val uri = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "web-launcher-${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Web Launcher")
                }
                val u = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                u
            }.getOrNull()
            runOnUiThread {
                if (uri == null) { toast("Screenshot non salvato"); return@runOnUiThread }
                MaterialAlertDialogBuilder(this).setTitle("📸 Screenshot salvato").setMessage("Nella galleria, cartella «Web Launcher».")
                    .setPositiveButton("Condividi") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Condividi screenshot"))
                    }.setNegativeButton("OK", null).show()
            }
        }
    }

    // ======================= Phishing warning =======================

    private val allowedHosts = mutableSetOf<String>()

    /** True if the address can be opened; otherwise shows a warning and opens only on request. */
    private fun guardUrl(url: String, open: () -> Unit): Boolean {
        if (!toolsPrefs.getBoolean("phishing", true)) return true
        val host = hostOf(url) ?: return true
        if (host in allowedHosts) return true
        if (apps.any { hostOf(normalizeUrl(it.url)) == host }) return true
        val warning = PhishingGuard.check(url) ?: return true
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle("⚠️ Possibile truffa")
                .setMessage("$warning\n\nNon inserire password, codici o dati della carta.")
                .setPositiveButton("Torna indietro", null)
                .setNegativeButton("Apri comunque") { _, _ -> allowedHosts.add(host); open() }
                .show()
        }
        return false
    }

    // ======================= Automatic cleanup =======================

    private fun noteHostVisit(url: String) {
        val host = hostOf(url) ?: return
        val p = getSharedPreferences("wl_cleanup", MODE_PRIVATE)
        p.edit().putLong("h_$host", System.currentTimeMillis()).apply()
    }

    private fun autoCleanup(force: Boolean = false): Int {
        val p = getSharedPreferences("wl_cleanup", MODE_PRIVATE)
        if (!force && (!toolsPrefs.getBoolean("autoClean", true) || System.currentTimeMillis() - p.getLong("last", 0L) < 7 * 86_400_000L)) return 0
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        val limit = System.currentTimeMillis() - 30 * 86_400_000L
        val keep = apps.mapNotNull { hostOf(normalizeUrl(it.url)) }.toSet()
        val old = p.all.filterKeys { it.startsWith("h_") }.mapNotNull { (k, v) ->
            val host = k.removePrefix("h_")
            if ((v as? Long ?: 0L) < limit && keep.none { host == it || host.endsWith(".$it") || it.endsWith(".$host") }) host else null
        }
        val cm = CookieManager.getInstance()
        val storage = WebStorage.getInstance()
        old.forEach { host ->
            listOf("https://$host", "http://$host").forEach { origin ->
                runCatching { storage.deleteOrigin(origin) }
                cm.getCookie(origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                    cm.setCookie(origin, "$name=; Max-Age=0; Path=/")
                    cm.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.$host")
                    cm.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.${PhishingGuard.registered(host)}")
                }
            }
            p.edit().remove("h_$host").apply()
        }
        cm.flush()
        val hist = repo.loadHistory()
        io.execute { hist.filter { it.time < limit }.forEach { repo.removeHistory(it.url) } }
        return old.size
    }

    // ======================= AI writing =======================

    private val rewriteGetJs = """
        (function(){
          var a=document.activeElement;
          if(a&&(a.tagName==='TEXTAREA'||(a.tagName==='INPUT'&&/^(text|search|email|url|)$/.test(a.type||'')))){
            var s=a.selectionStart,e=a.selectionEnd,all=(s===e);
            window.__wlSel={el:a,s:all?0:s,e:all?a.value.length:e};
            return JSON.stringify({text:all?a.value:a.value.substring(s,e),editable:true});
          }
          var sel=window.getSelection();
          if(sel&&sel.rangeCount&&sel.toString()){
            var ce=!!(a&&a.isContentEditable);
            window.__wlSel={range:sel.getRangeAt(0).cloneRange(),ce:ce};
            return JSON.stringify({text:sel.toString(),editable:ce});
          }
          if(a&&a.isContentEditable){
            window.__wlSel={el2:a};
            return JSON.stringify({text:a.innerText||'',editable:true});
          }
          return JSON.stringify({text:'',editable:false});
        })()
    """.trimIndent()

    private fun rewriteReplaceJs(text: String) = """
        (function(t){
          var x=window.__wlSel; if(!x) return false;
          if(x.el){ var el=x.el; el.focus(); el.setSelectionRange(x.s,x.e);
            if(!document.execCommand('insertText',false,t)){ el.setRangeText(t,x.s,x.e,'end'); el.dispatchEvent(new Event('input',{bubbles:true})); }
            return true; }
          if(x.range&&x.ce){ var s=window.getSelection(); s.removeAllRanges(); s.addRange(x.range); return document.execCommand('insertText',false,t); }
          if(x.el2){ x.el2.focus(); document.execCommand('selectAll',false,null); return document.execCommand('insertText',false,t); }
          return false;
        })(${org.json.JSONObject.quote(text)})
    """.trimIndent()

    private fun writeWithAi() {
        if (!aiReady()) return
        webView.evaluateJavascript(rewriteGetJs) { raw ->
            val o = runCatching { org.json.JSONObject(jsString(raw)) }.getOrNull() ?: org.json.JSONObject()
            val text = o.optString("text").trim()
            val editable = o.optBoolean("editable")
            val actions = if (text.isEmpty()) arrayOf("✍️ Scrivi da zero") else
                arrayOf("✨ Migliora", "✓ Correggi errori", "👔 Più formale", "😊 Più amichevole", "✂️ Più breve", "🇬🇧 Traduci in inglese", "↩️ Scrivi una risposta")
            if (text.isEmpty() && !editable) { toast("Tocca prima una casella di testo o seleziona del testo"); return@evaluateJavascript }
            MaterialAlertDialogBuilder(this).setTitle("✨ Scrivi con AI").setItems(actions) { _, w ->
                if (text.isEmpty()) {
                    val d = resources.displayMetrics.density
                    val et = TextInputEditText(this).apply { hint = "Cosa vuoi scrivere? Es. email per spostare l'appuntamento" }
                    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
                    MaterialAlertDialogBuilder(this).setTitle("✍️ Scrivi da zero").setView(box)
                        .setPositiveButton("Scrivi") { _, _ ->
                            val req = et.text?.toString()?.trim().orEmpty()
                            if (req.isNotEmpty()) runRewrite("Scrivi in italiano questo testo: $req. Rispondi solo con il testo pronto da usare.", editable)
                        }.setNegativeButton("Annulla", null).show()
                    return@setItems
                }
                val instr = when (w) {
                    0 -> "Migliora questo testo (chiarezza e scorrevolezza) mantenendo lingua, significato e tono"
                    1 -> "Correggi solo gli errori di ortografia, grammatica e punteggiatura di questo testo, senza cambiarne lo stile"
                    2 -> "Riscrivi questo testo in tono più formale e cortese"
                    3 -> "Riscrivi questo testo in tono più amichevole e cordiale"
                    4 -> "Riscrivi questo testo in modo più breve e diretto"
                    5 -> "Traduci in inglese questo testo"
                    else -> "Scrivi una risposta cortese in italiano a questo messaggio"
                }
                runRewrite("$instr. Rispondi solo con il testo finale, senza commenti.\n\n«$text»", editable && w != 6)
            }.show()
        }
    }

    private fun runRewrite(prompt: String, canReplace: Boolean) {
        toast("✨ Scrivo…")
        ai.ask(listOf("user" to prompt), "Sei un assistente di scrittura. Rispondi solo con il testo richiesto.", object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val result = text.trim().removePrefix("«").removeSuffix("»").trim()
                val b = MaterialAlertDialogBuilder(this@MainActivity).setTitle("✨ Risultato").setMessage(result)
                    .setNeutralButton("Copia") { _, _ -> copyText("Testo", result) }
                    .setNegativeButton("Annulla", null)
                if (canReplace) b.setPositiveButton("Inserisci") { _, _ ->
                    webView.evaluateJavascript(rewriteReplaceJs(result)) { r -> if (r != "true") { copyText("Testo", result); toast("Copiato: incollalo nella casella") } }
                }
                b.show()
            }
            override fun onError(message: String, noKey: Boolean) { toast(message) }
        })
    }

    // ======================= Price comparison & email =======================

    private fun comparePrices() {
        val js = """
            (function(){
              var name='';
              document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){
                try{ var j=JSON.parse(s.textContent); var arr=Array.isArray(j)?j:(j['@graph']||[j]);
                  arr.forEach(function(x){ if(!name && x && (x['@type']==='Product' || (Array.isArray(x['@type'])&&x['@type'].indexOf('Product')>=0))) name=x.name||''; });
                }catch(e){}
              });
              if(!name){ var m=document.querySelector('meta[property="og:title"]'); if(m) name=m.content; }
              if(!name){ var h=document.querySelector('h1'); if(h) name=h.innerText; }
              return (name||document.title||'').trim().substring(0,200);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val title = jsString(raw).trim()
            if (title.isEmpty()) { toast("Non trovo il nome del prodotto"); return@evaluateJavascript }
            fun offer(q: String) {
                val e = Uri.encode(q)
                val shops = listOf(
                    "Trovaprezzi" to "https://www.trovaprezzi.it/categoria.aspx?libera=$e",
                    "Idealo" to "https://www.idealo.it/MainSearchProductCategory.html?q=$e",
                    "Google Shopping" to "https://www.google.com/search?tbm=shop&q=$e",
                    "Amazon" to "https://www.amazon.it/s?k=$e",
                    "eBay" to "https://www.ebay.it/sch/i.html?_nkw=$e"
                )
                MaterialAlertDialogBuilder(this).setTitle("🛒 $q").setItems(shops.map { it.first }.toTypedArray()) { _, w ->
                    openInNewTab(shops[w].second)
                }.show()
            }
            if (ai.supported() && title.length > 40) {
                toast("✨ Riconosco il prodotto…")
                ai.ask(listOf("user" to "Dal titolo di questa pagina estrai marca e modello del prodotto, in massimo 8 parole, " +
                    "adatto a una ricerca di prezzi. Solo il testo.\n\n$title"), null, object : AiBridge.Listener {
                    override fun onDone(text: String, model: String) { offer(text.trim().trim('"', '«', '»').take(80)) }
                    override fun onError(message: String, noKey: Boolean) { offer(title.take(80)) }
                })
            } else offer(title.take(80))
        }
    }

    private fun summarizeEmail() {
        if (!aiReady()) return
        readPage { title, url, text ->
            showAiSheet(
                title = "Email",
                context = pageContext(title, url, text),
                firstQuestion = "Questa è una pagina di Gmail. Riassumi l'email aperta in italiano: chi scrive, cosa chiede, " +
                    "scadenze o azioni da fare. Se ci sono più email, riassumi l'ultima conversazione aperta.",
                showFirstQuestion = false,
                hint = "Es. scrivimi una risposta cortese"
            )
        }
    }

    // ======================= Loyalty cards =======================

    private val cards by lazy { JsonStore.cards(this) }

    /** Full-screen code at maximum brightness, for the checkout scanner. */
    private fun showCodeFullscreen(title: String, code: String, format: String, color: Int) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = title; textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setColor(color) }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(36) }
        })
        val width = resources.displayMetrics.widthPixels - dp(40)
        val bmp = Barcodes.render(code, format, if (Barcodes.is2D(format)) (width * 0.8).toInt() else width)
        root.addView(ImageView(this).apply {
            if (bmp != null) setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
        })
        root.addView(TextView(this).apply {
            text = if (bmp == null) "Codice non valido per questo formato:\n$code" else code
            textSize = 22f; letterSpacing = 0.12f; setTextColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(16), 0, dp(24))
            setTextIsSelectable(true)
        })
        root.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Chiudi"; isAllCaps = false; setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(android.graphics.Color.WHITE); addView(root) })
        dialog.window?.apply {
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            attributes = attributes.apply { screenBrightness = 1f }
            statusBarColor = android.graphics.Color.WHITE
            addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        dialog.window?.let { WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = true }
        dialog.show()
    }

    private fun showCardsPage() {
        openPage("Carte fedeltà", "Aggiungi", { _ -> addCardDialog() }) { _, body, dialog ->
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            fun render() {
                body.removeAllViews()
                val list = cards.load()
                if (list.isEmpty()) {
                    emptyLabel(body, "Nessuna carta.\nTocca «Aggiungi» e inquadra il codice a barre della tessera.")
                    return
                }
                val grid = android.widget.GridLayout(this).apply { columnCount = 2; setPadding(dp(4), dp(8), dp(4), 0) }
                list.sortedBy { it.name.lowercase() }.forEach { c ->
                    grid.addView(TextView(this).apply {
                        text = c.name; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.WHITE); gravity = android.view.Gravity.CENTER
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(c.color) }
                        layoutParams = android.widget.GridLayout.LayoutParams().apply {
                            width = 0; height = dp(96)
                            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                            setMargins(dp(5), dp(5), dp(5), dp(5))
                        }
                        setOnClickListener { showCodeFullscreen(c.name, c.code, c.format, c.color) }
                        setOnLongClickListener {
                            MaterialAlertDialogBuilder(this@MainActivity).setTitle(c.name)
                                .setItems(arrayOf("Mostra", "Rinomina", "Elimina")) { _, w ->
                                    when (w) {
                                        0 -> showCodeFullscreen(c.name, c.code, c.format, c.color)
                                        1 -> {
                                            val et = TextInputEditText(this@MainActivity).apply { setText(c.name); setSingleLine() }
                                            val box = android.widget.FrameLayout(this@MainActivity).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
                                            MaterialAlertDialogBuilder(this@MainActivity).setTitle("Nome").setView(box)
                                                .setPositiveButton("Salva") { _, _ ->
                                                    val n = et.text?.toString()?.trim().orEmpty()
                                                    if (n.isNotEmpty()) { cards.save(cards.load().map { if (it.id == c.id) it.copy(name = n) else it }); render() }
                                                }.setNegativeButton("Annulla", null).show()
                                        }
                                        2 -> confirmClear("Eliminare la carta ${c.name}?") { cards.save(cards.load().filter { it.id != c.id }); render() }
                                    }
                                }.show()
                            true
                        }
                    })
                }
                body.addView(grid)
                body.addView(TextView(this).apply {
                    text = "Tocca una carta per mostrarla alla cassa (luminosità al massimo). Tieni premuto per modificarla."
                    textSize = 13f; setTextColor(AppRepository.palette(this@MainActivity).textMuted); setPadding(dp(10), dp(16), dp(10), 0)
                })
            }
            render()
            cardsRefresh = { render() }
            dialog.setOnDismissListener { cardsRefresh = null }
        }
    }

    private var cardsRefresh: (() -> Unit)? = null

    private fun addCardDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Nuova carta")
            .setItems(arrayOf("📷 Inquadra il codice a barre", "⌨️ Scrivi il numero a mano")) { _, w ->
                if (w == 0) {
                    try {
                        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this).startScan()
                            .addOnSuccessListener { b -> saveCardDialog(b.rawValue.orEmpty(), Barcodes.fromMlKit(b.format)) }
                            .addOnFailureListener { toast("Scanner non disponibile: scrivi il numero a mano") }
                    } catch (_: Throwable) { toast("Scanner non disponibile: scrivi il numero a mano") }
                } else saveCardDialog("", null)
            }.show()
    }

    private fun saveCardDialog(code: String, format: String?) {
        val d = resources.displayMetrics.density
        val name = TextInputEditText(this).apply { hint = "Nome (es. Esselunga, Coop, Decathlon)"; setSingleLine() }
        val num = TextInputEditText(this).apply { hint = "Numero della carta"; setSingleLine(); setText(code) }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(name); if (format == null) addView(num)
        }
        var chosen = format ?: "EAN_13"
        val b = MaterialAlertDialogBuilder(this).setTitle(if (format == null) "Carta a mano" else "Codice letto: $code").setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val n = name.text?.toString()?.trim().orEmpty().ifBlank { "Carta" }
                val c = num.text?.toString()?.trim().orEmpty()
                if (c.isEmpty()) { toast("Manca il numero"); return@setPositiveButton }
                if (format == null) chosen = when {
                    Regex("^\\d{13}$").matches(c) -> "EAN_13"
                    Regex("^\\d{8}$").matches(c) -> "EAN_8"
                    Regex("^\\d{12}$").matches(c) -> "UPC_A"
                    else -> "CODE_128"
                }
                val color = Barcodes.palette[(n.lowercase().hashCode() and 0x7fffffff) % Barcodes.palette.size]
                cards.save(cards.load() + LoyaltyCard(UUID.randomUUID().toString(), n, c, chosen, color))
                toast("💳 Carta $n salvata")
                cardsRefresh?.invoke()
            }
            .setNegativeButton("Annulla", null)
        b.show()
    }

    // ======================= Tickets & documents (offline) =======================

    private val wallet by lazy { JsonStore.wallet(this) }
    private val walletDir by lazy { java.io.File(filesDir, "wallet").apply { mkdirs() } }

    private fun addToWallet(title: String, kind: String, source: java.io.File?, bytes: ByteArray? = null, ext: String = "pdf", format: String? = null, text: String? = null) {
        io.execute {
            val id = UUID.randomUUID().toString()
            val path = when {
                text != null -> text
                else -> {
                    val dest = java.io.File(walletDir, "$id.$ext")
                    runCatching { if (source != null) source.copyTo(dest, overwrite = true) else dest.writeBytes(bytes!!) }
                        .onFailure { runOnUiThread { toast("Non salvato") }; return@execute }
                    dest.absolutePath
                }
            }
            wallet.save(listOf(WalletItem(id, title, kind, path, System.currentTimeMillis(), format)) + wallet.load())
            runOnUiThread { toast("🎫 Salvato in Biglietti e documenti"); walletRefresh?.invoke() }
        }
    }

    private var walletRefresh: (() -> Unit)? = null

    private fun saveCurrentToWallet() {
        val url = webView.url ?: return
        if (isPdfViewer(url)) {
            val file = Uri.parse(url).getQueryParameter("file")?.substringAfterLast('/')?.let { java.io.File(pdfDir, it) }
            if (file == null || !file.exists()) { toast("PDF non trovato"); return }
            askWalletTitle(pdfName(url)) { t -> addToWallet(t, "pdf", file) }
            return
        }
        val bmp = runCatching {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { webView.draw(android.graphics.Canvas(it)) }
        }.getOrNull() ?: return
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        askWalletTitle(webView.title ?: "Pagina") { t -> addToWallet(t, "image", null, out.toByteArray(), "png") }
    }

    private fun askWalletTitle(default: String, onOk: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { setText(default.take(60)); setSingleLine(); selectAll() }
        val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
        MaterialAlertDialogBuilder(this).setTitle("Nome del biglietto o documento").setView(box)
            .setPositiveButton("Salva") { _, _ -> onOk(et.text?.toString()?.trim().orEmpty().ifBlank { default }) }
            .setNegativeButton("Annulla", null).show()
    }

    private val walletPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val mime = contentResolver.getType(uri).orEmpty()
        val name = displayName(uri).substringBeforeLast('.')
        io.execute {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            runOnUiThread {
                if (bytes == null) { toast("File non leggibile"); return@runOnUiThread }
                val isPdf = mime.contains("pdf")
                askWalletTitle(name) { t -> addToWallet(t, if (isPdf) "pdf" else "image", null, bytes, if (isPdf) "pdf" else "jpg") }
            }
        }
    }

    private fun showImageFullscreen(path: String) {
        val dialog = androidx.appcompat.app.AppCompatDialog(this, AppRepository.accentStyle(this))
        val img = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER
            load(java.io.File(path))
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(img)
        dialog.window?.apply {
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            attributes = attributes.apply { screenBrightness = 1f }
        }
        dialog.show()
    }

    private fun openWalletItem(w: WalletItem) {
        when (w.kind) {
            "pdf" -> {
                val f = java.io.File(w.path)
                if (!f.exists()) { toast("File mancante"); return }
                val viewer = MiaAi.ORIGIN + "/assets/pdf/viewer.html?file=" + Uri.encode("/files/wallet/" + f.name) + "&name=" + Uri.encode(w.title)
                if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false)
                openInBrowser(viewer)
            }
            "image" -> showImageFullscreen(w.path)
            "code" -> showCodeFullscreen(w.title, w.path, w.format ?: "QR_CODE", 0xFF3949AB.toInt())
        }
    }

    private fun showWalletPage() {
        openPage("Biglietti e documenti", "Aggiungi", { _ ->
            MaterialAlertDialogBuilder(this).setTitle("Aggiungi")
                .setItems(arrayOf("📄 PDF o foto dal telefono", "📷 Inquadra un QR o codice a barre", "ℹ️ Da una pagina aperta")) { _, w ->
                    when (w) {
                        0 -> walletPicker.launch(arrayOf("application/pdf", "image/*"))
                        1 -> try {
                            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this).startScan()
                                .addOnSuccessListener { b ->
                                    askWalletTitle("Biglietto") { t -> addToWallet(t, "code", null, format = Barcodes.fromMlKit(b.format), text = b.rawValue.orEmpty()) }
                                }
                        } catch (_: Throwable) { toast("Scanner non disponibile") }
                        2 -> MaterialAlertDialogBuilder(this).setMessage("Apri il biglietto (PDF o pagina con il QR) nel browser, poi menu ⋮ → «🎫 Salva nei biglietti».")
                            .setPositiveButton("OK", null).show()
                    }
                }.show()
        }) { _, body, dialog ->
            fun render() {
                body.removeAllViews()
                val list = wallet.load()
                if (list.isEmpty()) {
                    emptyLabel(body, "Niente salvato.\nBiglietti del treno, carte d'imbarco, biglietti di concerti:\nsi aprono anche senza connessione.")
                    return
                }
                list.forEach { w ->
                    val icon = when (w.kind) { "pdf" -> "📄"; "image" -> "🖼"; else -> "▦" }
                    listRow(body, "$icon  ${w.title}", "Salvato ${NewsAdapter.ago(w.time)} · disponibile offline",
                        onClick = { if (w.kind == "pdf") dialog.dismiss(); openWalletItem(w) },
                        onLong = {
                            MaterialAlertDialogBuilder(this).setTitle(w.title).setItems(arrayOf("Apri", "Condividi")) { _, i ->
                                if (i == 0) { if (w.kind == "pdf") dialog.dismiss(); openWalletItem(w) }
                                else if (w.kind == "code") copyText("Codice", w.path)
                                else runCatching {
                                    val f = java.io.File(w.path)
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (w.kind == "pdf") "application/pdf" else "image/png")
                                    }
                                    val collection = if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                        else android.provider.MediaStore.Files.getContentUri("external")
                                    val u = contentResolver.insert(collection, values)!!
                                    contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = if (w.kind == "pdf") "application/pdf" else "image/png"
                                        putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Condividi"))
                                }.onFailure { toast("Condivisione non riuscita") }
                            }.show()
                        },
                        onRemove = {
                            confirmClear("Eliminare «${w.title}»?") {
                                if (w.kind != "code") java.io.File(w.path).delete()
                                wallet.save(wallet.load().filter { it.id != w.id }); render()
                            }
                        })
                }
            }
            render()
            walletRefresh = { render() }
            dialog.setOnDismissListener { walletRefresh = null }
        }
    }

    // ======================= Desktop mode per app =======================

    private fun appForUrl(url: String?): AppItem? {
        val h = hostOf(url) ?: return null
        return apps.firstOrNull { a -> hostOf(normalizeUrl(a.url))?.let { it == h || h.endsWith(".$it") } == true }
    }

    // ======================= Checklists (inside notes) =======================

    private fun isChecklist(text: String) = text.lines().any { it.startsWith("☐ ") || it.startsWith("☑ ") }

    private fun showChecklist(noteId: String?, initialTitle: String = "Lista") {
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var id = noteId
        fun load(): Pair<String, MutableList<Pair<Boolean, String>>> {
            val n = notes.load().firstOrNull { it.id == id }
            val lines = n?.text?.lines() ?: listOf(initialTitle)
            val title = lines.firstOrNull { !it.startsWith("☐ ") && !it.startsWith("☑ ") && it.isNotBlank() } ?: initialTitle
            val items = lines.filter { it.startsWith("☐ ") || it.startsWith("☑ ") }.map { (it.startsWith("☑ ")) to it.drop(2) }.toMutableList()
            return title to items
        }
        fun save(title: String, items: List<Pair<Boolean, String>>) {
            val text = (listOf(title) + items.map { (if (it.first) "☑ " else "☐ ") + it.second }).joinToString("\n")
            val list = notes.load()
            val n = list.firstOrNull { it.id == id }
            if (n == null) {
                val nn = Note(UUID.randomUUID().toString(), text, System.currentTimeMillis()); id = nn.id; list.add(nn)
            } else { n.text = text; n.time = System.currentTimeMillis() }
            notes.save(list)
        }
        openPage(load().first, "Svuota spuntati", { _ ->
            val (t, items) = load(); save(t, items.filter { !it.first }); checklistRefresh?.invoke()
        }) { root, body, dialog ->
            val input = EditText(this).apply {
                hint = "Aggiungi (anche più cose: latte, pane, uova)"
                setSingleLine(); textSize = 16f; imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(palette.text); setHintTextColor(palette.textMuted)
                setPadding(dp(18), 0, dp(18), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24 * d; setColor(palette.iconTile); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(48), 1f)
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), dp(8))
                addView(input)
                addView(android.widget.ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_nav_mic); setColorFilter(palette.text); background = null
                    setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                    contentDescription = "Detta"
                    setOnClickListener {
                        noteDictationTarget = input
                        input.tag = "dictating"
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                        }
                        try { noteSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
                    }
                })
            }
            root.addView(row, 1)
            fun render() {
                body.removeAllViews()
                val (title, items) = load()
                if (items.isEmpty()) { emptyLabel(body, "Lista vuota: scrivi o detta qui sopra."); return }
                val ordered = items.withIndex().sortedBy { it.value.first }
                ordered.forEach { (i, it) ->
                    val r = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(6), 0, 0, 0)
                    }
                    r.addView(android.widget.CheckBox(this).apply {
                        isChecked = it.first; text = it.second; textSize = 17f
                        setTextColor(if (it.first) palette.textMuted else palette.text)
                        paintFlags = if (it.first) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                        setOnCheckedChangeListener { _, c -> items[i] = c to items[i].second; save(title, items); body.post { render() } }
                    })
                    r.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_web_close); setColorFilter(palette.textMuted)
                        setPadding(dp(14), dp(14), dp(14), dp(14)); layoutParams = android.widget.LinearLayout.LayoutParams(dp(50), dp(50))
                        setOnClickListener { items.removeAt(i); save(title, items); render() }
                    })
                    body.addView(r)
                }
            }
            fun addFromInput() {
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isEmpty()) return
                val (title, items) = load()
                splitItems(t).forEach { items.add(false to it) }
                save(title, items)
                input.setText("")
                render()
            }
            input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { addFromInput(); true } else false }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    // dictation fills the field: add it right away
                    if (noteDictationTarget === input && (e?.length ?: 0) > 0 && input.tag == "dictating") { input.tag = null; addFromInput() }
                }
            })
            render()
            checklistRefresh = { render() }
            dialog.setOnDismissListener { checklistRefresh = null; notesRefresh?.invoke() }
        }
    }

    private var checklistRefresh: (() -> Unit)? = null

    private fun splitItems(t: String): List<String> =
        t.split(Regex(",|;|\\s+e\\s+|\\n")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
            .map { it.replaceFirstChar { c -> c.uppercase() } }

    /** "aggiungi latte e pane alla lista della spesa" (voice). */
    private fun addToShoppingList(itemsText: String, listName: String) {
        val name = listName.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Lista della spesa" }
        val list = notes.load()
        var n = list.firstOrNull { isChecklist(it.text) && it.text.lines().first().trim().equals(name, true) }
        if (n == null) { n = Note(UUID.randomUUID().toString(), name, System.currentTimeMillis()); list.add(n) }
        val add = splitItems(itemsText)
        n.text = (n.text.trimEnd() + "\n" + add.joinToString("\n") { "☐ $it" }).trim()
        n.time = System.currentTimeMillis()
        notes.save(list)
        toast("☑ Aggiunto a $name: ${add.joinToString(", ")}")
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
            "Elimina",
            if (item.desktop) "🖥 Versione desktop: sì (tocca per togliere)" else "🖥 Apri sempre in versione desktop"
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
                    "• Microfono: comandi lunghi, es. «apri Amazon e cerca cuffie bluetooth»\n" +
                    "• Seleziona testo o tocca una casella → «✨ Riscrivi»: migliora, correggi, traduci\n" +
                    "• Menu ⋮ → 🛒 Confronta prezzi, 📧 Riassumi email (in Gmail)\n\n" +
                    "Il testo viene inviato al provider scelto in Mia AI solo quando usi queste funzioni."
                )
                .setPositiveButton("OK", null).show()
        }

        section("COMODITÀ")
        row("📋 Appunti intelligenti", "Propone l'azione per link, pacchi e numeri copiati", switch = toolsPrefs.getBoolean("smartClipboard", true)) {
            toolsPrefs.edit().putBoolean("smartClipboard", !toolsPrefs.getBoolean("smartClipboard", true)).apply(); renderSettings()
        }
        row("⏰ Promemoria", "Anche a voce: «ricordami domani alle 9 di…»") { showRemindersPage() }
        row("📦 Pacchi", "Traccia le spedizioni") { showParcelsPage() }
        row("💳 Carte fedeltà", "${cards.load().size} carte · mostra il codice alla cassa") { showCardsPage() }
        row("🎫 Biglietti e documenti", "Treni, aerei, eventi: anche offline") { showWalletPage() }
        row("☑ Lista della spesa", "Anche a voce: «aggiungi latte e pane alla lista»") {
            val n = notes.load().firstOrNull { isChecklist(it.text) && it.text.lines().first().trim().equals("Lista della spesa", true) }
            showChecklist(n?.id, "Lista della spesa")
        }
        row("🧮 Calcoli e conversioni", "Scrivi nella ricerca: «15% di 230», «10 km in miglia», «50 dollari in euro»") {
            showTab(TAB_HOME); searchInput.requestFocus()
        }

        section("STRUMENTI")
        row("🌦 Meteo", if (weatherPrefs.getBoolean("hidden", false)) "Nascosto" else weatherPrefs.getString("city", null) ?: "Scegli la città") {
            weatherPrefs.edit().putBoolean("hidden", false).apply(); renderWeather(); setupWeather()
        }
        row("⏱ Tempo di utilizzo", "Quanto usi ogni sito, con limiti giornalieri") { showUsagePage() }
        row("🔄 Sincronizza tra dispositivi", if (repo.getAutoBackupUri() != null) "Collegato a un file di backup" else "Tramite un file su Google Drive") { showSyncHelp() }
        row("🧩 Widget", "Ricerca e Notizie sulla schermata del telefono") {
            MaterialAlertDialogBuilder(this).setTitle("Widget")
                .setMessage("Tieni premuto su uno spazio vuoto della schermata del telefono → Widget → Web Launcher.\n\n" +
                    "• Cerca: barra di ricerca con microfono e notizie\n• Notizie: gli ultimi titoli delle tue fonti")
                .setPositiveButton("OK", null).show()
        }
        row("📝 Note", "Scrivi o detta, riordina con l'AI") { showNotesPage() }
        row("🔖 Leggi dopo", "Articoli salvati, leggibili offline") { showReadLaterPage() }
        row("📷 Scansiona QR code") { scanQr() }
        row("📄 Apri un PDF", "Dal telefono o da Drive") { pdfPicker.launch(arrayOf("application/pdf")) }

        section("NOTIZIE")
        row("Fonti notizie", "${news.enabledIds().size} attive su ${news.allSources().size}") { showNewsSources() }
        row("Aggiungi una fonte", "Da qualsiasi sito con notizie") { addNewsSourceDialog() }

        section("YOUTUBE")
        row("Opzioni YouTube", listOfNotNull(
            "salta annunci".takeIf { ytPrefs.getBoolean("skipAds", true) },
            "SponsorBlock".takeIf { ytPrefs.getBoolean("sponsorBlock", true) },
            "pulita".takeIf { ytPrefs.getBoolean("clean", false) },
            "audio a schermo spento".takeIf { ytPrefs.getBoolean("background", true) },
            "finestrella".takeIf { ytPrefs.getBoolean("autoPip", true) }
        ).joinToString(", ").ifBlank { "Tutto spento" }) { ytOptionsDialog() }
        row("Velocità e qualità", "${ytPrefs.getFloat("speed", 1f)}x · ${ytPrefs.getString("quality", "auto")}") { ytSpeedDialog() }
        row("Canali seguiti", "${ytChannels().size} · in Ascolta → Video") { audioTab = "video"; showTab(TAB_AUDIO) }

        section("APP INTEGRATE")
        val miaOnHome = apps.any { MiaAi.isMiaUrl(it.url) }
        row("Mia AI", "Chat AI personale: Gemini, OpenRouter, Groq, Ollama…",
            value = if (miaOnHome) "Apri" else "Aggiungi") {
            if (miaOnHome) openInBrowser(MiaAi.URL) else { addMiaToHome(); renderSettings() }
        }

        section("SICUREZZA E PRIVACY")
        row("⚠️ Avviso siti truffa", "Avvisa prima di aprire siti che imitano Poste, banche, corrieri…", switch = toolsPrefs.getBoolean("phishing", true)) {
            toolsPrefs.edit().putBoolean("phishing", !toolsPrefs.getBoolean("phishing", true)).apply(); renderSettings()
        }
        row("🕵️ Profilo 2", if (IsolatedBrowserActivity.supported()) "Navigazione separata: altri login, es. un secondo Gmail"
            else "Richiede «Android System WebView» aggiornato") {
            startActivity(Intent(this, IsolatedBrowserActivity::class.java))
        }
        row("Cancella dati del profilo 2", danger = true) {
            confirmClear("Cancellare cookie e login del profilo 2?") { IsolatedBrowserActivity.clear(); toast("Profilo 2 pulito") }
        }
        row("🧹 Pulizia automatica", "Ogni settimana cancella cookie e dati dei siti non visitati da 30 giorni (non delle tue app)",
            switch = toolsPrefs.getBoolean("autoClean", true)) {
            toolsPrefs.edit().putBoolean("autoClean", !toolsPrefs.getBoolean("autoClean", true)).apply(); renderSettings()
        }
        row("🧹 Pulisci ora") { val n = autoCleanup(force = true); toast(if (n == 0) "Niente da pulire" else "Puliti i dati di $n siti") }
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

    private fun backupJson(): String = fullBackupJson()

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
        val body = json.replace(Regex("\"savedAt\":\\d+,?"), "")
        if (body.hashCode() == syncPrefs.getInt("hash", 0)) return
        val savedAt = Regex("\"savedAt\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
        syncPrefs.edit().putInt("hash", body.hashCode()).putLong("written", savedAt).apply()
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
        noteHostVisit(url)
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
                    runCatching { applyBackupExtras(org.json.JSONObject(text)) }
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
        private const val TAB_AUDIO = 4
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_WIDGET = "widget_action"
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
