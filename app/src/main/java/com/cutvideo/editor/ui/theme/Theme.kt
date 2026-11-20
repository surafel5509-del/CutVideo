package com.cutvideo.editor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val CutColorScheme = darkColorScheme(
    primary = CutColors.Accent,
    onPrimary = Color.White,
    primaryContainer = CutColors.AccentSecondary,
    onPrimaryContainer = Color.White,
    secondary = CutColors.AccentSecondary,
    onSecondary = Color.White,
    tertiary = CutColors.Info,
    background = CutColors.Background,
    onBackground = CutColors.TextPrimary,
    surface = CutColors.BackgroundElevated,
    onSurface = CutColors.TextPrimary,
    surfaceVariant = CutColors.Surface,
    onSurfaceVariant = CutColors.TextSecondary,
    surfaceContainer = CutColors.Surface,
    surfaceContainerHigh = CutColors.SurfaceHigh,
    surfaceContainerHighest = CutColors.SurfaceHighest,
    outline = CutColors.BorderStrong,
    outlineVariant = CutColors.Border,
    error = CutColors.Danger,
    onError = Color.White,
    scrim = CutColors.Scrim
)

private val CutShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * CutVideo is dark-only by design: the preview must sit against a neutral dark canvas so colours in
 * the video are judged accurately, and the violet accent is tuned for a dark surround.
 */
@Composable
fun CutVideoTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = CutColorScheme,
        typography = CutTypography,
        shapes = CutShapes,
        content = content
    )
}

@Suppress("unused")
@Composable
private fun isDark(): Boolean = isSystemInDarkTheme()
