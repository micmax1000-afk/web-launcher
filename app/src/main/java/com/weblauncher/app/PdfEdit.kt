package com.weblauncher.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Changes to PDFs, always written to a new file (the original stays as it is).
 * Positions are fractions of the page as it is shown (0..1, from the top left), whatever the page rotation.
 */
object PdfEdit {
    private var ready = false
    fun init(c: Context) { if (!ready) { PDFBoxResourceLoader.init(c.applicationContext); ready = true } }

    sealed class Edit(val page: Int) {
        /** A picture (signature, written text, note) in the rectangle x, y, w, h. */
        class Image(page: Int, val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float) : Edit(page)
        /** A see-through yellow mark. */
        class Highlight(page: Int, val x: Float, val y: Float, val w: Float, val h: Float) : Edit(page)
    }

    data class Field(val name: String, val label: String, val kind: String, val value: String, val options: List<String>)

    // ---------- coordinates (pure, unit-tested) ----------

    /**
     * A point shown at (fx, fy) — fractions from the top left of the page as displayed — in PDF units, for a page
     * whose box starts at (llx, lly), is [w] × [h] wide when not rotated, and is shown rotated [rotation] degrees clockwise.
     */
    fun toPdf(fx: Float, fy: Float, llx: Float, lly: Float, w: Float, h: Float, rotation: Int): Pair<Float, Float> {
        val (u, v) = when (((rotation % 360) + 360) % 360) {
            90 -> fy to fx
            180 -> (1 - fx) to fy
            270 -> (1 - fy) to (1 - fx)
            else -> fx to (1 - fy)
        }
        return (llx + u * w) to (lly + v * h)
    }

    private fun mapper(page: PDPage): (Float, Float) -> Pair<Float, Float> {
        val box = page.cropBox ?: page.mediaBox
        return { fx, fy -> toPdf(fx, fy, box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation) }
    }

    // ---------- reading ----------

    private fun load(file: File, password: String? = null): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)

    fun pageCount(file: File): Int = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } }.getOrDefault(0)

    /** The page as a picture [width] pixels wide (white background), as it is shown. */
    fun render(file: File, page: Int, width: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(page.coerceIn(0, r.pageCount - 1)).use { p ->
                    val hgt = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, hgt, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    fun text(c: Context, file: File, maxChars: Int = 60_000): String? = runCatching {
        init(c)
        load(file).use { d -> PDFTextStripper().apply { sortByPosition = true }.getText(d) }
    }.getOrNull()?.replace(Regex("[ \\t]+"), " ")?.replace(Regex("\\n{3,}"), "\n\n")?.trim()?.take(maxChars)?.takeIf { it.length >= 40 }

    fun fields(c: Context, file: File): List<Field> {
        init(c)
        return load(file).use { d ->
            val form = d.documentCatalog.acroForm ?: return@use emptyList()
            form.fieldTree.mapNotNull { f ->
                val label = (f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName).trim()
                when (f) {
                    is PDCheckBox -> Field(f.fullyQualifiedName, label, "check", if (f.isChecked) "true" else "false", emptyList())
                    is PDRadioButton -> Field(f.fullyQualifiedName, label, "radio", f.value.orEmpty(), f.onValues.toList())
                    is PDChoice -> Field(f.fullyQualifiedName, label, "choice", f.value.firstOrNull().orEmpty(), f.options.toList())
                    is PDTextField -> Field(f.fullyQualifiedName, label, "text", f.value.orEmpty(), emptyList())
                    else -> null
                }
            }
        }
    }

    // ---------- writing ----------

    fun fill(c: Context, src: File, out: File, values: Map<String, String>) {
        init(c)
        load(src).use { d ->
            val form = d.documentCatalog.acroForm ?: error("Questo PDF non ha campi da compilare")
            form.needAppearances = true
            values.forEach { (name, v) ->
                when (val f = form.getField(name)) {
                    is PDCheckBox -> if (v == "true") f.check() else f.unCheck()
                    is PDRadioButton -> if (v.isNotBlank()) runCatching { f.value = v }
                    is PDChoice -> if (v.isNotBlank()) runCatching { f.setValue(v) }
                    is PDTextField -> runCatching { f.value = v }.onFailure { form.needAppearances = true }
                    else -> {}
                }
            }
            d.save(out)
        }
    }

    fun apply(c: Context, src: File, out: File, edits: List<Edit>) {
        init(c)
        load(src).use { d ->
            edits.groupBy { it.page }.forEach { (pi, list) ->
                val page = d.getPage(pi)
                val map = mapper(page)
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    list.forEach { e ->
                        when (e) {
                            is Edit.Image -> {
                                val img = LosslessFactory.createFromImage(d, e.bitmap)
                                val bl = map(e.x, e.y + e.h); val br = map(e.x + e.w, e.y + e.h); val tl = map(e.x, e.y)
                                cs.drawImage(img, Matrix(br.first - bl.first, br.second - bl.second, tl.first - bl.first, tl.second - bl.second, bl.first, bl.second))
                            }
                            is Edit.Highlight -> {
                                val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.35f }
                                cs.saveGraphicsState()
                                cs.setGraphicsStateParameters(gs)
                                cs.setNonStrokingColor(253, 224, 71)
                                val pts = listOf(map(e.x, e.y), map(e.x + e.w, e.y), map(e.x + e.w, e.y + e.h), map(e.x, e.y + e.h))
                                cs.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { cs.lineTo(it.first, it.second) }
                                cs.closePath(); cs.fill()
                                cs.restoreGraphicsState()
                            }
                        }
                    }
                }
            }
            d.save(out)
        }
    }

    /** One page of the new PDF: page [index] of [file] turned by [turn] degrees, or a photo. */
    sealed class PageOp {
        data class FromPdf(val file: File, val index: Int, val turn: Int = 0) : PageOp()
        data class Photo(val file: File) : PageOp()
    }

    fun rebuild(c: Context, ops: List<PageOp>, out: File) {
        init(c)
        val opened = HashMap<String, PDDocument>()
        try {
            PDDocument().use { nd ->
                ops.forEach { op ->
                    when (op) {
                        is PageOp.FromPdf -> {
                            val src = opened.getOrPut(op.file.path) { load(op.file) }
                            val p = nd.importPage(src.getPage(op.index))
                            p.rotation = ((src.getPage(op.index).rotation + op.turn) % 360 + 360) % 360
                        }
                        is PageOp.Photo -> {
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                            var bmp = android.graphics.BitmapFactory.decodeFile(op.file.path, opts) ?: return@forEach
                            val scale = minOf(1f, 2000f / maxOf(bmp.width, bmp.height))
                            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                            val a4 = if (bmp.width > bmp.height) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                            val page = PDPage(a4); nd.addPage(page)
                            val img = JPEGFactory.createFromImage(nd, bmp, 0.85f)
                            val m = 18f
                            val fit = minOf((a4.width - 2 * m) / bmp.width, (a4.height - 2 * m) / bmp.height)
                            val w = bmp.width * fit; val h = bmp.height * fit
                            PDPageContentStream(nd, page).use { it.drawImage(img, (a4.width - w) / 2, (a4.height - h) / 2, w, h) }
                        }
                    }
                }
                if (nd.numberOfPages == 0) error("Nessuna pagina")
                nd.save(out)
            }
        } finally { opened.values.forEach { runCatching { it.close() } } }
    }

    /** A small self-check for the automatic test: every kind of change on a PDF made on the spot. */
    fun selfTest(c: Context, dir: File): String = runCatching {
        init(c)
        val src = File(dir, "prova-modifica.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.addPage(PDPage(PDRectangle.A4).apply { rotation = 90 }); d.save(src) }
        val sig = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val a = File(dir, "prova-modifica-firmato.pdf")
        apply(c, src, a, listOf(Edit.Image(0, sig, 0.6f, 0.8f, 0.3f, 0.1f), Edit.Highlight(0, 0.1f, 0.1f, 0.5f, 0.03f), Edit.Image(1, sig, 0.1f, 0.1f, 0.2f, 0.1f)))
        val b = File(dir, "prova-modifica-pagine.pdf")
        rebuild(c, listOf(PageOp.FromPdf(a, 1, 90), PageOp.FromPdf(a, 0), PageOp.FromPdf(src, 0)), b)
        "PDFEDIT_OK pagine=${pageCount(b)} render=${render(b, 0, 300) != null}"
    }.getOrElse { "PDFEDIT_ERR ${it.javaClass.simpleName}: ${it.message}" }
}
