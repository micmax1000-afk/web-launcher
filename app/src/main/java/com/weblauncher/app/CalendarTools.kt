package com.weblauncher.app

import java.util.Calendar

/** Parti pure del calendario dell'app (provate nei test): inizio settimana, griglia del mese, compleanni nel giorno. */
object CalendarTools {
    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Il lunedì della settimana di [t], a mezzanotte. */
    fun weekStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(t)
        val back = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        add(Calendar.DAY_OF_YEAR, -back)
    }.timeInMillis

    /** I giorni della settimana che inizia a [start] (gestisce il cambio dell'ora). */
    fun days(start: Long, n: Int): List<Long> = (0 until n).map { i -> Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, i) }.timeInMillis }

    /** La griglia del mese di [t]: da lunedì prima del giorno 1 a domenica dopo l'ultimo (5 o 6 righe). */
    fun monthGrid(t: Long): List<Long> {
        val first = Calendar.getInstance().apply { timeInMillis = dayStart(t); set(Calendar.DAY_OF_MONTH, 1) }
        val start = weekStart(first.timeInMillis)
        val last = Calendar.getInstance().apply { timeInMillis = first.timeInMillis; set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)) }.timeInMillis
        val cells = mutableListOf<Long>()
        var d = start
        while (cells.size % 7 != 0 || d <= last) { cells += d; d = Calendar.getInstance().apply { timeInMillis = d; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis }
        return cells
    }

    fun sameMonth(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.MONTH) == y.get(Calendar.MONTH)
    }

    /** Il compleanno (mese 1-12, giorno) cade nel giorno [t]? Il 29 febbraio, negli anni non bisestili, si festeggia il 28. */
    fun birthdayOn(month: Int, day: Int, t: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        if (c.get(Calendar.MONTH) + 1 != month) return false
        val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        return c.get(Calendar.DAY_OF_MONTH) == day.coerceAtMost(max)
    }
}
