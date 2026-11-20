// CutVideo native engine — timeline resolution and animation sampling.
//
// This mirrors the read-only half of `TimelineManager.kt`: given a frame's timeline position it
// resolves which clip is visible, the source time to decode, the composed animation transform and the
// audio gain. Preview and export both use these rules.
#pragma once

#include <cstdint>
#include <vector>
#include "audio_processor.h"
#include "filter_engine.h"
#include "video_processor.h"

namespace cutvideo {

enum class TrackType : int32_t { kVideo = 0, kAudio = 1, kText = 2, kOverlay = 3 };

/** Must match `ClipAnimationType` declaration order in Kotlin. */
enum class AnimationType : int32_t {
    kNone = 0, kFade, kZoomIn, kZoomOut, kSlideLeft, kSlideRight, kSlideUp, kSlideDown,
    kRotateIn, kBlurIn, kPop, kFlicker
};

/** Must match `TransitionType` declaration order in Kotlin. */
enum class TransitionType : int32_t { kFade = 0, kSlideLeft, kSlideUp, kZoom, kWipe, kCircle };

struct ClipDesc {
    int32_t trackType = 0;
    int64_t startUs = 0;
    int64_t durationUs = 0;
    int64_t sourceInUs = 0;
    int64_t sourceDurationUs = 0;
    float speed = 1.0f;
    float volume = 1.0f;
    int32_t muted = 0;
    int32_t reversed = 0;
    int64_t fadeInUs = 0;
    int64_t fadeOutUs = 0;
    int32_t animationInType = 0;
    int64_t animationInUs = 0;
    int32_t animationOutType = 0;
    int64_t animationOutUs = 0;
    int32_t transitionInType = 0;
    int64_t transitionInUs = 0;
    int32_t transitionOutType = 0;
    int64_t transitionOutUs = 0;
};

struct AnimationState {
    float alpha = 1.0f;
    float scale = 1.0f;
    float translateX = 0.0f;  // fraction of frame width
    float translateY = 0.0f;  // fraction of frame height
    float rotationDegrees = 0.0f;
    float blurRadiusPx = 0.0f;
    float wipe = 1.0f;        // 1 = fully revealed (used by wipe/iris transitions)
};

/** Number of floats in the array returned by `animationToArray` / `transitionToArray`. */
constexpr int kStateFloatCount = 7;

bool clipContains(const ClipDesc& clip, int64_t timelineUs);
int64_t sourceTimeUs(const ClipDesc& clip, int64_t timelineUs);
int64_t clipEndUs(const ClipDesc& clip);
int64_t sourceSpanUs(const ClipDesc& clip);

/** Index of the clip visible on [trackType] at [timelineUs], or -1. */
int findActiveClip(const ClipDesc* clips, int count, TrackType trackType, int64_t timelineUs);

/** Samples an in/out animation. [progress] is 0 (start) .. 1 (settled). */
AnimationState sampleAnimation(AnimationType type, float progress);
/** Samples a transition. [progress] is 0 (not started) .. 1 (complete). */
AnimationState sampleTransition(TransitionType type, float progress);

/** Composes in animation, out animation and transitions for a clip at a timeline position. */
AnimationState composeState(const ClipDesc& clip, int64_t timelineUs);

/** Gain for a clip at a timeline position, including fades and mute. */
float gainAtTimeline(const ClipDesc& clip, int64_t timelineUs);

void animationToArray(const AnimationState& s, float* out);
void animationFromArray(const float* in, AnimationState& s);

/** Builds gain automation points for a whole timeline (used by export audio processing). */
std::vector<GainSegment> buildGainAutomation(const ClipDesc* clips, int count, TrackType trackType);

}  // namespace cutvideo
