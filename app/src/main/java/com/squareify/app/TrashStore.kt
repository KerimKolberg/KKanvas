package com.squareify.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** An original this app moved to the phone's trash after its result was saved. */
data class TrashedOriginal(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val trashedAt: Long,
    /** Small JPEG kept by the app; the trashed file itself can't be read any more. */
    val thumbnailPath: String?,
) {
    val expiresAt: Long get() = trashedAt + TrashStore.RETENTION_MS
}

/**
 * The app's "Recently deleted" list. Android deletes trashed media for good after 30 days, so
 * entries are dropped (with their thumbnails) after the same time.
 */
object TrashStore {
    val RETENTION_MS = TimeUnit.DAYS.toMillis(30)
    private const val PREFS_NAME = "trash"
    private const val KEY_ENTRIES = "entries"

    fun load(context: Context): List<TrashedOriginal> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        val all = try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TrashedOriginal(
                    uri = Uri.parse(o.getString("uri")),
                    name = o.getString("name"),
                    isVideo = o.getBoolean("isVideo"),
                    trashedAt = o.getLong("trashedAt"),
                    thumbnailPath = o.optString("thumbnail").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val (expired, current) = all.partition { it.expiresAt <= now }
        if (expired.isNotEmpty()) {
            expired.forEach { deleteThumbnail(it) }
            save(context, current)
        }
        return current
    }

    fun save(context: Context, entries: List<TrashedOriginal>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("uri", e.uri.toString())
                    .put("name", e.name)
                    .put("isVideo", e.isVideo)
                    .put("trashedAt", e.trashedAt)
                    .put("thumbnail", e.thumbnailPath ?: "")
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ENTRIES, array.toString())
        }
    }

    /** Keeps a small copy of [preview] to show in "Recently deleted". */
    fun saveThumbnail(context: Context, uri: Uri, preview: Bitmap?): String? {
        if (preview == null) return null
        return try {
            val dir = File(context.filesDir, "trash").apply { mkdirs() }
            val file = File(dir, "${uri.lastPathSegment ?: System.nanoTime()}.jpg")
            val scale = 256f / maxOf(preview.width, preview.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(preview, (preview.width * scale).toInt(), (preview.height * scale).toInt(), true)
            } else {
                preview
            }
            file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun deleteThumbnail(entry: TrashedOriginal) {
        entry.thumbnailPath?.let { File(it).delete() }
    }
}
