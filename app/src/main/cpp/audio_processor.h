// CutVideo native engine — PCM16 audio DSP used by preview and export.
#pragma once

#include <cstdint>
#include "platform.h"

namespace cutvideo {

/** Linear gain segment on the project timeline (microseconds). */
struct GainSegment {
    int64_t startUs = 0;
    int64_t endUs = 0;
    float startGain = 1.0f;
    float endGain = 1.0f;
};

/** Gain of the automation curve at an absolute timeline position. */
float gainAtTime(const GainSegment* segments, int count, int64_t timeUs);

/**
 * Applies a gain automation curve to interleaved PCM16 in place.
 * [startUs] is the timeline position of the first frame in the buffer, which lets the caller feed a
 * whole clip (or a whole concatenated project) at once.
 */
void applyGainAutomation(int16_t* pcm, int frames, int channels, int sampleRate,
                         const GainSegment* segments, int count, int64_t startUs, float masterGain);

/** Simple linear fade helper (used when there is no automation curve). */
void applyFade(int16_t* pcm, int frames, int channels, int sampleRate,
               int64_t fadeInUs, int64_t fadeOutUs, int64_t offsetInClipUs, int64_t clipDurationUs);

/** Sums two PCM16 buffers with gains, soft-clipping the result. Sizes must match. */
void mixPcm16(const int16_t* a, const int16_t* b, int16_t* out, int samples, float gainA, float gainB);

/** Linear resampling between sample rates. Returns the number of output frames. */
int resampleLinear(const int16_t* in, int frames, int channels, int inRate, int outRate,
                   int16_t* out, int outCapacityFrames);

/** Peak envelope for drawing a waveform: `buckets` values in 0..1. */
void computeWaveformPeaks(const int16_t* pcm, int frames, int channels, int buckets, float* outPeaks);

/** RMS level in 0..1, useful for meters and for ducking decisions. */
float rmsLevel(const int16_t* pcm, int samples);

/** Tanh soft clipper to keep summed tracks inside the PCM range. */
void softClip(int16_t* pcm, int samples, float drive);

int64_t framesToUs(int64_t frames, int sampleRate);

}  // namespace cutvideo
