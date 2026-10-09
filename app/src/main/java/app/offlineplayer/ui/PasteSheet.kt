@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.offlineplayer.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.offlineplayer.downloader.LinkParser
import app.offlineplayer.downloader.MediaMode

@Composable
fun PasteSheet(vm: MainViewModel) {
    val state = vm.paste
    val ctx = LocalContext.current
    fun clipboardText(): String? = runCatching {
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()

    // Opening the sheet with an empty field: offer whatever link is on the clipboard.
    LaunchedEffect(Unit) {
        if (state.text.isBlank()) {
            val clip = clipboardText()
            if (clip != null && LinkParser.parse(clip) is LinkParser.Result.Ok) {
                vm.onPasteText(clip)
                vm.checkLink()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { vm.closePaste() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Download a video", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.text,
                onValueChange = vm::onPasteText,
                label = { Text("YouTube link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { clipboardText()?.let { vm.onPasteText(it); vm.checkLink() } }) {
                        Icon(Icons.Filled.ContentPaste, "Paste")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.checkLink() }),
            )

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            when {
                state.probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Reading video details…", style = MaterialTheme.typography.bodyMedium)
                }

                state.probe == null -> OutlinedButton(onClick = { vm.checkLink() }, enabled = state.text.isNotBlank()) {
                    Text("Check link")
                }

                else -> {
                    val p = state.probe
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(p.thumbnailUrl, Modifier.width(120.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            p.durationSec?.let {
                                Text(formatDuration(it * 1000), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.mode == MediaMode.VIDEO,
                            onClick = { vm.setMode(MediaMode.VIDEO) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            enabled = p.videoQualities.isNotEmpty(),
                        ) { Text("Video (MP4)") }
                        SegmentedButton(
                            selected = state.mode == MediaMode.AUDIO,
                            onClick = { vm.setMode(MediaMode.AUDIO) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Audio only (M4A)") }
                    }

                    if (state.mode == MediaMode.VIDEO) {
                        Text("Quality", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            p.videoQualities.forEach { q ->
                                FilterChip(
                                    selected = state.height == q.height,
                                    onClick = { vm.setHeight(q.height) },
                                    label = {
                                        Text("${q.height}p" + (q.estimatedBytes?.let { " · ~${formatBytes(it)}" } ?: ""))
                                    },
                                )
                            }
                        }
                    } else {
                        Text(
                            "Best available audio" + (p.audioEstimatedBytes?.let { " · ~${formatBytes(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Button(
                        onClick = { vm.startDownload() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.mode == MediaMode.AUDIO || state.height != null,
                    ) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
