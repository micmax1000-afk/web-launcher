package com.weblauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.weblauncher.app.MainActivity.CatalogApp
import com.weblauncher.app.MainActivity.Companion.AI_SYSTEM
import com.weblauncher.app.MainActivity.Companion.DESKTOP_UA
import com.weblauncher.app.MainActivity.Companion.EXTRA_WIDGET
import com.weblauncher.app.MainActivity.Companion.MENU_AI
import com.weblauncher.app.MainActivity.Companion.NEWS_ALL
import com.weblauncher.app.MainActivity.Companion.STATE_TAB
import com.weblauncher.app.MainActivity.Companion.SUGGEST_TAG
import com.weblauncher.app.MainActivity.Companion.TAB_BROWSER
import com.weblauncher.app.MainActivity.Companion.TAB_HOME
import com.weblauncher.app.MainActivity.Companion.TAB_NEWS
import com.weblauncher.app.MainActivity.Companion.TAB_SETTINGS
import com.weblauncher.app.MainActivity.Companion.TAB_TOOLS
import com.weblauncher.app.MainActivity.ReaderDoc
import com.weblauncher.app.MainActivity.TodayEntry
import com.weblauncher.app.MainActivity.WebTab
import com.weblauncher.app.MainActivity.YtChannel
import java.util.Locale
import java.util.UUID

internal fun MainActivity.ytConfig() = YouTube.Config(
    skipAds = ytPrefs.getBoolean("skipAds", true),
    sponsorBlock = ytPrefs.getBoolean("sponsorBlock", true),
    clean = ytPrefs.getBoolean("clean", false),
    background = ytPrefs.getBoolean("background", true),
    speed = ytPrefs.getFloat("speed", 1f),
    quality = ytPrefs.getString("quality", "auto") ?: "auto"
)

internal fun MainActivity.ytDocStartSupported() =
    androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)

/** (Re)installs the YouTube script with the current options. */
internal fun MainActivity.installYouTubeScript() {
    if (!ytDocStartSupported()) return
    runCatching { ytScript?.remove() }
    ytScript = runCatching {
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, YouTube.script(ytConfig()), YouTube.ORIGINS)
    }.getOrNull()
}

internal fun MainActivity.setupYouTube() {
    installYouTubeScript()
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return
    androidx.webkit.WebViewCompat.addWebMessageListener(webView, YouTube.BRIDGE, YouTube.ORIGINS) { _, message, origin, isMainFrame, _ ->
        if (!isMainFrame || !YouTube.isYouTube(origin.toString())) return@addWebMessageListener
        val m = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
        when (m.optString("t")) {
            "sb" -> fetchSponsorSegments(m.optString("id"))
            "state" -> ytPlaying = m.optBoolean("playing")
            "transcript" -> {
                val cb = ytTranscriptCallback
                ytTranscriptCallback = null
                m.optJSONObject("data")?.let { cb?.invoke(it) }
            }
        }
    }
}

internal fun MainActivity.fetchSponsorSegments(id: String) {
    if (!Regex("[\\w-]{11}").matches(id)) return
    Thread {
        val segs = runCatching {
            val cats = Uri.encode("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\"]")
            val c = java.net.URL("https://sponsor.ajay.app/api/skipSegments?videoID=$id&categories=$cats")
                .openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            if (c.responseCode != 200) "[]" else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.let { body ->
                val arr = org.json.JSONArray(body)
                val out = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val seg = o.optJSONArray("segment") ?: continue
                    out.put(org.json.JSONObject().put("start", seg.optDouble(0)).put("end", seg.optDouble(1))
                        .put("category", o.optString("category")))
                }
                out.toString()
            }
        }.getOrDefault("[]")
        runOnUiThread {
            if (YouTube.isYouTube(webView.url)) webView.evaluateJavascript("window.__wlSegments&&__wlSegments('$id',$segs)", null)
        }
    }.start()
}

internal fun MainActivity.withVideoInfo(onData: (org.json.JSONObject) -> Unit) {
    if (!YouTube.isYouTube(webView.url)) { toast("Apri un video di YouTube"); return }
    toast("Leggo il video…")
    ytTranscriptCallback = onData
    webView.evaluateJavascript("window.__wlTranscript ? (__wlTranscript(), 1) : 0") { r ->
        if (r != "1") { ytTranscriptCallback = null; toast("Ricarica la pagina di YouTube e riprova") }
    }
    uiHandler.postDelayed({
        if (ytTranscriptCallback === onData) { ytTranscriptCallback = null; toast("Il video non ha risposto, riprova") }
    }, 15_000)
}

internal fun MainActivity.videoContext(o: org.json.JSONObject): String {
    val transcript = o.optString("transcript")
    return "Video YouTube «${o.optString("title")}» del canale ${o.optString("channel")}.\n" +
        (if (transcript.isNotBlank()) "--- TRASCRIZIONE (con minuti) ---\n$transcript\n--- FINE ---\n"
         else "Trascrizione non disponibile. Descrizione del video:\n${o.optString("description")}\n") + "\n"
}

internal fun MainActivity.summarizeVideo() {
    if (!aiReady()) return
    withVideoInfo { o ->
        val has = o.optString("transcript").isNotBlank()
        showAiSheet(
            title = "Riassunto video",
            context = videoContext(o),
            firstQuestion = if (has) "Riassumi il video in italiano: una frase iniziale e i punti principali, " +
                "ognuno con il minuto [mm:ss] in cui inizia." else
                "Non c'è la trascrizione: dimmi di cosa parla il video in base a titolo e descrizione, e avvisa che è un riassunto parziale.",
            showFirstQuestion = false
        )
    }
}

internal fun MainActivity.askVideo() {
    if (!aiReady()) return
    withVideoInfo { o ->
        showAiSheet(title = "Chiedi al video", context = videoContext(o), firstQuestion = null,
            hint = "Es. a che minuto spiega…? quali prodotti consiglia?")
    }
}

internal fun MainActivity.ytSpeedDialog() {
    val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    val labels = speeds.map { (if (it % 1f == 0f) it.toInt().toString() else it.toString()) + "x" }.toTypedArray()
    val cur = speeds.indexOfFirst { kotlin.math.abs(it - ytPrefs.getFloat("speed", 1f)) < 0.01f }.coerceAtLeast(0)
    MaterialAlertDialogBuilder(this).setTitle("Velocità video")
        .setSingleChoiceItems(labels, cur) { d, w ->
            ytPrefs.edit().putFloat("speed", speeds[w]).apply()
            webView.evaluateJavascript("window.__wlSetSpeed&&__wlSetSpeed(${speeds[w]})", null)
            installYouTubeScript()
            d.dismiss()
        }.show()
}

internal fun MainActivity.ytQualityDialog() {
    val values = arrayOf("auto", "small", "medium", "large", "hd720", "hd1080")
    val labels = arrayOf("Automatica", "240p (meno dati)", "360p", "480p", "720p", "1080p")
    val cur = values.indexOf(ytPrefs.getString("quality", "auto")).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this).setTitle("Qualità preferita")
        .setSingleChoiceItems(labels, cur) { d, w ->
            ytPrefs.edit().putString("quality", values[w]).apply()
            installYouTubeScript()
            d.dismiss()
            if (currentTab == TAB_SETTINGS) renderSettings()
            toast("Vale dai prossimi video (se YouTube lo permette)")
        }.show()
}

internal fun MainActivity.ytOptionsDialog() {
    val keys = arrayOf("skipAds", "sponsorBlock", "clean", "background", "autoPip")
    val labels = arrayOf("⏩ Salta automaticamente gli annunci", "⏭ SponsorBlock (salta sponsor e sigle)",
        "🔇 Interfaccia pulita (niente Shorts e consigliati)", "🎧 Audio a schermo spento", "🎬 Finestrella quando esci dall'app")
    val defaults = booleanArrayOf(true, true, false, true, true)
    val checked = BooleanArray(keys.size) { ytPrefs.getBoolean(keys[it], defaults[it]) }
    MaterialAlertDialogBuilder(this).setTitle("YouTube")
        .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
        .setPositiveButton("Salva") { _, _ ->
            val e = ytPrefs.edit()
            keys.forEachIndexed { i, k -> e.putBoolean(k, checked[i]) }
            e.apply()
            installYouTubeScript()
            if (YouTube.isYouTube(webView.url)) webView.reload()
            if (currentTab == TAB_SETTINGS) renderSettings()
        }
        .setNeutralButton("Qualità") { _, _ -> ytQualityDialog() }
        .setNegativeButton("Annulla", null)
        .show()
}

// ---------- picture-in-picture ----------

internal fun MainActivity.canPip() = android.os.Build.VERSION.SDK_INT >= 26 &&
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun MainActivity.enterVideoPip() {
    if (!canPip()) { toast("La finestrella non è supportata su questo telefono"); return }
    if (!YouTube.isYouTube(webView.url)) { toast("Funziona con i video di YouTube"); return }
    webView.evaluateJavascript("window.__wlPip&&__wlPip(true)", null)
    try {
        val params = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
        enterPictureInPictureMode(params)
    } catch (_: Exception) {
        webView.evaluateJavascript("window.__wlPip&&__wlPip(false)", null)
    }
}
