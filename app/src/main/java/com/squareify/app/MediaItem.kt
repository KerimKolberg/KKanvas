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

/** Colour and texture of the result. Neutral values change nothing; looks ([Look]) are sets of these. */
data class Adjustments(
    val brightness: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val grain: Float = 0f,
    /** 0.5–1.5; 1 = unchanged. */
    val contrast: Float = 1f,
    /** -1 (cooler, bluer) to 1 (warmer, more orange). */
    val warmth: Float = 0f,
    /** 0–1: lifts the blacks for a matte, washed-out look. */
    val fade: Float = 0f,
    /** 0–1: darkens the corners. */
    val vignette: Float = 0f,
) : Serializable

/** A frame drawn around a single photo or video, inside the padding. */
enum class FrameStyle(val label: String) {
    NONE("None"),
    THIN("Thin border"),
    POLAROID("Polaroid"),
    FILM("Film strip"),
}

/** Space and decoration around the photo. Each value is 0–1; [PhotoProcessor] maps them to sizes. */
data class Border(
    val margin: Float = 0f,
    val cornerRadius: Float = 0f,
    val shadow: Float = 0f,
    /** With a frame, the corners and shadow belong to the frame. */
    val frame: FrameStyle = FrameStyle.NONE,
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
    /** A caption on this item only; never part of the settings for new media. */
    val text: TextOverlay? = null,
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
    /** Set for a collage; its photos are in the cells and [sourceUri] is the first one. */
    val collage: Collage? = null,
    /** Set for a panorama split into carousel slides. */
    val panorama: Panorama? = null,
    /** A panorama's saved slides, in order; [outputUri] is the first. */
    val outputUris: List<Uri> = emptyList(),
)
