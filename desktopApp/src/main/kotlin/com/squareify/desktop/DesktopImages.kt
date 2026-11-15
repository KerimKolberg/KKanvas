package com.squareify.desktop

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reading photos (turned upright as the camera recorded it) and writing JPEGs, with Skia. */
object DesktopImages {

    /** [file] decoded upright, its longer side at most [maxSize]; can be drawn on. */
    fun decode(file: File, maxSize: Int): Bitmap = decode(file.readBytes(), maxSize, file.name)

    fun decode(bytes: ByteArray, maxSize: Int, name: String = "photo"): Bitmap {
        val codec = try {
            Codec.makeFromData(Data.makeFromBytes(bytes))
        } catch (e: Exception) {
            null
        }
        if (codec == null) {
            // HEIC and the like: FFmpeg turns it into a PNG first.
            require(Ffmpeg.available) { "Can't read $name" }
            val png = File.createTempFile("kk-heic", ".tmp").apply { writeBytes(bytes) }
            try {
                return decode(Ffmpeg.decodeToPng(png), maxSize, name)
            } finally {
                png.delete()
            }
        }
        val raw = codec.readPixels()
        return upright(raw, codec.encodedOrigin, maxSize).also { if (it !== raw) raw.close() }
    }

    /** [raw] turned by [origin] and scaled so its longer side is at most [maxSize]. */
    private fun upright(raw: Bitmap, origin: EncodedOrigin, maxSize: Int): Bitmap {
        val swap = origin.swapsWidthHeight()
        val shownW = if (swap) raw.height else raw.width
        val shownH = if (swap) raw.width else raw.height
        val scale = min(1f, maxSize.toFloat() / max(shownW, shownH))
        // Halve first while far too big, then one filtered step: smooth, without a big blur kernel.
        var source = Image.makeFromBitmap(raw.apply { setImmutable() })
        var w = raw.width
        var h = raw.height
        while (raw.width * scale < w / 2f && raw.height * scale < h / 2f) {
            val half = Bitmap().apply { allocN32Pixels(max(1, w / 2), max(1, h / 2)) }
            Canvas(half).drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()), Rect.makeWH(half.width.toFloat(), half.height.toFloat()), SamplingMode.LINEAR, null, true)
            source.close()
            w = half.width
            h = half.height
            source = Image.makeFromBitmap(half.apply { setImmutable() })
        }
        val outW = max(1, (shownW * scale).roundToInt())
        val outH = max(1, (shownH * scale).roundToInt())
        val out = Bitmap().apply { allocN32Pixels(outW, outH) }
        val canvas = Canvas(out)
        val turnedW = if (swap) h else w
        val turnedH = if (swap) w else h
        canvas.scale(outW.toFloat() / turnedW, outH.toFloat() / turnedH)
        canvas.concat(orientation(origin, w.toFloat(), h.toFloat()))
        canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.CATMULL_ROM, Paint(), true)
        source.close()
        return out
    }

    /** A JPEG of [bitmap] at Android's quality 95. */
    fun jpeg(bitmap: Bitmap): ByteArray {
        val image = Image.makeFromBitmap(bitmap)
        return try {
            checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 95)) { "Couldn't encode the picture" }.bytes
        } finally {
            image.close()
        }
    }

    /** When a JPEG was taken, as its camera wrote it ("2026:06:29 16:23:15"); null if it doesn't say. */
    fun dateTaken(file: File): String? = dateTaken(file.inputStream().use { it.readNBytes(1 shl 18) })

    fun dateTaken(jpeg: ByteArray): String? {
        fun u8(i: Int) = jpeg[i].toInt() and 0xFF
        if (jpeg.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        // The camera's notes (EXIF) are in an APP1 block before the picture itself.
        var at = 2
        while (at + 4 <= jpeg.size && u8(at) == 0xFF && u8(at + 1) != 0xDA) {
            val length = (u8(at + 2) shl 8) or u8(at + 3)
            val isExif = u8(at + 1) == 0xE1 && at + 10 <= jpeg.size && String(jpeg, at + 4, 4, Charsets.US_ASCII) == "Exif"
            if (isExif) return exifDate(jpeg, at + 10, minOf(jpeg.size, at + 2 + length))
            at += 2 + length
        }
        return null
    }

    /** DateTimeOriginal from the EXIF block that starts at [tiff]. */
    private fun exifDate(bytes: ByteArray, tiff: Int, end: Int): String? {
        if (tiff + 8 > end) return null
        val little = bytes[tiff] == 'I'.code.toByte()
        fun u16(i: Int): Int {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            return if (little) (b shl 8) or a else (a shl 8) or b
        }
        fun u32(i: Int): Int = if (little) (u16(i + 2) shl 16) or u16(i) else (u16(i) shl 16) or u16(i + 2)
        /** Where the value of [tag] is noted in the list at [ifd]. */
        fun entry(ifd: Int, tag: Int): Int? {
            if (ifd < tiff || ifd + 2 > end) return null
            return (0 until u16(ifd)).map { ifd + 2 + it * 12 }.firstOrNull { it + 12 <= end && u16(it) == tag }
        }
        val exifIfd = entry(tiff + u32(tiff + 4), 0x8769)?.let { tiff + u32(it + 8) } ?: return null
        val date = entry(exifIfd, 0x9003)?.let { tiff + u32(it + 8) } ?: return null
        if (date < tiff || date + 19 > end) return null
        return String(bytes, date, 19, Charsets.US_ASCII).takeIf { DATE_FORMAT.matches(it) }
    }

    private val DATE_FORMAT = Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")

    /** [jpeg] with [date] ("2026:06:29 16:23:15") noted as the time it was taken. */
    fun withDateTaken(jpeg: ByteArray, date: String): ByteArray {
        require(date.length == 19)
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = u16(v shr 16) + u16(v and 0xFFFF)
        // The smallest EXIF there is: a list pointing to the photo details, which hold the one date.
        val tiff = "MM".toByteArray() + u16(42) + u32(8) +
            u16(1) + u16(0x8769) + u16(4) + u32(1) + u32(26) + u32(0) +
            u16(1) + u16(0x9003) + u16(2) + u32(20) + u32(44) + u32(0) +
            date.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(2 + 6 + tiff.size) + "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** A PNG, for small thumbnails kept by the app. */
    fun png(bitmap: Bitmap): ByteArray {
        val image = Image.makeFromBitmap(bitmap)
        return try {
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Couldn't encode the picture" }.bytes
        } finally {
            image.close()
        }
    }

}

/**
 * Turns a [w] x [h] picture as stored into the way it's shown, for each EXIF orientation.
 * (Skiko's EncodedOrigin.toMatrix moves it by the wrong side's length.)
 */
private fun orientation(origin: EncodedOrigin, w: Float, h: Float): Matrix33 = when (origin) {
    EncodedOrigin.TOP_RIGHT -> Matrix33(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
    EncodedOrigin.BOTTOM_RIGHT -> Matrix33(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
    EncodedOrigin.BOTTOM_LEFT -> Matrix33(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
    EncodedOrigin.LEFT_TOP -> Matrix33(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    EncodedOrigin.RIGHT_TOP -> Matrix33(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
    EncodedOrigin.RIGHT_BOTTOM -> Matrix33(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
    EncodedOrigin.LEFT_BOTTOM -> Matrix33(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
    else -> Matrix33.IDENTITY
}
