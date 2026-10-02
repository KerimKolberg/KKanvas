package com.squareify.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Plain JSON for everything worth keeping between launches. Every field is read with a default,
 * so files written by an older version still load after new settings are added. The format is
 * the one the phone app has always written (with Android's org.json), and still reads those files.
 */

/** Reads a JSON object from text; throws if it isn't one. */
fun parseJsonObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.float(key: String, default: Float): Float =
    primitive(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.toFloat() ?: default

private fun JsonObject.floatOrNull(key: String): Float? =
    primitive(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.toFloat()

private fun JsonObject.longOrNull(key: String): Long? =
    primitive(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toDoubleOrNull()?.toLong() }

private fun JsonObject.int(key: String, default: Int): Int = longOrNull(key)?.toInt() ?: default

private fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean = primitive(key)?.booleanOrNull ?: default

private fun JsonObject.string(key: String, default: String = ""): String = primitive(key)?.content ?: default

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T =
    string(key).let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

private fun JsonObject.uri(key: String): MediaUri? = string(key).takeIf { it.isNotEmpty() }?.let(::mediaUri)

private fun <T> JsonArray?.mapObjects(transform: (JsonObject) -> T): List<T> =
    this?.mapNotNull { (it as? JsonObject)?.let(transform) } ?: emptyList()

/** JSON has no NaN or infinity: such a value is left out (and read back as the default), not written. */
private fun JsonObjectBuilder.putFloat(key: String, value: Float) {
    if (value.isFinite()) put(key, JsonPrimitive(value))
}

// Adjustments are stored flat, so a saved look is just these fields plus a name.

fun Adjustments.toJson(name: String? = null): JsonObject = buildJsonObject {
    name?.let { put("name", it) }
    putFloat("brightness", brightness)
    putFloat("saturation", saturation)
    putFloat("sharpness", sharpness)
    putFloat("grain", grain)
    putFloat("contrast", contrast)
    putFloat("warmth", warmth)
    putFloat("fade", fade)
    putFloat("vignette", vignette)
}

fun adjustmentsFromJson(o: JsonObject): Adjustments {
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

fun FrameSettings.toJson(): JsonObject = buildJsonObject {
    put("format", format.name)
    put("paddingStyle", paddingStyle.name)
    put("bgColor", bgColor)
    put("bgColor2", bgColor2)
    put("gradientDirection", gradientDirection.name)
    putFloat("blurStrength", blurStrength)
    put("border", buildJsonObject {
        putFloat("margin", border.margin)
        putFloat("cornerRadius", border.cornerRadius)
        putFloat("shadow", border.shadow)
        put("frame", border.frame.name)
        put("shape", border.shape.name)
    })
    put("adjustments", adjustments.toJson())
    put("texture", texture.name)
    text?.let { t ->
        put("text", buildJsonObject {
            put("text", t.text)
            put("font", t.font.name)
            putFloat("size", t.size)
            put("color", t.color)
            put("backdrop", t.backdrop.name)
            put("alignment", t.alignment.name)
            putFloat("position", t.position)
        })
    }
    put("video", buildJsonObject {
        put("trimStartMs", video.trimStartMs)
        video.trimEndMs?.let { put("trimEndMs", it) }
        put("muted", video.muted)
        putFloat("speed", video.speed)
        put("boomerang", video.boomerang)
    })
    put("watermark", buildJsonObject {
        put("enabled", watermark.enabled)
        put("mark", watermark.mark.name)
        put("corner", watermark.corner.name)
        putFloat("size", watermark.size)
        putFloat("opacity", watermark.opacity)
        put("color", watermark.color)
    })
}

fun frameSettingsFromJson(o: JsonObject): FrameSettings {
    val d = FrameSettings()
    val border = o.obj("border")
    val text = o.obj("text")
    val video = o.obj("video")
    val watermark = o.obj("watermark")
    return FrameSettings(
        format = o.enum("format", d.format),
        paddingStyle = o.enum("paddingStyle", d.paddingStyle),
        bgColor = o.int("bgColor", d.bgColor),
        bgColor2 = o.int("bgColor2", d.bgColor2),
        gradientDirection = o.enum("gradientDirection", d.gradientDirection),
        blurStrength = o.float("blurStrength", d.blurStrength),
        border = if (border == null) d.border else Border(
            margin = border.float("margin", 0f),
            cornerRadius = border.float("cornerRadius", 0f),
            shadow = border.float("shadow", 0f),
            frame = border.enum("frame", FrameStyle.NONE),
            shape = border.enum("shape", PhotoShape.RECTANGLE),
        ),
        adjustments = o.obj("adjustments")?.let(::adjustmentsFromJson) ?: d.adjustments,
        texture = o.enum("texture", d.texture),
        text = text?.let { t ->
            val dt = TextOverlay()
            TextOverlay(
                text = t.string("text"),
                font = t.enum("font", dt.font),
                size = t.float("size", dt.size),
                color = t.int("color", dt.color),
                backdrop = t.enum("backdrop", dt.backdrop),
                alignment = t.enum("alignment", dt.alignment),
                position = t.float("position", dt.position),
            )
        },
        video = if (video == null) d.video else VideoEdit(
            trimStartMs = video.longOrNull("trimStartMs") ?: 0L,
            trimEndMs = video.longOrNull("trimEndMs"),
            muted = video.boolean("muted"),
            speed = video.float("speed", 1f),
            boomerang = video.boolean("boomerang"),
        ),
        watermark = if (watermark == null) d.watermark else Watermark(
            enabled = watermark.boolean("enabled"),
            mark = watermark.enum("mark", d.watermark.mark),
            corner = watermark.enum("corner", d.watermark.corner),
            size = watermark.float("size", d.watermark.size),
            opacity = watermark.float("opacity", d.watermark.opacity),
            color = watermark.int("color", d.watermark.color),
        ),
    )
}

fun Collage.toJson(): JsonObject = buildJsonObject {
    put("layout", layout.name)
    putFloat("spacing", spacing)
    put("shortClips", shortClips.name)
    soundCell?.let { put("soundCell", it) }
    put("cells", buildJsonArray {
        cells.forEach { c ->
            add(buildJsonObject {
                put("uri", c.sourceUri.toString())
                put("name", c.displayName)
                put("isVideo", c.isVideo)
                put("fit", c.fit.name)
                putFloat("zoom", c.zoom)
                putFloat("panX", c.panX)
                putFloat("panY", c.panY)
                c.focusX?.let { putFloat("focusX", it) }
                c.focusY?.let { putFloat("focusY", it) }
                put("panned", c.panned)
                put("shape", c.shape.name)
                put("adjustments", c.adjustments.toJson())
            })
        }
    })
}

fun collageFromJson(o: JsonObject): Collage? {
    val cells = o.array("cells").mapObjects { c ->
        CollageCell(
            sourceUri = c.uri("uri") ?: emptyMediaUri,
            displayName = c.string("name"),
            preview = null,
            isVideo = c.boolean("isVideo"),
            fit = c.enum("fit", CellFit.FILL),
            zoom = c.float("zoom", 1f),
            panX = c.float("panX", 0f),
            panY = c.float("panY", 0f),
            focusX = c.floatOrNull("focusX"),
            focusY = c.floatOrNull("focusY"),
            panned = c.boolean("panned"),
            shape = c.enum("shape", PhotoShape.RECTANGLE),
            adjustments = c.obj("adjustments")?.let(::adjustmentsFromJson) ?: Adjustments(),
        )
    }
    if (cells.isEmpty()) return null
    return Collage(
        layout = o.enum("layout", CollageLayout.forCount(cells.size).first()),
        cells = cells,
        spacing = o.float("spacing", Collage.DEFAULT_SPACING),
        shortClips = o.enum("shortClips", ShortClips.FREEZE),
        soundCell = o.intOrNull("soundCell"),
    )
}

fun Panorama.toJson(): JsonObject = buildJsonObject {
    put("slides", slides)
    put("fit", fit.name)
    putFloat("position", position)
}

fun panoramaFromJson(o: JsonObject) = Panorama(
    slides = o.int("slides", Panorama.MIN_SLIDES),
    fit = o.enum("fit", CellFit.FILL),
    position = o.float("position", 0f),
)

fun Carousel.toJson(): JsonObject = buildJsonObject {
    put("slides", slides)
    put("stickers", buildJsonArray {
        stickers.forEach { s ->
            add(buildJsonObject {
                put("kind", s.kind.name)
                put("color", s.color)
                putFloat("x", s.placement.x)
                putFloat("y", s.placement.y)
                putFloat("width", s.placement.width)
                putFloat("rotation", s.placement.rotation)
            })
        }
    })
    put("photos", buildJsonArray {
        photos.forEach { p ->
            add(buildJsonObject {
                put("uri", p.sourceUri.toString())
                put("name", p.displayName)
                putFloat("aspect", p.aspect)
                putFloat("x", p.placement.x)
                putFloat("y", p.placement.y)
                putFloat("width", p.placement.width)
                putFloat("rotation", p.placement.rotation)
                put("shape", p.shape.name)
                put("adjustments", p.adjustments.toJson())
                p.crop?.let { putFloat("crop", it) }
                put("framed", p.framed)
            })
        }
    })
}

fun carouselFromJson(o: JsonObject): Carousel? {
    val photos = o.array("photos").mapObjects { p ->
        CarouselPhoto(
            sourceUri = p.uri("uri") ?: emptyMediaUri,
            displayName = p.string("name"),
            preview = null,
            aspect = p.float("aspect", 1f),
            placement = Placement(p.float("x", 0.5f), p.float("y", 0.5f), p.float("width", 0.8f), p.float("rotation", 0f)),
            shape = p.enum("shape", PhotoShape.RECTANGLE),
            adjustments = p.obj("adjustments")?.let(::adjustmentsFromJson) ?: Adjustments(),
            crop = p.floatOrNull("crop"),
            framed = p.boolean("framed"),
        )
    }
    if (photos.isEmpty()) return null
    val stickers = o.array("stickers").mapObjects { s ->
        val kind = s.enum("kind", StickerKind.HEART)
        CarouselSticker(
            kind = kind,
            color = s.int("color", kind.defaultColor),
            placement = Placement(s.float("x", 0.5f), s.float("y", 0.5f), s.float("width", 0.3f), s.float("rotation", 0f)),
        )
    }
    return Carousel(o.int("slides", Carousel.MIN_SLIDES), photos, stickers)
}

/** An item as saved: what it is and how it's edited, not its pictures or what it's doing right now. */
fun MediaItem.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("uri", sourceUri.toString())
    put("isVideo", isVideo)
    put("name", displayName)
    put("settings", settings.toJson())
    outputUri?.let { put("output", it.toString()) }
    put("outputs", buildJsonArray { outputUris.forEach { add(JsonPrimitive(it.toString())) } })
    put("isRendered", isRendered)
    warning?.let { put("warning", it) }
    put("originalTrashed", originalTrashed)
    collage?.let { put("collage", it.toJson()) }
    panorama?.let { put("panorama", it.toJson()) }
    carousel?.let { put("carousel", it.toJson()) }
}

fun mediaItemFromJson(o: JsonObject): MediaItem? {
    val uri = o.uri("uri") ?: return null
    return MediaItem(
        id = o.string("id").ifEmpty { randomId() },
        sourceUri = uri,
        isVideo = o.boolean("isVideo"),
        displayName = o.string("name", "media"),
        settings = o.obj("settings")?.let(::frameSettingsFromJson) ?: FrameSettings(),
        outputUri = o.uri("output"),
        outputUris = o.array("outputs")?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::mediaUri) } ?: emptyList(),
        isRendered = o.boolean("isRendered"),
        warning = o.string("warning").ifEmpty { null },
        originalTrashed = o.boolean("originalTrashed"),
        collage = o.obj("collage")?.let(::collageFromJson),
        panorama = o.obj("panorama")?.let(::panoramaFromJson),
        carousel = o.obj("carousel")?.let(::carouselFromJson),
    )
}
