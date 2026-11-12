package com.weblauncher.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Podcasts and radio in the background, with the media notification and headset controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val saver = object : Runnable {
        override fun run() {
            session?.player?.let { savePosition(it) }
            handler.postDelayed(this, 15_000)
        }
    }

    private var exo: ExoPlayer? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == "skipSilence") exo?.skipSilenceEnabled = p.getBoolean(key, false)
    }

    /** Ends the episode early when the podcast has an «outro» to skip. */
    private val outroCheck = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val outro = p.currentMediaItem?.mediaMetadata?.extras?.getInt("outroSec", 0) ?: 0
            if (outro > 0 && p.isPlaying && p.duration > 0 && p.duration - p.currentPosition <= outro * 1000L) {
                p.currentMediaItem?.mediaId?.let { AudioStore.markFinished(this@PlaybackService, it) }
                if (!playNextInQueue(p)) p.pause()
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Plays the next queued episode, if any. */
    private fun playNextInQueue(player: Player): Boolean {
        val e = AudioQueue.pop(this) ?: return false
        val repo = AudioRepository(this)
        val uri = if (repo.isDownloaded(e)) android.net.Uri.fromFile(repo.localFile(e)).toString() else e.audioUrl
        player.setMediaItem(episodeItem(this, e, uri), PodcastPrefs.startAt(this, e))
        player.setPlaybackSpeed(PodcastPrefs.speed(this, e.feedUrl))
        player.prepare(); player.play()
        AudioStore.addRecent(this, e)
        return true
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo = player
        val audioPrefs = getSharedPreferences("wl_audio", MODE_PRIVATE)
        player.skipSilenceEnabled = audioPrefs.getBoolean("skipSilence", false)
        audioPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saver); handler.removeCallbacks(outroCheck)
                if (isPlaying) { handler.postDelayed(saver, 15_000); handler.postDelayed(outroCheck, 1000) } else savePosition(player)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.let { AudioStore.markFinished(this@PlaybackService, it) }
                    playNextInQueue(player)
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    private fun savePosition(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.startsWith("radio:") || id.startsWith("music:") || player.isCurrentMediaItemLive) return
        val dur = player.duration.takeIf { it > 0 } ?: return
        AudioStore.savePosition(this, id, player.currentPosition, dur)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver); handler.removeCallbacks(outroCheck)
        runCatching { getSharedPreferences("wl_audio", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        session?.run {
            savePosition(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** A media item for a podcast episode, carrying the per-podcast «outro» to skip. */
fun episodeItem(c: android.content.Context, e: Episode, uri: String): androidx.media3.common.MediaItem {
    val md = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(e.title).setArtist(e.podcastTitle).setIsPlayable(true)
        .setExtras(android.os.Bundle().apply { putInt("outroSec", PodcastPrefs.get(c, e.feedUrl).outroSec); putString("feed", e.feedUrl) })
        .apply { e.artwork?.let { setArtworkUri(android.net.Uri.parse(it)) } }
        .build()
    return androidx.media3.common.MediaItem.Builder().setMediaId(e.id).setUri(uri).setMediaMetadata(md).build()
}
