package com.cutvideo.editor.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** CutVideo palette — a dark, violet-accented studio look (own identity, not a copy of any editor). */
object CutColors {
    val Background = Color(0xFF101014)
    val BackgroundElevated = Color(0xFF19191F)
    val BackgroundSunken = Color(0xFF0B0B0E)
    val Surface = Color(0xFF1E1E26)
    val SurfaceHigh = Color(0xFF26262F)
    val SurfaceHighest = Color(0xFF2F2F3A)

    val Accent = Color(0xFFA855F7)
    val AccentSecondary = Color(0xFF7C3AED)
    val AccentSoft = Color(0x33A855F7)
    val AccentGlow = Color(0x66A855F7)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextTertiary = Color(0xFF71717A)

    val Divider = Color(0x14FFFFFF)
    val Border = Color(0x1FFFFFFF)
    val BorderStrong = Color(0x33FFFFFF)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFF43F5E)
    val Info = Color(0xFF38BDF8)

    val VideoTrack = Color(0xFF7C3AED)
    val AudioTrack = Color(0xFF0EA5E9)
    val TextTrack = Color(0xFFF59E0B)
    val OverlayTrack = Color(0xFF10B981)

    val Scrim = Color(0xCC0B0B0E)

    val accentGradient = Brush.linearGradient(listOf(Accent, AccentSecondary))
    val accentGradientHorizontal = Brush.horizontalGradient(listOf(Accent, AccentSecondary))
    val panelGradient = Brush.verticalGradient(listOf(Color(0xFF1F1F27), Color(0xFF16161C)))
    val glowGradient = Brush.radialGradient(
        colors = listOf(Color(0x33A855F7), Color(0x00000000)),
        radius = 900f
    )

    fun trackColor(name: String): Color = when (name) {
        "VIDEO" -> VideoTrack
        "AUDIO" -> AudioTrack
        "TEXT" -> TextTrack
        else -> OverlayTrack
    }
}
