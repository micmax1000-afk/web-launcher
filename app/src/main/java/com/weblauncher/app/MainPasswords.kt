package com.weblauncher.app

import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ======================= Password =======================

/** Whether Android has a password manager chosen for filling and saving logins. */
internal fun MainActivity.autofillOn(): Boolean = Build.VERSION.SDK_INT >= 26 && runCatching {
    getSystemService(AutofillManager::class.java)?.let { it.isAutofillSupported && it.hasEnabledAutofillServices() } ?: false
}.getOrDefault(false)

private fun MainActivity.openAutofillSettings() {
    runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(android.net.Uri.parse("package:com.google.android.gms"))) }
        .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(android.net.Uri.parse("package:$packageName"))) }
        .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        .onFailure { toast("Apri Impostazioni → Password → Servizio di compilazione automatica") }
}

internal fun MainActivity.showPasswordInfo() {
    openPage("Password") { _, body, dialog ->
        fun render() {
            body.removeAllViews()
            val on = autofillOn()
            sectionLabel(body, "STATO")
            listRow(body, if (on) "✅ Salvataggio password attivo" else "⚠️ Salvataggio password spento",
                if (on) "Android propone di salvare la password quando accedi a un sito" else "Nessun gestore password scelto in Android: per questo il browser non le salva",
                onClick = { openAutofillSettings() })
            sectionLabel(body, if (on) "COSA FARE" else "COME ATTIVARLO")
            if (!on) {
                listRow(body, "1️⃣ Scegli «Google» come servizio", "Si apre la schermata di Android: seleziona Google e conferma", onClick = { openAutofillSettings() })
                listRow(body, "2️⃣ Accedi a un sito", "Al login Android chiede «Salvare la password?»: tocca Salva", onClick = {})
                listRow(body, "3️⃣ La volta dopo", "Tocca il campo: Android propone utente e password salvati", onClick = {})
            }
            listRow(body, "🔑 Le mie password", "Gestore password di Google: vedi, modifica e cancella", onClick = {
                dialog.dismiss(); openInBrowser("https://passwords.google.com/")
            })
            listRow(body, "⚙ Cambia il servizio", "Google, Samsung, Bitwarden o altri", onClick = { openAutofillSettings() })
            sectionLabel(body, "SE NON PROPONE DI SALVARE")
            hintLabel(body, "• Nelle schede «senza cronologia» non si salva nulla.\n" +
                "• Alcuni siti chiedono prima l'email e poi la password in due pagine: a volte Android non li riconosce. Accedi una volta e poi aggiungi la password a mano da «Le mie password».\n" +
                "• Se in passato hai toccato «Mai per questo sito», toglilo da «Le mie password» → impostazioni.")
        }
        render()
        dialog.setOnShowListener { render() }
    }
}

/** On a page with a password box: if Android has no password manager, suggest turning it on (at most once a week). */
internal fun MainActivity.maybeSuggestPasswords(view: android.webkit.WebView?) {
    if (view == null || autofillOn()) return
    val p = getSharedPreferences("wl_prefs", android.content.Context.MODE_PRIVATE)
    if (System.currentTimeMillis() - p.getLong("pwHintAt", 0L) < 7 * 86_400_000L) return
    view.evaluateJavascript("!!document.querySelector('input[type=password]')") { r ->
        if (r != "true") return@evaluateJavascript
        p.edit().putLong("pwHintAt", System.currentTimeMillis()).apply()
        com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), "🔑 Per salvare le password attiva il Gestore password di Google", 9000)
            .setAction("Attiva") { showPasswordInfo() }.show()
    }
}
