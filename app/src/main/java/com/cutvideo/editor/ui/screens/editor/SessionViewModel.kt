package com.cutvideo.editor.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.data.repository.AppSettings
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectSpec
import com.cutvideo.editor.domain.model.PipSpec
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.editor.EditorController
import com.cutvideo.editor.media.PcmDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight session view model for the dedicated Text, Audio and Effects screens.
 *
 * It shares the single [EditorController] from the container (so edits from any screen land in the same
 * document as the editor and the export) but never touches the preview players — the editor screen owns
 * those.
 */
class SessionViewModel(private val container: AppContainer) : ViewModel() {

    val editor: EditorController = container.editorController
    val doc: StateFlow<ProjectDoc?> = editor.doc
    val selectedClipId: StateFlow<String?> = editor.selectedClipId
    val playheadUs: StateFlow<Long> = editor.playheadUs
    val thumbnails = container.thumbnailStore
    val nativeEngine = container.nativeEngine

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _waveform = MutableStateFlow<List<Float>>(emptyList())
    val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

    private val _waveformLoading = MutableStateFlow(false)
    val waveformLoading: StateFlow<Boolean> = _waveformLoading.asStateFlow()

    private var openedId: String? = null
    private var waveformUri: String? = null

    fun open(projectId: String) {
        if (openedId == projectId) return
        openedId = projectId
        viewModelScope.launch { editor.open(projectId) { notice -> notify(notice) } }
    }

    fun select(clipId: String?) = editor.select(clipId)

    fun checkpoint(label: String = "Edit") = editor.checkpoint(label)

    fun updateClipLive(clipId: String, block: (Clip) -> Clip) = editor.updateClipLive(clipId, block)

    fun updateClip(clipId: String, label: String = "Edit", block: (Clip) -> Clip) =
        editor.updateClip(clipId, label, block)

    fun updateText(spec: TextSpec) {
        val clip = editor.selectedClip ?: return
        editor.updateClipLive(clip.id) { it.copy(text = spec, name = spec.text.take(24).ifBlank { "Text" }) }
    }

    fun updateTextClip(clipId: String, spec: TextSpec) =
        editor.updateClipLive(clipId) { it.copy(text = spec, name = spec.text.take(24).ifBlank { "Text" }) }

    fun addText(atUs: Long): String? {
        val id = editor.addTextClip("Your text here", atUs)
        if (id == null) notify("Could not add a text clip")
        return id
    }

    fun deleteClip(clipId: String) = editor.deleteClip(clipId)

    fun setVolume(clipId: String, volume: Float, live: Boolean = true) = editor.setVolume(clipId, volume, live)

    fun toggleMute(clipId: String) = editor.toggleMute(clipId)

    fun setFades(clipId: String, fadeInUs: Long, fadeOutUs: Long, live: Boolean = true) =
        editor.setFades(clipId, fadeInUs, fadeOutUs, live)

    fun toggleMuteOriginal() = editor.toggleMuteOriginal()

    val isOriginalMuted: Boolean get() = editor.isOriginalMuted

    fun extractAudio(clipId: String) {
        viewModelScope.launch {
            editor.extractAudio(clipId)
            notify("Audio extracted to a new audio track")
        }
    }

    fun importMedia(uris: List<Uri>, trackType: TrackType, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            editor.importMedia(uris, trackType)
            onDone?.invoke()
        }
    }

    fun setFilter(clipId: String, filterId: String, intensity: Float = 1f) =
        editor.setFilter(clipId, filterId, intensity)

    fun setFilterIntensity(clipId: String, intensity: Float) = editor.setFilterIntensity(clipId, intensity)

    fun toggleEffect(clipId: String, effectId: String, intensity: Float = 0.5f) =
        editor.toggleEffect(clipId, effectId, intensity)

    fun setEffects(clipId: String, effects: List<EffectSpec>) = editor.setEffects(clipId, effects)

    fun setAnimation(clipId: String, inType: ClipAnimationType, outType: ClipAnimationType, durationUs: Long) =
        editor.setAnimation(clipId, inType, outType, durationUs)

    fun applyAnimationToAll(type: ClipAnimationType, isIn: Boolean) =
        editor.applyAnimationToAll(type, isIn)

    fun setPip(clipId: String, spec: PipSpec) =
        editor.updateClipLive(clipId) { it.copy(pip = spec) }

    fun importSubtitles(uri: Uri) {
        viewModelScope.launch {
            val count = editor.importSubtitles(uri)
            notify(if (count > 0) "Imported $count subtitle cues" else "No cues found in that file")
        }
    }

    fun exportSubtitles(uri: Uri) {
        viewModelScope.launch {
            val count = editor.exportSubtitles(uri)
            notify(if (count > 0) "Exported $count cues" else "There are no text clips to export")
        }
    }

    fun saveNow() {
        viewModelScope.launch {
            editor.saveNow(draft = true)
            notify("Project saved")
        }
    }

    /** Decodes the audio of [uri] and computes real peaks for the waveform display. */
    fun loadWaveform(uriString: String, buckets: Int = 320) {
        if (uriString.isBlank() || waveformUri == uriString) return
        waveformUri = uriString
        _waveformLoading.value = true
        viewModelScope.launch {
            val peaks = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val decoded = PcmDecoder.decode(container.appContext, Uri.parse(uriString)) ?: return@withContext null
                nativeEngine.waveform(decoded.samples, decoded.channels, buckets)
                    ?: fallbackPeaks(decoded.samples, decoded.channels, buckets)
            }
            _waveform.value = peaks?.toList() ?: emptyList()
            _waveformLoading.value = false
        }
    }

    private fun fallbackPeaks(samples: ShortArray, channels: Int, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        val frames = if (channels > 0) samples.size / channels else 0
        if (frames == 0 || buckets == 0) return out
        val perBucket = (frames / buckets).coerceAtLeast(1)
        for (b in 0 until buckets) {
            var peak = 0f
            val start = b * perBucket
            var i = start
            val end = minOf(frames, start + perBucket)
            while (i < end) {
                val idx = i * channels
                val v = if (idx < samples.size) kotlin.math.abs(samples[idx].toInt()) / 32768f else 0f
                if (v > peak) peak = v
                i++
            }
            out[b] = peak
        }
        return out
    }

    fun notify(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(2600)
            if (_message.value == text) _message.value = null
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
