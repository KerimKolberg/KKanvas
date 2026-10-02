package com.squareify.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved projects and looks: the files the phone app has written so far keep loading the same. */
class JsonCodecTest {
    private val photo = mediaUri("content://media/picker/0/com.android.providers.media.photopicker/media/1000000033")

    /** Written by the phone app before the move to shared code (Android's org.json). */
    private val phoneFile = """{"version":1,"items":[{"id":"6f1c2a9e-0b7d-4c51-9a43-2f8e1d5b7c10","uri":"content:\/\/media\/picker\/0\/com.android.providers.media.photopicker\/media\/1000000033","isVideo":false,"name":"IMG_20260901_101500.jpg","settings":{"format":"PORTRAIT","paddingStyle":"BLUR","bgColor":-1,"bgColor2":-14800581,"gradientDirection":"DIAGONAL","blurStrength":0.3333333432674408,"border":{"margin":0.20000000298023224,"cornerRadius":0,"shadow":0.4000000059604645,"frame":"POLAROID","shape":"RECTANGLE"},"adjustments":{"brightness":1,"saturation":1.100000023841858,"sharpness":0,"grain":0,"contrast":1.0499999523162842,"warmth":-0.30000001192092896,"fade":0,"vignette":0.5},"texture":"DUST","text":{"text":"Hello \/ world","font":"ELEGANT","size":0.3499999940395355,"color":-1,"backdrop":"BOX","alignment":"CENTER","position":0.10000000149011612},"video":{"trimStartMs":500,"trimEndMs":2500,"muted":false,"speed":2,"boomerang":true},"watermark":{"enabled":true,"mark":"LOGO","corner":"TOP_LEFT","size":0.30000001192092896,"opacity":0.699999988079071,"color":-13255239}},"output":"content:\/\/media\/external\/images\/media\/9","outputs":[],"isRendered":true,"originalTrashed":false}]}"""

    @Test
    fun aFileFromThePhoneLoadsAsBefore() {
        val item = ProjectStore.decode(phoneFile).single()
        assertEquals("6f1c2a9e-0b7d-4c51-9a43-2f8e1d5b7c10", item.id)
        assertEquals(photo, item.sourceUri)
        assertEquals("IMG_20260901_101500.jpg", item.displayName)
        assertEquals(mediaUri("content://media/external/images/media/9"), item.outputUri)
        assertTrue(item.isRendered)
        assertEquals(
            FrameSettings(
                format = FrameFormat.PORTRAIT,
                paddingStyle = PaddingStyle.BLUR,
                bgColor = 0xFFFFFFFF.toInt(),
                bgColor2 = 0xFF1E293B.toInt(),
                gradientDirection = GradientDirection.DIAGONAL,
                blurStrength = 1f / 3,
                border = Border(margin = 0.2f, shadow = 0.4f, frame = FrameStyle.POLAROID),
                adjustments = Adjustments(saturation = 1.1f, contrast = 1.05f, warmth = -0.3f, vignette = 0.5f),
                texture = Texture.DUST,
                text = TextOverlay("Hello / world", font = TextFont.ELEGANT, size = 0.35f, backdrop = TextBackdrop.BOX, position = 0.1f),
                video = VideoEdit(trimStartMs = 500, trimEndMs = 2500, speed = 2f, boomerang = true),
                watermark = Watermark(enabled = true, mark = WatermarkMark.LOGO, corner = WatermarkCorner.TOP_LEFT, color = Watermark.TEAL),
            ),
            item.settings,
        )
    }

    @Test
    fun everythingComesBackFromANewFile() {
        val items = listOf(
            MediaItem(
                sourceUri = photo,
                isVideo = true,
                displayName = "collage_1",
                settings = FrameSettings(text = TextOverlay("Hi"), video = VideoEdit(muted = true)),
                collage = Collage(
                    layout = CollageLayout.BIG_LEFT,
                    cells = listOf(
                        CollageCell(photo, "a", null, fit = CellFit.FIT, zoom = 1.5f, panX = 0.2f, panY = -0.4f),
                        CollageCell(photo, "b", null, isVideo = true, focusX = 0.7f, focusY = 0.3f, panned = true, shape = PhotoShape.TORN),
                        CollageCell(photo, "c", null, adjustments = Adjustments(grain = 0.3f)),
                    ),
                    spacing = 0.5f,
                    shortClips = ShortClips.LOOP,
                    soundCell = 1,
                ),
            ),
            MediaItem(
                sourceUri = photo,
                isVideo = false,
                displayName = "pano.jpg",
                panorama = Panorama(slides = 5, fit = CellFit.FIT, position = 0.3f),
                outputUris = listOf(mediaUri("content://media/external/images/media/10"), mediaUri("content://media/external/images/media/11")),
            ),
            MediaItem(
                sourceUri = photo,
                isVideo = false,
                displayName = "carousel_1",
                warning = "No sound",
                carousel = Carousel(
                    slides = 3,
                    photos = listOf(
                        CarouselPhoto(photo, "a", null, 1.5f, Placement(1f, 0.4f, 0.9f, 12f)),
                        CarouselPhoto(photo, "b", null, 0.75f, Placement(2.5f, 0.5f, 0.6f), PhotoShape.ARCH, Adjustments(warmth = 0.4f), crop = 1f, framed = true),
                    ),
                    stickers = listOf(CarouselSticker(StickerKind.TAPE, 0xFF00FFFF.toInt(), Placement(1.4f, 0.2f, 0.45f, -12f))),
                ),
            ),
        )
        assertEquals(items, ProjectStore.decode(ProjectStore.encode(items)))
    }

    @Test
    fun anOlderFileWithoutNewerSettingsStillLoads() {
        val item = ProjectStore.decode("""{"items":[{"id":"x","uri":"$photo","name":"old.jpg","settings":{"format":"STORY","bgColor":-16777216}}]}""").single()
        assertEquals(FrameFormat.STORY, item.settings.format)
        assertEquals(0xFF000000.toInt(), item.settings.bgColor)
        assertEquals(Adjustments(), item.settings.adjustments)
        assertEquals(Watermark(), item.settings.watermark)
        assertNull(item.settings.text)
    }

    @Test
    fun oneBrokenItemDoesNotCostTheOthers() {
        val items = ProjectStore.decode("""{"items":[{"name":"no address"},{"uri":"$photo","collage":{"cells":"oops"}},{"uri":"$photo"}]}""")
        assertEquals(2, items.size)
        assertNull(items[0].collage)
    }

    @Test
    fun aValueThatIsNotANumberIsLeftOut() {
        val item = MediaItem(sourceUri = photo, isVideo = false, displayName = "x", settings = FrameSettings(blurStrength = Float.NaN))
        val back = ProjectStore.decode(ProjectStore.encode(listOf(item))).single()
        assertEquals(FrameSettings.DEFAULT_BLUR_STRENGTH, back.settings.blurStrength)
    }

    @Test
    fun savedLooksComeBack() {
        val looks = listOf(Look("Mine", Adjustments(warmth = 0.25f, fade = 0.1f)), Look("B&W \"strong\"", Adjustments(saturation = 0f)))
        assertEquals(looks, LooksStore.decode(LooksStore.encode(looks)))
        // As the phone saved them.
        assertEquals(
            listOf(Look("Mine", Adjustments(warmth = 0.25f))),
            LooksStore.decode("""[{"name":"Mine","brightness":1,"saturation":1,"sharpness":0,"grain":0,"contrast":1,"warmth":0.25,"fade":0,"vignette":0}]"""),
        )
    }
}
