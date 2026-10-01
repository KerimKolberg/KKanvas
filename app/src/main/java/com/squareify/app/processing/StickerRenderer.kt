package com.squareify.app.processing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.squareify.app.StickerKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Draws stickers as vector shapes into their box; used by the renderer and the live editor. */
object StickerRenderer {

    fun draw(canvas: Canvas, kind: StickerKind, color: Int, rect: RectF) {
        val w = rect.width()
        val h = rect.height()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            // A soft drop shadow makes them look stuck on.
            setShadowLayer(min(w, h) * 0.04f, 0f, min(w, h) * 0.02f, Color.argb(80, 0, 0, 0))
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(min(w, h) * 0.03f, 0f, min(w, h) * 0.015f, Color.argb(70, 0, 0, 0))
        }
        when (kind) {
            StickerKind.TAPE -> tape(canvas, rect, fill)
            StickerKind.HEART -> canvas.drawPath(heart(rect), fill)
            StickerKind.STAR -> canvas.drawPath(star(rect, points = 5, inner = 0.42f), fill)
            StickerKind.SPARKLE -> canvas.drawPath(sparkle(rect), fill)
            StickerKind.ARROW -> {
                stroke.strokeWidth = h * 0.09f
                canvas.drawPath(arrow(rect), stroke)
            }
            StickerKind.CIRCLE -> {
                stroke.strokeWidth = min(w, h) * 0.05f
                canvas.drawPath(scribbleCircle(rect), stroke)
            }
            StickerKind.UNDERLINE -> {
                stroke.strokeWidth = h * 0.2f
                canvas.drawPath(wave(rect), stroke)
            }
        }
    }

    /** Washi tape: a slightly see-through strip with torn zigzag ends and faint stripes. */
    private fun tape(canvas: Canvas, rect: RectF, fill: Paint) {
        val teeth = 6
        val bite = rect.width() * 0.025f
        val path = Path().apply {
            moveTo(rect.left, rect.top)
            lineTo(rect.right, rect.top)
            for (i in 1..teeth) lineTo(if (i % 2 == 1) rect.right - bite else rect.right, rect.top + rect.height() * i / teeth)
            lineTo(rect.left, rect.bottom)
            for (i in teeth - 1 downTo 0) lineTo(if (i % 2 == 1) rect.left + bite else rect.left, rect.top + rect.height() * i / teeth)
            close()
        }
        fill.alpha = 215
        canvas.drawPath(path, fill)
        val stripes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            strokeWidth = rect.height() * 0.12f
        }
        canvas.save()
        canvas.clipPath(path)
        var x = rect.left - rect.height()
        while (x < rect.right) {
            canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, stripes)
            x += rect.height() * 0.45f
        }
        canvas.restore()
    }

    private fun heart(r: RectF): Path = Path().apply {
        val w = r.width()
        val h = r.height()
        moveTo(r.left + w / 2, r.top + h * 0.28f)
        cubicTo(r.left + w * 0.38f, r.top, r.left, r.top + h * 0.06f, r.left + w * 0.04f, r.top + h * 0.38f)
        cubicTo(r.left + w * 0.08f, r.top + h * 0.62f, r.left + w * 0.32f, r.top + h * 0.78f, r.left + w / 2, r.bottom)
        cubicTo(r.left + w * 0.68f, r.top + h * 0.78f, r.left + w * 0.92f, r.top + h * 0.62f, r.right - w * 0.04f, r.top + h * 0.38f)
        cubicTo(r.right, r.top + h * 0.06f, r.left + w * 0.62f, r.top, r.left + w / 2, r.top + h * 0.28f)
        close()
    }

    private fun star(r: RectF, points: Int, inner: Float): Path = Path().apply {
        val cx = r.centerX()
        val cy = r.centerY() + r.height() * 0.04f
        val outerR = min(r.width(), r.height()) / 2
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outerR else outerR * inner
            val angle = -PI / 2 + i * PI / points
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** A four-pointed twinkle with curved sides. */
    private fun sparkle(r: RectF): Path = Path().apply {
        val cx = r.centerX()
        val cy = r.centerY()
        val rx = r.width() / 2
        val ry = r.height() / 2
        val pinch = 0.12f
        moveTo(cx, cy - ry)
        quadTo(cx + rx * pinch, cy - ry * pinch, cx + rx, cy)
        quadTo(cx + rx * pinch, cy + ry * pinch, cx, cy + ry)
        quadTo(cx - rx * pinch, cy + ry * pinch, cx - rx, cy)
        quadTo(cx - rx * pinch, cy - ry * pinch, cx, cy - ry)
        close()
    }

    /** A hand-drawn curved arrow pointing right. */
    private fun arrow(r: RectF): Path = Path().apply {
        val pad = r.height() * 0.12f
        val startX = r.left + pad
        val startY = r.bottom - pad * 2
        val endX = r.right - pad
        val endY = r.top + r.height() * 0.45f
        moveTo(startX, startY)
        quadTo(r.left + r.width() * 0.45f, r.top + pad, endX, endY)
        val head = r.height() * 0.3f
        moveTo(endX - head, endY - head * 0.75f)
        lineTo(endX, endY)
        lineTo(endX - head * 0.85f, endY + head * 0.6f)
    }

    /** A loose, slightly overshooting loop, as if circled with a pen. */
    private fun scribbleCircle(r: RectF): Path = Path().apply {
        val inset = min(r.width(), r.height()) * 0.08f
        val cx = r.centerX()
        val cy = r.centerY()
        val rx = r.width() / 2 - inset
        val ry = r.height() / 2 - inset
        val steps = 64
        for (i in 0..steps) {
            // A bit more than a full turn, drifting outwards so the ends don't meet.
            val t = i.toFloat() / steps
            val angle = PI * (0.85 + 2.25 * t)
            val grow = 0.94f + 0.1f * t
            val x = cx + (rx * grow * cos(angle)).toFloat()
            val y = cy + (ry * grow * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    /** A wavy underline. */
    private fun wave(r: RectF): Path = Path().apply {
        val pad = r.height() * 0.2f
        val amplitude = r.height() * 0.22f
        val steps = 60
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = r.left + pad + (r.width() - 2 * pad) * t
            val y = r.centerY() + (amplitude * sin(t * 3 * 2 * PI)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}
