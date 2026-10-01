package com.squareify.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Trim, speed, sound and boomerang for one video; [durationMs] is null until it's known. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoControls(edit: VideoEdit, durationMs: Long?, onChange: (VideoEdit) -> Unit) {
    Text("Video", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))

    if (durationMs != null && durationMs > VideoEdit.MIN_LENGTH_MS) {
        val start = edit.trimStartMs.coerceIn(0L, durationMs)
        val end = (edit.trimEndMs ?: durationMs).coerceIn(start, durationMs)
        Text(
            "Trim: ${clock(start)} – ${clock(end)}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { range ->
                onChange(
                    edit.copy(
                        trimStartMs = range.start.toLong(),
                        // Right at the end means "to the end", whatever the exact duration.
                        trimEndMs = range.endInclusive.toLong().takeIf { it < durationMs - 50 },
                    )
                )
            },
            valueRange = 0f..durationMs.toFloat(),
        )
    }

    Text("Speed", style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoEdit.SPEEDS.forEach { speed ->
            FilterChip(
                selected = edit.speed == speed,
                onClick = { onChange(edit.copy(speed = speed)) },
                label = {
                    Text(
                        when {
                            speed < 1f -> "${speedLabel(speed)} slow-mo"
                            speed > 1f -> "${speedLabel(speed)} fast"
                            else -> "Normal"
                        }
                    )
                },
            )
        }
    }

    SwitchRow(
        label = "Sound",
        checked = edit.keepsSound,
        enabled = edit.speed == 1f && !edit.boomerang,
        onChange = { onChange(edit.copy(muted = !it)) },
    )
    SwitchRow(
        label = "Boomerang (forward, then backward)",
        checked = edit.boomerang,
        enabled = true,
        onChange = { onChange(edit.copy(boomerang = it)) },
    )

    val notes = buildList {
        if (durationMs != null) add("Result: ${seconds(edit.outputLengthMs(durationMs))}")
        if (!edit.muted && !edit.keepsSound) add("No sound when slowed down, sped up or as a boomerang")
        if (edit.boomerang && durationMs != null) {
            val trimmed = (edit.trimEndMs ?: durationMs) - edit.trimStartMs
            if (trimmed > VideoEdit.MAX_BOOMERANG_MS) add("A boomerang uses the first ${VideoEdit.MAX_BOOMERANG_MS / 1000} s")
        }
    }
    Text(
        notes.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun speedLabel(speed: Float) = if (speed < 1f) "${(1 / speed).toInt()}×" else "${speed.toInt()}×"

/** 83400 ms → "1:23.4". */
private fun clock(ms: Long) = String.format(Locale.US, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000f)

private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000f)
