package com.motionarcade.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HubColorScheme = lightColorScheme(
    primary = Color(0xFF18B8B2),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFFF6B66),
    onSecondary = Color(0xFF12263A),
    tertiary = Color(0xFFFFC857),
    background = Color(0xFFF7FBFC),
    onBackground = Color(0xFF12263A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12263A),
)

@Composable
fun MotionArcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HubColorScheme,
        content = content,
    )
}
