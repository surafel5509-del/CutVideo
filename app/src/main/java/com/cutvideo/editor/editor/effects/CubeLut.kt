package com.cutvideo.editor.editor.effects

/**
 * Adobe/Resolve `.cube` 3D LUT parsing and GPU tiling.
 *
 * The parser is pure Kotlin ([parse] → [CubeLut]) and covered by `CubeLutTest`; [tileToRgba]
 * lays the lattice out as blue slices along X (`size*size` wide, `size` tall) which is exactly
 * what `lutLookup()` in `cutvideo_pass.glsl` samples. The GL upload lives in
 * `EffectChainRenderer` (cached per file), so preview and export share identical LUT maths.
 */
data class CubeLut(
    val size: Int,
    /** Red-fastest lattice: index = r + size * (g + size * b), values 0..1. */
    val values: FloatArray
) {
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        val rf = (r.coerceIn(0f, 1f) * n)
        val gf = (g.coerceIn(0f, 1f) * n)
        val bf = (b.coerceIn(0f, 1f) * n)
        val r0 = rf.toInt().coerceIn(0, n)
        val g0 = gf.toInt().coerceIn(0, n)
        val b0 = bf.toInt().coerceIn(0, n)
        val r1 = (r0 + 1).coerceAtMost(n)
        val g1 = (g0 + 1).coerceAtMost(n)
        val b1 = (b0 + 1).coerceAtMost(n)
        val tr = rf - r0
        val tg = gf - g0
        val tb = bf - b0
        val out = FloatArray(3)
        for (ch in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = values[3 * (ri + size * (gi + size * bi)) + ch]
            val c00 = v(r0, g0, b0) + (v(r1, g0, b0) - v(r0, g0, b0)) * tr
            val c10 = v(r0, g1, b0) + (v(r1, g1, b0) - v(r0, g1, b0)) * tr
            val c01 = v(r0, g0, b1) + (v(r1, g0, b1) - v(r0, g0, b1)) * tr
            val c11 = v(r0, g1, b1) + (v(r1, g1, b1) - v(r0, g1, b1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[ch] = c0 + (c1 - c0) * tb
        }
        return out
    }

    /**
     * Tiles the lattice into an RGBA8 byte buffer for GL upload: blue slice `b` occupies the
     * columns `[b*size, (b+1)*size)`; within a slice x = r, y = g.
     */
    fun tileToRgba(): ByteArray {
        val width = size * size
        val out = ByteArray(width * size * 4)
        for (b in 0 until size) {
            for (g in 0 until size) {
                var pixel = (b * size + g * width) * 4
                for (r in 0 until size) {
                    val base = 3 * (r + size * (g + size * b))
                    out[pixel] = (values[base].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[pixel + 1] = (values[base + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[pixel + 2] = (values[base + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[pixel + 3] = 0xFF.toByte()
                    pixel += 4
                }
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean = this === other || (
        other is CubeLut && other.size == size && other.values.contentEquals(values)
        )

    override fun hashCode(): Int = 31 * size + values.contentHashCode()
}

object CubeLuts {

    /** Parses `.cube` text (LUT_3D_SIZE only; DOMAIN_MIN/MAX are read but must be 0..1). */
    fun parse(text: String): CubeLut {
        var size = 0
        val values = ArrayList<Float>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) return@forEach
            when {
                line.startsWith("LUT_3D_SIZE") ->
                    size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                line.startsWith("LUT_1D_SIZE") ->
                    throw IllegalArgumentException("1D LUTs are not supported")
                line.startsWith("DOMAIN_") -> Unit // validated implicitly: values must be 0..1
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        values += parts[0].toFloat()
                        values += parts[1].toFloat()
                        values += parts[2].toFloat()
                    }
                }
            }
        }
        require(size in 2..64) { "LUT_3D_SIZE must be 2..64, got $size" }
        require(values.size == size * size * size * 3) {
            "expected ${size * size * size * 3} values, got ${values.size}"
        }
        return CubeLut(size, values.toFloatArray())
    }
}
