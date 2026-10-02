package com.squareify.app

import java.io.File
import java.util.Properties
import java.util.UUID

/** On Windows a photo or video is a file, kept as its file: URI. */
actual abstract class MediaUri {
    actual abstract override fun toString(): String
}

private data class FileUri(val value: String) : MediaUri() {
    override fun toString() = value
}

actual fun mediaUri(value: String): MediaUri = FileUri(value)

actual val emptyMediaUri: MediaUri = FileUri("")

actual typealias PlatformBitmap = org.jetbrains.skia.Bitmap

actual typealias JavaSerializable = java.io.Serializable

actual fun randomId(): String = UUID.randomUUID().toString()

/** Where the Windows app keeps its settings and saved projects. */
actual abstract class PlatformContext {
    abstract val dataDir: File
}

/** %APPDATA%\kk-Squareify. */
object DesktopContext : PlatformContext() {
    override val dataDir: File =
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "kk-Squareify")
}

/** Saved values in a .properties file, written in full on every change (they're small). */
private class PropertiesStore(private val file: File) : Preferences {
    private val values = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    override fun getString(key: String): String? = synchronized(values) { values.getProperty(key) }
    override fun getInt(key: String, default: Int): Int = getString(key)?.toIntOrNull() ?: default
    override fun getFloat(key: String, default: Float): Float = getString(key)?.toFloatOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default

    override fun edit(changes: Preferences.Editor.() -> Unit) {
        synchronized(values) {
            object : Preferences.Editor {
                override fun putString(key: String, value: String) { values.setProperty(key, value) }
                override fun putInt(key: String, value: Int) { values.setProperty(key, value.toString()) }
                override fun putFloat(key: String, value: Float) { values.setProperty(key, value.toString()) }
                override fun putBoolean(key: String, value: Boolean) { values.setProperty(key, value.toString()) }
            }.changes()
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, null) }
        }
    }
}

private val stores = mutableMapOf<String, Preferences>()

actual fun PlatformContext.preferences(name: String): Preferences = synchronized(stores) {
    stores.getOrPut(File(dataDir, "$name.properties").path) { PropertiesStore(File(dataDir, "$name.properties")) }
}

actual fun PlatformContext.readDataFile(name: String): String? =
    File(dataDir, name).takeIf { it.exists() }?.readText()

actual fun PlatformContext.writeDataFile(name: String, text: String) {
    dataDir.mkdirs()
    val file = File(dataDir, name)
    val temp = File(dataDir, "$name.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
    }
}

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    System.err.println("W/$tag: $message")
    error?.printStackTrace()
}
