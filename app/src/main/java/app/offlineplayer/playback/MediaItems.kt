package app.offlineplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.offlineplayer.data.Item
import app.offlineplayer.downloader.MediaMode
import java.io.File

fun Item.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.fromFile(File(filePath ?: "")))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(thumbPath?.let { Uri.fromFile(File(it)) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (mode == MediaMode.VIDEO) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

fun Item.isPlayableOffline(): Boolean = filePath != null && File(filePath).exists()
