package com.cutvideo.editor.editor.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Textured-quad program used for the text/overlay composite pass and for the base blit.
 *
 * Kept separate from [CutVideoShaderProgram] so the colour pass and the compositing pass can be
 * chained as two Media3 effects without either one owning the other's GL state.
 *
 * Like the colour program, this supports both ES 3.0 and ES 2.0 contexts: the asset shaders are ES
 * 3.0 and are converted for ES 2.0 on the fly (see [GlslCompat]) because Media3 falls back to an ES
 * 2.0 context on some devices.
 */
internal class OverlayDrawProgram {

    private var handle = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var samplerLocation = -1
    private var mvpLocation = -1
    private var alphaLocation = -1

    private val textures = HashMap<Int, Int>()

    private val positionBuffer: FloatBuffer = bufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoordBuffer: FloatBuffer = bufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    val isValid: Boolean get() = handle != 0

    fun init(context: Context) {
        if (handle != 0) return
        handle = buildProgram(context)
        if (handle == 0) return
        positionLocation = GLES20.glGetAttribLocation(handle, "aFramePosition")
        texCoordLocation = GLES20.glGetAttribLocation(handle, "aTexSamplingCoord")
        samplerLocation = GLES20.glGetUniformLocation(handle, "uTexSampler")
        mvpLocation = GLES20.glGetUniformLocation(handle, "uMvp")
        alphaLocation = GLES20.glGetUniformLocation(handle, "uAlpha")
    }

    /** Uploads (once) and returns the GL texture name for a bitmap. */
    fun textureFor(bitmap: Bitmap): Int {
        val key = System.identityHashCode(bitmap)
        textures[key]?.let { if (GLES20.glIsTexture(it)) return it }
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[key] = names[0]
        return names[0]
    }

    fun draw(textureId: Int, mvp: FloatArray, alpha: Float, blend: Boolean) {
        if (handle == 0) return
        GLES20.glUseProgram(handle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (samplerLocation >= 0) GLES20.glUniform1i(samplerLocation, 0)
        if (mvpLocation >= 0) GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        if (alphaLocation >= 0) GLES20.glUniform1f(alphaLocation, alpha)

        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        if (texCoordLocation >= 0) {
            GLES20.glEnableVertexAttribArray(texCoordLocation)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        if (texCoordLocation >= 0) GLES20.glDisableVertexAttribArray(texCoordLocation)
        if (blend) GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        textures.clear()
        if (handle != 0) {
            GLES20.glDeleteProgram(handle)
            handle = 0
        }
    }

    private fun buildProgram(context: Context): Int {
        val vertexEs3 = load(context, "shaders/cutvideo_overlay_vertex.glsl", FALLBACK_VERTEX_ES3)
        val fragmentEs3 = load(context, "shaders/cutvideo_overlay_fragment.glsl", FALLBACK_FRAGMENT_ES3)
        val es3 = CutVideoShaderProgram.glContextIsEs3()
        val attempts = if (es3) {
            listOf(vertexEs3 to fragmentEs3, es2(vertexEs3, false) to es2(fragmentEs3, true))
        } else {
            listOf(es2(vertexEs3, false) to es2(fragmentEs3, true), vertexEs3 to fragmentEs3)
        }
        for ((vertex, fragment) in attempts) {
            if (vertex.isEmpty() || fragment.isEmpty()) continue
            val program = link(vertex, fragment)
            if (program != 0) return program
        }
        Log.e(TAG, "overlay program failed to build on this GL context")
        return 0
    }

    private fun es2(source: String, fragment: Boolean): String = try {
        GlslCompat.toEs2(source, fragment)
    } catch (t: Throwable) {
        Log.e(TAG, "cannot build the ES 2.0 overlay variant", t)
        ""
    }

    private fun link(vertexSource: String, fragmentSource: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "overlay program link failed: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "overlay shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun load(context: Context, path: String, fallback: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        fallback
    }

    private companion object {
        const val TAG = "OverlayDrawProgram"

        val FALLBACK_VERTEX_ES3 = """
            #version 300 es
            in vec4 aFramePosition;
            in vec4 aTexSamplingCoord;
            uniform mat4 uMvp;
            out vec2 vTex;
            void main() {
                gl_Position = uMvp * aFramePosition;
                vTex = aTexSamplingCoord.xy;
            }
        """.trimIndent()

        val FALLBACK_FRAGMENT_ES3 = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 fragColor;
            uniform sampler2D uTexSampler;
            uniform float uAlpha;
            void main() {
                vec4 c = texture(uTexSampler, vTex);
                fragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()

        fun bufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
