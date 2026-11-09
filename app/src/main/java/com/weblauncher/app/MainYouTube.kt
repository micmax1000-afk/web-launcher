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
import com.weblauncher.app.MainActivity.Companion.EXTRA_OPEN_PLAYER
import com.weblauncher.app.MainActivity.Companion.EXTRA_WIDGET
import com.weblauncher.app.MainActivity.Companion.MENU_AI
import com.weblauncher.app.MainActivity.Companion.NEWS_ALL
import com.weblauncher.app.MainActivity.Companion.STATE_TAB
import com.weblauncher.app.MainActivity.Companion.SUGGEST_TAG
import com.weblauncher.app.MainActivity.Companion.TAB_AUDIO
import com.weblauncher.app.MainActivity.Companion.TAB_BROWSER
import com.weblauncher.app.MainActivity.Companion.TAB_HOME
import com.weblauncher.app.MainActivity.Companion.TAB_NEWS
import com.weblauncher.app.MainActivity.Companion.TAB_SETTINGS
import com.weblauncher.app.MainActivity.Companion.TAB_TOOLS
import com.weblauncher.app.MainActivity.ReaderDoc
import com.weblauncher.app.MainActivity.Song
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

internal fun MainActivity.followCurrentChannel() {
    webView.evaluateJavascript("window.__wlChannel ? __wlChannel() : '{}'") { raw ->
        val o = runCatching { org.json.JSONObject(jsString(raw)) }.getOrNull()
        val id = o?.optString("id").orEmpty()
        if (!id.startsWith("UC")) { toast("Apri un video o la pagina del canale"); return@evaluateJavascript }
        addYtChannel(YtChannel(id, o?.optString("name").orEmpty().ifBlank { "Canale" }))
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

internal fun MainActivity.ytChannels(): List<YtChannel> = runCatching {
    com.google.gson.Gson().fromJson<List<YtChannel>>(ytPrefs.getString("channels", "[]"),
        object : com.google.gson.reflect.TypeToken<List<YtChannel>>() {}.type)
}.getOrNull().orEmpty()

internal fun MainActivity.saveYtChannels(list: List<YtChannel>) {
    ytPrefs.edit().putString("channels", com.google.gson.Gson().toJson(list)).apply()
}

internal fun MainActivity.addYtChannel(c: YtChannel) {
    if (ytChannels().any { it.id == c.id }) { toast("Segui già ${c.name}"); return }
    saveYtChannels(ytChannels() + c)
    ytVideos = emptyList()
    toast("▶ Ora segui ${c.name}: lo trovi in Ascolta → Video")
}

internal fun MainActivity.refreshYtVideos() {
    val chans = ytChannels()
    val refresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.audioRefresh)
    if (chans.isEmpty()) { refresh.isRefreshing = false; return }
    refresh.isRefreshing = true
    Thread {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val all = try {
            pool.invokeAll(chans.map { ch ->
                java.util.concurrent.Callable {
                    runCatching {
                        val c = java.net.URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch.id).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 10000
                        c.setRequestProperty("Cookie", "SOCS=CAI")
                        val bytes = c.inputStream.use { it.readBytes() }
                        val head = String(bytes, 0, minOf(bytes.size, 4000), Charsets.UTF_8)
                        val realName = Regex("<title>([^<]+)</title>").find(head)?.groupValues?.get(1)
                            ?.let { androidx.core.text.HtmlCompat.fromHtml(it, 0).toString().trim() }
                        if (!realName.isNullOrBlank() && realName != ch.name) runOnUiThread {
                            saveYtChannels(ytChannels().map { if (it.id == ch.id) it.copy(name = realName) else it })
                        }
                        news.parseFeed(bytes, NewsSource(ch.id, realName ?: ch.name, "Video", "")).take(15)
                    }.getOrDefault(emptyList())
                }
            }, 40, java.util.concurrent.TimeUnit.SECONDS).flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
        } finally { pool.shutdownNow() }
        runOnUiThread {
            refresh.isRefreshing = false
            if (all.isNotEmpty()) {
                ytVideos = all.filter { !it.link.contains("/shorts/") }.sortedByDescending { it.time }
                ytPrefs.edit().putLong("refreshed", System.currentTimeMillis()).apply()
            }
            if (currentTab == TAB_AUDIO && audioTab == "video") renderAudio()
        }
    }.start()
}

internal fun MainActivity.renderVideos(list: android.widget.LinearLayout) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val chans = ytChannels()
    if (chans.isEmpty()) {
        list.addView(TextView(this).apply {
            text = "Nessun canale seguito.\n\nApri un video su YouTube → menu ⋮ → «Segui il canale», " +
                "oppure tocca «Cerca» e scrivi il nome del canale (es. @nomecanale).\n\n" +
                "Vedrai solo i video dei tuoi canali: niente consigliati, niente Shorts."
            textSize = 15f; setTextColor(palette.textMuted); setPadding(dp(14), dp(24), dp(14), dp(16))
        })
        return
    }
    if (ytVideos.isEmpty() && System.currentTimeMillis() - ytPrefs.getLong("refreshed", 0L) > 60_000L) refreshYtVideos()
    sectionLabel(list, "CANALI · ${chans.size}")
    val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    chans.forEach { ch ->
        row.addView(TextView(this).apply {
            text = ch.name; textSize = 13f; setTextColor(palette.text)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            setOnClickListener { performLongClick() }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(this@renderVideos).setTitle(ch.name)
                    .setItems(arrayOf("▶ Apri il canale", "✕ Non seguire più")) { _, w ->
                        if (w == 0) openInBrowser("https://m.youtube.com/channel/${ch.id}/videos")
                        else { saveYtChannels(ytChannels().filter { it.id != ch.id }); ytVideos = ytVideos.filter { it.sourceId != ch.id }; renderAudio() }
                    }.show()
                true
            }
        })
    }
    list.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(dp(6), 0, 0, 0); addView(row) })
    sectionLabel(list, "ULTIMI VIDEO")
    if (ytVideos.isEmpty()) {
        list.addView(TextView(this).apply { text = "Trascina in giù per caricare"; textSize = 14f; setTextColor(palette.textMuted); setPadding(dp(10), dp(8), 0, 0) })
    }
    ytVideos.take(60).forEach { v ->
        val r = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { openInBrowser(v.link) }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(this@renderVideos).setTitle(v.title)
                    .setItems(arrayOf("Apri", "Apri in nuova scheda", "Condividi", "🔖 Leggi dopo (link)")) { _, w ->
                        when (w) {
                            0 -> openInBrowser(v.link)
                            1 -> openInNewTab(v.link)
                            2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${v.title}\n${v.link}")
                            }, "Condividi video"))
                            3 -> saveForLater(ReaderDoc(v.title, v.source, v.link, v.image, v.summary.ifBlank { v.title }))
                        }
                    }.show()
                true
            }
        }
        r.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(palette.iconTile) }
            load(v.image) { transformations(coil.transform.RoundedCornersTransformation(20f)) }
        })
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        box.addView(TextView(this).apply {
            text = v.title; textSize = 14.5f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "${v.source} · ${NewsAdapter.ago(v.time)}"; textSize = 12f; setTextColor(palette.textMuted); setPadding(0, dp(3), 0, 0)
        })
        r.addView(box)
        list.addView(r)
    }
}

internal fun MainActivity.addChannelDialog() {
    val d = resources.displayMetrics.density
    val et = TextInputEditText(this).apply { hint = "@nomecanale o link del canale"; setSingleLine() }
    val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
    MaterialAlertDialogBuilder(this).setTitle("Segui un canale YouTube").setView(box)
        .setPositiveButton("Cerca") { _, _ ->
            val raw = et.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@setPositiveButton
            val url = when {
                raw.startsWith("http") -> raw.replace("://youtube.com", "://www.youtube.com").replace("://m.youtube.com", "://www.youtube.com")
                raw.startsWith("@") -> "https://www.youtube.com/$raw"
                raw.startsWith("UC") && raw.length == 24 -> "https://www.youtube.com/channel/$raw"
                else -> "https://www.youtube.com/@" + raw.replace(" ", "")
            }
            toast("Cerco il canale…")
            Thread {
                val found = runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 12000
                    c.setRequestProperty("Cookie", "SOCS=CAI")
                    c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                    val html = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val id = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
                        ?: Regex("\"(?:externalId|channelId|browseId)\":\"(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
                    val name = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    if (id == null) null else YtChannel(id, androidx.core.text.HtmlCompat.fromHtml(name ?: "Canale", 0).toString())
                }.getOrNull()
                runOnUiThread {
                    if (found == null) toast("Canale non trovato: prova dal video, menu ⋮ → Segui il canale")
                    else { addYtChannel(found); if (currentTab == TAB_AUDIO) { audioTab = "video"; refreshYtVideos(); renderAudio() } }
                }
            }.start()
        }
        .setNegativeButton("Annulla", null)
        .show()
}
