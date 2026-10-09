package app.offlineplayer.download

import android.content.Context
import app.offlineplayer.container
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps yt-dlp current without the user having to think about it. YouTube changes
 * often, and an old engine fails with HTTP 403, so the app checks for a newer one
 * on launch (at most daily) and again right after YouTube refuses a download.
 */
object DownloaderUpdates {
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val AFTER_FAILURE_MS = 30 * 60 * 1000L

    private const val PREFS = "downloader"
    private const val KEY_LAST_CHECK = "last_update_check"
    private val lock = Mutex()

    /** Checks for an update if the last check is older than [maxAgeMs]. Returns true if the engine changed. */
    suspend fun ensureFresh(context: Context, maxAgeMs: Long): Boolean = lock.withLock {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < maxAgeMs) return@withLock false
        val d = context.container.downloader
        runCatching { d.initialize() }
        val before = d.version()
        val ok = runCatching { d.update() }.isSuccess
        if (ok) prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        ok && d.version() != before
    }

    /** Manual "Update downloader": always checks. Returns a status line for the user. */
    suspend fun updateNow(context: Context): String = lock.withLock {
        val msg = context.container.downloader.update()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        msg
    }
}
