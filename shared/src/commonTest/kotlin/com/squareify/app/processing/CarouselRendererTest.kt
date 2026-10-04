package com.squareify.app.processing

import com.squareify.app.Carousel
import com.squareify.app.CarouselPhoto
import com.squareify.app.FrameSettings
import com.squareify.app.Placement
import com.squareify.app.PlatformBitmap
import com.squareify.app.emptyMediaUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarouselRendererTest {
    private val settings = FrameSettings(bgColor = Color.WHITE)

    private fun solid(color: Int): PlatformBitmap =
        picture(color, 200, 200)

    private fun photo(placement: Placement) = CarouselPhoto(emptyMediaUri, "p", null, 1f, placement)

    @Test
    fun aPhotoAcrossTheSeamShowsOnBothSlides() {
        // Half a slide wide, centred on the seam: the right quarter of slide 1, the left quarter of slide 2.
        val carousel = Carousel(2, listOf(photo(Placement(x = 1f, y = 0.5f, width = 0.5f))))
        val sources = listOf(solid(Color.RED))
        val first = CarouselRenderer.renderSlide(carousel, sources, settings, 0, 400, 400)
        val second = CarouselRenderer.renderSlide(carousel, sources, settings, 1, 400, 400)
        assertEquals(Color.RED, first.getPixel(350, 200))
        assertEquals(Color.WHITE, first.getPixel(250, 200))
        assertEquals(Color.RED, second.getPixel(50, 200))
        assertEquals(Color.WHITE, second.getPixel(150, 200))
    }

    @Test
    fun laterPhotosLieOnTopAndRotationTurnsThem() {
        val carousel = Carousel(
            2,
            listOf(
                photo(Placement(x = 0.5f, y = 0.5f, width = 0.8f)),
                // Smaller, turned 45°: its corners point up, down, left and right.
                photo(Placement(x = 0.5f, y = 0.5f, width = 0.4f, rotation = 45f)),
            ),
        )
        val slide = CarouselRenderer.renderSlide(carousel, listOf(solid(Color.RED), solid(Color.BLUE)), settings, 0, 400, 400)
        assertEquals(Color.BLUE, slide.getPixel(200, 200))
        // Straight up from the centre, past where an upright square would end: the turned corner.
        assertEquals(Color.BLUE, slide.getPixel(200, 200 - 100))
        // Where the upright square's corner would be: the red photo below shows instead.
        assertEquals(Color.RED, slide.getPixel(200 - 75, 200 - 75))
    }

    /** 300 x 100: red, green and blue thirds. */
    private fun bands(): PlatformBitmap = pictureOf(300, 100) {
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { i, color ->
            drawRect(i * 100f, 0f, (i + 1) * 100f, 100f, paint(color))
        }
    }

    @Test
    fun aCroppedPhotoShowsItsMiddle() {
        // Cut to a square: only the green middle third shows, filling a square box.
        val photo = CarouselPhoto(emptyMediaUri, "p", null, 3f, Placement(x = 0.5f, y = 0.5f, width = 0.5f), crop = 1f)
        val slide = CarouselRenderer.renderSlide(Carousel(2, listOf(photo)), listOf(bands()), settings, 0, 400, 400)
        assertEquals(Color.GREEN, slide.getPixel(200, 200))
        assertEquals(Color.GREEN, slide.getPixel(105, 105))
        assertEquals(Color.GREEN, slide.getPixel(295, 295))
        assertEquals(Color.WHITE, slide.getPixel(90, 200))
    }

    @Test
    fun aPrintHasAWhiteBorderAndADeepBottom() {
        val photo = CarouselPhoto(emptyMediaUri, "p", null, 1f, Placement(x = 0.5f, y = 0.5f, width = 0.6f), crop = 1f, framed = true)
        val gray = FrameSettings(bgColor = Color.DKGRAY)
        val slide = CarouselRenderer.renderSlide(Carousel(2, listOf(photo)), listOf(solid(Color.BLUE)), gray, 0, 400, 400)
        // Print: 240 wide; photo 240 / 1.12 = 214 wide, starting 13 px in from the print's top left.
        val printHeight = 240 / com.squareify.app.printAspect(1f)
        val top = 200 - printHeight / 2
        assertEquals(Color.BLUE, slide.getPixel(200, (top + 13 + 107).toInt()))
        val card = slide.getPixel(200, (top + 5).toInt())
        assertTrue("card ${hex(card)}", Color.red(card) > 240 && Color.blue(card) > 230)
        // The deep bottom: white well below the photo.
        val bottom = slide.getPixel(200, (top + 13 + 214 + 25).toInt())
        assertTrue("bottom ${hex(bottom)}", Color.red(bottom) > 240)
    }

    @Test
    fun theStripMatchesTheSlides() {
        val carousel = Carousel(3, listOf(photo(Placement(x = 1.5f, y = 0.5f, width = 2f))))
        val sources = listOf(solid(Color.GREEN))
        val strip = CarouselRenderer.renderStrip(carousel, sources, settings, 1200, 400)
        val middle = CarouselRenderer.renderSlide(carousel, sources, settings, 1, 400, 400)
        listOf(5, 200, 395).forEach { x -> assertEquals(strip.getPixel(400 + x, 200), middle.getPixel(x, 200)) }
    }
}
