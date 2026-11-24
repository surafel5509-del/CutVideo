package com.cutvideo.editor.domain.model

import kotlinx.serialization.Serializable

/**
 * Keyframe animation model.
 *
 * A [KeyframeTrack] animates one numeric property of a clip over time. Tracks live on the clip
 * (`Clip.keyframes`), so they are persisted with the project document and flow to both renderers:
 * the preview (Compose transforms + the GL look uniforms) and the export (the same
 * `CutVideoGlEffect`), keeping the two consistent.
 *
 * Times are microseconds on the clip's *local* timeline (0 = clip start).
 */
@Serializable
enum class KeyframeProperty(val label: String, val defaultValue: Float, val minValue: Float, val maxValue: Float) {
    POSITION_X("Position X", 0f, -1f, 1f),
    POSITION_Y("Position Y", 0f, -1f, 1f),
    SCALE("Scale", 1f, 0.1f, 4f),
    ROTATION("Rotation", 0f, -360f, 360f),
    OPACITY("Opacity", 1f, 0f, 1f),
    VOLUME("Volume", 1f, 0f, 2f),
    BLUR("Blur", 0f, 0f, 60f),
    GLOW("Glow", 0f, 0f, 1f),
    EFFECT_INTENSITY("Effect intensity", 0.5f, 0f, 1f),
    /** Generic effect-parameter track: the target parameter is [KeyframeTrack.param]. */
    EFFECT_PARAM("Effect parameter", 0.5f, -1000f, 1000f),
    CROP_LEFT("Crop left", 0f, 0f, 0.95f),
    CROP_RIGHT("Crop right", 0f, 0f, 0.95f),
    CROP_TOP("Crop top", 0f, 0f, 0.95f),
    CROP_BOTTOM("Crop bottom", 0f, 0f, 0.95f),
    MASK_X("Mask X", 0.5f, -0.5f, 1.5f),
    MASK_Y("Mask Y", 0.5f, -0.5f, 1.5f),
    MASK_SCALE("Mask scale", 0.4f, 0.02f, 2f),
    MASK_ROTATION("Mask rotation", 0f, -360f, 360f),
    MASK_FEATHER("Mask feather", 0.15f, 0f, 1f),
    MASK_OPACITY("Mask opacity", 1f, 0f, 1f),
    MASK_EXPANSION("Mask expansion", 0f, -1f, 1f),
    SPEED("Speed", 1f, 0.25f, 4f);

    val isMask: Boolean get() = name.startsWith("MASK_")
}

@Serializable
enum class KeyframeInterpolation(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease in"),
    EASE_OUT("Ease out"),
    EASE_IN_OUT("Ease in-out"),
    HOLD("Hold"),
    BEZIER("Bézier");

    val hasHandles: Boolean get() = this == BEZIER
}

/**
 * One keyframe: a value at a clip-local time plus the interpolation used to reach the *next*
 * keyframe. Bézier handles are normalised (0..1) control points like CSS `cubic-bezier`.
 */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
    val bezierInX: Float = 0.42f,
    val bezierInY: Float = 0f,
    val bezierOutX: Float = 0.58f,
    val bezierOutY: Float = 1f
)

/** All keyframes for one property of one clip. Kept sorted by time. */
@Serializable
data class KeyframeTrack(
    val property: KeyframeProperty,
    val keyframes: List<Keyframe> = emptyList(),
    /**
     * For effect tracks: the `EffectSpec.instanceId` this track animates. Empty targets the clip
     * itself (transform, volume, …) or — for [KeyframeProperty.EFFECT_INTENSITY] — every effect.
     */
    val targetId: String = "",
    /** For [KeyframeProperty.EFFECT_PARAM]: which parameter of the effect this track animates. */
    val param: String = ""
) {
    val sorted: List<Keyframe> get() = keyframes.sortedBy { it.timeUs }
    val isEmpty: Boolean get() = keyframes.isEmpty()
    val isNotEmpty: Boolean get() = keyframes.isNotEmpty()
}

/**
 * A mask applied to the clip's rendered image. The mask actually multiplies the frame's alpha in
 * the GLSL pipeline (see `cutvideo_effect.glsl`), so it affects preview *and* export — it is not a
 * decorative UI shape.
 */
@Serializable
enum class MaskType(val label: String) {
    CIRCLE("Circle"),
    RECT("Rectangle"),
    LINEAR("Linear"),
    MIRROR("Mirror"),
    FREEHAND("Freehand")
}

/** One point of a freehand mask stroke, normalised to the frame (0..1). */
@Serializable
data class MaskPoint(val xFrac: Float, val yFrac: Float)

@Serializable
data class MaskSpec(
    val id: String = Ids.mask(),
    val type: MaskType = MaskType.CIRCLE,
    val enabled: Boolean = true,
    /** Centre of the mask in frame fractions (0..1). */
    val xFrac: Float = 0.5f,
    val yFrac: Float = 0.5f,
    /** Size of the mask relative to the frame's short side (circle radius / rect half-size). */
    val scale: Float = 0.35f,
    val rotationDegrees: Float = 0f,
    /** Edge softness 0..1. */
    val feather: Float = 0.15f,
    /** Overall mask strength 0..1 (multiplies the coverage). */
    val opacity: Float = 1f,
    /** Grow (>0) or shrink (<0) the mask, in fractions of [scale]. */
    val expansion: Float = 0f,
    /** Keep the outside instead of the inside. */
    val invert: Boolean = false,
    /** Corner roundness for [MaskType.RECT] (0 = sharp, 1 = ellipse). */
    val roundness: Float = 0.2f,
    /** Strokes for [MaskType.FREEHAND]; the mask keeps the union of painted areas. */
    val strokes: List<List<MaskPoint>> = emptyList()
)

/**
 * Snapshot of a clip's editable state at import (or creation) time. The Revert tool restores from
 * this without touching the imported media file.
 */
@Serializable
data class ClipOriginal(
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transform: Transform = Transform(),
    val adjust: Adjustments = Adjustments(),
    val filter: FilterSpec = FilterSpec(),
    val effects: List<EffectSpec> = emptyList(),
    val blur: Float = 0f,
    val chroma: ChromaKeySpec = ChromaKeySpec(),
    val masks: List<MaskSpec> = emptyList(),
    val keyframes: List<KeyframeTrack> = emptyList()
) {
    companion object {
        fun of(clip: Clip): ClipOriginal = ClipOriginal(
            speed = clip.speed,
            volume = clip.volume,
            muted = clip.muted,
            fadeInUs = clip.fadeInUs,
            fadeOutUs = clip.fadeOutUs,
            transform = clip.transform,
            adjust = clip.adjust,
            filter = clip.filter,
            effects = clip.effects,
            blur = clip.blur,
            chroma = clip.chroma,
            masks = clip.masks,
            keyframes = clip.keyframes
        )
    }
}

/** Groups the Revert tool can restore independently. */
@Serializable
enum class RevertGroup(val label: String) {
    TRANSFORM("Transform"),
    COLOR("Colour & filters"),
    EFFECTS("Effects & blur"),
    MASKS("Masks"),
    KEYFRAMES("Keyframes"),
    AUDIO("Audio"),
    SPEED("Speed"),
    CHROMA("Chroma key")
}
