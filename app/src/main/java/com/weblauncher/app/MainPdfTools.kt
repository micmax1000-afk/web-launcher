package com.weblauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= PDF: firma, compila, evidenzia, pagine, chiedi all'AI =======================

/** The PDF file behind the in-app viewer address (…viewer.html?file=/files/pdf/x.pdf). */
internal fun MainActivity.pdfFileOf(url: String?): File? {
    val p = runCatching { Uri.parse(url).getQueryParameter("file") }.getOrNull() ?: return null
    return File(pdfDir, p.substringAfterLast('/')).takeIf { it.exists() }
}

private fun newPdfName(name: String, what: String) = name.removeSuffix(".pdf").removeSuffix(".PDF").take(60) + " ($what).pdf"

/** The tools for one PDF. */
internal fun MainActivity.showPdfTools(file: File, name: String) {
    openPage("Modifica il PDF") { _, body, dialog ->
        val d = resources.displayMetrics.density
        val preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0x11000000) }
        body.addView(preview, LinearLayout.LayoutParams(-1, (220 * d).toInt()).apply { setMargins(0, (6 * d).toInt(), 0, (6 * d).toInt()) })
        io.execute { val b = PdfEdit.render(file, 0, 500); runOnUiThread { b?.let { preview.setImageBitmap(it) } } }
        val pages = PdfEdit.pageCount(file)
        hintLabel(body, "$name · $pages ${if (pages == 1) "pagina" else "pagine"}. Ogni modifica crea un nuovo PDF: l'originale resta com'è.")
        listRow(body, "✍️ Firma", "Firmi col dito e metti la firma dove serve", onClick = { pdfEditor(file, name, "sign") })
        listRow(body, "📝 Compila", "Scrivi nei campi dei moduli, o del testo dove vuoi", onClick = { pdfFillForm(file, name) })
        listRow(body, "🖍 Evidenzia e note", "Segni in giallo e foglietti con le tue note", onClick = { pdfEditor(file, name, "highlight") })
        listRow(body, "📑 Pagine", "Unisci altri PDF, aggiungi foto, ruota, togli o sposta le pagine", onClick = { pdfPages(file, name) })
        listRow(body, "✨ Chiedi all'AI", "Riassumi, cosa devo pagare e quando, spiegamelo semplice, traduci", onClick = { pdfAsk(file, name) })
        listRow(body, "📤 Condividi", null, onClick = { sharePdf(file, name) })
        dialog.setOnDismissListener { }
    }
}

/** Picks a PDF from the phone and opens its tools. */
internal fun MainActivity.pickPdfForTools() {
    pdfPickDone = { uri ->
        val name = displayName(uri)
        io.execute {
            val dest = File(pdfDir, "tools_" + Integer.toHexString(uri.toString().hashCode()) + ".pdf")
            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } } }.isSuccess
            runOnUiThread { if (ok) showPdfTools(dest, name) else toast("Non riesco ad aprire il PDF") }
        }
    }
    runCatching { pdfPick.launch(arrayOf("application/pdf")) }.onFailure { toast("Non riesco ad aprire i file") }
}

internal fun MainActivity.sharePdf(file: File, name: String) {
    runCatching {
        val dir = File(cacheDir, "report").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if (it.endsWith(".pdf", true)) it else "$it.pdf" })
        file.copyTo(f, true)
        val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Condividi il PDF"))
    }.onFailure { toast("Non riesco a condividere il PDF") }
}

/** The new PDF is ready: open it, share it or save it where you like. */
private fun MainActivity.pdfDone(out: File, name: String) {
    MaterialAlertDialogBuilder(this).setTitle("✓ PDF pronto").setMessage("«$name»\nL'originale non è stato toccato.")
        .setPositiveButton("Apri") { _, _ -> openPdfPage(out, name) }
        .setNeutralButton("Salva nei file") { _, _ -> pendingPdfSave = out; runCatching { pdfSaveAs.launch(name) } }
        .setNegativeButton("Condividi") { _, _ -> sharePdf(out, name) }.show()
}

// ---------- signature pad ----------

/** A place to sign with the finger. */
class SignaturePad(c: Context) : View(c) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D3A8A.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bounds = android.graphics.RectF(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f)
    val isEmpty get() = bounds.right < 0
    fun clear() { path.reset(); bounds.set(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f); invalidate() }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(e.x, e.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
        }
        bounds.union(e.x, e.y); invalidate(); return true
    }
    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }
    /** The signature only (transparent around it). */
    fun bitmap(): Bitmap? {
        if (isEmpty) return null
        val m = 12f
        val w = (bounds.width() + 2 * m).toInt().coerceAtLeast(10); val h = (bounds.height() + 2 * m).toInt().coerceAtLeast(10)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { translate(-bounds.left + m, -bounds.top + m); drawPath(path, paint) }
        return b
    }
}

private fun MainActivity.signatureFile() = File(filesDir, "firma.png")

private fun MainActivity.askSignature(then: (Bitmap) -> Unit) {
    val d = resources.displayMetrics.density
    val saved = signatureFile().takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    if (saved != null) {
        MaterialAlertDialogBuilder(this).setTitle("✍️ La tua firma")
            .setView(ImageView(this).apply { setImageBitmap(saved); adjustViewBounds = true; setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0) })
            .setPositiveButton("Usa questa") { _, _ -> then(saved) }
            .setNeutralButton("Firma di nuovo") { _, _ -> signatureFile().delete(); askSignature(then) }
            .setNegativeButton("Annulla", null).show()
        return
    }
    val pad = SignaturePad(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(0xFFFAFBFE.toInt()); setStroke((2 * d).toInt(), 0xFFB9C2D6.toInt()) }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), 0)
        addView(pad, LinearLayout.LayoutParams(-1, (190 * d).toInt()))
        addView(TextView(this@askSignature).apply { text = "Firma qui con il dito: la ricordo per la prossima volta."; textSize = 13f; alpha = 0.7f; setPadding(0, (6 * d).toInt(), 0, 0) })
    }
    val dlg = MaterialAlertDialogBuilder(this).setTitle("✍️ Firma").setView(box)
        .setPositiveButton("Metti sul foglio", null).setNeutralButton("Cancella", null).setNegativeButton("Annulla", null).create()
    dlg.setOnShowListener {
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val b = pad.bitmap() ?: run { toast("Prima firma nel riquadro"); return@setOnClickListener }
            runCatching { signatureFile().outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            dlg.dismiss(); then(b)
        }
    }
    dlg.show()
}

/** Text as a picture to put on the page (black on transparent, or a yellow note). */
private fun MainActivity.textBitmap(text: String, note: Boolean): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (note) 0xFF3D2E00.toInt() else Color.BLACK; textSize = 44f }
    val lines = text.trim().lines().flatMap { l -> l.chunked(36) }.ifEmpty { listOf(" ") }
    val w = (lines.maxOf { p.measureText(it) } + 40).toInt().coerceAtLeast(60)
    val lh = p.fontSpacing
    val h = (lines.size * lh + 30).toInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    if (note) {
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFEF3C7.toInt() })
        c.drawRoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF59E0B.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
    }
    lines.forEachIndexed { i, l -> c.drawText(l, 20f, 15f + (i + 1) * lh - p.descent(), p) }
    return b
}

// ---------- page editor: signature, free text, highlight, notes ----------

/** [mode]: "sign" starts by asking the signature, "highlight" starts with the yellow marker. */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.pdfEditor(file: File, name: String, mode: String) {
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF (forse è protetto da password)"); return }
    var save: () -> Unit = {}
    openPage(if (mode == "sign") "Firma" else "Evidenzia e note", "Salva", { save() }) { root, body, dialog ->
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        class Item(val page: Int, val bitmap: Bitmap?, var x: Float, var y: Float, var w: Float, var h: Float, val highlight: Boolean)
        val items = mutableListOf<Item>()
        var page = 0
        var marker = mode == "highlight"
        val frame = FrameLayout(this)
        val img = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        val overlay = FrameLayout(this)
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val info = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        lateinit var drawItems: () -> Unit

        fun addItemView(it: Item) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            if (it.highlight) {
                overlay.addView(View(this).apply { setBackgroundColor(0x66FDE047); setOnLongClickListener { _ -> items.remove(it); drawItems(); true } },
                    FrameLayout.LayoutParams((it.w * W).toInt(), (it.h * H).toInt()).apply { leftMargin = (it.x * W).toInt(); topMargin = (it.y * H).toInt() })
                return
            }
            val holder = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { setStroke(dp(2), 0xFF3B6CF5.toInt()); cornerRadius = dp(4).toFloat() }
            }
            holder.addView(ImageView(this).apply { setImageBitmap(it.bitmap); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
            val handle = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF3B6CF5.toInt()) } }
            holder.addView(handle, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END))
            val lp = FrameLayout.LayoutParams((it.w * W).toInt(), (it.h * H).toInt()).apply { leftMargin = (it.x * W).toInt(); topMargin = (it.y * H).toInt() }
            overlay.addView(holder, lp)
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var ow = 0; var oh = 0
            holder.setOnTouchListener { v, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = v.layoutParams as FrameLayout.LayoutParams
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = l.leftMargin; oy = l.topMargin }
                    MotionEvent.ACTION_MOVE -> { l.leftMargin = (ox + e.rawX - sx).toInt().coerceIn(0, (W - l.width).toInt().coerceAtLeast(0)); l.topMargin = (oy + e.rawY - sy).toInt().coerceIn(0, (H - l.height).toInt().coerceAtLeast(0)); v.layoutParams = l }
                    MotionEvent.ACTION_UP -> { it.x = l.leftMargin / W; it.y = l.topMargin / H; if (Math.abs(e.rawX - sx) < 8 && Math.abs(e.rawY - sy) < 8) MaterialAlertDialogBuilder(this).setItems(arrayOf("Togli")) { _, _ -> items.remove(it); drawItems() }.show() }
                }
                true
            }
            handle.setOnTouchListener { _, e ->
                frame.parent?.requestDisallowInterceptTouchEvent(true)
                val l = holder.layoutParams as FrameLayout.LayoutParams
                val ratio = it.h * H / (it.w * W).coerceAtLeast(1f)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; ow = l.width; oh = l.height }
                    MotionEvent.ACTION_MOVE -> { val nw = (ow + e.rawX - sx).toInt().coerceIn(dp(40), (W - l.leftMargin).toInt()); l.width = nw; l.height = (nw * ratio).toInt(); holder.layoutParams = l }
                    MotionEvent.ACTION_UP -> { it.w = l.width / W; it.h = l.height / H }
                }
                true
            }
        }
        drawItems = { overlay.removeAllViews(); items.filter { it.page == page }.forEach { addItemView(it) } }

        fun place(b: Bitmap, widthFraction: Float) {
            val W = overlay.width.toFloat().coerceAtLeast(1f); val H = overlay.height.toFloat().coerceAtLeast(1f)
            val w = widthFraction; val h = (w * W * b.height / b.width) / H
            items += Item(page, b, (1 - w) / 2, (0.5f - h / 2).coerceAtLeast(0f), w, h, false); drawItems()
            toast("Trascinala dove serve · il pallino blu la ingrandisce")
        }
        // marker: drag on the page to mark
        var hx = 0f; var hy = 0f
        val draft = View(this).apply { setBackgroundColor(0x66FDE047); visibility = View.GONE }
        frame.addView(draft, FrameLayout.LayoutParams(0, 0))
        overlay.setOnTouchListener { _, e ->
            if (!marker) return@setOnTouchListener false
            frame.parent?.requestDisallowInterceptTouchEvent(true)
            val l = draft.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hx = e.x; hy = e.y; draft.visibility = View.VISIBLE }
                MotionEvent.ACTION_MOVE -> { l.leftMargin = minOf(hx, e.x).toInt(); l.topMargin = minOf(hy, e.y).toInt(); l.width = Math.abs(e.x - hx).toInt(); l.height = Math.abs(e.y - hy).toInt().coerceAtLeast(dp(14)); draft.layoutParams = l }
                MotionEvent.ACTION_UP -> {
                    draft.visibility = View.GONE
                    val W = overlay.width.toFloat(); val H = overlay.height.toFloat()
                    if (l.width > dp(10)) { items += Item(page, null, l.leftMargin / W, l.topMargin / H, l.width / W, l.height / H, true); drawItems() }
                }
            }
            true
        }
        fun show(p: Int) {
            page = p.coerceIn(0, total - 1)
            info.text = "Pagina ${page + 1} di $total" + if (marker) " · trascina il dito sul testo per evidenziarlo" else ""
            io.execute { val b = PdfEdit.render(file, page, resources.displayMetrics.widthPixels); runOnUiThread { b?.let { img.setImageBitmap(it) }; img.post { drawItems() } } }
        }
        // tools row
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun tool(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(1, 0x33888888) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) }
            setOnClickListener { onClick() }; tools.addView(this)
        }
        tool("‹") { show(page - 1) }
        tool("✍️ Firma") { marker = false; show(page); askSignature { place(it, 0.35f) } }
        tool("🔤 Testo") {
            marker = false; show(page)
            val et = EditText(this).apply { hint = "Cosa scrivo sul foglio"; setPadding(dp(20), dp(14), dp(20), dp(14)) }
            MaterialAlertDialogBuilder(this).setTitle("Testo sul foglio").setView(et).setPositiveButton("Metti") { _, _ -> et.text.toString().takeIf { it.isNotBlank() }?.let { place(textBitmap(it, false), 0.45f) } }.setNegativeButton("Annulla", null).show()
        }
        tool("🖍 Evidenzia") { marker = true; show(page) }
        tool("📌 Nota") {
            marker = false; show(page)
            val et = EditText(this).apply { hint = "La tua nota"; setPadding(dp(20), dp(14), dp(20), dp(14)) }
            MaterialAlertDialogBuilder(this).setTitle("Nota").setView(et).setPositiveButton("Metti") { _, _ -> et.text.toString().takeIf { it.isNotBlank() }?.let { place(textBitmap(it, true), 0.35f) } }.setNegativeButton("Annulla", null).show()
        }
        tool("›") { show(page + 1) }
        body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) })
        body.addView(info)
        body.addView(frame, LinearLayout.LayoutParams(-1, -2))
        hintLabel(body, "Tieni premuto un segno giallo per toglierlo. Tocca una firma, un testo o una nota per toglierli.")
        // «Salva» in the header
        save = {
            if (items.isEmpty()) toast("Non hai aggiunto niente")
            else {
                val edits = items.map { if (it.highlight) PdfEdit.Edit.Highlight(it.page, it.x, it.y, it.w, it.h) else PdfEdit.Edit.Image(it.page, it.bitmap!!, it.x, it.y, it.w, it.h) }
                val outName = newPdfName(name, if (items.any { !it.highlight && it.bitmap != null }) "firmato" else "con note")
                val out = File(pdfDir, "edit_${System.currentTimeMillis()}.pdf")
                toast("Creo il nuovo PDF…")
                io.execute {
                    val r = runCatching { PdfEdit.apply(this, file, out, edits) }
                    runOnUiThread { r.onSuccess { dialog.dismiss(); pdfDone(out, outName) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                }
            }
        }
        show(0)
        if (mode == "sign") frame.post { askSignature { place(it, 0.35f) } }
    }
}

// ---------- forms ----------

internal fun MainActivity.pdfFillForm(file: File, name: String) {
    toast("Cerco i campi del modulo…")
    io.execute {
        val fields = runCatching { PdfEdit.fields(this, file) }.getOrDefault(emptyList())
        runOnUiThread {
            if (fields.isEmpty()) {
                MaterialAlertDialogBuilder(this).setTitle("Nessun campo da compilare")
                    .setMessage("Questo PDF non ha campi da riempire (succede con i fogli scansionati). Puoi scrivere del testo dove vuoi sul foglio.")
                    .setPositiveButton("Scrivi sul foglio") { _, _ -> pdfEditor(file, name, "text") }.setNegativeButton("Chiudi", null).show()
                return@runOnUiThread
            }
            var save: () -> Unit = {}
    openPage("Compila", "Salva", { save() }) { _, body, dialog ->
                val values = HashMap<String, String>()
                hintLabel(body, "${fields.size} campi trovati. Lascia vuoti quelli che non ti servono.")
                fields.forEach { f ->
                    when (f.kind) {
                        "check" -> {
                            val cb = android.widget.CheckBox(this).apply { text = f.label; isChecked = f.value == "true"; textSize = 15f }
                            values[f.name] = f.value
                            cb.setOnCheckedChangeListener { _, on -> values[f.name] = on.toString() }
                            body.addView(cb)
                        }
                        "radio", "choice" -> {
                            val btn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                                isAllCaps = false; text = f.label + ": " + f.value.ifBlank { "scegli" }
                            }
                            btn.setOnClickListener { _ ->
                                MaterialAlertDialogBuilder(this).setTitle(f.label).setItems(f.options.toTypedArray()) { _, w -> values[f.name] = f.options[w]; btn.text = f.label + ": " + f.options[w] }.show()
                            }
                            body.addView(btn)
                        }
                        else -> {
                            body.addView(TextView(this).apply { text = f.label; textSize = 13f; alpha = 0.75f; setPadding(4, 16, 4, 0) })
                            val et = EditText(this).apply { setText(f.value); setSingleLine(false) }
                            et.addTextChangedListener(object : android.text.TextWatcher {
                                override fun afterTextChanged(s: android.text.Editable?) { values[f.name] = s.toString() }
                                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            })
                            body.addView(et)
                        }
                    }
                }
                listRow(body, "🔤 Scrivi anche fuori dai campi", "Testo libero, firma o note sul foglio", onClick = { pdfEditor(file, name, "text") })
                save = {
                    val out = File(pdfDir, "form_${System.currentTimeMillis()}.pdf")
                    toast("Creo il PDF compilato…")
                    io.execute {
                        val r = runCatching { PdfEdit.fill(this, file, out, values) }
                        runOnUiThread { r.onSuccess { dialog.dismiss(); pdfDone(out, newPdfName(name, "compilato")) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
                    }
                }
            }
        }
    }
}

// ---------- pages ----------

internal fun MainActivity.pdfPages(file: File, name: String) {
    val total = PdfEdit.pageCount(file)
    if (total == 0) { toast("Non riesco a leggere questo PDF (forse è protetto da password)"); return }
    var save: () -> Unit = {}
    openPage("Pagine", "Salva", { save() }) { _, body, dialog ->
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val ops = (0 until total).map { PdfEdit.PageOp.FromPdf(file, it) as PdfEdit.PageOp }.toMutableList()
        val thumbs = HashMap<String, Bitmap?>()
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        lateinit var render: () -> Unit
        fun keyOf(op: PdfEdit.PageOp) = when (op) { is PdfEdit.PageOp.FromPdf -> op.file.path + "#" + op.index; is PdfEdit.PageOp.Photo -> op.file.path }
        render = {
            grid.removeAllViews()
            ops.forEachIndexed { i, op ->
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE); elevation = dp(2).toFloat() }
                val turn = (op as? PdfEdit.PageOp.FromPdf)?.turn ?: 0
                iv.rotation = turn.toFloat()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    addView(iv, LinearLayout.LayoutParams(dp(92), dp(120)))
                    addView(TextView(this@pdfPages).apply { text = "${i + 1}" + (if (turn != 0) " ⟳" else "") + (if (op is PdfEdit.PageOp.Photo) " 📷" else ""); textSize = 12f; gravity = Gravity.CENTER })
                    layoutParams = android.widget.GridLayout.LayoutParams().apply { width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f) }
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@pdfPages).setTitle("Pagina ${i + 1}")
                            .setItems(arrayOf("⟳ Ruota", "⬅ Sposta prima", "➡ Sposta dopo", "🗑 Togli")) { _, w ->
                                when (w) {
                                    0 -> (op as? PdfEdit.PageOp.FromPdf)?.let { ops[i] = it.copy(turn = (it.turn + 90) % 360) } ?: toast("Le foto si girano prima di aggiungerle")
                                    1 -> if (i > 0) { ops.add(i - 1, ops.removeAt(i)) }
                                    2 -> if (i < ops.lastIndex) { ops.add(i + 1, ops.removeAt(i)) }
                                    3 -> if (ops.size > 1) ops.removeAt(i) else toast("Serve almeno una pagina")
                                }
                                render()
                            }.show()
                    }
                }
                grid.addView(cell)
                val k = keyOf(op)
                if (thumbs.containsKey(k)) iv.setImageBitmap(thumbs[k])
                else io.execute {
                    val b = when (op) {
                        is PdfEdit.PageOp.FromPdf -> PdfEdit.render(op.file, op.index, 220)
                        is PdfEdit.PageOp.Photo -> runCatching { android.graphics.BitmapFactory.decodeFile(op.file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
                    }
                    runOnUiThread { thumbs[k] = b; iv.setImageBitmap(b) }
                }
            }
        }
        hintLabel(body, "Tocca una pagina per ruotarla, spostarla o toglierla.")
        body.addView(grid)
        listRow(body, "➕ Aggiungi un PDF", "Le sue pagine vanno in fondo", onClick = {
            pdfPickDone = { uri ->
                io.execute {
                    val f = File(pdfDir, "add_" + System.currentTimeMillis() + ".pdf")
                    val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
                    val n = if (ok) PdfEdit.pageCount(f) else 0
                    runOnUiThread { if (n == 0) toast("Non riesco a leggere quel PDF") else { (0 until n).forEach { ops += PdfEdit.PageOp.FromPdf(f, it) }; render(); toast("Aggiunte $n pagine") } }
                }
            }
            runCatching { pdfPick.launch(arrayOf("application/pdf")) }
        })
        listRow(body, "📷 Aggiungi una foto", "Scatta una foto o scegline una: diventa una pagina", onClick = {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("📷 Scatta una foto", "🖼 Scegli dalla galleria")) { _, w ->
                if (w == 0) capturePhoto(File(scanDir, "pdfpage_${System.currentTimeMillis()}.jpg")) { shot -> if (shot != null) { ops += PdfEdit.PageOp.Photo(shot); render() } }
                else {
                    imagePickDone = { uri ->
                        io.execute {
                            val f = File(scanDir, "pdfpage_${System.currentTimeMillis()}.jpg")
                            val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
                            runOnUiThread { if (ok) { ops += PdfEdit.PageOp.Photo(f); render() } else toast("Non riesco a leggere la foto") }
                        }
                    }
                    runCatching { imagePick.launch("image/*") }
                }
            }.show()
        })
        save = {
            val out = File(pdfDir, "pages_${System.currentTimeMillis()}.pdf")
            toast("Creo il nuovo PDF…")
            io.execute {
                val r = runCatching { PdfEdit.rebuild(this, ops.toList(), out) }
                runOnUiThread { r.onSuccess { dialog.dismiss(); pdfDone(out, newPdfName(name, "${ops.size} pagine")) }.onFailure { toast("Non riuscito: ${it.message}".take(140)) } }
            }
        }
        render()
    }
}

// ---------- ask the AI ----------

internal fun MainActivity.pdfAsk(file: File, name: String) {
    if (!aiReady()) return
    val qs = arrayOf("📝 Riassumi", "💶 Cosa devo pagare e entro quando?", "🙂 Spiegamelo in parole semplici", "🌐 Traducilo in italiano", "❓ Fai tu una domanda")
    MaterialAlertDialogBuilder(this).setTitle("✨ Chiedi a questo PDF").setItems(qs) { _, w ->
        val q = when (w) {
            0 -> "Riassumi questo documento in italiano: prima 3 righe con l'essenziale, poi i punti importanti."
            1 -> "Dimmi in italiano se in questo documento ci sono importi da pagare e scadenze: quanto, entro quando, a chi, come. Se non ce ne sono dillo chiaramente."
            2 -> "Spiegami questo documento in parole semplici, in italiano, come a una persona che non conosce i termini tecnici. Dimmi se c'è qualcosa che devo fare."
            3 -> "Traduci in italiano questo documento, mantenendo i titoli."
            else -> null
        }
        toast("Leggo il PDF…")
        io.execute {
            val text = PdfEdit.text(this, file)
            runOnUiThread {
                val tail = " Indica le pagine da cui prendi le informazioni quando puoi."
                if (text != null) showAiSheet("Chiedi a «${name.take(40)}»", "Testo del PDF «$name»:\n$text\n", q?.plus(tail), showFirstQuestion = q != null && w == 4, hint = "Fai una domanda sul PDF…")
                // a scan without text: the PDF itself goes to the AI (works with models that read files, e.g. Gemini)
                else showAiSheet("Chiedi a «${name.take(40)}»", null, q ?: "Leggi il PDF allegato e dimmi in breve di cosa si tratta.", showFirstQuestion = false,
                    hint = "Fai una domanda sul PDF…", firstAttachments = listOf(Triple("pdf", "application/pdf", "/files/pdf/" + file.name)))
            }
        }
    }.show()
}
