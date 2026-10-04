package com.squareify.app.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import com.squareify.app.Adjustments
import com.squareify.app.Border
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import com.squareify.app.FrameStyle
import com.squareify.app.GradientDirection
import com.squareify.app.PaddingStyle
import com.squareify.app.PhotoShape
import com.squareify.app.PlatformBitmap
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

    /** The canvas for a [width] x [height] photo, with room for its frame style and margin. */
    fun canvasSize(width: Int, height: Int, settings: FrameSettings): Pair<Int, Int> {
        val frame = frameInsets(settings.border.frame, width.toFloat(), height.toFloat())
        return canvasSize(
            (width + frame.left + frame.right).roundToInt(),
            (height + frame.top + frame.bottom).roundToInt(),
            settings.format,
            settings.border.margin * MAX_MARGIN,
        )
    }

    /**
     * How far a frame style reaches beyond a [photoWidth] x [photoHeight] photo on each side,
     * in pixels (left, top, right, bottom); it scales with the photo's shorter side.
     */
    private fun frameInsets(style: FrameStyle, photoWidth: Float, photoHeight: Float): Rect {
        val s = min(photoWidth, photoHeight)
        return when (style) {
            FrameStyle.NONE -> Rect.Zero
            FrameStyle.THIN -> Rect(0.035f * s, 0.035f * s, 0.035f * s, 0.035f * s)
            // The classic deep bottom edge, for writing on.
            FrameStyle.POLAROID -> Rect(0.06f * s, 0.06f * s, 0.06f * s, 0.26f * s)
            // Sprocket bands along the long sides, like a strip of 35 mm film.
            FrameStyle.FILM -> if (photoWidth >= photoHeight) {
                Rect(0.03f * s, 0.17f * s, 0.03f * s, 0.17f * s)
            } else {
                Rect(0.17f * s, 0.03f * s, 0.17f * s, 0.03f * s)
            }
        }
    }

    /** Pads [source] to its format at natural resolution (capped at [MAX_OUTPUT_PIXELS]). */
    fun frame(source: PlatformBitmap, settings: FrameSettings): PlatformBitmap {
        val image = source.asImage()
        val (w, h) = canvasSize(image.width, image.height, settings)
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
    fun frameFitting(source: PlatformBitmap, settings: FrameSettings, maxSide: Int): PlatformBitmap {
        val image = source.asImage()
        val (w, h) = canvasSize(image.width, image.height, settings)
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
    fun frame(source: PlatformBitmap, settings: FrameSettings, width: Int, height: Int): PlatformBitmap =
        frame(source.asImage(), settings, width, height).asPlatformBitmap()

    internal fun frame(source: ImageBitmap, settings: FrameSettings, width: Int, height: Int): ImageBitmap {
        val output = ImageBitmap(width, height)
        val canvas = Canvas(output)

        drawBackground(canvas, source, settings, width, height)
        val inset = marginInset(settings, width, height)
        val border = settings.border
        val frame = frameInsets(border.frame, source.width.toFloat(), source.height.toFloat())
        // The photo plus its frame, as large as fits inside the margin, centred.
        val outer = fitCentered(
            source.width + frame.left + frame.right,
            source.height + frame.top + frame.bottom,
            Rect(inset, inset, width - inset, height - inset),
        )
        val scale = outer.width / (source.width + frame.left + frame.right)
        val photo = Rect(
            outer.left + frame.left * scale,
            outer.top + frame.top * scale,
            outer.right - frame.right * scale,
            outer.bottom - frame.bottom * scale,
        )
        if (border.frame == FrameStyle.NONE) {
            drawImage(canvas, source, source.bounds(), photo, border, border.shape)
        } else {
            drawFrame(canvas, outer, photo, border)
            drawImage(canvas, source, source.bounds(), photo, Border())
        }

        return applyAdjustments(output, settings.adjustments).also { drawOverlays(it, settings) }
    }

    /** A [width] x [height] box scaled to fit [area], centred. */
    private fun fitCentered(width: Float, height: Float, area: Rect): Rect {
        val scale = min(area.width / width, area.height / height)
        val w = width * scale
        val h = height * scale
        val left = area.left + (area.width - w) / 2
        val top = area.top + (area.height - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** The paint for a shadow under a shape [shortSide] across, [shadow] 0–1 strong. */
    private fun shadowPaint(shadow: Float, shortSide: Float): Paint = paint(argb(255, 0, 0, 0)).apply {
        setShadow(
            1f + shadow * 0.06f * shortSide,
            0f,
            shadow * 0.02f * shortSide,
            argb((shadow * 170).roundToInt(), 0, 0, 0),
        )
    }

    /** The frame around [photo]: card or film filling [outer], with the border's corners and shadow. */
    private fun drawFrame(canvas: Canvas, outer: Rect, photo: Rect, border: Border) {
        val radius = cornerRadius(border, outer)
        val shortSide = min(outer.width, outer.height)
        if (border.shadow > 0f) {
            canvas.drawRoundRect(outer.left, outer.top, outer.right, outer.bottom, radius, radius, shadowPaint(border.shadow, shortSide))
        }
        val fill = paint(
            when (border.frame) {
                FrameStyle.POLAROID -> rgb(250, 248, 242)
                FrameStyle.FILM -> rgb(20, 20, 20)
                else -> rgb(255, 255, 255)
            }
        )
        canvas.drawRoundRect(outer.left, outer.top, outer.right, outer.bottom, radius, radius, fill)
        if (border.frame == FrameStyle.FILM) drawSprocketHoles(canvas, outer, photo)
    }

    /** Rows of film perforations in the two bands beside the photo's long sides. */
    private fun drawSprocketHoles(canvas: Canvas, outer: Rect, photo: Rect) {
        val paint = paint(rgb(236, 236, 236))
        val horizontal = photo.width >= photo.height
        val band = if (horizontal) photo.top - outer.top else photo.left - outer.left
        val length = if (horizontal) outer.width else outer.height
        // Real 35 mm holes: a little longer along the film than across it.
        val along = band * 0.5f
        val across = band * 0.36f
        val pitch = band * 0.85f
        val count = ((length - band * 0.3f) / pitch).toInt().coerceAtLeast(1)
        val start = (length - (count - 1) * pitch) / 2
        val radius = across * 0.2f
        for (i in 0 until count) {
            val c = start + i * pitch
            if (horizontal) {
                val x = outer.left + c
                val topY = outer.top + band / 2
                val bottomY = outer.bottom - band / 2
                canvas.drawRoundRect(x - along / 2, topY - across / 2, x + along / 2, topY + across / 2, radius, radius, paint)
                canvas.drawRoundRect(x - along / 2, bottomY - across / 2, x + along / 2, bottomY + across / 2, radius, radius, paint)
            } else {
                val y = outer.top + c
                val leftX = outer.left + band / 2
                val rightX = outer.right - band / 2
                canvas.drawRoundRect(leftX - across / 2, y - along / 2, leftX + across / 2, y + along / 2, radius, radius, paint)
                canvas.drawRoundRect(rightX - across / 2, y - along / 2, rightX + across / 2, y + along / 2, radius, radius, paint)
            }
        }
    }

    /** Draws the texture, the caption and the watermark, if any, over the whole of [image]. */
    internal fun drawOverlays(image: ImageBitmap, settings: FrameSettings) {
        val area = image.bounds()
        val canvas = Canvas(image)
        Textures.draw(canvas, settings.texture, area)
        TextRenderer.draw(canvas, settings.text, area)
        WatermarkRenderer.draw(canvas, settings.watermark, area)
    }

    /**
     * Only the background of a [width] x [height] picture (blurred from [first], if that's the
     * style), for editors that draw the photos live on top.
     */
    fun background(first: PlatformBitmap?, settings: FrameSettings, width: Int, height: Int): PlatformBitmap {
        val output = ImageBitmap(width, height)
        val canvas = Canvas(output)
        if (first != null) {
            drawBackground(canvas, first.asImage(), settings, width, height)
        } else {
            canvas.fill(settings.bgColor, width.toFloat(), height.toFloat())
        }
        return output.asPlatformBitmap()
    }

    /** Only the caption and watermark, on a clear [width] x [height] picture; null if there are neither. */
    fun captionAndWatermark(settings: FrameSettings, width: Int, height: Int): PlatformBitmap? {
        if (settings.text == null && !settings.watermark.enabled) return null
        val output = ImageBitmap(width, height)
        val canvas = Canvas(output)
        TextRenderer.draw(canvas, settings.text, output.bounds())
        WatermarkRenderer.draw(canvas, settings.watermark, output.bounds())
        return output.asPlatformBitmap()
    }

    /** The margin on each side in pixels, for a [width] x [height] canvas. */
    fun marginInset(settings: FrameSettings, width: Int, height: Int): Float =
        settings.border.margin * MAX_MARGIN * min(width, height)

    /** [bitmap] with [adjustments]; [bitmap] may be recycled and must be mutable. */
    fun applyAdjustments(bitmap: PlatformBitmap, adjustments: Adjustments): PlatformBitmap =
        applyAdjustments(bitmap.asImage(), adjustments).asPlatformBitmap()

    /**
     * Colour ([colorFilter]), then sharpening, grain and vignette. May recycle [image] and return
     * a new one; [image] must be mutable.
     */
    internal fun applyAdjustments(image: ImageBitmap, adjustments: Adjustments): ImageBitmap {
        var result = applyColorAdjustments(image, adjustments)
        if (adjustments.sharpness > 0f) {
            result = applySharpen(result, adjustments.sharpness)
        }
        if (adjustments.grain > 0f) {
            result = applyGrain(result, adjustments.grain)
        }
        if (adjustments.vignette > 0f) {
            drawVignette(Canvas(result), result.bounds(), adjustments.vignette)
        }
        return result
    }

    fun drawBackground(canvas: Canvas, source: ImageBitmap, settings: FrameSettings, width: Int, height: Int) {
        when (settings.paddingStyle) {
            PaddingStyle.SOLID -> canvas.fill(settings.bgColor, width.toFloat(), height.toFloat())
            PaddingStyle.GRADIENT -> {
                val (endX, endY) = when (settings.gradientDirection) {
                    GradientDirection.VERTICAL -> 0f to height.toFloat()
                    GradientDirection.HORIZONTAL -> width.toFloat() to 0f
                    GradientDirection.DIAGONAL -> width.toFloat() to height.toFloat()
                }
                val paint = Paint()
                paint.shader = LinearGradientShader(
                    Offset.Zero,
                    Offset(endX, endY),
                    listOf(Color(settings.bgColor), Color(settings.bgColor2)),
                    tileMode = TileMode.Clamp,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            PaddingStyle.BLUR -> {
                val blurred = blurredCover(source, width.toFloat(), height.toFloat(), settings.blurStrength)
                canvas.drawPicture(blurred, blurred.bounds(), Rect(0f, 0f, width.toFloat(), height.toFloat()))
                blurred.recycle()
            }
        }
    }

    /**
     * A collage cell fitted with Blurred style: fills [rect] with a blurred copy of the cell's own
     * [source] (with the border's corners and shadow); the whole picture then goes on top.
     */
    internal fun drawBlurredFill(canvas: Canvas, source: ImageBitmap, rect: Rect, settings: FrameSettings, shape: PhotoShape = PhotoShape.RECTANGLE) {
        val blurred = blurredCover(source, rect.width, rect.height, settings.blurStrength)
        drawImage(canvas, blurred, blurred.bounds(), rect, settings.border, shape)
        blurred.recycle()
    }

    /** Corner radius in pixels of a picture drawn into [rect]. */
    fun cornerRadius(border: Border, rect: Rect): Float =
        border.cornerRadius * MAX_CORNER_RADIUS * min(rect.width, rect.height)

    /**
     * Draws the [crop] of [source] into [rect], with the border's rounded corners and shadow.
     * Also used for each cell of a collage.
     */
    internal fun drawImage(
        canvas: Canvas,
        source: ImageBitmap,
        crop: Rect,
        rect: Rect,
        border: Border,
        shape: PhotoShape = PhotoShape.RECTANGLE,
        /** Colour changes for this photo alone (e.g. one cell of a collage). */
        colorFilter: ColorFilter? = null,
    ) {
        val outline = PhotoShapes.path(shape, rect, cornerRadius(border, rect))

        if (border.shadow > 0f) {
            // A photo-shaped rect whose blurred shadow peeks out; the photo then covers the rect itself.
            canvas.drawPath(outline, shadowPaint(border.shadow, min(rect.width, rect.height)))
        }

        // An image shader keeps the outline anti-aliased (clipping wouldn't on a software canvas).
        val scaleX = rect.width / crop.width
        val scaleY = rect.height / crop.height
        val paint = Paint()
        paint.shader = imageShader(source, TileMode.Clamp, scaleX, scaleY, rect.left - crop.left * scaleX, rect.top - crop.top * scaleY)
        paint.colorFilter = colorFilter
        canvas.drawPath(outline, paint)
    }

    /**
     * A small blurred, slightly darkened centre crop of [source] with the aspect ratio of
     * [width] x [height] (cheap), to be stretched over that area. Strength 0–1 → radius 2–44 on
     * the 300 px work image; 1/3 gives the original 16.
     */
    private fun blurredCover(source: ImageBitmap, width: Float, height: Float, strength: Float): ImageBitmap {
        val radius = (2 + strength * 42).roundToInt()
        val workScale = min(1f, 300f / max(width, height))
        val workW = (width * workScale).roundToInt().coerceAtLeast(1)
        val workH = (height * workScale).roundToInt().coerceAtLeast(1)
        val scale = max(workW.toFloat() / source.width, workH.toFloat() / source.height)
        val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = scaledImage(source, scaledW, scaledH)

        val cropX = ((scaledW - workW) / 2).coerceIn(0, max(0, scaledW - 1))
        val cropY = ((scaledH - workH) / 2).coerceIn(0, max(0, scaledH - 1))
        val cropW = min(workW, scaledW - cropX).coerceAtLeast(1)
        val cropH = min(workH, scaledH - cropY).coerceAtLeast(1)
        val cropped = croppedImage(scaled, cropX, cropY, cropW, cropH)

        val blurred = StackBlur.blur(cropped, radius)
        // Darken to 85% so the photo stands out from its background.
        Canvas(blurred).fill(argb(38, 0, 0, 0), blurred.width.toFloat(), blurred.height.toFloat())

        if (scaled !== source) scaled.recycle()
        if (cropped !== scaled) cropped.recycle()
        return blurred
    }

    /**
     * Saturation, warmth, contrast, brightness and fade (in that order) as one colour filter;
     * null when they're all unchanged.
     */
    fun colorFilter(adjustments: Adjustments): ColorFilter? = colorMatrix(adjustments)?.toFilter()

    /**
     * A photo's own colour changes followed by the whole post's, as one filter: what live previews
     * show for a photo of a collage or carousel.
     */
    fun colorFilter(photo: Adjustments, whole: Adjustments): ColorFilter? {
        val first = colorMatrix(photo)
        val second = colorMatrix(whole)
        val combined = when {
            first == null -> second
            second == null -> first
            else -> concat(second, first)
        }
        return combined?.toFilter()
    }

    private fun FloatArray.toFilter(): ColorFilter = ColorFilter.colorMatrix(ColorMatrix(this))

    /** The 4x5 colour matrix (offsets in 0–255), built exactly as Android's ColorMatrix would. */
    private fun colorMatrix(adjustments: Adjustments): FloatArray? {
        val a = adjustments
        if (a.brightness == 1f && a.saturation == 1f && a.contrast == 1f && a.warmth == 0f && a.fade == 0f) return null
        var matrix = saturationMatrix(a.saturation)
        if (a.warmth != 0f) {
            // Warmer: more red, a touch more green, less blue; cooler the other way round.
            matrix = concat(channelMatrix(1 + 0.12f * a.warmth, 1 + 0.03f * a.warmth, 1 - 0.12f * a.warmth, 0f), matrix)
        }
        if (a.contrast != 1f) {
            // Stretch around mid-grey.
            matrix = concat(channelMatrix(a.contrast, a.contrast, a.contrast, 128f * (1 - a.contrast)), matrix)
        }
        if (a.brightness != 1f) {
            matrix = concat(channelMatrix(a.brightness, a.brightness, a.brightness, 0f), matrix)
        }
        if (a.fade > 0f) {
            // Black rises to 46 (at full fade) while white stays nearly white: a matte finish.
            val k = 0.2f * a.fade
            matrix = concat(channelMatrix(1 - k, 1 - k, 1 - k, 230f * k), matrix)
        }
        return matrix
    }

    /** Android's ColorMatrix.setSaturation. */
    private fun saturationMatrix(saturation: Float): FloatArray {
        val inverse = 1 - saturation
        val r = 0.213f * inverse
        val g = 0.715f * inverse
        val b = 0.072f * inverse
        return floatArrayOf(
            r + saturation, g, b, 0f, 0f,
            r, g + saturation, b, 0f, 0f,
            r, g, b + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** [first] then [then] as one matrix: Android's ColorMatrix.setConcat(then, first). */
    private fun concat(then: FloatArray, first: FloatArray): FloatArray {
        val result = FloatArray(20)
        var index = 0
        for (j in 0 until 20 step 5) {
            for (i in 0 until 4) {
                result[index++] = then[j] * first[i] + then[j + 1] * first[i + 5] + then[j + 2] * first[i + 10] + then[j + 3] * first[i + 15]
            }
            result[index++] = then[j] * first[4] + then[j + 1] * first[9] + then[j + 2] * first[14] + then[j + 3] * first[19] + then[j + 4]
        }
        return result
    }

    /** Scales red, green and blue, then adds [offset] (0–255) to each. */
    private fun channelMatrix(red: Float, green: Float, blue: Float, offset: Float) = floatArrayOf(
        red, 0f, 0f, 0f, offset,
        0f, green, 0f, 0f, offset,
        0f, 0f, blue, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * Darkens towards the corners of [area], which may reach beyond the canvas (one slide of a
     * panorama gets its part of the whole panorama's vignette).
     */
    internal fun drawVignette(canvas: Canvas, area: Rect, amount: Float) {
        val w = area.width
        val h = area.height
        val paint = Paint()
        paint.shader = RadialGradientShader(
            area.center,
            sqrt(w * w + h * h) / 2,
            listOf(Color.Transparent, Color.Transparent, Color(argb((amount * 210).roundToInt(), 0, 0, 0))),
            listOf(0f, 0.45f, 1f),
            TileMode.Clamp,
        )
        canvas.drawRect(area, paint)
    }

    private fun applyColorAdjustments(image: ImageBitmap, adjustments: Adjustments): ImageBitmap {
        val filter = colorFilter(adjustments) ?: return image

        val result = ImageBitmap(image.width, image.height)
        val paint = Paint()
        paint.colorFilter = filter
        Canvas(result).drawImage(image, Offset.Zero, paint)
        image.recycle()
        return result
    }

    /** 3x3 cross-shaped sharpening kernel: centre 4s+1, the four neighbours -s. */
    private fun applySharpen(image: ImageBitmap, amount: Float): ImageBitmap {
        val w = image.width
        val h = image.height
        val strength = amount * 1.5f
        val w1 = -strength
        val w4 = 4f * strength + 1f

        val src = IntArray(w * h)
        image.readPixels(src)
        val dst = src.copyOf()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val top = src[idx - w]
                val bottom = src[idx + w]
                val left = src[idx - 1]
                val right = src[idx + 1]
                val center = src[idx]

                val r = red(top) * w1 + red(left) * w1 + red(center) * w4 + red(right) * w1 + red(bottom) * w1
                val g = green(top) * w1 + green(left) * w1 + green(center) * w4 + green(right) * w1 + green(bottom) * w1
                val b = blue(top) * w1 + blue(left) * w1 + blue(center) * w4 + blue(right) * w1 + blue(bottom) * w1

                dst[idx] = argb(
                    alpha(center),
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255),
                )
            }
        }

        val result = imageFromPixels(dst, w, h)
        image.recycle()
        return result
    }

    /** Overlays random grey noise with a slightly varying alpha. */
    private fun applyGrain(image: ImageBitmap, amount: Float): ImageBitmap {
        val w = image.width
        val h = image.height
        val intensity = (255 * amount).toInt()

        val noisePixels = IntArray(w * h)
        val rnd = Random.Default
        for (i in noisePixels.indices) {
            val v = rnd.nextInt(256)
            val a = (intensity * (rnd.nextFloat() * 0.4f + 0.6f)).toInt().coerceIn(0, 255)
            noisePixels[i] = argb(a, v, v, v)
        }
        val noise = imageFromPixels(noisePixels, w, h)

        val result = ImageBitmap(w, h)
        val canvas = Canvas(result)
        canvas.drawImage(image, Offset.Zero, Paint())
        val paint = Paint()
        paint.blendMode = BlendMode.Overlay
        canvas.drawImage(noise, Offset.Zero, paint)

        image.recycle()
        noise.recycle()
        return result
    }
}
