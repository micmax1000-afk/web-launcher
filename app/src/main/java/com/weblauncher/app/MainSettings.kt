package com.weblauncher.app

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

internal fun MainActivity.renderSettings() {
    settingsList.removeAllViews()
    val engine = repo.getSearchEngine()
    val palette = AppRepository.palette(this)
    val accent = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF5B8CFF.toInt())
    settingsPanel.setBackgroundColor(palette.bg)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    settingsList.addView(TextView(this).apply {
        text = "← Strumenti"
        textSize = 15f
        setTextColor(accent)
        setPadding(dp(4), dp(10), dp(4), 0)
        setOnClickListener { showTab(TAB_TOOLS) }
    })
    settingsList.addView(TextView(this).apply {
        text = "Impostazioni"
        textSize = 28f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(palette.text)
        setPadding(dp(4), dp(12), dp(4), dp(8))
    })
    var group: android.widget.LinearLayout? = null
    fun section(title: String) {
        settingsList.addView(TextView(this).apply {
            text = title
            setTextColor(accent)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dp(8), dp(20), dp(8), dp(8))
        })
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(palette.card)
            strokeWidth = dp(1)
            strokeColor = palette.stroke
        }
        val inner = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        card.addView(inner)
        settingsList.addView(card)
        group = inner
    }
    fun row(label: String, sub: String? = null, value: String? = null, danger: Boolean = false,
            switch: Boolean? = null, onClick: () -> Unit) {
        val g = group ?: return
        if (g.childCount > 0) g.addView(View(this).apply {
            setBackgroundColor(palette.stroke)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)
            ).apply { marginStart = dp(18) }
        })
        val r = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(18), dp(12), dp(14), dp(12))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(if (danger) ContextCompat.getColor(this@renderSettings, R.color.danger) else palette.text)
        })
        if (!sub.isNullOrBlank()) box.addView(TextView(this).apply {
            text = sub
            textSize = 14f
            setTextColor(palette.textMuted)
            setPadding(0, dp(3), 0, 0)
        })
        r.addView(box)
        if (value != null) r.addView(TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(accent)
            setPadding(dp(10), 0, dp(4), 0)
        })
        if (switch != null) {
            val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                isChecked = switch
                isClickable = false
                isFocusable = false
            }
            r.addView(sw)
        } else if (!danger) r.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_web_forward)
            setColorFilter(palette.textMuted)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
        })
        r.setOnClickListener { onClick() }
        g.addView(r)
    }

    section("BROWSER E RICERCA")
    row("Aggiungi web app", "Nome, indirizzo e categoria") { showAddEditDialog(null) }
    row("Browser predefinito", "Apri qui i link di WhatsApp, email…", value = if (isDefaultBrowser()) "Sì" else "No") { requestDefaultBrowser() }
    row("Motore di ricerca", value = engine.label) { showSearchEnginePicker() }
    row("Suggerimenti di ricerca", "Mentre scrivi l'indirizzo (dal motore scelto)", switch = repo.searchSuggestions()) {
        repo.setSearchSuggestions(!repo.searchSuggestions()); renderSettings()
    }
    row("Nascondi barre scorrendo", "Più spazio, ma lo scorrimento è meno fluido", switch = repo.hideBarsOnScroll()) {
        repo.setHideBarsOnScroll(!repo.hideBarsOnScroll()); hideBarsOn = repo.hideBarsOnScroll(); renderSettings()
    }
    row("Dimensione testo", value = "${repo.textZoom()}%") { showTextZoomDialog() }
    row("Link e app", "Scelte «apri nell'app o qui»: ${openInPrefs.all.size}") {
        val entries = openInPrefs.all.entries.sortedBy { it.key }
        if (entries.isEmpty()) { toast("Nessuna scelta salvata"); return@row }
        MaterialAlertDialogBuilder(this).setTitle("Tocca per dimenticare la scelta")
            .setItems(entries.map { "${it.key} → ${if (it.value == "app") "app" else "qui"}" }.toTypedArray()) { _, w ->
                openInPrefs.edit().remove(entries[w].key).apply(); renderSettings()
            }.setNeutralButton("Dimentica tutte") { _, _ -> openInPrefs.edit().clear().apply(); renderSettings() }.show()
    }
    val themeLabel = when (repo.getThemeMode()) {
        "light" -> "Chiaro"
        "system" -> "Sistema"
        else -> "Scuro"
    }
    row("Tema", value = themeLabel) { showThemePicker() }
    val accentLabel = when (repo.getAccent()) {
        "amber" -> "Ambra"
        "green" -> "Verde"
        else -> "Blu"
    }
    row("Colore", value = accentLabel) { showAccentPicker() }
    row("Siti in modalità scura", "Scurisce i siti chiari quando il tema è scuro", switch = repo.darkSites()) {
        repo.setDarkSites(!repo.darkSites())
        applyDarkSites()
        if (browserHasPage) webView.reload()
        renderSettings()
    }

    section("INTELLIGENZA ARTIFICIALE")
    val aiRowSub = "Usa provider, chiave e modello di Mia AI"
    row("Assistente AI", aiRowSub, value = "Configura") { openMiaAi() }
    row("Comandi vocali con AI", "Es. «apri YouTube e cerca ricette di pasta»", switch = repo.aiVoice()) {
        repo.setAiVoice(!repo.aiVoice()); renderSettings()
    }
    row("Cosa sa fare", "Riassumi, chiedi alla pagina, spiega il testo selezionato…") {
        MaterialAlertDialogBuilder(this).setTitle("✨ AI nel launcher")
            .setMessage(
                "• Menu ⋮ → Riassumi pagina\n" +
                "• Menu ⋮ → Chiedi alla pagina\n" +
                "• Scrivi una domanda nella ricerca → «Chiedi all'AI»\n" +
                "• Seleziona del testo in una pagina → «Chiedi all'AI»\n" +
                "• Microfono: comandi lunghi, es. «apri Amazon e cerca cuffie bluetooth»\n" +
                "• Seleziona testo o tocca una casella → «✨ Riscrivi»: migliora, correggi, traduci\n" +
                "• Menu ⋮ → 🛒 Confronta prezzi, 📧 Riassumi email (in Gmail)\n\n" +
                "Il testo viene inviato al provider scelto in Mia AI solo quando usi queste funzioni."
            )
            .setPositiveButton("OK", null).show()
    }


    section("COMODITÀ")
    row("📋 Appunti intelligenti", "Propone l'azione per link, pacchi e numeri copiati", switch = toolsPrefs.getBoolean("smartClipboard", true)) {
        toolsPrefs.edit().putBoolean("smartClipboard", !toolsPrefs.getBoolean("smartClipboard", true)).apply(); renderSettings()
    }


    section("NOTIZIE")
    row("Fonti notizie", "${news.enabledIds().size} attive su ${news.allSources().size}") { showNewsSources() }
    row("Aggiungi una fonte", "Da qualsiasi sito con notizie") { addNewsSourceDialog() }

    section("YOUTUBE")
    row("Opzioni YouTube", listOfNotNull(
        "salta annunci".takeIf { ytPrefs.getBoolean("skipAds", true) },
        "SponsorBlock".takeIf { ytPrefs.getBoolean("sponsorBlock", true) },
        "pulita".takeIf { ytPrefs.getBoolean("clean", false) },
        "audio a schermo spento".takeIf { ytPrefs.getBoolean("background", true) },
        "finestrella".takeIf { ytPrefs.getBoolean("autoPip", true) }
    ).joinToString(", ").ifBlank { "Tutto spento" }) { ytOptionsDialog() }
    row("Velocità e qualità", "${ytPrefs.getFloat("speed", 1f)}x · ${ytPrefs.getString("quality", "auto")}") { ytSpeedDialog() }
    row("Canali seguiti", "${ytChannels().size} · in Ascolta → Video") { audioTab = "video"; showTab(TAB_AUDIO) }

    section("APP INTEGRATE")
    val miaOnHome = apps.any { MiaAi.isMiaUrl(it.url) }
    row("Mia AI", "Chat AI personale: Gemini, OpenRouter, Groq, Ollama…",
        value = if (miaOnHome) "Apri" else "Aggiungi") {
        if (miaOnHome) openInBrowser(MiaAi.URL) else { addMiaToHome(); renderSettings() }
    }

    section("SICUREZZA E PRIVACY")
    row("🔒 Blocca l'app con impronta", if (lockEnabled()) "Attivo · " + listOf("subito", "dopo 1 minuto", "dopo 5 minuti", "dopo 15 minuti")[listOf(0, 1, 5, 15).indexOf(lockPrefs.getInt("delayMin", 1)).coerceAtLeast(0)]
        else "Impronta o PIN all'apertura", switch = lockEnabled()) { toggleAppLock() }
    if (lockEnabled()) row("Quando bloccare", "Dopo quanto tempo fuori dall'app") {
        val vals = intArrayOf(0, 1, 5, 15)
        MaterialAlertDialogBuilder(this).setTitle("Blocca")
            .setSingleChoiceItems(arrayOf("Subito", "Dopo 1 minuto", "Dopo 5 minuti", "Dopo 15 minuti"), vals.indexOf(lockPrefs.getInt("delayMin", 1)).coerceAtLeast(0)) { dl, w ->
                lockPrefs.edit().putInt("delayMin", vals[w]).apply(); dl.dismiss(); renderSettings()
            }.show()
    }
    row("⚠️ Avviso siti truffa", "Avvisa prima di aprire siti che imitano Poste, banche, corrieri…", switch = toolsPrefs.getBoolean("phishing", true)) {
        toolsPrefs.edit().putBoolean("phishing", !toolsPrefs.getBoolean("phishing", true)).apply(); renderSettings()
    }
    row("Cancella dati del profilo 2", danger = true) {
        confirmClear("Cancellare cookie e login del profilo 2?") { IsolatedBrowserActivity.clear(); toast("Profilo 2 pulito") }
    }
    row("🧹 Pulizia automatica", "Ogni settimana cancella cookie e dati dei siti non visitati da 30 giorni (non delle tue app)",
        switch = toolsPrefs.getBoolean("autoClean", true)) {
        toolsPrefs.edit().putBoolean("autoClean", !toolsPrefs.getBoolean("autoClean", true)).apply(); renderSettings()
    }
    row("🧹 Pulisci ora") { val n = autoCleanup(force = true); toast(if (n == 0) "Niente da pulire" else "Puliti i dati di $n siti") }
    row("Blocca pubblicità e tracker", "Pagine più veloci, meno dati consumati", switch = repo.blockAds()) {
        repo.setBlockAds(!repo.blockAds()); blockAdsOn = repo.blockAds()
        if (browserHasPage) webView.reload()
        renderSettings()
    }
    val block3p = repo.blockThirdPartyCookies()
    row("Blocca cookie di terze parti", "Disattiva solo se un login non funziona", switch = block3p) {
        val next = !block3p
        repo.setBlockThirdPartyCookies(next)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !next)
        renderSettings()
    }
    row("Password e compilazione", "Autofill di Android") { showPasswordInfo() }
    row("Cancella cronologia", "Recenti e cronologia del browser") { confirmClear("Cancellare cronologia e recenti?") { clearHistoryAndRecents(); toast("Cronologia cancellata") } }
    row("Cancella cookie", "Ti disconnette dai siti") { confirmClear("Cancellare tutti i cookie?") { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); toast("Cookie cancellati") } }
    row("Cancella cache") { confirmClear("Cancellare la cache?") { webView.clearCache(true); toast("Cache cancellata") } }
    row("Cancella tutti i dati", "Recenti, cronologia, cookie, cache", danger = true) { confirmClear("Cancellare tutti i dati di navigazione?") { clearAllBrowsingData(); toast("Dati cancellati") } }

    section("BACKUP E SINCRONIZZAZIONE")
    row("📦 Backup completo", syncPrefs.getLong("fullLast", 0L).let { if (it > 0) "Ultimo ${NewsAdapter.ago(it)}" + (if (syncPrefs.getString("fullUri", null) != null) " · ogni notte" else "") else "Tutto in un file: libri, registrazioni, biglietti…" }) { showFullBackupMenu() }
    row("🔄 Sincronizza tra dispositivi", if (repo.getAutoBackupUri() != null) "Collegato a un file di backup" else "Tramite un file su Google Drive") { showSyncHelp() }
    val autoOn = repo.getAutoBackupUri() != null
    row("Backup", if (autoOn) "Automatico attivo" else "Su file, Drive o condivisione") { showBackupMenu() }
    row("Importa preferiti da Chrome", "Da un file bookmarks.html") { showBookmarkImportHelp() }
    row("Ripristina", "Da un file di backup") {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    section("AGGIORNAMENTI")
    row("⬆️ Controlla aggiornamenti", "Versione ${Updater.installedName(this)} (build ${Updater.installedCode(this)})" +
        Updater.lastCheck(this).let { if (it > 0) " · controllato ${NewsAdapter.ago(it)}" else "" }) { checkForUpdate(manual = true) }
    row("Avvisami delle nuove versioni", "Controllo automatico due volte al giorno", switch = Updater.autoCheck(this)) {
        Updater.setAutoCheck(this, !Updater.autoCheck(this)); if (Updater.autoCheck(this)) ensureNotificationPermission(); renderSettings()
    }

    section("HOME")
    row("🌦 Meteo in home", if (weatherPrefs.getBoolean("hidden", false)) "Nascosto" else weatherPrefs.getString("city", null) ?: "Scegli la città") {
        weatherPrefs.edit().putBoolean("hidden", false).apply(); renderWeather(); setupWeather()
    }
    row("🏠 Indirizzo di casa", carPrefs.getString("home", null) ?: "Per «Portami a casa» in modalità auto") { setHomeAddress { renderSettings() } }
    row("☀️ Buongiorno ogni mattina", if (Brief.enabled(this)) "Alle %02d:%02d · meteo, appuntamenti, scadenze, notizie".format(Brief.hour(this), Brief.minute(this))
        else "Una notifica con il riepilogo della giornata", switch = Brief.enabled(this)) {
        if (Brief.enabled(this)) { Brief.set(this, false); renderSettings() } else briefSettings()
    }
    if (Brief.enabled(this)) row("Ora del buongiorno", value = "%02d:%02d".format(Brief.hour(this), Brief.minute(this))) { briefSettings() }
    row("📅 Riquadro «Oggi»", "Appuntamenti, promemoria e scadenze sotto la ricerca", switch = homePrefs.getBoolean("today", true)) {
        homePrefs.edit().putBoolean("today", !homePrefs.getBoolean("today", true)).apply(); renderWeather(); renderSettings()
    }
    row("🧩 Widget", "Ricerca e Notizie sulla schermata del telefono") {
        MaterialAlertDialogBuilder(this).setTitle("Widget")
            .setMessage("Tieni premuto su uno spazio vuoto della schermata del telefono → Widget → Web Launcher.\n\n" +
                "• Cerca: barra di ricerca con microfono e notizie\n• Notizie: gli ultimi titoli delle tue fonti")
            .setPositiveButton("OK", null).show()
    }
    row("Icone per riga", value = homePrefs.getInt("cols", 4).toString()) {
        val vals = intArrayOf(3, 4, 5)
        MaterialAlertDialogBuilder(this).setTitle("Icone per riga")
            .setSingleChoiceItems(arrayOf("3 (grandi)", "4", "5 (piccole)"), vals.indexOf(homePrefs.getInt("cols", 4)).coerceAtLeast(0)) { dl, w ->
                homePrefs.edit().putInt("cols", vals[w]).apply(); applyHomeLook(); dl.dismiss(); renderSettings()
            }.show()
    }
    row("Nascondi i nomi delle app", switch = homePrefs.getBoolean("hideNames", false)) {
        homePrefs.edit().putBoolean("hideNames", !homePrefs.getBoolean("hideNames", false)).apply(); applyHomeLook(); renderSettings()
    }
    row("Sfondo della home", if (homePrefs.getBoolean("wallpaper", false)) "Foto personale · tocca per cambiare" else "Nessuno") {
        if (homePrefs.getBoolean("wallpaper", false)) {
            MaterialAlertDialogBuilder(this).setTitle("Sfondo").setItems(arrayOf("Cambia foto", "Togli lo sfondo")) { _, w ->
                if (w == 0) wallpaperPicker.launch("image/*")
                else { homePrefs.edit().putBoolean("wallpaper", false).apply(); applyHomeLook(); renderSettings() }
            }.show()
        } else wallpaperPicker.launch("image/*")
    }
    row("📁 Cartelle", "Tieni premuta un'app → «Metti in una cartella»") { toast("Tieni premuta un'app in home → Metti in una cartella") }
    row("Mostra \"Più usate\"", "In cima alla home, quando hai almeno 6 app", switch = repo.showMostUsed()) {
        repo.setShowMostUsed(!repo.showMostUsed()); refreshList(); renderSettings()
    }
    row("Sposta le icone", "Tieni premuta un'app → Sposta") { toast("Tieni premuta un'app e scegli Sposta") }
    row("Microfono", "Come attivare il permesso voce") {
        MaterialAlertDialogBuilder(this).setTitle("Microfono")
            .setMessage("Voce nativa Android.\nImpostazioni → App → Web Launcher → Microfono → Consenti")
            .setPositiveButton("OK", null).show()
    }
    row("Svuota home", "Elimina tutte le app salvate", danger = true) {
        MaterialAlertDialogBuilder(this).setMessage("Eliminare tutte le app dalla home? Puoi recuperarle solo da un backup.")
            .setPositiveButton("Svuota") { _, _ ->
                apps.forEach { a -> a.iconPath?.let { runCatching { java.io.File(it).delete() } } }
                apps = repo.resetDefaults(); categories = repo.loadCategories(); autoBackupNow(); refreshList(); toast("Home svuotata")
            }.setNegativeButton("Annulla", null).show()
    }

    settingsList.addView(TextView(this).apply {
        text = "Web Launcher $appVersion"
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setTextColor(palette.textMuted)
        setPadding(0, dp(24), 0, dp(8))
    })
}

internal fun MainActivity.showSearchEnginePicker() {
    val engines = SearchEngine.entries
    val labels = engines.map { it.label }.toTypedArray()
    val current = engines.indexOf(repo.getSearchEngine()).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this)
        .setTitle("Motore di ricerca")
        .setSingleChoiceItems(labels, current) { dialog, which ->
            repo.setSearchEngine(engines[which])
            toast("Motore: ${engines[which].label}")
            if (currentTab == TAB_SETTINGS) renderSettings()
            dialog.dismiss()
        }
        .setNegativeButton("Annulla", null)
        .show()
}

internal fun MainActivity.showThemePicker() {
    val modes = arrayOf("dark", "light", "system")
    val labels = arrayOf("Scuro", "Chiaro", "Sistema")
    val current = modes.indexOf(repo.getThemeMode()).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this)
        .setTitle("Tema")
        .setSingleChoiceItems(labels, current) { dialog, which ->
            repo.setThemeMode(modes[which])
            AppRepository.applySavedTheme(this)
            dialog.dismiss()
        }
        .setNegativeButton("Annulla", null)
        .show()
}

internal fun MainActivity.showAccentPicker() {
    val modes = arrayOf("blue", "amber", "green")
    val labels = arrayOf("Blu", "Ambra", "Verde")
    val current = modes.indexOf(repo.getAccent()).coerceAtLeast(0)
    MaterialAlertDialogBuilder(this)
        .setTitle("Colore")
        .setSingleChoiceItems(labels, current) { dialog, which ->
            repo.setAccent(modes[which])
            dialog.dismiss()
            recreate()
        }
        .setNegativeButton("Annulla", null)
        .show()
}
