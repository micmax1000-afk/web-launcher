package com.weblauncher.app

import android.Manifest
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private fun MainActivity.form(vararg fields: EditText): LinearLayout {
    val d = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        fields.forEach { addView(it) }
    }
}
private fun MainActivity.field(hint: String, value: String? = null, number: Boolean = false) = EditText(this).apply {
    this.hint = hint; setText(value.orEmpty()); setSingleLine()
    inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
}

// ======================= Finanze: mese, conti, entrate, ricorrenti, 730 =======================

internal fun MainActivity.showFinancePage() {
    openPage("Finanze") { _, body, dialog ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun render() {
            body.removeAllViews()
            val now = Calendar.getInstance()
            fun sameMonth(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) == now.get(Calendar.YEAR) && it.get(Calendar.MONTH) == now.get(Calendar.MONTH) }
            val spent = Expenses.load(this).filter { sameMonth(it.time) }.sumOf { it.amount }
            val earned = Finance.incomes(this).filter { sameMonth(it.time) }.sumOf { it.amount }
            val month = SimpleDateFormat("MMMM yyyy", Locale.ITALIAN).format(now.time).replaceFirstChar { it.uppercase() }
            sectionLabel(body, month.uppercase(Locale.ITALIAN))
            body.addView(TextView(this).apply {
                val saldo = earned - spent
                text = "Entrate  ${Expenses.fmt(earned)}\nSpese    ${Expenses.fmt(spent)}\nSaldo    ${if (saldo >= 0) "+" else "−"}${Expenses.fmt(kotlin.math.abs(saldo))}"
                textSize = 17f; setTextColor(palette.text); typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(14), dp(4), dp(14), dp(8))
            })

            val accounts = Finance.accounts(this)
            sectionLabel(body, "CONTI" + if (accounts.isNotEmpty()) " · totale ${Expenses.fmt(accounts.sumOf { it.balance })}" else "")
            accounts.forEach { a ->
                listRow(body, "${a.name} · ${Expenses.fmt(a.balance)}", "Aggiornato ${NewsAdapter.ago(a.updated)} · tocca per aggiornare il saldo",
                    onClick = { accountDialog(a) { render() } },
                    onRemove = { Finance.saveAccounts(this, Finance.accounts(this).filter { it.id != a.id }); render() })
            }
            listRow(body, "➕ Aggiungi un conto", "Conto corrente, carta prepagata, contanti, libretto…", onClick = { accountDialog(null) { render() } })

            val inc = Finance.incomes(this).sortedByDescending { it.time }
            sectionLabel(body, "ENTRATE")
            inc.take(8).forEach { i ->
                listRow(body, "${Expenses.fmt(i.amount)} · ${i.desc}", SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(java.util.Date(i.time)),
                    onClick = {}, onRemove = { Finance.saveIncomes(this, Finance.incomes(this).filter { it.id != i.id }); render() })
            }
            listRow(body, "➕ Aggiungi un'entrata", "Stipendio, pensione, rimborso, vendita…", onClick = { incomeDialog { render() } })

            val rec = Finance.recurring(this)
            sectionLabel(body, "RICORRENTI (AUTOMATICHE)")
            rec.forEach { r ->
                listRow(body, "${if (r.kind == "entrata") "⬆" else "⬇"} ${Expenses.fmt(r.amount)} · ${r.desc}", "Ogni mese il giorno ${r.day}" + if (r.kind == "spesa") " · ${r.cat}" else "",
                    onClick = {}, onRemove = { Finance.saveRecurring(this, Finance.recurring(this).filter { it.id != r.id }); render() })
            }
            listRow(body, "➕ Aggiungi una voce ricorrente", "Affitto, rata, abbonamento, stipendio: si aggiungono da sole ogni mese", onClick = { recurringDialog { render() } })

            sectionLabel(body, "730 · SPESE DETRAIBILI")
            listRow(body, "🧾 Spese sanitarie e detraibili ${now.get(Calendar.YEAR)}", "Totale, detrazione stimata ed esportazione per il 730",
                onClick = { show730Page(now.get(Calendar.YEAR)) })
            listRow(body, "🧾 Anno ${now.get(Calendar.YEAR) - 1} (per il 730 di quest'anno)", null, onClick = { show730Page(now.get(Calendar.YEAR) - 1) })
            emptyLabel(body, "I dati restano sul telefono e sono inclusi nel backup completo.")
        }
        render()
        financeRefresh = { render() }
        dialog.setOnDismissListener { financeRefresh = null }
    }
}

private fun MainActivity.accountDialog(a: Finance.Account?, done: () -> Unit) {
    val name = field("Nome (es. Conto BancoPosta)", a?.name)
    val bal = field("Saldo attuale €", a?.balance?.let { Calc.fmt(it) }, number = true)
    MaterialAlertDialogBuilder(this).setTitle(if (a == null) "Nuovo conto" else a.name).setView(form(name, bal))
        .setPositiveButton("Salva") { _, _ ->
            val v = Calc.num(bal.text.toString()) ?: run { toast("Scrivi il saldo"); return@setPositiveButton }
            val n = name.text.toString().trim().ifBlank { "Conto" }
            Finance.saveAccounts(this, Finance.accounts(this).filter { it.id != a?.id } + Finance.Account(a?.id ?: UUID.randomUUID().toString(), n, v, System.currentTimeMillis()))
            done()
        }.setNegativeButton("Annulla", null).show()
}

private fun MainActivity.incomeDialog(done: () -> Unit) {
    val amount = field("Importo €", number = true)
    val desc = field("Descrizione (es. Stipendio)")
    MaterialAlertDialogBuilder(this).setTitle("Nuova entrata").setView(form(amount, desc))
        .setPositiveButton("Salva") { _, _ ->
            val v = Calc.num(amount.text.toString()) ?: run { toast("Scrivi l'importo"); return@setPositiveButton }
            Finance.saveIncomes(this, Finance.incomes(this) + Finance.Income(UUID.randomUUID().toString(), v, desc.text.toString().trim().ifBlank { "Entrata" }, System.currentTimeMillis()))
            done()
        }.setNegativeButton("Annulla", null).show()
}

private fun MainActivity.recurringDialog(done: () -> Unit) {
    MaterialAlertDialogBuilder(this).setTitle("Voce ricorrente").setItems(arrayOf("⬇ Spesa (affitto, rata, abbonamento…)", "⬆ Entrata (stipendio, pensione…)")) { _, w ->
        val kind = if (w == 0) "spesa" else "entrata"
        val amount = field("Importo €", number = true)
        val desc = field(if (w == 0) "Descrizione (es. Affitto)" else "Descrizione (es. Stipendio)")
        val day = field("Giorno del mese (1-31)", Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString(), number = true)
        MaterialAlertDialogBuilder(this).setTitle(if (w == 0) "Spesa ricorrente" else "Entrata ricorrente").setView(form(amount, desc, day))
            .setPositiveButton("Salva") { _, _ ->
                val v = Calc.num(amount.text.toString()) ?: run { toast("Scrivi l'importo"); return@setPositiveButton }
                val dd = day.text.toString().toIntOrNull()?.coerceIn(1, 31) ?: 1
                val text = desc.text.toString().trim().ifBlank { if (w == 0) "Spesa" else "Entrata" }
                val now = Calendar.getInstance()
                // parte da questo mese: se il giorno è già passato la prima registrazione è subito
                val prev = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                Finance.saveRecurring(this, Finance.recurring(this) + Finance.Recurring(UUID.randomUUID().toString(), kind, v, text,
                    if (kind == "spesa") Expenses.guess(text) else "", dd, Finance.monthKey(prev)))
                val n = Finance.applyRecurring(this)
                toast(if (n > 0) "🔁 Salvata e registrata per questo mese" else "🔁 Salvata: si registrerà da sola il giorno $dd")
                done(); expensesRefresh?.invoke()
            }.setNegativeButton("Annulla", null).show()
    }.show()
}

internal fun MainActivity.show730Page(year: Int) {
    openPage("730 · $year") { _, body, _ ->
        val palette = AppRepository.palette(this)
        val d = resources.displayMetrics.density
        fun render() {
            body.removeAllViews()
            val ov = Finance.overrides(this)
            val all = Expenses.load(this).filter { Calendar.getInstance().apply { timeInMillis = it.time }.get(Calendar.YEAR) == year }.sortedBy { it.time }
            val ded = all.filter { Finance.isDeductible(it, ov) }
            val total = ded.sumOf { it.amount }
            body.addView(TextView(this).apply {
                text = "Totale detraibile: ${Expenses.fmt(total)}\nDetrazione stimata (19% oltre ${Expenses.fmt(Finance.FRANCHIGIA)}): ${Expenses.fmt(Finance.healthDeduction(total))}"
                textSize = 16f; setTextColor(palette.text); setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            })
            listRow(body, "📤 Esporta l'elenco", "Per il CAF o il commercialista", onClick = {
                val csv = "Data;Importo;Descrizione;Categoria\n" + ded.joinToString("\n") {
                    SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(java.util.Date(it.time)) + ";" + String.format(Locale.ITALIAN, "%.2f", it.amount) + ";" +
                        it.desc.replace(";", ",") + ";" + it.cat.substringAfter(' ')
                } + "\n\nTotale;" + String.format(Locale.ITALIAN, "%.2f", total)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Spese detraibili $year"); putExtra(Intent.EXTRA_TEXT, csv) }, "Esporta"))
            })
            emptyLabel(body, "Sono incluse in automatico le spese «Salute» e quelle con parole come farmacia, dentista, visita, veterinario. Tocca una spesa per includerla o escluderla. Conserva scontrini parlanti e fatture: le foto le trovi in Biglietti e documenti.")
            sectionLabel(body, "INCLUSE · ${ded.size}")
            ded.forEach { e -> listRow(body, "✅ ${Expenses.fmt(e.amount)} · ${e.desc}", SimpleDateFormat("d MMM", Locale.ITALIAN).format(java.util.Date(e.time)) + " · ${e.cat}", onClick = { Finance.toggleDeductible(this, e.id); render() }) }
            val other = all.filter { !Finance.isDeductible(it, ov) }
            if (other.isNotEmpty()) {
                sectionLabel(body, "ALTRE SPESE DELL'ANNO · tocca per includere")
                other.takeLast(60).reversed().forEach { e -> listRow(body, "${Expenses.fmt(e.amount)} · ${e.desc}", SimpleDateFormat("d MMM", Locale.ITALIAN).format(java.util.Date(e.time)) + " · ${e.cat}", onClick = { Finance.toggleDeductible(this, e.id); render() }) }
            }
            emptyLabel(body, "Stima indicativa: verifica sempre con il CAF o con il 730 precompilato.")
        }
        render()
    }
}

// ======================= Compleanni =======================

internal fun MainActivity.showBirthdaysPage() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        pendingContactCommand = "__birthdays__"; contactsPermission.launch(Manifest.permission.READ_CONTACTS); return
    }
    openPage("Compleanni") { _, body, _ ->
        val now = Calendar.getInstance()
        val list = Birthdays.upcoming(this, 366, now)
        if (list.isEmpty()) { emptyLabel(body, "Nessun compleanno nei contatti.\nAggiungi la data di nascita nella scheda del contatto (Contatti → Modifica → Evento → Compleanno)."); return@openPage }
        val fmt = SimpleDateFormat("d MMMM", Locale.ITALIAN)
        fun label(days: Int) = when (days) { 0 -> "🎉 Oggi"; 1 -> "Domani"; else -> "Tra $days giorni" }
        listOf("PROSSIMI 30 GIORNI" to list.filter { it.second <= 30 }, "PIÙ AVANTI" to list.filter { it.second > 30 }).forEach { (title, group) ->
            if (group.isEmpty()) return@forEach
            sectionLabel(body, title)
            group.forEach { (p, days) ->
                val date = Calendar.getInstance().apply { set(Calendar.MONTH, p.month - 1); set(Calendar.DAY_OF_MONTH, p.day) }
                val age = p.turning(now)?.let { " · compie $it anni" } ?: ""
                listRow(body, "${if (days == 0) "🎂 " else ""}${p.name}", "${label(days)} · ${fmt.format(date.time)}$age", onClick = { birthdayActions(p) })
            }
        }
    }
}

private fun MainActivity.birthdayActions(p: Birthdays.Person) {
    MaterialAlertDialogBuilder(this).setTitle("🎂 ${p.name}")
        .setItems(arrayOf("💬 Auguri su WhatsApp", "✨ Scrivi gli auguri con l'AI", "📞 Chiama")) { _, w ->
            when (w) {
                0 -> systemVoiceCommand("manda whatsapp a ${p.name} dicendo Tanti auguri di buon compleanno! 🎉")
                1 -> if (aiReady()) showAiSheet("Auguri per ${p.name}", null,
                    "Scrivi 3 brevi messaggi di auguri di compleanno in italiano per ${p.name}" + (p.turning(Calendar.getInstance())?.let { ", che compie $it anni" } ?: "") +
                        ": uno affettuoso, uno simpatico, uno formale. Solo i messaggi, numerati.", false)
                2 -> withContact("chiama ${p.name}", p.name) { _, number -> runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) } }
            }
        }.show()
}

// ======================= Chiedi ai tuoi dati (AI) =======================

/** Builds a compact, readable summary of the user's own data for the AI (only on request). */
internal fun MainActivity.myDataContext(): String = buildString {
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val since = System.currentTimeMillis() - 400L * 86_400_000L
    val exp = Expenses.load(this@myDataContext).filter { it.time >= since }.sortedBy { it.time }
    append("SPESE (data;importo €;categoria;descrizione), ultimi 13 mesi:\n")
    exp.takeLast(700).forEach { append(df.format(java.util.Date(it.time))).append(';').append(String.format(Locale.ROOT, "%.2f", it.amount)).append(';').append(it.cat.substringAfter(' ')).append(';').append(it.desc).append('\n') }
    val inc = Finance.incomes(this@myDataContext).filter { it.time >= since }
    if (inc.isNotEmpty()) { append("\nENTRATE:\n"); inc.forEach { append(df.format(java.util.Date(it.time))).append(';').append(String.format(Locale.ROOT, "%.2f", it.amount)).append(';').append(it.desc).append('\n') } }
    val rec = Finance.recurring(this@myDataContext)
    if (rec.isNotEmpty()) { append("\nVOCI RICORRENTI MENSILI:\n"); rec.forEach { append("${it.kind};${it.amount};${it.desc};giorno ${it.day}\n") } }
    val acc = Finance.accounts(this@myDataContext)
    if (acc.isNotEmpty()) { append("\nCONTI (saldo):\n"); acc.forEach { append("${it.name};${it.balance}\n") } }
    val dl = Deadlines.load(this@myDataContext)
    if (dl.isNotEmpty()) { append("\nSCADENZE, ABBONAMENTI, GARANZIE (tipo;titolo;data;importo;periodo):\n"); dl.forEach { append("${it.kind};${it.title};${df.format(java.util.Date(it.date))};${it.amount};${it.period}\n") } }
    val h = Stocks.holdings(this@myDataContext); val q = Stocks.quotes(this@myDataContext)
    if (h.isNotEmpty()) { append("\nPORTAFOGLIO (titolo;quantità;prezzo medio;ultimo prezzo):\n"); h.forEach { append("${it.name};${it.qty};${it.cost};${q[it.symbol]?.price ?: "?"}\n") } }
    val steps = runCatching { Steps.yesterday(this@myDataContext) }.getOrDefault(0)
    if (steps > 0) append("\nPASSI IERI: $steps\n")
}

internal fun MainActivity.askMyData() {
    if (!aiReady()) return
    val prefs = getSharedPreferences("wl_finance", MODE_PRIVATE)
    fun go() {
        toast("Preparo i tuoi dati…")
        io.execute {
            val ctx = myDataContext()
            runOnUiThread {
                val today = SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(java.util.Date())
                showAiSheet("Chiedi ai tuoi dati", "Oggi è $today. Questi sono i dati personali dell'utente salvati nell'app (rispondi solo in base a questi, " +
                    "fai i calcoli con attenzione e dillo se un dato manca):\n$ctx\n\n", null,
                    hint = "Es. quanto ho speso di benzina a ottobre?")
            }
        }
    }
    if (prefs.getBoolean("aiDataOk", false)) { go(); return }
    MaterialAlertDialogBuilder(this).setTitle("Chiedi ai tuoi dati")
        .setMessage("Per rispondere, l'AI riceve spese, entrate, conti, scadenze e portafoglio salvati nell'app (non note, cassaforte o password). " +
            "Vengono inviati al servizio AI configurato in Mia AI solo quando fai una domanda.")
        .setPositiveButton("Ho capito") { _, _ -> prefs.edit().putBoolean("aiDataOk", true).apply(); go() }
        .setNegativeButton("Annulla", null).show()
}
