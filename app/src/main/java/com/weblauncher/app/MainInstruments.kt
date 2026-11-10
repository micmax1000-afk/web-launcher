package com.weblauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.LOCATION_SERVICE
import android.content.Context.MODE_PRIVATE
import android.content.Context.SENSOR_SERVICE
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ======================= Measuring tools hub =======================

private fun MainActivity.withPermissions(vararg perms: String, action: () -> Unit) {
    if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) { action(); return }
    pendingPermAction = action; toolPermission.launch(arrayOf(*perms))
}

private fun MainActivity.big(body: LinearLayout, size: Float = 56f): TextView = TextView(this).apply {
    textSize = size; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
    setTextColor(AppRepository.palette(this@big).text); setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    body.addView(this)
}
private fun MainActivity.small(body: LinearLayout, size: Float = 15f): TextView = TextView(this).apply {
    textSize = size; gravity = Gravity.CENTER; setTextColor(AppRepository.palette(this@small).textMuted)
    val p = (8 * resources.displayMetrics.density).toInt(); setPadding(p * 2, p / 2, p * 2, p)
    body.addView(this)
}
private fun MainActivity.button(body: LinearLayout, label: String, action: (MaterialButton) -> Unit): MaterialButton =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; isAllCaps = false
        val m = (8 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(m * 2, m, m * 2, 0) }
        setOnClickListener { action(this) }
        body.addView(this)
    }
private fun httpGet(url: String): String {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 15000
    c.setRequestProperty("User-Agent", "WebLauncher")
    try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
}
/** The city chosen for the weather, as (lat, lon, name); asks to set it when missing. */
private fun MainActivity.myPlace(): Triple<Double, Double, String>? {
    val lat = weatherPrefs.getFloat("lat", Float.NaN); val lon = weatherPrefs.getFloat("lon", Float.NaN)
    if (lat.isNaN() || lon.isNaN()) { toast("Prima scegli la tua città nel Meteo"); setupWeather(); return null }
    return Triple(lat.toDouble(), lon.toDouble(), weatherPrefs.getString("city", "") ?: "")
}

internal fun MainActivity.showInstrumentsPage() {
    openPage("Misure e sensori") { _, body, _ ->
        sectionLabel(body, "MOVIMENTO E QUOTA")
        listRow(body, "⛰ Altimetro", "Quota e dislivello, con barometro o GPS", onClick = { showAltimeter() })
        listRow(body, "🚴 Tachimetro GPS", "Velocità, distanza e tempo", onClick = { withPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) { showSpeedometer() } })
        listRow(body, "⏱ Tabata", "Allenamento a intervalli con segnali acustici", onClick = { showTabata() })
        sectionLabel(body, "SENSORI")
        listRow(body, "🔊 Fonometro", "Rumore in decibel", onClick = { withPermissions(Manifest.permission.RECORD_AUDIO) { showSoundMeter() } })
        listRow(body, "🧲 Metal detector", "Trova chiodi, tubi e cavi nel muro", onClick = { showMetalDetector() })
        listRow(body, "🔍 Lente d'ingrandimento", "Fotocamera con zoom e luce", onClick = { withPermissions(Manifest.permission.CAMERA) { showMagnifier() } })
        listRow(body, "📏 Righello e goniometro", "Misure sullo schermo e inclinazione", onClick = { showRuler() })
        listRow(body, "🔦 Torcia, bussola e livella", null, onClick = { showUtilitiesPage() })
        sectionLabel(body, "CIELO E AMBIENTE")
        listRow(body, "🌅 Alba, tramonto e luna", "Orari di oggi e fasi lunari", onClick = { showSunMoon() })
        listRow(body, "🌬 Aria e pollini", "Qualità dell'aria nella tua città", onClick = { showAirQuality() })
        listRow(body, "🌍 Terremoti", "Ultime scosse in Italia (INGV)", onClick = { showQuakes() })
        sectionLabel(body, "CONTI")
        listRow(body, "🧾 Mancia e conto diviso", "Quanto a testa", onClick = { showSplitBill() })
    }
}

// ======================= Altimeter =======================

@SuppressLint("MissingPermission")
internal fun MainActivity.showAltimeter() {
    val sm = getSystemService(SENSOR_SERVICE) as SensorManager
    val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    val prefs = getSharedPreferences("wl_tools", MODE_PRIVATE)
    var listener: SensorEventListener? = null
    var gps: android.location.LocationListener? = null
    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    openPage("Altimetro") { _, body, dialog ->
        var p0 = prefs.getFloat("p0", 1013.25f).toDouble()
        var pressure = Double.NaN
        var alt = Double.NaN; var base = Double.NaN; var min = Double.NaN; var max = Double.NaN
        val value = big(body, 64f); value.text = "— m"
        val info = small(body)
        val delta = big(body, 24f); val range = small(body)
        fun show() {
            if (alt.isNaN()) return
            if (base.isNaN()) base = alt
            min = if (min.isNaN()) alt else minOf(min, alt); max = if (max.isNaN()) alt else maxOf(max, alt)
            value.text = "%.0f m".format(alt)
            delta.text = "Dislivello %+.0f m".format(alt - base)
            range.text = "min %.0f m · max %.0f m".format(min, max)
        }
        if (baro != null) {
            info.text = "Barometro del telefono · taratura %.1f hPa".format(p0)
            var smooth = Double.NaN
            listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    smooth = if (smooth.isNaN()) e.values[0].toDouble() else smooth * 0.9 + e.values[0] * 0.1
                    pressure = smooth; alt = Instruments.altitude(smooth, p0); show()
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, baro, SensorManager.SENSOR_DELAY_UI)
            button(body, "🌦 Tara con la pressione di oggi (meteo)") { _ ->
                val place = myPlace() ?: return@button
                toast("Leggo la pressione a ${place.third}…")
                io.execute {
                    val v = runCatching {
                        org.json.JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=${place.first}&longitude=${place.second}&current=pressure_msl"))
                            .getJSONObject("current").getDouble("pressure_msl")
                    }.getOrNull()
                    runOnUiThread {
                        if (v == null) { toast("Pressione non disponibile: controlla la connessione"); return@runOnUiThread }
                        p0 = v; prefs.edit().putFloat("p0", v.toFloat()).apply(); base = Double.NaN; min = Double.NaN; max = Double.NaN
                        info.text = "Barometro del telefono · taratura %.1f hPa".format(p0); toast("Tarato")
                    }
                }
            }
            button(body, "✏️ Tara con una quota che conosco") { _ ->
                val et = EditText(this).apply { hint = "Quota attuale in metri"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
                MaterialAlertDialogBuilder(this).setTitle("Quota attuale").setView(et)
                    .setPositiveButton("Tara") { _, _ ->
                        val known = et.text.toString().toDoubleOrNull()
                        if (known == null || pressure.isNaN()) { toast("Valore non valido"); return@setPositiveButton }
                        p0 = Instruments.seaLevelFor(pressure, known); prefs.edit().putFloat("p0", p0.toFloat()).apply()
                        base = Double.NaN; min = Double.NaN; max = Double.NaN
                        info.text = "Barometro del telefono · taratura %.1f hPa".format(p0)
                    }.setNegativeButton("Annulla", null).show()
            }
        } else {
            info.text = "Questo telefono non ha il barometro: uso il GPS (all'aperto, ±10-20 m)"
            withPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) {
                if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) { info.text = "Attiva la posizione (GPS) del telefono"; return@withPermissions }
                gps = android.location.LocationListener { l -> if (l.hasAltitude()) { alt = l.altitude; show(); info.text = "GPS · precisione ±%.0f m".format(l.accuracy) } }
                runCatching { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, gps!!) }
            }
        }
        button(body, "↺ Azzera il dislivello") { _ -> base = alt; min = alt; max = alt; show() }
        dialog.setOnDismissListener { listener?.let { sm.unregisterListener(it) }; gps?.let { runCatching { lm.removeUpdates(it) } } }
    }
}

// ======================= GPS speedometer =======================

@SuppressLint("MissingPermission")
internal fun MainActivity.showSpeedometer() {
    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) { toast("Attiva la posizione (GPS) del telefono"); return }
    var gps: android.location.LocationListener? = null
    openPage("Tachimetro") { _, body, dialog ->
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val speed = big(body, 84f); speed.text = "0"
        small(body).text = "km/h"
        val dist = big(body, 26f); val stats = small(body); val note = small(body, 13f)
        note.text = "Cerco i satelliti… (funziona all'aperto)"
        var last: android.location.Location? = null
        var meters = 0.0; var maxKmh = 0.0; var movingMs = 0L; var start = 0L
        fun show(kmh: Double) {
            speed.text = "%.0f".format(kmh)
            dist.text = if (meters < 1000) "%.0f m".format(meters) else "%.2f km".format(meters / 1000)
            val avg = if (movingMs > 0) meters / (movingMs / 1000.0) * 3.6 else 0.0
            val t = ((if (start > 0) System.currentTimeMillis() - start else 0L) / 1000).toInt()
            stats.text = "Tempo ${Instruments.fmtClock(t)} · media %.1f km/h · max %.0f km/h".format(avg, maxKmh)
        }
        show(0.0)
        gps = android.location.LocationListener { l ->
            if (l.accuracy > 30) { note.text = "Segnale debole (±%.0f m)".format(l.accuracy); return@LocationListener }
            note.text = "GPS · precisione ±%.0f m".format(l.accuracy)
            if (start == 0L) start = System.currentTimeMillis()
            val kmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
            last?.let { p ->
                val d = p.distanceTo(l).toDouble(); val dt = l.time - p.time
                if (kmh >= 1.5 && d < 200 && dt in 1..10_000) { meters += d; movingMs += dt }
            }
            last = l; maxKmh = maxOf(maxKmh, kmh); show(if (kmh < 1.5) 0.0 else kmh)
        }
        runCatching { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, gps!!) }
        button(body, "↺ Azzera") { _ -> meters = 0.0; maxKmh = 0.0; movingMs = 0; start = 0; last = null; show(0.0) }
        dialog.setOnDismissListener { gps?.let { runCatching { lm.removeUpdates(it) } } }
    }
}

// ======================= Tabata =======================

internal fun MainActivity.showTabata() {
    val prefs = getSharedPreferences("wl_tools", MODE_PRIVATE)
    var timer: android.os.CountDownTimer? = null
    var tone: android.media.ToneGenerator? = null
    openPage("Tabata") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        var work = prefs.getInt("tb_work", 20); var rest = prefs.getInt("tb_rest", 10)
        var rounds = prefs.getInt("tb_rounds", 8); var prep = prefs.getInt("tb_prep", 10)
        val phase = big(body, 30f); val clock = big(body, 96f); val round = small(body, 18f)
        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(setup)
        var running = false
        var startBtn: MaterialButton? = null
        fun idle() {
            running = false; timer?.cancel(); body.setBackgroundColor(0)
            phase.text = "Pronto"; phase.setTextColor(palette.text); clock.setTextColor(palette.text)
            val total = Instruments.tabata(prep, work, rest, rounds).sumOf { it.seconds }
            clock.text = Instruments.fmtClock(total); round.text = "$rounds round · lavoro ${work}s · riposo ${rest}s"
            setup.visibility = View.VISIBLE
            startBtn?.text = "▶ Inizia"
            dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        fun stepper(label: String, get: () -> Int, set: (Int) -> Unit, step: Int, min: Int, max: Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 6, 24, 6) }
            val tv = TextView(this).apply { textSize = 17f; setTextColor(palette.text); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            fun upd() { tv.text = "$label: ${get()}"; idle() }
            fun b(t: String, d: Int) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = t; textSize = 18f; minWidth = 0; minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(), -2).apply { marginStart = 12 }
                setOnClickListener { set((get() + d).coerceIn(min, max)); upd() }
            }
            row.addView(tv); row.addView(b("−", -step)); row.addView(b("+", step)); setup.addView(row); upd()
        }
        stepper("Lavoro (s)", { work }, { work = it; prefs.edit().putInt("tb_work", it).apply() }, 5, 5, 600)
        stepper("Riposo (s)", { rest }, { rest = it; prefs.edit().putInt("tb_rest", it).apply() }, 5, 0, 600)
        stepper("Round", { rounds }, { rounds = it; prefs.edit().putInt("tb_rounds", it).apply() }, 1, 1, 50)
        stepper("Preparazione (s)", { prep }, { prep = it; prefs.edit().putInt("tb_prep", it).apply() }, 5, 0, 60)
        fun beep(long: Boolean) = runCatching {
            if (tone == null) tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(if (long) android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else android.media.ToneGenerator.TONE_PROP_BEEP, if (long) 600 else 150)
        }
        fun run(list: List<Instruments.Phase>, i: Int) {
            if (i >= list.size) {
                beep(true); idle(); phase.text = "✅ Finito!"
                return
            }
            val ph = list[i]
            val color = when (ph.kind) { "lavoro" -> 0xFFDC2626.toInt(); "riposo" -> 0xFF16A34A.toInt(); else -> 0xFFF59E0B.toInt() }
            phase.text = when (ph.kind) { "lavoro" -> "🔥 LAVORO"; "riposo" -> "😮‍💨 RIPOSO"; else -> "Preparati" }
            phase.setTextColor(color); clock.setTextColor(color)
            round.text = if (ph.round > 0) "Round ${ph.round} di $rounds" else "Si parte tra poco"
            beep(true)
            timer = object : android.os.CountDownTimer(ph.seconds * 1000L, 200) {
                var lastShown = -1
                override fun onTick(ms: Long) {
                    val s = ((ms + 999) / 1000).toInt()
                    if (s != lastShown) { lastShown = s; clock.text = Instruments.fmtClock(s); if (s in 1..3) beep(false) }
                }
                override fun onFinish() { run(list, i + 1) }
            }.start()
        }
        startBtn = button(body, "▶ Inizia") { b ->
            if (running) { idle(); return@button }
            running = true; setup.visibility = View.GONE; b.text = "⏹ Ferma"
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            run(Instruments.tabata(prep, work, rest, rounds), 0)
        }
        small(body, 13f).text = "Lo schermo resta acceso durante l'allenamento. Tre bip brevi prima di ogni cambio, uno lungo al cambio."
        dialog.setOnDismissListener { timer?.cancel(); runCatching { tone?.release() } }
    }
}

// ======================= Sound meter =======================

@SuppressLint("MissingPermission")
internal fun MainActivity.showSoundMeter() {
    var active = true
    openPage("Fonometro") { _, body, dialog ->
        val value = big(body, 84f); value.text = "—"
        small(body).text = "dB"
        val label = big(body, 20f); val stats = small(body)
        small(body, 13f).text = "Valore indicativo: il microfono del telefono non è uno strumento tarato. L'audio non viene registrato né inviato."
        val rate = 44100
        val size = maxOf(android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT), 4096)
        Thread {
            val rec = runCatching { android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull()
            if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) { runOnUiThread { label.text = "Microfono non disponibile" }; return@Thread }
            val buf = ShortArray(size); var smooth = 0.0; var max = 0.0; var sum = 0.0; var n = 0; var lastUi = 0L
            runCatching {
                rec.startRecording()
                while (active) {
                    val read = rec.read(buf, 0, buf.size); if (read <= 0) continue
                    val db = Instruments.decibels(buf, read)
                    smooth = if (smooth == 0.0) db else smooth * 0.7 + db * 0.3
                    max = maxOf(max, db); sum += db; n++
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250) { lastUi = now; val s = smooth; val m = max; val avg = sum / n
                        runOnUiThread { value.text = "%.0f".format(s); label.text = Instruments.soundLabel(s); stats.text = "media %.0f dB · massimo %.0f dB".format(avg, m) } }
                }
            }
            runCatching { rec.stop() }; rec.release()
        }.start()
        dialog.setOnDismissListener { active = false }
    }
}

// ======================= Metal detector =======================

internal fun MainActivity.showMetalDetector() {
    val sm = getSystemService(SENSOR_SERVICE) as SensorManager
    val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: run { toast("Questo telefono non ha il magnetometro"); return }
    var listener: SensorEventListener? = null
    openPage("Metal detector") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val value = big(body, 72f); small(body).text = "µT (microtesla)"
        val label = big(body, 22f)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 200; val m = (24 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, (18 * resources.displayMetrics.density).toInt()).apply { setMargins(m, m / 2, m, m / 2) }
        }
        body.addView(bar)
        var base = Double.NaN
        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = kotlin.math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                if (base.isNaN()) base = v
                val diff = kotlin.math.abs(v - base)
                value.text = "%.0f".format(v); bar.progress = diff.toInt().coerceAtMost(200)
                val (t, c) = when { diff < 8 -> "Niente" to palette.textMuted; diff < 25 -> "Qualcosa vicino…" to 0xFFF59E0B.toInt(); else -> "🧲 Metallo!" to 0xFFDC2626.toInt() }
                label.text = t; label.setTextColor(c)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
        button(body, "↺ Tara qui (lontano dai metalli)") { _ -> base = Double.NaN }
        small(body, 13f).text = "Muovi lentamente la parte alta del telefono sul muro. Rileva ferro e acciaio (chiodi, tubi, armature) e cavi con corrente; non rame, alluminio o plastica. Il campo terrestre è circa 25-65 µT."
        dialog.setOnDismissListener { listener?.let { sm.unregisterListener(it) } }
    }
}

// ======================= Magnifier =======================

@Suppress("DEPRECATION")
internal fun MainActivity.showMagnifier() {
    var cam: android.hardware.Camera? = null
    openPage("Lente") { root, body, dialog ->
        (body.parent as? View)?.visibility = View.GONE
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tex = android.view.TextureView(this)
        root.addView(tex, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 12, 24, 24) }
        val zoom = android.widget.SeekBar(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        var light = false; var frozen = false
        val lightBtn = TextView(this).apply { text = "🔦"; textSize = 28f; setPadding(24, 0, 24, 0) }
        val freezeBtn = TextView(this).apply { text = "⏸"; textSize = 28f; setPadding(24, 0, 0, 0) }
        bar.addView(TextView(this).apply { text = "🔍"; textSize = 22f }); bar.addView(zoom); bar.addView(lightBtn); bar.addView(freezeBtn)
        root.addView(bar)
        fun params(f: (android.hardware.Camera.Parameters) -> Unit) = runCatching { cam?.let { c -> val p = c.parameters; f(p); c.parameters = p } }
        tex.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                runCatching {
                    val c = android.hardware.Camera.open(); cam = c
                    c.setDisplayOrientation(90)
                    val p = c.parameters
                    if (p.supportedFocusModes?.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) p.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    if (p.isZoomSupported) { zoom.max = p.maxZoom; zoom.progress = p.maxZoom / 3; p.zoom = p.maxZoom / 3 } else zoom.isEnabled = false
                    c.parameters = p
                    c.setPreviewTexture(st); c.startPreview()
                }.onFailure { toast("Fotocamera non disponibile") }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { runCatching { cam?.stopPreview(); cam?.release() }; cam = null; return true }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        zoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) params { it.zoom = v } }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        lightBtn.setOnClickListener { light = !light; params { it.flashMode = if (light) android.hardware.Camera.Parameters.FLASH_MODE_TORCH else android.hardware.Camera.Parameters.FLASH_MODE_OFF } }
        freezeBtn.setOnClickListener { frozen = !frozen; runCatching { if (frozen) cam?.stopPreview() else cam?.startPreview() }; freezeBtn.text = if (frozen) "▶" else "⏸" }
        tex.setOnClickListener { runCatching { cam?.autoFocus { _, _ -> } } }
        dialog.setOnDismissListener { runCatching { cam?.stopPreview(); cam?.release() }; cam = null }
    }
}

// ======================= Ruler & protractor =======================

private class RulerView(context: android.content.Context, val textColor: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val dm = resources.displayMetrics
        val pxMm = Instruments.pxPerMm(dm.ydpi)
        p.color = textColor; p.strokeWidth = dm.density * 1.2f; p.textSize = 13 * dm.density
        var mm = 0
        while (mm * pxMm < height) {
            val y = mm * pxMm
            val len = when { mm % 10 == 0 -> 34f; mm % 5 == 0 -> 22f; else -> 12f } * dm.density
            canvas.drawLine(0f, y, len, y, p)
            if (mm % 10 == 0 && mm > 0) canvas.drawText((mm / 10).toString(), len + 6 * dm.density, y + 5 * dm.density, p)
            mm++
        }
    }
}

internal fun MainActivity.showRuler() {
    val sm = getSystemService(SENSOR_SERVICE) as SensorManager
    var listener: SensorEventListener? = null
    openPage("Righello e goniometro") { root, body, dialog ->
        (body.parent as? View)?.visibility = View.GONE
        val palette = AppRepository.palette(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(RulerView(this, palette.text), LinearLayout.LayoutParams((90 * resources.displayMetrics.density).toInt(), -1))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        row.addView(side, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        small(side, 13f).text = "📏 Righello in centimetri sul bordo sinistro"
        val angle = big(side, 64f); val hint = small(side)
        hint.text = "📐 Goniometro: appoggia il lato lungo del telefono sulla superficie inclinata"
        var offset = 0.0; var current = 0.0
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { acc ->
            var sx = 0f; var sy = 0f
            listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    sx = sx * 0.85f + e.values[0] * 0.15f; sy = sy * 0.85f + e.values[1] * 0.15f
                    current = Instruments.tiltDegrees(sx, sy)
                    angle.text = "%.1f°".format(kotlin.math.abs(current - offset))
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        } ?: run { angle.text = "—" }
        button(side, "↺ Azzera l'angolo qui") { _ -> offset = current }
        dialog.setOnDismissListener { listener?.let { sm.unregisterListener(it) } }
    }
}

// ======================= Sun & moon =======================

internal fun MainActivity.showSunMoon() {
    val place = myPlace() ?: return
    openPage("Alba, tramonto e luna") { _, body, _ ->
        val hm = SimpleDateFormat("HH:mm", Locale.ITALIAN)
        val today = Calendar.getInstance()
        sectionLabel(body, "SOLE · ${place.third.uppercase(Locale.ITALIAN)}")
        val t = Instruments.sunTimes(place.first, place.second, today)
        if (t == null) small(body).text = "Oggi il sole non sorge o non tramonta a questa latitudine"
        else {
            big(body, 30f).text = "🌅 ${hm.format(t.first)}   🌇 ${hm.format(t.second)}"
            val mins = ((t.second - t.first) / 60000).toInt()
            val y = Instruments.sunTimes(place.first, place.second, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })
            val diff = y?.let { (((t.second - t.first) - (it.second - it.first)) / 1000).toInt() }
            small(body).text = "Luce: ${mins / 60} h ${mins % 60} min" + (diff?.let { " · ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it) / 60} min ${kotlin.math.abs(it) % 60} s rispetto a ieri" } ?: "")
        }
        sectionLabel(body, "PROSSIMI GIORNI")
        val df = SimpleDateFormat("EEE d MMM", Locale.ITALIAN)
        for (i in 1..6) {
            val d = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            Instruments.sunTimes(place.first, place.second, d)?.let { listRow(body, df.format(d.time).replaceFirstChar { c -> c.uppercase() }, "Alba ${hm.format(it.first)} · tramonto ${hm.format(it.second)}", onClick = {}) }
        }
        sectionLabel(body, "LUNA")
        val (age, lit) = Instruments.moon(System.currentTimeMillis())
        big(body, 26f).text = Instruments.moonName(age)
        val toFull = Instruments.daysToFullMoon(age)
        val fullDate = SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(java.util.Date(System.currentTimeMillis() + (toFull * 86_400_000L).toLong()))
        small(body).text = "Illuminata al ${(lit * 100).toInt()}% · prossima luna piena: $fullDate"
    }
}

// ======================= Air quality & pollen =======================

internal fun MainActivity.showAirQuality() {
    val place = myPlace() ?: return
    openPage("Aria e pollini") { _, body, _ ->
        val status = small(body); status.text = "Carico i dati per ${place.third}…"
        io.execute {
            val res = runCatching {
                org.json.JSONObject(httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.first}&longitude=${place.second}" +
                    "&current=european_aqi,pm10,pm2_5,nitrogen_dioxide,ozone,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen&timezone=auto"))
                    .getJSONObject("current")
            }
            runOnUiThread {
                val c = res.getOrNull()
                if (c == null) { status.text = "Dati non disponibili: controlla la connessione"; return@runOnUiThread }
                body.removeAllViews()
                sectionLabel(body, "QUALITÀ DELL'ARIA · ${place.third.uppercase(Locale.ITALIAN)}")
                val aqi = c.optInt("european_aqi", -1)
                big(body, 30f).text = if (aqi < 0) "n.d." else Instruments.aqiLabel(aqi)
                small(body).text = if (aqi < 0) "" else "Indice europeo: $aqi (più basso è meglio)"
                fun row(name: String, key: String, limit: String) { if (!c.isNull(key)) listRow(body, "$name: ${"%.0f".format(c.optDouble(key))} µg/m³", limit, onClick = {}) }
                row("Polveri sottili PM2.5", "pm2_5", "Limite giornaliero consigliato dall'OMS: 15")
                row("Polveri PM10", "pm10", "Limite giornaliero consigliato dall'OMS: 45")
                row("Biossido di azoto", "nitrogen_dioxide", "Traffico · limite giornaliero OMS: 25")
                row("Ozono", "ozone", "Più alto nei pomeriggi estivi · soglia OMS: 100")
                sectionLabel(body, "POLLINI")
                val pollens = listOf("Graminacee" to "grass_pollen", "Betulla" to "birch_pollen", "Ontano" to "alder_pollen", "Olivo" to "olive_pollen",
                    "Artemisia" to "mugwort_pollen", "Ambrosia" to "ragweed_pollen").filter { !c.isNull(it.second) }
                if (pollens.isEmpty()) small(body).text = "Dati sui pollini non disponibili in questo periodo"
                pollens.forEach { (n, k) -> val v = c.optDouble(k); listRow(body, "$n: ${Instruments.pollenLabel(v)}", "%.0f granuli/m³".format(v), onClick = {}) }
                emptyLabel(body, "Dati Open-Meteo / Copernicus, aggiornati ogni ora.")
            }
        }
    }
}

// ======================= Earthquakes (INGV) =======================

internal fun MainActivity.showQuakes() {
    val lat = weatherPrefs.getFloat("lat", Float.NaN); val lon = weatherPrefs.getFloat("lon", Float.NaN)
    val city = weatherPrefs.getString("city", null)
    val alerts = getSharedPreferences("wl_alerts", MODE_PRIVATE)
    openPage("Terremoti") { _, body, dialog ->
        val status = small(body); status.text = "Carico le ultime scosse…"
        io.execute {
            val res = runCatching { Instruments.parseQuakes(httpGet("https://webservices.ingv.it/fdsnws/event/1/query?format=text&minmag=2&limit=60&orderby=time")) }
            runOnUiThread {
                val list = res.getOrNull()
                if (list == null) { status.text = "Dati non disponibili: controlla la connessione"; return@runOnUiThread }
                body.removeAllViews()
                if (!lat.isNaN()) listRow(body, (if (alerts.getBoolean("quakes", false)) "🔔 Avviso attivo" else "🔕 Avvisami") + " per le scosse vicino a $city",
                    "Magnitudo 3 o più entro 100 km · controllo ogni 2 ore circa", onClick = {
                        alerts.edit().putBoolean("quakes", !alerts.getBoolean("quakes", false)).apply()
                        if (alerts.getBoolean("quakes", false)) ensureNotificationPermission()
                        AlertsWorker.schedule(this); dialog.dismiss(); showQuakes()
                    })
                sectionLabel(body, "ULTIME SCOSSE (MAGNITUDO 2+)")
                list.forEach { q ->
                    val km = if (lat.isNaN()) null else Instruments.haversineKm(lat.toDouble(), lon.toDouble(), q.lat, q.lon)
                    val icon = when { q.mag >= 4 -> "🔴"; q.mag >= 3 -> "🟠"; else -> "🟡" }
                    listRow(body, "$icon ${"%.1f".format(q.mag)} · ${q.place}", NewsAdapter.ago(q.time) + " · profondità ${"%.0f".format(q.depthKm)} km" +
                        (km?.let { " · a ${"%.0f".format(it)} km da te" } ?: ""), onClick = {
                        openInBrowser("https://www.google.com/maps/search/?api=1&query=${q.lat},${q.lon}"); dialog.dismiss()
                    })
                }
                emptyLabel(body, "Dati INGV (Istituto Nazionale di Geofisica e Vulcanologia). Non è un sistema di allerta in tempo reale.")
            }
        }
    }
}

// ======================= Tip & split =======================

internal fun MainActivity.showSplitBill() {
    openPage("Mancia e conto diviso") { _, body, _ ->
        val d = resources.displayMetrics.density
        val palette = AppRepository.palette(this)
        val bill = EditText(this).apply {
            hint = "Totale del conto €"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; textSize = 22f
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), 0) }
        }
        body.addView(bill)
        var tip = 0; var people = 2; var round = false
        val result = big(body, 40f); val detail = small(body)
        fun calc() {
            val v = Calc.num(bill.text.toString())
            if (v == null) { result.text = "—"; detail.text = ""; return }
            val s = Instruments.split(v, tip.toDouble(), people, round)
            result.text = "${Expenses.fmt(s.each)} a testa"
            detail.text = "Mancia ${Expenses.fmt(s.tip)} · totale ${Expenses.fmt(s.total)} · $people persone"
        }
        bill.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = calc()
        })
        sectionLabel(body, "MANCIA")
        val tips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0) }
        val tipBtns = mutableListOf<MaterialButton>()
        listOf(0, 5, 10, 15).forEach { t ->
            tipBtns += MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (t == 0) "No" else "$t%"; isCheckable = true; isChecked = t == 0
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
                setOnClickListener { tip = t; tipBtns.forEach { b -> b.isChecked = b === this }; calc() }
                tips.addView(this)
            }
        }
        body.addView(tips)
        sectionLabel(body, "PERSONE")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val count = TextView(this).apply { text = "2"; textSize = 30f; setTextColor(palette.text); setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0) }
        fun pb(t: String, delta: Int) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; textSize = 22f; setOnClickListener { people = (people + delta).coerceIn(1, 50); count.text = people.toString(); calc() }
        }
        row.addView(pb("−", -1)); row.addView(count); row.addView(pb("+", 1)); body.addView(row)
        button(body, "Arrotonda all'euro a testa: no") { b -> round = !round; b.text = "Arrotonda all'euro a testa: ${if (round) "sì" else "no"}"; calc() }
        calc()
    }
}

// ======================= Rubbish collection =======================

internal fun MainActivity.showWastePage() {
    openPage("Raccolta rifiuti") { _, body, _ ->
        fun render() {
            body.removeAllViews()
            val m = Evening.waste(this)
            emptyLabel(body, "Scegli cosa passa in ogni giorno: la sera prima alle 19 ricevi un avviso su cosa mettere fuori.")
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            Waste.DAYS.forEach { (day, name) ->
                val kinds = m[day].orEmpty()
                listRow(body, name, if (kinds.isEmpty()) { if (day == today) "Oggi · nessuna raccolta" else "Nessuna raccolta" } else { (if (day == today) "Oggi · " else "") + kinds.joinToString(" · ") }, onClick = {
                    val checked = BooleanArray(Waste.KINDS.size) { Waste.KINDS[it] in kinds }
                    MaterialAlertDialogBuilder(this).setTitle(name)
                        .setMultiChoiceItems(Waste.KINDS.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton("Salva") { _, _ ->
                            Evening.setWaste(this, m + (day to Waste.KINDS.filterIndexed { i, _ -> checked[i] }))
                            ensureNotificationPermission(); render()
                        }.setNegativeButton("Annulla", null).show()
                })
            }
            val out = Waste.tomorrow(m, Calendar.getInstance())
            if (out.isNotEmpty()) emptyLabel(body, "Stasera metti fuori: " + out.joinToString(", "))
        }
        render()
    }
}

// ======================= Weekly summary =======================

internal fun MainActivity.showWeeklySummary() {
    toast("Preparo il riepilogo…")
    io.execute {
        val t = runCatching { Evening.composeWeekly(this) }.getOrDefault("Nessun dato per questa settimana")
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val on = Evening.weekly(this)
            MaterialAlertDialogBuilder(this).setTitle("📊 La tua settimana").setMessage(t)
                .setPositiveButton("OK", null)
                .setNeutralButton(if (on) "Non inviarlo più" else "Inviamelo ogni domenica alle 19") { _, _ ->
                    Evening.setWeekly(this, !on)
                    if (!on) { ensureNotificationPermission(); toast("📊 Riepilogo ogni domenica alle 19") } else toast("Riepilogo settimanale spento")
                }.show()
        }
    }
}
