package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareify.app.Border
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import com.squareify.app.VideoEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Renders a synthetic phone-style video (landscape-coded, rotated 90°) with a square moving at
 * constant speed, then checks that every output frame shows the square one step further on, and
 * that the picture never shifts or changes size.
 */
@RunWith(AndroidJUnit4::class)
class VideoProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun portraitVideoToSquare() = checkSmoothRender(
        rotation = 90,
        settings = FrameSettings(bgColor = Color.BLACK),
    )

    @Test
    fun landscapeVideoToStoryWithMargin() = checkSmoothRender(
        rotation = 0,
        settings = FrameSettings(format = FrameFormat.STORY, bgColor = Color.BLACK, border = Border(margin = 0.4f)),
    )

    /** The sound is copied over whole; it went missing in an early version of the app. */
    @Test
    fun soundIsKept() = runBlocking {
        val source = File(context.cacheDir, "test_source_sound.mp4")
        val output = File(context.cacheDir, "test_output_sound.mp4")
        TestVideos.create(source, FRAMES, withSound = true)

        val soundDropped = VideoProcessor.render(context, Uri.fromFile(source), FrameSettings(), output) { }

        assertFalse("render reported dropped sound", soundDropped)
        val sourceSound = TestVideos.trackDurationUs(source, "audio/")!!
        val outputSound = TestVideos.trackDurationUs(output, "audio/")
        assertNotNull("output has no sound track", outputSound)
        assertEquals("sound length", sourceSound.toDouble(), outputSound!!.toDouble(), 50_000.0)
    }

    /** Renders a small 2 s clip (square marker moving 8 px per frame) with [edit]; returns marker x per frame. */
    private fun renderEdited(edit: VideoEdit, withSound: Boolean = true): Pair<List<Double>, File> = runBlocking {
        val source = File(context.cacheDir, "test_edit_source.mp4")
        val output = File(context.cacheDir, "test_edit_output.mp4")
        TestVideos.create(source, FRAMES, width = 640, height = 360, step = 8f, withSound = withSound)
        val dropped = VideoProcessor.render(context, Uri.fromFile(source), FrameSettings(bgColor = Color.BLACK, video = edit), output) { }
        assertFalse("sound reported as dropped", dropped)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.absolutePath)
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
        val xs = (0 until count).map { analyze(retriever.getFrameAtIndex(it)!!).markerX }
        retriever.release()
        Log.i(TAG, "edit=$edit frames=$count xs=${xs.map { it.toInt() }}")
        xs to output
    }

    /** Where the marker is in source frame [n] (640 px wide clip, so no scaling into the square). */
    private fun markerAt(n: Int) = 100.0 + 8 * n + 20

    @Test
    fun trimKeepsOnlyThePartChosen() {
        // 0.51 s to 1.51 s: source frames 16 to 45.
        val (xs, output) = renderEdited(VideoEdit(trimStartMs = 510, trimEndMs = 1510))
        assertTrue("frames ${xs.size}", xs.size in 29..31)
        assertEquals(markerAt(16), xs.first(), 9.0)
        // The sound is cut to the same second.
        assertEquals(1_000_000.0, TestVideos.trackDurationUs(output, "audio/")!!.toDouble(), 80_000.0)
    }

    @Test
    fun doubleSpeedSkipsEveryOtherFrameAndDropsTheSound() {
        val (xs, output) = renderEdited(VideoEdit(speed = 2f))
        assertTrue("frames ${xs.size}", xs.size in 29..31)
        // Each frame moves on two source frames.
        assertEquals(16.0, xs[10] - xs[9], 3.0)
        assertEquals(1_000_000.0, TestVideos.trackDurationUs(output, "video/")!!.toDouble(), 80_000.0)
        assertNull("sound kept at 2x", TestVideos.trackDurationUs(output, "audio/"))
    }

    @Test
    fun slowMotionStretchesTheClip() {
        val (xs, output) = renderEdited(VideoEdit(speed = 0.5f))
        assertEquals(FRAMES, xs.size)
        assertEquals(4_000_000.0, TestVideos.trackDurationUs(output, "video/")!!.toDouble(), 150_000.0)
    }

    @Test
    fun boomerangGoesThereAndBack() {
        val (xs, _) = renderEdited(VideoEdit(trimEndMs = 1000, boomerang = true))
        // 30 frames forward, 29 back (the turning frame isn't shown twice).
        assertTrue("frames ${xs.size}", xs.size in 57..61)
        val turn = xs.indices.maxBy { xs[it] }
        assertTrue("turns at frame $turn", turn in 27..31)
        assertEquals(xs.first(), xs.last(), 9.0)
    }

    @Test
    fun mutedHasNoSound() {
        val (_, output) = renderEdited(VideoEdit(muted = true))
        assertNull(TestVideos.trackDurationUs(output, "audio/"))
    }

    private fun checkSmoothRender(rotation: Int, settings: FrameSettings) = runBlocking {
        val source = File(context.cacheDir, "test_source.mp4")
        val output = File(context.cacheDir, "test_output.mp4")
        TestVideos.create(source, FRAMES, rotation)

        VideoProcessor.render(context, Uri.fromFile(source), settings, output) { }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.absolutePath)
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
        val frames = (0 until count).map { i -> analyze(retriever.getFrameAtIndex(i)!!) }
        retriever.release()

        val report = frames.mapIndexed { i, f -> "frame $i: $f" }.joinToString("\n")
        Log.i(TAG, report)
        assertEquals("frame count\n$report", FRAMES, count)

        val steps = frames.zipWithNext { a, b -> hypot(b.markerX - a.markerX, b.markerY - a.markerY) }
        val typicalStep = steps.sorted()[steps.size / 2]
        steps.forEachIndexed { i, step ->
            assertTrue(
                "step $i→${i + 1} is ${"%.1f".format(step)} px, expected ~${"%.1f".format(typicalStep)}\n$report",
                abs(step - typicalStep) < typicalStep * 0.35,
            )
        }
        val first = frames.first()
        frames.forEachIndexed { i, f ->
            assertTrue(
                "picture area of frame $i ${f.content} differs from frame 0 ${first.content}\n$report",
                abs(f.content.left - first.content.left) <= 3 && abs(f.content.top - first.content.top) <= 3 &&
                    abs(f.content.right - first.content.right) <= 3 && abs(f.content.bottom - first.content.bottom) <= 3,
            )
        }
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private data class FrameInfo(val markerX: Double, val markerY: Double, val markerPixels: Int, val content: Box) {
        override fun toString() =
            "marker=(${"%.1f".format(markerX)}, ${"%.1f".format(markerY)}) size=$markerPixels picture=$content"
    }

    /** Finds the white square (centroid) and the grey picture area (bounding box). */
    private fun analyze(frame: Bitmap): FrameInfo {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        var sumX = 0L
        var sumY = 0L
        var n = 0
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val luma = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (luma > 200) {
                    sumX += x
                    sumY += y
                    n++
                }
                if (luma > 30) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return FrameInfo(sumX / n.toDouble(), sumY / n.toDouble(), n, Box(left, top, right, bottom))
    }

    private companion object {
        const val TAG = "VideoProcessorTest"
        const val FRAMES = 60
    }
}
