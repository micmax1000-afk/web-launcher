package com.weblauncher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit

/** Pure logic of the updater (no Android classes): unit-tested. */
object UpdateLogic {
    data class Release(val tag: String, val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long)

    /** "v7.2.2-b1050" -> 1050 */
    fun codeFromTag(tag: String): Int? = Regex("""-b(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Parses the JSON of GitHub's /releases/latest. Null if it has no APK or no build number. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        val tag = o.get("tag_name")?.asString ?: return null
        val code = codeFromTag(tag) ?: return null
        val asset = o.getAsJsonArray("assets")?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true } ?: return null
        Release(
            tag = tag, code = code,
            name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: tag,
            notes = o.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            apkUrl = asset.get("browser_download_url").asString,
            size = asset.get("size")?.asLong ?: 0L
        )
    }.getOrNull()

    fun isNewer(release: Release?, installedCode: Long) = release != null && release.code > installedCode
}

object Updater {
    const val REPO = "micmax1000-afk/web-launcher"
    private const val CHANNEL = "aggiornamenti"
    private const val WORK = "wl_update_check"
    private const val PREFS = "wl_update"

    fun installedCode(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    fun autoCheck(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", true)
    fun setAutoCheck(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", on).apply(); schedule(c)
    }
    fun lastCheck(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0L)

    /** Network call: run off the main thread. */
    fun fetchLatest(c: Context): UpdateLogic.Release? {
        val conn = java.net.URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "WebLauncher-Updater")
        try {
            if (conn.responseCode == 404) return null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
            return UpdateLogic.parseRelease(body)
        } finally { conn.disconnect() }
    }

    /** Downloads the APK into the cache. [progress] receives 0..100 (or -1 if the size is unknown). */
    fun download(c: Context, r: UpdateLogic.Release, progress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "web-launcher-${r.code}.apk")
        var url = r.apkUrl
        var conn: java.net.HttpURLConnection
        var hops = 0
        while (true) { // GitHub redirects to its file server: follow redirects by hand (https -> https)
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "WebLauncher-Updater")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) { url = conn.getHeaderField("Location"); conn.disconnect(); continue }
            if (code != 200) { conn.disconnect(); error("Download non riuscito (HTTP $code)") }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -2
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val p = if (total > 0) (done * 100 / total).toInt() else -1
                    if (p != last) { last = p; progress(p) }
                }
            }
        }
        conn.disconnect()
        return out
    }

    /** True if the downloaded APK is signed with the same key as the installed app (update without uninstalling). */
    @Suppress("DEPRECATION")
    fun sameSignature(c: Context, apk: File): Boolean? = runCatching {
        val pm = c.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
            val b = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            if (a == null || b == null) null else a.map { it.toCharsString() }.toSet() == b.map { it.toCharsString() }.toSet()
        } else {
            val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures
            val b = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
            if (a == null || b == null) null else a.map { it.toCharsString() }.toSet() == b.map { it.toCharsString() }.toSet()
        }
    }.getOrNull()

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(c: Context) =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    fun install(c: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!autoCheck(c)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Notifies a new version once per build. */
    fun notifyIfNew(c: Context, r: UpdateLogic.Release) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt("notified", 0) >= r.code) return
        p.edit().putInt("notified", r.code).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_UPDATE, true)
        val pi = PendingIntent.getActivity(c, 4401, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_news)
            .setContentTitle("Nuova versione di Web Launcher")
            .setContentText("${r.name}: tocca per aggiornare")
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { nm.notify(4401, n) }
    }

    const val EXTRA_UPDATE = "wl_update"
}

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = applicationContext
        val r = runCatching { Updater.fetchLatest(c) }.getOrElse { return Result.retry() }
        if (UpdateLogic.isNewer(r, Updater.installedCode(c))) Updater.notifyIfNew(c, r!!)
        return Result.success()
    }
}
