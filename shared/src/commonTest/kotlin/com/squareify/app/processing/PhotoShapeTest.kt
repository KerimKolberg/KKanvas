package com.squareify.app.processing

import com.squareify.app.Adjustments
import com.squareify.app.Border
import com.squareify.app.Carousel
import com.squareify.app.CarouselPhoto
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.CollageLayout
import com.squareify.app.FrameSettings
import com.squareify.app.PhotoShape
import com.squareify.app.Placement
import com.squareify.app.PlatformBitmap
import com.squareify.app.emptyMediaUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoShapeTest {
    private fun red(width: Int = 400, height: Int = 400): PlatformBitmap =
        picture(Color.RED, width, height)

    /** A square red photo filling a white 400 x 400 canvas, in [shape]. */
    private fun single(shape: PhotoShape) =
        PhotoProcessor.frame(red(), FrameSettings(bgColor = Color.WHITE, border = Border(shape = shape)), 400, 400)

    @Test
    fun circleCutsTheCorners() {
        val out = single(PhotoShape.CIRCLE)
        assertEquals(Color.RED, out.getPixel(200, 200))
        assertEquals(Color.WHITE, out.getPixel(20, 20))
        assertEquals(Color.RED, out.getPixel(200, 5))
    }

    @Test
    fun archIsRoundOnTopAndSquareBelow() {
        val out = single(PhotoShape.ARCH)
        assertEquals(Color.WHITE, out.getPixel(10, 10))
        assertEquals(Color.RED, out.getPixel(5, 395))
        assertEquals(Color.RED, out.getPixel(395, 395))
    }

    @Test
    fun tornEdgesBiteIntoThePhoto() {
        val out = single(PhotoShape.TORN)
        assertEquals(Color.RED, out.getPixel(200, 200))
        // Somewhere along the top edge the tear leaves the background showing.
        val firstRed = (0 until 400 step 10).map { x -> (0 until 400).first { y -> Color.green(out.getPixel(x, y)) < 128 } }
        assertTrue("first red row per column: $firstRed", firstRed.any { it >= 3 })
        // But never deeper than a little way in (away from the sides, which are torn too).
        assertTrue((20 until 380).all { x -> out.getPixel(x, 20) == Color.RED })
    }

    @Test
    fun aCollageCellCanHaveItsOwnShapeAndColours() {
        val collage = Collage(
            CollageLayout.SIDE_BY_SIDE,
            listOf(
                CollageCell(emptyMediaUri, "a", null, shape = PhotoShape.CIRCLE),
                CollageCell(emptyMediaUri, "b", null, adjustments = Adjustments(saturation = 0f)),
            ),
            spacing = 0f,
        )
        val out = CollageRenderer.render(collage, listOf(red(), red()), FrameSettings(bgColor = Color.WHITE), 800, 400)
        // Left: a red oval, white in its corners. Right: grey, the same photo without colour.
        assertEquals(Color.RED, out.getPixel(200, 200))
        assertEquals(Color.WHITE, out.getPixel(10, 10))
        val grey = out.getPixel(600, 200)
        assertTrue(Color.red(grey) == Color.green(grey) && Color.green(grey) == Color.blue(grey))
    }

    @Test
    fun aCarouselPhotoCanBeAPill() {
        val carousel = Carousel(2, listOf(CarouselPhoto(emptyMediaUri, "p", null, 2f, Placement(1f, 0.5f, 1f), shape = PhotoShape.PILL)))
        val strip = CarouselRenderer.renderStrip(carousel, listOf(red(400, 200)), FrameSettings(bgColor = Color.WHITE), 800, 400)
        // The photo spans x 200..600, y 100..300; a pill's ends are round.
        assertEquals(Color.RED, strip.getPixel(400, 200))
        assertEquals(Color.WHITE, strip.getPixel(203, 103))
        assertEquals(Color.RED, strip.getPixel(400, 103))
    }
}
