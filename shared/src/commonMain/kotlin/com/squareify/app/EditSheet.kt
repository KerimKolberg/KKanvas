package com.squareify.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.squareify.app.processing.asImage
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onApply: (FrameSettings) -> Unit,
    /** Opens the panorama editor for this photo; null for videos. */
    onSplitIntoSlides: (() -> Unit)? = null,
) {
    val history = remember { EditHistory(item.settings) }
    var settings by history.part({ it }, { _, s -> s })
    var rendered by remember { mutableStateOf(item.thumbnail) }
    var showOriginal by remember { mutableStateOf(false) }
    // Set while the eyedropper is active: which colour a tap on the photo fills in.
    var pickingSlot by remember { mutableStateOf<ColorSlot?>(null) }
    val source = item.preview
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val platform = LocalAppPlatform.current
    // A video's length, for the trim slider.
    var durationMs by remember { mutableStateOf<Long?>(null) }
    if (item.isVideo) {
        LaunchedEffect(item.sourceUri) {
            durationMs = withContext(platform.io) { platform.videoDurationMs(item.sourceUri) }
        }
    }

    // Live preview: re-render on every change; a newer change cancels the older render.
    LaunchedEffect(settings) {
        if (source == null) return@LaunchedEffect
        delay(50)
        rendered = withContext(Dispatchers.Default) {
            PhotoProcessor.frameFitting(source, settings, LIVE_PREVIEW_SIZE)
        }
    }

    EditorFrame(
        title = "Edit: ${item.displayName}",
        history = history,
        unsaved = history.canUndo,
        what = "changes",
        onSave = {
            onApply(settings)
            onDismiss()
        },
        onClose = onDismiss,
        scrollState = scrollState,
    ) {
        Spacer(Modifier.height(8.dp))

        val picking = pickingSlot != null
        val shown = if (picking || showOriginal) source else rendered
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(picking, source) {
                    if (picking && source != null) {
                        detectTapGestures { offset ->
                            val color = pickPixel(source, size, offset) ?: return@detectTapGestures
                            settings = if (pickingSlot == ColorSlot.SECONDARY) {
                                settings.copy(bgColor2 = color)
                            } else {
                                settings.copy(bgColor = color)
                            }
                            pickingSlot = null
                        }
                    } else {
                        detectTapGestures(onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            shown?.let {
                Image(
                    bitmap = it.asImage(),
                    contentDescription = if (shown === source) "Original" else "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            when {
                picking -> "Tap the photo to pick a color"
                showOriginal -> "Original"
                source != null -> "Hold the preview to see the original"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp),
        )
        if (picking) {
            TextButton(
                onClick = { pickingSlot = null },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Cancel") }
        }

        Spacer(Modifier.height(8.dp))
        if (item.isVideo) {
            VideoControls(edit = settings.video, durationMs = durationMs, onChange = { settings = settings.copy(video = it) })
            Spacer(Modifier.height(12.dp))
        }
        FormatSelector(selected = settings.format, onSelect = { settings = settings.copy(format = it) })
        StyleControls(
            settings = settings,
            onChange = { settings = it },
            photoColors = item.photoColors,
            sample = item.preview,
            showText = true,
            onPickFromPhoto = if (source != null) {
                { slot ->
                    pickingSlot = slot
                    // The preview is at the top; bring it into view to tap on.
                    scope.launch { scrollState.animateScrollTo(0) }
                }
            } else {
                null
            },
        )
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
        if (onSplitIntoSlides != null) {
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onSplitIntoSlides()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Icon(Icons.Default.ViewCarousel, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Split into carousel slides")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The colour of [bitmap] under [tap], for an image drawn with ContentScale.Fit into a box of [boxSize]. */
private fun pickPixel(bitmap: PlatformBitmap, boxSize: IntSize, tap: Offset): Int? {
    val scale = min(boxSize.width / bitmap.width.toFloat(), boxSize.height / bitmap.height.toFloat())
    val left = (boxSize.width - bitmap.width * scale) / 2
    val top = (boxSize.height - bitmap.height * scale) / 2
    val x = ((tap.x - left) / scale).toInt()
    val y = ((tap.y - top) / scale).toInt()
    if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
    return bitmap.pixelAt(x, y) or 0xFF000000.toInt()
}
