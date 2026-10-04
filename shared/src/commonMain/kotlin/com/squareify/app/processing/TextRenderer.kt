package com.squareify.app.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.squareify.app.TextAlignment
import com.squareify.app.TextBackdrop
import com.squareify.app.TextOverlay
import kotlin.math.max
import kotlin.math.min

/** Draws a [TextOverlay] onto a picture. */
object TextRenderer {

    /** Extra space between lines, as a share of a line's height. */
    private const val LINE_SPACING = 1.05f

    /**
     * Draws [overlay] within [area] (the whole picture, or a whole panorama strip when drawing
     * one slide of it). Sizes follow the area's shorter side, so text looks the same at any
     * resolution.
     */
    fun draw(canvas: Canvas, overlay: TextOverlay?, area: Rect) {
        if (overlay == null || overlay.text.isBlank()) return
        val width = area.width
        val height = area.height
        val reference = min(width, height)
        val margin = reference * 0.05f
        val textSize = reference * (0.03f + 0.12f * overlay.size)
        val textIsLight = luminance(overlay.color) > 0.5
        val shadow = if (overlay.backdrop == TextBackdrop.SHADOW) {
            // A soft shadow in the opposite tone to the text.
            Shadow(
                color = Color(if (textIsLight) argb(160, 0, 0, 0) else argb(160, 255, 255, 255)),
                offset = Offset(0f, textSize * 0.04f),
                blurRadius = textSize * 0.12f,
            )
        } else {
            null
        }
        val style = TextStyle(
            color = Color(overlay.color),
            fontSize = textSize.sp,
            fontFamily = textFontFamily(overlay.font),
            textAlign = when (overlay.alignment) {
                TextAlignment.LEFT -> TextAlign.Left
                TextAlignment.CENTER -> TextAlign.Center
                TextAlignment.RIGHT -> TextAlign.Right
            },
            shadow = shadow,
        )

        val layout = layout(overlay.text.trimEnd(), style, max(1f, width - 2 * margin).toInt())
        val blockHeight = layout.size.height.toFloat()
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
            val pad = textSize * 0.35f
            canvas.drawRoundRect(
                left + boxLeft - pad,
                top - pad * 0.6f,
                left + boxRight + pad,
                top + blockHeight + pad * 0.6f,
                pad,
                pad,
                paint(if (textIsLight) argb(140, 0, 0, 0) else argb(170, 255, 255, 255)),
            )
        }

        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(width, height)) {
            drawText(layout, topLeft = Offset(left, top))
        }
    }

    /**
     * [text] laid out [width] wide. Lines are [LINE_SPACING] times their natural height apart,
     * with the extra space below each line but the last (as Android's StaticLayout spaces them).
     */
    private fun layout(text: String, style: TextStyle, width: Int): TextLayoutResult {
        val measurer = textMeasurer()
        val constraints = Constraints(minWidth = width, maxWidth = width)
        val natural = measurer.measure(text, style, constraints = constraints)
        if (natural.lineCount < 2) return natural
        val lineHeight = (natural.getLineBottom(0) - natural.getLineTop(0)) * LINE_SPACING
        return measurer.measure(
            text,
            style.copy(
                lineHeight = lineHeight.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.LastLineBottom),
            ),
            constraints = constraints,
        )
    }
}
