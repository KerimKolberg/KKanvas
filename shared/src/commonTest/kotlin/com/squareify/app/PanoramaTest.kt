package com.squareify.app

import kotlin.test.assertEquals
import kotlin.test.Test

class PanoramaTest {

    @Test
    fun autoSlidesShowThePhotoAtFullHeight() {
        // A 4:1 panorama: 4 square slides, or 5 portrait (4:5) ones.
        assertEquals(4, Panorama.autoSlides(8000, 2000, FrameFormat.SQUARE))
        assertEquals(5, Panorama.autoSlides(8000, 2000, FrameFormat.PORTRAIT))
        // Never fewer than 2 or more than 10.
        assertEquals(2, Panorama.autoSlides(3000, 4000, FrameFormat.SQUARE))
        assertEquals(10, Panorama.autoSlides(30000, 1000, FrameFormat.SQUARE))
    }

    @Test
    fun slidesAreInstagramWidth() {
        assertEquals(1440 to 1800, slideSize(FrameFormat.PORTRAIT))
    }

    @Test
    fun fillCropsTheSidesOfAWiderPhotoByPosition() {
        // 5:1 photo on a 4:1 strip: 1000 of its 5000 px width is cut off.
        val strip = 4000f to 1000f
        val centred = panoramaPlacement(5000, 1000, Panorama(4), strip.first, strip.second).first
        assertEquals(Box(500f, 0f, 4500f, 1000f), centred)
        val left = panoramaPlacement(5000, 1000, Panorama(4, position = -1f), strip.first, strip.second).first
        assertEquals(0f, left.left, 0.01f)
        val right = panoramaPlacement(5000, 1000, Panorama(4, position = 1f), strip.first, strip.second).first
        assertEquals(5000f, right.right, 0.01f)
        assertEquals(1000f, panoramaOverflow(5000, 1000, Panorama(4), strip.first, strip.second), 0.01f)
    }

    @Test
    fun fillCropsTopAndBottomOfANarrowerPhoto() {
        // 3:1 photo on a 4:1 strip: keeps the full width, cuts height to 750 of 1000.
        val crop = panoramaPlacement(3000, 1000, Panorama(4), 4000f, 1000f).first
        assertEquals(Box(0f, 125f, 3000f, 875f), crop)
    }

    @Test
    fun fitKeepsTheWholePhotoCentred() {
        val (crop, dst) = panoramaPlacement(3000, 1000, Panorama(4, fit = CellFit.FIT), 4000f, 1000f)
        assertEquals(Box(0f, 0f, 3000f, 1000f), crop)
        assertEquals(Box(500f, 0f, 3500f, 1000f), dst)
    }
}
