package com.cutvideo.editor.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice-over with the platform [MediaRecorder] (AAC in an MPEG-4 container).
 *
 * The file is written to the app's cache so it can be imported as a normal audio clip; nothing is left
 * behind if the take is discarded.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): File? {
        if (recorder != null) return output
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(192_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            output = file
            startedAt = System.currentTimeMillis()
            file
        }.getOrElse {
            runCatching { newRecorder.release() }
            recorder = null
            output = null
            null
        }
    }

    /** Stops the take and returns the recorded file (null when nothing usable was produced). */
    fun stop(): File? {
        val active = recorder ?: return null
        val file = output
        recorder = null
        output = null
        return runCatching {
            active.stop()
            active.release()
            if (file != null && file.exists() && file.length() > 1024) file else null
        }.getOrElse {
            runCatching { active.release() }
            file?.delete()
            null
        }
    }

    fun discard() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
        runCatching { active.release() }
        output?.delete()
        output = null
    }

    /** Elapsed wall-clock time of the current take, in microseconds. */
    fun elapsedUs(): Long =
        if (startedAt == 0L) 0L else (System.currentTimeMillis() - startedAt) * 1000L
}
