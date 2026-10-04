package com.squareify.app.processing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import com.squareify.app.Box
import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.FrameSettings
import com.squareify.app.PaddingStyle
import com.squareify.app.PlatformBitmap
import com.squareify.app.collageCellBoxes
import kotlin.math.min

/**
 * Draws a collage: background, then each photo in its cell (filled or fitted), then adjustments.
 * With the Blurred style, a fitted cell is padded with a blur of its own photo; the gaps and
 * margin around the cells show a blur of the first photo.
 */
object CollageRenderer {

    /** Cell rectangles on a [width] x [height] canvas. */
    fun cellRects(collage: Collage, settings: FrameSettings, width: Int, height: Int): List<Rect> {
        val inset = PhotoProcessor.marginInset(settings, width, height)
        val area = Box(inset, inset, width - inset, height - inset)
        return collageCellBoxes(collage.layout, collage.spacing, area).map { Rect(it.left, it.top, it.right, it.bottom) }
    }

    /**
     * [sources] are the cells' photos in cell order, at any resolution (previews or full size);
     * cells without a source stay empty.
     */
    fun render(collage: Collage, sources: List<PlatformBitmap?>, settings: FrameSettings, width: Int, height: Int): PlatformBitmap =
        renderImage(collage, sources.map { it?.asImage() }, settings, width, height).asPlatformBitmap()

    private fun renderImage(collage: Collage, sources: List<ImageBitmap?>, settings: FrameSettings, width: Int, height: Int): ImageBitmap {
        val output = ImageBitmap(width, height)
        val canvas = Canvas(output)
        val first = sources.firstOrNull { it != null }
        if (first != null) {
            // A blurred background is made from the first photo.
            PhotoProcessor.drawBackground(canvas, first, settings, width, height)
        } else {
            canvas.fill(settings.bgColor, width.toFloat(), height.toFloat())
        }

        cellRects(collage, settings, width, height).forEachIndexed { i, rect ->
            val cell = collage.cells.getOrNull(i) ?: return@forEachIndexed
            val source = sources.getOrNull(i) ?: return@forEachIndexed
            val filter = PhotoProcessor.colorFilter(cell.adjustments)
            when {
                cell.fit == CellFit.FILL ->
                    PhotoProcessor.drawImage(canvas, source, cropFor(source.width, source.height, rect, cell), rect, settings.border, cell.shape, filter)
                settings.paddingStyle == PaddingStyle.BLUR -> {
                    // The cell keeps its shape (outline, shadow); the photo inside only gets the corners.
                    PhotoProcessor.drawBlurredFill(canvas, source, rect, settings, cell.shape)
                    PhotoProcessor.drawImage(canvas, source, source.bounds(), fitInto(source, rect), settings.border.copy(shadow = 0f), colorFilter = filter)
                }
                else ->
                    PhotoProcessor.drawImage(canvas, source, source.bounds(), fitInto(source, rect), settings.border, cell.shape, filter)
            }
        }
        return PhotoProcessor.applyAdjustments(output, settings.adjustments).also { PhotoProcessor.drawOverlays(it, settings) }
    }

    /** The part of [source] a filled [cell] shows: the cell's aspect ratio, zoomed and panned. */
    fun cropFor(source: PlatformBitmap, cell: Rect, collageCell: CollageCell): Rect =
        source.asImage().let { cropFor(it.width, it.height, cell, collageCell) }

    private fun cropFor(sourceWidth: Int, sourceHeight: Int, cell: Rect, collageCell: CollageCell): Rect {
        val cellAspect = cell.width / cell.height
        var cropW = sourceWidth.toFloat()
        var cropH = cropW / cellAspect
        if (cropH > sourceHeight) {
            cropH = sourceHeight.toFloat()
            cropW = cropH * cellAspect
        }
        cropW /= collageCell.zoom
        cropH /= collageCell.zoom
        val focusX = collageCell.focusX
        val focusY = collageCell.focusY
        val (centerX, centerY) = if (!collageCell.panned && focusX != null && focusY != null) {
            // Smart crop: as close to the faces as the photo's edges allow.
            (focusX * sourceWidth).coerceIn(cropW / 2, sourceWidth - cropW / 2) to
                (focusY * sourceHeight).coerceIn(cropH / 2, sourceHeight - cropH / 2)
        } else {
            sourceWidth / 2f + collageCell.panX * (sourceWidth - cropW) / 2f to
                sourceHeight / 2f + collageCell.panY * (sourceHeight - cropH) / 2f
        }
        return Rect(centerX - cropW / 2, centerY - cropH / 2, centerX + cropW / 2, centerY + cropH / 2)
    }

    /** The whole photo, as large as fits in [cell], centred. */
    private fun fitInto(source: ImageBitmap, cell: Rect): Rect {
        val scale = min(cell.width / source.width, cell.height / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = cell.left + (cell.width - w) / 2
        val top = cell.top + (cell.height - h) / 2
        return Rect(left, top, left + w, top + h)
    }
}
