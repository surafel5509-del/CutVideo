package com.cutvideo.editor.nativebridge

import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TrackType

/**
 * Flattens a [ProjectDoc] into the parallel primitive arrays consumed by
 * `cpp/jni_bridge.cpp::readClip` — no JSON, no per-frame allocations.
 */
data class NativeTimelineData(
    val longs: LongArray,
    val ints: IntArray,
    val floats: FloatArray,
    val count: Int,
    val clipIds: List<String>,
    val indexById: Map<String, Int>,
    val signature: Long
) {
    companion object {
        const val LONG_STRIDE = 10
        const val INT_STRIDE = 7
        const val FLOAT_STRIDE = 2
        val EMPTY = NativeTimelineData(LongArray(0), IntArray(0), FloatArray(0), 0, emptyList(), emptyMap(), 0L)
    }
}

object NativeTimelineEncoder {

    fun encode(doc: ProjectDoc): NativeTimelineData {
        val ordered = ArrayList<Clip>(doc.clipCount())
        doc.tracks.forEach { track ->
            // Native clip resolution expects each track's clips in time order.
            ordered += track.sortedClips
        }
        if (ordered.isEmpty()) return NativeTimelineData.EMPTY

        val count = ordered.size
        val longs = LongArray(count * NativeTimelineData.LONG_STRIDE)
        val ints = IntArray(count * NativeTimelineData.INT_STRIDE)
        val floats = FloatArray(count * NativeTimelineData.FLOAT_STRIDE)
        val ids = ArrayList<String>(count)
        val indexById = HashMap<String, Int>(count)
        var signature = 17L

        ordered.forEachIndexed { i, clip ->
            val lb = i * NativeTimelineData.LONG_STRIDE
            longs[lb + 0] = clip.startUs
            longs[lb + 1] = clip.durationUs
            longs[lb + 2] = clip.sourceInUs
            longs[lb + 3] = clip.sourceDurationUs
            longs[lb + 4] = clip.fadeInUs
            longs[lb + 5] = clip.fadeOutUs
            longs[lb + 6] = clip.animationIn.durationUs
            longs[lb + 7] = clip.animationOut.durationUs
            longs[lb + 8] = clip.transitionIn?.durationUs ?: 0L
            longs[lb + 9] = clip.transitionOut?.durationUs ?: 0L

            val ib = i * NativeTimelineData.INT_STRIDE
            ints[ib + 0] = clip.trackType.ordinal
            ints[ib + 1] = if (clip.muted) 1 else 0
            ints[ib + 2] = if (clip.reversed) 1 else 0
            ints[ib + 3] = clip.animationIn.type.ordinal
            ints[ib + 4] = clip.animationOut.type.ordinal
            ints[ib + 5] = clip.transitionIn?.type?.ordinal ?: 0
            ints[ib + 6] = clip.transitionOut?.type?.ordinal ?: 0

            val fb = i * NativeTimelineData.FLOAT_STRIDE
            floats[fb + 0] = clip.speed
            floats[fb + 1] = clip.volume

            ids += clip.id
            indexById[clip.id] = i

            signature = signature * 31 + clip.id.hashCode()
            signature = signature * 31 + clip.startUs
            signature = signature * 31 + clip.durationUs
            signature = signature * 31 + clip.speed.toRawBits()
        }

        return NativeTimelineData(longs, ints, floats, count, ids, indexById, signature)
    }

    /** Applies the native enum ordering for transition types (must match `TransitionType` in C++). */
    fun transitionOrdinal(type: com.cutvideo.editor.domain.model.TransitionType): Int = type.ordinal

    /** Convenience for tests/diagnostics. */
    fun trackTypeOrdinal(type: TrackType): Int = type.ordinal
}
