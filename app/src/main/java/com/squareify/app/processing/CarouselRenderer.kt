package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.RectF
import com.squareify.app.Border
import com.squareify.app.Box
import com.squareify.app.Carousel
import com.squareify.app.FrameSettings
import com.squareify.app.FrameStyle
import com.squareify.app.Placement
import com.squareify.app.centerCrop
import com.squareify.app.printPhotoBox

/**
 * Draws a carousel: the background across all slides, then each photo where it was placed
 * (later ones on top), with the border's corners and shadow.
 */
object CarouselRenderer {

    /** The whole strip at [width] x [height]; [sources] are the photos in order (any resolution). */
    fun renderStrip(carousel: Carousel, sources: List<Bitmap?>, settings: FrameSettings, width: Int, height: Int): Bitmap =
        StripRenderer.renderStrip(settings, width, height, content(carousel, sources, settings))

    /** Slide [index] (from 0) at [slideWidth] x [slideHeight]. */
    fun renderSlide(
        carousel: Carousel,
        sources: List<Bitmap?>,
        settings: FrameSettings,
        index: Int,
        slideWidth: Int,
        slideHeight: Int,
    ): Bitmap = StripRenderer.renderSlide(settings, carousel.slides, index, slideWidth, slideHeight, content(carousel, sources, settings))

    /** Where photo [index] goes on a strip [stripWidth] wide and [stripHeight] high, before rotation. */
    fun photoRect(carousel: Carousel, index: Int, stripWidth: Float, stripHeight: Float): RectF {
        val photo = carousel.photos[index]
        return placementRect(photo.placement, photo.boxAspect, carousel.slides, stripWidth, stripHeight)
    }

    /** A placement's box on a strip of [slides] slides, before rotation. */
    fun placementRect(placement: Placement, aspect: Float, slides: Int, stripWidth: Float, stripHeight: Float): RectF {
        val unit = stripWidth / slides
        val w = placement.width * unit
        val h = w / aspect
        val cx = placement.x * unit
        val cy = placement.y * stripHeight
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** The white card of a print, with a soft shadow (stronger with the border's shadow setting). */
    private fun drawPrint(canvas: android.graphics.Canvas, rect: RectF, border: Border) {
        val short = minOf(rect.width(), rect.height())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(252, 251, 247)
        paint.setShadowLayer(
            short * (0.02f + 0.04f * border.shadow),
            0f,
            short * (0.008f + 0.015f * border.shadow),
            android.graphics.Color.argb(90 + (border.shadow * 80).toInt(), 0, 0, 0),
        )
        val radius = short * 0.012f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun content(carousel: Carousel, sources: List<Bitmap?>, settings: FrameSettings) =
        StripRenderer.Content { canvas, width, height ->
            val first = sources.firstOrNull { it != null }
            if (first != null) {
                PhotoProcessor.drawBackground(canvas, first, settings, width.toInt(), height.toInt())
            } else {
                canvas.drawColor(settings.bgColor)
            }
            // Frame styles are for single photos; the corners and shadow apply to each photo here.
            val border = settings.border.copy(frame = FrameStyle.NONE)
            carousel.photos.forEachIndexed { i, photo ->
                val source = sources.getOrNull(i) ?: return@forEachIndexed
                val rect = photoRect(carousel, i, width, height)
                canvas.save()
                canvas.rotate(photo.placement.rotation, rect.centerX(), rect.centerY())
                val crop = centerCrop(source.width, source.height, photo.crop).let { RectF(it.left, it.top, it.right, it.bottom) }
                val filter = PhotoProcessor.colorFilter(photo.adjustments)
                if (photo.framed) {
                    drawPrint(canvas, rect, border)
                    val inner = printPhotoBox(Box(rect.left, rect.top, rect.right, rect.bottom), photo.shownAspect)
                    PhotoProcessor.drawImage(canvas, source, crop, RectF(inner.left, inner.top, inner.right, inner.bottom), Border(), colorFilter = filter)
                } else {
                    PhotoProcessor.drawImage(canvas, source, crop, rect, border, photo.shape, filter)
                }
                canvas.restore()
            }
            // Stickers lie on top of all the photos.
            carousel.stickers.forEach { sticker ->
                val rect = placementRect(sticker.placement, sticker.kind.aspect, carousel.slides, width, height)
                canvas.save()
                canvas.rotate(sticker.placement.rotation, rect.centerX(), rect.centerY())
                StickerRenderer.draw(canvas, sticker.kind, sticker.color, rect)
                canvas.restore()
            }
        }
}
