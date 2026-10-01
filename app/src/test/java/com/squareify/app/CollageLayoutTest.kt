package com.squareify.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CollageLayoutTest {
    private val area = Box(0f, 0f, 1000f, 1000f)

    @Test
    fun withoutSpacingCellsTouch() {
        val cells = collageCellBoxes(CollageLayout.SIDE_BY_SIDE, 0f, area)
        assertEquals(Box(0f, 0f, 500f, 1000f), cells[0])
        assertEquals(Box(500f, 0f, 1000f, 1000f), cells[1])
    }

    @Test
    fun spacingIsAFullGapBetweenCellsAndNoneAtTheEdges() {
        // 100% spacing = 6% of 1000 px = 60 px between the cells.
        val cells = collageCellBoxes(CollageLayout.SIDE_BY_SIDE, 1f, area)
        assertEquals(0f, cells[0].left, 0.01f)
        assertEquals(470f, cells[0].right, 0.01f)
        assertEquals(530f, cells[1].left, 0.01f)
        assertEquals(1000f, cells[1].right, 0.01f)
        assertEquals(0f, cells[0].top, 0.01f)
        assertEquals(1000f, cells[0].bottom, 0.01f)
    }

    @Test
    fun gridCellsAreEqual() {
        val cells = collageCellBoxes(CollageLayout.GRID_3X3, 1f, area)
        val expected = (1000f - 2 * 60f) / 3
        cells.forEach {
            assertEquals(expected, it.width, 0.01f)
            assertEquals(expected, it.height, 0.01f)
        }
    }

    @Test
    fun everyLayoutCoversTheWholeArea() {
        CollageLayout.entries.forEach { layout ->
            val total = layout.cells.sumOf { (it.width * it.height).toDouble() }
            assertEquals(layout.label, 1.0, total, 1e-4)
        }
    }

    @Test
    fun layoutsOfferedPerPhotoCount() {
        assertEquals(listOf(CollageLayout.SIDE_BY_SIDE, CollageLayout.STACKED), CollageLayout.forCount(2))
        assertEquals(listOf(CollageLayout.TWO_THREE), CollageLayout.forCount(5))
        assertEquals(listOf(CollageLayout.GRID_3X3), CollageLayout.forCount(7))
        assertEquals(listOf(CollageLayout.GRID_3X3), CollageLayout.forCount(9))
        (2..CollageLayout.MAX_PHOTOS).forEach { n ->
            assertEquals("layouts for $n", true, CollageLayout.forCount(n).isNotEmpty())
        }
    }
}
