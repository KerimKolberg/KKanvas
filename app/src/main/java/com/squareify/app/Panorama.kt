package com.squareify.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One wide photo split into [slides] carousel slides that join up seamlessly when swiped.
 * The slides together form a strip [slides] slides wide; the photo fills it (cropped, placed by
 * [position]) or fits in it (whole, with the background around).
 */
data class Panorama(
    val slides: Int,
    val fit: CellFit = CellFit.FILL,
    /** Where a filled photo sits along the side that's cropped, -1..1 (0 = centred). */
    val position: Float = 0f,
) {
    companion object {
        const val MIN_SLIDES = 2
        const val MAX_SLIDES = 10
        /** Slides are saved this wide (Instagram shows at most 1440 px); the height follows the format. */
        const val SLIDE_WIDTH = 1440

        /** Formats a carousel can use: Instagram crops all slides to one of these. */
        val FORMATS = listOf(FrameFormat.SQUARE, FrameFormat.PORTRAIT, FrameFormat.GRID)

        /** As many slides as it takes to show a [width] x [height] photo at full height. */
        fun autoSlides(width: Int, height: Int, format: FrameFormat): Int {
            val slideAspect = format.widthRatio.toFloat() / format.heightRatio
            return (width.toFloat() / height / slideAspect).roundToInt().coerceIn(MIN_SLIDES, MAX_SLIDES)
        }
    }
}

/** Saved size of one slide in [format]. */
fun slideSize(format: FrameFormat): Pair<Int, Int> =
    Panorama.SLIDE_WIDTH to Panorama.SLIDE_WIDTH * format.heightRatio / format.widthRatio

/**
 * Where a [sourceWidth] x [sourceHeight] photo goes on a [stripWidth] x [stripHeight] strip:
 * the part of the photo used (source pixels) and where it's drawn (strip pixels).
 */
fun panoramaPlacement(
    sourceWidth: Int,
    sourceHeight: Int,
    panorama: Panorama,
    stripWidth: Float,
    stripHeight: Float,
): Pair<Box, Box> {
    val w = sourceWidth.toFloat()
    val h = sourceHeight.toFloat()
    return when (panorama.fit) {
        CellFit.FILL -> {
            // The largest part of the photo with the strip's shape, slid along by position.
            val stripAspect = stripWidth / stripHeight
            val cropW = min(w, h * stripAspect)
            val cropH = cropW / stripAspect
            val left = (w - cropW) / 2 * (1 + panorama.position)
            val top = (h - cropH) / 2 * (1 + panorama.position)
            Box(left, top, left + cropW, top + cropH) to Box(0f, 0f, stripWidth, stripHeight)
        }
        CellFit.FIT -> {
            val scale = min(stripWidth / w, stripHeight / h)
            val dw = w * scale
            val dh = h * scale
            val left = (stripWidth - dw) / 2
            val top = (stripHeight - dh) / 2
            Box(0f, 0f, w, h) to Box(left, top, left + dw, top + dh)
        }
    }
}

/** How much of the photo is cut off along the cropped side, in strip pixels (0 when fitted). */
fun panoramaOverflow(sourceWidth: Int, sourceHeight: Int, panorama: Panorama, stripWidth: Float, stripHeight: Float): Float {
    if (panorama.fit == CellFit.FIT) return 0f
    val scale = max(stripWidth / sourceWidth, stripHeight / sourceHeight)
    return max(sourceWidth * scale - stripWidth, sourceHeight * scale - stripHeight)
}
