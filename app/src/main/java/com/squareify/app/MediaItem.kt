package com.squareify.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.Serializable
import java.util.UUID

enum class PaddingStyle { SOLID, GRADIENT, BLUR }

/** Output aspect ratio. All of these are formats Instagram accepts. */
enum class FrameFormat(
    val label: String,
    val description: String,
    val widthRatio: Int,
    val heightRatio: Int,
    /** Prefix for saved file names. */
    val filePrefix: String,
) {
    SQUARE("1:1", "Square", 1, 1, "squared"),
    PORTRAIT("4:5", "Portrait", 4, 5, "portrait"),
    GRID("3:4", "Grid", 3, 4, "grid"),
    STORY("9:16", "Story", 9, 16, "story"),
}

data class Adjustments(
    val brightness: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val grain: Float = 0f,
) : Serializable

/** Space and decoration around the photo. Each value is 0–1; [PhotoProcessor] maps them to sizes. */
data class Border(
    val margin: Float = 0f,
    val cornerRadius: Float = 0f,
    val shadow: Float = 0f,
) : Serializable

/** How an item is padded and adjusted. The defaults for new items are saved by [SettingsStore]. */
data class FrameSettings(
    val format: FrameFormat = FrameFormat.SQUARE,
    val paddingStyle: PaddingStyle = PaddingStyle.SOLID,
    /** Solid background colour, or the top colour of the gradient. */
    val bgColor: Int = Color.WHITE,
    /** Bottom colour of the gradient. */
    val bgColor2: Int = Color.parseColor("#1E293B"),
    /** 0–1; the default matches the blur radius of the original app. */
    val blurStrength: Float = DEFAULT_BLUR_STRENGTH,
    val border: Border = Border(),
    val adjustments: Adjustments = Adjustments(),
) : Serializable {
    companion object {
        const val DEFAULT_BLUR_STRENGTH = 1f / 3
    }
}

data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val settings: FrameSettings = FrameSettings(),
    /** The photo (or a video frame), downscaled; used for thumbnails, live previews and colour picking. */
    val preview: Bitmap? = null,
    /** A few colours taken from [preview], offered as background colours. */
    val photoColors: List<Int> = emptyList(),
    val thumbnail: Bitmap? = null,
    /** The saved file; kept after an edit so the next save overwrites it instead of adding a copy. */
    val outputUri: Uri? = null,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    /** The saved file matches the current settings. */
    val isRendered: Boolean = false,
    val error: String? = null,
    /** Saved, but with a caveat, e.g. the audio couldn't be copied. */
    val warning: String? = null,
    /** The original was moved to the phone's trash after the result was saved. */
    val originalTrashed: Boolean = false,
)
