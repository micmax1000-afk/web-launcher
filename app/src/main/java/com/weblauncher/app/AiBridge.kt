package com.weblauncher.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs AI requests through an invisible WebView on Mia AI's origin, reusing Mia AI's
 * provider code and the provider / key / model the user configured there.
 */
class AiBridge(
    private val host: ViewGroup,
    private val loader: WebViewAssetLoader
) {
    interface Listener {
        fun onPartial(text: String) {}
        fun onDone(text: String, model: String)
        fun onAudio(base64: String, mime: String) {}
        fun onError(message: String, noKey: Boolean)
    }

    private var web: WebView? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()
    private val listeners = HashMap<Int, Listener>()
    private var nextId = 1

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(): WebView {
        web?.let { return it }
        val w = WebView(host.context)
        // creating a WebView switches the app back to the system light/dark mode: restore the chosen theme
        AppRepository.restoreUiMode(host.context)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                m?.let { android.util.Log.i("WL_AI_JS", "${it.messageLevel()} ${it.message().take(300)}") }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                return if (url.host == MiaAi.HOST) loader.shouldInterceptRequest(url) else null
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (ready) return
                ready = true
                val jobs = pending.toList()
                pending.clear()
                jobs.forEach { it() }
            }
        }
        WebViewCompat.addWebMessageListener(w, "WLAi", setOf(MiaAi.ORIGIN)) { _, message, origin, _, _ ->
            if (origin.toString().trimEnd('/') != MiaAi.ORIGIN) return@addWebMessageListener
            val m = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            val id = m.optInt("id")
            val l = listeners[id] ?: return@addWebMessageListener
            when (m.optString("t")) {
                "partial" -> l.onPartial(m.optString("text"))
                "done" -> { listeners.remove(id); l.onDone(m.optString("text"), m.optString("model")) }
                "audio" -> { listeners.remove(id); l.onAudio(m.optString("data"), m.optString("mime")) }
                "error" -> { listeners.remove(id); l.onError(m.optString("message"), m.optString("code") == "nokey") }
            }
        }
        // 1px, transparent: attached so its JavaScript and network keep running
        host.addView(w, ViewGroup.LayoutParams(1, 1))
        w.alpha = 0f
        w.isFocusable = false
        w.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.loadUrl(BRIDGE_URL)
        web = w
        return w
    }

    private fun run(job: () -> Unit) {
        ensure()
        if (ready) job() else pending.add(job)
    }

    /** messages: list of (role, content), role = "user" | "assistant". */
    /**
     * attachments: (kind, mime, url) added to the last message; url is an app path such as
     * /files/scan/x.jpg. kind "image" or "pdf" (Gemini also accepts audio sent as "pdf" with an audio mime).
     */
    fun ask(messages: List<Pair<String, String>>, system: String?, listener: Listener,
            attachments: List<Triple<String, String, String>> = emptyList()): Int {
        if (!supported()) {
            listener.onError("Il browser di sistema è troppo vecchio per l'AI: aggiorna \"Android System WebView\".", false)
            return 0
        }
        val id = nextId++
        listeners[id] = listener
        val arr = JSONArray()
        messages.forEachIndexed { i, (role, content) ->
            val o = JSONObject().put("role", role).put("content", content)
            if (i == messages.lastIndex && attachments.isNotEmpty()) {
                val atts = JSONArray()
                attachments.forEachIndexed { n, (kind, mime, url) ->
                    atts.put(JSONObject().put("kind", kind).put("mime", mime).put("url", url).put("name", "allegato$n"))
                }
                o.put("attachments", atts)
            }
            arr.put(o)
        }
        val js = "wlAsk($id, ${JSONObject.quote(arr.toString())}, ${JSONObject.quote(system.orEmpty())})"
        run { web?.evaluateJavascript(js, null) }
        return id
    }

    /** Natural voice: speech of [text] from Gemini with the key saved in Mia AI. onAudio gets base64 PCM and its mime. */
    fun tts(text: String, voice: String, onAudio: (String, String) -> Unit, onError: (String, String) -> Unit): Int {
        if (!supported()) { onError("Browser di sistema troppo vecchio", "old"); return 0 }
        val id = nextId++
        listeners[id] = object : Listener {
            override fun onDone(text: String, model: String) {}
            override fun onAudio(base64: String, mime: String) = onAudio(base64, mime)
            override fun onError(message: String, noKey: Boolean) = onError(message, if (noKey) "nokey" else "error")
        }
        run { web?.evaluateJavascript("wlTts($id, ${JSONObject.quote(text)}, ${JSONObject.quote(voice)})", null) }
        return id
    }

    /** Lira voice (Piper): speech of [text] with the Italian voice [voiceId]; onProgress reports the first download. */
    fun piper(text: String, voiceId: String, onAudio: (String, String) -> Unit, onError: (String, String) -> Unit, onProgress: (String) -> Unit = {}): Int {
        if (!supported()) { onError("Browser di sistema troppo vecchio", "old"); return 0 }
        val id = nextId++
        listeners[id] = object : Listener {
            override fun onPartial(text: String) = onProgress(text)
            override fun onDone(text: String, model: String) {}
            override fun onAudio(base64: String, mime: String) = onAudio(base64, mime)
            override fun onError(message: String, noKey: Boolean) = onError(message, "error")
        }
        run { web?.evaluateJavascript("wlPiper($id, ${JSONObject.quote(text)}, ${JSONObject.quote(voiceId)})", null) }
        return id
    }

    /** True when Mia AI has a Gemini key saved. */
    fun hasGeminiKey(callback: (Boolean) -> Unit) {
        if (!supported()) { callback(false); return }
        run { web?.evaluateJavascript("wlHasGemini()") { raw -> callback(raw?.trim('"') == "true") } }
    }

    fun cancel(id: Int) {
        if (listeners.remove(id) != null) web?.evaluateJavascript("wlAbort()", null)
    }

    /** (configured, "Gemini · model") from Mia AI's settings. */
    fun status(callback: (Boolean, String) -> Unit) {
        if (!supported()) { callback(false, ""); return }
        run {
            web?.evaluateJavascript("wlStatus()") { raw ->
                val json = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                val o = runCatching { JSONObject(json ?: "{}") }.getOrNull()
                callback(o?.optBoolean("configured") == true, o?.optString("label").orEmpty())
            }
        }
    }

    fun destroy() {
        web?.let { host.removeView(it); it.destroy() }
        web = null
        listeners.clear()
    }

    companion object {
        const val BRIDGE_URL = "${MiaAi.ORIGIN}/assets/wl-ai/bridge.html"
    }
}
