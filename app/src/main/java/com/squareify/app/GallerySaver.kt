package com.squareify.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/** Writes finished photos to Pictures/Squareify and videos to Movies/Squareify. */
object GallerySaver {
    private const val TAG = "GallerySaver"
    private const val FOLDER = "Squareify"

    fun saveImage(context: Context, bitmap: Bitmap, displayName: String, replace: Uri?): Uri? =
        save(
            context,
            replace,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "$displayName.jpg",
            "image/jpeg",
            Environment.DIRECTORY_PICTURES,
        ) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }

    /** Copies [file] into the gallery, then deletes it. */
    fun saveVideo(context: Context, file: File, displayName: String, replace: Uri?): Uri? {
        val uri = save(
            context,
            replace,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "$displayName.mp4",
            "video/mp4",
            Environment.DIRECTORY_MOVIES,
        ) { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        return uri
    }

    /**
     * Overwrites [replace] (an earlier save of the same item) if it still exists, so saving again
     * after an edit doesn't pile up copies; otherwise adds a new gallery entry.
     */
    private fun save(
        context: Context,
        replace: Uri?,
        collection: Uri,
        fileName: String,
        mimeType: String,
        directory: String,
        write: (OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        if (replace != null && exists(resolver, replace)) {
            try {
                val out = resolver.openOutputStream(replace, "wt") ?: throw FileNotFoundException("$replace")
                out.use(write)
                // The format, and with it the file name prefix, may have changed.
                rename(resolver, replace, fileName)
                return replace
            } catch (e: Exception) {
                Log.w(TAG, "could not overwrite $replace, saving a new copy", e)
            }
        }

        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
        // Hidden from the gallery until it's completely written.
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use(write)
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // Don't leave a half-written entry behind.
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** False if the file was deleted or moved to the trash since we saved it. */
    private fun exists(resolver: ContentResolver, uri: Uri): Boolean =
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), null, null, null)
                ?.use { c -> c.moveToFirst() && c.getInt(0) == 0 } ?: false
        } catch (e: Exception) {
            false
        }

    private fun rename(resolver: ContentResolver, uri: Uri, fileName: String) {
        try {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "could not rename $uri to $fileName", e)
        }
    }
}
