package com.weblauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.UUID

private const val UP = 0xFF16A34A.toInt()
private const val DOWN = 0xFFDC2626.toInt()

/** Small price chart: line + soft area, green when up, red when down. */
class PriceChartView(context: Context) : View(context) {
    var values: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var baseline: Double? = null
    var color = UP
    var showLabels = false
    var labelColor = 0xFF888888.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val d = resources.displayMetrics.density
        val left = if (showLabels) 4 * d else 0f
        val right = if (showLabels) 56 * d else 0f
        val top = 4 * d; val bottom = 4 * d
        val all = values + listOfNotNull(baseline)
        var min = all.min(); var max = all.max()
        if (max - min < 1e-9) { min -= 1; max += 1 }
        val w = width - left - right; val h = height - top - bottom
        fun x(i: Int) = left + w * i / (values.size - 1)
        fun y(v: Double) = (top + h * (1 - (v - min) / (max - min))).toFloat()
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path(line).apply { lineTo(x(values.size - 1), top + h); lineTo(x(0), top + h); close() }
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, top, 0f, top + h, (color and 0x00FFFFFF) or 0x40000000, color and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(area, p)
        p.shader = null
        baseline?.let { b ->
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0x66888888
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
            canvas.drawLine(left, y(b), left + w, y(b), p); p.pathEffect = null
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = if (showLabels) 2.2f * d else 1.6f * d; p.color = color
        p.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(line, p)
        if (showLabels) {
            p.style = Paint.Style.FILL; p.color = labelColor; p.textSize = 11 * d
            canvas.drawText(Stocks.fmtPrice(values.max()), left + w + 6 * d, top + 10 * d, p)
            canvas.drawText(Stocks.fmtPrice(values.min()), left + w + 6 * d, top + h, p)
        }
    }
}

// ======================= Borsa: watchlist =======================

internal fun MainActivity.showStocksPage() {
    openPage("Borsa", "Aggiungi", { _ -> addStockDialog { showStocksPage() } }) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            marketsStrip(body)
            val list = Stocks.watch(this)
            if (list.isEmpty()) {
                sectionLabel(body, "LA MIA WATCHLIST")
                hintLabel(body, "La tua watchlist è vuota.\nTocca «Aggiungi» e cerca un titolo (es. Eni, Apple, un ETF) oppure parti da questi:")
                Stocks.PRESETS.forEach { a ->
                    listRow(body, "➕ ${a.name}", a.symbol, onClick = { Stocks.saveWatch(this, Stocks.watch(this) + a); render() })
                }
                return
            }
            val banner = TextView(this).apply {
                textSize = 13f; setTextColor(0xFFB45309.toInt()); visibility = View.GONE
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(0x26F59E0B) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(4), dp(6), dp(8)) }
                setOnClickListener { render() }
            }
            body.addView(banner)
            sectionLabel(body, "LA MIA WATCHLIST")
            val alerts = Stocks.alerts(this).filter { !it.done }
            val holdings = Stocks.holdings(this)
            val summary = TextView(this).apply { textSize = 15f; setTextColor(palette.text); setPadding(dp(14), dp(10), dp(14), dp(10)) }
            fun refreshSummary() {
                val totals = Stocks.portfolio(holdings, Stocks.quotes(this@showStocksPage))
                summary.visibility = if (totals.isEmpty()) View.GONE else View.VISIBLE
                summary.text = android.text.SpannableStringBuilder("💼 Il mio portafoglio\n").apply {
                    totals.forEach { t ->
                        append("Valore ${Stocks.fmtPrice(t.value, t.currency)}  ")
                        val start = length
                        append("${if (t.gain >= 0) "+" else "−"}${Stocks.fmtPrice(kotlin.math.abs(t.gain), t.currency)} (${Stocks.fmtPct(t.gainPct)})\n")
                        setSpan(android.text.style.ForegroundColorSpan(if (t.gain >= 0) UP else DOWN), start, length, 0)
                    }
                    append("Tocca per grafici, tasse ed esportazione ›")
                }
            }
            if (holdings.isNotEmpty()) {
                summary.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke) }
                summary.setOnClickListener { showPortfolioPage() }
                body.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(4), dp(6), dp(10)) })
                refreshSummary()
            }
            list.forEach { a ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64); setPadding(dp(10), dp(6), dp(10), dp(6))
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { showStockDetail(a) }
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(this@showStocksPage).setTitle(a.name)
                            .setItems(arrayOf("⬆ Sposta in alto", "🗑 Togli dalla watchlist")) { _, w ->
                                val l = Stocks.watch(this@showStocksPage)
                                val i = l.indexOfFirst { it.symbol == a.symbol }
                                if (i >= 0) { val x = l.removeAt(i); if (w == 0) l.add(0, x) }
                                Stocks.saveWatch(this@showStocksPage, l); render()
                            }.show(); true
                    }
                }
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                names.addView(TextView(this).apply {
                    text = a.name + if (alerts.any { it.symbol == a.symbol }) " 🔔" else ""
                    textSize = 15f; setTextColor(palette.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val symView = TextView(this).apply { text = a.symbol; textSize = 12f; setTextColor(palette.textMuted) }
                names.addView(symView)
                val chart = PriceChartView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(70), dp(34)).apply { marginStart = dp(6) } }
                val prices = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(dp(96), -2)
                }
                val price = TextView(this).apply { text = "…"; textSize = 15f; setTextColor(palette.text); gravity = Gravity.END }
                val change = TextView(this).apply {
                    textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(3) }
                }
                prices.addView(price); prices.addView(change)
                row.addView(names); row.addView(chart); row.addView(prices)
                body.addView(row)
                io.execute {
                    val ch = runCatching { Stocks.chart(a.symbol) }.getOrNull()
                    runOnUiThread {
                        if (ch == null) {
                            // no fresh data: show the last known price (greyed) and say why
                            val q = Stocks.quotes(this@showStocksPage)[a.symbol]
                            if (q != null) {
                                price.text = Stocks.fmtPrice(q.price, q.currency); price.setTextColor(palette.textMuted)
                                change.text = Stocks.fmtPct(q.pct)
                                change.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6 * d; setColor(0xFF9CA3AF.toInt()) }
                                symView.text = "${a.symbol} · non aggiornato (${NewsAdapter.ago(q.time)})"
                            } else price.text = "n.d."
                            banner.text = "⚠ ${Stocks.describe(Stocks.lastError)} · tocca per riprovare"
                            banner.visibility = View.VISIBLE
                            return@runOnUiThread
                        }
                        Stocks.remember(this@showStocksPage, a.symbol, ch)
                        if (holdings.any { it.symbol == a.symbol }) refreshSummary()
                        val col = if (ch.change >= 0) UP else DOWN
                        price.text = Stocks.fmtPrice(ch.price, ch.currency)
                        change.text = Stocks.fmtPct(ch.changePct)
                        change.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6 * d; setColor(col) }
                        chart.color = col; chart.baseline = ch.previous; chart.values = ch.closes
                    }
                }
            }
            sectionLabel(body, "STRUMENTI")
            listRow(body, "💼 Il mio portafoglio", if (holdings.isEmpty()) "Aggiungi le tue azioni da un titolo: «Ho queste azioni»" else "Grafico, andamento, tasse stimate, CSV", onClick = { showPortfolioPage() })
            listRow(body, "🧮 Simula un piano di accumulo (PAC)", "Quanto diventa un versamento mensile", onClick = { savingsPlanDialog() })
            listRow(body, "🔧 Prova la connessione a Yahoo Finance", "Se vedi «n.d.»: mostra cosa risponde il servizio", onClick = { diagnoseStocks() })
            hintLabel(body, "Dati Yahoo Finance, possono essere in ritardo di 15-20 minuti. Tieni premuto un titolo per spostarlo o toglierlo.")
        }
        render()
    }
}

internal fun MainActivity.addStockDialog(onAdded: () -> Unit) {
    val d = resources.displayMetrics.density
    val input = EditText(this).apply { hint = "Nome o simbolo (es. Eni, Ferrari, VWCE, Apple)"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
    val box = android.widget.FrameLayout(this).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(input) }
    MaterialAlertDialogBuilder(this).setTitle("Aggiungi alla watchlist").setView(box)
        .setPositiveButton("Cerca") { _, _ ->
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@setPositiveButton
            toast("Cerco «$q»…")
            io.execute {
                val res = runCatching { Stocks.search(q) }
                runOnUiThread {
                    val list = res.getOrNull()
                    if (list == null) { toast(Stocks.describe(res.exceptionOrNull())); return@runOnUiThread }
                    if (list.isEmpty()) { toast("Nessun titolo trovato per «$q»"); return@runOnUiThread }
                    val type = mapOf("EQUITY" to "Azione", "ETF" to "ETF", "INDEX" to "Indice", "MUTUALFUND" to "Fondo",
                        "CURRENCY" to "Valuta", "CRYPTOCURRENCY" to "Cripto", "FUTURE" to "Materia prima")
                    MaterialAlertDialogBuilder(this).setTitle("Risultati")
                        .setItems(list.map { "${it.name}\n${it.symbol} · ${type[it.type] ?: it.type}${if (it.exchange.isNotBlank()) " · " + it.exchange else ""}" }.toTypedArray()) { _, w ->
                            val a = list[w]
                            val cur = Stocks.watch(this)
                            if (cur.none { it.symbol == a.symbol }) { Stocks.saveWatch(this, cur + a); toast("📈 ${a.name} aggiunto") }
                            onAdded()
                        }.show()
                }
            }
        }
        .setNeutralButton("Più usati") { _, _ ->
            val p = Stocks.PRESETS
            MaterialAlertDialogBuilder(this).setTitle("Più usati").setItems(p.map { it.name }.toTypedArray()) { _, w ->
                val cur = Stocks.watch(this)
                if (cur.none { it.symbol == p[w].symbol }) Stocks.saveWatch(this, cur + p[w])
                onAdded()
            }.show()
        }
        .setNegativeButton("Annulla", null).show()
    input.requestFocus()
}

internal fun MainActivity.showStockDetail(a: Stocks.Asset) {
    openPage(a.name.take(28)) { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val price = TextView(this).apply { textSize = 30f; setTextColor(palette.text); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(10), dp(4), dp(10), 0) }
        val change = TextView(this).apply { textSize = 15f; setPadding(dp(10), 0, dp(10), dp(6)) }
        val chart = PriceChartView(this).apply { showLabels = true; labelColor = palette.textMuted; layoutParams = LinearLayout.LayoutParams(-1, dp(210)).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) } }
        val info = TextView(this).apply { textSize = 13f; setTextColor(palette.textMuted); setPadding(dp(10), dp(4), dp(10), dp(8)) }
        body.addView(price); body.addView(change); body.addView(chart)
        val ranges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        body.addView(ranges); body.addView(info)
        var last: Stocks.Chart? = null
        val labels = mapOf("1G" to "oggi", "5G" to "in 5 giorni", "1M" to "in un mese", "6M" to "in 6 mesi", "1A" to "in un anno", "5A" to "in 5 anni")
        fun load(key: String) {
            val (range, interval) = Stocks.RANGES.first { it.first == key }.second
            for (i in 0 until ranges.childCount) (ranges.getChildAt(i) as MaterialButton).isChecked = (ranges.getChildAt(i) as MaterialButton).text == key
            io.execute {
                val ch = runCatching { Stocks.chart(a.symbol, range, interval) }.getOrNull()
                runOnUiThread {
                    if (ch == null) {
                        val q = Stocks.quotes(this@showStockDetail)[a.symbol]
                        price.text = q?.let { Stocks.fmtPrice(it.price, it.currency) } ?: "Dati non disponibili"
                        change.text = Stocks.describe(Stocks.lastError) + (q?.let { " · ultimo prezzo ${NewsAdapter.ago(it.time)}" } ?: "")
                        change.setTextColor(palette.textMuted)
                        return@runOnUiThread
                    }
                    if (key == "1G") last = ch
                    val col = if (ch.change >= 0) UP else DOWN
                    price.text = Stocks.fmtPrice(ch.price, ch.currency)
                    change.text = "${if (ch.change >= 0) "▲" else "▼"} ${Stocks.fmtPrice(kotlin.math.abs(ch.change))} (${Stocks.fmtPct(ch.changePct)}) ${labels[key]}"
                    change.setTextColor(col)
                    chart.color = col; chart.baseline = if (key == "1G") ch.previous else null; chart.values = ch.closes
                    info.text = "${a.symbol} · ${ch.exchange.ifBlank { a.exchange }} · ${ch.currency}"
                }
            }
        }
        Stocks.RANGES.forEach { (k, _) ->
            ranges.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = k; isCheckable = true; textSize = 12f; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { load(k) }
            })
        }
        load("1G")

        sectionLabel(body, "AZIONI")
        val h = Stocks.holdings(this).firstOrNull { it.symbol == a.symbol }
        listRow(body, if (h == null) "💼 Ho queste azioni" else "💼 Le mie quote: ${Stocks.fmtNum(h.qty)} a ${Stocks.fmtPrice(h.cost)}",
            if (h == null) "Quantità e prezzo medio: calcola guadagno o perdita" else
                Stocks.quotes(this)[a.symbol]?.let { q -> val g = h.qty * (q.price - h.cost); "Ora vale ${Stocks.fmtPrice(h.qty * q.price, q.currency)} · ${if (g >= 0) "+" else "−"}${Stocks.fmtPrice(kotlin.math.abs(g), q.currency)}" } ?: "Tocca per modificare",
            onClick = { holdingDialog(a, h) { dialog.dismiss(); showStockDetail(a) } })
        listRow(body, "🔔 Avvisami a un prezzo", "Notifica quando sale sopra o scende sotto un valore", onClick = { stockAlertDialog(a, last) })
        listRow(body, "📊 Avvisami se si muove molto", "Per esempio ±3% in un giorno", onClick = { pctAlertDialog(a) })
        listRow(body, "⚖️ Confronta con un altro titolo", "Chi è cresciuto di più in 1 mese, 6 mesi, 1 anno…", onClick = { compareDialog(a) })
        listRow(body, "✨ Perché si è mosso oggi?", "L'AI legge le ultime notizie", onClick = { whyStockMoved(a, last) })
        listRow(body, "📰 Tutte le notizie", "Google News su ${a.name}", onClick = {
            dialog.dismiss(); openInBrowser("https://news.google.com/search?q=" + java.net.URLEncoder.encode(a.name, "UTF-8") + "&hl=it&gl=IT&ceid=IT:it")
        })
        val alerts = Stocks.alerts(this).filter { it.symbol == a.symbol }
        if (alerts.isNotEmpty()) {
            sectionLabel(body, "AVVISI")
            alerts.forEach { al ->
                listRow(body, alertLabel(al), if (al.done) "✓ Già scattato" else if (al.firedDay != null) "Attivo · ultimo avviso ${al.firedDay}" else "Attivo",
                    onClick = {}, onRemove = { Stocks.saveAlerts(this, Stocks.alerts(this).filter { it.id != al.id }); dialog.dismiss(); showStockDetail(a) })
            }
        }
        if (Stocks.watch(this).none { it.symbol == a.symbol }) listRow(body, "➕ Aggiungi alla watchlist", null, onClick = {
            Stocks.saveWatch(this, Stocks.watch(this) + a); toast("Aggiunto")
        })
        stockExtrasSections(a, body)
    }
}

internal fun MainActivity.stockAlertDialog(a: Stocks.Asset, ch: Stocks.Chart?) {
    val d = resources.displayMetrics.density
    val input = EditText(this).apply {
        hint = ch?.let { "Prezzo attuale ${Stocks.fmtPrice(it.price)}" } ?: "Prezzo"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    val box = android.widget.FrameLayout(this).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(input) }
    fun save(above: Boolean?) {
        val v = Calc.num(input.text.toString()) ?: run { toast("Scrivi un prezzo"); return }
        val up = above ?: (ch == null || v >= ch.price)
        Stocks.saveAlerts(this, Stocks.alerts(this) + Stocks.Alert(UUID.randomUUID().toString(), a.symbol, a.name, up, v))
        if (Stocks.watch(this).none { it.symbol == a.symbol }) Stocks.saveWatch(this, Stocks.watch(this) + a)
        ensureNotificationPermission()
        toast("🔔 Ti avviso quando ${a.name} va ${if (up) "sopra" else "sotto"} ${Stocks.fmtPrice(v)} (controllo ogni 30 minuti)")
    }
    MaterialAlertDialogBuilder(this).setTitle("Avviso di prezzo · ${a.name}").setView(box)
        .setPositiveButton("Quando sale sopra") { _, _ -> save(true) }
        .setNegativeButton("Quando scende sotto") { _, _ -> save(false) }
        .setNeutralButton("Annulla", null).show()
}

internal fun MainActivity.whyStockMoved(a: Stocks.Asset, ch: Stocks.Chart?) {
    if (!aiReady()) return
    toast("✨ Leggo le notizie su ${a.name}…")
    io.execute {
        val titles = Stocks.news(a.name)
        runOnUiThread {
            if (titles.isEmpty()) { toast("Nessuna notizia recente trovata"); return@runOnUiThread }
            val move = ch?.let { "Oggi ${a.name} è a ${Stocks.fmtPrice(it.price, it.currency)}, ${Stocks.fmtPct(it.changePct)} rispetto alla chiusura precedente." } ?: ""
            showAiSheet("Perché si è mosso?", "$move\nUltimi titoli di giornale su ${a.name}:\n" + titles.joinToString("\n") { "- $it" } + "\n\n",
                "Spiegami in italiano, in 3-5 frasi semplici, perché ${a.name} si è mosso oggi, usando solo questi titoli. " +
                    "Se i titoli non bastano a spiegarlo dillo chiaramente. Non dare consigli di investimento.", false)
        }
    }
}

internal fun MainActivity.holdingDialog(a: Stocks.Asset, h: Stocks.Holding?, done: () -> Unit) {
    val d = resources.displayMetrics.density
    val qty = EditText(this).apply { hint = "Quantità (es. 120)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; h?.let { setText(Stocks.fmtNum(it.qty)) } }
    val cost = EditText(this).apply { hint = "Prezzo medio di acquisto"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; h?.let { setText(Stocks.fmtNum(it.cost)) } }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(qty); addView(cost) }
    val b = MaterialAlertDialogBuilder(this).setTitle("💼 ${a.name}").setView(box)
        .setPositiveButton("Salva") { _, _ ->
            val q = Calc.num(qty.text.toString()); val c = Calc.num(cost.text.toString())
            if (q == null || q <= 0 || c == null || c < 0) { toast("Scrivi quantità e prezzo"); return@setPositiveButton }
            Stocks.saveHoldings(this, Stocks.holdings(this).filter { it.symbol != a.symbol } + Stocks.Holding(a.symbol, a.name, q, c))
            if (Stocks.watch(this).none { it.symbol == a.symbol }) Stocks.saveWatch(this, Stocks.watch(this) + a)
            done()
        }
        .setNegativeButton("Annulla", null)
    if (h != null) b.setNeutralButton("Non le ho più") { _, _ -> Stocks.saveHoldings(this, Stocks.holdings(this).filter { it.symbol != a.symbol }); done() }
    b.show()
}

internal fun MainActivity.diagnoseStocks() {
    toast("Provo la connessione…")
    io.execute {
        val text = runCatching { Stocks.diagnose() }.getOrElse { "Errore: ${it.message}" }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            MaterialAlertDialogBuilder(this).setTitle("Connessione a Yahoo Finance").setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copia") { _, _ -> copyText("Diagnosi Yahoo Finance", text); toast("Copiato") }.show()
        }
    }
}
