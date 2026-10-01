package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.squareify.app.Border
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.panoramaPlacement

/**
 * Draws a panorama strip: the background, then the photo filled or fitted across all slides.
 * Adjustments apply to the strip as a whole, so the slides join up seamlessly.
 */
object PanoramaRenderer {

    /** The whole strip at [width] x [height], for previews. */
    fun renderStrip(source: Bitmap, panorama: Panorama, settings: FrameSettings, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawStrip(Canvas(output), source, panorama, settings, width.toFloat(), height.toFloat())
        return PhotoProcessor.applyAdjustments(output, settings.adjustments).also { PhotoProcessor.drawText(it, settings) }
    }

    /**
     * Slide [index] (from 0) at [slideWidth] x [slideHeight]. Drawn on its own so a 10-slide
     * panorama never has to fit in memory at once.
     */
    fun renderSlide(
        source: Bitmap,
        panorama: Panorama,
        settings: FrameSettings,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
    ): Bitmap {
        val stripWidth = slideWidth.toFloat() * panorama.slides
        val output = Bitmap.createBitmap(slideWidth, slideHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.translate(-index * slideWidth.toFloat(), 0f)
        drawStrip(canvas, source, panorama, settings, stripWidth, slideHeight.toFloat())
        // The vignette belongs to the whole panorama, not to each slide.
        val vignette = settings.adjustments.vignette
        val result = PhotoProcessor.applyAdjustments(output, settings.adjustments.copy(vignette = 0f))
        if (vignette > 0f) {
            val area = RectF(-index * slideWidth.toFloat(), 0f, stripWidth - index * slideWidth, slideHeight.toFloat())
            PhotoProcessor.drawVignette(Canvas(result), area, vignette)
        }
        // Text sits on the panorama as a whole too: it may run across slides.
        TextRenderer.draw(
            Canvas(result).apply { translate(-index * slideWidth.toFloat(), 0f) },
            settings.text,
            RectF(0f, 0f, stripWidth, slideHeight.toFloat()),
        )
        return result
    }

    private fun drawStrip(canvas: Canvas, source: Bitmap, panorama: Panorama, settings: FrameSettings, width: Float, height: Float) {
        PhotoProcessor.drawBackground(canvas, source, settings, width.toInt(), height.toInt())
        val (crop, dst) = panoramaPlacement(source.width, source.height, panorama, width, height)
        PhotoProcessor.drawImage(
            canvas,
            source,
            RectF(crop.left, crop.top, crop.right, crop.bottom),
            RectF(dst.left, dst.top, dst.right, dst.bottom),
            Border(),
        )
    }
}
