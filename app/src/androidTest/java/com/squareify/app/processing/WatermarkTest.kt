package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.Watermark
import com.squareify.app.WatermarkCorner
import com.squareify.app.WatermarkImages
import com.squareify.app.WatermarkMark
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatermarkTest {

    @Before
    fun loadArtwork() {
        WatermarkImages.load(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun black(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    /** Any bright pixel in the given part of [bitmap]. */
    private fun brightIn(bitmap: Bitmap, xs: IntRange, ys: IntRange) =
        ys.any { y -> xs.any { x -> Color.red(bitmap.getPixel(x, y)) > 150 } }

    private fun settings(watermark: Watermark) = FrameSettings(bgColor = Color.BLACK, watermark = watermark)

    @Test
    fun bothArtworksAreThere() {
        WatermarkMark.entries.forEach { assertTrue("$it missing", WatermarkImages[it] != null) }
    }

    @Test
    fun offByDefault() {
        val out = PhotoProcessor.frame(black(400, 400), settings(Watermark()), 400, 400)
        assertFalse(brightIn(out, 0 until 400, 0 until 400))
    }

    @Test
    fun sitsInTheChosenCorner() {
        val on = Watermark(enabled = true, opacity = 1f, size = 1f, corner = WatermarkCorner.BOTTOM_RIGHT)
        val out = PhotoProcessor.frame(black(400, 400), settings(on), 400, 400)
        assertTrue("not bottom right", brightIn(out, 300 until 400, 300 until 400))
        assertFalse("also top left", brightIn(out, 0 until 200, 0 until 200))
        val topLeft = PhotoProcessor.frame(black(400, 400), settings(on.copy(corner = WatermarkCorner.TOP_LEFT)), 400, 400)
        assertTrue("not top left", brightIn(topLeft, 0 until 100, 0 until 100))
    }

    @Test
    fun onAPanoramaItGoesInTheLastSlideOnly() {
        val on = settings(Watermark(enabled = true, opacity = 1f, size = 1f))
        val pano = Panorama(3)
        val source = black(900, 300)
        val first = PanoramaRenderer.renderSlide(source, pano, on, 0, 300, 300)
        val last = PanoramaRenderer.renderSlide(source, pano, on, 2, 300, 300)
        assertFalse(brightIn(first, 0 until 300, 0 until 300))
        assertTrue(brightIn(last, 200 until 300, 200 until 300))
    }
}
