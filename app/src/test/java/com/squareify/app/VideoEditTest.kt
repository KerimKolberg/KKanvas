package com.squareify.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditTest {

    @Test
    fun defaultUsesTheWholeClipWithSound() {
        val edit = VideoEdit()
        assertEquals(0L..8000L, edit.rangeMs(8000))
        assertEquals(8000L, edit.outputLengthMs(8000))
        assertTrue(edit.keepsSound)
    }

    @Test
    fun speedChangesTheLengthAndDropsTheSound() {
        assertEquals(2000L, VideoEdit(speed = 4f).outputLengthMs(8000))
        assertEquals(16000L, VideoEdit(speed = 0.5f).outputLengthMs(8000))
        assertFalse(VideoEdit(speed = 2f).keepsSound)
        assertFalse(VideoEdit(muted = true).keepsSound)
    }

    @Test
    fun trimIsKeptInsideTheClip() {
        assertEquals(1000L..3000L, VideoEdit(trimStartMs = 1000, trimEndMs = 3000).rangeMs(8000))
        // An end past the clip is the clip's end; a range shorter than the minimum is stretched.
        assertEquals(1000L..8000L, VideoEdit(trimStartMs = 1000, trimEndMs = 99000).rangeMs(8000))
        assertEquals(1000L..1300L, VideoEdit(trimStartMs = 1000, trimEndMs = 1000).rangeMs(8000))
    }

    @Test
    fun boomerangPlaysTwiceAndIsCapped() {
        assertEquals(4000L, VideoEdit(trimEndMs = 2000, boomerang = true).outputLengthMs(8000))
        assertEquals(0L..10000L, VideoEdit(boomerang = true).rangeMs(60000))
        assertFalse(VideoEdit(boomerang = true).keepsSound)
    }
}
