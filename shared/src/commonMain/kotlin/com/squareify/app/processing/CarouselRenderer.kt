package com.squareify.app.processing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import com.squareify.app.Border
import com.squareify.app.Box
import com.squareify.app.Carousel
import com.squareify.app.FrameSettings
import com.squareify.app.FrameStyle
import com.squareify.app.Placement
import com.squareify.app.PlatformBitmap
import com.squareify.app.centerCrop
import com.squareify.app.printPhotoBox

/**
 * Draws a carousel: the background across all slides, then each photo where it was placed
 * (later ones on top), with the border's corners and shadow.
 */
object CarouselRenderer {

    /** The whole strip at [width] x [height]; [sources] are the photos in order (any resolution). */
    fun renderStrip(carousel: Carousel, sources: List<PlatformBitmap?>, settings: FrameSettings, width: Int, height: Int): PlatformBitmap =
        StripRenderer.renderStrip(settings, width, height, content(carousel, sources.map { it?.asImage() }, settings)).asPlatformBitmap()

    /** Slide [index] (from 0) at [slideWidth] x [slideHeight]. */
    fun renderSlide(
        carousel: Carousel,
        sources: List<PlatformBitmap?>,
        settings: FrameSettings,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
    ): PlatformBitmap = StripRenderer.renderSlide(
        settings, carousel.slides, index, slideWidth, slideHeight, content(carousel, sources.map { it?.asImage() }, settings),
    ).asPlatformBitmap()

    /** Where photo [index] goes on a strip [stripWidth] wide and [stripHeight] high, before rotation. */
    fun photoRect(carousel: Carousel, index: Int, stripWidth: Float, stripHeight: Float): Rect {
        val photo = carousel.photos[index]
        return placementRect(photo.placement, photo.boxAspect, carousel.slides, stripWidth, stripHeight)
    }

    /** A placement's box on a strip of [slides] slides, before rotation. */
    fun placementRect(placement: Placement, aspect: Float, slides: Int, stripWidth: Float, stripHeight: Float): Rect {
        val unit = stripWidth / slides
        val w = placement.width * unit
        val h = w / aspect
        val cx = placement.x * unit
        val cy = placement.y * stripHeight
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** The white card of a print, with a soft shadow (stronger with the border's shadow setting). */
    private fun drawPrint(canvas: Canvas, rect: Rect, border: Border) {
        val short = minOf(rect.width, rect.height)
        val paint = paint(rgb(252, 251, 247))
        paint.setShadow(
            short * (0.02f + 0.04f * border.shadow),
            0f,
            short * (0.008f + 0.015f * border.shadow),
            argb(90 + (border.shadow * 80).toInt(), 0, 0, 0),
        )
        val radius = short * 0.012f
        canvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, paint)
    }

    private fun content(carousel: Carousel, sources: List<ImageBitmap?>, settings: FrameSettings) =
        StripRenderer.Content { canvas, width, height ->
            val first = sources.firstOrNull { it != null }
            if (first != null) {
                PhotoProcessor.drawBackground(canvas, first, settings, width.toInt(), height.toInt())
            } else {
                canvas.fill(settings.bgColor, width, height)
            }
            // Frame styles are for single photos; the corners and shadow apply to each photo here.
            val border = settings.border.copy(frame = FrameStyle.NONE)
            carousel.photos.forEachIndexed { i, photo ->
                val source = sources.getOrNull(i) ?: return@forEachIndexed
                val rect = photoRect(carousel, i, width, height)
                canvas.save()
                canvas.rotateAround(photo.placement.rotation, rect.center.x, rect.center.y)
                val crop = centerCrop(source.width, source.height, photo.crop).let { Rect(it.left, it.top, it.right, it.bottom) }
                val filter = PhotoProcessor.colorFilter(photo.adjustments)
                if (photo.framed) {
                    drawPrint(canvas, rect, border)
                    val inner = printPhotoBox(Box(rect.left, rect.top, rect.right, rect.bottom), photo.shownAspect)
                    PhotoProcessor.drawImage(canvas, source, crop, Rect(inner.left, inner.top, inner.right, inner.bottom), Border(), colorFilter = filter)
                } else {
                    PhotoProcessor.drawImage(canvas, source, crop, rect, border, photo.shape, filter)
                }
                canvas.restore()
            }
            // Stickers lie on top of all the photos.
            carousel.stickers.forEach { sticker ->
                val rect = placementRect(sticker.placement, sticker.kind.aspect, carousel.slides, width, height)
                canvas.save()
                canvas.rotateAround(sticker.placement.rotation, rect.center.x, rect.center.y)
                StickerRenderer.draw(canvas, sticker.kind, sticker.color, rect)
                canvas.restore()
            }
        }
}

/** Turns the canvas [degrees] clockwise around ([x], [y]), as Android's Canvas.rotate(degrees, x, y). */
fun Canvas.rotateAround(degrees: Float, x: Float, y: Float) {
    translate(x, y)
    rotate(degrees)
    translate(-x, -y)
}
