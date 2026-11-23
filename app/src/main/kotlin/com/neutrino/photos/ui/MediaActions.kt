package com.neutrino.photos.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.neutrino.photos.AppGraph
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sharing and saving decrypt to the device first: what leaves the app is the photograph, not ciphertext. */
object MediaActions {

    /** The originals, written to the share cache (cleared at sign-out) and handed to the system share sheet. */
    suspend fun share(context: Context, graph: AppGraph, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val uris = items.map { item ->
            val original = graph.content.original(item)
            val out = File(dir, safeName(item.fileName))
            withContext(Dispatchers.IO) { original.copyTo(out, overwrite = true) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        }
        val type = if (items.all { it.kind == MediaItem.Kind.PHOTO }) "image/*" else if (items.all { it.kind == MediaItem.Kind.VIDEO }) "video/*" else "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(items.single().mimeType).putExtra(Intent.EXTRA_STREAM, uris.single())
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, if (items.size == 1) "Share" else "Share ${items.size} items"))
    }

    /** Saves the decrypted original into the phone's gallery, under Pictures (or Movies)/Neutrino. */
    suspend fun saveToDevice(context: Context, graph: AppGraph, item: MediaItem) {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Saving to the phone needs Android 10 or later.")
        val original = graph.content.original(item)
        val video = item.kind == MediaItem.Kind.VIDEO
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(item.fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Neutrino")
            item.captureDate?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it.toEpochMilli()) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        withContext(Dispatchers.IO) {
            val uri = context.contentResolver.insert(collection, values) ?: throw IllegalStateException("The gallery refused the photo.")
            context.contentResolver.openOutputStream(uri)?.use { out -> original.inputStream().use { it.copyTo(out) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun safeName(name: String) = name.replace(Regex("""[/\\:*?"<>|]"""), "_").ifBlank { "photo" }
}

/** The actions on a selection, as a bar above the content. Archive or unarchive depending on where the selection is. */
@Composable
fun SelectionBar(selection: Set<String>, items: List<MediaItem>, onClear: () -> Unit, archived: Boolean = false) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val chosen = items.filter { it.id in selection }
    val allStarred = chosen.isNotEmpty() && chosen.all { it.isStarred }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Cancel") }
            Text("${selection.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { graph.library.setStarred(selection, !allStarred) }) {
                Icon(if (allStarred) Icons.Filled.Star else Icons.Filled.StarBorder, if (allStarred) "Unfavorite" else "Favorite")
            }
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.LibraryAdd, "Add to Album") }
            IconButton(onClick = { scope.launch { runCatching { MediaActions.share(context, graph, chosen) }.onFailure { message = it.message } } }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { graph.library.setArchived(selection, !archived); onClear() }) {
                Icon(if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive, if (archived) "Unarchive" else "Archive")
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }
    if (picking) AlbumPicker(onDismiss = { picking = false }) { albumId, newTitle ->
        picking = false
        scope.launch {
            runCatching { if (newTitle != null) graph.albums.create(newTitle, selection) else graph.albums.add(albumId!!, selection) }
                .onSuccess { onClear() }.onFailure { message = it.message }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (selection.size == 1) "Delete this item?" else "Delete ${selection.size} items?") },
        text = { Text("It moves to Recently Deleted for 30 days, on every device.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; graph.library.moveToTrash(selection); onClear() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    message?.let { AlertDialog(onDismissRequest = { message = null }, title = { Text("Something went wrong") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}

/** Choose an album, or name a new one. */
@Composable
fun AlbumPicker(onDismiss: () -> Unit, onPick: (albumId: String?, newTitle: String?) -> Unit) {
    val graph = LocalContext.current.graph
    val albums by graph.albums.albums.collectAsState()
    var newTitle by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { graph.albums.load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Album") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTitle, { newTitle = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New album") })
                    TextButton(onClick = { onPick(null, newTitle.trim()) }, enabled = newTitle.isNotBlank()) { Text("Create") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(albums.filter { !it.isAuto }, key = { it.id }) { album ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(album.id, null) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LibraryAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(album.title, Modifier.padding(start = 12.dp).weight(1f))
                            Text("${album.photoCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
