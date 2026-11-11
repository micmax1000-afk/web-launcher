package com.weblauncher.app

import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ======================= Volantini (DoveConviene) =======================

internal fun MainActivity.showFlyersPage() {
    openPage("Volantini") { _, body, dialog ->
        fun open(url: String) { dialog.dismiss(); openInBrowser(url) }
        fun askCity(then: () -> Unit) {
            val et = field("Comune (es. Bergamo)", Flyers.city(this).takeIf { it.isNotBlank() })
            MaterialAlertDialogBuilder(this).setTitle("Il tuo comune").setView(form(et)).setPositiveButton("Salva") { _, _ ->
                val v = et.text.toString().trim(); if (v.isBlank()) toast("Scrivi il comune") else { Flyers.setCity(this, v); then() }
            }.setNegativeButton("Annulla", null).show()
        }
        fun render() {
            body.removeAllViews()
            val city = Flyers.city(this)
            sectionLabel(body, "IL TUO COMUNE")
            listRow(body, "📍 " + city.ifBlank { "Scegli il comune" }, if (city.isBlank()) "Per vedere i volantini dei negozi vicini" else "Tocca per cambiarlo", onClick = { askCity { render() } })
            if (city.isBlank()) { hintLabel(body, "I volantini vengono da DoveConviene e si aprono qui nel Browser."); return }
            listRow(body, "🗞 Tutti i volantini a $city", "I negozi vicini, dal sito DoveConviene", onClick = { open(Flyers.cityUrl(city)) })
            sectionLabel(body, "PER CATEGORIA")
            Flyers.CATEGORIES.forEach { (k, l) -> listRow(body, l, null, onClick = { open(Flyers.categoryUrl(city, k)) }) }

            sectionLabel(body, "I MIEI NEGOZI")
            val favs = Flyers.favorites(this)
            if (favs.isEmpty()) hintLabel(body, "Tocca «Scegli i negozi» e metti la ⭐ a quelli dove fai la spesa.")
            Flyers.CHAINS.filter { it.second in favs }.forEach { (name, slugName) ->
                listRow(body, "⭐ $name", "Volantino a $city", onClick = { open(Flyers.chainUrl(city, slugName)) }, onRemove = { Flyers.toggleFavorite(this, slugName); render() })
            }
            listRow(body, "➕ Scegli i negozi", "Supermercati, discount, elettronica, casa", onClick = {
                val names = Flyers.CHAINS.map { it.first }.toTypedArray()
                val checked = BooleanArray(names.size) { Flyers.CHAINS[it].second in favs }
                MaterialAlertDialogBuilder(this).setTitle("I miei negozi").setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("Salva") { _, _ ->
                        Flyers.CHAINS.forEachIndexed { i, (_, s) -> if ((s in favs) != checked[i]) Flyers.toggleFavorite(this, s) }; render()
                    }.setNegativeButton("Annulla", null).show()
            })

            sectionLabel(body, "CERCA UN PRODOTTO")
            listRow(body, "🔎 Cerca nelle offerte", "Es. latte, caffè, lavatrice", onClick = {
                val et = field("Cosa cerchi?", null)
                MaterialAlertDialogBuilder(this).setTitle("Cerca nelle offerte").setView(form(et)).setPositiveButton("Cerca") { _, _ ->
                    et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { open(Flyers.productUrl(it)) }
                }.setNegativeButton("Annulla", null).show()
            })
            val list = notes.load().firstOrNull { isChecklist(it.text) && it.text.lines().first().trim().equals("Lista della spesa", true) }
            val items = list?.let { Flyers.shoppingItems(it.text) }.orEmpty()
            if (items.isNotEmpty()) {
                sectionLabel(body, "OFFERTE PER LA TUA LISTA DELLA SPESA")
                items.take(15).forEach { item -> listRow(body, "🛒 $item", "Tocca per vedere le offerte", onClick = { open(Flyers.productUrl(item)) }) }
            }
            hintLabel(body, "Le offerte per prodotto valgono in tutta Italia: controlla che il negozio sia vicino a te. Prezzi e date sono quelli pubblicati sul sito.")
        }
        render()
        if (Flyers.city(this).isBlank()) askCity { render() }
    }
}

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
