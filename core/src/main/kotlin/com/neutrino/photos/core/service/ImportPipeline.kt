package com.neutrino.photos.core.service

import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.media.MediaFacts
import com.neutrino.photos.core.media.Rendition
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.shared.core.crypto.B64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One item made ready by the app — the plaintext staged in a file, and what only Android can make
 * from it (the renditions, the EXIF) — for [ImportPipeline] to take the rest of the way.
 */
data class PreparedMedia(
    val file: File,
    val fileName: String,
    val mimeType: String,
    /** The 512 px plaintext cover, JPEG. Null draws a blank tile everywhere, so the app always tries. */
    val thumbnailJpeg: ByteArray?,
    /** The 2048 px preview, JPEG, when worth uploading ([Rendition.worthUploading]). */
    val previewJpeg: ByteArray?,
    val facts: MediaFacts,
    /** The device library's favourite flag. */
    val isFavorite: Boolean = false,
    /** [ImportPipeline.contentFingerprint] of the bytes as they came, before any conversion; null to take it from [file]. */
    val fingerprint: String? = null,
)

/**
 * What this device has already uploaded, by content hash and by `MediaStore` id, so picking the same
 * photo twice — or a backup run after an import — uploads nothing the second time.
 */
class ImportLedger(private val file: File) {
    @Serializable
    private data class Entries(val byHash: Map<String, String> = emptyMap(), val byLocalId: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private var entries: Entries = runCatching { json.decodeFromString(Entries.serializer(), file.readText()) }.getOrDefault(Entries())

    @Synchronized fun fileFor(hash: String?, localId: String?): String? = hash?.let(entries.byHash::get) ?: localId?.let(entries.byLocalId::get)
    @Synchronized fun knows(localId: String) = localId in entries.byLocalId

    @Synchronized fun record(hash: String, localId: String?, fileId: String) {
        entries = Entries(entries.byHash + (hash to fileId), if (localId != null) entries.byLocalId + (localId to fileId) else entries.byLocalId)
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(Entries.serializer(), entries)) }
    }

    val count: Int @Synchronized get() = entries.byHash.size

    @Synchronized fun clear() { entries = Entries(); file.delete() }
}

/**
 * One item end to end: de-duplicate → upload (encrypted, cover in the clear) → register as a photo →
 * publish metadata by merge → favourite → upload the preview rendition. The photograph is what
 * matters: once it is uploaded and registered nothing after it fails the import — a metadata entry
 * or a preview that did not make it costs speed and search, not the picture.
 */
class ImportPipeline(
    private val api: PhotosApi,
    private val uploader: Uploader,
    private val content: MediaContentService,
    private val ledger: ImportLedger,
    private val publishesLocation: () -> Boolean,
) {
    private val log = Logger.getLogger("ImportPipeline")

    sealed class Outcome {
        data class Imported(val item: MediaItem) : Outcome()
        data class Duplicate(val fileId: String) : Outcome()
    }

    /**
     * One import at a time, ledger check included: the picker, a share and a backup run can all be
     * handed the same photo at once, and two of them each finding it new is how it got uploaded twice.
     */
    private val lock = Mutex()

    suspend fun import(media: PreparedMedia, progress: (Long, Long) -> Unit = { _, _ -> }): Outcome = lock.withLock { importLocked(media, progress) }

    private suspend fun importLocked(media: PreparedMedia, progress: (Long, Long) -> Unit): Outcome {
        val hash = media.fingerprint ?: contentFingerprint(media.file)
        ledger.fileFor(hash, media.facts.localIdentifier)?.let { known ->
            // Known by its bytes (picked earlier) and now met by its MediaStore id: remember both, so a backup run skips it without hashing it again.
            media.facts.localIdentifier?.let { if (!ledger.knows(it)) ledger.record(hash, it, known) }
            return Outcome.Duplicate(known)
        }

        // No folder: a photograph lands in the Drive root, where the web's library listing looks.
        val created = uploader.upload(UploadSource.of(media.file, media.fileName, media.mimeType), folderId = null,
            thumbnailBase64 = media.thumbnailJpeg?.let(B64::encode), progress = progress)
        media.thumbnailJpeg?.let { content.keepThumbnail(created.id, it) }

        var record = MediaItem.of(api.register(created.id, media.facts.captureDate))
        ledger.record(hash, media.facts.localIdentifier, created.id)

        val patch = media.facts.patch(publishesLocation())
        if (patch.isNotEmpty()) {
            runCatching { api.patchMetadata(record.id, patch) }
                .onSuccess { record = record.copy(metadata = media.facts.document(publishesLocation())) }
                .onFailure { log.warning("metadata publish failed for ${record.id}: ${it.message}") }
        }
        if (media.isFavorite && !record.isStarred) {
            runCatching { api.update(record.id, isStarred = true) }.onSuccess { record = record.copy(isStarred = true) }
        }
        content.keepOriginal(record, media.file)
        media.previewJpeg?.let { uploadPreview(created.id, it) }
        return Outcome.Imported(record)
    }

    private suspend fun uploadPreview(originalFileId: String, jpeg: ByteArray) {
        try {
            val folder = content.previewsFolder(create = true) ?: return
            val preview = uploader.upload(UploadSource.of(jpeg, Rendition.previewName(originalFileId), "image/jpeg"), folder)
            content.rememberPreview(originalFileId, preview.id)
            content.keepPreview(originalFileId, jpeg)
        } catch (e: Exception) {
            log.warning("preview rendition failed for $originalFileId: ${e.message}")
        }
    }

    companion object {
        /**
         * What makes two files the same photo. For a JPEG, the image data from start-of-scan on, and
         * not the metadata before it: the system photo picker hands over a copy with its location
         * removed, and a photo picked once and then met again by a backup run must still be found.
         * Anything else is hashed whole.
         */
        fun contentFingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val start = jpegScanOffset(file) ?: 0L
            java.io.RandomAccessFile(file, "r").use { f ->
                f.seek(start)
                val buf = ByteArray(64 * 1024)
                while (true) { val n = f.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Where a JPEG's entropy-coded data begins (its `FFDA` marker), or null for anything that is not a JPEG. */
        fun jpegScanOffset(file: File): Long? = java.io.RandomAccessFile(file, "r").use { f ->
            fun u8(): Int = f.read().also { if (it < 0) throw java.io.EOFException() }
            try {
                if (u8() != 0xff || u8() != 0xd8) return null
                while (true) {
                    if (u8() != 0xff) return null
                    var marker = u8()
                    while (marker == 0xff) marker = u8()
                    if (marker == 0xda) return f.filePointer - 2
                    if (marker == 0xd8 || marker in 0xd0..0xd9) continue
                    val length = (u8() shl 8) or u8()
                    if (length < 2) return null
                    f.seek(f.filePointer + length - 2)
                }
                @Suppress("UNREACHABLE_CODE") null
            } catch (_: java.io.EOFException) {
                null
            }
        }
    }
}
