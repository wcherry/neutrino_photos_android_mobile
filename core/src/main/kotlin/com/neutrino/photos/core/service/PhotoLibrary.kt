package com.neutrino.photos.core.service

import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.model.Album
import com.neutrino.photos.core.model.DeviceSession
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.core.model.PhotoDto
import com.neutrino.photos.core.model.toDto
import com.neutrino.shared.core.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The library as the app shows it: every live photo record (Live Photo clips included — the timeline
 * hides the claimed ones), the archive and the trash. Changes are applied here first and undone if
 * the server refuses, so a tap never waits on the network. The listing is kept on disk, so the
 * timeline draws from the last answer before the server gives this one — and still draws offline.
 */
class PhotoLibrary(private val api: PhotosApi, private val scope: CoroutineScope, private val cacheFile: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _items = MutableStateFlow(readCache())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()
    val archived = MutableStateFlow<List<MediaItem>>(emptyList())
    val trash = MutableStateFlow<List<MediaItem>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** The last refresh could not reach the server; what is shown is the device's copy. */
    val unreachable = MutableStateFlow(false)
    /** Bumps on every change, for anything that caches work derived from the library. */
    val revision = MutableStateFlow(0L)

    suspend fun load() {
        isLoading.value = true
        try {
            _items.value = pages { limit, offset -> api.photos(limit, offset) }
            writeCache()
            unreachable.value = false
            error.value = null
        } catch (e: ApiException.Network) {
            unreachable.value = true
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
            revision.update { it + 1 }
        }
    }

    suspend fun loadArchive() = guarded { archived.value = pages { limit, offset -> api.photos(limit, offset, archived = true) } }
    suspend fun loadTrash() = guarded { trash.value = api.trash().photos.map(MediaItem::of).sortedByDescending { it.deletedAt } }

    private suspend fun pages(fetch: suspend (Int, Int) -> com.neutrino.photos.core.model.PhotoListDto): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        var offset = 0
        while (true) {
            val page = fetch(PAGE, offset).photos
            page.mapTo(all, MediaItem::of)
            if (page.size < PAGE || offset > MAX_ITEMS) break
            offset += PAGE
        }
        return all
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block(); error.value = null } catch (e: ApiException.Network) { unreachable.value = true } catch (e: Exception) { error.value = e.message }
    }

    fun find(id: String): MediaItem? = _items.value.firstOrNull { it.id == id } ?: archived.value.firstOrNull { it.id == id } ?: trash.value.firstOrNull { it.id == id }

    /** A photo this device just imported, shown before the next refresh. */
    fun inserted(item: MediaItem) {
        _items.update { list -> listOf(item) + list.filter { it.id != item.id } }
        changed()
    }

    fun setStarred(ids: Collection<String>, starred: Boolean) = mutate(ids, { it.copy(isStarred = starred) }) { api.update(it, isStarred = starred) }

    /** Archiving takes a photo out of the timeline, into the archive. */
    fun setArchived(ids: Collection<String>, archive: Boolean): Job {
        val set = ids.toSet()
        val beforeItems = _items.value
        val beforeArchived = archived.value
        if (archive) {
            val moving = beforeItems.filter { it.id in set }.map { it.copy(isArchived = true) }
            _items.value = beforeItems.filter { it.id !in set }
            archived.value = moving + beforeArchived
        } else {
            val moving = beforeArchived.filter { it.id in set }.map { it.copy(isArchived = false) }
            archived.value = beforeArchived.filter { it.id !in set }
            _items.value = moving + beforeItems
        }
        changed()
        return scope.launch {
            val failed = set.filter { id -> runCatching { api.update(id, isArchived = archive) }.isFailure }
            if (failed.isNotEmpty()) { _items.value = beforeItems; archived.value = beforeArchived; error.value = "Couldn’t update ${failed.size} item(s)."; changed() }
        }
    }

    fun moveToTrash(ids: Collection<String>): Job {
        val set = ids.toSet()
        val beforeItems = _items.value
        val beforeArchived = archived.value
        val now = Instant.now()
        val moving = (beforeItems + beforeArchived).filter { it.id in set }.map { it.copy(deletedAt = now) }
        _items.value = beforeItems.filter { it.id !in set }
        archived.value = beforeArchived.filter { it.id !in set }
        trash.update { moving + it }
        changed()
        return scope.launch {
            val failed = set.filter { id -> runCatching { api.moveToTrash(id) }.isFailure }
            if (failed.isNotEmpty()) {
                _items.value = beforeItems; archived.value = beforeArchived; trash.update { l -> l.filter { it.id !in failed } }
                error.value = "Couldn’t delete ${failed.size} item(s)."; changed()
            }
        }
    }

    fun restore(ids: Collection<String>): Job {
        val set = ids.toSet()
        val before = trash.value
        trash.value = before.filter { it.id !in set }
        return scope.launch {
            for (id in set) {
                runCatching { api.restore(id) }
                    .onSuccess { dto -> val item = MediaItem.of(dto); if (item.isArchived) archived.update { listOf(item) + it } else _items.update { listOf(item) + it } }
                    .onFailure { e -> before.firstOrNull { it.id == id }?.let { item -> trash.update { listOf(item) + it } }; error.value = e.message }
            }
            changed()
        }
    }

    fun deletePermanently(ids: Collection<String>): Job {
        val set = ids.toSet()
        val before = trash.value
        trash.value = before.filter { it.id !in set }
        return scope.launch {
            val failed = set.filter { id -> runCatching { api.deletePermanently(id) }.isFailure }
            if (failed.isNotEmpty()) { trash.value = before.filter { it.id in failed } + trash.value; error.value = "Couldn’t delete ${failed.size} item(s)." }
        }
    }

    fun emptyTrash(): Job {
        val before = trash.value
        trash.value = emptyList()
        return scope.launch { runCatching { api.emptyTrash() }.onFailure { trash.value = before; error.value = it.message } }
    }

    /** A corrected capture date; it moves the photo in the timeline. */
    fun setCaptureDate(id: String, date: Instant) = mutate(listOf(id), { it.copy(captureDate = date) }) { api.update(it, captureDate = date) }

    private fun mutate(ids: Collection<String>, change: (MediaItem) -> MediaItem, call: suspend (String) -> PhotoDto): Job {
        val set = ids.toSet()
        val beforeItems = _items.value
        val beforeArchived = archived.value
        _items.value = beforeItems.map { if (it.id in set) change(it) else it }
        archived.value = beforeArchived.map { if (it.id in set) change(it) else it }
        changed()
        return scope.launch {
            val failed = set.filter { id -> runCatching { call(id) }.isFailure }
            if (failed.isNotEmpty()) { _items.value = beforeItems; archived.value = beforeArchived; error.value = "Couldn’t update ${failed.size} item(s)."; changed() }
            else writeCache()
        }
    }

    private fun changed() = revision.update { it + 1 }

    fun reset() {
        _items.value = emptyList(); archived.value = emptyList(); trash.value = emptyList()
        error.value = null; unreachable.value = false
        cacheFile.delete()
        changed()
    }

    private fun readCache(): List<MediaItem> = runCatching {
        json.decodeFromString(ListSerializer(PhotoDto.serializer()), cacheFile.readText()).map(MediaItem::of)
    }.getOrDefault(emptyList())

    private fun writeCache() {
        runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(json.encodeToString(ListSerializer(PhotoDto.serializer()), _items.value.map { it.toDto() })) }
    }

    companion object {
        const val PAGE = 1000
        private const val MAX_ITEMS = 200_000
    }
}

/** Albums: the list, and one album's photos on demand. */
class AlbumService(private val api: PhotosApi, private val scope: CoroutineScope) {
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    suspend fun load() {
        isLoading.value = true
        try { albums.value = api.albums().map(Album::of).sortedByDescending { it.updatedAt }; error.value = null }
        catch (e: Exception) { if (e !is ApiException.Network) error.value = e.message }
        finally { isLoading.value = false }
    }

    suspend fun items(albumId: String): List<MediaItem> = api.albumItems(albumId).map(MediaItem::of)

    suspend fun create(title: String, photoIds: Collection<String> = emptyList()): Album {
        val album = Album.of(api.createAlbum(title.trim()))
        photoIds.forEach { runCatching { api.addToAlbum(album.id, it) } }
        val withCount = album.copy(photoCount = photoIds.size)
        albums.update { listOf(withCount) + it }
        return withCount
    }

    fun rename(id: String, title: String) {
        val before = albums.value
        albums.update { l -> l.map { if (it.id == id) it.copy(title = title) else it } }
        scope.launch { runCatching { api.renameAlbum(id, title) }.onFailure { albums.value = before; error.value = it.message } }
    }

    fun delete(id: String) {
        val before = albums.value
        albums.update { l -> l.filter { it.id != id } }
        scope.launch { runCatching { api.deleteAlbum(id) }.onFailure { albums.value = before; error.value = it.message } }
    }

    /** Adds photos to an album; answers how many went in. */
    suspend fun add(albumId: String, photoIds: Collection<String>): Int {
        val added = photoIds.count { runCatching { api.addToAlbum(albumId, it) }.isSuccess }
        albums.update { l -> l.map { if (it.id == albumId) it.copy(photoCount = it.photoCount + added, coverPhotoId = it.coverPhotoId ?: photoIds.firstOrNull()) else it } }
        return added
    }

    suspend fun remove(albumId: String, photoId: String) {
        api.removeFromAlbum(albumId, photoId)
        albums.update { l -> l.map { if (it.id == albumId) it.copy(photoCount = (it.photoCount - 1).coerceAtLeast(0)) else it } }
    }

    fun reset() { albums.value = emptyList(); error.value = null }
}

class DeviceSessionService(private val api: PhotosApi, private val scope: CoroutineScope, private val currentDeviceName: () -> String) {
    val sessions = MutableStateFlow<List<DeviceSession>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    suspend fun load() {
        isLoading.value = true
        error.value = null
        try {
            sessions.value = api.sessions(currentDeviceName()).sortedWith(compareByDescending<DeviceSession> { it.isCurrentDevice }.thenByDescending { it.lastUsedAt ?: it.createdAt })
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
        }
    }

    fun revoke(id: String) {
        val before = sessions.value
        sessions.value = before.filter { it.id != id }
        scope.launch { runCatching { api.revokeSession(id) }.onFailure { sessions.value = before; error.value = it.message } }
    }
}
