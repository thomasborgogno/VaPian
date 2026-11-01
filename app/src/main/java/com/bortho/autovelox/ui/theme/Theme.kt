package com.bortho.autovelox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00263F),
    background = Color(0xFF000000), // AMOLED black: also the least distracting at night
    surface = Color(0xFF0E1114),
    onBackground = Color(0xFFF2F4F7),
    onSurface = Color(0xFFF2F4F7),
    error = Color(0xFFFF6B5E),
)

private val Light = lightColorScheme()

/** Brand colors shared by the radar and the alert states. */
object VaPianColors {
    val Safe = Color(0xFF4CD964)
    val Warning = Color(0xFFFFC53D)
    val Danger = Color(0xFFFF453A)
    val UserCamera = Color(0xFF5AB0FF)
}

/** The dashboard is always dark; the phone follows the system. */
@Composable
fun VaPianTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (forceDark || isSystemInDarkTheme()) Dark else Light, content = content)
}
