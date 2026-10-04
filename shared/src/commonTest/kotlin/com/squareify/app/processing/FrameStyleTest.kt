package com.squareify.app.processing

import com.squareify.app.Border
import com.squareify.app.FrameSettings
import com.squareify.app.FrameStyle
import com.squareify.app.PlatformBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameStyleTest {
    private fun red(width: Int, height: Int): PlatformBitmap =
        picture(Color.RED, width, height)

    private fun settings(style: FrameStyle) = FrameSettings(bgColor = Color.BLUE, border = Border(frame = style))

    private fun isRed(p: Int) = Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) < 60

    /** First and last row of the red photo along column [x]. */
    private fun redRows(bitmap: PlatformBitmap, x: Int): IntRange {
        val rows = (0 until bitmap.height).filter { isRed(bitmap.getPixel(x, it)) }
        return rows.first()..rows.last()
    }

    @Test
    fun noFrameKeepsTheOldSizes() {
        assertEquals(1000 to 1000, PhotoProcessor.canvasSize(1000, 1000, settings(FrameStyle.NONE)))
    }

    @Test
    fun theCanvasGrowsSoThePhotoKeepsItsResolution() {
        // A 1000 px square photo with a Polaroid frame: 60 px sides and top, 260 px bottom.
        val (w, h) = PhotoProcessor.canvasSize(1000, 1000, settings(FrameStyle.POLAROID))
        assertEquals(1320, w)
        assertEquals(1320, h)
        val out = PhotoProcessor.frame(red(1000, 1000), settings(FrameStyle.POLAROID), w, h)
        val rows = redRows(out, w / 2)
        assertEquals(1000, rows.last - rows.first + 1, 2)
    }

    @Test
    fun polaroidHasADeepBottomEdge() {
        val s = settings(FrameStyle.POLAROID)
        val out = PhotoProcessor.frame(red(400, 400), s, 600, 600)
        val rows = redRows(out, 300)
        // The card below the photo is much deeper than above it.
        var top = rows.first - 1
        while (top > 0 && Color.blue(out.getPixel(300, top)) < 250) top--
        var bottom = rows.last + 1
        while (bottom < 599 && Color.blue(out.getPixel(300, bottom)) < 250) bottom++
        val above = rows.first - top
        val below = bottom - rows.last
        assertTrue("card above $above px, below $below px", below > above * 3)
        // And it's off-white, not the blue background.
        val card = out.getPixel(300, rows.last + below / 2)
        assertTrue(Color.red(card) > 240 && Color.green(card) > 240)
    }

    @Test
    fun filmStripHasDarkBandsWithHoles() {
        val out = PhotoProcessor.frame(red(600, 400), settings(FrameStyle.FILM), 800, 800)
        val rows = redRows(out, 400)
        // Above the photo: a dark band, with light holes along its middle.
        var bandTop = rows.first - 1
        while (bandTop > 0 && Color.blue(out.getPixel(400, bandTop)) < 250) bandTop--
        val bandY = (bandTop + rows.first) / 2
        val band = (0 until 800).map { out.getPixel(it, bandY) }
        assertTrue("no dark film", band.any { Color.red(it) < 40 && Color.green(it) < 40 && Color.blue(it) < 40 })
        assertTrue("no sprocket holes", band.any { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 })
    }

    @Test
    fun thinBorderIsWhiteAroundThePhoto() {
        val out = PhotoProcessor.frame(red(400, 400), settings(FrameStyle.THIN), 600, 600)
        val rows = redRows(out, 300)
        assertEquals(Color.WHITE, out.getPixel(300, rows.first - 4))
        assertEquals(Color.WHITE, out.getPixel(300, rows.last + 4))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
