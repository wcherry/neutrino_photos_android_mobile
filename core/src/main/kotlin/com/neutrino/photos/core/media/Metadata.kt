package com.neutrino.photos.core.media

import com.neutrino.photos.core.model.MediaItem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * What the device read out of a picture before encrypting it. Photo content is E2EE, so the server
 * never sees plaintext and extracts nothing itself: everything the library knows about the bytes —
 * dimensions, camera, where, whether it moves — arrives here, from the client that held them.
 */
data class MediaFacts(
    val width: Int? = null,
    val height: Int? = null,
    /** "jpeg", "heic", "png", "mp4" … — the web's `format`. */
    val format: String? = null,
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    /** "1/120" — kept as text, as cameras write it. */
    val exposureTime: String? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    /** EXIF `DateTimeOriginal` as written ("2024:06:01 09:30:00"). */
    val datetimeOriginal: String? = null,
    /** When it was taken, resolved to an instant; the record's capture date. */
    val captureDate: Instant? = null,
    val motion: MotionPhoto.Info? = null,
    /** The `MediaStore` id on the importing device, for `device.localIdentifier`. */
    val localIdentifier: String? = null,
) {
    val hasLocation get() = gpsLatitude != null && gpsLongitude != null

    /**
     * The record's `metadata` document, in the shape the web (`PhotoMetadata`) and iOS
     * (`MediaMetadata`) write. Coordinates are left out unless [includeLocation]: the server stores
     * this in the clear and `GET /photos/map` reads exactly those two keys, so publishing them is a
     * list of where somebody has been, beside a library the server otherwise cannot open.
     */
    fun document(includeLocation: Boolean): JsonObject = buildJsonObject {
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        format?.let { put("format", it) }
        val exif = buildJsonObject {
            make?.let { put("make", it) }
            model?.let { put("model", it) }
            lensModel?.let { put("lensModel", it) }
            exposureTime?.let { put("exposureTime", it) }
            fNumber?.let { put("fNumber", it) }
            iso?.let { put("iso", it) }
            focalLength?.let { put("focalLength", it) }
            if (includeLocation) {
                gpsLatitude?.let { put("gpsLatitude", it) }
                gpsLongitude?.let { put("gpsLongitude", it) }
            }
            datetimeOriginal?.let { put("datetimeOriginal", it) }
        }
        if (exif.isNotEmpty()) put("exif", exif)
        motion?.let { m ->
            // The web's `MotionPhotoInfo`, key for key.
            putJsonObject("motionPhoto") {
                put("subtype", MediaItem.MOTION_GOOGLE)
                put("signal", m.signal)
                m.presentationTimestampUs?.let { put("presentationTimestampUs", it) }
                m.embedded?.let { e -> putJsonObject("embedded") { put("offset", e.offset); put("length", e.length) } }
            }
        }
        localIdentifier?.let { id -> putJsonObject("device") { put("localIdentifier", id) } }
    }

    /**
     * The merge patch to publish: the document, plus `null`s removing coordinates a previous publish
     * may have left, when location is not to be shared (RFC 7396: `null` deletes a key).
     */
    fun patch(includeLocation: Boolean): JsonObject {
        val doc = document(includeLocation)
        if (includeLocation) return doc
        val exif = (doc["exif"] as? JsonObject).orEmpty() + mapOf("gpsLatitude" to JsonNull, "gpsLongitude" to JsonNull)
        return JsonObject(doc + ("exif" to JsonObject(exif)))
    }

    val isEmpty get() = document(true).isEmpty()
}

/** Renditions, as iOS `MediaRendition`: the ladder every client shares. */
object Rendition {
    /** 512 px, JPEG 0.8 — the web's `generateThumbnail`, stored in the clear on the Drive file. */
    const val THUMBNAIL_PIXELS = 512
    const val THUMBNAIL_QUALITY = 80
    /** 2048 px, JPEG 0.85 — encrypted, a second Drive file in "Photo Previews". */
    const val PREVIEW_PIXELS = 2048
    const val PREVIEW_QUALITY = 85
    /** A preview is uploaded only when it comes in under this fraction of the original. */
    const val PREVIEW_SAVING_THRESHOLD = 0.7

    const val PREVIEWS_FOLDER = "Photo Previews"

    /** The name is the index: another device finds a preview by listing the folder. Changing it orphans every preview. */
    fun previewName(originalFileId: String) = "$originalFileId.preview.jpg"

    fun originalOf(previewName: String): String? {
        val parts = previewName.split('.')
        return if (parts.size == 3 && parts[1] == "preview" && parts[2] == "jpg") parts[0] else null
    }

    fun worthUploading(previewBytes: Long, originalBytes: Long) = previewBytes < originalBytes * PREVIEW_SAVING_THRESHOLD
}
