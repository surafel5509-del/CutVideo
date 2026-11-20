package com.cutvideo.editor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cutvideo.editor.domain.export.ExportFrameRate
import com.cutvideo.editor.domain.export.ExportResolution
import com.cutvideo.editor.domain.export.ExportSettings
import com.cutvideo.editor.domain.export.VideoCodec
import com.cutvideo.editor.domain.model.AspectRatio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "cutvideo_settings")

enum class PreviewQuality(val label: String) { AUTO("Auto"), LOW("Data saver"), HIGH("High quality") }

data class AppSettings(
    val previewQuality: PreviewQuality = PreviewQuality.AUTO,
    val defaultAspect: AspectRatio = AspectRatio.RATIO_16_9,
    val exportResolution: ExportResolution = ExportResolution.P1080,
    val exportFrameRate: ExportFrameRate = ExportFrameRate.FPS30,
    val exportCodec: VideoCodec = VideoCodec.H264,
    val autosaveSeconds: Int = 8,
    val snappingEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val showWaveforms: Boolean = true,
    val hardwareEffects: Boolean = true
) {
    fun toExportSettings() = ExportSettings(
        resolution = exportResolution,
        frameRate = exportFrameRate,
        codec = exportCodec
    )
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val previewQuality = stringPreferencesKey("preview_quality")
        val defaultAspect = stringPreferencesKey("default_aspect")
        val exportResolution = stringPreferencesKey("export_resolution")
        val exportFrameRate = intPreferencesKey("export_frame_rate")
        val exportCodec = stringPreferencesKey("export_codec")
        val autosave = intPreferencesKey("autosave_seconds")
        val snapping = booleanPreferencesKey("snapping")
        val haptics = booleanPreferencesKey("haptics")
        val waveforms = booleanPreferencesKey("waveforms")
        val hardwareEffects = booleanPreferencesKey("hardware_effects")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            previewQuality = p[Keys.previewQuality]?.let { runCatching { PreviewQuality.valueOf(it) }.getOrNull() }
                ?: PreviewQuality.AUTO,
            defaultAspect = p[Keys.defaultAspect]?.let { runCatching { AspectRatio.valueOf(it) }.getOrNull() }
                ?: AspectRatio.RATIO_16_9,
            exportResolution = p[Keys.exportResolution]
                ?.let { runCatching { ExportResolution.valueOf(it) }.getOrNull() } ?: ExportResolution.P1080,
            exportFrameRate = p[Keys.exportFrameRate]
                ?.let { fps -> ExportFrameRate.entries.firstOrNull { it.fps == fps } } ?: ExportFrameRate.FPS30,
            exportCodec = p[Keys.exportCodec]?.let { runCatching { VideoCodec.valueOf(it) }.getOrNull() }
                ?: VideoCodec.H264,
            autosaveSeconds = p[Keys.autosave] ?: 8,
            snappingEnabled = p[Keys.snapping] ?: true,
            hapticsEnabled = p[Keys.haptics] ?: true,
            showWaveforms = p[Keys.waveforms] ?: true,
            hardwareEffects = p[Keys.hardwareEffects] ?: true
        )
    }

    suspend fun setPreviewQuality(value: PreviewQuality) = put(Keys.previewQuality, value.name)
    suspend fun setDefaultAspect(value: AspectRatio) = put(Keys.defaultAspect, value.name)
    suspend fun setExportResolution(value: ExportResolution) = put(Keys.exportResolution, value.name)
    suspend fun setExportCodec(value: VideoCodec) = put(Keys.exportCodec, value.name)
    suspend fun setSnapping(value: Boolean) = put(Keys.snapping, value)
    suspend fun setHaptics(value: Boolean) = put(Keys.haptics, value)
    suspend fun setWaveforms(value: Boolean) = put(Keys.waveforms, value)
    suspend fun setHardwareEffects(value: Boolean) = put(Keys.hardwareEffects, value)

    suspend fun setExportFrameRate(value: ExportFrameRate) {
        context.settingsStore.edit { it[Keys.exportFrameRate] = value.fps }
    }

    suspend fun setAutosaveSeconds(value: Int) {
        context.settingsStore.edit { it[Keys.autosave] = value.coerceIn(2, 120) }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }
}
