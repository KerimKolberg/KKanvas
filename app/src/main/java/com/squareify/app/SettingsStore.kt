package com.squareify.app

import android.content.Context
import androidx.core.content.edit

/** Remembers the settings used for newly added media between app launches. */
object SettingsStore {
    private const val PREFS_NAME = "frame_settings"
    private const val KEY_FORMAT = "format"
    private const val KEY_PADDING_STYLE = "paddingStyle"
    private const val KEY_BG_COLOR = "bgColor"
    private const val KEY_BG_COLOR_2 = "bgColor2"
    private const val KEY_BLUR_STRENGTH = "blurStrength"
    private const val KEY_MARGIN = "margin"
    private const val KEY_CORNER_RADIUS = "cornerRadius"
    private const val KEY_SHADOW = "shadow"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_SHARPNESS = "sharpness"
    private const val KEY_GRAIN = "grain"

    fun load(context: Context): FrameSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = FrameSettings()
        return FrameSettings(
            format = prefs.getString(KEY_FORMAT, null)
                ?.let { name -> FrameFormat.entries.firstOrNull { it.name == name } }
                ?: defaults.format,
            paddingStyle = prefs.getString(KEY_PADDING_STYLE, null)
                ?.let { name -> PaddingStyle.entries.firstOrNull { it.name == name } }
                ?: defaults.paddingStyle,
            bgColor = prefs.getInt(KEY_BG_COLOR, defaults.bgColor),
            bgColor2 = prefs.getInt(KEY_BG_COLOR_2, defaults.bgColor2),
            blurStrength = prefs.getFloat(KEY_BLUR_STRENGTH, defaults.blurStrength),
            border = Border(
                margin = prefs.getFloat(KEY_MARGIN, defaults.border.margin),
                cornerRadius = prefs.getFloat(KEY_CORNER_RADIUS, defaults.border.cornerRadius),
                shadow = prefs.getFloat(KEY_SHADOW, defaults.border.shadow),
            ),
            adjustments = Adjustments(
                brightness = prefs.getFloat(KEY_BRIGHTNESS, defaults.adjustments.brightness),
                saturation = prefs.getFloat(KEY_SATURATION, defaults.adjustments.saturation),
                sharpness = prefs.getFloat(KEY_SHARPNESS, defaults.adjustments.sharpness),
                grain = prefs.getFloat(KEY_GRAIN, defaults.adjustments.grain),
            ),
        )
    }

    fun save(context: Context, settings: FrameSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_FORMAT, settings.format.name)
            putString(KEY_PADDING_STYLE, settings.paddingStyle.name)
            putInt(KEY_BG_COLOR, settings.bgColor)
            putInt(KEY_BG_COLOR_2, settings.bgColor2)
            putFloat(KEY_BLUR_STRENGTH, settings.blurStrength)
            putFloat(KEY_MARGIN, settings.border.margin)
            putFloat(KEY_CORNER_RADIUS, settings.border.cornerRadius)
            putFloat(KEY_SHADOW, settings.border.shadow)
            putFloat(KEY_BRIGHTNESS, settings.adjustments.brightness)
            putFloat(KEY_SATURATION, settings.adjustments.saturation)
            putFloat(KEY_SHARPNESS, settings.adjustments.sharpness)
            putFloat(KEY_GRAIN, settings.adjustments.grain)
        }
    }
}
