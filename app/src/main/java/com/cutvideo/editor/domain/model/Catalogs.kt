package com.cutvideo.editor.domain.model

import androidx.compose.ui.graphics.Color

/**
 * Filter catalog.
 *
 * Each preset is a *real* 3x4 colour matrix plus a small set of additional parameters (gamma, lift,
 * grain) that are consumed by:
 *  - the GLSL shader used for preview and export (`ColorMatrixGlEffect` / `filter_engine.glsl`),
 *  - the C++ software path (`filter_engine.cpp::applyFilterPreset`) used for filter cards and for
 *    devices without a usable GL context,
 *  - the filter card renderer, which runs the C++ path over a real video frame.
 *
 * Matrices are row-major 3x4, RGBA in, RGBA out, offsets in 0..1 space.
 */
data class FilterPreset(
    val id: String,
    val name: String,
    val group: String,
    val matrix: FloatArray,
    val gamma: Float = 1f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val accent: Color
) {
    override fun equals(other: Any?): Boolean = other is FilterPreset && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class EffectPreset(
    val id: String,
    val name: String,
    val group: String,
    val description: String,
    val accent: Color,
    /** Default intensity when applied from the panel. */
    val defaultIntensity: Float = 0.5f,
    /** Effects that need the second texture pass / extra uniforms are flagged for the UI. */
    val premium: Boolean = false
)

data class TransitionPreset(
    val type: TransitionType,
    val name: String,
    val description: String,
    val accent: Color
)

data class TextAnimationPreset(
    val type: ClipAnimationType,
    val name: String,
    val accent: Color
)

object FilterCatalog {
    const val NONE = "none"

    private fun m(
        a: Float, b: Float, c: Float, d: Float,
        e: Float, f: Float, g: Float, h: Float,
        i: Float, j: Float, k: Float, l: Float
    ) = floatArrayOf(a, b, c, d, e, f, g, h, i, j, k, l)

    val presets: List<FilterPreset> = listOf(
        FilterPreset(
            NONE, "Original", "Basic",
            m(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f),
            accent = Color(0xFFA1A1AA)
        ),
        FilterPreset(
            "cinematic", "Cinematic", "Cinema",
            // teal shadows / warm highlights, mild contrast
            m(1.10f, 0.02f, -0.02f, -0.012f, 0.00f, 1.02f, 0.04f, -0.004f, -0.04f, 0.03f, 1.06f, 0.018f),
            gamma = 0.96f, vignette = 0.22f, grain = 0.05f, accent = Color(0xFF0E7490)
        ),
        FilterPreset(
            "vintage", "Vintage", "Retro",
            m(0.92f, 0.11f, 0.04f, 0.03f, 0.06f, 0.90f, 0.06f, 0.02f, 0.04f, 0.09f, 0.82f, 0.01f),
            gamma = 1.06f, grain = 0.12f, vignette = 0.30f, accent = Color(0xFFB45309)
        ),
        FilterPreset(
            "bw", "Black & White", "Mono",
            m(0.299f, 0.587f, 0.114f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0.299f, 0.587f, 0.114f, 0f),
            gamma = 1.02f, accent = Color(0xFFE4E4E7)
        ),
        FilterPreset(
            "warm", "Warm", "Colour",
            m(1.10f, 0.02f, 0.00f, 0.020f, 0.01f, 1.03f, 0.00f, 0.006f, 0.00f, 0.02f, 0.92f, -0.008f),
            accent = Color(0xFFF59E0B)
        ),
        FilterPreset(
            "cool", "Cool", "Colour",
            m(0.94f, 0.01f, 0.02f, -0.010f, 0.00f, 1.01f, 0.03f, 0.002f, 0.03f, 0.03f, 1.12f, 0.014f),
            accent = Color(0xFF38BDF8)
        ),
        FilterPreset(
            "retro", "Retro 84", "Retro",
            m(1.14f, 0.05f, -0.05f, 0.010f, 0.02f, 0.98f, 0.06f, -0.010f, 0.06f, 0.00f, 0.90f, 0.030f),
            gamma = 1.10f, grain = 0.16f, vignette = 0.18f, accent = Color(0xFFDB2777)
        ),
        FilterPreset(
            "film", "Film Stock", "Cinema",
            m(1.04f, 0.01f, 0.01f, -0.020f, 0.01f, 1.00f, 0.02f, -0.014f, 0.01f, 0.02f, 0.98f, -0.010f),
            gamma = 0.98f, grain = 0.09f, vignette = 0.12f, accent = Color(0xFFCA8A04)
        ),
        FilterPreset(
            "portrait", "Portrait", "People",
            m(1.06f, 0.03f, 0.00f, 0.010f, 0.02f, 1.02f, 0.00f, 0.006f, 0.01f, 0.01f, 1.04f, 0.008f),
            gamma = 1.04f, accent = Color(0xFFF472B6)
        ),
        FilterPreset(
            "vivid", "Vivid", "Colour",
            m(1.28f, -0.12f, -0.05f, -0.010f, -0.08f, 1.26f, -0.06f, -0.010f, -0.05f, -0.10f, 1.26f, 0.000f),
            accent = Color(0xFF22C55E)
        ),
        FilterPreset(
            "dramatic", "Dramatic", "Cinema",
            m(1.34f, -0.10f, -0.08f, -0.040f, -0.06f, 1.30f, -0.08f, -0.040f, -0.08f, -0.08f, 1.32f, -0.040f),
            gamma = 0.90f, vignette = 0.34f, grain = 0.06f, accent = Color(0xFFDC2626)
        )
    )

    private val byId = presets.associateBy { it.id }

    fun preset(id: String): FilterPreset = byId[id] ?: presets.first()
    fun groups(): List<String> = presets.map { it.group }.distinct()
}

object EffectsCatalog {
    val presets: List<EffectPreset> = listOf(
        EffectPreset("none", "None", "Basic", "Clean frame, no effect", Color(0xFFA1A1AA), 0f),
        EffectPreset("grain", "Film Grain", "Texture", "Animated silver-halide grain", Color(0xFFD6D3D1)),
        EffectPreset("vignette", "Vignette", "Light", "Darkens the frame edges", Color(0xFF7C3AED)),
        EffectPreset("scanlines", "Scanlines", "Texture", "CRT line structure", Color(0xFF22D3EE)),
        EffectPreset("glitch", "Glitch", "Distort", "RGB channel tearing", Color(0xFFF43F5E), premium = true),
        EffectPreset("chroma_aberration", "Chromatic", "Distort", "Lens colour fringing", Color(0xFFA855F7)),
        EffectPreset("bloom", "Bloom", "Light", "Soft highlight glow", Color(0xFFFDE68A)),
        EffectPreset("light_leak", "Light Leak", "Light", "Warm film leak from the edge", Color(0xFFFB923C)),
        EffectPreset("pulse", "Pulse", "Motion", "Breathing exposure", Color(0xFFEC4899)),
        EffectPreset("mirror", "Mirror", "Distort", "Kaleidoscope mirror", Color(0xFF60A5FA)),
        EffectPreset("posterize", "Posterize", "Stylise", "Reduced colour steps", Color(0xFF34D399)),
        EffectPreset("duotone", "Duotone", "Stylise", "Two-colour gradient map", Color(0xFF818CF8)),
        EffectPreset("sharpen", "Sharpen", "Detail", "Edge enhancement", Color(0xFFFACC15)),
        EffectPreset("vhs", "VHS", "Retro", "Tape wobble and noise", Color(0xFFF472B6), premium = true),
        EffectPreset("snow", "Snow", "Particles", "Drifting particles", Color(0xFFE0F2FE), premium = true),
        EffectPreset("zoom_blur", "Zoom Blur", "Motion", "Radial motion blur", Color(0xFFA3E635))
    )

    private val byId = presets.associateBy { it.id }
    fun preset(id: String): EffectPreset = byId[id] ?: presets.first()
    fun groups(): List<String> = presets.map { it.group }.distinct()
    fun all(): List<EffectPreset> = presets.filter { it.id != "none" }
}

object TransitionCatalog {
    val presets: List<TransitionPreset> = listOf(
        TransitionPreset(TransitionType.FADE, "Cross Fade", "Blend both clips", Color(0xFFA855F7)),
        TransitionPreset(TransitionType.SLIDE_LEFT, "Slide", "Push the frame sideways", Color(0xFF38BDF8)),
        TransitionPreset(TransitionType.SLIDE_UP, "Lift", "Push the frame upwards", Color(0xFF22C55E)),
        TransitionPreset(TransitionType.ZOOM, "Zoom", "Punch through the cut", Color(0xFFF59E0B)),
        TransitionPreset(TransitionType.WIPE, "Wipe", "Hard edge sweep", Color(0xFFF43F5E)),
        TransitionPreset(TransitionType.CIRCLE, "Iris", "Circular reveal", Color(0xFF818CF8))
    )

    fun preset(type: TransitionType): TransitionPreset =
        presets.firstOrNull { it.type == type } ?: presets.first()
}

object AnimationCatalog {
    val presets: List<TextAnimationPreset> = listOf(
        TextAnimationPreset(ClipAnimationType.NONE, "None", Color(0xFFA1A1AA)),
        TextAnimationPreset(ClipAnimationType.FADE, "Fade", Color(0xFFA855F7)),
        TextAnimationPreset(ClipAnimationType.ZOOM_IN, "Zoom In", Color(0xFF38BDF8)),
        TextAnimationPreset(ClipAnimationType.ZOOM_OUT, "Zoom Out", Color(0xFF22C55E)),
        TextAnimationPreset(ClipAnimationType.SLIDE_LEFT, "Slide Left", Color(0xFFF59E0B)),
        TextAnimationPreset(ClipAnimationType.SLIDE_RIGHT, "Slide Right", Color(0xFFF472B6)),
        TextAnimationPreset(ClipAnimationType.SLIDE_UP, "Slide Up", Color(0xFFFACC15)),
        TextAnimationPreset(ClipAnimationType.SLIDE_DOWN, "Slide Down", Color(0xFF34D399)),
        TextAnimationPreset(ClipAnimationType.ROTATE_IN, "Rotate In", Color(0xFF818CF8)),
        TextAnimationPreset(ClipAnimationType.BLUR_IN, "Blur In", Color(0xFF94A3B8)),
        TextAnimationPreset(ClipAnimationType.POP, "Pop", Color(0xFFF43F5E)),
        TextAnimationPreset(ClipAnimationType.FLICKER, "Flicker", Color(0xFFE879F9))
    )

    fun preset(type: ClipAnimationType): TextAnimationPreset =
        presets.firstOrNull { it.type == type } ?: presets.first()
}

/** Bundled font families. Mapping to actual typefaces lives in `ui/theme/Fonts.kt`. */
object FontCatalog {
    const val DEFAULT = "sans"
    val fonts: List<Pair<String, String>> = listOf(
        DEFAULT to "Sans",
        "sans_bold" to "Sans Bold",
        "serif" to "Serif",
        "mono" to "Mono",
        "condensed" to "Condensed",
        "cursive" to "Script"
    )
}
