#include "timeline_renderer.h"

#include <cmath>

namespace cutvideo {

namespace {

inline float easeOutBack(float p) {
    const float c1 = 1.70158f;
    const float c3 = c1 + 1.0f;
    const float x = p - 1.0f;
    return 1.0f + c3 * x * x * x + c1 * x * x;
}

inline float progressOf(int64_t elapsedUs, int64_t windowUs) {
    if (windowUs <= 0) return 1.0f;
    return clamp01(static_cast<float>(elapsedUs) / static_cast<float>(windowUs));
}

AnimationState applyAnimation(AnimationType type, float p, bool entering) {
    AnimationState s;
    const float t = clamp01(p);
    switch (type) {
        case AnimationType::kNone:
            break;
        case AnimationType::kFade:
            s.alpha = t;
            break;
        case AnimationType::kZoomIn:
            s.scale = 0.62f + 0.38f * t;
            s.alpha = std::min(1.0f, t * 1.6f);
            break;
        case AnimationType::kZoomOut:
            s.scale = 1.38f - 0.38f * t;
            s.alpha = std::min(1.0f, t * 1.6f);
            break;
        case AnimationType::kSlideLeft:
            s.translateX = entering ? (1.0f - t) : -(1.0f - t);
            s.alpha = std::min(1.0f, t * 2.0f);
            break;
        case AnimationType::kSlideRight:
            s.translateX = entering ? -(1.0f - t) : (1.0f - t);
            s.alpha = std::min(1.0f, t * 2.0f);
            break;
        case AnimationType::kSlideUp:
            s.translateY = entering ? (1.0f - t) : -(1.0f - t);
            s.alpha = std::min(1.0f, t * 2.0f);
            break;
        case AnimationType::kSlideDown:
            s.translateY = entering ? -(1.0f - t) : (1.0f - t);
            s.alpha = std::min(1.0f, t * 2.0f);
            break;
        case AnimationType::kRotateIn:
            s.rotationDegrees = (1.0f - t) * -28.0f;
            s.scale = 0.78f + 0.22f * t;
            s.alpha = std::min(1.0f, t * 1.8f);
            break;
        case AnimationType::kBlurIn:
            s.blurRadiusPx = (1.0f - t) * 18.0f;
            s.alpha = std::min(1.0f, t * 1.4f);
            break;
        case AnimationType::kPop:
            s.scale = easeOutBack(t);
            s.alpha = std::min(1.0f, t * 3.0f);
            break;
        case AnimationType::kFlicker: {
            const float flick = 0.55f + 0.45f * std::sin(t * 42.0f);
            s.alpha = lerpf(flick, 1.0f, smoothstepf(0.55f, 1.0f, t));
            break;
        }
        case AnimationType::kShake:
            s.alpha = std::min(1.0f, t * 2.5f);
            s.translateX = std::sin(t * 55.0f) * (1.0f - t) * 0.10f;
            s.translateY = std::cos(t * 47.0f) * (1.0f - t) * 0.06f;
            break;
        case AnimationType::kWobble:
            s.alpha = std::min(1.0f, t * 1.8f);
            s.scale = 0.90f + 0.10f * t;
            s.rotationDegrees = std::sin(t * 22.0f) * (1.0f - t) * 14.0f;
            break;
        case AnimationType::kBounce: {
            const float hop = std::fabs(std::sin(t * 12.566f)) * (1.0f - t);
            s.alpha = std::min(1.0f, t * 3.0f);
            s.scale = 1.0f - 0.35f * (1.0f - t) + 0.18f * hop;
            break;
        }
        case AnimationType::kSpinIn:
            s.alpha = std::min(1.0f, t * 1.8f);
            s.scale = 0.60f + 0.40f * t;
            s.rotationDegrees = (1.0f - t) * -180.0f;
            break;
        case AnimationType::kFlipIn:
            s.alpha = std::min(1.0f, t * 1.6f);
            s.scale = std::fabs(std::cos((1.0f - t) * 1.5707964f));
            break;
        case AnimationType::kGlitchIn:
            s.alpha = t;
            s.translateX = std::sin(t * 70.0f) * (1.0f - t) * 0.05f;
            s.blurRadiusPx = (1.0f - t) * 6.0f;
            break;
    }
    return s;
}

}  // namespace

bool clipContains(const ClipDesc& clip, int64_t timelineUs) {
    return timelineUs >= clip.startUs && timelineUs < clip.startUs + clip.durationUs;
}

int64_t clipEndUs(const ClipDesc& clip) { return clip.startUs + clip.durationUs; }

int64_t sourceSpanUs(const ClipDesc& clip) {
    const float speed = clip.speed <= 0.01f ? 1.0f : clip.speed;
    return static_cast<int64_t>(static_cast<double>(clip.durationUs) * speed);
}

int64_t sourceTimeUs(const ClipDesc& clip, int64_t timelineUs) {
    const float speed = clip.speed <= 0.01f ? 1.0f : clip.speed;
    int64_t local = timelineUs - clip.startUs;
    if (local < 0) local = 0;
    if (local > clip.durationUs) local = clip.durationUs;
    const int64_t consumed = static_cast<int64_t>(static_cast<double>(local) * speed);
    if (clip.reversed) {
        const int64_t span = sourceSpanUs(clip);
        return std::max<int64_t>(0, clip.sourceInUs + (span - consumed));
    }
    return clip.sourceInUs + consumed;
}

int findActiveClip(const ClipDesc* clips, int count, TrackType trackType, int64_t timelineUs) {
    if (!clips) return -1;
    for (int i = 0; i < count; ++i) {
        if (clips[i].trackType == static_cast<int32_t>(trackType) && clipContains(clips[i], timelineUs)) {
            return i;
        }
    }
    return -1;
}

AnimationState sampleAnimation(AnimationType type, float progress) {
    return applyAnimation(type, progress, true);
}

AnimationState sampleTransition(TransitionType type, float progress) {
    AnimationState s;
    const float t = clamp01(progress);
    switch (type) {
        case TransitionType::kFade:
            s.alpha = t;
            break;
        case TransitionType::kSlideLeft:
            s.translateX = (1.0f - t);
            s.alpha = 1.0f;
            break;
        case TransitionType::kSlideUp:
            s.translateY = (1.0f - t);
            break;
        case TransitionType::kZoom:
            s.scale = 0.72f + 0.28f * t;
            s.alpha = t;
            break;
        case TransitionType::kWipe:
            s.wipe = t;
            break;
        case TransitionType::kCircle:
            s.wipe = t;
            break;
        case TransitionType::kSlideRight:
            s.translateX = -(1.0f - t);
            break;
        case TransitionType::kSlideDown:
            s.translateY = -(1.0f - t);
            break;
        case TransitionType::kGlitch:
            s.alpha = t;
            s.translateX = std::sin(t * 60.0f) * (1.0f - t) * 0.04f;
            s.wipe = t;
            break;
        case TransitionType::kBlur:
            s.alpha = t;
            s.blurRadiusPx = (1.0f - t) * 20.0f;
            break;
        case TransitionType::kFlash:
            s.wipe = t;
            break;
        case TransitionType::kWhipPan:
            s.wipe = t;
            break;
        case TransitionType::kLightLeak:
            s.wipe = t;
            break;
        case TransitionType::kMaskWipe:
            s.wipe = t;
            break;
    }
    return s;
}

AnimationState composeState(const ClipDesc& clip, int64_t timelineUs) {
    AnimationState state;
    const int64_t local = timelineUs - clip.startUs;

    if (clip.animationInType != 0 && clip.animationInUs > 0 && local < clip.animationInUs) {
        const AnimationState in = applyAnimation(static_cast<AnimationType>(clip.animationInType),
                                                 progressOf(local, clip.animationInUs), true);
        state.alpha *= in.alpha;
        state.scale *= in.scale;
        state.translateX += in.translateX;
        state.translateY += in.translateY;
        state.rotationDegrees += in.rotationDegrees;
        state.blurRadiusPx += in.blurRadiusPx;
    }

    if (clip.animationOutType != 0 && clip.animationOutUs > 0) {
        const int64_t outStart = clip.durationUs - clip.animationOutUs;
        if (local > outStart) {
            const int64_t remaining = clip.durationUs - local;
            const AnimationState out = applyAnimation(static_cast<AnimationType>(clip.animationOutType),
                                                      progressOf(remaining, clip.animationOutUs), true);
            state.alpha *= out.alpha;
            state.scale *= out.scale;
            state.translateX += out.translateX;
            state.translateY += out.translateY;
            state.rotationDegrees += out.rotationDegrees;
            state.blurRadiusPx += out.blurRadiusPx;
        }
    }

    if (clip.transitionInUs > 0 && local < clip.transitionInUs) {
        const AnimationState tr = sampleTransition(static_cast<TransitionType>(clip.transitionInType),
                                                   progressOf(local, clip.transitionInUs));
        state.alpha *= tr.alpha;
        state.scale *= tr.scale;
        state.translateX += tr.translateX;
        state.translateY += tr.translateY;
        state.rotationDegrees += tr.rotationDegrees;
        state.blurRadiusPx += tr.blurRadiusPx;
        state.wipe = std::min(state.wipe, tr.wipe);
    }

    if (clip.transitionOutUs > 0) {
        const int64_t outStart = clip.durationUs - clip.transitionOutUs;
        if (local > outStart) {
            const int64_t remaining = clip.durationUs - local;
            // Reverse sampling: at the very end the clip is fully gone.
            AnimationState tr = sampleTransition(static_cast<TransitionType>(clip.transitionOutType),
                                                 progressOf(remaining, clip.transitionOutUs));
            state.alpha *= tr.alpha;
            state.scale *= tr.scale;
            state.translateX -= tr.translateX;
            state.translateY -= tr.translateY;
            state.rotationDegrees -= tr.rotationDegrees;
            state.blurRadiusPx += tr.blurRadiusPx;
            state.wipe = std::min(state.wipe, tr.wipe);
        }
    }

    state.alpha = clamp01(state.alpha);
    state.wipe = clamp01(state.wipe);
    return state;
}

float gainAtTimeline(const ClipDesc& clip, int64_t timelineUs) {
    if (clip.muted) return 0.0f;
    float gain = clip.volume;
    const int64_t local = timelineUs - clip.startUs;
    if (clip.fadeInUs > 0 && local < clip.fadeInUs) {
        gain *= progressOf(local, clip.fadeInUs);
    }
    if (clip.fadeOutUs > 0) {
        const int64_t outStart = clip.durationUs - clip.fadeOutUs;
        if (local > outStart) {
            gain *= progressOf(clip.durationUs - local, clip.fadeOutUs);
        }
    }
    return clampf(gain, 0.0f, 2.0f);
}

void animationToArray(const AnimationState& s, float* out) {
    if (!out) return;
    out[0] = s.alpha;
    out[1] = s.scale;
    out[2] = s.translateX;
    out[3] = s.translateY;
    out[4] = s.rotationDegrees;
    out[5] = s.blurRadiusPx;
    out[6] = s.wipe;
}

void animationFromArray(const float* in, AnimationState& s) {
    if (!in) return;
    s.alpha = in[0];
    s.scale = in[1];
    s.translateX = in[2];
    s.translateY = in[3];
    s.rotationDegrees = in[4];
    s.blurRadiusPx = in[5];
    s.wipe = in[6];
}

std::vector<GainSegment> buildGainAutomation(const ClipDesc* clips, int count, TrackType trackType) {
    std::vector<GainSegment> segments;
    if (!clips) return segments;
    for (int i = 0; i < count; ++i) {
        const ClipDesc& c = clips[i];
        if (c.trackType != static_cast<int32_t>(trackType)) continue;
        const float target = c.muted ? 0.0f : c.volume;
        const int64_t start = c.startUs;
        const int64_t end = clipEndUs(c);
        if (c.fadeInUs > 0) {
            segments.push_back({start, start + c.fadeInUs, 0.0f, target});
        } else {
            segments.push_back({start, start, target, target});
        }
        const int64_t fadeOutStart = end - c.fadeOutUs;
        if (c.fadeOutUs > 0) {
            segments.push_back({std::max(start, fadeOutStart), end, target, 0.0f});
        } else {
            segments.push_back({end, end, target, target});
        }
    }
    return segments;
}

}  // namespace cutvideo
