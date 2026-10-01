package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
    private var paperTile: Bitmap? = null

    /** Draws [texture] over [area], which may reach beyond the canvas (one slide of a strip). */
    fun draw(canvas: Canvas, texture: Texture, area: RectF) {
        when (texture) {
            Texture.NONE -> Unit
            Texture.PAPER -> paper(canvas, area)
            Texture.DUST -> dust(canvas, area)
            Texture.LIGHT_LEAK -> lightLeak(canvas, area)
        }
    }

    /** A warm, matte tint with a fine fibrous grain. */
    private fun paper(canvas: Canvas, area: RectF) {
        val tint = Paint()
        tint.color = Color.rgb(246, 238, 222)
        tint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        canvas.drawRect(area, tint)

        val grain = Paint(Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(paperGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // A tile covers a quarter of the shorter side, so the grain looks the same at any size.
        val scale = min(area.width(), area.height()) / 4f / TILE
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(area.left, area.top) })
        grain.shader = shader
        grain.alpha = 90
        grain.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        canvas.drawRect(area, grain)
    }

    @Synchronized
    private fun paperGrain(): Bitmap = paperTile ?: run {
        val random = Random(3)
        val pixels = IntArray(TILE * TILE) {
            val v = 128 + (random.nextFloat() - 0.5f) * 70
            Color.rgb(v.toInt(), v.toInt(), v.toInt())
        }
        // Short horizontal fibres: smear some rows a little.
        repeat(400) {
            val y = random.nextInt(TILE)
            val x0 = random.nextInt(TILE)
            val length = 6 + random.nextInt(18)
            val shade = if (random.nextBoolean()) 165 else 95
            for (dx in 0 until length) pixels[y * TILE + (x0 + dx) % TILE] = Color.rgb(shade, shade, shade)
        }
        Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).also { paperTile = it }
    }

    /** Specks and the odd hair, like an old print. */
    private fun dust(canvas: Canvas, area: RectF) {
        val random = Random(11)
        val short = min(area.width(), area.height())
        // The same density on a wide strip as on one slide.
        val count = (140 * max(1f, area.width() / area.height())).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(count) {
            val x = area.left + random.nextFloat() * area.width()
            val y = area.top + random.nextFloat() * area.height()
            val r = short * (0.0008f + random.nextFloat() * 0.0025f)
            paint.color = if (random.nextFloat() < 0.7f) Color.argb(120 + random.nextInt(100), 255, 255, 250) else Color.argb(90, 20, 15, 10)
            canvas.drawCircle(x, y, r, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = short * 0.0012f
        paint.strokeCap = Paint.Cap.ROUND
        repeat((6 * max(1f, area.width() / area.height())).toInt()) {
            val x = area.left + random.nextFloat() * area.width()
            val y = area.top + random.nextFloat() * area.height()
            val length = short * (0.04f + random.nextFloat() * 0.06f)
            val bend = (random.nextFloat() - 0.5f) * length
            paint.color = Color.argb(110, 255, 255, 250)
            canvas.drawPath(Path().apply { moveTo(x, y); quadTo(x + length / 2, y + bend, x + length, y + bend / 3) }, paint)
        }
    }

    /** Warm light bleeding in from the left edge and the top right, as on a film camera. */
    private fun lightLeak(canvas: Canvas, area: RectF) {
        val short = min(area.width(), area.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        paint.shader = RadialGradient(
            area.left, area.top + area.height() * 0.4f, short * 0.75f,
            intArrayOf(Color.argb(190, 255, 110, 40), Color.argb(90, 255, 70, 60), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradient(
            area.right, area.top, short * 0.55f,
            intArrayOf(Color.argb(150, 255, 200, 90), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area, paint)
    }
}
