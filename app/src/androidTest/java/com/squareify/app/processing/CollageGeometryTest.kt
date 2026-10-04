package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Photos and a portrait phone clip land in a video collage exactly where their cells are. */
@RunWith(AndroidJUnit4::class)
class CollageGeometryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = FrameSettings(bgColor = Color.WHITE)

    @Test
    fun cellsLandWhereTheyShould() = runBlocking {
        val red = photo("geo_red.png", Color.RED, 300, 400)
        val green = photo("geo_green.png", Color.GREEN, 400, 300)
        val clip = File(context.cacheDir, "geo_clip.mp4")
        TestVideos.create(clip, 20, rotation = 90, width = 1280, height = 720, step = 0f)
        val collage = Collage(
            layout = CollageLayout.BIG_LEFT,
            cells = listOf(
                CollageCell(Uri.fromFile(red), "red", null, fit = CellFit.FIT),
                CollageCell(Uri.fromFile(green), "green", null, fit = CellFit.FIT),
                CollageCell(Uri.fromFile(clip), "clip", null, isVideo = true, fit = CellFit.FIT),
            ),
        )
        val output = File(context.cacheDir, "geo_output.mp4")
        VideoCollageProcessor.render(context, collage, settings, output) { }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(output.absolutePath)
        val frame = retriever.getFrameAtIndex(10)!!
        retriever.release()

        val cells = CollageRenderer.cellRects(collage, settings, frame.width, frame.height)
        val expected = listOf(fit(300, 400, cells[0]), fit(400, 300, cells[1]), fit(720, 1280, cells[2]))
        val found = listOf(
            bounds(frame) { Color.red(it) > 180 && Color.green(it) < 80 },
            bounds(frame) { Color.green(it) > 180 && Color.red(it) < 80 },
            // The clip is grey (64) with a white square; the background is white.
            bounds(frame) { Color.red(it) in 40..100 && abs(Color.red(it) - Color.green(it)) < 15 },
        )
        val report = expected.indices.joinToString("\n") { "cell $it: expected ${expected[it]} found ${found[it]}" }
        Log.i("CollageGeometryTest", "frame ${frame.width}x${frame.height}\n$report")
        expected.indices.forEach { i ->
            val e = expected[i]
            val f = found[i]
            assertTrue(
                "cell $i is off\n$report",
                abs(e.left - f.left) <= 4 && abs(e.right - f.right) <= 4 && abs(e.top - f.top) <= 4 && abs(e.bottom - f.bottom) <= 4,
            )
        }
    }

    private fun photo(name: String, color: Int, width: Int, height: Int): File =
        File(context.cacheDir, name).also { file ->
            file.outputStream().use {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

    /** Where a [width] x [height] picture goes when fitted into [cell], in whole pixels. */
    private fun fit(width: Int, height: Int, cell: androidx.compose.ui.geometry.Rect): Rect {
        val scale = min(cell.width / width, cell.height / height)
        val w = width * scale
        val h = height * scale
        val left = cell.left + (cell.width - w) / 2
        val top = cell.top + (cell.height - h) / 2
        return Rect(left.toInt(), top.toInt(), (left + w).toInt() - 1, (top + h).toInt() - 1)
    }

    private fun bounds(frame: Bitmap, match: (Int) -> Boolean): Rect {
        var left = frame.width
        var top = frame.height
        var right = -1
        var bottom = -1
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                if (match(frame.getPixel(x, y))) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return Rect(left, top, right, bottom)
    }
}
