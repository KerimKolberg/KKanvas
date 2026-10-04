package com.squareify.app

/** The caption fonts, bundled with the app (shared/.../resources/fonts) so text looks the same everywhere. */
enum class TextFont(val label: String, val bold: Boolean = false, val italic: Boolean = false) {
    CLASSIC("Classic", bold = true),
    ELEGANT("Elegant", italic = true),
    TYPEWRITER("Typewriter"),
    HANDWRITTEN("Handwritten", bold = true),
    CASUAL("Casual"),
    CONDENSED("Condensed", bold = true),
}

/** What sits behind the text so it stays readable on any photo. */
enum class TextBackdrop { SHADOW, BOX, NONE }

enum class TextAlignment { LEFT, CENTER, RIGHT }

/** A caption or title drawn on top of the result, after the look (so it stays crisp). */
data class TextOverlay(
    val text: String = "",
    val font: TextFont = TextFont.CLASSIC,
    /** 0–1, relative to the picture's shorter side. */
    val size: Float = 0.35f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val backdrop: TextBackdrop = TextBackdrop.SHADOW,
    val alignment: TextAlignment = TextAlignment.CENTER,
    /** Where the text block sits, 0 = top, 1 = bottom. */
    val position: Float = 0.85f,
) : JavaSerializable
