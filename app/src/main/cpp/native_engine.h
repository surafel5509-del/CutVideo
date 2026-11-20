// CutVideo native engine — engine facade shared by preview fallback, filter cards and export audio.
#pragma once

#include <string>
#include <vector>

#include "audio_processor.h"
#include "filter_engine.h"
#include "timeline_renderer.h"
#include "video_processor.h"

namespace cutvideo {

constexpr const char* kEngineVersion = "1.0.0";
constexpr bool kEnableEffects = true;

/** Per-clip look: colour parameters plus the ordered effect stack. */
struct ClipLook {
    ColorParams params;
    std::vector<EffectSetting> effects;
};

/**
 * Owns the native projection of the project document.
 *
 * The Kotlin layer pushes the flattened timeline + per-clip looks once, then calls [renderFrame] for
 * every frame it decodes. That keeps the frame loop free of JSON/per-frame marshalling and gives the
 * native side authority over clip resolution, animation sampling, effects and audio gain.
 */
class NativeVideoEngine {
public:
    bool configure(int width, int height);
    void setTimeline(const ClipDesc* clips, int count);
    void setLook(int index, const ColorParams& params, const EffectSetting* effects, int effectCount);

    int clipCount() const;
    int activeVideoIndex(int64_t timelineUs) const;
    AnimationState stateAt(int64_t timelineUs) const;
    float gainAt(int64_t timelineUs) const;
    int64_t sourceTimeAt(int64_t timelineUs) const;

    /** Applies the active clip's look to a decoded RGBA frame. Returns the clip index or -1. */
    int renderFrame(Image& frame, int64_t timelineUs);

    std::string describe() const;
    void clear();

    int width() const { return width_; }
    int height() const { return height_; }

private:
    std::vector<ClipDesc> clips_;
    std::vector<ClipLook> looks_;
    int width_ = 0;
    int height_ = 0;
    mutable int activeIndex_ = -1;
};

}  // namespace cutvideo
