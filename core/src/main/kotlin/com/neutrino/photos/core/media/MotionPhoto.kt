package com.neutrino.photos.core.media

import java.io.File
import java.io.RandomAccessFile

/** Random access over a file's bytes — a whole photo is never needed to classify one. */
interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray

    class OfArray(private val bytes: ByteArray) : ByteSource {
        override val size get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            val start = offset.coerceIn(0, size).toInt()
            return bytes.copyOfRange(start, (start + length).coerceAtMost(bytes.size))
        }
    }

    class OfFile(private val file: File) : ByteSource {
        override val size get() = file.length()
        override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { f ->
            val start = offset.coerceIn(0, size)
            val n = minOf(length.toLong(), size - start).toInt()
            ByteArray(n).also { f.seek(start); f.readFully(it) }
        }
    }
}

/**
 * A Google Motion Photo: one JPEG with an MP4 appended, marked by `GCamera` XMP. A line-for-line
 * port of the web's `packages/api-photos/src/xmp.ts` and `motionPhoto.ts` (`readMotionPhotoInfo`'s
 * JPEG branch), so a file the web calls a motion photo is one here, with the same `signal`.
 *
 * Apple Live Photos are two files and are linked by id (`agent_docs/live-photos.md`); an Android
 * camera never writes one, so this side reads only Google's.
 */
object MotionPhoto {

    data class Embedded(val offset: Long, val length: Long)

    data class Info(
        val signal: String,
        val version: Int? = null,
        val presentationTimestampUs: Long? = null,
        val embedded: Embedded? = null,
    )

    data class GCameraMotion(val signal: String, val version: Int?, val presentationTimestampUs: Long?, val videoLengthFromEnd: Long?)

    /** How far into a JPEG to look for the packet. XMP sits in the first segments. */
    private const val MAX_JPEG_SCAN_BYTES = 2 * 1024 * 1024
    private const val XMP_APP1_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    /** Classifies a JPEG; null for an ordinary photo, which is what every file without the XMP gets. */
    fun read(source: ByteSource): Info? {
        val head = source.read(0, minOf(source.size, MAX_JPEG_SCAN_BYTES.toLong()).toInt())
        val xmp = findXmpInJpeg(head) ?: return null
        val google = readGCameraMotion(xmp) ?: return null
        return Info(google.signal, google.version, google.presentationTimestampUs, locateEmbeddedVideo(source, google.videoLengthFromEnd))
    }

    /** The XMP packet out of a JPEG's APP1 segments, stopping at start of scan. */
    fun findXmpInJpeg(bytes: ByteArray): String? {
        fun u(i: Int) = bytes[i].toInt() and 0xff
        if (bytes.size < 4 || u(0) != 0xff || u(1) != 0xd8) return null
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (u(offset) != 0xff) return null
            val marker = u(offset + 1)
            if (marker == 0xd8 || marker in 0xd0..0xd9) { offset += 2; continue }
            if (marker == 0xda) return null
            val length = (u(offset + 2) shl 8) or u(offset + 3)
            if (length < 2 || offset + 2 + length > bytes.size) return null
            val start = offset + 4
            val end = offset + 2 + length
            if (marker == 0xe1 && end - start > XMP_APP1_HEADER.length) {
                val header = String(bytes, start, XMP_APP1_HEADER.length, Charsets.UTF_8)
                if (header == XMP_APP1_HEADER) return String(bytes, start + XMP_APP1_HEADER.length, end - start - XMP_APP1_HEADER.length, Charsets.UTF_8)
            }
            offset = end
        }
        return null
    }

    /** One XMP property's value, whether written as an attribute or as an element. */
    fun xmpValue(xmp: String, property: String): String? {
        val name = Regex.escape(property)
        Regex("""$name\s*=\s*(["'])(.*?)\1""").find(xmp)?.let { return it.groupValues[2].trim() }
        return Regex("""<$name[^>]*>([^<]*)</$name>""").find(xmp)?.groupValues?.get(1)?.trim()
    }

    private fun xmpNumber(xmp: String, property: String): Double? = xmpValue(xmp, property)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun xmpFlag(xmp: String, property: String): Boolean {
        val raw = xmpValue(xmp, property) ?: return false
        return raw != "0" && !raw.equals("false", ignoreCase = true)
    }

    /** The clip's length from a MotionPhoto v2 `Container:Directory` (or the older `GContainer:`). */
    private fun containerMotionLength(xmp: String): Long? {
        for (item in Regex("""<G?Container:Item\b[^>]*/?>""").findAll(xmp)) {
            val semantic = Regex("""Item:Semantic\s*=\s*(["'])(.*?)\1""").find(item.value) ?: continue
            if (semantic.groupValues[2] != "MotionPhoto") continue
            val length = Regex("""Item:Length\s*=\s*(["'])(.*?)\1""").find(item.value) ?: continue
            val value = length.groupValues[2].toDoubleOrNull() ?: continue
            if (value.isFinite() && value > 0) return value.toLong()
        }
        return null
    }

    fun readGCameraMotion(xmp: String): GCameraMotion? {
        val version = xmpNumber(xmp, "GCamera:MotionPhotoVersion")
        val microVersion = xmpNumber(xmp, "GCamera:MicroVideoVersion")
        val signal = when {
            xmpFlag(xmp, "GCamera:MotionPhoto") -> "GCamera:MotionPhoto"
            xmpFlag(xmp, "GCamera:MicroVideo") -> "GCamera:MicroVideo"
            version != null -> "GCamera:MotionPhotoVersion"
            microVersion != null -> "GCamera:MicroVideoVersion"
            else -> return null
        }
        val pts = xmpNumber(xmp, "GCamera:MotionPhotoPresentationTimestampUs") ?: xmpNumber(xmp, "GCamera:MicroVideoPresentationTimestampUs")
        val lengthFromEnd = containerMotionLength(xmp) ?: xmpNumber(xmp, "GCamera:MicroVideoOffset")?.toLong()
        return GCameraMotion(signal, (version ?: microVersion)?.toInt(), pts?.toLong(), lengthFromEnd?.takeIf { it > 0 })
    }

    /**
     * Where the appended MP4 starts. The XMP's claim is checked against the `ftyp` box that has to be
     * there; failing that the last 8 MB are scanned for it. Null means the still is all there is.
     */
    fun locateEmbeddedVideo(source: ByteSource, lengthFromEnd: Long?): Embedded? {
        val size = source.size
        fun check(offset: Long): Embedded? {
            if (offset <= 0 || offset >= size) return null
            return if (startsWithFtyp(source.read(offset, 8))) Embedded(offset, size - offset) else null
        }
        if (lengthFromEnd != null) check(size - lengthFromEnd)?.let { return it }
        val scan = minOf(size, 8L * 1024 * 1024)
        val start = size - scan
        val tail = source.read(start, scan.toInt())
        fun u(i: Int) = tail[i].toInt() and 0xff
        var i = 0
        while (i + 8 <= tail.size) {
            if (u(i + 4) == 0x66 && u(i + 5) == 0x74 && u(i + 6) == 0x79 && u(i + 7) == 0x70) {
                val offset = start + i
                val box = (u(i).toLong() shl 24) or (u(i + 1).toLong() shl 16) or (u(i + 2).toLong() shl 8) or u(i + 3).toLong()
                if (box >= 8 && box <= size - offset) return Embedded(offset, size - offset)
            }
            i++
        }
        return null
    }

    private fun startsWithFtyp(head: ByteArray) =
        head.size >= 8 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() && head[6] == 0x79.toByte() && head[7] == 0x70.toByte()
}
