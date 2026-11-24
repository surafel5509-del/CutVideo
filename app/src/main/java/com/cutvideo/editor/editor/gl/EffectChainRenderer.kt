package com.cutvideo.editor.editor.gl

import android.opengl.GLES20
import com.cutvideo.editor.domain.model.EffectDef.PassKind
import com.cutvideo.editor.editor.effects.EffectChain
import com.cutvideo.editor.nativebridge.NativeEffectIds

/**
 * Renders the ordered effect chain as real GPU passes.
 *
 * Pipeline per frame:
 *  1. the look program draws the graded/masked/transformed frame into a chain target,
 *  2. each resolved effect becomes one or more passes (see the recipes below) with the result
 *     composited over the running frame using its blend mode and intensity,
 *  3. the final target is blitted into the framework-owned output framebuffer.
 *
 * The recipes are deliberately plain and inspectable — the exact same chain resolution
 * ([EffectChain.resolve]) drives the preview and the export, so the two cannot drift.
 * All work happens in four reusable frame-sized targets ([RenderTargets]); nothing is read back.
 *
 * Pass counts per effect kind: SINGLE = 1 draw, GAUSSIAN = (2·quality + 1) draws, BLOOM =
 * 7..11 draws (bright + separable blur scales + combine), SHADOW = 4 draws.
 */
internal class EffectChainRenderer(private val lookProgram: CutVideoShaderProgram) {

    private val passProgram = EffectPassProgram()
    private var targets: RenderTargets? = null
    private var targetWidth = 0
    private var targetHeight = 0

    fun init(context: android.content.Context) {
        passProgram.init(context)
    }

    /**
     * Draws one frame: `inputTexId` → (look pass) → (chain passes) → the currently bound output
     * framebuffer (Media3 binds it before calling into us).
     */
    fun drawFrame(inputTexId: Int, look: GlLook, width: Int, height: Int) {
        if (look.chain.isEmpty() || !passProgram.isValid || width <= 0 || height <= 0) {
            lookProgram.use()
            lookProgram.setLook(look)
            lookProgram.draw(inputTexId)
            return
        }

        val outState = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outState, 0)
        val outFbo = outState[0]
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, outState, 0)

        val targets = targetsFor(width, height)

        // 1. Look pass: grading, masks, transform, wipe → chain target 0.
        targets.bind(0)
        lookProgram.use()
        lookProgram.setLook(look)
        lookProgram.draw(inputTexId)

        passProgram.setCommon(
            1f / width, 1f / height, look.timeSeconds, look.aspect,
            DUOTONE_SHADOW, DUOTONE_HIGHLIGHT
        )

        // 2. Chain passes in list order (= render order).
        var current = 0
        val used = HashSet<Int>(RenderTargets.COUNT)
        look.chain.forEach { resolved ->
            used.clear()
            used += current
            current = when (resolved.def.passKind) {
                PassKind.SINGLE -> drawSingle(current, resolved, used)
                PassKind.GAUSSIAN -> drawGaussian(current, resolved, used)
                PassKind.BLOOM -> drawBloom(current, resolved, used)
                PassKind.SHADOW -> drawShadow(current, resolved, used)
            }
        }

        // 3. Blit the final chain target into the framework's output framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
        GLES20.glViewport(outState[0], outState[1], outState[2], outState[3])
        passProgram.drawMix(
            baseTex = targets.textures[current],
            processedTex = targets.textures[current],
            blend = 0,
            strength = 0f
        )
    }

    fun release() {
        passProgram.release()
        targets?.release()
        targets = null
    }

    // --- recipes ----------------------------------------------------------------------------------

    private fun drawSingle(current: Int, resolved: EffectChain.ResolvedEffect, used: HashSet<Int>): Int {
        val targets = targets ?: return current
        val next = pickWrite(used)
        targets.bind(next)
        passProgram.drawSingle(
            tex = targets.textures[current],
            effectId = NativeEffectIds.idOf(resolved.spec.id),
            strength = resolved.intensity,
            blend = resolved.blendOrdinal,
            p0 = packParams(resolved, 0),
            p1 = packParams(resolved, 4)
        )
        return next
    }

    /** Separable gaussian: `quality` iterations of H+V, then a blend over the original frame. */
    private fun drawGaussian(current: Int, resolved: EffectChain.ResolvedEffect, used: HashSet<Int>): Int {
        val targets = targets ?: return current
        val radius = resolved.value("radius", 12f)
        val quality = resolved.value("quality", 1f).toInt().coerceIn(1, 3)
        val perPassRadius = radius / quality * 0.6f

        val preserved = HashSet(used)
        preserved += current
        var blurred = current
        repeat(quality) {
            blurred = blurOnce(blurred, perPassRadius, preserved)
            preserved += blurred
        }

        val out = pickWrite(preserved)
        targets.bind(out)
        passProgram.drawMix(
            baseTex = targets.textures[current],
            processedTex = targets.textures[blurred],
            blend = resolved.blendOrdinal,
            strength = resolved.intensity
        )
        return out
    }

    /** Multi-pass glow/bloom: bright-pass → separable blur scales → tinted combine over the frame. */
    private fun drawBloom(current: Int, resolved: EffectChain.ResolvedEffect, used: HashSet<Int>): Int {
        val targets = targets ?: return current
        val threshold = resolved.value("threshold", 0.55f)
        val radius = resolved.value("radius", 6f)
        val levels = resolved.value("levels", 2f).toInt().coerceIn(1, 3)
        val tint = floatArrayOf(
            resolved.value("tintR", 1f), resolved.value("tintG", 1f), resolved.value("tintB", 1f)
        )

        val preserved = HashSet(used)
        preserved += current

        val bright = pickWrite(preserved)
        targets.bind(bright)
        passProgram.drawBright(targets.textures[current], threshold)
        preserved += bright

        var scale1 = blurOnce(bright, radius, preserved)
        preserved += scale1
        var scaleN = scale1
        for (level in 2..levels) {
            scaleN = blurOnce(scaleN, radius * level, preserved)
            preserved += scaleN
        }

        val out = pickWrite(preserved)
        targets.bind(out)
        val weights = when (levels) {
            1 -> floatArrayOf(1f, 0f)
            2 -> floatArrayOf(0.72f, 0.45f)
            else -> floatArrayOf(0.55f, 0.35f)
        }
        passProgram.drawCombine(
            scale1Tex = targets.textures[scale1],
            scale2Tex = targets.textures[scaleN],
            baseTex = targets.textures[current],
            w1 = weights[0], w2 = weights[1], tint = tint,
            blend = resolved.blendOrdinal,
            strength = resolved.intensity
        )
        return out
    }

    /** Drop shadow: offset+dilated alpha silhouette → gaussian blur → composite over the frame. */
    private fun drawShadow(current: Int, resolved: EffectChain.ResolvedEffect, used: HashSet<Int>): Int {
        val targets = targets ?: return current
        val offsetX = resolved.value("offsetX", 0.02f)
        val offsetY = resolved.value("offsetY", -0.02f)
        val blur = resolved.value("blur", 12f)
        val spread = resolved.value("spread", 0.2f)

        val preserved = HashSet(used)
        preserved += current

        val sil = pickWrite(preserved)
        targets.bind(sil)
        passProgram.drawShadowSilhouette(targets.textures[current], offsetX, offsetY, spread)
        preserved += sil

        val mask = blurOnce(sil, blur, preserved)
        preserved += mask

        val out = pickWrite(preserved)
        targets.bind(out)
        passProgram.drawShadowComposite(targets.textures[mask], targets.textures[current], resolved.intensity)
        return out
    }

    /** One separable blur (H then V) from [src] into a fresh target; returns that target. */
    private fun blurOnce(src: Int, radius: Float, preserved: Set<Int>): Int {
        val targets = targets ?: return src
        val h = pickWrite(preserved + src)
        targets.bind(h)
        passProgram.drawBlur(targets.textures[src], radius / targetWidth, 0f)
        val v = pickWrite(preserved + src + h)
        targets.bind(v)
        passProgram.drawBlur(targets.textures[h], 0f, radius / targetHeight)
        return v
    }

    private fun packParams(resolved: EffectChain.ResolvedEffect, offset: Int): FloatArray {
        val out = FloatArray(4)
        for (i in 0 until 4) out[i] = resolved.values.getOrElse(offset + i) { 0f }
        return out
    }

    private fun pickWrite(reads: Set<Int>): Int {
        for (i in 0 until RenderTargets.COUNT) {
            if (i !in reads) return i
        }
        return 0
    }

    private fun targetsFor(width: Int, height: Int): RenderTargets {
        val existing = targets
        if (existing != null && targetWidth == width && targetHeight == height) return existing
        existing?.release()
        val created = RenderTargets(width, height)
        targets = created
        targetWidth = width
        targetHeight = height
        return created
    }

    private companion object {
        val DUOTONE_SHADOW = floatArrayOf(0.06f, 0.09f, 0.16f)
        val DUOTONE_HIGHLIGHT = floatArrayOf(0.96f, 0.83f, 1.0f)
    }
}
