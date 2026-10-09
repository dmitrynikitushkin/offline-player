package app.offlineplayer.downloader

import java.net.URI

/** Pulls a single-video YouTube URL out of pasted or shared text, or explains why not. */
object LinkParser {
    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Invalid(val message: String) : Result
    }

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    private val youtubeHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    fun parse(text: String): Result {
        val raw = urlRegex.find(text.trim())?.value?.trimEnd('.', ',', ')', ']')
            ?: return Result.Invalid("That doesn't look like a link. In YouTube tap Share ▸ Copy link, then paste it here.")

        val uri = runCatching { URI(raw) }.getOrNull()
        val host = uri?.host?.lowercase()
            ?: return Result.Invalid("This link couldn't be read. Copy it again from YouTube's Share button.")

        if (host !in youtubeHosts) {
            return Result.Invalid("Only YouTube links are supported ($host isn't).")
        }

        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val isVideo = (host.endsWith("youtu.be") && path.length > 1) ||
            (path.startsWith("/watch") && Regex("""(^|&)v=""").containsMatchIn(query)) ||
            path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")

        return when {
            isVideo -> Result.Ok(raw)
            path.startsWith("/playlist") ->
                Result.Invalid("Playlist links aren't supported. Open one video and share that instead.")
            else ->
                Result.Invalid("This YouTube link isn't a single video (channel, search and home links can't be downloaded).")
        }
    }
}
