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
     * Script injected at document start into Mia AI's private origin. It does three things:
     *  1. makes Mia AI follow the launcher's light/dark theme (the WebView's own «prefers-color-scheme» is
     *     unreliable: with a dark phone and a light app it made Mia AI dark);
     *  2. gives it webkitSpeechRecognition backed by Android's voice input;
     *  3. gives it speechSynthesis backed by Android's TextToSpeech, with real start/end events and a
     *     «Ferma la lettura» button while it speaks (the WebView's own speechSynthesis may exist but be silent).
     */
    fun script(light: Boolean): String = SCRIPT.replace("__LIGHT__", if (light) "true" else "false")

    private val SCRIPT = """
        (function () {
          var LIGHT = __LIGHT__;
          // ---------- theme ----------
          window.__WL_THEME = LIGHT ? 'light' : 'dark';
          try {
            var realMatch = window.matchMedia.bind(window);
            window.matchMedia = function (q) {
              var m = /prefers-color-scheme\s*:\s*(light|dark)/i.exec(q || '');
              if (!m) return realMatch(q);
              return { matches: (m[1].toLowerCase() === 'light') === LIGHT, media: q, onchange: null,
                addEventListener: function () {}, removeEventListener: function () {},
                addListener: function () {}, removeListener: function () {}, dispatchEvent: function () { return false; } };
            };
          } catch (e) {}
          // the page's root element may not exist yet when this runs at document start
          function withRoot(fn) {
            if (document.documentElement) { fn(document.documentElement); return; }
            var mo = new MutationObserver(function () {
              if (document.documentElement) { mo.disconnect(); fn(document.documentElement); }
            });
            mo.observe(document, { childList: true });
          }
          try {
            withRoot(function (root) {
              if (!root.getAttribute('data-theme')) root.setAttribute('data-theme', window.__WL_THEME);
              new MutationObserver(function () {
                if (root.getAttribute('data-theme') === 'auto') root.setAttribute('data-theme', window.__WL_THEME);
              }).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
            });
          } catch (e) {}

          var bridge = window.$BRIDGE;
          if (!bridge) return;
          function send(o) { try { bridge.postMessage(JSON.stringify(o)); } catch (e) {} }

          // ---------- speech synthesis ----------
          var speaking = false, utter = null, seq = 0, curId = 0, lastCancel = null, pill = null;
          function showPill(on) {
            try {
              if (on && !pill) {
                pill = document.createElement('button');
                pill.type = 'button';
                pill.textContent = '\u23F9 Ferma la lettura';
                pill.setAttribute('style', 'position:fixed;left:50%;transform:translateX(-50%);bottom:calc(96px + env(safe-area-inset-bottom,0px));' +
                  'z-index:2147483647;background:#111827;color:#fff;border:0;border-radius:24px;padding:13px 22px;' +
                  'font:600 15px/1 system-ui,sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.35)');
                pill.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); synth.cancel(); });
                (document.body || document.documentElement).appendChild(pill);
              }
              if (pill) pill.style.display = on ? 'block' : 'none';
            } catch (e) {}
          }
          function Utterance(text) { this.text = text === undefined ? '' : String(text); this.lang = 'it-IT'; this.rate = 1; this.pitch = 1; this.volume = 1; this.voice = null; }
          function finish(u, errorName) {
            speaking = false; utter = null; showPill(false);
            if (!u) return;
            try {
              if (errorName && u.onerror) u.onerror({ error: errorName });
              else if (u.onend) u.onend({});
            } catch (e) {}
          }
          var synth = {
            get speaking() { return speaking; }, pending: false, paused: false, onvoiceschanged: null,
            speak: function (u) {
              var text = String((u && u.text) || '');
              // tapping the read button again while it is reading = stop (the page calls cancel() and then speak())
              if (lastCancel && Date.now() - lastCancel.t < 150 && lastCancel.text === text) { lastCancel = null; return; }
              lastCancel = null;
              utter = u; curId = ++seq; speaking = true; showPill(true);
              send({ t: 'speak', id: curId, text: text, lang: (u && u.lang) || 'it-IT', rate: (u && u.rate) || 1 });
            },
            cancel: function () {
              var was = speaking, u = utter;
              if (was) lastCancel = { t: Date.now(), text: String((u && u.text) || '') };
              send({ t: 'stop' });
              finish(u, null);
            },
            pause: function () {}, resume: function () {}, getVoices: function () { return []; },
            addEventListener: function () {}, removeEventListener: function () {}
          };
          try { Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true, writable: true }); } catch (e) { window.speechSynthesis = synth; }
          try { Object.defineProperty(window, 'SpeechSynthesisUtterance', { value: Utterance, configurable: true, writable: true }); } catch (e) { window.SpeechSynthesisUtterance = Utterance; }

          // ---------- speech recognition ----------
          var current = null;
          function Rec() { this.lang = 'it-IT'; this.continuous = false; this.interimResults = false; }
          Rec.prototype.start = function () {
            current = this;
            send({ t: 'listen', lang: this.lang || 'it-IT' });
            if (this.onstart) this.onstart();
          };
          Rec.prototype.stop = function () {};
          Rec.prototype.abort = function () {};
          try { Object.defineProperty(window, 'webkitSpeechRecognition', { value: Rec, configurable: true, writable: true }); } catch (e) { window.webkitSpeechRecognition = Rec; }
          try { Object.defineProperty(window, 'SpeechRecognition', { value: Rec, configurable: true, writable: true }); } catch (e) { window.SpeechRecognition = Rec; }

          bridge.onmessage = function (e) {
            var m; try { m = JSON.parse(e.data); } catch (x) { return; }
            if (m.t === 'speakstart' || m.t === 'speakend' || m.t === 'speakerror') {
              if (m.id !== curId) return;                       // an older reading: ignore
              if (m.t === 'speakstart') { if (utter && utter.onstart) { try { utter.onstart({}); } catch (x) {} } return; }
              finish(utter, m.t === 'speakerror' ? 'synthesis-failed' : null);
              return;
            }
            var r = current;
            if (!r) return;
            if (m.t === 'result' && r.onresult) {
              var alt = { transcript: m.text, confidence: 1 };
              var res = [alt]; res.isFinal = true;
              r.onresult({ resultIndex: 0, results: [res] });
            } else if (m.t === 'error' && r.onerror) {
              r.onerror({ error: m.error });
            }
            if (m.t === 'result' || m.t === 'error') { if (r.onend) r.onend(); current = null; }
          };
        })();
    """.trimIndent()
}
