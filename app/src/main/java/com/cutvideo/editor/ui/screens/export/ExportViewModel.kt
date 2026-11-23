package com.cutvideo.editor.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.data.db.ExportEntity
import com.cutvideo.editor.data.repository.AppSettings
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.export.ExportFrameRate
import com.cutvideo.editor.domain.export.ExportResolution
import com.cutvideo.editor.domain.export.ExportSettings
import com.cutvideo.editor.domain.export.ExportState
import com.cutvideo.editor.domain.export.VideoCodec
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.editor.EditorController
import com.cutvideo.editor.export.ExportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExportViewModel(private val container: AppContainer) : ViewModel() {

    val editor: EditorController = container.editorController
    val export: ExportController = container.exportController

    val doc: StateFlow<ProjectDoc?> = editor.doc
    val state: StateFlow<ExportState> = export.state
    val warnings: StateFlow<List<String>> = export.warnings

    val savedExports: StateFlow<List<ExportEntity>> = container.exportRepository.exports
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _resolution = MutableStateFlow(ExportResolution.P1080)
    val resolution: StateFlow<ExportResolution> = _resolution.asStateFlow()

    private val _frameRate = MutableStateFlow(ExportFrameRate.FPS30)
    val frameRate: StateFlow<ExportFrameRate> = _frameRate.asStateFlow()

    private val _codec = MutableStateFlow(VideoCodec.H264)
    val codec: StateFlow<VideoCodec> = _codec.asStateFlow()

    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    private var seeded = false
    private var openedId: String? = null

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun seedFrom(settings: AppSettings) {
        if (seeded) return
        seeded = true
        _resolution.value = settings.exportResolution
        _frameRate.value = settings.exportFrameRate
        _codec.value = settings.exportCodec
    }

    fun open(projectId: String) {
        if (openedId == projectId) return
        openedId = projectId
        viewModelScope.launch { editor.open(projectId) }
    }

    fun setResolution(value: ExportResolution) {
        _resolution.value = value
        refreshNotes()
    }

    fun setFrameRate(value: ExportFrameRate) {
        _frameRate.value = value
        refreshNotes()
    }

    fun setCodec(value: VideoCodec) {
        _codec.value = value
        refreshNotes()
    }

    fun currentSettings(): ExportSettings = ExportSettings(
        resolution = _resolution.value,
        frameRate = _frameRate.value,
        codec = _codec.value
    )

    fun refreshNotes() {
        val document = doc.value ?: return
        _notes.value = runCatching { export.preflight(document, currentSettings()) }
            .getOrElse { listOf(it.message ?: "This project cannot be exported yet") }
    }

    fun start() {
        val document = doc.value ?: return
        export.start(document, currentSettings())
    }

    fun cancel() = export.cancel()

    fun reset() {
        export.reset()
        _notes.value = emptyList()
    }

    /** Estimated output size in bytes for the current selection. */
    fun estimatedBytes(): Long {
        val document = doc.value ?: return 0L
        val settings = currentSettings()
        return (document.durationUs / 1_000_000.0 * settings.bitrate(document.aspect) / 8.0).toLong()
    }

    fun estimatedSizeLabel(): String = com.cutvideo.editor.core.TimeText.bytes(estimatedBytes())

    fun deleteExport(entity: ExportEntity) {
        viewModelScope.launch { container.exportController.deleteExport(entity.id, entity.contentUri) }
    }
}
