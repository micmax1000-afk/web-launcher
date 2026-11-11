package com.weblauncher.app

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Risultati sportivi dai dati pubblici di ESPN (non ufficiali: possono cambiare). Parsing puro e tollerante. */
object Sports {
    data class League(val id: String, val sport: String, val code: String, val name: String, val icon: String, val table: Boolean)
    data class Match(
        val id: String, val league: String, val start: Long, val state: String, val detail: String,
        val home: String, val away: String, val homeScore: String, val awayScore: String,
        val homeWin: Boolean, val awayWin: Boolean, val name: String = ""
    ) {
        val live get() = state == "in"
        val done get() = state == "post"
        /** For events without two sides (Formula 1, tournaments). */
        val single get() = home.isBlank() && away.isBlank()
    }
    data class Row(val pos: Int, val team: String, val played: String, val points: String, val extra: String)
    data class Table(val name: String, val rows: List<Row>)

    val LEAGUES = listOf(
        League("ita.1", "soccer", "ita.1", "Serie A", "⚽", true),
        League("ita.2", "soccer", "ita.2", "Serie B", "⚽", true),
        League("ita.coppa_italia", "soccer", "ita.coppa_italia", "Coppa Italia", "🏆", false),
        League("uefa.champions", "soccer", "uefa.champions", "Champions League", "⭐", true),
        League("uefa.europa", "soccer", "uefa.europa", "Europa League", "🟠", true),
        League("uefa.europa.conf", "soccer", "uefa.europa.conf", "Conference League", "🟢", true),
        League("eng.1", "soccer", "eng.1", "Premier League", "🏴", true),
        League("esp.1", "soccer", "esp.1", "Liga", "🇪🇸", true),
        League("ger.1", "soccer", "ger.1", "Bundesliga", "🇩🇪", true),
        League("fra.1", "soccer", "fra.1", "Ligue 1", "🇫🇷", true),
        League("nba", "basketball", "nba", "NBA", "🏀", true),
        League("atp", "tennis", "atp", "Tennis ATP", "🎾", false),
        League("wta", "tennis", "wta", "Tennis WTA", "🎾", false),
        League("f1", "racing", "f1", "Formula 1", "🏎", false)
    )
    fun league(id: String) = LEAGUES.firstOrNull { it.id == id }

    fun scoreboardUrl(l: League, from: Date, to: Date): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        return "https://site.api.espn.com/apis/site/v2/sports/${l.sport}/${l.code}/scoreboard?dates=${f.format(from)}-${f.format(to)}&limit=200&lang=it"
    }
    /** Address variants, from the richest to the simplest: ESPN refuses some of them for some sports. */
    fun scoreboardUrls(l: League, now: Long = System.currentTimeMillis()): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        val base = "https://site.api.espn.com/apis/site/v2/sports/${l.sport}/${l.code}/scoreboard"
        fun d(days: Int) = f.format(Date(now + days * 86_400_000L))
        return listOf(
            "$base?dates=${d(-4)}-${d(7)}&limit=200&lang=it",
            "$base?dates=${d(-4)}-${d(7)}&limit=200",
            "$base?dates=${d(-2)}-${d(3)}",
            base
        )
    }
    /** The first variant that answers; also says which one (for the automatic test). */
    fun fetchScoreboard(l: League): Pair<List<Match>, Int>? {
        val urls = scoreboardUrls(l)
        urls.forEachIndexed { i, u ->
            val body = get(u) ?: return@forEachIndexed
            var list = parseScoreboard(body, l.id)
            if (i == urls.lastIndex) {
                // only the current round came back (calcio, NBA): ask the next days one by one for the matches to come
                val f = SimpleDateFormat("yyyyMMdd", Locale.ROOT); val now = System.currentTimeMillis()
                val extra = (1..7).flatMap { d -> get(urls.last() + "?dates=" + f.format(Date(now + d * 86_400_000L)))?.let { parseScoreboard(it, l.id) }.orEmpty() }
                list = (list + extra).distinctBy { it.id }.sortedBy { it.start }
            }
            return list to i
        }
        return null
    }
    fun standingsUrl(l: League) = "https://site.api.espn.com/apis/v2/sports/${l.sport}/${l.code}/standings"

    private fun JsonObject.str(k: String): String = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String) = get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mmX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            runCatching { return SimpleDateFormat(p, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time }
        }
        return 0
    }

    private fun side(c: JsonObject): String = c.obj("team")?.let { it.str("shortDisplayName").ifBlank { it.str("displayName") } }
        ?: c.obj("athlete")?.let { it.str("shortName").ifBlank { it.str("displayName") } }
        ?: c.arr("athletes").joinToString(" / ") { it.obj("athlete")?.str("shortName") ?: it.str("displayName") }.ifBlank { c.str("name") }

    private fun score(c: JsonObject): String {
        c.get("score")?.let { s -> if (s.isJsonPrimitive) return s.asString; if (s.isJsonObject) return s.asJsonObject.str("displayValue") }
        val sets = c.arr("linescores").map { it.str("value").substringBefore('.') }.filter { it.isNotBlank() }
        return sets.joinToString(" ")
    }

    private fun matchFrom(league: String, ev: JsonObject, comp: JsonObject, evName: String): Match? {
        val st = (comp.obj("status") ?: ev.obj("status"))?.obj("type")
        val state = st?.str("state").orEmpty().ifBlank { "pre" }
        val detail = st?.str("shortDetail").orEmpty().ifBlank { st?.str("detail").orEmpty() }
        val cs = comp.arr("competitors")
        val start = parseDate(comp.str("date").ifBlank { ev.str("date") })
        if (cs.size == 2) {
            val home = cs.firstOrNull { it.str("homeAway") == "home" } ?: cs[0]
            val away = cs.firstOrNull { it.str("homeAway") == "away" && it !== home } ?: cs.first { it !== home }
            return Match(comp.str("id").ifBlank { ev.str("id") }, league, start, state, detail, side(home), side(away), score(home), score(away),
                home.get("winner")?.asBoolean == true, away.get("winner")?.asBoolean == true, evName)
        }
        if (cs.isEmpty() && evName.isBlank()) return null
        // Formula 1 and the like: the event, with the winner when it is over
        val win = cs.firstOrNull { it.get("winner")?.asBoolean == true } ?: cs.firstOrNull { it.str("order") == "1" }
        val label = evName.ifBlank { ev.str("name") }
        return Match(comp.str("id").ifBlank { ev.str("id") }, league, start, state, if (state == "post" && win != null) "Vince ${side(win)}" else detail, "", "", "", "", false, false, label)
    }

    /** A scoreboard: one match per competition (tennis events contain many, grouped). */
    fun parseScoreboard(json: String, league: String): List<Match> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val out = mutableListOf<Match>()
        root.arr("events").forEach { ev ->
            val evName = ev.str("shortName").ifBlank { ev.str("name") }
            val groups = ev.arr("groupings")
            if (groups.isNotEmpty()) {
                groups.forEach { g -> g.arr("competitions").forEach { c -> matchFrom(league, ev, c, evName)?.let { out += it } } }
            } else {
                val comps = ev.arr("competitions")
                if (comps.size == 1 && comps[0].arr("competitors").size != 2) matchFrom(league, ev, comps[0], evName)?.let { out += it }
                else comps.forEach { c -> matchFrom(league, ev, c, if (comps.size > 1) evName else "")?.let { out += it } }
            }
        }
        out.sortedBy { it.start }
    }.getOrDefault(emptyList())

    fun parseStandings(json: String, sport: String = "soccer"): List<Table> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val groups = root.arr("children").ifEmpty { listOf(root) }
        groups.mapNotNull { g ->
            val entries = g.obj("standings")?.arr("entries").orEmpty()
            if (entries.isEmpty()) return@mapNotNull null
            fun stat(e: JsonObject, vararg names: String) = e.arr("stats").firstOrNull { s -> names.any { it.equals(s.str("name"), true) || it.equals(s.str("abbreviation"), true) || it.equals(s.str("type"), true) } }
                ?.let { s -> s.str("displayValue").ifBlank { s.str("value").substringBefore(".0") } }.orEmpty()
            val basket = sport == "basketball"
            val rows = entries.mapIndexed { i, e ->
                val team = e.obj("team")?.let { it.str("shortDisplayName").ifBlank { it.str("displayName") } } ?: ""
                val w = stat(e, "wins", "W"); val l = stat(e, "losses", "L"); val dr = stat(e, "ties", "draws", "T", "D")
                if (basket) {
                    // NBA: ordered by winning percentage (the "points" there are a point difference, not standings points)
                    val pct = stat(e, "winPercent", "PCT")
                    Row(0, team, ((w.toIntOrNull() ?: 0) + (l.toIntOrNull() ?: 0)).toString(), pct, "$w-$l")
                } else {
                    val rank = stat(e, "rank").toIntOrNull() ?: (i + 1)
                    val wdl = if (w.isNotBlank() && l.isNotBlank()) listOf(w, dr.ifBlank { null }, l).filterNotNull().joinToString("-") else ""
                    Row(rank, team, stat(e, "gamesPlayed", "GP"), stat(e, "points", "PTS"), wdl)
                }
            }.let { rs -> if (basket) rs.sortedByDescending { it.points.replace(",", ".").toDoubleOrNull() ?: 0.0 }.mapIndexed { i, r -> r.copy(pos = i + 1) } else rs.sortedBy { it.pos } }
            Table(g.str("name").ifBlank { g.str("abbreviation") }, rows)
        }
    }.getOrDefault(emptyList())

    /** Text for a match row: "Inter 2 – 1 Milan" or "Inter – Milan". */
    fun line(m: Match): String = when {
        m.single -> m.name
        m.state == "pre" -> "${m.home} – ${m.away}"
        else -> "${m.home} ${m.homeScore} – ${m.awayScore} ${m.away}"
    }

    // ----- settings -----
    private fun prefs(c: Context) = c.getSharedPreferences("wl_sport", Context.MODE_PRIVATE)
    fun followed(c: Context): List<String> = prefs(c).getString("leagues", "ita.1|uefa.champions")!!.split('|').filter { league(it) != null }
    fun setFollowed(c: Context, ids: List<String>) = prefs(c).edit().putString("leagues", ids.joinToString("|")).apply()
    fun teams(c: Context): Set<String> = prefs(c).getStringSet("teams", emptySet()).orEmpty()
    fun toggleTeam(c: Context, t: String) { val s = teams(c).toMutableSet(); if (!s.remove(t)) s += t; prefs(c).edit().putStringSet("teams", s).apply() }

    /** Network (call off the main thread). Returns null when ESPN does not answer properly. */
    fun get(url: String): String? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WebLauncher")
        try { if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { conn.disconnect() }
    }.getOrNull()
}
