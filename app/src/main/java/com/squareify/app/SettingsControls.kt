package com.squareify.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
fun FormatSelector(
    selected: FrameFormat,
    onSelect: (FrameFormat) -> Unit,
    formats: List<FrameFormat> = FrameFormat.entries,
) {
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
 * [photoColors] and [onPickFromPhoto] only exist when editing a single item. [sample] is a photo
 * to preview the looks on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleControls(
    settings: FrameSettings,
    onChange: (FrameSettings) -> Unit,
    photoColors: List<Int> = emptyList(),
    onPickFromPhoto: ((ColorSlot) -> Unit)? = null,
    sample: Bitmap? = null,
    /** False where a border makes no sense (panorama slides must join up). */
    showBorder: Boolean = true,
    /** True in the editors for one item: a caption belongs to that item, not to all new media. */
    showText: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(StyleTab.BACKGROUND) }
    val tabs = StyleTab.entries.filter { (showBorder || it != StyleTab.BORDER) && (showText || it != StyleTab.TEXT) }
    val shown = if (tab in tabs) tab else tabs.first()
    SecondaryTabRow(
        selectedTabIndex = tabs.indexOf(shown),
        containerColor = Color.Transparent,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    ) {
        tabs.forEach { t ->
            Tab(selected = shown == t, onClick = { tab = t }, text = { Text(t.title) })
        }
    }
    when (shown) {
        StyleTab.BACKGROUND -> BackgroundControls(settings, onChange, photoColors, onPickFromPhoto)
        StyleTab.BORDER -> BorderControls(settings, onChange)
        StyleTab.ADJUST -> AdjustmentControls(settings, onChange, sample)
        StyleTab.TEXT -> TextControls(settings, onChange, photoColors)
    }
}

private enum class StyleTab(val title: String) {
    BACKGROUND("Background"),
    BORDER("Border"),
    ADJUST("Adjust"),
    TEXT("Text"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundControls(
    settings: FrameSettings,
    onChange: (FrameSettings) -> Unit,
    photoColors: List<Int>,
    onPickFromPhoto: ((ColorSlot) -> Unit)?,
) {
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
        PaddingStyle.BLUR -> {
            Spacer(Modifier.height(8.dp))
            AdjustmentSlider("Blur", settings.blurStrength, 0f, 1f) {
                onChange(settings.copy(blurStrength = it))
            }
        }
    }
}

@Composable
private fun BorderControls(settings: FrameSettings, onChange: (FrameSettings) -> Unit) {
    val border = settings.border
    AdjustmentSlider("Margin", border.margin, 0f, 1f) {
        onChange(settings.copy(border = border.copy(margin = it)))
    }
    AdjustmentSlider("Rounded corners", border.cornerRadius, 0f, 1f) {
        onChange(settings.copy(border = border.copy(cornerRadius = it)))
    }
    AdjustmentSlider("Shadow", border.shadow, 0f, 1f) {
        onChange(settings.copy(border = border.copy(shadow = it)))
    }
}

@Composable
private fun AdjustmentControls(settings: FrameSettings, onChange: (FrameSettings) -> Unit, sample: Bitmap?) {
    val adjustments = settings.adjustments
    fun change(transform: Adjustments.() -> Adjustments) = onChange(settings.copy(adjustments = adjustments.transform()))

    LookPicker(current = adjustments, sample = sample, onPick = { onChange(settings.copy(adjustments = it)) })
    Spacer(Modifier.height(8.dp))
    AdjustmentSlider("Brightness", adjustments.brightness, 0f, 2f) { change { copy(brightness = it) } }
    AdjustmentSlider("Contrast", adjustments.contrast, 0.5f, 1.5f) { change { copy(contrast = it) } }
    AdjustmentSlider("Saturation", adjustments.saturation, 0f, 2f) { change { copy(saturation = it) } }
    AdjustmentSlider("Warmth", adjustments.warmth, -1f, 1f) { change { copy(warmth = it) } }
    AdjustmentSlider("Fade", adjustments.fade, 0f, 1f) { change { copy(fade = it) } }
    AdjustmentSlider("Sharpness", adjustments.sharpness, 0f, 1f) { change { copy(sharpness = it) } }
    AdjustmentSlider("Grain", adjustments.grain, 0f, 1f) { change { copy(grain = it) } }
    AdjustmentSlider("Vignette", adjustments.vignette, 0f, 1f) { change { copy(vignette = it) } }
}

/**
 * One tile per look (built in, then the user's own), each showing [sample] with that look, plus
 * a tile that saves the current adjustments as a new look. Tapping a look sets all adjustments.
 */
@Composable
private fun LookPicker(current: Adjustments, sample: Bitmap?, onPick: (Adjustments) -> Unit) {
    val context = LocalContext.current
    val looks = BUILT_IN_LOOKS + LooksStore.saved
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Look?>(null) }

    val thumbnails by produceState<Map<Look, ImageBitmap>>(emptyMap(), sample, looks) {
        value = withContext(Dispatchers.Default) {
            val base = lookThumbnailBase(sample)
            looks.associateWith { look ->
                PhotoProcessor.applyAdjustments(base.copy(Bitmap.Config.ARGB_8888, true), look.adjustments).asImageBitmap()
            }
        }
    }

    Text("Looks", style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        looks.forEach { look ->
            val isSaved = look in LooksStore.saved
            LookTile(
                name = look.name,
                thumbnail = thumbnails[look],
                selected = look.adjustments == current,
                onClick = { onPick(look.adjustments) },
                onDelete = if (isSaved) ({ deleting = look }) else null,
            )
        }
        LookTile(name = "Save", thumbnail = null, selected = false, icon = Icons.Default.Add, onClick = { saving = true })
    }

    if (saving) {
        var name by remember { mutableStateOf("My look ${LooksStore.saved.size + 1}") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save this look") },
            text = {
                Column {
                    Text("Keeps the current adjustments so you can apply them in one tap.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        LooksStore.add(context, Look(name.trim(), current))
                        saving = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { look ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${look.name}\"?") },
            text = { Text("Photos already saved with it don't change.") },
            confirmButton = {
                TextButton(onClick = {
                    LooksStore.remove(context, look)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LookTile(
    name: String,
    thumbnail: ImageBitmap?,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (selected) 3.dp else 1.dp, if (selected) SELECTED_BORDER else SWATCH_BORDER, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClickLabel = "Delete $name", onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Delete $name", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * A small square to preview the looks on: the centre of [sample], or without a photo a made-up
 * scene (sky, warm horizon, skin tone, dark ground) that shows what each look does.
 */
private fun lookThumbnailBase(sample: Bitmap?): Bitmap {
    val size = 150
    val base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(base)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    if (sample != null) {
        val side = minOf(sample.width, sample.height)
        val left = (sample.width - side) / 2
        val top = (sample.height - side) / 2
        canvas.drawBitmap(sample, android.graphics.Rect(left, top, left + side, top + side), android.graphics.Rect(0, 0, size, size), paint)
    } else {
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            intArrayOf(0xFF4A90D9.toInt(), 0xFFF2A65A.toInt(), 0xFFD9A38A.toInt(), 0xFF2F4F3A.toInt()),
            floatArrayOf(0f, 0.45f, 0.65f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    }
    return base
}

/** The caption: words, font, size, colour, backdrop and where it sits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextControls(settings: FrameSettings, onChange: (FrameSettings) -> Unit, photoColors: List<Int>) {
    val overlay = settings.text ?: TextOverlay()
    fun change(transform: TextOverlay.() -> TextOverlay) = onChange(settings.copy(text = overlay.transform()))

    OutlinedTextField(
        value = overlay.text,
        onValueChange = { text -> change { copy(text = text.take(MAX_TEXT_LENGTH)) } },
        label = { Text("Caption or title") },
        placeholder = { Text("Type something…") },
        minLines = 1,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Font", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextFont.entries.forEach { font ->
            FilterChip(
                selected = overlay.font == font,
                onClick = { change { copy(font = font) } },
                // Each name is shown in its own font.
                label = { Text(font.label, fontFamily = remember(font) { FontFamily(Typeface(font.typeface)) }) },
            )
        }
    }
    AdjustmentSlider("Size", overlay.size, 0f, 1f) { change { copy(size = it) } }
    Text("Color", style = MaterialTheme.typography.labelMedium)
    ColorChoices(selected = overlay.color, onSelect = { change { copy(color = it) } }, photoColors = photoColors)

    Text("Behind the text", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TextBackdrop.entries.forEachIndexed { i, backdrop ->
            SegmentedButton(
                selected = overlay.backdrop == backdrop,
                onClick = { change { copy(backdrop = backdrop) } },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = TextBackdrop.entries.size),
                label = {
                    Text(
                        when (backdrop) {
                            TextBackdrop.SHADOW -> "Shadow"
                            TextBackdrop.BOX -> "Box"
                            TextBackdrop.NONE -> "Nothing"
                        }
                    )
                },
            )
        }
    }
    Text("Line up", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TextAlignment.entries.forEachIndexed { i, alignment ->
            SegmentedButton(
                selected = overlay.alignment == alignment,
                onClick = { change { copy(alignment = alignment) } },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = TextAlignment.entries.size),
                label = {
                    Text(
                        when (alignment) {
                            TextAlignment.LEFT -> "Left"
                            TextAlignment.CENTER -> "Center"
                            TextAlignment.RIGHT -> "Right"
                        }
                    )
                },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Height: ${heightLabel(overlay.position)}", style = MaterialTheme.typography.labelMedium)
    Slider(value = overlay.position, onValueChange = { change { copy(position = it) } })
    if (settings.text != null) {
        TextButton(onClick = { onChange(settings.copy(text = null)) }) { Text("Remove text") }
    }
}

private fun heightLabel(position: Float) = when {
    position < 0.2f -> "top"
    position > 0.8f -> "bottom"
    position in 0.4f..0.6f -> "middle"
    position < 0.5f -> "upper half"
    else -> "lower half"
}

private const val MAX_TEXT_LENGTH = 200

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
            Text(
                "From photo",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
