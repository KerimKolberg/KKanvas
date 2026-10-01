package com.squareify.app

import java.io.Serializable
import kotlin.math.max
import kotlin.math.min

/** Trim, speed, sound and boomerang for one video. The default changes nothing. */
data class VideoEdit(
    val trimStartMs: Long = 0L,
    /** Null = to the end of the clip. */
    val trimEndMs: Long? = null,
    val muted: Boolean = false,
    /** 0.25 (slow motion) to 4 (timelapse). */
    val speed: Float = 1f,
    /** Plays forward, then backward. */
    val boomerang: Boolean = false,
) : Serializable {
    /** Sound only survives at normal speed and when not played backwards. */
    val keepsSound: Boolean get() = !muted && speed == 1f && !boomerang

    /** The part of a [durationMs] clip that's used, in ms (a boomerang is capped at [MAX_BOOMERANG_MS]). */
    fun rangeMs(durationMs: Long): LongRange {
        val start = trimStartMs.coerceIn(0L, max(0L, durationMs - MIN_LENGTH_MS))
        var end = (trimEndMs ?: durationMs).coerceIn(start + MIN_LENGTH_MS, max(start + MIN_LENGTH_MS, durationMs))
        if (boomerang) end = min(end, start + MAX_BOOMERANG_MS)
        return start..end
    }

    /** Length of the result for a [durationMs] clip. */
    fun outputLengthMs(durationMs: Long): Long {
        val range = rangeMs(durationMs)
        val once = ((range.last - range.first) / speed).toLong()
        return if (boomerang) once * 2 else once
    }

    companion object {
        const val MIN_LENGTH_MS = 300L
        const val MAX_BOOMERANG_MS = 10_000L
        val SPEEDS = listOf(0.25f, 0.5f, 1f, 2f, 4f)
    }
}
