package com.squareify.app

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app needs from the phone or from Windows: reading and saving media, rendering
 * videos, the trash. Everything else ([AppModel], the screens, the drawing) is shared.
 */
interface AppPlatform {
    /** Where settings, looks and saved projects are kept. */
    val context: PlatformContext

    /** For work that waits on files: reading photos, saving. */
    val io: CoroutineDispatcher

    /** A short message for the user. */
    fun message(text: String, long: Boolean = true)

    /** Now, as "20260904_101500", for naming collages and carousels. */
    fun timestamp(): String

    /** Now, in ms since 1970. */
    fun now(): Long

    /** What a picked or shared file is; keeps access to it. Throws if it can't be opened. */
    fun open(uri: MediaUri): OpenedMedia

    /** The photo, or a frame from the middle of a video, its longer side at most [maxSize]. */
    fun loadPreview(uri: MediaUri, isVideo: Boolean, maxSize: Int): PlatformBitmap?

    /** A photo decoded for drawing on (mutable), its longer side at most [maxSize]. Throws if unreadable. */
    fun loadPhoto(uri: MediaUri, maxSize: Int): PlatformBitmap

    /** Where the faces in [photo] are, as fractions (0–1) of its width and height; null if none. */
    fun findFaces(photo: PlatformBitmap): Pair<Float, Float>?

    /** A video's length in ms, or null if it can't be read. */
    fun videoDurationMs(uri: MediaUri): Long?

    /** Saves a finished picture as a JPEG named [name], replacing the file [replace] if there is one. */
    fun saveImage(bitmap: PlatformBitmap, name: String, replace: MediaUri?): MediaUri?

    /** Deletes a file this app saved earlier (e.g. a slide left over from a longer version). */
    fun deleteOwn(uri: MediaUri)

    /** Starts rendering a video (or video collage) in the background; progress comes in [renderStates]. */
    fun renderVideo(item: MediaItem)

    /** Videos being rendered, by [MediaItem.id]. */
    val renderStates: StateFlow<Map<String, RenderState>>

    /** Forgets a finished render once the grid has taken it over. */
    fun clearRenderState(id: String)

    /** Moving originals to the trash and back; null where the app doesn't offer it. */
    val trash: OriginalsTrash?
}

/** A picked or shared file. */
data class OpenedMedia(val isVideo: Boolean, val displayName: String)

/** A video's render in the background. */
data class RenderState(
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val isRendered: Boolean = false,
    val outputUri: MediaUri? = null,
    val error: String? = null,
    val warning: String? = null,
)

/** An original this app moved to the trash after its result was saved. */
data class TrashedOriginal(
    val uri: MediaUri,
    val name: String,
    val isVideo: Boolean,
    val trashedAt: Long,
    /** A small picture kept by the app; the trashed file itself can't be read any more. */
    val thumbnailPath: String?,
) {
    val expiresAt: Long get() = trashedAt + RETENTION_MS

    companion object {
        /** Android deletes trashed media for good after 30 days. */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** The trash for originals, and the app's "Recently deleted" list. */
interface OriginalsTrash {
    /** What moving to the trash means here, for the question asked before it. */
    val moveExplanation: String

    /** What the "Recently deleted" list holds. */
    val listExplanation: String

    /** Days until the trash empties itself; null if it doesn't (the Windows Recycle Bin). */
    val keepsForDays: Int?

    /** The file to move to the trash for [item]'s original, or null if it can't be. */
    fun trashableUri(item: MediaItem): MediaUri?

    /** Moves [uris] to the trash once the user agrees (the phone asks itself); true if done. */
    suspend fun moveToTrash(uris: List<MediaUri>): Boolean

    /** Puts trashed files back; true if done. */
    suspend fun restore(uris: List<MediaUri>): Boolean

    /** Deletes trashed files for good; true if done. */
    suspend fun deleteForever(uris: List<MediaUri>): Boolean

    fun loadEntries(): List<TrashedOriginal>
    fun saveEntries(entries: List<TrashedOriginal>)

    /** Keeps a small copy of [preview] for the list; returns where. */
    fun saveThumbnail(uri: MediaUri, preview: PlatformBitmap?): String?
    fun deleteThumbnail(entry: TrashedOriginal)
    fun loadThumbnail(path: String): PlatformBitmap?
}

/** The platform, for screens that load pictures or use the trash themselves. */
val LocalAppPlatform = staticCompositionLocalOf<AppPlatform> { error("no AppPlatform provided") }
