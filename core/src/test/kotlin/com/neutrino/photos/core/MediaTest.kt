package com.neutrino.photos.core

import com.neutrino.photos.core.api.PhotosApi
import com.neutrino.photos.core.media.ByteSource
import com.neutrino.photos.core.media.Grouping
import com.neutrino.photos.core.media.MediaFacts
import com.neutrino.photos.core.media.MotionPhoto
import com.neutrino.photos.core.media.Rendition
import com.neutrino.photos.core.media.Timeline
import com.neutrino.photos.core.model.MediaItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The web's `motionPhoto.test.ts` cases for the JPEG half, with its fixtures. */
class MotionPhotoTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1).toList()
    private fun u32(v: Int) = listOf((v ushr 24) and 0xff, (v ushr 16) and 0xff, (v ushr 8) and 0xff, v and 0xff).map { it.toByte() }

    private fun jpeg(xmp: String?, appendMp4: Int = 0): ByteArray {
        val bytes = mutableListOf<Byte>(0xff.toByte(), 0xd8.toByte())
        if (xmp != null) {
            val payload = ascii("http://ns.adobe.com/xap/1.0/\u0000") + ascii(xmp)
            val length = payload.size + 2
            bytes += listOf(0xff, 0xe1, (length shr 8) and 0xff, length and 0xff).map { it.toByte() }
            bytes += payload
        }
        bytes += listOf(0xff, 0xda, 0x00, 0x02).map { it.toByte() } + List(32) { 0x55.toByte() }
        bytes += listOf(0xff.toByte(), 0xd9.toByte())
        if (appendMp4 > 0) bytes += u32(16) + ascii("ftyp") + ascii("mp42") + u32(0) + List(maxOf(0, appendMp4 - 16)) { 0.toByte() }
        return bytes.toByteArray()
    }

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="500000">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/></rdf:li>
     <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="64"/></rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""

    private val microXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description
  GCamera:MicroVideo="1"
  GCamera:MicroVideoVersion="1"
  GCamera:MicroVideoOffset="64"
  GCamera:MicroVideoPresentationTimestampUs="250000"/></rdf:RDF></x:xmpmeta>"""

    @Test fun findsXmp() {
        assertTrue(MotionPhoto.findXmpInJpeg(jpeg(motionXmp))!!.contains("GCamera:MotionPhoto"))
        assertNull(MotionPhoto.findXmpInJpeg(jpeg(null)))
        assertNull(MotionPhoto.findXmpInJpeg(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun readsValues() {
        assertEquals("1", MotionPhoto.xmpValue("""GCamera:MotionPhotoVersion="1"""", "GCamera:MotionPhotoVersion"))
        assertEquals("2", MotionPhoto.xmpValue("<GCamera:MotionPhotoVersion>2</GCamera:MotionPhotoVersion>", "GCamera:MotionPhotoVersion"))
        assertNull(MotionPhoto.xmpValue("<x:xmpmeta/>", "GCamera:MotionPhoto"))
    }

    @Test fun readsGCamera() {
        assertEquals(MotionPhoto.GCameraMotion("GCamera:MotionPhoto", 1, 500000, 64), MotionPhoto.readGCameraMotion(motionXmp))
        assertEquals(MotionPhoto.GCameraMotion("GCamera:MicroVideo", 1, 250000, 64), MotionPhoto.readGCameraMotion(microXmp))
        assertEquals("GCamera:MotionPhotoVersion", MotionPhoto.readGCameraMotion("""GCamera:MotionPhotoVersion="1"""")?.signal)
        assertNull(MotionPhoto.readGCameraMotion("<x:xmpmeta><dc:creator>someone</dc:creator></x:xmpmeta>"))
        assertNull(MotionPhoto.readGCameraMotion("""GCamera:MotionPhoto="0""""))
    }

    @Test fun classifiesAndLocatesTheClip() {
        val bytes = jpeg(motionXmp, appendMp4 = 64)
        val info = MotionPhoto.read(ByteSource.OfArray(bytes))!!
        assertEquals(MotionPhoto.Embedded(bytes.size - 64L, 64), info.embedded)

        val wrong = jpeg(motionXmp.replace("""Item:Length="64"""", """Item:Length="999""""), appendMp4 = 64)
        assertEquals(MotionPhoto.Embedded(wrong.size - 64L, 64), MotionPhoto.read(ByteSource.OfArray(wrong))!!.embedded)

        val noClip = MotionPhoto.read(ByteSource.OfArray(jpeg(motionXmp)))!!
        assertEquals("GCamera:MotionPhoto", noClip.signal)
        assertNull(noClip.embedded)

        assertNull(MotionPhoto.read(ByteSource.OfArray(jpeg(null))))
    }
}

class TimelineTest {
    private fun item(id: String, mime: String = "image/jpeg", taken: String? = "2026-03-10T12:00:00Z", metadata: String? = null, fileId: String = "f-$id") =
        MediaItem(id, fileId, "$id.jpg", mime, 10, null, false, false, taken?.let(Instant::parse), Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"), null, metadata?.let { Json.parseToJsonElement(it).jsonObject })

    @Test fun hidesClaimedClipsOnly() {
        val still = item("still", metadata = """{"device":{"liveVideoFileID":"f-clip"}}""")
        val clip = item("clip", mime = "video/quicktime", metadata = """{"motionPhoto":{"subtype":"live_photo_apple","signal":"x"}}""")
        val pairedById = item("s2", metadata = """{"motionPhoto":{"subtype":"live_photo_apple","signal":"x","contentIdentifier":"ABC"}}""")
        val clip2 = item("c2", mime = "video/quicktime", metadata = """{"motionPhoto":{"subtype":"live_photo_apple","signal":"x","contentIdentifier":"ABC"}}""")
        val video = item("holiday", mime = "video/mp4")
        val shown = Timeline.visible(listOf(still, clip, pairedById, clip2, video)).map { it.id }.toSet()
        assertEquals(setOf("still", "s2", "holiday"), shown)
        assertEquals("clip", Timeline.clipOf(still, listOf(still, clip))?.id)
        assertEquals("c2", Timeline.clipOf(pairedById, listOf(pairedById, clip2))?.id)
        assertTrue(still.isLivePhoto)
    }

    @Test fun sortsNewestFirstFallingBackToArrival() {
        val undated = item("undated", taken = null)
        val old = item("old", taken = "2020-01-01T00:00:00Z")
        val new = item("new", taken = "2026-05-01T00:00:00Z")
        assertEquals(listOf("new", "undated", "old"), Timeline.visible(listOf(old, undated, new)).map { it.id })
    }

    @Test fun groupsByDayMonthYear() {
        val items = listOf(item("a", taken = "2026-03-10T12:00:00Z"), item("b", taken = "2026-03-10T08:00:00Z"), item("c", taken = "2026-03-09T08:00:00Z"),
            item("d", taken = "2025-12-31T08:00:00Z"), item("v", mime = "video/mp4", taken = "2026-03-10T01:00:00Z"))
        val today = LocalDate.of(2026, 3, 10)
        val days = Timeline.sections(Timeline.visible(items), Grouping.DAY, ZoneOffset.UTC, today)
        assertEquals(listOf("Today", "Yesterday"), days.take(2).map { it.title })
        assertEquals("2 photos · 1 video", days[0].subtitle)
        assertEquals(2, Timeline.sections(items, Grouping.YEAR, ZoneOffset.UTC, today).size)
        assertEquals("December 2025", Timeline.sections(items, Grouping.MONTH, ZoneOffset.UTC, today).last().title)
        assertEquals(Grouping.MONTH, Grouping.DAY.denser)
        assertEquals(Grouping.DAY, Grouping.DAY.sparser)
    }

    @Test fun trashCountdown() {
        val deleted = item("x").copy(deletedAt = Instant.parse("2026-03-01T10:00:00Z"))
        assertEquals(21L, deleted.daysUntilPurge(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC))
    }
}

class MetadataTest {
    private val facts = MediaFacts(width = 4032, height = 3024, format = "jpeg", make = "Google", model = "Pixel 8", exposureTime = "1/120",
        fNumber = 1.7, iso = 100, focalLength = 6.9, gpsLatitude = 51.5, gpsLongitude = -0.12, datetimeOriginal = "2026:03:10 09:30:00",
        motion = MotionPhoto.Info("GCamera:MotionPhoto", 1, 500000, MotionPhoto.Embedded(100, 64)), localIdentifier = "42")

    @Test fun documentHasTheWebsShapeAndHoldsLocationBack() {
        val doc = facts.document(includeLocation = false)
        val exif = doc["exif"] as JsonObject
        assertEquals("Pixel 8", exif["model"]!!.toString().trim('"'))
        assertFalse("gpsLatitude" in exif)
        val motion = doc["motionPhoto"] as JsonObject
        assertEquals("\"motion_photo_google\"", motion["subtype"].toString())
        assertEquals("""{"offset":100,"length":64}""", motion["embedded"].toString())
        assertEquals("""{"localIdentifier":"42"}""", doc["device"].toString())
        assertTrue("gpsLatitude" in (facts.document(includeLocation = true)["exif"] as JsonObject))
    }

    @Test fun patchRemovesCoordinatesWhenWithheld() {
        val exif = facts.patch(includeLocation = false)["exif"] as JsonObject
        assertEquals(JsonNull, exif["gpsLatitude"])
        assertEquals(JsonNull, exif["gpsLongitude"])
        assertEquals(51.5.toString(), (facts.patch(true)["exif"] as JsonObject)["gpsLatitude"].toString())
    }

    @Test fun captureDateIsTheOneShapeTheServerParses() {
        assertEquals("2026-03-10T09:30:05", PhotosApi.captureDateWire(Instant.parse("2026-03-10T09:30:05.123Z")))
    }

    @Test fun renditionNamesAreTheIndex() {
        assertEquals("abc.preview.jpg", Rendition.previewName("abc"))
        assertEquals("abc", Rendition.originalOf("abc.preview.jpg"))
        assertNull(Rendition.originalOf("abc.live.mov"))
        assertNotNull(Rendition.originalOf(Rendition.previewName("1f0c-22")))
        assertTrue(Rendition.worthUploading(60, 100))
        assertFalse(Rendition.worthUploading(80, 100))
    }
}
