package com.weblauncher.app

import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= Bills in PDF: the AI reads amount and due date =======================

/** A PDF bill (e.g. from an e-mail): first pages rendered to images, then read like a receipt. */
internal fun MainActivity.billFromPdf(uri: Uri) {
    if (!aiReady()) return
    toast("✨ Leggo la bolletta…")
    io.execute {
        val res = runCatching {
            val pdf = File(scanDir, "bill_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)!!.use { input -> pdf.outputStream().use { input.copyTo(it) } }
            val images = mutableListOf<File>()
            android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r ->
                    for (i in 0 until minOf(r.pageCount, 2)) r.openPage(i).use { page ->
                        val scale = 1600f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        images += File(scanDir, "bill_${System.currentTimeMillis()}_$i.jpg").also { f ->
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        }
                    }
                }
            }
            images to pdf
        }
        runOnUiThread {
            val (images, pdf) = res.getOrNull() ?: run { toast("PDF non leggibile (protetto da password?)"); return@runOnUiThread }
            readReceipt(images, pdf)
        }
    }
}

// ======================= Spoken news bulletin =======================

internal fun MainActivity.newsBriefingChoice() {
    MaterialAlertDialogBuilder(this).setTitle("Il punto della giornata")
        .setItems(arrayOf("📝 Leggi il riassunto", "🎧 Ascolta il notiziario (2-3 minuti)")) { _, w ->
            if (w == 0) newsBriefing() else newsAudioBulletin()
        }.show()
}

/** The AI writes a short radio-style bulletin from today's headlines; text-to-speech reads it. */
internal fun MainActivity.newsAudioBulletin() {
    if (!aiReady()) return
    if (newsItems.isEmpty()) { toast("Prima carica le notizie"); return }
    val since = System.currentTimeMillis() - 24 * 3600_000L
    val top = newsItems.filter { it.time >= since }.ifEmpty { newsItems }
        .sortedWith(compareByDescending<NewsItem> { (it.also ?: emptyList()).size }.thenByDescending { it.time }).take(40)
    val lines = top.joinToString("\n") { n -> "- [${n.cat}] ${n.title}. ${n.summary.take(220)} (${n.source})" }
    val waiting = MaterialAlertDialogBuilder(this).setTitle("🎧 Notiziario").setMessage("Preparo il notiziario…")
        .setNegativeButton("Annulla", null).show()
    ai.ask(listOf("user" to "Notizie di oggi da testate italiane:\n$lines\n\n" +
        "Scrivi un notiziario radiofonico in italiano da leggere ad alta voce in circa 2 minuti e mezzo: " +
        "un saluto breve, poi 6-7 notizie dalla più importante, 2-3 frasi ciascuna, con passaggi naturali tra una e l'altra; " +
        "se ci sono notizie locali di Piemonte, Lombardia o Umbria dedica loro una frase alla fine. " +
        "Testo semplice: niente elenchi puntati, simboli, titoli o emoji. Usa solo queste informazioni."),
        "Sei uno speaker radiofonico italiano, chiaro e neutrale.", object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                runCatching { waiting.dismiss() }
                if (isFinishing) return
                val rate = getSharedPreferences("wl_prefs", MODE_PRIVATE).getFloat("reader_rate", 1f)
                MaterialAlertDialogBuilder(this@newsAudioBulletin).setTitle("🎧 Notiziario di oggi").setMessage(text)
                    .setPositiveButton("⏹ Stop") { _, _ -> tts?.stop() }
                    .setNeutralButton("🔁 Da capo") { _, _ -> speak(text, rate) }
                    .setOnCancelListener { tts?.stop() }.show()
                speak(text, rate)
            }
            override fun onError(message: String, noKey: Boolean) { runCatching { waiting.dismiss() }; toast(message) }
        })
}

// ======================= Weekly menu → shopping list =======================

/** Choose the recipes of the week: one shopping list with the ingredients merged (by the AI when available). */
internal fun MainActivity.weeklyMenu() {
    val recipes = Recipes.load(this)
    if (recipes.isEmpty()) { toast("Prima salva qualche ricetta"); return }
    val checked = BooleanArray(recipes.size)
    MaterialAlertDialogBuilder(this).setTitle("📅 Cosa cucini questa settimana?")
        .setMultiChoiceItems(recipes.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
        .setPositiveButton("Crea la lista") { _, _ ->
            val chosen = recipes.filterIndexed { i, _ -> checked[i] }
            if (chosen.isEmpty()) { toast("Nessuna ricetta scelta"); return@setPositiveButton }
            val raw = chosen.flatMap { it.ingredients }
            if (!ai.supported()) { addItemsToShoppingList(raw.distinct()); return@setPositiveButton }
            toast("✨ Unisco gli ingredienti…")
            val input = chosen.joinToString("\n\n") { r -> r.title + " (" + r.servings.ifBlank { "?" } + " porzioni):\n" + r.ingredients.joinToString("\n") { "- $it" } }
            ai.ask(listOf("user" to "Ricette scelte per la settimana:\n$input\n\n" +
                "Crea UNA lista della spesa: unisci gli ingredienti uguali sommando le quantità, togli acqua, sale e pepe, " +
                "ordina per reparto (frutta e verdura, carne e pesce, latticini, dispensa, altro). " +
                "Rispondi SOLO con un ingrediente per riga, senza titoli né simboli, es. «Pomodori 600 g»."),
                "Rispondi solo con l'elenco richiesto.", object : AiBridge.Listener {
                    override fun onDone(text: String, model: String) {
                        val items = text.lines().map { it.trim().trimStart('-', '•', '*', ' ').trim() }
                            .filter { it.isNotEmpty() && !it.endsWith(":") && it.length < 80 }
                        addItemsToShoppingList(items.ifEmpty { raw.distinct() })
                    }
                    override fun onError(message: String, noKey: Boolean) { addItemsToShoppingList(raw.distinct()) }
                })
        }
        .setNegativeButton("Annulla", null).show()
}
