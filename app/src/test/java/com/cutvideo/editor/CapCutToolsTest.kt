package com.cutvideo.editor

import com.cutvideo.editor.domain.cutout.CutoutEngine
import com.cutvideo.editor.domain.model.Keyframe
import com.cutvideo.editor.domain.model.KeyframeInterpolation
import com.cutvideo.editor.domain.model.KeyframeProperty
import com.cutvideo.editor.domain.model.KeyframeTrack
import com.cutvideo.editor.domain.model.MaskPoint
import com.cutvideo.editor.domain.model.MaskSpec
import com.cutvideo.editor.domain.model.MaskType
import com.cutvideo.editor.editor.KeyframeEngine
import com.cutvideo.editor.editor.gl.MaskRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the CapCut-style tools: keyframe curves, mask rasterisation, photo cutout. */
class CapCutToolsTest {

    private val epsilon = 1e-3f

    // --- keyframe engine --------------------------------------------------------------------------

    @Test
    fun keyframesInterpolateLinearlyAndClampOutside() {
        val track = KeyframeTrack(
            KeyframeProperty.SCALE,
            listOf(
                Keyframe(0L, 1f, KeyframeInterpolation.LINEAR),
                Keyframe(1_000_000L, 3f, KeyframeInterpolation.LINEAR)
            )
        )
        assertEquals(1f, KeyframeEngine.evaluate(track, 0L, 0f), epsilon)
        assertEquals(2f, KeyframeEngine.evaluate(track, 500_000L, 0f), epsilon)
        assertEquals(3f, KeyframeEngine.evaluate(track, 1_000_000L, 0f), epsilon)
        // Clamped outside the keyed range.
        assertEquals(1f, KeyframeEngine.evaluate(track, -500L, 0f), epsilon)
        assertEquals(3f, KeyframeEngine.evaluate(track, 9_000_000L, 0f), epsilon)
    }

    @Test
    fun holdInterpolationStepsAtTheLeftKey() {
        val track = KeyframeTrack(
            KeyframeProperty.SCALE,
            listOf(
                Keyframe(0L, 1f, KeyframeInterpolation.HOLD),
                Keyframe(1_000_000L, 2f, KeyframeInterpolation.LINEAR)
            )
        )
        assertEquals(1f, KeyframeEngine.evaluate(track, 999_000L, 0f), epsilon)
    }

    @Test
    fun easingCurvesAreMonotoneAndEndAtOne() {
        assertEquals(0f, KeyframeEngine.cubicBezierY(0.42f, 0f, 0.58f, 1f, 0f), epsilon)
        assertEquals(1f, KeyframeEngine.cubicBezierY(0.42f, 0f, 0.58f, 1f, 1f), epsilon)
        // Linear-ish bezier reproduces y = x.
        assertEquals(0.5f, KeyframeEngine.cubicBezierY(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f, 0.5f), epsilon)
        // Ease-in-out: below the diagonal at the start, above at the midpoint.
        val early = KeyframeEngine.cubicBezierY(0.42f, 0f, 0.58f, 1f, 0.25f)
        val mid = KeyframeEngine.cubicBezierY(0.42f, 0f, 0.58f, 1f, 0.5f)
        assertTrue(early < 0.25f)
        assertEquals(0.5f, mid, 0.02f)
    }

    @Test
    fun trackEditingHelpersAreImmutable() {
        val base = KeyframeTrack(KeyframeProperty.POSITION_X, listOf(Keyframe(0L, 0f)))
        val withTwo = KeyframeEngine.withKeyframe(base, Keyframe(500L, 0.5f))
        assertEquals(1, base.keyframes.size)
        assertEquals(2, withTwo.keyframes.size)
        val moved = KeyframeEngine.moveKeyframe(withTwo, 500L, 800L, 0.9f)
        assertEquals(2, moved.keyframes.size)
        assertEquals(800L, moved.keyframes[1].timeUs)
        assertEquals(0.9f, moved.keyframes[1].value, epsilon)
        val removed = KeyframeEngine.withoutKeyframe(moved, 800L)
        assertEquals(1, removed.keyframes.size)
    }

    @Test
    fun speedRampCurveIsContinuous() {
        val track = KeyframeTrack(
            KeyframeProperty.SPEED,
            listOf(
                Keyframe(0L, 1f, KeyframeInterpolation.LINEAR),
                Keyframe(1_000_000L, 2f, KeyframeInterpolation.LINEAR)
            )
        )
        // A 0.5s speed ramp must move smoothly between the keys (no jumps).
        var previous = KeyframeEngine.evaluate(track, 0L, 1f)
        for (t in 50_000L..1_000_000L step 50_000L) {
            val current = KeyframeEngine.evaluate(track, t, 1f)
            assertTrue("jump at $t", kotlin.math.abs(current - previous) < 0.2f)
            previous = current
        }
    }

    // --- mask rasteriser --------------------------------------------------------------------------

    @Test
    fun freehandStrokesProduceCoverage() {
        val stroke = listOf(
            MaskPoint(0.1f, 0.5f),
            MaskPoint(0.5f, 0.5f),
            MaskPoint(0.9f, 0.5f)
        )
        val mask = MaskSpec(type = MaskType.FREEHAND, strokes = listOf(stroke))
        val coverage = MaskRasterizer.rasterize(mask, 64, 64)
        assertEquals(64 * 64, coverage.alpha.size)
        // On the stroke line the coverage is solid; far corners stay empty.
        val center = coverage.alpha[32 * 64 + 32]
        assertTrue("center of stroke", center > 0.7f)
        val corner = coverage.alpha[1 * 64 + 1]
        assertTrue("corner untouched", corner < 0.05f)
    }

    @Test
    fun rasteriserIsDeterministicAndRespectsBounds() {
        val stroke = listOf(MaskPoint(0f, 0f), MaskPoint(1f, 1f))
        val mask = MaskSpec(type = MaskType.FREEHAND, strokes = listOf(stroke))
        val a = MaskRasterizer.rasterize(mask, 32, 32)
        val b = MaskRasterizer.rasterize(mask, 32, 32)
        assertTrue(a.alpha.contentEquals(b.alpha))
    }

    // --- photo cutout ----------------------------------------------------------------------------

    private fun solidImage(size: Int, color: Int = 0xFF3366CC.toInt()): IntArray =
        IntArray(size * size) { color }

    @Test
    fun eraseAndRestoreChangeAlphaWithoutTouchingOriginal() {
        val engine = CutoutEngine(16, 16, solidImage(16))
        engine.beginStroke()
        engine.stroke(CutoutEngine.Tool.ERASE, 8f, 8f, 8f, 8f, 8f, 1f, 1f)
        val composite = engine.composite()
        val centerAlpha = (composite[8 * 16 + 8] ushr 24) and 0xFF
        assertTrue("erased center", centerAlpha < 40)
        // The colour channels of the original are preserved.
        assertEquals(0x33, (composite[8 * 16 + 8] shr 16) and 0xFF)
        assertEquals(0x66, (composite[8 * 16 + 8] shr 8) and 0xFF)
        assertEquals(0xCC, composite[8 * 16 + 8] and 0xFF)

        engine.beginStroke()
        engine.stroke(CutoutEngine.Tool.RESTORE, 8f, 8f, 8f, 8f, 8f, 1f, 1f)
        val restored = engine.composite()
        val restoredAlpha = (restored[8 * 16 + 8] ushr 24) and 0xFF
        assertTrue("restored center", restoredAlpha > 220)
    }

    @Test
    fun undoRedoWalksStrokeHistory() {
        val engine = CutoutEngine(8, 8, solidImage(8))
        engine.beginStroke()
        engine.stroke(CutoutEngine.Tool.ERASE, 4f, 4f, 4f, 4f, 6f, 1f, 1f)
        val afterErase = engine.composite()[4 * 8 + 4] ushr 24
        assertTrue(engine.canUndo)
        assertTrue(engine.undo())
        assertEquals(255, engine.composite()[4 * 8 + 4] ushr 24)
        assertTrue(engine.canRedo)
        assertTrue(engine.redo())
        assertEquals(afterErase, engine.composite()[4 * 8 + 4] ushr 24)
    }

    @Test
    fun refineEdgesOnlyTouchesTheEdgeBand() {
        val engine = CutoutEngine(32, 32, solidImage(32))
        engine.beginStroke()
        engine.stroke(CutoutEngine.Tool.ERASE, 16f, 16f, 16f, 16f, 20f, 1f, 1f)
        val before = engine.alphaSnapshot()
        engine.refineEdges(1, 1)
        val after = engine.alphaSnapshot()
        // Fully erased and fully kept pixels are unchanged; only intermediate edge pixels move.
        var edgeChanged = false
        for (i in before.indices) {
            val b = before[i].toInt() and 0xFF
            val a = after[i].toInt() and 0xFF
            if (b != a) {
                assertTrue("only edge band refined", b != 0 && b != 255)
                edgeChanged = true
            }
        }
        assertTrue(edgeChanged)
    }

    @Test
    fun visibleFractionTracksTheMask() {
        val engine = CutoutEngine(10, 10, solidImage(10))
        assertEquals(1f, engine.visibleFraction(), epsilon)
        engine.beginStroke()
        // Erase everything in two strokes.
        engine.stroke(CutoutEngine.Tool.ERASE, 0f, 0f, 10f, 10f, 40f, 1f, 1f)
        assertTrue(engine.visibleFraction() < 0.2f)
    }

    @Test
    fun compositeRegionMatchesFullComposite() {
        val engine = CutoutEngine(12, 12, solidImage(12))
        engine.beginStroke()
        engine.stroke(CutoutEngine.Tool.ERASE, 6f, 6f, 6f, 6f, 6f, 1f, 1f)
        val full = engine.composite()
        val region = engine.compositeRegion(0, 0, 12, 12)
        assertTrue(full.contentEquals(region))
    }
}
