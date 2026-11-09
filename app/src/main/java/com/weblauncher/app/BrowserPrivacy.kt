package com.weblauncher.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.google.gson.Gson
import java.io.File
import java.util.Locale

/**
 * Automatic «Rifiuta» on cookie banners. Known consent platforms first (OneTrust, Didomi, iubenda,
 * Quantcast, Cookiebot, Complianz, CookieYes, Klaro, Usercentrics, TrustArc…), then a cautious generic
 * match: only short buttons whose text means «reject», inside an element that looks like a cookie banner.
 */
object CookieBanners {
    val SCRIPT = """
(function(){
  if (window.__wlCookie) return; window.__wlCookie = true;
  var SEL = ['#onetrust-reject-all-handler','.ot-pc-refuse-all-handler','#didomi-notice-disagree-button',
    '.didomi-continue-without-agreeing','.iubenda-cs-reject-btn','.qc-cmp2-summary-buttons button[mode="secondary"]',
    '#CybotCookiebotDialogBodyButtonDecline','#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll',
    '.cmplz-deny','.cky-btn-reject','.cm-btn-decline','.klaro .cn-decline','[data-testid="uc-deny-all-button"]',
    '#truste-consent-required','.truste-consent-required','.cc-deny','.cookie-notice-reject','#cookie_action_close_header_reject',
    '.fc-cta-do-not-consent','button[aria-label="Rifiuta tutto"]','button[aria-label="Reject all"]',
    '.sp_choice_type_REJECT_ALL','.message-component.sp_choice_type_13','#tarteaucitronAllDenied2','.osano-cm-denyAll',
    '.js-cookie-reject','#rejectAllButton','.evidon-banner-declinebutton'];
  var WORDS = /^(rifiuta( tutt[oi])?|rifiuto|non accetto|nega|continua senza accettare|accetta solo (i )?(cookie )?necessari|solo (cookie )?necessari|usa solo (i )?cookie necessari|reject( all)?|decline( all)?|deny( all)?|only necessary|necessary only|refuse( all)?|tout refuser|alle ablehnen|ablehnen|rechazar( todo)?)$/i;
  var CTX = /(cookie|consent|gdpr|cmp|privacy|banner|didomi|onetrust|iubenda|cookiebot|usercentrics|qc-cmp|sp_message|fc-consent)/i;
  function visible(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function inBanner(e){
    if (/^consent\./.test(location.hostname)) return true;
    for (var p = e, i = 0; p && i < 12; p = p.parentElement, i++) {
      if (CTX.test((p.id || '') + ' ' + (typeof p.className === 'string' ? p.className : '') + ' ' + (p.getAttribute && (p.getAttribute('aria-label') || '')))) return true;
    }
    return false;
  }
  function roots(){ var out = [document];
    var hosts = document.querySelectorAll('#usercentrics-root, #cmpwrapper, [id^="sp_message"]');
    for (var i = 0; i < hosts.length; i++) if (hosts[i].shadowRoot) out.push(hosts[i].shadowRoot);
    return out; }
  var tries = 0, done = false;
  function attempt(){
    if (done || tries++ > 40) return;
    var rs = roots();
    for (var r = 0; r < rs.length; r++) {
      for (var s = 0; s < SEL.length; s++) {
        var el = rs[r].querySelector(SEL[s]);
        if (el && visible(el)) { el.click(); done = true; report(); return; }
      }
      var btns = rs[r].querySelectorAll('button, a[role="button"], [role="button"], input[type="button"], input[type="submit"]');
      for (var b = 0; b < btns.length; b++) {
        var t = (btns[b].innerText || btns[b].value || '').trim().replace(/\s+/g, ' ');
        if (t.length > 0 && t.length < 45 && WORDS.test(t) && visible(btns[b]) && inBanner(btns[b])) { btns[b].click(); done = true; report(); return; }
      }
    }
    setTimeout(attempt, tries < 10 ? 500 : 1500);
  }
  function report(){ try { if (window.WLNative) WLNative.postMessage(JSON.stringify({type:'cookieRejected', host: location.hostname})); } catch(e){} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(attempt, 300); });
  else setTimeout(attempt, 300);
})();
""".trimIndent()

    private fun prefs(c: Context) = c.getSharedPreferences("wl_privacy", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("cookieReject", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("cookieReject", on).apply()
}

/** Counts of blocked ads/trackers per day, per site and per tracker. Kept only on the phone. */
object PrivacyStats {
    data class Data(
        var total: Long = 0,
        var cookieBanners: Long = 0,
        var byDay: MutableMap<String, Long> = mutableMapOf(),
        var bySite: MutableMap<String, Long> = mutableMapOf(),
        var byTracker: MutableMap<String, Long> = mutableMapOf(),
        var since: Long = System.currentTimeMillis()
    )

    private val pendingSite = mutableMapOf<String, Long>()
    private val pendingTracker = mutableMapOf<String, Long>()
    private var pendingTotal = 0L
    private var pendingBanners = 0L
    private val lock = Any()

    /** Called from the WebView's network thread for every blocked request. */
    fun blocked(site: String?, tracker: String) = synchronized(lock) {
        pendingTotal++
        val s = site?.removePrefix("www.") ?: "?"
        pendingSite[s] = (pendingSite[s] ?: 0) + 1
        pendingTracker[tracker] = (pendingTracker[tracker] ?: 0) + 1
    }

    fun bannerRejected() = synchronized(lock) { pendingBanners++ }

    private fun file(c: Context) = File(c.filesDir, "privacy_stats.json")

    fun load(c: Context): Data = runCatching { Gson().fromJson(file(c).readText(), Data::class.java) }.getOrNull()
        ?.also { d -> if (d.byDay == null) d.byDay = mutableMapOf(); if (d.bySite == null) d.bySite = mutableMapOf(); if (d.byTracker == null) d.byTracker = mutableMapOf() }
        ?: Data()

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())

    /** Writes the pending counts to disk (call off the main thread). */
    fun flush(c: Context) {
        val (t, b, sites, trackers) = synchronized(lock) {
            if (pendingTotal == 0L && pendingBanners == 0L) return
            val r = listOf(pendingTotal, pendingBanners, HashMap(pendingSite), HashMap(pendingTracker))
            pendingTotal = 0; pendingBanners = 0; pendingSite.clear(); pendingTracker.clear(); r
        }
        @Suppress("UNCHECKED_CAST")
        val d = load(c).apply {
            total += t as Long; cookieBanners += b as Long
            val day = today(); byDay[day] = (byDay[day] ?: 0) + t
            (sites as Map<String, Long>).forEach { (k, v) -> bySite[k] = (bySite[k] ?: 0) + v }
            (trackers as Map<String, Long>).forEach { (k, v) -> byTracker[k] = (byTracker[k] ?: 0) + v }
            // keep the file small
            if (byDay.size > 60) byDay.keys.sorted().take(byDay.size - 60).forEach { byDay.remove(it) }
            if (bySite.size > 300) bySite.entries.sortedBy { it.value }.take(bySite.size - 300).map { it.key }.forEach { bySite.remove(it) }
            if (byTracker.size > 300) byTracker.entries.sortedBy { it.value }.take(byTracker.size - 300).map { it.key }.forEach { byTracker.remove(it) }
        }
        runCatching { file(c).writeText(Gson().toJson(d)) }
    }

    fun reset(c: Context) { file(c).delete() }

    /** Sum of the last [days] days, today included. */
    fun lastDays(d: Data, days: Int, now: java.util.Calendar = java.util.Calendar.getInstance()): Long {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val cal = now.clone() as java.util.Calendar
        var sum = 0L
        repeat(days) { sum += d.byDay[f.format(cal.time)] ?: 0; cal.add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return sum
    }
}

/** Sites whose cookies and stored data are erased when the app goes to the background. */
object SiteCookies {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_privacy", Context.MODE_PRIVATE)
    fun list(c: Context): Set<String> = prefs(c).getStringSet("forget", emptySet()).orEmpty()
    fun has(c: Context, host: String?) = host != null && PhishingGuard.registered(host) in list(c)
    fun toggle(c: Context, host: String): Boolean {
        val reg = PhishingGuard.registered(host)
        val now = list(c).toMutableSet()
        val on = if (reg in now) { now.remove(reg); false } else { now.add(reg); true }
        prefs(c).edit().putStringSet("forget", now).apply()
        return on
    }
    fun remove(c: Context, reg: String) = prefs(c).edit().putStringSet("forget", list(c) - reg).apply()

    /** Expires every cookie of the site (and its subdomains we know) and deletes its storage. Main thread. */
    fun clear(domain: String) {
        val cm = CookieManager.getInstance()
        val hosts = listOf(domain, "www.$domain", "m.$domain", "accounts.$domain", "login.$domain")
        for (h in hosts) for (scheme in listOf("https://", "http://")) {
            val url = scheme + h
            val cookies = cm.getCookie(url) ?: continue
            cookies.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { name ->
                listOf("", "; domain=$domain", "; domain=.$domain", "; domain=$h").forEach { dom ->
                    cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/$dom")
                }
            }
            runCatching { WebStorage.getInstance().deleteOrigin(url) }
        }
        cm.flush()
    }

    fun clearAll(c: Context) = list(c).forEach { clear(it) }
}
