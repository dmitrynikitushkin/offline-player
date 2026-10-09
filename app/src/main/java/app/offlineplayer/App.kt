package app.offlineplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.offlineplayer.data.AppDatabase
import app.offlineplayer.data.Repository
import app.offlineplayer.download.DownloaderUpdates
import app.offlineplayer.downloader.Downloader
import app.offlineplayer.downloader.YtDlpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        // Unpacking the bundled engine takes a few seconds on first launch; do it early.
        container.appScope.launch {
            runCatching { container.downloader.initialize() }
            runCatching { DownloaderUpdates.ensureFresh(this@App, DownloaderUpdates.DAY_MS) }
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val downloader: Downloader = YtDlpDownloader(context)

    /** App-private storage on the phone: no permissions needed, removed on uninstall. */
    val mediaDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }

    val repository = Repository(context, db, downloader, mediaDir)
}

val Context.container: AppContainer get() = (applicationContext as App).container
