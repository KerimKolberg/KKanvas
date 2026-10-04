package com.squareify.app.processing

import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.CollageLayout
import com.squareify.app.FrameSettings
import com.squareify.app.PaddingStyle
import com.squareify.app.PlatformBitmap
import com.squareify.app.emptyMediaUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollageRendererTest {
    private val settings = FrameSettings(bgColor = Color.WHITE)

    private fun cell(fit: CellFit = CellFit.FILL, zoom: Float = 1f, panX: Float = 0f) =
        CollageCell(emptyMediaUri, "test", null, fit = fit, zoom = zoom, panX = panX)

    private fun solid(color: Int, width: Int, height: Int): PlatformBitmap =
        picture(color, width, height)

    @Test
    fun eachPhotoFillsItsCellWithTheBackgroundInTheGap() {
        val collage = Collage(CollageLayout.SIDE_BY_SIDE, listOf(cell(), cell()), spacing = 1f)
        val out = CollageRenderer.render(collage, listOf(solid(Color.RED, 400, 300), solid(Color.BLUE, 300, 400)), settings, 1000, 1000)
        assertEquals(Color.RED, out.getPixel(250, 500))
        assertEquals(Color.BLUE, out.getPixel(750, 500))
        // The 60 px gap runs from x = 470 to 530.
        assertEquals(Color.WHITE, out.getPixel(500, 500))
        // Filled cells reach the canvas edges.
        assertEquals(Color.RED, out.getPixel(2, 2))
    }

    @Test
    fun fitKeepsTheWholePhotoWithBackgroundAround() {
        val collage = Collage(CollageLayout.SIDE_BY_SIDE, listOf(cell(fit = CellFit.FIT), cell()), spacing = 0f)
        // A 4:1 photo in a 500 x 1000 cell becomes 500 x 125, centred vertically.
        val out = CollageRenderer.render(collage, listOf(solid(Color.RED, 400, 100), solid(Color.BLUE, 100, 100)), settings, 1000, 1000)
        assertEquals(Color.RED, out.getPixel(250, 500))
        assertEquals(Color.WHITE, out.getPixel(250, 100))
        assertEquals(Color.WHITE, out.getPixel(250, 900))
    }

    @Test
    fun blurredFitCellIsPaddedWithItsOwnPhoto() {
        val blurred = FrameSettings(paddingStyle = PaddingStyle.BLUR)
        val collage = Collage(CollageLayout.SIDE_BY_SIDE, listOf(cell(), cell(fit = CellFit.FIT)), spacing = 0f)
        // The blue 4:1 photo becomes a 500 x 125 strip across the middle of the right cell.
        val out = CollageRenderer.render(collage, listOf(solid(Color.RED, 300, 400), solid(Color.BLUE, 400, 100)), blurred, 1000, 1000)
        assertEquals(Color.BLUE, out.getPixel(750, 500))
        // Above it: a darkened blur of the blue photo, not of the first (red) one.
        val padding = out.getPixel(750, 100)
        assertTrue("padding is ${hex(padding)}", Color.blue(padding) > 150 && Color.red(padding) < 60)
    }

    @Test
    fun smartCropKeepsTheFacesInViewUntilMovedByHand() {
        // A wide photo in a tall cell: only 100 of its 900 px width fit. The faces are at the far right.
        val source = pictureOf(900, 300)
        val rect = androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 300f)
        val faces = cell().copy(focusX = 0.98f, focusY = 0.5f)
        val crop = CollageRenderer.cropFor(source, rect, faces)
        // Pushed as far right as the edge allows.
        assertEquals(900f, crop.right, 0.5f)
        // Centred on the faces when there's room.
        val middle = CollageRenderer.cropFor(source, rect, faces.copy(focusX = 0.4f))
        assertEquals(360f, middle.center.x, 0.5f)
        // Once the user drags, their position wins.
        val moved = CollageRenderer.cropFor(source, rect, faces.copy(panned = true, panX = -1f))
        assertEquals(0f, moved.left, 0.5f)
    }

    @Test
    fun zoomAndPanPickThePartOfThePhotoShown() {
        // Left half red, right half blue; zoomed in 2x and panned fully right, only blue is visible.
        val source = pictureOf(400, 400) {
            drawRect(0f, 0f, 200f, 400f, paint(Color.RED))
            drawRect(200f, 0f, 400f, 400f, paint(Color.BLUE))
        }

        val collage = Collage(CollageLayout.STACKED, listOf(cell(zoom = 2f, panX = 1f), cell()), spacing = 0f)
        val out = CollageRenderer.render(collage, listOf(source, solid(Color.GREEN, 100, 100)), settings, 1000, 1000)
        assertEquals(Color.BLUE, out.getPixel(100, 250))
        assertEquals(Color.BLUE, out.getPixel(900, 250))
    }
}
