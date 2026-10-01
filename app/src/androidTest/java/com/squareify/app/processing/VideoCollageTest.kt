package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareify.app.CellFit
import com.squareify.app.Collage
import com.squareify.app.CollageCell
import com.squareify.app.CollageLayout
import com.squareify.app.FrameSettings
import com.squareify.app.ShortClips
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Two synthetic clips side by side in a square collage: a 2 s one on the left and a 1 s one with
 * sound on the right, both fitted (whole clip visible). The moving squares show which frame of
 * each clip every output frame uses.
 */
@RunWith(AndroidJUnit4::class)
class VideoCollageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = FrameSettings(bgColor = Color.BLACK)

    @Test
    fun shorterClipFreezesOnItsLastFrame() = runBlocking {
        val (frames, output) = render(ShortClips.FREEZE, soundCell = 1)
        val report = report(frames)
        assertTrue("frame count ${frames.size}\n$report", frames.size in 59..61)

        frames.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("left clip stalled at frame ${i + 1}\n$report", b.left!! - a.left!! > 3)
            when {
                i + 1 < SHORT_FRAMES -> assertTrue("right clip stalled at frame ${i + 1}\n$report", b.right!! - a.right!! > 3)
                i + 1 > SHORT_FRAMES -> assertEquals("right clip moved at frame ${i + 1}\n$report", a.right!!, b.right!!, 1.0)
            }
        }
        // The sound plays once, as long as it is in the clip.
        val shortSound = TestVideos.trackDurationUs(shortClip, "audio/")!!
        assertEquals("sound length", shortSound.toDouble(), TestVideos.trackDurationUs(output, "audio/")!!.toDouble(), 80_000.0)
    }

    @Test
    fun shorterClipLoops() = runBlocking {
        val (frames, output) = render(ShortClips.LOOP, soundCell = 1)
        val report = report(frames)
        assertTrue("frame count ${frames.size}\n$report", frames.size in 59..61)
        // Frame 30 starts the short clip over.
        assertEquals("restart\n$report", frames[0].right!!, frames[SHORT_FRAMES].right!!, 2.0)
        assertEquals("second pass\n$report", frames[15].right!!, frames[SHORT_FRAMES + 15].right!!, 2.0)
        assertTrue("left clip went back\n$report", frames[SHORT_FRAMES].left!! > frames[SHORT_FRAMES - 1].left!!)
        // The sound loops along, for the whole collage.
        val sound = TestVideos.trackDurationUs(output, "audio/")!!
        assertTrue("sound is $sound Âµs", abs(sound - 2_000_000) < 100_000)
    }

    @Test
    fun photoStaysAndMutedHasNoSound() = runBlocking {
        val photo = File(context.cacheDir, "collage_red.png")
        photo.outputStream().use {
            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val collage = Collage(
            layout = CollageLayout.SIDE_BY_SIDE,
            cells = listOf(
                CollageCell(Uri.fromFile(longClip), "long", null, isVideo = true, fit = CellFit.FIT),
                CollageCell(Uri.fromFile(photo), "red", null, fit = CellFit.FIT),
            ),
            spacing = 0f,
            soundCell = null,
        )
        val output = File(context.cacheDir, "collage_output_muted.mp4")
        val soundDropped = VideoCollageProcessor.render(context, collage, settings, output) { }

        assertFalse(soundDropped)
        assertNull("muted collage has a sound track", TestVideos.trackDurationUs(output, "audio/"))
        val frames = readFrames(output)
        frames.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("clip stalled at frame ${i + 1}", b.left!! - a.left!! > 3)
        }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.absolutePath)
        val last = retriever.getFrameAtIndex(frames.size - 1)!!
        retriever.release()
        val pixel = last.getPixel(810, 540)
        assertTrue("photo cell is ${Integer.toHexString(pixel)}", Color.red(pixel) > 200 && Color.green(pixel) < 60)
    }

    private val longClip: File by lazy {
        File(context.cacheDir, "collage_long.mp4").also {
            TestVideos.create(it, LONG_FRAMES, width = 1280, height = 720, step = 15f)
        }
    }

    private val shortClip: File by lazy {
        File(context.cacheDir, "collage_short_sound.mp4").also {
            TestVideos.create(it, SHORT_FRAMES, width = 1280, height = 720, step = 15f, withSound = true)
        }
    }

    private suspend fun render(shortClips: ShortClips, soundCell: Int?): Pair<List<Markers>, File> {
        val collage = Collage(
            layout = CollageLayout.SIDE_BY_SIDE,
            cells = listOf(
                CollageCell(Uri.fromFile(longClip), "long", null, isVideo = true, fit = CellFit.FIT),
                CollageCell(Uri.fromFile(shortClip), "short", null, isVideo = true, fit = CellFit.FIT),
            ),
            spacing = 0f,
            shortClips = shortClips,
            soundCell = soundCell,
        )
        val output = File(context.cacheDir, "collage_output_$shortClips.mp4")
        val soundDropped = VideoCollageProcessor.render(context, collage, settings, output) { }
        assertFalse("render reported dropped sound", soundDropped)
        return readFrames(output) to output
    }

    /** Where the white square is in each half of every frame (x of its centre), null if not found. */
    private data class Markers(val left: Double?, val right: Double?)

    private fun readFrames(file: File): List<Markers> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
        val frames = (0 until count).map { i -> markers(retriever.getFrameAtIndex(i)!!) }
        retriever.release()
        return frames
    }

    private fun markers(frame: Bitmap): Markers {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val sum = DoubleArray(2)
        val n = IntArray(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                // White only: the red photo has low green and blue.
                if (Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200) {
                    val half = if (x < w / 2) 0 else 1
                    sum[half] += x.toDouble()
                    n[half]++
                }
            }
        }
        return Markers(
            if (n[0] > 0) sum[0] / n[0] else null,
            if (n[1] > 0) sum[1] / n[1] else null,
        )
    }

    private fun report(frames: List<Markers>) =
        frames.mapIndexed { i, m -> "frame $i: left=${m.left?.let { "%.1f".format(it) }} right=${m.right?.let { "%.1f".format(it) }}" }
            .joinToString("\n")
            .also { Log.i(TAG, it) }

    private companion object {
        const val TAG = "VideoCollageTest"
        const val LONG_FRAMES = 60
        const val SHORT_FRAMES = 30
    }
}
