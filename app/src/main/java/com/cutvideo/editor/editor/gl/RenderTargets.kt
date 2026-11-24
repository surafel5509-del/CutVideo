package com.cutvideo.editor.editor.gl

import android.opengl.GLES20

/**
 * A fixed set of frame-sized RGBA render targets for the effect chain.
 *
 * Four targets give every recipe room to read its inputs without overwriting them (the heaviest
 * combine reads three textures). Targets are created once per size and reused across frames —
 * no per-frame allocations, no copies outside the GPU. All GPU resources are deleted in [release].
 */
internal class RenderTargets(val width: Int, val height: Int) {

    val textures = IntArray(COUNT)
    private val framebuffers = IntArray(COUNT)

    init {
        val tex = IntArray(COUNT)
        GLES20.glGenTextures(COUNT, tex, 0)
        val fbo = IntArray(COUNT)
        GLES20.glGenFramebuffers(COUNT, fbo, 0)
        for (i in 0 until COUNT) {
            textures[i] = tex[i]
            framebuffers[i] = fbo[i]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[i], 0
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun bind(index: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[index])
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(COUNT, framebuffers, 0)
        GLES20.glDeleteTextures(COUNT, textures, 0)
        framebuffers.fill(0)
        textures.fill(0)
    }

    companion object {
        const val COUNT = 4
    }
}
