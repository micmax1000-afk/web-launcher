package com.weblauncher.app

import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun dayLabel(t: Long): String {
    if (t <= 0) return ""
    val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    val m = Calendar.getInstance().apply { timeInMillis = t }
    val hm = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(t))
    val d = m.get(Calendar.DAY_OF_YEAR) to m.get(Calendar.YEAR)
    c.add(Calendar.DAY_OF_YEAR, 1); val tom = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    c.add(Calendar.DAY_OF_YEAR, -2); val yest = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    return when (d) { today -> "Oggi $hm"; tom -> "Domani $hm"; yest -> "Ieri $hm"; else -> SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(Date(t)) }
}

internal fun MainActivity.showSportsPage() {
    openPage("Sport", "Campionati", { dlg -> chooseLeagues { dlg.dismiss(); showSportsPage() } }) { _, body, dialog ->
        val h = Handler(Looper.getMainLooper())
        var data: Map<String, List<Sports.Match>?> = emptyMap()
        fun matchRow(m: Sports.Match, showLeague: Boolean) {
            val l = Sports.league(m.league)
            val status = when { m.live -> "🔴 " + m.detail.ifBlank { "In corso" }; m.done -> if (m.single) m.detail else "Finita"; else -> dayLabel(m.start) }
            val title = (if (m.done && m.homeWin) "🏅 " else "") + Sports.line(m) + if (m.done && m.awayWin) " 🏅" else ""
            listRow(body, title, listOfNotNull(status, if (showLeague) l?.name else null, m.name.takeIf { !m.single && it.isNotBlank() && l?.sport == "tennis" }).joinToString(" · "), onClick = {
                if (m.single) return@listRow
                val teams = listOf(m.home, m.away)
                MaterialAlertDialogBuilder(this).setTitle(Sports.line(m)).setItems(teams.map { (if (it in Sports.teams(this)) "★ Non seguire " else "☆ Segui ") + it }.toTypedArray()) { _, i ->
                    Sports.toggleTeam(this, teams[i]); dialog.dismiss(); showSportsPage()
                }.show()
            })
        }
        fun render() {
            body.removeAllViews()
            if (data.isEmpty()) { emptyLabel(body, "Carico i risultati…"); return }
            val all = data.values.filterNotNull().flatten()
            val mine = Sports.teams(this)
            val fav = all.filter { it.home in mine || it.away in mine }
            if (fav.isNotEmpty()) {
                sectionLabel(body, "LE MIE SQUADRE")
                val live = fav.filter { it.live }; val next = fav.filter { it.state == "pre" }.take(4); val last = fav.filter { it.done }.takeLast(4).reversed()
                (live + last + next).distinctBy { it.id }.forEach { matchRow(it, true) }
            }
            Sports.followed(this).forEach { id ->
                val l = Sports.league(id) ?: return@forEach
                val list = data[id]
                sectionLabel(body, "${l.icon} ${l.name.uppercase(Locale.ITALIAN)}")
                when {
                    list == null -> hintLabel(body, "Dati non disponibili ora (ESPN non risponde). Riprova più tardi.")
                    list.isEmpty() -> hintLabel(body, "Nessun evento in questi giorni.")
                    else -> {
                        val live = list.filter { it.live }
                        val done = list.filter { it.done }.takeLast(if (l.sport == "tennis") 6 else 10).reversed()
                        val next = list.filter { it.state == "pre" }.take(if (l.sport == "tennis") 6 else 10)
                        live.forEach { matchRow(it, false) }
                        if (done.isNotEmpty()) { hintLabel(body, "Ultimi risultati"); done.forEach { matchRow(it, false) } }
                        if (next.isNotEmpty()) { hintLabel(body, "Prossimi"); next.forEach { matchRow(it, false) } }
                    }
                }
                if (l.table) listRow(body, "📊 Classifica ${l.name}", null, onClick = { showStandings(l) })
            }
            hintLabel(body, "Dati pubblici di ESPN, aggiornati ogni minuto mentre una partita è in corso. Tocca una partita per seguire una squadra.")
        }
        fun load() {
            io.execute {
                val now = System.currentTimeMillis()
                val from = Date(now - 4 * 86_400_000L); val to = Date(now + 7 * 86_400_000L)
                val res = Sports.followed(this).associateWith { id ->
                    val l = Sports.league(id)!!
                    Sports.get(Sports.scoreboardUrl(l, from, to))?.let { Sports.parseScoreboard(it, id) }
                }
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    data = res; render()
                    if (res.values.any { l -> l?.any { it.live } == true }) h.postDelayed({ if (dialog.isShowing) load() }, 60_000)
                }
            }
        }
        render(); load()
        dialog.setOnDismissListener { h.removeCallbacksAndMessages(null) }
    }
}

private fun MainActivity.chooseLeagues(done: () -> Unit) {
    val names = Sports.LEAGUES.map { "${it.icon} ${it.name}" }.toTypedArray()
    val cur = Sports.followed(this)
    val checked = BooleanArray(names.size) { Sports.LEAGUES[it].id in cur }
    MaterialAlertDialogBuilder(this).setTitle("Campionati da seguire").setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
        .setPositiveButton("Salva") { _, _ -> Sports.setFollowed(this, Sports.LEAGUES.filterIndexed { i, _ -> checked[i] }.map { it.id }); done() }
        .setNegativeButton("Annulla", null).show()
}

private fun MainActivity.showStandings(l: Sports.League) {
    openPage("Classifica") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        emptyLabel(body, "Carico la classifica…")
        io.execute {
            val tables = Sports.get(Sports.standingsUrl(l))?.let { Sports.parseStandings(it) }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                body.removeAllViews()
                if (tables.isNullOrEmpty()) { emptyLabel(body, "Classifica non disponibile ora."); return@runOnUiThread }
                val mine = Sports.teams(this)
                tables.forEach { t ->
                    sectionLabel(body, (if (tables.size > 1) t.name else l.name).uppercase(Locale.ITALIAN))
                    t.rows.forEach { r ->
                        body.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; setPadding(24, 14, 24, 14)
                            if (r.team in mine) setBackgroundColor(0x223B82F6)
                            fun cell(s: String, w: Float, bold: Boolean = false, end: Boolean = false) = addView(TextView(this@showStandings).apply {
                                text = s; textSize = 15f; setTextColor(palette.text); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                                if (end) gravity = android.view.Gravity.END; maxLines = 1
                            }, LinearLayout.LayoutParams(0, -2, w))
                            cell("${r.pos}", 0.7f); cell(r.team, 4f, r.team in mine); cell(r.played, 1f, end = true); cell(r.extra, 1.4f, end = true); cell(r.points, 1.2f, bold = true, end = true)
                        })
                    }
                }
                hintLabel(body, "Colonne: posizione, squadra, partite giocate, vinte-perse, punti (per l'NBA la percentuale di vittorie).")
            }
        }
    }
}

/** Automatic test on the virtual phone: does ESPN answer, and how many events/rows we read (logged, does not fail). */
internal fun MainActivity.sportsNetCheck() {
    Thread {
        val now = System.currentTimeMillis()
        listOf("ita.1", "uefa.champions", "nba", "atp", "f1").forEach { id ->
            val l = Sports.league(id)!!
            val sb = Sports.get(Sports.scoreboardUrl(l, Date(now - 7 * 86_400_000L), Date(now + 7 * 86_400_000L)))
            val ms = sb?.let { Sports.parseScoreboard(it, id) }
            val st = if (l.table) Sports.get(Sports.standingsUrl(l))?.let { Sports.parseStandings(it) } else null
            android.util.Log.i(SelfTest.TAG, "NETCHECK $id scoreboard=${if (sb == null) "ERR" else "ok"} matches=${ms?.size} sample=${ms?.firstOrNull()?.let { Sports.line(it) + " | " + it.detail }} tables=${st?.size} rows=${st?.firstOrNull()?.rows?.size} first=${st?.firstOrNull()?.rows?.firstOrNull()}")
        }
        val gz = runCatching {
            val c = java.net.URL(GialloZafferano.searchUrl("carbonara")).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile"); c.instanceFollowRedirects = true
            val code = c.responseCode; val body = if (code == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            "code=$code recipeLinks=${Regex("ricette\\.giallozafferano\\.it/[^\"]+\\.html").findAll(body).count()}"
        }.getOrElse { "ERR ${it.message}" }
        android.util.Log.i(SelfTest.TAG, "NETCHECK giallozafferano $gz")
    }.start()
}
