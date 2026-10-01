package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/** Picks a few representative colours from an image, offered as background colours. */
object ColorExtractor {
    private const val SAMPLE_SIZE = 48
    /** Colours closer than this (RGB distance) count as the same suggestion. */
    private const val MIN_DISTANCE = 48.0

    fun dominantColors(bitmap: Bitmap, count: Int = 5): List<Int> {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (small !== bitmap) small.recycle()

        // Bucket by the top 4 bits of each channel; each bucket keeps a pixel count and channel sums.
        val buckets = HashMap<Int, IntArray>()
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val acc = buckets.getOrPut(key) { IntArray(4) }
            acc[0]++
            acc[1] += r
            acc[2] += g
            acc[3] += b
        }

        val result = mutableListOf<Int>()
        for (acc in buckets.values.sortedByDescending { it[0] }) {
            val color = Color.rgb(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0])
            if (result.none { distance(it, color) < MIN_DISTANCE }) result += color
            if (result.size == count) break
        }
        return result
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
