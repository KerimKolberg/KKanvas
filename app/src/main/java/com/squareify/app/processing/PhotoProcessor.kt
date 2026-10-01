package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import com.squareify.app.PaddingStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

object PhotoProcessor {

    /**
     * Upper limit for a saved photo (16 MP). Tall formats of big photos would otherwise need
     * hundreds of MB; Instagram displays far less than this anyway.
     */
    private const val MAX_OUTPUT_PIXELS = 16_000_000L

    fun loadDownscaledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = true
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxDimension.toFloat() / max(w, h)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    /**
     * Canvas size for a [width] x [height] image in [format] at the image's own resolution:
     * one side stays as it is, the other grows until the aspect ratio matches.
     */
    fun canvasSize(width: Int, height: Int, format: FrameFormat): Pair<Int, Int> {
        val rw = format.widthRatio.toLong()
        val rh = format.heightRatio.toLong()
        return if (width * rh >= height * rw) {
            // Wider than the target: keep the width, pad above and below.
            width to ((width * rh + rw - 1) / rw).toInt()
        } else {
            ((height * rw + rh - 1) / rh).toInt() to height
        }
    }

    /** Pads [source] to its format at natural resolution (capped at [MAX_OUTPUT_PIXELS]). */
    fun frame(source: Bitmap, settings: FrameSettings): Bitmap {
        val (w, h) = canvasSize(source.width, source.height, settings.format)
        val pixels = w.toLong() * h
        if (pixels <= MAX_OUTPUT_PIXELS) return frame(source, settings, w, h)
        val scale = sqrt(MAX_OUTPUT_PIXELS.toDouble() / pixels)
        return frame(
            source,
            settings,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Draws [source] centred on a [width] x [height] canvas, fills the padding with the
     * background colour or a blurred copy of the image, then applies the adjustments.
     */
    fun frame(source: Bitmap, settings: FrameSettings, width: Int, height: Int): Bitmap {
        val adjustments = settings.adjustments
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (settings.paddingStyle == PaddingStyle.BLUR) {
            drawBlurredBackground(canvas, source, width, height)
        } else {
            canvas.drawColor(settings.bgColor)
        }

        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + w, top + h), paint)

        var result = applyColorAdjustments(output, adjustments.brightness, adjustments.saturation)
        if (adjustments.sharpness > 0f) {
            result = applySharpen(result, adjustments.sharpness)
        }
        if (adjustments.grain > 0f) {
            result = applyGrain(result, adjustments.grain)
        }
        return result
    }

    private fun drawBlurredBackground(canvas: Canvas, source: Bitmap, width: Int, height: Int) {
        // Blur a small centre crop with the canvas's aspect ratio (cheap), then stretch it over the canvas.
        val workScale = min(1f, 300f / max(width, height))
        val workW = (width * workScale).roundToInt().coerceAtLeast(1)
        val workH = (height * workScale).roundToInt().coerceAtLeast(1)
        val scale = max(workW.toFloat() / source.width, workH.toFloat() / source.height)
        val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val cropX = ((scaledW - workW) / 2).coerceIn(0, max(0, scaledW - 1))
        val cropY = ((scaledH - workH) / 2).coerceIn(0, max(0, scaledH - 1))
        val cropW = min(workW, scaledW - cropX).coerceAtLeast(1)
        val cropH = min(workH, scaledH - cropY).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(scaled, cropX, cropY, cropW, cropH)

        val blurred = StackBlur.blur(cropped, 16)

        // Darken slightly so the photo stands out from its background.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) }
        )
        canvas.drawBitmap(
            blurred,
            Rect(0, 0, blurred.width, blurred.height),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint,
        )

        if (scaled !== source) scaled.recycle()
        if (cropped !== scaled) cropped.recycle()
        blurred.recycle()
    }

    private fun applyColorAdjustments(bitmap: Bitmap, brightness: Float, saturation: Float): Bitmap {
        if (brightness == 1f && saturation == 1f) return bitmap

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(saturation)
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        satMatrix.postConcat(brightnessMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(satMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return result
    }

    /** 3x3 cross-shaped sharpening kernel: centre 4s+1, the four neighbours -s. */
    private fun applySharpen(bitmap: Bitmap, amount: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val strength = amount * 1.5f
        val w1 = -strength
        val w4 = 4f * strength + 1f

        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val top = src[idx - w]
                val bottom = src[idx + w]
                val left = src[idx - 1]
                val right = src[idx + 1]
                val center = src[idx]

                val r = Color.red(top) * w1 + Color.red(left) * w1 + Color.red(center) * w4 +
                    Color.red(right) * w1 + Color.red(bottom) * w1
                val g = Color.green(top) * w1 + Color.green(left) * w1 + Color.green(center) * w4 +
                    Color.green(right) * w1 + Color.green(bottom) * w1
                val b = Color.blue(top) * w1 + Color.blue(left) * w1 + Color.blue(center) * w4 +
                    Color.blue(right) * w1 + Color.blue(bottom) * w1

                dst[idx] = Color.argb(
                    Color.alpha(center),
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255),
                )
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(dst, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return result
    }

    /** Overlays random grey noise with a slightly varying alpha. */
    private fun applyGrain(bitmap: Bitmap, amount: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val intensity = (255 * amount).toInt()

        val noise = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val noisePixels = IntArray(w * h)
        val rnd = Random(System.nanoTime())
        for (i in noisePixels.indices) {
            val v = rnd.nextInt(256)
            val a = (intensity * (rnd.nextFloat() * 0.4f + 0.6f)).toInt().coerceIn(0, 255)
            noisePixels[i] = Color.argb(a, v, v, v)
        }
        noise.setPixels(noisePixels, 0, w, 0, 0, w, h)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        canvas.drawBitmap(noise, 0f, 0f, paint)

        bitmap.recycle()
        noise.recycle()
        return result
    }
}
