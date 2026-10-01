package com.squareify.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Photos are decoded at most this large before saving; 200 MP originals won't fit in memory. */
private const val MAX_DECODE_DIMENSION = 6000
private const val THUMBNAIL_SIZE = 500

class MainActivity : ComponentActivity() {
    // Photos/videos shared into the app from another app, waiting to be added to the grid.
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change the share has already been handled.
        if (savedInstanceState == null) {
            sharedUris = intent.sharedMediaUris()
        }
        setContent {
            MaterialTheme {
                SquarifyApp(sharedUris = sharedUris, onSharedUrisHandled = { sharedUris = emptyList() })
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
fun SquarifyApp(sharedUris: List<Uri> = emptyList(), onSharedUrisHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // Settings for newly added media, remembered across launches.
    var globalSettings by remember { mutableStateOf(SettingsStore.load(context)) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(globalSettings) {
        delay(300) // Sliders change this continuously while dragged; save once they settle.
        SettingsStore.save(context, globalSettings)
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

    fun updateItem(id: String, transform: (MediaItem) -> MediaItem) {
        items = items.map { if (it.id == id) transform(it) else it }
    }

    // Mirror progress from RenderService into the grid; drop finished entries from the holder.
    val renderStates by RenderStateHolder.states.collectAsState()
    LaunchedEffect(renderStates) {
        for ((id, state) in renderStates) {
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

    // Renders the photo in its format and saves it to Pictures/Squareify.
    suspend fun autoSavePhoto(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        updateItem(id) { it.copy(isProcessing = true, error = null) }
        try {
            val savedUri = withContext(Dispatchers.Default) {
                val source = PhotoProcessor.loadDownscaledBitmap(context, item.sourceUri, MAX_DECODE_DIMENSION)
                val framed = PhotoProcessor.frame(source, item.settings)
                val uri = saveImageToGallery(context, framed, outputFileName(item.settings.format, item.displayName))
                framed.recycle()
                uri
            }
            updateItem(id) { it.copy(outputUri = savedUri, isProcessing = false, isRendered = true) }
        } catch (e: Exception) {
            Log.e("Squareify", "autoSavePhoto failed for ${item.displayName}", e)
            updateItem(id) { it.copy(isProcessing = false, error = e.message ?: e.toString()) }
        }
    }

    // Picked or shared media: build previews, then save the photos right away.
    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isProcessing = true
        val settings = globalSettings
        scope.launch {
            val newItems = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        val type = context.contentResolver.getType(uri)
                        val isVideo = type != null && type.startsWith("video/")
                        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "media"
                        val thumb = renderThumbnail(context, uri, isVideo, settings)
                        MediaItem(
                            sourceUri = uri,
                            isVideo = isVideo,
                            displayName = name,
                            settings = settings,
                            thumbnail = thumb,
                            isRendered = false,
                        )
                    } catch (e: Exception) {
                        Log.w("Squareify", "could not open $uri", e)
                        null
                    }
                }
            }
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

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> addMedia(uris) }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            addMedia(sharedUris)
            onSharedUrisHandled()
        }
    }

    fun renderVideo(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        updateItem(id) { it.copy(isProcessing = true, progress = 0f, error = null, warning = null) }
        RenderService.enqueue(context, item)
    }

    fun retry(item: MediaItem) {
        if (item.isVideo) renderVideo(item.id) else scope.launch { autoSavePhoto(item.id) }
    }

    val savedItems = items.filter { it.isRendered && it.outputUri != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (savedItems.isNotEmpty()) {
                        IconButton(onClick = { shareOutputs(context, savedItems) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share all saved")
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            GlobalSettingsPanel(
                settings = globalSettings,
                onSettingsChange = { globalSettings = it },
                onAddMedia = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                mediaCount = items.size,
                isProcessing = isProcessing,
            )

            // Videos only render on request; with several waiting, offer to queue them all.
            val pendingVideos = items.filter { it.isVideo && !it.isRendered && !it.isProcessing }
            if (pendingVideos.size >= 2) {
                FilledTonalButton(
                    onClick = { pendingVideos.forEach { renderVideo(it.id) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Render all ${pendingVideos.size} videos")
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap \"Add Photos/Videos\" to get started", color = Color.Gray)
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
                            onEdit = { editingItemId = item.id },
                            onRemove = { items = items.filter { it.id != item.id } },
                            onRenderVideo = { renderVideo(item.id) },
                            onShare = { shareOutputs(context, listOf(item)) },
                            onRetry = { retry(item) },
                        )
                    }
                }
            }
        }
    }

    val editingItem = items.firstOrNull { it.id == editingItemId }
    if (editingItem != null) {
        EditSheet(
            item = editingItem,
            onDismiss = { editingItemId = null },
            onApply = { newSettings ->
                // Settings changed, so any earlier export no longer matches.
                updateItem(editingItem.id) {
                    it.copy(settings = newSettings, outputUri = null, isRendered = false, warning = null)
                }
                scope.launch {
                    val thumbnail = withContext(Dispatchers.Default) {
                        renderThumbnail(context, editingItem.sourceUri, editingItem.isVideo, newSettings)
                    }
                    updateItem(editingItem.id) { it.copy(thumbnail = thumbnail) }
                    if (!editingItem.isVideo) {
                        autoSavePhoto(editingItem.id)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsPanel(
    settings: FrameSettings,
    onSettingsChange: (FrameSettings) -> Unit,
    onAddMedia: () -> Unit,
    mediaCount: Int,
    isProcessing: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

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
            Text("$mediaCount item${if (mediaCount == 1) "" else "s"}", color = Color.Gray)
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Settings",
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        FormatSelector(selected = settings.format, onSelect = { onSettingsChange(settings.copy(format = it)) })

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            PaddingControls(settings = settings, onChange = onSettingsChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSelector(selected: FrameFormat, onSelect: (FrameFormat) -> Unit) {
    val formats = FrameFormat.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        formats.forEachIndexed { index, format ->
            SegmentedButton(
                selected = format == selected,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(format.label)
                        Text(format.description, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
    }
}

/** Background style, colour and adjustment sliders; shared by the settings panel and the edit sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaddingControls(settings: FrameSettings, onChange: (FrameSettings) -> Unit) {
    val adjustments = settings.adjustments
    Row {
        FilterChip(
            selected = settings.paddingStyle == PaddingStyle.SOLID,
            onClick = { onChange(settings.copy(paddingStyle = PaddingStyle.SOLID)) },
            label = { Text("Solid Color") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = settings.paddingStyle == PaddingStyle.BLUR,
            onClick = { onChange(settings.copy(paddingStyle = PaddingStyle.BLUR)) },
            label = { Text("Blurred Background") },
        )
    }
    if (settings.paddingStyle == PaddingStyle.SOLID) {
        Spacer(Modifier.height(8.dp))
        ColorSwatchRow(selected = settings.bgColor, onSelect = { onChange(settings.copy(bgColor = it)) })
    }
    Spacer(Modifier.height(8.dp))
    AdjustmentSlider("Brightness", adjustments.brightness, 0f, 2f) {
        onChange(settings.copy(adjustments = adjustments.copy(brightness = it)))
    }
    AdjustmentSlider("Saturation", adjustments.saturation, 0f, 2f) {
        onChange(settings.copy(adjustments = adjustments.copy(saturation = it)))
    }
    AdjustmentSlider("Sharpness", adjustments.sharpness, 0f, 1f) {
        onChange(settings.copy(adjustments = adjustments.copy(sharpness = it)))
    }
    AdjustmentSlider("Grain", adjustments.grain, 0f, 1f) {
        onChange(settings.copy(adjustments = adjustments.copy(grain = it)))
    }
}

@Composable
fun ColorSwatchRow(selected: Int, onSelect: (Int) -> Unit) {
    val swatches = listOf(
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        android.graphics.Color.parseColor("#F3F4F6"),
        android.graphics.Color.parseColor("#1E293B"),
        android.graphics.Color.parseColor("#EF4444"),
        android.graphics.Color.parseColor("#3B82F6"),
        android.graphics.Color.parseColor("#22C55E"),
        android.graphics.Color.parseColor("#F59E0B"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { c ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(c))
                    .border(
                        width = if (c == selected) 3.dp else 1.dp,
                        color = if (c == selected) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(c) }
            )
        }
    }
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label: ${(100 * value).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
fun MediaCard(
    item: MediaItem,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onRenderVideo: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF1F5F9))
    ) {
        item.thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                // Fit, not crop, so tall formats show their padding.
                contentScale = ContentScale.Fit,
            )
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("Video", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (item.isVideo && !item.isRendered && !item.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                FilledIconButton(onClick = onRenderVideo) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Render")
                }
            }
        }

        if (item.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    if (item.isVideo) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(item.progress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        if (item.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.75f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        item.error,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
            }
        }

        if (item.warning != null && !item.isProcessing) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF59E0B))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.Black,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    item.warning,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledIconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "Edit", modifier = Modifier.size(18.dp))
            }
            if (item.isRendered) {
                // Green = saved; tapping it shares the saved file.
                val savedTo = if (item.isVideo) "Movies/Squareify" else "Pictures/Squareify"
                FilledIconButton(
                    onClick = onShare,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF16A34A),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Saved to $savedTo. Share",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FilledIconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onApply: (FrameSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(item.settings) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Edit: ${item.displayName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FormatSelector(selected = settings.format, onSelect = { settings = settings.copy(format = it) })
            Spacer(Modifier.height(8.dp))
            PaddingControls(settings = settings, onChange = { settings = it })
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onApply(settings)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

/** Small preview of what will be saved: the photo or video frame padded to its format. */
private fun renderThumbnail(context: Context, uri: Uri, isVideo: Boolean, settings: FrameSettings): Bitmap? {
    val source = if (isVideo) {
        loadVideoFrame(context, uri)
    } else {
        PhotoProcessor.loadDownscaledBitmap(context, uri, THUMBNAIL_SIZE)
    }
    return source?.let { PhotoProcessor.frame(it, settings) }
}

/** A thumbnail-sized frame from the middle of the video. */
private fun loadVideoFrame(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        // Frame times are in microseconds: half the duration in ms * 1000.
        retriever.getScaledFrameAtTime(
            (500L * durationMs).coerceAtLeast(0L),
            MediaMetadataRetriever.OPTION_CLOSEST,
            THUMBNAIL_SIZE,
            THUMBNAIL_SIZE,
        )
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
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

/** Gallery file name (without extension), e.g. "story_IMG_1234". */
fun outputFileName(format: FrameFormat, displayName: String): String {
    val dot = displayName.lastIndexOf('.')
    val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
    return "${format.filePrefix}_$baseName"
}

private fun saveImageToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues()
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Squareify")
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    return uri
}

/** Copies [file] into Movies/Squareify, then deletes it. */
fun saveVideoToGallery(context: Context, file: File, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues()
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.mp4")
    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Squareify")
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { input -> input.copyTo(out) }
    }
    file.delete()
    return uri
}
