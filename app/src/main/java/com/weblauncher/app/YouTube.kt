package com.weblauncher.app

import org.json.JSONObject

/**
 * YouTube helpers injected into youtube.com pages only (document-start script limited to
 * YouTube's origins): ad auto-skip, SponsorBlock, clean UI, speed/quality, background audio,
 * picture-in-picture layout and transcript extraction for the AI.
 */
object YouTube {
    val ORIGINS = setOf(
        "https://m.youtube.com", "https://www.youtube.com", "https://youtube.com", "https://music.youtube.com"
    )
    const val BRIDGE = "WLYouTube"

    fun isYouTube(url: String?): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    data class Config(
        val skipAds: Boolean,
        val sponsorBlock: Boolean,
        val clean: Boolean,
        val background: Boolean,
        val speed: Float,
        val quality: String
    ) {
        fun json(): String = JSONObject()
            .put("skipAds", skipAds).put("sponsorBlock", sponsorBlock).put("clean", clean)
            .put("background", background).put("speed", speed.toDouble()).put("quality", quality)
            .toString()
    }

    fun script(c: Config): String = """
(function () {
  if (window.__wlYT) return; window.__wlYT = true;
  var C = ${c.json()};
  var bridge = window.$BRIDGE;
  function post(o) { try { if (bridge) bridge.postMessage(JSON.stringify(o)); } catch (e) {} }

  // ---------- background audio: the page never sees itself as hidden ----------
  if (C.background) {
    try {
      Object.defineProperty(document, 'hidden', { get: function () { return false; } });
      Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; } });
      Object.defineProperty(document, 'webkitHidden', { get: function () { return false; } });
    } catch (e) {}
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'blur'].forEach(function (ev) {
      window.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
      document.addEventListener(ev, function (e) { e.stopImmediatePropagation(); }, true);
    });
  }

  // ---------- styles: hide ad leftovers, optional clean UI ----------
  var css = '.ytp-ad-overlay-container,.ytp-ad-image-overlay,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,' +
    'ytm-promoted-video-renderer,ad-slot-renderer,ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer,' +
    'ytm-in-feed-ad-layout-renderer,.ytd-display-ad-renderer,#player-ads,#masthead-ad{display:none!important}';
  if (C.clean) css += 'ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,' +
    'ytd-reel-shelf-renderer,ytd-rich-shelf-renderer[is-shorts],ytm-rich-section-renderer,' +
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts),a[href^="/shorts"],' +
    'ytm-item-section-renderer[data-content-type="related"],ytm-single-column-watch-next-results-renderer ytm-item-section-renderer:not(:first-child),' +
    '#related,ytd-watch-next-secondary-results-renderer{display:none!important}';
  css += 'html.wl-pip,html.wl-pip body{overflow:hidden!important;background:#000!important}' +
    'html.wl-pip video{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
    'z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important}' +
    '#wl-toast{position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:rgba(20,20,20,.88);color:#fff;' +
    'padding:8px 14px;border-radius:16px;font:14px Roboto,sans-serif;z-index:2147483647;pointer-events:none;transition:opacity .3s}';
  function addCss() {
    if (document.getElementById('wl-yt-css')) return;
    var s = document.createElement('style'); s.id = 'wl-yt-css'; s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  addCss();
  document.addEventListener('DOMContentLoaded', addCss);

  function toast(t) {
    var el = document.getElementById('wl-toast');
    if (!el) { el = document.createElement('div'); el.id = 'wl-toast'; document.documentElement.appendChild(el); }
    el.textContent = t; el.style.opacity = '1';
    clearTimeout(el._t); el._t = setTimeout(function () { el.style.opacity = '0'; }, 1800);
  }

  function player() { return document.getElementById('movie_player') || document.querySelector('.html5-video-player'); }
  function video() { var p = player(); return (p && p.querySelector('video')) || document.querySelector('video'); }
  function videoId() {
    var m = location.href.match(/[?&]v=([\w-]{11})/) || location.pathname.match(/\/(?:shorts|live|embed)\/([\w-]{11})/);
    return m ? m[1] : null;
  }

  // ---------- ad auto-skip: mute, fast-forward, press "Skip" ----------
  var inAd = false, savedMuted = false, savedRate = 1;
  function adCheck() {
    if (!C.skipAds) return;
    var p = player(), v = video();
    var ad = !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
    if (ad && v) {
      if (!inAd) { inAd = true; savedMuted = v.muted; savedRate = v.playbackRate || 1; }
      v.muted = true;
      try { v.playbackRate = 16; } catch (e) {}
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.3) {
        try { v.currentTime = v.duration - 0.1; } catch (e) {}
      }
      var b = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,' +
        'button[class*="ad-skip"],.ytp-ad-skip-button-container button,ytm-skip-ad-button button');
      if (b) b.click();
    } else if (inAd) {
      inAd = false;
      if (v) { v.muted = savedMuted; try { v.playbackRate = savedRate; } catch (e) {} }
      applyPrefs(true);
    }
  }

  // ---------- interruptions: "are you still there?", premium / open-the-app prompts ----------
  var lastNag = 0;
  function nagCheck() {
    if (!C.skipAds) return;
    var now = Date.now(); if (now - lastNag < 1500) return;
    // "Video in pausa. Continuare la riproduzione?" -> yes, keep playing
    var still = document.querySelector('ytmusic-you-there-renderer button, ytmusic-you-there-renderer [role="button"],' +
      'yt-confirm-dialog-renderer #confirm-button button, yt-confirm-dialog-renderer #confirm-button,' +
      'ytm-confirm-dialog-renderer .confirm-button button, .ytm-you-there-renderer button');
    if (still && still.offsetParent !== null) { lastNag = now; still.click(); var v = video(); if (v && v.paused) { try { v.play(); } catch (e) {} } toast('Continuo la riproduzione'); return; }
    // promotions that cover the player: close with "No, grazie" / the dismiss button
    var promo = document.querySelector('ytmusic-mealbar-promo-renderer #dismiss-button button, ytmusic-mealbar-promo-renderer .dismiss-button,' +
      'ytm-mealbar-promo-renderer .mealbar-promo-dismiss-button button, yt-mealbar-promo-renderer #dismiss-button button,' +
      'ytmusic-upsell-dialog-renderer #dismiss-button button, ytm-upsell-dialog-renderer .upsell-dialog-dismiss-button button,' +
      'tp-yt-paper-dialog ytmusic-enforcement-message-view-model #dismiss-button button');
    if (promo && promo.offsetParent !== null) { lastNag = now; promo.click(); }
  }

  // ---------- speed & quality ----------
  var lastPrefVideo = null;
  function applyPrefs(force) {
    var v = video(); if (!v || inAd) return;
    var id = videoId();
    if (!force && id === lastPrefVideo) return;
    lastPrefVideo = id;
    if (C.speed && C.speed !== 1) { try { v.playbackRate = C.speed; } catch (e) {} }
    if (C.quality && C.quality !== 'auto') {
      var p = player();
      try { if (p && p.setPlaybackQualityRange) p.setPlaybackQualityRange(C.quality, C.quality); } catch (e) {}
      try { if (p && p.setPlaybackQuality) p.setPlaybackQuality(C.quality); } catch (e) {}
    }
  }
  window.__wlSetSpeed = function (s) {
    C.speed = s; var v = video(); if (v && !inAd) { try { v.playbackRate = s; } catch (e) {} }
    toast('Velocità ' + s + 'x');
  };

  // ---------- SponsorBlock (segments fetched by the app) ----------
  var segments = [], segFor = null, skippedAt = {};
  var LABELS = { sponsor: 'Sponsor', selfpromo: 'Autopromozione', interaction: 'Promemoria iscrizione', intro: 'Sigla', outro: 'Finale' };
  function loadSegments() {
    if (!C.sponsorBlock) return;
    var id = videoId();
    if (!id || id === segFor) return;
    segFor = id; segments = []; skippedAt = {};
    post({ t: 'sb', id: id });
  }
  window.__wlSegments = function (id, list) { if (id === segFor) segments = list || []; };
  function sbCheck() {
    if (!segments.length || inAd) return;
    var v = video(); if (!v) return;
    var t = v.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var s = segments[i];
      if (t >= s.start && t < s.end - 0.4 && !skippedAt[i]) {
        skippedAt[i] = true;
        v.currentTime = s.end;
        toast('⏭ ' + (LABELS[s.category] || 'Segmento') + ' saltato');
      }
    }
  }

  // ---------- state for picture-in-picture ----------
  var lastPlaying = null;
  function reportState() {
    var v = video();
    var playing = !!(v && !v.paused && !v.ended && v.readyState > 2);
    if (playing !== lastPlaying) {
      lastPlaying = playing;
      post({ t: 'state', playing: playing, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0 });
    }
  }
  window.__wlPip = function (on) { document.documentElement.classList.toggle('wl-pip', !!on); };

  // ---------- transcript for AI summaries ----------
  function playerResponse() {
    var p = player();
    try { if (p && p.getPlayerResponse) { var r = p.getPlayerResponse(); if (r && r.videoDetails) return r; } } catch (e) {}
    var r2 = window.ytInitialPlayerResponse;
    if (r2 && r2.videoDetails && r2.videoDetails.videoId === videoId()) return r2;
    return null;
  }
  window.__wlTranscript = async function () {
    var r = playerResponse();
    var out = { title: document.title.replace(/ - YouTube$/, ''), channel: '', description: '', transcript: '', channelId: '' };
    if (r && r.videoDetails) {
      out.title = r.videoDetails.title || out.title;
      out.channel = r.videoDetails.author || '';
      out.channelId = r.videoDetails.channelId || '';
      out.description = (r.videoDetails.shortDescription || '').substring(0, 4000);
    }
    try {
      var tracks = r.captions.playerCaptionsTracklistRenderer.captionTracks || [];
      var tr = tracks.find(function (x) { return /^it/.test(x.languageCode) && x.kind !== 'asr'; }) ||
               tracks.find(function (x) { return /^it/.test(x.languageCode); }) ||
               tracks.find(function (x) { return x.kind !== 'asr'; }) || tracks[0];
      if (tr) {
        var res = await fetch(tr.baseUrl + '&fmt=json3', { credentials: 'include' });
        var j = await res.json();
        var lines = [];
        (j.events || []).forEach(function (ev) {
          if (!ev.segs) return;
          var txt = ev.segs.map(function (s) { return s.utf8; }).join('').replace(/\s+/g, ' ').trim();
          if (!txt) return;
          var sec = Math.floor((ev.tStartMs || 0) / 1000);
          lines.push('[' + Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2) + '] ' + txt);
        });
        out.transcript = lines.join('\n').substring(0, 30000);
      }
    } catch (e) {}
    post({ t: 'transcript', data: out });
  };
  window.__wlChannel = function () {
    var r = playerResponse();
    var id = (r && r.videoDetails && r.videoDetails.channelId) || '';
    var name = (r && r.videoDetails && r.videoDetails.author) || '';
    if (!id) {
      var m = document.querySelector('meta[itemprop="channelId"],meta[itemprop="identifier"]');
      if (m) id = m.content;
      var l = document.querySelector('link[rel="canonical"]');
      if (!id && l) { var mm = l.href.match(/channel\/(UC[\w-]{22})/); if (mm) id = mm[1]; }
      var og = document.querySelector('meta[property="og:title"]');
      if (og) name = og.content;
    }
    return JSON.stringify({ id: id, name: name });
  };

  // ---------- main loop ----------
  setInterval(function () {
    try { adCheck(); nagCheck(); loadSegments(); applyPrefs(false); sbCheck(); reportState(); } catch (e) {}
  }, 300);
})();
""".trimIndent()
}
