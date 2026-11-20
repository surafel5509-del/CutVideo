package com.cutvideo.editor.domain.export

import com.cutvideo.editor.domain.model.AspectRatio
import kotlin.math.roundToInt

/** Target resolutions expressed as the *short* edge so portrait and landscape both behave. */
enum class ExportResolution(val shortSide: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P1440(1440, "1440p"),
    P2160(2160, "4K");

    /** Output pixel size for a given project aspect, always even (encoder requirement). */
    fun sizeFor(aspect: AspectRatio): Pair<Int, Int> {
        val ratio = aspect.ratio
        val (w, h) = if (ratio >= 1f) {
            (shortSide * ratio).roundToInt() to shortSide
        } else {
            shortSide to (shortSide / ratio).roundToInt()
        }
        return (w / 2 * 2) to (h / 2 * 2)
    }

    fun bitrateFor(aspect: AspectRatio, fps: Int, codec: VideoCodec): Int {
        val (w, h) = sizeFor(aspect)
        // bits per pixel per frame: H.265 needs ~35% less than H.264 for the same quality.
        val bpp = if (codec == VideoCodec.H265) 0.085 else 0.13
        val raw = w.toDouble() * h * fps * bpp
        return raw.roundToInt().coerceIn(1_000_000, 90_000_000)
    }
}

enum class VideoCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"),
    H265("video/hevc", "H.265")
}

enum class ExportFrameRate(val fps: Int, val label: String) {
    FPS24(24, "24 fps"),
    FPS25(25, "25 fps"),
    FPS30(30, "30 fps"),
    FPS60(60, "60 fps")
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS30,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateOverride: Int? = null
) {
    fun size(aspect: AspectRatio): Pair<Int, Int> = resolution.sizeFor(aspect)
    fun bitrate(aspect: AspectRatio): Int =
        bitrateOverride ?: resolution.bitrateFor(aspect, frameRate.fps, codec)
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Preparing(val message: String = "Preparing media…") : ExportState
    data class Running(
        val progress: Float,
        val frameRate: Float?,
        val estimatedRemainingMs: Long?,
        val stage: String
    ) : ExportState

    data class Success(val outputPath: String, val contentUri: String?, val sizeBytes: Long) : ExportState
    data class Failed(val message: String, val cause: Throwable? = null) : ExportState
    data object Cancelled : ExportState
}
