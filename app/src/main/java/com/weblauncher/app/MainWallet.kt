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

/** Full-screen code at maximum brightness, for the checkout scanner. */
internal fun MainActivity.showCodeFullscreen(title: String, code: String, format: String, color: Int) {
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

internal fun MainActivity.showCardsPage() {
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
                        MaterialAlertDialogBuilder(this@showCardsPage).setTitle(c.name)
                            .setItems(arrayOf("Mostra", "Rinomina", "Elimina")) { _, w ->
                                when (w) {
                                    0 -> showCodeFullscreen(c.name, c.code, c.format, c.color)
                                    1 -> {
                                        val et = TextInputEditText(this@showCardsPage).apply { setText(c.name); setSingleLine() }
                                        val box = android.widget.FrameLayout(this@showCardsPage).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
                                        MaterialAlertDialogBuilder(this@showCardsPage).setTitle("Nome").setView(box)
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
                textSize = 13f; setTextColor(AppRepository.palette(this@showCardsPage).textMuted); setPadding(dp(10), dp(16), dp(10), 0)
            })
        }
        render()
        cardsRefresh = { render() }
        dialog.setOnDismissListener { cardsRefresh = null }
    }
}

internal fun MainActivity.addCardDialog() {
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

internal fun MainActivity.saveCardDialog(code: String, format: String?) {
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

internal fun MainActivity.addToWallet(title: String, kind: String, source: java.io.File?, bytes: ByteArray? = null, ext: String = "pdf", format: String? = null, text: String? = null) {
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

internal fun MainActivity.saveCurrentToWallet() {
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

internal fun MainActivity.askWalletTitle(default: String, onOk: (String) -> Unit) {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply { setText(default.take(60)); setSingleLine(); selectAll() }
    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
    MaterialAlertDialogBuilder(this).setTitle("Nome del biglietto o documento").setView(box)
        .setPositiveButton("Salva") { _, _ -> onOk(et.text?.toString()?.trim().orEmpty().ifBlank { default }) }
        .setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.showImageFullscreen(path: String) {
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

internal fun MainActivity.openWalletItem(w: WalletItem) {
    when (w.kind) {
        "pdf" -> {
            val f = java.io.File(w.path)
            if (!f.exists()) { toast("File mancante"); return }
            val viewer = MiaAi.ORIGIN + "/assets/pdf/viewer.html?theme=" + (if (AppRepository.isLightUi(this)) "light" else "dark") + "&file=" + Uri.encode("/files/wallet/" + f.name) + "&name=" + Uri.encode(w.title)
            if (webTabs.getOrNull(webTabIndex)?.url?.startsWith("http") == true) openNewTab(false)
            openInBrowser(viewer)
        }
        "image" -> showImageFullscreen(w.path)
        "code" -> showCodeFullscreen(w.title, w.path, w.format ?: "QR_CODE", 0xFF3949AB.toInt())
    }
}

internal fun MainActivity.showWalletPage() {
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

internal fun MainActivity.appForUrl(url: String?): AppItem? {
    val h = hostOf(url) ?: return null
    return apps.firstOrNull { a -> hostOf(normalizeUrl(a.url))?.let { it == h || h.endsWith(".$it") } == true }
}
