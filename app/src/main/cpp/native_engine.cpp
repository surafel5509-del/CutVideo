#include "native_engine.h"

#include <cstdio>
#include <cstring>

namespace cutvideo {

bool NativeVideoEngine::configure(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    width_ = width;
    height_ = height;
    return true;
}

void NativeVideoEngine::setTimeline(const ClipDesc* clips, int count) {
    clips_.clear();
    looks_.clear();
    if (clips && count > 0) {
        clips_.assign(clips, clips + count);
        looks_.resize(static_cast<size_t>(count));
    }
    activeIndex_ = -1;
}

void NativeVideoEngine::setLook(int index, const ColorParams& params, const EffectSetting* effects, int effectCount) {
    if (index < 0 || index >= static_cast<int>(clips_.size())) return;
    ClipLook& look = looks_[static_cast<size_t>(index)];
    look.params = params;
    look.effects.clear();
    if (effects && effectCount > 0) {
        look.effects.assign(effects, effects + effectCount);
    }
}

int NativeVideoEngine::clipCount() const { return static_cast<int>(clips_.size()); }

int NativeVideoEngine::activeVideoIndex(int64_t timelineUs) const {
    return findActiveClip(clips_.data(), static_cast<int>(clips_.size()), TrackType::kVideo, timelineUs);
}

AnimationState NativeVideoEngine::stateAt(int64_t timelineUs) const {
    const int idx = activeVideoIndex(timelineUs);
    if (idx < 0) return AnimationState{0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    return composeState(clips_[static_cast<size_t>(idx)], timelineUs);
}

float NativeVideoEngine::gainAt(int64_t timelineUs) const {
    float total = 0.0f;
    for (const ClipDesc& c : clips_) {
        if (c.trackType == static_cast<int32_t>(TrackType::kVideo) ||
            c.trackType == static_cast<int32_t>(TrackType::kAudio)) {
            if (clipContains(c, timelineUs)) {
                total = std::max(total, gainAtTimeline(c, timelineUs));
            }
        }
    }
    return total;
}

int64_t NativeVideoEngine::sourceTimeAt(int64_t timelineUs) const {
    const int idx = activeVideoIndex(timelineUs);
    if (idx < 0) return 0;
    return sourceTimeUs(clips_[static_cast<size_t>(idx)], timelineUs);
}

int NativeVideoEngine::renderFrame(Image& frame, int64_t timelineUs) {
    if (!frame.valid()) return -1;
    const int idx = activeVideoIndex(timelineUs);
    if (idx < 0) {
        fillImage(frame, 0, 0, 0, 255);
        return -1;
    }

    const ClipDesc& clip = clips_[static_cast<size_t>(idx)];
    ClipLook& look = looks_[static_cast<size_t>(idx)];
    ColorParams params = look.params;
    params.timeSeconds = static_cast<float>(timelineUs) / 1'000'000.0f;
    params.aspect = frame.height > 0
        ? static_cast<float>(frame.width) / static_cast<float>(frame.height)
        : 16.0f / 9.0f;

    const AnimationState state = composeState(clip, timelineUs);
    params.alpha *= state.alpha;
    params.wipe = std::min(params.wipe, state.wipe);
    if (state.blurRadiusPx > 0.01f) {
        params.blurRadiusPx += state.blurRadiusPx;
    }

    applyColorPipelineWithEffects(frame, params, look.effects.data(),
                                  static_cast<int>(look.effects.size()));
    activeIndex_ = idx;
    return idx;
}

std::string NativeVideoEngine::describe() const {
    char buffer[256];
    std::snprintf(buffer, sizeof(buffer),
                  "CutVideoEngine v%s | clips=%d | output=%dx%d | effects=%s",
                  kEngineVersion, static_cast<int>(clips_.size()), width_, height_,
                  kEnableEffects ? "on" : "off");
    return std::string(buffer);
}

void NativeVideoEngine::clear() {
    clips_.clear();
    looks_.clear();
    activeIndex_ = -1;
}

}  // namespace cutvideo
