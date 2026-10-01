package com.squareify.app

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.CollageRenderer
import com.squareify.app.processing.FaceFinder
import com.squareify.app.processing.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Creates or edits a collage of photos and clips, with a live preview you can tap and drag on. */
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
    // A filled cell being moved or zoomed: drawn straight onto the preview, so it follows the
    // finger, until the full render catches up.
    var liveCell by remember { mutableStateOf<Int?>(null) }
    // While a finger is moving a photo, the full preview waits until it lifts.
    var dragging by remember { mutableStateOf(false) }

    // Live preview from the photos' in-memory previews; a newer change cancels the older render.
    LaunchedEffect(collage, settings, dragging) {
        if (dragging) return@LaunchedEffect
        delay(30)
        rendered = withContext(Dispatchers.Default) { renderCollagePreview(collage, settings, LIVE_PREVIEW_SIZE) }
        liveCell = null
    }

    fun updateCell(index: Int, cell: CollageCell) {
        collage = collage.copy(cells = collage.cells.toMutableList().also { it[index] = cell })
    }

    /** Swaps the contents of two cells; the sound stays with its clip. */
    fun swap(from: Int, to: Int) {
        val cells = collage.cells.toMutableList()
        cells[from] = cells[to].also { cells[to] = cells[from] }
        val soundCell = when (collage.soundCell) {
            from -> to
            to -> from
            else -> collage.soundCell
        }
        collage = collage.copy(cells = cells, soundCell = soundCell)
        selectedCell = to
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
        // Start from where the photo is now, which for a smart-cropped cell is around the faces.
        val panX = if (slackX > 0.5f) (crop.centerX() - source.width / 2f) / slackX else cell.panX
        val panY = if (slackY > 0.5f) (crop.centerY() - source.height / 2f) / slackY else cell.panY
        // Dragging right shows more of the left side, so the crop moves the other way.
        liveCell = index
        updateCell(
            index,
            cell.copy(
                panX = if (slackX > 0.5f) (panX - dx * sourcePerPixel / slackX).coerceIn(-1f, 1f) else panX,
                panY = if (slackY > 0.5f) (panY - dy * sourcePerPixel / slackY).coerceIn(-1f, 1f) else panY,
                panned = true,
            ),
        )
    }

    // Smart crop: find the faces in each photo once, so filled cells keep them in view.
    LaunchedEffect(Unit) {
        val todo = collage.cells.filter { it.focusX == null && it.preview != null }
        if (todo.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            todo.associate { cell -> cell.sourceUri to (FaceFinder.focus(cell.preview!!) ?: PointF(0.5f, 0.5f)) }
        }
        // Applied to the cells as they are now: they may have been moved around meanwhile.
        collage = collage.copy(
            cells = collage.cells.map { cell ->
                val focus = found[cell.sourceUri]
                if (focus != null && cell.focusX == null) cell.copy(focusX = focus.x, focusY = focus.y) else cell
            },
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
                liveCell = liveCell,
                onSelectCell = { selectedCell = it },
                onPan = ::pan,
                onDraggingChange = { dragging = it },
                onSwap = ::swap,
            )
            val index = selectedCell?.takeIf { it < collage.cells.size }
            Text(
                when {
                    index == null -> "Tap a photo or clip to adjust it. Hold and drag to swap two."
                    collage.cells[index].fit == CellFit.FILL -> "Drag to reposition it. Hold, then drag onto another cell to swap."
                    else -> "Hold, then drag it onto another cell to swap."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            if (index != null) {
                CellControls(
                    cell = collage.cells[index],
                    canMove = { collage.layout.neighbor(index, it, collage.cells.size) != null },
                    onChange = { changed ->
                        if (changed.zoom != collage.cells[index].zoom) liveCell = index
                        updateCell(index, changed)
                    },
                    onMove = { direction ->
                        collage.layout.neighbor(index, direction, collage.cells.size)?.let { swap(index, it) }
                    },
                )
            }

            if (collage.hasVideo) {
                VideoOptions(collage = collage, onChange = { collage = it })
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
            StyleControls(
                settings = settings,
                onChange = { settings = it },
                sample = collage.cells.firstNotNullOfOrNull { it.preview },
                showText = true,
                showFrameStyles = false,
            )

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
            if (collage.hasVideo) {
                Text(
                    "The video renders in the background; its card shows the progress.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The rendered collage. Tap selects a cell; dragging moves the photo inside the cell under the
 * finger; holding first, then dragging, carries the photo onto another cell to swap them.
 */
@Composable
private fun CollagePreview(
    rendered: Bitmap?,
    collage: Collage,
    settings: FrameSettings,
    selectedCell: Int?,
    liveCell: Int?,
    onSelectCell: (Int?) -> Unit,
    onPan: (index: Int, dx: Float, dy: Float, previewWidth: Int, previewHeight: Int) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onSwap: (from: Int, to: Int) -> Unit,
) {
    // The gesture handler lives across recompositions; read the latest values through these.
    val currentRendered by rememberUpdatedState(rendered)
    val currentCollage by rememberUpdatedState(collage)
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSelect by rememberUpdatedState(onSelectCell)
    val currentOnPan by rememberUpdatedState(onPan)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val currentOnSwap by rememberUpdatedState(onSwap)
    val haptics = LocalHapticFeedback.current
    val outline = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val badgeText = MaterialTheme.typography.labelMedium.copy(color = Color.White)
    val livePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    // The cell being carried to another place, and where the finger is.
    var moving by remember { mutableStateOf<Int?>(null) }
    var movePosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val bitmap = currentRendered ?: return@awaitEachGesture
                    val startCell = cellAt(down.position, size, bitmap, currentCollage, currentSettings)
                    // A drag starts once the finger moves; holding still first picks the photo up.
                    var lifted = false
                    var overSlop = Offset.Zero
                    val dragStart = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        }.also { if (it == null) lifted = true }
                    }
                    when {
                        dragStart != null -> {
                            if (startCell == null) return@awaitEachGesture
                            currentOnSelect(startCell)
                            val scale = fitScale(size, bitmap)
                            currentOnDraggingChange(true)
                            try {
                                currentOnPan(startCell, overSlop.x / scale, overSlop.y / scale, bitmap.width, bitmap.height)
                                drag(dragStart.id) { change ->
                                    val amount = change.positionChange()
                                    change.consume()
                                    currentOnPan(startCell, amount.x / scale, amount.y / scale, bitmap.width, bitmap.height)
                                }
                            } finally {
                                currentOnDraggingChange(false)
                            }
                        }
                        lifted -> currentOnSelect(startCell)
                        startCell != null -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnSelect(startCell)
                            moving = startCell
                            movePosition = down.position
                            try {
                                drag(down.id) { change ->
                                    movePosition = change.position
                                    change.consume()
                                }
                                val target = cellAt(movePosition, size, bitmap, currentCollage, currentSettings)
                                if (target != null && target != startCell) currentOnSwap(startCell, target)
                            } finally {
                                moving = null
                            }
                        }
                    }
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
        if (rendered != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = IntSize(size.width.toInt(), size.height.toInt())
                val rects = screenCellRects(boxSize, rendered, collage, settings)
                // The cell being moved or zoomed, drawn from its preview at the current crop.
                val liveIndex = liveCell
                val live = liveIndex?.let { collage.cells.getOrNull(it) }
                val liveSource = live?.preview
                val liveRect = liveIndex?.let { rects.getOrNull(it) }
                if (liveIndex != null && live != null && live.fit == CellFit.FILL && liveSource != null && liveRect != null) {
                    val cellInBitmap = CollageRenderer.cellRects(collage, settings, rendered.width, rendered.height)[liveIndex]
                    val crop = CollageRenderer.cropFor(liveSource, cellInBitmap, live)
                    val radius = PhotoProcessor.cornerRadius(settings.border, liveRect)
                    livePaint.colorFilter = PhotoProcessor.colorFilter(settings.adjustments)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.clipPath(android.graphics.Path().apply { addRoundRect(liveRect, radius, radius, android.graphics.Path.Direction.CW) })
                        val matrix = android.graphics.Matrix()
                        matrix.setRectToRect(crop, liveRect, android.graphics.Matrix.ScaleToFit.FILL)
                        native.drawBitmap(liveSource, matrix, livePaint)
                        native.restore()
                    }
                }
                // While carrying a photo: the cell it would go to, and the photo under the finger.
                val carried = moving?.let { collage.cells.getOrNull(it) }
                if (carried != null) {
                    val target = rects.indexOfFirst { it.contains(movePosition.x, movePosition.y) }
                        .takeIf { it >= 0 && it < collage.cells.size && it != moving }
                    target?.let { rects[it] }?.let { rect ->
                        drawRect(outline.copy(alpha = 0.35f), Offset(rect.left, rect.top), Size(rect.width(), rect.height()))
                    }
                    carried.preview?.let { photo ->
                        val side = 84.dp.toPx()
                        val scale = side / maxOf(photo.width, photo.height)
                        val w = photo.width * scale
                        val h = photo.height * scale
                        drawImage(
                            image = photo.asImageBitmap(),
                            dstOffset = IntOffset((movePosition.x - w / 2).roundToInt(), (movePosition.y - h / 2).roundToInt()),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            alpha = 0.85f,
                        )
                    }
                }
                // Clips are numbered as in the Sound choice; the one the sound comes from is highlighted.
                videoCells(collage).forEachIndexed { n, cellIndex ->
                    val rect = rects.getOrNull(cellIndex) ?: return@forEachIndexed
                    val radius = 11.dp.toPx()
                    val center = Offset(rect.left + 6.dp.toPx() + radius, rect.top + 6.dp.toPx() + radius)
                    drawCircle(
                        color = if (cellIndex == collage.soundCell) outline else Color.Black.copy(alpha = 0.6f),
                        radius = radius,
                        center = center,
                    )
                    val label = textMeasurer.measure("${n + 1}", badgeText)
                    drawText(label, topLeft = center - Offset(label.size.width / 2f, label.size.height / 2f))
                }
                val rect = selectedCell?.let { rects.getOrNull(it) }
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

/** Fill/Fit, moving the photo to the next cell in any direction, and zoom. */
@Composable
private fun CellControls(
    cell: CollageCell,
    canMove: (Direction) -> Boolean,
    onChange: (CollageCell) -> Unit,
    onMove: (Direction) -> Unit,
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
        Spacer(Modifier.width(4.dp))
        listOf(
            Direction.LEFT to Icons.AutoMirrored.Filled.ArrowBack,
            Direction.UP to Icons.Default.ArrowUpward,
            Direction.DOWN to Icons.Default.ArrowDownward,
            Direction.RIGHT to Icons.AutoMirrored.Filled.ArrowForward,
        ).forEach { (direction, icon) ->
            IconButton(
                onClick = { onMove(direction) },
                enabled = canMove(direction),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(icon, contentDescription = "Move ${direction.name.lowercase()}")
            }
        }
    }
    if (cell.fit == CellFit.FILL) {
        AdjustmentSlider("Zoom", cell.zoom, 1f, 3f) { onChange(cell.copy(zoom = it)) }
    }
}

/** Indices of the cells holding video clips, in cell order: clip 1, clip 2, … */
private fun videoCells(collage: Collage): List<Int> = collage.cells.indices.filter { collage.cells[it].isVideo }

/** What shorter clips do, and which clip the sound comes from. */
@Composable
private fun VideoOptions(collage: Collage, onChange: (Collage) -> Unit) {
    Text("Video", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Text("When a shorter clip ends", style = MaterialTheme.typography.labelMedium)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        ShortClips.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = collage.shortClips == mode,
                onClick = { onChange(collage.copy(shortClips = mode)) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = ShortClips.entries.size),
                label = { Text(if (mode == ShortClips.FREEZE) "Freeze last frame" else "Loop") },
            )
        }
    }
    Text("Sound", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = collage.soundCell == null,
            onClick = { onChange(collage.copy(soundCell = null)) },
            label = { Text("Off") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        videoCells(collage).forEachIndexed { n, cellIndex ->
            FilterChip(
                selected = collage.soundCell == cellIndex,
                onClick = { onChange(collage.copy(soundCell = cellIndex)) },
                label = { Text("Clip ${n + 1}") },
                leadingIcon = if (collage.soundCell == cellIndex) {
                    { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
    }
    Text(
        "The numbers on the preview show which clip is which.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
