package com.cutvideo.editor.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.cutvideo.editor.data.repository.MediaRepository
import com.cutvideo.editor.data.repository.ProjectRepository
import com.cutvideo.editor.data.repository.SettingsRepository
import com.cutvideo.editor.domain.history.UndoRedoManager
import com.cutvideo.editor.domain.model.Adjustments
import com.cutvideo.editor.domain.model.AspectRatio
import com.cutvideo.editor.domain.model.ChromaKeySpec
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
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        // Legacy entry point (library screen): adds the effect, or removes the first instance of it.
        val clip = _doc.value?.clip(clipId)
        if (clip != null && clip.effects.none { it.id == effectId } && clip.effects.size >= MAX_EFFECTS) {
            _status.value = EditorStatus.Error("Up to $MAX_EFFECTS effects per clip — remove one first")
            return
        }
        mutate("Toggle effect") {
            TimelineManager.updateClip(it, clipId) { clip ->
                val existing = clip.effects.firstOrNull { e -> e.id == effectId }
                val effects = if (existing != null) {
                    clip.effects - existing
                } else {
                    clip.effects + EffectSpec(effectId, intensity)
                }
                clip.copy(effects = effects)
            }
        }
    }

    // --- effect chain commands (ordering, parameters, blending) ---------------------------------

    /** Adds an effect at the end of the chain and returns its instance id. */
    fun addEffect(clipId: String, effectId: String): String? {
        val clip = _doc.value?.clip(clipId) ?: return null
        if (clip.effects.size >= MAX_EFFECTS) {
            _status.value = EditorStatus.Error("Up to $MAX_EFFECTS effects per clip — remove one first")
            return null
        }
        val spec = EffectSpec(effectId, com.cutvideo.editor.domain.model.EffectCatalog.def(effectId).defaultIntensity)
        mutate("Add effect") { doc ->
            TimelineManager.updateClip(doc, clipId) { it.copy(effects = it.effects + spec) }
        }
        return spec.instanceId
    }

    fun removeEffect(clipId: String, instanceId: String) {
        mutate("Remove effect") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(effects = clip.effects.filterNot { it.instanceId == instanceId })
            }
        }
    }

    /** Moves an effect up (−1) or down (+1) in the render order. */
    fun moveEffect(clipId: String, instanceId: String, delta: Int) {
        mutate("Reorder effects") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(effects = com.cutvideo.editor.editor.effects.EffectChain.moved(clip.effects, instanceId, delta))
            }
        }
    }

    fun duplicateEffect(clipId: String, instanceId: String) {
        val clip = _doc.value?.clip(clipId) ?: return
        if (clip.effects.size >= MAX_EFFECTS) {
            _status.value = EditorStatus.Error("Up to $MAX_EFFECTS effects per clip — remove one first")
            return
        }
        mutate("Duplicate effect") { doc ->
            TimelineManager.updateClip(doc, clipId) { c ->
                c.copy(effects = com.cutvideo.editor.editor.effects.EffectChain.duplicated(c.effects, instanceId))
            }
        }
    }

    fun toggleEffectEnabled(clipId: String, instanceId: String) {
        mutate("Toggle effect") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(effects = com.cutvideo.editor.editor.effects.EffectChain.toggled(clip.effects, instanceId))
            }
        }
    }

    fun setEffectParam(clipId: String, instanceId: String, key: String, value: Float, live: Boolean = false) {
        val update: (ProjectDoc) -> ProjectDoc = { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(
                    effects = com.cutvideo.editor.editor.effects.EffectChain
                        .withParam(clip.effects, instanceId, key, value)
                )
            }
        }
        if (live) mutateLive(update) else mutate("Set effect parameter", update)
    }

    fun setEffectIntensityById(clipId: String, instanceId: String, value: Float, live: Boolean = false) {
        val update: (ProjectDoc) -> ProjectDoc = { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(
                    effects = clip.effects.map {
                        if (it.instanceId == instanceId) it.copy(intensity = value.coerceIn(0f, 1f)) else it
                    }
                )
            }
        }
        if (live) mutateLive(update) else mutate("Set effect intensity", update)
    }

    fun setEffectBlend(clipId: String, instanceId: String, blend: com.cutvideo.editor.domain.model.BlendMode) {
        mutate("Set blend mode") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(effects = clip.effects.map {
                    if (it.instanceId == instanceId) it.copy(blend = blend) else it
                })
            }
        }
    }

    /** Replaces the clip's chain with a named multi-effect preset stack. */
    fun applyEffectStack(clipId: String, stackId: String) {
        val stack = com.cutvideo.editor.domain.model.EffectCatalog.stacks.firstOrNull { it.id == stackId } ?: return
        mutate("Apply effect preset") { doc ->
            TimelineManager.updateClip(doc, clipId) { it.copy(effects = stack.effects) }
        }
    }

    fun clearEffects(clipId: String) {
        mutate("Clear effects") { doc ->
            TimelineManager.updateClip(doc, clipId) { it.copy(effects = emptyList()) }
        }
    }

    fun setEffects(clipId: String, effects: List<EffectSpec>) {
        mutate("Update effects") {
            TimelineManager.updateClip(it, clipId) { clip -> clip.copy(effects = effects.take(MAX_EFFECTS)) }
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
    // Pro tools
    // ---------------------------------------------------------------------------------------------

    /** The reusable part of a clip's look: filter, effects, adjustments, chroma key and blur. */
    data class ClipStyle(
        val adjust: Adjustments = Adjustments(),
        val filter: FilterSpec = FilterSpec(),
        val effects: List<EffectSpec> = emptyList(),
        val chroma: ChromaKeySpec = ChromaKeySpec(),
        val blur: Float = 0f
    )

    private var styleClipboard: ClipStyle? = null

    val hasCopiedStyle: Boolean get() = styleClipboard != null

    /** Copies the look of a clip so it can be pasted on other clips or the whole timeline. */
    fun copyStyle(clipId: String): Boolean {
        val clip = _doc.value?.clip(clipId) ?: return false
        styleClipboard = ClipStyle(clip.adjust, clip.filter, clip.effects, clip.chroma, clip.blur)
        return true
    }

    fun pasteStyle(clipId: String): Boolean {
        val style = styleClipboard ?: return false
        mutate("Paste style") { doc -> applyStyle(doc, clipId, style) }
        return true
    }

    /** Applies the look of [clipId] to every other video/overlay clip. Returns how many changed. */
    fun applyStyleToAll(clipId: String): Int {
        val doc = _doc.value ?: return 0
        val source = doc.clip(clipId) ?: return 0
        val style = ClipStyle(source.adjust, source.filter, source.effects, source.chroma, source.blur)
        var changed = 0
        mutate("Apply look to all") { current ->
            var next = current
            current.tracks
                .filter { it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY }
                .flatMap { it.clips }
                .filter { it.id != clipId && !it.isText }
                .forEach { target ->
                    next = applyStyle(next, target.id, style)
                    changed++
                }
            next
        }
        return changed
    }

    private fun applyStyle(doc: ProjectDoc, clipId: String, style: ClipStyle): ProjectDoc =
        TimelineManager.updateClip(doc, clipId) { clip ->
            clip.copy(
                adjust = style.adjust,
                filter = style.filter,
                effects = style.effects,
                chroma = style.chroma,
                blur = style.blur
            )
        }

    /**
     * One-tap enhancement derived from an actual frame of the clip (not a fixed preset): the mean
     * luminance drives exposure, the standard deviation drives contrast, measured colourfulness
     * drives saturation and a low local gradient raises sharpening.
     */
    suspend fun autoEnhance(clipId: String): Boolean {
        val clip = _doc.value?.clip(clipId) ?: return false
        if (clip.uri.isBlank()) return false
        _status.value = EditorStatus.Working("Analysing exposure…")
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { thumbnailStore.frame(clip.uri, clip.sourceInUs, 192) }.getOrNull()
        }
        val stats = bitmap?.let { runCatching { frameStats(it) }.getOrNull() }
        if (stats == null) {
            _status.value = EditorStatus.Error("Could not read a frame from this clip")
            return false
        }
        val adjust = clip.adjust.copy(
            brightness = ((0.48f - stats.mean) * 0.9f).coerceIn(-0.30f, 0.30f),
            contrast = ((0.16f - stats.std) * 2.0f).coerceIn(-0.10f, 0.28f),
            saturation = when {
                stats.saturation < 0.12f -> 0.30f
                stats.saturation < 0.22f -> 0.16f
                else -> 0.05f
            },
            sharpen = if (stats.detail < 0.06f) 0.18f else 0f
        )
        mutate("Auto enhance") { doc ->
            TimelineManager.updateClip(doc, clipId) { it.copy(adjust = adjust) }
        }
        _status.value = EditorStatus.Idle
        return true
    }

    /** Moves a video clip onto the overlay track so it can be composited on top. */
    fun moveToOverlay(clipId: String): Boolean {
        val doc = _doc.value ?: return false
        val clip = doc.clip(clipId) ?: return false
        if (clip.isText || clip.trackType != TrackType.VIDEO) return false
        mutate("Send to overlay") { current ->
            var next = current
            val trackId = current.tracks.firstOrNull { it.type == TrackType.OVERLAY }?.id ?: run {
                val (withTrack, id) = TimelineManager.addTrack(current, TrackType.OVERLAY)
                next = withTrack
                id
            }
            TimelineManager.moveClip(next, clipId, clip.startUs, trackId)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Keyframes, masks and revert (CapCut-style tools)
    // -------------------------------------------------------------------------------------------------------------

    /**
     * Adds (or replaces) a keyframe for [property] at clip-local [timeUs], seeded with the value
     * currently visible there so the edit never jumps.
     */
    fun addKeyframe(clipId: String, property: com.cutvideo.editor.domain.model.KeyframeProperty, timeUs: Long) {
        mutate("Add keyframe") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val existing = clip.keyframeTrack(property)
                val current = KeyframeEngine.evaluate(existing, timeUs, baseValue(clip, property))
                val key = com.cutvideo.editor.domain.model.Keyframe(
                    timeUs = timeUs.coerceIn(0L, clip.durationUs),
                    value = current.coerceIn(property.minValue, property.maxValue)
                )
                val track = existing?.let { KeyframeEngine.withKeyframe(it, key) }
                    ?: com.cutvideo.editor.domain.model.KeyframeTrack(property, listOf(key))
                clip.copy(keyframes = clip.keyframes.filterNot { it.property == property } + track)
            }
        }
    }

    /**
     * Adds a keyframe on one effect's own parameter at the playhead. [param] targets that
     * parameter; empty [param] targets the effect's intensity.
     */
    fun addEffectKeyframe(clipId: String, instanceId: String, param: String, timeUs: Long) {
        mutate("Add effect keyframe") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val spec = clip.effects.firstOrNull { it.instanceId == instanceId } ?: return@updateClip clip
                val property = if (param.isEmpty()) {
                    com.cutvideo.editor.domain.model.KeyframeProperty.EFFECT_INTENSITY
                } else {
                    com.cutvideo.editor.domain.model.KeyframeProperty.EFFECT_PARAM
                }
                val base = if (param.isEmpty()) {
                    spec.intensity
                } else {
                    spec.params[param] ?: com.cutvideo.editor.domain.model.EffectCatalog
                        .def(spec.id).params.firstOrNull { it.key == param }?.default ?: 0f
                }
                val existing = clip.keyframes.firstOrNull {
                    it.property == property && it.targetId == instanceId && it.param == param
                }
                val current = KeyframeEngine.evaluate(existing, timeUs, base)
                val key = com.cutvideo.editor.domain.model.Keyframe(
                    timeUs = timeUs.coerceIn(0L, clip.durationUs),
                    value = current
                )
                val track = existing?.let { KeyframeEngine.withKeyframe(it, key) }
                    ?: com.cutvideo.editor.domain.model.KeyframeTrack(property, listOf(key), instanceId, param)
                clip.copy(
                    keyframes = clip.keyframes.filterNot {
                        it.property == property && it.targetId == instanceId && it.param == param
                    } + track
                )
            }
        }
    }

    fun deleteEffectKeyframe(clipId: String, instanceId: String, param: String, timeUs: Long) {
        mutate("Delete effect keyframe") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val property = if (param.isEmpty()) {
                    com.cutvideo.editor.domain.model.KeyframeProperty.EFFECT_INTENSITY
                } else {
                    com.cutvideo.editor.domain.model.KeyframeProperty.EFFECT_PARAM
                }
                val track = clip.keyframes.firstOrNull {
                    it.property == property && it.targetId == instanceId && it.param == param
                } ?: return@updateClip clip
                val next = KeyframeEngine.withoutKeyframe(track, timeUs)
                val remaining = clip.keyframes.filterNot {
                    it.property == property && it.targetId == instanceId && it.param == param
                }
                clip.copy(keyframes = if (next.isEmpty) remaining else remaining + next)
            }
        }
    }

    fun deleteKeyframe(clipId: String, property: com.cutvideo.editor.domain.model.KeyframeProperty, timeUs: Long) {
        mutate("Delete keyframe") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val track = clip.keyframeTrack(property) ?: return@updateClip clip
                val next = KeyframeEngine.withoutKeyframe(track, timeUs)
                clip.copy(
                    keyframes = if (next.isEmpty) {
                        clip.keyframes.filterNot { it.property == property }
                    } else {
                        clip.keyframes.filterNot { it.property == property } + next
                    }
                )
            }
        }
    }

    fun moveKeyframe(
        clipId: String,
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        fromUs: Long,
        toUs: Long,
        newValue: Float? = null
    ) {
        mutate("Move keyframe") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val track = clip.keyframeTrack(property) ?: return@updateClip clip
                clip.copy(
                    keyframes = clip.keyframes.filterNot { it.property == property } +
                        KeyframeEngine.moveKeyframe(track, fromUs, toUs, newValue)
                )
            }
        }
    }

    fun setKeyframeInterpolation(
        clipId: String,
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        timeUs: Long,
        interpolation: com.cutvideo.editor.domain.model.KeyframeInterpolation
    ) {
        mutate("Set interpolation") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val track = clip.keyframeTrack(property) ?: return@updateClip clip
                clip.copy(
                    keyframes = clip.keyframes.filterNot { it.property == property } +
                        KeyframeEngine.withInterpolation(track, timeUs, interpolation)
                )
            }
        }
    }

    fun setKeyframeHandles(
        clipId: String,
        property: com.cutvideo.editor.domain.model.KeyframeProperty,
        timeUs: Long,
        inX: Float,
        inY: Float,
        outX: Float,
        outY: Float
    ) {
        mutateLive {
            TimelineManager.updateClip(it, clipId) { clip ->
                val track = clip.keyframeTrack(property) ?: return@updateClip clip
                clip.copy(
                    keyframes = clip.keyframes.filterNot { kt -> kt.property == property } +
                        KeyframeEngine.withHandles(track, timeUs, inX, inY, outX, outY)
                )
            }
        }
    }

    fun clearKeyframes(clipId: String, property: com.cutvideo.editor.domain.model.KeyframeProperty? = null) {
        mutate("Clear keyframes") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(
                    keyframes = if (property == null) emptyList()
                    else clip.keyframes.filterNot { it.property == property }
                )
            }
        }
    }

    /** The value a property shows when no keyframes are set (the clip's static state). */
    fun baseValue(clip: Clip, property: com.cutvideo.editor.domain.model.KeyframeProperty): Float =
        when (property) {
            com.cutvideo.editor.domain.model.KeyframeProperty.POSITION_X -> clip.transform.offsetXFrac
            com.cutvideo.editor.domain.model.KeyframeProperty.POSITION_Y -> clip.transform.offsetYFrac
            com.cutvideo.editor.domain.model.KeyframeProperty.SCALE -> clip.transform.scaleX
            com.cutvideo.editor.domain.model.KeyframeProperty.ROTATION -> clip.transform.rotationDegrees
            com.cutvideo.editor.domain.model.KeyframeProperty.OPACITY -> 1f
            com.cutvideo.editor.domain.model.KeyframeProperty.VOLUME -> clip.effectiveVolume
            com.cutvideo.editor.domain.model.KeyframeProperty.BLUR -> clip.blur
            com.cutvideo.editor.domain.model.KeyframeProperty.SPEED -> clip.speed
            com.cutvideo.editor.domain.model.KeyframeProperty.GLOW -> 0f
            com.cutvideo.editor.domain.model.KeyframeProperty.EFFECT_INTENSITY ->
                clip.effects.firstOrNull()?.intensity ?: 0.5f
            com.cutvideo.editor.domain.model.KeyframeProperty.CROP_LEFT -> clip.transform.cropLeft
            com.cutvideo.editor.domain.model.KeyframeProperty.CROP_RIGHT -> clip.transform.cropRight
            com.cutvideo.editor.domain.model.KeyframeProperty.CROP_TOP -> clip.transform.cropTop
            com.cutvideo.editor.domain.model.KeyframeProperty.CROP_BOTTOM -> clip.transform.cropBottom
            else -> clip.masks.firstOrNull()?.let { mask ->
                when (property) {
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_X -> mask.xFrac
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_Y -> mask.yFrac
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_SCALE -> mask.scale
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_ROTATION -> mask.rotationDegrees
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_FEATHER -> mask.feather
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_OPACITY -> mask.opacity
                    com.cutvideo.editor.domain.model.KeyframeProperty.MASK_EXPANSION -> mask.expansion
                    else -> property.defaultValue
                }
            } ?: property.defaultValue
        }

    fun addMask(clipId: String, type: com.cutvideo.editor.domain.model.MaskType): String? {
        var newId: String? = null
        mutate("Add mask") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                if (clip.masks.size >= 2) return@updateClip clip // shader supports two; say so in UI
                val mask = com.cutvideo.editor.domain.model.MaskSpec(type = type)
                newId = mask.id
                clip.copy(masks = clip.masks + mask)
            }
        }
        return newId
    }

    fun updateMask(
        clipId: String,
        maskId: String,
        live: Boolean = false,
        block: (com.cutvideo.editor.domain.model.MaskSpec) -> com.cutvideo.editor.domain.model.MaskSpec
    ) {
        val update: (ProjectDoc) -> ProjectDoc = { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(masks = clip.masks.map { if (it.id == maskId) block(it) else it })
            }
        }
        if (live) mutateLive(update) else mutate("Edit mask", update)
    }

    fun deleteMask(clipId: String, maskId: String) {
        mutate("Delete mask") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                clip.copy(masks = clip.masks.filterNot { it.id == maskId })
            }
        }
    }

    /** Adds a freehand stroke to the clip's freehand mask (creates the mask when missing). */
    fun addMaskStroke(clipId: String, stroke: List<com.cutvideo.editor.domain.model.MaskPoint>) {
        mutateLive {
            TimelineManager.updateClip(it, clipId) { clip ->
                val existing = clip.masks.firstOrNull { m -> m.type == com.cutvideo.editor.domain.model.MaskType.FREEHAND }
                if (existing != null) {
                    clip.copy(masks = clip.masks.map { m ->
                        if (m.id == existing.id) m.copy(strokes = m.strokes + listOf(stroke)) else m
                    })
                } else {
                    val mask = com.cutvideo.editor.domain.model.MaskSpec(
                        type = com.cutvideo.editor.domain.model.MaskType.FREEHAND,
                        strokes = listOf(stroke)
                    )
                    clip.copy(masks = (clip.masks + mask).take(2))
                }
            }
        }
    }

    /** Restores one property group of a clip from its import-time snapshot. */
    fun revertProperty(clipId: String, group: com.cutvideo.editor.domain.model.RevertGroup) {
        mutate("Revert ${group.label}") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val original = clip.original ?: return@updateClip clip
                when (group) {
                    com.cutvideo.editor.domain.model.RevertGroup.TRANSFORM -> clip.copy(transform = original.transform)
                    com.cutvideo.editor.domain.model.RevertGroup.COLOR -> clip.copy(
                        adjust = original.adjust,
                        filter = original.filter
                    )
                    com.cutvideo.editor.domain.model.RevertGroup.EFFECTS -> clip.copy(
                        effects = original.effects,
                        blur = original.blur
                    )
                    com.cutvideo.editor.domain.model.RevertGroup.MASKS -> clip.copy(masks = original.masks)
                    com.cutvideo.editor.domain.model.RevertGroup.KEYFRAMES -> clip.copy(keyframes = original.keyframes)
                    com.cutvideo.editor.domain.model.RevertGroup.AUDIO -> clip.copy(
                        volume = original.volume,
                        muted = original.muted,
                        fadeInUs = original.fadeInUs,
                        fadeOutUs = original.fadeOutUs
                    )
                    com.cutvideo.editor.domain.model.RevertGroup.SPEED -> clip.copy(speed = original.speed)
                    com.cutvideo.editor.domain.model.RevertGroup.CHROMA -> clip.copy(chroma = original.chroma)
                }
            }
        }
    }

    /**
     * Restores the whole clip to its import-time state. The imported media file is never touched.
     * Callers should confirm with the user first (see the clip menu dialog).
     */
    fun revertClip(clipId: String) {
        mutate("Revert clip") { doc ->
            TimelineManager.updateClip(doc, clipId) { clip ->
                val original = clip.original ?: return@updateClip clip
                clip.copy(
                    speed = original.speed,
                    volume = original.volume,
                    muted = original.muted,
                    fadeInUs = original.fadeInUs,
                    fadeOutUs = original.fadeOutUs,
                    transform = original.transform,
                    adjust = original.adjust,
                    filter = original.filter,
                    effects = original.effects,
                    blur = original.blur,
                    chroma = original.chroma,
                    masks = original.masks,
                    keyframes = original.keyframes
                )
            }
        }
    }

    private data class FrameStats(val mean: Float, val std: Float, val saturation: Float, val detail: Float)

    /** Samples up to 48x48 pixels: cheap enough to run on the main thread for a thumbnail. */
    private fun frameStats(bitmap: Bitmap): FrameStats {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return FrameStats(0.5f, 0.18f, 0.3f, 0.1f)
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var satSum = 0.0
        var detailSum = 0.0
        var previousRow: FloatArray? = null
        var y = 0
        while (y < h) {
            val row = FloatArray((w - 1) / stepX + 1)
            var index = 0
            var x = 0
            while (x < w) {
                val color = bitmap.getPixel(x, y)
                val r = ((color shr 16) and 0xFF) / 255f
                val g = ((color shr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (index < row.size) row[index] = l
                val previous = previousRow
                if (previous != null && index < previous.size) detailSum += abs(l - previous[index])
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                satSum += if (maxC > 0.001f) (maxC - minC) / maxC else 0f
                sum += l
                sumSq += l.toDouble() * l
                n++
                index++
                x += stepX
            }
            previousRow = row
            y += stepY
        }
        if (n == 0) return FrameStats(0.5f, 0.18f, 0.3f, 0.1f)
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return FrameStats(
            mean = mean.toFloat(),
            std = sqrt(variance).toFloat(),
            saturation = (satSum / n).toFloat(),
            detail = (detailSum / n).toFloat()
        )
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
        val skipped = uris.size - imported.size
        _status.value = if (skipped > 0) {
            EditorStatus.Error("Imported ${imported.size} of ${uris.size} item(s); $skipped could not be read")
        } else {
            EditorStatus.Idle
        }
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
        val clip = TimelineManager.primaryVideoClip(doc, _playheadUs.value)
            ?: doc.videoClips().firstOrNull() ?: return null
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
        /** `uEffectIds[4]` in the shader and the native effect stack both hold four entries. */
        const val MAX_EFFECTS = 12

        const val TAG = "EditorController"
    }
}
