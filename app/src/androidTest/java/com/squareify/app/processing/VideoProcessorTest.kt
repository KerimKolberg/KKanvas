package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareify.app.Border
import com.squareify.app.FrameFormat
import com.squareify.app.FrameSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun checkSmoothRender(rotation: Int, settings: FrameSettings) = runBlocking {
        val source = File(context.cacheDir, "test_source.mp4")
        val output = File(context.cacheDir, "test_output.mp4")
        createTestVideo(source, rotation)

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

    /** 1920x1080 AVC; with a 90° rotation hint it looks like a portrait phone recording. */
    private fun createTestVideo(file: File, rotation: Int) {
        val width = 1920
        val height = 1080
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        val info = MediaCodec.BufferInfo()
        var track = -1
        var frame = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (frame == FRAMES) {
                        encoder.queueInputBuffer(index, 0, 0, frame * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        canvas.drawColor(Color.rgb(64, 64, 64))
                        val x = 100f + frame * STEP
                        canvas.drawRect(x, 480f, x + 120f, 600f, paint)
                        YuvImageWriter.writeBitmapToImage(bitmap, encoder.getInputImage(index)!!)
                        encoder.queueInputBuffer(index, 0, width * height * 3 / 2, frame * FRAME_US, 0)
                        frame++
                    }
                }
            }
            val out = encoder.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.outputFormat)
                muxer.start()
            } else if (out >= 0) {
                val data = encoder.getOutputBuffer(out)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    muxer.writeSampleData(track, data, info)
                }
                encoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }

    private companion object {
        const val TAG = "VideoProcessorTest"
        const val FRAMES = 60
        const val STEP = 20f
        const val FRAME_US = 33_333L
    }
}
