package com.cutvideo.editor.export

import android.content.Context
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.cutvideo.editor.domain.export.ExportSettings
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.editor.TextRasterizer
import com.cutvideo.editor.editor.gl.CutVideoGlEffect
import com.cutvideo.editor.editor.gl.OverlayCompositorGlEffect
import com.cutvideo.editor.editor.gl.TextOverlayEntry
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Builds and runs the export with Media3 Transformer.
 *
 * The composition is generated from the same [ProjectDoc] the preview uses:
 *  - one video sequence with one [EditedMediaItem] per video clip (clipping = trim, speed effect,
 *    the CutVideo GPU shader, then the text compositor),
 *  - one audio sequence per audio track (with the gain processor placing each clip absolutely),
 *  - output resolution / frame rate / codec from [ExportSettings].
 *
 * Progress comes from `Transformer.getProgress()` and the export listener — never synthesised.
 */
@UnstableApi
class TransformerExporter(
    private val context: Context,
    private val nativeEngine: NativeEngine,
    private val scope: CoroutineScope
) {

    data class Plan(
        val composition: Composition,
        val outputWidth: Int,
        val outputHeight: Int,
        val warnings: List<String>,
        val itemCount: Int,
        val estimatedDurationUs: Long
    )

    data class Result(val outputFile: File, val durationUs: Long, val warnings: List<String>)

    var lastTransformer: Transformer? = null
        private set

    /** Validates the project and builds the Media3 composition (also used for the pre-flight UI). */
    fun buildPlan(doc: ProjectDoc, settings: ExportSettings): Plan {
        val videoClips = doc.tracks
            .filter { it.type == TrackType.VIDEO }
            .flatMap { it.sortedClips }
            .filter { it.uri.isNotBlank() }
        if (videoClips.isEmpty()) throw ExportException("Add at least one video clip before exporting")

        val (width, height) = settings.size(doc.aspect)
        val fps = settings.frameRate.fps
        val warnings = mutableListOf<String>()

        if (videoClips.any { it.reversed }) {
            warnings.add("Reversed clips play forward in the export (reverse decoding is not supported yet)")
        }
        if (videoClips.any { it.stabilized }) {
            warnings.add("Stabilisation is a preview-only hint on this device")
        }
        val textClips = doc.textClips().filter { !it.text?.text.isNullOrBlank() }
        val overlays = doc.overlayClips().filter { it.pip.enabled && it.uri.isNotBlank() }
        if (overlays.isNotEmpty()) {
            warnings.add(
                "Picture-in-picture overlays are previewed live but are not flattened into this export yet"
            )
        }

        var compositionCursorUs = 0L
        val items = ArrayList<EditedMediaItem>(videoClips.size)
        videoClips.forEach { clip ->
            val clipStartUs = compositionCursorUs
            val item = buildVideoItem(
                clip = clip,
                doc = doc,
                settings = settings,
                width = width,
                height = height,
                textClips = textClips,
                clipStartUs = clipStartUs,
                warnings = warnings
            )
            items += item
            compositionCursorUs += clip.durationUs
        }

        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence(items)

        doc.tracks.filter { it.type == TrackType.AUDIO && !it.muted }.forEach { track ->
            val audioItems = buildAudioSequence(track.clips, track.volume, settings)
            if (audioItems.isNotEmpty()) sequences += EditedMediaItemSequence(audioItems)
        }

        val composition = Composition.Builder(sequences).build()
        return Plan(
            composition = composition,
            outputWidth = width,
            outputHeight = height,
            warnings = warnings.distinct(),
            itemCount = items.size,
            estimatedDurationUs = compositionCursorUs
        )
    }

    private fun buildVideoItem(
        clip: Clip,
        doc: ProjectDoc,
        settings: ExportSettings,
        width: Int,
        height: Int,
        textClips: List<Clip>,
        clipStartUs: Long,
        warnings: MutableList<String>
    ): EditedMediaItem {
        val videoEffects = ArrayList<Effect>()

        if (clip.transform.isCropped) {
            val t = clip.transform
            // Crop takes normalised device coordinates: left, right, bottom, top.
            videoEffects += Crop(
                -1f + 2f * t.cropLeft,
                1f - 2f * t.cropRight,
                -1f + 2f * t.cropBottom,
                1f - 2f * t.cropTop
            )
        }

        if (clip.transform.rotationDegrees != 0f || clip.transform.scaleX != 1f || clip.transform.scaleY != 1f) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(clip.transform.rotationDegrees)
                .setScale(clip.transform.scaleX, clip.transform.scaleY)
                .build()
        }

        if (clip.speed != 1f) {
            videoEffects += SpeedChangeEffect(clip.speed)
        }

        videoEffects += CutVideoGlEffect(
            clipProvider = { clip },
            aspect = doc.aspect,
            applyAnimation = true,
            // Media3 hands presentation timestamps in the composition timeline; convert to clip-local.
            localTimeProvider = { presentationTimeUs -> presentationTimeUs - clipStartUs }
        )

        val entries = textOverlaysFor(textClips, clip, clipStartUs, width, height)
        if (entries.isNotEmpty()) {
            videoEffects += OverlayCompositorGlEffect(entries)
        }

        videoEffects += FrameDropEffect.createDefaultFrameDropEffect(settings.frameRate.fps.toFloat())
        videoEffects += Presentation.createForWidthAndHeight(
            width,
            height,
            Presentation.LAYOUT_SCALE_TO_FIT
        )

        val audioProcessors = ArrayList<AudioProcessor>()
        if (clip.speed != 1f) {
            audioProcessors += SpeedChangingAudioProcessor(clip.speed)
        }
        val lead = 0L
        if (!clip.muted && !GainAudioProcessor.isNoOp(clip.volume, clip.fadeInUs, clip.fadeOutUs, lead)) {
            audioProcessors += GainAudioProcessor(
                leadSilenceUs = lead,
                gain = clip.effectiveVolume,
                fadeInUs = clip.fadeInUs,
                fadeOutUs = clip.fadeOutUs,
                clipDurationUs = clip.durationUs,
                nativeEngine = nativeEngine
            )
        }

        val builder = EditedMediaItem.Builder(mediaItemFor(clip))
            .setEffects(Effects(audioProcessors, videoEffects))
            .setFrameRate(settings.frameRate.fps)
        if (clip.muted) builder.setRemoveAudio(true)

        if (clip.uri.isBlank()) warnings.add("Clip '${clip.name}' has no media and was skipped")
        return builder.build()
    }

    private fun buildAudioSequence(clips: List<Clip>, trackVolume: Float, settings: ExportSettings): List<EditedMediaItem> {
        val sorted = clips.filter { it.uri.isNotBlank() }.sortedBy { it.startUs }
        val items = ArrayList<EditedMediaItem>(sorted.size)
        var cursorUs = 0L
        sorted.forEach { clip ->
            val leadSilence = max(0L, clip.startUs - cursorUs)
            val processors = ArrayList<AudioProcessor>()
            if (clip.speed != 1f) processors += SpeedChangingAudioProcessor(clip.speed)
            val gain = clip.effectiveVolume * trackVolume
            if (!GainAudioProcessor.isNoOp(gain, clip.fadeInUs, clip.fadeOutUs, leadSilence)) {
                processors += GainAudioProcessor(
                    leadSilenceUs = leadSilence,
                    gain = gain.coerceIn(0f, 2f),
                    fadeInUs = clip.fadeInUs,
                    fadeOutUs = clip.fadeOutUs,
                    clipDurationUs = clip.durationUs + leadSilence,
                    nativeEngine = nativeEngine
                )
            }
            items += EditedMediaItem.Builder(mediaItemFor(clip))
                .setEffects(Effects(processors, listOf()))
                .setFrameRate(settings.frameRate.fps)
                .build()
            cursorUs = clip.endUs
        }
        return items
    }

    private fun textOverlaysFor(
        textClips: List<Clip>,
        videoClip: Clip,
        clipStartUs: Long,
        width: Int,
        height: Int
    ): List<TextOverlayEntry> {
        val clipStart = videoClip.startUs
        val clipEnd = videoClip.endUs
        val entries = ArrayList<TextOverlayEntry>()
        textClips.forEach { text ->
            val visibleStart = max(text.startUs, clipStart)
            val visibleEnd = min(text.endUs, clipEnd)
            if (visibleEnd <= visibleStart) return@forEach
            val spec = text.text ?: return@forEach
            val raster = TextRasterizer.rasterize(spec, width, height) ?: return@forEach
            entries += TextOverlayEntry(
                clip = text,
                raster = raster,
                windowStartUs = visibleStart - clipStart,   // item-local
                windowEndUs = visibleEnd - clipStart
            )
        }
        return entries
    }

    private fun mediaItemFor(clip: Clip): MediaItem {
        val builder = MediaItem.Builder().setUri(clip.uri)
        if (clip.sourceInUs > 0L || clip.sourceDurationUs > 0L) {
            val clipper = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(clip.sourceInUs.coerceAtLeast(0L))
            if (clip.sourceDurationUs > 0L) {
                val end = min(clip.sourceInUs + clip.sourceSpanUs, clip.sourceDurationUs)
                clipper.setEndPositionUs(max(end, clip.sourceInUs + 10_000L))
            } else {
                clipper.setEndPositionUs(clip.sourceInUs + clip.sourceSpanUs)
            }
            builder.setClippingConfiguration(clipper.build())
        }
        return builder.build()
    }

    /**
     * Runs the export. Suspends until Transformer reports completion, error or cancellation.
     * Progress is polled from `Transformer.getProgress` and reported through [onProgress].
     */
    suspend fun export(
        plan: Plan,
        settings: ExportSettings,
        outputFile: File,
        onProgress: (progress: Float, framesPerSecond: Float?) -> Unit
    ): Result = suspendCancellableCoroutine { continuation ->
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(settings.codec.mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                    progressJob?.cancel()
                    if (continuation.isActive) {
                        continuation.resume(
                            Result(outputFile, plan.estimatedDurationUs, plan.warnings)
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException
                ) {
                    progressJob?.cancel()
                    Log.e(TAG, "export failed", exportException)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ExportException(describeError(exportException), exportException)
                        )
                    }
                }
            })
            .build()
        lastTransformer = transformer

        val progressHolder = ProgressHolder()
        var progressJob: Job? = null

        transformer.start(plan.composition, outputFile.absolutePath)

        progressJob = scope.launch(Dispatchers.Default) {
            while (continuation.isActive) {
                val state = runCatching { transformer.getProgress(progressHolder) }.getOrNull()
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f, null)
                }
                delay(250)
            }
        }

        continuation.invokeOnCancellation {
            progressJob?.cancel()
            runCatching { transformer.cancel() }
            runCatching { outputFile.delete() }
        }
    }

    fun cancel() {
        runCatching { lastTransformer?.cancel() }
    }

    private fun describeError(error: androidx.media3.transformer.ExportException): String {
        val code = error.errorCodeName
        val extra = error.message?.takeIf { it.isNotBlank() } ?: ""
        return when {
            code.contains("ENCODER", ignoreCase = true) ->
                "The device could not initialise the requested encoder ($code). Try a lower resolution or H.264."
            code.contains("DECOD", ignoreCase = true) ->
                "A source clip could not be decoded ($code). $extra"
            code.contains("MUXER", ignoreCase = true) ->
                "The output file could not be written ($code). Check free storage space."
            else -> "Export failed ($code). $extra"
        }.trim()
    }

    private companion object {
        const val TAG = "TransformerExporter"
    }
}
