package com.neutrino.photos.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.photos.Route
import com.neutrino.photos.core.media.Grouping
import com.neutrino.photos.core.media.Timeline
import com.neutrino.photos.graph
import com.neutrino.photos.media.ImportRunner
import com.neutrino.photos.media.MediaPreparer
import com.neutrino.photos.ui.MediaGrid
import com.neutrino.photos.ui.PageBar
import com.neutrino.photos.ui.SelectionBar
import com.neutrino.photos.ui.Unavailable
import kotlinx.coroutines.launch

/**
 * The timeline: every photo and video, newest first, grouped by day, month or year. Drawn from the
 * listing the device kept before the server answers, and from the plaintext covers — so it browses
 * offline, and before a key is restored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val items by graph.library.items.collectAsState()
    val loading by graph.library.isLoading.collectAsState()
    val unreachable by graph.library.unreachable.collectAsState()
    val grouping by graph.prefs.grouping.flow.collectAsState()
    val imports by graph.imports.state.collectAsState()
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var refreshing by remember { mutableStateOf(false) }

    val visible = remember(items) { Timeline.visible(items) }
    val sections = remember(visible, grouping) { Timeline.sections(visible, grouping) }

    LaunchedEffect(Unit) {
        graph.library.load()
        graph.content.refreshPreviewIndex()
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        graph.imports.enqueue(uris.map { MediaPreparer.Source(it) })
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            PageBar("Library", null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.ViewModule, "Group by") }
                    DropdownMenu(menu, { menu = false }) {
                        Grouping.entries.forEach { g ->
                            DropdownMenuItem(text = { Text(g.label + if (g == grouping) "  ✓" else "") }, onClick = { menu = false; graph.prefs.grouping.set(g) })
                        }
                    }
                }
                IconButton(onClick = { graph.router.push(Route.Backup) }) { Icon(Icons.Filled.CloudUpload, "Back Up This Phone") }
            }
        } else {
            SelectionBar(selection, visible, onClear = { selection = emptySet() })
        }
        ImportBanner(imports)
        if (unreachable) Text("Can’t reach the server — showing the photos this phone last saw.", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                scope.launch { refreshing = true; graph.library.load(); graph.content.refreshPreviewIndex(); refreshing = false }
            }) {
                when {
                    visible.isEmpty() && loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                    visible.isEmpty() -> Unavailable(Icons.Filled.PhotoLibrary, "No photos yet",
                        "Add photos from this phone. They are encrypted here before they are uploaded, and open on the web and your other devices.",
                        "Add Photos") { pick() }
                    else -> MediaGrid(sections, grouping, selection, { selection = it },
                        onOpen = { item -> graph.router.push(Route.Viewer(visible.map { it.id }, visible.indexOf(item))) },
                        onGroupingChange = { graph.prefs.grouping.set(it) })
                }
            }
            if (selection.isEmpty()) FloatingActionButton(onClick = { pick() }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, "Add Photos")
            }
        }
    }
}

/** Import progress while it runs, and what happened when it is done. */
@Composable
fun ImportBanner(state: ImportRunner.State) {
    val graph = LocalContext.current.graph
    if (state.total == 0) return
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.running) "Importing ${minOf(state.done + 1, state.total)} of ${state.total}…" else state.summary,
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (state.running) TextButton(onClick = { graph.imports.cancel() }) { Text("Stop") }
                else TextButton(onClick = { graph.imports.dismiss() }) { Text("OK") }
            }
            if (state.running) LinearProgressIndicator(progress = { (state.done + state.currentProgress) / state.total }, Modifier.fillMaxWidth())
            state.failures.lastOrNull()?.takeIf { !state.running }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
