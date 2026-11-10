package com.weblauncher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Locale

/** Draws the route of a workout, fitted into the view. */
class RouteView(c: android.content.Context) : View(c) {
    var pts: List<Pt> = emptyList(); var lineColor = 0xFF2563EB.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        if (pts.size < 2) return
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }; val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val k = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * k).coerceAtLeast(1e-6); val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = 24f; val scale = minOf((width - 2 * pad) / w, (height - 2 * pad) / h).toFloat()
        val ox = (width - w * scale) / 2f; val oy = (height - h * scale) / 2f
        fun x(lon: Double) = (ox + (lon - minLon) * k * scale).toFloat()
        fun y(lat: Double) = (oy + (maxLat - lat) * scale).toFloat()
        val path = Path(); pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(x(q.lon), y(q.lat)) else path.lineTo(x(q.lon), y(q.lat)) }
        p.color = lineColor; canvas.drawPath(path, p)
        dot.color = 0xFF16A34A.toInt(); canvas.drawCircle(x(pts.first().lon), y(pts.first().lat), 14f, dot)
        dot.color = 0xFFDC2626.toInt(); canvas.drawCircle(x(pts.last().lon), y(pts.last().lat), 14f, dot)
    }
}

private fun MainActivity.hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

// ======================= GPS recorder =======================

internal fun MainActivity.showWorkoutRecorder() {
    openPage("Allenamento") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var kind = WorkoutState.kind
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var render: () -> Unit
        val tick = object : Runnable { override fun run() { if (WorkoutState.running) { render(); h.postDelayed(this, 1000) } } }
        render = {
            body.removeAllViews()
            if (WorkoutState.running) {
                val t = WorkoutState.track
                body.addView(TextView(this).apply {
                    text = Fitness.clock(WorkoutState.elapsedMs()); textSize = 52f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(palette.text); setPadding(0, dp(16), 0, dp(4))
                })
                body.addView(statRow(statTile("Distanza", Fitness.dist(t.distanceM)), statTile("Passo", Fitness.pace(t.avgKmh()), "al km"), statTile("Velocità", "%.1f".format(Locale.ITALIAN, t.avgKmh()), "km/h")))
                val fixAge = if (WorkoutState.lastFixAt == 0L) -1 else (System.currentTimeMillis() - WorkoutState.lastFixAt) / 1000
                hintLabel(body, if (fixAge < 0) "📡 Cerco il segnale GPS… all'aperto è più veloce." else if (fixAge > 20) "📡 Segnale GPS debole da $fixAge secondi." else "📡 GPS ok")
                body.addView(RouteView(this).apply { pts = t.points.toList(); layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) } })
                body.addView(MaterialButton(this).apply {
                    text = "⏹ Termina e salva"; isAllCaps = false; textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }
                    setOnClickListener {
                        startService(Intent(this@showWorkoutRecorder, WorkoutService::class.java).setAction(WorkoutService.ACTION_STOP))
                        h.postDelayed({
                            val id = WorkoutState.savedId
                            if (id != null) { dialog.dismiss(); wellnessRefresh?.invoke(); Fitness.workouts(this@showWorkoutRecorder).firstOrNull { it.id == id }?.let { showWorkoutDetail(it) } }
                            else { toast("Troppo poca strada: allenamento non salvato"); render() }
                        }, 700)
                    }
                })
                hintLabel(body, "Puoi spegnere lo schermo o usare altre app: il tracciato continua. La notifica «Allenamento in corso» deve restare.")
            } else {
                sectionLabel(body, "COSA VUOI FARE")
                Fitness.KINDS.forEach { (k, label) ->
                    listRow(body, (if (k == kind) "✅ " else "") + label, null, onClick = { kind = k; render() })
                }
                body.addView(MaterialButton(this).apply {
                    text = "▶ Inizia"; isAllCaps = false; textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(12), dp(14), dp(12), dp(8)) }
                    setOnClickListener {
                        locationDone = { ok ->
                            if (!ok) MaterialAlertDialogBuilder(this@showWorkoutRecorder).setTitle("Serve la posizione")
                                .setMessage("Per misurare la strada serve il permesso di posizione. Puoi darlo da Impostazioni di Android → App → Web Launcher → Autorizzazioni.").setPositiveButton("OK", null).show()
                            else { ensureNotificationPermission(); begin() }
                        }
                        if (hasLocation()) { ensureNotificationPermission(); begin() }
                        else workoutLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                })
                hintLabel(body, "Usa il GPS del telefono. Le calorie sono una stima dal tuo ultimo peso registrato (${Food.lastWeight(this@showWorkoutRecorder).takeIf { it > 0 }?.let { "%.0f kg".format(Locale.ITALIAN, it) } ?: "non c'è: uso 70 kg"}).")
                listRow(body, "📋 Storico allenamenti", null, onClick = { showWorkoutHistory() })
            }
        }
        // starts the service and keeps the screen refreshing
        fun beginImpl() {
            val i = Intent(this, WorkoutService::class.java).putExtra("kind", kind)
            androidx.core.content.ContextCompat.startForegroundService(this, i)
            h.postDelayed({ render(); h.post(tick) }, 400)
        }
        pendingBegin = ::beginImpl
        render()
        if (WorkoutState.running) h.post(tick)
        dialog.setOnDismissListener { h.removeCallbacksAndMessages(null); pendingBegin = null }
    }
}

private var pendingBegin: (() -> Unit)? = null
private fun begin() { pendingBegin?.invoke() }

// ======================= history =======================

internal fun MainActivity.showWorkoutHistory() {
    openPage("Storico allenamenti") { _, body, _ ->
        val list = Fitness.workouts(this)
        val f = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
        if (list.isEmpty()) { emptyLabel(body, "Ancora nessun allenamento con il GPS.\nTocca «Allenamento» per iniziare."); return@openPage }
        sectionLabel(body, "TOTALE: ${list.size} · ${Fitness.dist(list.sumOf { it.distanceM })}")
        list.forEach { w ->
            val label = Fitness.KINDS.firstOrNull { it.first == w.kind }?.second ?: w.kind
            listRow(body, label.replace(Regex("^(\\S+)\\s+"), "$1 ") + " · " + Fitness.dist(w.distanceM), f.format(java.util.Date(w.start)).replaceFirstChar { it.uppercase() } + " · " + Fitness.clock(w.movingMs) + " · ${w.kcal.toInt()} kcal",
                onClick = { showWorkoutDetail(w) })
        }
    }
}

internal fun MainActivity.showWorkoutDetail(w: Workout) {
    openPage("Allenamento", "Elimina", { dialog ->
        confirmClear("Eliminare questo allenamento?") { Fitness.deleteWorkout(this, w.id); dialog.dismiss(); wellnessRefresh?.invoke() }
    }) { _, body, _ ->
        val d = resources.displayMetrics.density
        val speed = if (w.movingMs > 0) w.distanceM / (w.movingMs / 1000.0) * 3.6 else 0.0
        body.addView(TextView(this).apply {
            text = (Fitness.KINDS.firstOrNull { it.first == w.kind }?.second ?: w.kind) + " · " + SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.ITALIAN).format(java.util.Date(w.start))
            textSize = 15f; setTextColor(AppRepository.palette(this@showWorkoutDetail).textMuted); setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        })
        body.addView(RouteView(this).apply { pts = Fitness.points(this@showWorkoutDetail, w.id); layoutParams = LinearLayout.LayoutParams(-1, (240 * d).toInt()).apply { setMargins((8 * d).toInt(), 0, (8 * d).toInt(), (8 * d).toInt()) } })
        body.addView(statRow(statTile("Distanza", Fitness.dist(w.distanceM)), statTile("Tempo", Fitness.clock(w.movingMs), "in movimento"), statTile("Calorie", "${w.kcal.toInt()}", "stimate")))
        body.addView(statRow(statTile("Passo medio", Fitness.pace(speed), "al km"), statTile("Velocità max", "%.1f".format(Locale.ITALIAN, w.maxKmh), "km/h"), statTile("Salita", "${w.gainM.toInt()} m")))
    }
}

// ======================= plans =======================

internal fun MainActivity.showPlansPage() {
    openPage("Schede di allenamento", "Nuova", { dialog -> editPlan(null) { dialog.dismiss(); showPlansPage() } }) { _, body, dialog ->
        Fitness.plans(this).forEach { p ->
            listRow(body, "🏋️ ${p.name}", "${p.exercises.size} esercizi · circa ${Fitness.planMinutes(p)} min", onClick = { runPlan(p) },
                onLong = { editPlan(p) { dialog.dismiss(); showPlansPage() } })
        }
        hintLabel(body, "Tocca una scheda per iniziare. Tieni premuto per modificarla o eliminarla. Cronometro del riposo, segnale sonoro e contatore delle ripetizioni sono inclusi.")
    }
}

private fun MainActivity.editPlan(p: Plan?, done: () -> Unit) {
    val name = field("Nome della scheda", p?.name)
    val ex = field("Un esercizio per riga", p?.exercises?.joinToString("\n") { Fitness.exerciseText(it) } ?: "Squat 3x12 riposo 45\nPlank 3x30s riposo 30").apply { minLines = 6; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
    val box = form(name, ex)
    box.addView(TextView(this).apply { text = "Scrivi: nome, serie x ripetizioni, riposo in secondi.\nEsempi: «Squat 3x12 riposo 45» · «Plank 3x30s riposo 30» (la s indica secondi) · «Panca 4x8 riposo 90 40kg»"; textSize = 12f; setPadding(0, 12, 0, 0) })
    val b = MaterialAlertDialogBuilder(this).setTitle(if (p == null) "Nuova scheda" else "Modifica scheda").setView(box)
        .setPositiveButton("Salva") { _, _ ->
            val list = Fitness.parseExercises(ex.text.toString())
            if (name.text.toString().isBlank() || list.isEmpty()) return@setPositiveButton toast("Serve un nome e almeno un esercizio, per esempio «Squat 3x12 riposo 45»")
            val cur = Fitness.plans(this).filter { it.id != p?.id }
            Fitness.savePlans(this, cur + Plan(p?.id ?: Fitness.newPlanId(), name.text.toString().trim(), list)); done()
        }.setNegativeButton("Annulla", null)
    if (p != null) b.setNeutralButton("Elimina") { _, _ -> confirmClear("Eliminare «${p.name}»?") { Fitness.savePlans(this, Fitness.plans(this).filter { it.id != p.id }); done() } }
    b.show()
}

/** Runs a plan: set by set, with a rep counter (tap), countdown for timed holds and a rest timer with a beep. */
internal fun MainActivity.runPlan(plan: Plan) {
    openPage(plan.name.take(24)) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val started = System.currentTimeMillis()
        var exI = 0; var setI = 0; var reps = 0
        var timer: CountDownTimer? = null
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        fun beep(long: Boolean = false) { runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, if (long) 500 else 150) } }
        lateinit var render: () -> Unit
        fun big(text: String, size: Float = 40f, color: Int = palette.text) = TextView(this).apply {
            this.text = text; textSize = size; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(color); setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        fun btn(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
            text = label; isAllCaps = false; textSize = 17f
            layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(dp(12), dp(6), dp(12), dp(6)) }; setOnClickListener { onClick() }
        }
        fun nextSet() {
            timer?.cancel()
            val e = plan.exercises[exI]
            if (setI + 1 < e.sets) { setI++ } else { exI++; setI = 0 }
            reps = 0; render()
        }
        fun rest(sec: Int) {
            body.removeAllViews()
            val e = plan.exercises.getOrNull(exI)
            val upNext = if (setI + 1 < (e?.sets ?: 0)) "${e!!.name} · serie ${setI + 2}/${e.sets}" else plan.exercises.getOrNull(exI + 1)?.name?.let { "Prossimo: $it" } ?: "Ultimo esercizio completato"
            val t = big("$sec", 72f, 0xFF2563EB.toInt()); body.addView(big("Riposo", 22f, palette.textMuted)); body.addView(t); body.addView(hintLabel2(upNext))
            body.addView(btn("Salta il riposo") { nextSet() })
            timer = object : CountDownTimer(sec * 1000L, 250) {
                override fun onTick(ms: Long) { t.text = ((ms + 999) / 1000).toString(); if (ms in 1..3000 && (ms + 999) / 1000 != t.tag) { t.tag = (ms + 999) / 1000; beep() } }
                override fun onFinish() { beep(true); nextSet() }
            }.start()
        }
        render = {
            body.removeAllViews()
            val e = plan.exercises.getOrNull(exI)
            if (e == null) {
                timer?.cancel()
                val min = ((System.currentTimeMillis() - started) / 60000).toInt().coerceAtLeast(1)
                val kcal = 5.0 * (Food.lastWeight(this).takeIf { it > 0 } ?: 70.0) * (min / 60.0)
                Fitness.addAct(this, Act(java.util.UUID.randomUUID().toString(), "plan", "🏋️ ${plan.name}", started, min, 0.0, kcal))
                wellnessRefresh?.invoke()
                body.addView(big("🎉", 64f)); body.addView(big("Scheda completata!", 24f))
                body.addView(statRow(statTile("Durata", "$min min"), statTile("Calorie", "~${kcal.toInt()}", "stima")))
                body.addView(btn("Chiudi") { dialog.dismiss() })
                beep(true)
            } else {
                body.addView(big("Esercizio ${exI + 1} di ${plan.exercises.size}", 14f, palette.textMuted))
                body.addView(big(e.name, 26f))
                body.addView(big("Serie ${setI + 1} di ${e.sets}", 18f, palette.textMuted))
                if (e.kg > 0) body.addView(big("${if (e.kg % 1.0 == 0.0) e.kg.toInt() else e.kg} kg", 16f, palette.textMuted))
                if (e.seconds > 0) {
                    val t = big("${e.seconds}", 80f, 0xFF16A34A.toInt()); body.addView(t)
                    body.addView(btn("▶ Avvia il cronometro") {
                        timer?.cancel()
                        timer = object : CountDownTimer(e.seconds * 1000L, 250) {
                            override fun onTick(ms: Long) { t.text = ((ms + 999) / 1000).toString() }
                            override fun onFinish() { beep(true); if (e.restSec > 0 && (setI + 1 < e.sets || exI + 1 < plan.exercises.size)) rest(e.restSec) else nextSet() }
                        }.start()
                    })
                } else {
                    val counter = big("$reps / ${e.reps}", 72f, 0xFF16A34A.toInt()); body.addView(counter)
                    body.addView(btn("＋1 ripetizione (tocca)") {
                        reps++; counter.text = "$reps / ${e.reps}"; beep()
                        if (reps >= e.reps) { beep(true); if (e.restSec > 0 && (setI + 1 < e.sets || exI + 1 < plan.exercises.size)) rest(e.restSec) else nextSet() }
                    })
                    body.addView(btn("Serie fatta ✓") { if (e.restSec > 0 && (setI + 1 < e.sets || exI + 1 < plan.exercises.size)) rest(e.restSec) else nextSet() })
                }
                body.addView(btn("Salta esercizio") { timer?.cancel(); exI++; setI = 0; reps = 0; render() })
            }
        }
        render()
        dialog.setOnDismissListener { timer?.cancel(); tone?.release() }
    }
}

private fun MainActivity.hintLabel2(text: String) = TextView(this).apply { this.text = text; textSize = 15f; gravity = Gravity.CENTER; setTextColor(AppRepository.palette(this@hintLabel2).textMuted); setPadding(8, 8, 8, 16) }
