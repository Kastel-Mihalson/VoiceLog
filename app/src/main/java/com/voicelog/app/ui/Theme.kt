package com.voicelog.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BA6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF8A6A00),
    secondaryContainer = Color(0xFFFFEFC7),
    onSecondaryContainer = Color(0xFF3B2A00),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF14161C),
    surface = Color.White,
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF4A4F5C),
    outline = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEBEEF6),
    surfaceContainerHigh = Color(0xFFE5E9F2),
    surfaceContainerHighest = Color(0xFFDFE3EE)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4A3C8F),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFE6C766),
    secondaryContainer = Color(0xFF4A3B12),
    onSecondaryContainer = Color(0xFFFFEFC7),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF181B22),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF262A34),
    onSurfaceVariant = Color(0xFFB6BBC8),
    outline = Color(0xFF4A4F5C),
    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF14171D),
    surfaceContainer = Color(0xFF1A1D25),
    surfaceContainerHigh = Color(0xFF222630),
    surfaceContainerHighest = Color(0xFF2B303B)
)

@Composable
fun VoiceLogTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
