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

internal fun MainActivity.showBackupMenu() {
    val autoOn = repo.getAutoBackupUri() != null
    val options = arrayOf(
        "Salva su file o Drive",
        "Condividi",
        if (runCatching { packageManager.getPackageInfo("com.google.android.apps.docs", 0) }.isSuccess) "☁️ Salva su Google Drive" else "☁️ Drive dal sito (non hai l'app Drive)",
        if (autoOn) "Backup automatico: attivo" else "Attiva backup automatico"
    )
    MaterialAlertDialogBuilder(this)
        .setTitle("Backup")
        .setItems(options) { _, which ->
            when (which) {
                0 -> exportLauncher.launch("web-launcher-backup.json")
                1 -> shareBackup()
                2 -> backupViaDriveWeb()
                3 -> if (autoOn) showAutoBackupManager() else startAutoBackupSetup()
            }
        }
        .setNegativeButton("Chiudi", null)
        .show()
}

/** Without the Drive app: save the backup in Downloads, then open Drive web to upload it. */
internal fun MainActivity.backupViaDriveWeb() {
    val hasDrive = runCatching { packageManager.getPackageInfo("com.google.android.apps.docs", 0) }.isSuccess
    if (hasDrive) {
        toast("Nella finestra tocca ☰ → Drive e salva")
        exportLauncher.launch("web-launcher-backup.json")
        return
    }
    val json = backupJson()
    io.execute {
        val ok = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "web-launcher-backup.json")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            }
            val col = if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Files.getContentUri("external")
            val u = contentResolver.insert(col, values)!!
            contentResolver.openOutputStream(u, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        runOnUiThread {
            if (!ok) { toast("Backup non salvato"); return@runOnUiThread }
            MaterialAlertDialogBuilder(this).setTitle("☁️ Carica su Drive")
                .setMessage("Ho salvato «web-launcher-backup.json» nei Download.\n\nOra su Drive: tocca «+ Nuovo» → «Carica file» → Download → scegli il file.\n\n" +
                    "Per ripristinare: scaricalo da Drive e usa Impostazioni → Ripristina.")
                .setPositiveButton("Apri Drive") { _, _ -> showTab(TAB_BROWSER); openInBrowser("https://drive.google.com/drive/my-drive") }
                .setNegativeButton("Chiudi", null).show()
        }
    }
}

internal fun MainActivity.showAutoBackupManager() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Backup automatico")
        .setMessage("Ogni modifica viene scritta sul file che hai scelto, anche su Drive.")
        .setPositiveButton("Cambia file") { _, _ -> startAutoBackupSetup() }
        .setNeutralButton("Disattiva") { _, _ ->
            repo.setAutoBackupUri(null)
            toast("Backup automatico disattivato")
        }
        .setNegativeButton("Chiudi", null)
        .show()
}

internal fun MainActivity.backupJson(): String = fullBackupJson()

internal fun MainActivity.shareBackup() {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, backupJson())
        putExtra(Intent.EXTRA_SUBJECT, "web-launcher-backup.json")
    }, "Condividi backup"))
}

internal fun MainActivity.confirmClear(message: String, action: () -> Unit) {
    MaterialAlertDialogBuilder(this)
        .setMessage(message)
        .setPositiveButton("Cancella") { _, _ -> action() }
        .setNegativeButton("Annulla", null)
        .show()
}

internal fun MainActivity.clearHistoryAndRecents() {
    webView.clearHistory()
    repo.clearRecents()
    repo.clearHistory()
    if (!browserHasPage) renderRecents()
}

internal fun MainActivity.clearAllBrowsingData() {
    clearHistoryAndRecents()
    webView.clearCache(true)
    webView.clearFormData()
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    try {
        WebViewDatabase.getInstance(this).clearFormData()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
    } catch (_: Exception) { }
}

internal fun MainActivity.persistApps() {
    repo.saveApps(apps)
    autoBackupNow()
}

internal fun MainActivity.autoBackupNow() {
    val raw = repo.getAutoBackupUri() ?: return
    val json = backupJson()
    val body = json.replace(Regex("\"savedAt\":\\d+,?"), "")
    if (body.hashCode() == syncPrefs.getInt("hash", 0)) return
    val savedAt = Regex("\"savedAt\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
    syncPrefs.edit().putInt("hash", body.hashCode()).putLong("written", savedAt).apply()
    io.execute {
        try {
            val uri = Uri.parse(raw)
            (contentResolver.openOutputStream(uri, "wt") ?: contentResolver.openOutputStream(uri))
                ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("file non raggiungibile")
            syncPrefs.edit().putInt("fails", 0).apply()
        } catch (_: Exception) {
            val fails = syncPrefs.getInt("fails", 0) + 1
            syncPrefs.edit().putInt("fails", fails).putInt("hash", 0).apply()
            if (fails == 1 || fails % 5 == 0) runOnUiThread {
                MaterialAlertDialogBuilder(this).setTitle("⚠️ Backup automatico non riuscito")
                    .setMessage("Il file di backup non è raggiungibile (forse l'app Google Drive è stata disinstallata o il file è stato spostato).\n\n" +
                        "Il backup automatico resta attivo e riproverà. Puoi anche salvarlo nei Download e caricarlo su Drive dal sito.")
                    .setPositiveButton("Backup via Drive web") { _, _ -> backupViaDriveWeb() }
                    .setNeutralButton("Scegli altro file") { _, _ -> startAutoBackupSetup() }
                    .setNegativeButton("Ok", null).show()
            }
        }
    }
}

internal fun MainActivity.recordVisit(title: String, url: String) {
    if (isPdfViewer(url)) return
    noteHostVisit(url)
    val key = "$url|$title"
    if (key == lastRecorded) return
    lastRecorded = key
    io.execute {
        repo.addRecent(title, url)
        repo.addHistory(title, url)
    }
}

internal fun MainActivity.startAutoBackupSetup() {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, "web-launcher-backup.json")
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }
    autoBackupLauncher.launch(intent)
}

internal fun MainActivity.writeBackupToUri(uri: Uri, quiet: Boolean = false) {
    try {
        val json = backupJson()
        (contentResolver.openOutputStream(uri, "wt") ?: contentResolver.openOutputStream(uri))?.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
        } ?: run {
            if (!quiet) toast("Impossibile scrivere il file")
            else repo.setAutoBackupUri(null)
            return
        }
        if (!quiet) toast("Backup salvato (puoi scegliere Drive)")
    } catch (e: Exception) {
        if (quiet) repo.setAutoBackupUri(null)
        else toast("Errore backup: ${e.message}")
    }
}

internal fun MainActivity.readBackupFromUri(uri: Uri) {
    try {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: run { toast("Impossibile leggere il file"); return }
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
        val map = com.google.gson.Gson().fromJson<Map<String, Any>>(text, type)
            ?: run { toast("File non valido"); return }
        val appsJson = com.google.gson.Gson().toJson(map["apps"])
        val catsJson = com.google.gson.Gson().toJson(map["categories"])
        val appType = object : com.google.gson.reflect.TypeToken<MutableList<AppItem>>() {}.type
        val catType = object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        val loadedApps = com.google.gson.Gson().fromJson<MutableList<AppItem>?>(appsJson, appType)
            ?.filter { it != null && !it.name.isNullOrBlank() && !it.url.isNullOrBlank() }
            ?.map {
                it.copy(
                    id = it.id?.takeIf { id -> id.isNotBlank() } ?: UUID.randomUUID().toString(),
                    url = normalizeUrl(it.url),
                    category = it.category?.takeIf { c -> c.isNotBlank() } ?: "Utility",
                    iconPath = it.iconPath?.takeIf { p -> java.io.File(p).exists() }
                )
            }?.toMutableList()
        val loadedCats = com.google.gson.Gson().fromJson<MutableList<String>>(catsJson, catType)
        if (loadedApps.isNullOrEmpty()) { toast("Nessuna app nel backup"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ripristina backup")
            .setMessage("Sostituire le ${apps.size} app attuali con le ${loadedApps.size} del backup?")
            .setPositiveButton("Ripristina") { _, _ ->
                apps = loadedApps
                categories = (loadedCats?.filterNotNull()?.filter { it.isNotBlank() }?.toMutableList()
                    ?: repo.loadCategories())
                loadedApps.map { it.category }.distinct().filter { it !in categories }.forEach { categories.add(it) }
                persistApps()
                repo.saveCategories(categories)
                (map["searchEngine"] as? String)?.let { repo.setSearchEngine(SearchEngine.fromName(it)) }
                runCatching { applyBackupExtras(org.json.JSONObject(text)) }
                refreshList()
                toast("Backup ripristinato")
            }
            .setNegativeButton("Annulla", null)
            .show()
    } catch (e: Exception) {
        toast("Errore ripristino: ${e.message}")
    }
}
