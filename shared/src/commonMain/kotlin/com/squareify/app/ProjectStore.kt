package com.squareify.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keeps the grid between launches: every item with its edits, collages, panoramas and carousels,
 * as JSON in the app's private files. Pictures aren't stored; they're rebuilt from the originals.
 */
object ProjectStore {
    private const val TAG = "ProjectStore"
    private const val FILE = "projects.json"
    private const val VERSION = 1

    fun load(context: PlatformContext): List<MediaItem> =
        try {
            context.readDataFile(FILE)?.let(::decode) ?: emptyList()
        } catch (e: Exception) {
            logWarning(TAG, "could not read saved projects", e)
            emptyList()
        }

    fun save(context: PlatformContext, items: List<MediaItem>) {
        try {
            context.writeDataFile(FILE, encode(items))
        } catch (e: Exception) {
            logWarning(TAG, "could not save projects", e)
        }
    }

    fun encode(items: List<MediaItem>): String = buildJsonObject {
        put("version", VERSION)
        put("items", JsonArray(items.map { it.toJson() }))
    }.toString()

    /** The items in a saved file; throws if the file as a whole can't be read. */
    fun decode(json: String): List<MediaItem> {
        val items = parseJsonObject(json)["items"] as? JsonArray ?: return emptyList()
        return items.mapIndexedNotNull { i, item ->
            try {
                mediaItemFromJson(item as JsonObject)
            } catch (e: Exception) {
                // One unreadable item shouldn't cost all the others.
                logWarning(TAG, "skipping saved item $i", e)
                null
            }
        }
    }
}
