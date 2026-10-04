package com.squareify.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.squareify.app.processing.CarouselRenderer
import com.squareify.app.processing.CollageRenderer
import com.squareify.app.processing.ColorExtractor
import com.squareify.app.processing.PanoramaRenderer
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.processing.asImage
import com.squareify.app.processing.initRenderers
import com.squareify.app.processing.recycle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The grid and everything done to it, the same on the phone and on Windows. On the phone it
 * lives in a ViewModel, so it survives rotation and theme changes.
 */
class AppModel(val platform: AppPlatform, private val scope: CoroutineScope) {
    private val context get() = platform.context

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    /** Settings for newly added media, remembered across launches. */
    var globalSettings by mutableStateOf(SettingsStore.load(platform.context))
        private set

    /** True while picked or shared media are being loaded. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Originals this app moved to the trash, newest first ("Recently deleted"). */
    var trash by mutableStateOf(platform.trash?.loadEntries().orEmpty().sortedByDescending { it.trashedAt })
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
    fun trashableItems(ids: Set<String>? = null): List<MediaItem> {
        val trash = platform.trash ?: return emptyList()
        return items.filter {
            // A collage's originals are the photos it was made from, which have their own items.
            (ids == null || it.id in ids) && it.collage == null && it.carousel == null &&
                it.isRendered && !it.isProcessing && !it.originalTrashed &&
                trash.trashableUri(it) != null
        }
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

    private var saveSettingsJob: Job? = null

    /** One full-resolution photo at a time: keeps memory in check and orders saves of the same item. */
    private val photoSaveMutex = Mutex()

    init {
        LooksStore.load(context)
        initRenderers(context)
        // Mirror progress from the video renderer into the grid; drop finished entries.
        scope.launch {
            platform.renderStates.collect { states ->
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
                        platform.clearRenderState(id)
                    }
                }
            }
        }
        // Bring back the grid from last time, then keep it saved as it changes.
        scope.launch {
            val restored = withContext(platform.io) { ProjectStore.load(context) }
            if (restored.isNotEmpty()) {
                // Media shared in while this loaded stay on top.
                items = items + restored.filter { r -> items.none { it.id == r.id } }
                restorePictures(restored.map { it.id })
            }
            snapshotFlow { items }.collectLatest { list ->
                delay(500) // Edits come in bursts (sliders, render progress); save once they settle.
                withContext(platform.io) { ProjectStore.save(context, list) }
            }
        }
    }

    fun updateGlobalSettings(settings: FrameSettings) {
        globalSettings = settings
        saveSettingsJob?.cancel()
        saveSettingsJob = scope.launch {
            delay(300) // Sliders change this continuously while dragged; save once they settle.
            SettingsStore.save(context, settings)
        }
    }

    /** Picked or shared media: build previews, then save the photos right away. */
    fun addMedia(uris: List<MediaUri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        val settings = globalSettings
        scope.launch {
            val newItems = withContext(platform.io) { uris.mapNotNull { loadItem(it, settings) } }
            items = items + newItems
            isProcessing = false
            val failed = uris.size - newItems.size
            if (failed > 0) {
                platform.message("Couldn't open $failed of ${uris.size} files")
            }
            // Photos are saved right away; videos wait for the user to tap render.
            newItems.filter { !it.isVideo }.forEach { autoSavePhoto(it.id) }
        }
    }

    private fun loadItem(uri: MediaUri, settings: FrameSettings): MediaItem? =
        try {
            val media = platform.open(uri)
            val preview = platform.loadPreview(uri, media.isVideo, PREVIEW_SIZE)
            MediaItem(
                sourceUri = uri,
                isVideo = media.isVideo,
                displayName = media.displayName,
                settings = settings,
                preview = preview,
                photoColors = preview?.let { ColorExtractor.dominantColors(it) }.orEmpty(),
                thumbnail = preview?.let { renderThumbnail(it, settings) },
            )
        } catch (e: Exception) {
            logWarning(TAG, "could not open $uri", e)
            null
        }

    fun applyEdit(id: String, settings: FrameSettings) {
        val item = items.firstOrNull { it.id == id } ?: return
        // The saved file no longer matches; outputUri stays so the next save overwrites it.
        updateItem(id) { it.copy(settings = settings, isRendered = false, warning = null) }
        scope.launch {
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
                platform.message("Collages and slides can't go into another collage.")
            selected.size == 1 -> startPanorama(selected.single())
            else -> collageDraft = newCollage(selected.map { CollageCell(it.sourceUri, it.displayName, it.preview, isVideo = it.isVideo) })
        }
    }

    /**
     * Opens the collage editor with media picked just for it, or for a single photo the panorama
     * editor. They aren't added to the grid or saved on their own; only the result is.
     */
    fun startCollageFromPicker(uris: List<MediaUri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        scope.launch {
            val cells = withContext(platform.io) { uris.mapNotNull { loadCell(it) } }
            isProcessing = false
            if (cells.size < uris.size) {
                platform.message("Couldn't open ${uris.size - cells.size} of ${uris.size} files")
            }
            when {
                cells.size >= 2 -> collageDraft = newCollage(cells)
                cells.size == 1 -> {
                    val cell = cells.single()
                    if (cell.isVideo) {
                        platform.message(PICK_HINT)
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
    fun startCarouselFromPicker(uris: List<MediaUri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        scope.launch {
            val size = carouselPreviewSize(uris.size)
            val photos = withContext(platform.io) { uris.take(Carousel.MAX_PHOTOS).mapNotNull { platform.loadCarouselPhoto(it, size) } }
            isProcessing = false
            if (photos.size < uris.size) {
                platform.message("Couldn't open ${uris.size - photos.size} of ${uris.size} photos")
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
        val item = MediaItem(
            sourceUri = carousel.photos.first().sourceUri,
            isVideo = false,
            displayName = "carousel_${platform.timestamp()}",
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
        scope.launch {
            val item = items.firstOrNull { it.id == id } ?: return@launch
            val carousel = item.carousel ?: return@launch
            val thumbnail = withContext(Dispatchers.Default) { renderCarouselThumbnail(carousel, item.settings, THUMBNAIL_SIZE) }
            updateItem(id) { it.copy(thumbnail = thumbnail) }
            autoSavePhoto(id)
        }
    }

    /** A photo about to be split into carousel slides, open in the panorama editor. */
    data class PanoramaDraft(val sourceUri: MediaUri, val displayName: String, val preview: PlatformBitmap?)

    var panoramaDraft by mutableStateOf<PanoramaDraft?>(null)
        private set

    /** Opens the panorama editor for a photo in the grid. */
    fun startPanorama(item: MediaItem) {
        if (item.isVideo || item.collage != null || item.carousel != null) {
            platform.message(PICK_HINT)
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
        scope.launch {
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

    private fun loadCell(uri: MediaUri): CollageCell? =
        try {
            val media = platform.open(uri)
            CollageCell(uri, media.displayName, platform.loadPreview(uri, media.isVideo, PREVIEW_SIZE), isVideo = media.isVideo)
        } catch (e: Exception) {
            logWarning(TAG, "could not open $uri", e)
            null
        }

    /** Adds a new collage at the top of the grid and saves it (a video collage starts rendering). */
    fun createCollage(collage: Collage, settings: FrameSettings) {
        val item = MediaItem(
            sourceUri = collage.cells.first().sourceUri,
            isVideo = collage.hasVideo,
            displayName = "collage_${platform.timestamp()}",
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
        scope.launch {
            val thumbnail = withContext(Dispatchers.Default) { renderCollagePreview(collage, settings, THUMBNAIL_SIZE) }
            updateItem(id) { it.copy(thumbnail = thumbnail) }
            // With clips in it, the collage is a video and renders in the background.
            if (collage.hasVideo) renderVideo(id) else autoSavePhoto(id)
        }
    }

    fun renderVideo(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        updateItem(id) { it.copy(isProcessing = true, progress = 0f, error = null, warning = null) }
        platform.renderVideo(item)
    }

    fun renderAllVideos() {
        items.filter { it.isVideo && !it.isRendered && !it.isProcessing }.forEach { renderVideo(it.id) }
    }

    fun retry(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.isVideo) renderVideo(id) else scope.launch { autoSavePhoto(id) }
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
     * Moves the originals of all saved items (or those in [ids]) to the trash (deleted for good
     * after 30 days), once the user agrees.
     */
    fun trashOriginals(ids: Set<String>? = null) {
        val trash = platform.trash ?: return
        val targets = trashableItems(ids)
        if (targets.isEmpty()) return
        val uris = targets.map { trash.trashableUri(it)!! }
        scope.launch {
            if (!trash.moveToTrash(uris)) return@launch
            val itemIds = targets.map { it.id }
            val trashed = itemIds.zip(uris).mapNotNull { (id, uri) -> items.firstOrNull { it.id == id }?.let { it to uri } }
            items = items.map { if (it.id in itemIds) it.copy(originalTrashed = true) else it }
            selectedIds = selectedIds - itemIds.toSet()
            val now = platform.now()
            val entries = withContext(platform.io) {
                trashed.map { (item, uri) ->
                    TrashedOriginal(uri, item.displayName, item.isVideo, now, trash.saveThumbnail(uri, item.preview))
                }
            }
            storeTrash(entries + this@AppModel.trash)
            val n = entries.size
            platform.message("Moved $n original${if (n == 1) "" else "s"} to the trash", long = false)
        }
    }

    fun restoreFromTrash(entries: List<TrashedOriginal>) {
        val trash = platform.trash ?: return
        scope.launch {
            if (!trash.restore(entries.map { it.uri })) return@launch
            val restored = entries.map { it.uri }.toSet()
            items = items.map {
                if (it.originalTrashed && trash.trashableUri(it) in restored) {
                    it.copy(originalTrashed = false)
                } else {
                    it
                }
            }
            removeFromTrash(entries)
        }
    }

    fun deleteForever(entries: List<TrashedOriginal>) {
        val trash = platform.trash ?: return
        scope.launch {
            if (trash.deleteForever(entries.map { it.uri })) removeFromTrash(entries)
        }
    }

    private fun removeFromTrash(entries: List<TrashedOriginal>) {
        val trash = platform.trash ?: return
        entries.forEach { trash.deleteThumbnail(it) }
        val gone = entries.map { it.uri }.toSet()
        storeTrash(this.trash.filter { it.uri !in gone })
    }

    private fun storeTrash(entries: List<TrashedOriginal>) {
        trash = entries
        platform.trash?.saveEntries(entries)
    }

    /** Renders the photo in its format and saves it. */
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
                logWarning(TAG, "autoSavePhoto failed for ${item.displayName}", e)
                updateItem(id) { it.copy(isProcessing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Rebuilds restored items' pictures (previews, thumbnails, colours) from their originals, one
     * item at a time so the grid fills in without hogging the device.
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
            val previews = withContext(platform.io) {
                sources.associate { (uri, isVideo) ->
                    uri to try {
                        platform.loadPreview(uri, isVideo, PREVIEW_SIZE)
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
            platform.message("$missing item${if (missing == 1) "" else "s"} lost access to the original photos")
        }
    }

    /** The card picture for any kind of item, from the in-memory previews. */
    private fun thumbnailFor(item: MediaItem): PlatformBitmap? {
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
    private fun savePhoto(item: MediaItem): MediaUri? {
        val collage = item.collage
        val framed = if (collage != null) {
            platform.renderCollage(collage, item.settings)
        } else {
            val source = platform.loadPhoto(item.sourceUri, MAX_DECODE_DIMENSION)
            PhotoProcessor.frame(source, item.settings)
        }
        val uri = platform.saveImage(
            framed,
            if (collage != null) item.displayName else outputFileName(item.settings.format, item.displayName),
            replace = item.outputUri,
        )
        framed.asImage().recycle()
        return uri
    }

    /**
     * Saves each slide of a panorama, overwriting the slides of the previous save; slides left
     * over from an earlier, longer version (this app's own files) are deleted.
     */
    private fun savePanoramaSlides(item: MediaItem, panorama: Panorama): List<MediaUri> {
        val (width, height) = slideSize(item.settings.format)
        // Enough pixels to cover the whole strip, a little over to spare (portrait sources).
        val needed = (maxOf(width * panorama.slides, height) * 1.25f).toInt().coerceAtMost(MAX_PANORAMA_DECODE)
        val source = platform.loadPhoto(item.sourceUri, needed)
        val uris = (0 until panorama.slides).map { i ->
            val slide = PanoramaRenderer.renderSlide(source, panorama, item.settings, i, width, height)
            val uri = platform.saveImage(slide, slideFileName(item.displayName, i), replace = item.outputUris.getOrNull(i))
            slide.asImage().recycle()
            uri ?: throw IllegalStateException("Couldn't save slide ${i + 1}")
        }
        source.asImage().recycle()
        item.outputUris.drop(panorama.slides).forEach { platform.deleteOwn(it) }
        return uris
    }

    /**
     * Saves each slide of a carousel, named after the carousel ("carousel_<time>_1" …), overwriting
     * the previous save's slides; leftover slides of a longer earlier version are deleted.
     */
    private fun saveCarouselSlides(item: MediaItem, carousel: Carousel): List<MediaUri> {
        val (width, height) = slideSize(item.settings.format)
        // Each photo only as large as it appears on the slides (with room to spare), within memory.
        val sources = carousel.photos.mapIndexed { i, photo ->
            val rect = CarouselRenderer.photoRect(carousel, i, width.toFloat() * carousel.slides, height.toFloat())
            val needed = (maxOf(rect.width, rect.height) * 1.2f).toInt().coerceIn(256, 4000)
            try {
                platform.loadPhoto(photo.sourceUri, needed)
            } catch (e: Exception) {
                logWarning(TAG, "carousel photo ${photo.displayName} is gone", e)
                null
            }
        }
        val uris = (0 until carousel.slides).map { i ->
            val slide = CarouselRenderer.renderSlide(carousel, sources, item.settings, i, width, height)
            val uri = platform.saveImage(slide, "${item.displayName}_${i + 1}", replace = item.outputUris.getOrNull(i))
            slide.asImage().recycle()
            uri ?: throw IllegalStateException("Couldn't save slide ${i + 1}")
        }
        sources.forEach { it?.asImage()?.recycle() }
        item.outputUris.drop(carousel.slides).forEach { platform.deleteOwn(it) }
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

/** A photo for a carousel (placed later), or null if it can't be read. */
fun AppPlatform.loadCarouselPhoto(uri: MediaUri, previewSize: Int = PREVIEW_SIZE): CarouselPhoto? =
    try {
        val media = open(uri)
        val preview = loadPhoto(uri, previewSize)
        CarouselPhoto(
            sourceUri = uri,
            displayName = media.displayName,
            preview = preview,
            aspect = preview.width.toFloat() / preview.height,
            placement = Placement(0.5f, 0.5f, 0.8f),
        )
    } catch (e: Exception) {
        null
    }

/**
 * A collage from the original photos (clips show their middle frame), longer side at most
 * [maxSide]. Each photo is loaded only as large as its cell needs (with room to zoom), which
 * keeps nine photos within memory.
 */
fun AppPlatform.renderCollage(collage: Collage, settings: FrameSettings, maxSide: Int = Int.MAX_VALUE): PlatformBitmap {
    val (fullW, fullH) = collageSize(settings.format)
    val scale = min(1f, maxSide.toFloat() / max(fullW, fullH))
    val w = (fullW * scale).roundToInt()
    val h = (fullH * scale).roundToInt()
    val rects = CollageRenderer.cellRects(collage, settings, w, h)
    val sources = collage.cells.mapIndexed { i, cell ->
        val rect = rects.getOrNull(i) ?: return@mapIndexed null
        val needed = (max(rect.width, rect.height) * cell.zoom * 2).roundToInt().coerceIn(512, 4000)
        loadPreview(cell.sourceUri, cell.isVideo, needed)
    }
    return CollageRenderer.render(collage, sources, settings, w, h)
}
