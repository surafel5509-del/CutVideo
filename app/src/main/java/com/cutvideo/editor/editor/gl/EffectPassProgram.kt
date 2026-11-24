package com.cutvideo.editor.editor.gl

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * Program for `cutvideo_pass.glsl` — the effect-chain pass shader.
 *
 * One program serves every chain stage (single classic effect, separable gaussian, bright-pass,
 * bloom combine, drop-shadow construction, mix/blit); the stage is selected per draw. The recipe
 * logic lives in [EffectChainRenderer]; this class only maps calls to uniforms and quad draws.
 */
internal class EffectPassProgram {

    private var handle = 0
    private var positionLocation = -1
    private var texCoordLocation = -1

    private val uniforms = HashMap<String, Int>(32)
    private val positionBuffer: FloatBuffer = CutVideoShaderProgram.floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    )
    private val texCoordBuffer: FloatBuffer = CutVideoShaderProgram.floatBufferOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
    )

    val isValid: Boolean get() = handle != 0

    fun init(context: Context) {
        if (handle != 0) return
        val appContext = context.applicationContext
        val es3 = CutVideoShaderProgram.glContextIsEs3()
        val vertex = loadSource(appContext, "shaders/cutvideo_effect_vertex.glsl", FALLBACK_VERTEX)
        val fragment = loadSource(appContext, "shaders/cutvideo_pass.glsl", "")

        handle = if (es3) {
            createProgram(vertex, fragment).takeIf { it != 0 }
                ?: createProgram(safeEs2(vertex, false), safeEs2(fragment, true))
        } else {
            createProgram(safeEs2(vertex, false), safeEs2(fragment, true))
                .takeIf { it != 0 } ?: createProgram(vertex, fragment)
        }
        if (handle == 0) {
            Log.e(TAG, "pass shader failed to build — effect chain will not render")
            return
        }
        positionLocation = GLES20.glGetAttribLocation(handle, "aFramePosition")
        texCoordLocation = GLES20.glGetAttribLocation(handle, "aTexSamplingCoord")
    }

    fun release() {
        if (handle != 0) {
            GLES20.glDeleteProgram(handle)
            handle = 0
        }
        uniforms.clear()
    }

    // --- stage draws (target framebuffer is bound by the caller) -----------------------------------

    /** Full classic effect id with its parameters, blended over its own input. */
    fun drawSingle(tex: Int, effectId: Int, strength: Float, blend: Int, p0: FloatArray, p1: FloatArray) {
        draw(tex) {
            uniform1i("uStage", 0)
            uniform1i("uEffectId", effectId)
            uniform1f("uStrength", strength)
            uniform1i("uBlend", blend)
            uniform4fv("uP0", p0)
            uniform4fv("uP1", p1)
            uniform1f("uLutSize", 0f)
        }
    }

    /** The .cube LUT effect: same as [drawSingle] but with the tiled LUT bound on unit 2. */
    fun drawSingleLut(tex: Int, strength: Float, blend: Int, lutTexId: Int, lutSize: Int) {
        draw(tex) {
            uniform1i("uStage", 0)
            uniform1i("uEffectId", 37)
            uniform1f("uStrength", strength)
            uniform1i("uBlend", blend)
            uniform4fv("uP0", floatArrayOf(0f, 0f, 0f, 0f))
            uniform4fv("uP1", floatArrayOf(0f, 0f, 0f, 0f))
            bindTexture(2, lutTexId, "uLutTexture")
            uniform1f("uLutSize", lutSize.toFloat())
        }
    }

    /** Separable gaussian tap along `dir` (already includes texel size and radius). */
    fun drawBlur(srcTex: Int, dirX: Float, dirY: Float) {
        draw(srcTex) {
            uniform1i("uStage", 1)
            uniform2f("uBlurDir", dirX, dirY)
        }
    }

    /** Composites the processed texture (unit B) over the base (unit A). */
    fun drawMix(baseTex: Int, processedTex: Int, blend: Int, strength: Float) {
        use()
        bindTexture(0, baseTex, "uTexA")
        bindTexture(1, processedTex, "uTexB")
        uniform1i("uStage", 2)
        uniform1i("uBlend", blend)
        uniform1f("uStrength", strength)
        drawQuad()
    }

    /** Threshold bright-pass. */
    fun drawBright(srcTex: Int, threshold: Float) {
        draw(srcTex) {
            uniform1i("uStage", 3)
            uniform4fv("uP0", floatArrayOf(threshold, 0f, 0f, 0f))
        }
    }

    /** Weighted multi-scale bloom combine over the base frame. */
    fun drawCombine(scale1Tex: Int, scale2Tex: Int, baseTex: Int, w1: Float, w2: Float,
                    tint: FloatArray, blend: Int, strength: Float) {
        use()
        bindTexture(0, scale1Tex, "uTexA")
        bindTexture(1, scale2Tex, "uTexB")
        bindTexture(2, baseTex, "uTexC")
        uniform1i("uStage", 4)
        uniform2f("uCombineW", w1, w2)
        uniform3fv("uTint", tint)
        uniform1i("uBlend", blend)
        uniform1f("uStrength", strength)
        drawQuad()
    }

    /** Offset + dilated alpha silhouette of the frame (drop-shadow construction, step 1). */
    fun drawShadowSilhouette(srcTex: Int, offsetX: Float, offsetY: Float, spread: Float) {
        draw(srcTex) {
            uniform1i("uStage", 5)
            uniform4fv("uP0", floatArrayOf(offsetX, offsetY, spread, 0f))
        }
    }

    /** Composites the base frame over its blurred shadow mask. */
    fun drawShadowComposite(maskTex: Int, baseTex: Int, strength: Float) {
        use()
        bindTexture(0, maskTex, "uTexA")
        bindTexture(1, baseTex, "uTexB")
        uniform1i("uStage", 6)
        uniform1f("uStrength", strength)
        drawQuad()
    }

    // --- plumbing ---------------------------------------------------------------------------------

    fun setCommon(texelW: Float, texelH: Float, timeSeconds: Float, aspect: Float,
                  duotoneShadow: FloatArray, duotoneHighlight: FloatArray) {
        use()
        uniform2f("uTexelSize", texelW, texelH)
        uniform1f("uTime", timeSeconds)
        uniform1f("uAspect", aspect)
        uniform3fv("uDuotoneShadow", duotoneShadow)
        uniform3fv("uDuotoneHighlight", duotoneHighlight)
    }

    private fun draw(srcTex: Int, configure: () -> Unit) {
        use()
        bindTexture(0, srcTex, "uTexA")
        configure()
        drawQuad()
    }

    private fun use() {
        GLES20.glUseProgram(handle)
    }

    private fun bindTexture(unit: Int, texId: Int, samplerName: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        uniform1i(samplerName, unit)
    }

    private fun drawQuad() {
        if (positionLocation < 0) return
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

    private fun uniform3fv(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform3fv(location, 1, values, 0)
    }

    private fun uniform4fv(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform4fv(location, 1, values, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        if (fragmentSource.isEmpty()) return 0
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "pass program link failed: " + GLES20.glGetProgramInfoLog(program))
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
            Log.e(TAG, "pass shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun safeEs2(source: String, fragment: Boolean): String = try {
        GlslCompat.toEs2(source, fragment)
    } catch (t: Throwable) {
        Log.e(TAG, "cannot build the ES 2.0 variant of the pass shader", t)
        ""
    }

    private fun loadSource(context: Context, path: String, fallback: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        Log.w(TAG, "falling back to built-in shader for $path", t)
        fallback
    }

    private companion object {
        const val TAG = "CutVideoPass"

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
    }
}
