package com.weblauncher.app

import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

// ======================= Coda (play next) =======================

internal fun MainActivity.showQueuePage() {
    openPage("Coda di ascolto", "Svuota", { dlg ->
        if (AudioQueue.list(this).isEmpty()) return@openPage
        confirmClear("Svuotare la coda?") { AudioQueue.clear(this); dlg.dismiss(); renderAudio() }
    }) { _, body, _ ->
        fun render() {
            body.removeAllViews()
            val q = AudioQueue.list(this)
            if (q.isEmpty()) {
                emptyLabel(body, "La coda è vuota.\nTieni premuto un episodio e scegli «Aggiungi alla coda»: gli episodi partono uno dopo l'altro, anche a schermo spento.")
                return
            }
            hintLabel(body, "Partono in quest'ordine quando finisce quello in ascolto. Tocca per ascoltare subito, tieni premuto per spostare.")
            q.forEachIndexed { i, e ->
                mediaRow(body, e.artwork, "${i + 1}. ${e.title}", e.podcastTitle + (if (e.durationSec > 0) " · " + fmtDur(e.durationSec) else ""),
                    onLong = {
                        MaterialAlertDialogBuilder(this).setTitle(e.title).setItems(arrayOf("⬆ Sposta su", "⬇ Sposta giù", "✖ Togli dalla coda")) { _, w ->
                            when (w) { 0 -> AudioQueue.move(this, e.id, true); 1 -> AudioQueue.move(this, e.id, false); else -> AudioQueue.remove(this, e.id) }
                            render()
                        }.show()
                    }) { playEpisode(e, showPlayer = true); render() }
            }
        }
        render()
    }
}

// ======================= Per-podcast settings =======================

internal fun MainActivity.podcastSettingsDialog(p: Podcast) {
    val o = PodcastPrefs.get(this, p.feedUrl)
    val speeds = listOf(0f, 0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
    var speed = o.speed
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
    val speedBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    fun label() { speedBtn.text = "Velocità: " + if (speed == 0f) "come gli altri" else String.format(Locale.ITALIAN, "%.2g×", speed) }
    label()
    speedBtn.setOnClickListener {
        MaterialAlertDialogBuilder(this).setTitle("Velocità per ${p.title}")
            .setItems(speeds.map { if (it == 0f) "Come gli altri podcast" else String.format(Locale.ITALIAN, "%.2g×", it) }.toTypedArray()) { _, w -> speed = speeds[w]; label() }.show()
    }
    box.addView(speedBtn)
    val intro = EditText(this).apply { hint = "Secondi da saltare all'inizio (sigla)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; if (o.introSec > 0) setText(o.introSec.toString()) }
    val outro = EditText(this).apply { hint = "Secondi da saltare alla fine"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; if (o.outroSec > 0) setText(o.outroSec.toString()) }
    box.addView(intro); box.addView(outro)
    MaterialAlertDialogBuilder(this).setTitle("⚙️ " + p.title).setMessage("Valgono solo per questo podcast.").setView(box)
        .setPositiveButton("Salva") { _, _ ->
            PodcastPrefs.set(this, p.feedUrl, PodcastPrefs.Opts(speed, intro.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 0, outro.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 0))
            toast("Salvato")
        }.setNegativeButton("Annulla", null).show()
}

// ======================= Podcast tools: RSS, OPML, automatic downloads, skip silence =======================

internal fun MainActivity.showPodcastTools() {
    openPage("Impostazioni podcast") { _, body, _ ->
        val prefs = getSharedPreferences("wl_audio", android.content.Context.MODE_PRIVATE)
        fun render() {
            body.removeAllViews()
            sectionLabel(body, "AGGIUNGI PODCAST")
            listRow(body, "🔗 Aggiungi con un link RSS", "Per i podcast che non si trovano con la ricerca", onClick = { addPodcastByRss() })
            listRow(body, "📥 Importa da un'altra app (OPML)", "Il file che altre app di podcast esportano", onClick = { runCatching { opmlPicker.launch(arrayOf("*/*")) }.onFailure { toast("Non riesco ad aprire i file") } })
            listRow(body, "📤 Esporta i miei podcast (OPML)", "${audio.subscriptions().size} podcast seguiti", onClick = { exportOpml() })

            sectionLabel(body, "DOWNLOAD AUTOMATICO")
            val auto = PodcastAuto.enabled(this)
            listRow(body, (if (auto) "✅ " else "☐ ") + "Scarica i nuovi episodi su Wi-Fi", if (auto) "Ogni qualche ora, solo con Wi-Fi e batteria non scarica" else "Così li ascolti anche senza rete, senza consumare dati", onClick = {
                PodcastAuto.setEnabled(this, !auto); render()
            })
            val clean = PodcastAuto.cleanup(this)
            listRow(body, (if (clean) "✅ " else "☐ ") + "Cancella gli episodi già ascoltati", "Libera spazio da solo", onClick = { PodcastAuto.setCleanup(this, !clean); render() })
            listRow(body, "🧹 Cancella ora gli ascoltati", null, onClick = {
                io.execute { val n = PodcastAuto.deleteHeard(this); runOnUiThread { toast(if (n == 0) "Niente da cancellare" else "Cancellati $n episodi"); renderAudio() } }
            })

            sectionLabel(body, "ASCOLTO")
            val skip = prefs.getBoolean("skipSilence", false)
            listRow(body, (if (skip) "✅ " else "☐ ") + "Salta i silenzi", "Accorcia le pause nel parlato (podcast e file)", onClick = { prefs.edit().putBoolean("skipSilence", !skip).apply(); render() })
            listRow(body, "📋 Coda di ascolto", "${AudioQueue.list(this).size} episodi in coda", onClick = { showQueuePage() })
            hintLabel(body, "Velocità e salti di sigla si impostano per ogni podcast: apri il podcast e tocca «⚙️ Velocità e salti».")
        }
        render()
    }
}

internal fun MainActivity.addPodcastByRss() {
    val et = EditText(this).apply { hint = "https://…/feed.xml"; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI; setPadding(48, 24, 48, 24) }
    (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()
        ?.takeIf { it.startsWith("http") }?.let { et.setText(it) }
    MaterialAlertDialogBuilder(this).setTitle("🔗 Link RSS del podcast").setView(et)
        .setPositiveButton("Aggiungi") { _, _ ->
            val url = et.text.toString().trim()
            if (!url.startsWith("http")) { toast("Il link deve iniziare con http"); return@setPositiveButton }
            toast("Controllo il link…")
            io.execute {
                val p = runCatching { audio.podcastInfo(url, null) }.getOrNull()
                val ok = p != null && runCatching { audio.episodes(url).isNotEmpty() }.getOrDefault(false)
                runOnUiThread {
                    if (!ok || p == null) { toast("Non trovo episodi a questo link"); return@runOnUiThread }
                    audio.subscribe(p); toast("Ora segui ${p.title}"); refreshEpisodes(); renderAudio()
                }
            }
        }.setNegativeButton("Annulla", null).show()
}

internal fun MainActivity.importOpml(uri: Uri) {
    io.execute {
        val xml = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        val found = Opml.parse(xml).filter { !audio.isSubscribed(it.second) }
        found.forEach { (title, url) -> audio.subscribe(Podcast(url, title.ifBlank { "Podcast" }, "", null)) }
        runOnUiThread {
            toast(if (found.isEmpty()) "Nessun podcast nuovo nel file" else "Aggiunti ${found.size} podcast")
            if (found.isNotEmpty()) refreshEpisodes()
        }
    }
}

internal fun MainActivity.exportOpml() {
    val subs = audio.subscriptions()
    if (subs.isEmpty()) { toast("Non segui ancora nessun podcast"); return }
    shareTextFile("podcast.opml", Opml.build(subs), "text/x-opml", "Esporta i podcast")
}

/** Writes [text] into a shareable file and opens the share sheet. */
internal fun MainActivity.shareTextFile(name: String, text: String, mime: String, title: String) {
    runCatching {
        val dir = java.io.File(cacheDir, "report").apply { mkdirs() }
        val f = java.io.File(dir, name); f.writeText(text)
        val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, title))
    }.onFailure { toast("Non riesco a creare il file") }
}

// ======================= Radio alarm =======================

internal fun MainActivity.showRadioAlarmPage() {
    openPage("Sveglia con la radio") { _, body, _ ->
        fun render() {
            body.removeAllViews()
            val on = RadioAlarm.enabled(this)
            val st = RadioAlarm.station(this)
            val time = String.format(Locale.ITALIAN, "%02d:%02d", RadioAlarm.hour(this), RadioAlarm.minute(this))
            listRow(body, (if (on) "✅ Sveglia attiva" else "☐ Sveglia spenta"), if (on) "Suona alle $time" + if (RadioAlarm.weekdaysOnly(this)) " (lun-ven)" else " (tutti i giorni)" else "Tocca per accenderla", onClick = {
                if (!on && st == null) { toast("Prima scegli la radio"); return@listRow }
                if (!on && !RadioAlarm.canExact(this)) toast("Per l'orario preciso consenti «Sveglie e promemoria» in Controllo dell'app")
                RadioAlarm.set(this, !on, RadioAlarm.hour(this), RadioAlarm.minute(this), RadioAlarm.weekdaysOnly(this), st); render()
            })
            listRow(body, "🕖 Orario", time, onClick = {
                android.app.TimePickerDialog(this, { _, h, m -> RadioAlarm.set(this, RadioAlarm.enabled(this) || st != null, h, m, RadioAlarm.weekdaysOnly(this), st); render() },
                    RadioAlarm.hour(this), RadioAlarm.minute(this), true).show()
            })
            listRow(body, "📅 Giorni", if (RadioAlarm.weekdaysOnly(this)) "Solo dal lunedì al venerdì" else "Tutti i giorni", onClick = {
                RadioAlarm.set(this, on, RadioAlarm.hour(this), RadioAlarm.minute(this), !RadioAlarm.weekdaysOnly(this), st); render()
            })
            listRow(body, "📻 Radio", st?.name ?: "Scegli tra le preferite", onClick = {
                val favs = audio.favoriteStations()
                if (favs.isEmpty()) { toast("Prima aggiungi una radio ai preferiti (⭐ nella scheda Radio)"); return@listRow }
                MaterialAlertDialogBuilder(this).setTitle("Con quale radio ti svegli?").setItems(favs.map { it.name }.toTypedArray()) { _, w ->
                    RadioAlarm.set(this, on, RadioAlarm.hour(this), RadioAlarm.minute(this), RadioAlarm.weekdaysOnly(this), favs[w]); render()
                }.show()
            })
            hintLabel(body, "Alla sveglia parte la radio con il volume multimediale (almeno al 40%). Se la radio non si sente (niente internet), suona la sveglia normale del telefono. " +
                "Per spegnerla usa la notifica del lettore. Provala una volta prima di fidarti!")
        }
        render()
    }
}

// ======================= Morning news, read aloud =======================

internal fun MainActivity.listenNews() {
    fun go() {
        if (ai.supported()) { newsAudioBulletin(); return }
        // without the AI: read the main headlines
        val text = "Ecco i titoli principali. " + newsItems.take(8).joinToString(". ") { it.title.trimEnd('.') } + "."
        val rate = getSharedPreferences("wl_prefs", android.content.Context.MODE_PRIVATE).getFloat("reader_rate", 1f)
        MaterialAlertDialogBuilder(this).setTitle("📰 Titoli di oggi").setMessage(text)
            .setPositiveButton("⏹ Stop") { _, _ -> stopSpeaking() }.setOnCancelListener { stopSpeaking() }.show()
        speak(text, rate)
    }
    if (newsItems.isNotEmpty()) { go(); return }
    toast("Carico le notizie…")
    io.execute {
        val items = runCatching { news.refresh().first }.getOrDefault(emptyList())
        runOnUiThread {
            if (items.isEmpty()) { toast("Non riesco a caricare le notizie"); return@runOnUiThread }
            newsItems = items; go()
        }
    }
}

// ======================= Voice: podcasts and radio =======================

/** Handles «metti l'ultimo episodio di …», «metti Radio 3», «notiziario»; false if the text is something else. */
internal fun MainActivity.audioVoice(text: String): Boolean {
    val cmd = AudioVoice.parse(text) ?: return false
    when (cmd) {
        is AudioCmd.News -> listenNews()
        is AudioCmd.LatestEpisode -> {
            val p = AudioVoice.best(audio.subscriptions(), cmd.podcast) { it.title }
            toast("Cerco l'ultimo episodio di ${p?.title ?: cmd.podcast}…")
            io.execute {
                val pod = p ?: runCatching { audio.search(cmd.podcast).firstOrNull() }.getOrNull()
                val ep = pod?.let { runCatching { audio.episodes(it.feedUrl).maxByOrNull { e -> e.time } }.getOrNull() }
                runOnUiThread {
                    if (ep == null) toast("Non trovo il podcast «${cmd.podcast}»")
                    else playEpisode(ep.copy(artwork = ep.artwork ?: pod.artwork), showPlayer = true)
                }
            }
        }
        is AudioCmd.Radio -> {
            val fav = AudioVoice.best(audio.favoriteStations(), cmd.station) { it.name }
            if (fav != null) { playRadio(fav); toast("▶ ${fav.name}"); return true }
            toast("Cerco ${cmd.station}…")
            io.execute {
                val s = runCatching { AudioVoice.best(audio.searchStations(cmd.station), cmd.station) { it.name } ?: audio.searchStations(cmd.station).firstOrNull() }.getOrNull()
                runOnUiThread { if (s == null) toast("Non trovo la radio «${cmd.station}»") else { playRadio(s); toast("▶ ${s.name}") } }
            }
        }
    }
    return true
}
