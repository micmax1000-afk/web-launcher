package com.weblauncher.app

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= AI tools: Scrivi per me, traduci la pagina, frigo, spiegami un foglio =======================

private fun MainActivity.chipRow(body: LinearLayout, options: List<String>, selected: String, onPick: (String) -> Unit) {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
    val views = mutableListOf<TextView>()
    fun paint(cur: String) = views.forEach { v ->
        val on = v.text == cur
        v.setTextColor(if (on) android.graphics.Color.WHITE else palette.text)
        v.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (on) accent else palette.card); setStroke(1, if (on) accent else palette.stroke) }
    }
    options.forEach { o ->
        views += TextView(this).apply {
            text = o; textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            setOnClickListener { paint(o); onPick(o) }
            row.addView(this)
        }
    }
    paint(selected)
    body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
}

private fun MainActivity.dictateInto(et: EditText) {
    noteDictationTarget = et
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
    }
    try { noteSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
}

// ---------- Scrivi per me ----------

internal fun MainActivity.showWriteForMe(startKind: String = "Email") {
    if (!aiReady()) return
    openPage("Scrivi per me") { _, body, _ ->
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var kind = startKind; var tone = "Cortese"
        sectionLabel(body, "COSA DEVO SCRIVERE")
        chipRow(body, AiTools.KINDS, kind) { kind = it }
        val what = EditText(this).apply {
            hint = "Bastano poche parole, es. «disdire la palestra dal mese prossimo»"; minLines = 3; gravity = Gravity.TOP
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(what, LinearLayout.LayoutParams(-1, -2))
        val mic = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🎤 Dillo a voce"; isAllCaps = false; setOnClickListener { dictateInto(what) }
        }
        body.addView(mic, LinearLayout.LayoutParams(-2, -2))
        sectionLabel(body, "TONO")
        chipRow(body, AiTools.TONES, tone) { tone = it }
        val go = com.google.android.material.button.MaterialButton(this).apply { text = "✨ Scrivi"; isAllCaps = false }
        body.addView(go, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        val result = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); setPadding(dp(8), dp(16), dp(8), dp(8)); setTextColor(AppRepository.palette(this@showWriteForMe).text) }
        body.addView(result)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = android.view.View.GONE }
        body.addView(actions)
        var last = ""
        fun action(t: String, onClick: () -> Unit) = actions.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        })
        action("📋 Copia") { copyText("Testo", last); toast("Copiato") }
        action("✉️ Apri l'email") {
            val (subj, text) = AiTools.splitSubject(last)
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply { subj?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { startActivity(i) }.onFailure { toast("Nessuna app per le email") }
        }
        action("📤 Invia") { runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AiTools.splitSubject(last).second) }, "Invia con")) } }
        action("🔁 Rifai") { go.performClick() }
        go.setOnClickListener {
            val w = what.text.toString().trim()
            if (w.isEmpty()) { toast("Scrivi o di' in poche parole cosa vuoi dire"); return@setOnClickListener }
            go.isEnabled = false; result.text = "✨ Scrivo…"; actions.visibility = android.view.View.GONE
            ai.ask(listOf("user" to AiTools.writePrompt(kind, tone, w)), "Sei un assistente che scrive testi pronti da inviare, in italiano corretto e naturale.", object : AiBridge.Listener {
                override fun onPartial(text: String) { result.text = text }
                override fun onDone(text: String, model: String) {
                    go.isEnabled = true; last = text.trim(); result.text = last
                    actions.visibility = android.view.View.VISIBLE
                    actions.getChildAt(1).visibility = if (kind == "WhatsApp" || kind == "Auguri") android.view.View.GONE else android.view.View.VISIBLE
                }
                override fun onError(message: String, noKey: Boolean) {
                    go.isEnabled = true
                    result.text = if (noKey) "L'AI non è configurata: si imposta in Mia AI." else "Non riuscito: $message"
                }
            })
        }
    }
}

// ---------- translate the whole page in the Browser ----------

/** Translates the page in place with the AI (Google Translate when the AI is not available). */
internal fun MainActivity.translatePageInPlace() {
    val w = webView
    if (!ai.supported()) { translateCurrentPage(); return }
    toast("🌐 Traduco la pagina…")
    w.evaluateJavascript(AiTools.COLLECT_JS) { raw ->
        val texts = AiTools.stringArray(jsString(raw)).orEmpty()
        if (texts.isEmpty()) { toast("Non trovo testo da tradurre"); return@evaluateJavascript }
        val parts = AiTools.batches(texts)
        var done = 0; var failed = false
        val snack = com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), "🌐 Traduco… 0/${parts.size}", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction("Mostra l'originale") { w.evaluateJavascript(AiTools.RESTORE_JS, null) }
        snack.show()
        fun next(i: Int) {
            if (i >= parts.size || failed || w.url == null) {
                snack.dismiss()
                if (!failed) com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), "✓ Pagina tradotta in italiano", 10_000)
                    .setAction("Mostra l'originale") { w.evaluateJavascript(AiTools.RESTORE_JS, null) }.show()
                return
            }
            val r = parts[i]
            val slice = texts.subList(r.first, r.last + 1)
            val input = org.json.JSONArray(slice).toString()
            ai.ask(listOf("user" to "Traduci in italiano ogni elemento di questo array JSON. Rispondi SOLO con un array JSON con lo stesso numero di elementi (${slice.size}), nello stesso ordine. " +
                "Lascia invariati nomi propri, numeri e testi già in italiano.\n$input"), "Sei un traduttore. Rispondi solo con JSON valido.", object : AiBridge.Listener {
                override fun onDone(text: String, model: String) {
                    val out = AiTools.stringArray(text)
                    if (out != null) w.evaluateJavascript(AiTools.applyJs(r.first, org.json.JSONArray(out.take(slice.size)).toString()), null)
                    done++; snack.setText("🌐 Traduco… $done/${parts.size}")
                    next(i + 1)
                }
                override fun onError(message: String, noKey: Boolean) {
                    failed = true; snack.dismiss()
                    if (noKey) MaterialAlertDialogBuilder(this@translatePageInPlace).setTitle("L'AI non è configurata")
                        .setMessage("Per tradurre qui dentro serve l'AI (si imposta in Mia AI). Intanto posso aprire la pagina con Google Traduttore.")
                        .setPositiveButton("Google Traduttore") { _, _ -> translateCurrentPage() }.setNegativeButton("Chiudi", null).show()
                    else toast("Traduzione interrotta: $message".take(120))
                }
            })
        }
        next(0)
    }
}

/** After a page loads: if it says it is in another language, offer the translation (once per site). */
internal fun MainActivity.maybeOfferTranslation(view: android.webkit.WebView?, url: String?) {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
    if (!url.orEmpty().startsWith("http") || host == lastTranslateOffer || !ai.supported()) return
    view?.evaluateJavascript("(document.documentElement.lang||'')") { raw ->
        if (!AiTools.isForeign(jsString(raw)) || webView.url != url) return@evaluateJavascript
        lastTranslateOffer = host
        com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), "🌐 Questa pagina non è in italiano", 8000)
            .setAction("Traduci in italiano") { translatePageInPlace() }.show()
    }
}

// ---------- fridge photo → recipes ----------

internal fun MainActivity.fridgePhoto() {
    if (!aiReady()) return
    val f = File(scanDir, "frigo_${System.currentTimeMillis()}.jpg")
    capturePhoto(f) { shot ->
        if (shot == null) return@capturePhoto
        toast("✨ Guardo cosa c'è in frigo…")
        io.execute {
            shrinkPhoto(shot)
            runOnUiThread {
                ai.ask(listOf("user" to "Guarda la foto del frigo o della dispensa. Riconosci gli ingredienti che si vedono e proponi 3 ricette semplici che li usano. " +
                    "Rispondi SOLO con JSON: {\"ingredienti\":[\"uova\",...],\"ricette\":[{\"titolo\":\"...\",\"minuti\":20,\"usa\":[\"uova\",...],\"mancano\":[\"parmigiano\",...],\"passaggi\":[\"...\",...]}]}. " +
                    "In italiano. Se non vedi cibo rispondi {\"ingredienti\":[]}."), "Rispondi solo con JSON valido.", object : AiBridge.Listener {
                    override fun onDone(text: String, model: String) {
                        val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                        val ing = o?.optJSONArray("ingredienti")
                        if (o == null || ing == null || ing.length() == 0) { toast("Non riconosco ingredienti nella foto"); return }
                        showFridgeResult(shot, o)
                    }
                    override fun onError(message: String, noKey: Boolean) {
                        MaterialAlertDialogBuilder(this@fridgePhoto).setTitle(if (noKey) "L'AI non è configurata" else "L'AI non è riuscita")
                            .setMessage(if (noKey) "Serve un provider AI con la chiave, che si imposta in Mia AI." else "Motivo: $message\n\nPer le foto serve un modello che legge le immagini (per esempio Gemini).")
                            .setPositiveButton("Chiudi", null).show()
                    }
                }, listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)))
            }
        }
    }
}

private fun shrinkPhoto(shot: File) = runCatching {
    val bmp = android.graphics.BitmapFactory.decodeFile(shot.absolutePath)
    val sc = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
    val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
    shot.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
}

private fun org.json.JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

private fun MainActivity.showFridgeResult(shot: File, o: org.json.JSONObject) {
    openPage("Dal tuo frigo") { _, body, _ ->
        val d = resources.displayMetrics.density
        body.addView(android.widget.ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(shot.path)); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(-1, (160 * d).toInt()))
        sectionLabel(body, "HO RICONOSCIUTO")
        hintLabel(body, o.optJSONArray("ingredienti").strings().joinToString(" · "))
        val recipes = o.optJSONArray("ricette")
        sectionLabel(body, "PUOI CUCINARE")
        for (i in 0 until (recipes?.length() ?: 0)) {
            val r = recipes!!.optJSONObject(i) ?: continue
            val missing = r.optJSONArray("mancano").strings()
            val steps = r.optJSONArray("passaggi").strings()
            val title = r.optString("titolo")
            val sub = "⏱ ${r.optInt("minuti").takeIf { it > 0 }?.let { "$it min" } ?: "?"}" + if (missing.isEmpty()) " · hai tutto" else " · manca: " + missing.joinToString(", ")
            listRow(body, "🍳 $title", sub, onClick = {
                val b = MaterialAlertDialogBuilder(this).setTitle(title)
                    .setMessage(("Usa: " + r.optJSONArray("usa").strings().joinToString(", ") + "\n\n" + steps.mapIndexed { k, s -> "${k + 1}. $s" }.joinToString("\n")).trim())
                    .setPositiveButton("Chiudi", null)
                if (missing.isNotEmpty()) b.setNeutralButton("+ Spesa") { _, _ -> addItemsToShoppingList(missing) }
                b.show()
            })
            if (missing.isNotEmpty()) listRow(body, "   🛒 + Spesa: ${missing.joinToString(", ")}", null, onClick = { addItemsToShoppingList(missing) })
        }
        hintLabel(body, "L'AI può sbagliare a riconoscere qualcosa: controlla prima di cucinare.")
    }
}

// ---------- «Spiegami questo foglio» ----------

private const val EXPLAIN_Q = "Spiegami questo foglio in italiano semplice, come a una persona che non conosce i termini tecnici. " +
    "Prima dimmi in una riga che documento è (busta paga, referto, lettera, multa, bolletta…). Poi i punti importanti, " +
    "poi se devo fare qualcosa e entro quando. Se è un referto medico non fare diagnosi: spiega i termini e i valori fuori norma e suggerisci di parlarne col medico."

internal fun MainActivity.explainSheet() {
    if (!aiReady()) return
    MaterialAlertDialogBuilder(this).setTitle("🔍 Spiegami un foglio")
        .setItems(arrayOf("📷 Fotografa il foglio", "📑 Scegli un PDF")) { _, w ->
            if (w == 0) {
                val f = File(scanDir, "foglio_${System.currentTimeMillis()}.jpg")
                capturePhoto(f) { shot ->
                    if (shot == null) return@capturePhoto
                    io.execute { shrinkPhoto(shot); runOnUiThread { explainShow(null, listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name))) } }
                }
            } else {
                pdfPickDone = { uri ->
                    val name = displayName(uri)
                    io.execute {
                        val dest = File(pdfDir, "explain_" + System.currentTimeMillis() + ".pdf")
                        val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } } }.isSuccess
                        val text: String? = null   // il PDF va all'AI così com'è
                        runOnUiThread {
                            when {
                                !ok -> toast("Non riesco ad aprire il PDF")
                                text != null -> explainShow("Testo del documento «$name»:\n$text\n", emptyList())
                                else -> explainShow(null, listOf(Triple("pdf", "application/pdf", "/files/pdf/" + dest.name)))
                            }
                        }
                    }
                }
                runCatching { pdfPick.launch(arrayOf("application/pdf")) }
            }
        }.show()
}

private fun MainActivity.explainShow(context: String?, attachments: List<Triple<String, String, String>>) {
    toast("✨ Leggo il foglio…")
    showAiSheet("Spiegami questo foglio", context, "$EXPLAIN_Q\nAlla fine aggiungi una riga: «Spiegazione indicativa: per decisioni importanti chiedi a chi ha emesso il documento o a un esperto.»",
        showFirstQuestion = false, hint = "Fai una domanda su questo foglio…", firstAttachments = attachments)
}
