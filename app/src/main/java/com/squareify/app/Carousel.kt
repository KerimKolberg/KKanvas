package com.squareify.app

import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where a photo sits on a carousel. Horizontal measures are in slide widths from the left edge of
 * slide 1 (so x = 1.5 is the middle of slide 2), which lets a photo cross the seam between slides.
 */
data class Placement(
    /** Centre, in slide widths. */
    val x: Float,
    /** Centre, as a fraction (0–1) of the slides' height. */
    val y: Float,
    /** In slide widths; the height follows the photo's shape. */
    val width: Float,
    /** Degrees, clockwise. */
    val rotation: Float = 0f,
)

/** A photo placed freely on a carousel. */
data class CarouselPhoto(
    val sourceUri: Uri,
    val displayName: String,
    /** Downscaled, for the editor and previews. */
    val preview: Bitmap?,
    /** The photo's width / height. */
    val aspect: Float,
    val placement: Placement,
    val shape: PhotoShape = PhotoShape.RECTANGLE,
    /** Colour changes for this photo only, on top of the carousel's look. */
    val adjustments: Adjustments = Adjustments(),
)

/** Photos placed freely across [slides] carousel slides; later photos lie on top. */
data class Carousel(val slides: Int, val photos: List<CarouselPhoto>) {
    companion object {
        const val MIN_SLIDES = 2
        const val MAX_SLIDES = 10
        const val MAX_PHOTOS = 20
    }
}

/** Height of the slides in slide widths, e.g. 1.25 for 4:5. */
fun slideHeightUnits(format: FrameFormat): Float = format.heightRatio.toFloat() / format.widthRatio

/** The photo's rectangle before rotation, in slide widths (y too). */
fun placementBox(placement: Placement, aspect: Float, heightUnits: Float): Box {
    val h = placement.width / aspect
    val cy = placement.y * heightUnits
    return Box(placement.x - placement.width / 2, cy - h / 2, placement.x + placement.width / 2, cy + h / 2)
}

/** The smallest upright box around the photo once rotated, in slide widths. */
fun placementBounds(placement: Placement, aspect: Float, heightUnits: Float): Box {
    val box = placementBox(placement, aspect, heightUnits)
    val radians = Math.toRadians(placement.rotation.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    val halfW = (box.width * c + box.height * s) / 2
    val halfH = (box.width * s + box.height * c) / 2
    val cx = (box.left + box.right) / 2
    val cy = (box.top + box.bottom) / 2
    return Box(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/** Whether ([px], [py]) in slide widths falls on the photo, rotation included. */
fun placementContains(placement: Placement, aspect: Float, heightUnits: Float, px: Float, py: Float): Boolean {
    val box = placementBox(placement, aspect, heightUnits)
    val cx = (box.left + box.right) / 2
    val cy = (box.top + box.bottom) / 2
    // Turn the point back by the photo's rotation, then test against the upright rectangle.
    val radians = Math.toRadians(-placement.rotation.toDouble())
    val dx = px - cx
    val dy = py - cy
    val x = (dx * cos(radians) - dy * sin(radians)).toFloat() + cx
    val y = (dx * sin(radians) + dy * cos(radians)).toFloat() + cy
    return x in box.left..box.right && y in box.top..box.bottom
}

/** Placements with their photos' aspect ratios: all the geometry needs to know about the photos. */
fun Carousel.shapes(): List<Pair<Placement, Float>> = photos.map { it.placement to it.aspect }

/** Index of the top photo at ([px], [py]) in slide widths, or null. */
fun carouselPhotoAt(shapes: List<Pair<Placement, Float>>, heightUnits: Float, px: Float, py: Float): Int? =
    shapes.indices.lastOrNull { i -> placementContains(shapes[i].first, shapes[i].second, heightUnits, px, py) }

/**
 * Spreads photos (given by their aspect ratios) evenly across [slides]: each centred in its share
 * of the width, as large as fits with some room around it.
 */
fun spreadPlacements(aspects: List<Float>, slides: Int, heightUnits: Float): List<Placement> {
    if (aspects.isEmpty()) return emptyList()
    val share = slides.toFloat() / aspects.size
    return aspects.mapIndexed { i, aspect ->
        val width = min(share * 0.84f, heightUnits * 0.84f * aspect)
        Placement(x = share * (i + 0.5f), y = 0.5f, width = width)
    }
}

/** A photo sized to fit inside slide [slide] (from 0), centred and upright. */
fun fitSlidePlacement(slide: Int, aspect: Float, heightUnits: Float): Placement =
    Placement(x = slide + 0.5f, y = 0.5f, width = min(1f, heightUnits * aspect))

/** A placement after snapping, and the lines it snapped to (x in slide widths, y as fractions). */
data class Snapped(val placement: Placement, val guidesX: List<Float>, val guidesY: List<Float>)

/**
 * Pulls a photo that's close to a slide edge or middle (or the top, middle or bottom) onto it, and
 * a nearly straight photo straight. [threshold] is in slide widths.
 */
fun snapPlacement(placement: Placement, aspect: Float, heightUnits: Float, slides: Int, threshold: Float): Snapped {
    var p = placement
    // Straighten within a few degrees of a quarter turn.
    val quarter = Math.round(p.rotation / 90f) * 90f
    if (abs(p.rotation - quarter) < 4f) p = p.copy(rotation = quarter)
    val upright = abs(p.rotation % 180f) < 0.01f
    val box = if (upright) placementBox(p, aspect, heightUnits) else null

    // Vertical lines: every slide edge and middle.
    val targetsX = (0..slides * 2).map { it / 2f }
    val edgesX = if (box != null) listOf(box.left, p.x, box.right) else listOf(p.x)
    val dx = closest(edgesX, targetsX, threshold)
    // Horizontal lines: top, middle and bottom.
    val cy = p.y * heightUnits
    val targetsY = listOf(0f, heightUnits / 2, heightUnits)
    val edgesY = if (box != null) listOf(box.top, cy, box.bottom) else listOf(cy)
    val dy = closest(edgesY, targetsY, threshold)

    val snapped = p.copy(x = p.x + (dx?.first ?: 0f), y = p.y + (dy?.first ?: 0f) / heightUnits)
    return Snapped(
        snapped,
        listOfNotNull(dx?.second),
        listOfNotNull(dy?.second?.let { it / heightUnits }),
    )
}

/** The smallest move (and the line it lands on) bringing one of [edges] onto one of [targets]. */
private fun closest(edges: List<Float>, targets: List<Float>, threshold: Float): Pair<Float, Float>? {
    var best: Pair<Float, Float>? = null
    for (edge in edges) {
        for (target in targets) {
            val move = target - edge
            if (abs(move) <= threshold && (best == null || abs(move) < abs(best.first))) best = move to target
        }
    }
    return best
}

/**
 * Things that look wrong once swiped: a photo poking only a sliver into the next slide, or a
 * slide with no photo on it. Slides are numbered from 1, photos too.
 */
fun carouselWarnings(slides: Int, shapes: List<Pair<Placement, Float>>, heightUnits: Float): List<String> {
    val bounds = shapes.map { (placement, aspect) -> placementBounds(placement, aspect, heightUnits) }
        // Anything off the slides entirely doesn't count.
        .map { b -> Box(max(b.left, 0f), b.top, min(b.right, slides.toFloat()), b.bottom) }
    val warnings = mutableListOf<String>()
    bounds.forEachIndexed { i, b ->
        for (seam in 1 until slides) {
            if (b.left < seam && b.right > seam) {
                val left = seam - b.left
                val right = b.right - seam
                if (min(left, right) < SLIVER) {
                    val slide = if (left < right) seam else seam + 1
                    warnings += "Only a sliver of photo ${i + 1} shows on slide $slide"
                }
            }
        }
    }
    for (slide in 0 until slides) {
        if (bounds.none { it.right > slide && it.left < slide + 1 && it.width > 0f }) {
            warnings += "Slide ${slide + 1} has no photo on it"
        }
    }
    return warnings
}

/** Less than this (in slide widths) across a seam looks like a mistake. */
private const val SLIVER = 0.04f

/** Which slide (from 0) the photo's centre is on. */
fun slideOf(placement: Placement, slides: Int): Int = floor(placement.x).toInt().coerceIn(0, slides - 1)
