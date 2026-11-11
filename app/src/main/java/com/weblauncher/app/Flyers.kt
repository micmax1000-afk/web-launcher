package com.weblauncher.app

import android.content.Context
import java.text.Normalizer

/** Volantini da DoveConviene: indirizzi delle pagine per comune, categoria, catena e prodotto. Pure (unit-tested). */
object Flyers {
    const val BASE = "https://www.doveconviene.it"

    val CATEGORIES = listOf(
        "iper-e-super" to "🛒 Supermercati",
        "discount" to "🏷 Discount",
        "elettronica" to "📺 Elettronica",
        "cura-casa-e-corpo" to "🧴 Casa e cura della persona",
        "bricolage" to "🔨 Bricolage e giardino"
    )

    /** Catene comuni: (nome mostrato, nome nell'indirizzo di DoveConviene). */
    val CHAINS = listOf(
        "Lidl" to "lidl", "Eurospin" to "eurospin", "Conad" to "conad", "Coop" to "coop", "Esselunga" to "esselunga",
        "Carrefour" to "carrefour", "MD" to "md", "Penny" to "penny", "Aldi" to "aldi", "Pam" to "pam", "Despar" to "despar",
        "Famila" to "famila", "Bennet" to "bennet", "Iper" to "iper", "Tigotà" to "tigota", "Acqua & Sapone" to "acqua-sapone",
        "Unieuro" to "unieuro", "MediaWorld" to "mediaworld", "Euronics" to "euronics", "Leroy Merlin" to "leroy-merlin", "Brico Center" to "bricocenter"
    )

    /** "Reggio nell'Emilia" -> "reggio-nell-emilia", "Forlì" -> "forli". */
    fun slug(s: String): String = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun cityUrl(city: String) = "$BASE/${slug(city)}"
    fun categoryUrl(city: String, cat: String) = "$BASE/${slug(city)}/$cat"
    fun chainUrl(city: String, chain: String) = if (city.isBlank()) "$BASE/volantino/$chain" else "$BASE/${slug(city)}/volantino/$chain"
    fun productUrl(product: String) = "$BASE/prodotti/${slug(product)}"

    /** The items still to buy in the shopping list ("☐ Latte" lines). */
    fun shoppingItems(text: String): List<String> = text.lines().drop(1).mapNotNull { l ->
        val t = l.trim()
        if (t.startsWith("☐")) t.removePrefix("☐").trim().takeIf { it.isNotEmpty() } else null
    }

    // ----- settings -----
    private fun prefs(c: Context) = c.getSharedPreferences("wl_flyers", Context.MODE_PRIVATE)
    fun city(c: Context): String = prefs(c).getString("city", null)
        ?: c.getSharedPreferences("wl_weather", Context.MODE_PRIVATE).getString("city", null).orEmpty().substringBefore(',').trim().takeIf { it != "La tua zona" }.orEmpty()
    fun setCity(c: Context, v: String) = prefs(c).edit().putString("city", v.trim()).apply()
    fun favorites(c: Context): List<String> = prefs(c).getString("favs", "").orEmpty().split('|').filter { it.isNotBlank() }
    fun toggleFavorite(c: Context, chain: String) {
        val f = favorites(c).toMutableList(); if (!f.remove(chain)) f += chain
        prefs(c).edit().putString("favs", f.joinToString("|")).apply()
    }
}
