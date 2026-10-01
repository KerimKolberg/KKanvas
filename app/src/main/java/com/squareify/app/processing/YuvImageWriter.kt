package com.squareify.app.processing

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.roundToInt

/**
 * Writes an ARGB bitmap into a YUV 4:2:0 [Image] (the video encoder's input buffer),
 * honouring each plane's row and pixel stride so both planar and semi-planar layouts work.
 */
object YuvImageWriter {

    fun writeBitmapToImage(bitmap: Bitmap, image: Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        writeYPlane(pixels, width, height, yPlane)
        writeChromaPlane(pixels, width, height, uPlane, isU = true)
        writeChromaPlane(pixels, width, height, vPlane, isU = false)
    }

    private fun writeYPlane(pixels: IntArray, width: Int, height: Int, plane: Image.Plane) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)

        for (y in 0 until height) {
            var rowPos = 0
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val yVal = rgbToY(p)
                if (pixelStride == 1) {
                    row[x] = yVal
                } else {
                    row[rowPos] = yVal
                    rowPos += pixelStride
                }
            }
            buffer.position(y * rowStride)
            buffer.put(row, 0, if (pixelStride == 1) width else (width - 1) * pixelStride + 1)
        }
    }

    private fun writeChromaPlane(
        pixels: IntArray,
        width: Int,
        height: Int,
        plane: Image.Plane,
        isU: Boolean,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        if (pixelStride == 1) {
            // Planar: each chroma row is contiguous.
            val row = ByteArray(rowStride)
            for (cy in 0 until chromaHeight) {
                val srcY = (cy * 2).coerceAtMost(height - 1)
                for (cx in 0 until chromaWidth) {
                    val srcX = (cx * 2).coerceAtMost(width - 1)
                    val p = pixels[srcY * width + srcX]
                    row[cx] = if (isU) rgbToU(p) else rgbToV(p)
                }
                buffer.position(cy * rowStride)
                buffer.put(row, 0, chromaWidth)
            }
            return
        }

        // Semi-planar: U and V are interleaved, so write each sample at its absolute offset.
        for (cy in 0 until chromaHeight) {
            val srcY = (cy * 2).coerceAtMost(height - 1)
            val rowStart = cy * rowStride
            for (cx in 0 until chromaWidth) {
                val srcX = (cx * 2).coerceAtMost(width - 1)
                val p = pixels[srcY * width + srcX]
                val value = if (isU) rgbToU(p) else rgbToV(p)
                buffer.put(rowStart + cx * pixelStride, value)
            }
        }
    }

    // BT.601 full-range RGB -> YUV.

    private fun rgbToY(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        return y.roundToInt().coerceIn(0, 255).toByte()
    }

    private fun rgbToU(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val u = -0.169 * r - 0.331 * g + 0.5 * b + 128
        return u.roundToInt().coerceIn(0, 255).toByte()
    }

    private fun rgbToV(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val v = 0.5 * r - 0.419 * g - 0.081 * b + 128
        return v.roundToInt().coerceIn(0, 255).toByte()
    }
}
