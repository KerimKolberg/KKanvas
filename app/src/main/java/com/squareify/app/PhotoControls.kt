package com.squareify.app

import android.graphics.RectF
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.PhotoShapes

/** Rectangle, circle, arch, pill or torn paper for one photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShapeChips(selected: PhotoShape, onSelect: (PhotoShape) -> Unit) {
    Text("Shape", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoShape.entries.forEach { shape ->
            FilterChip(selected = shape == selected, onClick = { onSelect(shape) }, label = { Text(shape.label) })
        }
    }
}

/**
 * Colour changes for one photo of a collage or carousel, folded away until asked for; they
 * come on top of the whole post's look.
 */
@Composable
fun PhotoAdjustments(adjustments: Adjustments, onChange: (Adjustments) -> Unit) {
    var open by remember { mutableStateOf(adjustments != Adjustments()) }
    Row {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide photo colors" else "Adjust this photo's colors") }
        if (adjustments != Adjustments()) {
            TextButton(onClick = { onChange(Adjustments()) }) { Text("Reset") }
        }
    }
    if (open) {
        Column {
            AdjustmentSlider("Brightness", adjustments.brightness, 0f, 2f) { onChange(adjustments.copy(brightness = it)) }
            AdjustmentSlider("Contrast", adjustments.contrast, 0.5f, 1.5f) { onChange(adjustments.copy(contrast = it)) }
            AdjustmentSlider("Saturation", adjustments.saturation, 0f, 2f) { onChange(adjustments.copy(saturation = it)) }
            AdjustmentSlider("Warmth", adjustments.warmth, -1f, 1f) { onChange(adjustments.copy(warmth = it)) }
            AdjustmentSlider("Fade", adjustments.fade, 0f, 1f) { onChange(adjustments.copy(fade = it)) }
        }
    }
}

/** The same outline as the saved photo, for clipping live previews; [cornerRadius] for rectangles. */
fun photoOutline(shape: PhotoShape, cornerRadius: (Size) -> Float): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(PhotoShapes.path(shape, RectF(0f, 0f, size.width, size.height), cornerRadius(size)).asComposePath())
}
