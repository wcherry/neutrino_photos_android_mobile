package com.neutrino.photos.core

import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.media.MediaFacts
import com.neutrino.photos.core.media.MotionPhoto
import com.neutrino.photos.core.media.Rendition
import com.neutrino.photos.core.model.MediaItem
import com.neutrino.photos.core.service.AlbumService
import com.neutrino.photos.core.service.ImportLedger
import com.neutrino.photos.core.service.ImportPipeline
import com.neutrino.photos.core.service.MediaContentService
import com.neutrino.photos.core.service.PhotoLibrary
import com.neutrino.photos.core.service.PreparedMedia
import com.neutrino.photos.core.service.Uploader
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * The services against an in-memory server holding real ciphertext. The shared [FakeServer] is
 * Drive; the Photos service — records, metadata merge, trash, albums — is faked here, in the
 * server's response shapes.
 */
class ServicesTest {
    private lateinit var fake: FakeServer
    private lateinit var tmp: File
    private lateinit var api: PhotosApi
    private lateinit var keys: InMemoryKeyringStore
    private lateinit var check: DeviceKeyCheck
    private lateinit var content: MediaContentService
    private lateinit var pipeline: ImportPipeline
    private lateinit var library: PhotoLibrary
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var shareLocation = false

    // ── The fake Photos service ────────────────────────────────────────────────────────────────
    private val records = LinkedHashMap<String, JsonObject>()
    private val albums = LinkedHashMap<String, Pair<String, MutableList<String>>>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun route(r: RecordedRequest): MockResponse? {
        val path = r.requestUrl!!.encodedPath
        val body by lazy { r.body.readUtf8().let { if (it.isEmpty()) JsonObject(emptyMap()) else json.parseToJsonElement(it).jsonObject } }
        fun list(items: Collection<JsonObject>) = fake.ok(buildJsonObject { put("photos", buildJsonArray { items.forEach { add(it) } }); put("total", items.size) }.toString())
        Regex("/api/v1/drive/files/([^/]+)/thumbnail").matchEntire(path)?.let { m ->
            val t = fake.files[m.groupValues[1]]?.thumbnail ?: return fake.status(404)
            return MockResponse().setResponseCode(200).setBody(Buffer().write(B64.decode(t)!!))
        }
        when {
            path == "/api/v1/photos" && r.method == "GET" -> {
                val archived = r.requestUrl!!.queryParameter("archivedOnly") == "true"
                val limit = r.requestUrl!!.queryParameter("limit")!!.toInt()
                val offset = r.requestUrl!!.queryParameter("offset")!!.toInt()
                return list(records.values.filter { it["deletedAt"] is JsonNull && it.bool("isArchived") == archived }.drop(offset).take(limit))
            }
            path == "/api/v1/photos" && r.method == "POST" -> {
                val fileId = body["fileId"]!!.jsonPrimitive.content
                records.values.firstOrNull { it.str("fileId") == fileId }?.let { return fake.ok(it.toString()) }
                val f = fake.files[fileId] ?: return fake.status(404)
                val id = UUID.randomUUID().toString()
                records[id] = buildJsonObject {
                    put("id", id); put("fileId", fileId); put("fileName", f.name); put("mimeType", f.mimeType); put("sizeBytes", f.blob.size)
                    put("contentUrl", "/api/v1/drive/files/$fileId"); put("thumbnailUrl", if (f.thumbnail != null) "/api/v1/drive/files/$fileId/thumbnail" else null)
                    put("isStarred", false); put("isArchived", false); put("captureDate", body["captureDate"] ?: JsonNull)
                    put("createdAt", "2026-03-01T00:00:00"); put("updatedAt", "2026-03-01T00:00:00"); put("deletedAt", JsonNull)
                    put("metadata", body["metadata"] ?: JsonNull)
                }
                return fake.ok(records[id].toString())
            }
            path == "/api/v1/photos/trash" && r.method == "GET" -> return list(records.values.filter { it["deletedAt"] !is JsonNull })
            path == "/api/v1/photos/trash" && r.method == "DELETE" -> { records.values.removeAll { it["deletedAt"] !is JsonNull }; return fake.ok("{}") }
        }
        Regex("/api/v1/photos/([^/]+)/metadata").matchEntire(path)?.let { m ->
            val rec = records[m.groupValues[1]] ?: return fake.status(404)
            val merged = mergePatch(rec["metadata"] as? JsonObject ?: JsonObject(emptyMap()), body)
            records[m.groupValues[1]] = JsonObject(rec + ("metadata" to merged))
            return fake.ok(merged.toString())
        }
        Regex("/api/v1/photos/([^/]+)/restore").matchEntire(path)?.let { m ->
            val rec = records[m.groupValues[1]] ?: return fake.status(404)
            records[m.groupValues[1]] = JsonObject(rec + ("deletedAt" to JsonNull))
            return fake.ok(records[m.groupValues[1]].toString())
        }
        Regex("/api/v1/photos/([^/]+)/permanent").matchEntire(path)?.let { m -> records.remove(m.groupValues[1]); return fake.ok("{}") }
        Regex("/api/v1/photos/([^/]+)").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val rec = records[id] ?: return fake.status(404)
            return when (r.method) {
                "PATCH" -> { records[id] = JsonObject(rec + body.filterKeys { it in setOf("isStarred", "isArchived", "captureDate") }); fake.ok(records[id].toString()) }
                "DELETE" -> { records[id] = JsonObject(rec + ("deletedAt" to JsonPrimitive("2026-03-02T00:00:00"))); fake.ok("{}") }
                else -> fake.ok(rec.toString())
            }
        }
        if (path == "/api/v1/albums") {
            if (r.method == "POST") {
                val id = UUID.randomUUID().toString()
                albums[id] = body["title"]!!.jsonPrimitive.content to mutableListOf()
                return fake.ok(album(id).toString())
            }
            return fake.ok(buildJsonObject { put("albums", buildJsonArray { albums.keys.forEach { add(album(it)) } }) }.toString())
        }
        Regex("/api/v1/albums/([^/]+)/items").matchEntire(path)?.let { m ->
            val a = albums[m.groupValues[1]] ?: return fake.status(404)
            if (r.method == "POST") { a.second += body["photoId"]!!.jsonPrimitive.content; return MockResponse().setResponseCode(204) }
            return list(a.second.mapNotNull(records::get))
        }
        return null
    }

    private fun album(id: String) = buildJsonObject {
        put("id", id); put("title", albums[id]!!.first); put("isAuto", false); put("photoCount", albums[id]!!.second.size)
        put("createdAt", "2026-03-01T00:00:00"); put("updatedAt", "2026-03-01T00:00:00")
    }

    private fun mergePatch(target: JsonObject, patch: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(target)
        for ((k, v) in patch) when {
            v is JsonNull -> out.remove(k)
            v is JsonObject -> out[k] = mergePatch(out[k] as? JsonObject ?: JsonObject(emptyMap()), v)
            else -> out[k] = v
        }
        return JsonObject(out)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content == "true"

    @Before
    fun setUp() = runBlocking {
        fake = FakeServer()
        tmp = Files.createTempDirectory("photos").toFile()
        fake.extraRoutes = { r, _ -> route(r) }
        api = fake.client(config = NeutrinoApps.photos) { http, auth -> PhotosApi(http, auth) }
        api.retryPolicy = com.neutrino.shared.core.api.RetryPolicy.NONE
        api.auth.login(fake.email, fake.password)
        keys = InMemoryKeyringStore()
        check = DeviceKeyCheck(api, keys)
        KeyManager(api, keys, check).provision()
        content = MediaContentService(api, { keys.load(fake.userId) }, File(tmp, "media"))
        pipeline = ImportPipeline(api, Uploader(api, check, File(tmp, "up")), content, ImportLedger(File(tmp, "ledger.json"))) { shareLocation }
        library = PhotoLibrary(api, scope, File(tmp, "library.json"))
    }

    @After
    fun tearDown() { runCatching { fake.shutdown() } }

    private fun prepared(bytes: ByteArray, name: String = "PXL_1.jpg", preview: ByteArray? = null, facts: MediaFacts = MediaFacts()): PreparedMedia {
        val f = File(tmp, "staged-${UUID.randomUUID()}").apply { writeBytes(bytes) }
        return PreparedMedia(f, name, "image/jpeg", thumbnailJpeg = byteArrayOf(9, 8, 7), previewJpeg = preview, facts = facts, isFavorite = true)
    }

    @Test
    fun importUploadsEncryptedRegistersAndPublishesWithoutLocation() = runBlocking {
        val original = Random.nextBytes(300_000)
        val taken = Instant.parse("2026-03-10T09:30:05Z")
        val facts = MediaFacts(width = 4000, height = 3000, format = "jpeg", model = "Pixel 8", gpsLatitude = 1.0, gpsLongitude = 2.0, captureDate = taken, localIdentifier = "77")
        val outcome = pipeline.import(prepared(original, preview = Random.nextBytes(1000), facts = facts)) as ImportPipeline.Outcome.Imported
        val item = outcome.item

        val stored = fake.files[item.fileId]!!
        assertNull("a photograph lands in the Drive root", stored.folderId)
        assertFalse("stored as ciphertext", stored.blob.contentEquals(original))
        assertEquals(B64.encode(byteArrayOf(9, 8, 7)), stored.thumbnail)
        // The last upload was the preview: filed in its folder, with no cover, every field before the bytes.
        assertEquals(listOf("encrypted_metadata", "folder_id", "encrypted_file_key", "key_version", "file"), fake.lastUploadParts)

        val record = records[item.id]!!
        assertEquals("2026-03-10T09:30:05", record.str("captureDate"))
        assertTrue(record.bool("isStarred"))
        val exif = (record["metadata"] as JsonObject)["exif"] as JsonObject
        assertEquals("Pixel 8", exif.str("model"))
        assertFalse("location is held back by default", "gpsLatitude" in exif)

        val folder = fake.folders.entries.single { it.value.first == Rendition.PREVIEWS_FOLDER }
        assertNull(folder.value.second)
        val preview = fake.files.values.single { it.folderId == folder.key }
        assertEquals(Rendition.previewName(item.fileId), preview.name)

        // The same photo again is not uploaded twice.
        val before = fake.files.size
        assertTrue(pipeline.import(prepared(original)) is ImportPipeline.Outcome.Duplicate)
        assertEquals(before, fake.files.size)
    }

    @Test
    fun aCopyWithItsMetadataRemovedIsTheSamePhoto() = runBlocking {
        fun jpeg(app1: ByteArray) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, (app1.size + 2).toByte()) + app1 +
            byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 4, 1, 2) + Random(7).nextBytes(4000) + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        val withGps = File(tmp, "a.jpg").apply { writeBytes(jpeg("Exif..GPS 51.5N 0.1W".toByteArray())) }
        val redacted = File(tmp, "b.jpg").apply { writeBytes(jpeg("Exif..".toByteArray())) }
        assertEquals(ImportPipeline.contentFingerprint(withGps), ImportPipeline.contentFingerprint(redacted))
        val other = File(tmp, "c.bin").apply { writeBytes(Random.nextBytes(100)) }
        assertNull(ImportPipeline.jpegScanOffset(other))

        assertTrue(pipeline.import(prepared(withGps.readBytes())) is ImportPipeline.Outcome.Imported)
        assertTrue(pipeline.import(prepared(redacted.readBytes())) is ImportPipeline.Outcome.Duplicate)
    }

    @Test
    fun concurrentImportsOfOnePhotoUploadItOnce() = runBlocking {
        val bytes = Random.nextBytes(50_000)
        val outcomes = listOf(async(Dispatchers.IO) { pipeline.import(prepared(bytes)) }, async(Dispatchers.IO) { pipeline.import(prepared(bytes)) }).map { it.await() }
        assertEquals(1, outcomes.count { it is ImportPipeline.Outcome.Imported })
        assertEquals(1, records.size)
    }

    @Test
    fun anotherDeviceDecryptsTheOriginalAndFindsThePreview() = runBlocking {
        val original = Random.nextBytes(200_000)
        val previewBytes = Random.nextBytes(5_000)
        val item = (pipeline.import(prepared(original, preview = previewBytes)) as ImportPipeline.Outcome.Imported).item

        val other = MediaContentService(api, { keys.load(fake.userId) }, File(tmp, "other"))
        assertArrayEquals(byteArrayOf(9, 8, 7), other.thumbnail(item)!!.readBytes())
        assertArrayEquals(original, other.original(item).readBytes())
        other.refreshPreviewIndex()
        assertEquals(1, other.previews.size)
        val third = MediaContentService(api, { keys.load(fake.userId) }, File(tmp, "third"))
        third.refreshPreviewIndex()
        assertArrayEquals(previewBytes, third.viewable(item).readBytes())
    }

    @Test
    fun libraryStarsArchivesTrashesAndRestores() = runBlocking {
        val a = (pipeline.import(prepared(Random.nextBytes(1000))) as ImportPipeline.Outcome.Imported).item
        val b = (pipeline.import(prepared(Random.nextBytes(1000))) as ImportPipeline.Outcome.Imported).item
        library.load()
        assertEquals(2, library.items.value.size)

        library.setStarred(listOf(a.id), false).join()
        assertFalse(records[a.id]!!.bool("isStarred"))
        library.setArchived(listOf(b.id), true).join()
        assertEquals(listOf(a.id), library.items.value.map { it.id })
        library.loadArchive()
        assertEquals(listOf(b.id), library.archived.value.map { it.id })

        library.moveToTrash(listOf(a.id)).join()
        assertTrue(library.items.value.isEmpty())
        library.loadTrash()
        assertEquals(listOf(a.id), library.trash.value.map { it.id })
        library.restore(listOf(a.id)).join()
        assertEquals(listOf(a.id), library.items.value.map { it.id })
        library.moveToTrash(listOf(a.id)).join()
        library.emptyTrash().join()
        assertNull(records[a.id])
    }

    @Test
    fun theListingIsKeptForOfflineBrowsing() = runBlocking {
        pipeline.import(prepared(Random.nextBytes(1000)))
        library.load()
        val reopened = PhotoLibrary(api, scope, File(tmp, "library.json"))
        assertEquals(1, reopened.items.value.size)
        fake.shutdown()
        reopened.load()
        assertTrue(reopened.unreachable.value)
        assertEquals(1, reopened.items.value.size)
    }

    @Test
    fun albumsCollectPhotos() = runBlocking {
        val a = (pipeline.import(prepared(Random.nextBytes(1000))) as ImportPipeline.Outcome.Imported).item
        val service = AlbumService(api, scope)
        val album = service.create("Holiday", listOf(a.id))
        assertEquals(listOf(a.id), service.items(album.id).map { it.id })
        service.load()
        assertEquals(1, service.albums.value.single().photoCount)
    }

    @Test
    fun aMotionPhotosClipIsSlicedFromItsOwnBytes() = runBlocking {
        val clip = byteArrayOf(0, 0, 0, 16) + "ftypmp42".toByteArray() + ByteArray(52)
        val still = Random.nextBytes(5000) + clip
        val facts = MediaFacts(motion = MotionPhoto.Info("GCamera:MotionPhoto", 1, null, MotionPhoto.Embedded(5000, clip.size.toLong())))
        val item = (pipeline.import(prepared(still, facts = facts)) as ImportPipeline.Outcome.Imported).item
        assertTrue(item.isGoogleMotionPhoto)
        val other = MediaContentService(api, { keys.load(fake.userId) }, File(tmp, "other"))
        library.load()
        assertArrayEquals(clip, other.motionClip(library.items.value.single(), null).readBytes())
        assertNotNull(item.motionPhoto)
    }
}
