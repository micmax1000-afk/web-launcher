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

    lateinit var repo: AppRepository
    lateinit var adapter: LauncherAdapter
    lateinit var searchInput: EditText
    lateinit var webSearchHint: TextView
    lateinit var recycler: RecyclerView
    lateinit var homePanel: View
    lateinit var browserPanel: View
    lateinit var webView: WebView
    lateinit var recentsPanel: View
    lateinit var recentsList: android.widget.LinearLayout
    lateinit var webTitle: TextView
    lateinit var webAddress: EditText
    lateinit var tabCount: TextView
    lateinit var suggestPanel: View
    lateinit var suggestList: android.widget.LinearLayout
    lateinit var browserToolbar: View
    lateinit var bottomBar: View
    @Volatile var blockAdsOn = true
    var hideBarsOn = true
    var barsHidden = false
    var scrollAccum = 0
    var suggestToken = 0
    val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var relockPending = false
    var webPaused = false
    /** Disk work (history, backup) off the UI thread: keeps scrolling and page loads smooth. */
    val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    var lastRecorded = ""
    val ai by lazy { AiBridge(findViewById(android.R.id.content), assetLoader) }
    val assetLoader by lazy {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/pdf/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, pdfDir))
            .addPathHandler("/files/wallet/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, walletDir))
            .addPathHandler("/files/scan/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, scanDir))
            .addPathHandler("/files/rec/", androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler(this, RecordingService.dir(this)))
            .build()
    }
    var tts: android.speech.tts.TextToSpeech? = null
    var ttsReady = false
    var pendingSpeech: Pair<String, Float>? = null
    var speechReply: androidx.webkit.JavaScriptReplyProxy? = null
    /** Reading aloud: generation counter (a newer reading supersedes the older), who wants to know when it ends, web origin. */
    @Volatile var speakGen = 0
    var speakState: ((Boolean) -> Unit)? = null
    var speakFromWeb = false
    var speakReply: androidx.webkit.JavaScriptReplyProxy? = null
    var ttsWarned = false

    val webSpeechLauncher = registerForActivityResult(
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
    lateinit var addressIcon: ImageView
    lateinit var settingsPanel: View
    lateinit var settingsList: android.widget.LinearLayout
    var mobileUserAgent: String = ""
    val appVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    val webTabs = mutableListOf(WebTab())
    var webTabIndex = 0
    var iconTargetId: String? = null
    lateinit var webProgress: ProgressBar
    lateinit var iconHome: ImageView
    lateinit var labelHome: TextView
    lateinit var iconBrowser: ImageView
    lateinit var labelBrowser: TextView
    lateinit var iconSettings: ImageView
    lateinit var labelSettings: TextView
    val news by lazy { NewsRepository(this) }
    lateinit var newsAdapter: NewsAdapter
    var newsItems: List<NewsItem> = emptyList()
    var newsUpdatedAt = 0L
    var newsLoading = false
    var newsCat = NEWS_ALL
    val audio by lazy { AudioRepository(this) }
    var controller: androidx.media3.session.MediaController? = null
    var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    var audioTab = "podcast"
    var episodes: List<Episode> = emptyList()
    val radioCache = HashMap<String, List<RadioStation>>()
    var radioRegion = "Tutte"

    var apps: MutableList<AppItem> = mutableListOf()
    var categories: MutableList<String> = mutableListOf()
    var currentTab = TAB_HOME
    var browserHasPage = false
    private var leavePromptShowing = false
    private var suppressLeavePrompt = false

    val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (spoken.isNotEmpty()) handleVoiceText(spoken)
    }

    var bleDone: ((Boolean) -> Unit)? = null
    val blePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> bleDone?.invoke(r.isNotEmpty() && r.values.all { it }) }
    var locationDone: ((Boolean) -> Unit)? = null
    val workoutLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationDone?.invoke(r[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || r[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecognition() else toast(getString(R.string.mic_denied))
    }

    val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeBackupToUri(uri)
    }

    val autoBackupLauncher = registerForActivityResult(
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

    val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readBackupFromUri(uri)
    }

    // --- Web features: file upload, permissions, fullscreen video ---
    var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    var pendingPermissionResult: ((Map<String, Boolean>) -> Unit)? = null
    var customView: View? = null
    var customViewCallback: WebChromeClient.CustomViewCallback? = null

    val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    val sitePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cb = pendingPermissionResult
        pendingPermissionResult = null
        cb?.invoke(granted)
    }

    fun withAndroidPermissions(perms: List<String>, onResult: (Boolean) -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { onResult(true); return }
        if (pendingPermissionResult != null) { onResult(false); return }
        pendingPermissionResult = { res -> onResult(missing.all { res[it] == true }) }
        sitePermissionLauncher.launch(missing.toTypedArray())
    }

    fun siteName(origin: String?): String = try {
        Uri.parse(origin.orEmpty()).host?.removePrefix("www.") ?: "Il sito"
    } catch (_: Exception) { "Il sito" }

    val bookmarkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBookmarks(uri)
    }

    val defaultBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (currentTab == TAB_SETTINGS) renderSettings() }

    val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
    /** Every resource lookup (layouts, dialogs, colors) sees the app's light/dark choice, even after WebView reset it. */
    override fun getResources(): android.content.res.Resources {
        val r = super.getResources()
        if (baseContext != null) runCatching { AppRepository.enforceUiMode(this, r) }
        return r
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        runCatching { edgeSwipe(ev) }
        return super.dispatchTouchEvent(ev)
    }

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
        syncWebViewTheme()
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
        findViewById<View>(R.id.btnEmptyAdd).setOnClickListener {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("📚 Scegli dal catalogo", "✏️ Aggiungi a mano")) { _, w ->
                if (w == 0) showCatalogPage() else showAddEditDialog(null)
            }.show()
        }
        setupWebView()
        setupLauncher()
        setupNews()
        setupAudio()
        findViewById<View>(R.id.homeToolsScroll).isVisible = false
        applyHomeLook()
        renderWeather()
        renderParkBar()
        io.execute { runCatching { Deadlines.advance(this) }; runCatching { Finance.applyRecurring(this) } }
        runCatching { AlertsWorker.schedule(this) }
        runCatching { FullBackup.schedule(this) }
        runCatching { Updater.schedule(this) }
        runCatching { Stocks.schedule(this) }
        runCatching { Brief.schedule(this) }
        runCatching { Evening.schedule(this) }
        if (android.os.Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
            runCatching { if (Steps.available(this)) { Steps.schedule(this); io.execute { runCatching { Steps.sampleNow(this) } } } }
        io.execute { runCatching { Reminders.rescheduleAll(this) } }
        runCatching { SiteCookies.clearAll(this) }
        uiHandler.postDelayed({ runCatching { autoCleanup() } }, 8000)
        CrashReporter.pending(this)?.let { report -> uiHandler.postDelayed({ showCrashReport(report) }, 600) }
        uiHandler.postDelayed({ runCatching { showNewsAfterUpdate() } }, 900)
        setupNav()
        setupBack()
        applyBarPosition()
        setupDownloadDone()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateWebHint(s?.toString().orEmpty())
                updateCalcHint(s?.toString().orEmpty())
                updateUnifiedSearch(s?.toString().orEmpty())
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
            TAB_TOOLS -> TAB_TOOLS
            else -> TAB_HOME
        })
        if (savedInstanceState == null) openShortcut(intent)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
    }

    /** Colours from the runtime palette: WebView can flip resource colours to the system theme. */
    fun styleChrome(palette: AppRepository.Companion.UiPalette) {
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

    fun setupLauncher() {
        adapter = LauncherAdapter(
            onClick = { openApp(it) },
            onMenu = { item -> showAppMenu(item) },
            onHeader = { name -> showCategoryMenu(name) },
            onReorder = { fromId, toId -> applyReorder(fromId, toId) },
            onFolder = { name, items -> showFolder(name, items) }
        )
        adapter.spanCount = homePrefs.getInt("cols", 4)
        adapter.hideNames = homePrefs.getBoolean("hideNames", false)
        val glm = GridLayoutManager(this, adapter.spanCount)
        glm.spanSizeLookup = adapter.spanSizeLookup()
        recycler.layoutManager = glm
        recycler.adapter = adapter
        adapter.attachTo(recycler)
        refreshList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWebView() {
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
        setupWebMusic()
        setupCookieBanners()
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
                    if (request?.isForMainFrame == true && request?.hasGesture() == true && request?.isRedirect == false &&
                        hostOf(url) != hostOf(view?.url) && maybeOpenInApp(url) { view?.loadUrl(url) }) return true
                    return false
                }
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true } catch (_: Exception) { true }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (request == null) return null
                if (request.url?.host == MiaAi.HOST) return assetLoader.shouldInterceptRequest(request.url)
                if (!blockAdsOn) return null
                return AdBlocker.intercept(request.url?.host, request.isForMainFrame, pageHost)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                AdBlocker.resetCount()
                if (speakFromWeb) stopSpeaking()          // leaving the page: stop its voice
                pageHost = runCatching { Uri.parse(url).host }.getOrNull()
                showBars()
                if (MiaAi.isMiaUrl(url) &&
                    !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
                ) view?.evaluateJavascript(MiaAi.script(AppRepository.isLightUi(this@MainActivity)), null)
                webProgress.visibility = View.VISIBLE; webProgress.progress = 0
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                webProgress.visibility = View.INVISIBLE
                if (YouTube.isYouTube(url) && !ytDocStartSupported()) view?.evaluateJavascript(YouTube.script(ytConfig()), null)
                if (!ytDocStartSupported() && CookieBanners.enabled(this@MainActivity)) view?.evaluateJavascript(CookieBanners.SCRIPT, null)
                io.execute { PrivacyStats.flush(this@MainActivity) }
                runCatching { maybeSuggestPasswords(view) }
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

    fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as android.widget.FrameLayout).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, save: Boolean = false) {
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
                (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req).let { myDownloads += it }
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

    fun saveDataUrl(dataUrl: String, fallbackName: String) {
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

    var pendingDataDownload: ByteArray? = null
    val dataDownloadLauncher = registerForActivityResult(
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

    fun setupNav() {
        findViewById<View>(R.id.navHome).setOnClickListener { showTab(TAB_HOME) }
        findViewById<View>(R.id.navBrowser).setOnClickListener { showTab(TAB_BROWSER) }
        findViewById<View>(R.id.navNews).setOnClickListener { showTab(TAB_NEWS) }
        findViewById<View>(R.id.navAudio).setOnClickListener { showTab(TAB_AUDIO) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showTab(TAB_TOOLS) }
    }

    fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    currentTab == TAB_BROWSER && webView.canGoBack() -> webView.goBack()
                    currentTab == TAB_BROWSER -> showTab(TAB_HOME)
                    currentTab == TAB_SETTINGS -> showTab(TAB_TOOLS)
                    currentTab == TAB_TOOLS -> showTab(TAB_HOME)
                    currentTab == TAB_NEWS -> showTab(TAB_HOME)
                    currentTab == TAB_AUDIO -> showTab(TAB_HOME)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    fun showTab(tab: Int) {
        if (!suppressLeavePrompt && homePrefs.getBoolean("askClearOnLeave", false) && currentTab == TAB_BROWSER && tab != TAB_BROWSER &&
            (browserHasPage || repo.loadHistory().isNotEmpty())
        ) {
            if (leavePromptShowing) return
            leavePromptShowing = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Chiudi il browser")
                .setMessage("Vuoi cancellare la cronologia?")
                .setPositiveButton("Cancella") { _, _ ->
                    leavePromptShowing = false
                    clearHistoryAndRecents()
                    suppressLeavePrompt = true
                    showTab(tab)
                    suppressLeavePrompt = false
                }
                .setNegativeButton("Tieni") { _, _ ->
                    leavePromptShowing = false
                    suppressLeavePrompt = true
                    showTab(tab)
                    suppressLeavePrompt = false
                }
                .setNeutralButton("Annulla") { _, _ -> leavePromptShowing = false }
                .setOnCancelListener { leavePromptShowing = false }
                .show()
            return
        }
        AppRepository.restoreUiMode(this)
        if (tab != TAB_BROWSER && speakFromWeb) stopSpeaking()   // the page's voice must not go on in another screen
        syncWebViewTheme()
        currentTab = tab
        showBars()
        hideSuggestions()
        homePanel.isVisible = tab == TAB_HOME
        findViewById<View>(R.id.homeWallpaper).isVisible = tab == TAB_HOME && homePrefs.getBoolean("wallpaper", false) &&
            java.io.File(filesDir, "wallpaper.jpg").exists()
        browserPanel.isVisible = tab == TAB_BROWSER
        settingsPanel.isVisible = tab == TAB_SETTINGS
        findViewById<View>(R.id.toolsPanel).isVisible = tab == TAB_TOOLS
        if (tab == TAB_TOOLS) renderTools()
        findViewById<View>(R.id.newsPanel).isVisible = tab == TAB_NEWS
        findViewById<View>(R.id.audioPanel).isVisible = tab == TAB_AUDIO
        if (tab == TAB_AUDIO) onAudioShown()
        if (tab == TAB_HOME) { refreshWeather(); renderParkBar(); renderWeather() }
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
        val toolsActive = tab == TAB_SETTINGS || tab == TAB_TOOLS
        iconSettings.setColorFilter(if (toolsActive) accent else muted)
        labelSettings.setTextColor(if (toolsActive) accent else muted)
        // only the active tab is bold (Home used to stay bold whatever the tab)
        listOf(labelHome to (tab == TAB_HOME), labelBrowser to (tab == TAB_BROWSER), findViewById<TextView>(R.id.labelNews) to (tab == TAB_NEWS),
            findViewById<TextView>(R.id.labelAudio) to (tab == TAB_AUDIO), labelSettings to toolsActive)
            .forEach { (l, on) -> l.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
    }

    fun openInBrowser(url: String) {
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
    fun ensureTabLoaded() {
        val tab = webTabs.getOrNull(webTabIndex) ?: return
        val loaded = webView.url
        if (tab.url.startsWith("http") && (loaded.isNullOrBlank() || loaded == "about:blank") && tab.state == null) {
            applyTabChrome()
            browserHasPage = true
            webView.loadUrl(tab.url)
            if (!webAddress.isFocused) webAddress.setText(displayUrl(tab.url))
        }
    }

    fun restoreSavedTabs() {
        val (all, active0) = repo.loadTabs()
        // chiusura automatica delle schede non usate da tanto (scelta in Impostazioni → Browser)
        val days = homePrefs.getInt("tabAutoCloseDays", 0)
        val now = System.currentTimeMillis()
        val activeUrl = all.getOrNull(active0)?.url
        val saved = if (days <= 0) all else all.filter { it.lastUsed == 0L || now - it.lastUsed < days * 86_400_000L || it.url == activeUrl }
        if (saved.size < all.size) uiHandler.postDelayed({ toast("Chiuse ${all.size - saved.size} schede non usate da più di $days giorni") }, 1500)
        if (saved.isEmpty()) return
        val active = saved.indexOfFirst { it.url == activeUrl }.coerceAtLeast(0)
        webTabs.clear()
        saved.forEach { webTabs.add(WebTab(title = it.title, url = it.url, desktop = it.desktop, group = it.group, lastUsed = if (it.lastUsed > 0) it.lastUsed else now)) }
        webTabIndex = active.coerceIn(0, webTabs.lastIndex)
        browserHasPage = true
    }

    fun persistTabs() {
        if (!::repo.isInitialized) return
        runCatching { saveCurrentTab() }
        val keep = webTabs.withIndex().filter {
            !it.value.privateMode && it.value.url.startsWith("http") && !isLockedUrl(it.value.url)
        }
        val active = keep.indexOfFirst { it.index == webTabIndex }.coerceAtLeast(0)
        repo.saveTabs(keep.map { SavedTab(it.value.url, it.value.title, it.value.desktop, it.value.group, it.value.lastUsed) }, active)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onStop() {
        super.onStop()
        // battery: pages (scripts, ads, feeds) must not keep running in the background,
        // except a YouTube video playing with «audio a schermo spento» or picture-in-picture
        val keepAudio = YouTube.isYouTube(webView.url) && ytPlaying && ytPrefs.getBoolean("background", true)
        if (!keepAudio && !inPip && !isChangingConfigurations) {
            webView.onPause()
            webView.pauseTimers()
            webPaused = true
        }
        vaultDialog?.dismiss()
        if (speakFromWeb && !isChangingConfigurations) stopSpeaking()
        runCatching { forgetClosedSites() }
        io.execute { PrivacyStats.flush(this) }
        if (lockEnabled() && !isChangingConfigurations) lockedSince = System.currentTimeMillis()
        if (::webView.isInitialized && isLockedUrl(webView.url)) relockPending = true
    }

    override fun onStart() {
        super.onStart()
        if (webPaused && ::webView.isInitialized) { webView.onResume(); webView.resumeTimers(); webPaused = false }
        if (lockEnabled() && !inPip) {
            val delay = lockPrefs.getInt("delayMin", 1) * 60_000L
            if (lockedSince == 0L || System.currentTimeMillis() - lockedSince >= delay) { showLockOverlay(); unlockApp() }
        }
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
        syncWebViewTheme()
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

    fun openShortcut(intent: Intent?) {
        intent?.getStringExtra(EXTRA_WIDGET)?.takeIf { it.startsWith("settings_") }?.let { w ->     // «settings_PRIVACY»: a settings page
            intent.removeExtra(EXTRA_WIDGET); settingsPage = w.removePrefix("settings_"); settingsQuery = ""; showTab(TAB_SETTINGS); return
        }
        if (intent != null && handleShare(intent)) { intent.action = null; return }
        // prova automatica del tema (telefono virtuale): imposta un tema, poi apre una finestra di prova
        intent?.getStringExtra(SelfTest.EXTRA_THEME)?.let { mode ->
            intent.removeExtra(SelfTest.EXTRA_THEME)
            if (mode in setOf("light", "dark", "system")) { repo.setThemeMode(mode); AppRepository.applySavedTheme(this) }
            return
        }
        // prova automatica: Mia AI deve seguire il tema dell'app, anche con il sistema nell'altro tema
        if (intent?.getBooleanExtra(SelfTest.EXTRA_MIA, false) == true) {
            intent.removeExtra(SelfTest.EXTRA_MIA)
            openMiaAi()
            uiHandler.postDelayed({
                webView.evaluateJavascript("(function(){try{return document.documentElement.getAttribute('data-theme')+'|'+getComputedStyle(document.body).backgroundColor+'|'+typeof speechSynthesis.cancel+'|'+typeof webkitSpeechRecognition}catch(e){return 'errore '+e}})()") { r ->
                    android.util.Log.i(SelfTest.TAG, "MIA_CHECK=$r")
                }
            }, 6000)
            return
        }
        if (intent?.getBooleanExtra(SelfTest.EXTRA_AI_SHEET, false) == true) {
            intent.removeExtra(SelfTest.EXTRA_AI_SHEET)
            showAiSheet("Prova della finestra AI", null, null, previewAnswer = ("Questa è una risposta lunga per provare lo scorrimento. ".repeat(6) + "\n\n").repeat(14))
            return
        }
        if (intent?.getBooleanExtra(SelfTest.EXTRA_DIALOG, false) == true) {
            intent.removeExtra(SelfTest.EXTRA_DIALOG)
            val probe = com.google.android.material.textfield.TextInputEditText(this).apply { hint = "Suggerimento di prova" }
            MaterialAlertDialogBuilder(this).setTitle("Prova del tema")
                .setMessage("Questa finestra deve avere i colori del tema scelto nell'app.\n".repeat(6))
                .setView(probe).setPositiveButton("OK", null).show()
            probe.post {
                val h = probe.currentHintTextColor; val t = probe.currentTextColor
                fun lum(c: Int) = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)).toInt()
                android.util.Log.i(SelfTest.TAG, "HINT_LUM=${lum(h)} HINT_ALPHA=${android.graphics.Color.alpha(h)} TEXT_LUM=${lum(t)}")
            }
            return
        }
        if (intent?.getBooleanExtra(SelfTest.EXTRA, false) == true) {
            intent.removeExtra(SelfTest.EXTRA); io.execute { SelfTest.run(this) }; return
        }
        if (intent?.getBooleanExtra(Updater.EXTRA_UPDATE, false) == true) {
            intent.removeExtra(Updater.EXTRA_UPDATE); checkForUpdate(manual = true); return
        }
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
            "stocks" -> { intent.removeExtra(EXTRA_WIDGET); showStocksPage(); return }
            "waste" -> { intent.removeExtra(EXTRA_WIDGET); showWastePage(); return }
            "birthdays" -> { intent.removeExtra(EXTRA_WIDGET); showBirthdaysPage(); return }
            "wellness" -> { intent.removeExtra(EXTRA_WIDGET); showWellnessHub(); return }
            "hc" -> { intent.removeExtra(EXTRA_WIDGET); showHealthConnectPage(); return }
            "food" -> { intent.removeExtra(EXTRA_WIDGET); showFoodDiary(); return }
            "water" -> { intent.removeExtra(EXTRA_WIDGET); showWaterPage(); return }
            "calc" -> { intent.removeExtra(EXTRA_WIDGET); showCalorieCalculator(); return }
            "workout" -> { intent.removeExtra(EXTRA_WIDGET); showWorkoutRecorder(); return }
            "plans" -> { intent.removeExtra(EXTRA_WIDGET); showPlansPage(); return }
            "history" -> { intent.removeExtra(EXTRA_WIDGET); showWorkoutHistory(); return }
            "sleep" -> { intent.removeExtra(EXTRA_WIDGET); showSleepPage(); return }
            "meds" -> { intent.removeExtra(EXTRA_WIDGET); showMedsPage(); return }
            "visits" -> { intent.removeExtra(EXTRA_WIDGET); showVisitsPage(); return }
            "emergency" -> { intent.removeExtra(EXTRA_WIDGET); showEmergencyPage(); return }
            "report" -> { intent.removeExtra(EXTRA_WIDGET); showReportPage(); return }
            "music" -> { intent.removeExtra(EXTRA_WIDGET); showMusicPage(); return }
            "nowplaying" -> { intent.removeExtra(EXTRA_WIDGET); showNowPlayingFull(); return }
            "privacytools" -> { intent.removeExtra(EXTRA_WIDGET); showPrivacyTools(); return }
            "backuptools" -> { intent.removeExtra(EXTRA_WIDGET); showBackupTools(); return }
            "flyers" -> { intent.removeExtra(EXTRA_WIDGET); showFlyersPage(); return }
            "passwords" -> { intent.removeExtra(EXTRA_WIDGET); showPasswordInfo(); return }
            "recents" -> { intent.removeExtra(EXTRA_WIDGET); showRecentsPage(); return }
            "hr" -> { intent.removeExtra(EXTRA_WIDGET); showHrPage(); return }
            "body" -> { intent.removeExtra(EXTRA_WIDGET); showBodyPage(); return }
            "symptoms" -> { intent.removeExtra(EXTRA_WIDGET); showSymptomsPage(); return }
            "pdftext" -> {          // automatic test: text extraction of a PDF made on the spot (the AI reads this text)
                intent.removeExtra(EXTRA_WIDGET)
                val f = java.io.File(cacheDir, "prova-testo.pdf")
                runCatching {
                    val doc = android.graphics.pdf.PdfDocument()
                    val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
                    val paint = android.graphics.Paint().apply { textSize = 24f }
                    page.canvas.drawText("Calendario raccolta rifiuti 2026", 50f, 100f, paint)
                    page.canvas.drawText("Lunedi: Umido e Plastica", 50f, 150f, paint)
                    page.canvas.drawText("Giovedi: Carta e Vetro", 50f, 200f, paint)
                    doc.finishPage(page); f.outputStream().use { doc.writeTo(it) }; doc.close()
                }
                extractPdfText(f, 40_000) { t -> android.util.Log.i(SelfTest.TAG, "PDF_TEXT=" + (t?.replace("\n", " | ") ?: "NULL")) }
                return
            }
            "pdfpage" -> {          // automatic test: a small PDF made on the spot, shown by the in-app viewer
                intent.removeExtra(EXTRA_WIDGET)
                val f = java.io.File(cacheDir, "prova.pdf")
                runCatching {
                    val doc = android.graphics.pdf.PdfDocument()
                    val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
                    val paint = android.graphics.Paint().apply { textSize = 28f }
                    page.canvas.drawText("Calendario di prova", 60f, 120f, paint)
                    page.canvas.drawText("Lunedi: Umido   Giovedi: Carta", 60f, 180f, paint)
                    doc.finishPage(page); f.outputStream().use { doc.writeTo(it) }; doc.close()
                }
                openPdfPage(f, "Prova PDF"); return
            }
            "tools" -> { intent.removeExtra(EXTRA_WIDGET); showTab(TAB_TOOLS); return }
            "settings" -> { intent.removeExtra(EXTRA_WIDGET); settingsPage = null; settingsQuery = ""; showTab(TAB_SETTINGS); return }
            "instruments" -> { intent.removeExtra(EXTRA_WIDGET); showInstrumentsPage(); return }
            "tabata" -> { intent.removeExtra(EXTRA_WIDGET); showTabata(); return }
            "altimeter" -> { intent.removeExtra(EXTRA_WIDGET); showAltimeter(); return }
            "ruler" -> { intent.removeExtra(EXTRA_WIDGET); showRuler(); return }
            "metal" -> { intent.removeExtra(EXTRA_WIDGET); showMetalDetector(); return }
            "split" -> { intent.removeExtra(EXTRA_WIDGET); showSplitBill(); return }
            "finance" -> { intent.removeExtra(EXTRA_WIDGET); showFinancePage(); return }
            "weekly" -> { intent.removeExtra(EXTRA_WIDGET); showWeeklySummary(); return }
            "quakes" -> { intent.removeExtra(EXTRA_WIDGET); showQuakes(); return }
            "news" -> { intent.removeExtra(EXTRA_WIDGET); showTab(TAB_NEWS); return }
            "reminders" -> { intent.removeExtra(EXTRA_WIDGET); showRemindersPage(); return }
            "recorder" -> { intent.removeExtra(EXTRA_WIDGET); showRecorderPage(); return }
            "new_note" -> { intent.removeExtra(EXTRA_WIDGET); editNote(null); return }
            "ask" -> { intent.removeExtra(EXTRA_WIDGET); listenForQuestion(); return }
            "park" -> { intent.removeExtra(EXTRA_WIDGET); parkHere(); return }
            "scan" -> { intent.removeExtra(EXTRA_WIDGET); scanDocument(); return }
            "record" -> { intent.removeExtra(EXTRA_WIDGET); showRecorderPage(); if (!RecordingService.isRecording()) startRecording(); return }
            "podcast" -> { intent.removeExtra(EXTRA_WIDGET); audioTab = "podcast"; showTab(TAB_AUDIO); return }
            "brief" -> { intent.removeExtra(EXTRA_WIDGET); showBrief(null); return }
        }
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file") &&
            (intent.type?.contains("pdf") == true || data.path?.endsWith(".pdf", true) == true)
        ) { openPdfFromUri(data); return }
        val external = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        val url = intent?.getStringExtra("shortcut_url") ?: external ?: return
        if (url.isBlank()) return
        if (external != null && intent?.getStringExtra("shortcut_url") == null && maybeOpenInApp(url) {
                if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false)
                openInBrowser(normalizeUrl(url))
            }) return
        if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false)
        openInBrowser(normalizeUrl(url))
    }

    fun goAddress() {
        val raw = webAddress.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        leaveAddressBar()
        val url = if (looksLikeUrl(raw)) normalizeUrl(raw) else searchUrl(raw)
        openInBrowser(url)
    }

    fun currentTab(): WebTab = webTabs[webTabIndex]

    fun saveCurrentTab() {
        if (webTabs.isEmpty()) return
        val tab = webTabs[webTabIndex]
        val bundle = Bundle()
        webView.saveState(bundle)
        tab.state = bundle
        webView.url?.let { if (it != "about:blank") tab.url = it }
        webView.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
        tab.lastUsed = System.currentTimeMillis()
    }

    fun openNewTab(privateMode: Boolean) {
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

    fun closeCurrentTab() {
        if (webTabs.isEmpty()) return
        val closing = webTabs[webTabIndex]
        if (closing.privateMode) {
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
        }
        webTabs.removeAt(webTabIndex)
        runCatching { forgetClosedSites() }
        if (webTabs.isEmpty()) webTabs.add(WebTab())
        webTabIndex = webTabIndex.coerceAtMost(webTabs.lastIndex)
        restoreTab(webTabs[webTabIndex])
        renderTabStrip()
    }

    fun closeTabAt(index: Int) {
        if (index !in webTabs.indices) return
        if (index == webTabIndex) { closeCurrentTab(); return }
        webTabs.removeAt(index)
        if (index < webTabIndex) webTabIndex -= 1
        renderTabStrip()
    }

    fun switchToTab(index: Int) {
        if (index == webTabIndex || index !in webTabs.indices) return
        saveCurrentTab()
        webTabIndex = index
        restoreTab(webTabs[index])
        renderTabStrip()
    }

    fun restoreTab(tab: WebTab) {
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

    fun applyTabChrome() {
        val tab = webTabs.getOrNull(webTabIndex) ?: return
        val s = webView.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else mobileUserAgent + " WebLauncher/$appVersion"
        s.cacheMode = if (tab.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    fun displayUrl(url: String): String = if (MiaAi.isMiaUrl(url)) MiaAi.NAME
        else if (isPdfViewer(url)) "PDF · " + pdfName(url) else try {
        val u = Uri.parse(url)
        val host = u.host?.removePrefix("www.") ?: url
        if (u.scheme == "http") "⚠ $host" else host
    } catch (_: Exception) { url }

    /** Tab counter (Chrome style) + lock/search icon in the address bar. */
    fun renderTabStrip() {
        tabCount.text = if (webTabs.size > 99) ":D" else webTabs.size.toString()
        val url = webView.url.orEmpty()
        addressIcon.setImageResource(
            if (browserHasPage && url.startsWith("https://")) R.drawable.ic_web_lock else R.drawable.ic_search
        )
    }

    fun showTabSwitcher() {
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
        val grouped = webTabs.any { it.group.isNotBlank() }
        if (!grouped && webTabs.size > 1) root.addView(TextView(this).apply {
            text = "Tieni premuta una scheda per metterla in un gruppo (Lavoro, Casa, Viaggi…)"; textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(4), 0, dp(4), dp(10))
        })
        val order = webTabs.indices.sortedWith(compareBy({ if (webTabs[it].group.isBlank()) "\uFFFF" else webTabs[it].group.lowercase() }, { it }))
        var lastGroup: String? = null
        order.forEach { index -> val tab = webTabs[index]
            if (grouped && tab.group != lastGroup) {
                lastGroup = tab.group
                root.addView(TextView(this).apply {
                    text = (if (tab.group.isBlank()) "SENZA GRUPPO" else "📁 " + tab.group.uppercase()) + " · " + webTabs.count { it.group == tab.group }
                    textSize = 12f; letterSpacing = 0.06f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.textMuted); setPadding(dp(4), dp(8), 0, dp(6))
                })
            }
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
                setOnLongClickListener { sheet.dismiss(); chooseTabGroup(tab) { showTabSwitcher() }; true }
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

    fun showBrowserMenu(anchor: View) {
        AppRepository.restoreUiMode(this)
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
        if (hasPage) menuQuickActions(root) { popup.dismiss() }

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
        item("🔖 Leggi dopo", hasPage) { saveCurrentForLater() }
        item("📥 Salva pagina completa offline", hasPage) { saveOfflinePage() }
        item("🍪 Dimentica questo sito alla chiusura", hasPage, SiteCookies.has(this, pageHost ?: Uri.parse(webView.url ?: "").host)) {
            val h = Uri.parse(webView.url ?: "").host ?: return@item
            val on = SiteCookies.toggle(this, h)
            toast(if (on) "Cookie e dati di ${PhishingGuard.registered(h)} cancellati quando chiudi la scheda o l'app" else "Il sito non verrà più dimenticato")
        }
        item("✨ Traduci con AI", hasPage) { translatePageWithAi() }
        item("✨ Scrivi con AI", hasPage) { writeWithAi() }
        item("🛒 Confronta prezzi", hasPage) { comparePrices() }
        item("🍝 Salva ricetta", hasPage) { saveRecipeFromPage() }
        item("Cronologia") { showHistoryPage() }
        item("Download") { showDownloadsPage() }
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
        item("🕘 Recenti") { showRecentsPage() }
        item("Chiudi scheda") { closeCurrentTab() }
        item("Impostazioni") { showTab(TAB_SETTINGS) }
        if (blockAdsOn && hasPage) root.addView(TextView(this).apply {
            text = "🛡 ${AdBlocker.blockedCount} pubblicità e tracker bloccati · report ›"
            setOnClickListener { popup.dismiss(); showPrivacyReport() }
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

    /** WebView resets the theme to the phone's when it is created: put the app's back and tell the WebView. */
    fun syncWebViewTheme() {
        AppRepository.restoreUiMode(this)
        if (::webView.isInitialized) runCatching { webView.dispatchConfigurationChanged(resources.configuration) }
    }

    fun applyDarkSites() {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                webView.settings,
                repo.darkSites() && !AppRepository.isLightUi(this)
            )
        }
    }

    fun openInNewTab(url: String) {
        openNewTab(false)
        openInBrowser(url)
    }

    fun copyText(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Copiato")
    }

    fun showLinkMenu(url: String) {
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

    fun showImageMenu(url: String) {
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

    /**
     * Fingerprint / face / PIN prompt. strict = true refuses when the phone has no screen lock
     * (used to turn a lock on); otherwise the action runs anyway with a warning.
     */
    override fun onDestroy() {
        Music.webControl = null; Music.web = null
        io.shutdown()
        controllerFuture?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        runCatching { ai.destroy() }
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    /**
     * Bottom sheet chat: the page context (if any) is prepended to the first question,
     * follow-up questions keep the whole conversation.
     */
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
    val openRegions = mutableSetOf<String>()

    // ======================= PDF viewer (pdf.js, served from assets) =======================

    val pdfDir by lazy { java.io.File(cacheDir, "pdf").apply { mkdirs() } }

    // ======================= Reader mode =======================

    data class ReaderDoc(val title: String, val site: String, val url: String, val image: String?, val text: String)

    // ======================= Read later =======================

    val readLater by lazy { JsonStore.readLater(this) }

    // ======================= Notes =======================

    val notes by lazy { JsonStore.notes(this) }
    var noteDictationTarget: EditText? = null
    val noteSpeechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val et = noteDictationTarget ?: return@registerForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (r.resultCode == RESULT_OK && text.isNotEmpty()) {
            val cur = et.text?.toString().orEmpty()
            et.setText(if (cur.isBlank()) text.replaceFirstChar { it.uppercase() } else cur.trimEnd() + "\n" + text)
            et.setSelection(et.text.length)
        }
    }

    var notesRefresh: (() -> Unit)? = null

    // ======================= Home tools row =======================

    val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPdfFromUri(uri)
    }
    /** PDF of the rubbish collection calendar (Strumenti → Raccolta rifiuti). */
    val wastePdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWastePdf(uri)
    }
    var wasteRefresh: (() -> Unit)? = null

    val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var playerSheetUpdate: (() -> Unit)? = null
    var sleepAt = 0L
    val sleepRunnable = Runnable { controller?.pause(); sleepAt = 0L; toast("Timer: riproduzione in pausa") }

    // ======================= Weather (Open-Meteo: free, no key) =======================

    val weatherPrefs by lazy { getSharedPreferences("wl_weather", MODE_PRIVATE) }

    // ======================= «Oggi» card: weather + calendar + reminders + deadlines =======================

    data class TodayEntry(val time: Long, val allDay: Boolean, val text: String, val icon: String, val action: (() -> Unit)? = null)

    val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        homePrefs.edit().putBoolean("askedCal", true).apply()
        if (ok) renderWeather() else toast("Calendario non collegato")
    }

    val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) useMyLocationForWeather() else toast("Posizione negata: scrivi il nome della città")
    }

    // ======================= Usage time & daily limits =======================

    val usagePrefs by lazy { getSharedPreferences("wl_usage", MODE_PRIVATE) }
    var usageHost: String? = null
    var usageStart = 0L
    val today get() = java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(java.util.Date())

    var hasWindowFocusOrResumed = false

    // ======================= Sync between devices (backup file on Drive) =======================

    val syncPrefs by lazy { getSharedPreferences("wl_sync", MODE_PRIVATE) }

    val syncOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { toast("Questo file non può essere aggiornato: scegline uno su Drive"); return@registerForActivityResult }
        repo.setAutoBackupUri(uri.toString())
        syncPrefs.edit().putLong("written", 0L).putLong("applied", 0L).putLong("checked", 0L).apply()
        toast("Collegato: controllo i dati…")
        checkSyncFromOtherDevice()
    }

    // ======================= YouTube =======================

    val ytPrefs by lazy { getSharedPreferences("wl_yt", MODE_PRIVATE) }
    var ytScript: androidx.webkit.ScriptHandler? = null
    var cookieScript: androidx.webkit.ScriptHandler? = null
    @Volatile var pageHost: String? = null
    /** Tools page tiles by group: (group, [(label, emoji)]) — filled by renderTools, used by customizeTools. */
    var settingsQuery = ""
    var settingsPage: String? = null
    var toolsCatalog: List<Pair<String, List<Pair<String, String>>>> = emptyList()
    var ytPlaying = false
    var ytTranscriptCallback: ((org.json.JSONObject) -> Unit)? = null
    var inPip = false

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

    var ytVideos: List<NewsItem> = emptyList()

    // ======================= Calculator in search =======================

    var calcToken = 0

    // ======================= Smart clipboard =======================

    val toolsPrefs by lazy { getSharedPreferences("wl_tools", MODE_PRIVATE) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentTab == TAB_HOME && toolsPrefs.getBoolean("smartClipboard", true)) checkClipboard()
    }

    // ======================= Phishing warning =======================

    val allowedHosts = mutableSetOf<String>()

    // ======================= AI writing =======================

    val rewriteGetJs = """
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

    // ======================= Loyalty cards =======================

    val cards by lazy { JsonStore.cards(this) }

    var cardsRefresh: (() -> Unit)? = null

    // ======================= Tickets & documents (offline) =======================

    val wallet by lazy { JsonStore.wallet(this) }
    val walletDir by lazy { java.io.File(filesDir, "wallet").apply { mkdirs() } }

    var walletRefresh: (() -> Unit)? = null

    val walletPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

    var checklistRefresh: (() -> Unit)? = null

    // ======================= Web app catalog =======================

    data class CatalogApp(val name: String, val url: String, val cat: String, val desktop: Boolean = false, val note: String? = null)

    val catalog: List<CatalogApp> by lazy {
        runCatching {
            val json = assets.open("catalog.json").use { it.readBytes().toString(Charsets.UTF_8) }
            com.google.gson.Gson().fromJson<List<CatalogApp>>(json, object : com.google.gson.reflect.TypeToken<List<CatalogApp>>() {}.type)
        }.getOrNull().orEmpty()
    }

    // ======================= Document scanner (Google, no camera permission) =======================

    val scanDir by lazy { java.io.File(cacheDir, "scan").apply { mkdirs() } }

    val docScanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@registerForActivityResult
        val pdfUri = res.pdf?.uri ?: return@registerForActivityResult
        val pages = res.pages?.map { it.imageUri } ?: emptyList()
        io.execute {
            val stamp = System.currentTimeMillis()
            val pdf = java.io.File(scanDir, "scan_$stamp.pdf")
            runCatching { contentResolver.openInputStream(pdfUri)!!.use { i -> pdf.outputStream().use { i.copyTo(it) } } }
            val images = pages.take(10).mapIndexedNotNull { i, u ->
                runCatching {
                    val f = java.io.File(scanDir, "scan_${stamp}_$i.jpg")
                    val bmp = contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                    val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
                    val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    f
                }.getOrNull()
            }
            runOnUiThread {
                if (scanPurpose == "expense") { scanPurpose = null; readReceipt(images, pdf) }
                else scanDone(pdf, images, res.pages?.size ?: images.size)
            }
        }
    }

    // ======================= E-books =======================

    val epubPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        toast("Importo il libro…")
        io.execute {
            val res = runCatching { Ebooks.import(this, uri) }
            runOnUiThread {
                res.onSuccess { b -> toast("📖 ${b.title}"); booksRefresh?.invoke(); openBook(b) }
                    .onFailure { e -> toast(e.message?.takeIf { it.length < 80 } ?: "Non è un EPUB valido") }
            }
        }
    }

    var booksRefresh: (() -> Unit)? = null

    // ======================= Voice recorder with AI transcription =======================

    val recPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast("Serve il permesso microfono per registrare")
    }

    var recorderRefresh: (() -> Unit)? = null

    // ======================= Home look: columns, names, wallpaper, folders =======================

    val homePrefs by lazy { getSharedPreferences("wl_home", MODE_PRIVATE) }

    val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        io.execute {
            val ok = runCatching {
                val bmp = contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
                val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                java.io.File(filesDir, "wallpaper.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }.isSuccess
            runOnUiThread {
                if (ok) { homePrefs.edit().putBoolean("wallpaper", true).apply(); applyHomeLook(); toast("Sfondo impostato") }
                else toast("Immagine non valida")
                if (currentTab == TAB_SETTINGS) renderSettings()
            }
        }
    }

    // ======================= Camera capture (FileProvider) =======================

    var captureTarget: java.io.File? = null
    var captureDone: ((java.io.File?) -> Unit)? = null

    val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = captureTarget
        val cb = captureDone
        captureTarget = null; captureDone = null
        cb?.invoke(if (ok && f != null && f.exists() && f.length() > 0) f else null)
    }

    val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) captureTarget?.let { launchCamera(it) } else { toast("Serve il permesso fotocamera"); captureDone?.invoke(null) }
    }

    // ======================= Expenses & bills =======================

    var expenseMonth = java.util.Calendar.getInstance()
    var expensesRefresh: (() -> Unit)? = null
    var financeRefresh: (() -> Unit)? = null
    var birthdaysRefresh: (() -> Unit)? = null
    var wellnessRefresh: (() -> Unit)? = null
    var foodRefresh: (() -> Unit)? = null
    var hcRefresh: (() -> Unit)? = null
    /** Health Connect permission request: runs [hcRefresh] and a new reading when the user answers. */
    val hcPermission = registerForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { _ ->
        syncHealthConnect { hcRefresh?.invoke(); wellnessRefresh?.invoke() }
    }
    var scanPurpose: String? = null

    // ======================= Parking & saved places =======================

    var placesRefresh: (() -> Unit)? = null
    var pendingLocation: ((android.location.Location?) -> Unit)? = null

    val placeLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val cb = pendingLocation; pendingLocation = null
        if (r.values.any { it }) currentLocation(cb ?: return@registerForActivityResult) else { toast("Serve il permesso posizione"); cb?.invoke(null) }
    }

    // ======================= Vault =======================

    var vaultDialog: androidx.appcompat.app.AppCompatDialog? = null
    var vaultRefresh: (() -> Unit)? = null

    val vaultPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        io.execute {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            runOnUiThread { if (bytes == null) toast("Immagine non leggibile") else askVaultTitle { t -> saveVault(t, "photo", bytes) } }
        }
    }

    // ======================= Deadlines, subscriptions, warranties =======================

    var deadlineTab = Deadlines.DEADLINE
    var deadlinesRefresh: (() -> Unit)? = null

    val dayFmt get() = java.text.SimpleDateFormat("d MMM yyyy", Locale.ITALIAN)

    // ======================= Links that have an app on the phone =======================

    val openInPrefs by lazy { getSharedPreferences("wl_openin", MODE_PRIVATE) }

    // ======================= System voice commands =======================

    /** One-shot permission request for the measuring tools: runs [pendingPermAction] when everything is granted. */
    var pendingPermAction: (() -> Unit)? = null
    val toolPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val go = pendingPermAction; pendingPermAction = null
        if (r.values.any { it }) go?.invoke() else toast("Permesso negato: lo strumento non può funzionare")
    }
    var pendingContactCommand: String? = null
    val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cmd = pendingContactCommand; pendingContactCommand = null
        if (ok && cmd == "__birthdays__") { birthdaysRefresh?.invoke() ?: showBirthdaysPage() }
        else if (ok && cmd != null) systemVoiceCommand(cmd) else if (!ok) toast("Serve il permesso contatti")
    }
    var torchState = false

    // ======================= Ask by voice (spoken answer) =======================

    var voiceAskMode = false
    val askSpeechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (r.resultCode == RESULT_OK && text.isNotEmpty()) askByVoice(text)
    }

    // ======================= Unified search =======================

    var searchToken = 0

    // ======================= Car mode =======================

    val carPrefs by lazy { getSharedPreferences("wl_car", MODE_PRIVATE) }

    // ======================= App lock =======================

    val lockPrefs by lazy { getSharedPreferences("wl_lock", MODE_PRIVATE) }
    var lockedSince = 0L
    var lockOverlay: View? = null

    // ======================= Full backup UI =======================

    val fullBackupCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        val auto = pendingFullAuto
        pendingFullAuto = false
        if (auto) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            syncPrefs.edit().putString("fullUri", uri.toString()).apply()
            FullBackup.schedule(this)
        }
        toast("Creo il backup completo…")
        io.execute {
            val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { FullBackup.write(this, it) } }.isSuccess
            if (ok) syncPrefs.edit().putLong("fullLast", System.currentTimeMillis()).apply()
            runOnUiThread { toast(if (ok) "✓ Backup completo salvato" + (if (auto) " · si aggiornerà ogni notte" else "") else "Backup non riuscito"); if (currentTab == TAB_SETTINGS) renderSettings() }
        }
    }
    var pendingFullAuto = false

    val fullBackupRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        MaterialAlertDialogBuilder(this).setTitle("Ripristinare il backup completo?")
            .setMessage("App, note, libri, registrazioni, biglietti, spese e impostazioni verranno sostituiti con quelli del backup. Poi l'app si riavvia.")
            .setPositiveButton("Ripristina") { _, _ ->
                toast("Ripristino…")
                io.execute {
                    val res = runCatching { contentResolver.openInputStream(uri)!!.use { FullBackup.restore(this, it) } }
                    runOnUiThread {
                        res.onSuccess {
                            toast("✓ Ripristinati $it file: riavvio")
                            uiHandler.postDelayed({
                                val restart = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(restart)
                                Runtime.getRuntime().exit(0)
                            }, 1200)
                        }.onFailure { e -> toast(e.message ?: "Ripristino non riuscito") }
                    }
                }
            }.setNegativeButton("Annulla", null).show()
    }

    // ======================= Steps =======================

    val stepsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { Steps.schedule(this); io.execute { Steps.sampleNow(this); runOnUiThread { stepsRefresh?.invoke() } } }
        else toast("Serve il permesso «Attività fisica» per contare i passi")
    }
    var stepsRefresh: (() -> Unit)? = null

    // ======================= Health diary =======================

    var healthType = "pressione"

    var healthRefresh: (() -> Unit)? = null

    // ======================= Music on the phone =======================

    data class Song(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val duration: Long)

    var songs: List<Song>? = null
    var musicView = "songs"
    var musicFilter: Pair<String, String>? = null

    val musicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { songs = null; renderAudio() } else toast("Serve il permesso per leggere la musica del telefono")
    }

    /** Full-page settings (bottom-nav tab), grouped cards with large rows. */
    /** "Agenzia ANSA: ultime notizie ... - ANSA" -> "ANSA"; falls back to the host name. */
    class WebTab(
        var title: String = "Nuova scheda",
        var url: String = "",
        var state: Bundle? = null,
        var privateMode: Boolean = false,
        var desktop: Boolean = false,
        var group: String = "",
        var lastUsed: Long = System.currentTimeMillis()
    )

    companion object {
        const val TAB_HOME = 0
        const val TAB_BROWSER = 1
        const val TAB_SETTINGS = 2
        const val STATE_TAB = "current_tab"
        const val TAB_NEWS = 3
        const val TAB_AUDIO = 4
        const val TAB_TOOLS = 5
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_WIDGET = "widget_action"
        const val NEWS_ALL = "Tutte"
        val SUGGEST_TAG = Any()
        const val MENU_AI = 0x5A1
        const val AI_SYSTEM =
            "Sei l'assistente di un browser per smartphone. Rispondi in italiano, in modo chiaro e conciso, " +
                "con elenchi puntati quando aiutano. Niente preamboli."
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
