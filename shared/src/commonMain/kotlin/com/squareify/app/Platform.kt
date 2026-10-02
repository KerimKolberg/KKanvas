package com.squareify.app

/*
 * The few things the shared code needs from the platform it runs on. On the phone they are
 * Android's own classes, so the phone app uses them exactly as before; the Windows app has its own.
 */

/** Where a photo or video comes from: a content:// Uri on the phone, a file on Windows. */
expect abstract class MediaUri {
    /** The text it is saved as. */
    abstract override fun toString(): String
}

/** Reads a [MediaUri] back from the text it was saved as. */
expect fun mediaUri(value: String): MediaUri

/** Stands in for a missing address in an old or damaged saved file. */
expect val emptyMediaUri: MediaUri

/** A decoded picture (previews and thumbnails). */
expect class PlatformBitmap

/** java.io.Serializable, so settings can travel in an Android Intent. */
expect interface JavaSerializable

/** A new random id for an item. */
expect fun randomId(): String

/** What the stores need to find their files: Android's Context on the phone. */
expect abstract class PlatformContext

/** A small named set of saved values: SharedPreferences on the phone. */
interface Preferences {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean

    /** Changes several values at once; saved in the background. */
    fun edit(changes: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putInt(key: String, value: Int)
        fun putFloat(key: String, value: Float)
        fun putBoolean(key: String, value: Boolean)
    }
}

expect fun PlatformContext.preferences(name: String): Preferences

/** The text of one of the app's private files, or null if there's none yet. */
expect fun PlatformContext.readDataFile(name: String): String?

/** Replaces one of the app's private files, so that a crash mid-write can't leave half a file. */
expect fun PlatformContext.writeDataFile(name: String, text: String)

expect fun logWarning(tag: String, message: String, error: Throwable? = null)
