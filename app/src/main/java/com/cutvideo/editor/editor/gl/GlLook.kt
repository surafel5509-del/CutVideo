package com.cutvideo.editor.editor.gl

import com.cutvideo.editor.domain.model.AnimationFrame
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.nativebridge.NativeEffectIds

/**
 * Flattened uniform payload for [CutVideoShaderProgram].
 *
 * Built from the same [Clip] the native CPU path receives, so a filter looks identical in the
 * preview, in the effect cards and in the exported file.
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
    val effectAmounts: FloatArray
)

object GlLookFactory {

    /** Maximum simultaneous GPU effects; extra entries are ignored by the shader (documented). */
    const val MAX_GPU_EFFECTS = 4

    fun from(clip: Clip, aspect: AspectRatio, timelineUs: Long, animation: AnimationFrame?): GlLook {
        val preset = FilterCatalog.preset(clip.filter.id)
        val intensity = if (clip.filter.isActive) clip.filter.intensity else 0f
        val m = preset.matrix

        // Preset matrices are row-major with the translation in the 4th column. The shader multiplies
        // column vectors, so hand it the transposed 3x3 (i.e. column-major order for glUniformMatrix3fv).
        val columnMajor = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

        // The preset's built-in grain becomes a real grain effect entry.
        val effectIds = ArrayList<Int>(MAX_GPU_EFFECTS)
        val effectAmounts = ArrayList<Float>(MAX_GPU_EFFECTS)
        clip.effects.forEach { effect ->
            if (effect.intensity > 0f && effect.id != "none" && effectIds.size < MAX_GPU_EFFECTS) {
                effectIds += NativeEffectIds.idOf(effect.id)
                effectAmounts += effect.intensity
            }
        }
        if (preset.grain > 0f && intensity > 0f && effectIds.size < MAX_GPU_EFFECTS) {
            effectIds += NativeEffectIds.idOf("grain")
            effectAmounts += preset.grain * intensity
        }

        val key = clip.chroma.keyColor
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
            blurRadiusPx = clip.blur + (animation?.blurRadiusPx ?: 0f),
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
            effectAmounts = effectAmounts.toFloatArray()
        )
    }
}
