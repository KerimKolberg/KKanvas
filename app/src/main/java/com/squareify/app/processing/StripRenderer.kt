package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.squareify.app.FrameSettings

/**
 * Pictures that span several carousel slides (panoramas, carousels): drawn as one long strip,
 * either whole (previews) or one slide at a time (saving). Looks, text and watermark belong to
 * the strip as a whole, so the slides join up seamlessly.
 */
internal object StripRenderer {

    /** Draws the strip's content (background and photos) onto a canvas of the given size. */
    fun interface Content {
        fun draw(canvas: Canvas, stripWidth: Float, stripHeight: Float)
    }

    /** The whole strip at [width] x [height]. */
    fun renderStrip(settings: FrameSettings, width: Int, height: Int, content: Content): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(output), width.toFloat(), height.toFloat())
        return PhotoProcessor.applyAdjustments(output, settings.adjustments).also { PhotoProcessor.drawOverlays(it, settings) }
    }

    /**
     * Slide [index] (from 0) of [slides] at [slideWidth] x [slideHeight]. Drawn on its own so ten
     * slides never have to fit in memory at once.
     */
    fun renderSlide(
        settings: FrameSettings,
        slides: Int,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
        content: Content,
    ): Bitmap {
        val stripWidth = slideWidth.toFloat() * slides
        val shift = -index * slideWidth.toFloat()
        val output = Bitmap.createBitmap(slideWidth, slideHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.translate(shift, 0f)
        content.draw(canvas, stripWidth, slideHeight.toFloat())
        // The vignette belongs to the whole strip, not to each slide.
        val vignette = settings.adjustments.vignette
        val result = PhotoProcessor.applyAdjustments(output, settings.adjustments.copy(vignette = 0f))
        val strip = RectF(0f, 0f, stripWidth, slideHeight.toFloat())
        val stripCanvas = Canvas(result).apply { translate(shift, 0f) }
        if (vignette > 0f) PhotoProcessor.drawVignette(stripCanvas, strip, vignette)
        // Texture, text and watermark sit on the strip as a whole too: text may run across slides.
        Textures.draw(stripCanvas, settings.texture, strip)
        TextRenderer.draw(stripCanvas, settings.text, strip)
        WatermarkRenderer.draw(stripCanvas, settings.watermark, strip)
        return result
    }
}
