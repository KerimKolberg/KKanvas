package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareify.app.Adjustments
import com.squareify.app.CellFit
import com.squareify.app.FrameSettings
import com.squareify.app.Panorama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Slides cut from a panorama line up into the same picture as the whole strip. */
@RunWith(AndroidJUnit4::class)
class PanoramaRendererTest {
    private val settings = FrameSettings(bgColor = Color.WHITE)

    /** 4:1, red on the left fading to blue on the right, so every column has its own colour. */
    private fun panorama(): Bitmap {
        val bitmap = Bitmap.createBitmap(2000, 500, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 2000f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP)
        Canvas(bitmap).drawRect(0f, 0f, 2000f, 500f, paint)
        return bitmap
    }

    private fun close(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) <= 6 && abs(Color.blue(a) - Color.blue(b)) <= 6

    @Test
    fun slidesJoinUpSeamlessly() {
        val source = panorama()
        val pano = Panorama(slides = 4)
        val strip = PanoramaRenderer.renderStrip(source, pano, settings, 1600, 400)
        (0 until 4).forEach { i ->
            val slide = PanoramaRenderer.renderSlide(source, pano, settings, i, 400, 400)
            listOf(0, 200, 399).forEach { x ->
                val inSlide = slide.getPixel(x, 200)
                val inStrip = strip.getPixel(i * 400 + x, 200)
                assertTrue("slide $i x=$x: ${Integer.toHexString(inSlide)} vs strip ${Integer.toHexString(inStrip)}", close(inSlide, inStrip))
            }
        }
        // The first slide starts red, the last ends blue.
        assertTrue(Color.red(PanoramaRenderer.renderSlide(source, pano, settings, 0, 400, 400).getPixel(2, 200)) > 230)
        assertTrue(Color.blue(PanoramaRenderer.renderSlide(source, pano, settings, 3, 400, 400).getPixel(397, 200)) > 230)
    }

    @Test
    fun fitPadsAPanoramaThatIsTooShortForTheSlides() {
        // 4:1 photo over 2 square slides (a 2:1 strip): white above and below it.
        val slide = PanoramaRenderer.renderSlide(panorama(), Panorama(2, fit = CellFit.FIT), settings, 0, 400, 400)
        assertEquals(Color.WHITE, slide.getPixel(200, 20))
        assertEquals(Color.WHITE, slide.getPixel(200, 380))
        assertTrue(Color.red(slide.getPixel(200, 200)) > 150)
    }

    @Test
    fun vignetteDarkensOnlyThePanoramasEnds() {
        val white = Bitmap.createBitmap(2000, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val dark = settings.copy(adjustments = Adjustments(vignette = 1f))
        val pano = Panorama(4)
        val first = PanoramaRenderer.renderSlide(white, pano, dark, 0, 400, 400)
        val second = PanoramaRenderer.renderSlide(white, pano, dark, 1, 400, 400)
        // The outer corner of the first slide is dark; where slides 1 and 2 meet, nothing is.
        assertTrue(Color.red(first.getPixel(1, 1)) < 150)
        assertEquals(Color.red(first.getPixel(399, 200)), Color.red(second.getPixel(0, 200)), 3f)
        assertTrue(Color.red(second.getPixel(399, 200)) > 245)
    }

    @Test
    fun aLeftToRightGradientFlowsAcrossAllSlides() {
        // A fitted, very short photo leaves the gradient background showing above and below.
        val gradient = FrameSettings(
            paddingStyle = com.squareify.app.PaddingStyle.GRADIENT,
            bgColor = Color.RED,
            bgColor2 = Color.BLUE,
            gradientDirection = com.squareify.app.GradientDirection.HORIZONTAL,
        )
        val thin = Bitmap.createBitmap(1000, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val pano = Panorama(3, fit = CellFit.FIT)
        val slides = (0 until 3).map { PanoramaRenderer.renderSlide(thin, pano, gradient, it, 200, 200) }
        // Red at the very start, blue at the very end.
        assertTrue(Color.red(slides[0].getPixel(1, 20)) > 240)
        assertTrue(Color.blue(slides[2].getPixel(198, 20)) > 240)
        // Each seam continues where the slide before left off.
        for (i in 0 until 2) {
            assertEquals(Color.red(slides[i].getPixel(199, 20)), Color.red(slides[i + 1].getPixel(0, 20)), 3f)
        }
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Float) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tolerance)
}
