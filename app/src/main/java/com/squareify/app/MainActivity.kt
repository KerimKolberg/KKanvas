package com.squareify.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the shared items are already in the ViewModel.
        if (savedInstanceState == null) {
            viewModel.addMedia(intent.sharedMediaUris())
        }
        setContent {
            SquareifyTheme {
                SquarifyApp(viewModel)
            }
        }
    }
}

/** Media sent to us through the system share sheet ("Share → kk-Squareify"). */
private fun Intent.sharedMediaUris(): List<Uri> = when (action) {
    Intent.ACTION_SEND ->
        listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
    Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquarifyApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val items = viewModel.items
    // Saveable so an open sheet or preview survives rotation along with the ViewModel.
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings start open; they fold away once media is being added.
    var settingsExpanded by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    // The "move originals to trash" explanation; trashIds = null means all saved items.
    var confirmTrash by remember { mutableStateOf(false) }
    var trashIds by remember { mutableStateOf<Set<String>?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }

    val selectionMode = viewModel.selectedIds.isNotEmpty()
    val selectedItems = items.filter { it.id in viewModel.selectedIds }
    val savedItems = items.filter { it.isRendered && it.outputUri != null }

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    // Android's own confirmation dialogs for moving to / restoring from / emptying the trash.
    val systemRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onSystemRequestResult(result.resultCode == Activity.RESULT_OK) }
    fun confirmWithAndroid(request: IntentSender?) {
        request?.let { systemRequestLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    // Needed for the render progress notification on Android 13+. Rendering works without it.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Covers both the picker and media shared in from other apps.
    LaunchedEffect(viewModel.isProcessing) {
        if (viewModel.isProcessing) settingsExpanded = false
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> viewModel.addMedia(uris) }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selectedItems.size} selected") },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (savedItems.isNotEmpty()) {
                            IconButton(onClick = { shareOutputs(context, savedItems) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share all saved")
                            }
                        }
                        if (items.isNotEmpty() || viewModel.trash.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                MainMenu(
                                    expanded = menuOpen,
                                    onDismiss = { menuOpen = false },
                                    trashableCount = viewModel.trashableItems().size,
                                    trashCount = viewModel.trash.size,
                                    canRemoveSaved = items.any { it.isRendered && !it.isProcessing },
                                    canRemoveAll = items.isNotEmpty(),
                                    onTrashOriginals = {
                                        trashIds = null
                                        confirmTrash = true
                                    },
                                    onShowTrash = { showTrash = true },
                                    onRemoveSaved = viewModel::removeSaved,
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
                    trashableCount = viewModel.trashableItems(viewModel.selectedIds).size,
                    savedCount = selectedSaved.size,
                    onTrashOriginals = {
                        trashIds = viewModel.selectedIds
                        confirmTrash = true
                    },
                    onShare = { shareOutputs(context, selectedSaved) },
                    onRemove = viewModel::removeSelected,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            GlobalSettingsPanel(
                settings = viewModel.globalSettings,
                onSettingsChange = viewModel::updateGlobalSettings,
                expanded = settingsExpanded,
                onExpandedChange = { settingsExpanded = it },
                onAddMedia = {
                    settingsExpanded = false
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                mediaCount = items.size,
                isProcessing = viewModel.isProcessing,
            )

            // Videos only render on request.
            val pendingVideos = items.count { it.isVideo && !it.isRendered && !it.isProcessing }
            if (pendingVideos > 0 && !selectionMode) {
                FilledTonalButton(
                    onClick = viewModel::renderAllVideos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (pendingVideos == 1) "Render video" else "Render all $pendingVideos videos")
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap \"Add Photos/Videos\" to get started",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
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
                            onRemove = { viewModel.remove(item.id) },
                            onRenderVideo = { viewModel.renderVideo(item.id) },
                            onShare = { shareOutputs(context, listOf(item)) },
                            onRetry = { viewModel.retry(item.id) },
                            selectionMode = selectionMode,
                            selected = item.id in viewModel.selectedIds,
                            onToggleSelected = { viewModel.toggleSelected(item.id) },
                        )
                    }
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
                    viewModel.removeAll()
                }) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }

    if (confirmTrash) {
        val count = viewModel.trashableItems(trashIds).size
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move $count original${if (count == 1) "" else "s"} to the trash?") },
            text = {
                Text(
                    "Their squared versions are saved, so the original photos and videos can go. " +
                        "They move to the phone's trash and are deleted for good after 30 days. " +
                        "Until then you can restore them under ⋮ → Recently deleted.\n\n" +
                        "Android will ask you to confirm next."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    confirmWithAndroid(viewModel.requestTrashOriginals(trashIds))
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }

    if (showTrash) {
        RecentlyDeletedSheet(
            entries = viewModel.trash,
            onRestore = { confirmWithAndroid(viewModel.requestRestore(it)) },
            onDeleteForever = { confirmWithAndroid(viewModel.requestDeleteForever(it)) },
            onDismiss = { showTrash = false },
        )
    }

    items.firstOrNull { it.id == editingItemId }?.let { item ->
        EditSheet(
            item = item,
            onDismiss = { editingItemId = null },
            onApply = { viewModel.applyEdit(item.id, it) },
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
        DropdownMenuItem(text = { Text("Remove saved items") }, enabled = canRemoveSaved, onClick = item(onRemoveSaved))
        DropdownMenuItem(text = { Text("Remove all") }, enabled = canRemoveAll, onClick = item(onRemoveAll))
    }
}

/** Actions for the items picked by long-press. */
@Composable
private fun SelectionBar(
    trashableCount: Int,
    savedCount: Int,
    onTrashOriginals: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onCollage: (() -> Unit)? = null,
) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (onCollage != null) {
                SelectionAction(Icons.Default.Dashboard, "Collage", enabled = true, onClick = onCollage)
            }
            SelectionAction(Icons.Default.Share, "Share", enabled = savedCount > 0, onClick = onShare)
            SelectionAction(
                Icons.Default.DeleteSweep,
                if (trashableCount > 0) "Trash $trashableCount original${if (trashableCount == 1) "" else "s"}" else "Trash originals",
                enabled = trashableCount > 0,
                onClick = onTrashOriginals,
            )
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
    mediaCount: Int,
    isProcessing: Boolean,
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
            Button(onClick = onAddMedia, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isProcessing) "Processing…" else "Add Photos/Videos")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "$mediaCount item${if (mediaCount == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide settings" else "Show settings",
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        FormatSelector(selected = settings.format, onSelect = { onSettingsChange(settings.copy(format = it)) })

        if (expanded) {
            // Capped and scrollable so the grid below stays visible.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                StyleControls(settings = settings, onChange = onSettingsChange)
            }
        }
    }
}

/** Opens the system share sheet (Instagram feed, story, DMs, …) for the saved outputs of [items]. */
private fun shareOutputs(context: Context, items: List<MediaItem>) {
    val saved = items.filter { it.outputUri != null }
    val uris = saved.mapNotNull { it.outputUri }
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = when {
        saved.all { it.isVideo } -> "video/mp4"
        saved.none { it.isVideo } -> "image/jpeg"
        else -> "*/*"
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
