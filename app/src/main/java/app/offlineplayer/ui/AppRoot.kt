package app.offlineplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.offlineplayer.data.Status

@Composable
fun AppRoot(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    if (vm.fullscreen) {
        FullscreenPlayer(vm)
        return
    }

    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val active = downloads.count { it.status == Status.QUEUED || it.status == Status.DOWNLOADING }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.openPaste() },
                icon = { Icon(Icons.Filled.ContentPaste, null) },
                text = { Text("Paste link") },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == Tab.LIBRARY, onClick = { vm.tab = Tab.LIBRARY },
                    icon = { Icon(Icons.Filled.VideoLibrary, null) }, label = { Text("Library") },
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.DOWNLOADS, onClick = { vm.tab = Tab.DOWNLOADS },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.Filled.Download, null)
                        }
                    },
                    label = { Text("Downloads") },
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.PLAYER, onClick = { vm.tab = Tab.PLAYER },
                    icon = { Icon(Icons.Filled.PlayCircle, null) }, label = { Text("Player") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (vm.tab) {
                Tab.LIBRARY -> LibraryScreen(vm)
                Tab.DOWNLOADS -> DownloadsScreen(vm, downloads)
                Tab.PLAYER -> PlayerScreen(vm)
            }
        }
    }

    if (vm.paste.visible) PasteSheet(vm)
}
