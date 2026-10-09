package app.offlineplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.offlineplayer.data.Item
import app.offlineplayer.data.Status
import app.offlineplayer.downloader.MediaMode

@Composable
fun DownloadsScreen(vm: MainViewModel, downloads: List<Item>) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Downloads",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        if (downloads.isEmpty()) {
            EmptyHint("No downloads in progress.\nFinished downloads move to the Library.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(downloads, key = { it.id }) { item ->
                DownloadRow(
                    item,
                    onCancel = { vm.cancel(item) },
                    onRetry = { vm.retry(item) },
                    onRemove = { vm.delete(item) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(item: Item, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item.thumbnail, Modifier.width(96.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val kind = if (item.mode == MediaMode.VIDEO) "Video ${item.maxHeight?.let { "${it}p" } ?: ""}".trim() else "Audio"
            val size = item.estimatedSize?.let { " · ~${formatBytes(it)}" } ?: ""
            val (line, color) = when (item.status) {
                Status.QUEUED -> "Waiting · $kind$size" to MaterialTheme.colorScheme.onSurfaceVariant
                Status.DOWNLOADING -> "${item.progress.toInt()}%${formatEta(item.etaSec)} · $kind$size" to MaterialTheme.colorScheme.primary
                Status.FAILED -> "Failed: ${item.error ?: "unknown error"}" to MaterialTheme.colorScheme.error
                Status.CANCELLED -> "Cancelled · $kind" to MaterialTheme.colorScheme.onSurfaceVariant
                Status.COMPLETED -> "Done" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = color)
            if (item.status == Status.FAILED) ErrorDetails(item.errorDetails)
            when (item.status) {
                Status.DOWNLOADING -> {
                    Spacer(Modifier.height(6.dp))
                    if (item.progress <= 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                Status.QUEUED -> {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
                else -> Unit
            }
        }
        when (item.status) {
            Status.QUEUED, Status.DOWNLOADING ->
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
            Status.FAILED, Status.CANCELLED -> {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Retry") }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove") }
            }
            Status.COMPLETED -> Unit
        }
    }
}
