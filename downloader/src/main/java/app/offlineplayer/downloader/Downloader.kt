package app.offlineplayer.downloader

import java.io.File

/**
 * The app's only contract with the download engine. Playback, library and UI
 * never touch yt-dlp directly, so the engine can be updated or replaced alone.
 */
interface Downloader {
    /** Unpacks the bundled engine on first run. Safe to call repeatedly. */
    suspend fun initialize()

    /** Reads title, duration, thumbnail and available qualities without downloading. */
    suspend fun probe(url: String): ProbeResult

    /** Downloads into [outDir]; files are named after [DownloadSpec.id]. Throws [DownloadException]. */
    suspend fun download(spec: DownloadSpec, outDir: File, onProgress: (Progress) -> Unit): DownloadOutput

    /** Kills a running download started with the same id. */
    fun cancel(id: String)

    /** Updates the extractor (yt-dlp) in place; returns a human-readable status. */
    suspend fun update(): String

    fun version(): String?
}

enum class MediaMode { VIDEO, AUDIO }

data class QualityOption(val height: Int, val estimatedBytes: Long?)

data class ProbeResult(
    val url: String,
    val title: String,
    val durationSec: Long?,
    val thumbnailUrl: String?,
    /** Highest first. */
    val videoQualities: List<QualityOption>,
    val audioEstimatedBytes: Long?,
)

data class DownloadSpec(val id: String, val url: String, val mode: MediaMode, val maxHeight: Int?)

data class DownloadOutput(val media: File, val thumbnail: File?)

data class Progress(val percent: Float, val etaSec: Long?)

enum class FailureReason {
    UNSUPPORTED_LINK, PLAYLIST, PRIVATE, SIGN_IN_REQUIRED, MEMBERS_ONLY, DRM, LIVE,
    UNAVAILABLE, BLOCKED, NETWORK, FORMAT_UNAVAILABLE, NO_SPACE, CANCELLED, UNKNOWN,
}

class DownloadException(
    val reason: FailureReason,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)
