package com.neutrino.photos.core.model

import com.neutrino.shared.core.api.DriveDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** `GET /api/v1/photos` — one photo record, exactly as the server sends it. */
@Serializable
data class PhotoDto(
    val id: String,
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long = 0,
    val contentUrl: String? = null,
    val thumbnailUrl: String? = null,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val captureDate: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class PhotoListDto(val photos: List<PhotoDto> = emptyList(), val total: Int = 0)

@Serializable
data class AlbumDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val isAuto: Boolean = false,
    val personId: String? = null,
    val photoCount: Int = 0,
    val coverPhotoId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class AlbumListDto(val albums: List<AlbumDto> = emptyList())

/**
 * A photograph or video in the library. The record is the Photos service's; the bytes are the Drive
 * file [fileId] names. [metadata] is kept as the server's JSON so a key this build does not model —
 * another client's — survives every read, and is only ever written back by merge (`PATCH`).
 */
data class MediaItem(
    val id: String,
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val thumbnailUrl: String?,
    val isStarred: Boolean,
    val isArchived: Boolean,
    val captureDate: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val metadata: JsonObject?,
) {
    enum class Kind { PHOTO, VIDEO, OTHER }

    val kind: Kind get() = when {
        mimeType.startsWith("image/") -> Kind.PHOTO
        mimeType.startsWith("video/") -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /** What the timeline sorts and groups by: when it was taken, or failing that when it arrived. */
    val timelineDate: Instant get() = captureDate ?: createdAt

    val displayName: String get() = fileName.substringBeforeLast('.').ifEmpty { fileName }

    val width: Int? get() = metadata.int("width")
    val height: Int? get() = metadata.int("height")
    val exif: JsonObject? get() = metadata?.get("exif") as? JsonObject
    val device: JsonObject? get() = metadata?.get("device") as? JsonObject
    val motionPhoto: JsonObject? get() = metadata?.get("motionPhoto") as? JsonObject

    /** The Drive file of an Apple Live Photo's clip, named on the still (`agent_docs/live-photos.md`). */
    val liveVideoFileId: String? get() = device.string("liveVideoFileID")
    val motionSubtype: String? get() = motionPhoto.string("subtype")
    val motionContentIdentifier: String? get() = motionPhoto.string("contentIdentifier")
    val isLivePhoto: Boolean get() = liveVideoFileId != null || device.bool("isLivePhoto") == true
    val isGoogleMotionPhoto: Boolean get() = motionSubtype == MOTION_GOOGLE && kind == Kind.PHOTO
    val isMotion: Boolean get() = isLivePhoto || isGoogleMotionPhoto

    fun daysUntilPurge(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val deleted = deletedAt ?: return null
        val due = LocalDate.ofInstant(deleted, zone).plusDays(TRASH_RETENTION_DAYS)
        return ChronoUnit.DAYS.between(LocalDate.ofInstant(now, zone), due).coerceAtLeast(0)
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
        const val MOTION_GOOGLE = "motion_photo_google"
        const val MOTION_APPLE = "live_photo_apple"

        fun of(dto: PhotoDto) = MediaItem(
            id = dto.id, fileId = dto.fileId, fileName = dto.fileName, mimeType = dto.mimeType, sizeBytes = dto.sizeBytes,
            thumbnailUrl = dto.thumbnailUrl, isStarred = dto.isStarred, isArchived = dto.isArchived,
            captureDate = DriveDate.parse(dto.captureDate),
            createdAt = DriveDate.parse(dto.createdAt) ?: Instant.EPOCH,
            updatedAt = DriveDate.parse(dto.updatedAt) ?: DriveDate.parse(dto.createdAt) ?: Instant.EPOCH,
            deletedAt = DriveDate.parse(dto.deletedAt), metadata = dto.metadata,
        )
    }
}

fun MediaItem.toDto() = PhotoDto(
    id = id, fileId = fileId, fileName = fileName, mimeType = mimeType, sizeBytes = sizeBytes, thumbnailUrl = thumbnailUrl,
    isStarred = isStarred, isArchived = isArchived, captureDate = captureDate?.let(DriveDate::wire), createdAt = DriveDate.wire(createdAt),
    updatedAt = DriveDate.wire(updatedAt), deletedAt = deletedAt?.let(DriveDate::wire), metadata = metadata,
)

data class Album(
    val id: String,
    val title: String,
    val description: String?,
    val isAuto: Boolean,
    val photoCount: Int,
    val coverPhotoId: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(dto: AlbumDto) = Album(dto.id, dto.title, dto.description, dto.isAuto, dto.photoCount, dto.coverPhotoId,
            DriveDate.parse(dto.updatedAt) ?: DriveDate.parse(dto.createdAt) ?: Instant.EPOCH)
    }
}

data class DeviceSession(
    val id: String,
    val deviceName: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val isCurrentDevice: Boolean,
)

internal fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject?.strings(key: String): List<String> =
    (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/** Public readers over a metadata document, for the info panel. */
object MetadataFields {
    fun string(o: JsonObject?, key: String) = o.string(key) ?: (o?.get(key) as? JsonPrimitive)?.contentOrNull
    fun double(o: JsonObject?, key: String) = o.double(key)
    fun int(o: JsonObject?, key: String) = o.int(key)
}
