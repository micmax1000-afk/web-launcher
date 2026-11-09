package com.weblauncher.app

import android.content.Context.CAMERA_SERVICE
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

/** A non-browser app installed for this link (e.g. Drive, Maps, Amazon), if any. */
internal fun MainActivity.nativeAppFor(url: String): android.content.pm.ResolveInfo? = runCatching {
    val pm = packageManager
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/x")).addCategory(Intent.CATEGORY_BROWSABLE), 0)
        .map { it.activityInfo.packageName }.toSet()
    pm.queryIntentActivities(i, 0).firstOrNull { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in browsers }
}.getOrNull()

/** True if the link was handed to an app (or a question is shown); false = open it here. */
internal fun MainActivity.maybeOpenInApp(url: String, onHere: () -> Unit): Boolean {
    val host = hostOf(url) ?: return false
    if (apps.any { hostOf(normalizeUrl(it.url)) == host }) return false
    val pref = openInPrefs.getString(host, null)
    if (pref == "web") return false
    val app = nativeAppFor(url) ?: return false
    val pkg = app.activityInfo.packageName
    val launch = {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { onHere() }
    }
    if (pref == "app") { launch(); return true }
    val name = app.loadLabel(packageManager).toString()
    val remember = android.widget.CheckBox(this).apply { text = "Ricorda per $host"; isChecked = true }
    val box = android.widget.FrameLayout(this).apply {
        val d = resources.displayMetrics.density
        setPadding((20 * d).toInt(), (6 * d).toInt(), (20 * d).toInt(), 0); addView(remember)
    }
    MaterialAlertDialogBuilder(this).setTitle("Aprire con $name?").setMessage(host).setView(box)
        .setPositiveButton("App $name") { _, _ -> if (remember.isChecked) openInPrefs.edit().putString(host, "app").apply(); launch() }
        .setNegativeButton("Qui") { _, _ -> if (remember.isChecked) openInPrefs.edit().putString(host, "web").apply(); onHere() }
        .show()
    return true
}

/** (name, number) of the best matching contact, or null. */
internal fun MainActivity.findContact(name: String): Pair<String, String>? = runCatching {
    contentResolver.query(
        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE),
        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
    )?.use { c ->
        val all = mutableListOf<Triple<String, String, Int>>()
        while (c.moveToNext()) all.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2)))
        all.sortedWith(compareBy<Triple<String, String, Int>> { !it.first.equals(name, true) }
            .thenBy { !it.first.lowercase().startsWith(name.lowercase()) }
            .thenBy { it.third != android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE })
            .firstOrNull()?.let { it.first to it.second }
    }
}.getOrNull()

internal fun MainActivity.withContact(cmd: String, name: String, use: (String, String) -> Unit) {
    val digits = name.replace(Regex("[^0-9+]"), "")
    if (digits.length >= 6) { use(name, digits); return }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        pendingContactCommand = cmd; contactsPermission.launch(Manifest.permission.READ_CONTACTS); return
    }
    val c = findContact(name.trim()) ?: run { toast("Non trovo «$name» nei contatti"); return }
    use(c.first, c.second)
}

internal fun MainActivity.spokenNumber(w: String): Int? = when (w.lowercase(Locale.ITALIAN)) {
    "un", "uno", "una" -> 1; "due" -> 2; "tre" -> 3; "quattro" -> 4; "cinque" -> 5; "sei" -> 6; "sette" -> 7; "otto" -> 8
    "nove" -> 9; "dieci" -> 10; "quindici" -> 15; "venti" -> 20; "trenta" -> 30; "quaranta" -> 40; "mezz'ora", "mezzora" -> 30
    else -> w.toIntOrNull()
}

/** Alarm, timer, torch, calls, WhatsApp and SMS by voice. Returns true if handled. */
internal fun MainActivity.systemVoiceCommand(raw: String): Boolean {
    val t = raw.trim().lowercase(Locale.ITALIAN)
    Regex("^(?:metti|imposta|punta|crea)?\\s*(?:la|una)?\\s*sveglia\\s+(?:alle|per le|a|ore)?\\s*(\\d{1,2})(?:[:. ](\\d{2}))?(?:\\s+e\\s+(mezza|un quarto|\\d{1,2}))?").find(t)?.let { m ->
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: when (m.groupValues[3]) { "mezza" -> 30; "un quarto" -> 15; "" -> 0; else -> m.groupValues[3].toInt() }
        if (t.contains("pomeriggio") || t.contains("sera")) { if (h < 12) h += 12 }
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, min)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Sveglia").putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        runCatching { startActivity(i); toast("⏰ Sveglia alle %d:%02d".format(h, min)) }.onFailure { toast("L'orologio del telefono non accetta sveglie") }
        return true
    }
    Regex("^(?:metti|imposta|avvia|fai partire)?\\s*(?:un)?\\s*timer\\s+(?:di|da|per)?\\s*(\\S+)\\s*(minuti|minuto|secondi|secondo|ore|ora)?(?:\\s+e\\s+(\\S+)\\s*(secondi|minuti)?)?").find(t)?.let { m ->
        val n = spokenNumber(m.groupValues[1]) ?: return@let
        var secs = when { m.groupValues[2].startsWith("sec") -> n; m.groupValues[2].startsWith("or") -> n * 3600; m.groupValues[1].startsWith("mezz") -> 1800; else -> n * 60 }
        spokenNumber(m.groupValues[3])?.let { extra -> secs += if (m.groupValues[4].startsWith("min")) extra * 60 else if (m.groupValues[2].startsWith("or")) extra * 60 else extra }
        val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Timer")
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        runCatching { startActivity(i); toast("⏱ Timer di ${fmtTime(secs * 1000L)}") }.onFailure { toast("L'orologio del telefono non accetta timer") }
        return true
    }
    calendarCommand(raw, t)?.let { return it }
    if (Regex("\\b(accendi|spegni|attiva|disattiva)\\b.*\\btorcia\\b|^torcia$").containsMatchIn(t)) {
        val on = !Regex("\\b(spegni|disattiva)\\b").containsMatchIn(t) && !(t == "torcia" && torchState)
        val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
        if (id == null) toast("Torcia non disponibile") else runCatching { cm.setTorchMode(id, on); torchState = on; toast(if (on) "🔦 Torcia accesa" else "Torcia spenta") }
        return true
    }
    Regex("^(?:manda|invia|scrivi)\\s+(?:un\\s+)?(?:messaggio\\s+)?(?:whatsapp|su whatsapp)\\s+(?:a|ad)\\s+(.+?)(?:\\s+(?:che|dicendo|dicendogli|dicendole|scrivendo)\\s+(.*))?$").find(t)?.let { m ->
        val body = raw.trim().let { r -> m.groupValues[2].takeIf { it.isNotBlank() }?.let { g -> r.substring(r.lowercase(Locale.ITALIAN).lastIndexOf(g)).trim() } }.orEmpty()
        withContact(raw, m.groupValues[1]) { name, number ->
            var n = number.replace(Regex("[^0-9+]"), "")
            n = when { n.startsWith("+") -> n.drop(1); n.startsWith("00") -> n.drop(2); n.startsWith("3") || n.startsWith("0") -> "39$n"; else -> n }
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n" + if (body.isNotEmpty()) "?text=" + Uri.encode(body) else ""))
            runCatching { startActivity(i.setPackage("com.whatsapp")) }.recoverCatching { startActivity(i.setPackage("com.whatsapp.w4b")) }
                .onFailure { toast("WhatsApp non installato") }
            toast("💬 WhatsApp a $name")
        }
        return true
    }
    Regex("^(?:manda|invia|scrivi)\\s+(?:un\\s+)?(?:sms|messaggio)\\s+(?:a|ad)\\s+(.+?)(?:\\s+(?:che|dicendo|scrivendo)\\s+(.*))?$").find(t)?.let { m ->
        val body = raw.trim().let { r -> m.groupValues[2].takeIf { it.isNotBlank() }?.let { g -> r.substring(r.lowercase(Locale.ITALIAN).lastIndexOf(g)).trim() } }.orEmpty()
        withContact(raw, m.groupValues[1]) { name, number ->
            runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)) }
            toast("✉️ Messaggio a $name")
        }
        return true
    }
    Regex("^(?:chiama|telefona a|telefona|fai una chiamata a)\\s+(.+)$").find(t)?.let { m ->
        withContact(raw, m.groupValues[1]) { name, number ->
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
            toast("📞 $name: tocca il tasto verde per chiamare")
        }
        return true
    }
    if (Regex("^(?:attiva |apri |avvia )?(?:la )?modalità auto|^modalità guida").containsMatchIn(t)) { showCarMode(); return true }
    return false
}

internal fun MainActivity.listenForQuestion() {
    if (!aiReady()) return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Fai una domanda")
    }
    try { askSpeechLauncher.launch(intent) } catch (_: Exception) { toast(getString(R.string.no_speech)) }
}

internal fun MainActivity.looksLikeQuestion(t: String): Boolean {
    val s = t.trim().lowercase(Locale.ITALIAN)
    return s.endsWith("?") || Regex("^(chi|cosa|che cosa|che|come|quando|dove|perché|perche|quanto|quanti|quante|quale|quali|qual|dimmi|spiegami|raccontami|sai)${WB}").containsMatchIn(s)
}

internal fun MainActivity.askByVoice(question: String) {
    if (!aiReady()) return
    val d = resources.displayMetrics.density
    val palette = AppRepository.palette(this)
    val answer = TextView(this).apply {
        text = "…"; textSize = 17f; setTextColor(palette.text); setLineSpacing(0f, 1.2f)
        setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0); setTextIsSelectable(true)
    }
    var full = ""
    val dlg = MaterialAlertDialogBuilder(this).setTitle("🗣 $question").setView(android.widget.ScrollView(this).apply { addView(answer) })
        .setPositiveButton("Altra domanda") { _, _ -> tts?.stop(); listenForQuestion() }
        .setNeutralButton("Continua in chat") { _, _ -> tts?.stop(); showAiSheet("Risposta AI", null, question) }
        .setNegativeButton("Stop") { _, _ -> tts?.stop() }
        .setOnCancelListener { tts?.stop() }
        .show()
    val id = ai.ask(listOf("user" to question),
        "Sei un assistente vocale. Rispondi in italiano in massimo 3-4 frasi brevi e chiare, adatte a essere lette ad alta voce: " +
            "niente elenchi, simboli, tabelle o link. Se servono dati aggiornati che non conosci, dillo.",
        object : AiBridge.Listener {
            override fun onPartial(text: String) { answer.text = text }
            override fun onDone(text: String, model: String) { full = text; answer.text = text; if (dlg.isShowing) speak(text, 1f) }
            override fun onError(message: String, noKey: Boolean) { answer.text = message }
        })
    dlg.setOnDismissListener { if (full.isEmpty()) ai.cancel(id) }
}

internal fun MainActivity.updateUnifiedSearch(q: String) {
    val box = findViewById<android.widget.LinearLayout>(R.id.searchResults)
    val query = q.trim().lowercase(Locale.ITALIAN)
    val token = ++searchToken
    if (query.length < 2) { box.removeAllViews(); box.isVisible = false; return }
    Thread {
        data class Hit(val icon: String, val title: String, val sub: String, val action: () -> Unit)
        val hits = mutableListOf<Hit>()
        fun has(s: String?) = s?.lowercase(Locale.ITALIAN)?.contains(query) == true
        runCatching {
            notes.load().filter { has(it.text) }.take(3).forEach { n ->
                val first = n.text.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                hits += Hit(if (isChecklist(n.text)) "☑" else "📝", first.take(60), "Nota", {
                    if (isChecklist(n.text)) showChecklist(n.id) else editNote(n)
                })
            }
            Ebooks.load(this).filter { has(it.title) || has(it.author) }.take(2).forEach { b -> hits += Hit("📖", b.title, "Libro · ${b.author}", { openBook(b) }) }
            wallet.load().filter { has(it.title) }.take(2).forEach { w -> hits += Hit("🎫", w.title, "Biglietti e documenti", { openWalletItem(w) }) }
            cards.load().filter { has(it.name) }.take(2).forEach { c -> hits += Hit("💳", c.name, "Carta fedeltà", { showCodeFullscreen(c.name, c.code, c.format, c.color) }) }
            Deadlines.load(this).filter { has(it.title) }.take(2).forEach { x -> hits += Hit("📆", x.title, "Scadenza · ${daysLeft(x.date)}", { showDeadlinesPage(x.kind) }) }
            Places.load(this).filter { has(it.name) || has(it.note) }.take(2).forEach { p -> hits += Hit("📍", p.name, "Luogo · tocca per andarci", { navigateTo(p.lat, p.lon, false) }) }
            Expenses.load(this).filter { has(it.desc) }.sortedByDescending { it.time }.take(2).forEach { e ->
                hits += Hit("💰", "${Expenses.fmt(e.amount)} · ${e.desc}", "Spesa", { showExpensesPage() })
            }
            readLater.load().filter { has(it.title) }.take(2).forEach { r ->
                hits += Hit("🔖", r.title, "Leggi dopo", { showReader(ReaderDoc(r.title, r.site, r.url, r.image, r.text), r) })
            }
            repo.loadHistory().filter { has(it.title) || has(it.url) }.distinctBy { it.url }.take(3).forEach { h ->
                hits += Hit("🕘", recentLabel(RecentSite(h.title, h.url)), displayUrl(h.url), { openInBrowser(h.url) })
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$query%"), null)?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 3) {
                        val name = c.getString(0) ?: continue; val num = c.getString(1) ?: continue; n++
                        hits += Hit("👤", name, num, { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))) } })
                    }
                }
            }
        }
        runOnUiThread {
            if (token != searchToken) return@runOnUiThread
            box.removeAllViews()
            if (hits.isEmpty()) { box.isVisible = false; return@runOnUiThread }
            val palette = AppRepository.palette(this)
            val d = resources.displayMetrics.density
            box.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d; setColor(palette.card); setStroke(d.toInt().coerceAtLeast(1), palette.stroke)
            }
            hits.take(8).forEach { h ->
                box.addView(android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
                    addView(TextView(this@updateUnifiedSearch).apply { text = h.icon; textSize = 18f; setPadding(0, 0, (12 * d).toInt(), 0) })
                    addView(android.widget.LinearLayout(this@updateUnifiedSearch).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(this@updateUnifiedSearch).apply { text = h.title; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(palette.text) })
                        addView(TextView(this@updateUnifiedSearch).apply { text = h.sub; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(palette.textMuted) })
                    })
                    setOnClickListener { searchInput.setText(""); h.action() }
                })
            }
            box.isVisible = true
        }
    }.start()
}

/**
 * «metti dentista martedì alle 10», «aggiungi al calendario cena da Luca sabato alle 20»,
 * «appuntamento dal commercialista il 12/12 alle 15»: opens the phone's calendar already filled in.
 */
internal fun MainActivity.calendarCommand(raw: String, t: String): Boolean? {
    val (cleaned, hasCal) = CalendarVoice.clean(t) ?: return null
    val p = Reminders.parse(cleaned) ?: return if (hasCal) { toast("Dimmi anche giorno e ora, es. «martedì alle 10»"); true } else null
    val title = p.text.ifBlank { "Appuntamento" }
    val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, title)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, p.at)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, p.at + 3_600_000L)
    return runCatching {
        startActivity(i)
        toast("📅 ${title} · " + java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN).format(java.util.Date(p.at)) + ": controlla e salva")
        true
    }.getOrElse { toast("Nessuna app calendario trovata"); true }
}
