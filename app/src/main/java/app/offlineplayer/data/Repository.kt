package app.offlineplayer.data

import android.content.Context
import app.offlineplayer.download.DownloadWorker
import app.offlineplayer.downloader.Downloader
import app.offlineplayer.downloader.MediaMode
import app.offlineplayer.downloader.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    private val downloader: Downloader,
    private val mediaDir: File,
) {
    val library = db.items().library()
    val downloads = db.items().downloads()
    val totalBytes = db.items().totalBytes()

    suspend fun enqueue(probe: ProbeResult, mode: MediaMode, height: Int?): Long {
        val estimate = when (mode) {
            MediaMode.AUDIO -> probe.audioEstimatedBytes
            MediaMode.VIDEO -> probe.videoQualities.firstOrNull { it.height == height }?.estimatedBytes
        }
        val id = db.items().insert(
            Item(
                url = probe.url,
                mode = mode,
                maxHeight = if (mode == MediaMode.VIDEO) height else null,
                title = probe.title,
                remoteThumbnail = probe.thumbnailUrl,
                durationSec = probe.durationSec,
                status = Status.QUEUED,
                estimatedSize = estimate,
            ),
        )
        DownloadWorker.enqueue(context, id)
        return id
    }

    suspend fun cancel(id: Long) {
        // Mark first so the worker knows this was the user, not the system.
        db.items().setStatus(id, Status.CANCELLED, null, null)
        DownloadWorker.cancel(context, id)
        downloader.cancel(id.toString())
    }

    suspend fun retry(id: Long) {
        db.items().resetForRetry(id)
        DownloadWorker.enqueue(context, id)
    }

    /** Deletes the files and the library entry (and, by cascade, its queue entries). */
    suspend fun delete(item: Item) {
        if (item.status == Status.QUEUED || item.status == Status.DOWNLOADING) cancel(item.id)
        withContext(Dispatchers.IO) {
            item.filePath?.let { File(it).delete() }
            item.thumbPath?.let { File(it).delete() }
            mediaDir.listFiles { f -> f.name.startsWith("${item.id}.") }?.forEach { it.delete() }
        }
        db.items().delete(item)
    }
}
