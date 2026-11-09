package com.weblauncher.app

import java.net.URLEncoder
import java.util.Locale

/** Ready-made search links for flights, hotels and trains (no account, no API key). Pure: unit-tested. */
object TravelLinks {
    data class Query(val from: String, val to: String, val depart: String?, val back: String?, val people: Int)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun iata(s: String) = s.trim().takeIf { Regex("^[A-Za-z]{3}$").matches(it) }?.lowercase(Locale.ROOT)
    private fun yymmdd(d: String) = d.replace("-", "").drop(2) // 2026-12-10 -> 261210
    private fun slug(s: String) = java.text.Normalizer.normalize(s.trim().lowercase(Locale.ITALIAN), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun googleFlights(q: Query): String {
        val text = buildString {
            append("Voli da ${q.from.trim()} a ${q.to.trim()}")
            q.depart?.let { append(" il $it") }
            q.back?.let { append(" ritorno $it") } ?: if (q.depart != null) append(" solo andata") else Unit
            append(" per ${q.people} ${if (q.people == 1) "adulto" else "adulti"}")
        }
        return "https://www.google.com/travel/flights?hl=it&curr=EUR&q=" + enc(text)
    }

    /** Skyscanner needs airport/city codes (TRN, MXP, LON…); otherwise its home page. */
    fun skyscanner(q: Query): String {
        val f = iata(q.from); val t = iata(q.to)
        if (f == null || t == null || q.depart == null) return "https://www.skyscanner.it/"
        return "https://www.skyscanner.it/trasporti/voli/$f/$t/${yymmdd(q.depart)}/" +
            (q.back?.let { "${yymmdd(it)}/" } ?: "") + "?adultsv2=${q.people}&rtn=${if (q.back != null) 1 else 0}"
    }

    fun booking(q: Query): String = "https://www.booking.com/searchresults.it.html?ss=" + enc(q.to) +
        (q.depart?.let { "&checkin=$it" } ?: "") + (q.back?.let { "&checkout=$it" } ?: "") +
        "&group_adults=${q.people}&no_rooms=1&group_children=0"

    fun airbnb(q: Query): String = "https://www.airbnb.it/s/" + enc(q.to).replace("+", "%20") + "/homes?adults=${q.people}" +
        (q.depart?.let { "&checkin=$it" } ?: "") + (q.back?.let { "&checkout=$it" } ?: "")

    fun trainline(q: Query): String = "https://www.thetrainline.com/it/orari-treni/${slug(q.from)}-a-${slug(q.to)}"

    const val TRENITALIA = "https://www.trenitalia.com/"
    const val ITALO = "https://www.italotreno.com/it"
}
