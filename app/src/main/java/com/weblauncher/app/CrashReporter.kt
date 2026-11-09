package com.weblauncher.app

import android.content.Context
import java.io.File

/** Saves the last crash (and non-fatal errors) so the user can send the report. */
object CrashReporter {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(app, "CRASH", e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** Errors caught without closing the app: kept so they can be reported too. */
    fun log(c: Context, where: String, e: Throwable) { runCatching { write(c, "ERRORE ($where)", e) } }

    private fun write(c: Context, kind: String, e: Throwable) {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        val text = buildString {
            append("$kind · Web Launcher $version · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n")
            append(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ITALIAN).format(java.util.Date())).append("\n\n")
            append(e.stackTraceToString().take(6000))
        }
        file(c).writeText(text)
    }

    fun pending(c: Context): String? = file(c).takeIf { it.exists() }?.readText()

    /** Keeps a copy for «Segnala un problema» even after the popup was dismissed. */
    fun clear(c: Context) { runCatching { file(c).copyTo(File(c.filesDir, "last_error_seen.txt"), overwrite = true) }; file(c).delete() }

    fun last(c: Context): String? = pending(c) ?: File(c.filesDir, "last_error_seen.txt").takeIf { it.exists() }?.readText()
}
