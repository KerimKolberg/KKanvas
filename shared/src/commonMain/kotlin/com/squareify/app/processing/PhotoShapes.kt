package com.squareify.app.processing

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import com.squareify.app.PhotoShape
import kotlin.math.min
import kotlin.random.Random

/** Outlines for [PhotoShape]s, shared by the renderers and the editors' live previews. */
object PhotoShapes {
    /** Points along each edge of a torn outline; fixed so it tears the same at any size. */
    private const val TEAR_POINTS = 36

    /** The outline of a photo drawn into [rect]; [cornerRadius] rounds the rectangle. */
    fun path(shape: PhotoShape, rect: Rect, cornerRadius: Float): Path = Path().apply {
        when (shape) {
            PhotoShape.RECTANGLE -> addRoundRect(RoundRect(rect, CornerRadius(cornerRadius)))
            PhotoShape.CIRCLE -> addOval(rect)
            PhotoShape.PILL -> {
                val r = min(rect.width, rect.height) / 2
                addRoundRect(RoundRect(rect, CornerRadius(r)))
            }
            PhotoShape.ARCH -> {
                // A round top as wide as the photo (as high as it allows), straight sides, flat bottom.
                val rise = min(rect.width / 2, rect.height)
                moveTo(rect.left, rect.bottom)
                lineTo(rect.left, rect.top + rise)
                arcTo(Rect(rect.left, rect.top, rect.right, rect.top + 2 * rise), 180f, 180f, false)
                lineTo(rect.right, rect.bottom)
                close()
            }
            PhotoShape.TORN -> tear(this, rect)
        }
    }

    /** A rough paper edge: each side wanders a little way inwards, the same way every time. */
    private fun tear(path: Path, rect: Rect) {
        val depth = min(rect.width, rect.height) * 0.025f
        val random = Random(7)
        fun jitter() = random.nextFloat() * depth
        path.moveTo(rect.left + jitter(), rect.top + jitter())
        for (i in 1..TEAR_POINTS) path.lineTo(rect.left + rect.width * i / TEAR_POINTS, rect.top + jitter())
        for (i in 1..TEAR_POINTS) path.lineTo(rect.right - jitter(), rect.top + rect.height * i / TEAR_POINTS)
        for (i in 1..TEAR_POINTS) path.lineTo(rect.right - rect.width * i / TEAR_POINTS, rect.bottom - jitter())
        for (i in 1 until TEAR_POINTS) path.lineTo(rect.left + jitter(), rect.bottom - rect.height * i / TEAR_POINTS)
        path.close()
    }
}
