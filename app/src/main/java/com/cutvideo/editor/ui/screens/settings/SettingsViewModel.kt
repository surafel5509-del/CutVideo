package com.cutvideo.editor.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.data.repository.AppSettings
import com.cutvideo.editor.data.repository.PreviewQuality
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.export.ExportFrameRate
import com.cutvideo.editor.domain.export.ExportResolution
import com.cutvideo.editor.domain.export.VideoCodec
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.nativebridge.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.settingsRepository

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _exportCount = MutableStateFlow(0)
    val exportCount: StateFlow<Int> = _exportCount.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val nativeAvailable: Boolean = container.nativeEngine.available
    val nativeVersion: String = NativeLib.version()
    val nativeDescription: String = container.nativeEngine.describe()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) { container.thumbnailStore.cacheSizeBytes() }
            _exportCount.value = runCatching {
                container.exportRepository.exports.first().size
            }.getOrDefault(0)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.thumbnailStore.clear()
                java.io.File(container.appContext.cacheDir, "voice").deleteRecursively()
                java.io.File(container.appContext.cacheDir, "exports").listFiles()?.forEach { it.delete() }
            }
            _cacheBytes.value = 0L
            notify("Cache cleared")
        }
    }

    fun setPreviewQuality(value: PreviewQuality) = update { repository.setPreviewQuality(value); "Preview quality: ${value.label}" }

    fun setDefaultAspect(value: AspectRatio) = update { repository.setDefaultAspect(value); "Default aspect: ${value.label}" }

    fun setExportResolution(value: ExportResolution) =
        update { repository.setExportResolution(value); "Default export: ${value.label}" }

    fun setExportFrameRate(value: ExportFrameRate) =
        update { repository.setExportFrameRate(value); "Default frame rate: ${value.label}" }

    fun setExportCodec(value: VideoCodec) = update { repository.setExportCodec(value); "Codec: ${value.label}" }

    fun setSnapping(value: Boolean) = update { repository.setSnapping(value); "Snapping ${if (value) "on" else "off"}" }

    fun setHaptics(value: Boolean) = update { repository.setHaptics(value); "Haptics ${if (value) "on" else "off"}" }

    fun setWaveforms(value: Boolean) = update { repository.setWaveforms(value); "Waveforms ${if (value) "on" else "off"}" }

    fun setHardwareEffects(value: Boolean) =
        update { repository.setHardwareEffects(value); "GPU effects ${if (value) "on" else "off"}" }

    fun setAutosaveSeconds(value: Int) = update { repository.setAutosaveSeconds(value); "Autosave every ${value}s" }

    fun notify(text: String) {
        _message.value = text
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun update(block: suspend () -> String) {
        viewModelScope.launch {
            _message.value = runCatching { block() }.getOrElse { "Could not save that setting: ${it.message}" }
        }
    }
}
