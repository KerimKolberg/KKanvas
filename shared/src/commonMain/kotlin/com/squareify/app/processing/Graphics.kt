package com.squareify.app.processing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.squareify.app.PlatformBitmap
import com.squareify.app.PlatformContext
import com.squareify.app.TextFont
import kotlin.math.pow

/*
 * All drawing is done with Compose's graphics (ImageBitmap, Canvas, Paint, Path, shaders), which
 * are Android's own graphics on the phone and Skia on Windows. These are the few things that API
 * doesn't cover; each platform has a small version of its own.
 */

/** Call once at start-up, before drawing text (the phone's text needs the app's Context). */
expect fun initRenderers(context: PlatformContext)

/** The picture as Compose sees it, without a copy. */
expect fun PlatformBitmap.asImage(): ImageBitmap

/** The other way round, without a copy. */
expect fun ImageBitmap.asPlatformBitmap(): PlatformBitmap

/** Frees the pixels now rather than whenever the garbage collector gets to them (big photos). */
expect fun ImageBitmap.recycle()

/** A new picture (that can be drawn on) from ARGB pixels, not premultiplied, row by row. */
expect fun imageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** Decodes a PNG or JPEG. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap

/**
 * Paints [image] scaled by [scaleX] x [scaleY] and then moved by [translateX], [translateY],
 * filtered; beyond its edges as [tileMode] says.
 */
expect fun imageShader(
    image: ImageBitmap,
    tileMode: TileMode,
    scaleX: Float,
    scaleY: Float,
    translateX: Float,
    translateY: Float,
): Shader

/**
 * A soft shadow under whatever this paint draws, [dx], [dy] away, in [color]. [radius] is the
 * blur radius as Android's Paint.setShadowLayer has it.
 */
expect fun Paint.setShadow(radius: Float, dx: Float, dy: Float, color: Int)

/** The font a caption is drawn in, bundled with the app so it's the same everywhere. */
expect fun textFontFamily(font: TextFont): FontFamily

/** Measures text at 1 px per sp. One per thread. */
internal expect fun textMeasurer(): TextMeasurer

/** A file bundled with the shared code (fonts, watermark artwork). */
internal expect fun readResource(path: String): ByteArray

/** Android's Color.argb. */
internal fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

internal fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

internal fun alpha(color: Int): Int = color ushr 24
internal fun red(color: Int): Int = (color shr 16) and 0xFF
internal fun green(color: Int): Int = (color shr 8) and 0xFF
internal fun blue(color: Int): Int = color and 0xFF

/** A paint in [color] (anti-aliased, filtering pictures, as all of Compose's paints). */
internal fun paint(color: Int): Paint = Paint().apply { this.color = Color(color) }

/** Fills a [width] x [height] area from the origin, over what's there (Android's drawColor). */
internal fun Canvas.fill(color: Int, width: Float, height: Float) {
    drawRect(0f, 0f, width, height, paint(color))
}

/** Relative luminance, 0 (black) to 1 (white), as androidx's ColorUtils.calculateLuminance. */
internal fun luminance(color: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c < 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(red(color)) + 0.7152 * linear(green(color)) + 0.0722 * linear(blue(color))
}

/** The bundled font file of a caption font. */
internal val TextFont.fontFile: String
    get() = when (this) {
        TextFont.CLASSIC -> "Roboto.ttf"
        TextFont.ELEGANT -> "NotoSerif-Italic.ttf"
        TextFont.TYPEWRITER -> "CutiveMono-Regular.ttf"
        TextFont.HANDWRITTEN -> "DancingScript.ttf"
        TextFont.CASUAL -> "ComingSoon-Regular.ttf"
        TextFont.CONDENSED -> "RobotoCondensed.ttf"
    }

/** The weight a caption font is drawn at (the variable fonts are set to it). */
internal val TextFont.weight: Int get() = if (bold) 700 else 400

/** [source] resized to [width] x [height], filtered; [source] itself if it's that size already. */
internal fun scaledImage(source: ImageBitmap, width: Int, height: Int): ImageBitmap {
    if (width == source.width && height == source.height) return source
    val result = ImageBitmap(width, height)
    Canvas(result).drawImageRect(
        source,
        srcSize = IntSize(source.width, source.height),
        dstSize = IntSize(width, height),
        paint = Paint(),
    )
    return result
}

/** The [width] x [height] part of [source] at [x], [y]; [source] itself if that's all of it. */
internal fun croppedImage(source: ImageBitmap, x: Int, y: Int, width: Int, height: Int): ImageBitmap {
    if (x == 0 && y == 0 && width == source.width && height == source.height) return source
    val result = ImageBitmap(width, height)
    Canvas(result).drawImageRect(
        source,
        srcOffset = IntOffset(x, y),
        srcSize = IntSize(width, height),
        dstSize = IntSize(width, height),
        paint = Paint(),
    )
    return result
}

/** Draws the [crop] of [image] stretched over [dst], filtered (Android's drawBitmap with rectangles). */
internal fun Canvas.drawPicture(image: ImageBitmap, crop: Rect, dst: Rect, paint: Paint = Paint()) {
    val scaleX = dst.width / crop.width
    val scaleY = dst.height / crop.height
    paint.shader = imageShader(image, TileMode.Clamp, scaleX, scaleY, dst.left - crop.left * scaleX, dst.top - crop.top * scaleY)
    drawRect(dst, paint)
}

/** The whole of [image] as a rectangle. */
internal fun ImageBitmap.bounds(): Rect = Rect(0f, 0f, width.toFloat(), height.toFloat())
