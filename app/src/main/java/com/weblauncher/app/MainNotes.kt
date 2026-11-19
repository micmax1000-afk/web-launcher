package com.weblauncher.app

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
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

// ======================= Note: griglia, spunte, foto, voce, archivio, cestino, blocco, AI =======================

internal val MainActivity.notePhotoDir: File get() = File(filesDir, "note_photos").apply { mkdirs() }
internal val MainActivity.noteAudioDir: File get() = File(filesDir, "note_audio").apply { mkdirs() }
private val MainActivity.notePrefs get() = getSharedPreferences("wl_notes", android.content.Context.MODE_PRIVATE)

private fun MainActivity.noteBg(color: Int?): Int {
    val c = color?.let { NoteTools.COLORS.getOrNull(it) } ?: return AppRepository.palette(this).card
    return if (AppRepository.isLightUi(this)) c.first else c.second
}

/** Elimina per sempre le note rimaste nel cestino più di 7 giorni (con foto e audio). */
internal fun MainActivity.purgeNoteTrash() {
    val all = notes.load(); val now = System.currentTimeMillis()
    val old = all.filter { NoteTools.expired(it, now) }
    if (old.isEmpty()) return
    old.forEach { removeNoteFiles(it) }
    notes.save(all.filter { !NoteTools.expired(it, now) })
}

private fun MainActivity.removeNoteFiles(n: Note) {
    NoteTools.photosOf(n).forEach { File(notePhotoDir, it).delete() }
    n.audio?.let { File(noteAudioDir, it).delete() }
    n.remindId?.let { runCatching { Reminders.remove(this, it) } }
}

/** Sposta la nota nel cestino (si recupera per 7 giorni). */
internal fun MainActivity.deleteNote(id: String) {
    val all = notes.load()
    val n = all.firstOrNull { it.id == id } ?: return
    n.remindId?.let { runCatching { Reminders.remove(this, it) } }
    n.remindId = null; n.remindAt = null; n.deletedAt = System.currentTimeMillis(); n.pinned = false
    notes.save(all)
}

private fun MainActivity.purgeNote(id: String) {
    val all = notes.load()
    all.firstOrNull { it.id == id }?.let { removeNoteFiles(it) }
    notes.save(all.filter { it.id != id })
}

private fun MainActivity.updateNote(id: String, change: (Note) -> Unit) {
    val all = notes.load(); all.firstOrNull { it.id == id }?.let(change); notes.save(all)
}

/** Apre una nota: chiede l'impronta se è bloccata; le liste semplici si aprono come lista. */
internal fun MainActivity.openNote(n: Note, onSaved: () -> Unit = { notesRefresh?.invoke() }) {
    fun go() { if (NoteTools.isPureList(n)) showChecklist(n.id) else editNote(n, onSaved) }
    if (n.locked) authenticate("Nota protetta", strict = true) { go() } else go()
}

internal fun MainActivity.showNotesPage() {
    var query = ""
    var label: String? = null
    var tab = 0                                   // 0 note, 1 archivio, 2 cestino
    purgeNoteTrash()
    openPage("Note", "Nuova", { _ ->
        MaterialAlertDialogBuilder(this).setItems(arrayOf("📝 Nota", "☑ Lista con spunte", "🛒 Lista della spesa")) { _, w ->
            when (w) { 0 -> editNote(null); 1 -> showChecklist(null, "Lista"); else -> showChecklist(null, "Lista della spesa") }
        }.show()
    }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun chipView(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 13.5f; gravity = Gravity.CENTER
            setTextColor(if (on) accent else palette.text); if (on) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = 18f * d; setColor(palette.card); setStroke(dp(if (on) 2 else 1).coerceAtLeast(1), if (on) accent else palette.stroke) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, 0, dp(8)) }
        val search = EditText(this).apply {
            hint = "🔍 Cerca nelle note…"; textSize = 15f; setTextColor(palette.text); setHintTextColor(palette.textMuted)
            maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = 22f * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, dp(4), dp(8)) }
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, 0, dp(6)) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, 0, dp(4)) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tabs); body.addView(search)
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) })
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        body.addView(list)
        lateinit var render: () -> Unit

        fun drawBars() {
            tabs.removeAllViews()
            val all = notes.load()
            listOf("📝 Note", "🗄 Archivio · ${all.count { it.archived && !NoteTools.isTrashed(it) }}", "🗑 Cestino · ${all.count { NoteTools.isTrashed(it) }}")
                .forEachIndexed { i, t -> tabs.addView(chipView(t, tab == i) { tab = i; drawBars(); render() }) }
            tools.removeAllViews(); chips.removeAllViews()
            if (tab != 0) return
            val grid = notePrefs.getString("view", "grid") == "grid"
            // due tasti chiari: quello acceso è la vista che vedi
            tools.addView(chipView("▦ Griglia", grid) { notePrefs.edit().putString("view", "grid").apply(); drawBars(); render() })
            tools.addView(chipView("☰ Elenco", !grid) { notePrefs.edit().putString("view", "list").apply(); drawBars(); render() })
            val sort = notePrefs.getString("sort", "time") ?: "time"
            tools.addView(chipView("↕ " + (NoteTools.SORTS.firstOrNull { it.first == sort }?.second ?: "Ultima modifica"), false) {
                MaterialAlertDialogBuilder(this).setTitle("Ordina per").setItems(NoteTools.SORTS.map { it.second }.toTypedArray()) { _, w ->
                    notePrefs.edit().putString("sort", NoteTools.SORTS[w].first).apply(); drawBars(); render()
                }.show()
            })
            (listOf<String?>(null) + NoteTools.LABELS).forEach { l -> chips.addView(chipView(l ?: "Tutte", l == label) { label = l; drawBars(); render() }) }
        }

        fun menu(n: Note) {
            val now = System.currentTimeMillis()
            when {
                NoteTools.isTrashed(n) -> MaterialAlertDialogBuilder(this).setTitle(NoteTools.title(n))
                    .setItems(arrayOf("↩ Recupera", "✕ Elimina per sempre")) { _, w ->
                        if (w == 0) { updateNote(n.id) { it.deletedAt = null }; toast("Recuperata") }
                        else confirmClear("Eliminarla per sempre? Non si può annullare.") { purgeNote(n.id); drawBars(); render() }
                        drawBars(); render()
                    }.show()
                n.archived -> MaterialAlertDialogBuilder(this).setTitle(NoteTools.title(n))
                    .setItems(arrayOf("↩ Rimetti tra le note", "🗑 Sposta nel cestino")) { _, w ->
                        if (w == 0) updateNote(n.id) { it.archived = false } else deleteNote(n.id)
                        drawBars(); render()
                    }.show()
                else -> MaterialAlertDialogBuilder(this).setTitle(NoteTools.title(n))
                    .setItems(arrayOf(if (n.pinned) "📌 Togli dalle fissate" else "📌 Fissa in alto", "🗄 Archivia",
                        if (n.locked) "🔓 Sblocca" else "🔒 Blocca con l'impronta", "📤 Condividi", "🗑 Sposta nel cestino")) { _, w ->
                        when (w) {
                            0 -> updateNote(n.id) { it.pinned = !n.pinned }
                            1 -> { updateNote(n.id) { it.archived = true; it.pinned = false }; toast("Archiviata: la trovi in «Archivio»") }
                            2 -> authenticate(if (n.locked) "Sblocca la nota" else "Blocca la nota", strict = true) { updateNote(n.id) { it.locked = !n.locked }; drawBars(); render() }
                            3 -> {
                                if (n.locked) toast("Sbloccala prima di condividerla")
                                else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, n.text) }, "Condividi nota"))
                            }
                            else -> { deleteNote(n.id); toast("Nel cestino: la recuperi per ${NoteTools.TRASH_DAYS} giorni") }
                        }
                        drawBars(); render()
                    }.show()
            }
            if (now < 0) return
        }

        fun preview(n: Note, box: LinearLayout, grid: Boolean) {
            val photos = NoteTools.photosOf(n)
            if (photos.isNotEmpty() && !n.locked) {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = 10f * d; setColor(palette.stroke) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (grid) 90 else 110)).apply { bottomMargin = dp(6) }
                }
                box.addView(iv)
                val f = File(notePhotoDir, photos.first())
                io.execute { val b = runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull(); runOnUiThread { iv.setImageBitmap(b) } }
            }
            val title = NoteTools.title(n) + (if (photos.size > 1) " · 📷${photos.size}" else "") + (if ((n.remindAt ?: 0L) > System.currentTimeMillis()) " ⏰" else "")
            box.addView(TextView(this).apply { text = (if (n.locked) "🔒 " else "") + title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(palette.text); maxLines = 2 })
            if (n.locked) { box.addView(TextView(this).apply { text = "protetta · si apre con l'impronta"; textSize = 12.5f; setTextColor(palette.textMuted) }); return }
            val (prose, items) = NoteTools.split(n.text)
            if (items.isNotEmpty()) box.addView(TextView(this).apply {
                text = "☑ ${items.count { it.first }}/${items.size}"; textSize = 12f; setTextColor(accent); setPadding(0, dp(2), 0, 0)
            })
            val rest = prose.lines().filter { it.isNotBlank() }.drop(if (prose.isNotBlank()) 1 else 0)
            val lines = rest.take(if (grid) 4 else 2).toMutableList()
            items.take(if (grid) 4 else 3).forEach { lines += (if (it.first) "☑ " else "☐ ") + it.second }
            if (lines.isNotEmpty()) box.addView(TextView(this).apply {
                text = lines.joinToString("\n"); textSize = 13f; setTextColor(palette.textMuted); maxLines = if (grid) 6 else 3; setPadding(0, dp(3), 0, 0)
            })
            if (n.audio != null) box.addView(TextView(this).apply {
                text = "🎙 Nota vocale" + (n.audioMs?.let { " · " + NoteTools.clock(it) } ?: ""); textSize = 13f; setTextColor(accent); setPadding(0, dp(4), 0, 0)
            })
            val now = System.currentTimeMillis()
            val sub = listOfNotNull(n.label, if (NoteTools.isTrashed(n)) "ancora ${NoteTools.daysLeft(n, now)} giorni" else NewsAdapter.ago(n.time)).joinToString(" · ")
            box.addView(TextView(this).apply { text = sub; textSize = 11.5f; setTextColor(palette.textMuted); setPadding(0, dp(4), 0, 0) })
        }

        fun card(n: Note, grid: Boolean): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(noteBg(n.color)); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
            alpha = if (NoteTools.isTrashed(n)) 0.75f else 1f
            preview(n, this, grid)
            setOnClickListener {
                if (NoteTools.isTrashed(n)) { menu(n); return@setOnClickListener }
                openNote(n) { drawBars(); render() }
            }
            setOnLongClickListener { menu(n); true }
        }

        fun section(title: String?, items: List<Note>, grid: Boolean) {
            if (items.isEmpty()) return
            if (title != null) sectionLabel(list, title)
            if (!grid) items.forEach { n ->
                list.addView(card(n, false).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) } })
            } else items.chunked(2).forEach { pair ->
                // la riga deve essere larga quanto la pagina: le due note se la dividono a metà
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                pair.forEach { n -> row.addView(card(n, true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) } }) }
                if (pair.size == 1) row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } })
                list.addView(row)
            }
        }

        render = {
            list.removeAllViews()
            val all = notes.load()
            val sort = notePrefs.getString("sort", "time") ?: "time"
            val grid = tab == 0 && notePrefs.getString("view", "grid") == "grid"
            search.visibility = if (tab == 2) View.GONE else View.VISIBLE
            when (tab) {
                0 -> {
                    val live = all.filter { !it.archived && !NoteTools.isTrashed(it) }
                    val shown = NoteTools.sorted(live.filter { NoteTools.matches(it, query, label) }, sort)
                    if (live.isEmpty()) emptyLabel(list, "Nessuna nota.\nTocca «Nuova» in alto: puoi anche dettarla.")
                    else if (shown.isEmpty()) hintLabel(list, "Nessuna nota trovata.")
                    else {
                        val pinned = shown.filter { it.pinned }
                        section(if (pinned.isNotEmpty()) "📌 FISSATE" else null, pinned, grid)
                        section(if (pinned.isNotEmpty()) "ALTRE" else null, shown.filter { !it.pinned }, grid)
                        hintLabel(list, "Tieni premuta una nota per fissarla, archiviarla, bloccarla o spostarla nel cestino.")
                    }
                }
                1 -> {
                    val arch = NoteTools.sorted(all.filter { it.archived && !NoteTools.isTrashed(it) && NoteTools.matches(it, query, null) }, sort)
                    if (arch.isEmpty()) emptyLabel(list, "Archivio vuoto.\nArchivia una nota per toglierla dall'elenco senza cancellarla.")
                    else { hintLabel(list, "Tocca per aprire, tieni premuto per rimetterla tra le note."); section(null, arch, false) }
                }
                else -> {
                    val trash = all.filter { NoteTools.isTrashed(it) }.sortedByDescending { it.deletedAt }
                    if (trash.isEmpty()) emptyLabel(list, "Cestino vuoto.\nLe note eliminate restano qui ${NoteTools.TRASH_DAYS} giorni.")
                    else {
                        hintLabel(list, "Si svuota da solo: ogni nota resta ${NoteTools.TRASH_DAYS} giorni. Tocca una nota per recuperarla.")
                        listRow(list, "🗑 Svuota il cestino adesso", "${trash.size} note", onClick = {
                            confirmClear("Eliminare per sempre ${trash.size} note?") { trash.forEach { purgeNote(it.id) }; drawBars(); render() }
                        })
                        section(null, trash, false)
                    }
                }
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty(); render() }
        })
        drawBars(); render()
        notesRefresh = { drawBars(); render() }
        dialog.setOnDismissListener { notesRefresh = null }
    }
}

internal fun MainActivity.editNote(existing: Note?, onSaved: () -> Unit = { notesRefresh?.invoke() }) {
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    var saved = false
    val id = existing?.id ?: UUID.randomUUID().toString()
    var pinned = existing?.pinned ?: false
    var color = existing?.color
    var label = existing?.label
    var remindAt = existing?.remindAt
    var remindId = existing?.remindId
    var locked = existing?.locked ?: false
    var audio = existing?.audio
    var audioMs = existing?.audioMs
    val photos = (existing?.let { NoteTools.photosOf(it) } ?: emptyList()).toMutableList()
    val (prose0, items0) = NoteTools.split(existing?.text.orEmpty())
    val items = items0.toMutableList()
    var recorder: android.media.MediaRecorder? = null
    var recStart = 0L
    var player: android.media.MediaPlayer? = null
    val h = android.os.Handler(android.os.Looper.getMainLooper())

    openPage(if (existing == null) "Nuova nota" else "Nota", "Salva", { dlg -> dlg.dismiss() }) { _, body, dialog ->
        val et = EditText(this).apply {
            setText(prose0)
            textSize = 17f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            hint = "Scrivi o detta la tua nota…"
            gravity = Gravity.TOP
            minLines = 4
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val paper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        }
        fun paint() { paper.background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(noteBg(color)); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) } }
        val info = TextView(this).apply { textSize = 13f; setTextColor(palette.textMuted); setPadding(dp(12), dp(6), dp(12), 0) }
        fun refreshInfo() {
            info.text = listOfNotNull(
                if (pinned) "📌 Fissata" else null, if (locked) "🔒 Protetta" else null, label,
                remindAt?.takeIf { it > System.currentTimeMillis() }?.let { "⏰ " + java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(java.util.Date(it)) }
            ).joinToString(" · ")
            info.visibility = if (info.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        // ---- spunte dentro la nota ----
        val checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        var showChecks = items.isNotEmpty()
        lateinit var drawChecks: () -> Unit
        drawChecks = {
            checks.removeAllViews()
            checks.visibility = if (showChecks) View.VISIBLE else View.GONE
            items.forEachIndexed { i, (done, txt) ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(CheckBox(this).apply {
                    isChecked = done; text = txt; textSize = 16f
                    setTextColor(if (done) palette.textMuted else palette.text)
                    paintFlags = if (done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, c -> items[i] = c to items[i].second; checks.post { drawChecks() } }
                })
                r.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_web_close); setColorFilter(palette.textMuted)
                    setPadding(dp(12), dp(12), dp(12), dp(12)); layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setOnClickListener { items.removeAt(i); drawChecks() }
                })
                checks.addView(r)
            }
            val add = EditText(this).apply {
                hint = "＋ aggiungi voce"; textSize = 15f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(palette.text); setHintTextColor(accent); background = null; setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            add.setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_DONE) {
                    val t = add.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty()) { splitItems(t).forEach { items.add(false to it) }; drawChecks(); checks.post { (checks.getChildAt(checks.childCount - 1) as? EditText)?.requestFocus() } }
                    true
                } else false
            }
            checks.addView(add)
        }

        // ---- foto ----
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(4), dp(10), 0) }
        val stripScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
        lateinit var addPhoto: () -> Unit
        lateinit var drawPhotos: () -> Unit
        drawPhotos = {
            strip.removeAllViews()
            stripScroll.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
            photos.toList().forEach { name ->
                val f = File(notePhotoDir, name)
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = 12f * d; setColor(palette.stroke) }
                    layoutParams = LinearLayout.LayoutParams(dp(110), dp(110)).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@editNote).setItems(arrayOf("🔍 Vedi grande", "🗑 Togli la foto")) { _, w ->
                            if (w == 0) runCatching {
                                val u = androidx.core.content.FileProvider.getUriForFile(this@editNote, "$packageName.files", f)
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }.onFailure { toast("Non riesco ad aprire la foto") }
                            else { f.delete(); photos.remove(name); drawPhotos() }
                        }.show()
                    }
                }
                strip.addView(iv)
                io.execute { val b = runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull(); runOnUiThread { iv.setImageBitmap(b) } }
            }
            strip.addView(TextView(this).apply {
                text = "＋"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(palette.textMuted)
                background = GradientDrawable().apply { cornerRadius = 12f * d; setStroke(dp(2), palette.stroke) }
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(110))
                setOnClickListener { addPhoto() }
            })
        }
        addPhoto = {
            val f = File(notePhotoDir, "${id}_${System.currentTimeMillis()}.jpg")
            capturePhoto(f) { shot ->
                if (shot == null) return@capturePhoto
                io.execute { shrinkPhoto(shot); runOnUiThread { photos += shot.name; drawPhotos() } }
            }
        }

        // ---- nota vocale ----
        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = 14f * d; setColor(palette.iconTile) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10), dp(8), dp(10), 0) }
        }
        lateinit var drawAudio: () -> Unit
        drawAudio = {
            audioRow.removeAllViews()
            val a = audio
            audioRow.visibility = if (a == null) View.GONE else View.VISIBLE
            if (a != null) {
                val f = File(noteAudioDir, a)
                audioRow.addView(TextView(this).apply {
                    text = (if (player?.isPlaying == true) "⏸ " else "▶ ") + "Nota vocale" + (audioMs?.let { " · " + NoteTools.clock(it) } ?: "")
                    textSize = 15f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val p = player
                        if (p != null && p.isPlaying) { p.pause(); drawAudio() }
                        else runCatching {
                            if (p == null) player = android.media.MediaPlayer().apply { setDataSource(f.path); prepare(); setOnCompletionListener { drawAudio() } }
                            player?.start(); drawAudio()
                        }.onFailure { toast("Non riesco a riprodurla") }
                    }
                })
                audioRow.addView(TextView(this).apply {
                    text = "✍️ Testo"; textSize = 13.5f; setTextColor(accent); setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        if (!aiReady()) return@setOnClickListener
                        val copy = File(scanDir, "voce_${System.currentTimeMillis()}.aac")
                        runCatching { f.copyTo(copy, true) }.onFailure { toast("Audio non trovato"); return@setOnClickListener }
                        toast("✨ Scrivo il testo…")
                        ai.ask(listOf("user" to "Trascrivi in italiano esattamente quello che viene detto nell'audio allegato, con la punteggiatura. Rispondi solo con il testo."),
                            null, object : AiBridge.Listener {
                                override fun onDone(text: String, model: String) {
                                    val t = text.trim(); if (t.isEmpty()) { toast("Non ho capito l'audio"); return }
                                    val cur = et.text?.toString().orEmpty().trimEnd()
                                    et.setText(if (cur.isEmpty()) t else "$cur\n$t"); et.setSelection(et.text.length)
                                }
                                override fun onError(message: String, noKey: Boolean) {
                                    toast(if (noKey) "Configura l'AI in Impostazioni → Assistente AI" else "Per scrivere il testo serve un modello che ascolta l'audio, come Gemini")
                                }
                            }, listOf(Triple("pdf", "audio/aac", "/files/scan/" + copy.name)))
                    }
                })
                audioRow.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_web_close); setColorFilter(palette.textMuted)
                    setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    setOnClickListener {
                        confirmClear("Togliere la nota vocale?") { player?.release(); player = null; f.delete(); audio = null; audioMs = null; drawAudio() }
                    }
                })
            }
        }

        // ---- i tasti ----
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(8), 0, dp(4)) }
        fun action(labelText: String, onClick: (com.google.android.material.button.MaterialButton) -> Unit) = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = labelText; isAllCaps = false; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { onClick(this) }
            actions.addView(this)
        }
        action("🎤 Detta") {
            noteDictationTarget = et
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            }
            try { noteSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
        }
        action("☑ Spunte") { showChecks = true; drawChecks(); checks.post { (checks.getChildAt(checks.childCount - 1) as? EditText)?.requestFocus() } }
        action("📷 Foto") { addPhoto() }
        fun stopRec(b: com.google.android.material.button.MaterialButton?) {
            val r = recorder ?: return
            recorder = null; h.removeCallbacksAndMessages(null)
            runCatching { r.stop() }; r.release()
            audioMs = System.currentTimeMillis() - recStart
            b?.text = "🎙 Voce"; drawAudio()
        }
        action("🎙 Voce") { b ->
            if (recorder != null) { stopRec(b); return@action }
            fun start() {
                player?.release(); player = null
                audio?.let { File(noteAudioDir, it).delete() }
                val name = "${id}_${System.currentTimeMillis()}.aac"
                val ok = runCatching {
                    val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
                    r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    r.setOutputFormat(android.media.MediaRecorder.OutputFormat.AAC_ADTS)
                    r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    r.setAudioChannels(1); r.setAudioSamplingRate(22050); r.setAudioEncodingBitRate(40000)
                    r.setOutputFile(File(noteAudioDir, name).absolutePath)
                    r.prepare(); r.start(); recorder = r
                }.isSuccess
                if (!ok) { toast("Non riesco a registrare"); return }
                audio = name; recStart = System.currentTimeMillis(); audioRow.visibility = View.GONE
                val tick = object : Runnable { override fun run() { if (recorder == null) return; b.text = "⏹ Ferma " + NoteTools.clock(System.currentTimeMillis() - recStart); h.postDelayed(this, 500) } }
                tick.run()
            }
            withPermissions(Manifest.permission.RECORD_AUDIO) { start() }
        }
        action("⏰ Ricordamelo") {
            val now = Calendar.getInstance()
            val active = remindAt?.takeIf { it > System.currentTimeMillis() }
            fun pick() {
                android.app.DatePickerDialog(this, { _, y, m, dd ->
                    android.app.TimePickerDialog(this, { _, hh, mi ->
                        val at = Calendar.getInstance().apply { set(y, m, dd, hh, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        if (at <= System.currentTimeMillis()) { toast("Scegli un momento nel futuro"); return@TimePickerDialog }
                        remindId?.let { runCatching { Reminders.remove(this, it) } }
                        ensureNotificationPermission()
                        val title = et.text?.toString()?.trim()?.lines()?.firstOrNull { it.isNotBlank() }?.take(60) ?: items.firstOrNull()?.second ?: "Nota"
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
        action("📌 Fissa") { pinned = !pinned; refreshInfo(); toast(if (pinned) "Fissata in alto" else "Tolta dalle fissate") }
        action("🔒 Blocca") {
            authenticate(if (locked) "Sblocca la nota" else "Blocca la nota", strict = true) {
                locked = !locked; refreshInfo(); toast(if (locked) "🔒 Si aprirà con l'impronta" else "🔓 Sbloccata")
            }
        }
        action("🏷 Etichetta") {
            val labels = (NoteTools.LABELS + "Nessuna").toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Etichetta").setItems(labels) { _, w -> label = NoteTools.LABELS.getOrNull(w); refreshInfo() }.show()
        }
        action("🎨 Colore") {
            val names = (NoteTools.COLOR_NAMES + "Nessuno").toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Colore").setItems(names) { _, w -> color = if (w < NoteTools.COLORS.size) w else null; paint() }.show()
        }
        action("✨ AI") {
            val text = NoteTools.join(et.text?.toString().orEmpty(), items)
            if (text.isEmpty() || !aiReady()) { if (text.isEmpty()) toast("Scrivi prima qualcosa nella nota"); return@action }
            val opts = arrayOf("✏️ Correggi e riordina", "📝 Riassumi", "☑ Trasforma in spunte", "🛒 Metti nella spesa le cose da comprare")
            MaterialAlertDialogBuilder(this).setTitle("✨ Chiedi all'AI su questa nota").setItems(opts) { _, w ->
                fun askNote(prompt: String, done: (String) -> Unit) {
                    toast("✨ Ci penso…")
                    ai.ask(listOf("user" to "$prompt\n\n$text"), null, object : AiBridge.Listener {
                        override fun onDone(text: String, model: String) = done(text.trim())
                        override fun onError(message: String, noKey: Boolean) { toast(if (noKey) "Configura l'AI in Impostazioni → Assistente AI" else message) }
                    })
                }
                when (w) {
                    0 -> askNote("Riordina questa nota in italiano: correggi gli errori, dai una struttura chiara (titolo breve sulla prima riga, poi elenchi puntati con «- » dove servono). Non aggiungere informazioni. Rispondi solo con la nota.") {
                        val (p, its) = NoteTools.split(it); et.setText(p); et.setSelection(et.text.length); if (its.isNotEmpty()) { items.clear(); items.addAll(its); showChecks = true; drawChecks() }
                    }
                    1 -> showAiSheet("Riassunto della nota", "Nota:\n$text\n\n", "Riassumi questa nota in italiano in poche righe.", showFirstQuestion = false)
                    2 -> askNote("Dalla nota qui sotto ricava le cose da fare, una per riga, senza numeri né simboli. Rispondi solo con l'elenco.") { out ->
                        val ls = out.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.isNotBlank() }
                        if (ls.isEmpty()) { toast("Non ho trovato cose da fare"); return@askNote }
                        ls.forEach { items.add(false to it) }; showChecks = true; drawChecks(); toast("☑ Aggiunte ${ls.size} spunte")
                    }
                    else -> askNote("Dalla nota qui sotto elenca solo le cose da comprare (ingredienti, prodotti), separate da virgola, in italiano. Se non ce ne sono rispondi NIENTE.") { out ->
                        if (out.isBlank() || out.uppercase().startsWith("NIENTE")) toast("Non ho trovato cose da comprare") else addToShoppingList(out, "Lista della spesa")
                    }
                }
            }.show()
        }
        action("📤 Condividi") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, NoteTools.join(et.text?.toString().orEmpty(), items))
            }, "Condividi nota"))
        }

        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        paper.addView(info); paper.addView(et); paper.addView(checks); paper.addView(audioRow); paper.addView(stripScroll)
        body.addView(paper)
        paint(); refreshInfo(); drawChecks(); drawPhotos(); drawAudio()

        dialog.setOnDismissListener {
            if (saved) return@setOnDismissListener
            saved = true
            if (recorder != null) stopRec(null)
            player?.release(); player = null
            val text = NoteTools.join(et.text?.toString().orEmpty(), items)
            val list = notes.load()
            val n = list.firstOrNull { it.id == id }
            val empty = text.isEmpty() && photos.isEmpty() && audio == null
            if (n == null) {
                if (!empty) list.add(Note(id, text, System.currentTimeMillis(), pinned, color, label, null, remindAt, remindId,
                    photos.toList(), false, null, audio, audioMs, locked))
            } else if (empty) {
                list.remove(n); remindId?.let { runCatching { Reminders.remove(this, it) } }
            } else {
                if (n.text != text) n.time = System.currentTimeMillis()
                n.text = text; n.pinned = pinned; n.color = color; n.label = label; n.photo = null; n.photos = photos.toList()
                n.remindAt = remindAt; n.remindId = remindId; n.audio = audio; n.audioMs = audioMs; n.locked = locked
            }
            notes.save(list)
            onSaved()
        }
        if (existing == null) et.requestFocus()
    }
}
