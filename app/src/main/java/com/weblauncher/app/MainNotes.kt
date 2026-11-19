package com.weblauncher.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// ======================= Note: cerca, fissa, etichette, colori, promemoria, foto, AI =======================

internal val MainActivity.notePhotoDir: File get() = File(filesDir, "note_photos").apply { mkdirs() }

private fun MainActivity.noteBg(n: Note): Int {
    val palette = AppRepository.palette(this)
    val c = n.color?.let { NoteTools.COLORS.getOrNull(it) } ?: return palette.card
    return if (AppRepository.isLightUi(this)) c.first else c.second
}

internal fun MainActivity.showNotesPage() {
    var query = ""
    var label: String? = null
    openPage("Note", "Nuova", { _ ->
        MaterialAlertDialogBuilder(this).setItems(arrayOf("📝 Nota", "☑ Lista con spunte", "🛒 Lista della spesa")) { _, w ->
            when (w) { 0 -> editNote(null); 1 -> showChecklist(null, "Lista"); else -> showChecklist(null, "Lista della spesa") }
        }.show()
    }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val search = EditText(this).apply {
            hint = "🔍 Cerca nelle note…"; textSize = 15f; setTextColor(palette.text); setHintTextColor(palette.textMuted)
            maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = 22f * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) }
        }
        body.addView(search)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, 0, dp(4)) }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        lateinit var render: () -> Unit
        fun drawChips() {
            chips.removeAllViews()
            (listOf<String?>(null) + NoteTools.LABELS).forEach { l ->
                val on = l == label
                chips.addView(TextView(this).apply {
                    text = l ?: "Tutte"; textSize = 13.5f
                    setTextColor(if (on) accent else palette.text); if (on) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = GradientDrawable().apply { cornerRadius = 18f * d; setColor(palette.card); setStroke(dp(if (on) 2 else 1).coerceAtLeast(1), if (on) accent else palette.stroke) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                    setOnClickListener { label = l; drawChips(); render() }
                })
            }
        }
        fun card(n: Note) {
            val lines = n.text.trim().lines().filter { it.isNotBlank() }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(noteBg(n)); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            }
            val icons = (if (n.photo != null) " 📷" else "") + (if ((n.remindAt ?: 0L) > System.currentTimeMillis()) " ⏰" else "")
            c.addView(TextView(this).apply {
                text = NoteTools.title(n) + icons; textSize = 15.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(palette.text); maxLines = 2
            })
            val sub = listOfNotNull(
                n.label,
                NewsAdapter.ago(n.time),
                n.remindAt?.takeIf { it > System.currentTimeMillis() }?.let { "promemoria " + java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(java.util.Date(it)) },
                lines.drop(1).joinToString(" ").take(80).takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            c.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(palette.textMuted); maxLines = 2; setPadding(0, dp(2), 0, 0) })
            c.setOnClickListener { if (isChecklist(n.text)) showChecklist(n.id) else editNote(n) { render() } }
            c.setOnLongClickListener {
                MaterialAlertDialogBuilder(this).setTitle(NoteTools.title(n))
                    .setItems(arrayOf(if (n.pinned) "📌 Togli dalle fissate" else "📌 Fissa in alto", "📤 Condividi", "🗑 Elimina")) { _, w ->
                        when (w) {
                            0 -> { val all = notes.load(); all.firstOrNull { it.id == n.id }?.pinned = !n.pinned; notes.save(all); render() }
                            1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text) }, "Condividi nota"))
                            else -> confirmClear("Eliminare la nota?") { deleteNote(n.id); render() }
                        }
                    }.show()
                true
            }
            list.addView(c)
        }
        render = {
            list.removeAllViews()
            val all = notes.load()
            val shown = NoteTools.sorted(all.filter { NoteTools.matches(it, query, label) })
            if (all.isEmpty()) emptyLabel(list, "Nessuna nota.\nTocca «Nuova» in alto: puoi anche dettarla.")
            else if (shown.isEmpty()) hintLabel(list, "Nessuna nota trovata.")
            else {
                val pinned = shown.filter { it.pinned }; val others = shown.filter { !it.pinned }
                if (pinned.isNotEmpty()) { sectionLabel(list, "📌 FISSATE"); pinned.forEach { card(it) } }
                if (others.isNotEmpty()) { if (pinned.isNotEmpty()) sectionLabel(list, "ALTRE"); others.forEach { card(it) } }
                hintLabel(list, "Tieni premuta una nota per fissarla in alto, condividerla o eliminarla.")
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); render() }
        })
        drawChips(); render()
        notesRefresh = { render() }
        dialog.setOnDismissListener { notesRefresh = null }
    }
}

/** Toglie una nota, la sua foto e il suo promemoria. */
internal fun MainActivity.deleteNote(id: String) {
    val all = notes.load()
    val n = all.firstOrNull { it.id == id } ?: return
    n.photo?.let { File(notePhotoDir, it).delete() }
    n.remindId?.let { runCatching { Reminders.remove(this, it) } }
    notes.save(all.filter { it.id != id })
}

internal fun MainActivity.editNote(existing: Note?, onSaved: () -> Unit = { notesRefresh?.invoke() }) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    var saved = false
    val id = existing?.id ?: UUID.randomUUID().toString()
    var pinned = existing?.pinned ?: false
    var color = existing?.color
    var label = existing?.label
    var photo = existing?.photo
    var remindAt = existing?.remindAt
    var remindId = existing?.remindId
    openPage(if (existing == null) "Nuova nota" else "Nota", "Salva", { dlg -> dlg.dismiss() }) { root, body, dialog ->
        val et = EditText(this).apply {
            setText(existing?.text.orEmpty())
            textSize = 17f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            hint = "Scrivi o detta la tua nota…"
            gravity = Gravity.TOP
            minLines = 8
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val paper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        }
        fun paint() {
            val probe = Note(id, "", 0, color = color)
            paper.background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(noteBg(probe)); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
        }
        val info = TextView(this).apply { textSize = 13f; setTextColor(palette.textMuted); setPadding(dp(12), dp(6), dp(12), 0) }
        fun refreshInfo() {
            info.text = listOfNotNull(
                if (pinned) "📌 Fissata" else null, label,
                remindAt?.takeIf { it > System.currentTimeMillis() }?.let { "⏰ " + java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(java.util.Date(it)) }
            ).joinToString(" · ")
            info.visibility = if (info.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val photoView = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 12f * d }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) }
        }
        fun showPhoto() {
            val f = photo?.let { File(notePhotoDir, it) }
            if (f == null || !f.exists()) { photoView.visibility = View.GONE; return }
            photoView.visibility = View.VISIBLE
            io.execute {
                val bmp = runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
                runOnUiThread { photoView.setImageBitmap(bmp) }
            }
        }
        photoView.setOnClickListener {
            val f = photo?.let { File(notePhotoDir, it) } ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this).setItems(arrayOf("🔍 Vedi grande", "🗑 Togli la foto")) { _, w ->
                if (w == 0) runCatching {
                    val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { toast("Non riesco ad aprire la foto") }
                else { f.delete(); photo = null; showPhoto() }
            }.show()
        }
        paper.addView(info); paper.addView(et); paper.addView(photoView)
        paint(); refreshInfo(); showPhoto()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(8), 0, dp(4)) }
        fun action(labelText: String, onClick: () -> Unit) = actions.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labelText; isAllCaps = false; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        })
        action("🎤 Detta") {
            noteDictationTarget = et
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            }
            try { noteSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
        }
        action("📌 Fissa") { pinned = !pinned; refreshInfo(); toast(if (pinned) "Fissata in alto" else "Tolta dalle fissate") }
        action("⏰ Ricordamelo") {
            val now = Calendar.getInstance()
            val active = remindAt?.takeIf { it > System.currentTimeMillis() }
            fun pick() {
                android.app.DatePickerDialog(this, { _, y, m, dd ->
                    android.app.TimePickerDialog(this, { _, h, mi ->
                        val at = Calendar.getInstance().apply { set(y, m, dd, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        if (at <= System.currentTimeMillis()) { toast("Scegli un momento nel futuro"); return@TimePickerDialog }
                        remindId?.let { runCatching { Reminders.remove(this, it) } }
                        ensureNotificationPermission()
                        val title = et.text?.toString()?.trim()?.lines()?.firstOrNull { it.isNotBlank() }?.take(60) ?: "Nota"
                        remindId = Reminders.add(this, "📝 $title", at).id
                        remindAt = at; refreshInfo(); toast("⏰ Te lo ricordo")
                    }, 9, 0, true).show()
                }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
            }
            if (active == null) pick()
            else MaterialAlertDialogBuilder(this).setTitle("Promemoria").setItems(arrayOf("Cambia data e ora", "Togli il promemoria")) { _, w ->
                if (w == 0) pick() else { remindId?.let { runCatching { Reminders.remove(this, it) } }; remindId = null; remindAt = null; refreshInfo() }
            }.show()
        }
        action("📷 Foto") {
            val f = File(notePhotoDir, "${id}_${System.currentTimeMillis()}.jpg")
            capturePhoto(f) { shot ->
                if (shot == null) return@capturePhoto
                io.execute {
                    shrinkPhoto(shot)
                    runOnUiThread { photo?.let { old -> if (old != shot.name) File(notePhotoDir, old).delete() }; photo = shot.name; showPhoto() }
                }
            }
        }
        action("🏷 Etichetta") {
            val items = (NoteTools.LABELS + "Nessuna").toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Etichetta").setItems(items) { _, w -> label = NoteTools.LABELS.getOrNull(w); refreshInfo() }.show()
        }
        action("🎨 Colore") {
            val items = (NoteTools.COLOR_NAMES + "Nessuno").toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Colore").setItems(items) { _, w -> color = if (w < NoteTools.COLORS.size) w else null; paint() }.show()
        }
        action("✨ AI") {
            val text = et.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || !aiReady()) { if (text.isEmpty()) toast("Scrivi prima qualcosa nella nota"); return@action }
            val opts = arrayOf("✏️ Correggi e riordina", "📝 Riassumi", "☑ Fanne una lista con spunte", "🛒 Metti nella spesa le cose da comprare", "❓ Fai una domanda")
            MaterialAlertDialogBuilder(this).setTitle("✨ Chiedi all'AI su questa nota").setItems(opts) { _, w ->
                fun run(prompt: String, done: (String) -> Unit) {
                    toast("✨ Ci penso…")
                    ai.ask(listOf("user" to "$prompt\n\n$text"), null, object : AiBridge.Listener {
                        override fun onDone(text: String, model: String) = done(text.trim())
                        override fun onError(message: String, noKey: Boolean) { toast(if (noKey) "Configura l'AI in Impostazioni → Assistente AI" else message) }
                    })
                }
                when (w) {
                    0 -> run("Riordina questa nota in italiano: correggi gli errori, dai una struttura chiara (titolo breve sulla prima riga, poi elenchi puntati con «- » dove servono). Non aggiungere informazioni. Rispondi solo con la nota.") { et.setText(it); et.setSelection(et.text.length) }
                    1 -> showAiSheet("Riassunto della nota", "Nota:\n$text\n\n", "Riassumi questa nota in italiano in poche righe.", showFirstQuestion = false)
                    2 -> run("Trasforma questa nota in una lista di cose da fare, una per riga, senza numeri né simboli. Prima riga: un titolo breve. Rispondi solo con la lista.") { out ->
                        val ls = out.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.isNotBlank() }
                        if (ls.size < 2) { toast("Non ho trovato una lista"); return@run }
                        val list = notes.load()
                        list.add(Note(UUID.randomUUID().toString(), ls.first() + "\n" + ls.drop(1).joinToString("\n") { "☐ $it" }, System.currentTimeMillis(), label = label))
                        notes.save(list); notesRefresh?.invoke(); toast("☑ Creata la lista «${ls.first()}»")
                    }
                    3 -> run("Dalla nota qui sotto elenca solo le cose da comprare (ingredienti, prodotti), separate da virgola, in italiano. Se non ce ne sono rispondi NIENTE.") { out ->
                        if (out.isBlank() || out.uppercase().startsWith("NIENTE")) toast("Non ho trovato cose da comprare") else addToShoppingList(out, "Lista della spesa")
                    }
                    else -> { dialog.dismiss(); showAskPage(prefill = null); toast("Scrivi la domanda: la nota non viene allegata, incollala se serve") }
                }
            }.show()
        }
        action("📤 Condividi") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, et.text?.toString().orEmpty())
            }, "Condividi nota"))
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        body.addView(paper)
        dialog.setOnDismissListener {
            if (saved) return@setOnDismissListener
            saved = true
            val text = et.text?.toString()?.trim().orEmpty()
            val list = notes.load()
            val n = list.firstOrNull { it.id == id }
            val empty = text.isEmpty() && photo == null
            if (n == null) {
                if (!empty) list.add(Note(id, text, System.currentTimeMillis(), pinned, color, label, photo, remindAt, remindId))
            } else if (empty) {
                list.remove(n); remindId?.let { runCatching { Reminders.remove(this, it) } }
            } else {
                if (n.text != text) n.time = System.currentTimeMillis()
                n.text = text; n.pinned = pinned; n.color = color; n.label = label; n.photo = photo; n.remindAt = remindAt; n.remindId = remindId
            }
            notes.save(list)
            onSaved()
        }
        if (existing == null) et.requestFocus()
    }
}
