package com.squareify.app.processing

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Stack Blur by Mario Klingemann: a fast approximation of a Gaussian blur.
 * Works on the RGB channels; the output is fully opaque.
 */
object StackBlur {

    fun blur(source: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return source

        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        var rsum: Int
        var gsum: Int
        var bsum: Int
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int
        var sir: IntArray
        var stackpointer: Int
        var p: Int
        var yi = 0
        var yw = 0

        // Horizontal pass.
        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                val stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi++
            }
            yw += w
        }

        // Vertical pass.
        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            stackpointer = radius

            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                val stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun min(a: Int, b: Int): Int = if (a < b) a else b

    private fun max(a: Int, b: Int): Int = if (a > b) a else b
}
