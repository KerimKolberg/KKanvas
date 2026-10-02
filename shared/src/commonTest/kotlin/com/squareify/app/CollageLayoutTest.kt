package com.squareify.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

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
            assertEquals(1.0, total, 1e-4, layout.label)
        }
    }

    @Test
    fun neighborsInAGrid() {
        // 2 x 2: 0 1 / 2 3
        val grid = CollageLayout.GRID_2X2
        assertEquals(1, grid.neighbor(0, Direction.RIGHT, 4))
        assertEquals(2, grid.neighbor(0, Direction.DOWN, 4))
        assertEquals(0, grid.neighbor(2, Direction.UP, 4))
        assertEquals(2, grid.neighbor(3, Direction.LEFT, 4))
        assertEquals(null, grid.neighbor(0, Direction.LEFT, 4))
        assertEquals(null, grid.neighbor(0, Direction.UP, 4))
    }

    @Test
    fun neighborsInUnevenLayouts() {
        // Big left: 0 is the left half, 1 top right, 2 bottom right.
        val bigLeft = CollageLayout.BIG_LEFT
        assertEquals(1, bigLeft.neighbor(0, Direction.RIGHT, 3))
        assertEquals(0, bigLeft.neighbor(2, Direction.LEFT, 3))
        assertEquals(2, bigLeft.neighbor(1, Direction.DOWN, 3))
        // 2 + 3: 0 1 on top, 2 3 4 below; the middle bottom cell overlaps both top cells equally.
        val twoThree = CollageLayout.TWO_THREE
        assertEquals(2, twoThree.neighbor(0, Direction.DOWN, 5))
        assertEquals(0, twoThree.neighbor(3, Direction.UP, 5))
        assertEquals(1, twoThree.neighbor(4, Direction.UP, 5))
    }

    @Test
    fun emptyCellsAreNotNeighbors() {
        // 7 photos in 3 x 3: cells 7 and 8 are empty.
        assertEquals(null, CollageLayout.GRID_3X3.neighbor(6, Direction.RIGHT, 7))
        assertEquals(null, CollageLayout.GRID_3X3.neighbor(4, Direction.DOWN, 7))
        assertEquals(6, CollageLayout.GRID_3X3.neighbor(3, Direction.DOWN, 7))
    }

    @Test
    fun layoutsOfferedPerPhotoCount() {
        assertEquals(listOf(CollageLayout.SIDE_BY_SIDE, CollageLayout.STACKED), CollageLayout.forCount(2))
        assertEquals(listOf(CollageLayout.TWO_THREE), CollageLayout.forCount(5))
        assertEquals(listOf(CollageLayout.GRID_3X3), CollageLayout.forCount(7))
        assertEquals(listOf(CollageLayout.GRID_3X3), CollageLayout.forCount(9))
        (2..CollageLayout.MAX_PHOTOS).forEach { n ->
            assertTrue(CollageLayout.forCount(n).isNotEmpty(), "layouts for $n")
        }
    }
}
