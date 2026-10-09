package app.offlineplayer.downloader

import app.offlineplayer.downloader.FailureReason.*

/** Turns yt-dlp's stderr into something a person can act on. */
internal object ErrorMapper {
    fun map(output: String): DownloadException {
        val t = output.lowercase()
        val details = tail(output)
        fun e(r: FailureReason, msg: String, retry: Boolean = false) = DownloadException(r, msg, retry, details)
        return when {
            "private video" in t ->
                e(PRIVATE, "This video is private. Only public videos can be downloaded.")
            "confirm your age" in t || "age-restricted" in t || "age restricted" in t ->
                e(SIGN_IN_REQUIRED, "This video is age-restricted and needs a signed-in account, which this app doesn't use.")
            "not a bot" in t ->
                e(BLOCKED, "YouTube asked for a sign-in to check you're not a bot. Wait a while and retry, or update the downloader (Library ▸ ⋮).", true)
            "members-only" in t || "members only" in t || "join this channel" in t ->
                e(MEMBERS_ONLY, "This is a members-only video and can't be downloaded.")
            "drm" in t ->
                e(DRM, "This video is DRM-protected (for example a purchased or rented film) and can't be downloaded.")
            "live event" in t || "is live" in t || "premieres in" in t || "live stream" in t ->
                e(LIVE, "Live streams and upcoming premieres can't be downloaded. Try again after the broadcast has ended.")
            "requested format is not available" in t ->
                e(FORMAT_UNAVAILABLE, "That quality isn't available for this video. Pick another quality.", true)
            "no space left" in t ->
                e(NO_SPACE, "The phone is out of storage. Free some space and retry.", true)
            "video unavailable" in t || "has been removed" in t || "not available in your country" in t ||
                "who has blocked it" in t || "account associated with this video has been terminated" in t ->
                e(UNAVAILABLE, "YouTube says this video is unavailable (removed, region-blocked or restricted).")
            "http error 403" in t ->
                e(BLOCKED, "YouTube refused the download (HTTP 403). This usually means the downloader is out of date; the app updates it automatically. Tap Retry.", true)
            "http error 429" in t ->
                e(BLOCKED, "YouTube is rate-limiting this connection (HTTP 429). Wait a while, or switch between Wi-Fi and mobile data, then Retry.", true)
            "unable to download" in t || "timed out" in t || "failed to resolve" in t ||
                "network is unreachable" in t || "connection" in t || "temporary failure" in t ->
                e(NETWORK, "Network problem. Check your connection and retry.", true)
            "unsupported url" in t ->
                e(UNSUPPORTED_LINK, "This link isn't supported.")
            else ->
                e(UNKNOWN, lastErrorLine(output) ?: "The download failed for an unknown reason.", true)
        }
    }

    /** Last meaningful lines of yt-dlp output, without progress spam. */
    private fun tail(o: String): String? =
        o.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[download]") }
            .toList()
            .takeLast(20)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?.take(4000)

    private fun lastErrorLine(o: String): String? =
        o.lineSequence().lastOrNull { it.startsWith("ERROR") }
            ?.removePrefix("ERROR:")?.trim()?.take(300)
}
