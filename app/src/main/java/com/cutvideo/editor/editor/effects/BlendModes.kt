package com.cutvideo.editor.editor.effects

import com.cutvideo.editor.domain.model.BlendMode
import kotlin.math.abs

/**
 * CPU mirror of the GLSL blend functions in `cutvideo_pass.glsl`. The shader and this file are kept
 * identical by convention (`EffectChainTest` pins the formulas) — the CPU copy backs tests and any
 * software rendering path.
 *
 * All functions work per channel on 0..1 values and clamp their result.
 */
object BlendModes {

    fun blend(mode: BlendMode, base: Float, src: Float): Float {
        val b = base.coerceIn(0f, 1f)
        val s = src.coerceIn(0f, 1f)
        val r = when (mode) {
            BlendMode.NORMAL -> s
            BlendMode.ADD -> b + s
            BlendMode.SCREEN -> 1f - (1f - b) * (1f - s)
            BlendMode.MULTIPLY -> b * s
            BlendMode.OVERLAY -> if (b <= 0.5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)
            BlendMode.SOFT_LIGHT -> softLight(b, s)
            BlendMode.HARD_LIGHT -> if (s <= 0.5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)
            BlendMode.DIFFERENCE -> abs(b - s)
        }
        return r.coerceIn(0f, 1f)
    }

    private fun softLight(b: Float, s: Float): Float =
        if (s <= 0.5f) {
            b - (1f - 2f * s) * b * (1f - b)
        } else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else kotlin.math.sqrt(b)
            b + (2f * s - 1f) * (d - b)
        }

    /** Mixes an effect result over its input: `amount` 0 = untouched frame, 1 = full effect. */
    fun composite(mode: BlendMode, base: Float, src: Float, amount: Float): Float {
        val a = amount.coerceIn(0f, 1f)
        return base + (blend(mode, base, src) - base) * a
    }

    fun blend(mode: BlendMode, base: FloatArray, src: FloatArray): FloatArray =
        FloatArray(minOf(base.size, src.size)) { i -> blend(mode, base[i], src[i]) }
}
