package com.weblauncher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import java.util.Locale

// Funzioni comuni rimaste dopo che la sezione Ascolta è passata alla sua app.

internal fun MainActivity.ensureNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE).getBoolean("asked_notif", false)
    ) {
        getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply()
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun MainActivity.fmtDur(sec: Long): String = when {
    sec <= 0 -> ""
    sec >= 3600 -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    else -> "${(sec / 60).coerceAtLeast(1)} min"
}

internal fun MainActivity.fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

internal fun MainActivity.shareTextFile(name: String, text: String, mime: String, title: String) {
    runCatching {
        val dir = java.io.File(cacheDir, "report").apply { mkdirs() }
        val f = java.io.File(dir, name); f.writeText(text)
        val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
    }.onFailure { toast("Non riesco a creare il file") }
}

/** Row with artwork, two lines and optional trailing buttons. */
internal fun MainActivity.mediaRow(
    parent: android.widget.LinearLayout, art: String?, title: String, sub: String, progress: String? = null,
    trailing: List<Pair<Int, () -> Unit>> = emptyList(), onLong: (() -> Unit)? = null, onClick: () -> Unit
) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(8), 0, dp(8))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    row.addView(ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(palette.iconTile) }
        load(art) { transformations(coil.transform.RoundedCornersTransformation(24f)); error(R.drawable.ic_nav_audio) }
    })
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    box.addView(TextView(this).apply {
        text = title; textSize = 15f; setTextColor(palette.text); maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD)
    })
    box.addView(TextView(this).apply {
        text = sub; textSize = 12.5f; setTextColor(palette.textMuted); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
    })
    if (!progress.isNullOrBlank()) box.addView(TextView(this).apply {
        text = progress; textSize = 12f
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt()))
    })
    row.addView(box)
    trailing.forEach { (icon, action) ->
        row.addView(android.widget.ImageButton(this).apply {
            setImageResource(icon); setColorFilter(palette.text); background = null
            setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { action() }
        })
    }
    parent.addView(row)
}

/** Apre una registrazione con il lettore audio del telefono. */
internal fun MainActivity.playMedia(id: String, uri: String, title: String, sub: String, art: String?, start: Long) {
    runCatching {
        val u = Uri.parse(uri)
        val shareUri = if (u.scheme == "file") androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", java.io.File(u.path!!)) else u
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(shareUri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { toast("Non trovo un'app per ascoltarla") }
}

internal fun MainActivity.statTile(label: String, value: String, sub: String? = null, color: Int? = null, fraction: Double? = null): LinearLayout {
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
        if (fraction != null) addView(progressBar(fraction, color ?: 0xFF2563EB.toInt()))
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

/** «Foto del piatto»: l'AI guarda la foto e stima il pasto (solo una stima da leggere, niente diario). */
internal fun MainActivity.foodFromPhoto() {
    if (!aiReady()) return
    val f = java.io.File(scanDir, "food_${System.currentTimeMillis()}.jpg")
    capturePhoto(f) { shot ->
        if (shot == null) return@capturePhoto
        toast("✨ L'AI guarda il piatto…")
        io.execute {
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(shot.absolutePath)
                val sc = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                shot.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            }
            runOnUiThread { askFoodAi(shot) }
        }
    }
}

private fun MainActivity.askFoodAi(shot: java.io.File) {
    ai.ask(listOf("user" to "Guarda la foto di un pasto. Riconosci cosa c'è nel piatto e stima una porzione realistica, quella che si vede. " +
        "Rispondi SOLO con un oggetto JSON: {\"piatto\": \"nome breve\", \"porzione_g\": 300, \"kcal\": 550, \"proteine_g\": 25, \"carboidrati_g\": 60, \"grassi_g\": 20, \"note\": \"cosa hai riconosciuto e quanto sei sicuro\"}. " +
        "Numeri interi. Se non vedi del cibo, rispondi {\"kcal\": 0}."),
        "Rispondi solo con JSON valido.", object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val e = Food.parseAi(text)
                val msg = if (e == null) "L'AI non ha riconosciuto del cibo, oppure la risposta non era comprensibile.\n\n" + text.take(300)
                else "${e.name}\n≈ ${e.grams.toInt()} g\n${e.kcal.toInt()} kcal · proteine ${e.protein.toInt()} g · carboidrati ${e.carbs.toInt()} g · grassi ${e.fat.toInt()} g" +
                    (if (e.note.isNotBlank()) "\n\n${e.note}" else "") + "\n\n(stima indicativa)"
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@askFoodAi).setTitle("🍽 Foto del piatto").setMessage(msg).setPositiveButton("Chiudi", null).show()
            }
            override fun onError(message: String, noKey: Boolean) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@askFoodAi).setTitle(if (noKey) "L'AI non è configurata" else "L'AI non è riuscita")
                    .setMessage(if (noKey) "Serve un provider AI con la chiave, che si imposta in Mia AI." else "Motivo: $message\n\nPer le foto serve un modello che legge le immagini (per esempio Gemini).")
                    .setPositiveButton(if (noKey) "Configura Mia AI" else "Chiudi") { _, _ -> if (noKey) openMiaAi() }
                    .setNegativeButton("Annulla", null).show()
            }
        }, listOf(Triple("image", "image/jpeg", "/files/scan/" + shot.name)))
}
