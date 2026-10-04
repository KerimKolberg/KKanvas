package com.squareify.app

import kotlin.math.abs
import androidx.compose.material3.FilterChip
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.layout.BoxWithConstraints
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squareify.app.processing.CarouselRenderer
import com.squareify.app.processing.PhotoProcessor
import com.squareify.app.processing.StickerRenderer
import com.squareify.app.processing.rotateAround
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * The carousel canvas: photos placed freely across slides, dragged, pinched to resize and turned
 * with two fingers; they snap to slide edges and middles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarouselEditor(
    initialCarousel: Carousel,
    initialSettings: FrameSettings,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onApply: (Carousel, FrameSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember {
        EditHistory(initialCarousel to initialSettings.copy(format = initialSettings.format.takeIf { it in Panorama.FORMATS } ?: FrameFormat.PORTRAIT))
    }
    var carousel by history.part({ it.first }, { state, c -> state.copy(first = c) })
    var settings by history.part({ it.second }, { state, s -> state.copy(second = s) })
    var selected by remember { mutableStateOf<Int?>(null) }
    var guides by remember { mutableStateOf<Snapped?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val heightUnits = slideHeightUnits(settings.format)

    // Indices count photos first, then stickers.
    fun update(index: Int, placement: Placement) {
        carousel = carousel.withLayerPlacement(index, placement)
    }
    var choosingSticker by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<CarouselTemplate?>(null) }

    // More photos, added in the middle of the slide most in need of one.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickLimit(Carousel.MAX_PHOTOS))) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val size = carouselPreviewSize(carousel.photos.size + uris.size)
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { loadCarouselPhoto(context, it, size) } }
            var photos = carousel.photos
            added.take(Carousel.MAX_PHOTOS - photos.size).forEach { photo ->
                val counts = (0 until carousel.slides).map { s -> photos.count { slideOf(it.placement, carousel.slides) == s } }
                val slide = counts.indexOf(counts.min())
                photos = photos + photo.copy(placement = fitSlidePlacement(slide, photo.aspect, heightUnits).let { it.copy(width = it.width * 0.8f) })
            }
            carousel = carousel.copy(photos = photos)
            loading = false
        }
    }

    EditorFrame(
        title = if (isNew) "New carousel" else "Edit carousel",
        history = history,
        unsaved = isNew || history.canUndo,
        what = if (isNew) "new carousel" else "changes",
        onSave = {
            onApply(carousel, settings)
            onDismiss()
        },
        onClose = onDismiss,
    ) {
        Spacer(Modifier.height(8.dp))

        CarouselCanvas(
            carousel = carousel,
            settings = settings,
            selected = selected,
            guides = guides,
            onSelect = { selected = it },
            onPlace = { index, snapped ->
                update(index, snapped.placement)
                guides = snapped
            },
            onGestureEnd = { guides = null },
        )
        Text(
            "Drag photos and stickers anywhere, across the seams. Two fingers resize and turn them. " +
                "Swipe an empty spot to scroll.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        TemplatePicker(
            aspects = carousel.photos.map { it.aspect },
            heightUnits = heightUnits,
            selected = template,
            onPick = { picked ->
                // Photos and look change together: one undo step.
                carousel = applyTemplate(picked, carousel, heightUnits)
                settings = templateSettings(picked, settings)
                template = picked
                selected = null
            },
        )

        val index = selected?.takeIf { it < carousel.photos.size }
        if (index != null) {
            PhotoActions(
                onFitSlide = {
                    val photo = carousel.photos[index]
                    update(index, fitSlidePlacement(slideOf(photo.placement, carousel.slides), photo.boxAspect, heightUnits))
                },
                onStraighten = { update(index, carousel.photos[index].placement.copy(rotation = 0f)) },
                onFront = {
                    val photos = carousel.photos.toMutableList()
                    photos.add(photos.removeAt(index))
                    carousel = carousel.copy(photos = photos)
                    selected = photos.lastIndex
                },
                onBack = {
                    val photos = carousel.photos.toMutableList()
                    photos.add(0, photos.removeAt(index))
                    carousel = carousel.copy(photos = photos)
                    selected = 0
                },
                onRemove = {
                    carousel = carousel.copy(photos = carousel.photos.filterIndexed { i, _ -> i != index })
                    selected = null
                },
            )
            val photo = carousel.photos[index]
            CropChoices(photo) { changed ->
                carousel = carousel.copy(photos = carousel.photos.toMutableList().also { it[index] = changed })
            }
            if (!photo.framed) {
                ShapeChips(selected = photo.shape, onSelect = { shape ->
                    carousel = carousel.copy(photos = carousel.photos.toMutableList().also { it[index] = photo.copy(shape = shape) })
                })
            }
            PhotoAdjustments(photo.adjustments) { adjustments ->
                carousel = carousel.copy(photos = carousel.photos.toMutableList().also { it[index] = photo.copy(adjustments = adjustments) })
            }
        }

        val stickerIndex = selected?.let { it - carousel.photos.size }?.takeIf { it in carousel.stickers.indices }
        if (stickerIndex != null) {
            val sticker = carousel.stickers[stickerIndex]
            fun change(updated: CarouselSticker) {
                carousel = carousel.copy(stickers = carousel.stickers.toMutableList().also { it[stickerIndex] = updated })
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { change(sticker.copy(placement = sticker.placement.copy(rotation = 0f))) }) { Text("Straighten") }
                TextButton(onClick = {
                    val copy = sticker.copy(placement = sticker.placement.copy(x = sticker.placement.x + 0.1f, y = (sticker.placement.y + 0.06f).coerceAtMost(0.95f)))
                    carousel = carousel.copy(stickers = carousel.stickers + copy)
                    selected = carousel.photos.size + carousel.stickers.lastIndex
                }) { Text("Duplicate") }
                TextButton(onClick = {
                    carousel = carousel.copy(stickers = carousel.stickers.filterIndexed { i, _ -> i != stickerIndex })
                    selected = null
                }) { Text("Remove") }
            }
            Text("Sticker color", style = MaterialTheme.typography.labelMedium)
            ColorChoices(selected = sticker.color, onSelect = { change(sticker.copy(color = it)) })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Slides", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { carousel = carousel.copy(slides = carousel.slides - 1) },
                enabled = carousel.slides > Carousel.MIN_SLIDES,
            ) { Icon(Icons.Default.Remove, contentDescription = "Fewer slides") }
            Text(
                "${carousel.slides}",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp),
            )
            FilledTonalIconButton(
                onClick = { carousel = carousel.copy(slides = carousel.slides + 1) },
                enabled = carousel.slides < Carousel.MAX_SLIDES,
            ) { Icon(Icons.Default.Add, contentDescription = "More slides") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !loading && carousel.photos.size < Carousel.MAX_PHOTOS,
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (loading) "Adding…" else "Add photos")
            }
            TextButton(
                onClick = {
                    val placements = spreadPlacements(carousel.photos.map { it.boxAspect }, carousel.slides, heightUnits)
                    carousel = carousel.copy(photos = carousel.photos.zip(placements) { p, place -> p.copy(placement = place) })
                },
                enabled = carousel.photos.isNotEmpty(),
            ) { Text("Spread out evenly") }
        }
        TextButton(onClick = { choosingSticker = !choosingSticker }) {
            Icon(Icons.Default.EmojiEmotions, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (choosingSticker) "Hide stickers" else "Add sticker")
        }
        if (choosingSticker) {
            StickerPalette { kind ->
                // Onto the slide of whatever is selected, else the first.
                val slide = selected?.let { carousel.layers().getOrNull(it) }?.let { slideOf(it.first, carousel.slides) } ?: 0
                val width = when (kind) {
                    StickerKind.TAPE -> 0.45f
                    StickerKind.UNDERLINE -> 0.6f
                    StickerKind.ARROW, StickerKind.CIRCLE -> 0.45f
                    else -> 0.25f
                }
                val tilt = if (kind == StickerKind.TAPE) -12f else 0f
                carousel = carousel.copy(
                    stickers = carousel.stickers + CarouselSticker(kind, kind.defaultColor, Placement(slide + 0.5f, 0.5f, width, tilt)),
                )
                selected = carousel.photos.size + carousel.stickers.lastIndex
                choosingSticker = false
            }
        }

        Spacer(Modifier.height(8.dp))
        FormatSelector(selected = settings.format, onSelect = { settings = settings.copy(format = it) }, formats = Panorama.FORMATS)
        StyleControls(
            settings = settings,
            onChange = { settings = it },
            sample = carousel.photos.firstNotNullOfOrNull { it.preview },
            showText = true,
            showFrameStyles = false,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { previewing = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Preview the swipe")
        }
        Button(
            onClick = {
                onApply(carousel, settings)
                onDismiss()
            },
            enabled = carousel.photos.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(if (isNew) "Create ${carousel.slides} slides" else "Apply")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (previewing) {
        val shown = carousel
        val style = settings
        val sources = shown.photos.map { it.preview }
        SwipePreviewDialog(
            slides = shown.slides,
            aspect = 1f / slideHeightUnits(style.format),
            renderSlide = { i ->
                val width = SWIPE_PREVIEW_WIDTH
                CarouselRenderer.renderSlide(shown, sources, style, i, width, (width * slideHeightUnits(style.format)).toInt())
            },
            warnings = carouselWarnings(shown.slides, shown.shapes(), slideHeightUnits(style.format)),
            onDismiss = { previewing = false },
        )
    }
}

/** The slides side by side, with the photos on top; scrolls sideways when wider than the screen. */
@Composable
private fun CarouselCanvas(
    carousel: Carousel,
    settings: FrameSettings,
    selected: Int?,
    guides: Snapped?,
    onSelect: (Int?) -> Unit,
    onPlace: (Int, Snapped) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val canvasHeight = 260.dp
    val heightUnits = slideHeightUnits(settings.format)
    val slideWidth = canvasHeight / heightUnits
    val canvasWidth = slideWidth * carousel.slides

    // Background, and text + logo, drawn as they'll be saved; the photos on top are live.
    var background by remember { mutableStateOf<Bitmap?>(null) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    val first = carousel.photos.firstOrNull()?.preview
    val pxHeight = with(density) { canvasHeight.roundToPx() }
    val pxWidth = (pxHeight / heightUnits * carousel.slides).toInt().coerceAtLeast(1)
    LaunchedEffect(first, settings.copy(adjustments = Adjustments(), text = null, watermark = Watermark()), pxWidth, pxHeight) {
        delay(30)
        background = withContext(Dispatchers.Default) { PhotoProcessor.background(first, settings, pxWidth, pxHeight) }
    }
    LaunchedEffect(settings.text, settings.watermark, pxWidth, pxHeight) {
        delay(30)
        overlay = withContext(Dispatchers.Default) { PhotoProcessor.captionAndWatermark(settings, pxWidth, pxHeight) }
    }

    val currentCarousel by rememberUpdatedState(carousel)
    val currentHeightUnits by rememberUpdatedState(heightUnits)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnPlace by rememberUpdatedState(onPlace)
    val currentOnGestureEnd by rememberUpdatedState(onGestureEnd)
    val accent = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .size(canvasWidth, canvasHeight)
                .clipToBounds()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = currentCarousel
                        val unit = size.width.toFloat() / c.slides
                        val units = currentHeightUnits
                        val layers = c.layers()
                        val hit = carouselPhotoAt(layers, units, down.position.x / unit, down.position.y / unit)
                        currentOnSelect(hit)
                        // On an empty spot the touch is left alone, so the canvas can scroll.
                        if (hit == null) return@awaitEachGesture
                        down.consume()
                        val aspect = layers[hit].second
                        // Where the photo would be without snapping, so it follows the finger past snap points.
                        var raw = layers[hit].first
                        var travel = Offset.Zero
                        var moving = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                                val pan = event.calculatePan()
                                travel += pan
                                // A tap shouldn't nudge the photo: wait for a real move or a second finger.
                                if (!moving && event.changes.size < 2 && travel.getDistance() < viewConfiguration.touchSlop) {
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                moving = true
                                raw = raw.copy(
                                    x = raw.x + pan.x / unit,
                                    y = raw.y + pan.y / size.height,
                                    width = (raw.width * event.calculateZoom()).coerceIn(0.08f, c.slides.toFloat()),
                                    rotation = raw.rotation + event.calculateRotation(),
                                )
                                currentOnPlace(hit, snapPlacement(raw, aspect, units, c.slides, SNAP_DISTANCE.toPx() / unit))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } finally {
                            currentOnGestureEnd()
                        }
                    }
                },
        ) {
            background?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            carousel.photos.forEach { photo ->
                val preview = photo.preview
                if (preview != null) {
                    val w = slideWidth * photo.placement.width
                    val h = w / photo.boxAspect
                    val left = slideWidth * photo.placement.x - w / 2
                    val top = canvasHeight * photo.placement.y - h / 2
                    CarouselPhotoView(
                        photo = photo,
                        preview = preview,
                        settings = settings,
                        modifier = Modifier
                            .absoluteOffset(left, top)
                            .size(w, h),
                    )
                }
            }
            // Stickers, drawn live on top of the photos.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    carousel.stickers.forEach { sticker ->
                        val rect = CarouselRenderer.placementRect(sticker.placement, sticker.kind.aspect, carousel.slides, size.width, size.height)
                        canvas.save()
                        canvas.rotateAround(sticker.placement.rotation, rect.center.x, rect.center.y)
                        StickerRenderer.draw(canvas, sticker.kind, sticker.color, rect)
                        canvas.restore()
                    }
                }
            }
            overlay?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = size.width / carousel.slides
                val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                // Seams between slides, and slide numbers.
                for (s in 0 until carousel.slides) {
                    if (s > 0) {
                        drawLine(Color.White.copy(alpha = 0.85f), Offset(s * unit, 0f), Offset(s * unit, size.height), 1.5.dp.toPx(), pathEffect = dash)
                    }
                    val label = textMeasurer.measure("${s + 1}", numberStyle)
                    val x = s * unit + 6.dp.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.5f),
                        topLeft = Offset(x - 3.dp.toPx(), 4.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(label.size.width + 6.dp.toPx(), label.size.height.toFloat()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                    drawText(label, topLeft = Offset(x, 4.dp.toPx()))
                }
                // Lines the dragged photo snapped to.
                guides?.guidesX?.forEach { gx -> drawLine(accent, Offset(gx * unit, 0f), Offset(gx * unit, size.height), 2.dp.toPx()) }
                guides?.guidesY?.forEach { gy -> drawLine(accent, Offset(0f, gy * size.height), Offset(size.width, gy * size.height), 2.dp.toPx()) }
                // The selected photo's or sticker's outline, turned with it.
                val layer = selected?.let { carousel.layers().getOrNull(it) }
                if (layer != null) {
                    val (placement, aspect) = layer
                    val w = placement.width * unit
                    val h = w / aspect
                    val center = Offset(placement.x * unit, placement.y * size.height)
                    rotate(placement.rotation, center) {
                        drawRect(
                            accent,
                            topLeft = Offset(center.x - w / 2, center.y - h / 2),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

/** What can be done to the selected photo. */
@Composable
private fun PhotoActions(
    onFitSlide: () -> Unit,
    onStraighten: () -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onFitSlide) { Text("Fit its slide") }
        TextButton(onClick = onStraighten) { Text("Straighten") }
        TextButton(onClick = onFront) { Text("To front") }
        TextButton(onClick = onBack) { Text("To back") }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/** How close (on screen) a photo's edge or middle must come to a guide line to snap onto it. */
private val SNAP_DISTANCE: Dp = 8.dp

/** One tile per sticker, drawn by the same code as the saved slides. */
@Composable
private fun StickerPalette(onPick: (StickerKind) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StickerKind.entries.forEach { kind ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(kind) }
                    .padding(4.dp),
            ) {
                // A mid grey shows white and coloured stickers alike, in light and dark mode.
                Canvas(
                    modifier = Modifier
                        .size(width = 60.dp, height = 42.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF6B7280)),
                ) {
                    val w = min(size.width * 0.86f, size.height * 0.8f * kind.aspect)
                    val h = w / kind.aspect
                    val rect = androidx.compose.ui.geometry.Rect(center.x - w / 2, center.y - h / 2, center.x + w / 2, center.y + h / 2)
                    drawIntoCanvas { StickerRenderer.draw(it, kind, kind.defaultColor, rect) }
                }
                Text(kind.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** One photo on the live canvas: cropped as it will be, in its shape or its white print. */
@Composable
private fun CarouselPhotoView(photo: CarouselPhoto, preview: Bitmap, settings: FrameSettings, modifier: Modifier) {
    val image = remember(preview) { preview.asImageBitmap() }
    val crop = remember(preview, photo.crop) { centerCrop(preview.width, preview.height, photo.crop) }
    val painter = remember(image, crop) {
        BitmapPainter(
            image,
            IntOffset(crop.left.roundToInt(), crop.top.roundToInt()),
            IntSize(crop.width.roundToInt().coerceAtLeast(1), crop.height.roundToInt().coerceAtLeast(1)),
        )
    }
    val filter = remember(photo.adjustments, settings.adjustments) {
        PhotoProcessor.colorFilter(photo.adjustments, settings.adjustments)
    }
    if (photo.framed) {
        BoxWithConstraints(
            modifier = modifier
                .graphicsLayer {
                    rotationZ = photo.placement.rotation
                    shadowElevation = (2.dp + 8.dp * settings.border.shadow).toPx()
                    shape = RoundedCornerShape(2.dp)
                    clip = true
                }
                .background(Color(0xFFFCFBF7)),
        ) {
            val photoWidth = maxWidth / (1 + 2 * PRINT_SIDE)
            val side = photoWidth * PRINT_SIDE
            Image(
                painter,
                contentDescription = photo.displayName,
                contentScale = ContentScale.FillBounds,
                colorFilter = filter,
                modifier = Modifier
                    .absoluteOffset(side, side)
                    .size(photoWidth, photoWidth / photo.shownAspect),
            )
        }
    } else {
        Image(
            painter,
            contentDescription = photo.displayName,
            contentScale = ContentScale.FillBounds,
            colorFilter = filter,
            modifier = modifier.graphicsLayer {
                rotationZ = photo.placement.rotation
                shadowElevation = settings.border.shadow * 12.dp.toPx()
                shape = photoOutline(photo.shape) { size -> min(size.width, size.height) * settings.border.cornerRadius * 0.5f }
                clip = true
            },
        )
    }
}

/**
 * One tile per template, each a little drawing of the first two slides laid out with these very
 * photos, and how many slides it takes. Templates that would need more than 20 slides are greyed.
 */
@Composable
private fun TemplatePicker(aspects: List<Float>, heightUnits: Float, selected: CarouselTemplate?, onPick: (CarouselTemplate) -> Unit) {
    val layouts = remember(aspects, heightUnits) {
        CarouselTemplate.entries.associateWith { if (it.fits(aspects.size)) arrangeTemplate(it, aspects, heightUnits) else null }
    }
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val paper = MaterialTheme.colorScheme.surfaceVariant
    Text("Templates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarouselTemplate.entries.forEach { template ->
            val layout = layouts[template]
            val isSelected = template == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = layout != null) { onPick(template) }
                    .graphicsLayer { alpha = if (layout != null) 1f else 0.4f }
                    .padding(4.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .width(88.dp)
                        .height(44.dp * heightUnits),
                ) {
                    val slide = size.width / 2
                    for (s in 0 until 2) {
                        drawRoundRect(
                            color = if (isSelected) accent.copy(alpha = 0.18f) else paper,
                            topLeft = Offset(s * slide + 1.dp.toPx(), 0f),
                            size = androidx.compose.ui.geometry.Size(slide - 2.dp.toPx(), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        )
                    }
                    layout?.slots?.forEachIndexed { k, slot ->
                        if (slot.placement.x - slot.placement.width / 2 >= 2f) return@forEachIndexed
                        val box = placementBox(slot.placement, slot.boxAspect(aspects[layout.order[k]]), heightUnits)
                        val center = Offset((box.left + box.right) / 2 * slide, (box.top + box.bottom) / 2 / heightUnits * size.height)
                        rotate(slot.placement.rotation, center) {
                            drawRect(
                                color = if (slot.framed) Color.White else if (isSelected) accent else ink.copy(alpha = 0.7f),
                                topLeft = Offset(box.left * slide, box.top / heightUnits * size.height),
                                size = androidx.compose.ui.geometry.Size(box.width * slide, box.height / heightUnits * size.height),
                            )
                        }
                    }
                }
                Text(template.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(
                    if (layout != null) "${layout.slides} slides" else "Too many photos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How a photo is cut and whether it sits in a white print. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropChoices(photo: CarouselPhoto, onChange: (CarouselPhoto) -> Unit) {
    val crops = listOf<Pair<String, Float?>>("Whole" to null, "Square" to 1f, "4:5" to 0.8f, "3:2" to 1.5f)
    Text("Crop", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        crops.forEach { (label, aspect) ->
            FilterChip(
                selected = photo.crop == aspect || (aspect != null && photo.crop?.let { abs(it - aspect) < 0.01f } == true),
                onClick = { onChange(photo.copy(crop = aspect)) },
                label = { Text(label) },
            )
        }
        FilterChip(selected = photo.framed, onClick = { onChange(photo.copy(framed = !photo.framed)) }, label = { Text("Print border") })
    }
}
