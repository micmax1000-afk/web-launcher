package com.weblauncher.app

import android.util.Log

/**
 * Runs the text recognisers on the phone's own regex engine (ICU), which differs from the JVM used by
 * unit tests. Started by the emulator test in CI: a failure is logged and makes the check fail.
 */
object SelfTest {
    const val EXTRA = "wl_selftest"
    const val TAG = "WL_SELFTEST"
    const val EXTRA_THEME = "wl_theme_check"
    const val EXTRA_DIALOG = "wl_dialog_check"

    fun run(activity: MainActivity): Boolean {
        val checks = listOf<Pair<String, () -> Any?>>(
            "promemoria domani" to { Reminders.parse("ricordami domani alle 9 di chiamare Marco") },
            "promemoria venerdì" to { Reminders.parse("venerdì alle 18:30 palestra") },
            "promemoria feriali" to { Reminders.parse("dal lunedì al venerdì alle 7:30 sveglia") },
            "promemoria ogni lunedì" to { Reminders.parse("ogni lunedì alle 20 spazzatura") },
            "promemoria mese" to { Reminders.parse("il 5 di ogni mese pagare affitto") },
            "promemoria tra" to { Reminders.parse("tra mezz'ora spegnere il forno") },
            "calcolo" to { Calc.evaluate("15% di 230") },
            "unità" to { Calc.evaluate("10 km in miglia") },
            "valuta" to { Calc.currencyQuery("50 dollari in euro") },
            "spesa a voce" to { Expenses.parseVoice("ho speso 12,50 al supermercato") },
            "categoria spesa" to { Expenses.guess("benzina") },
            "pacco" to { Parcels.carrier("RR123456789IT") },
            "anti-phishing" to { PhishingGuard.check("https://poste-verifica-accesso.top/login") },
            "domanda" to { activity.looksLikeQuestion("perché il cielo è blu") },
            "notizie borsa" to { Stocks.parseNewsTitles("<rss><item><title><![CDATA[Eni sale]]></title></item></rss>") },
            "calendario a voce" to { CalendarVoice.clean("aggiungi al calendario cena da Luca sabato alle 20") },
            "compleanni" to { Birthdays.parseDate("--03-14") },
            "ricorrenti" to { Finance.dueMonths(202601, 5, java.util.Calendar.getInstance()) },
            "aggiornamenti" to { UpdateLogic.codeFromTag("v7.4.1-b1200") }
        )
        var ok = true
        checks.forEach { (name, f) ->
            runCatching { f() }.onFailure { ok = false; Log.e(TAG, "ERRORE $name", it) }
        }
        Log.i(TAG, if (ok) "SELFTEST OK" else "SELFTEST FALLITO")
        return ok
    }
}
