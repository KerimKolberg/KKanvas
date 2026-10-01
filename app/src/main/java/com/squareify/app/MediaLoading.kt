package com.squareify.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.squareify.app.processing.PhotoProcessor

/** Photos are decoded at most this large before saving; 200 MP originals won't fit in memory. */
const val MAX_DECODE_DIMENSION = 6000
/** Source kept in memory per item ([MediaItem.preview]). */
const val PREVIEW_SIZE = 720
const val THUMBNAIL_SIZE = 600
const val LIVE_PREVIEW_SIZE = 900
const val FULLSCREEN_SIZE = 1440

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

/** Card-sized preview of what will be saved. */
fun renderThumbnail(source: Bitmap, settings: FrameSettings): Bitmap =
    PhotoProcessor.frameFitting(source, settings, THUMBNAIL_SIZE)

/** Gallery file name (without extension), e.g. "story_IMG_1234". */
fun outputFileName(format: FrameFormat, displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "${format.filePrefix}_$baseName"
}
