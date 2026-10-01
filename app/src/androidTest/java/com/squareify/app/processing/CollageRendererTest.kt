package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.CollageLayout
import com.squareify.app.FrameSettings
import com.squareify.app.PaddingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollageRendererTest {
    private val settings = FrameSettings(bgColor = Color.WHITE)

    private fun cell(fit: CellFit = CellFit.FILL, zoom: Float = 1f, panX: Float = 0f) =
        CollageCell(Uri.EMPTY, "test", null, fit = fit, zoom = zoom, panX = panX)

    private fun solid(color: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

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
        assertTrue("padding is ${Integer.toHexString(padding)}", Color.blue(padding) > 150 && Color.red(padding) < 60)
    }

    @Test
    fun zoomAndPanPickThePartOfThePhotoShown() {
        // Left half red, right half blue; zoomed in 2x and panned fully right, only blue is visible.
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, 200f, 400f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(200f, 0f, 400f, 400f, paint)

        val collage = Collage(CollageLayout.STACKED, listOf(cell(zoom = 2f, panX = 1f), cell()), spacing = 0f)
        val out = CollageRenderer.render(collage, listOf(source, solid(Color.GREEN, 100, 100)), settings, 1000, 1000)
        assertEquals(Color.BLUE, out.getPixel(100, 250))
        assertEquals(Color.BLUE, out.getPixel(900, 250))
    }
}
