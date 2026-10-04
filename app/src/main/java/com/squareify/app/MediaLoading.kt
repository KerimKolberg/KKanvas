package com.squareify.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.processing.loadDownscaledBitmap

/** The file name a picked or shared item shows, or null if the app it came from doesn't say. */
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

/**
 * Keeps read access to a picked photo or video after the app restarts, so saved projects can be
 * edited again. Photo picker links allow this; some shared ones don't, which is fine.
 */
fun keepAccess(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
    }
}

/** Length of a video in ms, or null if it can't be read. */
fun videoDurationMs(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}
