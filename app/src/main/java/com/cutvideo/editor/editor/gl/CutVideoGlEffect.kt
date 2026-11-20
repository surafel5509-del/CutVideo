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

    init {
        program.init(context.applicationContext)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setTexelSize(inputWidth, inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(presentationTimeUs: Long, inputTexId: Int, outputTexId: Int) {
        val clip = clipProvider()
        val localUs = localTimeProvider(presentationTimeUs)
        val animation = if (applyAnimation) AnimationEngine.composeLocal(clip, localUs) else null
        program.use()
        program.setLook(GlLookFactory.from(clip, aspect, localUs, animation))
        program.draw(inputTexId)
    }

    override fun release() {
        program.release()
        super.release()
    }
}
