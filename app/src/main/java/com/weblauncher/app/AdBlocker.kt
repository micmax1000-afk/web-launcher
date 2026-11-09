package com.weblauncher.app

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Small built-in blocklist of ad and tracker hosts. A request is blocked when its
 * host is a listed domain or one of its subdomains. Main pages are never blocked.
 */
object AdBlocker {
    private val domains = hashSetOf(
        // Google ads & analytics
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "google-analytics.com",
        "googletagservices.com", "googletagmanager.com", "adservice.google.com", "adservice.google.it",
        "pagead2.googlesyndication.com", "imasdk.googleapis.com", "app-measurement.com",
        // Meta / social trackers
        "connect.facebook.net", "pixel.facebook.com", "an.facebook.com", "ads-twitter.com",
        "static.ads-twitter.com", "analytics.twitter.com", "ads.linkedin.com", "px.ads.linkedin.com",
        "analytics.tiktok.com", "ads.tiktok.com", "ct.pinterest.com", "sc-static.net", "tr.snapchat.com",
        // Ad networks / exchanges
        "adnxs.com", "adsrvr.org", "advertising.com", "amazon-adsystem.com", "adform.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "outbrainimg.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com", "teads.tv", "yieldmo.com",
        "sharethrough.com", "media.net", "33across.com", "indexww.com", "contextweb.com",
        "bidswitch.net", "adsafeprotected.com", "moatads.com", "doubleverify.com", "serving-sys.com",
        "mathtag.com", "turn.com", "rlcdn.com", "bluekai.com", "demdex.net", "everesttech.net",
        "zemanta.com", "revcontent.com", "mgid.com", "adroll.com", "quantserve.com", "quantcount.com",
        "scorecardresearch.com", "imrworldwide.com", "chartbeat.com", "chartbeat.net", "hotjar.com",
        "hotjar.io", "mouseflow.com", "crazyegg.com", "fullstory.com", "clarity.ms", "newrelic.com",
        "nr-data.net", "segment.io", "segment.com", "mixpanel.com", "amplitude.com", "branch.io",
        "adjust.com", "appsflyer.com", "kochava.com", "onesignal.com", "pushwoosh.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.net",
        "adcolony.com", "unityads.unity3d.com", "applovin.com", "inmobi.com", "vungle.com",
        "an.yandex.ru", "mc.yandex.ru", "ads.yahoo.com", "analytics.yahoo.com",
        "bat.bing.com", "ads.microsoft.com", "c.bing.com",
        // Italian ad networks / trackers
        "adkaora.space", "seedtag.com", "ividence.com", "mediamond.it", "webtrekk.net",
        "4wnet.com", "tradedoubler.com", "zanox.com", "awin1.com", "nuggad.net", "gemius.pl",
        "wt-safetag.com", "permutive.com", "permutive.app", "id5-sync.com", "liadm.com",
        "intentiq.com", "tapad.com", "lijit.com", "sovrn.com", "gumgum.com",
        "richaudience.com", "adagio.io", "onetag-sys.com", "sonobi.com", "triplelift.com"
    )

    private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    var blockedCount = 0
        private set

    fun resetCount() { blockedCount = 0 }

    fun isBlocked(host: String?): Boolean = match(host) != null

    /** The blocklist entry that matches [host] (itself or a parent domain), or null. */
    fun match(host: String?): String? {
        var h = host?.lowercase() ?: return null
        while (true) {
            if (h in domains) return h
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return null
            h = h.substring(dot + 1)
        }
    }

    /** Returns an empty response when the sub-resource must be blocked, null otherwise. */
    fun intercept(host: String?, isMainFrame: Boolean, site: String? = null): WebResourceResponse? {
        if (isMainFrame) return null
        val hit = match(host) ?: return null
        blockedCount++
        PrivacyStats.blocked(site, hit)
        return empty
    }
}
