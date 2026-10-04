package com.squareify.app.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import com.squareify.app.Texture
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Textures laid over a whole picture, made in code. Everything is placed relative to the area
 * with fixed random seeds, so a preview and the saved file look the same at any size.
 */
object Textures {
    private const val TILE = 256

    /** Draws [texture] over [area], which may reach beyond the canvas (one slide of a strip). */
    fun draw(canvas: Canvas, texture: Texture, area: Rect) {
        when (texture) {
            Texture.NONE -> Unit
            Texture.PAPER -> paper(canvas, area)
            Texture.DUST -> dust(canvas, area)
            Texture.LIGHT_LEAK -> lightLeak(canvas, area)
        }
    }

    /** A warm, matte tint with a fine fibrous grain. */
    private fun paper(canvas: Canvas, area: Rect) {
        val tint = paint(rgb(246, 238, 222))
        tint.blendMode = BlendMode.Multiply
        canvas.drawRect(area, tint)

        // A tile covers a quarter of the shorter side, so the grain looks the same at any size.
        val scale = min(area.width, area.height) / 4f / TILE
        val grain = Paint()
        grain.shader = imageShader(paperGrain, TileMode.Repeated, scale, scale, area.left, area.top)
        grain.alpha = 90 / 255f
        grain.blendMode = BlendMode.Overlay
        canvas.drawRect(area, grain)
    }

    private val paperGrain: ImageBitmap by lazy {
        val random = Random(3)
        val pixels = IntArray(TILE * TILE) {
            val v = 128 + (random.nextFloat() - 0.5f) * 70
            rgb(v.toInt(), v.toInt(), v.toInt())
        }
        // Short horizontal fibres: smear some rows a little.
        repeat(400) {
            val y = random.nextInt(TILE)
            val x0 = random.nextInt(TILE)
            val length = 6 + random.nextInt(18)
            val shade = if (random.nextBoolean()) 165 else 95
            for (dx in 0 until length) pixels[y * TILE + (x0 + dx) % TILE] = rgb(shade, shade, shade)
        }
        imageFromPixels(pixels, TILE, TILE)
    }

    /** Specks and the odd hair, like an old print. */
    private fun dust(canvas: Canvas, area: Rect) {
        val random = Random(11)
        val short = min(area.width, area.height)
        // The same density on a wide strip as on one slide.
        val count = (140 * max(1f, area.width / area.height)).toInt()
        val paint = Paint()
        repeat(count) {
            val x = area.left + random.nextFloat() * area.width
            val y = area.top + random.nextFloat() * area.height
            val r = short * (0.0008f + random.nextFloat() * 0.0025f)
            paint.color = Color(if (random.nextFloat() < 0.7f) argb(120 + random.nextInt(100), 255, 255, 250) else argb(90, 20, 15, 10))
            canvas.drawCircle(Offset(x, y), r, paint)
        }
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = short * 0.0012f
        paint.strokeCap = StrokeCap.Round
        repeat((6 * max(1f, area.width / area.height)).toInt()) {
            val x = area.left + random.nextFloat() * area.width
            val y = area.top + random.nextFloat() * area.height
            val length = short * (0.04f + random.nextFloat() * 0.06f)
            val bend = (random.nextFloat() - 0.5f) * length
            paint.color = Color(argb(110, 255, 255, 250))
            canvas.drawPath(Path().apply { moveTo(x, y); quadraticTo(x + length / 2, y + bend, x + length, y + bend / 3) }, paint)
        }
    }

    /** Warm light bleeding in from the left edge and the top right, as on a film camera. */
    private fun lightLeak(canvas: Canvas, area: Rect) {
        val short = min(area.width, area.height)
        val paint = Paint()
        paint.blendMode = BlendMode.Screen
        paint.shader = RadialGradientShader(
            Offset(area.left, area.top + area.height * 0.4f),
            short * 0.75f,
            listOf(Color(argb(190, 255, 110, 40)), Color(argb(90, 255, 70, 60)), Color.Transparent),
            listOf(0f, 0.45f, 1f),
            TileMode.Clamp,
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradientShader(
            Offset(area.right, area.top),
            short * 0.55f,
            listOf(Color(argb(150, 255, 200, 90)), Color.Transparent),
            null,
            TileMode.Clamp,
        )
        canvas.drawRect(area, paint)
    }
}
