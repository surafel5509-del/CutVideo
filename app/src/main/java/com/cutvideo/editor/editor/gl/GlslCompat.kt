package com.cutvideo.editor.editor.gl

/**
 * Derives an OpenGL ES 2.0 variant of a `#version 300 es` shader at runtime.
 *
 * Media3's `DefaultVideoFrameProcessor` creates an ES 3.0 GL context when it can and silently falls
 * back to ES 2.0 otherwise (`createFocusedEglContextWithFallback`). A `#version 300 es` shader fails
 * to compile on an ES 2.0 context, and if *every* shader in a custom [androidx.media3.effect.
 * GlShaderProgram] fails to build the program draws nothing — the preview stays frozen while audio
 * keeps playing. Rather than shipping two hand-synchronised copies of every shader, CutVideo keeps
 * the ES 3.0 file as the single source of truth and converts it deterministically.
 *
 * The conversion covers exactly the constructs CutVideo's shaders use (verified by
 * `GlslCompatTest`); ES 3.0-only constructs are rejected loudly instead of producing a broken
 * shader.
 */
object GlslCompat {

    /** ES3-only built-ins that have no ES2 equivalent — refuse rather than emit broken GLSL. */
    private val ES3_ONLY = listOf(
        "textureLod", "textureGrad", "textureOffset", "texelFetch", "inverse(", "dFdx", "dFdy",
        "fwidth", "bitCount", "findLSB", "findMSB", "usampler", "isampler"
    )

    fun isEs3Only(source: String): Boolean = ES3_ONLY.any { source.contains(it) }

    /**
     * Converts an ES 3.0 (`#version 300 es`) shader to an ES 2.0 shader.
     *
     * @param source the ES 3.0 GLSL source.
     * @param fragment true for fragment shaders, false for vertex shaders.
     * @throws IllegalArgumentException if the source uses ES 3.0-only constructs.
     */
    fun toEs2(source: String, fragment: Boolean): String {
        require(!isEs3Only(source)) {
            "Shader uses an ES 3.0-only construct that has no ES 2.0 equivalent: " +
                ES3_ONLY.first { source.contains(it) }
        }
        val out = StringBuilder(source.length + 64)
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#version") -> {
                    // ES 2.0 accepts no #version 300 header; drop it.
                }
                fragment && line.startsWith("precision highp float") -> {
                    // highp is optional in ES 2.0 fragment shaders.
                    out.append("#ifdef GL_FRAGMENT_PRECISION_HIGH\n")
                        .append("precision highp float;\n")
                        .append("#else\n")
                        .append("precision mediump float;\n")
                        .append("#endif\n")
                }
                line.startsWith("out ") && fragment && line.contains("fragColor") -> {
                    // ES 2.0 fragment shaders write to the built-in gl_FragColor.
                }
                else -> {
                    var converted = raw
                    when {
                        line.startsWith("in ") ->
                            converted = raw.replaceFirst(Regex("""^(\s*)in\s"""), "$1" + (if (fragment) "varying " else "attribute "))
                        line.startsWith("out ") ->
                            converted = raw.replaceFirst(Regex("""^(\s*)out\s"""), "$1" + (if (fragment) "varying " else "varying "))
                    }
                    if (fragment) {
                        converted = converted
                            .replace(Regex("""\btexture\s*\("""), "texture2D(")
                            .replace(Regex("""\bfragColor\b"""), "gl_FragColor")
                    }
                    out.append(converted).append('\n')
                }
            }
        }
        return out.toString()
    }
}
