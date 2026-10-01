package com.squareify.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.Serializable

/** Which artwork goes in the corner (white on transparent, tinted when drawn). */
enum class WatermarkMark(val label: String, val drawable: Int) {
    LETTERS("KK letters", R.drawable.kk_monogram),
    LOGO("Full logo", R.drawable.kk_watermark),
}

enum class WatermarkCorner(val label: String) {
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right"),
}

/** The kk logo in a corner of everything saved; part of the settings for new media. */
data class Watermark(
    val enabled: Boolean = false,
    val mark: WatermarkMark = WatermarkMark.LETTERS,
    val corner: WatermarkCorner = WatermarkCorner.BOTTOM_RIGHT,
    /** 0–1: from 5% to 20% of the picture's shorter side wide. */
    val size: Float = 0.3f,
    val opacity: Float = 0.7f,
    val color: Int = Color.WHITE,
) : Serializable {
    companion object {
        /** The teal of the logo. */
        val TEAL = Color.rgb(53, 189, 185)
        val COLORS = listOf("White" to Color.WHITE, "Black" to Color.BLACK, "Teal" to TEAL)
    }
}

/** The watermark artwork, decoded once; renderers have no Context, so it's loaded at start-up. */
object WatermarkImages {
    private val images = mutableMapOf<WatermarkMark, Bitmap>()

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
