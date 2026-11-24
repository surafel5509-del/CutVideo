package com.cutvideo.editor.nativebridge

import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.EffectsCatalog
import com.cutvideo.editor.domain.model.FilterCatalog

/**
 * Layout of the float array handed to `cpp/jni_bridge.cpp::readParams` and to the shared GLSL shader.
 * The order is load-bearing: it is mirrored in C++ and in `assets/shaders/cutvideo_effect.glsl`.
 */
object NativeParams {
    /** Slots 0..32 are the colour/look parameters. */
    const val BASE_COUNT = 33
    /** Slots 33..34 carry the transition mask so the CPU fallback can render wipes too. */
    const val COUNT = 35

    const val FILTER_MATRIX = 0      // 0..8  3x3 row-major
    const val FILTER_OFFSET = 9      // 9..11
    const val FILTER_INTENSITY = 12
    const val GAMMA = 13
    const val BRIGHTNESS = 14
    const val CONTRAST = 15
    const val SATURATION = 16
    const val EXPOSURE = 17
    const val TEMPERATURE = 18
    const val TINT = 19
    const val VIGNETTE = 20
    const val SHARPEN = 21
    const val BLUR_RADIUS = 22
    const val CHROMA_ENABLED = 23
    const val CHROMA_KEY = 24        // 24..26
    const val CHROMA_SIMILARITY = 27
    const val CHROMA_SMOOTHNESS = 28
    const val CHROMA_SPILL = 29
    const val ALPHA = 30
    const val TIME_SECONDS = 31
    const val ASPECT = 32
    const val WIPE = 33
    const val WIPE_MODE = 34
}

/** Effect ids must match `EffectId` in `video_processor.h`. */
object NativeEffectIds {
    private val order = mapOf(
        "none" to 0,
        "grain" to 1,
        "vignette" to 2,
        "scanlines" to 3,
        "glitch" to 4,
        "chroma_aberration" to 5,
        "bloom" to 6,
        "light_leak" to 7,
        "pulse" to 8,
        "mirror" to 9,
        "posterize" to 10,
        "duotone" to 11,
        "sharpen" to 12,
        "vhs" to 13,
        "snow" to 14,
        "zoom_blur" to 15,
        "blur" to 16,
        "pixelate" to 17,
        "neon" to 18,
        "halftone" to 19,
        "tilt_shift" to 20,
        "old_film" to 21,
        "heat_wave" to 22,
        "spotlight" to 23,
        // --- professional engine effects (Phase 1) --------------------------------------------
        "gaussian_blur" to 24,
        "glow" to 25,
        "bloom_pro" to 26,
        "drop_shadow" to 27,
        "vignette_pro" to 28,
        "grain_pro" to 29,
        // --- Phase 2: colour grading & digital ------------------------------------------------
        "lgg" to 30,
        "curves" to 31,
        "color_wheels" to 32,
        "hue_sat" to 33,
        "film_emulation" to 34,
        "teal_orange" to 35,
        "bleach_bypass" to 36,
        "cube_lut" to 37,
        "rgb_split" to 38,
        "pixel_sort" to 39,
        "datamosh" to 40,
        "liquid" to 41,
        "displacement" to 42,
        // --- Phase 3: motion blur family ------------------------------------------------------
        "motion_blur" to 43,
        "radial_blur" to 44
    )

    init {
        // Guard against the catalogue and the native enum drifting apart.
        check(EffectsCatalog.presets.size == order.size) {
            "EffectsCatalog has ${EffectsCatalog.presets.size} entries, native enum has ${order.size}"
        }
        EffectsCatalog.presets.forEachIndexed { index, preset ->
            check(order[preset.id] == index) { "Effect '${preset.id}' is not in native slot $index" }
        }
    }

    fun idOf(effectId: String): Int = order[effectId] ?: 0
    fun of(clip: Clip): IntArray =
        clip.effects.filter { it.enabled && it.intensity > 0f && it.id != "none" }.map { idOf(it.id) }.toIntArray()
    fun strengths(clip: Clip): FloatArray =
        clip.effects.filter { it.enabled && it.intensity > 0f && it.id != "none" }.map { it.intensity }.toFloatArray()
}

object NativeParamWriter {

    /** Builds the look array for a clip. [timelineUs] drives time-animated effects. */
    fun params(
        clip: Clip,
        aspect: AspectRatio,
        timelineUs: Long = 0L,
        alphaOverride: Float = 1f,
        wipe: Float = 1f,
        wipeMode: Int = 0
    ): FloatArray {
        val out = FloatArray(NativeParams.COUNT)
        val preset = FilterCatalog.preset(clip.filter.id)
        val intensity = if (clip.filter.isActive) clip.filter.intensity else 0f

        for (i in 0 until 9) out[NativeParams.FILTER_MATRIX + i] = preset.matrix[i]
        // 3x4 presets keep the row offsets in the fourth column (indices 3, 7, 11).
        out[NativeParams.FILTER_OFFSET + 0] = preset.matrix[3]
        out[NativeParams.FILTER_OFFSET + 1] = preset.matrix[7]
        out[NativeParams.FILTER_OFFSET + 2] = preset.matrix[11]
        out[NativeParams.FILTER_INTENSITY] = intensity
        out[NativeParams.GAMMA] = if (intensity > 0f) preset.gamma else 1f

        out[NativeParams.BRIGHTNESS] = clip.adjust.brightness
        out[NativeParams.CONTRAST] = clip.adjust.contrast
        out[NativeParams.SATURATION] = clip.adjust.saturation
        out[NativeParams.EXPOSURE] = clip.adjust.exposure
        out[NativeParams.TEMPERATURE] = clip.adjust.temperature
        out[NativeParams.TINT] = clip.adjust.tint
        out[NativeParams.VIGNETTE] = (clip.adjust.vignette + preset.vignette * intensity).coerceIn(0f, 1f)
        out[NativeParams.SHARPEN] = clip.adjust.sharpen
        out[NativeParams.BLUR_RADIUS] = clip.blur

        out[NativeParams.CHROMA_ENABLED] = if (clip.chroma.enabled) 1f else 0f
        val key = clip.chroma.keyColor
        out[NativeParams.CHROMA_KEY + 0] = ((key shr 16) and 0xFF) / 255f
        out[NativeParams.CHROMA_KEY + 1] = ((key shr 8) and 0xFF) / 255f
        out[NativeParams.CHROMA_KEY + 2] = (key and 0xFF) / 255f
        out[NativeParams.CHROMA_SIMILARITY] = clip.chroma.similarity
        out[NativeParams.CHROMA_SMOOTHNESS] = clip.chroma.smoothness
        out[NativeParams.CHROMA_SPILL] = clip.chroma.spill

        out[NativeParams.ALPHA] = alphaOverride.coerceIn(0f, 1f)
        out[NativeParams.TIME_SECONDS] = timelineUs / 1_000_000f
        out[NativeParams.ASPECT] = aspect.ratio
        out[NativeParams.WIPE] = wipe.coerceIn(0f, 1f)
        out[NativeParams.WIPE_MODE] = wipeMode.toFloat()
        return out
    }
}
