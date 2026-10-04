package com.squareify.app.processing

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import com.squareify.app.PlatformBitmap
import com.squareify.app.PlatformContext

/*
 * What the picture tests need, under android.graphics' names, so the same tests run on the phone
 * and on Windows.
 */

/** The app's Context on the phone; nothing much on Windows. */
expect fun testContext(): PlatformContext

object Color {
    const val BLACK = 0xFF000000.toInt()
    const val DKGRAY = 0xFF444444.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()

    fun alpha(color: Int) = color ushr 24
    fun red(color: Int) = (color shr 16) and 0xFF
    fun green(color: Int) = (color shr 8) and 0xFF
    fun blue(color: Int) = color and 0xFF
    fun argb(alpha: Int, red: Int, green: Int, blue: Int) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    fun rgb(red: Int, green: Int, blue: Int) = argb(255, red, green, blue)
}

/** A [width] x [height] picture in one [color] (opaque). */
fun picture(color: Int, width: Int, height: Int): PlatformBitmap =
    ImageBitmap(width, height).also { Canvas(it).fill(color, width.toFloat(), height.toFloat()) }.asPlatformBitmap()

val PlatformBitmap.width: Int get() = asImage().width
val PlatformBitmap.height: Int get() = asImage().height

/** The pixel at ([x], [y]) as ARGB, not premultiplied (Android's Bitmap.getPixel). */
fun PlatformBitmap.getPixel(x: Int, y: Int): Int {
    val pixel = IntArray(1)
    asImage().readPixels(pixel, x, y, 1, 1)
    return pixel[0]
}

/** A colour as hex, for messages. */
fun hex(color: Int): String = color.toUInt().toString(16)

// JUnit's argument order (message first), which the picture tests were written with.
fun assertTrue(message: String, condition: Boolean) = kotlin.test.assertTrue(condition, message)
fun assertFalse(message: String, condition: Boolean) = kotlin.test.assertFalse(condition, message)

/** A [width] x [height] picture drawn by [draw] (clear to start with). */
fun pictureOf(width: Int, height: Int, draw: Canvas.() -> Unit = {}): PlatformBitmap =
    ImageBitmap(width, height).also { Canvas(it).draw() }.asPlatformBitmap()
