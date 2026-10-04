package com.squareify.app.processing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import com.squareify.app.Watermark
import com.squareify.app.WatermarkCorner
import com.squareify.app.WatermarkMark
import kotlin.math.min
import kotlin.math.roundToInt

/** Draws the kk logo into a corner. */
object WatermarkRenderer {

    /** The artwork (white on transparent, tinted when drawn), decoded once. */
    private val images: Map<WatermarkMark, ImageBitmap> by lazy {
        WatermarkMark.entries.associateWith { mark ->
            val file = when (mark) {
                WatermarkMark.LETTERS -> "kk_monogram.png"
                WatermarkMark.LOGO -> "kk_watermark.png"
            }
            decodeImage(readResource("watermark/$file"))
        }
    }

    /** The artwork for [mark], e.g. to show in the settings. */
    fun image(mark: WatermarkMark): ImageBitmap = images.getValue(mark)

    /**
     * Draws [watermark] in a corner of [area] (the picture, or a whole panorama strip when drawing
     * one slide of it); its size follows the area's shorter side.
     */
    fun draw(canvas: Canvas, watermark: Watermark, area: Rect) {
        if (!watermark.enabled) return
        val image = image(watermark.mark)
        val reference = min(area.width, area.height)
        val width = reference * (0.05f + 0.15f * watermark.size)
        val height = width * image.height / image.width
        val margin = reference * 0.04f
        val left = when (watermark.corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.BOTTOM_LEFT -> area.left + margin
            WatermarkCorner.TOP_RIGHT, WatermarkCorner.BOTTOM_RIGHT -> area.right - margin - width
        }
        val top = when (watermark.corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_RIGHT -> area.top + margin
            WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_RIGHT -> area.bottom - margin - height
        }
        val paint = Paint()
        // The artwork is white: tint it, keeping its soft edges.
        paint.colorFilter = ColorFilter.tint(Color(watermark.color), BlendMode.SrcIn)
        paint.alpha = (watermark.opacity.coerceIn(0f, 1f) * 255).roundToInt() / 255f
        canvas.drawPicture(image, image.bounds(), Rect(left, top, left + width, top + height), paint)
    }
}
