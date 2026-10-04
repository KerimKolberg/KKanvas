package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.squareify.app.PlatformBitmap
import com.squareify.app.PlatformContext
import com.squareify.app.TextFont
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

@Volatile
private var appContext: Context? = null

actual fun initRenderers(context: PlatformContext) {
    appContext = context.applicationContext ?: context
}

actual fun PlatformBitmap.asImage(): ImageBitmap = asImageBitmap()

actual fun ImageBitmap.asPlatformBitmap(): PlatformBitmap = asAndroidBitmap()

actual fun ImageBitmap.recycle() {
    asAndroidBitmap().recycle()
}

actual fun imageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }.asImageBitmap()

actual fun decodeImage(bytes: ByteArray): ImageBitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })
        ?.asImageBitmap()
        ?: throw IllegalArgumentException("not a picture")

private fun TileMode.toAndroid(): android.graphics.Shader.TileMode = when (this) {
    TileMode.Repeated -> android.graphics.Shader.TileMode.REPEAT
    TileMode.Mirror -> android.graphics.Shader.TileMode.MIRROR
    TileMode.Decal -> android.graphics.Shader.TileMode.DECAL
    else -> android.graphics.Shader.TileMode.CLAMP
}

actual fun imageShader(
    image: ImageBitmap,
    tileMode: TileMode,
    scaleX: Float,
    scaleY: Float,
    translateX: Float,
    translateY: Float,
): Shader = BitmapShader(image.asAndroidBitmap(), tileMode.toAndroid(), tileMode.toAndroid()).apply {
    setLocalMatrix(Matrix().apply {
        setScale(scaleX, scaleY)
        postTranslate(translateX, translateY)
    })
}

actual fun Paint.setShadow(radius: Float, dx: Float, dy: Float, color: Int) {
    nativePaint.setShadowLayer(radius, dx, dy, color)
}

private val families = ConcurrentHashMap<TextFont, FontFamily>()

actual fun textFontFamily(font: TextFont): FontFamily = families.getOrPut(font) {
    val bytes = readResource("fonts/${font.fontFile}")
    val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
    buffer.flip()
    val slant = if (font.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT
    val builder = Font.Builder(buffer).setWeight(font.weight).setSlant(slant)
    // The variable fonts start out regular; bold ones are set to their weight.
    if (font.bold) builder.setFontVariationSettings("'wght' ${font.weight}")
    val family = android.graphics.fonts.FontFamily.Builder(builder.build()).build()
    val typeface = android.graphics.Typeface.CustomFallbackBuilder(family)
        .setStyle(FontStyle(font.weight, slant))
        // Letters the font lacks come from the phone's own font.
        .setSystemFallback("sans-serif")
        .build()
    FontFamily(Typeface(typeface))
}

private val measurers = ThreadLocal<TextMeasurer>()

internal actual fun textMeasurer(): TextMeasurer = measurers.get() ?: run {
    val context = checkNotNull(appContext) { "initRenderers(context) wasn't called" }
    TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr).also { measurers.set(it) }
}

private object Resources

internal actual fun readResource(path: String): ByteArray =
    checkNotNull(Resources::class.java.classLoader?.getResourceAsStream(path)) { "missing $path" }.use { it.readBytes() }
