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

// ======================= Aggiornamenti dall'app (GitHub Releases) =======================
internal fun MainActivity.checkForUpdate(manual: Boolean) {
    if (manual) toast("Controllo aggiornamenti…")
    Thread {
        val res = runCatching { Updater.fetchLatest(this) }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val r = res.getOrNull()
            when {
                res.isFailure -> if (manual) toast("Controllo non riuscito: verifica la connessione")
                r == null -> if (manual) MaterialAlertDialogBuilder(this).setTitle("Aggiornamenti")
                    .setMessage("Non ci sono ancora versioni pubblicate.").setPositiveButton("OK", null).show()
                !UpdateLogic.isNewer(r, Updater.installedCode(this)) -> if (manual) MaterialAlertDialogBuilder(this).setTitle("Sei aggiornato")
                    .setMessage("Hai già l'ultima versione (${Updater.installedName(this)}, build ${Updater.installedCode(this)}).")
                    .setPositiveButton("OK", null).show()
                else -> MaterialAlertDialogBuilder(this).setTitle("Nuova versione: ${r.name}")
                    .setMessage((r.notes.take(1200).ifBlank { "Miglioramenti e correzioni." }) +
                        (if (r.size > 0) "\n\nDimensione: %.1f MB".format(r.size / 1048576.0) else ""))
                    .setPositiveButton("Aggiorna") { _, _ -> downloadUpdate(r) }
                    .setNegativeButton("Più tardi", null).show()
            }
        }
    }.start()
}

internal fun MainActivity.downloadUpdate(r: UpdateLogic.Release) {
    if (!Updater.canInstall(this)) {
        MaterialAlertDialogBuilder(this).setTitle("Permesso necessario")
            .setMessage("Per installare l'aggiornamento Android chiede di consentire a Web Launcher di installare app. Attivalo nella pagina che si apre, poi torna qui e tocca di nuovo «Aggiorna».")
            .setPositiveButton("Apri impostazioni") { _, _ -> runCatching { startActivity(Updater.installPermissionIntent(this)) } }
            .setNegativeButton("Annulla", null).show()
        return
    }
    val d = resources.displayMetrics.density
    val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { isIndeterminate = true; max = 100 }
    val label = TextView(this).apply { text = "Download in corso…"; setPadding(0, 0, 0, (8 * d).toInt()) }
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
        addView(label); addView(bar)
    }
    val dlg = MaterialAlertDialogBuilder(this).setTitle("Aggiornamento ${r.name}").setView(box).setCancelable(false).show()
    Thread {
        val res = runCatching {
            Updater.download(this, r) { p -> runOnUiThread { if (p >= 0) { bar.isIndeterminate = false; bar.setProgressCompat(p, true); label.text = "Download $p%" } } }
        }
        runOnUiThread {
            runCatching { dlg.dismiss() }
            val apk = res.getOrNull()
            if (apk == null) { toast(res.exceptionOrNull()?.message ?: "Download non riuscito"); return@runOnUiThread }
            if (Updater.sameSignature(this, apk) == false) {
                MaterialAlertDialogBuilder(this).setTitle("Firma diversa")
                    .setMessage("Questa versione è firmata con una chiave diversa da quella installata: Android chiederà di disinstallare l'app, e i dati andrebbero persi.\n\nPrima fai un «Backup completo» (Impostazioni → Backup), poi disinstalla e installa il file scaricato.")
                    .setPositiveButton("Backup completo") { _, _ -> showFullBackupMenu() }
                    .setNegativeButton("Annulla", null).show()
            } else runCatching { Updater.install(this, apk) }.onFailure { toast("Impossibile avviare l'installazione") }
        }
    }.start()
}

internal fun MainActivity.toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
