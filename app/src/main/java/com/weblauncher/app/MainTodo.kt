package com.weblauncher.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ======================= «Da fare» =======================

internal fun MainActivity.showTodoPage() {
    openPage("Da fare", "Aggiungi", { _ -> showQuickAdd() }) { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val now = System.currentTimeMillis(); val start = Todo.startOfDay(now)
            val items = Todo.items(this)
            if (items.isEmpty()) { emptyLabel(body, "Niente da fare nei prossimi giorni 🙂\nTocca «Aggiungi» per un promemoria, una scadenza o una nota."); return }
            val late = items.filter { it.time < now && !it.allDay && it.kind in listOf("rem", "med") }
            fun section(title: String, list: List<Todo.Item>) { if (list.isEmpty()) return; sectionLabel(body, title); list.forEach { row(body, it, dialog) { render() } } }
            section("⏳ IN RITARDO", late)
            val rest = items - late.toSet()
            section("OGGI", rest.filter { it.time in start until start + 86_400_000L })
            section("DOMANI", rest.filter { it.time in start + 86_400_000L until start + 2 * 86_400_000L })
            section("PROSSIMI GIORNI", rest.filter { it.time >= start + 2 * 86_400_000L })
            section("IERI, ANCORA APERTO", rest.filter { it.time < start })
            hintLabel(body, "Tocca una voce per segnarla come fatta, rimandarla o aprirla.")
        }
        render()
    }
}

private fun MainActivity.row(body: LinearLayout, i: Todo.Item, page: androidx.appcompat.app.AppCompatDialog, refresh: () -> Unit) {
    val fixed = i.icon + " " + i.text
    val sub = Todo.line(i).removePrefix(i.icon + " ").removeSuffix(i.text).trim().ifBlank { if (i.allDay) "Tutto il giorno" else null }
    listRow(body, fixed, sub, onClick = {
        when (i.kind) {
            "rem" -> MaterialAlertDialogBuilder(this).setTitle(i.text).setItems(arrayOf("✅ Fatto", "⏰ Rimanda di 1 ora", "📋 Apri i promemoria")) { _, w ->
                when (w) {
                    0 -> { Todo.doneReminder(this, i.id.toInt()); refresh() }
                    1 -> { Reminders.load(this).firstOrNull { it.id == i.id.toInt() }?.let { r -> Reminders.remove(this, r.id); Reminders.add(this, r.text, System.currentTimeMillis() + 3_600_000L, r.repeat) }; refresh() }
                    else -> showRemindersPage()
                }
            }.show()
            "med" -> MaterialAlertDialogBuilder(this).setTitle(i.text).setItems(arrayOf("✅ Ho preso la dose", "💊 Apri i farmaci")) { _, w ->
                if (w == 0) { Meds.markTaken(this, i.id); toast("Segnato"); refresh() } else showMedsPage()
            }.show()
            "dead" -> showDeadlinesPage(i.extra.ifBlank { Deadlines.DEADLINE })
            "visit" -> showVisitsPage()
            "bday" -> showBirthdaysPage()
            "waste" -> showWastePage()
            "park" -> showPlacesPage()
            "cal" -> runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, i.id.toLong()))) }
        }
    })
}

// ======================= «+» quick add =======================

/** Everything you add most often, one tap away. */
internal fun MainActivity.showQuickAdd() {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val palette = AppRepository.palette(this)
    val sheet = BottomSheetDialog(this)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(22)) }
    root.addView(android.widget.TextView(this).apply { text = "Cosa vuoi aggiungere?"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text); setPadding(dp(4), dp(4), 0, dp(10)) })
    val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
    fun tile(icon: String, label: String, action: () -> Unit) = grid.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(14), dp(4), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.card); setStroke(1, palette.stroke) }
        addView(android.widget.TextView(this@showQuickAdd).apply { text = icon; textSize = 28f; gravity = Gravity.CENTER })
        addView(android.widget.TextView(this@showQuickAdd).apply { text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(palette.text); setPadding(0, dp(4), 0, 0) })
        layoutParams = android.widget.GridLayout.LayoutParams().apply { width = 0; height = -2; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { sheet.dismiss(); action() }
    })
    tile("📝", "Nota") { editNote(null) }
    tile("🛒", "Spesa") { quickShopping() }
    tile("⏰", "Promemoria") { quickReminder() }
    tile("⚠️", "Scadenza") { editDeadline(Deadlines.DEADLINE) }
    tile("💰", "Spesa in euro") { addExpenseDialog() }
    tile("📷", "Foto documento") { scanDocument() }
    tile("🎙", "Appunto a voce") { requestMicAndListen() }
    tile("⚖️", "Peso e misure") { healthType = "peso"; showHealthPage() }
    tile("💧", "Un bicchiere d'acqua") { Food.addWater(this, Food.glass(this)); toast("💧 +${Food.glass(this)} ml"); wellnessRefresh?.invoke() }
    root.addView(grid)
    sheet.setContentView(root); sheet.show()
}

internal fun MainActivity.quickShopping() {
    val et = field("Cosa devi comprare? (anche più cose: latte, pane, uova)", null)
    MaterialAlertDialogBuilder(this).setTitle("🛒 Aggiungi alla spesa").setView(form(et)).setPositiveButton("Aggiungi") { _, _ ->
        val items = et.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) toast("Scrivi cosa comprare") else { addItemsToShoppingList(items); com.weblauncher.app.ShopWidget.update(this) }
    }.setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.quickReminder() {
    val et = field("Es. «chiama il dentista domani alle 10»", null)
    MaterialAlertDialogBuilder(this).setTitle("⏰ Nuovo promemoria").setView(form(et)).setPositiveButton("Crea") { _, _ ->
        val t = et.text.toString().trim(); if (t.isEmpty()) toast("Scrivi cosa ricordare") else createReminderFromText(t)
    }.setNegativeButton("Annulla", null).show()
}

/** The «+» button on the Home: shown only there. */
internal fun MainActivity.setupQuickButton() {
    val root = findViewById<View>(R.id.bottomBar).parent as? androidx.constraintlayout.widget.ConstraintLayout ?: return
    val d = resources.displayMetrics.density
    val fab = com.google.android.material.floatingactionbutton.FloatingActionButton(this).apply {
        id = View.generateViewId(); setImageResource(R.drawable.ic_tool_plus); contentDescription = "Aggiungi"
        size = com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_NORMAL
        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(this@setupQuickButton, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
        setOnClickListener { showQuickAdd() }
        setOnLongClickListener { showTodoPage(); true }
    }
    val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(-2, -2).apply {
        bottomToTop = R.id.bottomBar; endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        marginEnd = (18 * d).toInt(); bottomMargin = (10 * d).toInt()
    }
    root.addView(fab, lp)
    quickFab = fab
    fab.visibility = if (currentTab == MainActivity.TAB_HOME) View.VISIBLE else View.GONE
}

// ======================= «Cosa usi?» =======================

/** Choose which groups of tools to show in Strumenti. Tools hidden one by one earlier stay hidden. */
internal fun MainActivity.showWhatYouUse(after: () -> Unit = {}) {
    val tp = getSharedPreferences("wl_tools", android.content.Context.MODE_PRIVATE)
    val hidden = tp.getStringSet("hidden", emptySet()).orEmpty()
    val cat = toolsCatalog.filter { it.first != "⭐ PREFERITI" }
    if (cat.isEmpty()) return
    val blurb = mapOf(
        "ORGANIZZA" to "note, lista della spesa, ricette, compleanni", "LA TUA GIORNATA" to "buongiorno, meteo, avvisi, modalità auto",
        "SOLDI" to "spese, finanze, scadenze, borsa", "SALUTE E SPORT" to "benessere, passi, allenamento", "AI" to "Mia AI e domande a voce",
        "DOCUMENTI" to "scanner, PDF, biglietti, cassaforte", "UTILITÀ" to "QR code, misure, calcoli", "BROWSER E APP" to "app, download, privacy, backup")
    val labels = cat.map { (g, items) -> g.lowercase(Locale.ITALIAN).replaceFirstChar { it.uppercase() } + (blurb[g]?.let { " — $it" } ?: "") }.toTypedArray()
    val checked = BooleanArray(cat.size) { i -> cat[i].second.any { it.first !in hidden } }
    MaterialAlertDialogBuilder(this).setTitle("Cosa usi?")
        .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
        .setPositiveButton("Salva") { _, _ ->
            val newHidden = hidden.toMutableSet()
            cat.forEachIndexed { i, (_, items) ->
                if (checked[i]) { /* a group switched on again: its tools come back */ if (cat[i].second.all { it.first in hidden }) newHidden.removeAll(items.map { it.first }.toSet()) }
                else newHidden.addAll(items.map { it.first })
            }
            tp.edit().putStringSet("hidden", newHidden).putBoolean("usesAsked", true).apply(); renderTools(); after()
        }
        .setNegativeButton("Non ora") { _, _ -> tp.edit().putBoolean("usesAsked", true).apply() }
        .setNeutralButton("Mostra tutto") { _, _ -> tp.edit().putStringSet("hidden", emptySet()).putBoolean("usesAsked", true).apply(); renderTools(); after() }
        .show()
}
