package com.cutvideo.editor

import com.cutvideo.editor.domain.model.AnimationCatalog
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectsCatalog
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.domain.model.FontCatalog
import com.cutvideo.editor.domain.model.TransitionCatalog
import com.cutvideo.editor.domain.model.TransitionType
import com.cutvideo.editor.nativebridge.NativeEffectIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the load-bearing contract between the catalogues and the native engine.
 *
 * `EffectId` in `cpp/video_processor.h`, `uEffectIds` in `assets/shaders/cutvideo_effect.glsl` and
 * `NativeEffectIds.order` are index-coupled: a new effect inserted anywhere but the end silently
 * shifts every other effect. These tests (plus `init` in [NativeEffectIds]) fail the build instead.
 */
class CatalogIntegrityTest {

    @Test
    fun `effect catalogue is aligned with the native effect ids`() {
        // Touching the object runs its init block, which checks size + index alignment.
        assertNotNull(NativeEffectIds)
        EffectsCatalog.presets.forEachIndexed { index, preset ->
            assertEquals("effect '${preset.id}' must live in native slot $index",
                index, NativeEffectIds.idOf(preset.id))
        }
        assertEquals(0, NativeEffectIds.idOf("none"))
        assertEquals(0, NativeEffectIds.idOf("this-effect-does-not-exist"))
    }

    @Test
    fun `every catalogue entry has a unique id`() {
        val effectIds = EffectsCatalog.presets.map { it.id }
        assertEquals(effectIds.size, effectIds.toSet().size)
        val filterIds = FilterCatalog.presets.map { it.id }
        assertEquals(filterIds.size, filterIds.toSet().size)
    }

    @Test
    fun `filter matrices are well formed`() {
        FilterCatalog.presets.forEach { preset ->
            assertEquals("${preset.id} matrix must be 3x4", 12, preset.matrix.size)
            assertTrue("${preset.id} has a non-finite coefficient",
                preset.matrix.all { it.isFinite() })
            assertTrue("${preset.id} gamma out of range", preset.gamma in 0.2f..3f)
            assertTrue("${preset.id} grain out of range", preset.grain in 0f..1f)
            assertTrue("${preset.id} vignette out of range", preset.vignette in 0f..1f)
        }
        val identity = FilterCatalog.preset(FilterCatalog.NONE)
        assertEquals(FilterCatalog.NONE, identity.id)
    }

    @Test
    fun `every animation and transition has a preset`() {
        ClipAnimationType.entries.forEach { type ->
            assertNotNull("no catalogue entry for $type", AnimationCatalog.preset(type))
            if (type != ClipAnimationType.NONE) {
                assertEquals(type, AnimationCatalog.preset(type).type)
            }
        }
        TransitionType.entries.forEach { type ->
            assertEquals("no catalogue entry for $type", type, TransitionCatalog.preset(type).type)
        }
    }

    @Test
    fun `bundled fonts are unique`() {
        val keys = FontCatalog.fonts.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(FontCatalog.fonts.any { it.first == FontCatalog.DEFAULT })
    }
}
