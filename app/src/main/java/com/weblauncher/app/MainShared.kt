package com.weblauncher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import java.util.Locale

// Funzioni comuni rimaste dopo che la sezione Ascolta è passata alla sua app.

internal fun MainActivity.ensureNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !getSharedPreferences("wl_audio", MODE_PRIVATE).getBoolean("asked_notif", false)
    ) {
        getSharedPreferences("wl_audio", MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply()
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
