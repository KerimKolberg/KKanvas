package com.squareify.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquareifyScreen(model: AppModel) = CompositionLocalProvider(LocalAppPlatform provides model.platform) {
    val items = model.items
    // Saveable so an open sheet or preview survives rotation along with the ViewModel.
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings start open whenever the app starts (kept in the ViewModel, not restored from an
    // earlier session); they fold away once media is being added.
    var settingsExpanded by model::settingsExpanded
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    // The "move originals to trash" explanation; trashIds = null means all saved items.
    var confirmTrash by remember { mutableStateOf(false) }
    var trashIds by remember { mutableStateOf<Set<String>?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }

    val selectionMode = model.selectedIds.isNotEmpty()
    val selectedItems = items.filter { it.id in model.selectedIds }
    val savedItems = items.filter { it.isRendered && it.outputUri != null }

    PlatformBackHandler(enabled = selectionMode) { model.clearSelection() }

    // Covers both the picker and media shared in from other apps.
    LaunchedEffect(model.isProcessing) {
        if (model.isProcessing) settingsExpanded = false
    }

    val pickMedia = rememberMediaPicker(PickKind.MEDIA) { uris -> model.addMedia(uris) }
    // Straight into a new collage, without adding (and saving) the picked media on their own.
    val pickForCollage = rememberMediaPicker(PickKind.COLLAGE) { uris -> model.startCollageFromPicker(uris) }
    val pickForCarousel = rememberMediaPicker(PickKind.CAROUSEL) { uris -> model.startCarouselFromPicker(uris) }
    val pickForPanorama = rememberMediaPicker(PickKind.PANORAMA) { uris -> model.startCollageFromPicker(uris) }
    val sharing = rememberSharing()

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = model::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selectedItems.size} selected") },
                    actions = {
                        IconButton(onClick = model::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(APP_NAME) },
                    actions = {
                        if (savedItems.isNotEmpty()) {
                            IconButton(onClick = { sharing.share(savedItems) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share all saved")
                            }
                        }
                        if (items.isNotEmpty() || model.trash.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                MainMenu(
                                    expanded = menuOpen,
                                    onDismiss = { menuOpen = false },
                                    hasTrash = model.platform.trash != null,
                                    trashableCount = model.trashableItems().size,
                                    trashCount = model.trash.size,
                                    canRemoveSaved = items.any { it.isRendered && !it.isProcessing },
                                    canRemoveAll = items.isNotEmpty(),
                                    onTrashOriginals = {
                                        trashIds = null
                                        confirmTrash = true
                                    },
                                    onShowTrash = { showTrash = true },
                                    onRemoveSaved = model::removeSaved,
                                    onRemoveAll = { confirmRemoveAll = true },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                val selectedSaved = selectedItems.filter { it.isRendered && it.outputUri != null }
                SelectionBar(
                    hasTrash = model.platform.trash != null,
                    trashableCount = model.trashableItems(model.selectedIds).size,
                    savedCount = selectedSaved.size,
                    onTrashOriginals = {
                        trashIds = model.selectedIds
                        confirmTrash = true
                    },
                    onShare = { sharing.share(selectedSaved) },
                    onRemove = model::removeSelected,
                    // One photo: split it into carousel slides.
                    collageEnabled = selectedItems.size in 1..CollageLayout.MAX_PHOTOS,
                    onCollage = model::startCollageFromSelection,
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            // A big window (Windows): the settings beside the grid, and as many columns as fit.
            val wide = maxWidth >= WIDE_LAYOUT
            val settingsPanel = @Composable {
                GlobalSettingsPanel(
                    settings = model.globalSettings,
                    onSettingsChange = model::updateGlobalSettings,
                    expanded = settingsExpanded || wide,
                    onExpandedChange = { settingsExpanded = it },
                    onAddMedia = {
                        settingsExpanded = false
                        pickMedia()
                    },
                    onCreate = { kind ->
                        when (kind) {
                            CreateKind.COLLAGE -> pickForCollage()
                            CreateKind.CAROUSEL -> pickForCarousel()
                            CreateKind.PANORAMA -> pickForPanorama()
                        }
                    },
                    isProcessing = model.isProcessing,
                    sample = items.lastOrNull { it.collage == null && it.preview != null }?.preview,
                    alwaysOpen = wide,
                )
            }
            val grid: @Composable ColumnScope.(GridCells) -> Unit = { columns ->
                // Videos only render on request.
                val pendingVideos = items.count { it.isVideo && !it.isRendered && !it.isProcessing }
                if (pendingVideos > 0 && !selectionMode) {
                    FilledTonalButton(
                        onClick = model::renderAllVideos,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = if (wide) 12.dp else 0.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (pendingVideos == 1) "Render video" else "Render all $pendingVideos videos")
                    }
                }

                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (wide) {
                                "Click \"Add Photos/Videos\" or drag photos and videos here to get started,\n" +
                                    "or \"Create\" for a collage, a carousel or panorama slides."
                            } else {
                                "Tap \"Add Photos/Videos\" to get started,\nor \"Create\" for a collage, a carousel\nor panorama slides."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = columns,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            MediaCard(
                                item = item,
                                onOpen = { previewItemId = item.id },
                                onEdit = { editingItemId = item.id },
                                onRemove = { model.remove(item.id) },
                                onRenderVideo = { model.renderVideo(item.id) },
                                onShare = { sharing.share(listOf(item)) },
                                onPostToInstagram = sharing.postToInstagram?.let { post -> { post(listOf(item)) } },
                                onRetry = { model.retry(item.id) },
                                selectionMode = selectionMode,
                                selected = item.id in model.selectedIds,
                                onToggleSelected = { model.toggleSelected(item.id) },
                            )
                        }
                    }
                }
            }
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(SETTINGS_WIDTH)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) { settingsPanel() }
                    VerticalDivider()
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) { grid(GridCells.Adaptive(CARD_WIDTH)) }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    settingsPanel()
                    grid(GridCells.Fixed(2))
                }
            }
        }
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all ${items.size} items?") },
            text = { Text("This only clears the list. Your photos and videos, and the saved results, stay in the gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    model.removeAll()
                }) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }

    if (confirmTrash) {
        val count = model.trashableItems(trashIds).size
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move $count original${if (count == 1) "" else "s"} to the trash?") },
            text = {
                Text(
                    model.platform.trash?.moveExplanation.orEmpty()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    model.trashOriginals(trashIds)
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }

    if (showTrash) {
        RecentlyDeletedSheet(
            entries = model.trash,
            onRestore = { model.restoreFromTrash(it) },
            onDeleteForever = { model.deleteForever(it) },
            onDismiss = { showTrash = false },
        )
    }

    items.firstOrNull { it.id == editingItemId }?.let { item ->
        val collage = item.collage
        val panorama = item.panorama
        val carousel = item.carousel
        when {
            carousel != null -> CarouselEditor(
                initialCarousel = carousel,
                initialSettings = item.settings,
                isNew = false,
                onDismiss = { editingItemId = null },
                onApply = { c, s -> model.applyCarouselEdit(item.id, c, s) },
            )
            collage != null -> CollageEditor(
                initialCollage = collage,
                initialSettings = item.settings,
                isNew = false,
                onDismiss = { editingItemId = null },
                onApply = { c, s -> model.applyCollageEdit(item.id, c, s) },
            )
            panorama != null -> PanoramaEditor(
                sourceUri = item.sourceUri,
                preview = item.preview,
                initialPanorama = panorama,
                initialSettings = item.settings,
                isNew = false,
                onDismiss = { editingItemId = null },
                onApply = { p, s -> model.applyPanoramaEdit(item.id, p, s) },
            )
            else -> EditSheet(
                item = item,
                onDismiss = { editingItemId = null },
                onApply = { model.applyEdit(item.id, it) },
                onSplitIntoSlides = if (item.isVideo) null else ({ model.startPanorama(item) }),
            )
        }
    }
    model.carouselDraft?.let { carousel ->
        CarouselEditor(
            initialCarousel = carousel,
            initialSettings = model.globalSettings,
            isNew = true,
            onDismiss = model::dismissCarouselDraft,
            onApply = { c, s -> model.createCarousel(c, s) },
        )
    }
    model.panoramaDraft?.let { draft ->
        PanoramaEditor(
            sourceUri = draft.sourceUri,
            preview = draft.preview,
            initialPanorama = null,
            initialSettings = model.globalSettings,
            isNew = true,
            onDismiss = model::dismissPanoramaDraft,
            onApply = { p, s -> model.createPanorama(draft, p, s) },
        )
    }
    model.collageDraft?.let { collage ->
        CollageEditor(
            initialCollage = collage,
            initialSettings = model.globalSettings,
            isNew = true,
            onDismiss = model::dismissCollageDraft,
            onApply = { c, s -> model.createCollage(c, s) },
        )
    }
    items.firstOrNull { it.id == previewItemId }?.let { item ->
        PreviewDialog(item = item, onDismiss = { previewItemId = null })
    }
}

@Composable
private fun MainMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasTrash: Boolean,
    trashableCount: Int,
    trashCount: Int,
    canRemoveSaved: Boolean,
    canRemoveAll: Boolean,
    onTrashOriginals: () -> Unit,
    onShowTrash: () -> Unit,
    onRemoveSaved: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    fun item(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (hasTrash) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (trashableCount > 0) {
                            "Move $trashableCount original${if (trashableCount == 1) "" else "s"} to trash…"
                        } else {
                            "Move originals to trash…"
                        }
                    )
                },
                enabled = trashableCount > 0,
                onClick = item(onTrashOriginals),
            )
            DropdownMenuItem(text = { Text("Recently deleted ($trashCount)") }, onClick = item(onShowTrash))
            HorizontalDivider()
        }
        DropdownMenuItem(text = { Text("Remove saved items") }, enabled = canRemoveSaved, onClick = item(onRemoveSaved))
        DropdownMenuItem(text = { Text("Remove all") }, enabled = canRemoveAll, onClick = item(onRemoveAll))
    }
}

/** Actions for the items picked by long-press. */
@Composable
private fun SelectionBar(
    hasTrash: Boolean,
    trashableCount: Int,
    savedCount: Int,
    onTrashOriginals: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    collageEnabled: Boolean,
    onCollage: () -> Unit,
) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SelectionAction(Icons.Default.Dashboard, "Collage", enabled = collageEnabled, onClick = onCollage)
            SelectionAction(Icons.Default.Share, "Share", enabled = savedCount > 0, onClick = onShare)
            if (hasTrash) {
                SelectionAction(
                    Icons.Default.DeleteSweep,
                    if (trashableCount > 0) "Trash $trashableCount original${if (trashableCount == 1) "" else "s"}" else "Trash originals",
                    enabled = trashableCount > 0,
                    onClick = onTrashOriginals,
                )
            }
            SelectionAction(Icons.Default.RemoveCircleOutline, "Remove", enabled = true, onClick = onRemove)
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun GlobalSettingsPanel(
    settings: FrameSettings,
    onSettingsChange: (FrameSettings) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddMedia: () -> Unit,
    onCreate: (CreateKind) -> Unit,
    isProcessing: Boolean,
    /** A photo to preview the looks on. */
    sample: PlatformBitmap?,
    /** In its own column beside the grid (big windows): always open, at full height. */
    alwaysOpen: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val buttonPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            Button(onClick = onAddMedia, modifier = Modifier.weight(1f), contentPadding = buttonPadding) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isProcessing) "Processing…" else "Add Photos/Videos", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            CreateButton(enabled = !isProcessing, contentPadding = buttonPadding, onCreate = onCreate)
            if (!alwaysOpen) {
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide settings" else "Show settings",
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        FormatSelector(selected = settings.format, onSelect = { onSettingsChange(settings.copy(format = it)) })

        if (alwaysOpen) {
            StyleControls(settings = settings, onChange = onSettingsChange, sample = sample)
        } else if (expanded) {
            // Capped and scrollable so the grid below stays visible.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                StyleControls(settings = settings, onChange = onSettingsChange, sample = sample)
            }
        }
    }
}

/** From this window width on, the settings go beside the grid instead of above it. */
val WIDE_LAYOUT = 900.dp
private val SETTINGS_WIDTH = 520.dp
private val CARD_WIDTH = 260.dp

/** What the Create button makes. */
enum class CreateKind(val title: String, val subtitle: String, val icon: ImageVector) {
    COLLAGE("Collage", "2-9 photos or videos in one picture", Icons.Default.Dashboard),
    CAROUSEL("Carousel", "Photos placed freely across slides", Icons.Default.ViewCarousel),
    PANORAMA("Panorama slides", "One wide photo split seamlessly", Icons.Default.Panorama),
}

/** "Create": a menu of the things made from several photos, or across several slides. */
@Composable
private fun CreateButton(enabled: Boolean, contentPadding: PaddingValues, onCreate: (CreateKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled, contentPadding = contentPadding) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Create", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CreateKind.entries.forEach { kind ->
                DropdownMenuItem(
                    leadingIcon = { Icon(kind.icon, contentDescription = null) },
                    text = {
                        Column {
                            Text(kind.title)
                            Text(
                                kind.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onCreate(kind)
                    },
                )
            }
        }
    }
}
