package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.squareify.app.Adjustments
import com.squareify.app.Border
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import com.squareify.app.PaddingStyle
import kotlin.math.ceil
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

    // What the 0–1 border settings map to.
    /** Margin on each side at 100%, as a fraction of the canvas's shorter side. */
    private const val MAX_MARGIN = 0.25f
    /** Corner radius at 100%, as a fraction of the photo's shorter side (a full pill/circle). */
    private const val MAX_CORNER_RADIUS = 0.5f

    /**
     * Canvas size for a [width] x [height] image in [format] at the image's own resolution:
     * one side stays as it is, the other grows until the aspect ratio matches. With a
     * [marginFraction] (of the canvas's shorter side, on every side) the canvas grows so the
     * image keeps its resolution.
     */
    fun canvasSize(width: Int, height: Int, format: FrameFormat, marginFraction: Float = 0f): Pair<Int, Int> {
        val rw = format.widthRatio.toLong()
        val rh = format.heightRatio.toLong()
        if (marginFraction <= 0f) {
            return if (width * rh >= height * rw) {
                // Wider than the target: keep the width, pad above and below.
                width to ((width * rh + rw - 1) / rw).toInt()
            } else {
                ((height * rw + rh - 1) / rh).toInt() to height
            }
        }
        val ratio = rw.toDouble() / rh
        // Total margin (both sides) relative to the canvas height.
        val inset = 2 * marginFraction * min(ratio, 1.0)
        val canvasHeight = max(width / (ratio - inset), height / (1 - inset))
        return ceilTolerant(canvasHeight * ratio) to ceilTolerant(canvasHeight)
    }

    /** Rounds up, ignoring float noise (1250.0000047 is 1250, not 1251). */
    private fun ceilTolerant(value: Double): Int = ceil(value - 1e-4).toInt()

    fun canvasSize(width: Int, height: Int, settings: FrameSettings): Pair<Int, Int> =
        canvasSize(width, height, settings.format, settings.border.margin * MAX_MARGIN)

    /** Pads [source] to its format at natural resolution (capped at [MAX_OUTPUT_PIXELS]). */
    fun frame(source: Bitmap, settings: FrameSettings): Bitmap {
        val (w, h) = canvasSize(source.width, source.height, settings)
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

    /** Like [frame], scaled down so the longer side is at most [maxSide]; for previews. */
    fun frameFitting(source: Bitmap, settings: FrameSettings, maxSide: Int): Bitmap {
        val (w, h) = canvasSize(source.width, source.height, settings)
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        return frame(
            source,
            settings,
            (w * scale).roundToInt().coerceAtLeast(1),
            (h * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * Draws [source] centred on a [width] x [height] canvas inside the margin, fills the rest
     * with the background, then applies the adjustments.
     */
    fun frame(source: Bitmap, settings: FrameSettings, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        drawBackground(canvas, source, settings, width, height)
        val photo = photoRect(source, marginInset(settings, width, height), width, height)
        drawImage(canvas, source, fullRect(source), photo, settings.border)

        return applyAdjustments(output, settings.adjustments)
    }

    /** The margin on each side in pixels, for a [width] x [height] canvas. */
    fun marginInset(settings: FrameSettings, width: Int, height: Int): Float =
        settings.border.margin * MAX_MARGIN * min(width, height)

    /**
     * Colour ([colorFilter]), then sharpening, grain and vignette. May recycle [bitmap] and return
     * a new one; [bitmap] must be mutable.
     */
    fun applyAdjustments(bitmap: Bitmap, adjustments: Adjustments): Bitmap {
        var result = applyColorAdjustments(bitmap, adjustments)
        if (adjustments.sharpness > 0f) {
            result = applySharpen(result, adjustments.sharpness)
        }
        if (adjustments.grain > 0f) {
            result = applyGrain(result, adjustments.grain)
        }
        if (adjustments.vignette > 0f) {
            applyVignette(result, adjustments.vignette)
        }
        return result
    }

    fun drawBackground(canvas: Canvas, source: Bitmap, settings: FrameSettings, width: Int, height: Int) {
        when (settings.paddingStyle) {
            PaddingStyle.SOLID -> canvas.drawColor(settings.bgColor)
            PaddingStyle.GRADIENT -> {
                val paint = Paint()
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    settings.bgColor, settings.bgColor2,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            PaddingStyle.BLUR -> {
                val blurred = blurredCover(source, width.toFloat(), height.toFloat(), settings.blurStrength)
                canvas.drawBitmap(
                    blurred,
                    Rect(0, 0, blurred.width, blurred.height),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                blurred.recycle()
            }
        }
    }

    /**
     * A collage cell fitted with Blurred style: fills [rect] with a blurred copy of the cell's own
     * [source] (with the border's corners and shadow); the whole picture then goes on top.
     */
    fun drawBlurredFill(canvas: Canvas, source: Bitmap, rect: RectF, settings: FrameSettings) {
        val blurred = blurredCover(source, rect.width(), rect.height(), settings.blurStrength)
        drawImage(canvas, blurred, fullRect(blurred), rect, settings.border)
        blurred.recycle()
    }

    /** Corner radius in pixels of a picture drawn into [rect]. */
    fun cornerRadius(border: Border, rect: RectF): Float =
        border.cornerRadius * MAX_CORNER_RADIUS * min(rect.width(), rect.height())

    private fun fullRect(source: Bitmap) = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())

    /** Where the photo goes: as large as fits inside the margin, centred. */
    private fun photoRect(source: Bitmap, inset: Float, width: Int, height: Int): RectF {
        val scale = min(
            (width - 2 * inset) / source.width,
            (height - 2 * inset) / source.height,
        )
        val w = source.width * scale
        val h = source.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /**
     * Draws the [crop] of [source] into [rect], with the border's rounded corners and shadow.
     * Also used for each cell of a collage.
     */
    fun drawImage(canvas: Canvas, source: Bitmap, crop: RectF, rect: RectF, border: Border) {
        val shortSide = min(rect.width(), rect.height())
        val radius = cornerRadius(border, rect)

        if (border.shadow > 0f) {
            // A photo-shaped rect whose blurred shadow peeks out; the photo then covers the rect itself.
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            shadowPaint.color = Color.BLACK
            shadowPaint.setShadowLayer(
                1f + border.shadow * 0.06f * shortSide,
                0f,
                border.shadow * 0.02f * shortSide,
                Color.argb((border.shadow * 170).roundToInt(), 0, 0, 0),
            )
            canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        }

        // A bitmap shader keeps rounded edges anti-aliased (clipPath wouldn't on a software canvas).
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        matrix.setRectToRect(crop, rect, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    /**
     * A small blurred, slightly darkened centre crop of [source] with the aspect ratio of
     * [width] x [height] (cheap), to be stretched over that area. Strength 0–1 → radius 2–44 on
     * the 300 px work image; 1/3 gives the original 16.
     */
    private fun blurredCover(source: Bitmap, width: Float, height: Float, strength: Float): Bitmap {
        val radius = (2 + strength * 42).roundToInt()
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

        val blurred = StackBlur.blur(cropped, radius)
        // Darken to 85% so the photo stands out from its background.
        Canvas(blurred).drawColor(Color.argb(38, 0, 0, 0))

        if (scaled !== source) scaled.recycle()
        if (cropped !== scaled) cropped.recycle()
        return blurred
    }

    /**
     * Saturation, warmth, contrast, brightness and fade (in that order) as one colour filter;
     * null when they're all unchanged.
     */
    fun colorFilter(adjustments: Adjustments): ColorMatrixColorFilter? {
        val a = adjustments
        if (a.brightness == 1f && a.saturation == 1f && a.contrast == 1f && a.warmth == 0f && a.fade == 0f) return null
        val matrix = ColorMatrix()
        matrix.setSaturation(a.saturation)
        if (a.warmth != 0f) {
            // Warmer: more red, a touch more green, less blue; cooler the other way round.
            matrix.postConcat(channelMatrix(1 + 0.12f * a.warmth, 1 + 0.03f * a.warmth, 1 - 0.12f * a.warmth, 0f))
        }
        if (a.contrast != 1f) {
            // Stretch around mid-grey.
            matrix.postConcat(channelMatrix(a.contrast, a.contrast, a.contrast, 128f * (1 - a.contrast)))
        }
        if (a.brightness != 1f) {
            matrix.postConcat(channelMatrix(a.brightness, a.brightness, a.brightness, 0f))
        }
        if (a.fade > 0f) {
            // Black rises to 46 (at full fade) while white stays nearly white: a matte finish.
            val k = 0.2f * a.fade
            matrix.postConcat(channelMatrix(1 - k, 1 - k, 1 - k, 230f * k))
        }
        return ColorMatrixColorFilter(matrix)
    }

    /** Scales red, green and blue, then adds [offset] (0–255) to each. */
    private fun channelMatrix(red: Float, green: Float, blue: Float, offset: Float) = ColorMatrix(
        floatArrayOf(
            red, 0f, 0f, 0f, offset,
            0f, green, 0f, 0f, offset,
            0f, 0f, blue, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /** Darkens the corners of [bitmap] in place; the middle stays as it is. */
    private fun applyVignette(bitmap: Bitmap, amount: Float) {
        drawVignette(Canvas(bitmap), RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), amount)
    }

    /**
     * Darkens towards the corners of [area], which may reach beyond the canvas (one slide of a
     * panorama gets its part of the whole panorama's vignette).
     */
    fun drawVignette(canvas: Canvas, area: RectF, amount: Float) {
        val w = area.width()
        val h = area.height()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            area.centerX(), area.centerY(), sqrt(w * w + h * h) / 2,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((amount * 210).roundToInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area, paint)
    }

    private fun applyColorAdjustments(bitmap: Bitmap, adjustments: Adjustments): Bitmap {
        val filter = colorFilter(adjustments) ?: return bitmap

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = filter
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
