package com.cutvideo.editor.export

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
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
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class ExportException(
    message: String,
    cause: Throwable? = null,
    /** Transformer's `errorCodeName` when this wraps a real `ExportException` from Media3. */
    val errorCodeName: String = ""
) : Exception(message, cause)

/**
 * Builds and runs the export with Media3 Transformer.
 *
 * The composition is generated from the same [ProjectDoc] the preview uses:
 *  - one video sequence with one [EditedMediaItem] per video clip (clipping = trim, speed through
 *    `EditedMediaItem.setSpeed`, the CutVideo GPU shader, then the text compositor),
 *  - one audio sequence per audio track (gaps place each clip at its absolute timeline position,
 *    the gain processor applies volume and fades),
 *  - output resolution / frame rate / codec from [ExportSettings].
 *
 * Threading contract (critical): `Transformer` must be created and accessed from one and the same
 * thread's `Looper` — `Transformer.start`, `getProgress` and `cancel` all call
 * `verifyApplicationThread()`. This class owns a dedicated [HandlerThread] for the whole export so
 * every Transformer call happens on that looper and none of them can throw
 * `IllegalStateException: Transformer is accessed on the wrong thread.`
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

    private var exportThread: HandlerThread? = null
    private var exportHandler: Handler? = null

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
        sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items)

        doc.tracks.filter { it.type == TrackType.AUDIO && !it.muted }.forEach { track ->
            buildAudioSequence(track.clips, track.volume, settings, warnings)?.let { sequence ->
                sequences += sequence
            }
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

        val t = clip.transform
        if (t.isCropped) {
            val left = t.cropLeft.coerceIn(0f, 0.98f)
            val right = t.cropRight.coerceIn(0f, 0.98f)
            val top = t.cropTop.coerceIn(0f, 0.98f)
            val bottom = t.cropBottom.coerceIn(0f, 0.98f)
            // Media3's Crop throws IllegalArgumentException unless right > left and top > bottom,
            // so only emit the effect when the crop region is genuinely valid.
            if (left + right < 1f && top + bottom < 1f) {
                // Crop takes normalised device coordinates: left, right, bottom, top.
                videoEffects += Crop(
                    -1f + 2f * left,
                    1f - 2f * right,
                    -1f + 2f * bottom,
                    1f - 2f * top
                )
            } else {
                warnings.add("Clip '${clip.name}' has an empty crop region — crop ignored")
            }
        }

        if (t.rotationDegrees != 0f || t.scaleX != 1f || t.scaleY != 1f) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(t.rotationDegrees)
                .setScale(t.scaleX.coerceIn(0.05f, 10f), t.scaleY.coerceIn(0.05f, 10f))
                .build()
        }

        // NOTE: speed is applied ONLY through EditedMediaItem.setSpeed below. Media3 1.11 rejects
        // (checkState in EditedMediaItem) an item that combines a SpeedProvider with a
        // speed-changing effect such as SpeedChangeEffect, and applying it twice would also break
        // the clip durations. setSpeed adjusts video timestamps *and* the audio (with pitch
        // handling) so the item lands on the timeline at the duration the document expects.

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
        if (!clip.muted && !GainAudioProcessor.isNoOp(clip.effectiveVolume, clip.fadeInUs, clip.fadeOutUs, 0L)) {
            audioProcessors += GainAudioProcessor(
                leadSilenceUs = 0L,
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
        if (clip.speed != 1f && clip.speed > 0f) builder.setSpeed(FixedSpeedProvider(clip.speed))
        if (clip.muted) builder.setRemoveAudio(true)

        if (clip.uri.isBlank()) warnings.add("Clip '${clip.name}' has no media and was skipped")
        return builder.build()
    }

    /**
     * Builds one audio track's sequence *including the gaps* that place every clip at its absolute
     * timeline position. Gaps are first-class in Media3 (`EditedMediaItemSequence.Builder#addGap`)
     * and render as silence, which is exact — no hand-rolled lead-silence processor that would
     * distort the item's duration model.
     *
     * @return the sequence, or null when the track has no usable clips.
     */
    private fun buildAudioSequence(
        clips: List<Clip>,
        trackVolume: Float,
        settings: ExportSettings,
        warnings: MutableList<String>
    ): EditedMediaItemSequence? {
        val sorted = clips.filter { it.uri.isNotBlank() }.sortedBy { it.startUs }
        if (sorted.isEmpty()) return null
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L
        sorted.forEach { clip ->
            val leadSilence = max(0L, clip.startUs - cursorUs)
            if (leadSilence > 0L) builder.addGap(leadSilence)
            val processors = ArrayList<AudioProcessor>()
            val gain = clip.effectiveVolume * trackVolume
            if (!GainAudioProcessor.isNoOp(gain, clip.fadeInUs, clip.fadeOutUs, 0L)) {
                processors += GainAudioProcessor(
                    leadSilenceUs = 0L,
                    gain = gain.coerceIn(0f, 2f),
                    fadeInUs = clip.fadeInUs,
                    fadeOutUs = clip.fadeOutUs,
                    clipDurationUs = clip.durationUs,
                    nativeEngine = nativeEngine
                )
            }
            val itemBuilder = EditedMediaItem.Builder(mediaItemFor(clip))
                .setEffects(Effects(processors, listOf()))
                .setFrameRate(settings.frameRate.fps)
            if (clip.speed != 1f && clip.speed > 0f) {
                itemBuilder.setSpeed(FixedSpeedProvider(clip.speed))
            }
            builder.addItem(itemBuilder.build())
            cursorUs = clip.endUs
        }
        return builder.build()
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
     *
     * Everything Transformer-related happens on one dedicated [HandlerThread] looper:
     * construction, `start`, the `getProgress` poll and `cancel`. Progress is reported through
     * [onProgress].
     */
    suspend fun export(
        plan: Plan,
        settings: ExportSettings,
        outputFile: File,
        onProgress: (progress: Float, framesPerSecond: Float?) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        suspendCancellableCoroutine { continuation ->
            val thread = HandlerThread("cutvideo-export").apply { start() }
            exportThread = thread
            val handler = Handler(thread.looper)
            exportHandler = handler

            val progressHolder = ProgressHolder()
            var progressTicker: Runnable? = null

            fun shutdownThread() {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    thread.quitSafely()
                }
                if (exportThread === thread) {
                    exportThread = null
                    exportHandler = null
                }
            }

            handler.post {
                if (!continuation.isActive) {
                    shutdownThread()
                    return@post
                }
                try {
                    val transformer = Transformer.Builder(context)
                        .setLooper(thread.looper)
                        .setVideoMimeType(settings.codec.mime)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: androidx.media3.transformer.ExportResult
                            ) {
                                progressTicker?.let { handler.removeCallbacks(it) }
                                shutdownThread()
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
                                progressTicker?.let { handler.removeCallbacks(it) }
                                shutdownThread()
                                Log.e(TAG, "export failed", exportException)
                                runCatching { outputFile.delete() }
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        ExportException(
                                            describeError(exportException),
                                            exportException,
                                            exportException.errorCodeName
                                        )
                                    )
                                }
                            }
                        })
                        .build()
                    lastTransformer = transformer

                    transformer.start(plan.composition, outputFile.absolutePath)

                    // Poll real Transformer progress on the same looper (getProgress also verifies
                    // the application thread, so it must never be called from a random dispatcher).
                    val ticker = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            val state =
                                runCatching { transformer.getProgress(progressHolder) }.getOrNull()
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(progressHolder.progress / 100f, null)
                            }
                            handler.postDelayed(this, 250)
                        }
                    }
                    progressTicker = ticker
                    handler.postDelayed(ticker, 250)
                } catch (t: Throwable) {
                    Log.e(TAG, "could not start export", t)
                    shutdownThread()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ExportException(
                                t.message ?: "Could not start the export on this device",
                                t
                            )
                        )
                    }
                }
            }

            continuation.invokeOnCancellation {
                val activeHandler = exportHandler ?: handler
                activeHandler.post {
                    runCatching { lastTransformer?.cancel() }
                    runCatching { outputFile.delete() }
                }
                shutdownThread()
            }
        }
    }

    /** Cancels a running export. Safe to call from any thread. */
    fun cancel() {
        val handler = exportHandler
        val transformer = lastTransformer
        if (handler != null && transformer != null) {
            handler.post { runCatching { transformer.cancel() } }
        } else {
            runCatching { transformer?.cancel() }
        }
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

    /**
     * Constant speed handed to `EditedMediaItem.Builder#setSpeed`, which adjusts both the video
     * timestamps and the audio so the item lands on the timeline at the duration the document
     * expects. This is the *only* place speed is applied — never alongside a SpeedChangeEffect.
     */
    private class FixedSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private companion object {
        const val TAG = "TransformerExporter"
    }
}
