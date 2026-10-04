package com.squareify.desktop

import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.CollageLayout
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import com.squareify.app.ShortClips
import com.squareify.app.VideoEdit
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.toMediaUri
import com.squareify.desktop.TestMedia.Companion.blue
import com.squareify.desktop.TestMedia.Companion.green
import com.squareify.desktop.TestMedia.Companion.pixel
import com.squareify.desktop.TestMedia.Companion.red
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Photos and videos on Windows: reading, saving, FFmpeg, and videos drawn by the shared renderers. */
class MediaTest {
    private val dir = Files.createTempDirectory("kk-media").toFile()
    private val media = TestMedia(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun near(expected: Long, actual: Long, tolerance: Long) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")

    @Test
    fun photosAreTurnedUprightAsTheCameraSaid() {
        // Stored landscape, red left; EXIF 6 = turn 90° clockwise to show it.
        val upright = DesktopImages.decode(media.photo("turned.jpg", 400, 200, orientation = 6), 2000)
        assertEquals(200, upright.width)
        assertEquals(400, upright.height)
        assertTrue(red(upright.pixel(100, 50)) > 200 && blue(upright.pixel(100, 50)) < 60, "top should be red")
        assertTrue(blue(upright.pixel(100, 350)) > 200 && red(upright.pixel(100, 350)) < 60, "bottom should be blue")
    }

    @Test
    fun bigPhotosAreLoadedNoLargerThanAsked() {
        val small = DesktopImages.decode(media.photo("big.jpg", 4000, 3000), 720)
        assertEquals(720, small.width)
        assertEquals(540, small.height)
        // Still sharp halves, no smearing across the middle beyond a few pixels.
        assertTrue(red(small.pixel(350, 270)) > 200)
        assertTrue(blue(small.pixel(370, 270)) > 200)
    }

    @Test
    fun ffmpegIsThereWithTheRadeonEncoder() {
        assertTrue(Ffmpeg.available)
        assertEquals("h264_amf", Ffmpeg.h264Encoder)
    }

    @Test
    fun probeSeesSizeLengthSoundAndTurn() {
        val probe = Ffmpeg.probe(media.clip("a.mp4", 640, 360, seconds = 2.0))
        assertEquals(640, probe.width)
        assertEquals(360, probe.height)
        near(2000, probe.durationMs, 100)
        assertEquals(30.0, probe.fps, 0.01)
        assertTrue(probe.hasAudio)
        val turned = Ffmpeg.probe(media.clip("t.mp4", 640, 360, seconds = 1.0, sound = false, rotate = 90))
        assertEquals(360, turned.width)
        assertEquals(640, turned.height)
        assertFalse(turned.hasAudio)
    }

    @Test
    fun aVideoIsPaddedToItsFormatWithItsSound() {
        val clip = media.clip("wide.mp4", 640, 360, seconds = 2.0)
        val settings = FrameSettings(format = FrameFormat.SQUARE, bgColor = 0xFF00FF00.toInt())
        val out = File(dir, "out.mp4")
        DesktopVideo.render(clip, settings, out) {}
        val probe = Ffmpeg.probe(out)
        val (w, h) = PhotoProcessor.canvasSize(640, 360, settings)
        assertEquals(w / 2 * 2, probe.width)
        assertEquals(h / 2 * 2, probe.height)
        near(2000, probe.durationMs, 150)
        assertTrue(probe.hasAudio)
        // Green above the picture.
        val frame = DesktopImages.decode(Ffmpeg.frameAt(out, 1000, 2000), 2000)
        val top = frame.pixel(probe.width / 2, 10)
        assertTrue(green(top) > 200 && red(top) < 60 && blue(top) < 60, "padding is ${top.toUInt().toString(16)}")
    }

    @Test
    fun speedTrimBoomerangAndMuteFollowThePhonesRules() {
        val clip = media.clip("four.mp4", 320, 320, seconds = 4.0)
        fun lengthOf(edit: VideoEdit): Pair<Long, Boolean> {
            val out = File(dir, "e${edit.hashCode().toUInt()}.mp4")
            DesktopVideo.render(clip, FrameSettings(video = edit), out) {}
            return Ffmpeg.probe(out).let { it.durationMs to it.hasAudio }
        }
        lengthOf(VideoEdit(speed = 2f)).let { (ms, sound) -> near(2000, ms, 150); assertFalse(sound) }
        lengthOf(VideoEdit(speed = 0.5f)).let { (ms, sound) -> near(8000, ms, 200); assertFalse(sound) }
        lengthOf(VideoEdit(trimStartMs = 1000, trimEndMs = 3000)).let { (ms, sound) -> near(2000, ms, 150); assertTrue(sound) }
        lengthOf(VideoEdit(boomerang = true)).let { (ms, sound) -> near(8000, ms, 200); assertFalse(sound) }
        lengthOf(VideoEdit(muted = true)).let { (ms, sound) -> near(4000, ms, 150); assertFalse(sound) }
    }

    @Test
    fun aVideoCollageRunsAsLongAsItsLongestClipWithTheChosenSound() {
        val long = media.clip("long.mp4", 640, 360, seconds = 3.0)
        val short = media.clip("short.mp4", 360, 640, seconds = 1.0, sound = false, color = "red")
        val photo = media.photo("p.jpg")
        val collage = Collage(
            CollageLayout.forCount(3).first(),
            listOf(
                CollageCell(long.toMediaUri(), "long", null, isVideo = true),
                CollageCell(short.toMediaUri(), "short", null, isVideo = true, fit = CellFit.FIT),
                CollageCell(photo.toMediaUri(), "photo", null),
            ),
            shortClips = ShortClips.FREEZE,
            soundCell = 0,
        )
        val out = File(dir, "collage.mp4")
        DesktopVideo.renderCollage(collage, FrameSettings(format = FrameFormat.PORTRAIT), out) {}
        val probe = Ffmpeg.probe(out)
        assertEquals(1080, probe.width)
        assertEquals(1350, probe.height)
        near(3000, probe.durationMs, 150)
        assertTrue(probe.hasAudio)
        assertEquals(30.0, probe.fps, 0.01)
    }
}
