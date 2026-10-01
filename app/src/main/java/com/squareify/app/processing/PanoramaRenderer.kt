package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.RectF
import com.squareify.app.Border
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.panoramaPlacement

/** Draws a panorama strip: the background, then the photo filled or fitted across all slides. */
object PanoramaRenderer {

    /** The whole strip at [width] x [height], for previews. */
    fun renderStrip(source: Bitmap, panorama: Panorama, settings: FrameSettings, width: Int, height: Int): Bitmap =
        StripRenderer.renderStrip(settings, width, height, content(source, panorama, settings))

    /** Slide [index] (from 0) at [slideWidth] x [slideHeight]. */
    fun renderSlide(
        source: Bitmap,
        panorama: Panorama,
        settings: FrameSettings,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
    ): Bitmap = StripRenderer.renderSlide(settings, panorama.slides, index, slideWidth, slideHeight, content(source, panorama, settings))

    private fun content(source: Bitmap, panorama: Panorama, settings: FrameSettings) =
        StripRenderer.Content { canvas, width, height ->
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
