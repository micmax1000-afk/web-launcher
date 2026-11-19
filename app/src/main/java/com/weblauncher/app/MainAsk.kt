package com.weblauncher.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID

// ======================= «Chiedi»: l'AI in un solo posto =======================

private fun MainActivity.accentColor() =
    com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())

/**
 * La finestra «Chiedi»: una chat a tutto schermo dentro l'app. Le conversazioni si salvano (Cronologia).
 * [attachments] viaggiano col primo messaggio (foto o PDF).
 */
internal fun MainActivity.showAskPage(
    prefill: String? = null,
    autoSend: Boolean = false,
    attachments: List<Triple<String, String, String>> = emptyList(),
    attachLabel: String? = null,
    chat: AskChats.Chat? = null
) {
    if (!aiReady()) return
    val palette = AppRepository.palette(this)
    val accent = accentColor()
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val chatId = chat?.id ?: UUID.randomUUID().toString()
    val msgs = chat?.msgs.orEmpty().toMutableList()
    var running = 0
    var pendingAtts: List<Triple<String, String, String>> = attachments
    var pendingLabel: String? = attachLabel
    var pageCtx: String? = null
    var historyAction: () -> Unit = {}
    var chatTitle = chat?.title.orEmpty()

    openPage("✨ Chiedi", "Cronologia", { historyAction() }) { root, body, dialog ->
        val scroll = body.parent as ScrollView
        val modelLabel = TextView(this).apply { textSize = 12f; setTextColor(palette.textMuted); setPadding(dp(6), 0, 0, dp(4)) }
        body.addView(modelLabel)
        ai.status { ok, label -> modelLabel.text = if (ok) label else "L'AI non è ancora configurata: Impostazioni → Assistente AI" }

        fun bubble(text: CharSequence, user: Boolean): TextView = TextView(this).apply {
            this.text = text; textSize = 15.5f; setLineSpacing(0f, 1.15f)
            setTextColor(if (user) Color.WHITE else palette.text)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 18f * d; setColor(if (user) accent else palette.card)
                if (!user) setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (user) Gravity.END else Gravity.START
                setMargins(if (user) dp(48) else 0, dp(6), if (user) 0 else dp(28), dp(2))
            }
            body.addView(this)
        }

        fun actions(answer: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
            fun chip(label: String, onClick: () -> Unit) = row.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply { cornerRadius = 16f * d; setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { onClick() }
            })
            chip("Copia") { copyText("Risposta AI", answer) }
            chip("Condividi") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, answer) }, "Condividi risposta"))
            }
            chip("Leggi") { speak(answer, 1f) }
            chip("📝 Nota") {
                notes.save(notes.load() + Note(UUID.randomUUID().toString(), chatTitle.ifBlank { "Risposta AI" } + "\n" + answer, System.currentTimeMillis()))
                toast("Salvato nelle Note")
            }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        // ---- la barra in basso: allegato, testo, microfono, invio ----
        val attachInfo = TextView(this).apply {
            textSize = 13f; setTextColor(accent); setPadding(dp(18), dp(4), dp(18), 0); visibility = View.GONE
            setOnClickListener { pendingAtts = emptyList(); pendingLabel = null; pageCtx = null; visibility = View.GONE }
        }
        fun refreshAttach() {
            val l = pendingLabel
            attachInfo.visibility = if (l == null) View.GONE else View.VISIBLE
            attachInfo.text = if (l == null) "" else "📎 $l  ✕"
        }
        val input = EditText(this).apply {
            hint = "Scrivi un messaggio…"; textSize = 15f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            maxLines = 4; imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = 22f * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun roundBtn(icon: Int, fill: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); setColorFilter(tint); contentDescription = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

        fun send(question: String) {
            if (question.isBlank() || running != 0) return
            val firstTurn = msgs.isEmpty()
            val ctx = pageCtx
            val content = if (ctx != null) ctx + question else question
            msgs.add(AskChats.Msg("user", content))
            // la schermata mostra solo la domanda, non il testo della pagina
            bubble(question, true)
            val atts = pendingAtts
            pendingAtts = emptyList(); pendingLabel = null; pageCtx = null; refreshAttach()
            val answerView = bubble("…", false)
            input.setText("")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            if (firstTurn && chatTitle.isBlank()) chatTitle = AskChats.titleOf(question)
            val title = chatTitle.ifBlank { AskChats.titleOf(question) }
            running = ai.ask(msgs.map { it.role to it.text }, MainActivity.AI_SYSTEM, object : AiBridge.Listener {
                override fun onPartial(text: String) { answerView.text = formatAi(text) }
                override fun onDone(text: String, model: String) {
                    running = 0
                    msgs.add(AskChats.Msg("assistant", text))
                    answerView.text = formatAi(text)
                    if (model.isNotBlank()) modelLabel.text = model
                    actions(text)
                    AskChats.save(this@showAskPage, AskChats.Chat(chatId, title, System.currentTimeMillis(), msgs.toList()))
                    scroll.post { scroll.smoothScrollTo(0, (answerView.top - dp(8)).coerceAtLeast(0)) }
                }
                override fun onError(message: String, noKey: Boolean) {
                    running = 0
                    msgs.removeAt(msgs.lastIndex)
                    answerView.text = message
                    answerView.setTextColor(ContextCompat.getColor(this@showAskPage, R.color.danger))
                    if (noKey) body.addView(com.google.android.material.button.MaterialButton(this@showAskPage).apply {
                        text = "Configura l'AI"; isAllCaps = false
                        setOnClickListener { dialog.dismiss(); showAiSettings() }
                    })
                }
            }, atts)
        }

        fun attachMenu() {
            val items = mutableListOf("📷 Una foto", "📑 Un PDF")
            if (browserHasPage) items += "🌐 La pagina aperta nel browser"
            MaterialAlertDialogBuilder(this).setTitle("Allega").setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> {
                        val f = File(scanDir, "chiedi_${System.currentTimeMillis()}.jpg")
                        capturePhoto(f) { shot ->
                            if (shot == null) return@capturePhoto
                            io.execute {
                                shrinkPhoto(shot)
                                runOnUiThread { pendingAtts = listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)); pendingLabel = "Foto"; pageCtx = null; refreshAttach() }
                            }
                        }
                    }
                    1 -> {
                        pdfPickDone = { uri ->
                            val name = displayName(uri)
                            io.execute {
                                val dest = File(pdfDir, "chiedi_" + System.currentTimeMillis() + ".pdf")
                                val ok = runCatching { contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } } }.isSuccess
                                runOnUiThread {
                                    if (!ok) toast("Non riesco ad aprire il PDF")
                                    else { pendingAtts = listOf(Triple("pdf", "application/pdf", "/files/pdf/" + dest.name)); pendingLabel = "PDF: " + name.take(30); pageCtx = null; refreshAttach() }
                                }
                            }
                        }
                        runCatching { pdfPick.launch(arrayOf("application/pdf")) }
                    }
                    else -> readPage { title, url, text ->
                        pageCtx = pageContext(title, url, text); pendingAtts = emptyList(); pendingLabel = "Pagina: " + title.take(30); refreshAttach()
                    }
                }
            }.show()
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(roundBtn(R.drawable.ic_web_more, palette.card, palette.text, "Allega") { attachMenu() })
            addView(input)
            addView(roundBtn(R.drawable.ic_nav_mic, palette.card, accent, "Parla") {
                askVoiceTarget = { q -> input.setText(q); send(q) }
                val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                }
                try { askSpeechLauncher.launch(i) } catch (_: Exception) { askVoiceTarget = null; toast(getString(R.string.no_speech)) }
            })
            addView(roundBtn(R.drawable.ic_web_forward, accent, Color.WHITE, "Invia") { send(input.text?.toString()?.trim().orEmpty()) })
        }
        root.addView(attachInfo)
        root.addView(bar)
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(input.text?.toString()?.trim().orEmpty()); true } else false }

        // ---- il contenuto: conversazione ripresa, oppure il benvenuto con le scorciatoie ----
        fun welcome() {
            body.addView(TextView(this).apply {
                text = "Cosa vuoi chiedere?"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(palette.text); setPadding(dp(6), dp(14), 0, dp(2))
            })
            hintLabel(body, "Scrivi qui sotto, parla col microfono, oppure usa 📎 per allegare una foto, un PDF o la pagina aperta. Oppure parti da qui:")
            listRow(body, "📝 Scrivi per me", "Email, WhatsApp, disdette, reclami, auguri", onClick = { dialog.dismiss(); showWriteForMe() })
            listRow(body, "📄 Spiegami un foglio", "Fotografa o scegli un PDF: ti dico cosa c'è e cosa fare", onClick = { dialog.dismiss(); explainSheet() })
            listRow(body, "🍽 Foto del piatto", "Una stima di cosa c'è nel piatto e delle calorie", onClick = { dialog.dismiss(); foodFromPhoto() })
            listRow(body, "🧊 Frigo e ricette", "Fotografa il frigo: ti propongo cosa cucinare", onClick = { dialog.dismiss(); fridgePhoto() })
            listRow(body, "🧠 Chiedi ai tuoi dati", "Spese, scadenze, note, compleanni…", onClick = { dialog.dismiss(); askMyData() })
        }
        if (msgs.isEmpty()) welcome()
        else msgs.forEach { m ->
            if (m.role == "user") bubble(m.text.substringAfter("--- FINE PAGINA ---\n\n"), true)
            else { bubble(formatAi(m.text), false); actions(m.text) }
        }
        refreshAttach()

        historyAction = {
            val list = AskChats.load(this)
            if (list.isEmpty()) toast("Ancora nessuna conversazione salvata")
            else {
                val labels = (listOf("➕ Nuova conversazione") + list.map { it.title }).toTypedArray()
                MaterialAlertDialogBuilder(this).setTitle("Cronologia").setItems(labels) { _, w ->
                    dialog.dismiss()
                    if (w == 0) showAskPage() else showAskPage(chat = list[w - 1])
                }.setNegativeButton("Chiudi", null).show()
            }
        }
        dialog.setOnDismissListener { if (running != 0) ai.cancel(running); stopSpeaking(); askVoiceTarget = null }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!prefill.isNullOrBlank()) { input.setText(prefill); if (autoSend) send(prefill) }
    }
}

/** La barra «✨ Chiedi…» in cima alla Home, con microfono, fotocamera e le scorciatoie. */
internal fun MainActivity.setupAskBar() {
    val header = findViewById<LinearLayout>(R.id.header) ?: return
    if (header.findViewWithTag<View>("askBar") != null) return
    val palette = AppRepository.palette(this)
    val accent = accentColor()
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val card = LinearLayout(this).apply {
        tag = "askBar"
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(6), 0)
        background = GradientDrawable().apply { cornerRadius = 27f * d; setColor(palette.card); setStroke((1.5f * d).toInt().coerceAtLeast(1), accent) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) }
        setOnClickListener { showAskPage() }
    }
    card.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_ai); setColorFilter(accent)
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
    })
    card.addView(TextView(this).apply {
        text = "Chiedi o scrivi qualcosa…"; textSize = 15f; setTextColor(palette.textMuted)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    fun small(icon: Int, desc: String, onClick: () -> Unit) = card.addView(ImageButton(this).apply {
        setImageResource(icon); setColorFilter(accent); contentDescription = desc; background = null
        setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    })
    small(R.drawable.ic_nav_mic, "Parla") {
        if (!aiReady()) return@small
        askVoiceTarget = { q -> showAskPage(prefill = q, autoSend = true) }
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Cosa vuoi chiedere?")
        }
        try { askSpeechLauncher.launch(i) } catch (_: Exception) { askVoiceTarget = null; toast(getString(R.string.no_speech)) }
    }
    small(R.drawable.ic_tool_scan, "Fotografa") {
        if (!aiReady()) return@small
        val f = File(scanDir, "chiedi_${System.currentTimeMillis()}.jpg")
        capturePhoto(f) { shot ->
            if (shot == null) return@capturePhoto
            io.execute {
                shrinkPhoto(shot)
                runOnUiThread { showAskPage(attachments = listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)), attachLabel = "Foto", prefill = "Cosa vedi in questa foto?") }
            }
        }
    }
    header.addView(card)
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
    fun chip(label: String, onClick: () -> Unit) = chips.addView(TextView(this).apply {
        text = label; textSize = 13.5f; setTextColor(palette.text)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = 18f * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    })
    chip("📝 Scrivi per me") { if (aiReady()) showWriteForMe() }
    chip("📄 Spiegami un foglio") { if (aiReady()) explainSheet() }
    chip("🍽 Foto piatto") { foodFromPhoto() }
    chip("🧊 Frigo") { if (aiReady()) fridgePhoto() }
    chip("🧠 I miei dati") { if (aiReady()) askMyData() }
    header.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
}

// ======================= Impostazioni dell'assistente AI =======================

internal fun MainActivity.showAiSettings() {
    openPage("Assistente AI") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val statusView = TextView(this).apply { textSize = 16f; setTextColor(palette.text); setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (4 * d).toInt()) }
        val detail = TextView(this).apply { textSize = 13.5f; setTextColor(palette.textMuted); setPadding((10 * d).toInt(), 0, (10 * d).toInt(), (10 * d).toInt()) }
        body.addView(statusView); body.addView(detail)
        fun refresh() {
            statusView.text = "Controllo…"
            if (!ai.supported()) { statusView.text = "⚠️ Non disponibile"; detail.text = "Aggiorna «Android System WebView» dal Play Store."; return }
            ai.status { ok, label ->
                statusView.text = if (ok) "✓ Collegata" else "⚠️ Da configurare"
                detail.text = if (ok) label else "Manca la chiave o il modello. Tocca «Cambia chiave o modello»."
            }
        }
        refresh()
        listRow(body, "🔄 Prova il collegamento", "Manda una domanda di prova e dice se l'AI risponde", onClick = {
            if (!aiReady()) return@listRow
            statusView.text = "Provo…"
            ai.ask(listOf("user" to "Rispondi solo con la parola: ok"), "Rispondi in una parola.", object : AiBridge.Listener {
                override fun onDone(text: String, model: String) { statusView.text = "✓ Funziona"; detail.text = (if (model.isNotBlank()) "$model · " else "") + "Risposta: " + text.trim().take(40) }
                override fun onError(message: String, noKey: Boolean) { statusView.text = "⚠️ Non funziona"; detail.text = if (noKey) "Manca la chiave dell'AI." else message.take(200) }
            })
        })
        listRow(body, "🔑 Cambia chiave o modello", "Si fa nelle impostazioni di Mia AI (si apre come pagina)", onClick = { dialog.dismiss(); openMiaAi() })
        sectionLabel(body, "CONVERSAZIONI")
        val n = AskChats.load(this).size
        listRow(body, "📚 Conversazioni salvate: $n", "Tocca per cancellarle tutte", onClick = {
            if (n == 0) toast("Non ce ne sono")
            else MaterialAlertDialogBuilder(this).setTitle("Cancellare tutte le conversazioni?").setMessage("$n conversazioni saranno cancellate. Non si può annullare.")
                .setPositiveButton("Cancella") { _, _ -> AskChats.clear(this); toast("Cancellate"); dialog.dismiss(); showAiSettings() }.setNegativeButton("Annulla", null).show()
        })
        hintLabel(body, "L'AI usa la chiave e il modello che imposti in Mia AI. La qualità delle risposte dipende dal modello scelto: per leggere foto e PDF serve un modello che le capisce, come Gemini.")
    }
}
