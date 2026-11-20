package com.cutvideo.editor.export

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.cutvideo.editor.data.db.ExportEntity
import com.cutvideo.editor.data.repository.ExportRepository
import com.cutvideo.editor.domain.export.ExportSettings
import com.cutvideo.editor.domain.export.ExportState
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.media.ThumbnailStore
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Owns the export lifecycle: pre-flight validation, Transformer run, gallery publish, DB record and
 * cancellation. Lives in the application container so an export survives leaving the export screen
 * (the foreground [ExportService] keeps the process alive while it runs).
 */
@UnstableApi
class ExportController(
    private val context: Context,
    private val nativeEngine: NativeEngine,
    private val exportRepository: ExportRepository,
    private val thumbnailStore: ThumbnailStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private var job: Job? = null
    private var exporter: TransformerExporter? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Non-fatal issues the user should see before starting (returns empty when all good). */
    fun preflight(doc: ProjectDoc, settings: ExportSettings): List<String> {
        val notes = ArrayList<String>()
        try {
            val plan = TransformerExporter(context, nativeEngine, scope).buildPlan(doc, settings)
            notes += plan.warnings
            val freeBytes = context.cacheDir.usableSpace
            val estimatedBytes = (plan.estimatedDurationUs / 1_000_000.0) * settings.bitrate(doc.aspect) / 8.0
            if (estimatedBytes > freeBytes * 0.9) {
                notes += "Not enough free storage for this export (${(estimatedBytes / 1_048_576).toInt()} MB needed)"
            }
            if (settings.resolution.shortSide >= 1440) {
                notes += "1440p/4K exports are slow on most phones and may not be supported by this encoder"
            }
        } catch (e: ExportException) {
            notes += e.message ?: "This project cannot be exported yet"
        }
        return notes
    }

    fun start(doc: ProjectDoc, settings: ExportSettings) {
        if (isRunning) return
        job = scope.launch { run(doc, settings) }
    }

    private suspend fun run(doc: ProjectDoc, settings: ExportSettings) {
        ExportService.start(context)
        val runExporter = TransformerExporter(context, nativeEngine, scope)
        exporter = runExporter
        var outputFile: File? = null
        try {
            _state.value = ExportState.Preparing("Preparing the timeline…")
            val plan = withContext(Dispatchers.Default) { runExporter.buildPlan(doc, settings) }
            _warnings.value = plan.warnings

            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "cutvideo_${System.currentTimeMillis()}.mp4")
            outputFile = file

            val startedAt = System.currentTimeMillis()
            _state.value = ExportState.Running(0f, null, null, "Rendering with Media3 Transformer")

            val result = runExporter.export(plan, settings, file) { progress, fps ->
                val elapsed = System.currentTimeMillis() - startedAt
                val etaMs = if (progress > 0.02f) {
                    max(0L, (elapsed * (1.0 - progress) / progress).toLong())
                } else {
                    null
                }
                _state.value = ExportState.Running(
                    progress = progress.coerceIn(0f, 1f),
                    frameRate = fps,
                    estimatedRemainingMs = etaMs,
                    stage = "Rendering ${(progress * 100).toInt()}%"
                )
            }

            _state.value = ExportState.Running(0.99f, null, null, "Saving to the gallery")
            val saved = MediaStoreSaver.publish(context, result.outputFile, doc.name)
            if (saved == null) {
                _state.value = ExportState.Failed(
                    "The video was rendered but could not be saved to the gallery. " +
                        "Check that CutVideo has storage access."
                )
                return
            }

            val id = UUID.randomUUID().toString()
            exportRepository.add(
                ExportEntity(
                    id = id,
                    projectId = doc.id,
                    projectName = doc.name,
                    filePath = saved.displayPath,
                    contentUri = saved.uri.toString(),
                    thumbnailPath = null,
                    width = plan.outputWidth,
                    height = plan.outputHeight,
                    frameRate = settings.frameRate.fps,
                    codec = settings.codec.label,
                    sizeBytes = saved.sizeBytes,
                    durationUs = result.durationUs,
                    createdAt = System.currentTimeMillis(),
                    status = "completed"
                )
            )
            _state.value = ExportState.Success(saved.displayPath, saved.uri.toString(), saved.sizeBytes)
        } catch (cancel: CancellationException) {
            _state.value = ExportState.Cancelled
            outputFile?.delete()
        } catch (e: ExportException) {
            Log.e(TAG, "export failed", e)
            _state.value = ExportState.Failed(e.message ?: "Export failed", e)
            outputFile?.delete()
        } catch (t: Throwable) {
            Log.e(TAG, "unexpected export failure", t)
            _state.value = ExportState.Failed(t.message ?: "Unexpected export error", t)
            outputFile?.delete()
        } finally {
            exporter = null
            ExportService.stop(context)
        }
    }

    fun cancel() {
        exporter?.cancel()
        job?.cancel()
        _state.value = ExportState.Cancelled
    }

    fun reset() {
        if (!isRunning) _state.value = ExportState.Idle
    }

    fun deleteExport(id: String, contentUri: String?) {
        scope.launch {
            contentUri?.let { runCatching { MediaStoreSaver.delete(context, android.net.Uri.parse(it)) } }
            exportRepository.delete(id)
            thumbnailStore.cacheSizeBytes()
        }
    }

    private companion object {
        const val TAG = "ExportController"
    }
}
