package com.weblauncher.app

import org.json.JSONObject

/** Allerta meteo dalle previsioni di Open-Meteo (le stesse del meteo dell'app). Non è l'allerta ufficiale della Protezione civile. */
object MeteoAlerts {
    data class Alert(val day: String, val kind: String, val title: String, val text: String)

    /** [json]: risposta di /v1/forecast con daily=weather_code,precipitation_sum,wind_gusts_10m_max,temperature_2m_min. */
    fun check(json: String, city: String): List<Alert> {
        val daily = runCatching { JSONObject(json).getJSONObject("daily") }.getOrNull() ?: return emptyList()
        val days = daily.optJSONArray("time") ?: return emptyList()
        val code = daily.optJSONArray("weather_code"); val rain = daily.optJSONArray("precipitation_sum")
        val gust = daily.optJSONArray("wind_gusts_10m_max"); val tmin = daily.optJSONArray("temperature_2m_min")
        val out = mutableListOf<Alert>()
        for (i in 0 until days.length()) {
            val day = days.optString(i)
            val when_ = if (i == 0) "Oggi" else if (i == 1) "Domani" else day
            val c = code?.optInt(i, -1) ?: -1
            val mm = rain?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val g = gust?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val t = tmin?.optDouble(i, 99.0)?.takeIf { !it.isNaN() } ?: 99.0
            if (c in 95..99) out += Alert(day, "temporali", "⛈ $when_ temporali a $city", "Previsti temporali" + if (mm >= 1) ", circa ${mm.toInt()} mm di pioggia." else ".")
            else if (mm >= 20) out += Alert(day, "pioggia", "🌧 $when_ pioggia forte a $city", "Circa ${mm.toInt()} mm di pioggia previsti.")
            if (c in 71..77 || c == 85 || c == 86) out += Alert(day, "neve", "❄️ $when_ neve a $city", "Prevista neve.")
            if (g >= 60) out += Alert(day, "vento", "💨 $when_ vento forte a $city", "Raffiche fino a ${g.toInt()} km/h.")
            if (t <= -2) out += Alert(day, "gelo", "🥶 $when_ gelo a $city", "Minima di ${t.toInt()}°: attenzione al ghiaccio.")
        }
        return out
    }

    /** Ore di silenzio: [from] e [to] in minuti dalla mezzanotte; l'intervallo può passare la mezzanotte. */
    fun inQuiet(minuteOfDay: Int, from: Int, to: Int): Boolean =
        if (from == to) false else if (from < to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
}
