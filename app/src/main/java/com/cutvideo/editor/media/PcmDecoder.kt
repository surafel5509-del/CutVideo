package com.cutvideo.editor.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int
) {
    val frames: Int get() = if (channels > 0) samples.size / channels else 0
    val durationUs: Long get() = if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L
}

/**
 * Decodes the audio track of any imported media to PCM16 with MediaExtractor + MediaCodec.
 *
 * This is what feeds the waveform display and the native mixing helpers. Decoding is bounded so a
 * 2-hour podcast cannot exhaust memory, and large files are never read fully into RAM.
 */
object PcmDecoder {

    private const val TAG = "PcmDecoder"
    private const val TIMEOUT_US = 10_000L
    private const val MAX_SECONDS = 300

    suspend fun decode(context: Context, uri: Uri, maxDurationUs: Long = MAX_SECONDS * 1_000_000L): DecodedAudio? =
        withContext(Dispatchers.IO) { decodeBlocking(context, uri, maxDurationUs) }

    private fun decodeBlocking(context: Context, uri: Uri, maxDurationUs: Long): DecodedAudio? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val output = ArrayList<Short>(sampleRate * channels * 8)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPtsUs = -1L

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            if (firstPtsUs < 0) firstPtsUs = pts
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer: ByteBuffer = codec.getOutputBuffer(outIndex) ?: continue
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val count = shorts.remaining()
                            val tmp = ShortArray(count)
                            shorts.get(tmp)
                            tmp.forEach { output.add(it) }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outIndex, false)

                        val elapsedUs = info.presentationTimeUs - firstPtsUs
                        if (elapsedUs > maxDurationUs) {
                            outputDone = true
                            inputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = newFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }

            if (output.isEmpty()) return null
            val samples = ShortArray(output.size)
            for (i in output.indices) samples[i] = output[i]
            // Drop a trailing partial frame so channel interleaving stays valid.
            val usable = samples.size - (samples.size % channels.coerceAtLeast(1))
            DecodedAudio(samples.copyOf(usable), sampleRate, channels)
        } catch (t: Throwable) {
            Log.w(TAG, "audio decode failed for $uri", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
