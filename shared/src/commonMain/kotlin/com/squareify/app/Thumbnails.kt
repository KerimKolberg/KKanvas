package com.squareify.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import com.squareify.app.processing.CarouselRenderer
import com.squareify.app.processing.CollageRenderer
import com.squareify.app.processing.PanoramaRenderer
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.processing.asImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photos are decoded at most this large before saving; 200 MP originals won't fit in memory. */
const val MAX_DECODE_DIMENSION = 6000
/** Source kept in memory per item ([MediaItem.preview]). */
const val PREVIEW_SIZE = 720
const val THUMBNAIL_SIZE = 600
const val LIVE_PREVIEW_SIZE = 900
const val FULLSCREEN_SIZE = 1440
/** A panorama is loaded this large for its editor and full-screen preview. */
const val PANORAMA_EDITOR_SIZE = 2400
/** Previews for carousels with many photos (up to 100) are kept smaller, to stay within memory. */
const val SMALL_PREVIEW_SIZE = 420

/** The preview size for a carousel of [count] photos. */
fun carouselPreviewSize(count: Int) = if (count > 20) SMALL_PREVIEW_SIZE else PREVIEW_SIZE

/** A collage drawn from its cells' in-memory previews, longer side at most [maxSide]. */
fun renderCollagePreview(collage: Collage, settings: FrameSettings, maxSide: Int): PlatformBitmap {
    val (w, h) = collageSize(settings.format)
    val scale = min(1f, maxSide.toFloat() / max(w, h))
    return CollageRenderer.render(
        collage,
        collage.cells.map { it.preview },
        settings,
        (w * scale).roundToInt(),
        (h * scale).roundToInt(),
    )
}

/** A panorama's whole strip, [height] px high (as wide as the slides make it). */
fun renderPanoramaPreview(source: PlatformBitmap, panorama: Panorama, settings: FrameSettings, height: Int): PlatformBitmap {
    val (slideW, slideH) = slideSize(settings.format)
    val width = (height.toFloat() * slideW * panorama.slides / slideH).roundToInt()
    return PanoramaRenderer.renderStrip(source, panorama, settings, width, height)
}

/** A panorama's strip, [width] px wide, with thin lines where one slide ends and the next begins. */
fun renderPanoramaThumbnail(source: PlatformBitmap, panorama: Panorama, settings: FrameSettings, width: Int): PlatformBitmap {
    val (slideW, slideH) = slideSize(settings.format)
    val height = (width.toFloat() * slideH / (slideW * panorama.slides)).roundToInt().coerceAtLeast(1)
    val strip = PanoramaRenderer.renderStrip(source, panorama, settings, width, height)
    drawSlideLines(strip, panorama.slides)
    return strip
}

/** A carousel's strip from the photos' previews, [width] px wide, with lines where slides meet. */
fun renderCarouselThumbnail(carousel: Carousel, settings: FrameSettings, width: Int): PlatformBitmap {
    val (slideW, slideH) = slideSize(settings.format)
    val height = (width.toFloat() * slideH / (slideW * carousel.slides)).roundToInt().coerceAtLeast(1)
    val strip = CarouselRenderer.renderStrip(carousel, carousel.photos.map { it.preview }, settings, width, height)
    drawSlideLines(strip, carousel.slides)
    return strip
}

/** Thin white lines where one slide ends and the next begins. */
private fun drawSlideLines(strip: PlatformBitmap, slides: Int) {
    val image = strip.asImage()
    val paint = Paint().apply {
        isAntiAlias = false
        color = Color.White
        alpha = 200 / 255f
        strokeWidth = (image.width / 300f).coerceAtLeast(1.5f)
    }
    val canvas = Canvas(image)
    for (i in 1 until slides) {
        val x = image.width.toFloat() * i / slides
        canvas.drawLine(Offset(x, 0f), Offset(x, image.height.toFloat()), paint)
    }
}

/** Gallery file name of slide [index] (from 0), e.g. "carousel_IMG_1234_1". */
fun slideFileName(displayName: String, index: Int): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "carousel_${baseName}_${index + 1}"
}

/** Card-sized preview of what will be saved. */
fun renderThumbnail(source: PlatformBitmap, settings: FrameSettings): PlatformBitmap =
    PhotoProcessor.frameFitting(source, settings, THUMBNAIL_SIZE)

/** Gallery file name (without extension), e.g. "story_IMG_1234". */
fun outputFileName(format: FrameFormat, displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "${format.filePrefix}_$baseName"
}
