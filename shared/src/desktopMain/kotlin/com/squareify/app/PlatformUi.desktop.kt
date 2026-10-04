package com.squareify.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The app's window, for dialogs that belong to it. Set by the Windows app at start. */
object DesktopWindow {
    var frame: Frame? = null
}

/** Photo and video files the app opens. */
val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")

/** A file as the app keeps it. */
fun File.toMediaUri(): MediaUri = mediaUri(absoluteFile.toURI().toString())

/** The file behind a [MediaUri] made by [toMediaUri]. */
fun MediaUri.toFile(): File = File(java.net.URI(toString()))

@Composable
actual fun rememberMediaPicker(kind: PickKind, onPicked: (List<MediaUri>) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember(kind) {
        {
            val extensions = PHOTO_EXTENSIONS + if (kind.videos) VIDEO_EXTENSIONS else emptySet()
            val dialog = FileDialog(DesktopWindow.frame, if (kind.videos) "Add photos and videos" else "Add photos", FileDialog.LOAD)
            dialog.isMultipleMode = kind.maxItems > 1
            // The Windows dialog filters by this pattern.
            dialog.file = extensions.joinToString(";") { "*.$it" }
            dialog.isVisible = true
            val picked = dialog.files.orEmpty()
                .filter { it.extension.lowercase() in extensions }
                .take(kind.maxItems)
            if (picked.isNotEmpty()) callback.value(picked.map { it.toMediaUri() })
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun rememberSharing(): Sharing = remember {
    object : Sharing {
        /** Instagram has no Windows app to share to: show the saved files in Explorer instead. */
        override fun share(items: List<MediaItem>) {
            val first = items.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) } }.firstOrNull() ?: return
            ProcessBuilder("explorer.exe", "/select,", first.toFile().path).start()
        }

        override val postToInstagram: ((List<MediaItem>) -> Unit)? = null
    }
}

/** The teal and navy of the kk logo. */
private val Teal = Color(0xFF35BDB9)
private val Navy = Color(0xFF0E2038)

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(primary = Teal, onPrimary = Navy, secondary = Teal, background = Navy, surface = Color(0xFF13294A))
    } else {
        lightColorScheme(primary = Color(0xFF00696A), secondary = Navy)
    }

actual fun editorDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnClickOutside = false,
)
