package com.cutvideo.editor.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.cutvideo.editor.data.repository.MediaRepository
import com.cutvideo.editor.data.repository.ProjectRepository
import com.cutvideo.editor.data.repository.SettingsRepository
import com.cutvideo.editor.domain.history.UndoRedoManager
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.Clip
import com.cutvideo.editor.domain.model.ClipAnimationType
import com.cutvideo.editor.domain.model.EffectSpec
import com.cutvideo.editor.domain.model.FilterSpec
import com.cutvideo.editor.domain.model.Ids
import com.cutvideo.editor.domain.model.ProjectDoc
import com.cutvideo.editor.domain.model.TextSpec
import com.cutvideo.editor.domain.model.TrackType
import com.cutvideo.editor.domain.model.TransitionSpec
import com.cutvideo.editor.domain.subtitle.SrtCodec
import com.cutvideo.editor.domain.subtitle.SubtitleCue
import com.cutvideo.editor.domain.timeline.TimelineManager
import com.cutvideo.editor.media.MediaImporter
import com.cutvideo.editor.media.ThumbnailStore
import com.cutvideo.editor.nativebridge.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editing session controller.
 *
 * Owns the mutable [ProjectDoc], the undo stack, autosave and every editing operation. The UI only
 * sends intents here; the document is the single source of truth that both the preview and the export
 * read from.
 */
class EditorController(
    private val context: Context,
    private val projectRepository: ProjectRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val mediaImporter: MediaImporter,
    private val thumbnailStore: ThumbnailStore,
    private val nativeEngine: NativeEngine,
    private val scope: CoroutineScope
) {

    private val _doc = MutableStateFlow<ProjectDoc?>(null)
    val doc: StateFlow<ProjectDoc?> = _doc.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _playheadUs = MutableStateFlow(0L)
    val playheadUs: StateFlow<Long> = _playheadUs.asStateFlow()

    private val _status = MutableStateFlow<EditorStatus>(EditorStatus.Idle)
    val status: StateFlow<EditorStatus> = _status.asStateFlow()

    val undoRedo = UndoRedoManager()
    private var autosaveJob: Job? = null
    private var openedProjectId: String? = null

    val selectedClip: Clip?
        get() = _selectedClipId.value?.let { id -> _doc.value?.clip(id) }

    sealed interface EditorStatus {
        data object Idle : EditorStatus
        data class Working(val message: String) : EditorStatus
        data class Error(val message: String) : EditorStatus
    }

    // ---------------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------------

    suspend fun open(projectId: String, onRecovered: (String) -> Unit = {}) {
        // Already live in memory: keep unsaved edits and the undo stack (the editor screen, the text,
        // audio and effects studios all share this one session).
        if (openedProjectId == projectId && _doc.value != null) return
        openedProjectId = projectId
        val loaded = projectRepository.load(projectId)
        if (loaded == null) {
            _status.value = EditorStatus.Error("Project not found")
            return
        }
        val normalized = TimelineManager.closeVideoGaps(loaded)
        undoRedo.clear()
        _doc.value = normalized
        _playheadUs.value = normalized.lastPlayheadUs.coerceIn(0L, normalized.durationUs)
        _selectedClipId.value = normalized.videoClips().firstOrNull()?.id
        if (normalized !== loaded) {
            onRecovered("Recovered unsaved timeline state")
        }
        nativeEngine.attach(normalized)
    }

    fun close() {
        autosaveJob?.cancel()
        scope.launch { persist(_doc.value, draft = true) }
    }

    /** Applies an edit, recording an undo checkpoint first. */
    fun mutate(label: String, block: (ProjectDoc) -> ProjectDoc) {
        val current = _doc.value ?: return
        val next = block(current)
        if (next === current) return
        undoRedo.record(label, current)
        _doc.value = TimelineManager.closeVideoGaps(next)
        scheduleAutosave()
    }

    /**
     * High-frequency edits (slider drags) update the document without flooding the undo stack: the
     * checkpoint is only recorded when the gesture starts.
     */
    fun mutateLive(block: (ProjectDoc) -> ProjectDoc) {
        val current = _doc.value ?: return
        _doc.value = block(current)
        scheduleAutosave()
    }

    fun checkpoint(label: String) {
        _doc.value?.let { undoRedo.record(label, it) }
    }

    fun undo() {
        val current = _doc.value ?: return
        val previous = undoRedo.undo(current) ?: return
        _doc.value = previous
        scheduleAutosave()
    }

    fun redo() {
        val current = _doc.value ?: return
        val next = undoRedo.redo(current) ?: return
        _doc.value = next
        scheduleAutosave()
    }

    fun setPlayhead(us: Long) {
        val duration = _doc.value?.durationUs ?: 0L
        _playheadUs.value = us.coerceIn(0L, maxOf(duration, 0L))
    }

    fun select(clipId: String?) {
        _selectedClipId.value = clipId
    }

    fun rename(name: String) {
        val doc = _doc.value ?: return
        val clean = name.trim().ifEmpty { doc.name }
        _doc.value = doc.copy(name = clean)
        scope.launch { projectRepository.rename(doc.id, clean) }
    }

    fun setAspect(aspect: AspectRatio) {
        mutate("Change aspect ratio") { it.copy(aspect = aspect) }
    }

    fun setFrameRate(fps: Int) {
        mutate("Change frame rate") { it.copy(frameRate = fps) }
    }

    // ---------------------------------------------------------------------------------------------
    // Timeline operations
    // ---------------------------------------------------------------------------------------------

    fun splitAtPlayhead() {
        val clipId = _selectedClipId.value
        val position = _playheadUs.value
        mutate("Split") { TimelineManager.splitAtPlayhead(it, position, clipId) }
    }

    fun deleteSelected() {
        val id = _selectedClipId.value ?: return
        mutate("Delete clip") { TimelineManager.deleteClip(it, id) }
        _selectedClipId.value = null
    }

    fun duplicateSelected() {
        val id = _selectedClipId.value ?: return
        mutate("Duplicate clip") { TimelineManager.duplicateClip(it, id) }
    }

    fun trimStart(clipId: String, newStartUs: Long, live: Boolean = false) {
        if (live) mutateLive { TimelineManager.trimStart(it, clipId, newStartUs) }
        else mutate("Trim start") { TimelineManager.trimStart(it, clipId, newStartUs) }
    }

    fun trimEnd(clipId: String, newEndUs: Long, live: Boolean = false) {
        if (live) mutateLive { TimelineManager.trimEnd(it, clipId, newEndUs) }
        else mutate("Trim end") { TimelineManager.trimEnd(it, clipId, newEndUs) }
    }

    fun moveClip(clipId: String, newStartUs: Long, trackId: String?, live: Boolean = false) {
        if (live) mutateLive { TimelineManager.moveClip(it, clipId, newStartUs, trackId) }
        else mutate("Move clip") { TimelineManager.moveClip(it, clipId, newStartUs, trackId) }
    }

    fun updateClip(clipId: String, label: String = "Edit clip", block: (Clip) -> Clip) {
        mutate(label) { TimelineManager.updateClip(it, clipId, block) }
    }

    fun updateClipLive(clipId: String, block: (Clip) -> Clip) {
        mutateLive { TimelineManager.updateClip(it, clipId, block) }
    }

    fun setSpeed(clipId: String, speed: Float) {
        mutate("Change speed") { TimelineManager.setSpeed(it, clipId, speed) }
    }

    fun setVolume(clipId: String, volume: Float, live: Boolean = false) {
        val update: (ProjectDoc) -> ProjectDoc = { TimelineManager.setVolume(it, clipId, volume) }
        if (live) mutateLive(update) else mutate("Change volume", update)
    }

    fun toggleMute(clipId: String) {
        val clip = _doc.value?.clip(clipId) ?: return
        mutate(if (clip.muted) "Unmute clip" else "Mute clip") {
            TimelineManager.setMuted(it, clipId, !clip.muted)
        }
    }

    fun setFades(clipId: String, fadeInUs: Long, fadeOutUs: Long, live: Boolean = false) {
        val update: (ProjectDoc) -> ProjectDoc = { TimelineManager.setFades(it, clipId, fadeInUs, fadeOutUs) }
        if (live) mutateLive(update) else mutate("Change fades", update)
    }

    fun setFilter(clipId: String, filterId: String, intensity: Float = 1f) {
        mutate("Apply filter") {
            TimelineManager.updateClip(it, clipId) { clip ->
                clip.copy(filter = FilterSpec(filterId, intensity))
            }
        }
    }

    fun setFilterIntensity(clipId: String, intensity: Float) {
        mutateLive {
            TimelineManager.updateClip(it, clipId) { clip ->
                clip.copy(filter = clip.filter.copy(intensity = intensity))
            }
        }
    }

    fun toggleEffect(clipId: String, effectId: String, intensity: Float = 0.5f) {
        mutate("Toggle effect") {
            TimelineManager.updateClip(it, clipId) { clip ->
                val existing = clip.effects.firstOrNull { e -> e.id == effectId }
                val effects = if (existing != null) {
                    clip.effects - existing
                } else {
                    (clip.effects + EffectSpec(effectId, intensity)).take(4)
                }
                clip.copy(effects = effects)
            }
        }
    }

    fun setEffects(clipId: String, effects: List<EffectSpec>) {
        mutate("Update effects") {
            TimelineManager.updateClip(it, clipId) { clip -> clip.copy(effects = effects.take(4)) }
        }
    }

    fun setTransition(fromClipId: String, toClipId: String, spec: TransitionSpec?) {
        mutate("Set transition") { TimelineManager.setBoundaryTransition(it, fromClipId, toClipId, spec) }
    }

    fun setAnimation(clipId: String, inType: ClipAnimationType, outType: ClipAnimationType, durationUs: Long) {
        mutate("Set animation") {
            TimelineManager.updateClip(it, clipId) { clip ->
                clip.copy(
                    animationIn = clip.animationIn.copy(
                        type = inType,
                        durationUs = durationUs.coerceIn(80_000L, clip.durationUs / 2)
                    ),
                    animationOut = clip.animationOut.copy(
                        type = outType,
                        durationUs = durationUs.coerceIn(80_000L, clip.durationUs / 2)
                    )
                )
            }
        }
    }

    fun applyAnimationToAll(type: ClipAnimationType, inType: Boolean) {
        mutate("Apply animation to all") { doc ->
            doc.copy(
                tracks = doc.tracks.map { track ->
                    track.copy(
                        clips = track.clips.map { clip ->
                            val duration = minOf(500_000L, clip.durationUs / 2).coerceAtLeast(80_000L)
                            if (inType) clip.copy(animationIn = clip.animationIn.copy(type = type, durationUs = duration))
                            else clip.copy(animationOut = clip.animationOut.copy(type = type, durationUs = duration))
                        }
                    )
                },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Media
    // ---------------------------------------------------------------------------------------------

    suspend fun importMedia(uris: List<Uri>, trackType: TrackType = TrackType.VIDEO) {
        val doc = _doc.value ?: return
        _status.value = EditorStatus.Working("Importing ${uris.size} item(s)…")
        val imported = mediaImporter.import(uris, doc.id, trackType, doc.durationUs)
        if (imported.isEmpty()) {
            _status.value = EditorStatus.Error("Nothing could be imported from the selection")
            return
        }
        mutate("Import media") { current ->
            imported.fold(current) { acc, media ->
                TimelineManager.addClipToTrack(acc, trackFor(acc, trackType), media.clip)
            }
        }
        _selectedClipId.value = imported.firstOrNull()?.clip?.id
        _status.value = EditorStatus.Idle
    }

    suspend fun extractAudio(videoClipId: String) {
        val doc = _doc.value ?: return
        val clip = doc.clip(videoClipId) ?: return
        _status.value = EditorStatus.Working("Extracting audio…")
        val extracted = mediaImporter.extractAudioClip(clip, doc.id)
        if (extracted == null) {
            _status.value = EditorStatus.Error("This clip has no audio stream")
            return
        }
        mutate("Extract audio") { current ->
            TimelineManager.addClipToTrack(current, trackFor(current, TrackType.AUDIO), extracted.clip)
        }
        _status.value = EditorStatus.Idle
    }

    /** Mutes (or unmutes) the original audio of every clip on the video track. */
    fun toggleMuteOriginal() {
        mutate("Mute original audio") { current ->
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.type == TrackType.VIDEO) {
                        track.copy(clips = track.clips.map { it.copy(muted = !it.muted) })
                    } else {
                        track
                    }
                },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    val isOriginalMuted: Boolean
        get() = _doc.value?.videoClips()?.let { clips -> clips.isNotEmpty() && clips.all { it.muted } } ?: false

    // ---------------------------------------------------------------------------------------------
    // Text & subtitles
    // ---------------------------------------------------------------------------------------------

    fun addTextClip(text: String, atUs: Long, durationUs: Long = TimelineManager.DEFAULT_TEXT_US): String? {
        val doc = _doc.value ?: return null
        val trackId = trackFor(doc, TrackType.TEXT)
        val clip = Clip(
            id = Ids.clip(),
            trackType = TrackType.TEXT,
            name = text.take(24),
            startUs = atUs,
            durationUs = durationUs,
            text = TextSpec(text = text)
        )
        mutate("Add text") { TimelineManager.addClipToTrack(it, trackId, clip) }
        _selectedClipId.value = clip.id
        return clip.id
    }

    fun addOverlayTrackClip(clip: Clip) {
        val doc = _doc.value ?: return
        val trackId = trackFor(doc, TrackType.OVERLAY)
        mutate("Add overlay") { TimelineManager.addClipToTrack(it, trackId, clip) }
    }

    suspend fun importSubtitles(uri: Uri): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val doc = _doc.value ?: return@withContext 0
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@withContext 0
        val cues = SrtCodec.parse(text)
        if (cues.isEmpty()) return@withContext 0
        val trackId = trackFor(doc, TrackType.TEXT)
        mutate("Import subtitles") { current ->
            cues.fold(current) { acc, cue ->
                val clip = Clip(
                    id = Ids.clip(),
                    trackType = TrackType.TEXT,
                    name = cue.text.take(24),
                    startUs = cue.startUs,
                    durationUs = (cue.endUs - cue.startUs).coerceAtLeast(TimelineManager.MIN_CLIP_US),
                    text = TextSpec(text = cue.text)
                )
                TimelineManager.addClipToTrack(acc, trackId, clip)
            }
        }
        cues.size
    }

    suspend fun exportSubtitles(uri: Uri): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val doc = _doc.value ?: return@withContext 0
        val cues = doc.textClips().sortedBy { it.startUs }.mapIndexed { index, clip ->
            SubtitleCue(index + 1, clip.startUs, clip.endUs, clip.text?.text ?: clip.name)
        }
        if (cues.isEmpty()) return@withContext 0
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(SrtCodec.write(cues)) }
            ?: return@withContext 0
        cues.size
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            val seconds = runCatching { settingsRepository.settings.first().autosaveSeconds }.getOrDefault(8)
            delay(seconds * 1000L)
            persist(_doc.value, draft = true)
        }
    }

    /** Writes the document now. Returns the saved project id (or null when nothing is open). */
    suspend fun saveNow(draft: Boolean = false): String? {
        val document = _doc.value ?: return null
        autosaveJob?.cancel()
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            projectRepository.save(document.copy(lastPlayheadUs = _playheadUs.value), null, draft)
            document.id
        }
    }

    private suspend fun persist(document: ProjectDoc?, draft: Boolean) {
        val doc = document ?: return
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                projectRepository.save(doc.copy(lastPlayheadUs = _playheadUs.value), null, draft)
                val cover = generateCover(doc)
                if (cover != null) projectRepository.setThumbnail(doc.id, cover)
            }.onFailure { Log.w(TAG, "autosave failed", it) }
        }
    }

    /** Renders the cover from a real frame of the first clip at the playhead. */
    suspend fun generateCover(doc: ProjectDoc): String? {
        val clip = doc.primaryVideoClip(_playheadUs.value) ?: doc.videoClips().firstOrNull() ?: return null
        if (clip.uri.isBlank()) return null
        val frame: Bitmap = thumbnailStore.frame(clip.uri, clip.sourceInUs, 640) ?: return null
        return thumbnailStore.saveProjectCover(doc.id, frame)
    }

    fun createProject(name: String, aspect: AspectRatio, frameRate: Int): ProjectDoc {
        val doc = TimelineManager.createProject(name, aspect, frameRate)
        _doc.value = doc
        openedProjectId = doc.id
        undoRedo.clear()
        scope.launch { projectRepository.save(doc, null, isDraft = true) }
        return doc
    }

    fun deleteClip(clipId: String) {
        mutate("Delete clip") { TimelineManager.deleteClip(it, clipId) }
    }

    fun addTrack(type: TrackType) {
        mutate("Add track") { TimelineManager.addTrack(it, type).first }
    }

    fun setCrop(clipId: String, left: Float, top: Float, right: Float, bottom: Float) {
        mutateLive {
            TimelineManager.updateClip(it, clipId) { clip ->
                clip.copy(
                    transform = clip.transform.copy(
                        cropLeft = left.coerceIn(0f, 0.45f),
                        cropTop = top.coerceIn(0f, 0.45f),
                        cropRight = right.coerceIn(0f, 0.45f),
                        cropBottom = bottom.coerceIn(0f, 0.45f)
                    )
                )
            }
        }
    }

    fun setRotation(clipId: String, rotation: Float) {
        updateClip(clipId, "Rotate") { it.copy(transform = it.transform.copy(rotationDegrees = rotation % 360f)) }
    }

    fun setScale(clipId: String, scale: Float) {
        updateClipLive(clipId) {
            it.copy(transform = it.transform.copy(scaleX = scale, scaleY = scale))
        }
    }

    fun setPip(clipId: String, enabled: Boolean) {
        updateClip(clipId, "Toggle PiP") {
            it.copy(pip = it.pip.copy(enabled = enabled))
        }
    }

    fun setChromaEnabled(clipId: String, enabled: Boolean) {
        updateClip(clipId, "Chroma key") { it.copy(chroma = it.chroma.copy(enabled = enabled)) }
    }

    fun freezeFrame(clipId: String): String? {
        val doc = _doc.value ?: return null
        val clip = doc.clip(clipId) ?: return null
        val frameClip = clip.copy(
            id = Ids.clip(),
            name = "${clip.name} (freeze)",
            durationUs = 1_500_000L,
            sourceInUs = TimelineManager.sourceTimeUs(clip, _playheadUs.value),
            speed = 1f,
            frozen = true,
            animationIn = clip.animationIn.copy(type = ClipAnimationType.NONE),
            animationOut = clip.animationOut.copy(type = ClipAnimationType.NONE)
        )
        mutate("Freeze frame") { TimelineManager.addClipToTrack(it, trackFor(it, TrackType.VIDEO), frameClip) }
        return frameClip.id
    }

    private fun trackFor(doc: ProjectDoc, type: TrackType): String {
        doc.tracks.firstOrNull { it.type == type }?.let { return it.id }
        val (updated, id) = TimelineManager.addTrack(doc, type)
        _doc.value = updated
        return id
    }

    /** Writes a rendered preview frame to a file (used by diagnostics / cover capture). */
    suspend fun captureFrame(uri: String, positionUs: Long, file: File): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = thumbnailStore.frame(uri, positionUs, 720) ?: return@withContext false
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            }.getOrDefault(false)
        }

    val currentProjectId: String? get() = openedProjectId

    private companion object {
        const val TAG = "EditorController"
    }
}
