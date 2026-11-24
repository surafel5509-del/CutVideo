package com.cutvideo.editor

import com.cutvideo.editor.editor.effects.CubeLuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `.cube` LUT parser and GPU tiling contract (preview and export share this maths). */
class CubeLutTest {

    private fun identityCube(size: Int): String = buildString {
        appendLine("TITLE \"identity\"")
        appendLine("LUT_3D_SIZE $size")
        appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    appendLine("${r / (size - 1f)} ${g / (size - 1f)} ${b / (size - 1f)}")
                }
            }
        }
    }

    @Test
    fun `parses a 3D cube and keeps red-fastest order`() {
        val lut = CubeLuts.parse(identityCube(3))
        assertEquals(3, lut.size)
        assertEquals(81, lut.values.size)
        // index = r + size*(g + size*b): r=2,g=0,b=0 → index 2 → value (1, 0, 0)
        assertEquals(1f, lut.values[6], 1e-5f)
        assertEquals(0f, lut.values[7], 1e-5f)
        // r=0,g=0,b=2 → index 18 → value (0, 0, 1)
        assertEquals(1f, lut.values[3 * 18 + 2], 1e-5f)
    }

    @Test
    fun `identity LUT returns the input colour`() {
        val lut = CubeLuts.parse(identityCube(5))
        val out = lut.sample(0.25f, 0.5f, 0.75f)
        assertEquals(0.25f, out[0], 2e-3f)
        assertEquals(0.5f, out[1], 2e-3f)
        assertEquals(0.75f, out[2], 2e-3f)
    }

    @Test
    fun `tiles blue slices along X`() {
        val lut = CubeLuts.parse(identityCube(3))
        val rgba = lut.tileToRgba()
        assertEquals(9 * 3 * 4, rgba.size)
        // Pixel at x = b*size + r, y = g. Take b=2, g=1, r=0 → x=6, y=1.
        val pixel = (1 * 9 + 6) * 4
        assertEquals(0, rgba[pixel].toInt())      // r = 0
        assertEquals(128, rgba[pixel + 1].toInt()) // g = 1/2 * 255 ≈ 128
        assertEquals(255, rgba[pixel + 2].toInt()) // b = 1
    }

    @Test
    fun `rejects malformed input`() {
        val tooFew = runCatching { CubeLuts.parse("LUT_3D_SIZE 3\n0 0 0\n") }
        assertTrue(tooFew.isFailure)
        val oneD = runCatching { CubeLuts.parse("LUT_1D_SIZE 3\n0 0 0\n") }
        assertTrue(oneD.isFailure)
    }
}
