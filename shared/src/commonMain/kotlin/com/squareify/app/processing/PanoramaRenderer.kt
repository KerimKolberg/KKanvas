package com.squareify.app.processing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import com.squareify.app.Border
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.PlatformBitmap
import com.squareify.app.panoramaPlacement

/** Draws a panorama strip: the background, then the photo filled or fitted across all slides. */
object PanoramaRenderer {

    /** The whole strip at [width] x [height], for previews. */
    fun renderStrip(source: PlatformBitmap, panorama: Panorama, settings: FrameSettings, width: Int, height: Int): PlatformBitmap =
        StripRenderer.renderStrip(settings, width, height, content(source.asImage(), panorama, settings)).asPlatformBitmap()

    /** Slide [index] (from 0) at [slideWidth] x [slideHeight]. */
    fun renderSlide(
        source: PlatformBitmap,
        panorama: Panorama,
        settings: FrameSettings,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
    ): PlatformBitmap = StripRenderer.renderSlide(
        settings, panorama.slides, index, slideWidth, slideHeight, content(source.asImage(), panorama, settings),
    ).asPlatformBitmap()

    private fun content(source: ImageBitmap, panorama: Panorama, settings: FrameSettings) =
        StripRenderer.Content { canvas, width, height ->
            PhotoProcessor.drawBackground(canvas, source, settings, width.toInt(), height.toInt())
            val (crop, dst) = panoramaPlacement(source.width, source.height, panorama, width, height)
            PhotoProcessor.drawImage(
                canvas,
                source,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                Rect(dst.left, dst.top, dst.right, dst.bottom),
                Border(),
            )
        }
}
