package com.squareify.app

import android.graphics.Bitmap
import android.net.Uri
import java.io.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in fractions (0–1) of an area, or in pixels; plain floats so it's unit-testable. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun box(left: Double, top: Double, right: Double, bottom: Double) =
    Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/** [columns] x [rows] equal cells, row by row. */
private fun grid(columns: Int, rows: Int): List<Box> =
    (0 until rows).flatMap { r ->
        (0 until columns).map { c ->
            box(c / columns.toDouble(), r / rows.toDouble(), (c + 1) / columns.toDouble(), (r + 1) / rows.toDouble())
        }
    }

/** Where the photos of a collage go, in fractions of the area inside the margin. */
enum class CollageLayout(val label: String, val cells: List<Box>) {
    SIDE_BY_SIDE("Side by side", grid(2, 1)),
    STACKED("Stacked", grid(1, 2)),
    BIG_LEFT("Big left", listOf(box(0.0, 0.0, 0.5, 1.0), box(0.5, 0.0, 1.0, 0.5), box(0.5, 0.5, 1.0, 1.0))),
    BIG_TOP("Big top", listOf(box(0.0, 0.0, 1.0, 0.5), box(0.0, 0.5, 0.5, 1.0), box(0.5, 0.5, 1.0, 1.0))),
    THREE_COLUMNS("3 columns", grid(3, 1)),
    THREE_ROWS("3 rows", grid(1, 3)),
    GRID_2X2("2 × 2", grid(2, 2)),
    BIG_TOP_THREE(
        "Big top + 3",
        listOf(box(0.0, 0.0, 1.0, 0.6)) + grid(3, 1).map { box(it.left.toDouble(), 0.6, it.right.toDouble(), 1.0) },
    ),
    BIG_LEFT_THREE(
        "Big left + 3",
        listOf(box(0.0, 0.0, 0.6, 1.0)) + grid(1, 3).map { box(0.6, it.top.toDouble(), 1.0, it.bottom.toDouble()) },
    ),
    TWO_THREE(
        "2 + 3",
        grid(2, 1).map { box(it.left.toDouble(), 0.0, it.right.toDouble(), 0.5) } +
            grid(3, 1).map { box(it.left.toDouble(), 0.5, it.right.toDouble(), 1.0) },
    ),
    GRID_3X2("3 × 2", grid(3, 2)),
    GRID_2X3("2 × 3", grid(2, 3)),
    GRID_3X3("3 × 3", grid(3, 3));

    companion object {
        const val MAX_PHOTOS = 9

        /** Layouts for [count] photos; 7 or 8 photos use the 3 × 3 grid with empty cells. */
        fun forCount(count: Int): List<CollageLayout> =
            entries.filter { it.cells.size == count }.ifEmpty { entries.filter { it.cells.size >= count }.take(1) }
    }
}

enum class Direction { LEFT, UP, RIGHT, DOWN }

/**
 * The cell next to cell [index] in [direction], among the first [count] cells (those holding a
 * photo), or null if there is none. Of several, the closest one that overlaps it the most wins,
 * e.g. right of "Big left"'s big cell is its top-right cell.
 */
fun CollageLayout.neighbor(index: Int, direction: Direction, count: Int): Int? {
    val from = cells.getOrNull(index) ?: return null
    val eps = 1e-4f
    return (0 until minOf(count, cells.size))
        .filter { it != index }
        .mapNotNull { i ->
            val to = cells[i]
            // Gap between the two along the direction, and how much they overlap across it.
            val (gap, overlap) = when (direction) {
                Direction.LEFT -> from.left - to.right to (minOf(from.bottom, to.bottom) - maxOf(from.top, to.top))
                Direction.RIGHT -> to.left - from.right to (minOf(from.bottom, to.bottom) - maxOf(from.top, to.top))
                Direction.UP -> from.top - to.bottom to (minOf(from.right, to.right) - maxOf(from.left, to.left))
                Direction.DOWN -> to.top - from.bottom to (minOf(from.right, to.right) - maxOf(from.left, to.left))
            }
            if (gap >= -eps && overlap > eps) Triple(i, gap, overlap) else null
        }
        // Compared in 1/1000 steps, so float rounding can't break ties (then the earlier cell wins).
        .minWithOrNull(
            compareBy<Triple<Int, Float, Float>> { (it.second * 1000).roundToInt() }
                .thenByDescending { (it.third * 1000).roundToInt() }
                .thenBy { it.first },
        )
        ?.first
}

enum class CellFit { FILL, FIT }

/** What a clip shorter than the collage does once it ends. */
enum class ShortClips { FREEZE, LOOP }

/** One photo or video clip in a collage. */
data class CollageCell(
    val sourceUri: Uri,
    val displayName: String,
    /** The photo (or a frame from the middle of the clip), downscaled; for live previews. */
    val preview: Bitmap?,
    val isVideo: Boolean = false,
    val fit: CellFit = CellFit.FILL,
    /** 1 = the photo just fills its cell; larger zooms in. */
    val zoom: Float = 1f,
    /** Where a filled photo sits in its cell, -1..1 per axis (0 = centred). */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** Where the faces are (0–1 of the photo), found when the editor opens; 0.5 if none. */
    val focusX: Float? = null,
    val focusY: Float? = null,
    /** The user has moved the photo by hand; until then a filled cell keeps the faces in view. */
    val panned: Boolean = false,
    val shape: PhotoShape = PhotoShape.RECTANGLE,
    /** Colour changes for this photo only, on top of the collage's look. */
    val adjustments: Adjustments = Adjustments(),
)

data class Collage(
    val layout: CollageLayout,
    val cells: List<CollageCell>,
    /** Gap between cells, 0–1. */
    val spacing: Float = DEFAULT_SPACING,
    val shortClips: ShortClips = ShortClips.FREEZE,
    /** The cell whose clip the collage takes its sound from; null = silent. */
    val soundCell: Int? = cells.indexOfFirst { it.isVideo }.takeIf { it >= 0 },
) {
    /** With a clip in it, the collage is saved as a video. */
    val hasVideo: Boolean get() = cells.any { it.isVideo }

    companion object {
        const val DEFAULT_SPACING = 0.3f
        /** The gap at 100% spacing, as a fraction of the area's shorter side. */
        const val MAX_SPACING = 0.06f
        /** Saved collages are this wide (Instagram shows at most 1440 px); the height follows the format. */
        const val OUTPUT_WIDTH = 2160
        /** Video collages are this wide: Instagram's video width, and quick to render. */
        const val VIDEO_OUTPUT_WIDTH = 1080
    }
}

/** Saved size of a photo collage in [format]. */
fun collageSize(format: FrameFormat): Pair<Int, Int> =
    Collage.OUTPUT_WIDTH to Collage.OUTPUT_WIDTH * format.heightRatio / format.widthRatio

/** Saved size of a video collage in [format]. */
fun videoCollageSize(format: FrameFormat): Pair<Int, Int> =
    Collage.VIDEO_OUTPUT_WIDTH to Collage.VIDEO_OUTPUT_WIDTH * format.heightRatio / format.widthRatio

/** A [Collage] without its previews, so it can travel in an Intent to the render service. */
data class CollageSpec(
    val layout: CollageLayout,
    val cells: List<CellSpec>,
    val spacing: Float,
    val shortClips: ShortClips,
    val soundCell: Int?,
) : Serializable {
    data class CellSpec(
        val uri: String,
        val displayName: String,
        val isVideo: Boolean,
        val fit: CellFit,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
        val focusX: Float? = null,
        val focusY: Float? = null,
        val panned: Boolean = false,
        val shape: PhotoShape = PhotoShape.RECTANGLE,
        val adjustments: Adjustments = Adjustments(),
    ) : Serializable

    fun toCollage() = Collage(
        layout = layout,
        cells = cells.map {
            CollageCell(
                Uri.parse(it.uri), it.displayName, null, it.isVideo, it.fit, it.zoom, it.panX, it.panY,
                focusX = it.focusX, focusY = it.focusY, panned = it.panned, shape = it.shape, adjustments = it.adjustments,
            )
        },
        spacing = spacing,
        shortClips = shortClips,
        soundCell = soundCell,
    )
}

fun Collage.toSpec() = CollageSpec(
    layout = layout,
    cells = cells.map {
        CollageSpec.CellSpec(
            it.sourceUri.toString(), it.displayName, it.isVideo, it.fit, it.zoom, it.panX, it.panY,
            focusX = it.focusX, focusY = it.focusY, panned = it.panned, shape = it.shape, adjustments = it.adjustments,
        )
    },
    spacing = spacing,
    shortClips = shortClips,
    soundCell = soundCell,
)

/**
 * Pixel rectangles of the cells on a canvas: [area] is the canvas inside the margin. Cells are
 * separated by the spacing gap; the outer cells end flush with the area.
 */
fun collageCellBoxes(layout: CollageLayout, spacing: Float, area: Box): List<Box> {
    val gap = spacing * Collage.MAX_SPACING * min(area.width, area.height)
    val half = gap / 2
    // Grow the area by half a gap, then shrink every cell by half a gap: inner gaps are a full
    // gap wide and the outer edges stay where they were.
    val left = area.left - half
    val top = area.top - half
    val width = area.width + gap
    val height = area.height + gap
    return layout.cells.map { c ->
        Box(
            left + c.left * width + half,
            top + c.top * height + half,
            left + c.right * width - half,
            top + c.bottom * height - half,
        )
    }
}
