package com.cutvideo.editor.nativebridge

import android.graphics.Bitmap
import android.util.Log
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Safe façade over the JNI surface.
 *
 * Layout of the bridge:
 *  - [attach] pushes the flattened timeline plus one look per clip
 *  - [renderFrame] applies the active clip's look to a decoded frame (software path)
 *  - the GPU path uses the same numbers through [CutVideoGlEffect][com.cutvideo.editor.editor.gl.CutVideoGlEffect]
 *  - audio helpers (waveform, gain ramps, mixing) are used by the audio editor and export
 *
 * Every method degrades gracefully: if the library failed to load the caller gets `null`/`false` and
 * uses the Kotlin fallback.
 */
class NativeEngine {

    val available: Boolean = NativeLib.isLoaded

    private var handle: Long = 0L
    private var attachedSignature: Long = -1L
    private var attachedDocId: String? = null
    private var indexById: Map<String, Int> = emptyMap()
    private var lastError: String? = null

    val error: String? get() = lastError

    fun attach(doc: ProjectDoc): Boolean {
        if (!available) return false
        val data = NativeTimelineEncoder.encode(doc)
        if (handle != 0L && attachedDocId == doc.id && attachedSignature == data.signature) {
            indexById = data.indexById
            return true
        }
        return runCatching {
            if (handle == 0L) {
                handle = NativeBridge.nativeEngineCreate(1280, 720)
                if (handle == 0L) {
                    lastError = "nativeEngineCreate returned 0"
                    return false
                }
            }
            NativeBridge.nativeEngineSetTimeline(handle, data.longs, data.ints, data.floats, data.count)
            doc.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    val index = data.indexById[clip.id] ?: return@forEach
                    setLook(index, clip, doc.aspect, 0L)
                }
            }
            indexById = data.indexById
            attachedSignature = data.signature
            attachedDocId = doc.id
            true
        }.getOrElse {
            lastError = it.message
            Log.e(TAG, "attach failed", it)
            false
        }
    }

    fun setLook(index: Int, clip: Clip, aspect: AspectRatio, timelineUs: Long) {
        if (!available || handle == 0L) return
        runCatching {
            NativeBridge.nativeEngineSetLook(
                handle,
                index,
                NativeParamWriter.params(clip, aspect, timelineUs),
                NativeEffectIds.of(clip),
                NativeEffectIds.strengths(clip)
            )
        }.onFailure { Log.w(TAG, "setLook failed: ${it.message}") }
    }

    /** Updates the look of a clip after the user edits it. */
    fun refreshClip(doc: ProjectDoc, clip: Clip) {
        val index = indexById[clip.id] ?: return
        setLook(index, clip, doc.aspect, 0L)
    }

    /** Applies the active clip's look to [bitmap]. Returns the active clip index (or -1). */
    fun renderFrame(bitmap: Bitmap, timelineUs: Long): Int {
        if (!available || handle == 0L || bitmap.isRecycled) return -1
        return runCatching {
            val buffer = bitmap.toDirectBuffer()
            val index = NativeBridge.nativeEngineRenderFrame(
                handle, buffer, bitmap.width, bitmap.height, bitmap.rowBytes, timelineUs
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            index
        }.getOrElse {
            lastError = it.message
            -1
        }
    }

    /** One-shot colour pipeline (filter & effect cards, screenshots, fallback rendering). */
    fun applyLook(
        bitmap: Bitmap,
        params: FloatArray,
        effectIds: IntArray = IntArray(0),
        effectStrengths: FloatArray = FloatArray(0)
    ): Boolean {
        if (!available || bitmap.isRecycled) return false
        return runCatching {
            val buffer = bitmap.toDirectBuffer()
            val ok = NativeBridge.nativeApplyLook(
                buffer, bitmap.width, bitmap.height, bitmap.rowBytes, params, effectIds, effectStrengths
            )
            if (ok) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            }
            ok
        }.getOrElse {
            lastError = it.message
            false
        }
    }

    fun generateLut(params: FloatArray): ByteArray? = if (available) {
        runCatching { NativeBridge.nativeGenerateLut(params) }.getOrNull()
    } else null

    fun waveform(pcm: ShortArray, channels: Int, buckets: Int): FloatArray? = if (available) {
        runCatching { NativeBridge.nativeComputeWaveform(pcm, channels, buckets) }.getOrNull()
    } else null

    /** In-place gain ramp over a PCM buffer (used by the export audio processor). */
    fun applyGain(pcm: ShortArray, channels: Int, sampleRate: Int, gainStart: Float, gainEnd: Float, master: Float): Boolean =
        available && runCatching {
            NativeBridge.nativeApplyGain(pcm, channels, sampleRate, gainStart, gainEnd, master)
        }.getOrDefault(false)

    fun mix(a: ShortArray, b: ShortArray, gainA: Float, gainB: Float): ShortArray? = if (available) {
        runCatching { NativeBridge.nativeMixPcm(a, b, gainA, gainB) }.getOrNull()
    } else null

    fun resample(pcm: ShortArray, channels: Int, inRate: Int, outRate: Int): ShortArray? = if (available) {
        runCatching { NativeBridge.nativeResample(pcm, channels, inRate, outRate) }.getOrNull()
    } else null

    fun sampleAnimation(typeOrdinal: Int, progress: Float): FloatArray? = if (available) {
        runCatching { NativeBridge.nativeSampleAnimation(typeOrdinal, progress) }.getOrNull()
    } else null

    fun sampleTransition(typeOrdinal: Int, progress: Float): FloatArray? = if (available) {
        runCatching { NativeBridge.nativeSampleTransition(typeOrdinal, progress) }.getOrNull()
    } else null

    fun gainAt(timelineUs: Long): Float =
        if (available && handle != 0L) runCatching { NativeBridge.nativeEngineGainAt(handle, timelineUs) }
            .getOrDefault(1f) else 1f

    fun sourceTimeAt(timelineUs: Long): Long =
        if (available && handle != 0L) runCatching { NativeBridge.nativeEngineSourceTimeAt(handle, timelineUs) }
            .getOrDefault(0L) else 0L

    fun describe(): String = when {
        !available -> "Native engine unavailable"
        handle == 0L -> "Native engine idle"
        else -> runCatching { NativeBridge.nativeEngineDescribe(handle) }.getOrDefault("Native engine ready")
    }

    fun detach() {
        if (handle != 0L) {
            runCatching { NativeBridge.nativeEngineDestroy(handle) }
            handle = 0L
        }
        attachedSignature = -1L
        attachedDocId = null
        indexById = emptyMap()
    }

    private fun Bitmap.toDirectBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        copyPixelsToBuffer(buffer)
        buffer.rewind()
        return buffer
    }

    private companion object {
        const val TAG = "NativeEngine"
    }
}
