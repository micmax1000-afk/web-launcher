package com.weblauncher.app

import android.content.Context.ALARM_SERVICE
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Context.MODE_PRIVATE
import android.content.Context.PRINT_SERVICE
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.weblauncher.app.MainActivity.CatalogApp
import com.weblauncher.app.MainActivity.Companion.AI_SYSTEM
import com.weblauncher.app.MainActivity.Companion.DESKTOP_UA
import com.weblauncher.app.MainActivity.Companion.EXTRA_OPEN_PLAYER
import com.weblauncher.app.MainActivity.Companion.EXTRA_WIDGET
import com.weblauncher.app.MainActivity.Companion.MENU_AI
import com.weblauncher.app.MainActivity.Companion.NEWS_ALL
import com.weblauncher.app.MainActivity.Companion.STATE_TAB
import com.weblauncher.app.MainActivity.Companion.SUGGEST_TAG
import com.weblauncher.app.MainActivity.Companion.TAB_AUDIO
import com.weblauncher.app.MainActivity.Companion.TAB_BROWSER
import com.weblauncher.app.MainActivity.Companion.TAB_HOME
import com.weblauncher.app.MainActivity.Companion.TAB_NEWS
import com.weblauncher.app.MainActivity.Companion.TAB_SETTINGS
import com.weblauncher.app.MainActivity.Companion.TAB_TOOLS
import com.weblauncher.app.MainActivity.ReaderDoc
import com.weblauncher.app.MainActivity.Song
import com.weblauncher.app.MainActivity.TodayEntry
import com.weblauncher.app.MainActivity.WebTab
import com.weblauncher.app.MainActivity.YtChannel
import java.util.Locale
import java.util.UUID

internal fun MainActivity.updateCalcHint(q: String) {
    val hint = findViewById<TextView>(R.id.calcHint)
    val text = q.trim()
    val token = ++calcToken
    val local = if (text.isEmpty()) null else Calc.evaluate(text)
    val reminder = if (text.startsWith("ricordami", true) || text.startsWith("promemoria", true)) "⏰ Crea promemoria: $text" else null
    val parcel = if (Parcels.looksLikeTracking(text)) "📦 Traccia pacco ${text.uppercase()}" else null
    val shown = local?.let { "🧮 $it" } ?: reminder ?: parcel
    hint.isVisible = shown != null
    hint.text = shown
    hint.setOnClickListener {
        when {
            local != null -> copyText("Risultato", local.substringAfter("= ").trim())
            reminder != null -> { searchInput.setText(""); createReminderFromText(text) }
            parcel != null -> { searchInput.setText(""); trackParcel(text) }
        }
    }
    val cur = if (local == null && reminder == null) Calc.currencyQuery(text) else null
    if (cur != null) {
        hint.isVisible = true
        hint.text = "💱 Calcolo il cambio…"
        Thread {
            val r = Calc.convert(cur.first, cur.second, cur.third)
            runOnUiThread {
                if (token != calcToken) return@runOnUiThread
                hint.text = r?.let { "💱 $it" } ?: "💱 Cambio non disponibile (connessione?)"
                hint.setOnClickListener { r?.let { copyText("Cambio", it.substringAfter("= ")) } }
            }
        }.start()
    }
}

internal fun MainActivity.checkClipboard() {
    val bar = findViewById<TextView>(R.id.clipBar)
    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty() || text.length > 5000) return
    val hash = text.hashCode()
    if (hash == toolsPrefs.getInt("lastClip", 0)) return
    val urlMatch = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ';')
    val phone = Regex("^(\\+39\\s?)?[03]\\d[\\d\\s]{6,11}$").matches(text) && !Parcels.looksLikeTracking(text.replace(" ", ""))
    val action: Pair<String, () -> Unit>? = when {
        urlMatch != null -> ("📋 Apri il link copiato: " + (hostOf(urlMatch) ?: urlMatch)) to { openInBrowser(urlMatch) }
        Parcels.looksLikeTracking(text) -> "📦 Traccia il pacco copiato: ${text.uppercase()}" to { trackParcel(text) }
        Regex("^(\\+39\\s?)?3\\d{2}[\\s]?\\d{6,7}$").matches(text) || (phone && text.length <= 16) ->
            "📞 Chiama $text" to { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + text.replace(" ", "")))) }
        text.length > 400 -> "✨ Riassumi il testo copiato" to {
            if (aiReady()) showAiSheet("Riassunto", "Testo:\n$text\n\n", "Riassumi questo testo in italiano in pochi punti.", false)
        }
        else -> null
    }
    if (action == null) return
    bar.text = action.first + "   ✕"
    bar.isVisible = true
    bar.setOnClickListener { v ->
        toolsPrefs.edit().putInt("lastClip", hash).apply()
        v.isVisible = false
        action.second()
    }
    bar.setOnLongClickListener { v -> toolsPrefs.edit().putInt("lastClip", hash).apply(); v.isVisible = false; true }
}

// ======================= Parcels =======================

internal fun MainActivity.trackParcel(raw: String) {
    val code = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
    val (carrier, url) = Parcels.carrier(code)
    val list = Parcels.load(this)
    if (list.none { it.code == code }) {
        list.add(0, Parcels.Parcel(code, "", carrier, System.currentTimeMillis()))
        Parcels.save(this, list)
    }
    toast("📦 $carrier")
    openInBrowser(url)
}

internal fun MainActivity.showParcelsPage() {
    openPage("Pacchi", "Aggiungi", { _ ->
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { hint = "Codice di spedizione"; setSingleLine() }
        val name = TextInputEditText(this).apply { hint = "Cosa è (facoltativo)"; setSingleLine() }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et); addView(name)
        }
        MaterialAlertDialogBuilder(this).setTitle("Traccia un pacco").setView(box)
            .setPositiveButton("Traccia") { _, _ ->
                val code = et.text?.toString()?.trim().orEmpty()
                if (code.length < 6) return@setPositiveButton
                val c = code.uppercase(Locale.ROOT).replace(" ", "")
                val list = Parcels.load(this).filter { it.code != c }.toMutableList()
                list.add(0, Parcels.Parcel(c, name.text?.toString()?.trim().orEmpty(), Parcels.carrier(c).first, System.currentTimeMillis()))
                Parcels.save(this, list)
                trackParcel(c)
            }.setNegativeButton("Annulla", null).show()
    }) { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = Parcels.load(this)
            if (list.isEmpty()) { emptyLabel(body, "Nessun pacco.\nCopia un codice di spedizione: la home te lo propone da sola."); return }
            list.forEach { p ->
                listRow(body, p.label.ifBlank { p.code }, "${p.carrier}${if (p.label.isNotBlank()) " · ${p.code}" else ""} · aggiunto ${NewsAdapter.ago(p.time)}",
                    onClick = { dialog.dismiss(); openInBrowser(Parcels.carrier(p.code).second) },
                    onRemove = { Parcels.save(this, Parcels.load(this).filter { it.code != p.code }); render() })
            }
        }
        render()
    }
}

// ======================= Reminders =======================

internal fun MainActivity.createReminderFromText(raw: String, fromVoice: Boolean = false) {
    val parsed = Reminders.parse(raw)
    if (parsed != null) { confirmReminder(parsed.text, parsed.at, parsed.repeat); return }
    if (!ai.supported()) { toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»"); return }
    toast("✨ Capisco il promemoria…")
    val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy HH:mm", Locale.ITALIAN).format(java.util.Date())
    ai.ask(listOf("user" to "Adesso è $now. Estrai il promemoria da: «$raw». Rispondi SOLO con JSON " +
        "{\"text\":\"cosa ricordare\",\"datetime\":\"yyyy-MM-ddTHH:mm\"}."), "Rispondi solo con JSON valido.",
        object : AiBridge.Listener {
            override fun onDone(text: String, model: String) {
                val o = runCatching { org.json.JSONObject(Regex("\\{[\\s\\S]*\\}").find(text)!!.value) }.getOrNull()
                val at = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).parse(o!!.getString("datetime"))!!.time
                }.getOrNull()
                if (o == null || at == null) toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»")
                else confirmReminder(o.optString("text").ifBlank { raw }, at)
            }
            override fun onError(message: String, noKey: Boolean) { toast("Non ho capito quando: es. «domani alle 9 chiamare Marco»") }
        })
}

internal fun MainActivity.confirmReminder(text: String, at: Long, repeat: String? = null) {
    ensureNotificationPermission()
    Reminders.add(this, text, at, repeat)
    val fmt = java.text.SimpleDateFormat("EEEE d MMMM 'alle' HH:mm", Locale.ITALIAN).format(java.util.Date(at))
    toast("⏰ ${repeat?.let { Reminders.REPEAT_LABELS[it] + ", dal " } ?: ""}$fmt: $text")
    val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
    if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() && !toolsPrefs.getBoolean("askedExact", false)) {
        toolsPrefs.edit().putBoolean("askedExact", true).apply()
        MaterialAlertDialogBuilder(this).setTitle("Promemoria puntuali")
            .setMessage("Per avvisarti all'ora esatta Android chiede il permesso «Sveglie e promemoria». Senza, l'avviso può arrivare con qualche minuto di ritardo.")
            .setPositiveButton("Apri impostazioni") { _, _ ->
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            }.setNegativeButton("Va bene così", null).show()
    }
}

internal fun MainActivity.showRemindersPage() {
    openPage("Promemoria", "Nuovo", { _ ->
        val d = resources.displayMetrics.density
        val et = TextInputEditText(this).apply { hint = "Es. domani alle 9 chiamare il dentista\noppure: ogni giorno alle 8 la pastiglia" }
        var rep: String? = null
        val chips = com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection = true; setPadding(0, (10 * d).toInt(), 0, 0) }
        listOf(null to "Una volta", "day" to "Ogni giorno", "weekdays" to "Lun–ven", "week" to "Ogni settimana", "month" to "Ogni mese").forEach { (k, l) ->
            chips.addView(com.google.android.material.chip.Chip(this).apply { text = l; isCheckable = true; isChecked = k == null; setOnClickListener { rep = k } })
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(et); addView(chips)
        }
        MaterialAlertDialogBuilder(this).setTitle("Nuovo promemoria").setView(box)
            .setPositiveButton("Crea") { _, _ ->
                val t = et.text?.toString()?.trim().orEmpty()
                if (t.isEmpty()) return@setPositiveButton
                val p = Reminders.parse(t)
                val r = rep
                if (r != null && p != null) confirmReminder(p.text, Reminders.next(p.at, r, System.currentTimeMillis() - 1), r)
                else if (r != null) toast("Scrivi anche l'ora, es. «alle 8 la pastiglia»")
                else createReminderFromText(t)
            }
            .setNegativeButton("Annulla", null).show()
    }) { _, body, _ ->
        val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN)
        fun render() {
            body.removeAllViews()
            val list = Reminders.load(this).sortedBy { it.at }
            if (list.isEmpty()) { emptyLabel(body, "Nessun promemoria.\nDi' o scrivi «ricordami domani alle 9 di…»"); return }
            val now = System.currentTimeMillis()
            listOf("RICORRENTI" to list.filter { it.repeat != null }, "IN ARRIVO" to list.filter { it.repeat == null && it.at > now && !it.done },
                "PASSATI" to list.filter { it.repeat == null && (it.at <= now || it.done) }.reversed())
                .forEach { (label, group) ->
                    if (group.isEmpty()) return@forEach
                    sectionLabel(body, label)
                    group.forEach { r ->
                        listRow(body, r.text, (r.repeat?.let { "🔁 " + Reminders.REPEAT_LABELS[it] + " · prossimo " } ?: "") +
                            fmt.format(java.util.Date(r.at)).replaceFirstChar { it.uppercase() },
                            onClick = {}, onRemove = { Reminders.remove(this, r.id); render() })
                    }
                }
        }
        render()
    }
}

// ======================= Screenshot / save as PDF =======================

internal fun MainActivity.savePageAsPdf() {
    val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
    val name = (webView.title ?: "Pagina").take(60)
    pm.print(name, webView.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
    toast("Scegli «Salva come PDF» come stampante")
}

internal fun MainActivity.screenshotPage() {
    val bmp = runCatching {
        Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { webView.draw(android.graphics.Canvas(it)) }
    }.getOrNull() ?: run { toast("Screenshot non riuscito"); return }
    io.execute {
        val uri = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "web-launcher-${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Web Launcher")
            }
            val u = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            u
        }.getOrNull()
        runOnUiThread {
            if (uri == null) { toast("Screenshot non salvato"); return@runOnUiThread }
            MaterialAlertDialogBuilder(this).setTitle("📸 Screenshot salvato").setMessage("Nella galleria, cartella «Web Launcher».")
                .setPositiveButton("Condividi") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Condividi screenshot"))
                }.setNegativeButton("OK", null).show()
        }
    }
}

/** True if the address can be opened; otherwise shows a warning and opens only on request. */
internal fun MainActivity.guardUrl(url: String, open: () -> Unit): Boolean {
    if (!toolsPrefs.getBoolean("phishing", true)) return true
    val host = hostOf(url) ?: return true
    if (host in allowedHosts) return true
    if (apps.any { hostOf(normalizeUrl(it.url)) == host }) return true
    val warning = PhishingGuard.check(url) ?: return true
    runOnUiThread {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Possibile truffa")
            .setMessage("$warning\n\nNon inserire password, codici o dati della carta.")
            .setPositiveButton("Torna indietro", null)
            .setNegativeButton("Apri comunque") { _, _ -> allowedHosts.add(host); open() }
            .show()
    }
    return false
}

// ======================= Automatic cleanup =======================

internal fun MainActivity.noteHostVisit(url: String) {
    val host = hostOf(url) ?: return
    val p = getSharedPreferences("wl_cleanup", MODE_PRIVATE)
    p.edit().putLong("h_$host", System.currentTimeMillis()).apply()
}

internal fun MainActivity.autoCleanup(force: Boolean = false): Int {
    val p = getSharedPreferences("wl_cleanup", MODE_PRIVATE)
    if (!force && (!toolsPrefs.getBoolean("autoClean", true) || System.currentTimeMillis() - p.getLong("last", 0L) < 7 * 86_400_000L)) return 0
    p.edit().putLong("last", System.currentTimeMillis()).apply()
    val limit = System.currentTimeMillis() - 30 * 86_400_000L
    val keep = apps.mapNotNull { hostOf(normalizeUrl(it.url)) }.toSet()
    val old = p.all.filterKeys { it.startsWith("h_") }.mapNotNull { (k, v) ->
        val host = k.removePrefix("h_")
        if ((v as? Long ?: 0L) < limit && keep.none { host == it || host.endsWith(".$it") || it.endsWith(".$host") }) host else null
    }
    val cm = CookieManager.getInstance()
    val storage = WebStorage.getInstance()
    old.forEach { host ->
        listOf("https://$host", "http://$host").forEach { origin ->
            runCatching { storage.deleteOrigin(origin) }
            cm.getCookie(origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                cm.setCookie(origin, "$name=; Max-Age=0; Path=/")
                cm.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.$host")
                cm.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.${PhishingGuard.registered(host)}")
            }
        }
        p.edit().remove("h_$host").apply()
    }
    cm.flush()
    val hist = repo.loadHistory()
    io.execute { hist.filter { it.time < limit }.forEach { repo.removeHistory(it.url) } }
    return old.size
}

internal fun MainActivity.rewriteReplaceJs(text: String) = """
    (function(t){
      var x=window.__wlSel; if(!x) return false;
      if(x.el){ var el=x.el; el.focus(); el.setSelectionRange(x.s,x.e);
        if(!document.execCommand('insertText',false,t)){ el.setRangeText(t,x.s,x.e,'end'); el.dispatchEvent(new Event('input',{bubbles:true})); }
        return true; }
      if(x.range&&x.ce){ var s=window.getSelection(); s.removeAllRanges(); s.addRange(x.range); return document.execCommand('insertText',false,t); }
      if(x.el2){ x.el2.focus(); document.execCommand('selectAll',false,null); return document.execCommand('insertText',false,t); }
      return false;
    })(${org.json.JSONObject.quote(text)})
""".trimIndent()

internal fun MainActivity.writeWithAi() {
    if (!aiReady()) return
    webView.evaluateJavascript(rewriteGetJs) { raw ->
        val o = runCatching { org.json.JSONObject(jsString(raw)) }.getOrNull() ?: org.json.JSONObject()
        val text = o.optString("text").trim()
        val editable = o.optBoolean("editable")
        val actions = if (text.isEmpty()) arrayOf("✍️ Scrivi da zero") else
            arrayOf("✨ Migliora", "✓ Correggi errori", "👔 Più formale", "😊 Più amichevole", "✂️ Più breve", "🇬🇧 Traduci in inglese", "↩️ Scrivi una risposta")
        if (text.isEmpty() && !editable) { toast("Tocca prima una casella di testo o seleziona del testo"); return@evaluateJavascript }
        MaterialAlertDialogBuilder(this).setTitle("✨ Scrivi con AI").setItems(actions) { _, w ->
            if (text.isEmpty()) {
                val d = resources.displayMetrics.density
                val et = TextInputEditText(this).apply { hint = "Cosa vuoi scrivere? Es. email per spostare l'appuntamento" }
                val box = android.widget.FrameLayout(this).apply { setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); addView(et) }
                MaterialAlertDialogBuilder(this).setTitle("✍️ Scrivi da zero").setView(box)
                    .setPositiveButton("Scrivi") { _, _ ->
                        val req = et.text?.toString()?.trim().orEmpty()
                        if (req.isNotEmpty()) runRewrite("Scrivi in italiano questo testo: $req. Rispondi solo con il testo pronto da usare.", editable)
                    }.setNegativeButton("Annulla", null).show()
                return@setItems
            }
            val instr = when (w) {
                0 -> "Migliora questo testo (chiarezza e scorrevolezza) mantenendo lingua, significato e tono"
                1 -> "Correggi solo gli errori di ortografia, grammatica e punteggiatura di questo testo, senza cambiarne lo stile"
                2 -> "Riscrivi questo testo in tono più formale e cortese"
                3 -> "Riscrivi questo testo in tono più amichevole e cordiale"
                4 -> "Riscrivi questo testo in modo più breve e diretto"
                5 -> "Traduci in inglese questo testo"
                else -> "Scrivi una risposta cortese in italiano a questo messaggio"
            }
            runRewrite("$instr. Rispondi solo con il testo finale, senza commenti.\n\n«$text»", editable && w != 6)
        }.show()
    }
}

internal fun MainActivity.runRewrite(prompt: String, canReplace: Boolean) {
    toast("✨ Scrivo…")
    ai.ask(listOf("user" to prompt), "Sei un assistente di scrittura. Rispondi solo con il testo richiesto.", object : AiBridge.Listener {
        override fun onDone(text: String, model: String) {
            val result = text.trim().removePrefix("«").removeSuffix("»").trim()
            val b = MaterialAlertDialogBuilder(this@runRewrite).setTitle("✨ Risultato").setMessage(result)
                .setNeutralButton("Copia") { _, _ -> copyText("Testo", result) }
                .setNegativeButton("Annulla", null)
            if (canReplace) b.setPositiveButton("Inserisci") { _, _ ->
                webView.evaluateJavascript(rewriteReplaceJs(result)) { r -> if (r != "true") { copyText("Testo", result); toast("Copiato: incollalo nella casella") } }
            }
            b.show()
        }
        override fun onError(message: String, noKey: Boolean) { toast(message) }
    })
}

// ======================= Price comparison & email =======================

internal fun MainActivity.comparePrices() {
    val js = """
        (function(){
          var name='';
          document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){
            try{ var j=JSON.parse(s.textContent); var arr=Array.isArray(j)?j:(j['@graph']||[j]);
              arr.forEach(function(x){ if(!name && x && (x['@type']==='Product' || (Array.isArray(x['@type'])&&x['@type'].indexOf('Product')>=0))) name=x.name||''; });
            }catch(e){}
          });
          if(!name){ var m=document.querySelector('meta[property="og:title"]'); if(m) name=m.content; }
          if(!name){ var h=document.querySelector('h1'); if(h) name=h.innerText; }
          return (name||document.title||'').trim().substring(0,200);
        })()
    """.trimIndent()
    webView.evaluateJavascript(js) { raw ->
        val title = jsString(raw).trim()
        if (title.isEmpty()) { toast("Non trovo il nome del prodotto"); return@evaluateJavascript }
        fun offer(q: String) {
            val e = Uri.encode(q)
            val shops = listOf(
                "Trovaprezzi" to "https://www.trovaprezzi.it/categoria.aspx?libera=$e",
                "Idealo" to "https://www.idealo.it/MainSearchProductCategory.html?q=$e",
                "Google Shopping" to "https://www.google.com/search?tbm=shop&q=$e",
                "Amazon" to "https://www.amazon.it/s?k=$e",
                "eBay" to "https://www.ebay.it/sch/i.html?_nkw=$e"
            )
            MaterialAlertDialogBuilder(this).setTitle("🛒 $q").setItems(shops.map { it.first }.toTypedArray()) { _, w ->
                openInNewTab(shops[w].second)
            }.show()
        }
        if (ai.supported() && title.length > 40) {
            toast("✨ Riconosco il prodotto…")
            ai.ask(listOf("user" to "Dal titolo di questa pagina estrai marca e modello del prodotto, in massimo 8 parole, " +
                "adatto a una ricerca di prezzi. Solo il testo.\n\n$title"), null, object : AiBridge.Listener {
                override fun onDone(text: String, model: String) { offer(text.trim().trim('"', '«', '»').take(80)) }
                override fun onError(message: String, noKey: Boolean) { offer(title.take(80)) }
            })
        } else offer(title.take(80))
    }
}

internal fun MainActivity.summarizeEmail() {
    if (!aiReady()) return
    readPage { title, url, text ->
        showAiSheet(
            title = "Email",
            context = pageContext(title, url, text),
            firstQuestion = "Questa è una pagina di Gmail. Riassumi l'email aperta in italiano: chi scrive, cosa chiede, " +
                "scadenze o azioni da fare. Se ci sono più email, riassumi l'ultima conversazione aperta.",
            showFirstQuestion = false,
            hint = "Es. scrivimi una risposta cortese"
        )
    }
}
