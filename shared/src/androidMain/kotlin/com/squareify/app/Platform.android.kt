package com.squareify.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID

actual typealias MediaUri = Uri

actual fun mediaUri(value: String): MediaUri = Uri.parse(value)

actual val emptyMediaUri: MediaUri get() = Uri.EMPTY

actual typealias PlatformBitmap = android.graphics.Bitmap

actual typealias JavaSerializable = java.io.Serializable

actual fun randomId(): String = UUID.randomUUID().toString()

actual typealias PlatformContext = Context

private class SharedPreferencesStore(private val prefs: SharedPreferences) : Preferences {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun edit(changes: Preferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : Preferences.Editor {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putFloat(key: String, value: Float) { editor.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
        }.changes()
        editor.apply()
    }
}

actual fun PlatformContext.preferences(name: String): Preferences =
    SharedPreferencesStore(getSharedPreferences(name, Context.MODE_PRIVATE))

actual fun PlatformContext.readDataFile(name: String): String? =
    File(filesDir, name).takeIf { it.exists() }?.readText()

actual fun PlatformContext.writeDataFile(name: String, text: String) {
    // Written beside the old file and swapped in.
    val file = File(filesDir, name)
    val temp = File(filesDir, "$name.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
    }
}

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    Log.w(tag, message, error)
}

