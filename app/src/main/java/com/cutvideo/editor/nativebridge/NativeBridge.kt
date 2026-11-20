package com.cutvideo.editor.nativebridge

import java.nio.ByteBuffer

/**
 * Raw JNI surface. Implemented in `cpp/jni_bridge.cpp`.
 *
 * Only [NativeEngine] should call these — the wrappers add capability checks so a missing or failed
 * native call can never take down the UI.
 */
internal object NativeBridge {

    external fun nativeVersion(): String

    external fun nativeEngineCreate(width: Int, height: Int): Long
    external fun nativeEngineDestroy(handle: Long)
    external fun nativeEngineSetTimeline(
        handle: Long,
        longs: LongArray,
        ints: IntArray,
        floats: FloatArray,
        count: Int
    )

    external fun nativeEngineSetLook(
        handle: Long,
        index: Int,
        params: FloatArray,
        effectIds: IntArray,
        effectIntensities: FloatArray
    )

    /** Renders the active clip's look into [buffer] (direct RGBA). Returns the clip index or -1. */
    external fun nativeEngineRenderFrame(
        handle: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        timelineUs: Long
    ): Int

    external fun nativeEngineGainAt(handle: Long, timelineUs: Long): Float
    external fun nativeEngineSourceTimeAt(handle: Long, timelineUs: Long): Long
    external fun nativeEngineDescribe(handle: Long): String

    /** One-shot colour pipeline for a bitmap (filter/effect cards, software fallback). */
    external fun nativeApplyLook(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        params: FloatArray,
        effectIds: IntArray,
        effectIntensities: FloatArray
    ): Boolean

    external fun nativeGenerateLut(params: FloatArray): ByteArray

    external fun nativeComputeWaveform(pcm: ShortArray, channels: Int, buckets: Int): FloatArray

    external fun nativeApplyGain(
        pcm: ShortArray,
        channels: Int,
        sampleRate: Int,
        gainStart: Float,
        gainEnd: Float,
        master: Float
    ): Boolean

    external fun nativeMixPcm(a: ShortArray, b: ShortArray, gainA: Float, gainB: Float): ShortArray

    external fun nativeResample(pcm: ShortArray, channels: Int, inRate: Int, outRate: Int): ShortArray

    external fun nativeSampleAnimation(type: Int, progress: Float): FloatArray
    external fun nativeSampleTransition(type: Int, progress: Float): FloatArray

    external fun nativeSourceTime(longs: LongArray, ints: IntArray, floats: FloatArray, timelineUs: Long): Long
}
