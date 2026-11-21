package com.neutrino.photos.core.api

import com.neutrino.photos.core.model.AlbumDto
import com.neutrino.photos.core.model.AlbumListDto
import com.neutrino.photos.core.model.DeviceSession
import com.neutrino.photos.core.model.PhotoDto
import com.neutrino.photos.core.model.PhotoListDto
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.NeutrinoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Two services, deliberately: the **library** is Photos (`/api/v1/photos`, `/api/v1/albums` — the
 * records, favourite and archive flags, albums), the **bytes** are Drive (download, key, upload,
 * folders — inherited from [NeutrinoApi]). An upload is therefore two calls: store the ciphertext,
 * then register it as a photo.
 */
class PhotosApi(http: OkHttpClient, auth: AuthClient) : NeutrinoApi(http, auth) {

    /** One page of the library, newest capture first. `archived` lists the archive instead. */
    suspend fun photos(limit: Int, offset: Int, archived: Boolean = false): PhotoListDto {
        val u = url("/photos").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("orderBy", "captureDate")
            .apply { if (archived) addQueryParameter("archivedOnly", "true") }.build()
        return json(PhotoListDto.serializer(), get(u))
    }

    suspend fun trash(): PhotoListDto = json(PhotoListDto.serializer(), get("/photos/trash"))

    suspend fun photo(id: String): PhotoDto = json(PhotoDto.serializer(), get("/photos/$id"))

    /** The records behind some Drive files, in one request — which of them are already photos. */
    suspend fun photosForFiles(fileIds: List<String>): List<PhotoDto> =
        json(PhotoListDto.serializer(), method("POST", "/photos/by-files", buildJsonObject { put("fileIds", JsonArray(fileIds.map(::JsonPrimitive))) })).photos

    /**
     * Registers an uploaded Drive file as a photo. Idempotent per file: a file already registered
     * answers with its existing record and [metadata] is *not* applied — merge with [patchMetadata].
     */
    suspend fun register(fileId: String, captureDate: Instant?, metadata: JsonObject? = null): PhotoDto =
        json(PhotoDto.serializer(), method("POST", "/photos", buildJsonObject {
            put("fileId", fileId)
            captureDate?.let { put("captureDate", captureDateWire(it)) }
            metadata?.let { put("metadata", it) }
        }))

    suspend fun update(id: String, isStarred: Boolean? = null, isArchived: Boolean? = null, captureDate: Instant? = null): PhotoDto =
        json(PhotoDto.serializer(), method("PATCH", "/photos/$id", buildJsonObject {
            isStarred?.let { put("isStarred", it) }
            isArchived?.let { put("isArchived", it) }
            captureDate?.let { put("captureDate", DriveDate.wire(it)) }
        }))

    /** Merges [patch] into the record's metadata (RFC 7396). Never a whole-document write — `live-photos.md` rule 4. */
    suspend fun patchMetadata(id: String, patch: JsonObject) { text(method("PATCH", "/photos/$id/metadata", patch)) }

    suspend fun moveToTrash(id: String) { text(method("DELETE", "/photos/$id", null as String?)) }
    suspend fun restore(id: String): PhotoDto = json(PhotoDto.serializer(), method("POST", "/photos/$id/restore", null as String?))
    suspend fun deletePermanently(id: String) { text(method("DELETE", "/photos/$id/permanent", null as String?)) }
    suspend fun emptyTrash() { text(method("DELETE", "/photos/trash", null as String?)) }

    // ── Albums ──────────────────────────────────────────────────────────────────────────────────

    suspend fun albums(): List<AlbumDto> = json(AlbumListDto.serializer(), get("/albums")).albums
    suspend fun createAlbum(title: String): AlbumDto = json(AlbumDto.serializer(), method("POST", "/albums", buildJsonObject { put("title", title) }))
    suspend fun renameAlbum(id: String, title: String): AlbumDto = json(AlbumDto.serializer(), method("PATCH", "/albums/$id", buildJsonObject { put("title", title) }))
    suspend fun deleteAlbum(id: String) { text(method("DELETE", "/albums/$id", null as String?)) }
    suspend fun albumItems(id: String): List<PhotoDto> = json(PhotoListDto.serializer(), get("/albums/$id/items")).photos
    suspend fun addToAlbum(albumId: String, photoId: String) { text(method("POST", "/albums/$albumId/items", buildJsonObject { put("photoId", photoId) })) }
    suspend fun removeFromAlbum(albumId: String, photoId: String) { text(method("DELETE", "/albums/$albumId/items/$photoId", null as String?)) }

    // ── Drive ───────────────────────────────────────────────────────────────────────────────────

    /** The plaintext cover thumbnail a record's `thumbnailUrl` names (`/api/v1/…`), or null when there is none. */
    suspend fun thumbnail(serverPath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            send { okhttp3.Request.Builder().url(absolute(serverPath)).get() }.use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        } catch (e: ApiException.Http) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun folderPage(folderId: String, limit: Int, offset: Int, type: String? = null): FolderContentsDto {
        val u = url("/drive/folders/$folderId").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .apply { type?.let { addQueryParameter("type", it) } }.build()
        return json(FolderContentsDto.serializer(), get(u))
    }

    suspend fun sessions(currentDeviceName: String): List<DeviceSession> =
        json(SessionsDto.serializer(), get("/auth/sessions")).sessions.map {
            DeviceSession(it.id, it.deviceName, it.userAgent, DriveDate.parse(it.createdAt) ?: Instant.EPOCH, DriveDate.parse(it.lastUsedAt), it.deviceName == currentDeviceName)
        }

    suspend fun revokeSession(id: String) { text(method("DELETE", "/auth/sessions/$id", null as String?)) }

    companion object {
        private val NAIVE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * `POST /photos` parses `%Y-%m-%dT%H:%M:%S` and nothing else — a `Z` or fractional seconds and
         * the date is dropped in silence. UTC, as every response reports it and as iOS sends it.
         */
        fun captureDateWire(instant: Instant): String = NAIVE.format(instant.atOffset(ZoneOffset.UTC))
    }
}

@Serializable
internal data class SessionsDto(val sessions: List<SessionDto> = emptyList())

@Serializable
internal data class SessionDto(val id: String, val deviceName: String? = null, val userAgent: String? = null, val createdAt: String? = null, val lastUsedAt: String? = null)
