package com.cutvideo.editor.editor.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.editor.AnimationEngine
import com.cutvideo.editor.editor.TextRasterizer

/**
 * Text / caption compositor for the export pipeline.
 *
 * Each entry is a real rasterised text bitmap with the clip that produced it, so the animation and
 * fade curves come from the same document as the Compose preview.
 */
@UnstableApi
data class TextOverlayEntry(
    val clip: Clip,
    val raster: TextRasterizer.RasterizedText,
    /** Local time inside the sequence item where the text appears (us). */
    val windowStartUs: Long,
    val windowEndUs: Long
)

@UnstableApi
class OverlayCompositorGlEffect(
    private val entries: List<TextOverlayEntry>
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        OverlayCompositorGlShaderProgram(context.applicationContext, entries, useHdr)
}

@UnstableApi
private class OverlayCompositorGlShaderProgram(
    context: Context,
    private val entries: List<TextOverlayEntry>,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 2) {

    private val program = OverlayDrawProgram().apply { init(context) }
    private var frameWidth = 1920
    private var frameHeight = 1080
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val mvp = FloatArray(16)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth
        frameHeight = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // 1. Blit the (already colour-graded) frame straight through.
        program.draw(inputTexId, identity, 1f, blend = false)

        // 2. Composite every visible text overlay.
        entries.forEach { entry ->
            if (presentationTimeUs < entry.windowStartUs || presentationTimeUs > entry.windowEndUs) {
                return@forEach
            }
            val localUs = presentationTimeUs - entry.clip.startUs
            val animation = AnimationEngine.composeLocal(entry.clip, localUs)
            if (animation.alpha <= 0.01f) return@forEach

            val raster = entry.raster
            val scale = animation.scale
            val centerX = (raster.centerXFrac * 2f - 1f) + animation.translateX * 2f
            val centerY = (1f - raster.centerYFrac * 2f) - animation.translateY * 2f
            val widthNdc = raster.widthFrac
            val heightNdc = raster.heightFrac

            Matrix.setIdentityM(mvp, 0)
            Matrix.translateM(mvp, 0, centerX, centerY, 0f)
            Matrix.rotateM(mvp, 0, raster.rotationDegrees + animation.rotationDegrees, 0f, 0f, 1f)
            Matrix.scaleM(mvp, 0, widthNdc * scale, heightNdc * scale, 1f)

            val texture = program.textureFor(raster.bitmap)
            program.draw(texture, mvp, animation.alpha, blend = true)
        }
    }

    override fun release() {
        program.release()
        entries.forEach { if (!it.raster.bitmap.isRecycled) it.raster.bitmap.recycle() }
        super.release()
    }

    companion object {
        /** Convenience for tests / previews. */
        fun overlayFor(clip: Clip, frameWidth: Int, frameHeight: Int): TextOverlayEntry? {
            val spec = clip.text ?: return null
            val raster = TextRasterizer.rasterize(spec, frameWidth, frameHeight) ?: return null
            return TextOverlayEntry(clip, raster, clip.startUs, clip.endUs)
        }
    }
}

/** Bitmap holder so media3 does not need to know about our text model. */
@UnstableApi
internal fun Bitmap.asOverlay(): Bitmap = this
