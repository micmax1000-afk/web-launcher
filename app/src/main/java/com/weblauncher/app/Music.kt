package com.weblauncher.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/** Needed only so that Android lets the app see the active media sessions (Spotify, YouTube Music…). It reads no notifications. */
class MusicListener : NotificationListenerService()

data class Playlist(val id: String, val name: String, val url: String)
data class NowPlaying(val title: String, val artist: String, val app: String, val playing: Boolean, val art: Bitmap?, val controller: MediaController)

/** What was asked by voice about the music. */
sealed class MusicCmd {
    data class Control(val action: String) : MusicCmd()          // pause | play | next | prev
    data class Find(val query: String, val app: String?) : MusicCmd()   // app: "spotify" | "ytmusic" | null
}

object Music {
    const val SPOTIFY = "com.spotify.music"
    const val YTMUSIC = "com.google.android.apps.youtube.music"

    // ---------- pure ----------

    /** "spotify" / "ytmusic" from a link, or null. */
    fun appFor(url: String): String? {
        val u = url.lowercase()
        return when {
            u.startsWith("spotify:") || u.contains("spotify.com") || u.contains("spoti.fi") -> "spotify"
            u.contains("music.youtube.com") || u.contains("youtube.com") || u.contains("youtu.be") -> "ytmusic"
            else -> null
        }
    }
    fun appName(app: String?) = when (app) { "spotify" -> "Spotify"; "ytmusic" -> "YouTube Music"; else -> "Musica" }
    fun packageOf(app: String?) = when (app) { "spotify" -> SPOTIFY; "ytmusic" -> YTMUSIC; else -> null }
    fun searchUri(app: String, q: String): String = if (app == "spotify") "spotify:search:" + Uri.encode(q) else "https://music.youtube.com/search?q=" + Uri.encode(q)

    private val APP_WORDS = Regex("\\s+(?:su|con|in|da|tramite)\\s+(spotify|youtube music|youtube|you tube|yt music)\\s*$", RegexOption.IGNORE_CASE)
    private val MUSIC_WORDS = Regex("(musica|canzon|brano|brani|playlist|album|spotify|youtube|radio|disco)", RegexOption.IGNORE_CASE)

    fun parseVoice(raw: String): MusicCmd? {
        val t = raw.trim().lowercase().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        if (Regex("^(metti\\s+(in\\s+)?pausa(\\s+(la\\s+)?musica)?|pausa(\\s+(la\\s+)?musica)?|ferma\\s+la\\s+musica|stop\\s+(alla\\s+)?musica|spegni\\s+la\\s+musica)$").matches(t)) return MusicCmd.Control("pause")
        if (Regex("^(riprendi|continua)(\\s+(la\\s+)?musica)?$|^(fai\\s+)?(ri)?parti(re)?\\s+la\\s+musica$").matches(t)) return MusicCmd.Control("play")
        if (Regex("^((metti\\s+)?(la\\s+)?(prossima|successiva)(\\s+(canzone|brano|traccia))?|salta(\\s+(questa\\s+)?(canzone|brano|traccia))?|avanti(\\s+(canzone|brano))?)$").matches(t)) return MusicCmd.Control("next")
        if (Regex("^((metti\\s+)?(la\\s+)?precedente(\\s+(canzone|brano|traccia))?|torna\\s+indietro(\\s+(canzone|brano))?|canzone\\s+precedente)$").matches(t)) return MusicCmd.Control("prev")
        val m = Regex("^(metti|ascolta|fai\\s+partire|fammi\\s+ascoltare|riproduci|suona|cerca)\\s+(.+)$").find(t) ?: return null
        val verb = m.groupValues[1]; var rest = m.groupValues[2].trim()
        var app: String? = null
        APP_WORDS.find(rest)?.let { am -> app = if (am.groupValues[1].contains("spotify", true)) "spotify" else "ytmusic"; rest = rest.removeRange(am.range).trim() }
        if (rest.isEmpty()) return null
        val musical = verb != "metti" && verb != "cerca" || MUSIC_WORDS.containsMatchIn(rest) || app != null
        if (!musical) return null
        if (verb == "cerca" && app == null && !MUSIC_WORDS.containsMatchIn(rest)) return null
        return MusicCmd.Find(rest, app)
    }

    // ---------- settings ----------

    private fun prefs(c: Context) = c.getSharedPreferences("wl_music", Context.MODE_PRIVATE)
    fun playlists(c: Context): List<Playlist> = runCatching {
        Gson().fromJson<List<Playlist>>(prefs(c).getString("lists", null), object : TypeToken<List<Playlist>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull()
    fun addPlaylist(c: Context, name: String, url: String) = prefs(c).edit().putString("lists", Gson().toJson(playlists(c) + Playlist(UUID.randomUUID().toString(), name, url))).apply()
    fun removePlaylist(c: Context, id: String) = prefs(c).edit().putString("lists", Gson().toJson(playlists(c).filter { it.id != id })).apply()
    fun preferred(c: Context): String? = prefs(c).getString("app", null)
    fun setPreferred(c: Context, app: String) = prefs(c).edit().putString("app", app).apply()

    fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    /** Preferred app if installed, else the one that is installed, else null. */
    fun defaultApp(c: Context): String? {
        val p = preferred(c)
        if (p != null && installed(c, packageOf(p)!!)) return p
        return listOf("spotify", "ytmusic").firstOrNull { installed(c, packageOf(it)!!) }
    }

    // ---------- the system's media sessions ----------

    fun hasAccess(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private fun appLabel(c: Context, pkg: String) = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun current(c: Context): NowPlaying? {
        if (!hasAccess(c)) return null
        val list = runCatching {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(ComponentName(c, MusicListener::class.java))
        }.getOrNull().orEmpty()
        val ctl = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null } ?: return null
        val md = ctl.metadata ?: return null
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return NowPlaying(title, artist, appLabel(c, ctl.packageName), ctl.playbackState?.state == PlaybackState.STATE_PLAYING, art, ctl)
    }

    /** Sends a command to whatever is playing. Returns false if nothing can be controlled. */
    fun control(c: Context, action: String): Boolean {
        val n = current(c) ?: return false
        val t = n.controller.transportControls
        when (action) {
            "pause" -> t.pause(); "play" -> t.play(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious()
            "toggle" -> if (n.playing) t.pause() else t.play()
            else -> return false
        }
        return true
    }
}
