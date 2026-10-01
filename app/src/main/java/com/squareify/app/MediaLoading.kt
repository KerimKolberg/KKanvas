package com.squareify.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.squareify.app.processing.CollageRenderer
import com.squareify.app.processing.PanoramaRenderer
import com.squareify.app.processing.PhotoProcessor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photos are decoded at most this large before saving; 200 MP originals won't fit in memory. */
const val MAX_DECODE_DIMENSION = 6000
/** Source kept in memory per item ([MediaItem.preview]). */
const val PREVIEW_SIZE = 720
const val THUMBNAIL_SIZE = 600
const val LIVE_PREVIEW_SIZE = 900
const val FULLSCREEN_SIZE = 1440
/** A panorama is loaded this large for its editor and full-screen preview. */
const val PANORAMA_EDITOR_SIZE = 2400

fun queryDisplayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

/** The photo, or a frame from the middle of the video, with the longer side at most [maxSize]. */
fun loadSourceImage(context: Context, uri: Uri, isVideo: Boolean, maxSize: Int): Bitmap? =
    if (isVideo) loadVideoFrame(context, uri, maxSize) else PhotoProcessor.loadDownscaledBitmap(context, uri, maxSize)

private fun loadVideoFrame(context: Context, uri: Uri, maxSize: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        // Frame times are in microseconds: half the duration in ms * 1000.
        retriever.getScaledFrameAtTime(
            (500L * durationMs).coerceAtLeast(0L),
            MediaMetadataRetriever.OPTION_CLOSEST,
            maxSize,
            maxSize,
        )
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * The gallery (MediaStore) entry behind a picked or shared item, which is what can be moved to the
 * trash; null when there is none (e.g. a photo that only exists in the cloud).
 */
fun mediaStoreUri(context: Context, uri: Uri, isVideo: Boolean): Uri? {
    if (uri.authority != MediaStore.AUTHORITY) {
        // From the documents picker: there's an official conversion.
        return try {
            MediaStore.getMediaUri(context, uri)
        } catch (e: Exception) {
            null
        }
    }
    val segments = uri.pathSegments
    if (segments.firstOrNull()?.startsWith("picker") != true) {
        // Already a gallery entry, e.g. shared from the gallery app.
        return uri
    }
    // Photo picker links end in the gallery id for media stored on the phone:
    // content://media/picker/0/com.android.providers.media.photopicker/media/<id>
    if (PHOTO_PICKER_LOCAL !in segments) return null
    val id = uri.lastPathSegment?.toLongOrNull() ?: return null
    val collection = if (isVideo) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
    return ContentUris.withAppendedId(collection, id)
}

private const val PHOTO_PICKER_LOCAL = "com.android.providers.media.photopicker"

/** A collage drawn from its cells' in-memory previews, longer side at most [maxSide]. */
fun renderCollagePreview(collage: Collage, settings: FrameSettings, maxSide: Int): Bitmap {
    val (w, h) = collageSize(settings.format)
    val scale = min(1f, maxSide.toFloat() / max(w, h))
    return CollageRenderer.render(
        collage,
        collage.cells.map { it.preview },
        settings,
        (w * scale).roundToInt(),
        (h * scale).roundToInt(),
    )
}

/**
 * A collage from the original photos (clips show their middle frame), longer side at most
 * [maxSide]. Each photo is loaded only as large as its cell needs (with room to zoom), which keeps
 * nine photos within memory.
 */
fun renderCollage(context: Context, collage: Collage, settings: FrameSettings, maxSide: Int = Int.MAX_VALUE): Bitmap {
    val (fullW, fullH) = collageSize(settings.format)
    val scale = min(1f, maxSide.toFloat() / max(fullW, fullH))
    val w = (fullW * scale).roundToInt()
    val h = (fullH * scale).roundToInt()
    val rects = CollageRenderer.cellRects(collage, settings, w, h)
    val sources = collage.cells.mapIndexed { i, cell ->
        val rect = rects.getOrNull(i) ?: return@mapIndexed null
        val needed = (max(rect.width(), rect.height()) * cell.zoom * 2).roundToInt().coerceIn(512, 4000)
        loadSourceImage(context, cell.sourceUri, cell.isVideo, needed)
    }
    return CollageRenderer.render(collage, sources, settings, w, h)
}

/** A panorama's whole strip, [height] px high (as wide as the slides make it). */
fun renderPanoramaPreview(source: Bitmap, panorama: Panorama, settings: FrameSettings, height: Int): Bitmap {
    val (slideW, slideH) = slideSize(settings.format)
    val width = (height.toFloat() * slideW * panorama.slides / slideH).roundToInt()
    return PanoramaRenderer.renderStrip(source, panorama, settings, width, height)
}

/** A panorama's strip, [width] px wide, with thin lines where one slide ends and the next begins. */
fun renderPanoramaThumbnail(source: Bitmap, panorama: Panorama, settings: FrameSettings, width: Int): Bitmap {
    val (slideW, slideH) = slideSize(settings.format)
    val height = (width.toFloat() * slideH / (slideW * panorama.slides)).roundToInt().coerceAtLeast(1)
    val strip = PanoramaRenderer.renderStrip(source, panorama, settings, width, height)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 200
        strokeWidth = (width / 300f).coerceAtLeast(1.5f)
    }
    val canvas = android.graphics.Canvas(strip)
    for (i in 1 until panorama.slides) {
        val x = width.toFloat() * i / panorama.slides
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }
    return strip
}

/** Gallery file name of slide [index] (from 0), e.g. "carousel_IMG_1234_1". */
fun slideFileName(displayName: String, index: Int): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "carousel_${baseName}_${index + 1}"
}

/** Card-sized preview of what will be saved. */
fun renderThumbnail(source: Bitmap, settings: FrameSettings): Bitmap =
    PhotoProcessor.frameFitting(source, settings, THUMBNAIL_SIZE)

/** Gallery file name (without extension), e.g. "story_IMG_1234". */
fun outputFileName(format: FrameFormat, displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "${format.filePrefix}_$baseName"
}
