package com.neutrino.photos.core.service

import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.SecretStream
import com.neutrino.shared.core.keys.DeviceKeyCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream

/** Something to upload: its name, plaintext type and exact size, and a way to read it. */
class UploadSource(val name: String, val mimeType: String, val length: Long, val open: () -> InputStream) {
    companion object {
        fun of(file: File, name: String, mimeType: String) = UploadSource(name, mimeType, file.length()) { file.inputStream() }
        fun of(bytes: ByteArray, name: String, mimeType: String) = UploadSource(name, mimeType, bytes.size.toLong()) { bytes.inputStream() }
    }
}

/**
 * Encrypt and upload one file, as Drive for Android does: a fresh DEK, the body as NEB1 (neutrino#318)
 * streamed a chunk at a time into a temp file, the name and type encrypted beside it, the DEK sealed
 * to the account's current public key and sent in the same request — then the same key `PUT` again,
 * which is an upsert, for a server that ignored it in the body. A file with no key ref is unreadable
 * by every client, so that second call is allowed to throw.
 *
 * The cover thumbnail is the one thing sent in the clear, by design: the server cannot make one from
 * ciphertext, and the grid has to draw before any key is unlocked — as the web and iOS send it.
 */
class Uploader(private val api: NeutrinoApi, private val keyCheck: DeviceKeyCheck, private val tempDir: File) {

    suspend fun upload(
        source: UploadSource,
        folderId: String?,
        thumbnailBase64: String? = null,
        progress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): FileDto = withContext(Dispatchers.IO) {
        val (active, version) = keyCheck.sealingVersion()
        val dek = FileCrypto.generateDek()
        tempDir.mkdirs()
        val cipher = File.createTempFile("np-upload-", ".bin", tempDir)
        try {
            cipher.outputStream().buffered().use { out -> source.open().use { SecretStream.encryptNeb1(it, out, dek, source.length) } }
            val metadata = FileCrypto.encryptMetadata(source.name, source.mimeType, dek)
            val sealed = FileCrypto.sealDek(dek, active.publicKey)
            dek.fill(0)
            val body = multipart(metadata, folderId, thumbnailBase64, sealed, version, source.name, source.mimeType,
                ProgressFileBody(cipher, "application/octet-stream".toMediaTypeOrNull(), progress))
            val created = api.upload(body)
            api.setFileKey(created.id, sealed, version)
            created
        } finally {
            dek.fill(0)
            cipher.delete()
        }
    }

    companion object {
        /** **Part order is load-bearing**: the server stops reading at the file part, so every field precedes it. */
        fun multipart(
            encryptedMetadata: String, folderId: String?, thumbnailBase64: String?, sealedFileKey: String, keyVersion: Int,
            fileName: String, mimeType: String, content: RequestBody,
        ): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("encrypted_metadata", encryptedMetadata)
            folderId?.let { addFormDataPart("folder_id", it) }
            thumbnailBase64?.let { addFormDataPart("thumbnail_b64", it) }
            addFormDataPart("encrypted_file_key", sealedFileKey)
            addFormDataPart("key_version", keyVersion.toString())
            // The blob's part carries the *plaintext* type, which the server stores on the row.
            addFormDataPart("file", fileName, TypedBody(content, mimeType.toMediaTypeOrNull()))
        }.build()
    }
}

class ProgressFileBody(private val file: File, private val type: MediaType?, private val onProgress: (Long, Long) -> Unit) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val n = source.read(buffer, 64 * 1024)
                if (n < 0) break
                sink.write(buffer, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}

private class TypedBody(private val inner: RequestBody, private val type: MediaType?) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = inner.contentLength()
    override fun writeTo(sink: BufferedSink) = inner.writeTo(sink)
}
