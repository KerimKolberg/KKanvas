package com.squareify.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.abs

class CarouselTemplateTest {
    private val portrait = 1.25f // 4:5 slides
    private val mixed = List(45) { i -> listOf(1.5f, 0.75f, 1f, 1.33f, 0.8f)[i % 5] }

    @Test
    fun manyPhotosFitOnFewSlides() {
        assertEquals(5, CarouselTemplate.DENSE_GRID.slidesFor(45))
        assertEquals(5, CarouselTemplate.CONTACT_SHEET.slidesFor(100))
        assertTrue(CarouselTemplate.CONTACT_SHEET.fits(100))
        // 4 per slide can't take 100 photos within Instagram's 20 slides.
        assertFalse(CarouselTemplate.CLEAN_GRID.fits(100))
        assertFalse(CarouselTemplate.SPREAD.fits(21))
    }

    @Test
    fun defaultsPickFewerSlidesForMorePhotos() {
        assertEquals(CarouselTemplate.SPREAD, defaultTemplate(8))
        assertEquals(CarouselTemplate.CLEAN_GRID, defaultTemplate(30))
        assertEquals(CarouselTemplate.DENSE_GRID, defaultTemplate(90))
        assertEquals(CarouselTemplate.CONTACT_SHEET, defaultTemplate(100))
    }

    @Test
    fun everyPhotoGetsASlotOnItsSlides() {
        CarouselTemplate.entries.filter { it.fits(mixed.size) }.forEach { template ->
            val layout = arrangeTemplate(template, mixed, portrait)
            assertEquals(mixed.size, layout.slots.size, template.name)
            assertEquals(mixed.indices.toSet(), layout.order.toSet(), template.name)
            layout.slots.forEach { slot ->
                assertTrue(slot.placement.x > 0f && slot.placement.x < layout.slides, "${template.name} x=${slot.placement.x}")
                assertTrue(slot.placement.y > 0f && slot.placement.y < 1f, "${template.name} y=${slot.placement.y}")
            }
        }
    }

    @Test
    fun gridCellsAreEvenAndTheLastRowIsCentred() {
        // 9 photos, 4 per slide: slides 1-2 full, one photo alone on slide 3.
        val layout = arrangeTemplate(CarouselTemplate.CLEAN_GRID, List(9) { 1f }, portrait)
        assertEquals(3, layout.slides)
        val widths = layout.slots.map { it.placement.width }.toSet()
        assertEquals(1, widths.size)
        // Cells are cropped to their own shape, taller than wide on a 4:5 slide.
        assertTrue(layout.slots.first().crop!! < 1f)
        assertEquals(2.5f, layout.slots.last().placement.x, 1e-4f)
    }

    @Test
    fun galleryRowsKeepWholePhotosWithEvenEdges() {
        val layout = arrangeTemplate(CarouselTemplate.GALLERY_ROWS, mixed.take(6), portrait)
        assertEquals(Carousel.MIN_SLIDES, layout.slides)
        assertTrue(layout.slots.all { it.crop == null })
        // Group by slide and row (same centre y): every row starts and ends at the same place on its slide.
        val rows = layout.slots.indices.groupBy { layout.slots[it].placement.x.toInt() to (layout.slots[it].placement.y * 1000).toInt() }
        val edges = rows.map { (key, row) ->
            val boxes = row.map { placementBox(layout.slots[it].placement, mixed[it], portrait) }
            boxes.minOf { it.left } - key.first to boxes.maxOf { it.right } - key.first
        }
        rows.keys.map { it.first }.distinct().forEach { slide ->
            val onSlide = rows.keys.withIndex().filter { it.value.first == slide }.map { edges[it.index] }
            onSlide.forEach { (left, right) ->
                assertEquals(onSlide.first().first, left, 0.01f)
                assertEquals(onSlide.first().second, right, 0.01f)
            }
        }
        // With a good split both slides fill their width: the usual margin on both sides.
        edges.forEach { (left, right) -> assertEquals(0.06f, left, 0.02f); assertEquals(0.94f, right, 0.02f) }
    }

    @Test
    fun heroIsTheWidestPhotoAcrossTheFirstSeam() {
        val aspects = listOf(1f, 0.8f, 2.4f, 1.33f, 0.75f)
        val layout = arrangeTemplate(CarouselTemplate.HERO, aspects, portrait)
        assertEquals(2, layout.order.first())
        assertEquals(1f, layout.slots.first().placement.x, 1e-4f)
        assertTrue(layout.slots.first().placement.width > 1.5f)
        // Everything else from slide 3 on.
        assertTrue(layout.slots.drop(1).all { it.placement.x > 2f })
        assertEquals(3, layout.slides)
    }

    @Test
    fun polaroidsArePrintsHeldByTape() {
        val layout = arrangeTemplate(CarouselTemplate.POLAROIDS, List(5) { 1.33f }, portrait)
        assertEquals(2, layout.slides)
        assertTrue(layout.slots.all { it.framed && it.crop == 1f })
        assertEquals(5, layout.stickers.size)
        assertTrue(layout.stickers.all { it.kind == StickerKind.TAPE })
        // Prints lean, alternately.
        assertTrue(layout.slots.map { it.placement.rotation }.any { it < 0f } && layout.slots.any { it.placement.rotation > 0f })
    }

    @Test
    fun featureAlternatesTheBigPhoto() {
        val layout = arrangeTemplate(CarouselTemplate.FEATURE, List(8) { 1f }, portrait)
        val bigOnSlide1 = layout.slots[0].placement
        val bigOnSlide2 = layout.slots[4].placement
        assertTrue(bigOnSlide1.y < 0.5f && bigOnSlide2.y > 0.5f)
        assertTrue(abs(bigOnSlide1.width - (1 - 0.12f)) < 1e-4f)
    }
}
