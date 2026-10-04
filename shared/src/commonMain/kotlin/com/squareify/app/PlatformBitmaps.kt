package com.squareify.app

import com.squareify.app.processing.asImage

/** A picture's size, the same way on the phone and on Windows. */
val PlatformBitmap.width: Int get() = asImage().width
val PlatformBitmap.height: Int get() = asImage().height

/** The pixel at ([x], [y]) as ARGB, not premultiplied. */
fun PlatformBitmap.pixelAt(x: Int, y: Int): Int {
    val pixel = IntArray(1)
    asImage().readPixels(pixel, x, y, 1, 1)
    return pixel[0]
}
