package com.cutvideo.editor.editor.gl

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.editor.AnimationEngine

/**
 * Media3 [GlEffect] wrapper around [CutVideoShaderProgram].
 *
 * The same effect instance is used by:
 *  - `PreviewController` (via `ExoPlayer.setVideoEffects`) so the preview is the real pipeline,
 *  - `TransformerExporter` (per `EditedMediaItem`) so the export is the real pipeline.
 *
 * [localTimeProvider] converts the presentation timestamp Media3 hands to the shader into clip-local
 * microseconds, which is what the animation/transition curves expect.
 */
@UnstableApi
class CutVideoGlEffect(
    private val clipProvider: () -> Clip,
    private val aspect: AspectRatio,
    private val applyAnimation: Boolean,
    private val localTimeProvider: (Long) -> Long = { it }
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CutVideoGlShaderProgram(context, clipProvider, aspect, applyAnimation, localTimeProvider, useHdr)
}

@UnstableApi
private class CutVideoGlShaderProgram(
    context: Context,
    private val clipProvider: () -> Clip,
    private val aspect: AspectRatio,
    private val applyAnimation: Boolean,
    private val localTimeProvider: (Long) -> Long,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val program = CutVideoShaderProgram()
    private val chainRenderer = EffectChainRenderer(program)
    private var outputWidth = 0
    private var outputHeight = 0

    init {
        program.init(context.applicationContext)
        chainRenderer.init(context.applicationContext)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setTexelSize(inputWidth, inputHeight)
        outputWidth = inputWidth
        outputHeight = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val clip = clipProvider()
        val localUs = localTimeProvider(presentationTimeUs)
        val animation = if (applyAnimation) AnimationEngine.composeLocal(clip, localUs) else null
        val look = try {
            GlLookFactory.from(clip, aspect, localUs, animation)
        } catch (t: Throwable) {
            // A broken look must never kill playback: fall back to the untouched input.
            android.util.Log.e("CutVideoEffect", "look build failed, passing frame through", t)
            program.drawBlit(inputTexId)
            return
        }
        try {
            chainRenderer.drawFrame(inputTexId, look, outputWidth, outputHeight)
        } catch (t: Throwable) {
            // Any chain failure degrades to the look-only draw (frames keep flowing; playback
            // stays alive) — then to a raw pass-through if even that fails.
            android.util.Log.e("CutVideoEffect", "chain draw failed, falling back to look pass", t)
            try {
                program.use()
                program.setLook(look)
                program.draw(inputTexId)
            } catch (t2: Throwable) {
                android.util.Log.e("CutVideoEffect", "look draw failed too, passing frame through", t2)
                program.drawBlit(inputTexId)
            }
        }
    }

    override fun release() {
        chainRenderer.release()
        program.release()
        super.release()
    }
}
