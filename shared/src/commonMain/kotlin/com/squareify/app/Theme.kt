package com.squareify.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Follows dark mode; on the phone with Material You colours taken from the wallpaper (Android 12+). */
@Composable
fun SquareifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = platformColorScheme(isSystemInDarkTheme()), content = content)
}
