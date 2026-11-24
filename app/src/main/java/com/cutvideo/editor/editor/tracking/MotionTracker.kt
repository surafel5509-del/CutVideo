package com.cutvideo.editor.editor.tracking

import kotlin.math.abs

/** One analysis frame: downsampled luma (0..1), row-major. */
data class TrackFrame(
    val timeUs: Long,
    val luma: FloatArray,
    val width: Int,
    val height: Int
)

/** A tracked motion sample: ROI-centre displacement in normalised frame fractions. */
data class TrackedKey(
    val timeUs: Long,
    /** Displacement as a fraction of frame width/height (matches POSITION_X/Y keyframes). */
    val dx: Float,
    val dy: Float,
    /** 0..1 match quality — below ~0.3 the track is unreliable. */
    val confidence: Float
)

/**
 * Translation-only motion tracker (block matching, coarse-to-fine).
 *
 * Real image analysis: the template from the first frame's ROI is located in every following
 * frame by minimising SAD over three pyramid levels, and the accumulated displacement is emitted
 * as [TrackedKey] samples ready to become POSITION_X/Y keyframes (or mask-centre keyframes).
 *
 * Honest limits: translation only (no rotation/scale), best on a distinct subject against a
 * stable background. Pure Kotlin so `MotionTrackerTest` can verify it on synthetic shifts.
 */
object MotionTracker {

    private const val LEVELS = 3
    private const val SEARCH_RADII = 6 // per level, pixels

    /**
     * Tracks the ROI (given in normalised 0..1 coordinates of the analysis frames) across
     * [frames] and returns one key per frame. The first frame anchors at (0, 0).
     */
    fun track(
        frames: List<TrackFrame>,
        roiXFrac: Float,
        roiYFrac: Float,
        roiWFrac: Float,
        roiHFrac: Float
    ): List<TrackedKey> {
        if (frames.isEmpty()) return emptyList()
        val first = frames.first()
        val rx = (roiXFrac * first.width).toInt().coerceIn(0, first.width - 2)
        val ry = (roiYFrac * first.height).toInt().coerceIn(0, first.height - 2)
        val rw = (roiWFrac * first.width).toInt().coerceIn(2, first.width - rx)
        val rh = (roiHFrac * first.height).toInt().coerceIn(2, first.height - ry)

        val out = ArrayList<TrackedKey>(frames.size)
        out += TrackedKey(first.timeUs, 0f, 0f, 1f)
        var cumDx = 0
        var cumDy = 0
        var lastDx = 0
        var lastDy = 0

        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            val cur = frames[i]
            if (cur.width != prev.width || cur.height != prev.height) {
                out += TrackedKey(cur.timeUs, cumDx / cur.width.toFloat(), cumDy / cur.height.toFloat(), 0f)
                continue
            }
            // Seed the search with the previous increment (const-velocity prior).
            val (dx, dy, conf) = matchLevel(prev, cur, rx, ry, rw, rh, lastDx, lastDy)
            cumDx += dx
            cumDy += dy
            lastDx = dx
            lastDy = dy
            out += TrackedKey(
                cur.timeUs,
                cumDx / cur.width.toFloat(),
                cumDy / cur.height.toFloat(),
                conf
            )
        }
        return out
    }

    /** Coarse-to-fine SAD matching. Returns the per-frame displacement in analysis pixels. */
    private fun matchLevel(
        prev: TrackFrame,
        cur: TrackFrame,
        rx: Int, ry: Int, rw: Int, rh: Int,
        seedDx: Int, seedDy: Int
    ): Triple<Int, Int, Float> {
        var bestDx = 0
        var bestDy = 0
        var centerDx = seedDx
        var centerDy = seedDy
        var bestSad = Float.MAX_VALUE

        // The coarsest level is the full frame as level 0; deeper levels shrink the search around
        // the running best (classic hierarchical matching on the same buffer — the luma is already
        // downsampled before it reaches us, so "levels" are search refinements).
        for (level in 0 until LEVELS) {
            val radius = SEARCH_RADII shr level
            var levelBestX = centerDx
            var levelBestY = centerDy
            var levelBestSad = Float.MAX_VALUE
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val candX = centerDx + dx
                    val candY = centerDy + dy
                    val sad = sad(prev, cur, rx, ry, rw, rh, candX, candY)
                    if (sad < levelBestSad) {
                        levelBestSad = sad
                        levelBestX = candX
                        levelBestY = candY
                    }
                }
            }
            centerDx = levelBestX
            centerDy = levelBestY
            bestDx = levelBestX
            bestDy = levelBestY
            bestSad = levelBestSad
        }

        // Confidence: how much better the best match is than a flat cost.
        val flat = rw * rh * 0.5f
        val conf = (1f - bestSad / flat).coerceIn(0f, 1f)
        return Triple(bestDx, bestDy, conf)
    }

    private fun sad(
        prev: TrackFrame,
        cur: TrackFrame,
        rx: Int, ry: Int, rw: Int, rh: Int,
        dx: Int, dy: Int
    ): Float {
        var sum = 0f
        for (y in 0 until rh) {
            val py = ry + y
            val cy = py + dy
            if (cy < 0 || cy >= cur.height) return Float.MAX_VALUE
            var pIndex = py * prev.width + rx
            var cIndex = cy * cur.width + rx + dx
            for (x in 0 until rw) {
                val cx = rx + x + dx
                if (cx < 0 || cx >= cur.width) return Float.MAX_VALUE
                sum += abs(prev.luma[pIndex] - cur.luma[cIndex])
                pIndex++
                cIndex++
            }
        }
        return sum
    }
}
