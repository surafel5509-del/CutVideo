package com.cutvideo.editor.editor.gl

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal OpenGL ES 3.0 program for the CutVideo colour/effect shader.
 *
 * Written directly against GLES20 (which is valid inside an ES 3.0 context) instead of a helper
 * library so the whole pipeline is explicit: one full-screen triangle strip in, one texture in, the
 * framework-owned framebuffer out.
 */
internal class CutVideoShaderProgram {

    private var handle = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var samplerLocation = -1

    private val uniforms = HashMap<String, Int>(32)
    private val positionBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val texCoordBuffer: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    val isValid: Boolean get() = handle != 0

    fun init(context: Context) {
        if (handle != 0) return
        val vertex = loadShaderSource(context, "shaders/cutvideo_effect_vertex.glsl", FALLBACK_VERTEX)
        val fragment = loadShaderSource(context, "shaders/cutvideo_effect.glsl", FALLBACK_FRAGMENT)
        handle = createProgram(vertex, fragment)
        if (handle == 0) {
            Log.e(TAG, "shader program could not be created")
            return
        }
        positionLocation = GLES20.glGetAttribLocation(handle, "aFramePosition")
        texCoordLocation = GLES20.glGetAttribLocation(handle, "aTexSamplingCoord")
        samplerLocation = GLES20.glGetUniformLocation(handle, "uTexSampler")
    }

    fun use() {
        if (handle != 0) GLES20.glUseProgram(handle)
    }

    fun setTexelSize(width: Int, height: Int) {
        uniform2f("uTexelSize", 1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1))
    }

    fun setLook(look: GlLook) {
        uniformMatrix3("uFilterMatrix", look.filterMatrix)
        uniform3f("uFilterOffset", look.filterOffset[0], look.filterOffset[1], look.filterOffset[2])
        uniform1f("uFilterIntensity", look.filterIntensity)
        uniform1f("uGamma", look.gamma)
        uniform1f("uBrightness", look.brightness)
        uniform1f("uContrast", look.contrast)
        uniform1f("uSaturation", look.saturation)
        uniform1f("uExposure", look.exposure)
        uniform1f("uTemperature", look.temperature)
        uniform1f("uTint", look.tint)
        uniform1f("uVignette", look.vignette)
        uniform1f("uSharpen", look.sharpen)
        uniform1f("uBlurRadius", look.blurRadiusPx)
        uniform1i("uChromaEnabled", if (look.chromaEnabled) 1 else 0)
        uniform3f("uChromaKey", look.chromaKey[0], look.chromaKey[1], look.chromaKey[2])
        uniform1f("uChromaSimilarity", look.chromaSimilarity)
        uniform1f("uChromaSmoothness", look.chromaSmoothness)
        uniform1f("uChromaSpill", look.chromaSpill)
        uniform1f("uAlpha", look.alpha)
        uniform1f("uWipe", look.wipe)
        uniform1i("uWipeMode", look.wipeMode)
        uniform1f("uTime", look.timeSeconds)
        uniform1f("uAspect", look.aspect)
        uniform3f("uDuotoneShadow", 0.06f, 0.09f, 0.16f)
        uniform3f("uDuotoneHighlight", 0.96f, 0.83f, 1.0f)
        setEffects(look.effectIds, look.effectAmounts)
    }

    private fun setEffects(ids: IntArray, amounts: FloatArray) {
        val idArray = IntArray(4)
        val amountArray = FloatArray(4)
        for (i in 0 until minOf(4, ids.size)) {
            idArray[i] = ids[i]
            amountArray[i] = amounts.getOrElse(i) { 0f }
        }
        val idLocation = uniform("uEffectIds[0]")
        if (idLocation >= 0) GLES20.glUniform1iv(idLocation, 4, idArray, 0)
        val amountLocation = uniform("uEffectAmounts[0]")
        if (amountLocation >= 0) GLES20.glUniform1fv(amountLocation, 4, amountArray, 0)
    }

    fun draw(inputTextureId: Int) {
        if (handle == 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (samplerLocation >= 0) GLES20.glUniform1i(samplerLocation, 0)

        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        if (texCoordLocation >= 0) {
            GLES20.glEnableVertexAttribArray(texCoordLocation)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLocation)
        if (texCoordLocation >= 0) GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    fun release() {
        if (handle != 0) {
            GLES20.glDeleteProgram(handle)
            handle = 0
        }
        uniforms.clear()
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun uniform(name: String): Int {
        uniforms[name]?.let { return it }
        val location = if (handle != 0) GLES20.glGetUniformLocation(handle, name) else -1
        uniforms[name] = location
        return location
    }

    private fun uniform1f(name: String, value: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1f(location, value)
    }

    private fun uniform1i(name: String, value: Int) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1i(location, value)
    }

    private fun uniform2f(name: String, a: Float, b: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform2f(location, a, b)
    }

    private fun uniform3f(name: String, a: Float, b: Float, c: Float) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform3f(location, a, b, c)
    }

    private fun uniformMatrix3(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniformMatrix3fv(location, 1, false, values, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(program))
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
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun loadShaderSource(context: Context, path: String, fallback: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        Log.w(TAG, "falling back to built-in shader for $path", t)
        fallback
    }

    private companion object {
        const val TAG = "CutVideoShader"

        val FALLBACK_VERTEX = """
            #version 300 es
            in vec4 aFramePosition;
            in vec4 aTexSamplingCoord;
            out vec2 vTex;
            void main() {
                gl_Position = aFramePosition;
                vTex = aTexSamplingCoord.xy;
            }
        """.trimIndent()

        /** Passthrough + alpha so a shader failure never black-frames the preview. */
        val FALLBACK_FRAGMENT = """
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

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
