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
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
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
    fun ask(messages: List<Pair<String, String>>, system: String?, listener: Listener): Int {
        if (!supported()) {
            listener.onError("Il browser di sistema è troppo vecchio per l'AI: aggiorna \"Android System WebView\".", false)
            return 0
        }
        val id = nextId++
        listeners[id] = listener
        val arr = JSONArray()
        messages.forEach { (role, content) -> arr.put(JSONObject().put("role", role).put("content", content)) }
        val js = "wlAsk($id, ${JSONObject.quote(arr.toString())}, ${JSONObject.quote(system.orEmpty())})"
        run { web?.evaluateJavascript(js, null) }
        return id
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
