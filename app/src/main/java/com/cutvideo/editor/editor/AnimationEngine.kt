package com.cutvideo.editor.editor

import com.cutvideo.editor.domain.model.AnimationFrame
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.TransitionType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin mirror of `timeline_renderer.cpp`.
 *
 * The Compose preview needs the animation transform on the UI thread at 60fps, so it samples here.
 * The export path uses the identical curves through [CutVideoGlEffect], and both are verified against
 * the C++ implementation in `tools/native_tests.cpp`. Curves must be kept in sync with
 * `applyAnimation()` in C++.
 */
object AnimationEngine {

    fun sample(type: ClipAnimationType, progress: Float): AnimationFrame {
        val t = progress.coerceIn(0f, 1f)
        return when (type) {
            ClipAnimationType.NONE -> AnimationFrame()
            ClipAnimationType.FADE -> AnimationFrame(alpha = t)
            ClipAnimationType.ZOOM_IN -> AnimationFrame(alpha = min(1f, t * 1.6f), scale = 0.62f + 0.38f * t)
            ClipAnimationType.ZOOM_OUT -> AnimationFrame(alpha = min(1f, t * 1.6f), scale = 1.38f - 0.38f * t)
            ClipAnimationType.SLIDE_LEFT -> AnimationFrame(alpha = min(1f, t * 2f), translateX = 1f - t)
            ClipAnimationType.SLIDE_RIGHT -> AnimationFrame(alpha = min(1f, t * 2f), translateX = -(1f - t))
            ClipAnimationType.SLIDE_UP -> AnimationFrame(alpha = min(1f, t * 2f), translateY = 1f - t)
            ClipAnimationType.SLIDE_DOWN -> AnimationFrame(alpha = min(1f, t * 2f), translateY = -(1f - t))
            ClipAnimationType.ROTATE_IN -> AnimationFrame(
                alpha = min(1f, t * 1.8f),
                scale = 0.78f + 0.22f * t,
                rotationDegrees = (1f - t) * -28f
            )
            ClipAnimationType.BLUR_IN -> AnimationFrame(
                alpha = min(1f, t * 1.4f),
                blurRadiusPx = (1f - t) * 18f
            )
            ClipAnimationType.POP -> AnimationFrame(alpha = min(1f, t * 3f), scale = easeOutBack(t))
            ClipAnimationType.FLICKER -> {
                val flick = 0.55f + 0.45f * sin(t * 42f)
                val blend = smoothstep(0.55f, 1f, t)
                AnimationFrame(alpha = flick + (1f - flick) * blend)
            }
            ClipAnimationType.SHAKE -> AnimationFrame(
                alpha = min(1f, t * 2.5f),
                translateX = sin(t * 55f) * (1f - t) * 0.10f,
                translateY = cos(t * 47f) * (1f - t) * 0.06f
            )
            ClipAnimationType.WOBBLE -> AnimationFrame(
                alpha = min(1f, t * 1.8f),
                scale = 0.90f + 0.10f * t,
                rotationDegrees = sin(t * 22f) * (1f - t) * 14f
            )
            ClipAnimationType.BOUNCE -> {
                val hop = abs(sin(t * 12.566f)) * (1f - t)
                AnimationFrame(
                    alpha = min(1f, t * 3f),
                    scale = 1f - 0.35f * (1f - t) + 0.18f * hop
                )
            }
            ClipAnimationType.SPIN_IN -> AnimationFrame(
                alpha = min(1f, t * 1.8f),
                scale = 0.60f + 0.40f * t,
                rotationDegrees = (1f - t) * -180f
            )
            ClipAnimationType.FLIP_IN -> AnimationFrame(
                alpha = min(1f, t * 1.6f),
                scale = abs(cos((1f - t) * 1.5707964f))
            )
            ClipAnimationType.GLITCH_IN -> AnimationFrame(
                alpha = t,
                translateX = sin(t * 70f) * (1f - t) * 0.05f,
                blurRadiusPx = (1f - t) * 6f
            )
        }
    }

    fun sampleTransition(type: TransitionType, progress: Float): AnimationFrame {
        val t = progress.coerceIn(0f, 1f)
        return when (type) {
            TransitionType.FADE -> AnimationFrame(alpha = t)
            TransitionType.SLIDE_LEFT -> AnimationFrame(translateX = 1f - t)
            TransitionType.SLIDE_UP -> AnimationFrame(translateY = 1f - t)
            TransitionType.ZOOM -> AnimationFrame(alpha = t, scale = 0.72f + 0.28f * t)
            TransitionType.WIPE -> AnimationFrame(wipe = t)
            TransitionType.CIRCLE -> AnimationFrame(wipe = t)
            TransitionType.SLIDE_RIGHT -> AnimationFrame(translateX = -(1f - t))
            TransitionType.SLIDE_DOWN -> AnimationFrame(translateY = -(1f - t))
            TransitionType.GLITCH -> AnimationFrame(
                alpha = t,
                translateX = sin(t * 60f) * (1f - t) * 0.04f,
                wipe = t
            )
            TransitionType.BLUR -> AnimationFrame(alpha = t, blurRadiusPx = (1f - t) * 20f)
            // Phase 3: shader wipe modes 5–8 (see `uWipeMode` in cutvideo_effect.glsl).
            TransitionType.FLASH -> AnimationFrame(wipe = t)
            TransitionType.WHIP_PAN -> AnimationFrame(wipe = t)
            TransitionType.LIGHT_LEAK -> AnimationFrame(wipe = t)
            TransitionType.MASK_WIPE -> AnimationFrame(wipe = t)
        }
    }

    /** Combines in/out animations and boundary transitions for a clip at [timelineUs]. */
    fun compose(clip: Clip, timelineUs: Long): AnimationFrame {
        var alpha = 1f
        var scale = 1f
        var tx = 0f
        var ty = 0f
        var rotation = 0f
        var blur = 0f
        var wipe = 1f

        val local = timelineUs - clip.startUs

        if (clip.animationIn.isActive && local < clip.animationIn.durationUs) {
            val f = sample(clip.animationIn.type, progress(local, clip.animationIn.durationUs))
            alpha *= f.alpha; scale *= f.scale
            tx += f.translateX; ty += f.translateY
            rotation += f.rotationDegrees; blur += f.blurRadiusPx
        }

        if (clip.animationOut.isActive) {
            val outStart = clip.durationUs - clip.animationOut.durationUs
            if (local > outStart) {
                val f = sample(clip.animationOut.type, progress(clip.durationUs - local, clip.animationOut.durationUs))
                alpha *= f.alpha; scale *= f.scale
                tx += f.translateX; ty += f.translateY
                rotation += f.rotationDegrees; blur += f.blurRadiusPx
            }
        }

        clip.transitionIn?.let { spec ->
            if (local < spec.durationUs) {
                val f = sampleTransition(spec.type, progress(local, spec.durationUs))
                alpha *= f.alpha; scale *= f.scale
                tx += f.translateX; ty += f.translateY
                rotation += f.rotationDegrees; blur += f.blurRadiusPx
                wipe = min(wipe, f.wipe)
            }
        }

        clip.transitionOut?.let { spec ->
            val outStart = clip.durationUs - spec.durationUs
            if (local > outStart) {
                val f = sampleTransition(spec.type, progress(clip.durationUs - local, spec.durationUs))
                alpha *= f.alpha; scale *= f.scale
                tx -= f.translateX; ty -= f.translateY
                rotation -= f.rotationDegrees; blur += f.blurRadiusPx
                wipe = min(wipe, f.wipe)
            }
        }

        return AnimationFrame(
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale,
            translateX = tx,
            translateY = ty,
            rotationDegrees = rotation,
            blurRadiusPx = blur,
            wipe = wipe.coerceIn(0f, 1f)
        ).withKeyframes(clip, timelineUs)
    }

    /**
     * Merges the keyframed transform/opacity values into the animation frame. These four
     * properties are owned by [compose] (the preview applies them through `graphicsLayer`, the
     * export through the shader transform uniforms), so no other layer should re-apply them.
     * Blur / mask / effect / volume keyframes are evaluated by their own owners.
     */
    private fun AnimationFrame.withKeyframes(clip: Clip, timelineUs: Long): AnimationFrame {
        if (!clip.hasKeyframes) return this
        val tracks = clip.keyframes
        val kfScale = KeyframeEngine.valueOr(KeyframeProperty.SCALE, tracks, timelineUs)
        val kfX = KeyframeEngine.valueOr(KeyframeProperty.POSITION_X, tracks, timelineUs)
        val kfY = KeyframeEngine.valueOr(KeyframeProperty.POSITION_Y, tracks, timelineUs)
        val kfRot = KeyframeEngine.valueOr(KeyframeProperty.ROTATION, tracks, timelineUs)
        val kfOpacity = KeyframeEngine.valueOr(KeyframeProperty.OPACITY, tracks, timelineUs)
        return copy(
            alpha = (alpha * kfOpacity).coerceIn(0f, 1f),
            scale = scale * kfScale,
            translateX = translateX + kfX,
            translateY = translateY + kfY,
            rotationDegrees = rotationDegrees + kfRot
        )
    }

    /** Same as [compose] but with a clip-local time (used by the export shader). */
    fun composeLocal(clip: Clip, localUs: Long): AnimationFrame =
        compose(clip.copy(startUs = 0L), localUs)

    /** Wipe mode hint for the native CPU mask / shader: 0 = horizontal, 1 = circular iris. */
    /** Shader `uWipeMode` for a transition (mirror of the wipe block in cutvideo_effect.glsl). */
    fun wipeMode(transition: TransitionType?): Int = when (transition) {
        TransitionType.WIPE -> 0
        TransitionType.CIRCLE -> 1
        TransitionType.GLITCH -> 3
        TransitionType.BLUR -> 4
        TransitionType.FLASH -> 5
        TransitionType.WHIP_PAN -> 6
        TransitionType.LIGHT_LEAK -> 7
        TransitionType.MASK_WIPE -> 8
        else -> 9 // cross fade (and anything driven purely by transform/alpha)
    }

    private fun progress(elapsedUs: Long, windowUs: Long): Float =
        if (windowUs <= 0L) 1f else (elapsedUs.toFloat() / windowUs.toFloat()).coerceIn(0f, 1f)

    private fun easeOutBack(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = p - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
