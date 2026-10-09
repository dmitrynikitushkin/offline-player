@file:OptIn(UnstableApi::class)

package app.offlineplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val ui by vm.player.collectAsStateWithLifecycle()
    if (!ui.hasMedia) {
        EmptyHint("Nothing is playing.\nPick something in the Library, or tap “Play all”.")
        return
    }
    val showVideo = ui.isVideo && !vm.listenMode

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (showVideo) {
                    VideoSurface(vm.controller, keepScreenOn = ui.isPlaying, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(model = ui.artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                    if (ui.isVideo) {
                        Text(
                            "Listening mode — audio only",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp)) {
                Text(ui.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(8.dp))
                SeekBar(ui, vm)
                Controls(ui, vm)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    SpeedChip(ui.speed) { vm.setSpeed(it) }
                    if (ui.isVideo) {
                        AssistChip(
                            onClick = { vm.listenMode = !vm.listenMode },
                            label = { Text(if (vm.listenMode) "Watch" else "Listen") },
                            leadingIcon = { Icon(if (vm.listenMode) Icons.Filled.Movie else Icons.Filled.Headphones, null) },
                        )
                        AssistChip(
                            onClick = { vm.listenMode = false; vm.fullscreen = true },
                            label = { Text("Full screen") },
                            leadingIcon = { Icon(Icons.Filled.Fullscreen, null) },
                        )
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text(
                "Up next · ${ui.queue.size} in queue",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(ui.queue, key = { i, row -> "$i-${row.mediaId}" }) { i, row ->
            val current = i == ui.index
            Row(
                Modifier.fillMaxWidth().clickable { vm.playQueueIndex(i) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}. ${row.title}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { vm.moveQueue(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                IconButton(onClick = { vm.moveQueue(i, i + 1) }, enabled = i < ui.queue.lastIndex) {
                    Icon(Icons.Filled.ArrowDownward, "Move down")
                }
                IconButton(onClick = { vm.removeFromQueue(i) }) { Icon(Icons.Filled.Close, "Remove from queue") }
            }
        }
    }
}

@Composable
private fun SeekBar(ui: PlayerUi, vm: MainViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = ui.duration.coerceAtLeast(1)
    val value = if (dragging) dragValue else (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { vm.seekTo((dragValue * dur).toLong()); dragging = false },
        enabled = ui.duration > 0,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatDuration((value * dur).toLong()), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(ui.duration), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Controls(ui: PlayerUi, vm: MainViewModel, tint: Color = Color.Unspecified) {
    val colors = if (tint == Color.Unspecified) IconButtonDefaults.iconButtonColors()
    else IconButtonDefaults.iconButtonColors(contentColor = tint)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.previous() }, colors = colors) { Icon(Icons.Filled.SkipPrevious, "Previous") }
        IconButton(onClick = { vm.skipBack() }, colors = colors) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
        FilledIconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(64.dp)) {
            Icon(if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ui.isPlaying) "Pause" else "Play", Modifier.size(36.dp))
        }
        IconButton(onClick = { vm.skipForward() }, colors = colors) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
        IconButton(onClick = { vm.next() }, colors = colors) { Icon(Icons.Filled.SkipNext, "Next") }
    }
}

@Composable
private fun SpeedChip(speed: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("${speed.toString().removeSuffix(".0")}×") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(text = { Text("${s.toString().removeSuffix(".0")}×") }, onClick = { open = false; onPick(s) })
            }
        }
    }
}

@Composable
fun VideoSurface(player: Player?, keepScreenOn: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = player
            view.keepScreenOn = keepScreenOn
        },
        // Detaching only removes the picture; the service keeps playing the audio.
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable
fun FullscreenPlayer(vm: MainViewModel) {
    val ui by vm.player.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    val activity = LocalContext.current as Activity

    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previous
        }
    }
    BackHandler { vm.fullscreen = false }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable { overlay = !overlay }) {
        VideoSurface(vm.controller, keepScreenOn = ui.isPlaying, modifier = Modifier.fillMaxSize())
        if (overlay) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.title ?: "", color = Color.White, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { vm.fullscreen = false }) {
                        Icon(Icons.Filled.FullscreenExit, "Exit full screen", tint = Color.White)
                    }
                }
                Column {
                    SeekBar(ui, vm)
                    Controls(ui, vm, tint = Color.White)
                }
            }
        }
    }
}
