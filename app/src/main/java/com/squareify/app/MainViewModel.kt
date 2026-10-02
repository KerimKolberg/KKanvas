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
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squareify.app.processing.ColorExtractor
import com.squareify.app.processing.CarouselRenderer
import com.squareify.app.processing.PanoramaRenderer
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Items picked by long-press for a batch action (trash originals, share, remove, collage). */
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The settings panel on the main screen: open when the app starts, folded while adding media. */
    var settingsExpanded by mutableStateOf(true)

    /** A new collage open in the editor, not yet created. */
    var collageDraft by mutableStateOf<Collage?>(null)
        private set

    /** Saved items (all, or only those in [ids]) whose original can still be moved to the trash. */
    fun trashableItems(ids: Set<String>? = null): List<MediaItem> =
        items.filter {
            // A collage's originals are the photos it was made from, which have their own items.
            (ids == null || it.id in ids) && it.collage == null && it.carousel == null &&
                it.isRendered && !it.isProcessing && !it.originalTrashed &&
                mediaStoreUri(context, it.sourceUri, it.isVideo) != null
        }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll() {
        selectedIds = items.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    fun removeSelected() {
        items = items.filter { it.id !in selectedIds }
        clearSelection()
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
        LooksStore.load(application)
        WatermarkImages.load(application)
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
        // Bring back the grid from last time, then keep it saved as it changes.
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { ProjectStore.load(context) }
            if (restored.isNotEmpty()) {
                // Media shared in while this loaded stay on top.
                items = items + restored.filter { r -> items.none { it.id == r.id } }
                restorePictures(restored.map { it.id })
            }
            snapshotFlow { items }.collectLatest { list ->
                delay(500) // Edits come in bursts (sliders, render progress); save once they settle.
                withContext(Dispatchers.IO) { ProjectStore.save(context, list) }
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
            keepAccess(context, uri)
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

    /**
     * Opens the collage editor with the selected items; a single selected photo opens the
     * panorama editor instead (split into carousel slides).
     */
    fun startCollageFromSelection() {
        val selected = items.filter { it.id in selectedIds }
        when {
            selected.any { it.collage != null || it.panorama != null || it.carousel != null } ->
                Toast.makeText(context, "Collages and slides can't go into another collage.", Toast.LENGTH_LONG).show()
            selected.size == 1 -> startPanorama(selected.single())
            else -> collageDraft = newCollage(selected.map { CollageCell(it.sourceUri, it.displayName, it.preview, isVideo = it.isVideo) })
        }
    }

    /**
     * Opens the collage editor with media picked just for it, or for a single photo the panorama
     * editor. They aren't added to the grid or saved on their own; only the result is.
     */
    fun startCollageFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        viewModelScope.launch {
            val cells = withContext(Dispatchers.IO) { uris.mapNotNull { loadCell(it) } }
            isProcessing = false
            if (cells.size < uris.size) {
                Toast.makeText(context, "Couldn't open ${uris.size - cells.size} of ${uris.size} files", Toast.LENGTH_LONG).show()
            }
            when {
                cells.size >= 2 -> collageDraft = newCollage(cells)
                cells.size == 1 -> {
                    val cell = cells.single()
                    if (cell.isVideo) {
                        Toast.makeText(context, PICK_HINT, Toast.LENGTH_LONG).show()
                    } else {
                        panoramaDraft = PanoramaDraft(cell.sourceUri, cell.displayName, cell.preview)
                    }
                }
            }
        }
    }

    fun dismissCollageDraft() {
        collageDraft = null
    }

    /** A new carousel open in the editor, not yet created. */
    var carouselDraft by mutableStateOf<Carousel?>(null)
        private set

    /** Opens the carousel editor with picked photos spread across a slide each (2 to 10 slides). */
    fun startCarouselFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        viewModelScope.launch {
            val size = carouselPreviewSize(uris.size)
            val photos = withContext(Dispatchers.IO) { uris.take(Carousel.MAX_PHOTOS).mapNotNull { loadCarouselPhoto(context, it, size) } }
            isProcessing = false
            if (photos.size < uris.size) {
                Toast.makeText(context, "Couldn't open ${uris.size - photos.size} of ${uris.size} photos", Toast.LENGTH_LONG).show()
            }
            if (photos.isEmpty()) return@launch
            val format = globalSettings.format.takeIf { it in Panorama.FORMATS } ?: FrameFormat.PORTRAIT
            // Many photos start in a grid, a few on a slide each; the editor offers the other templates.
            carouselDraft = applyTemplate(defaultTemplate(photos.size), Carousel(Carousel.MIN_SLIDES, photos), slideHeightUnits(format))
        }
    }

    fun dismissCarouselDraft() {
        carouselDraft = null
    }

    /** Adds the carousel at the top of the grid and saves its slides. */
    fun createCarousel(carousel: Carousel, settings: FrameSettings) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val item = MediaItem(
            sourceUri = carousel.photos.first().sourceUri,
            isVideo = false,
            displayName = "carousel_$stamp",
            settings = settings,
            preview = carousel.photos.first().preview,
            carousel = carousel,
        )
        items = listOf(item) + items
        carouselDraft = null
        refreshCarousel(item.id)
    }

    fun applyCarouselEdit(id: String, carousel: Carousel, settings: FrameSettings) {
        updateItem(id) { it.copy(carousel = carousel, settings = settings, isRendered = false, warning = null) }
        refreshCarousel(id)
    }

    private fun refreshCarousel(id: String) {
        viewModelScope.launch {
            val item = items.firstOrNull { it.id == id } ?: return@launch
            val carousel = item.carousel ?: return@launch
            val thumbnail = withContext(Dispatchers.Default) { renderCarouselThumbnail(carousel, item.settings, THUMBNAIL_SIZE) }
            updateItem(id) { it.copy(thumbnail = thumbnail) }
            autoSavePhoto(id)
        }
    }

    /** A photo about to be split into carousel slides, open in the panorama editor. */
    data class PanoramaDraft(val sourceUri: Uri, val displayName: String, val preview: android.graphics.Bitmap?)

    var panoramaDraft by mutableStateOf<PanoramaDraft?>(null)
        private set

    /** Opens the panorama editor for a photo in the grid. */
    fun startPanorama(item: MediaItem) {
        if (item.isVideo || item.collage != null || item.carousel != null) {
            Toast.makeText(context, PICK_HINT, Toast.LENGTH_LONG).show()
            return
        }
        panoramaDraft = PanoramaDraft(item.sourceUri, item.displayName, item.preview)
    }

    fun dismissPanoramaDraft() {
        panoramaDraft = null
    }

    /** Adds the slides as one item at the top of the grid and saves them. */
    fun createPanorama(draft: PanoramaDraft, panorama: Panorama, settings: FrameSettings) {
        val item = MediaItem(
            sourceUri = draft.sourceUri,
            isVideo = false,
            displayName = draft.displayName,
            settings = settings,
            preview = draft.preview,
            panorama = panorama,
        )
        items = listOf(item) + items
        clearSelection()
        panoramaDraft = null
        refreshPanorama(item.id)
    }

    fun applyPanoramaEdit(id: String, panorama: Panorama, settings: FrameSettings) {
        updateItem(id) { it.copy(panorama = panorama, settings = settings, isRendered = false, warning = null) }
        refreshPanorama(id)
    }

    private fun refreshPanorama(id: String) {
        viewModelScope.launch {
            val item = items.firstOrNull { it.id == id } ?: return@launch
            val preview = item.preview
            val panorama = item.panorama
            if (preview != null && panorama != null) {
                val thumbnail = withContext(Dispatchers.Default) {
                    renderPanoramaThumbnail(preview, panorama, item.settings, THUMBNAIL_SIZE)
                }
                updateItem(id) { it.copy(thumbnail = thumbnail) }
            }
            autoSavePhoto(id)
        }
    }

    private fun newCollage(cells: List<CollageCell>) =
        Collage(layout = CollageLayout.forCount(cells.size).first(), cells = cells)

    private fun loadCell(uri: Uri): CollageCell? =
        try {
            keepAccess(context, uri)
            val type = context.contentResolver.getType(uri)
            val isVideo = type != null && type.startsWith("video/")
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "media"
            CollageCell(uri, name, loadSourceImage(context, uri, isVideo, PREVIEW_SIZE), isVideo = isVideo)
        } catch (e: Exception) {
            Log.w(TAG, "could not open $uri", e)
            null
        }

    /** Adds a new collage at the top of the grid and saves it (a video collage starts rendering). */
    fun createCollage(collage: Collage, settings: FrameSettings) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val item = MediaItem(
            sourceUri = collage.cells.first().sourceUri,
            isVideo = collage.hasVideo,
            displayName = "collage_$stamp",
            settings = settings,
            collage = collage,
        )
        items = listOf(item) + items
        clearSelection()
        collageDraft = null
        refreshCollage(item.id, collage, settings)
    }

    fun applyCollageEdit(id: String, collage: Collage, settings: FrameSettings) {
        // As with photos: outputUri stays so the next save overwrites the earlier file.
        updateItem(id) { it.copy(collage = collage, settings = settings, isRendered = false, warning = null) }
        refreshCollage(id, collage, settings)
    }

    private fun refreshCollage(id: String, collage: Collage, settings: FrameSettings) {
        viewModelScope.launch {
            val thumbnail = withContext(Dispatchers.Default) { renderCollagePreview(collage, settings, THUMBNAIL_SIZE) }
            updateItem(id) { it.copy(thumbnail = thumbnail) }
            // With clips in it, the collage is a video and renders in the background service.
            if (collage.hasVideo) renderVideo(id) else autoSavePhoto(id)
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
    fun requestTrashOriginals(ids: Set<String>? = null): IntentSender? {
        val targets = trashableItems(ids)
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
                selectedIds = selectedIds - request.itemIds.toSet()
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
                val saved = withContext(Dispatchers.Default) {
                    val panorama = item.panorama
                    val carousel = item.carousel
                    when {
                        panorama != null -> savePanoramaSlides(item, panorama)
                        carousel != null -> saveCarouselSlides(item, carousel)
                        else -> listOfNotNull(savePhoto(item))
                    }
                }
                updateItem(id) {
                    // A newer edit arrived meanwhile: it's still waiting for its own save.
                    val current = it.settings == item.settings && it.collage == item.collage &&
                        it.panorama == item.panorama && it.carousel == item.carousel
                    it.copy(
                        outputUri = saved.firstOrNull(),
                        outputUris = if (item.panorama != null || item.carousel != null) saved else emptyList(),
                        isProcessing = !current,
                        isRendered = current,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "autoSavePhoto failed for ${item.displayName}", e)
                updateItem(id) { it.copy(isProcessing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Rebuilds restored items' pictures (previews, thumbnails, colours) from their originals, one
     * item at a time so the grid fills in without hogging the phone.
     */
    private suspend fun restorePictures(ids: List<String>) {
        var missing = 0
        for (id in ids) {
            val item = items.firstOrNull { it.id == id } ?: continue
            val sources = buildList {
                add(item.sourceUri to item.isVideo)
                item.collage?.cells?.forEach { add(it.sourceUri to it.isVideo) }
                item.carousel?.photos?.forEach { add(it.sourceUri to false) }
            }.distinct()
            val previews = withContext(Dispatchers.IO) {
                sources.associate { (uri, isVideo) ->
                    uri to try {
                        loadSourceImage(context, uri, isVideo, PREVIEW_SIZE)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val gone = previews.values.count { it == null }
            if (gone > 0) missing++
            updateItem(id) { current ->
                current.copy(
                    preview = current.preview ?: previews[current.sourceUri],
                    collage = current.collage?.let { c -> c.copy(cells = c.cells.map { it.copy(preview = it.preview ?: previews[it.sourceUri]) }) },
                    carousel = current.carousel?.let { c -> c.copy(photos = c.photos.map { it.copy(preview = it.preview ?: previews[it.sourceUri]) }) },
                    // Saved results can still be shared; only something not saved yet is stuck.
                    error = if (gone > 0 && !current.isRendered) "The original isn't available any more" else current.error,
                )
            }
            val updated = items.firstOrNull { it.id == id } ?: continue
            val pictures = withContext(Dispatchers.Default) {
                thumbnailFor(updated) to updated.preview?.takeIf { updated.collage == null && updated.carousel == null }
                    ?.let { ColorExtractor.dominantColors(it) }
            }
            updateItem(id) { it.copy(thumbnail = pictures.first ?: it.thumbnail, photoColors = pictures.second ?: it.photoColors) }
        }
        if (missing > 0) {
            Toast.makeText(context, "$missing item${if (missing == 1) "" else "s"} lost access to the original photos", Toast.LENGTH_LONG).show()
        }
    }

    /** The card picture for any kind of item, from the in-memory previews. */
    private fun thumbnailFor(item: MediaItem): android.graphics.Bitmap? {
        val collage = item.collage
        val carousel = item.carousel
        val panorama = item.panorama
        val preview = item.preview
        return when {
            collage != null -> renderCollagePreview(collage, item.settings, THUMBNAIL_SIZE)
            carousel != null -> renderCarouselThumbnail(carousel, item.settings, THUMBNAIL_SIZE)
            preview == null -> null
            panorama != null -> renderPanoramaThumbnail(preview, panorama, item.settings, THUMBNAIL_SIZE)
            else -> renderThumbnail(preview, item.settings)
        }
    }

    /** A photo or photo collage, saved as one file. */
    private fun savePhoto(item: MediaItem): Uri? {
        val collage = item.collage
        val framed = if (collage != null) {
            renderCollage(context, collage, item.settings)
        } else {
            val source = PhotoProcessor.loadDownscaledBitmap(context, item.sourceUri, MAX_DECODE_DIMENSION)
            PhotoProcessor.frame(source, item.settings)
        }
        val uri = GallerySaver.saveImage(
            context,
            framed,
            if (collage != null) item.displayName else outputFileName(item.settings.format, item.displayName),
            replace = item.outputUri,
        )
        framed.recycle()
        return uri
    }

    /**
     * Saves each slide of a panorama, overwriting the slides of the previous save; slides left
     * over from an earlier, longer version (this app's own files) are deleted.
     */
    private fun savePanoramaSlides(item: MediaItem, panorama: Panorama): List<Uri> {
        val (width, height) = slideSize(item.settings.format)
        // Enough pixels to cover the whole strip, a little over to spare (portrait sources).
        val needed = (maxOf(width * panorama.slides, height) * 1.25f).toInt().coerceAtMost(MAX_PANORAMA_DECODE)
        val source = PhotoProcessor.loadDownscaledBitmap(context, item.sourceUri, needed)
        val uris = (0 until panorama.slides).map { i ->
            val slide = PanoramaRenderer.renderSlide(source, panorama, item.settings, i, width, height)
            val uri = GallerySaver.saveImage(context, slide, slideFileName(item.displayName, i), replace = item.outputUris.getOrNull(i))
            slide.recycle()
            uri ?: throw IllegalStateException("Couldn't save slide ${i + 1}")
        }
        source.recycle()
        item.outputUris.drop(panorama.slides).forEach { GallerySaver.deleteOwn(context, it) }
        return uris
    }

    /**
     * Saves each slide of a carousel, named after the carousel ("carousel_<time>_1" …), overwriting
     * the previous save's slides; leftover slides of a longer earlier version are deleted.
     */
    private fun saveCarouselSlides(item: MediaItem, carousel: Carousel): List<Uri> {
        val (width, height) = slideSize(item.settings.format)
        // Each photo only as large as it appears on the slides (with room to spare), within memory.
        val sources = carousel.photos.mapIndexed { i, photo ->
            val rect = CarouselRenderer.photoRect(carousel, i, width.toFloat() * carousel.slides, height.toFloat())
            val needed = (maxOf(rect.width(), rect.height()) * 1.2f).toInt().coerceIn(256, 4000)
            try {
                PhotoProcessor.loadDownscaledBitmap(context, photo.sourceUri, needed)
            } catch (e: Exception) {
                Log.w(TAG, "carousel photo ${photo.displayName} is gone", e)
                null
            }
        }
        val uris = (0 until carousel.slides).map { i ->
            val slide = CarouselRenderer.renderSlide(carousel, sources, item.settings, i, width, height)
            val uri = GallerySaver.saveImage(context, slide, "${item.displayName}_${i + 1}", replace = item.outputUris.getOrNull(i))
            slide.recycle()
            uri ?: throw IllegalStateException("Couldn't save slide ${i + 1}")
        }
        sources.forEach { it?.recycle() }
        item.outputUris.drop(carousel.slides).forEach { GallerySaver.deleteOwn(context, it) }
        return uris
    }

    private fun updateItem(id: String, transform: (MediaItem) -> MediaItem) {
        items = items.map { if (it.id == id) transform(it) else it }
    }

    private companion object {
        const val TAG = "Squareify"
        const val PICK_HINT = "Pick one photo to split into carousel slides, or 2-9 photos and videos for a collage."
        /** Longest side a panorama is decoded at for saving its slides. */
        const val MAX_PANORAMA_DECODE = 12_000
    }
}
