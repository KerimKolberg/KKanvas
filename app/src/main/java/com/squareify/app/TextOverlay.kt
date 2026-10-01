package com.squareify.app

import android.graphics.Color
import android.graphics.Typeface
import java.io.Serializable

/** Fonts every Android phone has, so nothing needs to be bundled. */
enum class TextFont(val label: String, private val family: String, private val style: Int) {
    CLASSIC("Classic", "sans-serif", Typeface.BOLD),
    ELEGANT("Elegant", "serif", Typeface.ITALIC),
    TYPEWRITER("Typewriter", "serif-monospace", Typeface.NORMAL),
    HANDWRITTEN("Handwritten", "cursive", Typeface.BOLD),
    CASUAL("Casual", "casual", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.BOLD);

    val typeface: Typeface get() = Typeface.create(family, style)
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
    val color: Int = Color.WHITE,
    val backdrop: TextBackdrop = TextBackdrop.SHADOW,
    val alignment: TextAlignment = TextAlignment.CENTER,
    /** Where the text block sits, 0 = top, 1 = bottom. */
    val position: Float = 0.85f,
) : Serializable
