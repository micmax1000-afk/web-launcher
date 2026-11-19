package com.weblauncher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ======================= Calendario: settimana e mese con tutto insieme =======================

/** Tutto quello che c'è tra [from] e [to]: appuntamenti, promemoria, scadenze, compleanni, rifiuti. */
internal fun MainActivity.agenda(from: Long, to: Long): List<Todo.Item> {
    val out = mutableListOf<Todo.Item>()
    out += runCatching { Todo.calendar(this, from - 2 * 3_600_000L, to) }.getOrDefault(emptyList()).filter { it.time < to }
    Reminders.load(this).filter { !it.done && it.at in from until to }.forEach { r ->
        out += Todo.Item(r.at, false, r.text, if (r.repeat != null) "🔁" else "⏰", "rem", r.id.toString())
    }
    runCatching { Deadlines.load(this) }.getOrDefault(mutableListOf()).filter { it.kind != Deadlines.WARRANTY && it.date in from until to }.forEach { d ->
        out += Todo.Item(d.date, true, if (d.kind == Deadlines.SUBSCRIPTION) "${d.title} si rinnova" else "${d.title} scade", "⚠️", "dead", d.id, d.kind)
    }
    val people = runCatching { Birthdays.load(this) }.getOrDefault(emptyList())
    val waste = runCatching { Evening.waste(this) }.getOrDefault(emptyMap()); val wasteDates = runCatching { Evening.wasteDates(this) }.getOrDefault(emptyMap())
    var day = CalendarTools.dayStart(from)
    while (day < to) {
        val dd = day
        people.filter { CalendarTools.birthdayOn(it.month, it.day, dd) }.forEach { out += Todo.Item(dd, true, "Compleanno di ${it.name}", "🎂", "bday") }
        val k = runCatching { Waste.kindsOn(waste, wasteDates, Calendar.getInstance().apply { timeInMillis = dd }) }.getOrDefault(emptyList())
        if (k.isNotEmpty()) out += Todo.Item(dd, true, "Raccolta: " + k.joinToString(", "), "🗑", "waste")
        day = Calendar.getInstance().apply { timeInMillis = dd; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    }
    return out.sortedWith(compareBy<Todo.Item> { CalendarTools.dayStart(it.time) }.thenBy { !it.allDay }.thenBy { it.time })
}

private fun MainActivity.openAgendaItem(i: Todo.Item) {
    when (i.kind) {
        "cal" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, i.id.toLong()))) }
        "rem" -> showRemindersPage()
        "dead" -> showDeadlinesPage(i.extra.ifBlank { Deadlines.DEADLINE })
        "bday" -> showBirthdaysPage()
        "waste" -> showWastePage()
    }
}

/** Apre il calendario del telefono con l'appuntamento già compilato: lì si controlla e si salva. */
internal fun MainActivity.insertEvent(title: String, begin: Long, place: String? = null, allDay: Boolean = false) {
    val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, title)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
    if (!place.isNullOrBlank()) i.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, place)
    runCatching { startActivity(i) }.onFailure { toast("Nessuna app calendario trovata") }
}

private fun MainActivity.addEventMenu(day: Long) {
    MaterialAlertDialogBuilder(this).setTitle("＋ Nuovo appuntamento")
        .setItems(arrayOf("✍️ Scrivilo (es. «dentista giovedì alle 15»)", "🗓 Modulo del calendario", "🎤 A voce", "📷 Da una foto (invito, lettera…)")) { _, w ->
            when (w) {
                0 -> {
                    val et = field("Es. «cena da Luca sabato alle 20»", null)
                    MaterialAlertDialogBuilder(this).setTitle("Nuovo appuntamento").setView(form(et)).setPositiveButton("Avanti") { _, _ ->
                        eventFromText(et.text.toString().trim())
                    }.setNegativeButton("Annulla", null).show()
                }
                1 -> insertEvent("", Calendar.getInstance().apply { timeInMillis = day; set(Calendar.HOUR_OF_DAY, 9) }.timeInMillis)
                2 -> {
                    askVoiceTarget = { t -> eventFromText(t) }
                    val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Es. «dentista giovedì alle 15»")
                    }
                    try { askSpeechLauncher.launch(i) } catch (_: Exception) { askVoiceTarget = null; toast(getString(R.string.no_speech)) }
                }
                else -> eventFromPhoto()
            }
        }.show()
}

private fun MainActivity.eventFromText(t: String) {
    if (t.isBlank()) { toast("Scrivi cosa e quando"); return }
    val p = Reminders.parse(t)
    if (p == null) { toast("Non ho capito giorno e ora: es. «martedì alle 10»"); return }
    insertEvent(p.text.ifBlank { "Appuntamento" }, p.at)
}

/** L'AI legge la foto di un invito o di una lettera e prepara l'appuntamento. */
private fun MainActivity.eventFromPhoto() {
    if (!aiReady()) return
    val f = File(scanDir, "evento_${System.currentTimeMillis()}.jpg")
    capturePhoto(f) { shot ->
        if (shot == null) return@capturePhoto
        toast("✨ Leggo la foto…")
        io.execute {
            shrinkPhoto(shot)
            runOnUiThread {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                ai.ask(listOf("user" to "Oggi è $today. Nella foto c'è un invito, una lettera o un avviso con un appuntamento. " +
                    "Rispondi SOLO con JSON: {\"titolo\":\"breve\",\"data\":\"AAAA-MM-GG\",\"ora\":\"HH:MM o vuoto se tutto il giorno\",\"luogo\":\"indirizzo o vuoto\"}. " +
                    "Se non trovi una data rispondi {\"data\":\"\"}."), "Rispondi solo con JSON valido.", object : AiBridge.Listener {
                    override fun onDone(text: String, model: String) {
                        val o = runCatching { org.json.JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }.getOrNull()
                        val date = o?.optString("data").orEmpty()
                        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(date)
                        if (o == null || m == null) { toast("Non ho trovato una data nella foto"); return }
                        val hm = Regex("^(\\d{1,2})[:.](\\d{2})").find(o.optString("ora"))
                        val cal = Calendar.getInstance().apply {
                            clear(); set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), hm?.groupValues?.get(1)?.toInt() ?: 9, hm?.groupValues?.get(2)?.toInt() ?: 0)
                        }
                        insertEvent(o.optString("titolo").ifBlank { "Appuntamento" }, cal.timeInMillis, o.optString("luogo"), allDay = hm == null)
                    }
                    override fun onError(message: String, noKey: Boolean) { toast(if (noKey) "Configura l'AI in Impostazioni → Assistente AI" else message) }
                }, listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)))
            }
        }
    }
}

internal fun MainActivity.showCalendarPage() {
    var month = false
    var anchor = CalendarTools.dayStart(System.currentTimeMillis())
    var selected = anchor
    lateinit var render: () -> Unit
    openPage("Calendario", "＋ Aggiungi", { addEventMenu(selected) }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val today = CalendarTools.dayStart(System.currentTimeMillis())
        val colors = mapOf("cal" to accent, "rem" to 0xFFE0A100.toInt(), "dead" to 0xFFE05A2A.toInt(), "bday" to 0xFFD9467A.toInt(), "waste" to 0xFF16A34A.toInt())
        val dayFmt = SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN); val tf = SimpleDateFormat("HH:mm", Locale.ITALIAN)

        fun box(bg: Int, stroke: Int, r: Float = 12f) = GradientDrawable().apply { cornerRadius = r * d; setColor(bg); setStroke(d.toInt().coerceAtLeast(1), stroke) }
        fun itemRow(parent: LinearLayout, i: Todo.Item) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(12), 0)
                background = box(palette.card, palette.stroke)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
                setOnClickListener { openAgendaItem(i) }
            }
            row.addView(View(this).apply { setBackgroundColor(colors[i.kind] ?: accent); layoutParams = LinearLayout.LayoutParams(dp(4), dp(44)).apply { marginEnd = dp(10) } })
            row.addView(TextView(this).apply {
                text = "${i.icon}  ${i.text}"; textSize = 14.5f; setTextColor(palette.text); maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply { text = if (i.allDay) "" else tf.format(Date(i.time)); textSize = 12.5f; setTextColor(palette.textMuted) })
            parent.addView(row)
        }
        fun dayHeader(parent: LinearLayout, t: Long) = parent.addView(TextView(this).apply {
            text = (if (t == today) "OGGI · " else if (t == today + 86_400_000L) "DOMANI · " else "") + dayFmt.format(Date(t)).uppercase(Locale.ITALIAN)
            textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(if (t == today) accent else palette.textMuted); setPadding(dp(8), dp(12), 0, dp(4))
        })

        render = {
            body.removeAllViews()
            // barra: ‹ periodo › e Settimana / Mese
            val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
            fun arrow(t: String, delta: Int) = TextView(this).apply {
                text = t; textSize = 24f; setTextColor(palette.text); setPadding(dp(14), dp(2), dp(14), dp(2))
                setOnClickListener {
                    anchor = Calendar.getInstance().apply { timeInMillis = anchor; if (month) add(Calendar.MONTH, delta) else add(Calendar.DAY_OF_YEAR, 7 * delta) }.timeInMillis
                    selected = if (month) Calendar.getInstance().apply { timeInMillis = anchor; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis else CalendarTools.weekStart(anchor)
                    if (CalendarTools.sameMonth(today, anchor) && month || !month && CalendarTools.weekStart(today) == CalendarTools.weekStart(anchor)) selected = today
                    render()
                }
            }
            val ws = CalendarTools.weekStart(anchor)
            val label = if (month) SimpleDateFormat("MMMM yyyy", Locale.ITALIAN).format(Date(anchor)).replaceFirstChar { it.uppercase() }
                else SimpleDateFormat("d MMM", Locale.ITALIAN).format(Date(ws)) + " – " + SimpleDateFormat("d MMM", Locale.ITALIAN).format(Date(ws + 6 * 86_400_000L))
            nav.addView(arrow("‹", -1))
            nav.addView(TextView(this).apply { text = label; textSize = 16.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(palette.text); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            nav.addView(arrow("›", 1))
            body.addView(nav)
            val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8)) }
            fun mode(t: String, m: Boolean) = modes.addView(TextView(this).apply {
                text = t; textSize = 13.5f; setPadding(dp(16), dp(7), dp(16), dp(7))
                setTextColor(if (month == m) accent else palette.text); if (month == m) setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { cornerRadius = 18f * d; setColor(palette.card); setStroke(dp(if (month == m) 2 else 1).coerceAtLeast(1), if (month == m) accent else palette.stroke) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { month = m; render() }
            })
            mode("Settimana", false); mode("Mese", true)
            modes.addView(TextView(this).apply {
                text = "Oggi"; textSize = 13.5f; setPadding(dp(16), dp(7), dp(16), dp(7)); setTextColor(palette.text)
                background = GradientDrawable().apply { cornerRadius = 18f * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
                setOnClickListener { anchor = today; selected = today; render() }
            })
            body.addView(modes)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                listRow(body, "📅 Collega il calendario del telefono", "Per vedere qui anche gli appuntamenti", onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) })

            if (!month) {
                val days = CalendarTools.days(ws, 7)
                val items = agenda(ws, days.last() + 86_400_000L)
                val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val initials = listOf("L", "M", "M", "G", "V", "S", "D")
                days.forEachIndexed { idx, t ->
                    val n = items.count { CalendarTools.dayStart(it.time) == t }
                    val on = t == today
                    strip.addView(TextView(this).apply {
                        text = initials[idx] + "\n" + Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH) + "\n" + "•".repeat(n.coerceAtMost(3)).ifEmpty { " " }
                        textSize = 12f; gravity = Gravity.CENTER; setLineSpacing(0f, 0.95f)
                        setTextColor(if (on) android.graphics.Color.WHITE else palette.text)
                        background = box(if (on) accent else palette.card, if (on) accent else palette.stroke, 10f)
                        setPadding(0, dp(6), 0, dp(4))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                    })
                }
                body.addView(strip)
                var any = false
                days.forEach { t ->
                    val list = items.filter { CalendarTools.dayStart(it.time) == t }
                    if (list.isEmpty()) return@forEach
                    any = true; dayHeader(body, t); list.forEach { itemRow(body, it) }
                }
                if (!any) hintLabel(body, "Niente in programma questa settimana.")
            } else {
                val cells = CalendarTools.monthGrid(anchor)
                val items = agenda(cells.first(), cells.last() + 86_400_000L)
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("L", "M", "M", "G", "V", "S", "D").forEach { s -> head.addView(TextView(this).apply { text = s; textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(palette.textMuted); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }) }
                body.addView(head)
                cells.chunked(7).forEach { week ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    week.forEach { t ->
                        val inMonth = CalendarTools.sameMonth(t, anchor)
                        val icons = items.filter { CalendarTools.dayStart(it.time) == t }.map { it.icon }.distinct().take(2).joinToString("")
                        val sel = t == selected
                        row.addView(TextView(this).apply {
                            text = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH).toString() + "\n" + icons.ifEmpty { " " }
                            textSize = 12f; gravity = Gravity.TOP or Gravity.START; setPadding(dp(4), dp(3), dp(2), dp(3))
                            setTextColor(if (inMonth) palette.text else palette.textMuted); if (t == today) setTypeface(typeface, Typeface.BOLD)
                            alpha = if (inMonth) 1f else 0.5f
                            background = GradientDrawable().apply { cornerRadius = 8f * d; setColor(palette.card); setStroke(dp(if (sel || t == today) 2 else 1).coerceAtLeast(1), if (sel) accent else if (t == today) palette.textMuted else palette.stroke) }
                            minHeight = dp(48)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
                            setOnClickListener { selected = t; render() }
                        })
                    }
                    body.addView(row)
                }
                dayHeader(body, selected)
                val list = items.filter { CalendarTools.dayStart(it.time) == selected }
                if (list.isEmpty()) hintLabel(body, "Niente in programma. Tocca «＋ Aggiungi» in alto.") else list.forEach { itemRow(body, it) }
            }
        }
        render()
        calendarPageRefresh = { render() }
        dialog.setOnDismissListener { calendarPageRefresh = null }
    }
}
