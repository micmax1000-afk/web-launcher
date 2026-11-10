package com.weblauncher.app

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Asks for the Bluetooth permissions (Android 12+: scan/connect; before: location) and then runs [ok]. */
internal fun MainActivity.withBlePermission(ok: () -> Unit) {
    val need = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (need.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) { ok(); return }
    bleDone = { granted -> if (granted) ok() else MaterialAlertDialogBuilder(this).setTitle("Serve il permesso Bluetooth")
        .setMessage("Per trovare la fascia cardio serve il permesso «Dispositivi nelle vicinanze». Puoi darlo da Impostazioni di Android → App → Web Launcher → Autorizzazioni.").setPositiveButton("OK", null).show() }
    blePermission.launch(need)
}

private data class Recovery(val time: Long, val peak: Int, val after: Int)

private fun MainActivity.recoveries(): List<Recovery> = runCatching {
    Gson().fromJson<List<Recovery>>(getSharedPreferences("wl_fitness", android.content.Context.MODE_PRIVATE).getString("hr_recovery", null), object : TypeToken<List<Recovery>>() {}.type)
}.getOrNull().orEmpty().filterNotNull()

/** Shows a countdown while the strap is read; calls [done] with every reading collected (or null if cancelled / no signal). */
private fun MainActivity.measureHr(title: String, message: String, seconds: Int, done: (List<Pair<Long, Int>>?) -> Unit) {
    val tv = TextView(this).apply { textSize = 40f; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40) }
    val h = Handler(Looper.getMainLooper())
    var left = seconds; var finished = false
    HrBand.startRecording()
    val dlg = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setView(tv).setCancelable(false)
        .setNegativeButton("Annulla") { _, _ -> finished = true; HrBand.stopRecording(); done(null) }.create()
    val tick = object : Runnable { override fun run() {
        if (finished) return
        val bpm = HrBand.current()
        tv.text = "${if (bpm > 0) "❤ $bpm" else "❤ —"}\n$left s"
        if (left-- <= 0) { finished = true; val s = HrBand.stopRecording(); dlg.dismiss(); done(s) } else h.postDelayed(this, 1000)
    } }
    dlg.show(); h.post(tick)
}

internal fun MainActivity.showHrPage() {
    openPage("Fascia cardio") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val h = Handler(Looper.getMainLooper())
        var live: TextView? = null; var liveSub: TextView? = null
        lateinit var render: () -> Unit
        lateinit var chooseAndConnectRef: () -> Unit
        fun updateLive() {
            val bpm = HrBand.current(); val max = WorkoutOpts.maxHr(this); val z = HrZones.zone(bpm, max)
            live?.text = if (bpm > 0) "❤ $bpm" else "❤ —"; live?.setTextColor(if (bpm > 0) HrZones.COLORS[z] else palette.textMuted)
            liveSub?.text = when {
                bpm > 0 -> HrZones.NAMES[z]
                HrBand.state == "connecting" -> "Mi collego a ${HrBand.name}…"
                HrBand.state == "connected" -> "Collegata, aspetto il battito (indossa la fascia: sensori bagnati)"
                HrBand.error.isNotEmpty() -> HrBand.error
                else -> "Non collegata"
            }
        }
        val tick = object : Runnable { override fun run() { updateLive(); h.postDelayed(this, 1000) } }
        fun chooseAndConnect(): Unit = withBlePermission {
            toast("Cerco la fascia… (8 secondi)")
            HrBand.scan(this, 8, { list ->
                if (isFinishing) return@scan
                if (list.isEmpty()) MaterialAlertDialogBuilder(this).setTitle("Nessuna fascia trovata")
                    .setMessage("Controlla che:\n• la fascia sia indossata con i sensori bagnati;\n• non sia già collegata a un'altra app (Mi Fitness, Polar Beat…): se è così, chiudi quell'app o spegni il Bluetooth dell'altra;\n• il Bluetooth sia acceso.")
                    .setPositiveButton("Riprova") { _, _ -> chooseAndConnect() }.setNegativeButton("Chiudi", null).show()
                else MaterialAlertDialogBuilder(this).setTitle("Scegli la fascia").setItems(list.map { it.name + " (" + it.address.takeLast(5) + ")" }.toTypedArray()) { _, i ->
                    ensureNotificationPermission(); HrBand.connect(this, list[i].address, list[i].name); render()
                }.show()
            }, { msg -> toast(msg) })
        }
        render = {
            body.removeAllViews()
            live = TextView(this).apply { textSize = 56f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0) }
            liveSub = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setTextColor(palette.textMuted); setPadding(16, 0, 16, (10 * d).toInt()) }
            body.addView(live); body.addView(liveSub); updateLive()

            sectionLabel(body, "COLLEGAMENTO")
            val saved = HrBand.savedName(this)
            if (HrBand.isOn()) listRow(body, "🔌 Scollega la fascia", HrBand.name, onClick = { HrBand.disconnect(); render() })
            else {
                if (saved != null) listRow(body, "🔗 Riconnetti a $saved", "Tocca dopo aver indossato la fascia", onClick = { withBlePermission { ensureNotificationPermission(); HrBand.autoConnect(this); render() } })
                listRow(body, if (saved != null) "🔎 Cerca un'altra fascia" else "🔎 Cerca la fascia", "Funziona con le fasce Bluetooth standard (Polar, Garmin, Wahoo, Decathlon, Magene…)", onClick = { chooseAndConnect() })
            }
            if (saved != null) listRow(body, "🗑 Dimentica la fascia", saved, onClick = { HrBand.forget(this); render() })
            hintLabel(body, "Molte fasce si collegano a una sola app alla volta: se non si trova, chiudi le altre app che la usano.")

            sectionLabel(body, "ZONE DI BATTITO")
            val max = WorkoutOpts.maxHr(this)
            listRow(body, "Battito massimo: $max bpm", if (getSharedPreferences("wl_fitness", android.content.Context.MODE_PRIVATE).getInt("max_hr", 0) > 0) "Impostato da te: tocca per cambiare" else "Stimato dall'età (formula di Tanaka): tocca per cambiare", onClick = {
                val et = field("Battito massimo (bpm)", max.toString(), number = true)
                MaterialAlertDialogBuilder(this).setTitle("Battito massimo").setView(form(et)).setMessage("Se lo conosci da una prova sotto sforzo o dal tuo orologio, scrivilo. Altrimenti lascio la stima dall'età.")
                    .setPositiveButton("Salva") { _, _ -> val v = et.text.toString().toIntOrNull(); if (v != null && v in 120..230) { WorkoutOpts.setMaxHr(this, v); render() } else toast("Numero non valido") }
                    .setNeutralButton("Usa la stima") { _, _ -> WorkoutOpts.setMaxHr(this, 0); render() }.setNegativeButton("Annulla", null).show()
            })
            (1..5).forEach { z -> val (lo, hi) = HrZones.range(z, max); listRow(body, listOf("🔵", "🟢", "🟡", "🟠", "🔴")[z - 1] + " " + HrZones.NAMES[z], "$lo–$hi bpm", onClick = {}) }
            val tgt = WorkoutOpts.zoneTarget(this)
            listRow(body, if (tgt == 0) "🔔 Avviso se esci dalla zona: spento" else "🔔 Resta in zona $tgt", if (tgt == 0) "Durante l'allenamento con il GPS la voce ti dice di accelerare o rallentare" else "La voce ti avvisa se vai sotto o sopra ${HrZones.range(tgt, max).first}–${HrZones.range(tgt, max).second} bpm", onClick = {
                MaterialAlertDialogBuilder(this).setTitle("Zona da mantenere").setItems(arrayOf("Spento", "Zona 1", "Zona 2 (base)", "Zona 3 (aerobica)", "Zona 4 (soglia)")) { _, i -> WorkoutOpts.setZoneTarget(this, i); render() }.show()
            })

            sectionLabel(body, "PROVE")
            listRow(body, "🛌 Battito a riposo (1 minuto)", "Da sdraiato e tranquillo: si salva nel diario ‘Battito’", onClick = {
                if (HrBand.current() == 0) { toast("Collega prima la fascia"); return@listRow }
                measureHr("Battito a riposo", "Resta sdraiato e respira piano. Misuro per un minuto.", 60) { s ->
                    if (s == null) return@measureHr
                    val after = s.filter { it.first > (s.firstOrNull()?.first ?: 0L) + 15_000 }
                    val v = Hr.avg(after.ifEmpty { s })
                    if (v == 0) toast("Nessun battito letto: controlla la fascia")
                    else { val l = Health.load(this); l.add(Measure(java.util.UUID.randomUUID().toString(), "battito", v.toDouble(), null, System.currentTimeMillis(), "a riposo, fascia")); Health.save(this, l)
                        MaterialAlertDialogBuilder(this).setTitle("Battito a riposo: $v bpm").setMessage("Salvato nel diario. Misuralo sempre alla stessa ora (meglio al mattino): se per più giorni sale molto può indicare stanchezza o stress. Per un parere chiedi al medico.").setPositiveButton("OK", null).show() }
                }
            })
            listRow(body, "📉 Recupero dopo uno sforzo", "Quanto scende il battito in un minuto", onClick = {
                if (HrBand.current() == 0) { toast("Collega prima la fascia"); return@listRow }
                MaterialAlertDialogBuilder(this).setTitle("Prova di recupero").setMessage("1. Fai uno sforzo intenso (scale, corsa, salti) per 2–3 minuti.\n2. Appena finito, tocca «Misura» e resta fermo in piedi.\n\nLeggo il battito subito e dopo un minuto.")
                    .setPositiveButton("Misura") { _, _ ->
                        val peak = HrBand.current()
                        measureHr("Recupero", "Resta fermo. Picco: $peak bpm", 60) { s ->
                            if (s == null) return@measureHr
                            val after = HrBand.current().takeIf { it > 0 } ?: s.lastOrNull()?.second ?: 0
                            if (peak == 0 || after == 0) { toast("Lettura non riuscita"); return@measureHr }
                            val l = (recoveries() + Recovery(System.currentTimeMillis(), peak, after)).takeLast(50)
                            getSharedPreferences("wl_fitness", android.content.Context.MODE_PRIVATE).edit().putString("hr_recovery", Gson().toJson(l)).apply()
                            MaterialAlertDialogBuilder(this).setTitle("Recupero: −${peak - after} bpm").setMessage("Picco $peak → dopo un minuto $after.\nGiudizio: ${Hr.recoveryLabel(peak - after)}.\n\nÈ un indicatore di forma, non una diagnosi: ripetilo ogni tanto per vedere come cambia.").setPositiveButton("OK", null).show()
                            render()
                        }
                    }.setNegativeButton("Annulla", null).show()
            })
            val rec = recoveries()
            if (rec.isNotEmpty()) {
                sectionLabel(body, "RECUPERI MISURATI")
                rec.reversed().take(6).forEach { r -> listRow(body, "−${r.peak - r.after} bpm · ${Hr.recoveryLabel(r.peak - r.after)}", SimpleDateFormat("EEE d MMM", Locale.ITALIAN).format(Date(r.time)) + " · ${r.peak} → ${r.after}", onClick = {}) }
            }
            val rest = Health.load(this).filter { it.type == "battito" && it.note.contains("riposo") }.sortedBy { it.time }.takeLast(14)
            if (rest.size >= 2) {
                sectionLabel(body, "BATTITO A RIPOSO NEL TEMPO")
                body.addView(LineChartView(this).apply { series1 = rest.map { it.v1 }; color1 = 0xFFDC2626.toInt(); gridColor = palette.stroke; textColor = palette.textMuted
                    layoutParams = LinearLayout.LayoutParams(-1, (150 * d).toInt()).apply { setMargins(12, 8, 12, 8) } })
            }

            sectionLabel(body, "COLLEGAMENTO CON MI FITNESS")
            val on = HcWrite.enabled(this)
            listRow(body, if (on) "✅ Salvo gli allenamenti su Health Connect" else "☐ Salva gli allenamenti su Health Connect", if (on) "Compaiono anche in Mi Fitness e nelle altre app. Tocca per spegnere" else "Così li vedi anche in Mi Fitness. Serve un permesso in più", onClick = {
                if (on) { HcWrite.setEnabled(this, false); render() }
                else { HcWrite.setEnabled(this, true); hcPermission.launch(Hc.PERMISSIONS + HcWrite.PERMISSIONS); render() }
            })
        }
        render()
        h.post(tick)
        dialog.setOnDismissListener { h.removeCallbacksAndMessages(null) }
    }
}

// ======================= Body: goal and measure reminder =======================

internal fun MainActivity.showBodyPage() {
    openPage("Corpo e obiettivo") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        val prefs = getSharedPreferences("wl_care", android.content.Context.MODE_PRIVATE)
        fun chart(type: String, label: String, color: Int, goal: Double? = null) {
            val l = Health.load(this).filter { it.type == type }.sortedBy { it.time }.takeLast(16)
            if (l.isEmpty()) return
            sectionLabel(body, label.uppercase(Locale.ITALIAN) + " · ultimo ${Health.fmt(l.last())}")
            if (l.size >= 2) body.addView(LineChartView(this).apply {
                series1 = l.map { it.v1 }; if (goal != null) series2 = l.map { goal }; color1 = color; color2 = 0xFF16A34A.toInt(); gridColor = palette.stroke; textColor = palette.textMuted
                layoutParams = LinearLayout.LayoutParams(-1, (150 * d).toInt()).apply { setMargins(12, 8, 12, 8) }
            })
            else hintLabel(body, "Aggiungi un'altra misura per vedere il grafico.")
        }
        fun render() {
            body.removeAllViews()
            val w = Food.lastWeight(this); val goal = prefs.getFloat("goal_weight", 0f).toDouble()
            sectionLabel(body, "OBIETTIVO DI PESO")
            listRow(body, if (goal > 0) "🎯 Obiettivo: ${"%.1f".format(Locale.ITALIAN, goal)} kg" else "🎯 Imposta il peso che vuoi raggiungere", if (w > 0 && goal > 0) {
                val diff = w - goal
                if (Math.abs(diff) < 0.3) "Obiettivo raggiunto! 🎉" else if (diff > 0) "Ora ${"%.1f".format(Locale.ITALIAN, w)} kg: mancano ${"%.1f".format(Locale.ITALIAN, diff)} kg" else "Ora ${"%.1f".format(Locale.ITALIAN, w)} kg: ne mancano ${"%.1f".format(Locale.ITALIAN, -diff)} da prendere"
            } else "Tocca per scriverlo", onClick = {
                val et = field("Peso obiettivo (kg)", goal.takeIf { it > 0 }?.let { "%.1f".format(Locale.ITALIAN, it) }, number = true)
                MaterialAlertDialogBuilder(this).setTitle("Peso obiettivo").setView(form(et)).setPositiveButton("Salva") { _, _ ->
                    val v = Calc.num(et.text.toString()); if (v == null || v !in 30.0..300.0) toast("Peso non valido") else { prefs.edit().putFloat("goal_weight", v.toFloat()).apply(); render() }
                }.setNeutralButton("Togli") { _, _ -> prefs.edit().remove("goal_weight").apply(); render() }.setNegativeButton("Annulla", null).show()
            })
            if (goal > 0) { val (lo, hi) = Nutrition.healthyRange(Food.profile(this).heightCm.takeIf { it > 0 } ?: 170.0); if (Food.profile(this).heightCm > 0 && (goal < lo - 0.5 || goal > hi + 0.5)) hintLabel(body, "Attenzione: questo obiettivo è fuori dal peso sano per la tua altezza (%.0f–%.0f kg). Parlane col medico.".format(Locale.ITALIAN, lo, hi)) }
            chart("peso", "Peso", 0xFF2563EB.toInt(), goal.takeIf { it > 0 })
            chart("vita", "Vita", 0xFFF59E0B.toInt()); chart("fianchi", "Fianchi", 0xFF8B5CF6.toInt())

            sectionLabel(body, "PROMEMORIA SETTIMANALE")
            val rid = prefs.getInt("body_rem", 0); val dow = prefs.getInt("body_dow", Calendar.MONDAY); val t = prefs.getString("body_time", "08:00") ?: "08:00"
            val days = mapOf(Calendar.MONDAY to "lunedì", Calendar.TUESDAY to "martedì", Calendar.WEDNESDAY to "mercoledì", Calendar.THURSDAY to "giovedì", Calendar.FRIDAY to "venerdì", Calendar.SATURDAY to "sabato", Calendar.SUNDAY to "domenica")
            listRow(body, if (rid != 0) "🔔 Ogni ${days[dow]} alle $t" else "🔕 Nessun promemoria", if (rid != 0) "«Pesati e misura vita e fianchi». Tocca per cambiare o spegnere" else "Una notifica a settimana per ricordarti le misure", onClick = {
                MaterialAlertDialogBuilder(this).setItems(arrayOf("Scegli giorno e ora", "Spegni")) { _, i ->
                    if (i == 1) { if (rid != 0) Reminders.remove(this, rid); prefs.edit().remove("body_rem").apply(); render() }
                    else MaterialAlertDialogBuilder(this).setTitle("Che giorno?").setItems(days.values.toTypedArray()) { _, di ->
                        val chosen = days.keys.toList()[di]
                        val (hh, mm) = t.split(':').map { it.toInt() }
                        TimePickerDialog(this, { _, h2, m2 ->
                            if (rid != 0) Reminders.remove(this, rid)
                            ensureNotificationPermission()
                            val tt = "%02d:%02d".format(h2, m2)
                            val r = Reminders.add(this, "⚖️ Pesati e misura vita e fianchi", nextWeekday(chosen, tt), "week")
                            prefs.edit().putInt("body_rem", r.id).putInt("body_dow", chosen).putString("body_time", tt).apply(); render()
                        }, hh, mm, true).show()
                    }.show()
                }.show()
            })
            listRow(body, "➕ Aggiungi una misura", "Peso, vita, fianchi, pressione…", onClick = { healthType = "peso"; showHealthPage() })
            hintLabel(body, "Pesati sempre alla stessa ora, meglio al mattino. Conta l'andamento di settimane, non il singolo giorno.")
        }
        render()
    }
}

// ======================= Symptom journal =======================

internal fun MainActivity.showSymptomsPage() {
    openPage("Diario dei sintomi", "Aggiungi", { dialog -> addSymptom { dialog.dismiss(); showSymptomsPage() } }) { _, body, dialog ->
        val list = Symptoms.load(this)
        if (list.isEmpty()) { emptyLabel(body, "Nessun sintomo annotato.\nQuando hai un disturbo, tocca «Aggiungi»: nel referto per il medico compare con data e intensità."); return@openPage }
        val f = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
        list.forEach { s ->
            listRow(body, "📝 ${s.what} · ${s.level}/10", f.format(Date(s.time)).replaceFirstChar { it.uppercase() } + if (s.note.isNotBlank()) " · ${s.note}" else "", onClick = {},
                onRemove = { Symptoms.remove(this, s.id); dialog.dismiss(); showSymptomsPage() })
        }
        hintLabel(body, "Serve a ricordare e a spiegare al medico: non è una diagnosi. Per dolore al petto forte, difficoltà a respirare o sintomi improvvisi chiama subito il 112.")
    }
}

private fun MainActivity.addSymptom(done: () -> Unit) {
    val what = field("Cosa senti (es. mal di testa)", null)
    val note = field("Nota (facoltativa)", null)
    val level = field("Intensità da 1 a 10", "5", number = true)
    val box = form(what, level, note)
    val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "Scegli tra i più comuni ▾"; isAllCaps = false
        setOnClickListener { MaterialAlertDialogBuilder(this@addSymptom).setItems(Symptoms.COMMON.toTypedArray()) { _, i -> what.setText(Symptoms.COMMON[i]) }.show() }
    })
    MaterialAlertDialogBuilder(this).setTitle("Nuovo sintomo").setView(box).setPositiveButton("Salva") { _, _ ->
        val lv = level.text.toString().toIntOrNull()
        if (what.text.toString().isBlank() || lv == null || lv !in 1..10) return@setPositiveButton toast("Scrivi cosa senti e un'intensità da 1 a 10")
        Symptoms.add(this, Symptom(Symptoms.newId(), what.text.toString().trim(), lv, note.text.toString().trim(), System.currentTimeMillis())); done()
    }.setNegativeButton("Annulla", null).show()
}
