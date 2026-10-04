package com.squareify.app.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.squareify.app.PlatformBitmap
import com.squareify.app.PlatformContext
import com.squareify.app.TextFont
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.SamplingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

actual fun initRenderers(context: PlatformContext) = Unit

actual fun PlatformBitmap.asImage(): ImageBitmap = asComposeImageBitmap()

actual fun ImageBitmap.asPlatformBitmap(): PlatformBitmap = asSkiaBitmap()

actual fun ImageBitmap.recycle() {
    asSkiaBitmap().close()
}

actual fun imageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    // ARGB ints in little-endian memory are Skia's BGRA bytes; Skia then premultiplies as it draws.
    val bytes = ByteArray(pixels.size * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    val source = Bitmap()
    source.installPixels(info, bytes, width * 4)
    val result = ImageBitmap(width, height)
    Canvas(result).drawImage(source.asComposeImageBitmap(), Offset.Zero, Paint())
    source.close()
    return result
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

private fun TileMode.toSkia(): FilterTileMode = when (this) {
    TileMode.Repeated -> FilterTileMode.REPEAT
    TileMode.Mirror -> FilterTileMode.MIRROR
    TileMode.Decal -> FilterTileMode.DECAL
    else -> FilterTileMode.CLAMP
}

actual fun imageShader(
    image: ImageBitmap,
    tileMode: TileMode,
    scaleX: Float,
    scaleY: Float,
    translateX: Float,
    translateY: Float,
): Shader {
    val matrix = Matrix33(scaleX, 0f, translateX, 0f, scaleY, translateY, 0f, 0f, 1f)
    // Bilinear, as Android filters pictures drawn with a filtering paint.
    return Image.makeFromBitmap(image.asSkiaBitmap())
        .makeShader(tileMode.toSkia(), tileMode.toSkia(), SamplingMode.LINEAR, matrix)
        .asComposeShader()
}

actual fun Paint.setShadow(radius: Float, dx: Float, dy: Float, color: Int) {
    // Android turns the radius into a blur sigma this way (BlurMaskFilter's convertRadiusToSigma).
    val sigma = if (radius > 0f) 0.57735f * radius + 0.5f else 0f
    skiaPaint.imageFilter = ImageFilter.makeDropShadow(dx, dy, sigma, sigma, color)
}

private val families = ConcurrentHashMap<TextFont, FontFamily>()

actual fun textFontFamily(font: TextFont): FontFamily = families.getOrPut(font) {
    val bytes = readResource("fonts/${font.fontFile}")
    FontFamily(
        Font(
            identity = "kk-${font.name}",
            data = bytes,
            weight = FontWeight(font.weight),
            style = if (font.italic) FontStyle.Italic else FontStyle.Normal,
            // The variable fonts start out regular; bold ones are set to their weight.
            variationSettings = if (font.bold) FontVariation.Settings(FontVariation.weight(font.weight)) else FontVariation.Settings(),
        ),
    )
}

private val resolver by lazy { createFontFamilyResolver() }

private val measurers = ThreadLocal.withInitial { TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr) }

internal actual fun textMeasurer(): TextMeasurer = measurers.get()

private object Resources

internal actual fun readResource(path: String): ByteArray =
    checkNotNull(Resources::class.java.classLoader?.getResourceAsStream(path)) { "missing $path" }.use { it.readBytes() }
