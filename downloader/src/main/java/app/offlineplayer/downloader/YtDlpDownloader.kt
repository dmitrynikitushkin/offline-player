package app.offlineplayer.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * yt-dlp running on the phone (bundled by youtubedl-android with Python and ffmpeg).
 * No cookies or accounts are ever passed, so private, members-only and age-gated
 * videos fail by design, and yt-dlp itself refuses DRM-protected formats.
 */
class YtDlpDownloader(private val context: Context) : Downloader {
    private val initLock = Mutex()
    @Volatile private var ready = false

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (!ready) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                ready = true
            }
        }
    }

    override suspend fun probe(url: String): ProbeResult {
        initialize()
        val req = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--socket-timeout", "30")
            addJsChallengeOptions()
        }
        val json = try {
            runInterruptible(Dispatchers.IO) { YoutubeDL.getInstance().execute(req).out }
        } catch (e: YoutubeDLException) {
            throw ErrorMapper.map(e.message.orEmpty())
        }
        return try {
            parseProbe(url, JSONObject(json))
        } catch (e: JSONException) {
            throw DownloadException(FailureReason.UNKNOWN, "Couldn't read the video details. Try updating the downloader.", true)
        }
    }

    override suspend fun download(spec: DownloadSpec, outDir: File, onProgress: (Progress) -> Unit): DownloadOutput {
        initialize()
        outDir.mkdirs()
        cleanup(outDir, spec.id)

        val req = YoutubeDLRequest(spec.url).apply {
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--no-mtime")
            addOption("--socket-timeout", "30")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            addOption("--embed-metadata")
            addJsChallengeOptions()
            addOption("--write-thumbnail") // saved next to the media as <id>.jpg/.webp
            addOption("-o", "${outDir.absolutePath}/${spec.id}.%(ext)s")
            when (spec.mode) {
                MediaMode.VIDEO -> {
                    val h = spec.maxHeight ?: 1080
                    addOption(
                        "-f",
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b",
                    )
                    addOption("--merge-output-format", "mp4")
                    addOption("--remux-video", "mp4")
                }
                MediaMode.AUDIO -> {
                    addOption("-f", "ba[ext=m4a]/ba")
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                }
            }
        }

        try {
            // runInterruptible: if the worker is cancelled, the waiting thread is
            // interrupted and the library kills the yt-dlp process.
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(
                    request = req,
                    processId = spec.id,
                    callback = { percent, eta, _ ->
                        if (percent >= 0f) onProgress(Progress(percent, eta.takeIf { it > 0 }))
                    },
                )
            }
        } catch (e: YoutubeDL.CanceledException) {
            cleanup(outDir, spec.id)
            throw DownloadException(FailureReason.CANCELLED, "Cancelled", retryable = true)
        } catch (e: YoutubeDLException) {
            cleanup(outDir, spec.id)
            throw ErrorMapper.map(e.message.orEmpty())
        }

        val wanted = if (spec.mode == MediaMode.VIDEO) "mp4" else "m4a"
        val media = File(outDir, "${spec.id}.$wanted").takeIf { it.exists() }
            ?: outDir.listFiles { f -> f.name.startsWith("${spec.id}.") && f.extension in MEDIA_EXTS }?.firstOrNull()
            ?: throw DownloadException(FailureReason.UNKNOWN, "The download finished but no file was produced.", true)
        val thumb = THUMB_EXTS.map { File(outDir, "${spec.id}.$it") }.firstOrNull { it.exists() }
        return DownloadOutput(media, thumb)
    }

    override fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    override suspend fun update(): String {
        initialize()
        return withContext(Dispatchers.IO) {
            val status = try {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            } catch (e: YoutubeDLException) {
                throw DownloadException(FailureReason.NETWORK, "Update failed: ${e.message}", true)
            }
            val v = version() ?: "unknown version"
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Already up to date ($v)" else "Updated to $v"
        }
    }

    override fun version(): String? = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /**
     * YouTube serves a JavaScript challenge that must be solved or downloads get HTTP 403.
     * youtubedl-android 0.18.1+ bundles QuickJS and passes --js-runtimes itself; the solver
     * scripts ship inside yt-dlp. If the bundled scripts don't match the yt-dlp version,
     * allow fetching the matching ones from GitHub.
     */
    private fun YoutubeDLRequest.addJsChallengeOptions() {
        addOption("--remote-components", "ejs:github")
    }

    private fun cleanup(dir: File, id: String) {
        dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
    }

    private fun parseProbe(url: String, j: JSONObject): ProbeResult {
        if (j.optString("_type") == "playlist") {
            throw DownloadException(FailureReason.PLAYLIST, "Playlist links aren't supported. Share a single video instead.")
        }
        val live = j.optString("live_status")
        if (j.optBoolean("is_live") || live == "is_live" || live == "is_upcoming") {
            throw DownloadException(FailureReason.LIVE, "Live streams and upcoming premieres can't be downloaded.")
        }

        val duration = j.optDouble("duration").takeIf { !it.isNaN() }?.toLong()
        val formats = j.optJSONArray("formats")
        val parsed = buildList {
            for (i in 0 until (formats?.length() ?: 0)) {
                val f = formats!!.getJSONObject(i)
                if (f.optBoolean("has_drm")) continue
                val tbr = f.optDouble("tbr").takeIf { !it.isNaN() }
                val size = f.optLong("filesize").takeIf { it > 0 }
                    ?: f.optLong("filesize_approx").takeIf { it > 0 }
                    ?: if (tbr != null && duration != null) (tbr * 125 * duration).toLong() else null
                add(
                    Fmt(
                        height = f.optInt("height").takeIf { it > 0 },
                        ext = f.optString("ext"),
                        vcodec = f.optString("vcodec", "none").ifEmpty { "none" },
                        acodec = f.optString("acodec", "none").ifEmpty { "none" },
                        tbr = tbr ?: 0.0,
                        size = size,
                    ),
                )
            }
        }

        val audioOnly = parsed.filter { it.vcodec == "none" && it.acodec != "none" }
        val bestAudio = audioOnly.filter { it.ext == "m4a" }.maxByOrNull { it.tbr } ?: audioOnly.maxByOrNull { it.tbr }
        val audioSize = bestAudio?.size

        val qualities = parsed.filter { it.vcodec != "none" && it.height != null && it.height >= 144 }
            .groupBy { it.height!! }
            .map { (h, list) ->
                val v = list.filter { it.ext == "mp4" }.maxByOrNull { it.tbr } ?: list.maxBy { it.tbr }
                val needsAudio = v.acodec == "none"
                val est = v.size?.let { if (needsAudio) it + (audioSize ?: 0) else it }
                QualityOption(h, est)
            }
            .sortedByDescending { it.height }

        return ProbeResult(
            url = url,
            title = j.optString("title").ifBlank { "Untitled video" },
            durationSec = duration,
            thumbnailUrl = j.optString("thumbnail").ifBlank { null },
            videoQualities = qualities,
            audioEstimatedBytes = audioSize,
        )
    }

    private data class Fmt(
        val height: Int?, val ext: String, val vcodec: String, val acodec: String, val tbr: Double, val size: Long?,
    )

    private companion object {
        val MEDIA_EXTS = setOf("mp4", "m4a", "webm", "mkv", "opus", "mp3")
        val THUMB_EXTS = listOf("jpg", "webp", "png")
    }
}
