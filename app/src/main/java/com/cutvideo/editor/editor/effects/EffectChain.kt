package com.cutvideo.editor.editor.effects

import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.EffectCatalog
import com.cutvideo.editor.domain.model.EffectDef
import com.cutvideo.editor.domain.model.EffectSpec
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.KeyframeTrack
import com.cutvideo.editor.editor.KeyframeEngine

/**
 * The effect chain resolver — the single source of truth that turns a clip's ordered effect list
 * into concrete parameter values at a point in time.
 *
 * Both renderers consume exactly this output: the preview (`CutVideoGlShaderProgram`) and the
 * export (the same shader program via Media3) cannot drift because there is nothing else to
 * compute. Pure Kotlin — `EffectChainTest` pins the resolution rules.
 */
object EffectChain {

    /** One effect resolved for a specific frame time. */
    data class ResolvedEffect(
        val spec: EffectSpec,
        val def: EffectDef,
        /** Parameter values with keyframes applied (definition order is preserved by [values]). */
        val values: List<Float>,
        val intensity: Float,
        val blendOrdinal: Int,
        val enabled: Boolean
    ) {
        /** Value of the parameter at [index] of `def.params`. */
        fun value(index: Int): Float = values.getOrElse(index) { 0f }

        fun value(key: String, fallback: Float = 0f): Float {
            val index = def.params.indexOfFirst { it.key == key }
            return if (index >= 0) value(index) else fallback
        }
    }

    /**
     * Resolves the chain in render order (list order = processing order). Disabled effects and
     * zero-intensity effects are kept out of the result entirely so the GPU does no work for them.
     */
    fun resolve(clip: Clip, timeUs: Long): List<ResolvedEffect> =
        clip.effects.mapNotNull { spec -> resolveOne(spec, clip.keyframes, timeUs) }

    fun resolveOne(spec: EffectSpec, tracks: List<KeyframeTrack>, timeUs: Long): ResolvedEffect? {
        val def = EffectCatalog.def(spec.id)
        val intensity = intensityOf(spec, tracks, timeUs)
        if (!spec.enabled || intensity <= 0.0005f) return null
        val values = def.params.map { paramDef ->
            paramOf(spec, paramDef.key, paramDef.default, tracks, timeUs)
                .coerceIn(paramDef.min, paramDef.max)
        }
        return ResolvedEffect(
            spec = spec,
            def = def,
            values = values,
            intensity = intensity.coerceIn(0f, 1f),
            blendOrdinal = spec.blend.ordinal,
            enabled = true
        )
    }

    /**
     * Intensity resolution. A track targeting this exact instance replaces the static value (the
     * new per-effect "animate this effect" behaviour). A track with no target is the legacy master
     * key: it scales every effect's intensity by `value / default` so old projects render as before.
     */
    fun intensityOf(spec: EffectSpec, tracks: List<KeyframeTrack>, timeUs: Long): Float {
        val own = tracks.firstOrNull {
            it.property == KeyframeProperty.EFFECT_INTENSITY && it.isNotEmpty &&
                it.targetId == spec.instanceId
        }
        if (own != null) return KeyframeEngine.evaluate(own, timeUs, spec.intensity)
        val master = tracks.firstOrNull {
            it.property == KeyframeProperty.EFFECT_INTENSITY && it.isNotEmpty && it.targetId.isEmpty()
        } ?: return spec.intensity
        val scale = KeyframeEngine.evaluate(
            master, timeUs, KeyframeProperty.EFFECT_INTENSITY.defaultValue
        ) / KeyframeProperty.EFFECT_INTENSITY.defaultValue.coerceAtLeast(0.001f)
        return (spec.intensity * scale).coerceIn(0f, 1f)
    }

    /**
     * One parameter: `EFFECT_PARAM` tracks targeting this instance and parameter override the
     * stored value (falling back to the definition default when the map has no entry).
     */
    fun paramOf(spec: EffectSpec, key: String, default: Float, tracks: List<KeyframeTrack>, timeUs: Long): Float {
        val base = spec.params[key] ?: default
        val track = tracks.firstOrNull {
            it.property == KeyframeProperty.EFFECT_PARAM && it.param == key && it.isNotEmpty &&
                it.targetId == spec.instanceId
        } ?: return base
        return KeyframeEngine.evaluate(track, timeUs, base)
    }

    // --- chain editing helpers (immutable; the controller wraps them in undoable commands) --------

    fun moved(chain: List<EffectSpec>, instanceId: String, delta: Int): List<EffectSpec> {
        val index = chain.indexOfFirst { it.instanceId == instanceId }
        val target = (index + delta).coerceIn(0, chain.size - 1)
        if (index < 0 || target == index) return chain
        val next = chain.toMutableList()
        next.removeAt(index)
        next.add(target, chain[index])
        return next
    }

    fun duplicated(chain: List<EffectSpec>, instanceId: String): List<EffectSpec> {
        val index = chain.indexOfFirst { it.instanceId == instanceId }
        if (index < 0) return chain
        val copy = chain[index].copy(instanceId = com.cutvideo.editor.domain.model.Ids.newId("fx"))
        return chain.toMutableList().apply { add(index + 1, copy) }
    }

    fun toggled(chain: List<EffectSpec>, instanceId: String): List<EffectSpec> =
        chain.map { if (it.instanceId == instanceId) it.copy(enabled = !it.enabled) else it }

    fun withParam(chain: List<EffectSpec>, instanceId: String, key: String, value: Float): List<EffectSpec> =
        chain.map {
            if (it.instanceId == instanceId) it.copy(params = it.params + (key to value)) else it
        }
}
