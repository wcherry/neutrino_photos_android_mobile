package com.neutrino.photos.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import com.neutrino.photos.PhotoLinks
import com.neutrino.photos.core.media.Timeline
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.core.model.MetadataFields
import com.neutrino.photos.graph
import com.neutrino.photos.media.MediaPreparer
import com.neutrino.photos.ui.AlbumPicker
import com.neutrino.photos.ui.MediaActions
import com.neutrino.photos.ui.Thumbnail
import com.neutrino.photos.ui.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Items a screen outside the library (an album) hands the viewer, so it can page through them. */
object ViewerItems {
    val extra = HashMap<String, MediaItem>()
}

/**
 * One photo or video at a time, full screen: swipe between them, pinch or double-tap to zoom, play a
 * video or a photo's motion. Opens on the cover the grid already has, then the 2048 px preview, then
 * — only when one is needed — the original.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(ids: List<String>, start: Int, albumId: String?, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by graph.library.revision.collectAsState()
    val all by graph.library.items.collectAsState()
    fun resolve(id: String) = graph.library.find(id) ?: ViewerItems.extra[id]
    val pager = rememberPagerState(initialPage = start.coerceIn(0, maxOf(0, ids.size - 1))) { ids.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    @Suppress("UNUSED_EXPRESSION") revision
    val current = ids.getOrNull(pager.currentPage)?.let(::resolve)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { ids[it] }) { page ->
            val item = resolve(ids[page])
            if (item == null) Box(Modifier.fillMaxSize()) { Text("This item is no longer in your library.", Modifier.align(Alignment.Center), color = Color.White) }
            else when (item.kind) {
                MediaItem.Kind.VIDEO -> VideoPage(item, active = page == pager.currentPage) { chrome = !chrome }
                else -> PhotoPage(item, clip = Timeline.clipOf(item, all), onZoom = { zoomed = it }, onTap = { chrome = !chrome })
            }
        }

        if (chrome && current != null) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(current.timelineDate), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(current.displayName, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current.deletedAt == null) IconButton(onClick = { graph.library.setStarred(listOf(current.id), !current.isStarred) }) {
                    Icon(if (current.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder, if (current.isStarred) "Unfavorite" else "Favorite", tint = Color.White)
                }
                IconButton(onClick = { scope.launch { runCatching { MediaActions.share(context, graph, listOf(current)) }.onFailure { message = it.message } } }) {
                    Icon(Icons.Filled.Share, "Share", tint = Color.White)
                }
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "Info", tint = Color.White) }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        fun act(f: () -> Unit) { menu = false; f() }
                        if (current.deletedAt != null) {
                            DropdownMenuItem(text = { Text("Restore") }, onClick = { act { graph.library.restore(listOf(current.id)); onBack() } })
                            DropdownMenuItem(text = { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }, onClick = { act { graph.library.deletePermanently(listOf(current.id)); onBack() } })
                        } else {
                            DropdownMenuItem(text = { Text("Save to Phone") }, onClick = {
                                act { scope.launch { runCatching { MediaActions.saveToDevice(context, graph, current) }.onSuccess { message = "Saved to your phone’s gallery." }.onFailure { message = it.message } } }
                            })
                            DropdownMenuItem(text = { Text("Add to Album…") }, onClick = { act { picking = true } })
                            if (albumId != null) DropdownMenuItem(text = { Text("Remove from Album") }, onClick = {
                                act { scope.launch { runCatching { graph.albums.remove(albumId, current.id) }.onSuccess { onBack() }.onFailure { message = it.message } } }
                            })
                            DropdownMenuItem(text = { Text("Copy Link") }, onClick = {
                                act { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Photo link", PhotoLinks.link(current.fileId))) }
                            })
                            DropdownMenuItem(text = { Text(if (current.isArchived) "Unarchive" else "Archive") }, onClick = { act { graph.library.setArchived(listOf(current.id), !current.isArchived); onBack() } })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { act { confirmDelete = true } })
                        }
                    }
                }
            }
        }
    }

    if (info && current != null) ModalBottomSheet(onDismissRequest = { info = false }) { InfoPanel(current) }
    if (picking && current != null) AlbumPicker(onDismiss = { picking = false }) { id, title ->
        picking = false
        scope.launch { runCatching { if (title != null) graph.albums.create(title, listOf(current.id)) else graph.albums.add(id!!, listOf(current.id)) }.onFailure { message = it.message } }
    }
    if (confirmDelete && current != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this item?") },
        text = { Text("It moves to Recently Deleted for 30 days, on every device.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; graph.library.moveToTrash(listOf(current.id)); onBack() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}

@Composable
private fun PhotoPage(item: MediaItem, clip: MediaItem?, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    val graph = LocalContext.current.graph
    var full by remember(item.fileId) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(item.fileId) { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var playing by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.fileId) {
        runCatching {
            val file = graph.content.viewable(item)
            withContext(Dispatchers.IO) { decodeForScreen(file, 4096) }
        }.onSuccess { full = it }.onFailure { error = it.message }
    }
    fun setScale(s: Float, at: Offset = Offset.Zero) {
        scale = s.coerceIn(1f, 10f)
        if (scale == 1f) offset = Offset.Zero
        onZoom(scale > 1f)
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .pointerInput(item.fileId) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { setScale(if (scale > 1f) 1f else 2.5f) })
        }
        .pointerInput(item.fileId) {
            detectTransformGestures { _, pan, zoom, _ ->
                if (zoom != 1f || scale > 1f) {
                    setScale(scale * zoom)
                    // Clamped so the picture never slides off the screen.
                    val maxX = size.width * (scale - 1) / 2
                    val maxY = size.height * (scale - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                }
            }
        }) {
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        val bitmap = full
        if (bitmap != null) Image(bitmap, item.displayName, layer, contentScale = ContentScale.Fit)
        else Thumbnail(item, layer.background(Color.Black), contentScale = ContentScale.Fit)
        if (bitmap == null && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        error?.let { Text(it, Modifier.align(Alignment.Center).padding(32.dp), color = Color.White) }
        motion?.takeIf { playing }?.let { clipFile ->
            ClipPlayer(clipFile, loop = true, controls = false, Modifier.fillMaxSize())
        }
        if (item.isMotion) {
            TextButton(onClick = {
                if (playing) { playing = false; return@TextButton }
                scope.launch {
                    runCatching { motion ?: graph.content.motionClip(item, clip) }.onSuccess { motion = it; playing = true }.onFailure { error = it.message }
                }
            }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.large)) {
                Icon(Icons.Filled.MotionPhotosOn, null, tint = Color.White)
                Text(if (playing) "  Stop" else if (item.isLivePhoto) "  Live" else "  Motion", color = Color.White)
            }
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val graph = LocalContext.current.graph
    var file by remember(item.fileId) { mutableStateOf<File?>(null) }
    var error by remember(item.fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.fileId, active) {
        // Videos are downloaded only for the page on screen: one is often the size of a hundred photos.
        if (active && file == null) runCatching { graph.content.original(item) }.onSuccess { file = it }.onFailure { error = it.message }
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        val f = file
        if (f != null && active) ClipPlayer(f, loop = false, controls = true, Modifier.fillMaxSize().navigationBarsPadding())
        else Thumbnail(item, Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
        if (f == null && error == null && active) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        error?.let { Text(it, Modifier.align(Alignment.Center).padding(32.dp), color = Color.White) }
    }
}

@Composable
private fun ClipPlayer(file: File, loop: Boolean, controls: Boolean, modifier: Modifier) {
    val view = remember(file) { arrayOfNulls<VideoView>(1) }
    DisposableEffect(file) { onDispose { view[0]?.stopPlayback() } }
    AndroidView(factory = { ctx ->
        VideoView(ctx).also { v ->
            view[0] = v
            if (controls) v.setMediaController(MediaController(ctx).also { it.setAnchorView(v) })
            v.setOnPreparedListener { mp -> mp.isLooping = loop; v.start() }
            v.setVideoURI(Uri.fromFile(file))
        }
    }, modifier = modifier.background(Color.Black))
}

/** The picture at no more than [maxPixels] on its longest edge, upright. */
private fun decodeForScreen(file: File, maxPixels: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalStateException("This photo’s format can’t be shown on this phone.")
    val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return decoded.asImageBitmap()
    val m = Matrix().also { MediaPreparer.orient(it, orientation) }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    if (upright !== decoded) decoded.recycle()
    return upright.asImageBitmap()
}

@Composable
private fun InfoPanel(item: MediaItem) {
    val exif = item.exif
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(formatDateTime(item.timelineDate), style = MaterialTheme.typography.titleMedium)
        Text(item.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        InfoRow("Size", android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes))
        if (item.width != null && item.height != null) InfoRow("Dimensions", "${item.width} × ${item.height}")
        listOfNotNull(MetadataFields.string(exif, "make"), MetadataFields.string(exif, "model")).joinToString(" ").takeIf { it.isNotBlank() }?.let { InfoRow("Camera", it) }
        MetadataFields.string(exif, "lensModel")?.let { InfoRow("Lens", it) }
        listOfNotNull(
            MetadataFields.double(exif, "fNumber")?.let { "ƒ%.1f".format(it) },
            MetadataFields.string(exif, "exposureTime"),
            MetadataFields.int(exif, "iso")?.let { "ISO $it" },
            MetadataFields.double(exif, "focalLength")?.let { "%.1f mm".format(it) },
        ).joinToString("  ").takeIf { it.isNotBlank() }?.let { InfoRow("Exposure", it) }
        val lat = MetadataFields.double(exif, "gpsLatitude")
        val lon = MetadataFields.double(exif, "gpsLongitude")
        if (lat != null && lon != null) InfoRow("Location", "%.5f, %.5f".format(lat, lon))
        if (item.isMotion) InfoRow(if (item.isLivePhoto) "Live Photo" else "Motion Photo", MetadataFields.string(item.motionPhoto, "signal") ?: "Yes")
        item.daysUntilPurge()?.let { InfoRow("Deleted", if (it == 0L) "Deleting soon" else "$it days left") }
        Text("Photos are end-to-end encrypted. Only the details above are readable by the server, and location only if you share it in Settings.",
            Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f))
    }
}
