package com.cutvideo.editor.editor.gl

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal OpenGL ES program for the CutVideo colour/effect shader.
 *
 * Written directly against GLES20 (which is valid inside an ES 2.0 *and* an ES 3.0 context) instead
 * of a helper library so the whole pipeline is explicit: one full-screen triangle strip in, one
 * texture in, the framework-owned framebuffer out.
 *
 * Media3 creates an ES 3.0 context when it can and falls back to ES 2.0 otherwise. The shader
 * assets are ES 3.0 (`#version 300 es`); on an ES 2.0 context they are converted on the fly by
 * [GlslCompat]. If the look shader cannot be built at all, a pass-through blit (also provided in
 * both ES variants) keeps frames flowing so preview and export never turn black or freeze.
 */
internal class CutVideoShaderProgram {

    private var handle = 0
    private var blitHandle = 0
    private var blitProgramPosition = -1
    private var blitProgramTexCoord = -1
    private var blitProgramSampler = -1
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var samplerLocation = -1

    /** Pending texel size; applied while the program is bound (glUniform needs a current program). */
    private var texelWidth = 1f
    private var texelHeight = 1f

    private var maskTextureId = 0
    private var fallbackMaskTextureId = 0
    private var uploadedFreehand: FloatArray? = null

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

    val isValid: Boolean get() = handle != 0 || blitHandle != 0

    fun init(context: Context) {
        if (handle != 0 || blitHandle != 0) return
        val appContext = context.applicationContext
        val es3 = glContextIsEs3()

        val vertexEs3 = loadShaderSource(appContext, "shaders/cutvideo_effect_vertex.glsl", FALLBACK_VERTEX_ES3)
        val fragmentEs3 = loadShaderSource(appContext, "shaders/cutvideo_effect.glsl", FALLBACK_FRAGMENT_ES3)

        // Preferred variant first, then the other one — some drivers report a version they cannot
        // actually compile for, so try both before giving up on the look shader.
        if (es3) {
            handle = createProgram(vertexEs3, fragmentEs3)
            if (handle == 0) handle = createProgram(safeEs2(vertexEs3, false), safeEs2(fragmentEs3, true))
        } else {
            handle = createProgram(safeEs2(vertexEs3, false), safeEs2(fragmentEs3, true))
            if (handle == 0) handle = createProgram(vertexEs3, fragmentEs3)
        }

        if (handle == 0) {
            // Never leave the pipeline without a program: a broken driver/shader combination would
            // otherwise render an empty (black) frame instead of the untouched video.
            Log.e(TAG, "look shader failed to build, falling back to a pass-through blit")
            if (es3) {
                blitHandle = createProgram(vertexEs3, BLIT_FRAGMENT_ES3)
                    .takeIf { it != 0 } ?: createProgram(safeEs2(vertexEs3, false), BLIT_FRAGMENT_ES2)
            } else {
                blitHandle = createProgram(safeEs2(vertexEs3, false), BLIT_FRAGMENT_ES2)
                    .takeIf { it != 0 } ?: createProgram(vertexEs3, BLIT_FRAGMENT_ES3)
            }
            if (blitHandle == 0) {
                Log.e(TAG, "even the pass-through blit failed to build — frames will not render")
                return
            }
            blitProgramPosition = GLES20.glGetAttribLocation(blitHandle, "aFramePosition")
            blitProgramTexCoord = GLES20.glGetAttribLocation(blitHandle, "aTexSamplingCoord")
            blitProgramSampler = GLES20.glGetUniformLocation(blitHandle, "uTexSampler")
            return
        }
        positionLocation = GLES20.glGetAttribLocation(handle, "aFramePosition")
        texCoordLocation = GLES20.glGetAttribLocation(handle, "aTexSamplingCoord")
        samplerLocation = GLES20.glGetUniformLocation(handle, "uTexSampler")
        // Uniforms default to 0 in GLES, so an alpha uniform that is never set would hide the frame.
        GLES20.glUseProgram(handle)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(handle, "uAlpha"), 1f)
    }

    fun use() {
        when {
            handle != 0 -> GLES20.glUseProgram(handle)
            blitHandle != 0 -> GLES20.glUseProgram(blitHandle)
        }
    }

    /** Stores the texel size; applied on the next [setLook]/[draw] while the program is bound. */
    fun setTexelSize(width: Int, height: Int) {
        texelWidth = 1f / width.coerceAtLeast(1)
        texelHeight = 1f / height.coerceAtLeast(1)
    }

    fun setLook(look: GlLook) {
        // uTexelSize used to be written from configure() with no program bound (GL_INVALID_OPERATION);
        // apply it here where a program is guaranteed to be current.
        uniform2f("uTexelSize", texelWidth, texelHeight)
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
        setTransform(look)
        setMasks(look)
    }

    private fun setTransform(look: GlLook) {
        uniform1f("uTransformScale", look.transformScale)
        uniform1f("uTransformRotation", look.transformRotation)
        uniform2f("uTransformOffset", look.transformOffsetX, look.transformOffsetY)
    }

    private fun setMasks(look: GlLook) {
        val count = minOf(2, look.masks.size)
        uniform1i("uMaskCount", count)
        val types = IntArray(2)
        val centers = FloatArray(6)
        val params = FloatArray(8)
        val extras = FloatArray(4)
        var freehandIndex = -1
        for (i in 0 until count) {
            val mask = look.masks[i]
            types[i] = mask.type
            centers[i * 3] = mask.xFrac
            centers[i * 3 + 1] = mask.yFrac
            centers[i * 3 + 2] = mask.scale
            params[i * 4] = mask.rotationRadians
            params[i * 4 + 1] = mask.feather
            params[i * 4 + 2] = mask.opacity
            params[i * 4 + 3] = mask.expansion
            extras[i * 2] = mask.roundness
            extras[i * 2 + 1] = mask.invert
            if (mask.type == 4) freehandIndex = i
        }
        uniform1iv("uMaskType[0]", types)
        uniform3fv("uMaskCenterScale[0]", centers)
        uniform4fv("uMaskParams[0]", params)
        uniform2fv("uMaskExtras[0]", extras)
        uniform1i("uMaskFreehandIndex", freehandIndex)

        // The freehand coverage always occupies texture unit 1 (a 1x1 white fallback when unused)
        // so the sampler is never left pointing at the video texture.
        val texture = if (freehandIndex >= 0 && look.freehandAlpha != null && look.freehandWidth > 0) {
            uploadFreehand(look.freehandAlpha, look.freehandWidth, look.freehandHeight)
        } else {
            fallbackMaskTexture()
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        uniform1i("uMaskTexture", 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun uploadFreehand(alpha: FloatArray, width: Int, height: Int): Int {
        if (maskTextureId != 0 && uploadedFreehand === alpha) return maskTextureId
        if (maskTextureId == 0) {
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            maskTextureId = names[0]
        }
        val bytes = ByteArray(alpha.size) { i -> (alpha[i].coerceIn(0f, 1f) * 255f).toInt().toByte() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { position(0) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        uploadedFreehand = alpha
        return maskTextureId
    }

    private fun fallbackMaskTexture(): Int {
        if (fallbackMaskTextureId == 0) {
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            fallbackMaskTextureId = names[0]
            val white = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).apply { position(0) }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fallbackMaskTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, white
            )
        }
        return fallbackMaskTextureId
    }

    /** Copies the input texture to the framework-owned output framebuffer. */
    fun drawBlit(inputTextureId: Int) {
        if (blitHandle == 0) return
        GLES20.glUseProgram(blitHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (blitProgramSampler >= 0) GLES20.glUniform1i(blitProgramSampler, 0)
        GLES20.glEnableVertexAttribArray(blitProgramPosition)
        GLES20.glVertexAttribPointer(blitProgramPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        if (blitProgramTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(blitProgramTexCoord)
            GLES20.glVertexAttribPointer(blitProgramTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(blitProgramPosition)
        if (blitProgramTexCoord >= 0) GLES20.glDisableVertexAttribArray(blitProgramTexCoord)
    }

    fun draw(inputTextureId: Int) {
        if (handle == 0) {
            drawBlit(inputTextureId)
            return
        }
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
        if (blitHandle != 0) {
            GLES20.glDeleteProgram(blitHandle)
            blitHandle = 0
        }
        if (maskTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
            maskTextureId = 0
        }
        if (fallbackMaskTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fallbackMaskTextureId), 0)
            fallbackMaskTextureId = 0
        }
        uploadedFreehand = null
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

    private fun uniform1iv(name: String, values: IntArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform1iv(location, values.size, values, 0)
    }

    private fun uniform2fv(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform2fv(location, values.size / 2, values, 0)
    }

    private fun uniform3fv(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform3fv(location, values.size / 3, values, 0)
    }

    private fun uniform4fv(name: String, values: FloatArray) {
        val location = uniform(name)
        if (location >= 0) GLES20.glUniform4fv(location, values.size / 4, values, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
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

    private fun safeEs2(source: String, fragment: Boolean): String = try {
        GlslCompat.toEs2(source, fragment)
    } catch (t: Throwable) {
        Log.e(TAG, "cannot build the ES 2.0 variant", t)
        ""
    }

    private fun loadShaderSource(context: Context, path: String, fallback: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        Log.w(TAG, "falling back to built-in shader for $path", t)
        fallback
    }

    internal companion object {
        private const val TAG = "CutVideoShader"

        /** True when the current GL context is ES 3.0 (Media3 falls back to ES 2.0 otherwise). */
        fun glContextIsEs3(): Boolean {
            val version = runCatching { GLES20.glGetString(GLES20.GL_VERSION) }.getOrNull() ?: return true
            return version.contains("OpenGL ES 3") || version.contains("OpenGL ES-CM 3")
        }

        val FALLBACK_VERTEX_ES3 = """
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

        /**
         * Minimal copy program used when the look shader cannot be built on this GPU/driver.
         * It only samples the input texture, so the frame always reaches the encoder.
         */
        val BLIT_FRAGMENT_ES3 = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            out vec4 fragColor;
            uniform sampler2D uTexSampler;
            void main() {
                fragColor = texture(uTexSampler, vTex);
            }
        """.trimIndent()

        val BLIT_FRAGMENT_ES2 = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTexSampler;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTex);
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
