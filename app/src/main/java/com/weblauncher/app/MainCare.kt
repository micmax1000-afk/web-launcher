package com.weblauncher.app

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun MainActivity.carePrefs() = getSharedPreferences("wl_care", android.content.Context.MODE_PRIVATE)

// ======================= Sleep =======================

internal fun MainActivity.showSleepPage() {
    openPage("Sonno", "Aggiungi", { dialog ->
        val et = field("Ore dormite (es. 7,5)", null, number = true)
        MaterialAlertDialogBuilder(this).setTitle("Com'è andata stanotte?").setView(form(et)).setPositiveButton("Salva") { _, _ ->
            val h = Calc.num(et.text.toString())
            if (h == null || h !in 0.5..16.0) toast("Scrivi le ore, per esempio 7,5")
            else { val l = Health.load(this); l.add(Measure(java.util.UUID.randomUUID().toString(), "sonno", h, null, System.currentTimeMillis())); Health.save(this, l); dialog.dismiss(); showSleepPage(); wellnessRefresh?.invoke() }
        }.setNegativeButton("Annulla", null).show()
    }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val snap = Hc.cached(this)
        val nights = snap?.days?.filter { it.sleepMin > 0 }.orEmpty().takeLast(14)
        val manual = Health.load(this).filter { it.type == "sonno" }.sortedBy { it.time }
        val hours = if (nights.isNotEmpty()) nights.map { it.sleepMin / 60.0 } else manual.takeLast(14).map { it.v1 }
        if (hours.isEmpty()) {
            emptyLabel(body, "Nessun dato sul sonno.\nSe colleghi l'orologio (Benessere → Orologio e app fitness) lo vedi qui da solo; altrimenti usa «Aggiungi» in alto.")
        } else {
            val avg = hours.average()
            body.addView(statRow(
                statTile("Media", Hc.duration((avg * 60).toLong()), "ultime ${hours.size} notti", if (avg >= 7) 0xFF16A34A.toInt() else if (avg >= 6) 0xFFF59E0B.toInt() else 0xFFDC2626.toInt()),
                statTile("Ultima notte", Hc.duration((hours.last() * 60).toLong())),
                statTile("Migliore", Hc.duration((hours.max() * 60).toLong()))))
            if (hours.size >= 2) body.addView(LineChartView(this).apply {
                series1 = hours; color1 = 0xFF6366F1.toInt(); gridColor = palette.stroke; textColor = palette.textMuted
                layoutParams = LinearLayout.LayoutParams(-1, (160 * d).toInt()).apply { setMargins((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt()) }
            })
            hintLabel(body, "Per gli adulti si consigliano 7–9 ore a notte.")
        }
        if (nights.isNotEmpty()) {
            sectionLabel(body, "DALL'OROLOGIO")
            nights.reversed().take(7).forEach { n ->
                listRow(body, "😴 ${n.date}: ${Hc.duration(n.sleepMin)}", if (n.deepMin > 0 || n.remMin > 0) "profondo ${Hc.duration(n.deepMin)} · REM ${Hc.duration(n.remMin)}" else null, onClick = {})
            }
        }
        sectionLabel(body, "PROMEMORIA PER ANDARE A LETTO")
        val rid = carePrefs().getInt("sleep_rem", 0); val time = carePrefs().getString("sleep_time", "22:30") ?: "22:30"
        listRow(body, if (rid != 0) "🔔 Ogni sera alle $time" else "🔕 Non attivo", if (rid != 0) "Tocca per cambiare o spegnere" else "Una notifica per ricordarti di prepararti a dormire", onClick = {
            MaterialAlertDialogBuilder(this).setItems(arrayOf("Scegli l'orario", if (rid != 0) "Spegni" else "Annulla")) { _, w ->
                if (w == 1) { if (rid != 0) { Reminders.remove(this, rid); carePrefs().edit().remove("sleep_rem").apply() }; dialog.dismiss(); showSleepPage() }
                else {
                    val (h, m) = time.split(':').map { it.toInt() }
                    TimePickerDialog(this, { _, hh, mm ->
                        if (rid != 0) Reminders.remove(this, rid)
                        ensureNotificationPermission()
                        val t = "%02d:%02d".format(hh, mm)
                        val r = Reminders.add(this, "😴 È ora di prepararsi per dormire", Meds.nextAt(t), "day")
                        carePrefs().edit().putInt("sleep_rem", r.id).putString("sleep_time", t).apply(); dialog.dismiss(); showSleepPage()
                    }, h, m, true).show()
                }
            }.show()
        })
        if (manual.isNotEmpty()) { sectionLabel(body, "INSERITO A MANO"); manual.reversed().take(7).forEach { m -> listRow(body, "😴 ${Health.fmt(m)}", SimpleDateFormat("EEE d MMM", Locale.ITALIAN).format(Date(m.time)), onClick = {}) } }
    }
}

// ======================= Medicines =======================

internal fun MainActivity.showMedsPage() {
    openPage("Farmaci", "Aggiungi", { dialog -> editMed(null) { dialog.dismiss(); showMedsPage() } }) { _, body, dialog ->
        val list = Meds.load(this)
        if (list.isEmpty()) { emptyLabel(body, "Nessun farmaco.\nAggiungine uno: ti ricordo gli orari e ti avviso quando le scorte stanno finendo."); return@openPage }
        fun again() { dialog.dismiss(); showMedsPage(); wellnessRefresh?.invoke() }
        list.forEach { m ->
            val low = Meds.isLow(m)
            val sub = buildList {
                if (m.dose.isNotBlank()) add(m.dose)
                Meds.nextDose(m)?.let { add("prossima: $it") }
                if (m.times.isNotEmpty()) add("oggi ${Meds.takenToday(this@showMedsPage, m.id)}/${m.times.size}")
                add(if (m.stock <= 0 && m.times.isNotEmpty()) "⚠️ finito" else "${m.stock} rimaste" + if (m.times.isNotEmpty()) " (~${Meds.daysLeft(m)} giorni)" else "")
            }.joinToString(" · ")
            listRow(body, (if (low) "⚠️ " else "💊 ") + m.name, sub, onClick = {
                MaterialAlertDialogBuilder(this).setTitle(m.name).setItems(arrayOf("✅ Ho preso la dose", "Modifica", "Elimina")) { _, w ->
                    when (w) {
                        0 -> { Meds.markTaken(this, m.id); toast("Segnato. Restano ${(m.stock - m.perDose).coerceAtLeast(0)}"); again() }
                        1 -> editMed(m) { again() }
                        2 -> confirmClear("Eliminare ${m.name}?") { Meds.remove(this, m.id); again() }
                    }
                }.show()
            })
        }
        hintLabel(body, "Le scorte scendono quando tocchi «Ho preso la dose». Alle 19 ricevi un avviso se un farmaco sta per finire. Non sostituisce le indicazioni del medico.")
    }
}

private fun MainActivity.editMed(m: Med?, done: () -> Unit) {
    val name = field("Nome del farmaco", m?.name)
    val dose = field("Dose (es. 1 compressa da 500 mg)", m?.dose)
    val times = field("Orari (es. 8, 14, 20:30)", m?.times?.joinToString(", "))
    val per = field("Pastiglie per dose", (m?.perDose ?: 1).toString(), number = true)
    val stock = field("Quante ne hai nella confezione", m?.stock?.toString(), number = true)
    MaterialAlertDialogBuilder(this).setTitle(if (m == null) "Nuovo farmaco" else "Modifica").setView(form(name, dose, times, per, stock))
        .setPositiveButton("Salva") { _, _ ->
            if (name.text.toString().isBlank()) return@setPositiveButton toast("Scrivi il nome")
            val t = Meds.parseTimes(times.text.toString())
            if (times.text.toString().isNotBlank() && t.isEmpty()) return@setPositiveButton toast("Orari non validi: scrivi per esempio 8, 20:30")
            if (t.isNotEmpty()) ensureNotificationPermission()
            Meds.upsert(this, Med(m?.id ?: Meds.newId(), name.text.toString().trim(), dose.text.toString().trim(), t, stock.text.toString().toIntOrNull() ?: 0, per.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1))
            done()
        }.setNegativeButton("Annulla", null).show()
}

// ======================= Visits =======================

internal fun MainActivity.showVisitsPage() {
    openPage("Visite mediche", "Aggiungi", { dialog -> editVisit(null) { dialog.dismiss(); showVisitsPage() } }) { _, body, dialog ->
        val all = Visits.load(this)
        if (all.isEmpty()) { emptyLabel(body, "Nessuna visita.\nAggiungine una: ti avviso la sera prima e due ore prima."); return@openPage }
        val now = System.currentTimeMillis(); val f = SimpleDateFormat("EEE d MMM yyyy 'alle' HH:mm", Locale.ITALIAN)
        fun again() { dialog.dismiss(); showVisitsPage() }
        val next = all.filter { it.time >= now }; val past = all.filter { it.time < now }.reversed()
        if (next.isNotEmpty()) sectionLabel(body, "PROSSIME")
        next.forEach { v -> listRow(body, "🩺 ${v.title}", f.format(Date(v.time)).replaceFirstChar { it.uppercase() } + (if (v.place.isNotBlank()) " · ${v.place}" else ""), onClick = { editVisit(v) { again() } }, onRemove = { Visits.remove(this, v.id); again() }) }
        if (past.isNotEmpty()) sectionLabel(body, "PASSATE")
        past.take(10).forEach { v -> listRow(body, v.title, f.format(Date(v.time)), onClick = { editVisit(v) { again() } }, onRemove = { Visits.remove(this, v.id); again() }) }
    }
}

private fun MainActivity.editVisit(v: Visit?, done: () -> Unit) {
    val title = field("Che visita (es. Cardiologo)", v?.title)
    val place = field("Dove (facoltativo)", v?.place)
    val note = field("Nota (facoltativo)", v?.note)
    var at = v?.time ?: Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }.timeInMillis
    val f = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ITALIAN)
    val whenBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false }
    fun show() { whenBtn.text = "📅 " + f.format(Date(at)) }
    show()
    whenBtn.setOnClickListener {
        pickDate("Giorno della visita", at) { day ->
            val c = Calendar.getInstance().apply { timeInMillis = at }
            TimePickerDialog(this, { _, h, m -> at = Calendar.getInstance().apply { timeInMillis = day; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }.timeInMillis; show() }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }
    }
    val box = form(title, place, note).apply { addView(whenBtn) }
    MaterialAlertDialogBuilder(this).setTitle(if (v == null) "Nuova visita" else "Modifica visita").setView(box)
        .setPositiveButton("Salva") { _, _ ->
            if (title.text.toString().isBlank()) return@setPositiveButton toast("Scrivi che visita è")
            ensureNotificationPermission()
            Visits.upsert(this, Visit(v?.id ?: Visits.newId(), title.text.toString().trim(), at, place.text.toString().trim(), note.text.toString().trim()))
            toast("🩺 Salvata, con promemoria"); done()
        }.setNegativeButton("Annulla", null).show()
}

// ======================= Emergency card =======================

internal fun MainActivity.showEmergencyPage() {
    openPage("Scheda d'emergenza", "Modifica", { dialog -> editEmergency { dialog.dismiss(); showEmergencyPage() } }) { _, body, dialog ->
        val e = Emergency.load(this)
        if (Emergency.isEmpty(e)) { emptyLabel(body, "La scheda è vuota.\nTocca «Modifica» e scrivi gruppo sanguigno, allergie, farmaci e chi chiamare: in caso di bisogno serve ai soccorritori."); return@openPage }
        Emergency.lines(e).forEach { listRow(body, it, null, onClick = {}) }
        sectionLabel(body, "SUL BLOCCO SCHERMO")
        val on = Emergency.onLockScreen(this)
        listRow(body, if (on) "🔓 Visibile a schermo bloccato" else "🔒 Non visibile a schermo bloccato", if (on) "Chiunque può leggerla: tocca per nasconderla" else "Tocca per mostrarla in una notifica, leggibile anche senza sbloccare", onClick = {
            if (on) { Emergency.setOnLockScreen(this, false); dialog.dismiss(); showEmergencyPage() }
            else MaterialAlertDialogBuilder(this).setTitle("Mostrarla a schermo bloccato?")
                .setMessage("Chi ha il telefono in mano potrà leggere nome, allergie, patologie e contatti senza sbloccarlo. È utile ai soccorritori, ma è un dato personale visibile a chiunque.")
                .setPositiveButton("Mostra") { _, _ -> ensureNotificationPermission(); Emergency.setOnLockScreen(this, true); dialog.dismiss(); showEmergencyPage() }
                .setNegativeButton("Annulla", null).show()
        })
        listRow(body, "📤 Condividi", "Come testo", onClick = { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Scheda d'emergenza\n" + Emergency.lines(e).joinToString("\n")), "Condividi")) })
    }
}

private fun MainActivity.editEmergency(done: () -> Unit) {
    val e = Emergency.load(this)
    val f = listOf(
        field("Nome e cognome", e.name), field("Data di nascita", e.birth), field("Gruppo sanguigno (es. A+)", e.blood), field("Allergie", e.allergies),
        field("Patologie", e.conditions), field("Farmaci che prendi", e.medicines), field("Contatto 1 (nome)", e.contact1), field("Telefono 1", e.phone1),
        field("Contatto 2 (nome)", e.contact2), field("Telefono 2", e.phone2), field("Note", e.notes))
    val sv = android.widget.ScrollView(this).apply { addView(form(*f.toTypedArray())) }
    MaterialAlertDialogBuilder(this).setTitle("Scheda d'emergenza").setView(sv).setPositiveButton("Salva") { _, _ ->
        val v = f.map { it.text.toString().trim() }
        Emergency.save(this, EmergencyCard(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])); done()
    }.setNegativeButton("Annulla", null).show()
}

// ======================= Report for the doctor =======================

internal fun MainActivity.showReportPage() {
    openPage("Referto per il medico") { _, body, _ ->
        val palette = AppRepository.palette(this)
        var days = 30
        fun render() {
            body.removeAllViews()
            val to = System.currentTimeMillis(); val from = to - days * 86_400_000L
            val data = reportData(from, to)
            sectionLabel(body, "PERIODO")
            listRow(body, "Ultimi $days giorni", "Tocca per cambiare", onClick = {
                val opts = intArrayOf(14, 30, 90, 180)
                MaterialAlertDialogBuilder(this).setItems(opts.map { "Ultimi $it giorni" }.toTypedArray()) { _, i -> days = opts[i]; render() }.show()
            })
            Report.sections(data).forEach { (title, lines) ->
                sectionLabel(body, title.uppercase(Locale.ITALIAN))
                lines.forEach { body.addView(TextView(this).apply { text = it; textSize = 14f; setTextColor(palette.text); setPadding(32, 4, 24, 4) }) }
            }
            body.addView(MaterialButton(this).apply {
                text = "📄 Crea il PDF e condividilo"; isAllCaps = false; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()).apply { setMargins(24, 28, 24, 12) }
                setOnClickListener { sharePdf(data) }
            })
            hintLabel(body, "Il file contiene dati di salute: condividilo solo con il tuo medico.")
        }
        render()
    }
}

private fun MainActivity.reportData(from: Long, to: Long): Report.Data {
    val all = Health.load(this).filter { it.time in from..to }.sortedBy { it.time }
    val snap = Hc.cached(this)
    val days = snap?.days.orEmpty().takeLast(((to - from) / 86_400_000L).toInt())
    val e = Emergency.load(this)
    return Report.Data(
        e.name, from, to,
        all.filter { it.type == "peso" }.map { it.time to it.v1 },
        all.filter { it.type == "pressione" && it.v2 != null }.map { Triple(it.time, it.v1.toInt(), it.v2!!.toInt()) },
        all.filter { it.type == "glicemia" }.map { it.time to it.v1 },
        Hc.avg(days.filter { it.steps > 0 }) { it.steps.toDouble() }.toLong(), Hc.avg(days.filter { it.sleepMin > 0 }) { it.sleepMin.toDouble() }.toLong(), Hc.avg(days.filter { it.restHr > 0 }) { it.restHr.toDouble() }.toLong(),
        Fitness.allTimes(this).count { it in from..to },
        Meds.load(this).map { it.name + if (it.dose.isNotBlank()) " (${it.dose})" else "" }, e.allergies, e.conditions)
}

private fun MainActivity.sharePdf(data: Report.Data) {
    io.execute {
        val file = runCatching {
            val doc = PdfDocument()
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; isFakeBoldText = true }
            val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; isFakeBoldText = true; color = 0xFF2563EB.toInt() }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
            var n = 1; var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); var y = 60f
            fun newPage() { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); y = 50f }
            fun line(s: String, p: Paint, step: Float) {
                // wrap long lines at ~95 characters
                s.chunked(95).ifEmpty { listOf("") }.forEach { part -> if (y > 790f) newPage(); page.canvas.drawText(part, 40f, y, p); y += step }
            }
            line("Riepilogo di salute" + if (data.name.isNotBlank()) " — ${data.name}" else "", title, 28f)
            line("Creato il " + SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(Date()) + " con Web Launcher", text, 20f)
            Report.sections(data).forEach { (t, ls) -> y += 10f; line(t, head, 20f); ls.forEach { line("• $it", text, 15f) } }
            y += 20f; line("Dati inseriti dal paziente o letti dall'orologio: non sono una diagnosi.", text, 14f)
            doc.finishPage(page)
            val dir = File(cacheDir, "report").apply { mkdirs() }
            val out = File(dir, "referto_${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.pdf")
            out.outputStream().use { doc.writeTo(it) }; doc.close(); out
        }.getOrNull()
        runOnUiThread {
            if (file == null) { toast("Non sono riuscito a creare il PDF"); return@runOnUiThread }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Condividi il referto"))
        }
    }
}
