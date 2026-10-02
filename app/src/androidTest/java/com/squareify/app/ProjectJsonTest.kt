package com.squareify.app

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saved projects come back exactly as they were. Uses the JSON conversion only, so the app's
 * real saved grid on the phone is never touched.
 */
@RunWith(AndroidJUnit4::class)
class ProjectJsonTest {
    private val photo = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")
    private val clip = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/2")

    private val settings = FrameSettings(
        format = FrameFormat.PORTRAIT,
        paddingStyle = PaddingStyle.GRADIENT,
        bgColor = Color.RED,
        border = Border(margin = 0.2f, cornerRadius = 0.3f, shadow = 0.4f, frame = FrameStyle.POLAROID),
        adjustments = Adjustments(brightness = 1.1f, warmth = -0.3f, fade = 0.2f, vignette = 0.5f),
        text = TextOverlay("Hello", font = TextFont.ELEGANT, backdrop = TextBackdrop.BOX, position = 0.1f),
        video = VideoEdit(trimStartMs = 500, trimEndMs = 2500, speed = 2f, boomerang = true),
        watermark = Watermark(enabled = true, mark = WatermarkMark.LOGO, corner = WatermarkCorner.TOP_LEFT),
    )

    /** Saved and read back; then once more after Android's org.json (what the app used to save with) rewrote the file. */
    private fun roundTrip(item: MediaItem): MediaItem {
        val saved = item.toJson().toString()
        val viaOrgJson = mediaItemFromJson(parseJsonObject(JSONObject(saved).toString()))!!
        assertEquals(item, viaOrgJson)
        return mediaItemFromJson(parseJsonObject(saved))!!
    }

    @Test
    fun aPhotoWithEverySettingComesBack() {
        val item = MediaItem(
            sourceUri = photo,
            isVideo = false,
            displayName = "IMG_1.jpg",
            settings = settings,
            outputUri = Uri.parse("content://media/external/images/media/9"),
            isRendered = true,
            originalTrashed = true,
        )
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun collagesPanoramasAndCarouselsComeBack() {
        val collage = MediaItem(
            sourceUri = photo,
            isVideo = true,
            displayName = "collage_1",
            settings = settings,
            collage = Collage(
                layout = CollageLayout.BIG_LEFT,
                cells = listOf(
                    CollageCell(photo, "a", null, fit = CellFit.FIT, zoom = 1.5f, panX = 0.2f, panY = -0.4f),
                    CollageCell(clip, "b", null, isVideo = true, focusX = 0.7f, focusY = 0.3f, panned = true),
                    CollageCell(photo, "c", null),
                ),
                spacing = 0.5f,
                shortClips = ShortClips.LOOP,
                soundCell = 1,
            ),
        )
        assertEquals(collage, roundTrip(collage))

        val panorama = MediaItem(
            sourceUri = photo,
            isVideo = false,
            displayName = "pano.jpg",
            panorama = Panorama(slides = 5, fit = CellFit.FIT, position = 0.3f),
            outputUris = listOf(Uri.parse("content://media/external/images/media/10"), Uri.parse("content://media/external/images/media/11")),
        )
        assertEquals(panorama, roundTrip(panorama))

        val carousel = MediaItem(
            sourceUri = photo,
            isVideo = false,
            displayName = "carousel_1",
            carousel = Carousel(
                slides = 3,
                photos = listOf(
                    CarouselPhoto(photo, "a", null, 1.5f, Placement(1f, 0.4f, 0.9f, 12f)),
                    CarouselPhoto(clip, "b", null, 0.75f, Placement(2.5f, 0.5f, 0.6f), shape = PhotoShape.ARCH, adjustments = Adjustments(warmth = 0.4f), crop = 1f, framed = true),
                ),
                stickers = listOf(CarouselSticker(StickerKind.TAPE, Color.CYAN, Placement(1.4f, 0.2f, 0.45f, -12f))),
            ),
            settings = FrameSettings(texture = Texture.DUST, gradientDirection = GradientDirection.DIAGONAL),
        )
        assertEquals(carousel, roundTrip(carousel))
    }

    @Test
    fun anOlderFileWithoutNewerSettingsStillLoads() {
        val old = parseJsonObject("""{"id":"x","uri":"$photo","isVideo":false,"name":"old.jpg","settings":{"format":"STORY","bgColor":-16777216}}""")
        val item = mediaItemFromJson(old)!!
        assertEquals(FrameFormat.STORY, item.settings.format)
        assertEquals(Color.BLACK, item.settings.bgColor)
        assertEquals(Adjustments(), item.settings.adjustments)
        assertEquals(Watermark(), item.settings.watermark)
    }
}
