package com.cutvideo.editor.editor.gl

import com.cutvideo.editor.domain.model.AnimationFrame
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.MaskType
import com.cutvideo.editor.editor.KeyframeEngine
import com.cutvideo.editor.nativebridge.NativeEffectIds

/**
 * Flattened uniform payload for [CutVideoShaderProgram].
 *
 * Built from the same [Clip] the native CPU path receives, so a filter looks identical in the
 * preview, in the effect cards and in the exported file. Keyframe values are baked in at the given
 * clip-local time, so the preview (which samples at its playhead) and the export (which samples at
 * each frame's presentation time) produce the same motion.
 */
data class GlLook(
    /** Column-major mat3 as OpenGL expects. */
    val filterMatrix: FloatArray,
    val filterOffset: FloatArray,
    val filterIntensity: Float,
    val gamma: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val exposure: Float,
    val temperature: Float,
    val tint: Float,
    val vignette: Float,
    val sharpen: Float,
    val blurRadiusPx: Float,
    val chromaEnabled: Boolean,
    val chromaKey: FloatArray,
    val chromaSimilarity: Float,
    val chromaSmoothness: Float,
    val chromaSpill: Float,
    val alpha: Float,
    val wipe: Float,
    val wipeMode: Int,
    val timeSeconds: Float,
    val aspect: Float,
    val effectIds: IntArray,
    val effectAmounts: FloatArray,
    /** Keyframed/animated transform baked for the shader (identity when the preview uses Compose). */
    val transformScale: Float = 1f,
    val transformRotation: Float = 0f,
    val transformOffsetX: Float = 0f,
    val transformOffsetY: Float = 0f,
    /** Up to two real masks, evaluated (with their keyframes) at this frame's time. */
    val masks: List<GlMask> = emptyList(),
    /** Coverage grid for the freehand mask (row-major, 0..1) when one of [masks] is freehand. */
    val freehandAlpha: FloatArray? = null,
    val freehandWidth: Int = 0,
    val freehandHeight: Int = 0
)

/** One mask flattened for the shader. */
data class GlMask(
    /** 0 circle, 1 rect, 2 linear, 3 mirror, 4 freehand (mirrors `cutvideo_effect.glsl`). */
    val type: Int,
    val xFrac: Float,
    val yFrac: Float,
    val scale: Float,
    val rotationRadians: Float,
    val feather: Float,
    val opacity: Float,
    val expansion: Float,
    val roundness: Float,
    val invert: Float
)

object GlLookFactory {

    /** Maximum simultaneous GPU effects; extra entries are ignored by the shader (documented). */
    const val MAX_GPU_EFFECTS = 4

    /** Masks the shader samples (uniform arrays are fixed at 2). */
    const val MAX_GPU_MASKS = 2

    fun from(clip: Clip, aspect: AspectRatio, timelineUs: Long, animation: AnimationFrame?): GlLook {
        val preset = FilterCatalog.preset(clip.filter.id)
        val intensity = if (clip.filter.isActive) clip.filter.intensity else 0f
        val m = preset.matrix

        // Preset matrices are row-major with the translation in the 4th column. The shader multiplies
        // column vectors, so hand it the transposed 3x3 (i.e. column-major order for glUniformMatrix3fv).
        val columnMajor = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

        // Keyframed effect intensity scales every effect amount; keyframed glow injects a bloom.
        val effectIntensityScale =
            KeyframeEngine.valueOr(KeyframeProperty.EFFECT_INTENSITY, clip.keyframes, timelineUs) /
                KeyframeProperty.EFFECT_INTENSITY.defaultValue.coerceAtLeast(0.001f)
        val glow = KeyframeEngine.valueOr(KeyframeProperty.GLOW, clip.keyframes, timelineUs)

        val effectIds = ArrayList<Int>(MAX_GPU_EFFECTS)
        val effectAmounts = ArrayList<Float>(MAX_GPU_EFFECTS)
        clip.effects.forEach { effect ->
            if (effect.intensity > 0f && effect.id != "none" && effectIds.size < MAX_GPU_EFFECTS) {
                effectIds += NativeEffectIds.idOf(effect.id)
                effectAmounts += (effect.intensity * effectIntensityScale).coerceIn(0f, 1f)
            }
        }
        if (preset.grain > 0f && intensity > 0f && effectIds.size < MAX_GPU_EFFECTS) {
            effectIds += NativeEffectIds.idOf("grain")
            effectAmounts += (preset.grain * intensity).coerceIn(0f, 1f)
        }
        if (glow > 0.002f && effectIds.size < MAX_GPU_EFFECTS) {
            effectIds += NativeEffectIds.idOf("bloom")
            effectAmounts += glow.coerceIn(0f, 1f)
        }

        val key = clip.chroma.keyColor
        val masks = clip.masks.filter { it.enabled }.take(MAX_GPU_MASKS)
        val freehand = masks.indexOfFirst { it.type == MaskType.FREEHAND }
        val freehandGrid = if (freehand >= 0) freehandCoverage(masks[freehand]) else null

        return GlLook(
            filterMatrix = columnMajor,
            filterOffset = floatArrayOf(m[3], m[7], m[11]),
            filterIntensity = intensity,
            gamma = if (intensity > 0f) preset.gamma else 1f,
            brightness = clip.adjust.brightness,
            contrast = clip.adjust.contrast,
            saturation = clip.adjust.saturation,
            exposure = clip.adjust.exposure,
            temperature = clip.adjust.temperature,
            tint = clip.adjust.tint,
            vignette = (clip.adjust.vignette + preset.vignette * intensity).coerceIn(0f, 1f),
            sharpen = clip.adjust.sharpen,
            blurRadiusPx = clip.blur + (animation?.blurRadiusPx ?: 0f) +
                KeyframeEngine.valueOr(KeyframeProperty.BLUR, clip.keyframes, timelineUs) -
                KeyframeProperty.BLUR.defaultValue,
            chromaEnabled = clip.chroma.enabled,
            chromaKey = floatArrayOf(
                ((key shr 16) and 0xFF) / 255f,
                ((key shr 8) and 0xFF) / 255f,
                (key and 0xFF) / 255f
            ),
            chromaSimilarity = clip.chroma.similarity,
            chromaSmoothness = clip.chroma.smoothness,
            chromaSpill = clip.chroma.spill,
            alpha = animation?.alpha ?: 1f,
            wipe = animation?.wipe ?: 1f,
            wipeMode = if (clip.transitionIn?.type == com.cutvideo.editor.domain.model.TransitionType.CIRCLE ||
                clip.transitionOut?.type == com.cutvideo.editor.domain.model.TransitionType.CIRCLE
            ) 1 else 0,
            timeSeconds = timelineUs / 1_000_000f,
            aspect = aspect.ratio,
            effectIds = effectIds.toIntArray(),
            effectAmounts = effectAmounts.toFloatArray(),
            transformScale = animation?.scale ?: 1f,
            transformRotation = animation?.rotationDegrees ?: 0f,
            transformOffsetX = animation?.translateX ?: 0f,
            transformOffsetY = animation?.translateY ?: 0f,
            masks = masks.map { it.toGl(timelineUs, clip.keyframes) },
            freehandAlpha = freehandGrid?.alpha,
            freehandWidth = freehandGrid?.width ?: 0,
            freehandHeight = freehandGrid?.height ?: 0
        )
    }

    /**
     * Freehand strokes only change when the user paints, so the rasterised coverage is cached by
     * the stroke list identity. The GL program then also skips re-uploading unchanged grids.
     */
    private val freehandCache = HashMap<Int, MaskRasterizer.Coverage>()

    internal fun freehandCoverage(mask: com.cutvideo.editor.domain.model.MaskSpec): MaskRasterizer.Coverage {
        val cacheKey = System.identityHashCode(mask.strokes)
        freehandCache[cacheKey]?.let { return it }
        val coverage = MaskRasterizer.rasterize(mask, MaskRasterizer.DEFAULT_SIZE, MaskRasterizer.DEFAULT_SIZE)
        if (freehandCache.size > 8) freehandCache.clear()
        freehandCache[cacheKey] = coverage
        return coverage
    }
}

private fun com.cutvideo.editor.domain.model.MaskSpec.toGl(
    timelineUs: Long,
    tracks: List<com.cutvideo.editor.domain.model.KeyframeTrack>
): GlMask {
    fun maskValue(prop: KeyframeProperty, base: Float): Float {
        val track = tracks.firstOrNull { it.property == prop } ?: return base
        return KeyframeEngine.evaluate(track, timelineUs, base)
    }
    return GlMask(
        type = when (type) {
            MaskType.CIRCLE -> 0
            MaskType.RECT -> 1
            MaskType.LINEAR -> 2
            MaskType.MIRROR -> 3
            MaskType.FREEHAND -> 4
        },
        xFrac = maskValue(KeyframeProperty.MASK_X, xFrac),
        yFrac = maskValue(KeyframeProperty.MASK_Y, yFrac),
        scale = maskValue(KeyframeProperty.MASK_SCALE, scale),
        rotationRadians = Math.toRadians(
            maskValue(KeyframeProperty.MASK_ROTATION, rotationDegrees).toDouble()
        ).toFloat(),
        feather = maskValue(KeyframeProperty.MASK_FEATHER, feather),
        opacity = maskValue(KeyframeProperty.MASK_OPACITY, opacity),
        expansion = maskValue(KeyframeProperty.MASK_EXPANSION, expansion),
        roundness = roundness,
        invert = if (invert) 1f else 0f
    )
}
