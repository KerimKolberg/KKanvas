package com.squareify.app

/** Which artwork goes in the corner (white on transparent, tinted when drawn). */
enum class WatermarkMark(val label: String) {
    LETTERS("KK letters"),
    LOGO("Full logo"),
}

enum class WatermarkCorner(val label: String) {
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right"),
}

/** The kk logo in a corner of everything saved; part of the settings for new media. */
data class Watermark(
    val enabled: Boolean = false,
    val mark: WatermarkMark = WatermarkMark.LETTERS,
    val corner: WatermarkCorner = WatermarkCorner.BOTTOM_RIGHT,
    /** 0–1: from 5% to 20% of the picture's shorter side wide. */
    val size: Float = 0.3f,
    val opacity: Float = 0.7f,
    val color: Int = WHITE,
) : JavaSerializable {
    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /** The teal of the logo. */
        const val TEAL = 0xFF35BDB9.toInt()
        val COLORS = listOf("White" to WHITE, "Black" to BLACK, "Teal" to TEAL)
    }
}
