package com.squareify.app

import android.app.Application
import android.app.PendingIntent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squareify.app.processing.ColorExtractor
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The grid and everything done to it. Lives in a ViewModel so it survives rotation and theme changes. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    /** Settings for newly added media, remembered across launches. */
    var globalSettings by mutableStateOf(SettingsStore.load(application))
        private set

    /** True while picked or shared media are being loaded. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Originals this app moved to the phone's trash, newest first ("Recently deleted"). */
    var trash by mutableStateOf(TrashStore.load(application).sortedByDescending { it.trashedAt })
        private set

    /** Saved items whose original can still be moved to the trash. */
    val trashableItems: List<MediaItem>
        get() = items.filter {
            it.isRendered && !it.isProcessing && !it.originalTrashed &&
                mediaStoreUri(context, it.sourceUri, it.isVideo) != null
        }

    /** A trash / restore / delete that Android is asking the user to confirm. */
    private var pendingRequest: PendingRequest? = null

    private sealed interface PendingRequest {
        data class Trash(val itemIds: List<String>, val uris: List<Uri>) : PendingRequest
        data class Restore(val entries: List<TrashedOriginal>) : PendingRequest
        data class DeleteForever(val entries: List<TrashedOriginal>) : PendingRequest
    }

    private var saveSettingsJob: Job? = null

    /** One full-resolution photo at a time: keeps memory in check and orders saves of the same item. */
    private val photoSaveMutex = Mutex()

    init {
        // Mirror progress from RenderService into the grid; drop finished entries from the holder.
        viewModelScope.launch {
            RenderStateHolder.states.collect { states ->
                for ((id, state) in states) {
                    if (items.none { it.id == id }) continue
                    updateItem(id) {
                        it.copy(
                            isProcessing = state.isProcessing,
                            progress = state.progress,
                            isRendered = it.isRendered || state.isRendered,
                            outputUri = state.outputUri ?: it.outputUri,
                            error = state.error,
                            warning = state.warning,
                        )
                    }
                    if (state.isRendered || state.error != null) {
                        RenderStateHolder.clear(id)
                    }
                }
            }
        }
    }

    fun updateGlobalSettings(settings: FrameSettings) {
        globalSettings = settings
        saveSettingsJob?.cancel()
        saveSettingsJob = viewModelScope.launch {
            delay(300) // Sliders change this continuously while dragged; save once they settle.
            SettingsStore.save(context, settings)
        }
    }

    /** Picked or shared media: build previews, then save the photos right away. */
    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        val settings = globalSettings
        viewModelScope.launch {
            val newItems = withContext(Dispatchers.IO) { uris.mapNotNull { loadItem(it, settings) } }
            items = items + newItems
            isProcessing = false
            val failed = uris.size - newItems.size
            if (failed > 0) {
                Toast.makeText(context, "Couldn't open $failed of ${uris.size} files", Toast.LENGTH_LONG).show()
            }
            // Photos are saved right away; videos wait for the user to tap render.
            newItems.filter { !it.isVideo }.forEach { autoSavePhoto(it.id) }
        }
    }

    private fun loadItem(uri: Uri, settings: FrameSettings): MediaItem? =
        try {
            val type = context.contentResolver.getType(uri)
            val isVideo = type != null && type.startsWith("video/")
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "media"
            val preview = loadSourceImage(context, uri, isVideo, PREVIEW_SIZE)
            MediaItem(
                sourceUri = uri,
                isVideo = isVideo,
                displayName = name,
                settings = settings,
                preview = preview,
                photoColors = preview?.let { ColorExtractor.dominantColors(it) }.orEmpty(),
                thumbnail = preview?.let { renderThumbnail(it, settings) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open $uri", e)
            null
        }

    fun applyEdit(id: String, settings: FrameSettings) {
        val item = items.firstOrNull { it.id == id } ?: return
        // The saved file no longer matches; outputUri stays so the next save overwrites it.
        updateItem(id) { it.copy(settings = settings, isRendered = false, warning = null) }
        viewModelScope.launch {
            val preview = item.preview
            if (preview != null) {
                val thumbnail = withContext(Dispatchers.Default) { renderThumbnail(preview, settings) }
                updateItem(id) { it.copy(thumbnail = thumbnail) }
            }
            if (!item.isVideo) autoSavePhoto(id)
        }
    }

    fun renderVideo(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        updateItem(id) { it.copy(isProcessing = true, progress = 0f, error = null, warning = null) }
        RenderService.enqueue(context, item)
    }

    fun renderAllVideos() {
        items.filter { it.isVideo && !it.isRendered && !it.isProcessing }.forEach { renderVideo(it.id) }
    }

    fun retry(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.isVideo) renderVideo(id) else viewModelScope.launch { autoSavePhoto(id) }
    }

    fun remove(id: String) {
        items = items.filter { it.id != id }
    }

    /** Clears finished items from the list; the files themselves stay in the gallery. */
    fun removeSaved() {
        items = items.filterNot { it.isRendered && !it.isProcessing }
    }

    fun removeAll() {
        items = emptyList()
    }

    /**
     * Asks Android to move the originals of all saved items to the trash (deleted for good after
     * 30 days). Returns the confirmation to show, or null if there's nothing to do.
     */
    fun requestTrashOriginals(): IntentSender? {
        val targets = trashableItems
        if (targets.isEmpty()) return null
        val uris = targets.map { mediaStoreUri(context, it.sourceUri, it.isVideo)!! }
        return systemRequest(PendingRequest.Trash(targets.map { it.id }, uris)) {
            MediaStore.createTrashRequest(context.contentResolver, uris, true)
        }
    }

    fun requestRestore(entries: List<TrashedOriginal>): IntentSender? =
        systemRequest(PendingRequest.Restore(entries)) {
            MediaStore.createTrashRequest(context.contentResolver, entries.map { it.uri }, false)
        }

    fun requestDeleteForever(entries: List<TrashedOriginal>): IntentSender? =
        systemRequest(PendingRequest.DeleteForever(entries)) {
            MediaStore.createDeleteRequest(context.contentResolver, entries.map { it.uri })
        }

    private fun systemRequest(request: PendingRequest, create: () -> PendingIntent): IntentSender? =
        try {
            val intent = create()
            pendingRequest = request
            intent.intentSender
        } catch (e: Exception) {
            Log.w(TAG, "Android refused $request", e)
            Toast.makeText(context, "That didn't work: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            null
        }

    /** The user answered Android's confirmation for the last request. */
    fun onSystemRequestResult(approved: Boolean) {
        val request = pendingRequest ?: return
        pendingRequest = null
        if (!approved) return
        when (request) {
            is PendingRequest.Trash -> {
                val trashed = request.itemIds.zip(request.uris)
                    .mapNotNull { (id, uri) -> items.firstOrNull { it.id == id }?.let { it to uri } }
                items = items.map { if (it.id in request.itemIds) it.copy(originalTrashed = true) else it }
                viewModelScope.launch {
                    val now = System.currentTimeMillis()
                    val entries = withContext(Dispatchers.IO) {
                        trashed.map { (item, uri) ->
                            TrashedOriginal(uri, item.displayName, item.isVideo, now, TrashStore.saveThumbnail(context, uri, item.preview))
                        }
                    }
                    storeTrash(entries + trash)
                    val n = entries.size
                    Toast.makeText(context, "Moved $n original${if (n == 1) "" else "s"} to the trash", Toast.LENGTH_SHORT).show()
                }
            }
            is PendingRequest.Restore -> {
                val restored = request.entries.map { it.uri }.toSet()
                items = items.map {
                    if (it.originalTrashed && mediaStoreUri(context, it.sourceUri, it.isVideo) in restored) {
                        it.copy(originalTrashed = false)
                    } else {
                        it
                    }
                }
                removeFromTrash(request.entries)
            }
            is PendingRequest.DeleteForever -> removeFromTrash(request.entries)
        }
    }

    private fun removeFromTrash(entries: List<TrashedOriginal>) {
        entries.forEach { TrashStore.deleteThumbnail(it) }
        val gone = entries.map { it.uri }.toSet()
        storeTrash(trash.filter { it.uri !in gone })
    }

    private fun storeTrash(entries: List<TrashedOriginal>) {
        trash = entries
        TrashStore.save(context, entries)
    }

    /** Renders the photo in its format and saves it to Pictures/Squareify. */
    private suspend fun autoSavePhoto(id: String) {
        updateItem(id) { it.copy(isProcessing = true, error = null) }
        photoSaveMutex.withLock {
            // Read the item only now, so an edit made while waiting is what gets saved.
            val item = items.firstOrNull { it.id == id } ?: return
            try {
                val savedUri = withContext(Dispatchers.Default) {
                    val source = PhotoProcessor.loadDownscaledBitmap(context, item.sourceUri, MAX_DECODE_DIMENSION)
                    val framed = PhotoProcessor.frame(source, item.settings)
                    val uri = GallerySaver.saveImage(
                        context,
                        framed,
                        outputFileName(item.settings.format, item.displayName),
                        replace = item.outputUri,
                    )
                    framed.recycle()
                    uri
                }
                updateItem(id) {
                    // A newer edit arrived meanwhile: it's still waiting for its own save.
                    val current = it.settings == item.settings
                    it.copy(outputUri = savedUri, isProcessing = !current, isRendered = current)
                }
            } catch (e: Exception) {
                Log.e(TAG, "autoSavePhoto failed for ${item.displayName}", e)
                updateItem(id) { it.copy(isProcessing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun updateItem(id: String, transform: (MediaItem) -> MediaItem) {
        items = items.map { if (it.id == id) transform(it) else it }
    }

    private companion object {
        const val TAG = "Squareify"
    }
}
