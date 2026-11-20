package com.cutvideo.editor.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cutvideo.editor.nativebridge.NativeEngine
import java.nio.ByteBuffer

/**
 * Per-clip audio gain for the export pipeline.
 *
 * Responsibilities:
 *  - optional lead-in silence so an audio clip lands at its absolute timeline position even though
 *    Media3 sequences are gapless,
 *  - constant clip volume,
 *  - linear fade in / fade out over the clip's own duration.
 *
 * When the native library is available the sample loop is handed to `applyGainAutomation` in
 * `audio_processor.cpp`; otherwise the same ramp is computed in Kotlin. A per-clip instance is used,
 * so frame counting from zero is exact.
 */
@UnstableApi
class GainAudioProcessor(
    private val leadSilenceUs: Long,
    private val gain: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val clipDurationUs: Long,
    private val nativeEngine: NativeEngine
) : BaseAudioProcessor() {

    private var frameIndex = 0L
    private var bytesPerFrame = 4
    private var sampleRate = 48_000
    private var channels = 2
    private var silenceEmitted = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onFlush() {
        frameIndex = 0L
        silenceEmitted = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        var silenceFrames = 0L
        if (!silenceEmitted && leadSilenceUs > 0L) {
            silenceFrames = leadSilenceUs * sampleRate / 1_000_000L
            silenceEmitted = true
        }
        val silenceBytes = (silenceFrames * bytesPerFrame).toInt()

        val output = replaceOutputBuffer(silenceBytes + remaining)
        repeat(silenceBytes) { output.put(0) }

        val start = output.position()
        output.put(inputBuffer)

        // Gain ramp inside this buffer using the native DSP when available.
        val frames = remaining / bytesPerFrame
        val startUs = frameIndex * 1_000_000L / sampleRate.coerceAtLeast(1)
        val endUs = startUs + frames * 1_000_000L / sampleRate.coerceAtLeast(1)
        if (nativeEngine.available && frames > 0) {
            val shorts = ShortArray(frames * channels)
            val slice = output.duplicate()
            slice.position(start)
            slice.asShortBuffer().get(shorts)
            // Apply an average of the ramp endpoints over the buffer for stability at small sizes.
            val gStart = gainAt(startUs)
            val gEnd = gainAt(endUs)
            nativeEngine.applyGain(shorts, channels, sampleRate, gStart, gEnd, 1f)
            val patched = output.duplicate()
            patched.position(start)
            patched.asShortBuffer().put(shorts)
        } else {
            val shortView = output.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            shortView.position(start / 2)
            for (f in 0 until frames) {
                val t = (frameIndex + f) * 1_000_000L / sampleRate.coerceAtLeast(1)
                val g = gainAt(t)
                for (c in 0 until channels) {
                    if (shortView.hasRemaining()) {
                        val index = shortView.position()
                        val value = (shortView.get(index) * g).toInt().coerceIn(-32768, 32767)
                        shortView.put(index, value.toShort())
                    }
                }
            }
        }

        frameIndex += frames
        output.flip()
    }

    private fun gainAt(us: Long): Float {
        var g = gain
        if (fadeInUs > 0 && us < fadeInUs) {
            g *= (us.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0 && clipDurationUs > 0) {
            val fadeOutStart = clipDurationUs - fadeOutUs
            if (us > fadeOutStart) {
                g *= ((clipDurationUs - us).toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
            }
        }
        return g.coerceIn(0f, 2f)
    }

    companion object {
        /** True when the processor would not change the audio at all. */
        fun isNoOp(gain: Float, fadeInUs: Long, fadeOutUs: Long, leadSilenceUs: Long): Boolean =
            kotlin.math.abs(gain - 1f) < 0.001f && fadeInUs <= 0 && fadeOutUs <= 0 && leadSilenceUs <= 0

        const val UNKNOWN = C.LENGTH_UNSET.toLong()
    }
}
