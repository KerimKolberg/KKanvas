package com.squareify.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/*
 * The parts of the screens that work differently on the phone and on Windows: picking files,
 * sharing, the back button, colours. Each has a phone and a Windows version.
 */

const val APP_NAME = "kk-Squareify"

/** What a picker is for: how many items, and whether videos may be picked. */
enum class PickKind(val maxItems: Int, val videos: Boolean) {
    /** "Add Photos/Videos". */
    MEDIA(20, true),
    /** Straight into a new collage. */
    COLLAGE(CollageLayout.MAX_PHOTOS, true),
    /** A new carousel, or more photos for one. */
    CAROUSEL(Carousel.MAX_PHOTOS, false),
    /** One wide photo to split into slides. */
    PANORAMA(1, false),
}

/** Opens a picker for [kind]; what's picked goes to [onPicked] (nothing if cancelled). */
@Composable
expect fun rememberMediaPicker(kind: PickKind, onPicked: (List<MediaUri>) -> Unit): () -> Unit

/** The phone's back button / gesture, while [enabled]. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Passing saved results on. */
interface Sharing {
    fun share(items: List<MediaItem>)

    /** Straight into Instagram's new post; null where there's no Instagram app. */
    val postToInstagram: ((List<MediaItem>) -> Unit)?
}

@Composable
expect fun rememberSharing(): Sharing

/** The colours: the phone's wallpaper colours (Android 12+), the app's own on Windows. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme

/** A dialog covering the whole screen that only the editor itself closes. */
expect fun editorDialogProperties(): DialogProperties
