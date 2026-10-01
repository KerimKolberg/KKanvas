package com.squareify.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private var now = 10_000L
    private fun history() = EditHistory(0) { now }

    @Test
    fun aDragIsOneStep() {
        val h = history()
        // A slider dragged from 0 to 5, a change every 16 ms.
        (1..5).forEach { now += 16; h.set(it) }
        h.undo()
        assertEquals(0, h.value)
        assertFalse(h.canUndo)
    }

    @Test
    fun separateChangesAreSeparateSteps() {
        val h = history()
        h.set(1)
        now += 2000
        h.set(2)
        h.undo()
        assertEquals(1, h.value)
        h.undo()
        assertEquals(0, h.value)
        h.redo()
        h.redo()
        assertEquals(2, h.value)
        assertFalse(h.canRedo)
    }

    @Test
    fun aNewChangeAfterUndoDropsTheRedos() {
        val h = history()
        h.set(1)
        now += 2000
        h.undo()
        assertTrue(h.canRedo)
        h.set(5)
        assertFalse(h.canRedo)
    }

    @Test
    fun unrecordedChangesAreNotSteps() {
        val h = history()
        h.set(1, record = false)
        assertFalse(h.canUndo)
        assertEquals(1, h.value)
    }

    @Test
    fun partsReadAndWriteTheirShare() {
        val h = EditHistory("a" to 1) { now }
        var number by h.part({ it.second }, { state, n -> state.copy(second = n) })
        number = 7
        assertEquals("a" to 7, h.value)
        h.undo()
        assertEquals(1, number)
    }
}
