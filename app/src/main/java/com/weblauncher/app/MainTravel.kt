package com.weblauncher.app

import android.content.Context.MODE_PRIVATE
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

// ======================= Travel search: flights, hotels, trains =======================

internal fun MainActivity.showTravelPage() {
    val prefs = getSharedPreferences("wl_travel", MODE_PRIVATE)
    val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val nice = SimpleDateFormat("EEE d MMM yyyy", Locale.ITALIAN)
    var depart: Long? = prefs.getLong("depart", 0L).takeIf { it > System.currentTimeMillis() - 86_400_000L }
    var back: Long? = prefs.getLong("back", 0L).takeIf { depart != null && it >= depart!! }
    var people = prefs.getInt("people", 2)

    openPage("Viaggi") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun field(hint: String, value: String?): EditText = EditText(this).apply {
            this.hint = hint; setText(value.orEmpty()); setSingleLine(); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(palette.text); setHintTextColor(palette.textMuted)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(4)) }
        }
        sectionLabel(body, "DOVE")
        val from = field("Da (città o codice aeroporto, es. Torino o TRN)", prefs.getString("from", null))
        val to = field("A (città o codice, es. Londra o LON)", prefs.getString("to", null))
        body.addView(from); body.addView(to)

        sectionLabel(body, "QUANDO E QUANTI")
        val dates = TextView(this).apply { textSize = 16f; setTextColor(palette.text); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        fun refreshDates() {
            dates.text = "Andata: " + (depart?.let { nice.format(it) } ?: "—") + "\nRitorno: " + (back?.let { nice.format(it) } ?: "solo andata") +
                "\nPersone: $people"
        }
        refreshDates()
        body.addView(dates)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        fun small(label: String, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { action() }
        }
        row.addView(small("📅 Andata") { pickDate("Andata", depart ?: System.currentTimeMillis()) { depart = it; if (back != null && back!! < it) back = null; refreshDates() } })
        row.addView(small("📅 Ritorno") {
            pickDate("Ritorno", back ?: depart ?: System.currentTimeMillis()) { if (depart != null && it < depart!!) toast("Il ritorno è prima dell'andata") else { back = it; refreshDates() } }
        })
        row.addView(small("👤 $people") {})
        (row.getChildAt(2) as MaterialButton).setOnClickListener { b ->
            people = if (people >= 9) 1 else people + 1; (b as MaterialButton).text = "👤 $people"; refreshDates()
        }
        body.addView(row)
        body.addView(small("Togli il ritorno (solo andata)") { back = null; refreshDates() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(4)) }
        })

        fun query(): TravelLinks.Query? {
            val f = from.text.toString().trim(); val t = to.text.toString().trim()
            if (t.isEmpty()) { toast("Scrivi la destinazione"); return null }
            prefs.edit().putString("from", f).putString("to", t).putLong("depart", depart ?: 0L).putLong("back", back ?: 0L).putInt("people", people).apply()
            return TravelLinks.Query(f, t, depart?.let { iso.format(it) }, back?.let { iso.format(it) }, people)
        }
        fun go(needsFrom: Boolean, link: (TravelLinks.Query) -> String) {
            val q = query() ?: return
            if (needsFrom && q.from.isEmpty()) { toast("Scrivi anche la partenza"); return }
            dialog.dismiss(); openInBrowser(link(q))
        }
        sectionLabel(body, "✈️ VOLI")
        listRow(body, "Google Voli", "Confronta compagnie e prezzi; puoi attivare gli avvisi di prezzo", onClick = { go(true) { TravelLinks.googleFlights(it) } })
        listRow(body, "Skyscanner", "Ricerca diretta se usi i codici (TRN, MXP, LON…)", onClick = { go(true) { TravelLinks.skyscanner(it) } })
        sectionLabel(body, "🏨 DORMIRE")
        listRow(body, "Booking", "Hotel e appartamenti a destinazione", onClick = { go(false) { TravelLinks.booking(it) } })
        listRow(body, "Airbnb", "Case e stanze", onClick = { go(false) { TravelLinks.airbnb(it) } })
        sectionLabel(body, "🚆 TRENI")
        listRow(body, "Orari e prezzi (Trainline)", "Trenitalia e Italo insieme", onClick = { go(true) { TravelLinks.trainline(it) } })
        listRow(body, "Trenitalia", "Sito ufficiale", onClick = { dialog.dismiss(); openInBrowser(TravelLinks.TRENITALIA) })
        listRow(body, "Italo", "Sito ufficiale", onClick = { dialog.dismiss(); openInBrowser(TravelLinks.ITALO) })
        emptyLabel(body, "La ricerca si apre già compilata nel browser: niente app da installare.")
    }
}
