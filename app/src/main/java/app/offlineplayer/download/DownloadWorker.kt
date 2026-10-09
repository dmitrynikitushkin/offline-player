package app.offlineplayer.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.offlineplayer.App
import app.offlineplayer.container
import app.offlineplayer.data.Status
import app.offlineplayer.downloader.DownloadException
import app.offlineplayer.downloader.DownloadSpec
import app.offlineplayer.downloader.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One worker per queued item. A process-wide mutex makes them run one at a time,
 * in the order they were added. WorkManager persists them across app restarts
 * and reschedules any the system interrupts.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        val c = applicationContext.container
        val dao = c.db.items()

        return lock.withLock {
            val item = dao.get(id)
            if (item == null || (item.status != Status.QUEUED && item.status != Status.DOWNLOADING)) {
                return@withLock Result.success()
            }
            runCatching { setForeground(foregroundInfo(id, item.title, 0)) }
            dao.setProgress(id, Status.DOWNLOADING, 0f, null)

            val latest = MutableStateFlow(Progress(0f, null))
            try {
                val output = coroutineScope {
                    val reporter = launch {
                        var last = -1f
                        while (isActive) {
                            delay(750)
                            val p = latest.value
                            if (p.percent != last) {
                                last = p.percent
                                dao.setProgress(id, Status.DOWNLOADING, p.percent, p.etaSec)
                                runCatching { setForeground(foregroundInfo(id, item.title, p.percent.toInt())) }
                            }
                        }
                    }
                    try {
                        c.downloader.download(
                            DownloadSpec(id.toString(), item.url, item.mode, item.maxHeight),
                            c.mediaDir,
                        ) { latest.value = it }
                    } finally {
                        reporter.cancel()
                    }
                }
                val current = dao.get(id) ?: return@withLock Result.success() // deleted meanwhile
                dao.update(
                    current.copy(
                        status = Status.COMPLETED,
                        progress = 100f,
                        etaSec = null,
                        error = null,
                        filePath = output.media.absolutePath,
                        thumbPath = output.thumbnail?.absolutePath,
                        fileSize = output.media.length(),
                        completedAt = System.currentTimeMillis(),
                    ),
                )
                Result.success()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    c.downloader.cancel(id.toString())
                    // Stopped by the system (not the user): WorkManager will run it again.
                    if (dao.get(id)?.status == Status.DOWNLOADING) dao.setProgress(id, Status.QUEUED, 0f, null)
                }
                throw e
            } catch (e: DownloadException) {
                if (dao.get(id)?.status != Status.CANCELLED) dao.setStatus(id, Status.FAILED, e.message)
                Result.success() // failure is recorded on the item; Retry re-enqueues
            } catch (e: Exception) {
                dao.setStatus(id, Status.FAILED, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                Result.success()
            }
        }
    }

    private fun foregroundInfo(id: Long, title: String, pct: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "$pct%" else "Starting…")
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val notificationId = 1000 + (id % 100_000).toInt()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }

    companion object {
        private const val KEY_ID = "item_id"
        private val lock = Mutex()

        private fun workName(id: Long) = "download-$id"

        fun enqueue(context: Context, id: Long) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .addTag("download")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        }
    }
}
