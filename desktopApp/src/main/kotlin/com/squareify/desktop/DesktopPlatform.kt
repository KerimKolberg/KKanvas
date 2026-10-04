package com.squareify.desktop

import com.squareify.app.AppPlatform
import com.squareify.app.DesktopContext
import com.squareify.app.MediaItem
import com.squareify.app.MediaUri
import com.squareify.app.OpenedMedia
import com.squareify.app.PHOTO_EXTENSIONS
import com.squareify.app.PlatformBitmap
import com.squareify.app.PlatformContext
import com.squareify.app.RenderState
import com.squareify.app.VIDEO_EXTENSIONS
import com.squareify.app.logWarning
import com.squareify.app.outputFileName
import com.squareify.app.toFile
import com.squareify.app.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Windows' side of [AppPlatform]: files instead of the gallery, FFmpeg for video, the Recycle Bin. */
class DesktopPlatform(override val context: PlatformContext = DesktopContext) : AppPlatform {
    override val io = Dispatchers.IO

    /** Messages for the window to show (a snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override fun message(text: String, long: Boolean) {
        messages.tryEmit(text)
    }

    override fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

    override fun now(): Long = System.currentTimeMillis()

    override fun open(uri: MediaUri): OpenedMedia {
        val file = uri.toFile()
        require(file.isFile) { "${file.name} isn't there" }
        val extension = file.extension.lowercase()
        require(extension in PHOTO_EXTENSIONS || extension in VIDEO_EXTENSIONS) { "${file.name} isn't a photo or video" }
        return OpenedMedia(isVideo = extension in VIDEO_EXTENSIONS, displayName = file.name)
    }

    override fun loadPreview(uri: MediaUri, isVideo: Boolean, maxSize: Int): PlatformBitmap? {
        val file = uri.toFile()
        if (!isVideo) return DesktopImages.decode(file, maxSize)
        // As on the phone: a frame from the middle of the clip.
        return try {
            val probe = Ffmpeg.probe(file)
            DesktopImages.decode(Ffmpeg.frameAt(file, probe.durationMs / 2, maxSize), maxSize, file.name)
        } catch (e: Exception) {
            logWarning("Squareify", "no frame from ${file.name}", e)
            null
        }
    }

    override fun loadPhoto(uri: MediaUri, maxSize: Int): PlatformBitmap = DesktopImages.decode(uri.toFile(), maxSize)

    /** Windows' own face detector, for smart crop in collages. */
    override fun findFaces(photo: PlatformBitmap): Pair<Float, Float>? = WindowsFaces.focus(photo)

    override fun videoDurationMs(uri: MediaUri): Long? = try {
        Ffmpeg.probe(uri.toFile()).durationMs
    } catch (e: Exception) {
        null
    }

    override fun saveImage(bitmap: PlatformBitmap, name: String, replace: MediaUri?): MediaUri? =
        saveFile(WindowsFolders.pictures, "$name.jpg", replace) { it.writeBytes(DesktopImages.jpeg(bitmap)) }

    override fun deleteOwn(uri: MediaUri) {
        val file = uri.toFile()
        if (file.parentFile?.name == WindowsFolders.FOLDER) file.delete()
    }

    /**
     * Writes [fileName] into [folder], overwriting [replace] (an earlier save of the same item) if
     * it's still there and renaming it if the name changed; otherwise a new file, with " (1)" etc.
     * added if the name is taken, as the phone's gallery does.
     */
    private fun saveFile(folder: File, fileName: String, replace: MediaUri?, write: (File) -> Unit): MediaUri {
        folder.mkdirs()
        val earlier = replace?.let { runCatching { it.toFile() }.getOrNull() }?.takeIf { it.isFile }
        val target = when {
            earlier == null -> freeName(folder, fileName)
            earlier.name.equals(fileName, ignoreCase = true) -> earlier
            else -> freeName(folder, fileName)
        }
        // Written beside it and swapped in, so a half-written file never shows.
        val temp = File(folder, "${target.name}.part")
        write(temp)
        if (earlier != null && earlier != target) earlier.delete()
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Couldn't save ${target.name}" }
        }
        return target.toMediaUri()
    }

    private fun freeName(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        var file = File(folder, fileName)
        var n = 1
        while (file.exists()) file = File(folder, "$base ($n).$extension").also { n++ }
        return file
    }

    private val states = MutableStateFlow<Map<String, RenderState>>(emptyMap())
    override val renderStates: StateFlow<Map<String, RenderState>> = states.asStateFlow()

    override fun clearRenderState(id: String) {
        states.update { it - id }
    }

    /** Videos render one after another in the background, like the phone's render service. */
    private val queue = Channel<MediaItem>(Channel.UNLIMITED)
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val savedVideos = mutableMapOf<String, MediaUri>()

    init {
        renderScope.launch {
            for (item in queue) renderOne(item)
        }
    }

    override fun renderVideo(item: MediaItem) {
        states.update { it + (item.id to RenderState(isProcessing = true)) }
        queue.trySend(item)
    }

    private fun renderOne(item: MediaItem) {
        states.update { it + (item.id to (it[item.id] ?: RenderState()).copy(isProcessing = true, progress = 0f, error = null)) }
        val temp = File.createTempFile("kk-render-", ".mp4")
        try {
            require(Ffmpeg.available) { "FFmpeg is missing, so videos can't be made" }
            val onProgress = { p: Float -> states.update { it + (item.id to (it[item.id] ?: RenderState()).copy(progress = p)) } }
            val collage = item.collage
            val soundDropped = if (collage != null) {
                DesktopVideo.renderCollage(collage, item.settings, temp, onProgress)
            } else {
                DesktopVideo.render(item.sourceUri.toFile(), item.settings, temp, onProgress)
            }
            // Collages are already named, e.g. "collage_20261001_120000".
            val name = if (collage != null) item.displayName else outputFileName(item.settings.format, item.displayName)
            val saved = saveFile(WindowsFolders.videos, "$name.mp4", item.outputUri ?: savedVideos[item.id]) { temp.copyTo(it, overwrite = true) }
            savedVideos[item.id] = saved
            states.update {
                it + (item.id to RenderState(isProcessing = false, progress = 1f, isRendered = true, outputUri = saved, warning = if (soundDropped) "No sound" else null))
            }
        } catch (e: Exception) {
            logWarning("Squareify", "render failed for ${item.displayName}", e)
            states.update { it + (item.id to RenderState(isProcessing = false, error = e.message ?: e.toString())) }
        } finally {
            temp.delete()
        }
    }

    override val trash = RecycleBin(context)
}
