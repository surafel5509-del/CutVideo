package com.cutvideo.editor.domain.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * The effect engine's catalog: one [EffectDef] per effect, declaring the *meaningful* professional
 * controls for that effect (nothing decorative), its category, and how it composites.
 *
 * The GLSL side is kept in lock-step by `NativeEffectIds`'s id guard and `EffectCatalogTest`.
 */

@Serializable
enum class EffectCategory(val label: String) {
    LIGHTING("Cinematic lighting"),
    COLOR("Color grading"),
    DIGITAL("Digital"),
    BLUR("Blur & focus"),
    TEXTURE("Texture"),
    DISTORT("Distort"),
    UTILITY("Utility")
}

/** Blend modes used when an effect's output is mixed over its input (mirror in `BlendModes`). */
@Serializable
enum class BlendMode(val label: String) {
    NORMAL("Normal"),
    ADD("Add"),
    SCREEN("Screen"),
    MULTIPLY("Multiply"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft light"),
    HARD_LIGHT("Hard light"),
    DIFFERENCE("Difference")
}

/** One adjustable parameter of an effect. */
@Serializable
data class EffectParamDef(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = ""
)

/**
 * Definition of an effect: its identity, professional parameters, and pass kind.
 *
 * `passKind` tells the GPU chain renderer how many draw passes the effect needs:
 *  - `SINGLE`: one pass, effect computed from the source texture;
 *  - `GAUSSIAN`: separable blur — horizontal pass, vertical pass, then a mix pass;
 *  - `BLOOM`: bright-pass + two blur scales + combine pass (multi-pass glow/bloom);
 *  - `SHADOW`: silhouette extraction + separable blur + behind-frame composite.
 */
data class EffectDef(
    val id: String,
    val name: String,
    val category: EffectCategory,
    val description: String,
    val accent: Color,
    val passKind: PassKind = PassKind.SINGLE,
    /** Shown as the primary amount slider; also the value an "add" click seeds. */
    val defaultIntensity: Float = 0.5f,
    val params: List<EffectParamDef> = emptyList(),
    /** Blend modes offered in the inspector (all modes for most effects). */
    val blendModes: List<BlendMode> = BlendMode.entries,
    /** Legacy flag kept for the older cards (extra uniforms). */
    val premium: Boolean = false
) {
    enum class PassKind { SINGLE, GAUSSIAN, BLOOM, SHADOW }
}

/**
 * The engine catalog. Ids 1..23 are the pre-existing effects (unchanged shader slots so every old
 * project keeps rendering exactly the same); the professional Phase-1 effects continue the table.
 */
object EffectCatalog {

    val defs: List<EffectDef> = listOf(
        EffectDef("grain", "Film Grain", EffectCategory.TEXTURE, "Animated silver-halide grain", Color(0xFFD6D3D1),
            params = listOf(
                EffectParamDef("size", "Grain size", 0.5f, 4f, 1f),
                EffectParamDef("speed", "Animation speed", 0f, 4f, 1f)
            )),
        EffectDef("vignette", "Vignette", EffectCategory.LIGHTING, "Darkens the frame edges", Color(0xFF7C3AED),
            params = listOf(
                EffectParamDef("roundness", "Roundness", 0.2f, 1.6f, 1f),
                EffectParamDef("softness", "Softness", 0.05f, 1f, 0.5f),
                EffectParamDef("centerX", "Center X", -0.5f, 1.5f, 0.5f),
                EffectParamDef("centerY", "Center Y", -0.5f, 1.5f, 0.5f)
            )),
        EffectDef("scanlines", "Scanlines", EffectCategory.DIGITAL, "CRT line structure", Color(0xFF22D3EE),
            params = listOf(
                EffectParamDef("frequency", "Line frequency", 60f, 900f, 480f),
                EffectParamDef("opacity", "Line opacity", 0f, 1f, 0.5f)
            )),
        EffectDef("glitch", "Digital Glitch", EffectCategory.DIGITAL, "RGB channel tearing", Color(0xFFF43F5E), premium = true,
            params = listOf(
                EffectParamDef("speed", "Speed", 0f, 4f, 1f),
                EffectParamDef("blockSize", "Block size", 4f, 96f, 40f)
            )),
        EffectDef("chroma_aberration", "Chromatic Aberration", EffectCategory.DISTORT, "Lens colour fringing", Color(0xFFA855F7),
            params = listOf(
                EffectParamDef("spread", "Spread", 0.2f, 3f, 1f)
            )),
        EffectDef("bloom", "Bloom (classic)", EffectCategory.LIGHTING, "The original single-pass glow", Color(0xFFF59E0B),
            params = emptyList()),
        EffectDef("light_leak", "Light Leak", EffectCategory.LIGHTING, "Warm analog leak overlay", Color(0xFFF97316),
            params = listOf(
                EffectParamDef("speed", "Drift speed", 0f, 3f, 1f)
            )),
        EffectDef("pulse", "Pulse", EffectCategory.DIGITAL, "Rhythmic brightness pulse", Color(0xFF38BDF8),
            params = listOf(
                EffectParamDef("speed", "Speed", 0.2f, 6f, 1f)
            )),
        EffectDef("mirror", "Mirror", EffectCategory.DISTORT, "Reflects one half of the frame", Color(0xFF22C55E),
            params = listOf(
                EffectParamDef("position", "Axis position", 0f, 1f, 0.5f)
            )),
        EffectDef("posterize", "Posterize", EffectCategory.COLOR, "Quantises colour levels", Color(0xFFA855F7),
            params = listOf(
                EffectParamDef("levels", "Levels", 2f, 16f, 6f)
            )),
        EffectDef("duotone", "Duotone", EffectCategory.COLOR, "Two-tone colour mapping", Color(0xFF7C3AED),
            params = emptyList()),
        EffectDef("sharpen", "Sharpen", EffectCategory.UTILITY, "Unsharp-mask sharpening", Color(0xFF22D3EE),
            params = listOf(
                EffectParamDef("radius", "Radius", 0.5f, 3f, 1f)
            )),
        EffectDef("vhs", "VHS", EffectCategory.DIGITAL, "Tape softness, wobble and noise", Color(0xFF111114),
            params = listOf(
                EffectParamDef("wobble", "Wobble", 0f, 1f, 1f),
                EffectParamDef("noise", "Tape noise", 0f, 1f, 1f)
            )),
        EffectDef("snow", "Snow", EffectCategory.TEXTURE, "Falling snow overlay", Color(0xFFE5E7EB),
            params = listOf(
                EffectParamDef("density", "Density", 0.2f, 3f, 1f),
                EffectParamDef("speed", "Fall speed", 0.2f, 4f, 1f)
            )),
        EffectDef("zoom_blur", "Zoom Blur", EffectCategory.BLUR, "Radial streaks from the centre", Color(0xFF38BDF8),
            params = listOf(
                EffectParamDef("strength", "Strength", 0.2f, 2.5f, 1f)
            )),
        EffectDef("blur", "Blur (classic)", EffectCategory.BLUR, "The original single-pass blur", Color(0xFF94A3B8),
            params = listOf(
                EffectParamDef("radius", "Radius", 0.5f, 24f, 6f)
            )),
        EffectDef("pixelate", "Pixelate", EffectCategory.DIGITAL, "Mosaic pixel grid", Color(0xFF22C55E),
            params = listOf(
                EffectParamDef("size", "Pixel size", 2f, 64f, 14f)
            )),
        EffectDef("neon", "Neon Edge Glow", EffectCategory.LIGHTING, "Edge detect + glow tint", Color(0xFFA855F7),
            params = listOf(
                EffectParamDef("threshold", "Edge threshold", 0.02f, 0.6f, 0.12f)
            )),
        EffectDef("halftone", "Halftone Print", EffectCategory.TEXTURE, "Dot-screen print look", Color(0xFF111114),
            params = listOf(
                EffectParamDef("scale", "Dot scale", 2f, 24f, 8f)
            )),
        EffectDef("tilt_shift", "Tilt Shift", EffectCategory.BLUR, "Band-focus miniature look", Color(0xFF0EA5E9),
            params = listOf(
                EffectParamDef("focus", "Focus position", 0f, 1f, 0.5f),
                EffectParamDef("band", "Band width", 0.05f, 0.8f, 0.25f)
            )),
        EffectDef("old_film", "Old Film", EffectCategory.TEXTURE, "Flicker, dust and gate weave", Color(0xFFD6D3D1),
            params = listOf(
                EffectParamDef("flicker", "Flicker", 0f, 1f, 1f)
            )),
        EffectDef("heat_wave", "Heat Wave", EffectCategory.DISTORT, "Rising heat distortion", Color(0xFFF97316),
            params = listOf(
                EffectParamDef("speed", "Rise speed", 0.2f, 3f, 1f),
                EffectParamDef("amplitude", "Amplitude", 0.2f, 2f, 1f)
            )),
        EffectDef("spotlight", "Spotlight", EffectCategory.LIGHTING, "Stage light with falloff", Color(0xFFFBBF24),
            params = listOf(
                EffectParamDef("x", "Position X", -0.2f, 1.2f, 0.5f),
                EffectParamDef("y", "Position Y", -0.2f, 1.2f, 0.42f),
                EffectParamDef("radius", "Radius", 0.1f, 1.2f, 0.62f)
            )),

        // ---- Phase 1: professional engine effects (real multi-pass processing) --------------------
        EffectDef("gaussian_blur", "Gaussian Blur", EffectCategory.BLUR, "Separable multi-pass gaussian blur",
            Color(0xFF60A5FA), passKind = EffectDef.PassKind.GAUSSIAN,
            params = listOf(
                EffectParamDef("radius", "Radius", 0.5f, 64f, 12f),
                EffectParamDef("quality", "Quality (passes)", 1f, 3f, 1f)
            )),
        EffectDef("glow", "Glow", EffectCategory.LIGHTING, "Threshold glow: bright areas bloom outward",
            Color(0xFFFBBF24), passKind = EffectDef.PassKind.BLOOM,
            params = listOf(
                EffectParamDef("threshold", "Threshold", 0f, 1f, 0.6f),
                EffectParamDef("radius", "Radius", 0.5f, 64f, 18f),
                EffectParamDef("tintR", "Tint R", 0f, 1f, 1f),
                EffectParamDef("tintG", "Tint G", 0f, 1f, 0.92f),
                EffectParamDef("tintB", "Tint B", 0f, 1f, 0.8f)
            ),
            blendModes = listOf(BlendMode.NORMAL, BlendMode.ADD, BlendMode.SCREEN, BlendMode.SOFT_LIGHT)),
        EffectDef("bloom_pro", "Bloom", EffectCategory.LIGHTING, "Multi-scale bloom (three blur levels)",
            Color(0xFFFB923C), passKind = EffectDef.PassKind.BLOOM,
            params = listOf(
                EffectParamDef("threshold", "Threshold", 0f, 1f, 0.75f),
                EffectParamDef("radius", "Radius", 0.5f, 64f, 26f),
                EffectParamDef("levels", "Bloom levels", 1f, 3f, 2f)
            ),
            blendModes = listOf(BlendMode.NORMAL, BlendMode.ADD, BlendMode.SCREEN)),
        EffectDef("drop_shadow", "Drop Shadow", EffectCategory.LIGHTING, "Blurred shadow behind the frame",
            Color(0xFF111114), passKind = EffectDef.PassKind.SHADOW,
            params = listOf(
                EffectParamDef("offsetX", "Offset X", -0.2f, 0.2f, 0.03f),
                EffectParamDef("offsetY", "Offset Y", -0.2f, 0.2f, 0.03f),
                EffectParamDef("blur", "Blur", 0.5f, 64f, 14f),
                EffectParamDef("spread", "Spread", 0f, 1f, 0f)
            )),
        EffectDef("vignette_pro", "Vignette Pro", EffectCategory.LIGHTING, "Precise vignette with centre control",
            Color(0xFF8B5CF6),
            params = listOf(
                EffectParamDef("roundness", "Roundness", 0.2f, 1.6f, 1f),
                EffectParamDef("softness", "Softness", 0.05f, 1f, 0.5f),
                EffectParamDef("centerX", "Center X", -0.5f, 1.5f, 0.5f),
                EffectParamDef("centerY", "Center Y", -0.5f, 1.5f, 0.5f)
            )),
        EffectDef("grain_pro", "Film Grain Pro", EffectCategory.TEXTURE, "Grain with size and speed control",
            Color(0xFFE7E5E4),
            params = listOf(
                EffectParamDef("size", "Grain size", 0.5f, 4f, 1f),
                EffectParamDef("speed", "Animation speed", 0f, 4f, 1f),
                EffectParamDef("roughness", "Roughness", 0f, 1f, 0.5f)
            )),

        // ---- Phase 2: colour grading & digital effects -------------------------------------------
        EffectDef("lgg", "Lift / Gamma / Gain", EffectCategory.COLOR, "Per-channel lift, gamma and gain",
            Color(0xFF34D399), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("liftR", "Lift R", -0.3f, 0.3f, 0f),
                EffectParamDef("liftG", "Lift G", -0.3f, 0.3f, 0f),
                EffectParamDef("liftB", "Lift B", -0.3f, 0.3f, 0f),
                EffectParamDef("gamma", "Gamma", 0.4f, 2.5f, 1f),
                EffectParamDef("gainR", "Gain R", 0.5f, 1.5f, 1f),
                EffectParamDef("gainG", "Gain G", 0.5f, 1.5f, 1f),
                EffectParamDef("gainB", "Gain B", 0.5f, 1.5f, 1f)
            ), premium = true),
        EffectDef("curves", "RGB Curves", EffectCategory.COLOR, "Parametric shadow/midtone/highlight curve",
            Color(0xFF22D3EE), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("shadows", "Shadows", -0.5f, 0.5f, 0f),
                EffectParamDef("midtones", "Midtones", -0.5f, 0.5f, 0f),
                EffectParamDef("highlights", "Highlights", -0.5f, 0.5f, 0f),
                EffectParamDef("contrast", "Contrast", -0.5f, 1f, 0f)
            ), premium = true),
        EffectDef("color_wheels", "Color Wheels", EffectCategory.COLOR, "Tint shadows, mids and highlights",
            Color(0xFFF472B6), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("shadowsHue", "Shadows hue", 0f, 1f, 0.6f),
                EffectParamDef("shadowsSat", "Shadows sat", 0f, 1f, 0.12f),
                EffectParamDef("midsHue", "Mids hue", 0f, 1f, 0.12f),
                EffectParamDef("midsSat", "Mids sat", 0f, 1f, 0.06f),
                EffectParamDef("highsHue", "Highlights hue", 0f, 1f, 0.1f),
                EffectParamDef("highsSat", "Highlights sat", 0f, 1f, 0.1f),
                EffectParamDef("balance", "Balance", -1f, 1f, 0f)
            ), premium = true),
        EffectDef("hue_sat", "Hue vs Sat / Lum", EffectCategory.COLOR, "Selective hue adjustments",
            Color(0xFFA78BFA), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("hueCenter", "Hue centre", 0f, 1f, 0f),
                EffectParamDef("hueRange", "Hue range", 0.02f, 1f, 0.25f),
                EffectParamDef("hueShift", "Hue shift", -1f, 1f, 0f),
                EffectParamDef("satAdjust", "Saturation", -1f, 1f, 0f),
                EffectParamDef("lumAdjust", "Luminance", -1f, 1f, 0f)
            ), premium = true),
        EffectDef("film_emulation", "Film Emulation", EffectCategory.COLOR, "Print density, fade and warmth",
            Color(0xFFFBBF24), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("density", "Density", -0.5f, 1f, 0.15f),
                EffectParamDef("fade", "Fade", 0f, 1f, 0.15f),
                EffectParamDef("warmth", "Warmth", -1f, 1f, 0.2f),
                EffectParamDef("saturation", "Saturation", -1f, 1f, -0.1f)
            ), premium = true),
        EffectDef("teal_orange", "Teal & Orange", EffectCategory.COLOR, "Blockbuster split toning",
            Color(0xFF0EA5E9), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("shadowTint", "Shadow tint", 0f, 1f, 0.5f),
                EffectParamDef("highlightTint", "Highlight tint", 0f, 1f, 0.5f),
                EffectParamDef("balance", "Balance", -1f, 1f, 0f)
            )),
        EffectDef("bleach_bypass", "Bleach Bypass", EffectCategory.COLOR, "Silver-retention contrast",
            Color(0xFF94A3B8), defaultIntensity = 1f,
            params = listOf(
                EffectParamDef("contrast", "Contrast", 0f, 1f, 0.5f),
                EffectParamDef("desaturation", "Desaturation", 0f, 1f, 0.5f)
            )),
        EffectDef("cube_lut", ".cube LUT", EffectCategory.COLOR, "Import and apply a 3D colour LUT",
            Color(0xFFFB7185), defaultIntensity = 1f, premium = true),
        EffectDef("rgb_split", "RGB Split", EffectCategory.DIGITAL, "Directional channel separation",
            Color(0xFFF43F5E), defaultIntensity = 0.6f,
            params = listOf(
                EffectParamDef("angle", "Angle", 0f, 1f, 0f),
                EffectParamDef("spread", "Spread", 0f, 3f, 1f),
                EffectParamDef("radial", "Radial", 0f, 1f, 0.4f)
            )),
        EffectDef("pixel_sort", "Pixel Sort", EffectCategory.DIGITAL, "Bright pixels sorted into streaks",
            Color(0xFF22D3EE), defaultIntensity = 0.7f,
            params = listOf(
                EffectParamDef("threshold", "Threshold", 0f, 1f, 0.55f),
                EffectParamDef("length", "Streak length", 0.1f, 1f, 0.5f),
                EffectParamDef("direction", "Vertical", 0f, 1f, 0f)
            )),
        EffectDef("datamosh", "Datamosh", EffectCategory.DIGITAL, "Block displacement and smear",
            Color(0xFFE879F9), defaultIntensity = 0.7f,
            params = listOf(
                EffectParamDef("blocks", "Block density", 2f, 48f, 12f),
                EffectParamDef("smear", "Smear", 0f, 1f, 0.5f),
                EffectParamDef("seed", "Seed", 0f, 20f, 0f)
            )),
        EffectDef("liquid", "Liquid Warp", EffectCategory.DISTORT, "Fluid UV displacement",
            Color(0xFF2DD4BF), defaultIntensity = 0.6f,
            params = listOf(
                EffectParamDef("amplitude", "Amplitude", 0f, 2f, 0.6f),
                EffectParamDef("frequency", "Frequency", 0.2f, 4f, 1f),
                EffectParamDef("speed", "Speed", 0f, 3f, 1f)
            )),
        EffectDef("displacement", "Displacement", EffectCategory.DISTORT, "Directional wave warp",
            Color(0xFF818CF8), defaultIntensity = 0.6f,
            params = listOf(
                EffectParamDef("angle", "Angle", 0f, 1f, 0f),
                EffectParamDef("amount", "Amount", 0f, 2f, 0.6f),
                EffectParamDef("scale", "Wave scale", 0.2f, 4f, 1f)
            )),

        // ---- Phase 3: motion blur family ---------------------------------------------------------
        EffectDef("motion_blur", "Motion Blur", EffectCategory.BLUR, "Directional multi-tap blur",
            Color(0xFF60A5FA), defaultIntensity = 0.7f,
            params = listOf(
                EffectParamDef("angle", "Angle", 0f, 1f, 0f),
                EffectParamDef("distance", "Distance", 0f, 2f, 0.7f)
            )),
        EffectDef("radial_blur", "Radial Motion", EffectCategory.BLUR, "Zoom blur around a centre",
            Color(0xFF38BDF8), defaultIntensity = 0.7f,
            params = listOf(
                EffectParamDef("centerX", "Centre X", -0.5f, 1.5f, 0.5f),
                EffectParamDef("centerY", "Centre Y", -0.5f, 1.5f, 0.5f),
                EffectParamDef("strength", "Strength", 0f, 2f, 0.8f)
            ))
    )

    /** Legacy alias: the original `EffectsCatalog` API used by the cards/panels. */
    val presets: List<EffectPreset> = listOf(
        EffectPreset("none", "None", "Basic", "Clean frame, no effect", Color(0xFFA1A1AA), 0f)
    ) + defs.map {
        EffectPreset(it.id, it.name, it.category.label, it.description, it.accent, it.defaultIntensity, it.premium)
    }

    fun def(id: String): EffectDef = defs.firstOrNull { it.id == id }
        ?: defs.first() // unknown ids render as the first entry's defaults instead of crashing

    fun paramDef(id: String, key: String): EffectParamDef? =
        defs.firstOrNull { it.id == id }?.params?.firstOrNull { it.key == key }

    /** Seed parameter map for a freshly added effect. */
    fun defaultParams(id: String): Map<String, Float> =
        def(id).params.associate { it.key to it.default }

    /**
     * Named multi-effect stacks ("presets" in the compositing sense). Applying one replaces the
     * clip's current stack — this is what the preset row in the inspector uses.
     */
    val stacks: List<EffectStackPreset> = listOf(
        EffectStackPreset(
            "cinematic_glow", "Cinematic Glow",
            listOf(
                EffectSpec("bloom_pro", 0.45f, params = mapOf("threshold" to 0.7f, "radius" to 30f), blend = BlendMode.ADD),
                EffectSpec("vignette_pro", 0.35f),
                EffectSpec("grain_pro", 0.25f, params = mapOf("size" to 1.2f))
            )
        ),
        EffectStackPreset(
            "dreamy", "Dreamy",
            listOf(
                EffectSpec("glow", 0.5f, params = mapOf("threshold" to 0.45f, "radius" to 24f), blend = BlendMode.SCREEN),
                EffectSpec("gaussian_blur", 0.25f, params = mapOf("radius" to 10f)),
                EffectSpec("grain_pro", 0.15f)
            )
        ),
        EffectStackPreset(
            "retro_tape", "Retro Tape",
            listOf(
                EffectSpec("vhs", 0.6f),
                EffectSpec("scanlines", 0.4f, params = mapOf("frequency" to 420f)),
                EffectSpec("grain_pro", 0.3f, params = mapOf("roughness" to 0.8f)),
                EffectSpec("vignette", 0.3f)
            )
        ),
        EffectStackPreset(
            "night_flash", "Night Flash",
            listOf(
                EffectSpec("drop_shadow", 0.5f, params = mapOf("offsetX" to 0.02f, "offsetY" to 0.04f, "blur" to 22f)),
                EffectSpec("bloom_pro", 0.35f),
                EffectSpec("vignette_pro", 0.4f, params = mapOf("softness" to 0.3f))
            )
        ),
        EffectStackPreset(
            "clean_punch", "Clean Punch",
            listOf(
                EffectSpec("sharpen", 0.45f),
                EffectSpec("grain_pro", 0.12f),
                EffectSpec("vignette_pro", 0.22f)
            )
        )
    )
}

/** A named stack of effects applied at once. */
data class EffectStackPreset(
    val id: String,
    val name: String,
    val effects: List<EffectSpec>
)
