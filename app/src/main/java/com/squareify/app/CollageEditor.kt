package com.squareify.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.CollageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Creates or edits a photo collage, with a live preview you can tap and drag on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageEditor(
    initialCollage: Collage,
    initialSettings: FrameSettings,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onApply: (Collage, FrameSettings) -> Unit,
) {
    var collage by remember { mutableStateOf(initialCollage) }
    var settings by remember { mutableStateOf(initialSettings) }
    var selectedCell by remember { mutableStateOf<Int?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }

    // Live preview from the photos' in-memory previews; a newer change cancels the older render.
    LaunchedEffect(collage, settings) {
        delay(30)
        rendered = withContext(Dispatchers.Default) { renderCollagePreview(collage, settings, LIVE_PREVIEW_SIZE) }
    }

    fun updateCell(index: Int, cell: CollageCell) {
        collage = collage.copy(cells = collage.cells.toMutableList().also { it[index] = cell })
    }

    /** Moves a filled photo inside its cell by a drag of [dx], [dy] preview pixels. */
    fun pan(index: Int, dx: Float, dy: Float, previewWidth: Int, previewHeight: Int) {
        val cell = collage.cells.getOrNull(index) ?: return
        val source = cell.preview ?: return
        if (cell.fit != CellFit.FILL) return
        val rect = CollageRenderer.cellRects(collage, settings, previewWidth, previewHeight).getOrNull(index) ?: return
        val crop = CollageRenderer.cropFor(source, rect, cell)
        val sourcePerPixel = crop.width() / rect.width()
        val slackX = (source.width - crop.width()) / 2
        val slackY = (source.height - crop.height()) / 2
        // Dragging right shows more of the left side, so the crop moves the other way.
        updateCell(
            index,
            cell.copy(
                panX = if (slackX > 0.5f) (cell.panX - dx * sourcePerPixel / slackX).coerceIn(-1f, 1f) else cell.panX,
                panY = if (slackY > 0.5f) (cell.panY - dy * sourcePerPixel / slackY).coerceIn(-1f, 1f) else cell.panY,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(if (isNew) "New collage" else "Edit collage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            CollagePreview(
                rendered = rendered,
                collage = collage,
                settings = settings,
                selectedCell = selectedCell,
                onSelectCell = { selectedCell = it },
                onPan = ::pan,
            )
            Text(
                if (selectedCell == null) "Tap a photo to adjust it" else "Drag to move the photo inside its cell",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp),
            )

            val index = selectedCell
            if (index != null && index < collage.cells.size) {
                CellControls(
                    cell = collage.cells[index],
                    canMoveEarlier = index > 0,
                    canMoveLater = index < collage.cells.size - 1,
                    onChange = { updateCell(index, it) },
                    onMove = { step ->
                        val cells = collage.cells.toMutableList()
                        val other = index + step
                        cells[index] = cells[other].also { cells[other] = cells[index] }
                        collage = collage.copy(cells = cells)
                        selectedCell = other
                    },
                )
            }

            Text("Layout", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            LayoutPicker(
                count = collage.cells.size,
                selected = collage.layout,
                onSelect = { collage = collage.copy(layout = it) },
            )
            Spacer(Modifier.height(8.dp))
            AdjustmentSlider("Spacing", collage.spacing, 0f, 1f) { collage = collage.copy(spacing = it) }

            Spacer(Modifier.height(8.dp))
            FormatSelector(selected = settings.format, onSelect = { settings = settings.copy(format = it) })
            StyleControls(settings = settings, onChange = { settings = it })

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onApply(collage, settings)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isNew) "Create collage" else "Apply")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The rendered collage; tap selects a cell, dragging moves the photo in the cell under the finger. */
@Composable
private fun CollagePreview(
    rendered: Bitmap?,
    collage: Collage,
    settings: FrameSettings,
    selectedCell: Int?,
    onSelectCell: (Int?) -> Unit,
    onPan: (index: Int, dx: Float, dy: Float, previewWidth: Int, previewHeight: Int) -> Unit,
) {
    // The gesture handlers live across recompositions; read the latest values through these.
    val currentRendered by rememberUpdatedState(rendered)
    val currentCollage by rememberUpdatedState(collage)
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSelect by rememberUpdatedState(onSelectCell)
    val currentOnPan by rememberUpdatedState(onPan)
    val outline = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val bitmap = currentRendered ?: return@detectTapGestures
                    currentOnSelect(cellAt(offset, size, bitmap, currentCollage, currentSettings))
                }
            }
            .pointerInput(Unit) {
                var dragging: Int? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val bitmap = currentRendered
                        dragging = bitmap?.let { cellAt(offset, size, it, currentCollage, currentSettings) }
                        dragging?.let { currentOnSelect(it) }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, amount ->
                    val index = dragging ?: return@detectDragGestures
                    val bitmap = currentRendered ?: return@detectDragGestures
                    change.consume()
                    val scale = fitScale(size, bitmap)
                    currentOnPan(index, amount.x / scale, amount.y / scale, bitmap.width, bitmap.height)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        rendered?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Collage preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (rendered != null && selectedCell != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = IntSize(size.width.toInt(), size.height.toInt())
                val rect = screenCellRects(boxSize, rendered, collage, settings).getOrNull(selectedCell)
                if (rect != null) {
                    drawRect(
                        color = outline,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** How much the preview image is scaled to fit its box (ContentScale.Fit). */
private fun fitScale(box: IntSize, bitmap: Bitmap): Float =
    min(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())

/** The cells' rectangles in the preview box's coordinates. */
private fun screenCellRects(box: IntSize, bitmap: Bitmap, collage: Collage, settings: FrameSettings): List<RectF> {
    val scale = fitScale(box, bitmap)
    val offsetX = (box.width - bitmap.width * scale) / 2
    val offsetY = (box.height - bitmap.height * scale) / 2
    return CollageRenderer.cellRects(collage, settings, bitmap.width, bitmap.height).map {
        RectF(it.left * scale + offsetX, it.top * scale + offsetY, it.right * scale + offsetX, it.bottom * scale + offsetY)
    }
}

/** Index of the photo cell under [offset], or null for the background or an empty cell. */
private fun cellAt(offset: Offset, box: IntSize, bitmap: Bitmap, collage: Collage, settings: FrameSettings): Int? =
    screenCellRects(box, bitmap, collage, settings)
        .indexOfFirst { it.contains(offset.x, offset.y) }
        .takeIf { it >= 0 && it < collage.cells.size }

@Composable
private fun CellControls(
    cell: CollageCell,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onChange: (CollageCell) -> Unit,
    onMove: (step: Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            CellFit.entries.forEachIndexed { i, fit ->
                SegmentedButton(
                    selected = cell.fit == fit,
                    onClick = { onChange(cell.copy(fit = fit)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = CellFit.entries.size),
                    label = { Text(if (fit == CellFit.FILL) "Fill" else "Fit") },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onMove(-1) }, enabled = canMoveEarlier) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move earlier")
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveLater) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move later")
        }
    }
    if (cell.fit == CellFit.FILL) {
        AdjustmentSlider("Zoom", cell.zoom, 1f, 3f) { onChange(cell.copy(zoom = it)) }
    }
}

/** Small diagrams of the layouts available for [count] photos. */
@Composable
private fun LayoutPicker(count: Int, selected: CollageLayout, onSelect: (CollageLayout) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CollageLayout.forCount(count).forEach { layout ->
            val isSelected = layout == selected
            val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(layout) }
                    .padding(4.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .size(52.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) color else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(5.dp)
                ) {
                    collageCellBoxes(layout, 1f, Box(0f, 0f, size.width, size.height)).forEachIndexed { i, b ->
                        drawRoundRect(
                            // Cells left empty (e.g. 7 photos in 3 × 3) are faint.
                            color = if (i < count) color else color.copy(alpha = 0.25f),
                            topLeft = Offset(b.left, b.top),
                            size = Size(b.width, b.height),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
                Text(layout.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
