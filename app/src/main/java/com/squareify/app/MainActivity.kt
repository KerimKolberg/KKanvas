package com.squareify.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the shared items are already in the ViewModel.
        if (savedInstanceState == null) {
            viewModel.addMedia(intent.sharedMediaUris())
        }
        setContent {
            MaterialTheme {
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

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> viewModel.addMedia(uris) }

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
                settings = viewModel.globalSettings,
                onSettingsChange = viewModel::updateGlobalSettings,
                onAddMedia = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                mediaCount = items.size,
                isProcessing = viewModel.isProcessing,
            )

            // Videos only render on request; with several waiting, offer to queue them all.
            val pendingVideos = items.count { it.isVideo && !it.isRendered && !it.isProcessing }
            if (pendingVideos >= 2) {
                FilledTonalButton(
                    onClick = viewModel::renderAllVideos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Render all $pendingVideos videos")
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
                            onOpen = { previewItemId = item.id },
                            onEdit = { editingItemId = item.id },
                            onRemove = { viewModel.remove(item.id) },
                            onRenderVideo = { viewModel.renderVideo(item.id) },
                            onShare = { shareOutputs(context, listOf(item)) },
                            onRetry = { viewModel.retry(item.id) },
                        )
                    }
                }
            }
        }
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
            // Capped and scrollable so the grid below stays visible.
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
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
