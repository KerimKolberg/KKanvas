package com.squareify.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

/** Decodes the photo at [uri] (software, mutable), its longer side at most [maxDimension]. */
fun PhotoProcessor.loadDownscaledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.isMutableRequired = true
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val scale = maxDimension.toFloat() / max(w, h)
        if (scale < 1f) {
            decoder.setTargetSize(
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}
