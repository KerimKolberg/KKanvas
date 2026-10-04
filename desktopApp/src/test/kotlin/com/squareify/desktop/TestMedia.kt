package com.squareify.desktop

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File

/** Photos and videos made up for the tests, in a temporary folder. */
class TestMedia(val dir: File) {
    init {
        dir.mkdirs()
    }

    /** A [width] x [height] JPEG: red left half, blue right half; [orientation] as the camera's EXIF tag. */
    fun photo(name: String, width: Int = 400, height: Int = 200, orientation: Int = 1): File {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        val canvas = Canvas(bitmap)
        canvas.drawRect(Rect.makeWH(width / 2f, height.toFloat()), Paint().apply { color = RED })
        canvas.drawRect(Rect.makeXYWH(width / 2f, 0f, width / 2f, height.toFloat()), Paint().apply { color = BLUE })
        val jpeg = DesktopImages.jpeg(bitmap)
        bitmap.close()
        return File(dir, name).apply { writeBytes(if (orientation == 1) jpeg else withOrientation(jpeg, orientation)) }
    }

    /** [jpeg] with an EXIF block that holds only the orientation, right after the start marker. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian, first IFD at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation, SHORT
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** A test-pattern clip with a tone, [seconds] long. */
    fun clip(name: String, width: Int = 640, height: Int = 360, seconds: Double = 3.0, fps: Int = 30, sound: Boolean = true, rotate: Int = 0, color: String? = null): File {
        val out = File(dir, name)
        val video = if (color != null) "color=c=$color:s=${width}x$height:r=$fps:d=$seconds" else "testsrc2=s=${width}x$height:r=$fps:d=$seconds"
        val command = buildList {
            addAll(listOf(Ffmpeg.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", video))
            if (sound) addAll(listOf("-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds"))
            addAll(listOf("-c:v", "libx264", "-pix_fmt", "yuv420p"))
            if (sound) addAll(listOf("-c:a", "aac", "-shortest"))
            add(out.path)
        }
        val result = Ffmpeg.run(command)
        check(result.exitCode == 0) { result.errors }
        if (rotate == 0) return out
        // As a phone stores a sideways recording: the pixels as filmed, plus a turn for players.
        val turned = File(dir, "turned_$name")
        val r = Ffmpeg.run(listOf(Ffmpeg.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-display_rotation", "${-rotate}", "-i", out.path, "-c", "copy", turned.path))
        check(r.exitCode == 0) { r.errors }
        out.delete()
        turned.renameTo(out)
        return out
    }

    companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()

        /** The pixel at ([x], [y]) as ARGB. */
        fun Bitmap.pixel(x: Int, y: Int): Int {
            val bytes = readPixels(ImageInfo(1, 1, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), 4, x, y)!!
            return ((bytes[3].toInt() and 0xFF) shl 24) or ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
        }

        fun red(c: Int) = (c shr 16) and 0xFF
        fun green(c: Int) = (c shr 8) and 0xFF
        fun blue(c: Int) = c and 0xFF
    }
}
