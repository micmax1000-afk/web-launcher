package com.weblauncher.app

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ======================= Tab groups =======================

internal fun MainActivity.chooseTabGroup(tab: MainActivity.WebTab, done: () -> Unit) {
    val existing = webTabs.map { it.group }.filter { it.isNotBlank() }.distinct()
    val names = (existing + listOf("Lavoro", "Casa", "Viaggi", "Svago")).distinct()
    val items = names + listOf("➕ Nuovo gruppo…", "Nessun gruppo")
    MaterialAlertDialogBuilder(this).setTitle("Metti nel gruppo").setItems(items.toTypedArray()) { _, i ->
        when {
            i < names.size -> { tab.group = names[i]; persistTabs(); done() }
            i == names.size -> {
                val et = field("Nome del gruppo", null)
                MaterialAlertDialogBuilder(this).setTitle("Nuovo gruppo").setView(form(et)).setPositiveButton("OK") { _, _ ->
                    et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { tab.group = it.take(24); persistTabs() }; done()
                }.setNegativeButton("Annulla") { _, _ -> done() }.show()
            }
            else -> { tab.group = ""; persistTabs(); done() }
        }
    }.setOnCancelListener { done() }.show()
}

// ======================= Address bar at the bottom =======================

/** Puts the address bar at the top (default) or at the bottom of the browser, easier to reach with one hand. */
internal fun MainActivity.applyBarPosition() {
    val panel = browserPanel as? LinearLayout ?: return
    val bar = findViewById<View>(R.id.browserToolbar) ?: return
    val bottom = homePrefs.getBoolean("barBottom", false)
    val idx = panel.indexOfChild(bar)
    val want = if (bottom) panel.childCount - 1 else 0
    if (idx == want) return
    panel.removeView(bar)
    if (bottom) panel.addView(bar) else panel.addView(bar, 0)
}

// ======================= Swipe from the edge: back / forward =======================

private var edgeStartX = -1f
private var edgeStartY = 0f
private var edgeFromLeft = true

/** Watches the touches (does not consume them): a swipe from the left edge goes back, from the right edge goes forward. */
internal fun MainActivity.edgeSwipe(ev: MotionEvent) {
    if (currentTab != MainActivity.TAB_BROWSER || !browserHasPage || !homePrefs.getBoolean("edgeSwipe", true)) { edgeStartX = -1f; return }
    val d = resources.displayMetrics.density
    val w = resources.displayMetrics.widthPixels
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            edgeStartX = -1f
            if (ev.rawX < 22 * d) { edgeStartX = ev.rawX; edgeStartY = ev.rawY; edgeFromLeft = true }
            else if (ev.rawX > w - 22 * d) { edgeStartX = ev.rawX; edgeStartY = ev.rawY; edgeFromLeft = false }
        }
        MotionEvent.ACTION_UP -> {
            if (edgeStartX < 0) return
            val dx = ev.rawX - edgeStartX; val dy = Math.abs(ev.rawY - edgeStartY)
            edgeStartX = -1f
            if (dy > 70 * d) return
            if (edgeFromLeft && dx > 90 * d && webView.canGoBack()) { webView.goBack(); edgeHint("‹ Indietro") }
            else if (!edgeFromLeft && dx < -90 * d && webView.canGoForward()) { webView.goForward(); edgeHint("Avanti ›") }
        }
        MotionEvent.ACTION_CANCEL -> edgeStartX = -1f
    }
}

private fun MainActivity.edgeHint(text: String) {
    webView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
}

// ======================= Download finished =======================

internal val myDownloads = mutableSetOf<Long>()

/** When a download started here finishes: a message with «Apri». */
internal fun MainActivity.setupDownloadDone() {
    val r = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id !in myDownloads) return
            myDownloads.remove(id)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            var name = "file"; var ok = false; var mime: String? = null
            runCatching {
                dm.query(android.app.DownloadManager.Query().setFilterById(id)).use { cur ->
                    if (cur.moveToFirst()) {
                        ok = cur.getInt(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)) == android.app.DownloadManager.STATUS_SUCCESSFUL
                        name = cur.getString(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE)) ?: name
                        mime = cur.getString(cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_MEDIA_TYPE))
                    }
                }
            }
            val root = findViewById<View>(android.R.id.content)
            if (!ok) { com.google.android.material.snackbar.Snackbar.make(root, "❌ Download non riuscito: $name", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show(); return }
            com.google.android.material.snackbar.Snackbar.make(root, "✅ Scaricato: $name", 8000).setAction("Apri") {
                runCatching {
                    val uri = dm.getUriForDownloadedFile(id)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: dm.getMimeTypeForDownloadedFile(id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { showDownloadsPage() }
            }.show()
        }
    }
    androidx.core.content.ContextCompat.registerReceiver(this, r, android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
}

// ======================= Quick actions at the top of the browser menu =======================

/** A row of big buttons for the things used most on a page: find, reading mode, listen, translate, summary, ask. */
internal fun MainActivity.menuQuickActions(root: LinearLayout, close: () -> Unit) {
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val palette = AppRepository.palette(this)
    val actions = listOf(
        Triple("🔎", "Trova") { showFindDialog() },
        Triple("📖", "Lettura") { openReaderMode() },
        Triple("🔊", "Ascolta") { openReaderMode(listen = true) },
        Triple("🌍", "Traduci") { translateCurrentPage() },
        Triple("✨", "Riassumi") { summarizePage() },
        Triple("💬", "Chiedi") { askThePage() }
    )
    actions.chunked(3).forEach { rowItems ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), 0) }
        rowItems.forEach { (icon, label, action) ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                setPadding(0, dp(8), 0, dp(6))
                val tv = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
                addView(TextView(this@menuQuickActions).apply { text = icon; textSize = 22f; gravity = android.view.Gravity.CENTER })
                addView(TextView(this@menuQuickActions).apply { text = label; textSize = 12f; gravity = android.view.Gravity.CENTER; setTextColor(palette.text) })
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { close(); action() }
            })
        }
        root.addView(row)
    }
    root.addView(View(this).apply {
        setBackgroundColor(palette.stroke)
        layoutParams = LinearLayout.LayoutParams(-1, d.toInt().coerceAtLeast(1)).apply { topMargin = dp(6) }
    })
}
