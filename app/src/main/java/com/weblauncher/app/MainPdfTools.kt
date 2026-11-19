package com.weblauncher.app

import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= PDF: chiedi all'AI e condividi (firma, compila e pagine sono nell'app «PDF e fogli») =======================

/** The PDF file behind the in-app viewer address (…viewer.html?file=/files/pdf/x.pdf). */
internal fun MainActivity.pdfFileOf(url: String?): File? {
    val p = runCatching { Uri.parse(url).getQueryParameter("file") }.getOrNull() ?: return null
    return File(pdfDir, p.substringAfterLast('/')).takeIf { it.exists() }
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
        extractPdfText(file) { text ->
            run {
                val tail = " Indica le pagine da cui prendi le informazioni quando puoi."
                if (text != null) showAiSheet("Chiedi a «${name.take(40)}»", "Testo del PDF «$name»:\n$text\n", q?.plus(tail), showFirstQuestion = q != null && w == 4, hint = "Fai una domanda sul PDF…")
                // a scan without text: the PDF itself goes to the AI (works with models that read files, e.g. Gemini)
                else showAiSheet("Chiedi a «${name.take(40)}»", null, q ?: "Leggi il PDF allegato e dimmi in breve di cosa si tratta.", showFirstQuestion = false,
                    hint = "Fai una domanda sul PDF…", firstAttachments = listOf(Triple("pdf", "application/pdf", "/files/pdf/" + file.name)))
            }
        }
    }.show()
}
