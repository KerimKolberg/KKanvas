package com.squareify.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

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

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LOOKS, null) ?: return
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) saved += array.getJSONObject(i).toLook()
        } catch (e: Exception) {
            Log.w(TAG, "could not read saved looks", e)
        }
    }

    /** Adds [look], replacing a saved look with the same name. */
    fun add(context: Context, look: Look) {
        val existing = saved.indexOfFirst { it.name.equals(look.name, ignoreCase = true) }
        if (existing >= 0) saved[existing] = look else saved += look
        persist(context)
    }

    fun remove(context: Context, look: Look) {
        saved.remove(look)
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        saved.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_LOOKS, array.toString()) }
    }

    private fun Look.toJson() = adjustments.toJson(JSONObject().put("name", name))

    private fun JSONObject.toLook() = Look(getString("name"), adjustmentsFromJson(this))
}
