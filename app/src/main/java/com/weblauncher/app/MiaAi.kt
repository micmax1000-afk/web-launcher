package com.weblauncher.app

/**
 * Mia AI (github.com/micmax1000-afk/mia-ai-app) is bundled in assets/mia-ai and served
 * through WebViewAssetLoader on a private https origin, so its localStorage (keys, chats)
 * stays separate from every website.
 */
object MiaAi {
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"
    const val URL = "$ORIGIN/assets/mia-ai/index.html"
    const val NAME = "Mia AI"
    const val BRIDGE = "WLNative"

    fun isMiaUrl(url: String?): Boolean =
        url != null && url.startsWith("$ORIGIN/assets/mia-ai/")

    /**
     * Android WebView has no Web Speech API. These shims give Mia AI the same
     * webkitSpeechRecognition / speechSynthesis objects, backed by Android's native
     * voice input and TextToSpeech through an origin-restricted message channel.
     */
    val SPEECH_SHIM = """
        (function () {
          var bridge = window.$BRIDGE;
          if (!bridge) return;
          var current = null;
          function Rec() { this.lang = 'it-IT'; this.continuous = false; this.interimResults = false; }
          Rec.prototype.start = function () {
            current = this;
            bridge.postMessage(JSON.stringify({ t: 'listen', lang: this.lang || 'it-IT' }));
            if (this.onstart) this.onstart();
          };
          Rec.prototype.stop = function () {};
          Rec.prototype.abort = function () {};
          bridge.onmessage = function (e) {
            var m; try { m = JSON.parse(e.data); } catch (x) { return; }
            var r = current;
            if (!r) return;
            if (m.t === 'result' && r.onresult) {
              r.onresult({ resultIndex: 0, results: [[{ transcript: m.text, confidence: 1 }]] });
            } else if (m.t === 'error' && r.onerror) {
              r.onerror({ error: m.error });
            }
            if (m.t === 'result' || m.t === 'error') { if (r.onend) r.onend(); current = null; }
          };
          if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
            window.webkitSpeechRecognition = Rec;
          }
          if (!window.speechSynthesis) {
            window.SpeechSynthesisUtterance = function (text) {
              this.text = text; this.lang = 'it-IT'; this.rate = 1; this.pitch = 1; this.voice = null;
            };
            window.speechSynthesis = {
              speak: function (u) {
                bridge.postMessage(JSON.stringify({ t: 'speak', text: String(u.text || ''), lang: u.lang || 'it-IT', rate: u.rate || 1 }));
              },
              cancel: function () { bridge.postMessage(JSON.stringify({ t: 'stop' })); },
              getVoices: function () { return []; },
              onvoiceschanged: null
            };
          }
        })();
    """.trimIndent()
}
