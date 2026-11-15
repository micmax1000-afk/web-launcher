package com.weblauncher.app

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ======================= Common pieces =======================

/** A small rounded box with a label, a value and an optional line under it. */
internal fun MainActivity.statTile(label: String, value: String, sub: String? = null, color: Int? = null): LinearLayout {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(4), dp(11), dp(4), dp(11))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        addView(TextView(this@statTile).apply { text = label; textSize = 12f; setTextColor(palette.textMuted); gravity = Gravity.CENTER })
        addView(TextView(this@statTile).apply {
            text = value; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(color ?: palette.text); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0); maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        })
        if (sub != null) addView(TextView(this@statTile).apply { text = sub; textSize = 11f; setTextColor(palette.textMuted); gravity = Gravity.CENTER; maxLines = 1 })
    }
}

internal fun MainActivity.statRow(vararg tiles: View): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tiles.forEach { addView(it) } }

internal fun MainActivity.progressBar(fraction: Double, color: Int): View {
    val d = resources.displayMetrics.density
    val palette = AppRepository.palette(this)
    return android.widget.FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, (10 * d).toInt()).apply { setMargins((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt()) }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 5 * d; setColor(palette.stroke) }
        addView(View(this@progressBar).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 5 * d; setColor(color) }
            layoutParams = android.widget.FrameLayout.LayoutParams(0, -1)
        })
        post { (getChildAt(0).layoutParams as android.widget.FrameLayout.LayoutParams).width = (width * fraction.coerceIn(0.0, 1.0)).toInt(); getChildAt(0).requestLayout() }
    }
}

// ======================= Health Connect (any watch or fitness app) =======================

/** Reads Health Connect in the background and stores the result; [then] runs on the main thread. */
internal fun MainActivity.syncHealthConnect(then: (() -> Unit)? = null) {
    if (Hc.status(this) != Hc.SDK_AVAILABLE) { then?.invoke(); return }
    io.execute {
        Hc.refreshBlocking(this)
        runOnUiThread { then?.invoke() }
    }
}

/** Opens the watch app that is installed, or a short list if there are several. */
internal fun MainActivity.openWatchAppRow(body: android.widget.LinearLayout) {
    val apps = listOf(
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.google.android.apps.fitness" to "Fitbit",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.huawei.health" to "Huawei Salute",
        "fi.polar.polarflow" to "Polar Flow"
    )
    val installed = apps.mapNotNull { (pkg, name) ->
        packageManager.getLaunchIntentForPackage(pkg)?.let { name to it }
    }
    when (installed.size) {
        0 -> listRow(body, "Apri l'app dell'orologio", "Samsung Health, Zepp, Garmin, Mi Fitness… poi attiva Health Connect", onClick = {
            toast("Non trovo l'app dell'orologio: aprila dal telefono")
        })
        1 -> listRow(body, "Apri ${installed[0].first}", "Qui si attiva la condivisione con Health Connect", onClick = {
            runCatching { startActivity(installed[0].second) }.onFailure { toast("Non riesco ad aprirla") }
        })
        else -> listRow(body, "Apri l'app dell'orologio", installed.joinToString { it.first }, onClick = {
            val names = installed.map { it.first }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle("Quale orologio?").setItems(names) { _, w ->
                runCatching { startActivity(installed[w].second) }
            }.show()
        })
    }
}

internal fun MainActivity.showHealthConnectPage() {
    openPage("Orologio e app fitness") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        fun render() {
            body.removeAllViews()
            val status = Hc.status(this)
            when (status) {
                Hc.SDK_AVAILABLE -> {}
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    hintLabel(body, "Health Connect va installato o aggiornato dal Play Store per poter leggere i dati.")
                    listRow(body, "⬇️ Installa o aggiorna Health Connect", null, onClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Hc.PACKAGE}&url=healthconnect%3A%2F%2Fonboarding"))) }
                            .onFailure { openInBrowser("https://play.google.com/store/apps/details?id=${Hc.PACKAGE}") }
                    })
                    return
                }
                else -> { hintLabel(body, "Questo telefono non supporta Health Connect, quindi i dati dell'orologio non si possono leggere da qui."); return }
            }
            val snap = Hc.cached(this)
            sectionLabel(body, "COLLEGAMENTO")
            listRow(body, "1. Consenti la lettura", "Passi, battito, sonno, peso, allenamenti", onClick = { hcPermission.launch(Hc.PERMISSIONS) })
            openWatchAppRow(body)
            listRow(body, "2. Aggiorna ora", if (snap != null) "Ultima lettura ${NewsAdapter.ago(snap.time)}" else "Mai letto", onClick = {
                toast("Leggo i dati…"); syncHealthConnect { render(); wellnessRefresh?.invoke(); toast("Dati aggiornati") }
            })
            listRow(body, "⚙️ Apri Health Connect", "Qui vedi quali app scrivono i dati e puoi revocare i permessi", onClick = {
                runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }.onFailure { toast("Non riesco ad aprire Health Connect") }
            })
            if (snap != null) {
                sectionLabel(body, "DA CHI ARRIVANO I DATI")
                if (snap.sources.isEmpty()) hintLabel(body, "Nessun dato ricevuto negli ultimi 30 giorni. Controlla che nell'app del tuo orologio la condivisione con Health Connect sia attiva (vedi sotto).")
                else {
                    val who = snap.sources.map { Hc.sourceName(this, it) }.distinct()
                    val sub = if (who == listOf("Telefono")) "Solo il telefono: l'orologio non sta ancora scrivendo"
                    else who.joinToString()
                    listRow(body, "🧭 Le mie sorgenti", sub, onClick = { showSourcesPage() })
                }

                sectionLabel(body, "ULTIMA LETTURA")
                val today = Hc.today(snap)
                val night = Hc.lastNight(snap)
                val rows = buildList {
                    today?.takeIf { it.steps > 0 }?.let { add("👟 Passi oggi" to String.format(Locale.ITALIAN, "%,d", it.steps)) }
                    today?.takeIf { it.distanceM > 0 }?.let { add("📍 Distanza oggi" to Fitness.dist(it.distanceM)) }
                    today?.takeIf { it.activeKcal > 0 }?.let { add("🔥 Calorie attive oggi" to "${it.activeKcal.toInt()} kcal") }
                    night?.let { add("😴 Sonno (${it.date})" to Hc.duration(it.sleepMin) + if (it.deepMin > 0) " · profondo ${Hc.duration(it.deepMin)}" else "") }
                    Hc.last(snap.days, 7).lastOrNull { it.restHr > 0 }?.let { add("💓 Battito a riposo" to "${it.restHr} bpm") }
                    today?.takeIf { it.hrAvg > 0 }?.let { add("💓 Battito oggi" to "media ${it.hrAvg} · min ${it.hrMin} · max ${it.hrMax}") }
                    if (snap.spo2 > 0) add("🫁 Ossigeno nel sangue" to "%.0f%% · ${NewsAdapter.ago(snap.spo2Time)}".format(snap.spo2))
                    if (snap.systolic > 0) add("❤️ Pressione" to "${snap.systolic}/${snap.diastolic} · ${NewsAdapter.ago(snap.bpTime)}")
                    if (snap.glucoseMgDl > 0) add("🩸 Glicemia" to "${snap.glucoseMgDl.toInt()} mg/dL · ${NewsAdapter.ago(snap.glucoseTime)}")
                    snap.exercises.firstOrNull()?.let { add("🏅 Ultimo allenamento" to "${it.type} · ${SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(Date(it.start))} · ${(it.end - it.start) / 60000} min") }
                }
                if (rows.isEmpty()) hintLabel(body, "Ancora nessun dato: tocca «Scegli i dati da leggere» e poi «Aggiorna ora».")
                rows.forEach { (l, v) -> listRow(body, l, v, onClick = {}) }
            }
            sectionLabel(body, "L'OROLOGIO")
            hintLabel(body, "Web Launcher non si accoppia all'orologio. Lo fa la sua app.\n" +
                "Apri quella app, cerca «Health Connect» o «Condividi i dati» e attiva passi, battito, sonno e allenamenti. Poi torna qui e tocca «Aggiorna ora».")
            hintLabel(body, "I dati restano sul telefono: Web Launcher li legge soltanto.")
        }
        render()
        hcRefresh = { render() }
        dialog.setOnDismissListener { hcRefresh = null }
        syncHealthConnect { render() }
    }
}

// ======================= Wellness hub =======================

internal fun MainActivity.showWellnessHub() {
    openPage("Benessere") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        fun render() {
            body.removeAllViews()
            val snap = Hc.cached(this)
            val hcOn = Hc.status(this) == Hc.SDK_AVAILABLE
            val today = Hc.today(snap)
            val day = Food.dayKey(System.currentTimeMillis())
            val eaten = Food.totals(Food.day(Food.load(this), day))
            val target = Food.targets(this)
            val steps = today?.steps?.takeIf { it > 0 } ?: Steps.today(this).toLong()
            val goalSteps = Steps.goal(this)
            val water = Food.waterMl(this); val waterGoal = Food.waterGoal(this)
            val night = Hc.lastNight(snap)
            val restHr = Hc.last(snap?.days.orEmpty(), 7).lastOrNull { it.restHr > 0 }?.restHr ?: 0L
            val times = Fitness.allTimes(this); val goal = Fitness.weeklyGoal(this); val week = Fitness.weekCount(times)

            sectionLabel(body, "OGGI")
            body.addView(statRow(
                statTile("Passi", String.format(Locale.ITALIAN, "%,d", steps), "obiettivo ${String.format(Locale.ITALIAN, "%,d", goalSteps)}", if (steps >= goalSteps) 0xFF16A34A.toInt() else null).apply { setOnClickListener { showStepsPage() } },
                statTile("Acqua", "%.1f L".format(Locale.ITALIAN, water / 1000.0), "di %.1f".format(Locale.ITALIAN, waterGoal / 1000.0), if (water >= waterGoal) 0xFF16A34A.toInt() else null).apply { setOnClickListener { showWaterPage() } },
                statTile("Calorie", "${eaten.kcal.toInt()}", target?.let { "di ${it.kcal}" } ?: "mangiate").apply { setOnClickListener { showFoodDiary() } }))
            body.addView(statRow(
                statTile("Sonno", night?.let { Hc.duration(it.sleepMin) } ?: "—", if (night != null) "ultima notte" else "dall'orologio").apply { setOnClickListener { showSleepPage() } },
                statTile("Battito a riposo", if (restHr > 0) "$restHr bpm" else "—").apply { setOnClickListener { showHrPage() } },
                statTile("Allenamenti", "$week / $goal", "questa settimana", if (week >= goal) 0xFF16A34A.toInt() else null).apply { setOnClickListener { showWorkoutHistory() } }))

            listRow(body, "⌚ Orologio e app fitness",
                if (!hcOn) "Non disponibile su questo telefono" else if (snap == null) "Samsung, Fitbit, Garmin, Amazfit… tocca per collegare" else "Collegato · ultima lettura ${NewsAdapter.ago(snap.time)}",
                onClick = { showHealthConnectPage() })
            if (snap != null && snap.sources.isNotEmpty()) listRow(body, "🧭 Le mie sorgenti", "Da quale app arriva ogni dato", onClick = { showSourcesPage() })

            sectionLabel(body, "SEZIONI")
            listRow(body, "🍽 Alimentazione", "Diario, acqua, barcode e foto", onClick = { showWellnessFood() })
            listRow(body, "🏃 Allenamento", "GPS, schede, Tabata e fascia cardio", onClick = { showWellnessTrain() })
            listRow(body, "⚖️ Corpo", "Peso, sonno, passi e misure", onClick = { showWellnessBody() })
            listRow(body, "💊 Salute", "Farmaci, visite, sintomi e referto", onClick = { showWellnessCare() })
            hintLabel(body, "Le stime (calorie, fabbisogno, foto dei piatti) sono indicative e non sostituiscono il parere del medico o del nutrizionista.")
        }
        render()
        wellnessRefresh = { render() }
        dialog.setOnDismissListener { wellnessRefresh = null }
        if (Hc.status(this) == Hc.SDK_AVAILABLE && Hc.cached(this) != null) syncHealthConnect { render() }   // refresh quietly
    }
}

internal fun MainActivity.showWellnessFood() {
    openPage("Alimentazione") { _, body, _ ->
        val day = Food.dayKey(System.currentTimeMillis())
        val eaten = Food.totals(Food.day(Food.load(this), day))
        val target = Food.targets(this)
        val water = Food.waterMl(this); val waterGoal = Food.waterGoal(this)
        listRow(body, "🔍 Scansiona un prodotto", "Codice a barre: calorie, Nutri-Score, allergeni e additivi", onClick = { scanFoodBarcode() })
        listRow(body, "📸 Foto di un piatto", "L'AI stima cosa c'è e le calorie (indicative)", onClick = { foodFromPhoto() })
        listRow(body, "🍽 Diario alimentare", "Oggi ${eaten.kcal.toInt()} kcal" + (target?.let { " su ${it.kcal}" } ?: ""), onClick = { showFoodDiary() })
        listRow(body, "💧 Acqua", "Oggi ${water} ml su $waterGoal", onClick = { showWaterPage() })
        listRow(body, "🧮 Fabbisogno di calorie", if (target != null) "Il tuo obiettivo: ${target.kcal} kcal al giorno" else "Calcolo da peso, altezza, età e attività", onClick = { showCalorieCalculator() })
    }
}

internal fun MainActivity.showWellnessTrain() {
    openPage("Allenamento") { _, body, _ ->
        val times = Fitness.allTimes(this); val goal = Fitness.weeklyGoal(this); val week = Fitness.weekCount(times)
        val streak = Fitness.streakWeeks(times, goal)
        listRow(body, "▶️ Registra un allenamento", "Corsa, camminata o bici con il GPS, anche a schermo spento", onClick = { showWorkoutRecorder() })
        listRow(body, "⏱ Tabata", "Intervalli con segnali acustici", onClick = { showTabata() })
        listRow(body, "📋 Schede di esercizi", "Serie, ripetizioni e pause con il timer", onClick = { showPlansPage() })
        listRow(body, "📈 Storico", "Gli allenamenti fatti, con percorso e dati", onClick = { showWorkoutHistory() })
        listRow(body, "❤️‍🔥 Fascia cardio", if (HrBand.current() > 0) "Collegata: ${HrBand.current()} bpm" else HrBand.savedName(this)?.let { "Salvata: $it" } ?: "Battito in diretta, zone, recupero", onClick = { showHrPage() })
        listRow(body, "🎯 Obiettivo settimanale", "$week su $goal questa settimana" + if (streak > 0) " · serie di $streak settiman${if (streak == 1) "a" else "e"} 🔥" else "", onClick = { goalDialog() })
    }
}

internal fun MainActivity.showWellnessBody() {
    openPage("Corpo") { _, body, _ ->
        val snap = Hc.cached(this)
        val night = Hc.lastNight(snap)
        listRow(body, "⚖️ Peso, BMI e misure", Food.lastWeight(this).takeIf { it > 0 }?.let { w ->
            val h = Food.profile(this).heightCm
            "%.1f kg".format(Locale.ITALIAN, w) + if (h > 0) " · BMI %.1f (${Nutrition.bmiLabel(Nutrition.bmi(w, h))})".format(Locale.ITALIAN, Nutrition.bmi(w, h)) else ""
        } ?: "Grafici di peso, vita e fianchi", onClick = { healthType = "peso"; showHealthPage() })
        listRow(body, "📏 Obiettivo e promemoria misure", "Peso obiettivo, grafici di vita e fianchi, pesata settimanale", onClick = { showBodyPage() })
        listRow(body, "😴 Sonno", night?.let { "Ultima notte ${Hc.duration(it.sleepMin)}" } ?: "Dall'orologio o a mano, con promemoria per andare a letto", onClick = { showSleepPage() })
        listRow(body, "❤️ Pressione, glicemia e altre misure", "Il diario con i grafici", onClick = { healthType = "pressione"; showHealthPage() })
        listRow(body, "👟 Passi", null, onClick = { showStepsPage() })
        listRow(body, "🩺 Misuratori Bluetooth", BleHealth.savedSummary(this) ?: "Pressione, bilancia, termometro, saturimetro, glucometro", onClick = { showBleDevicesPage() })
    }
}

internal fun MainActivity.showWellnessCare() {
    openPage("Salute") { _, body, _ ->
        val low = Meds.lowStock(this)
        listRow(body, "💊 Farmaci", if (low.isNotEmpty()) "⚠️ Scorte in esaurimento: ${low.joinToString { it.name }}" else "Promemoria e scorte", onClick = { showMedsPage() })
        listRow(body, "📝 Diario dei sintomi", Symptoms.load(this).firstOrNull()?.let { "Ultimo: ${it.what} (${it.level}/10)" } ?: "Mal di testa, dolori… con data e intensità", onClick = { showSymptomsPage() })
        listRow(body, "🩺 Visite e analisi", Visits.load(this).firstOrNull { it.time > System.currentTimeMillis() }?.let { "Prossima: ${it.title} · ${SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(Date(it.time))}" } ?: "Appuntamenti con promemoria", onClick = { showVisitsPage() })
        listRow(body, "🆘 Scheda d'emergenza", if (Emergency.isEmpty(Emergency.load(this))) "Gruppo sanguigno, allergie, contatti" else "Pronta" + if (Emergency.onLockScreen(this)) " · visibile a telefono bloccato" else "", onClick = { showEmergencyPage() })
        listRow(body, "📄 Report per il medico", "PDF con pressione, peso, glicemia e attività dell'ultimo mese", onClick = { showReportPage() })
    }
}

internal fun MainActivity.goalDialog() {
    val options = (1..7).map { "$it allenament${if (it == 1) "o" else "i"} a settimana" }.toTypedArray()
    MaterialAlertDialogBuilder(this).setTitle("Obiettivo settimanale")
        .setSingleChoiceItems(options, Fitness.weeklyGoal(this) - 1) { dl, w -> Fitness.setWeeklyGoal(this, w + 1); dl.dismiss(); wellnessRefresh?.invoke() }
        .setNegativeButton("Chiudi", null).show()
}

/** Which app writes each kind of data into Health Connect, with a warning when two apps write the same thing. */
internal fun MainActivity.showSourcesPage() {
    openPage("Le mie sorgenti") { _, body, _ ->
        val snap = Hc.cached(this)
        val map = snap?.bySource.orEmpty()
        if (map.isEmpty()) {
            hintLabel(body, "Ancora nessuna informazione: apri «Orologio e app fitness» e tocca «Aggiorna ora».")
            return@openPage
        }
        val names = map.mapValues { (_, pkgs) -> pkgs.map { Hc.sourceName(this, it) }.distinct() }
        val doubled = names.filterValues { it.size > 1 }
        if (doubled.isNotEmpty()) {
            sectionLabel(body, "DUE APP PER GLI STESSI DATI")
            hintLabel(body, "${doubled.keys.joinToString { it.lowercase(Locale.ITALIAN) }} arrivano da più app. " +
                "Se il numero è il doppio, apri Health Connect e metti in cima l'app dell'orologio.")
        }
        val onlyPhone = names.values.flatten().distinct() == listOf("Telefono")
        if (onlyPhone) hintLabel(body, "Questi dati li scrive il telefono. L'orologio non è ancora collegato.")
        sectionLabel(body, "CHI SCRIVE COSA")
        names.forEach { (label, who) ->
            listRow(body, (if (who.size > 1) "⚠️ " else "") + label, who.joinToString(), onClick = {})
        }
        listRow(body, "⚙️ Apri Health Connect", "Per cambiare le priorità o togliere un'app", onClick = {
            runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }.onFailure { toast("Non riesco ad aprire Health Connect") }
        })
        hintLabel(body, "I misuratori Bluetooth collegati qui (pressione, bilancia…) salvano nel tuo diario della salute, non in Health Connect.")
    }
}
