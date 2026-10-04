package com.squareify.desktop

import com.squareify.app.PlatformContext
import com.squareify.app.MediaItem
import com.squareify.app.MediaUri
import com.squareify.app.OriginalsTrash
import com.squareify.app.PlatformBitmap
import com.squareify.app.TrashedOriginal
import com.squareify.app.mediaUri
import com.squareify.app.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The Windows Recycle Bin for originals. Windows keeps each recycled file as $R… with its old
 * path in a matching $I… file, in C:\$Recycle.Bin\<user's id>; restoring moves it back.
 */
class RecycleBin(private val context: PlatformContext) : OriginalsTrash {
    override val moveExplanation =
        "Their squared versions are saved, so the original photos and videos can go. " +
            "They move to the Recycle Bin. You can put them back under ⋮ → Recently deleted, " +
            "or from the Recycle Bin itself."

    override val listExplanation =
        "Originals moved to the Recycle Bin after their squared version was saved. " +
            "They stay there until the Recycle Bin is emptied."

    override val keepsForDays: Int? = null

    private val entriesFile get() = File(context.dataDir, "trash.json")
    private val thumbnails get() = File(context.dataDir, "trash")

    /** An original on this computer, not one of the app's own results. */
    override fun trashableUri(item: MediaItem): MediaUri? {
        val file = try {
            item.sourceUri.toFile()
        } catch (e: Exception) {
            return null
        }
        val own = listOf(WindowsFolders.pictures, WindowsFolders.videos).any { file.absolutePath.startsWith(it.absolutePath, ignoreCase = true) }
        return if (file.isFile && !own) item.sourceUri else null
    }

    override suspend fun moveToTrash(uris: List<MediaUri>): Boolean = withContext(Dispatchers.IO) {
        uris.map { it.toFile() }.filter { it.exists() }.all(::recycle)
    }

    override suspend fun restore(uris: List<MediaUri>): Boolean = withContext(Dispatchers.IO) {
        uris.all { uri ->
            val original = uri.toFile()
            val entry = recycled(original) ?: return@all original.exists()
            if (original.exists()) return@all false
            original.parentFile?.mkdirs()
            entry.data.renameTo(original).also { if (it) entry.info.delete() }
        }
    }

    override suspend fun deleteForever(uris: List<MediaUri>): Boolean = withContext(Dispatchers.IO) {
        uris.all { uri ->
            val entry = recycled(uri.toFile()) ?: return@all true
            entry.data.deleteRecursively().also { if (it) entry.info.delete() }
        }
    }

    /** Sends [file] to the Recycle Bin, as Explorer's Delete does. */
    private fun recycle(file: File): Boolean {
        val desktop = java.awt.Desktop.getDesktop()
        if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
            return desktop.moveToTrash(file)
        }
        val script = "Add-Type -AssemblyName Microsoft.VisualBasic; " +
            "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('${file.absolutePath.replace("'", "''")}', 'OnlyErrorDialogs', 'SendToRecycleBin')"
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start()
        return process.waitFor() == 0 && !file.exists()
    }

    /** A recycled file: its contents ($R…) and its record ($I…). */
    private class Recycled(val data: File, val info: File)

    /** Where the Recycle Bin keeps [original], if it's there. */
    private fun recycled(original: File): Recycled? {
        val bin = File(original.absoluteFile.toPath().root.toFile(), "\$Recycle.Bin\\$userSid")
        val infos = bin.listFiles { f -> f.name.startsWith("\$I") }.orEmpty()
        val info = infos.filter { originalPath(it).equals(original.absolutePath, ignoreCase = true) }.maxByOrNull { it.lastModified() } ?: return null
        val data = File(bin, "\$R" + info.name.substring(2))
        return if (data.exists()) Recycled(data, info) else null
    }

    /** The path stored in a $I file (Windows 10 and later: version 2). */
    private fun originalPath(info: File): String? = try {
        val buffer = ByteBuffer.wrap(info.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        when (buffer.getLong(0)) {
            2L -> {
                val chars = buffer.getInt(24)
                String(info.readBytes(), 28, (chars - 1) * 2, StandardCharsets.UTF_16LE)
            }
            1L -> String(info.readBytes(), 24, 520, StandardCharsets.UTF_16LE).substringBefore('\u0000')
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** The user's security id, which names their folder in the Recycle Bin. */
    private val userSid: String by lazy {
        val process = ProcessBuilder("whoami", "/user", "/fo", "csv", "/nh").start()
        val line = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        line.split(',').last().trim('"', ' ', '\r', '\n')
    }

    override fun loadEntries(): List<TrashedOriginal> = try {
        val array = Json.parseToJsonElement(entriesFile.readText()) as JsonArray
        array.map { element ->
            val o = element as JsonObject
            TrashedOriginal(
                uri = mediaUri(o.getValue("uri").jsonPrimitive.content),
                name = o.getValue("name").jsonPrimitive.content,
                isVideo = o.getValue("isVideo").jsonPrimitive.boolean,
                trashedAt = o.getValue("trashedAt").jsonPrimitive.long,
                thumbnailPath = (o["thumbnail"] as? JsonPrimitive)?.content,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun saveEntries(entries: List<TrashedOriginal>) {
        context.dataDir.mkdirs()
        val array = JsonArray(
            entries.map { e ->
                buildJsonObject {
                    put("uri", e.uri.toString())
                    put("name", e.name)
                    put("isVideo", e.isVideo)
                    put("trashedAt", e.trashedAt)
                    e.thumbnailPath?.let { put("thumbnail", it) }
                }
            },
        )
        entriesFile.writeText(array.toString())
    }

    override fun saveThumbnail(uri: MediaUri, preview: PlatformBitmap?): String? {
        if (preview == null) return null
        thumbnails.mkdirs()
        val file = File(thumbnails, "${uri.toString().hashCode().toUInt()}_${System.currentTimeMillis()}.png")
        return try {
            val small = DesktopImages.decode(DesktopImages.png(preview), 200)
            file.writeBytes(DesktopImages.png(small))
            small.close()
            file.path
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteThumbnail(entry: TrashedOriginal) {
        entry.thumbnailPath?.let { File(it).delete() }
    }

    override fun loadThumbnail(path: String): PlatformBitmap? = try {
        DesktopImages.decode(File(path), 200)
    } catch (e: Exception) {
        null
    }
}
