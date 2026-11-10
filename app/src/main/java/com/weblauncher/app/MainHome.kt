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

// ======================= Tools page (bottom bar) =======================

internal fun MainActivity.renderTools() {
    val list = findViewById<android.widget.LinearLayout>(R.id.toolsList)
    list.removeAllViews()
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    findViewById<View>(R.id.toolsPanel).setBackgroundColor(palette.bg)
    val header = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(12), 0, dp(4))
    }
    header.addView(TextView(this).apply {
        text = "Strumenti"; textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
    })
    header.addView(android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_nav_settings); setColorFilter(palette.text); background = null
        setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        contentDescription = "Impostazioni"
        setOnClickListener { showTab(TAB_SETTINGS) }
    })
    header.addView(android.widget.ImageButton(this).apply {
        setImageResource(android.R.drawable.ic_menu_edit); setColorFilter(palette.text); background = null
        setPadding(dp(12), dp(12), dp(12), dp(12)); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        contentDescription = "Personalizza"
        setOnClickListener { customizeTools() }
    }, 1)
    list.addView(header)
    val groups = mutableListOf<Pair<String, List<Triple<String, String, () -> Unit>>>>()
    fun group(title: String, items: List<Triple<String, String, () -> Unit>>) { groups += title to items }
    fun renderGroup(title: String, items: List<Triple<String, String, () -> Unit>>) {
        if (items.isEmpty()) return
        sectionLabel(list, title)
        val grid = android.widget.GridLayout(this).apply { columnCount = 4 }
        items.forEach { (_, label, action) ->
            grid.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                setPadding(dp(2), dp(10), dp(2), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
                }
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; height = dp(92)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                addView(ImageView(this@renderTools).apply {
                    setImageResource(toolIcon(label))
                    setColorFilter(palette.text)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(28), dp(28))
                })
                addView(TextView(this@renderTools).apply {
                    text = label; textSize = 12f; gravity = android.view.Gravity.CENTER; setTextColor(palette.text)
                    maxLines = 2; setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { action() }
            })
        }
        list.addView(grid)
    }
    group("ORGANIZZA", listOf(
        Triple("📝", "Note") { showNotesPage() },
        Triple("🛒", "Lista spesa") {
            val n = notes.load().firstOrNull { isChecklist(it.text) && it.text.lines().first().trim().equals("Lista della spesa", true) }
            showChecklist(n?.id, "Lista della spesa")
        },
        Triple("🎙", "Registratore") { showRecorderPage() },
        Triple("🗣", "Chiedi a voce") { listenForQuestion() },
        Triple("🍝", "Ricette") { showRecipesPage() },
        Triple("🚘", "Modalità auto") { showCarMode() },
        Triple("☀️", "Buongiorno") { previewBrief() },
        Triple("👟", "Passi") { showStepsPage() },
        Triple("❤️", "Salute") { showHealthPage() },
        Triple("🔔", "Avvisi") { showAlertsPage() },
        Triple("💰", "Spese") { showExpensesPage() },
        Triple("💼", "Finanze") { showFinancePage() },
        Triple("🧠", "Chiedi ai tuoi dati") { askMyData() },
        Triple("🎂", "Compleanni") { showBirthdaysPage() },
        Triple("📆", "Scadenze") { showDeadlinesPage(Deadlines.DEADLINE) },
        Triple("🔁", "Abbonamenti") { showDeadlinesPage(Deadlines.SUBSCRIPTION) },
        Triple("🧾", "Garanzie") { showDeadlinesPage(Deadlines.WARRANTY) },
        Triple("🚗", "Parcheggio e luoghi") { showPlacesPage() }
    ))
    group("DOCUMENTI", listOf(
        Triple("📄", "Scanner") { scanDocument() },
        Triple("📑", "Apri PDF") { pdfPicker.launch(arrayOf("application/pdf")) },
        Triple("🎫", "Biglietti") { showWalletPage() },
        Triple("💳", "Carte fedeltà") { showCardsPage() },
        Triple("📖", "Libri") { showBooksPage() },
        Triple("🔖", "Leggi dopo") { showReadLaterPage() },
        Triple("🔐", "Cassaforte") { showVault() }
    ))
    group("UTILITÀ", listOf(
        Triple("📷", "QR code") { scanQr() },
        Triple("🔦", "Torcia e bussola") { showUtilitiesPage() },
        Triple("📈", "Borsa") { showStocksPage() },
        Triple("📦", "Pacchi") { showParcelsPage() },
        Triple("🌦", "Meteo") { if (weatherPrefs.getString("city", null) == null) setupWeather() else showWeatherDetail() },
        Triple("⏱", "Tempo di utilizzo") { showUsagePage() },
        Triple("🧮", "Calcoli") {
            showTab(TAB_HOME); searchInput.requestFocus()
            toast("Scrivi nella ricerca: «15% di 230», «10 km in miglia», «50 dollari in euro»")
        }
    ))
    group("BROWSER E APP", listOf(
        Triple("📚", "Catalogo app") { showCatalogPage() },
        Triple("🕵️", "Profilo 2") { startActivity(Intent(this, IsolatedBrowserActivity::class.java)) },
        Triple("⬇", "Download") { showDownloadsPage() },
        Triple("🕘", "Cronologia") { showHistoryPage() },
        Triple("✨", "Mia AI") { openMiaAi() },
        Triple("⚙", "Impostazioni") { showTab(TAB_SETTINGS) }
    ))
    // personalizzazione: tasselli nascosti e preferiti in alto
    val tp = getSharedPreferences("wl_tools", MODE_PRIVATE)
    val hidden = tp.getStringSet("hidden", emptySet()).orEmpty()
    val favs = tp.getString("favs", "").orEmpty().split("|").filter { it.isNotBlank() }
    val all = groups.flatMap { it.second }
    toolsCatalog = groups.map { g -> g.first to g.second.map { it.second to it.first } }
    val favItems = favs.mapNotNull { f -> all.firstOrNull { it.second == f } }
    renderGroup("⭐ PREFERITI", favItems)
    groups.forEach { (t, items) -> renderGroup(t, items.filter { it.second !in hidden && it.second !in favs }) }
    if (hidden.isNotEmpty()) emptyLabel(list, "${hidden.size} strumenti nascosti · tocca ✏️ in alto per mostrarli")
}

internal fun MainActivity.applyHomeLook() {
    adapter.spanCount = homePrefs.getInt("cols", 4)
    adapter.hideNames = homePrefs.getBoolean("hideNames", false)
    (recycler.layoutManager as? GridLayoutManager)?.spanCount = adapter.spanCount
    val wp = findViewById<ImageView>(R.id.homeWallpaper)
    val f = java.io.File(filesDir, "wallpaper.jpg")
    if (homePrefs.getBoolean("wallpaper", false) && f.exists()) {
        wp.load(f) { memoryCacheKey("wp" + f.lastModified()) }
        wp.isVisible = currentTab == TAB_HOME
        val dim = if (AppRepository.isLightUi(this)) 0x55FFFFFF else 0x77000000
        wp.foreground = android.graphics.drawable.ColorDrawable(dim)
    } else wp.isVisible = false
    refreshList()
}

internal fun MainActivity.chooseFolder(item: AppItem) {
    val existing = apps.mapNotNull { (it.folder as String?)?.takeIf { f -> f.isNotBlank() } }.distinct().sorted()
    val opts = existing.map { "📁 $it" } + "➕ Nuova cartella…"
    MaterialAlertDialogBuilder(this).setTitle("Cartella per ${item.name}").setItems(opts.toTypedArray()) { _, w ->
        if (w < existing.size) { item.folder = existing[w]; persistApps(); refreshList(); toast("Spostata in ${existing[w]}") }
        else {
            val d = resources.displayMetrics.density
            val et = TextInputEditText(this).apply { hint = "Nome (es. Social, Banche, Acquisti)"; setSingleLine() }
            val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
            MaterialAlertDialogBuilder(this).setTitle("Nuova cartella").setView(box)
                .setPositiveButton("Crea") { _, _ ->
                    val n = et.text?.toString()?.trim().orEmpty()
                    if (n.isNotEmpty()) { item.folder = n; persistApps(); refreshList(); toast("Cartella $n creata") }
                }.setNegativeButton("Annulla", null).show()
        }
    }.show()
}

internal fun MainActivity.showFolder(name: String, items: List<AppItem>) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(24)) }
    val head = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    head.addView(TextView(this).apply {
        text = "📁 $name"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
    })
    head.addView(TextView(this).apply {
        text = "Rinomina"; textSize = 14f; setPadding(dp(10), dp(8), dp(4), dp(8))
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
        setOnClickListener {
            val et = TextInputEditText(this@showFolder).apply { setText(name); setSingleLine() }
            val box = android.widget.FrameLayout(this@showFolder).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
            MaterialAlertDialogBuilder(this@showFolder).setTitle("Nome della cartella").setView(box)
                .setPositiveButton("Salva") { _, _ ->
                    val n = et.text?.toString()?.trim().orEmpty()
                    if (n.isNotEmpty()) { apps.filter { it.folder == name }.forEach { it.folder = n }; persistApps(); refreshList(); sheet.dismiss() }
                }.setNegativeButton("Annulla", null).show()
        }
    })
    root.addView(head)
    val grid = android.widget.GridLayout(this).apply { columnCount = 4; setPadding(0, dp(10), 0, 0) }
    items.forEach { a ->
        grid.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            addView(ImageView(this@showFolder).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(palette.iconTile) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                val local = a.iconPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                load(local ?: "https://www.google.com/s2/favicons?domain=${hostOf(normalizeUrl(a.url))}&sz=128")
            })
            addView(TextView(this@showFolder).apply {
                text = a.name; textSize = 12f; maxLines = 2; gravity = android.view.Gravity.CENTER; setTextColor(palette.text)
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { sheet.dismiss(); openApp(a) }
            setOnLongClickListener { sheet.dismiss(); showAppMenu(a); true }
        })
    }
    root.addView(grid)
    root.addView(TextView(this).apply {
        text = "Tieni premuta un'app per toglierla dalla cartella o modificarla."
        textSize = 12f; setTextColor(palette.textMuted); setPadding(0, dp(12), 0, 0)
    })
    sheet.setContentView(root)
    sheet.show()
}

internal fun MainActivity.launchCamera(f: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
    try { takePicture.launch(uri) } catch (_: Exception) { toast("Fotocamera non disponibile"); captureDone?.invoke(null) }
}

internal fun MainActivity.capturePhoto(target: java.io.File, done: (java.io.File?) -> Unit) {
    target.parentFile?.mkdirs()
    captureTarget = target; captureDone = done
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(target)
    else cameraPermission.launch(Manifest.permission.CAMERA)
}

private fun toolIcon(label: String): Int = when (label) {
    "Note" -> R.drawable.ic_tool_note
    "Lista spesa" -> R.drawable.ic_tool_shop
    "Registratore" -> R.drawable.ic_tool_record
    "Chiedi a voce" -> R.drawable.ic_tool_voice
    "Ricette" -> R.drawable.ic_tool_recipe
    "Modalità auto" -> R.drawable.ic_tool_car
    "Buongiorno" -> R.drawable.ic_tool_sun
    "Passi" -> R.drawable.ic_tool_steps
    "Salute" -> R.drawable.ic_tool_heart
    "Avvisi" -> R.drawable.ic_tool_bell
    "Spese" -> R.drawable.ic_tool_spend
    "Finanze" -> R.drawable.ic_tool_finance
    "Chiedi ai tuoi dati" -> R.drawable.ic_tool_brain
    "Compleanni" -> R.drawable.ic_tool_cake
    "Scadenze" -> R.drawable.ic_tool_calendar
    "Abbonamenti" -> R.drawable.ic_tool_repeat
    "Garanzie" -> R.drawable.ic_tool_warranty
    "Parcheggio e luoghi" -> R.drawable.ic_tool_pin
    "Scanner" -> R.drawable.ic_tool_scan
    "Apri PDF" -> R.drawable.ic_tool_pdf
    "Biglietti" -> R.drawable.ic_tool_ticket
    "Carte fedeltà" -> R.drawable.ic_tool_loyalty
    "Libri" -> R.drawable.ic_tool_book
    "Leggi dopo" -> R.drawable.ic_tool_bookmark
    "Cassaforte" -> R.drawable.ic_tool_lock
    "QR code" -> R.drawable.ic_tool_qr
    "Torcia e bussola" -> R.drawable.ic_tool_torch
    "Borsa" -> R.drawable.ic_tool_chart
    "Pacchi" -> R.drawable.ic_tool_box
    "Meteo" -> R.drawable.ic_tool_weather
    "Tempo di utilizzo" -> R.drawable.ic_tool_usage
    "Calcoli" -> R.drawable.ic_tool_calc
    "Catalogo app" -> R.drawable.ic_tool_catalog
    "Profilo 2" -> R.drawable.ic_tool_mask
    "Download" -> R.drawable.ic_tool_download
    "Cronologia" -> R.drawable.ic_tool_history
    "Mia AI" -> R.drawable.ic_tool_spark
    "Impostazioni" -> R.drawable.ic_tool_settings
    else -> R.drawable.ic_nav_tools
}

/** ✏️ in Strumenti: show/hide tiles and choose the favourites shown first. */
internal fun MainActivity.customizeTools() {
    val tp = getSharedPreferences("wl_tools", MODE_PRIVATE)
    val all = toolsCatalog.flatMap { it.second }
    if (all.isEmpty()) return
    MaterialAlertDialogBuilder(this).setTitle("Personalizza Strumenti")
        .setItems(arrayOf("👁 Mostra o nascondi strumenti", "⭐ Scegli i preferiti (in alto)", "↺ Ripristina come all'inizio")) { _, w ->
            when (w) {
                0 -> {
                    val hidden = tp.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
                    val labels = all.map { it.first }
                    val checked = BooleanArray(labels.size) { labels[it] !in hidden }
                    MaterialAlertDialogBuilder(this).setTitle("Strumenti visibili")
                        .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton("Salva") { _, _ ->
                            tp.edit().putStringSet("hidden", labels.filterIndexed { i, _ -> !checked[i] }.toSet()).apply(); renderTools()
                        }.setNegativeButton("Annulla", null).show()
                }
                1 -> {
                    val favs = tp.getString("favs", "").orEmpty().split("|").filter { it.isNotBlank() }.toMutableList()
                    val labels = all.map { it.first }
                    val checked = BooleanArray(labels.size) { labels[it] in favs }
                    MaterialAlertDialogBuilder(this).setTitle("Preferiti (in alto, nell'ordine scelto)")
                        .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on ->
                            checked[i] = on; if (on) favs.add(labels[i]) else favs.remove(labels[i])
                        }
                        .setPositiveButton("Salva") { _, _ -> tp.edit().putString("favs", favs.distinct().joinToString("|")).apply(); renderTools() }
                        .setNegativeButton("Annulla", null).show()
                }
                2 -> { tp.edit().clear().apply(); renderTools(); toast("Strumenti ripristinati") }
            }
        }.show()
}
