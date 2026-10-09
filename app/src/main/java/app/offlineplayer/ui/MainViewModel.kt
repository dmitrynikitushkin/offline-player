package app.offlineplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.offlineplayer.container
import app.offlineplayer.data.Item
import app.offlineplayer.downloader.DownloadException
import app.offlineplayer.downloader.LinkParser
import app.offlineplayer.downloader.MediaMode
import app.offlineplayer.downloader.ProbeResult
import app.offlineplayer.playback.PlaybackService
import app.offlineplayer.playback.isPlayableOffline
import app.offlineplayer.playback.toMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

enum class Tab { LIBRARY, DOWNLOADS, PLAYER }

data class PasteState(
    val visible: Boolean = false,
    val text: String = "",
    val probing: Boolean = false,
    val probe: ProbeResult? = null,
    val error: String? = null,
    val mode: MediaMode = MediaMode.VIDEO,
    val height: Int? = null,
)

data class QueueRow(val mediaId: String, val title: String)

data class PlayerUi(
    val connected: Boolean = false,
    val queue: List<QueueRow> = emptyList(),
    val index: Int = -1,
    val title: String? = null,
    val artwork: String? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val speed: Float = 1f,
    val isVideo: Boolean = false,
) {
    val hasMedia get() = queue.isNotEmpty() && index in queue.indices
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.repository

    val library = repo.library.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloads = repo.downloads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val totalBytes = repo.totalBytes.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    var tab by mutableStateOf(Tab.LIBRARY)
    var paste by mutableStateOf(PasteState())
        private set
    var listenMode by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    var controller: MediaController? by mutableStateOf(null)
        private set
    private val _player = MutableStateFlow(PlayerUi())
    val player = _player.asStateFlow()
    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val ctrl = MediaController.Builder(app, token).buildAsync().await()
            ctrl.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refreshPlayer()
            })
            controller = ctrl
            refreshPlayer()
            while (true) {
                delay(500)
                if (ctrl.isPlaying) refreshPlayer()
            }
        }
    }

    override fun onCleared() {
        controller?.release()
    }

    private fun refreshPlayer() {
        val p = controller ?: return
        val rows = (0 until p.mediaItemCount).map { i ->
            p.getMediaItemAt(i).let { QueueRow(it.mediaId, it.mediaMetadata.title?.toString() ?: "Untitled") }
        }
        val meta = p.mediaMetadata
        _player.value = PlayerUi(
            connected = true,
            queue = rows,
            index = if (rows.isEmpty()) -1 else p.currentMediaItemIndex,
            title = meta.title?.toString(),
            artwork = meta.artworkUri?.toString(),
            isPlaying = p.isPlaying,
            position = p.currentPosition.coerceAtLeast(0),
            duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            speed = p.playbackParameters.speed,
            isVideo = meta.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO ||
                p.currentTracks.containsType(C.TRACK_TYPE_VIDEO),
        )
    }

    // ---------- Paste link / download ----------

    fun openPaste(prefill: String? = null) {
        paste = PasteState(visible = true, text = prefill.orEmpty())
        if (!prefill.isNullOrBlank() && LinkParser.parse(prefill) is LinkParser.Result.Ok) checkLink()
    }

    fun closePaste() {
        probeJob?.cancel()
        paste = PasteState()
    }

    fun onPasteText(text: String) {
        paste = paste.copy(text = text, error = null, probe = null)
    }

    fun setMode(mode: MediaMode) { paste = paste.copy(mode = mode) }
    fun setHeight(h: Int) { paste = paste.copy(height = h) }

    fun checkLink() {
        when (val parsed = LinkParser.parse(paste.text)) {
            is LinkParser.Result.Invalid -> paste = paste.copy(error = parsed.message, probe = null)
            is LinkParser.Result.Ok -> {
                paste = paste.copy(probing = true, error = null, probe = null)
                probeJob?.cancel()
                probeJob = viewModelScope.launch {
                    paste = try {
                        val r = c.downloader.probe(parsed.url)
                        val defaultHeight = r.videoQualities.firstOrNull { it.height <= 1080 }?.height
                            ?: r.videoQualities.firstOrNull()?.height
                        paste.copy(probing = false, probe = r, height = defaultHeight)
                    } catch (e: DownloadException) {
                        paste.copy(probing = false, error = e.message)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        paste.copy(probing = false, error = "Couldn't read this link: ${e.message}")
                    }
                }
            }
        }
    }

    fun startDownload() {
        val probe = paste.probe ?: return
        val mode = paste.mode
        val height = paste.height
        viewModelScope.launch {
            repo.enqueue(probe, mode, height)
            closePaste()
            tab = Tab.DOWNLOADS
        }
    }

    fun cancel(item: Item) = viewModelScope.launch { repo.cancel(item.id) }
    fun retry(item: Item) = viewModelScope.launch { repo.retry(item.id) }

    fun delete(item: Item) = viewModelScope.launch {
        controller?.let { p ->
            for (i in p.mediaItemCount - 1 downTo 0) {
                if (p.getMediaItemAt(i).mediaId == item.id.toString()) p.removeMediaItem(i)
            }
        }
        repo.delete(item)
        _messages.send("Deleted “${item.title}”")
    }

    fun updateDownloader() = viewModelScope.launch {
        _messages.send("Updating downloader…")
        val msg = try {
            c.downloader.update()
        } catch (e: Exception) {
            e.message ?: "Update failed"
        }
        _messages.send(msg)
    }

    // ---------- Playback ----------

    /** Replaces the queue with the whole library and starts at [item]. */
    fun playLibraryFrom(item: Item) {
        val p = controller ?: return
        val list = library.value.filter { it.isPlayableOffline() }
        val start = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        if (list.isEmpty()) return
        p.setMediaItems(list.map { it.toMediaItem() }, start, list[start].positionMs)
        p.prepare()
        p.play()
        tab = Tab.PLAYER
    }

    fun playAll() = library.value.firstOrNull { it.isPlayableOffline() }?.let { playLibraryFrom(it) }

    fun addToQueue(item: Item) = viewModelScope.launch {
        val p = controller ?: return@launch
        if (!item.isPlayableOffline()) return@launch
        p.addMediaItem(item.toMediaItem())
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        _messages.send("Added to queue")
    }

    fun playQueueIndex(i: Int) {
        controller?.run { seekToDefaultPosition(i); play() }
    }

    fun moveQueue(from: Int, to: Int) {
        val p = controller ?: return
        if (to in 0 until p.mediaItemCount) p.moveMediaItem(from, to)
    }

    /** Removes from the queue only; the file stays in the library. */
    fun removeFromQueue(i: Int) { controller?.removeMediaItem(i) }

    fun togglePlay() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_ENDED) seekTo(0); play() } }
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skipBack() = controller?.seekBack()
    fun skipForward() = controller?.seekForward()
    fun previous() = controller?.seekToPrevious()
    fun next() = controller?.seekToNext()
    fun setSpeed(s: Float) = controller?.setPlaybackSpeed(s)
}
