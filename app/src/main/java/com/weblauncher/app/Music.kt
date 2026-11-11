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

/** Buttons in the workout notification (work with the screen off). */
class MusicKeyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) { Music.control(context, intent.getStringExtra("a") ?: return) }
}

data class Playlist(val id: String, val name: String, val url: String)
data class NowPlaying(val title: String, val artist: String, val app: String, val playing: Boolean, val art: Bitmap?, val controller: MediaController, val posMs: Long = 0, val durMs: Long = 0)

/** What was asked by voice about the music. */
sealed class MusicCmd {
    data class Control(val action: String) : MusicCmd()          // pause | play | next | prev
    data class Find(val query: String, val app: String?) : MusicCmd()   // app: "spotify" | "ytmusic" | "youtube" | null
}

/** What the YouTube Music page inside the app's browser is playing. */
data class WebPlaying(val title: String, val artist: String, val art: String, val playing: Boolean, val at: Long, val music: Boolean = true, val posMs: Long = 0, val durMs: Long = 0)

object Music {
    /** The YouTube Music player inside the app: its state and a way to command it (set by the main screen). */
    @Volatile var web: WebPlaying? = null
    @Volatile var webControl: ((String) -> Unit)? = null
    fun webActive() = web?.let { it.title.isNotBlank() && System.currentTimeMillis() - it.at < 8000 } == true

    const val STATE_JS = "(function(){try{var v=document.querySelector('video');var m=navigator.mediaSession&&navigator.mediaSession.metadata;var art='';" +
        "if(m&&m.artwork&&m.artwork.length)art=m.artwork[m.artwork.length-1].src||'';" +
        "var t=m?(m.title||''):'';if(!t)t=(document.title||'').replace(/^\\(\\d+\\)\\s*/,'').replace(/\\s*-\\s*YouTube( Music)?$/,'');" +
        "var id=(location.href.match(/[?&]v=([\\w-]{11})/)||[])[1];if(!art&&id)art='https://i.ytimg.com/vi/'+id+'/hqdefault.jpg';" +
        "return JSON.stringify({t:t,a:m?(m.artist||''):'',art:art,p:!!(v&&!v.paused&&!v.ended),has:!!v,mu:location.hostname.indexOf('music.')==0,pos:v?Math.floor(v.currentTime*1000):0,dur:(v&&isFinite(v.duration))?Math.floor(v.duration*1000):0});}catch(e){return '{}';}})()"
    fun controlJs(action: String) = "(function(a){try{var v=document.querySelector('video');function c(s){for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.click();return true;}}return false;}" +
        "if(a=='pause'){v&&v.pause();}else if(a=='play'){v&&v.play();}else if(a=='toggle'){v&&(v.paused?v.play():v.pause());}" +
        "else if(a=='next'){c(['ytmusic-player-bar .next-button','.next-button','a.ytp-next-button','button[aria-label*=\\'Succ\\']','button[aria-label*=\\'Next\\']'])||(v&&v.duration&&(v.currentTime=v.duration));}" +
        "else if(a=='prev'){c(['ytmusic-player-bar .previous-button','.previous-button','button[aria-label*=\\'Prec\\']','button[aria-label*=\\'Prev\\']'])||(v&&(v.currentTime=0));}}catch(e){}})('" + action.filter { it.isLetter() } + "')"

    /** The result of [STATE_JS] as WebView hands it over (a JSON string inside a JSON string). */
    fun parseWebState(raw: String?, now: Long = System.currentTimeMillis()): WebPlaying? = runCatching {
        val inner = com.google.gson.JsonParser.parseString(raw).asString
        val o = com.google.gson.JsonParser.parseString(inner).asJsonObject
        if (o.get("has")?.asBoolean != true) return null
        WebPlaying(o.get("t")?.asString.orEmpty(), o.get("a")?.asString.orEmpty(), o.get("art")?.asString.orEmpty(), o.get("p")?.asBoolean == true, now, o.get("mu")?.asBoolean != false, o.get("pos")?.asLong ?: 0L, o.get("dur")?.asLong ?: 0L)
    }.getOrNull()

    const val SPOTIFY = "com.spotify.music"
    const val YTMUSIC = "com.google.android.apps.youtube.music"

    // ---------- pure ----------

    /** "spotify" / "ytmusic" from a link, or null. */
    fun appFor(url: String): String? {
        val u = url.lowercase()
        return when {
            u.startsWith("spotify:") || u.contains("spotify.com") || u.contains("spoti.fi") -> "spotify"
            u.contains("music.youtube.com") -> "ytmusic"
            u.contains("youtube.com") || u.contains("youtu.be") -> "youtube"
            else -> null
        }
    }
    fun appName(app: String?) = when (app) { "spotify" -> "Spotify"; "ytmusic" -> "YouTube Music"; "youtube" -> "YouTube"; else -> "Musica" }
    fun packageOf(app: String?) = when (app) { "spotify" -> SPOTIFY; "ytmusic" -> YTMUSIC; else -> null }
    fun searchUri(app: String, q: String): String = when (app) {
        "spotify" -> "spotify:search:" + enc(q)
        "youtube" -> "https://m.youtube.com/results?search_query=" + enc(q)
        else -> "https://music.youtube.com/search?q=" + enc(q)
    }
    private fun enc(q: String) = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")

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
        APP_WORDS.find(rest)?.let { am -> app = if (am.groupValues[1].contains("spotify", true)) "spotify" else if (am.groupValues[1].contains("music", true)) "ytmusic" else "youtube"; rest = rest.removeRange(am.range).trim() }
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
    /** YouTube Music opens inside Web Launcher (its own browser) unless the user chose the separate app. */
    fun ytInApp(c: Context) = prefs(c).getBoolean("yt_inapp", true) || !installed(c, YTMUSIC)
    fun setYtInApp(c: Context, on: Boolean) = prefs(c).edit().putBoolean("yt_inapp", on).apply()

    /** Where searches go: the preferred one; Spotify only if its app is there; otherwise YouTube Music (always available inside the app). */
    fun defaultApp(c: Context): String {
        val p = preferred(c)
        if (p == "ytmusic" || p == "youtube" || p == "spotify" && installed(c, SPOTIFY)) return p!!
        return "ytmusic"
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
        return NowPlaying(title, artist, appLabel(c, ctl.packageName), ctl.playbackState?.state == PlaybackState.STATE_PLAYING, art, ctl,
            ctl.playbackState?.position ?: 0L, md.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    fun keyFor(action: String) = when (action) {
        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY; "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> 0
    }

    /** The same as pressing the media key of a headset: needs no permission and works with the screen off. */
    fun pressKey(c: Context, action: String): Boolean {
        val code = keyFor(action); if (code == 0) return false
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)); true
        }.getOrDefault(false)
    }

    /** Sends a command to whatever is playing: through the media session if the app may see it, otherwise with the media key. */
    fun control(c: Context, action: String): Boolean {
        val wc = webControl
        if (wc != null && webActive()) { wc(action); return true }
        val n = current(c)
        if (n != null) {
            val t = n.controller.transportControls
            when (action) {
                "pause" -> t.pause(); "play" -> t.play(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious()
                "toggle" -> if (n.playing) t.pause() else t.play()
                else -> return pressKey(c, action)
            }
            return true
        }
        return pressKey(c, action)
    }
}
