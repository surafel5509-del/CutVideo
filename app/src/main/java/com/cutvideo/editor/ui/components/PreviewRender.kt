package com.cutvideo.editor.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.EffectPreset
import com.cutvideo.editor.domain.model.FilterCatalog
import com.cutvideo.editor.domain.model.FilterPreset
import com.cutvideo.editor.nativebridge.NativeEffectIds
import com.cutvideo.editor.nativebridge.NativeEngine
import com.cutvideo.editor.nativebridge.NativeParamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Produces a *real* preview bitmap for a filter or effect card by pushing a decoded video frame
 * through the colour pipeline.
 *
 * Preferred path: C++ `applyColorPipelineWithEffects` through JNI (same code as the CPU renderer).
 * Fallback: Compose [ColorMatrix] built from the same preset — still real pixel maths, just without the
 * procedural effects.
 */
@Composable
fun rememberLookPreview(
    source: Bitmap?,
    clip: Clip,
    aspect: AspectRatio,
    nativeEngine: NativeEngine,
    effect: EffectPreset? = null,
    filter: FilterPreset? = null,
    effectIntensity: Float = effect?.defaultIntensity ?: 0f
): Bitmap? {
    val context = LocalContext.current
    val key = listOf(
        source?.hashCode(), clip.filter.id, clip.filter.intensity, aspect.name,
        effect?.id, effectIntensity,
        clip.adjust, clip.blur,
        filter?.id
    ).joinToString("|")

    return produceState<Bitmap?>(initialValue = null, key1 = key, key2 = nativeEngine.available) {
        val src = source ?: run {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val targetFilter = filter ?: FilterCatalog.preset(clip.filter.id)
            val previewClip = clip.copy(
                filter = com.cutvideo.editor.domain.model.FilterSpec(
                    id = targetFilter.id,
                    intensity = if (filter != null) 1f else clip.filter.intensity
                ),
                effects = if (effect != null) listOf(com.cutvideo.editor.domain.model.EffectSpec(effect.id, effectIntensity)) else clip.effects,
                // Preview cards must not show the clip's transition/fade state.
                adjust = clip.adjust.copy(vignette = if (filter != null) 0f else clip.adjust.vignette)
            )
            val copy = src.copy(Bitmap.Config.ARGB_8888, true)
            val applied = nativeEngine.applyLook(
                copy,
                NativeParamWriter.params(previewClip, aspect),
                NativeEffectIds.of(previewClip),
                NativeEffectIds.strengths(previewClip)
            )
            if (!applied) {
                // Kotlin fallback: apply the preset's colour matrix only.
                fallbackMatrix(copy, targetFilter)
            }
            copy
        }
    }.value
}

private fun fallbackMatrix(bitmap: Bitmap, preset: FilterPreset) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val m = preset.matrix
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val nr = (m[0] * r + m[1] * g + m[2] * b + m[3]).coerceIn(0f, 1f)
        val ng = (m[4] * r + m[5] * g + m[6] * b + m[7]).coerceIn(0f, 1f)
        val nb = (m[8] * r + m[9] * g + m[10] * b + m[11]).coerceIn(0f, 1f)
        pixels[i] = (p and 0xFF000000.toInt()) or
            ((nr * 255).toInt() shl 16) or
            ((ng * 255).toInt() shl 8) or
            (nb * 255).toInt()
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}

/** Compose colour matrix equivalent, used where a GPU/CPU render would be wasteful. */
fun FilterPreset.toColorMatrix(): ColorMatrix {
    val m = matrix
    return ColorMatrix(
        floatArrayOf(
            m[0], m[1], m[2], 0f, m[3] * 255f,
            m[4], m[5], m[6], 0f, m[7] * 255f,
            m[8], m[9], m[10], 0f, m[11] * 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

/** Helper for screens that show a one-off bitmap. */
fun Bitmap.toImage() = asImageBitmap()
