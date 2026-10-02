package com.squareify.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The watermark artwork, decoded once; renderers have no Context, so it's loaded at start-up. */
object WatermarkImages {
    private val images = mutableMapOf<WatermarkMark, Bitmap>()

    private val WatermarkMark.drawable: Int
        get() = when (this) {
            WatermarkMark.LETTERS -> R.drawable.kk_monogram
            WatermarkMark.LOGO -> R.drawable.kk_watermark
        }

    fun load(context: Context) {
        synchronized(images) {
            if (images.isNotEmpty()) return
            val options = BitmapFactory.Options().apply { inScaled = false }
            WatermarkMark.entries.forEach { mark ->
                BitmapFactory.decodeResource(context.resources, mark.drawable, options)?.let { images[mark] = it }
            }
        }
    }

    operator fun get(mark: WatermarkMark): Bitmap? = synchronized(images) { images[mark] }
}
