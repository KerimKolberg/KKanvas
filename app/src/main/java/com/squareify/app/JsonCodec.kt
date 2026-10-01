package com.squareify.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/*
 * Plain JSON for everything worth keeping between launches. Every field is read with a default,
 * so files written by an older version still load after new settings are added.
 */

internal fun JSONObject.float(key: String, default: Float): Float = optDouble(key, default.toDouble()).toFloat()

private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T): T =
    optString(key).let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

private fun JSONObject.uri(key: String): Uri? = optString(key).takeIf { it.isNotEmpty() }?.let(Uri::parse)

private fun JSONObject.floatOrNull(key: String): Float? = if (has(key) && !isNull(key)) getDouble(key).toFloat() else null

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }

// Adjustments are stored flat, so a saved look is just these fields plus a name.

internal fun Adjustments.toJson(into: JSONObject = JSONObject()): JSONObject = into.apply {
    put("brightness", brightness.toDouble())
    put("saturation", saturation.toDouble())
    put("sharpness", sharpness.toDouble())
    put("grain", grain.toDouble())
    put("contrast", contrast.toDouble())
    put("warmth", warmth.toDouble())
    put("fade", fade.toDouble())
    put("vignette", vignette.toDouble())
}

internal fun adjustmentsFromJson(o: JSONObject): Adjustments {
    val d = Adjustments()
    return Adjustments(
        brightness = o.float("brightness", d.brightness),
        saturation = o.float("saturation", d.saturation),
        sharpness = o.float("sharpness", d.sharpness),
        grain = o.float("grain", d.grain),
        contrast = o.float("contrast", d.contrast),
        warmth = o.float("warmth", d.warmth),
        fade = o.float("fade", d.fade),
        vignette = o.float("vignette", d.vignette),
    )
}

internal fun FrameSettings.toJson(): JSONObject = JSONObject().apply {
    put("format", format.name)
    put("paddingStyle", paddingStyle.name)
    put("bgColor", bgColor)
    put("bgColor2", bgColor2)
    put("gradientDirection", gradientDirection.name)
    put("blurStrength", blurStrength.toDouble())
    put("border", JSONObject().apply {
        put("margin", border.margin.toDouble())
        put("cornerRadius", border.cornerRadius.toDouble())
        put("shadow", border.shadow.toDouble())
        put("frame", border.frame.name)
        put("shape", border.shape.name)
    })
    put("adjustments", adjustments.toJson())
    put("texture", texture.name)
    text?.let { t ->
        put("text", JSONObject().apply {
            put("text", t.text)
            put("font", t.font.name)
            put("size", t.size.toDouble())
            put("color", t.color)
            put("backdrop", t.backdrop.name)
            put("alignment", t.alignment.name)
            put("position", t.position.toDouble())
        })
    }
    put("video", JSONObject().apply {
        put("trimStartMs", video.trimStartMs)
        video.trimEndMs?.let { put("trimEndMs", it) }
        put("muted", video.muted)
        put("speed", video.speed.toDouble())
        put("boomerang", video.boomerang)
    })
    put("watermark", JSONObject().apply {
        put("enabled", watermark.enabled)
        put("mark", watermark.mark.name)
        put("corner", watermark.corner.name)
        put("size", watermark.size.toDouble())
        put("opacity", watermark.opacity.toDouble())
        put("color", watermark.color)
    })
}

internal fun frameSettingsFromJson(o: JSONObject): FrameSettings {
    val d = FrameSettings()
    val border = o.optJSONObject("border")
    val text = o.optJSONObject("text")
    val video = o.optJSONObject("video")
    val watermark = o.optJSONObject("watermark")
    return FrameSettings(
        format = o.enum("format", d.format),
        paddingStyle = o.enum("paddingStyle", d.paddingStyle),
        bgColor = o.optInt("bgColor", d.bgColor),
        bgColor2 = o.optInt("bgColor2", d.bgColor2),
        gradientDirection = o.enum("gradientDirection", d.gradientDirection),
        blurStrength = o.float("blurStrength", d.blurStrength),
        border = if (border == null) d.border else Border(
            margin = border.float("margin", 0f),
            cornerRadius = border.float("cornerRadius", 0f),
            shadow = border.float("shadow", 0f),
            frame = border.enum("frame", FrameStyle.NONE),
            shape = border.enum("shape", PhotoShape.RECTANGLE),
        ),
        adjustments = o.optJSONObject("adjustments")?.let(::adjustmentsFromJson) ?: d.adjustments,
        texture = o.enum("texture", d.texture),
        text = text?.let { t ->
            val dt = TextOverlay()
            TextOverlay(
                text = t.optString("text"),
                font = t.enum("font", dt.font),
                size = t.float("size", dt.size),
                color = t.optInt("color", dt.color),
                backdrop = t.enum("backdrop", dt.backdrop),
                alignment = t.enum("alignment", dt.alignment),
                position = t.float("position", dt.position),
            )
        },
        video = if (video == null) d.video else VideoEdit(
            trimStartMs = video.optLong("trimStartMs", 0L),
            trimEndMs = if (video.has("trimEndMs")) video.getLong("trimEndMs") else null,
            muted = video.optBoolean("muted", false),
            speed = video.float("speed", 1f),
            boomerang = video.optBoolean("boomerang", false),
        ),
        watermark = if (watermark == null) d.watermark else Watermark(
            enabled = watermark.optBoolean("enabled", false),
            mark = watermark.enum("mark", d.watermark.mark),
            corner = watermark.enum("corner", d.watermark.corner),
            size = watermark.float("size", d.watermark.size),
            opacity = watermark.float("opacity", d.watermark.opacity),
            color = watermark.optInt("color", d.watermark.color),
        ),
    )
}

internal fun Collage.toJson(): JSONObject = JSONObject().apply {
    put("layout", layout.name)
    put("spacing", spacing.toDouble())
    put("shortClips", shortClips.name)
    soundCell?.let { put("soundCell", it) }
    put("cells", JSONArray().apply {
        cells.forEach { c ->
            put(JSONObject().apply {
                put("uri", c.sourceUri.toString())
                put("name", c.displayName)
                put("isVideo", c.isVideo)
                put("fit", c.fit.name)
                put("zoom", c.zoom.toDouble())
                put("panX", c.panX.toDouble())
                put("panY", c.panY.toDouble())
                c.focusX?.let { put("focusX", it.toDouble()) }
                c.focusY?.let { put("focusY", it.toDouble()) }
                put("panned", c.panned)
                put("shape", c.shape.name)
                put("adjustments", c.adjustments.toJson())
            })
        }
    })
}

internal fun collageFromJson(o: JSONObject): Collage? {
    val cells = o.optJSONArray("cells").mapObjects { c ->
        CollageCell(
            sourceUri = c.uri("uri") ?: Uri.EMPTY,
            displayName = c.optString("name"),
            preview = null,
            isVideo = c.optBoolean("isVideo"),
            fit = c.enum("fit", CellFit.FILL),
            zoom = c.float("zoom", 1f),
            panX = c.float("panX", 0f),
            panY = c.float("panY", 0f),
            focusX = c.floatOrNull("focusX"),
            focusY = c.floatOrNull("focusY"),
            panned = c.optBoolean("panned"),
            shape = c.enum("shape", PhotoShape.RECTANGLE),
            adjustments = c.optJSONObject("adjustments")?.let(::adjustmentsFromJson) ?: Adjustments(),
        )
    }
    if (cells.isEmpty()) return null
    return Collage(
        layout = o.enum("layout", CollageLayout.forCount(cells.size).first()),
        cells = cells,
        spacing = o.float("spacing", Collage.DEFAULT_SPACING),
        shortClips = o.enum("shortClips", ShortClips.FREEZE),
        soundCell = if (o.has("soundCell")) o.getInt("soundCell") else null,
    )
}

internal fun Panorama.toJson(): JSONObject = JSONObject().apply {
    put("slides", slides)
    put("fit", fit.name)
    put("position", position.toDouble())
}

internal fun panoramaFromJson(o: JSONObject) = Panorama(
    slides = o.optInt("slides", Panorama.MIN_SLIDES),
    fit = o.enum("fit", CellFit.FILL),
    position = o.float("position", 0f),
)

internal fun Carousel.toJson(): JSONObject = JSONObject().apply {
    put("slides", slides)
    put("stickers", JSONArray().apply {
        stickers.forEach { s ->
            put(JSONObject().apply {
                put("kind", s.kind.name)
                put("color", s.color)
                put("x", s.placement.x.toDouble())
                put("y", s.placement.y.toDouble())
                put("width", s.placement.width.toDouble())
                put("rotation", s.placement.rotation.toDouble())
            })
        }
    })
    put("photos", JSONArray().apply {
        photos.forEach { p ->
            put(JSONObject().apply {
                put("uri", p.sourceUri.toString())
                put("name", p.displayName)
                put("aspect", p.aspect.toDouble())
                put("x", p.placement.x.toDouble())
                put("y", p.placement.y.toDouble())
                put("width", p.placement.width.toDouble())
                put("rotation", p.placement.rotation.toDouble())
                put("shape", p.shape.name)
                put("adjustments", p.adjustments.toJson())
                p.crop?.let { put("crop", it.toDouble()) }
                put("framed", p.framed)
            })
        }
    })
}

internal fun carouselFromJson(o: JSONObject): Carousel? {
    val photos = o.optJSONArray("photos").mapObjects { p ->
        CarouselPhoto(
            sourceUri = p.uri("uri") ?: Uri.EMPTY,
            displayName = p.optString("name"),
            preview = null,
            aspect = p.float("aspect", 1f),
            placement = Placement(p.float("x", 0.5f), p.float("y", 0.5f), p.float("width", 0.8f), p.float("rotation", 0f)),
            shape = p.enum("shape", PhotoShape.RECTANGLE),
            adjustments = p.optJSONObject("adjustments")?.let(::adjustmentsFromJson) ?: Adjustments(),
            crop = p.floatOrNull("crop"),
            framed = p.optBoolean("framed"),
        )
    }
    if (photos.isEmpty()) return null
    val stickers = o.optJSONArray("stickers").mapObjects { s ->
        val kind = s.enum("kind", StickerKind.HEART)
        CarouselSticker(
            kind = kind,
            color = s.optInt("color", kind.defaultColor),
            placement = Placement(s.float("x", 0.5f), s.float("y", 0.5f), s.float("width", 0.3f), s.float("rotation", 0f)),
        )
    }
    return Carousel(o.optInt("slides", Carousel.MIN_SLIDES), photos, stickers)
}

/** An item as saved: what it is and how it's edited, not its pictures or what it's doing right now. */
internal fun MediaItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uri", sourceUri.toString())
    put("isVideo", isVideo)
    put("name", displayName)
    put("settings", settings.toJson())
    outputUri?.let { put("output", it.toString()) }
    put("outputs", JSONArray().apply { outputUris.forEach { put(it.toString()) } })
    put("isRendered", isRendered)
    warning?.let { put("warning", it) }
    put("originalTrashed", originalTrashed)
    collage?.let { put("collage", it.toJson()) }
    panorama?.let { put("panorama", it.toJson()) }
    carousel?.let { put("carousel", it.toJson()) }
}

internal fun mediaItemFromJson(o: JSONObject): MediaItem? {
    val uri = o.uri("uri") ?: return null
    val outputs = o.optJSONArray("outputs")
    return MediaItem(
        id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
        sourceUri = uri,
        isVideo = o.optBoolean("isVideo"),
        displayName = o.optString("name", "media"),
        settings = o.optJSONObject("settings")?.let(::frameSettingsFromJson) ?: FrameSettings(),
        outputUri = o.uri("output"),
        outputUris = if (outputs == null) emptyList() else (0 until outputs.length()).map { Uri.parse(outputs.getString(it)) },
        isRendered = o.optBoolean("isRendered"),
        warning = o.optString("warning").ifEmpty { null },
        originalTrashed = o.optBoolean("originalTrashed"),
        collage = o.optJSONObject("collage")?.let(::collageFromJson),
        panorama = o.optJSONObject("panorama")?.let(::panoramaFromJson),
        carousel = o.optJSONObject("carousel")?.let(::carouselFromJson),
    )
}
