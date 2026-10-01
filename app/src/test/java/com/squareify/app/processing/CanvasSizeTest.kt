package com.squareify.app.processing

import com.squareify.app.FrameFormat
import com.squareify.app.outputFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasSizeTest {

    @Test
    fun squarePadsTheShortSide() {
        assertEquals(4000 to 4000, PhotoProcessor.canvasSize(4000, 3000, FrameFormat.SQUARE))
        assertEquals(1920 to 1920, PhotoProcessor.canvasSize(1080, 1920, FrameFormat.SQUARE))
    }

    @Test
    fun landscapeKeepsWidthInPortraitFormats() {
        assertEquals(4000 to 5000, PhotoProcessor.canvasSize(4000, 3000, FrameFormat.PORTRAIT))
        assertEquals(4000 to 5334, PhotoProcessor.canvasSize(4000, 3000, FrameFormat.GRID))
        assertEquals(1920 to 3414, PhotoProcessor.canvasSize(1920, 1080, FrameFormat.STORY))
    }

    @Test
    fun tallImageKeepsHeight() {
        // 9:16 phone video into 4:5: the width grows.
        assertEquals(1536 to 1920, PhotoProcessor.canvasSize(1080, 1920, FrameFormat.PORTRAIT))
    }

    @Test
    fun matchingAspectRatioAddsNoPadding() {
        assertEquals(1080 to 1920, PhotoProcessor.canvasSize(1080, 1920, FrameFormat.STORY))
        assertEquals(3000 to 4000, PhotoProcessor.canvasSize(3000, 4000, FrameFormat.GRID))
    }

    @Test
    fun marginGrowsTheCanvasSoThePhotoKeepsItsResolution() {
        // 10% of the canvas on each side of a 1000 px square: 1000 / 0.8.
        assertEquals(1250 to 1250, PhotoProcessor.canvasSize(1000, 1000, FrameFormat.SQUARE, 0.1f))
        // 9:16 canvas, margin 10% of its 1250 px width = 125 px per side, leaving exactly 1000 px.
        assertEquals(1250 to 2223, PhotoProcessor.canvasSize(1000, 1000, FrameFormat.STORY, 0.1f))
    }

    @Test
    fun zeroMarginMatchesThePlainCanvas() {
        assertEquals(
            PhotoProcessor.canvasSize(4000, 3000, FrameFormat.PORTRAIT),
            PhotoProcessor.canvasSize(4000, 3000, FrameFormat.PORTRAIT, 0f),
        )
    }

    @Test
    fun outputFileNameUsesFormatPrefixAndDropsExtension() {
        assertEquals("story_IMG_1234", outputFileName(FrameFormat.STORY, "IMG_1234.jpg"))
        assertEquals("squared_clip.final", outputFileName(FrameFormat.SQUARE, "clip.final.mp4"))
        assertEquals("portrait_.hidden", outputFileName(FrameFormat.PORTRAIT, ".hidden"))
    }
}
