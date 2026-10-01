package com.squareify.app.processing

import android.graphics.Bitmap
import android.media.Image

/**
 * Writes an ARGB bitmap into a YUV 4:2:0 [Image] (the video encoder's input buffer),
 * honouring each plane's row and pixel stride so both planar and semi-planar layouts work.
 * Runs once per video frame, so it works a row at a time in integer maths.
 */
object YuvImageWriter {

    fun writeBitmapToImage(bitmap: Bitmap, image: Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planes = image.planes
        writeLuma(pixels, width, height, planes[0])
        writeChroma(pixels, width, height, planes[1], isU = true)
        writeChroma(pixels, width, height, planes[2], isU = false)
    }

    private fun writeLuma(pixels: IntArray, width: Int, height: Int, plane: Image.Plane) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowLength = (width - 1) * pixelStride + 1
        val row = ByteArray(rowLength)
        for (y in 0 until height) {
            val start = y * rowStride
            if (pixelStride != 1) {
                // Keep the bytes between our samples; they may belong to another plane.
                buffer.position(start)
                buffer.get(row, 0, rowLength)
            }
            var i = y * width
            var pos = 0
            for (x in 0 until width) {
                val p = pixels[i++]
                row[pos] = luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
                pos += pixelStride
            }
            buffer.position(start)
            buffer.put(row, 0, rowLength)
        }
    }

    private fun writeChroma(pixels: IntArray, width: Int, height: Int, plane: Image.Plane, isU: Boolean) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val rowLength = (chromaWidth - 1) * pixelStride + 1
        val row = ByteArray(rowLength)
        for (cy in 0 until chromaHeight) {
            val start = cy * rowStride
            if (pixelStride != 1) {
                // Semi-planar: U and V are interleaved, so keep the other plane's bytes in between.
                buffer.position(start)
                buffer.get(row, 0, rowLength)
            }
            val top = cy * 2 * width
            val bottom = top + width
            var pos = 0
            for (cx in 0 until chromaWidth) {
                val x = cx * 2
                // Each chroma sample covers a 2 x 2 block: use its average colour.
                val a = pixels[top + x]
                val b = pixels[top + x + 1]
                val c = pixels[bottom + x]
                val d = pixels[bottom + x + 1]
                val red = (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((c shr 16) and 0xFF) + ((d shr 16) and 0xFF) + 2) shr 2
                val green = (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((c shr 8) and 0xFF) + ((d shr 8) and 0xFF) + 2) shr 2
                val blue = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
                row[pos] = (if (isU) chromaU(red, green, blue) else chromaV(red, green, blue)).toByte()
                pos += pixelStride
            }
            buffer.position(start)
            buffer.put(row, 0, rowLength)
        }
    }

    // RGB -> BT.709 limited-range YUV (black = 16, white = 235), in 1/256 steps. This is what
    // Mp4Writer declares to the encoder; full-range values read as limited crush the shadows.

    private fun luma(r: Int, g: Int, b: Int): Int = clamp(((47 * r + 157 * g + 16 * b + 128) shr 8) + 16)

    private fun chromaU(r: Int, g: Int, b: Int): Int = clamp(((-26 * r - 86 * g + 112 * b + 128) shr 8) + 128)

    private fun chromaV(r: Int, g: Int, b: Int): Int = clamp(((112 * r - 102 * g - 10 * b + 128) shr 8) + 128)

    private fun clamp(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value
}
