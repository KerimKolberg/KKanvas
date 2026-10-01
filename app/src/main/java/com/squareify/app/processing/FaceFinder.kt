package com.squareify.app.processing

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.FaceDetector
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Finds where the faces are in a photo, with Android's built-in face detector. */
object FaceFinder {
    private const val TAG = "FaceFinder"
    private const val MAX_FACES = 8
    /** Detection runs on a copy this large: quick, and plenty for faces worth keeping in view. */
    private const val WORK_SIZE = 480
    private const val MIN_CONFIDENCE = 0.3f

    /**
     * The middle of the area the faces in [bitmap] take up, as fractions (0–1) of its width and
     * height; null if it finds no faces.
     */
    fun focus(bitmap: Bitmap): PointF? {
        try {
            val scale = min(1f, WORK_SIZE.toFloat() / max(bitmap.width, bitmap.height))
            // The detector wants RGB 565 and an even width.
            val w = ((bitmap.width * scale).toInt() and 1.inv()).coerceAtLeast(2)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val small = scaled.copy(Bitmap.Config.RGB_565, false)
            if (scaled !== bitmap) scaled.recycle()
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(w, h, MAX_FACES).findFaces(small, faces)
            small.recycle()

            val area = RectF()
            val mid = PointF()
            for (i in 0 until count) {
                val face = faces[i] ?: continue
                if (face.confidence() < MIN_CONFIDENCE) continue
                face.getMidPoint(mid)
                val eyes = face.eyesDistance()
                // The whole head around the eyes: a little wider than tall, mostly below them.
                val head = RectF(mid.x - 1.3f * eyes, mid.y - 1.2f * eyes, mid.x + 1.3f * eyes, mid.y + 1.8f * eyes)
                if (area.isEmpty) area.set(head) else area.union(head)
            }
            if (area.isEmpty) return null
            return PointF((area.centerX() / w).coerceIn(0f, 1f), (area.centerY() / h).coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.w(TAG, "face detection failed", e)
            return null
        }
    }
}
