package com.squareify.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.PanoramaRenderer
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Splits one wide photo into carousel slides; shows the slides as they'll swipe by. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanoramaEditor(
    sourceUri: Uri,
    preview: Bitmap?,
    initialPanorama: Panorama?,
    initialSettings: FrameSettings,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onApply: (Panorama, FrameSettings) -> Unit,
) {
    val context = LocalContext.current
    // Starts with the small preview, then a sharper copy for the slides.
    var source by remember { mutableStateOf(preview) }
    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            try {
                PhotoProcessor.loadDownscaledBitmap(context, sourceUri, PANORAMA_EDITOR_SIZE)
            } catch (e: Exception) {
                null
            }
        }?.let { source = it }
    }
    fun auto(format: FrameFormat): Int = source?.let { Panorama.autoSlides(it.width, it.height, format) } ?: 3
    val history = remember {
        // Carousels can't be 9:16; Instagram would crop the slides.
        val start = initialSettings.copy(format = initialSettings.format.takeIf { it in Panorama.FORMATS } ?: FrameFormat.PORTRAIT)
        EditHistory((initialPanorama ?: Panorama(auto(start.format))) to start)
    }
    var panorama by history.part({ it.first }, { state, p -> state.copy(first = p) })
    var settings by history.part({ it.second }, { state, s -> state.copy(second = s) })
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var previewing by remember { mutableStateOf(false) }

    LaunchedEffect(source, panorama, settings) {
        val src = source ?: return@LaunchedEffect
        delay(30)
        rendered = withContext(Dispatchers.Default) { renderPanoramaPreview(src, panorama, settings, SLIDE_PREVIEW_HEIGHT) }
    }

    EditorFrame(
        title = if (isNew) "Carousel slides" else "Edit slides",
        history = history,
        unsaved = isNew || history.canUndo,
        what = if (isNew) "new slides" else "changes",
        onSave = {
            onApply(panorama, settings)
            onDismiss()
        },
        onClose = onDismiss,
    ) {
        Spacer(Modifier.height(8.dp))

        SlideStrip(rendered = rendered, slides = panorama.slides, format = settings.format)
        Text(
            "Swipe to see every slide. Post them together as one carousel, in this order.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text("Slides", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { panorama = panorama.copy(slides = panorama.slides - 1) },
                enabled = panorama.slides > Panorama.MIN_SLIDES,
            ) { Icon(Icons.Default.Remove, contentDescription = "Fewer slides") }
            Text(
                "${panorama.slides}",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp),
            )
            FilledTonalIconButton(
                onClick = { panorama = panorama.copy(slides = panorama.slides + 1) },
                enabled = panorama.slides < Panorama.MAX_SLIDES,
            ) { Icon(Icons.Default.Add, contentDescription = "More slides") }
            val autoCount = auto(settings.format)
            TextButton(
                onClick = { panorama = panorama.copy(slides = autoCount) },
                enabled = panorama.slides != autoCount,
            ) { Text("Auto ($autoCount)") }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            CellFit.entries.forEachIndexed { i, fit ->
                SegmentedButton(
                    selected = panorama.fit == fit,
                    onClick = { panorama = panorama.copy(fit = fit) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = CellFit.entries.size),
                    label = { Text(if (fit == CellFit.FILL) "Fill (crop)" else "Fit (whole photo)") },
                )
            }
        }
        val src = source
        if (panorama.fit == CellFit.FILL && src != null) {
            val (slideW, slideH) = slideSize(settings.format)
            val overflow = panoramaOverflow(src.width, src.height, panorama, slideW.toFloat() * panorama.slides, slideH.toFloat())
            if (overflow > 1f) {
                // Which side gets cropped depends on whether the photo is wider than the strip.
                val sideways = src.width.toFloat() / src.height > slideW.toFloat() * panorama.slides / slideH
                Text(
                    if (sideways) "Position (left – right)" else "Position (top – bottom)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(value = panorama.position, onValueChange = { panorama = panorama.copy(position = it) }, valueRange = -1f..1f)
            }
        }

        Spacer(Modifier.height(8.dp))
        FormatSelector(
            selected = settings.format,
            onSelect = { format ->
                // Keep the auto count in step with the slide shape, unless it was set by hand.
                if (panorama.slides == auto(settings.format)) panorama = panorama.copy(slides = auto(format))
                settings = settings.copy(format = format)
            },
            formats = Panorama.FORMATS,
        )
        StyleControls(settings = settings, onChange = { settings = it }, sample = preview, showBorder = false, showText = true)

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { previewing = true }, enabled = source != null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Preview the swipe")
        }
        Button(
            onClick = {
                onApply(panorama, settings)
                onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(if (isNew) "Create ${panorama.slides} slides" else "Apply")
        }
        Spacer(Modifier.height(24.dp))
    }

    val shownSource = source
    if (previewing && shownSource != null) {
        val shown = panorama
        val style = settings
        val (slideW, slideH) = slideSize(style.format)
        SwipePreviewDialog(
            slides = shown.slides,
            aspect = slideW.toFloat() / slideH,
            renderSlide = { i ->
                val width = SWIPE_PREVIEW_WIDTH
                PanoramaRenderer.renderSlide(shownSource, shown, style, i, width, width * slideH / slideW)
            },
            warnings = emptyList(),
            onDismiss = { previewing = false },
        )
    }
}

/** The slides side by side, cut from the rendered strip, numbered. */
@Composable
private fun SlideStrip(rendered: Bitmap?, slides: Int, format: FrameFormat) {
    val aspect = format.widthRatio.toFloat() / format.heightRatio
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SLIDE_HEIGHT_DP.dp + 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (rendered == null) {
            CircularProgressIndicator()
        } else {
            SlideRow(rendered, slides, aspect)
        }
    }
}

@Composable
private fun SlideRow(rendered: Bitmap, slides: Int, aspect: Float) {
    val image = remember(rendered) { rendered.asImageBitmap() }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until slides) {
            val left = rendered.width * i / slides
            val right = rendered.width * (i + 1) / slides
            Box(
                modifier = Modifier
                    .height(SLIDE_HEIGHT_DP.dp)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                Image(
                    painter = remember(image, i, slides) {
                        BitmapPainter(image, IntOffset(left, 0), IntSize(right - left, rendered.height))
                    },
                    contentDescription = "Slide ${i + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "${i + 1}/$slides",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

private const val SLIDE_HEIGHT_DP = 220
/** Height in pixels the strip is rendered at for the editor. */
private const val SLIDE_PREVIEW_HEIGHT = 560
