package com.neutrino.photos.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.photos.core.media.Grouping
import com.neutrino.photos.core.media.TimelineSection
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.core.service.MediaContentService
import com.neutrino.photos.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The grid's bitmaps, in memory over [MediaContentService]'s disk cache of the plaintext covers. A
 * cell draws from here; a miss reads disk, then the server, once per file.
 */
class ThumbnailMemory(private val content: MediaContentService) {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(item: MediaItem): ImageBitmap? = cache.get(item.fileId)

    suspend fun load(item: MediaItem): ImageBitmap? {
        cache.get(item.fileId)?.let { return it }
        val file = runCatching { content.thumbnail(item) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() } ?: return null
        cache.put(item.fileId, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()
}

@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val memory = LocalContext.current.graph.thumbnails
    var bitmap by remember(item.fileId) { mutableStateOf(memory.peek(item)) }
    LaunchedEffect(item.fileId, item.thumbnailUrl) { if (bitmap == null) bitmap = memory.load(item) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, item.displayName, Modifier.fillMaxSize(), contentScale = contentScale)
        else Icon(if (item.kind == MediaItem.Kind.VIDEO) Icons.Filled.PlayCircle else Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One cell: the cover, a badge for what it is, and the selection mark while selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(item: MediaItem, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)) {
        Thumbnail(item, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            if (item.kind == MediaItem.Kind.VIDEO) Badge(Icons.Filled.PlayCircle)
            else if (item.isMotion) Badge(Icons.Filled.MotionPhotosOn)
        }
        if (item.isStarred) Icon(Icons.Filled.Star, "Favorite", Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp), tint = Color.White)
        if (selecting) {
            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (selected) "Selected" else "Not selected",
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).background(if (selected) Color.White else Color.Transparent, CircleShape),
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White)
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector) =
    Icon(icon, null, Modifier.size(18.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(1.dp), tint = Color.White)

/**
 * The timeline's grid: sections with headers, the density pinched between days, months and years,
 * a long press to start selecting. Tapping opens the viewer at that item, paging through [ordered].
 */
@Composable
fun MediaGrid(
    sections: List<TimelineSection>,
    grouping: Grouping?,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onGroupingChange: (Grouping) -> Unit = {},
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
) {
    val columns = grouping?.columns ?: 3
    val selecting = selection.isNotEmpty()
    val currentGrouping by rememberUpdatedState(grouping)
    val pinch = Modifier.pointerInput(Unit) {
        // Pinch in: denser (more per row); pinch out: sparser. Single-finger scrolling is untouched.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startSpan = 0f
            var done = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && !done) {
                    val span = abs(pressed[0].position.x - pressed[1].position.x) + abs(pressed[0].position.y - pressed[1].position.y)
                    if (startSpan == 0f) startSpan = span
                    val g = currentGrouping ?: continue
                    when {
                        span < startSpan * 0.7f -> { onGroupingChange(g.denser); done = true }
                        span > startSpan * 1.4f -> { onGroupingChange(g.sparser); done = true }
                    }
                    pressed.forEach { it.consume() }
                }
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns), state = state,
        modifier = modifier.fillMaxSize().then(if (grouping != null) pinch else Modifier),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        for (section in sections) {
            if (section.title.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "s-${section.key}") {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    section.subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            items(section.items, key = { it.id }) { item ->
                MediaCell(item, selecting, item.id in selection,
                    onClick = { if (selecting) onSelectionChange(selection.toggle(item.id)) else onOpen(item) },
                    onLongClick = { onSelectionChange(selection.toggle(item.id)) })
            }
        }
    }
}

fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
