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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    CAPTIONS("Captions"),
    MASK("Mask"),
    KEYFRAME("Keyframes"),
    GRAPH("Graph"),
    CUTOUT("Cutout")
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

    // --- pro tools ---------------------------------------------------------------------------

    fun copyStyle() = withSelectedClip { clip ->
        _message.value = if (editor.copyStyle(clip.id)) "Style copied — select another clip to paste" else "Nothing to copy"
    }

    fun pasteStyle() = withSelectedClip { clip ->
        _message.value = if (editor.pasteStyle(clip.id)) "Style pasted" else "Copy a style first"
    }

    fun applyStyleToAll() = withSelectedClip { clip ->
        val count = editor.applyStyleToAll(clip.id)
        _message.value = if (count > 0) "Look applied to $count clip(s)" else "No other clips to update"
    }

    fun autoEnhance() = withSelectedClip { clip ->
        viewModelScope.launch {
            if (editor.autoEnhance(clip.id)) _message.value = "Auto enhance applied"
        }
    }

    fun moveToOverlay() = withSelectedClip { clip ->
        _message.value = if (editor.moveToOverlay(clip.id)) {
            "Clip sent to the overlay track"
        } else {
            "Only video clips on the main track can be sent to overlay"
        }
    }

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

    // --- keyframes, masks, revert (CapCut-style tools) --------------------------------------

    fun addKeyframe(property: com.cutvideo.editor.domain.model.KeyframeProperty) =
        withSelectedClip { editor.addKeyframe(it.id, property, clipLocalPlayhead(it)) }

    /** Adds a keyframe at an explicit clip-local time (used by the graph editor's ramp presets). */
    fun addKeyframeAt(property: com.cutvideo.editor.domain.model.KeyframeProperty, timeUs: Long) =
        withSelectedClip { editor.addKeyframe(it.id, property, timeUs.coerceIn(0L, it.durationUs)) }

    fun deleteKeyframe(property: com.cutvideo.editor.domain.model.KeyframeProperty, timeUs: Long) =
        withSelectedClip { editor.deleteKeyframe(it.id, property, timeUs) }

    fun moveKeyframe(
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        fromUs: Long,
        toUs: Long,
        newValue: Float? = null
    ) = withSelectedClip { editor.moveKeyframe(it.id, property, fromUs, toUs, newValue) }

    fun setKeyframeInterpolation(
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        timeUs: Long,
        interpolation: com.cutvideo.editor.domain.model.KeyframeInterpolation
    ) = withSelectedClip { editor.setKeyframeInterpolation(it.id, property, timeUs, interpolation) }

    fun setKeyframeHandles(
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        timeUs: Long,
        inX: Float,
        inY: Float,
        outX: Float,
        outY: Float
    ) = withSelectedClip { editor.setKeyframeHandles(it.id, property, timeUs, inX, inY, outX, outY) }

    fun clearKeyframes(property: com.cutvideo.editor.domain.model.KeyframeProperty? = null) =
        withSelectedClip { editor.clearKeyframes(it.id, property) }

    // --- effect chain (ordered stack, per-effect parameters) --------------------------------------

    fun addEffect(effectId: String) = withSelectedClip { editor.addEffect(it.id, effectId) }

    fun removeEffect(instanceId: String) = withSelectedClip { editor.removeEffect(it.id, instanceId) }

    fun moveEffect(instanceId: String, delta: Int) = withSelectedClip { editor.moveEffect(it.id, instanceId, delta) }

    fun duplicateEffect(instanceId: String) = withSelectedClip { editor.duplicateEffect(it.id, instanceId) }

    fun toggleEffectEnabled(instanceId: String) = withSelectedClip { editor.toggleEffectEnabled(it.id, instanceId) }

    fun setEffectParam(instanceId: String, key: String, value: Float, live: Boolean = true) =
        withSelectedClip { editor.setEffectParam(it.id, instanceId, key, value, live) }

    fun setEffectIntensityFor(instanceId: String, value: Float, live: Boolean = true) =
        withSelectedClip { editor.setEffectIntensityById(it.id, instanceId, value, live) }

    fun setEffectBlend(instanceId: String, blend: com.cutvideo.editor.domain.model.BlendMode) =
        withSelectedClip { editor.setEffectBlend(it.id, instanceId, blend) }

    /** Copies an imported `.cube` file into app storage and binds it to a `cube_lut` effect. */
    fun importEffectLut(instanceId: String, uri: Uri) {
        val clipId = editor.selectedClipId.value ?: return
        viewModelScope.launch {
            val ok: String? = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(container.appContext.filesDir, "luts").apply { mkdirs() }
                    val out = java.io.File(dir, "lut-${System.currentTimeMillis()}.cube")
                    val stream = container.appContext.contentResolver.openInputStream(uri)
                        ?: return@runCatching null
                    stream.use { input -> out.outputStream().use { input.copyTo(it) } }
                    // Validate before binding so a bad file never reaches the shader.
                    com.cutvideo.editor.editor.effects.CubeLuts.parse(out.readText())
                    out.absolutePath
                }.getOrNull()
            }
            if (ok != null) {
                editor.setEffectLutPath(clipId, instanceId, ok)
                _message.value = "LUT applied"
            } else {
                _message.value = "Could not read that .cube LUT"
            }
        }
    }

    fun applyEffectStack(stackId: String) = withSelectedClip { editor.applyEffectStack(it.id, stackId) }

    fun clearEffects() = withSelectedClip { editor.clearEffects(it.id) }

    /** [param] empty targets the effect's intensity; otherwise that parameter. */
    fun addEffectParamKeyframe(instanceId: String, param: String) =
        withSelectedClip { editor.addEffectKeyframe(it.id, instanceId, param, clipLocalPlayhead(it)) }

    fun deleteEffectParamKeyframe(instanceId: String, param: String) =
        withSelectedClip { editor.deleteEffectKeyframe(it.id, instanceId, param, clipLocalPlayhead(it)) }

    /**
     * Motion tracking: samples analysis frames of the selected clip, tracks the central ROI with
     * [com.cutvideo.editor.editor.tracking.MotionTracker], and writes the result as
     * POSITION_X/Y keyframes. Real analysis on real frames — slow-ish on long clips, so it runs
     * off the main thread with a capped frame budget.
     */
    fun trackMotion() {
        val clip = editor.selectedClip ?: doc.value?.videoClips()?.firstOrNull()
        if (clip == null || clip.uri.isBlank()) {
            _message.value = "Select a video clip to track"
            return
        }
        _message.value = "Tracking motion…"
        viewModelScope.launch {
            val keys = withContext(Dispatchers.Default) {
                runCatching {
                    val durationUs = clip.durationUs.coerceAtLeast(1L)
                    val frameCount = 96
                    val stepUs = (durationUs / frameCount).coerceIn(60_000L, 1_000_000L)
                    val frames = ArrayList<com.cutvideo.editor.editor.tracking.TrackFrame>()
                    var t = 0L
                    while (t < durationUs && frames.size < frameCount) {
                        val sourceUs = clip.sourceInUs + (t * clip.speed.coerceAtLeast(0.05f)).toLong()
                        val bitmap = container.thumbnailStore.frame(clip.uri, sourceUs, 160) ?: break
                        val w = 96
                        val h = (bitmap.height * w / bitmap.width).coerceAtLeast(2)
                        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                        val pixels = IntArray(w * h)
                        small.getPixels(pixels, 0, w, 0, 0, w, h)
                        val luma = FloatArray(w * h) { i ->
                            val p = pixels[i]
                            (((p shr 16) and 0xFF) * 0.2126f + ((p shr 8) and 0xFF) * 0.7152f +
                                (p and 0xFF) * 0.0722f) / 255f
                        }
                        frames += com.cutvideo.editor.editor.tracking.TrackFrame(t, luma, w, h)
                        t += stepUs
                    }
                    com.cutvideo.editor.editor.tracking.MotionTracker.track(
                        frames, roiXFrac = 0.3f, roiYFrac = 0.3f, roiWFrac = 0.4f, roiHFrac = 0.4f
                    ).filter { it.confidence > 0.08f }
                }.getOrElse { emptyList() }
            }
            if (keys.size < 2) {
                _message.value = "Motion tracking found no reliable motion"
            } else {
                editor.applyMotionTrack(clip.id, keys)
                _message.value = "Tracked ${keys.size} points — position keyframes added"
            }
        }
    }

    /** Playhead relative to the selected clip's start (keyframe times are clip-local). */
    fun clipLocalPlayhead(clip: Clip): Long =
        (playheadUs.value - clip.startUs).coerceIn(0L, clip.durationUs)

    fun addMask(type: com.cutvideo.editor.domain.model.MaskType) = withSelectedClip { clip ->
        val id = editor.addMask(clip.id, type)
        _message.value = if (id != null) {
            "${type.label} mask added"
        } else {
            "Two masks is the render limit — delete one to add another"
        }
    }

    fun updateMask(
        maskId: String,
        live: Boolean = false,
        block: (com.cutvideo.editor.domain.model.MaskSpec) -> com.cutvideo.editor.domain.model.MaskSpec
    ) = withSelectedClip { editor.updateMask(it.id, maskId, live, block) }

    fun deleteMask(maskId: String) = withSelectedClip { editor.deleteMask(it.id, maskId) }

    fun addMaskStroke(stroke: List<com.cutvideo.editor.domain.model.MaskPoint>) =
        withSelectedClip { editor.addMaskStroke(it.id, stroke) }

    fun revertProperty(group: com.cutvideo.editor.domain.model.RevertGroup) =
        withSelectedClip { editor.revertProperty(it.id, group) }

    fun revertClip() = withSelectedClip { editor.revertClip(it.id) }

    fun hasRevertSnapshot(clip: Clip): Boolean = clip.original != null

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

    /** Adds a photo / transparent PNG as a real overlay layer on the timeline. */
    fun importImageLayer(uri: Uri) {
        val projectId = doc.value?.id ?: run {
            showMessage("Open a project first")
            return
        }
        viewModelScope.launch {
            val imported = container.mediaImporter.importImage(
                uri = uri,
                projectId = projectId,
                insertAtUs = playheadUs.value
            )
            if (imported == null) {
                showMessage("Could not read that image")
            } else {
                editor.addOverlayTrackClip(imported.clip)
                showMessage("Image added as an overlay layer")
            }
        }
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
