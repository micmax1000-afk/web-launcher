package com.weblauncher.app

import android.content.Context.CAMERA_SERVICE
import android.content.Context.MODE_PRIVATE
import android.content.Context.SENSOR_SERVICE
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

// ======================= Checklists (inside notes) =======================

internal fun MainActivity.isChecklist(text: String) = text.lines().any { it.startsWith("☐ ") || it.startsWith("☑ ") }

internal fun MainActivity.showChecklist(noteId: String?, initialTitle: String = "Lista") {
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
            addView(android.widget.ImageButton(this@showChecklist).apply {
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

internal fun MainActivity.splitItems(t: String): List<String> =
    t.split(Regex(",|;|\\s+e\\s+|\\n")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        .map { it.replaceFirstChar { c -> c.uppercase() } }

/** "aggiungi latte e pane alla lista della spesa" (voice). */
internal fun MainActivity.addToShoppingList(itemsText: String, listName: String) {
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

internal fun MainActivity.isInstalled(c: CatalogApp): Boolean {
    val h = hostOf(c.url)
    return apps.any { a -> hostOf(normalizeUrl(a.url)) == h && (h != "google.com" && h != "docs.google.com" || a.url.trimEnd('/') == c.url.trimEnd('/')) }
}

internal fun MainActivity.installCatalogApp(c: CatalogApp) {
    if (isInstalled(c)) return
    if (c.cat !in categories) { categories.add(c.cat); repo.saveCategories(categories) }
    apps.add(AppItem(UUID.randomUUID().toString(), c.name, c.url, c.cat, false, apps.size, null, 0, false, c.desktop))
    persistApps(); refreshList()
}

internal fun MainActivity.showCatalogPage() {
    openPage("Catalogo app web") { root, body, _ ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var query = ""
        fun render() {
            body.removeAllViews()
            val q = query.lowercase(Locale.ROOT)
            val list = catalog.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) || it.cat.lowercase(Locale.ROOT).contains(q) }
            if (list.isEmpty()) { emptyLabel(body, "Nessuna app trovata.\nPuoi aggiungere qualsiasi sito da Impostazioni → Aggiungi web app."); return }
            list.groupBy { it.cat }.forEach { (cat, group) ->
                sectionLabel(body, cat.uppercase())
                group.forEach { c ->
                    val installed = isInstalled(c)
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(4), dp(6))
                    }
                    row.addView(ImageView(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(palette.iconTile) }
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        load("https://www.google.com/s2/favicons?domain=${hostOf(c.url)}&sz=64")
                    })
                    val box = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                    }
                    box.addView(TextView(this).apply { text = c.name; textSize = 16f; setTextColor(palette.text) })
                    box.addView(TextView(this).apply {
                        text = listOfNotNull(hostOf(c.url), c.note).joinToString(" · "); textSize = 12f; setTextColor(palette.textMuted)
                    })
                    row.addView(box)
                    row.addView(TextView(this).apply {
                        text = if (installed) "✓ In home" else "Aggiungi"
                        textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(if (installed) palette.textMuted else android.graphics.Color.WHITE)
                        setPadding(dp(14), dp(7), dp(14), dp(7))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 16 * d
                            if (installed) setStroke(d.toInt().coerceAtLeast(1), palette.stroke) else setColor(accent)
                        }
                        setOnClickListener {
                            if (!installed) { installCatalogApp(c); toast("${c.name} aggiunta alla home"); render() }
                            else openInBrowser(c.url)
                        }
                    })
                    body.addView(row)
                }
            }
            body.addView(TextView(this).apply {
                text = "Dopo averla provata, puoi disinstallare l'app ufficiale dal telefono."
                textSize = 13f; setTextColor(palette.textMuted); setPadding(dp(10), dp(16), dp(10), 0)
            })
        }
        val et = EditText(this).apply {
            hint = "Cerca nel catalogo"; setSingleLine(); textSize = 16f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            setPadding(dp(18), 0, dp(18), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d; setColor(palette.iconTile); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim().orEmpty(); render() }
            })
        }
        root.addView(et, 1)
        render()
    }
}

internal fun MainActivity.scanDocument() {
    try {
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> docScanLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { toast("Scanner non disponibile: servono i Google Play Services aggiornati") }
    } catch (_: Throwable) { toast("Scanner non disponibile su questo telefono") }
}

internal fun MainActivity.scanDone(pdf: java.io.File, images: List<java.io.File>, pageCount: Int) {
    if (!pdf.exists()) { toast("Scansione non salvata"); return }
    val title = "Documento " + java.text.SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN).format(java.util.Date())
    val opts = arrayOf("📄 Apri", "🎫 Salva nei biglietti e documenti", "⬇ Salva nei Download", "Condividi", "✨ Leggi e riassumi con AI", "💰 È uno scontrino o una bolletta")
    MaterialAlertDialogBuilder(this).setTitle("Scansione pronta · $pageCount ${if (pageCount == 1) "pagina" else "pagine"}")
        .setItems(opts) { _, w ->
            when (w) {
                0 -> {
                    val dest = java.io.File(pdfDir, pdf.name); runCatching { pdf.copyTo(dest, true) }
                    showPdf(dest, title, null)
                }
                1 -> askWalletTitle(title) { t -> addToWallet(t, "pdf", pdf) }
                2, 3 -> io.execute {
                    val u = runCatching {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$title.pdf".replace(":", "."))
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        }
                        val col = if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                            else android.provider.MediaStore.Files.getContentUri("external")
                        contentResolver.insert(col, values)!!.also { uri -> contentResolver.openOutputStream(uri)!!.use { o -> pdf.inputStream().use { it.copyTo(o) } } }
                    }.getOrNull()
                    runOnUiThread {
                        if (u == null) toast("Non salvato")
                        else if (w == 2) toast("Salvato nei Download")
                        else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Condividi documento"))
                    }
                }
                4 -> readScanWithAi(images)
                5 -> readReceipt(images, pdf)
            }
        }.show()
}

internal fun MainActivity.readScanWithAi(images: List<java.io.File>) {
    if (!aiReady()) return
    if (images.isEmpty()) { toast("Immagini della scansione non disponibili"); return }
    showAiSheet(
        title = "Documento scansionato",
        context = null,
        firstQuestion = "Leggi il documento nelle immagini allegate e riassumilo in italiano: di cosa si tratta, " +
            "importi, date e scadenze, cosa bisogna fare. Se richiesto, potrò chiederti il testo completo.",
        showFirstQuestion = false,
        firstAttachments = images.map { Triple("image", "image/jpeg", "/files/scan/" + it.name) }
    )
}

// ======================= Utilities: torch, compass, level =======================

internal fun MainActivity.showUtilitiesPage() {
    val sm = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
    val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    val torchId = runCatching {
        cm.cameraIdList.firstOrNull { id -> cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    var torchOn = false
    var listener: android.hardware.SensorEventListener? = null
    openPage("Utilità") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun card(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
            body.addView(this)
        }
        // torch
        val torch = card()
        val torchBtn = TextView(this).apply {
            text = "🔦"; textSize = 46f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(120), dp(120))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(palette.iconTile) }
        }
        val torchLabel = TextView(this).apply { text = if (torchId == null) "Torcia non disponibile" else "Torcia spenta"; textSize = 15f; setTextColor(palette.text); setPadding(0, dp(8), 0, 0) }
        torchBtn.setOnClickListener {
            val id = torchId ?: return@setOnClickListener
            torchOn = !torchOn
            runCatching { cm.setTorchMode(id, torchOn) }.onFailure { torchOn = false; toast("Torcia occupata da un'altra app") }
            (torchBtn.background as android.graphics.drawable.GradientDrawable).setColor(if (torchOn) 0xFFFFD54F.toInt() else palette.iconTile)
            torchLabel.text = if (torchOn) "Torcia accesa" else "Torcia spenta"
        }
        torch.addView(torchBtn); torch.addView(torchLabel)
        // compass
        val compass = card()
        val needle = TextView(this).apply { text = "⬆"; textSize = 54f; setTextColor(0xFFE53935.toInt()); gravity = android.view.Gravity.CENTER }
        val heading = TextView(this).apply { textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); gravity = android.view.Gravity.CENTER }
        compass.addView(TextView(this).apply { text = "Bussola"; textSize = 13f; setTextColor(palette.textMuted) })
        compass.addView(needle); compass.addView(heading)
        // level
        val level = card()
        val bubbleBox = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(200), dp(200))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(palette.iconTile); setStroke(dp(2), palette.stroke)
            }
        }
        val bubble = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(accent) }
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.CENTER)
        }
        bubbleBox.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), palette.textMuted) }
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER)
        })
        bubbleBox.addView(bubble)
        val angles = TextView(this).apply { textSize = 15f; setTextColor(palette.text); setPadding(0, dp(8), 0, 0); gravity = android.view.Gravity.CENTER }
        level.addView(TextView(this).apply { text = "Livella (appoggia il telefono)"; textSize = 13f; setTextColor(palette.textMuted); setPadding(0, 0, 0, dp(8)) })
        level.addView(bubbleBox); level.addView(angles)

        val rot = FloatArray(9); val orient = FloatArray(3)
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                when (e.sensor.type) {
                    android.hardware.Sensor.TYPE_ROTATION_VECTOR -> {
                        android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
                        android.hardware.SensorManager.getOrientation(rot, orient)
                        val az = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                        needle.rotation = -az
                        heading.text = "${az.toInt()}° ${dirs[((az + 22.5f) / 45f).toInt() % 8]}"
                    }
                    android.hardware.Sensor.TYPE_ACCELEROMETER -> {
                        val x = e.values[0]; val y = e.values[1]
                        val max = dp(80).toFloat()
                        bubble.translationX = (-x / 9.81f * max * 2).coerceIn(-max, max)
                        bubble.translationY = (y / 9.81f * max * 2).coerceIn(-max, max)
                        val ax = Math.toDegrees(Math.asin((x / 9.81).coerceIn(-1.0, 1.0))).toInt()
                        val ay = Math.toDegrees(Math.asin((y / 9.81).coerceIn(-1.0, 1.0))).toInt()
                        angles.text = if (kotlin.math.abs(ax) <= 1 && kotlin.math.abs(ay) <= 1) "✓ In bolla" else "${kotlin.math.abs(ax)}° · ${kotlin.math.abs(ay)}°"
                    }
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        val rv = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (rv != null) sm.registerListener(listener, rv, android.hardware.SensorManager.SENSOR_DELAY_UI) else heading.text = "Bussola non disponibile"
        if (acc != null) sm.registerListener(listener, acc, android.hardware.SensorManager.SENSOR_DELAY_UI)
        dialog.setOnDismissListener {
            listener?.let { sm.unregisterListener(it) }
            if (torchOn) torchId?.let { runCatching { cm.setTorchMode(it, false) } }
        }
    }
}

internal fun MainActivity.openBook(b: Book) = startActivity(Intent(this, EbookActivity::class.java).putExtra("book", b.id))

internal fun MainActivity.showBooksPage() {
    openPage("Libri", "Aggiungi", { _ -> epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val books = Ebooks.load(this).sortedByDescending {
                getSharedPreferences("wl_books", MODE_PRIVATE).getLong("last_${it.id}", it.added)
            }
            if (books.isEmpty()) {
                emptyLabel(body, "Nessun libro.\nTocca «Aggiungi» e scegli un file EPUB\n(dal telefono, da Drive o dai Download).\n\nI libri Kindle e quelli protetti (DRM) non si possono aprire.")
                return
            }
            books.forEach { b ->
                val (ch, _) = Ebooks.progress(this, b.id)
                val pct = (ch + 1) * 100 / b.spine.size.coerceAtLeast(1)
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { openBook(b) }
                    setOnLongClickListener {
                        confirmClear("Eliminare «${b.title}»?") { Ebooks.delete(this@showBooksPage, b.id); render() }
                        true
                    }
                }
                row.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(80)).apply { marginEnd = dp(14) }
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8 * d; setColor(palette.iconTile) }
                    b.cover?.let { c -> load(java.io.File(Ebooks.dir(this@showBooksPage), "${b.id}/${b.root}${java.net.URLDecoder.decode(c, "UTF-8")}")) {
                        transformations(coil.transform.RoundedCornersTransformation(12f))
                    } }
                })
                val box = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                }
                box.addView(TextView(this).apply {
                    text = b.title; textSize = 16f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text)
                })
                box.addView(TextView(this).apply { text = b.author; textSize = 13f; setTextColor(palette.textMuted) })
                box.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = pct
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8); marginEnd = dp(12) }
                })
                box.addView(TextView(this).apply { text = "$pct% · capitolo ${ch + 1} di ${b.spine.size}"; textSize = 12f; setTextColor(palette.textMuted) })
                row.addView(box)
                body.addView(row)
            }
            body.addView(TextView(this).apply {
                text = "Tieni premuto un libro per eliminarlo."; textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(10), dp(12), 0, 0)
            })
        }
        render()
        booksRefresh = { render() }
        dialog.setOnDismissListener { booksRefresh = null }
    }
}

internal fun MainActivity.recTitles() = getSharedPreferences("wl_rec", MODE_PRIVATE)

internal fun MainActivity.startRecording() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        recPermission.launch(Manifest.permission.RECORD_AUDIO); return
    }
    ensureNotificationPermission()
    ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
    uiHandler.postDelayed({ recorderRefresh?.invoke() }, 400)
}

internal fun MainActivity.stopRecording() {
    startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
}

internal fun MainActivity.recDuration(f: java.io.File): Long = runCatching {
    val r = android.media.MediaMetadataRetriever()
    r.setDataSource(f.absolutePath)
    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    r.release()
    ms ?: (f.length() * 8 / 40)  // ~40 kbps
}.getOrDefault(f.length() * 8 / 40)

internal fun MainActivity.showRecorderPage() {
    openPage("Registrazioni") { root, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val big = TextView(this).apply {
            textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(16), dp(4), dp(16), dp(10)) }
        }
        root.addView(big, 1)
        val ticker = object : Runnable {
            override fun run() {
                val rec = RecordingService.isRecording()
                big.text = if (rec) "■  Ferma  ·  " + fmtTime(System.currentTimeMillis() - RecordingService.startedAt) else "●  Registra"
                big.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 32 * d; setColor(if (rec) 0xFF424242.toInt() else 0xFFE53935.toInt())
                }
                if (rec) uiHandler.postDelayed(this, 500)
            }
        }
        big.setOnClickListener { if (RecordingService.isRecording()) stopRecording() else startRecording() }
        fun render() {
            uiHandler.removeCallbacks(ticker); uiHandler.post(ticker)
            body.removeAllViews()
            val files = RecordingService.dir(this).listFiles { f -> f.name.endsWith(".aac") && f != RecordingService.current }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            if (files.isEmpty()) {
                emptyLabel(body, "Nessuna registrazione.\nContinua anche a schermo spento.\nPoi l'AI può trascriverla e riassumerla (con Gemini).")
                return
            }
            val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
            files.forEach { f ->
                val title = recTitles().getString(f.name, null) ?: ("Registrazione " + fmt.format(java.util.Date(f.lastModified())))
                val dur = recDuration(f)
                listRow(body, title, "${fmtTime(dur)} · ${android.text.format.Formatter.formatShortFileSize(this, f.length())}",
                    onClick = { playMedia("rec:" + f.name, Uri.fromFile(f).toString(), title, "Registrazione", null, 0L) },
                    onLong = {
                        MaterialAlertDialogBuilder(this).setTitle(title)
                            .setItems(arrayOf("▶ Ascolta", "✨ Trascrivi e riassumi", "✏️ Rinomina", "Condividi")) { _, w ->
                                when (w) {
                                    0 -> playMedia("rec:" + f.name, Uri.fromFile(f).toString(), title, "Registrazione", null, 0L)
                                    1 -> transcribe(f, title, dur)
                                    2 -> {
                                        val et = TextInputEditText(this).apply { setText(title); setSingleLine(); selectAll() }
                                        val box = android.widget.FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
                                        MaterialAlertDialogBuilder(this).setTitle("Nome").setView(box)
                                            .setPositiveButton("Salva") { _, _ ->
                                                recTitles().edit().putString(f.name, et.text?.toString()?.trim().orEmpty().ifBlank { title }).apply(); render()
                                            }.setNegativeButton("Annulla", null).show()
                                    }
                                    3 -> io.execute {
                                        val u = runCatching {
                                            val values = android.content.ContentValues().apply {
                                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, title.replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".aac")
                                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/aac")
                                            }
                                            val col = if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                                else android.provider.MediaStore.Files.getContentUri("external")
                                            contentResolver.insert(col, values)!!.also { uri -> contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }
                                        }.getOrNull()
                                        runOnUiThread {
                                            if (u == null) toast("Condivisione non riuscita")
                                            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                type = "audio/aac"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Condividi registrazione"))
                                        }
                                    }
                                }
                            }.show()
                    },
                    onRemove = { confirmClear("Eliminare «$title»?") { f.delete(); recTitles().edit().remove(f.name).apply(); render() } })
            }
            body.addView(TextView(this).apply {
                text = "Tocca per ascoltare, tieni premuto per trascrivere con l'AI, rinominare o condividere."
                textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(10), dp(12), 0, 0)
            })
        }
        render()
        recorderRefresh = { runOnUiThread { render() } }
        RecordingService.listener = { runOnUiThread { recorderRefresh?.invoke() } }
        dialog.setOnDismissListener { recorderRefresh = null; RecordingService.listener = null; uiHandler.removeCallbacks(ticker) }
    }
}

internal fun MainActivity.transcribe(f: java.io.File, title: String, durMs: Long) {
    if (!aiReady()) return
    if (f.length() > 14L * 1024 * 1024) { toast("Registrazione troppo lunga per la trascrizione (max circa 45 minuti)"); return }
    showAiSheet(
        title = "Trascrizione · $title",
        context = null,
        firstQuestion = "Nell'audio allegato c'è una registrazione di ${fmtTime(durMs)}. Prima scrivi la TRASCRIZIONE fedele in italiano " +
            "(nella lingua parlata), con il minuto [mm:ss] all'inizio di ogni paragrafo e i cambi di persona se si capiscono. " +
            "Poi un RIASSUNTO con i punti principali e le cose da fare.",
        showFirstQuestion = false,
        firstAttachments = listOf(Triple("pdf", "audio/aac", "/files/rec/" + f.name))
    )
    toast("✨ Invio l'audio… la trascrizione funziona con Gemini")
}
