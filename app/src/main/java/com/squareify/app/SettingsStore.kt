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
    private const val KEY_GRADIENT_DIRECTION = "gradientDirection"
    private const val KEY_BLUR_STRENGTH = "blurStrength"
    private const val KEY_MARGIN = "margin"
    private const val KEY_CORNER_RADIUS = "cornerRadius"
    private const val KEY_SHADOW = "shadow"
    private const val KEY_FRAME_STYLE = "frameStyle"
    private const val KEY_SHAPE = "photoShape"
    private const val KEY_WATERMARK = "watermark"
    private const val KEY_WATERMARK_MARK = "watermarkMark"
    private const val KEY_WATERMARK_CORNER = "watermarkCorner"
    private const val KEY_WATERMARK_SIZE = "watermarkSize"
    private const val KEY_WATERMARK_OPACITY = "watermarkOpacity"
    private const val KEY_WATERMARK_COLOR = "watermarkColor"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_SHARPNESS = "sharpness"
    private const val KEY_GRAIN = "grain"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_WARMTH = "warmth"
    private const val KEY_FADE = "fade"
    private const val KEY_VIGNETTE = "vignette"
    private const val KEY_TEXTURE = "texture"

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
            gradientDirection = prefs.getString(KEY_GRADIENT_DIRECTION, null)
                ?.let { name -> GradientDirection.entries.firstOrNull { it.name == name } }
                ?: defaults.gradientDirection,
            blurStrength = prefs.getFloat(KEY_BLUR_STRENGTH, defaults.blurStrength),
            border = Border(
                margin = prefs.getFloat(KEY_MARGIN, defaults.border.margin),
                cornerRadius = prefs.getFloat(KEY_CORNER_RADIUS, defaults.border.cornerRadius),
                shadow = prefs.getFloat(KEY_SHADOW, defaults.border.shadow),
                frame = prefs.getString(KEY_FRAME_STYLE, null)
                    ?.let { name -> FrameStyle.entries.firstOrNull { it.name == name } }
                    ?: defaults.border.frame,
                shape = prefs.getString(KEY_SHAPE, null)
                    ?.let { name -> PhotoShape.entries.firstOrNull { it.name == name } }
                    ?: defaults.border.shape,
            ),
            adjustments = Adjustments(
                brightness = prefs.getFloat(KEY_BRIGHTNESS, defaults.adjustments.brightness),
                saturation = prefs.getFloat(KEY_SATURATION, defaults.adjustments.saturation),
                sharpness = prefs.getFloat(KEY_SHARPNESS, defaults.adjustments.sharpness),
                grain = prefs.getFloat(KEY_GRAIN, defaults.adjustments.grain),
                contrast = prefs.getFloat(KEY_CONTRAST, defaults.adjustments.contrast),
                warmth = prefs.getFloat(KEY_WARMTH, defaults.adjustments.warmth),
                fade = prefs.getFloat(KEY_FADE, defaults.adjustments.fade),
                vignette = prefs.getFloat(KEY_VIGNETTE, defaults.adjustments.vignette),
            ),
            texture = prefs.getString(KEY_TEXTURE, null)
                ?.let { name -> Texture.entries.firstOrNull { it.name == name } }
                ?: defaults.texture,
            watermark = Watermark(
                enabled = prefs.getBoolean(KEY_WATERMARK, defaults.watermark.enabled),
                mark = prefs.getString(KEY_WATERMARK_MARK, null)
                    ?.let { name -> WatermarkMark.entries.firstOrNull { it.name == name } }
                    ?: defaults.watermark.mark,
                corner = prefs.getString(KEY_WATERMARK_CORNER, null)
                    ?.let { name -> WatermarkCorner.entries.firstOrNull { it.name == name } }
                    ?: defaults.watermark.corner,
                size = prefs.getFloat(KEY_WATERMARK_SIZE, defaults.watermark.size),
                opacity = prefs.getFloat(KEY_WATERMARK_OPACITY, defaults.watermark.opacity),
                color = prefs.getInt(KEY_WATERMARK_COLOR, defaults.watermark.color),
            ),
        )
    }

    fun save(context: Context, settings: FrameSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_FORMAT, settings.format.name)
            putString(KEY_PADDING_STYLE, settings.paddingStyle.name)
            putInt(KEY_BG_COLOR, settings.bgColor)
            putInt(KEY_BG_COLOR_2, settings.bgColor2)
            putString(KEY_GRADIENT_DIRECTION, settings.gradientDirection.name)
            putFloat(KEY_BLUR_STRENGTH, settings.blurStrength)
            putFloat(KEY_MARGIN, settings.border.margin)
            putFloat(KEY_CORNER_RADIUS, settings.border.cornerRadius)
            putFloat(KEY_SHADOW, settings.border.shadow)
            putString(KEY_FRAME_STYLE, settings.border.frame.name)
            putString(KEY_SHAPE, settings.border.shape.name)
            putFloat(KEY_BRIGHTNESS, settings.adjustments.brightness)
            putFloat(KEY_SATURATION, settings.adjustments.saturation)
            putFloat(KEY_SHARPNESS, settings.adjustments.sharpness)
            putFloat(KEY_GRAIN, settings.adjustments.grain)
            putFloat(KEY_CONTRAST, settings.adjustments.contrast)
            putFloat(KEY_WARMTH, settings.adjustments.warmth)
            putFloat(KEY_FADE, settings.adjustments.fade)
            putFloat(KEY_VIGNETTE, settings.adjustments.vignette)
            putString(KEY_TEXTURE, settings.texture.name)
            putBoolean(KEY_WATERMARK, settings.watermark.enabled)
            putString(KEY_WATERMARK_MARK, settings.watermark.mark.name)
            putString(KEY_WATERMARK_CORNER, settings.watermark.corner.name)
            putFloat(KEY_WATERMARK_SIZE, settings.watermark.size)
            putFloat(KEY_WATERMARK_OPACITY, settings.watermark.opacity)
            putInt(KEY_WATERMARK_COLOR, settings.watermark.color)
        }
    }
}
