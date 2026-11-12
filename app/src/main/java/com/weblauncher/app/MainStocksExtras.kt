package com.weblauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.UUID

private const val UP = 0xFF16A34A.toInt()
private const val DOWN = 0xFFDC2626.toInt()
private val PIE_COLORS = intArrayOf(0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFFF59E0B.toInt(), 0xFF9333EA.toInt(), 0xFFDC2626.toInt(), 0xFF0891B2.toInt(), 0xFF64748B.toInt())

/** A simple ring chart (portfolio split). */
class PieChartView(context: Context) : android.view.View(context) {
    var slices: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(canvas: Canvas) {
        val total = slices.sum().takeIf { it > 0 } ?: return
        val s = minOf(width, height).toFloat(); val stroke = s * 0.22f
        p.strokeWidth = stroke
        val r = RectF((width - s) / 2 + stroke / 2, (height - s) / 2 + stroke / 2, (width + s) / 2 - stroke / 2, (height + s) / 2 - stroke / 2)
        var a = -90f
        slices.forEachIndexed { i, v -> val sw = (v / total * 360).toFloat(); p.color = PIE_COLORS[i % PIE_COLORS.size]; canvas.drawArc(r, a, sw, false, p); a += sw }
    }
}

private fun typeLabel(t: String) = when (t.uppercase(Locale.ROOT)) {
    "EQUITY" -> "Azioni"; "ETF" -> "ETF"; "MUTUALFUND" -> "Fondi"; "BOND", "GOVT" -> "Obbligazioni"
    "CRYPTOCURRENCY" -> "Cripto"; "FUTURE" -> "Materie prime"; "CURRENCY" -> "Valute"; "INDEX" -> "Indici"; else -> "Altro"
}

// ======================= Main markets strip =======================

internal fun MainActivity.marketsStrip(body: LinearLayout) {
    val palette = AppRepository.palette(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val state = Stocks.MARKETS.filter { it.zone.isNotBlank() }.distinctBy { it.zone }.joinToString("  ") { m ->
        val city = when (m.zone) { "Europe/Rome" -> "Milano"; "Europe/Berlin" -> "Europa"; else -> "New York" }
        (if (Stocks.isOpen(m) == true) "🟢 $city aperta" else "⚪ $city chiusa")
    }
    sectionLabel(body, "MERCATI")
    hintLabel(body, state)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(6)) }
    body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    Stocks.MARKETS.forEach { m ->
        val price = TextView(this).apply { textSize = 13f; setTextColor(palette.textMuted); text = "…" }
        val pct = TextView(this).apply { textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(palette.text) }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(palette.card); setStroke(1, palette.stroke) }
            layoutParams = LinearLayout.LayoutParams(dp(112), -2).apply { marginEnd = dp(8) }
            addView(TextView(this@marketsStrip).apply { text = m.name; textSize = 13f; setTextColor(palette.text); maxLines = 1 })
            addView(pct); addView(price)
            setOnClickListener { showStockDetail(Stocks.Asset(m.symbol, m.name, "INDEX")) }
        })
        Stocks.quotes(this)[m.symbol]?.let { q -> pct.text = Stocks.fmtPct(q.pct); price.text = Stocks.fmtPrice(q.price) }
        io.execute {
            val ch = runCatching { Stocks.chart(m.symbol) }.getOrNull()
            runOnUiThread {
                if (ch == null) { if (pct.text.isNullOrBlank()) price.text = "n.d."; return@runOnUiThread }
                Stocks.remember(this, m.symbol, ch)
                pct.text = Stocks.fmtPct(ch.changePct); pct.setTextColor(if (ch.change >= 0) UP else DOWN)
                price.text = Stocks.fmtPrice(ch.price, ch.currency)
            }
        }
    }
}

// ======================= Portfolio =======================

internal fun MainActivity.showPortfolioPage() {
    openPage("Il mio portafoglio", "Esporta", { _ ->
        val h = Stocks.holdings(this)
        if (h.isEmpty()) toast("Il portafoglio è vuoto") else shareTextFile("portafoglio.csv", Stocks.csv(h, Stocks.quotes(this)), "text/csv", "Esporta il portafoglio")
    }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val holdings = Stocks.holdings(this)
            if (holdings.isEmpty()) {
                emptyLabel(body, "Nessun titolo nel portafoglio.\nApri un titolo dalla Borsa e tocca «Ho queste azioni».")
                listRow(body, "🧮 Simula un piano di accumulo (PAC)", "Quanto diventa un versamento mensile", onClick = { savingsPlanDialog() })
                return
            }
            val quotes = Stocks.quotes(this)
            val totals = Stocks.portfolio(holdings, quotes)
            Stocks.recordHistory(this, totals)
            sectionLabel(body, "TOTALE")
            totals.forEach { t ->
                val tax = holdings.filter { quotes[it.symbol]?.currency == t.currency }.sumOf { h ->
                    quotes[h.symbol]?.let { q -> Stocks.taxOnGain(h.qty * (q.price - h.cost), h.govBond) } ?: 0.0
                }
                body.addView(statRow(
                    statTile("Valore", Stocks.fmtPrice(t.value, t.currency)),
                    statTile("Guadagno", (if (t.gain >= 0) "+" else "−") + Stocks.fmtPrice(kotlin.math.abs(t.gain), t.currency), Stocks.fmtPct(t.gainPct), if (t.gain >= 0) UP else DOWN),
                    statTile("Tasse stimate", Stocks.fmtPrice(tax, t.currency), "se vendi oggi")))
            }
            // split by type
            val byType = holdings.mapNotNull { h -> quotes[h.symbol]?.let { q ->
                val type = Stocks.watch(this).firstOrNull { it.symbol == h.symbol }?.type.orEmpty()
                (if (h.govBond) "Titoli di Stato" else typeLabel(type)) to h.qty * q.price } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedByDescending { it.second }
            if (byType.size > 1 || totals.size == 1) {
                sectionLabel(body, "COME È DIVISO")
                val tot = byType.sumOf { it.second }
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4))
                    addView(PieChartView(this@showPortfolioPage).apply { slices = byType.map { it.second } }, LinearLayout.LayoutParams(dp(120), dp(120)))
                    addView(LinearLayout(this@showPortfolioPage).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
                        byType.forEachIndexed { i, (n, v) ->
                            addView(TextView(this@showPortfolioPage).apply {
                                text = "●  $n  ${String.format(Locale.ITALIAN, "%.0f%%", v / tot * 100)}"; textSize = 14f; setTextColor(palette.text); setPadding(0, dp(3), 0, dp(3))
                                setTextColor(PIE_COLORS[i % PIE_COLORS.size])
                            })
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                if (totals.size > 1) hintLabel(body, "Le percentuali mescolano valute diverse senza cambio: sono indicative.")
            }
            // history
            val hist = Stocks.history(this)[totals.first().currency].orEmpty()
            sectionLabel(body, "ANDAMENTO DEL VALORE")
            if (hist.size >= 2) {
                body.addView(LineChartView(this).apply { series1 = hist.map { it.second }; textColor = palette.textMuted }, LinearLayout.LayoutParams(-1, dp(170)))
                hintLabel(body, "Dal ${hist.first().first} a oggi (${hist.size} giorni registrati, quando apri la Borsa).")
            } else hintLabel(body, "Il grafico si riempie giorno dopo giorno, ogni volta che apri il portafoglio.")
            sectionLabel(body, "TITOLI")
            holdings.forEach { h ->
                val q = quotes[h.symbol]
                val g = q?.let { h.qty * (it.price - h.cost) }
                listRow(body, h.name + if (h.govBond) " 🏛" else "",
                    if (q == null) "${Stocks.fmtNum(h.qty)} quote · prezzo non ancora letto"
                    else "${Stocks.fmtNum(h.qty)} × ${Stocks.fmtPrice(q.price, q.currency)} = ${Stocks.fmtPrice(h.qty * q.price, q.currency)} · ${if (g!! >= 0) "+" else "−"}${Stocks.fmtPrice(kotlin.math.abs(g), q.currency)}",
                    onClick = { dialog.dismiss(); showStockDetail(Stocks.Asset(h.symbol, h.name)) },
                    onLong = {
                        MaterialAlertDialogBuilder(this).setTitle(h.name)
                            .setItems(arrayOf(if (h.govBond) "Non è un titolo di Stato (tasse 26%)" else "🏛 È un titolo di Stato (tasse 12,5%)")) { _, _ ->
                                Stocks.saveHoldings(this, Stocks.holdings(this).map { if (it.symbol == h.symbol) it.copy(govBond = !h.govBond) else it }); render()
                            }.show()
                    })
            }
            sectionLabel(body, "STRUMENTI")
            listRow(body, "🧮 Simula un piano di accumulo (PAC)", "Quanto diventa un versamento mensile", onClick = { savingsPlanDialog() })
            listRow(body, "📤 Esporta in CSV", "Si apre con Excel o Fogli Google", onClick = { shareTextFile("portafoglio.csv", Stocks.csv(Stocks.holdings(this), Stocks.quotes(this)), "text/csv", "Esporta il portafoglio") })
            hintLabel(body, "Tasse stimate: 26% sui guadagni (12,5% per i titoli di Stato, tieni premuto per indicarlo). È una stima: non considera minusvalenze, commissioni, bolli né il regime fiscale del tuo conto. Prezzi Yahoo Finance in ritardo.")
        }
        render()
        // refresh the prices in the background, then redraw
        io.execute {
            Stocks.holdings(this).forEach { h -> runCatching { Stocks.chart(h.symbol) }.getOrNull()?.let { Stocks.remember(this, h.symbol, it) } }
            runOnUiThread { if (dialog.isShowing) render() }
        }
    }
}

internal fun MainActivity.savingsPlanDialog() {
    val d = resources.displayMetrics.density
    fun field(h: String, v: String) = EditText(this).apply { hint = h; setText(v); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
    val monthly = field("Versamento al mese (€)", "100"); val years = field("Per quanti anni", "10"); val rate = field("Rendimento medio annuo (%)", "4")
    val out = TextView(this).apply { textSize = 15f; setPadding(0, (12 * d).toInt(), 0, 0) }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        addView(TextView(this@savingsPlanDialog).apply { text = "€ al mese" }); addView(monthly)
        addView(TextView(this@savingsPlanDialog).apply { text = "Anni" }); addView(years)
        addView(TextView(this@savingsPlanDialog).apply { text = "Rendimento annuo stimato (%)" }); addView(rate); addView(out) }
    fun calc() {
        val m = Calc.num(monthly.text.toString()) ?: 0.0; val y = Calc.num(years.text.toString())?.toInt()?.coerceIn(1, 60) ?: 1
        val r = Calc.num(rate.text.toString()) ?: 0.0
        val p = Stocks.savingsPlan(m, y, r)
        out.text = "Versi in tutto: ${Stocks.fmtPrice(p.invested, "EUR")}\nValore finale: ${Stocks.fmtPrice(p.value, "EUR")}\n" +
            "Guadagno: ${Stocks.fmtPrice(p.gain, "EUR")}\nDopo le tasse (26%): ${Stocks.fmtPrice(p.net, "EUR")}\n\nSolo una simulazione: i rendimenti veri cambiano ogni anno e possono essere negativi."
    }
    listOf(monthly, years, rate).forEach { it.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = calc()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }) }
    calc()
    MaterialAlertDialogBuilder(this).setTitle("🧮 Piano di accumulo").setView(android.widget.ScrollView(this).apply { addView(box) }).setPositiveButton("Chiudi", null).show()
}

// ======================= Detail extras: % alerts, dividends, news, comparison =======================

internal fun MainActivity.pctAlertDialog(a: Stocks.Asset) {
    val d = resources.displayMetrics.density
    val input = EditText(this).apply { hint = "Percentuale, es. 3"; setText("3"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
    val box = android.widget.FrameLayout(this).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(input) }
    fun save(up: Boolean) {
        val v = Calc.num(input.text.toString())?.takeIf { it > 0 } ?: run { toast("Scrivi una percentuale"); return }
        Stocks.saveAlerts(this, Stocks.alerts(this) + Stocks.Alert(UUID.randomUUID().toString(), a.symbol, a.name, up, 0.0, pct = if (up) v else -v))
        if (Stocks.watch(this).none { it.symbol == a.symbol }) Stocks.saveWatch(this, Stocks.watch(this) + a)
        ensureNotificationPermission()
        toast("🔔 Ti avviso se ${a.name} ${if (up) "sale" else "scende"} del ${Stocks.fmtNum(v)}% in un giorno")
    }
    MaterialAlertDialogBuilder(this).setTitle("Avviso sulla variazione · ${a.name}").setMessage("Una notifica al giorno al massimo, controllo ogni 30 minuti.").setView(box)
        .setPositiveButton("Se sale") { _, _ -> save(true) }.setNegativeButton("Se scende") { _, _ -> save(false) }
        .setNeutralButton("Annulla", null).show()
}

internal fun MainActivity.alertLabel(al: Stocks.Alert) =
    if (al.pct != 0.0) "${if (al.pct > 0) "Sale" else "Scende"} del ${Stocks.fmtNum(kotlin.math.abs(al.pct))}% in un giorno"
    else "${if (al.above) "Sopra" else "Sotto"} ${Stocks.fmtPrice(al.price)}"

/** Dividends and news, loaded in the background into [body]. */
internal fun MainActivity.stockExtrasSections(a: Stocks.Asset, body: LinearLayout) {
    val divBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val newsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(divBox); body.addView(newsBox)
    io.execute {
        val divs = if (a.type.uppercase(Locale.ROOT) in listOf("INDEX", "CURRENCY", "FUTURE", "CRYPTOCURRENCY")) emptyList() else Stocks.dividends(a.symbol)
        val news = Stocks.newsWithLinks(a.name)
        runOnUiThread {
            if (divs.isNotEmpty()) {
                sectionLabel(divBox, "DIVIDENDI")
                val (year, next) = Stocks.dividendSummary(divs)
                val price = Stocks.quotes(this)[a.symbol]?.price
                listRow(divBox, "💶 Ultimi 12 mesi: ${Stocks.fmtNum(year)}" + (price?.takeIf { it > 0 && year > 0 }?.let { " · rendimento ${String.format(Locale.ITALIAN, "%.1f%%", year / it * 100)}" } ?: ""),
                    next?.let { "Prossimo stimato verso ${java.text.SimpleDateFormat("MMMM yyyy", Locale.ITALIAN).format(java.util.Date(it))} (in base alle date passate)" }, onClick = {})
                divs.takeLast(6).reversed().forEach { dv ->
                    listRow(divBox, java.text.SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(java.util.Date(dv.time)), "Stacco ${Stocks.fmtNum(dv.amount)} per azione", onClick = {})
                }
            }
            if (news.isNotEmpty()) {
                sectionLabel(newsBox, "NOTIZIE")
                news.take(6).forEach { n -> listRow(newsBox, n.title, n.source.ifBlank { null }, onClick = { if (n.link.isNotBlank()) openInBrowser(n.link) }) }
            }
        }
    }
}

internal fun MainActivity.compareDialog(a: Stocks.Asset) {
    val others = (Stocks.watch(this) + Stocks.PRESETS).filter { it.symbol != a.symbol }.distinctBy { it.symbol }
    MaterialAlertDialogBuilder(this).setTitle("Confronta ${a.name} con…").setItems(others.map { it.name }.toTypedArray()) { _, w -> showComparePage(a, others[w]) }.show()
}

internal fun MainActivity.showComparePage(a: Stocks.Asset, b: Stocks.Asset) {
    openPage("Confronto") { _, body, _ ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val legend = TextView(this).apply { textSize = 15f; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val chart = LineChartView(this).apply { color1 = 0xFF2563EB.toInt(); color2 = 0xFFF59E0B.toInt(); textColor = palette.textMuted }
        val ranges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        body.addView(legend); body.addView(chart, LinearLayout.LayoutParams(-1, dp(220))); body.addView(ranges)
        hintLabel(body, "Entrambi partono da 100: così vedi chi è cresciuto di più nel periodo, anche se hanno prezzi o valute diverse.")
        fun load(key: String) {
            val (range, interval) = Stocks.RANGES.first { it.first == key }.second
            legend.text = "Carico…"
            io.execute {
                val ca = runCatching { Stocks.chart(a.symbol, range, interval) }.getOrNull()
                val cb = runCatching { Stocks.chart(b.symbol, range, interval) }.getOrNull()
                runOnUiThread {
                    if (ca == null || cb == null) { legend.text = "Dati non disponibili: ${Stocks.describe(Stocks.lastError)}"; return@runOnUiThread }
                    val (sa, sb) = Stocks.normalize(ca.closes, cb.closes)
                    chart.series1 = sa; chart.series2 = sb; chart.invalidate()
                    fun ch(s: List<Double>) = s.lastOrNull()?.let { Stocks.fmtPct(it - 100) } ?: "—"
                    legend.text = android.text.SpannableStringBuilder().apply {
                        append("● ${a.name}: ${ch(sa)}\n"); setSpan(android.text.style.ForegroundColorSpan(0xFF2563EB.toInt()), 0, length, 0)
                        val st = length; append("● ${b.name}: ${ch(sb)}"); setSpan(android.text.style.ForegroundColorSpan(0xFFD97706.toInt()), st, length, 0)
                    }
                }
            }
        }
        Stocks.RANGES.filter { it.first != "1G" }.forEach { (k, _) ->
            ranges.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = k; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { load(k) }
            })
        }
        load("6M")
    }
}
