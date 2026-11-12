package com.weblauncher.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

private enum class St { OK, TODO, OPTIONAL }
private class Check(val icon: String, val title: String, val state: St, val okText: String, val fixText: String, val fix: () -> Unit)

private fun MainActivity.has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun MainActivity.openAppSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }.onFailure { toast("Impostazioni non disponibili") }
}

private fun MainActivity.askPermission(p: String) {
    // after a refusal Android no longer shows the question: go to the app's settings instead
    if (shouldShowRequestPermissionRationale(p) || getSharedPreferences("wl_prefs", android.content.Context.MODE_PRIVATE).getBoolean("asked_$p", false).not()) {
        getSharedPreferences("wl_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("asked_$p", true).apply()
        checkPermissionLauncher.launch(p)
    } else openAppSettings()
}

private fun MainActivity.checks(): List<Check> {
    val out = mutableListOf<Check>()
    // --- essential: reminders and alerts must arrive ---
    val notifOk = if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.POST_NOTIFICATIONS) && NotificationManagerCompat.from(this).areNotificationsEnabled() else NotificationManagerCompat.from(this).areNotificationsEnabled()
    out += Check("🔔", "Notifiche", if (notifOk) St.OK else St.TODO, "Attive: promemoria e avvisi arrivano", "Spente: non arriverebbero promemoria, farmaci e avvisi") {
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) askPermission(Manifest.permission.POST_NOTIFICATIONS)
        else runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }.onFailure { openAppSettings() }
    }
    val exactOk = Build.VERSION.SDK_INT < 31 || (getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
    out += Check("⏰", "Promemoria all'ora giusta", if (exactOk) St.OK else St.TODO, "Permesso dato: suonano all'ora esatta", "Senza il permesso possono arrivare in ritardo") {
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }.onFailure { openAppSettings() }
    }
    val battOk = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    out += Check("🔋", "Batteria senza limiti", if (battOk) St.OK else St.TODO, "Android non ferma l'app in background", "Consigliato: scegli «Senza limiti» per avvisi, allenamenti e musica affidabili") {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.onFailure { openAppSettings() }
    }
    // --- useful, if you use the feature ---
    out += Check("🔑", "Salvataggio password", if (autofillOn()) St.OK else St.OPTIONAL, "Gestore password attivo", "Scegli Google per salvare le password dei siti") { showPasswordInfo() }
    out += Check("📍", "Posizione", if (has(Manifest.permission.ACCESS_FINE_LOCATION)) St.OK else St.OPTIONAL, "Permesso dato: meteo e allenamenti con il GPS", "Serve per il meteo del posto e gli allenamenti con il GPS") { askPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    out += Check("🎤", "Microfono", if (has(Manifest.permission.RECORD_AUDIO)) St.OK else St.OPTIONAL, "Permesso dato: comandi vocali e registratore", "Serve per i comandi vocali e il registratore") { askPermission(Manifest.permission.RECORD_AUDIO) }
    out += Check("📅", "Calendario", if (has(Manifest.permission.READ_CALENDAR)) St.OK else St.OPTIONAL, "Collegato: gli appuntamenti compaiono in «Oggi»", "Per vedere gli appuntamenti in «Oggi» e in «Da fare»") { askPermission(Manifest.permission.READ_CALENDAR) }
    out += Check("🎂", "Contatti (compleanni)", if (has(Manifest.permission.READ_CONTACTS)) St.OK else St.OPTIONAL, "Collegati: compleanni dai contatti", "Per i compleanni dai contatti (puoi anche scriverli a mano)") { askPermission(Manifest.permission.READ_CONTACTS) }
    out += Check("👟", "Passi", if (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)) St.OK else St.OPTIONAL, "Permesso dato: contapassi", "Per contare i passi con il telefono") { askPermission(Manifest.permission.ACTIVITY_RECOGNITION) }
    val hcOn = Hc.status(this) == Hc.SDK_AVAILABLE && Hc.cached(this) != null
    out += Check("⌚", "Orologio (Mi Fitness)", if (hcOn) St.OK else St.OPTIONAL, "Collegato tramite Health Connect", "Per vedere passi, sonno e battito dell'orologio") { showHealthConnectPage() }
    out += Check("🎵", "Titolo della musica", if (Music.hasAccess(this)) St.OK else St.OPTIONAL, "Attivo: titolo e copertina visibili", "Facoltativo: i tasti funzionano già senza (serve «Accesso alle notifiche»)") { askMusicAccess() }
    // --- keeping safe ---
    val last = syncPrefs.getLong("fullLast", 0L)
    val days = if (last > 0) (System.currentTimeMillis() - last) / 86_400_000L else -1
    out += Check("📦", "Backup", if (days in 0..30) St.OK else St.TODO, "Ultimo backup ${if (days <= 0) "oggi" else "$days giorni fa"}", if (days < 0) "Nessun backup: se perdi il telefono perdi i dati" else "Ultimo backup $days giorni fa: rifallo") { showBackupTools() }
    out += Check("⬆️", "Aggiornamenti automatici", if (Updater.autoCheck(this)) St.OK else St.OPTIONAL, "Attivi: ti avviso delle novità", "Attivali per ricevere le nuove versioni") { Updater.setAutoCheck(this, true); appCheckRefresh?.invoke() }
    out += Check("🌐", "Browser predefinito", if (isDefaultBrowser()) St.OK else St.OPTIONAL, "Sì: i link si aprono qui", "Facoltativo: apri qui i link di WhatsApp e email") { requestDefaultBrowser() }
    return out
}

/** One page that checks permissions and Android settings and fixes them with a tap. */
internal fun MainActivity.showAppCheck() {
    openPage("Controllo dell'app") { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val list = checks()
            val todo = list.filter { it.state == St.TODO }
            sectionLabel(body, "RISULTATO")
            listRow(body, if (todo.isEmpty()) "✅ Tutto a posto" else "⚠️ ${todo.size} ${if (todo.size == 1) "cosa da sistemare" else "cose da sistemare"}",
                if (todo.isEmpty()) "Le cose importanti funzionano" else todo.joinToString(", ") { it.title }, onClick = {})
            if (todo.isNotEmpty()) { sectionLabel(body, "DA SISTEMARE"); todo.forEach { c -> listRow(body, "${c.icon} ${c.title}", c.fixText, onClick = { c.fix() }) } }
            val opt = list.filter { it.state == St.OPTIONAL }
            if (opt.isNotEmpty()) { sectionLabel(body, "FACOLTATIVO"); opt.forEach { c -> listRow(body, "➕ ${c.icon} ${c.title}", c.fixText, onClick = { c.fix() }) } }
            val ok = list.filter { it.state == St.OK }
            if (ok.isNotEmpty()) { sectionLabel(body, "A POSTO"); ok.forEach { c -> listRow(body, "✅ ${c.icon} ${c.title}", c.okText, onClick = {}) } }
            hintLabel(body, "Quando torni da una schermata di Android, questa pagina si aggiorna da sola. Se Android dice «accesso negato» per un'app installata fuori dal Play Store, apri Impostazioni → App → Web Launcher → ⋮ → «Consenti impostazioni con restrizioni».")
        }
        render()
        appCheckRefresh = { runCatching { if (dialog.isShowing) render() } }
        dialog.setOnDismissListener { appCheckRefresh = null }
    }
}
