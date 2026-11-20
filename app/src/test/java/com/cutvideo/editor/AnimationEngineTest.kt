package com.cutvideo.editor

import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationSpec
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.model.TransitionSpec
import com.cutvideo.editor.domain.model.TransitionType
import com.cutvideo.editor.editor.AnimationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the checks in `tools/native_tests.cpp` for the Kotlin curves that drive the live preview:
 * every motion must end fully visible and stay inside sane bounds for any progress, otherwise the
 * preview and the exported file disagree.
 */
class AnimationEngineTest {

    private fun clip(
        type: ClipAnimationType = ClipAnimationType.NONE,
        transition: TransitionType? = null
    ) = Clip(
        id = "clip",
        trackType = TrackType.VIDEO,
        name = "clip",
        startUs = 0L,
        durationUs = 2_000_000L,
        sourceDurationUs = 2_000_000L,
        animationIn = ClipAnimationSpec(type, 500_000L),
        transitionIn = transition?.let { TransitionSpec(it, 400_000L) }
    )

    @Test
    fun `every motion ends fully visible`() {
        ClipAnimationType.entries.forEach { type ->
            val frame = AnimationEngine.sample(type, 1f)
            assertEquals("$type must end opaque", 1f, frame.alpha, 0.001f)
            assertTrue("$type must keep a positive scale", frame.scale > 0f)
        }
    }

    @Test
    fun `motion frames stay in range across progress`() {
        ClipAnimationType.entries.forEach { type ->
            var p = 0
            while (p <= 100) {
                val frame = AnimationEngine.sample(type, p / 100f)
                assertTrue("$type alpha out of range at $p", frame.alpha in -0.001f..1.001f)
                assertTrue("$type scale out of range at $p", frame.scale in 0f..3f)
                assertTrue("$type blur out of range at $p", frame.blurRadiusPx in 0f..64f)
                p += 5
            }
        }
    }

    @Test
    fun `every transition completes`() {
        TransitionType.entries.forEach { type ->
            val frame = AnimationEngine.sampleTransition(type, 1f)
            assertEquals("$type must end opaque", 1f, frame.alpha, 0.001f)
            assertEquals("$type must end revealed", 1f, frame.wipe, 0.001f)
            assertEquals("$type must settle horizontally", 0f, frame.translateX, 0.001f)
            assertEquals("$type must settle vertically", 0f, frame.translateY, 0.001f)
        }
    }

    @Test
    fun `fade in starts transparent and ends opaque`() {
        val c = clip(ClipAnimationType.FADE)
        assertEquals(0f, AnimationEngine.compose(c, 0L).alpha, 0.01f)
        assertEquals(1f, AnimationEngine.compose(c, 600_000L).alpha, 0.001f)
    }

    @Test
    fun `blur transition feeds the blur radius`() {
        val c = clip(transition = TransitionType.BLUR)
        val frame = AnimationEngine.compose(c, 0L)
        assertTrue("blur dissolve must blur at the start", frame.blurRadiusPx > 10f)
        assertEquals(1f, AnimationEngine.compose(c, 500_000L).alpha, 0.001f)
    }

    @Test
    fun `progress is clamped outside the animation window`() {
        val c = clip(ClipAnimationType.ZOOM_IN)
        val before = AnimationEngine.compose(c, -500_000L)
        val after = AnimationEngine.compose(c, 900_000L)
        assertTrue(before.alpha <= 0.01f)
        assertEquals(1f, after.alpha, 0.001f)
        assertEquals(1f, after.scale, 0.001f)
    }
}
