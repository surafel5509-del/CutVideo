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
        ),
        // --- second wave: the looks people reach for most on short-form video -----------------
        FilterPreset(
            "teal_orange", "Teal & Orange", "Cinema",
            m(1.06f, 0.04f, -0.02f, 0.010f, 0.00f, 1.00f, 0.02f, 0.000f, -0.05f, 0.05f, 1.10f, -0.010f),
            gamma = 0.97f, vignette = 0.20f, grain = 0.04f, accent = Color(0xFF0891B2)
        ),
        FilterPreset(
            "noir", "Noir", "Mono",
            m(0.32f, 0.62f, 0.06f, -0.030f, 0.32f, 0.62f, 0.06f, -0.030f, 0.32f, 0.62f, 0.06f, -0.030f),
            gamma = 0.92f, vignette = 0.30f, grain = 0.08f, accent = Color(0xFFE4E4E7)
        ),
        FilterPreset(
            "sepia", "Sepia", "Retro",
            m(0.393f, 0.769f, 0.189f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0.272f, 0.534f, 0.131f, 0f),
            gamma = 1.00f, grain = 0.10f, vignette = 0.20f, accent = Color(0xFFA16207)
        ),
        FilterPreset(
            "pastel", "Pastel", "Soft",
            m(0.90f, 0.06f, 0.04f, 0.060f, 0.04f, 0.92f, 0.04f, 0.060f, 0.04f, 0.06f, 0.90f, 0.070f),
            gamma = 1.08f, vignette = 0.06f, accent = Color(0xFFFBCFE8)
        ),
        FilterPreset(
            "food", "Food", "People",
            m(1.18f, -0.06f, -0.02f, 0.010f, -0.04f, 1.16f, -0.02f, 0.010f, -0.04f, -0.02f, 1.10f, 0.000f),
            gamma = 1.02f, accent = Color(0xFFF97316)
        ),
        FilterPreset(
            "sky", "Sky", "Landscape",
            m(0.98f, 0.02f, 0.00f, 0.000f, 0.00f, 1.04f, 0.02f, 0.000f, -0.02f, 0.04f, 1.20f, 0.010f),
            gamma = 1.03f, accent = Color(0xFF0EA5E9)
        ),
        FilterPreset(
            "forest", "Forest", "Landscape",
            m(0.92f, 0.06f, 0.00f, 0.000f, 0.02f, 1.16f, 0.02f, 0.010f, 0.00f, 0.06f, 0.94f, 0.000f),
            gamma = 1.02f, accent = Color(0xFF15803D)
        ),
        FilterPreset(
            "neon_night", "Neon Night", "Night",
            m(1.10f, 0.02f, -0.04f, -0.010f, 0.00f, 1.00f, 0.06f, -0.010f, 0.04f, 0.00f, 1.22f, 0.020f),
            gamma = 0.94f, grain = 0.06f, accent = Color(0xFFC026D3)
        ),
        FilterPreset(
            "night", "Night", "Night",
            m(0.92f, 0.02f, 0.02f, -0.020f, 0.00f, 0.98f, 0.04f, -0.010f, 0.02f, 0.06f, 1.14f, 0.020f),
            gamma = 1.10f, vignette = 0.24f, accent = Color(0xFF1E3A8A)
        ),
        FilterPreset(
            "sunset", "Sunset", "Warm",
            m(1.16f, 0.04f, -0.02f, 0.020f, 0.02f, 1.02f, 0.00f, 0.005f, -0.02f, 0.02f, 0.88f, 0.005f),
            gamma = 1.02f, accent = Color(0xFFEA580C)
        ),
        FilterPreset(
            "iced", "Iced", "Cool",
            m(0.94f, 0.00f, 0.02f, -0.005f, 0.00f, 1.02f, 0.04f, 0.005f, 0.02f, 0.06f, 1.18f, 0.020f),
            gamma = 1.04f, accent = Color(0xFF67E8F9)
        ),
        FilterPreset(
            "fade", "Fade", "Soft",
            m(0.82f, 0.10f, 0.06f, 0.060f, 0.06f, 0.84f, 0.08f, 0.060f, 0.06f, 0.08f, 0.84f, 0.070f),
            gamma = 1.10f, grain = 0.07f, accent = Color(0xFFD6D3D1)
        ),
        FilterPreset(
            "hdr", "HDR", "Detail",
            m(1.22f, -0.10f, -0.06f, -0.020f, -0.08f, 1.20f, -0.06f, -0.020f, -0.06f, -0.10f, 1.22f, -0.020f),
            gamma = 0.94f, accent = Color(0xFFA3E635)
        ),
        FilterPreset(
            "clay", "Clay", "Stylise",
            m(0.96f, 0.08f, 0.04f, 0.030f, 0.06f, 0.92f, 0.06f, 0.020f, 0.05f, 0.10f, 0.84f, 0.020f),
            gamma = 1.05f, grain = 0.05f, accent = Color(0xFFB45309)
        ),
        FilterPreset(
            "cyber", "Cyber", "Stylise",
            m(1.02f, -0.04f, 0.06f, 0.000f, -0.02f, 0.96f, 0.10f, 0.000f, 0.10f, 0.00f, 1.24f, 0.020f),
            gamma = 0.96f, accent = Color(0xFF8B5CF6)
        ),
        FilterPreset(
            "golden", "Golden Hour", "Warm",
            m(1.18f, 0.06f, -0.04f, 0.020f, 0.03f, 1.06f, -0.02f, 0.010f, -0.04f, 0.02f, 0.86f, 0.000f),
            gamma = 1.02f, vignette = 0.14f, accent = Color(0xFFF59E0B)
        )
    )

    private val byId = presets.associateBy { it.id }

    fun preset(id: String): FilterPreset = byId[id] ?: presets.first()
    fun groups(): List<String> = presets.map { it.group }.distinct()
}

object EffectsCatalog {
    /**
     * The single source of truth is now `EffectCatalog` (the effects engine catalog in
     * `Effects.kt`); this object keeps the legacy API shape so existing panels and the
     * `NativeEffectIds` guard keep working unchanged.
     */
    val presets: List<EffectPreset> get() = EffectCatalog.presets

    private val byId by lazy { presets.associateBy { it.id } }
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
        TransitionPreset(TransitionType.CIRCLE, "Iris", "Circular reveal", Color(0xFF818CF8)),
        TransitionPreset(TransitionType.SLIDE_RIGHT, "Push Right", "Frame exits to the right", Color(0xFF60A5FA)),
        TransitionPreset(TransitionType.SLIDE_DOWN, "Drop", "Frame pushes downwards", Color(0xFF2DD4BF)),
        TransitionPreset(TransitionType.GLITCH, "Glitch Cut", "Digital tearing between clips", Color(0xFFE879F9)),
        TransitionPreset(TransitionType.BLUR, "Blur Dissolve", "Soft focus hand-off", Color(0xFF94A3B8))
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
        TextAnimationPreset(ClipAnimationType.FLICKER, "Flicker", Color(0xFFE879F9)),
        TextAnimationPreset(ClipAnimationType.SHAKE, "Shake", Color(0xFFFB7185)),
        TextAnimationPreset(ClipAnimationType.WOBBLE, "Wobble", Color(0xFFC084FC)),
        TextAnimationPreset(ClipAnimationType.BOUNCE, "Bounce", Color(0xFF4ADE80)),
        TextAnimationPreset(ClipAnimationType.SPIN_IN, "Spin In", Color(0xFF38BDF8)),
        TextAnimationPreset(ClipAnimationType.FLIP_IN, "Flip In", Color(0xFFFBBF24)),
        TextAnimationPreset(ClipAnimationType.GLITCH_IN, "Glitch In", Color(0xFFF0ABFC))
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
