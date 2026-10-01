package com.squareify.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the grid between launches: every item with its edits, collages, panoramas and carousels,
 * as JSON in the app's private files. Pictures aren't stored; they're rebuilt from the originals.
 */
object ProjectStore {
    private const val TAG = "ProjectStore"
    private const val FILE = "projects.json"
    private const val VERSION = 1

    fun load(context: Context): List<MediaItem> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val items = JSONObject(file.readText()).optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                try {
                    mediaItemFromJson(items.getJSONObject(i))
                } catch (e: Exception) {
                    // One unreadable item shouldn't cost all the others.
                    Log.w(TAG, "skipping saved item $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read saved projects", e)
            emptyList()
        }
    }

    fun save(context: Context, items: List<MediaItem>) {
        val json = JSONObject().apply {
            put("version", VERSION)
            put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        }
        // Written beside the old file and swapped in, so a crash mid-write can't lose everything.
        val file = File(context.filesDir, FILE)
        val temp = File(context.filesDir, "$FILE.tmp")
        try {
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not save projects", e)
        }
    }
}
