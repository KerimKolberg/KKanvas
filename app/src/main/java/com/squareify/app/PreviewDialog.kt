package com.squareify.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen view of an item's result; hold to see the original. */
@Composable
fun PreviewDialog(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Start with the small versions, then swap in screen-sized renders.
    var rendered by remember(item.id) { mutableStateOf<Bitmap?>(item.thumbnail) }
    var original by remember(item.id) { mutableStateOf<Bitmap?>(item.preview) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var showOriginal by remember { mutableStateOf(false) }

    LaunchedEffect(item.id, item.settings, item.collage, item.panorama) {
        loading = true
        val panorama = item.panorama
        if (panorama != null) {
            // The whole strip, with lines where the slides meet; hold for the original photo.
            val source = withContext(Dispatchers.IO) {
                try {
                    PhotoProcessor.loadDownscaledBitmap(context, item.sourceUri, PANORAMA_EDITOR_SIZE)
                } catch (e: Exception) {
                    Log.w("Squareify", "full-size panorama preview failed", e)
                    null
                }
            }
            if (source != null) {
                original = source
                rendered = withContext(Dispatchers.Default) {
                    renderPanoramaThumbnail(source, panorama, item.settings, PANORAMA_EDITOR_SIZE)
                }
            }
            loading = false
            return@LaunchedEffect
        }
        val collage = item.collage
        if (collage != null) {
            // A collage has no single original to compare with.
            original = null
            withContext(Dispatchers.IO) {
                try {
                    renderCollage(context, collage, item.settings, FULLSCREEN_SIZE)
                } catch (e: Exception) {
                    Log.w("Squareify", "full-size collage preview failed", e)
                    null
                }
            }?.let { rendered = it }
            loading = false
            return@LaunchedEffect
        }
        val source = withContext(Dispatchers.IO) {
            try {
                loadSourceImage(context, item.sourceUri, item.isVideo, FULLSCREEN_SIZE)
            } catch (e: Exception) {
                Log.w("Squareify", "full-size preview failed for ${item.displayName}", e)
                null
            }
        }
        if (source != null) {
            original = source
            rendered = withContext(Dispatchers.Default) {
                PhotoProcessor.frameFitting(source, item.settings, FULLSCREEN_SIZE)
            }
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
        ) {
            (if (showOriginal && original != null) original else rendered)?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = if (showOriginal) "Original" else "Result",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (loading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            Text(
                when {
                    original == null -> ""
                    showOriginal -> "Original"
                    else -> "Hold to see the original"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
