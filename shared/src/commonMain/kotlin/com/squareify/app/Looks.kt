package com.squareify.app

import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A named set of adjustments, applied in one tap. */
data class Look(val name: String, val adjustments: Adjustments)

val BUILT_IN_LOOKS = listOf(
    Look("Original", Adjustments()),
    Look("Warm", Adjustments(warmth = 0.5f, saturation = 1.1f, contrast = 1.05f)),
    Look("Cool", Adjustments(warmth = -0.45f, saturation = 0.95f, contrast = 1.05f)),
    Look("Vivid", Adjustments(saturation = 1.35f, contrast = 1.15f, sharpness = 0.2f)),
    Look("Film", Adjustments(warmth = 0.2f, saturation = 0.85f, contrast = 0.95f, fade = 0.35f, grain = 0.3f, vignette = 0.3f)),
    Look("Faded", Adjustments(saturation = 0.75f, contrast = 0.9f, fade = 0.6f)),
    Look("B&W", Adjustments(saturation = 0f, contrast = 1.15f, grain = 0.15f)),
    Look("Noir", Adjustments(saturation = 0f, contrast = 1.4f, brightness = 0.95f, vignette = 0.5f)),
)

/** Looks the user saved, kept between launches. Compose state, so every picker shows changes at once. */
object LooksStore {
    private const val TAG = "LooksStore"
    private const val PREFS_NAME = "looks"
    private const val KEY_LOOKS = "saved"

    val saved = mutableStateListOf<Look>()
    private var loaded = false

    fun load(context: PlatformContext) {
        if (loaded) return
        loaded = true
        val json = context.preferences(PREFS_NAME).getString(KEY_LOOKS) ?: return
        try {
            saved += decode(json)
        } catch (e: Exception) {
            logWarning(TAG, "could not read saved looks", e)
        }
    }

    /** Adds [look], replacing a saved look with the same name. */
    fun add(context: PlatformContext, look: Look) {
        val existing = saved.indexOfFirst { it.name.equals(look.name, ignoreCase = true) }
        if (existing >= 0) saved[existing] = look else saved += look
        persist(context)
    }

    fun remove(context: PlatformContext, look: Look) {
        saved.remove(look)
        persist(context)
    }

    private fun persist(context: PlatformContext) {
        context.preferences(PREFS_NAME).edit { putString(KEY_LOOKS, encode(saved)) }
    }

    fun encode(looks: List<Look>): String = JsonArray(looks.map { it.adjustments.toJson(name = it.name) }).toString()

    fun decode(json: String): List<Look> =
        (Json.parseToJsonElement(json) as JsonArray).map { element ->
            val o = element as JsonObject
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: error("a look without a name")
            Look(name, adjustmentsFromJson(o))
        }
}
