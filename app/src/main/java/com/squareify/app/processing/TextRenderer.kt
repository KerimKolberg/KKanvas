package com.squareify.app.processing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.squareify.app.TextAlignment
import com.squareify.app.TextBackdrop
import com.squareify.app.TextOverlay
import com.squareify.app.typeface
import kotlin.math.max
import kotlin.math.min

/** Draws a [TextOverlay] onto a picture. */
object TextRenderer {

    /**
     * Draws [overlay] within [area] (the whole picture, or a whole panorama strip when drawing
     * one slide of it). Sizes follow the area's shorter side, so text looks the same at any
     * resolution.
     */
    fun draw(canvas: Canvas, overlay: TextOverlay?, area: RectF) {
        if (overlay == null || overlay.text.isBlank()) return
        val width = area.width()
        val height = area.height()
        val reference = min(width, height)
        val margin = reference * 0.05f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = overlay.font.typeface
        paint.textSize = reference * (0.03f + 0.12f * overlay.size)
        paint.color = overlay.color
        val textIsLight = ColorUtils.calculateLuminance(overlay.color) > 0.5
        if (overlay.backdrop == TextBackdrop.SHADOW) {
            // A soft shadow in the opposite tone to the text.
            val shadow = if (textIsLight) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)
            paint.setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.04f, shadow)
        }

        val text = overlay.text.trimEnd()
        val layoutWidth = max(1f, width - 2 * margin).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(
                when (overlay.alignment) {
                    TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                }
            )
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()

        val blockHeight = layout.height.toFloat()
        val left = area.left + margin
        val top = area.top + margin + (height - 2 * margin - blockHeight).coerceAtLeast(0f) * overlay.position.coerceIn(0f, 1f)

        if (overlay.backdrop == TextBackdrop.BOX) {
            // A rounded box around the lines, in the opposite tone to the text.
            var boxLeft = Float.MAX_VALUE
            var boxRight = 0f
            for (line in 0 until layout.lineCount) {
                boxLeft = min(boxLeft, layout.getLineLeft(line))
                boxRight = max(boxRight, layout.getLineRight(line))
            }
            val pad = paint.textSize * 0.35f
            val box = RectF(left + boxLeft - pad, top - pad * 0.6f, left + boxRight + pad, top + blockHeight + pad * 0.6f)
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            boxPaint.color = if (textIsLight) Color.argb(140, 0, 0, 0) else Color.argb(170, 255, 255, 255)
            canvas.drawRoundRect(box, pad, pad, boxPaint)
        }

        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
    }
}
