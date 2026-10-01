package com.squareify.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.util.UUID

enum class PaddingStyle { SOLID, BLUR }

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
)

/** How an item is padded and adjusted. The defaults for new items are saved by [SettingsStore]. */
data class FrameSettings(
    val format: FrameFormat = FrameFormat.SQUARE,
    val paddingStyle: PaddingStyle = PaddingStyle.SOLID,
    val bgColor: Int = Color.WHITE,
    val adjustments: Adjustments = Adjustments(),
)

data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val settings: FrameSettings = FrameSettings(),
    val thumbnail: Bitmap? = null,
    val outputUri: Uri? = null,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val isRendered: Boolean = false,
    val error: String? = null,
    /** Saved, but with a caveat, e.g. the audio couldn't be copied. */
    val warning: String? = null,
)
