package com.cutvideo.editor

import com.cutvideo.editor.editor.gl.GlslCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real shader assets must survive the ES 3.0 → ES 2.0 conversion: preview and export both
 * draw with these programs, and on ES 2.0 devices Media3's fallback context compiles the
 * converted source. This is the gate that catches ES3-only syntax before it reaches a device.
 */
class EffectShaderTest {

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/shaders/$name"),
            File("app/src/main/assets/shaders/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("shader asset not found: $name (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `pass shader converts to ES2 without ES3 syntax`() {
        val source = asset("cutvideo_pass.glsl")
        val es2 = GlslCompat.toEs2(source, fragment = true)
        assertFalse("version line must go", es2.contains("#version"))
        assertFalse("ES3 'in' must be gone", Regex("""^in\s""", RegexOption.MULTILINE).containsMatchIn(es2))
        assertFalse("ES3 'out' must be gone", Regex("""^\s*out\s+vec4""", RegexOption.MULTILINE).containsMatchIn(es2))
        assertTrue(es2.contains("gl_FragColor"))
        assertTrue("texture() becomes texture2D()", es2.contains("texture2D("))
        // Integer % does not exist in GLSL ES 1.00 — the shader must not use the % operator.
        source.lines().filter { !it.trimStart().startsWith("//") }.forEach { line ->
            assertFalse("bare % operator (not ES2): $line", line.contains("%"))
        }
    }

    @Test
    fun `look shader converts to ES2 without ES3 syntax`() {
        val source = asset("cutvideo_effect.glsl")
        val es2 = GlslCompat.toEs2(source, fragment = true)
        assertFalse(es2.contains("#version"))
        assertFalse(Regex("""^in\s""", RegexOption.MULTILINE).containsMatchIn(es2))
        assertTrue(es2.contains("gl_FragColor"))
    }

    @Test
    fun `pass shader stages and effect ids stay in their documented slots`() {
        val source = asset("cutvideo_pass.glsl")
        // Stage constants the Kotlin renderer passes in EffectPassProgram.
        listOf(
            "STAGE_SINGLE = 0", "STAGE_BLUR = 1", "STAGE_MIX = 2", "STAGE_BRIGHT = 3",
            "STAGE_COMBINE = 4", "STAGE_SHADOW_SIL = 5", "STAGE_SHADOW_COMPOSITE = 6"
        ).forEach { assertTrue("missing $it", source.contains(it)) }
        // All 23 classic ids plus the two SINGLE-pass pro effects.
        for (id in 1..23) assertTrue("effect id $id", source.contains("id == $id"))
        assertTrue(source.contains("id == 28"))
        assertTrue(source.contains("id == 29"))
    }

    @Test
    fun `look shader no longer applies effects inline`() {
        val source = asset("cutvideo_effect.glsl")
        assertFalse("effects render as chain passes now", source.contains("applyOneEffect"))
        assertFalse("effect uniforms belong to the pass shader", source.contains("uEffectIds"))
    }
}
