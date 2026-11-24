package com.cutvideo.editor.domain.model

import kotlinx.serialization.Serializable

/**
 * The single source of truth for an edit.
 *
 * Everything (preview, export, thumbnails, persistence, undo/redo) reads this document. There is
 * deliberately no second "render graph" model: the native engine receives a flattened projection of
 * this same document (see `NativeTimelineEncoder`), and the Media3 Transformer export receives the
 * same clip list. That is what keeps preview and export consistent.
 *
 * All times are microseconds (Long), all colours are ARGB Ints.
 */
@Serializable
data class ProjectDoc(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val aspect: AspectRatio = AspectRatio.RATIO_16_9,
    val frameRate: Int = 30,
    val tracks: List<Track> = emptyList(),
    val masterVolume: Float = 1f,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val lastPlayheadUs: Long = 0L
) {
    val durationUs: Long
        get() = tracks.flatMap { it.clips }.maxOfOrNull { it.endUs } ?: 0L

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun clip(clipId: String): Clip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == clipId } }

    fun trackOf(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    fun videoClips(): List<Clip> = tracks.filter { it.type == TrackType.VIDEO }.flatMap { it.clips }

    fun audioClips(): List<Clip> = tracks.filter { it.type == TrackType.AUDIO }.flatMap { it.clips }

    fun textClips(): List<Clip> = tracks.filter { it.type == TrackType.TEXT }.flatMap { it.clips }

    fun overlayClips(): List<Clip> = tracks.filter { it.type == TrackType.OVERLAY }.flatMap { it.clips }

    fun clipCount(): Int = tracks.sumOf { it.clips.size }

    /** Deep-ish copy helper used by every mutating operation. */
    fun mapTracks(transform: (Track) -> Track): ProjectDoc =
        copy(tracks = tracks.map(transform), updatedAt = System.currentTimeMillis())
}

@Serializable
enum class TrackType { VIDEO, AUDIO, TEXT, OVERLAY }

@Serializable
data class Track(
    val id: String,
    val type: TrackType,
    val name: String,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val volume: Float = 1f,
    val clips: List<Clip> = emptyList()
) {
    val sortedClips: List<Clip> get() = clips.sortedBy { it.startUs }
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L
}

/** A clip on a track. `durationUs` is timeline duration *after* speed changes. */
@Serializable
data class Clip(
    val id: String,
    val trackType: TrackType,
    val name: String = "Clip",
    val mediaId: String? = null,
    val uri: String = "",
    val mimeType: String = "video/mp4",
    val startUs: Long = 0L,
    val durationUs: Long = 0L,
    /** Offset of the visible region inside the source media. */
    val sourceInUs: Long = 0L,
    /** Full length of the source media (used to clamp trims). */
    val sourceDurationUs: Long = 0L,
    val speed: Float = 1f,
    val reversed: Boolean = false,
    val frozen: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transform: Transform = Transform(),
    val adjust: Adjustments = Adjustments(),
    val filter: FilterSpec = FilterSpec(),
    val effects: List<EffectSpec> = emptyList(),
    val chroma: ChromaKeySpec = ChromaKeySpec(),
    val blur: Float = 0f,
    val stabilized: Boolean = false,
    val pip: PipSpec = PipSpec(),
    val text: TextSpec? = null,
    val animationIn: ClipAnimationSpec = ClipAnimationSpec(),
    val animationOut: ClipAnimationSpec = ClipAnimationSpec(),
    val transitionIn: TransitionSpec? = null,
    val transitionOut: TransitionSpec? = null,
    /** Keyframe tracks animating this clip's properties (see [KeyframeProperty]). */
    val keyframes: List<KeyframeTrack> = emptyList(),
    /** Masks applied to the rendered image (real alpha masking in the GL pipeline). */
    val masks: List<MaskSpec> = emptyList(),
    /** Snapshot of the editable state at import time; the Revert tool restores from this. */
    val original: ClipOriginal? = null,
    /** Static image clip (photo / transparent PNG) instead of a video. */
    val isImage: Boolean = false
) {
    val endUs: Long get() = startUs + durationUs
    /** How much source material is consumed by this clip (before speed). */
    val sourceSpanUs: Long get() = (durationUs * speed).toLong()
    val isText: Boolean get() = trackType == TrackType.TEXT
    val hasVideoStream: Boolean get() = trackType == TrackType.VIDEO || trackType == TrackType.OVERLAY
    val effectiveVolume: Float get() = if (muted) 0f else volume

    fun keyframeTrack(property: KeyframeProperty): KeyframeTrack? =
        keyframes.firstOrNull { it.property == property && it.isNotEmpty }

    val hasKeyframes: Boolean get() = keyframes.any { it.isNotEmpty }
    val hasMasks: Boolean get() = masks.any { it.enabled }
}

@Serializable
data class Transform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetXFrac: Float = 0f,
    val offsetYFrac: Float = 0f,
    val rotationDegrees: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val cropLeft: Float = 0f,
    val cropRight: Float = 0f,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f
) {
    val isIdentity: Boolean
        get() = scaleX == 1f && scaleY == 1f && offsetXFrac == 0f && offsetYFrac == 0f &&
            rotationDegrees == 0f && !flipH && !flipV && !isCropped
    val isCropped: Boolean get() = cropLeft > 0f || cropRight > 0f || cropTop > 0f || cropBottom > 0f
}

/**
 * Colour adjustments. These map 1:1 onto uniforms of `video_gl_shader` and onto the C++ software
 * path in `video_processor.cpp`, so preview, filter cards and export agree.
 */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,     // -1..1
    val contrast: Float = 0f,       // -1..1 (0 = neutral)
    val saturation: Float = 0f,     // -1..1 (0 = neutral)
    val exposure: Float = 0f,       // -1..1
    val temperature: Float = 0f,    // -1 (cool) .. 1 (warm)
    val tint: Float = 0f,           // -1 (green) .. 1 (magenta)
    val vignette: Float = 0f,       // 0..1
    val sharpen: Float = 0f         // 0..1
) {
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && exposure == 0f &&
            temperature == 0f && tint == 0f && vignette == 0f && sharpen == 0f
}

@Serializable
data class FilterSpec(val id: String = FilterCatalog.NONE, val intensity: Float = 1f) {
    val isActive: Boolean get() = id != FilterCatalog.NONE && intensity > 0.001f
}

/**
 * One effect instance on a clip.
 *
 * The list order on [Clip.effects] is the render order — the engine processes effects strictly in
 * sequence (the "effect graph" is an ordered chain, like a compositing stack). [params] holds the
 * professional controls declared by `EffectCatalog.def(id)`; missing keys fall back to the
 * definition's defaults, so documents written before the engine upgrade load unchanged.
 */
@Serializable
data class EffectSpec(
    val id: String,
    val intensity: Float = 0.5f,
    val params: Map<String, Float> = emptyMap(),
    val blend: BlendMode = BlendMode.NORMAL,
    val enabled: Boolean = true,
    /** Stable identity for keyframe targeting and duplication. */
    val instanceId: String = Ids.newId("fx"),
    /** For `cube_lut`: absolute path of the imported `.cube` file (copied into app storage). */
    val lutPath: String = ""
) {
    /** Effective value of one parameter: keyframed overrides (see `EffectChain`) use [paramAt]. */
    fun param(key: String): Float = params[key] ?: EffectCatalog.paramDef(id, key)?.default ?: 0f
}

@Serializable
data class ChromaKeySpec(
    val enabled: Boolean = false,
    val keyColor: Int = 0xFF00FF00.toInt(),
    val similarity: Float = 0.35f,
    val smoothness: Float = 0.12f,
    val spill: Float = 0.3f
)

@Serializable
data class PipSpec(
    val enabled: Boolean = false,
    val scale: Float = 0.4f,
    val offsetXFrac: Float = 0f,
    val offsetYFrac: Float = 0f,
    val cornerRadiusDp: Float = 12f,
    val borderWidthDp: Float = 0f,
    val borderColor: Int = 0xFFFFFFFF.toInt()
)

@Serializable
data class TextSpec(
    val text: String = "Your text",
    val fontId: String = FontCatalog.DEFAULT,
    val sizeSp: Float = 36f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0x00000000,
    val backgroundPaddingDp: Float = 8f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineHeightMultiplier: Float = 1.15f,
    val align: TextAlign = TextAlign.CENTER,
    /** Normalised position of the text anchor inside the frame. */
    val xFrac: Float = 0.5f,
    val yFrac: Float = 0.78f,
    val rotationDegrees: Float = 0f,
    val maxWidthFrac: Float = 0.86f,
    val shadow: TextShadow = TextShadow(),
    val outline: TextOutline = TextOutline()
)

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
data class TextShadow(
    val enabled: Boolean = true,
    val color: Int = 0xCC000000.toInt(),
    val radius: Float = 8f,
    val dx: Float = 0f,
    val dy: Float = 2f
)

@Serializable
data class TextOutline(
    val enabled: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val width: Float = 4f
)

@Serializable
enum class ClipAnimationType {
    // Declaration order is part of the native contract (`AnimationType` in timeline_renderer.h),
    // so new motions are always appended.
    NONE, FADE, ZOOM_IN, ZOOM_OUT, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
    ROTATE_IN, BLUR_IN, POP, FLICKER,
    SHAKE, WOBBLE, BOUNCE, SPIN_IN, FLIP_IN, GLITCH_IN
}

@Serializable
data class ClipAnimationSpec(
    val type: ClipAnimationType = ClipAnimationType.NONE,
    val durationUs: Long = 500_000L
) {
    val isActive: Boolean get() = type != ClipAnimationType.NONE && durationUs > 0
}

@Serializable
enum class TransitionType {
    // Mirrors `TransitionType` in timeline_renderer.h — append only.
    FADE, SLIDE_LEFT, SLIDE_UP, ZOOM, WIPE, CIRCLE,
    SLIDE_RIGHT, SLIDE_DOWN, GLITCH, BLUR,
    // Phase 3 additions (shader wipe modes 5–8).
    FLASH, WHIP_PAN, LIGHT_LEAK, MASK_WIPE
}

@Serializable
data class TransitionSpec(val type: TransitionType = TransitionType.FADE, val durationUs: Long = 600_000L)

@Serializable
enum class AspectRatio(val label: String, val widthRatio: Int, val heightRatio: Int) {
    RATIO_9_16("9:16", 9, 16),
    RATIO_16_9("16:9", 16, 9),
    RATIO_1_1("1:1", 1, 1),
    RATIO_4_5("4:5", 4, 5),
    RATIO_4_3("4:3", 4, 3),
    RATIO_21_9("21:9", 21, 9);

    val ratio: Float get() = widthRatio.toFloat() / heightRatio.toFloat()
    val portrait: Boolean get() = heightRatio > widthRatio
}

/**
 * Animation state sampled at a point in time. Used by the Compose preview layer and by the export
 * `GlEffect` so both produce the same motion.
 */
data class AnimationFrame(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val blurRadiusPx: Float = 0f,
    /** 1 = fully revealed. Used by wipe/iris transitions. */
    val wipe: Float = 1f
)
