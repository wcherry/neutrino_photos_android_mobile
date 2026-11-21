package com.neutrino.photos.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.neutrino.photos.core.media.ByteSource
import com.neutrino.photos.core.media.MediaFacts
import com.neutrino.photos.core.media.MotionPhoto
import com.neutrino.photos.core.media.Rendition
import com.neutrino.photos.core.service.ImportPipeline
import com.neutrino.photos.core.service.PreparedMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns what the picker, a share or the device library handed over into what the import pipeline
 * uploads: the plaintext staged in a file, the renditions, and the facts only the plaintext holds.
 * Everything here runs where the plaintext already is, because nowhere else can — the server holds
 * ciphertext.
 *
 * Bitmaps are decoded at the size wanted (`inSampleSize`), never whole, except for the one case
 * that must be whole: HEIC, converted to a full-resolution JPEG because most browsers cannot show
 * it — the iOS app's rule, so a picture stored here opens in the web app reading the same file.
 */
class MediaPreparer(private val context: Context, private val staging: File) {

    class Unsupported(message: String) : Exception(message)

    data class Source(val uri: Uri, val mediaStoreId: Long? = null, val isFavorite: Boolean = false, val dateTaken: Instant? = null)

    private val resolver: ContentResolver get() = context.contentResolver

    fun prepare(source: Source): PreparedMedia {
        staging.mkdirs()
        val (displayName, reportedMime) = describe(source.uri)
        val mime = reportedMime?.takeIf { it.startsWith("image/") || it.startsWith("video/") } ?: mimeFromName(displayName)
            ?: throw Unsupported("“$displayName” isn’t a photo or a video.")
        val stem = displayName.substringBeforeLast('.').ifBlank { "IMG_${System.currentTimeMillis()}" }
        val raw = File(staging, UUID.randomUUID().toString() + "." + extensionFor(mime))
        // With ACCESS_MEDIA_LOCATION, the original (GPS included) rather than a redacted copy.
        val readUri = if (source.mediaStoreId != null && Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.setRequireOriginal(source.uri) }.getOrDefault(source.uri) else source.uri
        (runCatching { resolver.openInputStream(readUri) }.getOrNull() ?: resolver.openInputStream(source.uri))?.use { input ->
            raw.outputStream().use { input.copyTo(it) }
        } ?: throw Unsupported("“$displayName” could not be read.")

        return try {
            if (mime.startsWith("video/")) prepareVideo(raw, stem, mime, source)
            else prepareImage(raw, stem, mime, source)
        } catch (e: Exception) {
            raw.delete()
            throw e
        }
    }

    // ── Images ──────────────────────────────────────────────────────────────────────────────────

    private fun prepareImage(raw: File, stem: String, mime: String, source: Source): PreparedMedia {
        // Of the bytes as they came: a HEIC is re-encoded below, and no two encodings need agree.
        val fingerprint = ImportPipeline.contentFingerprint(raw)
        val exif = runCatching { ExifInterface(raw) }.getOrNull()
        val facts = imageFacts(raw, mime, exif, source)
        var file = raw
        var storedMime = mime
        if (mime == "image/heic" || mime == "image/heif") {
            file = heicToJpeg(raw, exif)
            raw.delete()
            storedMime = "image/jpeg"
        }
        val orientation = if (file == raw) exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        else ExifInterface.ORIENTATION_NORMAL
        val thumbnail = rendition(file, Rendition.THUMBNAIL_PIXELS, Rendition.THUMBNAIL_QUALITY, orientation)
        val preview = rendition(file, Rendition.PREVIEW_PIXELS, Rendition.PREVIEW_QUALITY, orientation)
            ?.takeIf { Rendition.worthUploading(it.size.toLong(), file.length()) }
        val motion = if (storedMime == "image/jpeg") runCatching { MotionPhoto.read(ByteSource.OfFile(file)) }.getOrNull() else null
        return PreparedMedia(file, "${nameStem(stem, facts.captureDate, "IMG")}.${extensionFor(storedMime)}", storedMime, thumbnail, preview,
            facts.copy(motion = motion, format = formatOf(storedMime)), source.isFavorite, fingerprint)
    }

    private fun imageFacts(file: File, mime: String, exif: ExifInterface?, source: Source): MediaFacts {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var width = bounds.outWidth.takeIf { it > 0 }
        var height = bounds.outHeight.takeIf { it > 0 }
        if (exif?.isFlipped == true || exif?.rotationDegrees == 90 || exif?.rotationDegrees == 270) { val w = width; width = height; height = w }
        val latLong = exif?.latLong
        val original = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        return MediaFacts(
            width = width, height = height, format = formatOf(mime),
            make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.ifEmpty { null },
            model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.ifEmpty { null },
            lensModel = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null },
            exposureTime = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }?.let(::exposureText),
            fNumber = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 },
            iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            focalLength = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 },
            gpsLatitude = latLong?.get(0), gpsLongitude = latLong?.get(1),
            datetimeOriginal = original,
            captureDate = exifInstant(original, exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) ?: source.dateTaken,
            localIdentifier = source.mediaStoreId?.toString(),
        )
    }

    /** A JPEG of the picture at no more than [maxPixels] on its longest edge, upright. */
    private fun rendition(file: File, maxPixels: Int, quality: Int, orientation: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxPixels) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxPixels.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); orient(this, orientation) }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
        ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); bitmap.recycle(); out.toByteArray() }
    }.getOrNull()

    private fun heicToJpeg(raw: File, exif: ExifInterface?): File {
        if (Build.VERSION.SDK_INT < 28) throw Unsupported("HEIC photos need Android 9 or later.")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        val out = File(staging, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        // The decoder applied the rotation; the camera facts travel across, the orientation does not.
        if (exif != null) runCatching {
            val copy = ExifInterface(out)
            COPIED_TAGS.forEach { tag -> exif.getAttribute(tag)?.let { copy.setAttribute(tag, it) } }
            copy.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            copy.saveAttributes()
        }
        return out
    }

    // ── Videos ──────────────────────────────────────────────────────────────────────────────────

    private fun prepareVideo(file: File, stem: String, mime: String, source: Source): PreparedMedia {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (rotation == 90 || rotation == 270) { val w = width; width = height; height = w }
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709)
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::videoDate)
            val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, Rendition.THUMBNAIL_PIXELS, Rendition.THUMBNAIL_PIXELS)
            else retriever.frameAtTime
            val thumbnail = frame?.let { f ->
                val scale = minOf(1f, Rendition.THUMBNAIL_PIXELS.toFloat() / max(f.width, f.height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(f, (f.width * scale).roundToInt(), (f.height * scale).roundToInt(), true) else f
                ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, Rendition.THUMBNAIL_QUALITY, out); out.toByteArray() }
            }
            val facts = MediaFacts(width = width, height = height, format = formatOf(mime), gpsLatitude = location?.first, gpsLongitude = location?.second,
                captureDate = date ?: source.dateTaken, localIdentifier = source.mediaStoreId?.toString())
            return PreparedMedia(file, "${nameStem(stem, facts.captureDate, "VID")}.${extensionFor(mime)}", mime, thumbnail, null, facts, source.isFavorite)
        } finally {
            retriever.release()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun describe(uri: Uri): Pair<String, String?> {
        var name: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return (name ?: uri.lastPathSegment ?: "photo") to resolver.getType(uri)
    }

    companion object {
        private val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val CAMERA_NAME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /**
         * The system photo picker names what it hands over by a bare number (`1000000021`); such a
         * name says nothing in Drive or the web. It becomes what a camera would have called it,
         * from when it was taken — `IMG_20260310_093000`. A real name is kept as it is.
         */
        fun nameStem(stem: String, taken: Instant?, prefix: String): String =
            if (stem.isNotEmpty() && stem.all(Char::isDigit)) "${prefix}_" + CAMERA_NAME.format((taken ?: Instant.now()).atZone(ZoneId.systemDefault())) else stem

        fun mimeFromName(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("image/") || it.startsWith("video/") }

        fun extensionFor(mime: String) = when (mime) {
            "image/jpeg" -> "jpg"; "image/heic" -> "heic"; "image/heif" -> "heif"; "video/quicktime" -> "mov"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: mime.substringAfter('/')
        }

        fun formatOf(mime: String) = when (mime) { "image/jpeg" -> "jpeg"; "video/quicktime" -> "mov"; else -> mime.substringAfter('/') }

        /** EXIF times are wall-clock, in the zone the camera was in — given by `OffsetTimeOriginal` when written, else assumed to be this phone's. */
        fun exifInstant(raw: String?, offset: String?): Instant? {
            val local = runCatching { LocalDateTime.parse(raw?.trim(), EXIF_DATE) }.getOrNull() ?: return null
            val zoneOffset = offset?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
            return if (zoneOffset != null) local.toInstant(zoneOffset) else local.atZone(ZoneId.systemDefault()).toInstant()
        }

        /** `20240601T093000.000Z`, as `MediaMetadataRetriever` reports a video's date. */
        fun videoDate(raw: String): Instant? = runCatching {
            OffsetDateTime.parse(raw.replace(Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(\.\d+)?Z$"""), "$1-$2-$3T$4:$5:$6Z")).toInstant()
        }.getOrNull()?.takeIf { it.isAfter(Instant.parse("1971-01-01T00:00:00Z")) }

        /** ISO 6709, as a video's location is written: `+51.5007-000.1246/`. */
        fun iso6709(raw: String): Pair<Double, Double>? {
            val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(raw) ?: return null
            return (m.groupValues[1].toDoubleOrNull() ?: return null) to (m.groupValues[2].toDoubleOrNull() ?: return null)
        }

        fun exposureText(seconds: Double): String = if (seconds >= 1) "${(seconds * 10).roundToInt() / 10.0}".removeSuffix(".0") + "s"
            else "1/${(1 / seconds).roundToInt()}"

        fun orient(matrix: Matrix, orientation: Int) {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
        }
    }
}
