package com.squareify.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Ready-made carousel designs that arrange any number of photos neatly, many to a slide when
 * there are lots of them. Pure geometry on the photos' aspect ratios, so it's unit-testable.
 */
enum class CarouselTemplate(val label: String, val description: String) {
    SPREAD("One per slide", "Each photo on its own slide"),
    CLEAN_GRID("Clean grid", "4 per slide, neat cells"),
    DENSE_GRID("Dense grid", "9 per slide"),
    CONTACT_SHEET("Contact sheet", "20 per slide on black, like film"),
    GALLERY_ROWS("Gallery rows", "Whole photos in even rows"),
    FEATURE("Feature + three", "One big, three small"),
    POLAROIDS("Polaroid wall", "Tilted prints, taped on paper"),
    HERO("Seamless hero", "The widest across slides 1-2, then grids"),
}

/** Where one photo goes: its placement, the shape it's cropped to (null = whole photo), and a white print border. */
data class TemplateSlot(val placement: Placement, val crop: Float? = null, val framed: Boolean = false)

/**
 * A template applied: how many slides, a slot per photo in [order] (indices into the photos
 * given), and stickers to add.
 */
data class TemplateLayout(
    val slides: Int,
    val order: List<Int>,
    val slots: List<TemplateSlot>,
    val stickers: List<CarouselSticker> = emptyList(),
)

/** Space around each slide's content, and between photos, in slide widths. */
private const val MARGIN = 0.06f
private const val GAP = 0.03f

/** A print's white border: sides and top, and the deep bottom, as fractions of the photo's width. */
const val PRINT_SIDE = 0.06f
const val PRINT_BOTTOM = 0.24f

/** Width / height of a photo of [photoAspect] inside a white print border. */
fun printAspect(photoAspect: Float): Float =
    (1 + 2 * PRINT_SIDE) / (1 / photoAspect + PRINT_SIDE + PRINT_BOTTOM)

/** How many photos fit on one slide (null: as many as suit the photos). */
fun CarouselTemplate.perSlide(): Int? = when (this) {
    CarouselTemplate.SPREAD -> 1
    CarouselTemplate.CLEAN_GRID -> 4
    CarouselTemplate.DENSE_GRID -> 9
    CarouselTemplate.CONTACT_SHEET -> 20
    CarouselTemplate.GALLERY_ROWS -> 6
    CarouselTemplate.FEATURE -> 4
    CarouselTemplate.POLAROIDS -> 3
    CarouselTemplate.HERO -> null
}

/** Slides needed for [count] photos; may be more than Instagram allows (see [fits]). */
fun CarouselTemplate.slidesFor(count: Int): Int = when (this) {
    CarouselTemplate.HERO -> 2 + ceil(max(0, count - 1) / 4f).toInt()
    else -> ceil(count / perSlide()!!.toFloat()).toInt()
}.coerceAtLeast(Carousel.MIN_SLIDES)

/** Whether [count] photos fit within Instagram's slide limit with this template. */
fun CarouselTemplate.fits(count: Int): Boolean = count >= 1 && slidesFor(count) <= Carousel.MAX_SLIDES

/**
 * The template to start with for [count] freshly picked photos: the roomiest one that keeps the
 * carousel to 10 slides or fewer (easy to swipe through); the editor offers the rest.
 */
fun defaultTemplate(count: Int): CarouselTemplate = when {
    count <= 10 -> CarouselTemplate.SPREAD
    count <= 40 -> CarouselTemplate.CLEAN_GRID
    count <= 90 -> CarouselTemplate.DENSE_GRID
    else -> CarouselTemplate.CONTACT_SHEET
}

/** Arranges photos of the given [aspects] (width / height) on slides [heightUnits] slide widths high. */
fun arrangeTemplate(template: CarouselTemplate, aspects: List<Float>, heightUnits: Float): TemplateLayout {
    val n = aspects.size
    val slides = template.slidesFor(n)
    val identity = aspects.indices.toList()
    return when (template) {
        CarouselTemplate.SPREAD -> TemplateLayout(
            slides,
            identity,
            spreadPlacements(aspects, slides, heightUnits).map { TemplateSlot(it) },
        )
        CarouselTemplate.CLEAN_GRID -> TemplateLayout(slides, identity, grid(n, 0, 2, 2, heightUnits, MARGIN, GAP))
        CarouselTemplate.DENSE_GRID -> TemplateLayout(slides, identity, grid(n, 0, 3, 3, heightUnits, MARGIN, GAP))
        CarouselTemplate.CONTACT_SHEET -> TemplateLayout(slides, identity, grid(n, 0, 4, 5, heightUnits, 0.05f, 0.02f))
        CarouselTemplate.GALLERY_ROWS -> TemplateLayout(slides, identity, galleryRows(aspects, slides, heightUnits))
        CarouselTemplate.FEATURE -> TemplateLayout(slides, identity, feature(n, heightUnits))
        CarouselTemplate.POLAROIDS -> polaroids(aspects, slides, heightUnits)
        CarouselTemplate.HERO -> hero(aspects, heightUnits)
    }
}

/**
 * [count] photos in [columns] x [rows] cells per slide from slide [firstSlide], left to right and
 * top to bottom; a short last row is centred.
 */
private fun grid(count: Int, firstSlide: Int, columns: Int, rows: Int, heightUnits: Float, margin: Float, gap: Float): List<TemplateSlot> {
    val perSlide = columns * rows
    val cellW = (1 - 2 * margin - (columns - 1) * gap) / columns
    val cellH = (heightUnits - 2 * margin - (rows - 1) * gap) / rows
    return (0 until count).map { i ->
        val slide = firstSlide + i / perSlide
        val k = i % perSlide
        val row = k / columns
        // How many share this row: fewer on the last row of the last slide.
        val inRow = min(columns, count - (i - k + row * columns))
        val col = k % columns
        val rowWidth = inRow * cellW + (inRow - 1) * gap
        val x = slide + (1 - rowWidth) / 2 + col * (cellW + gap) + cellW / 2
        val y = (margin + row * (cellH + gap) + cellH / 2) / heightUnits
        TemplateSlot(Placement(x, y, cellW), crop = cellW / cellH)
    }
}

/**
 * Whole photos in rows of equal height per row, each row exactly as wide as the slide's content:
 * the gallery-wall look. The row count that best fills the slide wins; the block is centred.
 */
private fun galleryRows(aspects: List<Float>, slides: Int, heightUnits: Float): List<TemplateSlot> {
    val slots = arrayOfNulls<TemplateSlot>(aspects.size)
    val perSlide = ceil(aspects.size / slides.toFloat()).toInt()
    val width = 1 - 2 * MARGIN
    val height = heightUnits - 2 * MARGIN
    for (slide in 0 until slides) {
        val indices = (slide * perSlide until min(aspects.size, (slide + 1) * perSlide)).toList()
        if (indices.isEmpty()) continue
        // Try every way of breaking the photos into rows (in order); keep the one whose height
        // comes closest to the slide's, preferring a little short over too tall.
        var best: List<List<Int>> = listOf(indices)
        var bestScore = Float.MAX_VALUE
        for (rows in rowSplits(indices)) {
            val total = rows.sumOf { row -> rowHeight(row.map { aspects[it] }, width).toDouble() }.toFloat() + (rows.size - 1) * GAP
            val score = abs(total - height) + if (total > height) (total - height) * 0.5f else 0f
            if (score < bestScore) {
                bestScore = score
                best = rows
            }
        }
        val heights = best.map { row -> rowHeight(row.map { aspects[it] }, width) }
        val total = heights.sum() + (best.size - 1) * GAP
        // Too tall: shrink everything to fit, keeping the edges even.
        val scale = min(1f, height / total)
        var y = (heightUnits - total * scale) / 2
        best.forEachIndexed { r, row ->
            val h = heights[r] * scale
            val rowWidth = row.sumOf { (aspects[it] * h).toDouble() }.toFloat() + (row.size - 1) * GAP * scale
            var x = slide + (1 - rowWidth) / 2
            row.forEach { i ->
                val w = aspects[i] * h
                slots[i] = TemplateSlot(Placement(x + w / 2, (y + h / 2) / heightUnits, w))
                x += w + GAP * scale
            }
            y += h + GAP * scale
        }
    }
    return slots.map { it!! }
}

/** The height that makes photos of [aspects] fill [width] side by side, gaps included. */
private fun rowHeight(aspects: List<Float>, width: Float): Float = (width - (aspects.size - 1) * GAP) / aspects.sum()

/** Every way to cut [items] into consecutive rows (up to 4096 for 13 items; slides hold far fewer). */
private fun rowSplits(items: List<Int>): Sequence<List<List<Int>>> = sequence {
    val cuts = items.size - 1
    for (mask in 0 until (1 shl min(cuts, 12))) {
        val rows = mutableListOf<List<Int>>()
        var start = 0
        for (i in 0 until cuts) {
            if (mask and (1 shl i) != 0) {
                rows += items.subList(start, i + 1)
                start = i + 1
            }
        }
        rows += items.subList(start, items.size)
        yield(rows)
    }
}

/** Per slide one big photo and three small ones in a row, the big one alternating top and bottom. */
private fun feature(count: Int, heightUnits: Float): List<TemplateSlot> {
    val width = 1 - 2 * MARGIN
    val height = heightUnits - 2 * MARGIN
    val bigH = (height - GAP) * 0.62f
    val smallH = height - GAP - bigH
    val smallW = (width - 2 * GAP) / 3
    return (0 until count).map { i ->
        val slide = i / 4
        val k = i % 4
        val bigOnTop = slide % 2 == 0
        val bigTop = if (bigOnTop) MARGIN else MARGIN + smallH + GAP
        val smallTop = if (bigOnTop) MARGIN + bigH + GAP else MARGIN
        if (k == 0) {
            TemplateSlot(Placement(slide + 0.5f, (bigTop + bigH / 2) / heightUnits, width), crop = width / bigH)
        } else {
            // Fewer than three small ones on the last slide: centre them.
            val smalls = min(3, count - slide * 4 - 1)
            val rowWidth = smalls * smallW + (smalls - 1) * GAP
            val x = slide + (1 - rowWidth) / 2 + (k - 1) * (smallW + GAP) + smallW / 2
            TemplateSlot(Placement(x, (smallTop + smallH / 2) / heightUnits, smallW), crop = smallW / smallH)
        }
    }
}

/** Three square prints per slide, scattered and tilted, each held by a strip of tape. */
private fun polaroids(aspects: List<Float>, slides: Int, heightUnits: Float): TemplateLayout {
    // Where the three prints sit on a slide (x, y as fractions) and how they lean.
    val spots = listOf(Triple(0.33f, 0.3f, -6f), Triple(0.68f, 0.5f, 5f), Triple(0.38f, 0.74f, -3f))
    // Sized for 4:5 slides; smaller on square ones so the three still fit.
    val printWidth = 0.46f * min(1f, heightUnits / 1.25f)
    val slots = aspects.indices.map { i ->
        val (x, y, tilt) = spots[i % 3]
        TemplateSlot(Placement(i / 3 + x, y, printWidth, tilt), crop = 1f, framed = true)
    }
    val stickers = slots.map { slot ->
        val p = slot.placement
        val printHeight = p.width / printAspect(1f)
        // Tape across the top edge of each print, leaning a little the other way.
        CarouselSticker(
            StickerKind.TAPE,
            StickerKind.TAPE.defaultColor,
            Placement(p.x, p.y - printHeight / 2 / heightUnits * 0.92f, printWidth * 0.45f, -p.rotation * 1.6f),
        )
    }
    return TemplateLayout(slides, aspects.indices.toList(), slots, stickers)
}

/** The widest photo across the first two slides, the rest in a clean grid after it. */
private fun hero(aspects: List<Float>, heightUnits: Float): TemplateLayout {
    val widest = aspects.indices.maxBy { aspects[it] }
    val order = listOf(widest) + aspects.indices.filter { it != widest }
    val heroWidth = 2 - 2 * MARGIN
    val heroHeight = heightUnits - 2 * MARGIN
    val heroSlot = TemplateSlot(Placement(1f, 0.5f, heroWidth), crop = heroWidth / heroHeight)
    val rest = grid(order.size - 1, firstSlide = 2, columns = 2, rows = 2, heightUnits = heightUnits, margin = MARGIN, gap = GAP)
    return TemplateLayout(CarouselTemplate.HERO.slidesFor(aspects.size), order, listOf(heroSlot) + rest)
}

/** [carousel]'s photos rearranged by [template]; its stickers stay, or the template's come if it has none. */
fun applyTemplate(template: CarouselTemplate, carousel: Carousel, heightUnits: Float): Carousel {
    val layout = arrangeTemplate(template, carousel.photos.map { it.aspect }, heightUnits)
    val photos = layout.order.mapIndexed { k, i ->
        val slot = layout.slots[k]
        carousel.photos[i].copy(placement = slot.placement, crop = slot.crop, framed = slot.framed)
    }
    val stickers = if (carousel.stickers.isEmpty()) layout.stickers else carousel.stickers
    return Carousel(layout.slides.coerceIn(Carousel.MIN_SLIDES, Carousel.MAX_SLIDES), photos, stickers)
}

/** The look a template comes with (background, texture); most keep the current one. */
fun templateSettings(template: CarouselTemplate, settings: FrameSettings): FrameSettings = when (template) {
    CarouselTemplate.CONTACT_SHEET -> settings.copy(
        paddingStyle = PaddingStyle.SOLID,
        bgColor = 0xFF141414.toInt(),
        border = settings.border.copy(cornerRadius = 0f, shadow = 0f),
    )
    CarouselTemplate.POLAROIDS -> settings.copy(
        paddingStyle = PaddingStyle.SOLID,
        bgColor = 0xFFEFE7DA.toInt(),
        texture = Texture.PAPER,
        border = settings.border.copy(cornerRadius = 0f, shadow = 0.35f),
    )
    else -> settings
}

/** Width / height of the box a slot takes for a photo of [photoAspect], print border included. */
fun TemplateSlot.boxAspect(photoAspect: Float): Float {
    val shown = crop ?: photoAspect
    return if (framed) printAspect(shown) else shown
}
