package com.weblauncher.app

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Complete backup: the app's private files (books, recordings, tickets, notes, expenses…)
 * and settings, in one ZIP. The vault is excluded on purpose: its key never leaves the phone.
 */
object FullBackup {
    private val SKIP_FILES = setOf("news_cache.json", "last_crash.txt", "last_error_seen.txt", "podcast_episodes.json")
    private val SKIP_DIRS = setOf("vault")

    private fun prefsDir(c: Context) = File(c.applicationInfo.dataDir, "shared_prefs")

    fun write(c: Context, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun add(base: File, f: File, prefix: String) {
                if (f.isDirectory) {
                    if (f.name in SKIP_DIRS && f.parentFile == base) return
                    f.listFiles()?.forEach { add(base, it, prefix) }
                    return
                }
                if (f.name in SKIP_FILES || f.name.endsWith(".tmp")) return
                zip.putNextEntry(ZipEntry(prefix + f.relativeTo(base).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(("Web Launcher · backup completo · " + java.util.Date()).toByteArray())
            zip.closeEntry()
            c.filesDir.listFiles()?.forEach { add(c.filesDir, it, "files/") }
            prefsDir(c).listFiles()?.filter { it.name.startsWith("wl_") && it.name != "wl_sync.xml" }?.forEach { add(prefsDir(c), it, "prefs/") }
        }
    }

    /** Restores files and settings; the app must restart afterwards. */
    fun restore(c: Context, input: InputStream): Int {
        var n = 0
        val files = c.filesDir.canonicalFile
        val prefs = prefsDir(c).canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            if (e == null) throw IllegalStateException("File vuoto")
            var valid = false
            while (e != null) {
                val name = e.name
                val (base, rel) = when {
                    name.startsWith("files/") -> files to name.removePrefix("files/")
                    name.startsWith("prefs/") -> prefs to name.removePrefix("prefs/")
                    name == "info.txt" -> { valid = true; null to "" }
                    else -> null to ""
                }
                if (base != null && rel.isNotEmpty() && !e.isDirectory) {
                    val dest = File(base, rel).canonicalFile
                    if (!dest.path.startsWith(base.path + File.separator)) throw SecurityException("Percorso non valido")
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    n++
                }
                e = zip.nextEntry
            }
            if (!valid) throw IllegalStateException("Non è un backup di Web Launcher")
        }
        return n
    }

    fun schedule(c: Context) {
        val p = c.getSharedPreferences("wl_sync", Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(c)
        if (p.getString("fullUri", null) == null) { wm.cancelUniqueWork("wl_full_backup"); return }
        wm.enqueueUniquePeriodicWork("wl_full_backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FullBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
}

class FullBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val p = applicationContext.getSharedPreferences("wl_sync", Context.MODE_PRIVATE)
        val uri = p.getString("fullUri", null) ?: return Result.success()
        return runCatching {
            val cr = applicationContext.contentResolver
            (cr.openOutputStream(Uri.parse(uri), "wt") ?: cr.openOutputStream(Uri.parse(uri)))!!.use { FullBackup.write(applicationContext, it) }
            p.edit().putLong("fullLast", System.currentTimeMillis()).putInt("fullFails", 0).apply()
            Result.success()
        }.getOrElse {
            p.edit().putInt("fullFails", p.getInt("fullFails", 0) + 1).apply()
            Result.retry()
        }
    }
}
