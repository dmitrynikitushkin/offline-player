package app.offlineplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.offlineplayer.container
import app.offlineplayer.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Owns the single ExoPlayer. The MediaSession gives lock-screen and notification
 * controls, Bluetooth/headset buttons and audio focus for free. The playlist is
 * the play queue; it is mirrored to the database so it survives restarts.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private val positions = HashMap<String, Long>()
    private val prefs by lazy { getSharedPreferences("playback", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)     // keep CPU awake while playing with screen off
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(callback)
            .build()

        scope.launch { restore() }
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveCurrentPosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentPosition()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val ids = queueIds()
        val current = player.currentMediaItem?.mediaId?.toLongOrNull()
        val pos = normalized(player.currentPosition, player.duration)
        runBlocking(Dispatchers.IO) {
            val db = container.db
            current?.let { db.items().savePosition(it, pos) }
            db.queue().replace(ids)
        }
        prefs.edit().putInt(KEY_INDEX, player.currentMediaItemIndex).apply()
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    /** Reloads the last queue, current item and its position after the app was closed. */
    private suspend fun restore() {
        val db = container.db
        val (items, saved) = withContext(Dispatchers.IO) {
            db.queue().queueItems().filter { it.isPlayableOffline() } to db.items().positions()
        }
        saved.forEach { positions[it.id.toString()] = it.positionMs }
        if (items.isEmpty() || player.mediaItemCount > 0) return // a controller already set something
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex)
        player.setMediaItems(items.map { it.toMediaItem() }, index, positions[items[index].id.toString()] ?: 0L)
        player.prepare()
    }

    private val callback = object : MediaSession.Callback {
        // Rebuild items from the database so the URI and artwork are always the local files.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
            val byId = withContext(Dispatchers.IO) { container.db.items().getAll(ids) }.associateBy { it.id.toString() }
            mediaItems.map { byId[it.mediaId]?.toMediaItem() ?: it }.toMutableList()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val oldId = old.mediaItem?.mediaId ?: return
            if (oldId != new.mediaItem?.mediaId) {
                // Finished items restart from the beginning next time; skipped ones resume.
                val pos = if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) 0L else old.positionMs
                savePosition(oldId, pos)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            ) {
                val saved = positions[mediaItem.mediaId] ?: 0L
                if (saved > 0) player.seekTo(saved)
            }
            prefs.edit().putInt(KEY_INDEX, player.currentMediaItemIndex).apply()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { savePosition(it, 0L) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveCurrentPosition()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                val ids = queueIds()
                container.appScope.launch { container.db.queue().replace(ids) }
                prefs.edit().putInt(KEY_INDEX, player.currentMediaItemIndex).apply()
            }
        }
    }

    private fun queueIds(): List<Long> =
        (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }

    private fun saveCurrentPosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        savePosition(id, normalized(player.currentPosition, player.duration))
    }

    private fun savePosition(mediaId: String, pos: Long) {
        val id = mediaId.toLongOrNull() ?: return
        positions[mediaId] = pos
        container.appScope.launch { container.db.items().savePosition(id, pos) }
    }

    /** Treat "a few seconds from the end" as finished. */
    private fun normalized(pos: Long, duration: Long): Long =
        if (duration != C.TIME_UNSET && duration > 0 && pos > duration - 3_000) 0L else pos.coerceAtLeast(0L)

    companion object {
        const val SKIP_MS = 10_000L
        private const val KEY_INDEX = "queue_index"
    }
}
