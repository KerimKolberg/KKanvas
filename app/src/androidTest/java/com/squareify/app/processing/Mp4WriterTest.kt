package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** What goes into the encoder comes out of a player in the same colours. */
@RunWith(AndroidJUnit4::class)
class Mp4WriterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun colorsComeOutAsDrawn() {
        // Near-black and near-white show range mistakes; the saturated ones show matrix mistakes.
        val colors = listOf(
            Color.rgb(8, 8, 8), Color.rgb(40, 40, 40), Color.rgb(128, 128, 128), Color.rgb(245, 245, 245),
            Color.rgb(220, 40, 30), Color.rgb(40, 180, 60), Color.rgb(30, 60, 220), Color.rgb(240, 200, 40),
        )
        val width = 640
        val height = 320
        val patch = width / 4
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = Paint()
        colors.forEachIndexed { i, color ->
            paint.color = color
            val x = (i % 4) * patch.toFloat()
            val y = (i / 4) * (height / 2f)
            canvas.drawRect(x, y, x + patch, y + height / 2f, paint)
        }

        val file = File(context.cacheDir, "colors.mp4")
        val writer = Mp4Writer(file, width, height, 30, null)
        try {
            repeat(15) { writer.encode(frame, it * 33_333L) }
            writer.finishVideo()
            writer.close()
        } finally {
            writer.release()
        }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val decoded = retriever.getFrameAtIndex(10)!!
        retriever.release()

        val report = StringBuilder()
        var worst = 0
        colors.forEachIndexed { i, color ->
            val x = (i % 4) * patch + patch / 2
            val y = (i / 4) * (height / 2) + height / 4
            val got = decoded.getPixel(x, y)
            val error = maxOf(
                abs(Color.red(got) - Color.red(color)),
                abs(Color.green(got) - Color.green(color)),
                abs(Color.blue(got) - Color.blue(color)),
            )
            worst = maxOf(worst, error)
            report.append("drawn ${hex(color)} decoded ${hex(got)} error $error\n")
        }
        Log.i("Mp4WriterTest", report.toString())
        assertTrue("colours changed by up to $worst:\n$report", worst <= 10)
    }

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
}
