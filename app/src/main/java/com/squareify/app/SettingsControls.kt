package com.squareify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

/** Which background colour the eyedropper fills in: the solid/top colour or the gradient's bottom. */
enum class ColorSlot { PRIMARY, SECONDARY }

private val PRESET_COLORS = listOf(
    android.graphics.Color.WHITE,
    android.graphics.Color.BLACK,
    android.graphics.Color.parseColor("#F3F4F6"),
    android.graphics.Color.parseColor("#1E293B"),
    android.graphics.Color.parseColor("#EF4444"),
    android.graphics.Color.parseColor("#3B82F6"),
    android.graphics.Color.parseColor("#22C55E"),
    android.graphics.Color.parseColor("#F59E0B"),
)

private val SELECTED_BORDER = Color(0xFF2563EB)
private val SWATCH_BORDER = Color(0xFFCBD5E1)

@Composable
fun FormatSelector(selected: FrameFormat, onSelect: (FrameFormat) -> Unit) {
    val formats = FrameFormat.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        formats.forEachIndexed { index, format ->
            SegmentedButton(
                selected = format == selected,
                onClick = { onSelect(format) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(format.label)
                        Text(format.description, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
    }
}

/**
 * Background, border and adjustment controls; shared by the settings panel and the edit sheet.
 * [photoColors] and [onPickFromPhoto] only exist when editing a single item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleControls(
    settings: FrameSettings,
    onChange: (FrameSettings) -> Unit,
    photoColors: List<Int> = emptyList(),
    onPickFromPhoto: ((ColorSlot) -> Unit)? = null,
) {
    SectionLabel("Background")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaddingStyle.entries.forEach { style ->
            FilterChip(
                selected = settings.paddingStyle == style,
                onClick = { onChange(settings.copy(paddingStyle = style)) },
                label = {
                    Text(
                        when (style) {
                            PaddingStyle.SOLID -> "Solid color"
                            PaddingStyle.GRADIENT -> "Gradient"
                            PaddingStyle.BLUR -> "Blurred"
                        }
                    )
                },
            )
        }
    }
    when (settings.paddingStyle) {
        PaddingStyle.SOLID -> ColorChoices(
            selected = settings.bgColor,
            onSelect = { onChange(settings.copy(bgColor = it)) },
            photoColors = photoColors,
            onPickFromPhoto = onPickFromPhoto?.let { pick -> { pick(ColorSlot.PRIMARY) } },
        )
        PaddingStyle.GRADIENT -> {
            Text("Top", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            ColorChoices(
                selected = settings.bgColor,
                onSelect = { onChange(settings.copy(bgColor = it)) },
                photoColors = photoColors,
                onPickFromPhoto = onPickFromPhoto?.let { pick -> { pick(ColorSlot.PRIMARY) } },
            )
            Text("Bottom", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            ColorChoices(
                selected = settings.bgColor2,
                onSelect = { onChange(settings.copy(bgColor2 = it)) },
                photoColors = photoColors,
                onPickFromPhoto = onPickFromPhoto?.let { pick -> { pick(ColorSlot.SECONDARY) } },
            )
        }
        PaddingStyle.BLUR -> AdjustmentSlider("Blur", settings.blurStrength, 0f, 1f) {
            onChange(settings.copy(blurStrength = it))
        }
    }

    val border = settings.border
    SectionLabel("Border")
    AdjustmentSlider("Margin", border.margin, 0f, 1f) {
        onChange(settings.copy(border = border.copy(margin = it)))
    }
    AdjustmentSlider("Rounded corners", border.cornerRadius, 0f, 1f) {
        onChange(settings.copy(border = border.copy(cornerRadius = it)))
    }
    AdjustmentSlider("Shadow", border.shadow, 0f, 1f) {
        onChange(settings.copy(border = border.copy(shadow = it)))
    }

    val adjustments = settings.adjustments
    SectionLabel("Adjustments")
    AdjustmentSlider("Brightness", adjustments.brightness, 0f, 2f) {
        onChange(settings.copy(adjustments = adjustments.copy(brightness = it)))
    }
    AdjustmentSlider("Saturation", adjustments.saturation, 0f, 2f) {
        onChange(settings.copy(adjustments = adjustments.copy(saturation = it)))
    }
    AdjustmentSlider("Sharpness", adjustments.sharpness, 0f, 1f) {
        onChange(settings.copy(adjustments = adjustments.copy(sharpness = it)))
    }
    AdjustmentSlider("Grain", adjustments.grain, 0f, 1f) {
        onChange(settings.copy(adjustments = adjustments.copy(grain = it)))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** Preset swatches plus a custom colour; when editing one item also colours from its photo and an eyedropper. */
@Composable
fun ColorChoices(
    selected: Int,
    onSelect: (Int) -> Unit,
    photoColors: List<Int> = emptyList(),
    onPickFromPhoto: (() -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val isCustom = selected !in PRESET_COLORS && selected !in photoColors

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRESET_COLORS.forEach { c -> ColorSwatch(c, isSelected = c == selected, onClick = { onSelect(c) }) }
        // Shows the chosen colour when it isn't one of the swatches.
        ColorSwatch(
            color = if (isCustom) selected else null,
            isSelected = isCustom,
            icon = Icons.Default.Palette,
            contentDescription = "Custom color",
            onClick = { showPicker = true },
        )
    }
    if (photoColors.isNotEmpty() || onPickFromPhoto != null) {
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("From photo", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            photoColors.forEach { c -> ColorSwatch(c, isSelected = c == selected, onClick = { onSelect(c) }) }
            if (onPickFromPhoto != null) {
                ColorSwatch(
                    color = null,
                    isSelected = false,
                    icon = Icons.Default.Colorize,
                    contentDescription = "Pick a color from the photo",
                    onClick = onPickFromPhoto,
                )
            }
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = selected,
            onDismiss = { showPicker = false },
            onPick = {
                onSelect(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Int?,
    isSelected: Boolean,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(if (color != null) Color(color) else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) SELECTED_BORDER else SWATCH_BORDER,
                shape = shape,
            )
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            val tint = when {
                color == null -> MaterialTheme.colorScheme.onSurfaceVariant
                ColorUtils.calculateLuminance(color) > 0.5 -> Color.Black
                else -> Color.White
            }
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp), tint = tint)
        }
    }
}

/** Hue / saturation / brightness picker for any background colour. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Use color") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Custom color") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(color))
                        .border(1.dp, SWATCH_BORDER, RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                            )
                        )
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(value = saturation, onValueChange = { saturation = it })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value = brightness, onValueChange = { brightness = it })
                Text(
                    "#%06X".format(color and 0xFFFFFF),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray,
                )
            }
        },
    )
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label: ${(100 * value).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}
