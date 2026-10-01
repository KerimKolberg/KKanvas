package com.squareify.app.processing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.squareify.app.Watermark
import com.squareify.app.WatermarkCorner
import com.squareify.app.WatermarkImages
import kotlin.math.min
import kotlin.math.roundToInt

/** Draws the kk logo into a corner. */
object WatermarkRenderer {

    /**
     * Draws [watermark] in a corner of [area] (the picture, or a whole panorama strip when drawing
     * one slide of it); its size follows the area's shorter side.
     */
    fun draw(canvas: Canvas, watermark: Watermark, area: RectF) {
        if (!watermark.enabled) return
        val image = WatermarkImages[watermark.mark] ?: return
        val reference = min(area.width(), area.height())
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The artwork is white: tint it, keeping its soft edges.
        paint.colorFilter = PorterDuffColorFilter(watermark.color, PorterDuff.Mode.SRC_IN)
        paint.alpha = (watermark.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmap(image, null, RectF(left, top, left + width, top + height), paint)
    }
}
