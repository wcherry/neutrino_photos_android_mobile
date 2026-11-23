package com.neutrino.photos.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.photos.Collection
import com.neutrino.photos.Route
import com.neutrino.photos.core.media.Timeline
import com.neutrino.photos.core.media.TimelineSection
import com.neutrino.photos.core.model.Album
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.graph
import com.neutrino.photos.ui.FormRow
import com.neutrino.photos.ui.FormSection
import com.neutrino.photos.ui.MediaGrid
import com.neutrino.photos.ui.PageBar
import com.neutrino.photos.ui.SelectionBar
import com.neutrino.photos.ui.Thumbnail
import com.neutrino.photos.ui.Unavailable
import com.neutrino.photos.ui.viewer.ViewerItems
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog
import kotlinx.coroutines.launch

/** The collections every library has, then the user's albums. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen() {
    val graph = LocalContext.current.graph
    val albums by graph.albums.albums.collectAsState()
    val loading by graph.albums.isLoading.collectAsState()
    val items by graph.library.items.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Album?>(null) }
    var deleting by remember { mutableStateOf<Album?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.albums.load() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Albums", null) { IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New Album") } }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FormSection(null) {
                    val visible = Timeline.visible(items)
                    CollectionRow(Icons.Filled.Star, Collection.FAVORITES, visible.count { it.isStarred })
                    CollectionRow(Icons.Filled.Videocam, Collection.VIDEOS, visible.count { it.kind == MediaItem.Kind.VIDEO })
                    CollectionRow(Icons.Filled.NewReleases, Collection.RECENTLY_ADDED, null)
                    CollectionRow(Icons.Filled.Archive, Collection.ARCHIVE, null)
                    CollectionRow(Icons.Filled.Delete, Collection.TRASH, null, divider = false)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text("My Albums", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                if (loading) Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                else Text("No albums yet. Select photos in the library and choose Add to Album, or tap +.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(albums, key = { it.id }) { album ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(Modifier.combinedClickable(onClick = { graph.router.push(Route.AlbumDetail(album)) }, onLongClick = { if (!album.isAuto) menu = true })) {
                        val cover = album.coverPhotoId?.let(graph.library::find)
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            if (cover != null) Thumbnail(cover, Modifier.fillMaxSize())
                            else Icon(Icons.Filled.Collections, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(album.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("${album.photoCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; renaming = album })
                        DropdownMenuItem(text = { Text("Delete Album", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = album })
                    }
                }
            }
        }
    }
    if (creating) TextInputDialog("New Album", "", "Title", "Create", onDismiss = { creating = false }) { title ->
        creating = false
        scope.launch { runCatching { graph.albums.create(title) } }
    }
    renaming?.let { album -> TextInputDialog("Rename Album", album.title, "Title", "Rename", onDismiss = { renaming = null }) { renaming = null; graph.albums.rename(album.id, it) } }
    deleting?.let { album ->
        ConfirmDialog("Delete “${album.title}”?", "The album is deleted. Its photos stay in your library.", "Delete", destructive = true, onDismiss = { deleting = null }) {
            graph.albums.delete(album.id)
        }
    }
}

@Composable
private fun CollectionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, collection: Collection, count: Int?, divider: Boolean = true) {
    val graph = LocalContext.current.graph
    FormRow(onClick = { graph.router.push(Route.CollectionView(collection)) }, divider = divider) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(collection.title, Modifier.padding(start = 12.dp).weight(1f))
        count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun AlbumDetailScreen(album: Album, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val revision by graph.library.revision.collectAsState()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(album.id, revision) {
        runCatching { graph.albums.items(album.id) }.onSuccess { list ->
            // The library's copy is fresher (a favourite just set); the album's fills in anything it lacks.
            items = Timeline.visible(list.map { graph.library.find(it.id) ?: it }.filter { it.deletedAt == null })
            list.forEach { ViewerItems.extra[it.id] = it }
        }.onFailure { error = it.message }
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    val title = graph.albums.albums.collectAsState().value.firstOrNull { it.id == album.id }?.title ?: album.title
    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) PageBar(title, onBack)
        else Column {
            SelectionBar(selection, items.orEmpty(), onClear = { selection = emptySet() })
            if (!album.isAuto) TextButton(onClick = {
                val chosen = selection
                selection = emptySet()
                scope.launch { chosen.forEach { runCatching { graph.albums.remove(album.id, it) } }; items = items?.filter { it.id !in chosen } }
            }, Modifier.padding(start = 8.dp)) { Text("Remove from Album") }
        }
        val list = items
        when {
            list == null && error == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list == null -> Unavailable(Icons.Filled.Collections, "Couldn’t open this album", error ?: "", "Retry") { error = null }
            list.isEmpty() -> Unavailable(Icons.Filled.Collections, "This album is empty", "Select photos in the library and choose Add to Album.")
            else -> MediaGrid(listOf(TimelineSection("all", "", null, list)), null, selection, { selection = it },
                onOpen = { item -> graph.router.push(Route.Viewer(list.map { it.id }, list.indexOf(item), album.id)) })
        }
    }
}

/** Favorites, Videos, Recently Added, Archive and Recently Deleted. */
@Composable
fun CollectionScreen(collection: Collection, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val items by graph.library.items.collectAsState()
    val archived by graph.library.archived.collectAsState()
    val trash by graph.library.trash.collectAsState()
    var selection by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(collection) {
        when (collection) {
            Collection.ARCHIVE -> graph.library.loadArchive()
            Collection.TRASH -> graph.library.loadTrash()
            else -> {}
        }
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    val list = when (collection) {
        Collection.FAVORITES -> Timeline.visible(items).filter { it.isStarred }
        Collection.VIDEOS -> Timeline.visible(items).filter { it.kind == MediaItem.Kind.VIDEO }
        Collection.RECENTLY_ADDED -> Timeline.visible(items).sortedByDescending { it.createdAt }.take(200)
        Collection.ARCHIVE -> Timeline.visible(archived)
        Collection.TRASH -> trash
        Collection.LIBRARY -> Timeline.visible(items)
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) PageBar(collection.title, onBack) {
            if (collection == Collection.TRASH && trash.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty", color = MaterialTheme.colorScheme.error) }
        } else if (collection == Collection.TRASH) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selection = emptySet() }) { Text("Cancel") }
                Text("${selection.size} selected", Modifier.weight(1f))
                TextButton(onClick = { graph.library.restore(selection); selection = emptySet() }) { Text("Restore") }
                TextButton(onClick = { graph.library.deletePermanently(selection); selection = emptySet() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
        } else SelectionBar(selection, list, onClear = { selection = emptySet() }, archived = collection == Collection.ARCHIVE)

        if (collection == Collection.TRASH && list.isNotEmpty()) Text("Items are deleted for good 30 days after they are moved here.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Unavailable(Icons.Filled.Collections, "Nothing here", when (collection) {
            Collection.FAVORITES -> "Tap the star on a photo to add it to Favorites."
            Collection.ARCHIVE -> "Archived photos are kept out of the library without being deleted."
            Collection.TRASH -> "Deleted photos stay here for 30 days."
            else -> "No photos yet."
        })
        else MediaGrid(listOf(TimelineSection("all", "", null, list)), null, selection, { selection = it },
            onOpen = { item -> graph.router.push(Route.Viewer(list.map { it.id }, list.indexOf(item))) })
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty Recently Deleted?") },
        text = { Text("Everything here is deleted for good, on every device. This can’t be undone.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; graph.library.emptyTrash() }) { Text("Empty", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
    )
}
