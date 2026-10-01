package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareify.app.Adjustments
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.TextBackdrop
import com.squareify.app.TextOverlay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextRendererTest {

    private fun black(width: Int = 400, height: Int = 400): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    /** Rows (top, bottom) that contain pixels of [color], or null if none. */
    private fun rowsWith(bitmap: Bitmap, match: (Int) -> Boolean): IntRange? {
        var first = -1
        var last = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (match(bitmap.getPixel(x, y))) {
                    if (first < 0) first = y
                    last = y
                    break
                }
            }
        }
        return if (first < 0) null else first..last
    }

    private val isRed = { p: Int -> Color.red(p) > 180 && Color.green(p) < 80 }

    @Test
    fun textSitsWherePositionSays() {
        val top = TextOverlay("Hello", color = Color.RED, backdrop = TextBackdrop.NONE, position = 0f)
        val bottom = top.copy(position = 1f)
        val atTop = PhotoProcessor.frame(black(), FrameSettings(bgColor = Color.BLACK, text = top), 400, 400)
        val atBottom = PhotoProcessor.frame(black(), FrameSettings(bgColor = Color.BLACK, text = bottom), 400, 400)
        val topRows = rowsWith(atTop, isRed)!!
        val bottomRows = rowsWith(atBottom, isRed)!!
        assertTrue("top text at $topRows", topRows.first < 60)
        assertTrue("bottom text at $bottomRows", bottomRows.last > 340)
    }

    @Test
    fun textIsDrawnAfterTheLook() {
        // Black & white turns the picture grey, but the caption keeps its colour.
        val settings = FrameSettings(
            bgColor = Color.BLACK,
            adjustments = Adjustments(saturation = 0f),
            text = TextOverlay("Colour", color = Color.RED, backdrop = TextBackdrop.NONE, size = 1f, position = 0.5f),
        )
        assertTrue(rowsWith(PhotoProcessor.frame(black(), settings, 400, 400), isRed) != null)
    }

    @Test
    fun aTitleRunsAcrossPanoramaSlides() {
        val source = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        // Wide enough that a centred title covers both sides of the seam between the 2 slides.
        val settings = FrameSettings(
            bgColor = Color.BLACK,
            text = TextOverlay("WWWWWWWW", color = Color.RED, backdrop = TextBackdrop.NONE, size = 1f, position = 0.5f),
        )
        val pano = Panorama(2)
        val first = PanoramaRenderer.renderSlide(source, pano, settings, 0, 300, 300)
        val second = PanoramaRenderer.renderSlide(source, pano, settings, 1, 300, 300)
        fun redNear(bitmap: Bitmap, xs: IntRange) = (0 until bitmap.height).any { y -> xs.any { x -> isRed(bitmap.getPixel(x, y)) } }
        assertTrue("first slide has no text near its right edge", redNear(first, 260..299))
        assertTrue("second slide has no text near its left edge", redNear(second, 0..40))
    }
}
