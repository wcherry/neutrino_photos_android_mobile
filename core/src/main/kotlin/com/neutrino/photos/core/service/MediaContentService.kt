package com.neutrino.photos.core.service

import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.media.Rendition
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.openDek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

sealed class MediaException(message: String) : Exception(message) {
    class MissingKeyVersion(val version: Int) : MediaException(
        "This photo needs encryption key version $version, which this device does not have. On a device that holds your key, " +
            "open Settings › Encryption and back up your older keys, then reopen this app.")
    class DecryptionFailed : MediaException("This photo could not be decrypted — your key may not match this account, or the file is damaged.")
    class NoMotion : MediaException("This photo has no motion to play.")
}

/**
 * Bytes on the device, decrypted, in three places with three lifetimes:
 *
 * - `thumbs/` — the plaintext 512 px covers the grid draws. Small; kept until cleared.
 * - `media/` — decrypted originals, previews and motion clips. Capped at [capacityBytes] and evicted
 *   least recently used, because a library is bigger than a phone.
 *
 * Everything decrypts through the shared reader ([FileCrypto.decryptFile]), which handles v0, NEB1
 * and the chunked Photos shape alike — never a decrypt of this app's own.
 */
class MediaContentService(
    private val api: PhotosApi,
    private val keyring: () -> Keyring?,
    private val root: File,
    var capacityBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    private val log = Logger.getLogger("MediaContentService")
    private val thumbs = File(root, "thumbs")
    private val media = File(root, "media")
    private val indexFile = File(root, "previews.json")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val folderLock = Mutex()
    private var previewsFolderId: String? = null

    /** Original Drive file id → its preview rendition's file id. Persisted; refreshed from Drive. */
    @Volatile var previews: Map<String, String> = readIndex()
        private set

    private fun lock(key: String) = locks.getOrPut(key) { Mutex() }

    // ── Thumbnails ──────────────────────────────────────────────────────────────────────────────

    fun cachedThumbnail(item: MediaItem): File? = File(thumbs, "${item.fileId}.jpg").takeIf { it.length() > 0 }

    /** The cover thumbnail as JPEG bytes, from disk or the server; null when the file has none. */
    suspend fun thumbnail(item: MediaItem): File? {
        cachedThumbnail(item)?.let { return it }
        val url = item.thumbnailUrl ?: return null
        return lock("t:${item.fileId}").withLock {
            cachedThumbnail(item) ?: api.thumbnail(url)?.let { bytes -> store(File(thumbs, "${item.fileId}.jpg"), bytes) }
        }
    }

    /** What an import already had in hand, so the photo just uploaded draws without a download. */
    fun keepThumbnail(fileId: String, jpeg: ByteArray) { runCatching { store(File(thumbs, "$fileId.jpg"), jpeg) } }

    // ── Originals and previews ──────────────────────────────────────────────────────────────────

    fun cachedOriginal(item: MediaItem): File? = File(media, originalName(item)).takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }
    fun cachedPreview(item: MediaItem): File? = File(media, "${item.fileId}-preview.jpg").takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }

    /** The decrypted original, downloaded once. */
    suspend fun original(item: MediaItem): File = lock("o:${item.fileId}").withLock {
        cachedOriginal(item) ?: download(item.fileId, File(media, originalName(item))).also { trim() }
    }

    /**
     * What the viewer opens at: the 2048 px preview when one was uploaded, else the original. A
     * preview is never required — anything missing one falls back to the step above it.
     */
    suspend fun viewable(item: MediaItem): File {
        if (item.kind != MediaItem.Kind.PHOTO) return original(item)
        cachedPreview(item)?.let { return it }
        cachedOriginal(item)?.let { return it }
        val previewId = previews[item.fileId] ?: return original(item)
        return lock("p:${item.fileId}").withLock {
            cachedPreview(item) ?: runCatching { download(previewId, File(media, "${item.fileId}-preview.jpg")) }.getOrElse {
                log.warning("preview ${item.fileId} failed (${it.message}); opening the original")
                null
            }
        }?.also { trim() } ?: original(item)
    }

    fun keepPreview(fileId: String, jpeg: ByteArray) { runCatching { store(File(media, "$fileId-preview.jpg"), jpeg) } }
    fun keepOriginal(item: MediaItem, file: File) { runCatching { file.copyTo(File(media, originalName(item)), overwrite = true) } }

    /**
     * A playable clip for a photo that moves: an Apple Live Photo's separate clip, downloaded, or a
     * Google Motion Photo's MP4 sliced out of the still's own bytes at the recorded offset. Nothing is
     * re-encoded and the original is never rewritten.
     */
    suspend fun motionClip(item: MediaItem, clip: MediaItem?): File {
        if (clip != null) return original(clip)
        if (!item.isGoogleMotionPhoto) throw MediaException.NoMotion()
        val out = File(media, "${item.fileId}-motion.mp4")
        if (out.exists()) return out
        val still = original(item)
        val embedded = item.motionPhoto?.get("embedded") as? JsonObject
        val offset = (embedded?.get("offset") as? JsonPrimitive)?.longOrNull
            ?: com.neutrino.photos.core.media.MotionPhoto.read(com.neutrino.photos.core.media.ByteSource.OfFile(still))?.embedded?.offset
            ?: throw MediaException.NoMotion()
        return withContext(Dispatchers.IO) {
            val partial = File(media, ".${out.name}.part")
            RandomAccessFile(still, "r").use { src ->
                src.seek(offset)
                partial.outputStream().buffered().use { dst ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = src.read(buf); if (n < 0) break; dst.write(buf, 0, n) }
                }
            }
            partial.renameTo(out)
            out
        }
    }

    private suspend fun download(fileId: String, destination: File): File = withContext(Dispatchers.IO) {
        val ref = api.fileKey(fileId)
        val dek = ref?.let {
            val ring = keyring() ?: throw KeyException.NoEncryptionKey()
            val named = it.keyVersion ?: 1
            val (dek, openedWith) = ring.openDek(it.encryptedFileKey, named)
                ?: throw if (ring.entry(named) == null) MediaException.MissingKeyVersion(named) else MediaException.DecryptionFailed()
            if (openedWith != named) runCatching { api.setFileKey(fileId, it.encryptedFileKey, openedWith) }
            dek
        }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.part")
        try {
            api.openBlob(fileId).use { response ->
                val body = response.body ?: throw ApiException.Decode("empty body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    partial.outputStream().buffered().use { out ->
                        // No key ref: a picture uploaded before E2EE, which the web serves as it stands too.
                        if (dek == null) input.copyTo(out)
                        else if (!FileCrypto.decryptFile(input, out, dek, totalLength = total.takeIf { it >= 0 })) throw MediaException.DecryptionFailed()
                    }
                }
            }
            if (!partial.renameTo(destination)) throw java.io.IOException("Could not save ${destination.name}")
            destination
        } finally {
            dek?.fill(0)
            partial.delete()
        }
    }

    // ── The preview rendition index ─────────────────────────────────────────────────────────────

    /** The "Photo Previews" folder in the Drive root, found by name and created on first use. */
    suspend fun previewsFolder(create: Boolean): String? = folderLock.withLock {
        previewsFolderId?.let { return it }
        val root = api.rootFolderId()
        var offset = 0
        while (offset < PAGE * MAX_PAGES) {
            val page = api.folderPage(root, PAGE, offset)
            page.folders.firstOrNull { it.name == Rendition.PREVIEWS_FOLDER }?.let { previewsFolderId = it.id; return it.id }
            if (page.folders.size < PAGE) break
            offset += PAGE
        }
        if (!create) return null
        api.createFolder(Rendition.PREVIEWS_FOLDER, null).id.also { previewsFolderId = it }
    }

    /** Re-reads which originals have a preview, by listing the folder and parsing the names. */
    suspend fun refreshPreviewIndex() {
        try {
            val folder = previewsFolder(create = false) ?: return
            val found = HashMap<String, String>()
            var offset = 0
            while (offset < 1000 * MAX_PAGES) {
                val page = api.folderPage(folder, 1000, offset)
                page.files.forEach { f -> Rendition.originalOf(f.name)?.let { found[it] = f.id } }
                if (page.files.size < 1000) break
                offset += 1000
            }
            previews = found
            writeIndex()
        } catch (e: Exception) {
            // A missing index costs speed, not correctness: every read falls back to the original.
            log.warning("preview index refresh failed: ${e.message}")
        }
    }

    fun rememberPreview(originalFileId: String, previewFileId: String) {
        previews = previews + (originalFileId to previewFileId)
        writeIndex()
    }

    private fun readIndex(): Map<String, String> = runCatching {
        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), indexFile.readText())
    }.getOrDefault(emptyMap())

    private fun writeIndex() {
        runCatching { root.mkdirs(); indexFile.writeText(Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), previews)) }
    }

    // ── Housekeeping ────────────────────────────────────────────────────────────────────────────

    /** Drops the least recently used media past [capacityBytes]. Thumbnails are never evicted here. */
    fun trim() {
        val files = media.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= capacityBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= capacityBytes) break
            total -= f.length()
            f.delete()
        }
    }

    data class Usage(val originals: Long, val previews: Long, val thumbnails: Long)

    fun usage(): Usage {
        val m = media.listFiles().orEmpty()
        val previewsSize = m.filter { it.name.endsWith("-preview.jpg") }.sumOf { it.length() }
        return Usage(m.sumOf { it.length() } - previewsSize, previewsSize, thumbs.listFiles().orEmpty().sumOf { it.length() })
    }

    /** Optimize Storage: drop originals and clips, keep the previews that make opening fast. */
    fun removeOriginals(): Long {
        val gone = media.listFiles().orEmpty().filter { !it.name.endsWith("-preview.jpg") }
        return gone.sumOf { it.length() }.also { gone.forEach(File::delete) }
    }

    fun clear() {
        media.deleteRecursively()
        thumbs.deleteRecursively()
        indexFile.delete()
        previews = emptyMap()
        previewsFolderId = null
    }

    private fun store(file: File, bytes: ByteArray): File {
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, ".${file.name}.part")
        partial.writeBytes(bytes)
        partial.renameTo(file)
        return file
    }

    companion object {
        private const val PAGE = 200
        private const val MAX_PAGES = 50

        fun extension(fileName: String, mimeType: String): String =
            fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
                ?: when (mimeType) {
                    "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/heic" -> "heic"; "image/webp" -> "webp"; "image/gif" -> "gif"
                    "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; else -> "bin"
                }

        fun originalName(item: MediaItem) = "${item.fileId}.${extension(item.fileName, item.mimeType)}"
    }
}
