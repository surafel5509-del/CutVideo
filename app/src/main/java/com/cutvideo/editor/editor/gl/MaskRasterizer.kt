package com.cutvideo.editor.editor.gl

import com.cutvideo.editor.domain.model.MaskPoint
import com.cutvideo.editor.domain.model.MaskSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Rasterises a freehand [MaskSpec] into a coverage grid (0..1) that the GL shader samples through
 * `uMaskTexture`. Pure Kotlin on plain arrays so stroke geometry is unit-testable.
 */
object MaskRasterizer {

    /** The shader's freehand texture resolution (power-of-two friendly). */
    const val DEFAULT_SIZE = 256

    /** Brush radius as a fraction of the grid (matches the on-screen brush cursor). */
    const val DEFAULT_BRUSH_FRAC = 0.035f

    data class Coverage(val width: Int, val height: Int, val alpha: FloatArray)

    fun rasterize(mask: MaskSpec, width: Int = DEFAULT_SIZE, height: Int = DEFAULT_SIZE): Coverage {
        val grid = FloatArray(width * height)
        if (width <= 0 || height <= 0) return Coverage(width, height, grid)
        val radiusPx = DEFAULT_BRUSH_FRAC * maxOf(width, height)
        mask.strokes.forEach { stroke -> stampStroke(grid, width, height, stroke, radiusPx) }
        return Coverage(width, height, grid)
    }

    /** Stamps a soft circular brush along a polyline, unioning coverage (max blend). */
    fun stampStroke(
        grid: FloatArray,
        width: Int,
        height: Int,
        stroke: List<MaskPoint>,
        radiusPx: Float
    ) {
        if (stroke.isEmpty()) return
        val r = radiusPx.coerceAtLeast(0.75f)
        if (stroke.size == 1) {
            stamp(grid, width, height, stroke[0].xFrac * width, stroke[0].yFrac * height, r)
            return
        }
        for (i in 0 until stroke.size - 1) {
            val a = stroke[i]
            val b = stroke[i + 1]
            val ax = a.xFrac * width
            val ay = a.yFrac * height
            val bx = b.xFrac * width
            val by = b.yFrac * height
            val dist = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = ceil(dist / (r * 0.5f)).toInt().coerceIn(1, 2000)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                stamp(grid, width, height, ax + (bx - ax) * t, ay + (by - ay) * t, r)
            }
        }
    }

    private fun stamp(grid: FloatArray, width: Int, height: Int, cx: Float, cy: Float, radiusPx: Float) {
        val minX = floor(cx - radiusPx).toInt().coerceIn(0, width - 1)
        val maxX = floor(cx + radiusPx).toInt().coerceIn(0, width - 1)
        val minY = floor(cy - radiusPx).toInt().coerceIn(0, height - 1)
        val maxY = floor(cy + radiusPx).toInt().coerceIn(0, height - 1)
        val r2 = radiusPx * radiusPx
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d2 = dx * dx + dy * dy
                if (d2 <= r2) {
                    // Soft edge: linear falloff over the outer 35% of the brush.
                    val d = sqrt(d2)
                    val edge = radiusPx * 0.65f
                    val v = if (d <= edge) 1f else (1f - (d - edge) / (radiusPx - edge)).coerceIn(0f, 1f)
                    val index = y * width + x
                    if (v > grid[index]) grid[index] = v
                }
            }
        }
    }
}
