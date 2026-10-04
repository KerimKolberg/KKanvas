package com.squareify.app.processing

import com.squareify.app.Carousel
import com.squareify.app.CarouselPhoto
import com.squareify.app.CarouselSticker
import com.squareify.app.FrameSettings
import com.squareify.app.Placement
import com.squareify.app.PlatformBitmap
import com.squareify.app.StickerKind
import com.squareify.app.Texture
import com.squareify.app.emptyMediaUri
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextureStickerTest {
    private fun solid(color: Int, width: Int = 400, height: Int = 400): PlatformBitmap =
        picture(color, width, height)

    private fun framed(color: Int, texture: Texture) =
        PhotoProcessor.frame(solid(color), FrameSettings(texture = texture), 400, 400)

    @Test
    fun paperWarmsAndTextures() {
        val out = framed(Color.WHITE, Texture.PAPER)
        val p = out.getPixel(200, 200)
        assertTrue("paper ${hex(p)}", Color.red(p) > Color.blue(p))
        // Grainy: not every pixel the same.
        assertTrue((0 until 400 step 7).map { out.getPixel(it, 100) }.toSet().size > 3)
    }

    @Test
    fun lightLeakGlowsFromTheLeft() {
        val out = framed(Color.rgb(60, 60, 60), Texture.LIGHT_LEAK)
        val edge = out.getPixel(2, 160)
        val middle = out.getPixel(300, 300)
        assertTrue("edge ${hex(edge)}", Color.red(edge) > 150 && Color.red(edge) > Color.blue(edge) + 50)
        assertTrue("middle ${hex(middle)}", Color.red(middle) < Color.red(edge))
    }

    @Test
    fun dustLooksTheSameEveryTime() {
        val a = framed(Color.rgb(90, 90, 90), Texture.DUST)
        val b = framed(Color.rgb(90, 90, 90), Texture.DUST)
        assertTrue((0 until 400).any { x -> (0 until 400 step 4).any { y -> a.getPixel(x, y) != Color.rgb(90, 90, 90) } })
        (0 until 400 step 13).forEach { x -> assertEquals(a.getPixel(x, x), b.getPixel(x, x)) }
    }

    @Test
    fun aTextureFlowsAcrossCarouselSlides() {
        val carousel = Carousel(2, listOf(CarouselPhoto(emptyMediaUri, "p", null, 2f, Placement(1f, 0.5f, 2f))))
        val settings = FrameSettings(texture = Texture.LIGHT_LEAK)
        val sources = listOf(solid(Color.rgb(60, 60, 60), 800, 400))
        val strip = CarouselRenderer.renderStrip(carousel, sources, settings, 800, 400)
        val second = CarouselRenderer.renderSlide(carousel, sources, settings, 1, 400, 400)
        // The leak glows on the first slide only; the second matches the strip's right half.
        listOf(10, 200, 390).forEach { x ->
            val s = strip.getPixel(400 + x, 160)
            val p = second.getPixel(x, 160)
            assertTrue(abs(Color.red(s) - Color.red(p)) <= 2 && abs(Color.blue(s) - Color.blue(p)) <= 2)
        }
    }

    @Test
    fun stickersLieOnTopOfPhotos() {
        val carousel = Carousel(
            2,
            listOf(CarouselPhoto(emptyMediaUri, "p", null, 1f, Placement(0.5f, 0.5f, 0.9f))),
            listOf(CarouselSticker(StickerKind.STAR, Color.YELLOW, Placement(0.5f, 0.5f, 0.4f))),
        )
        val slide = CarouselRenderer.renderSlide(carousel, listOf(solid(Color.BLUE)), FrameSettings(), 0, 400, 400)
        val middle = slide.getPixel(200, 205)
        assertTrue("star ${hex(middle)}", Color.red(middle) > 200 && Color.green(middle) > 200 && Color.blue(middle) < 60)
        // Outside the star, the photo.
        assertEquals(Color.BLUE, slide.getPixel(60, 60))
    }

    @Test
    fun everyStickerDrawsSomething() {
        StickerKind.entries.forEach { kind ->
            val bitmap = solid(Color.BLACK, 300, 300)
            val w = 200f
            val h = w / kind.aspect
            StickerRenderer.draw(androidx.compose.ui.graphics.Canvas(bitmap.asImage()), kind, Color.WHITE, androidx.compose.ui.geometry.Rect(50f, 150 - h / 2, 250f, 150 + h / 2))
            val lit = (0 until 300 step 3).sumOf { x -> (0 until 300 step 3).count { y -> Color.red(bitmap.getPixel(x, y)) > 150 } }
            assertTrue("$kind drew $lit bright samples", lit > 20)
        }
    }
}
