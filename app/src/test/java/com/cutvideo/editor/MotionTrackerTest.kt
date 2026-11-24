package com.cutvideo.editor

import com.cutvideo.editor.editor.tracking.MotionTracker
import com.cutvideo.editor.editor.tracking.TrackFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The motion tracker must recover known translations on synthetic frames. */
class MotionTrackerTest {

    /** Builds a frame with a bright square at (squareX, squareY) on a dark background. */
    private fun frame(timeUs: Long, squareX: Int, squareY: Int, w: Int = 64, h: Int = 48): TrackFrame {
        val luma = FloatArray(w * h) { 0.1f }
        for (y in squareY until (squareY + 10).coerceAtMost(h)) {
            for (x in squareX until (squareX + 12).coerceAtMost(w)) {
                luma[y * w + x] = 0.9f
            }
        }
        return TrackFrame(timeUs, luma, w, h)
    }

    @Test
    fun `recovers a constant translation`() {
        val frames = (0 until 6).map { i ->
            frame(i * 100_000L, 10 + i * 2, 8 + i)
        }
        val keys = MotionTracker.track(frames, 0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(6, keys.size)
        // After 5 frames the square moved (10, 5) pixels = (10/64, 5/48) of the frame.
        val last = keys.last()
        assertEquals(10f / 64f, last.dx, 0.03f)
        assertEquals(5f / 48f, last.dy, 0.03f)
        assertTrue("confidence ${last.confidence}", last.confidence > 0.3f)
    }

    @Test
    fun `stationary scene tracks near zero`() {
        val frames = (0 until 4).map { i -> frame(i * 100_000L, 20, 16) }
        val keys = MotionTracker.track(frames, 0.1f, 0.1f, 0.6f, 0.6f)
        assertEquals(4, keys.size)
        assertEquals(0f, keys.last().dx, 0.01f)
        assertEquals(0f, keys.last().dy, 0.01f)
    }

    @Test
    fun `handles empty input`() {
        assertTrue(MotionTracker.track(emptyList(), 0f, 0f, 1f, 1f).isEmpty())
    }
}
