package com.cutvideo.editor.editor

import com.cutvideo.editor.domain.model.Keyframe
import com.cutvideo.editor.domain.model.KeyframeInterpolation
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.KeyframeTrack
import kotlin.math.abs

/**
 * Evaluates [KeyframeTrack]s — the single source of truth for keyframe interpolation used by the
 * preview (Compose + GL uniforms), the export (`CutVideoGlEffect`) and the Graph editor.
 *
 * Pure Kotlin with no Android dependencies so every curve is unit-testable (`KeyframeEngineTest`).
 */
object KeyframeEngine {

    /** Value of [track] at clip-local [timeUs], or [default] when the track has no keyframes. */
    fun evaluate(track: KeyframeTrack?, timeUs: Long, default: Float): Float {
        if (track == null) return default
        val keys = track.sorted
        if (keys.isEmpty()) return default
        if (keys.size == 1) return keys[0].value
        if (timeUs <= keys.first().timeUs) return keys.first().value
        if (timeUs >= keys.last().timeUs) return keys.last().value

        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (timeUs >= a.timeUs && timeUs <= b.timeUs) {
                if (a.interpolation == KeyframeInterpolation.HOLD) return a.value
                val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
                val x = (timeUs - a.timeUs).toFloat() / span
                val eased = ease(a, x)
                return a.value + (b.value - a.value) * eased
            }
        }
        return keys.last().value
    }

    /** Applies easing for the segment starting at [a] at normalised progress [x] (0..1). */
    fun ease(a: Keyframe, x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return when (a.interpolation) {
            KeyframeInterpolation.LINEAR -> t
            KeyframeInterpolation.HOLD -> 0f
            KeyframeInterpolation.EASE_IN -> t * t
            KeyframeInterpolation.EASE_OUT -> 1f - (1f - t) * (1f - t)
            KeyframeInterpolation.EASE_IN_OUT ->
                if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)
            KeyframeInterpolation.BEZIER -> cubicBezierY(a.bezierInX, a.bezierInY, a.bezierOutX, a.bezierOutY, t)
        }
    }

    /**
     * CSS-style `cubic-bezier(x1, y1, x2, y2)`: given the animation progress `x` (time fraction),
     * returns the eased value fraction. The curve is `B(t)` with `Bx(t) = x`; solved with Newton's
     * method and a bisection fallback, matching how animation tools render their curves.
     */
    fun cubicBezierY(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val target = x.coerceIn(0f, 1f)
        var t = target
        // Newton iterations on Bx(t) - target.
        repeat(8) {
            val bx = bezierAxis(t, x1, x2)
            val dx = bezierAxisDerivative(t, x1, x2)
            if (abs(dx) < 1e-6f) return@repeat
            val delta = (bx - target) / dx
            t -= delta
            if (abs(delta) < 1e-6f) return bezierAxis(t.coerceIn(0f, 1f), y1, y2)
        }
        // Bisection fallback keeps the result monotone in x even for extreme handles.
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (bezierAxis(mid, x1, x2) < target) lo = mid else hi = mid
        }
        return bezierAxis((lo + hi) / 2f, y1, y2)
    }

    private fun bezierAxis(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    private fun bezierAxisDerivative(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * a + 6f * u * t * (b - a) + 3f * t * t * (1f - b)
    }

    /** The value every other code path should use when a property has no keyframes. */
    fun valueOr(property: KeyframeProperty, tracks: List<KeyframeTrack>, timeUs: Long): Float =
        evaluate(tracks.firstOrNull { it.property == property }, timeUs, property.defaultValue)

    // --- track editing helpers (return new lists; never mutate) -----------------------------------

    fun withKeyframe(track: KeyframeTrack, keyframe: Keyframe): KeyframeTrack =
        track.copy(keyframes = (track.keyframes.filterNot { it.timeUs == keyframe.timeUs } + keyframe)
            .sortedBy { it.timeUs })

    fun withoutKeyframe(track: KeyframeTrack, timeUs: Long): KeyframeTrack =
        track.copy(keyframes = track.keyframes.filterNot { it.timeUs == timeUs })

    fun moveKeyframe(track: KeyframeTrack, fromUs: Long, toUs: Long, newValue: Float? = null): KeyframeTrack =
        track.copy(
            keyframes = track.keyframes.map {
                if (it.timeUs == fromUs) {
                    it.copy(timeUs = toUs.coerceAtLeast(0L), value = newValue ?: it.value)
                } else it
            }.sortedBy { it.timeUs }
        )

    fun withInterpolation(track: KeyframeTrack, timeUs: Long, interpolation: KeyframeInterpolation): KeyframeTrack =
        track.copy(keyframes = track.keyframes.map {
            if (it.timeUs == timeUs) it.copy(interpolation = interpolation) else it
        })

    fun withHandles(
        track: KeyframeTrack,
        timeUs: Long,
        bezierInX: Float,
        bezierInY: Float,
        bezierOutX: Float,
        bezierOutY: Float
    ): KeyframeTrack = track.copy(keyframes = track.keyframes.map {
        if (it.timeUs == timeUs) it.copy(
            interpolation = KeyframeInterpolation.BEZIER,
            bezierInX = bezierInX.coerceIn(0f, 1f),
            bezierInY = bezierInY.coerceIn(-1f, 2f),
            bezierOutX = bezierOutX.coerceIn(0f, 1f),
            bezierOutY = bezierOutY.coerceIn(-1f, 2f)
        ) else it
    })

    /** Samples a track into [steps]+1 points for the graph editor curve. */
    fun sampleCurve(track: KeyframeTrack, fromUs: Long, toUs: Long, steps: Int = 96): List<Pair<Float, Float>> {
        if (toUs <= fromUs || steps <= 0) return emptyList()
        val fallback = track.sorted.firstOrNull()?.value ?: 0f
        return (0..steps).map { i ->
            val t = fromUs + (toUs - fromUs) * i / steps
            (t - fromUs).toFloat() / (toUs - fromUs) to evaluate(track, t, fallback)
        }
    }
}
