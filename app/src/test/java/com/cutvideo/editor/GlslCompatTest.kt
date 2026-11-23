package com.cutvideo.editor

import com.cutvideo.editor.editor.gl.GlslCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the ES 3.0 → ES 2.0 shader conversion that keeps the preview and export rendering alive
 * on devices where Media3 falls back to an OpenGL ES 2.0 context.
 */
class GlslCompatTest {

    private val vertexEs3 = """
        #version 300 es
        in vec4 aFramePosition;
        in vec4 aTexSamplingCoord;
        out vec2 vTex;
        void main() {
            gl_Position = aFramePosition;
            vTex = aTexSamplingCoord.xy;
        }
    """.trimIndent()

    private val fragmentEs3 = """
        #version 300 es
        precision highp float;
        in vec2 vTex;
        out vec4 fragColor;
        uniform sampler2D uTexSampler;
        void main() {
            vec4 c = texture(uTexSampler, vTex);
            fragColor = vec4(c.rgb, 1.0);
        }
    """.trimIndent()

    @Test
    fun vertexShaderDropsVersionAndUsesAttributes() {
        val es2 = GlslCompat.toEs2(vertexEs3, fragment = false)
        assertFalse("ES2 shaders must not keep the ES3 version line", es2.contains("#version"))
        assertTrue("vertex inputs become attributes", es2.contains("attribute vec4 aFramePosition"))
        assertTrue("vertex outputs become varyings", es2.contains("varying vec2 vTex"))
        assertFalse("no ES3 'in' remains", Regex("""^in\s""", RegexOption.MULTILINE).containsMatchIn(es2))
    }

    @Test
    fun fragmentShaderUsesTexture2DAndGlFragColor() {
        val es2 = GlslCompat.toEs2(fragmentEs3, fragment = true)
        assertFalse(es2.contains("#version"))
        assertTrue(es2.contains("texture2D(uTexSampler, vTex)"))
        assertTrue(es2.contains("gl_FragColor"))
        assertFalse("ES3 out declaration must be gone", es2.contains("out vec4"))
        assertTrue("fragment inputs become varyings", es2.contains("varying vec2 vTex"))
    }

    @Test
    fun highpIsGuardedForEs2FragmentShaders() {
        val es2 = GlslCompat.toEs2(fragmentEs3, fragment = true)
        assertTrue(es2.contains("#ifdef GL_FRAGMENT_PRECISION_HIGH"))
        assertTrue(es2.contains("precision mediump float;"))
    }

    @Test
    fun es3OnlyConstructsAreRejected() {
        val bad = "#version 300 es\nvoid main() { float x = textureLod(uS, v, 0.0).r; }"
        assertTrue(GlslCompat.isEs3Only(bad))
        var threw = false
        try {
            GlslCompat.toEs2(bad, fragment = true)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("ES3-only constructs must throw instead of producing broken GLSL", threw)
    }

    @Test
    fun cutVideoEffectShaderConvertsCleanly() {
        // The real production shader must survive the conversion (this is the whole point of the
        // adapter): run the same constructs it uses through the converter.
        val shader = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 fragColor;
            uniform sampler2D uTexSampler;
            uniform float uTime;
            float hash11(float p) { p = fract(p * 0.1031); return fract(p); }
            vec3 sampleRgb(vec2 uv) { return texture(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb; }
            void main() {
                vec3 color;
                for (int i = 0; i < 4; ++i) {
                    color += sampleRgb(vTex) * float(i);
                }
                if (uTime > 0.5) { color *= 2.0; } else { color = clamp(color, 0.0, 1.0); }
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
        val es2 = GlslCompat.toEs2(shader, fragment = true)
        assertTrue(es2.contains("texture2D(uTexSampler"))
        assertTrue(es2.contains("gl_FragColor = vec4(color, 1.0);"))
        assertFalse(es2.contains("fragColor"))
        assertFalse(es2.contains("#version"))
    }
}
