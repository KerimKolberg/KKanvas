package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareify.app.Adjustments
import com.squareify.app.BUILT_IN_LOOKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AdjustmentsTest {

    private fun solid(color: Int, size: Int = 40): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun adjusted(color: Int, adjustments: Adjustments): Int =
        PhotoProcessor.applyAdjustments(solid(color), adjustments).getPixel(20, 20)

    @Test
    fun originalChangesNothing() {
        val color = Color.rgb(120, 80, 200)
        assertEquals(color, adjusted(color, BUILT_IN_LOOKS.first { it.name == "Original" }.adjustments))
    }

    @Test
    fun blackAndWhiteHasNoColour() {
        val out = adjusted(Color.rgb(200, 40, 40), Adjustments(saturation = 0f))
        assertTrue(abs(Color.red(out) - Color.green(out)) <= 1 && abs(Color.green(out) - Color.blue(out)) <= 1)
    }

    @Test
    fun warmthShiftsTowardsOrange() {
        val grey = Color.rgb(128, 128, 128)
        val warm = adjusted(grey, Adjustments(warmth = 1f))
        val cool = adjusted(grey, Adjustments(warmth = -1f))
        assertTrue("warm ${Integer.toHexString(warm)}", Color.red(warm) > 128 && Color.blue(warm) < 128)
        assertTrue("cool ${Integer.toHexString(cool)}", Color.red(cool) < 128 && Color.blue(cool) > 128)
    }

    @Test
    fun contrastStretchesAroundMidGrey() {
        assertTrue(Color.red(adjusted(Color.rgb(60, 60, 60), Adjustments(contrast = 1.5f))) < 60)
        assertTrue(Color.red(adjusted(Color.rgb(200, 200, 200), Adjustments(contrast = 1.5f))) > 200)
        assertEquals(128, Color.red(adjusted(Color.rgb(128, 128, 128), Adjustments(contrast = 1.5f))), 1)
    }

    @Test
    fun fadeLiftsTheBlacks() {
        assertTrue(Color.red(adjusted(Color.BLACK, Adjustments(fade = 1f))) in 40..52)
        assertTrue(Color.red(adjusted(Color.WHITE, Adjustments(fade = 1f))) > 240)
    }

    @Test
    fun vignetteDarkensOnlyTheCorners() {
        val out = PhotoProcessor.applyAdjustments(solid(Color.WHITE, 200), Adjustments(vignette = 1f))
        assertEquals(Color.WHITE, out.getPixel(100, 100))
        assertTrue(Color.red(out.getPixel(1, 1)) < 120)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, got $actual", abs(expected - actual) <= tolerance)
}
