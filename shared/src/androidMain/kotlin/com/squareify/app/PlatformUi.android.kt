package com.squareify.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

/** At most [wanted] items from the photo picker, but no more than this phone's picker allows. */
fun pickLimit(wanted: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        min(wanted, MediaStore.getPickImagesMaxLimit())
    } else {
        wanted
    }

@Composable
actual fun rememberMediaPicker(kind: PickKind, onPicked: (List<MediaUri>) -> Unit): () -> Unit {
    val type = if (kind.videos) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly
    if (kind.maxItems == 1) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { onPicked(listOf(it)) }
        }
        return { launcher.launch(PickVisualMediaRequest(type)) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickLimit(kind.maxItems))) { uris ->
        onPicked(uris)
    }
    return { launcher.launch(PickVisualMediaRequest(type)) }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun rememberSharing(): Sharing {
    val context = LocalContext.current
    return remember(context) {
        object : Sharing {
            override fun share(items: List<MediaItem>) = shareOutputs(context, items)
            override val postToInstagram: (List<MediaItem>) -> Unit = { postToInstagram(context, it) }
        }
    }
}

/** Opens the system share sheet (Instagram feed, story, DMs, …) for the saved outputs of [items]. */
private fun shareOutputs(context: Context, items: List<MediaItem>) {
    val send = sendIntent(items) ?: return
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * Straight into Instagram's new-post screen with the saved outputs (several become one carousel,
 * in order). Without Instagram it falls back to the share sheet.
 */
private fun postToInstagram(context: Context, items: List<MediaItem>) {
    val send = sendIntent(items) ?: return
    try {
        context.startActivity(send.setPackage(INSTAGRAM))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Instagram isn't installed; pick an app instead.", Toast.LENGTH_SHORT).show()
        context.startActivity(Intent.createChooser(send.setPackage(null), null))
    }
}

private const val INSTAGRAM = "com.instagram.android"

/** A send intent for the saved outputs of [items]; null if nothing is saved. */
private fun sendIntent(items: List<MediaItem>): Intent? {
    val saved = items.filter { it.outputUri != null }
    // Slides (panoramas, carousels) go all together, in order, ready to post as one carousel.
    val uris = saved.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) } }
    if (uris.isEmpty()) return null
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
    return send
}

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual fun editorDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnClickOutside = false,
    decorFitsSystemWindows = false,
)

actual val isDesktop: Boolean = false
