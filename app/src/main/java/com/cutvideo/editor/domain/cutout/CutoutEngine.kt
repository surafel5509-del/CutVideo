package com.cutvideo.editor.domain.cutout

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Manual photo background remover: a real pixel-level brush engine over an ARGB buffer with an
 * 8-bit alpha layer, erase/restore brushes (size, hardness, opacity), stroke undo/redo and edge
 * refinement. Pure Kotlin on plain arrays — every behaviour is unit-testable (`CutoutEngineTest`)
 * and the UI renders exactly what this engine produces (checkerboard under the alpha).
 *
 * The source pixels are never modified: `composite()` derives the result, so the original photo is
 * preserved and any edit can always be rolled back to it.
 */
class CutoutEngine(
    val width: Int,
    val height: Int,
    originalPixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "bad size ${width}x$height" }
        require(originalPixels.size == width * height) { "pixel buffer must be width*height" }
    }

    /** The untouched source image (ARGB). */
    private val original: IntArray = originalPixels.copyOf()

    /** Alpha layer: 255 = fully visible, 0 = erased. */
    private val alpha = ByteArray(width * height) { 0xFF.toByte() }

    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    enum class Tool { ERASE, RESTORE }

    /** Copies of the current alpha (e.g. for a thumbnail); never the internal buffer. */
    fun alphaSnapshot(): ByteArray = alpha.copyOf()

    /** Pushes the current alpha onto the undo stack (call once at stroke start). */
    fun beginStroke() {
        undoStack.addLast(alpha.copyOf())
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(alpha.copyOf())
        System.arraycopy(previous, 0, alpha, 0, alpha.size)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(alpha.copyOf())
        System.arraycopy(next, 0, alpha, 0, alpha.size)
        return true
    }

    /** Restores every pixel to fully visible (keeps undo history). */
    fun resetAlpha() {
        beginStroke()
        for (i in alpha.indices) alpha[i] = 0xFF.toByte()
    }

    /**
     * Paints one brush stroke segment between two pixel-space points.
     * [size] is the brush diameter in pixels, [hardness] 0..1 (1 = hard edge), [opacity] 0..1.
     * Returns the dirty rectangle in pixel space so the UI can update only that region.
     */
    fun stroke(
        tool: Tool,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        size: Float,
        hardness: Float,
        opacity: Float
    ): IntArray {
        val radius = (size / 2f).coerceAtLeast(0.5f)
        val dist = kotlin.math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        val steps = max(1, (dist / (radius * 0.45f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            stamp(tool, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius, hardness, opacity)
        }
        val minX = (minOf(x0, x1) - radius - 2).toInt().coerceIn(0, width - 1)
        val minY = (minOf(y0, y1) - radius - 2).toInt().coerceIn(0, height - 1)
        val maxX = (maxOf(x0, x1) + radius + 2).toInt().coerceIn(0, width - 1)
        val maxY = (maxOf(y0, y1) + radius + 2).toInt().coerceIn(0, height - 1)
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun stamp(tool: Tool, cx: Float, cy: Float, radius: Float, hardness: Float, opacity: Float) {
        val minX = max(0, (cx - radius).toInt())
        val maxX = min(width - 1, (cx + radius).toInt())
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(height - 1, (cy + radius).toInt())
        val hard = hardness.coerceIn(0f, 1f)
        val op = opacity.coerceIn(0f, 1f)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d > radius) continue
                val coverage = falloff(d, radius, hard) * op
                if (coverage <= 0.002f) continue
                val index = y * width + x
                val current = alpha[index].toInt() and 0xFF
                val next = when (tool) {
                    Tool.ERASE -> (current * (1f - coverage)).toInt()
                    Tool.RESTORE -> (current + (255 - current) * coverage).toInt()
                }.coerceIn(0, 255)
                alpha[index] = next.toByte()
            }
        }
    }

    /**
     * Brush profile: hardness 1 → hard edge at the radius, hardness 0 → smooth gaussian-like
     * falloff across the whole brush.
     */
    private fun falloff(d: Float, radius: Float, hardness: Float): Float {
        val inner = radius * (0.15f + 0.8f * hardness)
        if (d <= inner) return 1f
        if (d >= radius) return 0f
        val t = (d - inner) / (radius - inner)
        return if (hardness > 0.6f) {
            1f - t
        } else {
            exp(-3.5f * t * t) * (1f - t)
        }
    }

    /**
     * Edge refinement: feathers/transparency-cleans the boundary of the current cutout with a
     * small blur applied only to edge pixels (fully kept and fully erased pixels are untouched).
     */
    fun refineEdges(radiusPx: Int = 1, passes: Int = 1) {
        if (radiusPx <= 0 || passes <= 0) return
        beginStroke()
        repeat(passes) {
            val src = alpha.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val center = src[i].toInt() and 0xFF
                    if (center == 0 || center == 255) continue // only refine the edge band
                    var sum = 0
                    var count = 0
                    for (oy in -radiusPx..radiusPx) {
                        for (ox in -radiusPx..radiusPx) {
                            val nx = x + ox
                            val ny = y + oy
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                            sum += src[ny * width + nx].toInt() and 0xFF
                            count++
                        }
                    }
                    if (count > 0) alpha[i] = (sum / count).toByte()
                }
            }
        }
    }

    /** Multiplies an extra global alpha (0..1) — used for the edge-softness control. */
    fun featherAll(amount: Float) {
        if (amount <= 0f) return
        beginStroke()
        val k = 1f - amount.coerceIn(0f, 1f) * 0.5f
        for (i in alpha.indices) {
            val current = alpha[i].toInt() and 0xFF
            alpha[i] = (current * k).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** Composites the current alpha over the original pixels (premultiplied-free ARGB out). */
    fun composite(): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val a = alpha[i].toInt() and 0xFF
            val p = original[i]
            out[i] = (p and 0x00FFFFFF) or (a shl 24)
        }
        return out
    }

    /** Composites only a rectangle (the UI updates just the brushed region while painting). */
    fun compositeRegion(x: Int, y: Int, w: Int, h: Int): IntArray {
        val x0 = x.coerceIn(0, width)
        val y0 = y.coerceIn(0, height)
        val x1 = (x + w).coerceIn(0, width)
        val y1 = (y + h).coerceIn(0, height)
        val out = IntArray(max(0, x1 - x0) * max(0, y1 - y0))
        var i = 0
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val idx = py * width + px
                val a = alpha[idx].toInt() and 0xFF
                out[i++] = (original[idx] and 0x00FFFFFF) or (a shl 24)
            }
        }
        return out
    }

    /** How much of the frame is currently visible (0..1) — handy for the "removed 42%" readout. */
    fun visibleFraction(): Float {
        var kept = 0
        for (b in alpha) if ((b.toInt() and 0xFF) > 8) kept++
        return kept.toFloat() / alpha.size
    }

    companion object {
        private const val MAX_UNDO = 24

        /** Loads an ARGB int array from an android.graphics.Bitmap (UI layer only). */
        fun fromBitmap(bitmap: android.graphics.Bitmap): CutoutEngine {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return CutoutEngine(bitmap.width, bitmap.height, pixels)
        }
    }
}
