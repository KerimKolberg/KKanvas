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
