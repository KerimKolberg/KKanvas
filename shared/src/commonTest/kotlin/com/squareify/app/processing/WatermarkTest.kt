package com.squareify.app.processing

import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import com.squareify.app.PlatformBitmap
import com.squareify.app.Watermark
import com.squareify.app.WatermarkCorner
import com.squareify.app.WatermarkMark
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatermarkTest {

    @BeforeTest
    fun setUp() {
        initRenderers(testContext())
    }

    private fun black(width: Int, height: Int): PlatformBitmap =
        picture(Color.BLACK, width, height)

    /** Any bright pixel in the given part of [bitmap]. */
    private fun brightIn(bitmap: PlatformBitmap, xs: IntRange, ys: IntRange) =
        ys.any { y -> xs.any { x -> Color.red(bitmap.getPixel(x, y)) > 150 } }

    private fun settings(watermark: Watermark) = FrameSettings(bgColor = Color.BLACK, watermark = watermark)

    @Test
    fun bothArtworksAreThere() {
        WatermarkMark.entries.forEach { assertTrue("$it missing", WatermarkRenderer.image(it).width > 0) }
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
