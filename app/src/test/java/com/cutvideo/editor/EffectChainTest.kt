package com.cutvideo.editor

import com.cutvideo.editor.domain.model.BlendMode
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.EffectCatalog
import com.cutvideo.editor.domain.model.EffectSpec
import com.cutvideo.editor.domain.model.EffectStackPreset
import com.cutvideo.editor.domain.model.Keyframe
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.KeyframeTrack
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.editor.effects.BlendModes
import com.cutvideo.editor.editor.effects.EffectChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effect-chain contract: ordering, per-effect parameters, keyframe resolution and the blend
 * math the GPU shader mirrors. Preview and export share this code, so these tests pin what both
 * renderers do.
 */
class EffectChainTest {

    private fun clipWith(effects: List<EffectSpec>, tracks: List<KeyframeTrack> = emptyList()): Clip =
        Clip(
            id = "clip-a",
            trackType = TrackType.VIDEO,
            name = "a",
            uri = "content://test/a.mp4",
            startUs = 0L,
            durationUs = 1_000_000L,
            sourceDurationUs = 1_000_000L,
            effects = effects,
            keyframes = tracks
        )

    // --- catalog invariants the renderer relies on -------------------------------------------------

    @Test
    fun `every effect definition maps to a known engine id`() {
        EffectCatalog.defs.forEach { def ->
            val def2 = EffectCatalog.def(def.id)
            assertEquals(def.id, def2.id)
            // Parameters must have unique keys (the renderer packs them by index).
            assertEquals(def.params.size, def.params.map { it.key }.toSet().size)
            def.params.forEach { p ->
                assertTrue("${def.id}.${p.key} range", p.min <= p.default && p.default <= p.max)
            }
        }
    }

    @Test
    fun `stacks only reference defined effects`() {
        EffectCatalog.stacks.forEach { stack: EffectStackPreset ->
            stack.effects.forEach { spec ->
                assertNotNull("stack ${stack.id} references ${spec.id}", EffectCatalog.defs.find { it.id == spec.id })
            }
        }
    }

    @Test
    fun `effect ids stay in their historical slots`() {
        val byId = EffectCatalog.defs.map { it.id }
        // The first 23 slots are load-bearing (old projects + C++ enum).
        assertEquals(
            listOf(
                "grain", "vignette", "scanlines", "glitch", "chroma_aberration", "bloom", "light_leak",
                "pulse", "mirror", "posterize", "duotone", "sharpen", "vhs", "snow", "zoom_blur", "blur",
                "pixelate", "neon", "halftone", "tilt_shift", "old_film", "heat_wave", "spotlight"
            ),
            byId.take(23)
        )
        assertEquals(
            listOf("gaussian_blur", "glow", "bloom_pro", "drop_shadow", "vignette_pro", "grain_pro"),
            byId.drop(23)
        )
    }

    // --- chain editing ----------------------------------------------------------------------------

    @Test
    fun `moved reorders within bounds`() {
        val a = EffectSpec("grain")
        val b = EffectSpec("vhs")
        val c = EffectSpec("neon")
        val chain = listOf(a, b, c)
        assertEquals(listOf(b, a, c), EffectChain.moved(chain, a.instanceId, 1))
        assertEquals(listOf(a, c, b), EffectChain.moved(chain, c.instanceId, -1))
        // Out-of-range moves are no-ops.
        assertEquals(chain, EffectChain.moved(chain, a.instanceId, -1))
        assertEquals(chain, EffectChain.moved(chain, "missing", 1))
    }

    @Test
    fun `duplicated inserts a copy with a fresh instance id`() {
        val a = EffectSpec("grain", intensity = 0.7f, params = mapOf("size" to 2f))
        val chain = EffectChain.duplicated(listOf(a), a.instanceId)
        assertEquals(2, chain.size)
        assertTrue(chain[0].instanceId != chain[1].instanceId)
        assertEquals("grain", chain[1].id)
        assertEquals(0.7f, chain[1].intensity, 1e-6f)
        assertEquals(mapOf("size" to 2f), chain[1].params)
    }

    @Test
    fun `toggled flips enabled and withParam updates one instance`() {
        val a = EffectSpec("grain")
        val b = EffectSpec("vhs")
        val toggled = EffectChain.toggled(listOf(a, b), a.instanceId)
        assertFalse(toggled[0].enabled)
        assertTrue(toggled[1].enabled)
        val updated = EffectChain.withParam(listOf(a, b), b.instanceId, "wobble", 0.9f)
        assertEquals(0.9f, updated[1].params["wobble"]!!, 1e-6f)
        assertTrue(updated[0].params.isEmpty())
    }

    // --- resolution -------------------------------------------------------------------------------

    @Test
    fun `disabled and zero-intensity effects leave the chain`() {
        val on = EffectSpec("grain", intensity = 0.5f)
        val off = EffectSpec("vhs", intensity = 0.5f, enabled = false)
        val zero = EffectSpec("neon", intensity = 0f)
        val resolved = EffectChain.resolve(clipWith(listOf(on, off, zero)), 0L)
        assertEquals(1, resolved.size)
        assertEquals("grain", resolved[0].spec.id)
    }

    @Test
    fun `chain keeps list order which is the render order`() {
        val chain = listOf(EffectSpec("grain"), EffectSpec("vhs"), EffectSpec("neon"))
        val resolved = EffectChain.resolve(clipWith(chain), 0L)
        assertEquals(listOf("grain", "vhs", "neon"), resolved.map { it.spec.id })
    }

    @Test
    fun `parameter resolution falls back to definition defaults`() {
        val spec = EffectSpec("gaussian_blur", intensity = 0.8f)
        val resolved = EffectChain.resolveOne(spec, emptyList(), 0L)!!
        // "radius" default is 12 for gaussian_blur.
        assertEquals(12f, resolved.value("radius"), 1e-6f)
        assertEquals(1f, resolved.value("quality"), 1e-6f)
        // Unknown keys fall back to the given value.
        assertEquals(-1f, resolved.value("nope", -1f), 1e-6f)
    }

    @Test
    fun `keyframed parameter overrides the stored value for its instance only`() {
        val a = EffectSpec("gaussian_blur", intensity = 1f)
        val b = EffectSpec("gaussian_blur", intensity = 1f)
        val track = KeyframeTrack(
            property = KeyframeProperty.EFFECT_PARAM,
            keyframes = listOf(Keyframe(timeUs = 0L, value = 40f), Keyframe(timeUs = 1_000_000L, value = 40f)),
            targetId = a.instanceId,
            param = "radius"
        )
        val resolved = EffectChain.resolve(clipWith(listOf(a, b), listOf(track)), 500_000L)
        assertEquals(40f, resolved[0].value("radius"), 1e-3f)
        assertEquals(12f, resolved[1].value("radius"), 1e-6f)
    }

    @Test
    fun `own intensity track replaces the static intensity`() {
        val spec = EffectSpec("grain", intensity = 0.2f)
        val track = KeyframeTrack(
            property = KeyframeProperty.EFFECT_INTENSITY,
            keyframes = listOf(Keyframe(timeUs = 0L, value = 0.9f), Keyframe(timeUs = 1_000_000L, value = 0.9f)),
            targetId = spec.instanceId
        )
        val resolved = EffectChain.resolve(clipWith(listOf(spec), listOf(track)), 0L)
        assertEquals(0.9f, resolved[0].intensity, 1e-3f)
    }

    @Test
    fun `legacy master intensity track scales every effect`() {
        val a = EffectSpec("grain", intensity = 0.5f)
        val b = EffectSpec("vhs", intensity = 0.4f)
        // Legacy semantics: value / defaultValue is the multiplier (default is 0.5).
        val master = KeyframeTrack(
            property = KeyframeProperty.EFFECT_INTENSITY,
            keyframes = listOf(Keyframe(timeUs = 0L, value = 0.25f), Keyframe(timeUs = 1_000_000L, value = 0.25f)),
            targetId = ""
        )
        val resolved = EffectChain.resolve(clipWith(listOf(a, b), listOf(master)), 0L)
        assertEquals(0.25f, resolved[0].intensity, 1e-3f)   // 0.5 * (0.25/0.5)
        assertEquals(0.20f, resolved[1].intensity, 1e-3f)   // 0.4 * 0.5
    }

    @Test
    fun `param keyframes are ignored when they target another parameter`() {
        val spec = EffectSpec("gaussian_blur", intensity = 1f)
        val track = KeyframeTrack(
            property = KeyframeProperty.EFFECT_PARAM,
            keyframes = listOf(Keyframe(timeUs = 0L, value = 40f)),
            targetId = spec.instanceId,
            param = "quality"
        )
        val resolved = EffectChain.resolveOne(spec, listOf(track), 0L)!!
        assertEquals(12f, resolved.value("radius"), 1e-6f)
        assertEquals(40f.coerceIn(1f, 3f), resolved.value("quality"), 1e-6f) // clamped to the def range
    }

    // --- blend math (the shader mirrors these formulas) ---------------------------------------------

    @Test
    fun `blend formulas are pinned`() {
        assertEquals(1f, BlendModes.blend(BlendMode.ADD, 0.6f, 0.6f), 1e-5f)
        assertEquals(0.36f, BlendModes.blend(BlendMode.MULTIPLY, 0.6f, 0.6f), 1e-5f)
        assertEquals(0.84f, BlendModes.blend(BlendMode.SCREEN, 0.6f, 0.6f), 1e-5f)
        assertEquals(0f, BlendModes.blend(BlendMode.DIFFERENCE, 0.6f, 0.6f), 1e-5f)
        // Overlay: base 0.25 (dark) → 2bs = 0.3; base 0.75 (light) → 1-2(1-b)(1-s) = 0.8
        assertEquals(0.30f, BlendModes.blend(BlendMode.OVERLAY, 0.25f, 0.6f), 1e-5f)
        assertEquals(0.80f, BlendModes.blend(BlendMode.OVERLAY, 0.75f, 0.6f), 1e-5f)
        // Hard light keys off the SOURCE: src 0.6 > 0.5 → 1-2(1-b)(1-s) = 0.6
        assertEquals(0.60f, BlendModes.blend(BlendMode.HARD_LIGHT, 0.5f, 0.6f), 1e-5f)
        assertEquals(0.6f, BlendModes.blend(BlendMode.NORMAL, 0.3f, 0.6f), 1e-5f)
    }

    @Test
    fun `composite is an identity at zero and the blend at full amount`() {
        assertEquals(0.3f, BlendModes.composite(BlendMode.NORMAL, 0.3f, 0.9f, 0f), 1e-5f)
        assertEquals(0.9f, BlendModes.composite(BlendMode.NORMAL, 0.3f, 0.9f, 1f), 1e-5f)
        val half = BlendModes.composite(BlendMode.NORMAL, 0.2f, 0.8f, 0.5f)
        assertEquals(0.5f, half, 1e-5f)
    }

    @Test
    fun `soft light stays inside the unit range`() {
        for (i in 0..10) {
            for (j in 0..10) {
                val b = i / 10f
                val s = j / 10f
                val r = BlendModes.blend(BlendMode.SOFT_LIGHT, b, s)
                assertTrue("softLight($b,$s)=$r", r in 0f..1f)
            }
        }
    }
}
