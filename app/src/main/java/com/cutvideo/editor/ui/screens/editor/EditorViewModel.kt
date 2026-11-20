package com.cutvideo.editor.ui.screens.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectSpec
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.model.TransitionSpec
import com.cutvideo.editor.domain.model.TransitionType
import com.cutvideo.editor.editor.EditorController
import com.cutvideo.editor.editor.PreviewController
import com.cutvideo.editor.editor.PreviewState
import com.cutvideo.editor.ui.components.TimelineZoom
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Tools available in the editor's bottom panel. */
enum class EditorTool(val label: String) {
    NONE("Tools"),
    TRIM("Trim"),
    SPLIT("Split"),
    SPEED("Speed"),
    ADJUST("Adjust"),
    FILTER("Filters"),
    EFFECTS("Effects"),
    ANIMATION("Animation"),
    TRANSITION("Transition"),
    CROP("Crop"),
    TEXT("Text"),
    AUDIO("Audio"),
    CHROMA("Chroma"),
    PIP("PiP"),
    CAPTIONS("Captions")
}

class EditorViewModel(private val container: AppContainer) : ViewModel() {

    val editor: EditorController = container.editorController
    val preview: PreviewController = container.previewController

    val doc: StateFlow<ProjectDoc?> = editor.doc
    val selectedClipId: StateFlow<String?> = editor.selectedClipId
    val playheadUs: StateFlow<Long> = editor.playheadUs
    val previewState: StateFlow<PreviewState> = preview.state
    val status: StateFlow<EditorController.EditorStatus> = editor.status

    private val _tool = MutableStateFlow(EditorTool.NONE)
    val tool: StateFlow<EditorTool> = _tool.asStateFlow()

    private val _zoom = MutableStateFlow(TimelineZoom.Default)
    val zoom: StateFlow<TimelineZoom> = _zoom.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _snapping = MutableStateFlow(true)
    val snapping: StateFlow<Boolean> = _snapping.asStateFlow()

    private val _filterSource = MutableStateFlow<Bitmap?>(null)
    val filterSource: StateFlow<Bitmap?> = _filterSource.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var openedId: String? = null
    private var structureSignature: String = ""
    private var filterSourceJob: Job? = null

    init {
        viewModelScope.launch {
            editor.doc.filterNotNull().collect { document ->
                val signature = structuralSignature(document)
                if (signature != structureSignature) {
                    structureSignature = signature
                    preview.setDocument(document, editor.playheadUs.value)
                } else {
                    document.clip(editor.selectedClipId.value ?: "")?.let { preview.refreshLook(it) }
                }
            }
        }
        viewModelScope.launch {
            preview.state.collect { state ->
                if (state.isPlaying) editor.setPlayhead(state.positionUs)
                state.error?.let { _message.value = "Preview error: $it" }
            }
        }
        viewModelScope.launch {
            editor.selectedClipId.collect { loadFilterSource() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    fun open(projectId: String) {
        if (openedId == projectId) return
        openedId = projectId
        viewModelScope.launch {
            editor.open(projectId) { notice -> _message.value = notice }
            structureSignature = structuralSignature(editor.doc.value ?: return@launch)
            loadFilterSource()
        }
    }

    fun createProject(name: String, aspect: com.cutvideo.editor.domain.model.AspectRatio, fps: Int) {
        val created = editor.createProject(name, aspect, fps)
        openedId = created.id
        structureSignature = structuralSignature(created)
        preview.setDocument(created, 0L)
    }

    fun onClearedInternal() {
        editor.close()
    }

    override fun onCleared() {
        super.onCleared()
        editor.close()
    }

    // ---------------------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------------------

    fun togglePlay() {
        preview.togglePlayPause()
    }

    fun seekTo(us: Long) {
        editor.setPlayhead(us)
        preview.seekTo(us)
    }

    fun stepFrame(frames: Int) {
        preview.pause()
        val fps = doc.value?.frameRate ?: 30
        seekTo(playheadUs.value + frames * (1_000_000L / fps))
    }

    fun toggleMuteOriginal() {
        val muted = !preview.isMuteOriginal
        preview.setMuteOriginal(muted)
        editor.toggleMuteOriginal()
    }

    fun toggleFullscreen() {
        _fullscreen.value = !_fullscreen.value
    }

    fun selectTool(tool: EditorTool) {
        _tool.value = if (_tool.value == tool) EditorTool.NONE else tool
        if (_tool.value == EditorTool.FILTER || _tool.value == EditorTool.EFFECTS) loadFilterSource()
    }

    fun closeTool() {
        _tool.value = EditorTool.NONE
    }

    fun setZoom(factor: Float) {
        _zoom.value = _zoom.value.zoomBy(factor)
    }

    fun toggleSnapping() {
        _snapping.value = !_snapping.value
    }

    fun dismissMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------------------------------------
    // Editing intents
    // ---------------------------------------------------------------------------------------------

    private fun withSelectedClip(block: (Clip) -> Unit) {
        val clip = editor.selectedClip ?: run {
            _message.value = "Select a clip on the timeline first"
            return
        }
        block(clip)
    }

    fun split() = editor.splitAtPlayhead()

    fun deleteSelected() = editor.deleteSelected()

    fun duplicateSelected() = editor.duplicateSelected()

    fun undo() = editor.undo()

    fun redo() = editor.redo()

    fun selectClip(id: String?) = editor.select(id)

    fun moveClip(clipId: String, startUs: Long, trackId: String?, live: Boolean) =
        editor.moveClip(clipId, startUs, trackId, live)

    fun trimStart(clipId: String, us: Long, live: Boolean) = editor.trimStart(clipId, us, live)

    fun trimEnd(clipId: String, us: Long, live: Boolean) = editor.trimEnd(clipId, us, live)

    fun gestureCheckpoint() = editor.checkpoint("Timeline edit")

    fun setSpeed(speed: Float) = withSelectedClip { editor.setSpeed(it.id, speed) }

    fun setFilter(filterId: String) = withSelectedClip { editor.setFilter(it.id, filterId) }

    fun setFilterIntensity(value: Float) {
        withSelectedClip { editor.setFilterIntensity(it.id, value) }
    }

    fun toggleEffect(effectId: String, intensity: Float) =
        withSelectedClip { editor.toggleEffect(it.id, effectId, intensity) }

    fun setEffectIntensity(effectId: String, intensity: Float) = withSelectedClip { clip ->
        editor.setEffects(
            clip.id,
            clip.effects.map { if (it.id == effectId) it.copy(intensity = intensity) else it }
        )
    }

    fun updateAdjustments(
        live: Boolean = true,
        block: (Clip) -> Clip
    ) = withSelectedClip { clip ->
        if (live) editor.updateClipLive(clip.id, block) else editor.updateClip(clip.id, "Adjust", block)
    }

    fun setCrop(left: Float, top: Float, right: Float, bottom: Float) = withSelectedClip {
        editor.setCrop(it.id, left, top, right, bottom)
    }

    fun rotate(degrees: Float) = withSelectedClip { clip ->
        editor.setRotation(clip.id, (clip.transform.rotationDegrees + degrees) % 360f)
    }

    fun setScale(scale: Float) = withSelectedClip { editor.setScale(it.id, scale) }

    fun setVolume(volume: Float, live: Boolean = true) = withSelectedClip {
        editor.setVolume(it.id, volume, live)
    }

    fun toggleClipMute() = withSelectedClip { editor.toggleMute(it.id) }

    fun setFades(fadeInUs: Long, fadeOutUs: Long, live: Boolean = true) = withSelectedClip {
        editor.setFades(it.id, fadeInUs, fadeOutUs, live)
    }

    fun setChroma(enabled: Boolean) = withSelectedClip { editor.setChromaEnabled(it.id, enabled) }

    fun setChromaParams(keyColor: Int, similarity: Float, smoothness: Float, spill: Float) =
        withSelectedClip { clip ->
            editor.updateClipLive(clip.id) {
                it.copy(
                    chroma = it.chroma.copy(
                        keyColor = keyColor,
                        similarity = similarity,
                        smoothness = smoothness,
                        spill = spill
                    )
                )
            }
        }

    fun setPip(enabled: Boolean) = withSelectedClip { editor.setPip(it.id, enabled) }

    fun setPipParams(scale: Float, x: Float, y: Float, radius: Float, border: Float) =
        withSelectedClip { clip ->
            editor.updateClipLive(clip.id) {
                it.copy(
                    pip = it.pip.copy(
                        scale = scale,
                        offsetXFrac = x,
                        offsetYFrac = y,
                        cornerRadiusDp = radius,
                        borderWidthDp = border
                    )
                )
            }
        }

    fun setAnimation(inType: ClipAnimationType, outType: ClipAnimationType, durationUs: Long) =
        withSelectedClip { editor.setAnimation(it.id, inType, outType, durationUs) }

    fun applyAnimationToAll(type: ClipAnimationType, isIn: Boolean) =
        editor.applyAnimationToAll(type, isIn)

    fun setTransition(type: TransitionType, durationUs: Long) = withSelectedClip { clip ->
        val document = doc.value ?: return@withSelectedClip
        val videoClips = document.videoClips().sortedBy { it.startUs }
        val index = videoClips.indexOfFirst { it.id == clip.id }
        val next = videoClips.getOrNull(index + 1)
        if (next == null) {
            _message.value = "Add a clip after this one to place a transition"
            return@withSelectedClip
        }
        editor.setTransition(clip.id, next.id, TransitionSpec(type, durationUs))
    }

    fun clearTransition() = withSelectedClip { clip ->
        val document = doc.value ?: return@withSelectedClip
        val videoClips = document.videoClips().sortedBy { it.startUs }
        val index = videoClips.indexOfFirst { it.id == clip.id }
        val next = videoClips.getOrNull(index + 1) ?: return@withSelectedClip
        editor.setTransition(clip.id, next.id, null)
    }

    fun freezeFrame() {
        val clip = editor.selectedClip ?: run {
            _message.value = "Select a clip first"
            return
        }
        val id = editor.freezeFrame(clip.id)
        _message.value = if (id != null) "Freeze frame added at the playhead" else "Could not freeze this clip"
    }

    fun reverseSelected() {
        _message.value = "Reverse needs full random-access decoding and is not available in this build"
    }

    fun stabilizeSelected() {
        _message.value = "Stabilisation depends on device gyro + encoder support; not available in this build"
    }

    fun addText() {
        val position = playheadUs.value
        val id = editor.addTextClip("Your text here", position)
        if (id != null) {
            _tool.value = EditorTool.TEXT
            _message.value = "Text added — edit it in the Text panel"
        }
    }

    fun updateSelectedText(spec: TextSpec) = withSelectedClip { clip ->
        editor.updateClipLive(clip.id) { it.copy(text = spec, name = spec.text.take(24)) }
    }

    fun extractAudio() = withSelectedClip { clip ->
        viewModelScope.launch { editor.extractAudio(clip.id) }
    }

    fun importMedia(uris: List<Uri>, trackType: TrackType) {
        viewModelScope.launch { editor.importMedia(uris, trackType) }
    }

    fun importSubtitles(uri: Uri) {
        viewModelScope.launch {
            val count = editor.importSubtitles(uri)
            _message.value = if (count > 0) "Imported $count subtitle cues" else "No subtitles found in that file"
        }
    }

    fun exportSubtitles(uri: Uri) {
        viewModelScope.launch {
            val count = editor.exportSubtitles(uri)
            _message.value = if (count > 0) "Exported $count cues to the selected file" else "There are no text clips to export"
        }
    }

    fun addTrack(type: TrackType) = editor.addTrack(type)

    fun renameProject(name: String) = editor.rename(name)

    fun setAspect(aspect: com.cutvideo.editor.domain.model.AspectRatio) = editor.setAspect(aspect)

    fun setFrameRate(fps: Int) = editor.setFrameRate(fps)

    fun deleteProject(onDone: () -> Unit) {
        viewModelScope.launch {
            val id = doc.value?.id ?: return@launch
            container.projectRepository.delete(id)
            onDone()
        }
    }

    fun saveNow() {
        viewModelScope.launch {
            editor.saveNow(draft = true)
            _message.value = "Project saved"
        }
    }

    /** Stable signature of everything that requires rebuilding the preview playlist. */
    private fun structuralSignature(document: ProjectDoc): String = buildString {
        append(document.id).append('|').append(document.aspect).append('|')
        document.tracks.forEach { track ->
            append(track.id).append(':').append(track.muted).append(';')
            track.sortedClips.forEach { clip ->
                append(clip.id).append(',').append(clip.startUs).append(',').append(clip.durationUs)
                    .append(',').append(clip.sourceInUs).append(',').append(clip.speed)
                    .append(',').append(clip.uri.hashCode()).append('|')
            }
        }
    }

    /** Decodes a frame of the selected clip so filter/effect cards can render live previews. */
    fun loadFilterSource() {
        filterSourceJob?.cancel()
        val clip = editor.selectedClip ?: doc.value?.videoClips()?.firstOrNull() ?: return
        if (clip.uri.isBlank()) return
        filterSourceJob = viewModelScope.launch {
            val bitmap = container.thumbnailStore.frame(clip.uri, clip.sourceInUs, 320)
            _filterSource.value = bitmap
        }
    }

    /** Used by the clip context menu. */
    fun clipEffects(clip: Clip): List<EffectSpec> = clip.effects

    fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(2600)
            if (_message.value == text) _message.value = null
        }
    }
}
