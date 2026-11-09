package com.weblauncher.app

import android.net.Uri
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

// ======================= Cookie banners: automatic «Rifiuta» =======================

internal fun MainActivity.setupCookieBanners() {
    runCatching { cookieScript?.remove() }
    cookieScript = null
    if (!CookieBanners.enabled(this)) return
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
        runCatching {
            // only counts rejected banners: the page cannot ask anything else through this bridge
            androidx.webkit.WebViewCompat.addWebMessageListener(webView, "WLCookie", setOf("*")) { _, _, _, _, _ -> PrivacyStats.bannerRejected() }
        }
    }
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        cookieScript = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, CookieBanners.SCRIPT.replace("window.WLNative", "window.WLCookie").replace("WLNative.postMessage", "WLCookie.postMessage"), setOf("*"))
        }.getOrNull()
    }
}

// ======================= Sites forgotten when closed =======================

/** Clears cookies and storage of the «forget» sites that are not open in any tab. */
internal fun MainActivity.forgetClosedSites() {
    val forget = SiteCookies.list(this)
    if (forget.isEmpty()) return
    val open = webTabs.mapNotNull { runCatching { Uri.parse(it.url).host }.getOrNull()?.let { h -> PhishingGuard.registered(h) } }.toSet()
    (forget - open).forEach { SiteCookies.clear(it) }
}

internal fun MainActivity.showForgetSites() {
    openPage("Siti da dimenticare") { _, body, _ ->
        fun render() {
            body.removeAllViews()
            val list = SiteCookies.list(this).sorted()
            emptyLabel(body, "Per questi siti cookie e dati salvati si cancellano quando chiudi la scheda o esci dall'app: al prossimo accesso sei un visitatore nuovo.\n\nPer aggiungerne uno: nel browser, menu ⋮ → 🍪 Dimentica questo sito alla chiusura.")
            if (list.isEmpty()) return
            sectionLabel(body, "SITI · ${list.size}")
            list.forEach { reg ->
                listRow(body, reg, "Tocca per cancellare adesso", onClick = { SiteCookies.clear(reg); toast("Dati di $reg cancellati") },
                    onRemove = { SiteCookies.remove(this, reg); render() })
            }
        }
        render()
    }
}

// ======================= Privacy report =======================

internal fun MainActivity.showPrivacyReport() {
    io.execute {
        PrivacyStats.flush(this)
        val d = PrivacyStats.load(this)
        runOnUiThread { renderPrivacyReport(d) }
    }
}

private fun MainActivity.renderPrivacyReport(d: PrivacyStats.Data) {
    openPage("Report privacy", "Azzera", { dlg ->
        confirmClear("Azzerare le statistiche?") { PrivacyStats.reset(this); dlg.dismiss(); toast("Statistiche azzerate") }
    }) { _, body, _ ->
        val palette = AppRepository.palette(this)
        val dens = resources.displayMetrics.density
        fun big(n: Long, label: String) {
            body.addView(TextView(this).apply {
                text = "%,d".format(n).replace(',', '.') + "\n" + label
                textSize = 20f; setTextColor(palette.text)
                setPadding((20 * dens).toInt(), (10 * dens).toInt(), (20 * dens).toInt(), (6 * dens).toInt())
            })
        }
        big(PrivacyStats.lastDays(d, 1), "pubblicità e tracciatori bloccati oggi")
        big(PrivacyStats.lastDays(d, 7), "negli ultimi 7 giorni")
        big(d.total, "dal " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ITALIAN).format(java.util.Date(d.since)))
        if (d.cookieBanners > 0) big(d.cookieBanners, "banner dei cookie rifiutati per te")
        val sites = d.bySite.entries.sortedByDescending { it.value }.take(15)
        if (sites.isNotEmpty()) {
            sectionLabel(body, "SITI CON PIÙ TRACCIATORI")
            sites.forEach { (k, v) -> listRow(body, k, "$v bloccati", onClick = {}) }
        }
        val trackers = d.byTracker.entries.sortedByDescending { it.value }.take(15)
        if (trackers.isNotEmpty()) {
            sectionLabel(body, "TRACCIATORI PIÙ FREQUENTI")
            trackers.forEach { (k, v) -> listRow(body, k, "$v volte", onClick = {}) }
        }
        if (d.total == 0L) emptyLabel(body, "Ancora nessun dato: naviga un po' con il blocco pubblicità attivo.")
        emptyLabel(body, "I numeri restano solo sul telefono.")
    }
}

// ======================= Full pages offline (web archive) =======================

data class OfflinePage(val id: String, val title: String, val url: String, val time: Long, val size: Long)

private fun MainActivity.offlineDir() = File(filesDir, "offline").apply { mkdirs() }
private fun MainActivity.offlineIndex() = File(offlineDir(), "index.json")

internal fun MainActivity.loadOfflinePages(): MutableList<OfflinePage> = runCatching {
    Gson().fromJson<MutableList<OfflinePage>>(offlineIndex().readText(), object : TypeToken<MutableList<OfflinePage>>() {}.type)
}.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()

private fun MainActivity.saveOfflineIndex(list: List<OfflinePage>) { runCatching { offlineIndex().writeText(Gson().toJson(list)) } }

internal fun MainActivity.saveOfflinePage() {
    val url = webView.url ?: return
    val title = webView.title?.takeIf { it.isNotBlank() } ?: url
    val id = UUID.randomUUID().toString()
    val f = File(offlineDir(), "$id.mht")
    webView.saveWebArchive(f.path, false) { path ->
        if (path == null || !f.exists()) { toast("Questa pagina non si può salvare"); return@saveWebArchive }
        val list = loadOfflinePages()
        list.filter { it.url == url }.forEach { File(offlineDir(), "${it.id}.mht").delete() }
        list.removeAll { it.url == url }
        list.add(0, OfflinePage(id, title, url, System.currentTimeMillis(), f.length()))
        saveOfflineIndex(list)
        toast("📥 Pagina salvata: la trovi in Leggi dopo, anche senza rete")
    }
}

internal fun MainActivity.showOfflinePages() {
    openPage("Pagine complete offline") { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = loadOfflinePages()
            if (list.isEmpty()) { emptyLabel(body, "Nessuna pagina salvata.\nNel browser: menu ⋮ → 📥 Salva pagina completa offline"); return }
            sectionLabel(body, "PAGINE · ${list.size} · %.1f MB".format(list.sumOf { it.size } / 1048576.0))
            list.forEach { p ->
                listRow(body, p.title, "${hostOf(p.url) ?: ""} · ${NewsAdapter.ago(p.time)}",
                    onClick = { openOfflinePage(p) },
                    onLong = {
                        MaterialAlertDialogBuilder(this).setTitle(p.title).setItems(arrayOf("Apri l'originale (online)", "Elimina")) { _, w ->
                            if (w == 0) { dialog.dismiss(); openInBrowser(p.url) }
                            else { File(offlineDir(), "${p.id}.mht").delete(); saveOfflineIndex(loadOfflinePages().filter { it.id != p.id }); render() }
                        }.show()
                    },
                    onRemove = { File(offlineDir(), "${p.id}.mht").delete(); saveOfflineIndex(loadOfflinePages().filter { it.id != p.id }); render() })
            }
        }
        render()
    }
}

/** Shows the saved archive in a sandboxed WebView: no scripts, no network. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
internal fun MainActivity.openOfflinePage(p: OfflinePage) {
    val f = File(offlineDir(), "${p.id}.mht")
    if (!f.exists()) { toast("File non trovato"); return }
    openPage(p.title, "Online", { dlg -> dlg.dismiss(); openInBrowser(p.url) }) { root, body, _ ->
        (body.parent as? View)?.visibility = View.GONE
        val wv = android.webkit.WebView(this)
        AppRepository.restoreUiMode(this)
        wv.settings.javaScriptEnabled = false
        wv.settings.allowFileAccess = true
        wv.settings.blockNetworkLoads = true
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                request?.url?.toString()?.takeIf { it.startsWith("http") }?.let { openInBrowser(it) }
                return true
            }
        }
        root.addView(wv, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        wv.loadUrl(Uri.fromFile(f).toString())
    }
}
