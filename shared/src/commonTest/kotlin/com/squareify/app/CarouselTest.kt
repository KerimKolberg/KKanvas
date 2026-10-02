package com.squareify.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class CarouselTest {
    private val square = 1f // slide height for 1:1, in slide widths

    @Test
    fun spreadPutsOnePhotoInTheMiddleOfEachShare() {
        val placements = spreadPlacements(listOf(1f, 1f, 1f), slides = 3, heightUnits = square)
        assertEquals(listOf(0.5f, 1.5f, 2.5f), placements.map { it.x })
        placements.forEach { assertEquals(0.84f, it.width, 1e-4f) }
        // A wide photo is limited by the width of its share, a tall one by the height.
        assertEquals(0.84f, spreadPlacements(listOf(3f), 1, square).single().width, 1e-4f)
        assertEquals(0.42f, spreadPlacements(listOf(0.5f), 1, square).single().width, 1e-4f)
    }

    @Test
    fun hitTestFollowsRotation() {
        // A 1 x 0.2 bar across the middle of slide 1, turned upright by 90°.
        val bar = Placement(x = 0.5f, y = 0.5f, width = 1f, rotation = 90f)
        assertTrue(placementContains(bar, aspect = 5f, heightUnits = square, px = 0.5f, py = 0.9f))
        assertFalse(placementContains(bar, aspect = 5f, heightUnits = square, px = 0.9f, py = 0.5f))
    }

    @Test
    fun topPhotoWins() {
        val shapes = listOf(Placement(1f, 0.5f, 1f) to 1f, Placement(1.2f, 0.5f, 0.4f) to 1f)
        assertEquals(1, carouselPhotoAt(shapes, square, 1.2f, 0.5f))
        assertEquals(0, carouselPhotoAt(shapes, square, 0.6f, 0.5f))
        assertNull(carouselPhotoAt(shapes, square, 1.9f, 0.05f))
    }

    @Test
    fun snapsToSlideEdgesAndMiddles() {
        // The left edge is 0.01 from the seam between slides 1 and 2: pulled onto it.
        val s = snapPlacement(Placement(x = 1.26f, y = 0.5f, width = 0.5f), 1f, square, 3, threshold = 0.03f)
        assertEquals(1.25f, s.placement.x, 1e-4f)
        assertEquals(listOf(1f), s.guidesX)
        // Nearly level: straightened; nearly centred vertically: centred.
        val t = snapPlacement(Placement(x = 2.3f, y = 0.51f, width = 0.3f, rotation = 2f), 1f, square, 3, 0.03f)
        assertEquals(0f, t.placement.rotation, 1e-4f)
        assertEquals(0.5f, t.placement.y, 1e-4f)
        // Far from everything: left alone.
        val u = snapPlacement(Placement(x = 2.2f, y = 0.3f, width = 0.3f), 1f, square, 3, 0.01f)
        assertEquals(2.2f, u.placement.x, 1e-4f)
        assertTrue(u.guidesX.isEmpty())
    }

    @Test
    fun warnsAboutSliversAndEmptySlides() {
        // Reaches only 0.02 into slide 2.
        val warnings = carouselWarnings(3, listOf(Placement(x = 0.52f, y = 0.5f, width = 1f) to 1f), square)
        assertTrue(warnings.any { it.contains("sliver") && it.contains("slide 2") }, warnings.toString())
        assertTrue(warnings.any { it.contains("Slide 3 has no photo") }, warnings.toString())
        // A photo straddling the seam generously is fine.
        assertTrue(carouselWarnings(2, listOf(Placement(1f, 0.5f, 1.2f) to 1f), square).isEmpty())
    }

    @Test
    fun fitSlideKeepsThePhotoInsideOneSlide() {
        val wide = fitSlidePlacement(slide = 2, aspect = 2f, heightUnits = 1.25f)
        assertEquals(2.5f, wide.x)
        assertEquals(1f, wide.width)
        val tall = fitSlidePlacement(slide = 0, aspect = 0.5f, heightUnits = 1.25f)
        assertEquals(0.625f, tall.width, 1e-4f)
    }
}
