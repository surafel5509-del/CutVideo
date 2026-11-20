#include "audio_processor.h"

#include <cmath>
#include <cstring>
#include <vector>

namespace cutvideo {

float gainAtTime(const GainSegment* segments, int count, int64_t timeUs) {
    if (!segments || count <= 0) return 1.0f;
    if (timeUs <= segments[0].startUs) return segments[0].startGain;
    for (int i = 0; i < count; ++i) {
        const GainSegment& s = segments[i];
        if (timeUs >= s.startUs && timeUs <= s.endUs) {
            const int64_t span = s.endUs - s.startUs;
            if (span <= 0) return s.endGain;
            const float t = static_cast<float>(timeUs - s.startUs) / static_cast<float>(span);
            return lerpf(s.startGain, s.endGain, t);
        }
    }
    return segments[count - 1].endGain;
}

void applyGainAutomation(int16_t* pcm, int frames, int channels, int sampleRate,
                         const GainSegment* segments, int count, int64_t startUs, float masterGain) {
    if (!pcm || frames <= 0 || channels <= 0 || sampleRate <= 0) return;
    const int64_t usPerFrame = 1'000'000LL / sampleRate;
    for (int f = 0; f < frames; ++f) {
        const int64_t t = startUs + static_cast<int64_t>(f) * usPerFrame;
        const float g = gainAtTime(segments, count, t) * masterGain;
        for (int c = 0; c < channels; ++c) {
            const int idx = f * channels + c;
            pcm[idx] = static_cast<int16_t>(clampf(pcm[idx] * g, -32768.0f, 32767.0f));
        }
    }
}

void applyFade(int16_t* pcm, int frames, int channels, int sampleRate,
               int64_t fadeInUs, int64_t fadeOutUs, int64_t offsetInClipUs, int64_t clipDurationUs) {
    if (!pcm || frames <= 0 || channels <= 0 || sampleRate <= 0) return;
    const int64_t usPerFrame = 1'000'000LL / sampleRate;
    for (int f = 0; f < frames; ++f) {
        const int64_t local = offsetInClipUs + static_cast<int64_t>(f) * usPerFrame;
        float g = 1.0f;
        if (fadeInUs > 0 && local < fadeInUs) {
            g *= static_cast<float>(local) / static_cast<float>(fadeInUs);
        }
        const int64_t fadeOutStart = clipDurationUs - fadeOutUs;
        if (fadeOutUs > 0 && local > fadeOutStart) {
            g *= clamp01(static_cast<float>(clipDurationUs - local) / static_cast<float>(fadeOutUs));
        }
        for (int c = 0; c < channels; ++c) {
            const int idx = f * channels + c;
            pcm[idx] = static_cast<int16_t>(clampf(pcm[idx] * g, -32768.0f, 32767.0f));
        }
    }
}

void mixPcm16(const int16_t* a, const int16_t* b, int16_t* out, int samples, float gainA, float gainB) {
    if (!out || samples <= 0) return;
    for (int i = 0; i < samples; ++i) {
        float va = a ? a[i] * gainA : 0.0f;
        float vb = b ? b[i] * gainB : 0.0f;
        // Soft knee instead of hard clipping keeps mixes musical at high gain.
        float sum = va + vb;
        if (sum > 32767.0f || sum < -32768.0f) {
            sum = 32767.0f * std::tanh(sum / 32767.0f);
        }
        out[i] = static_cast<int16_t>(clampf(sum, -32768.0f, 32767.0f));
    }
}

int resampleLinear(const int16_t* in, int frames, int channels, int inRate, int outRate,
                   int16_t* out, int outCapacityFrames) {
    if (!in || !out || frames <= 0 || channels <= 0 || inRate <= 0 || outRate <= 0) return 0;
    const double ratio = static_cast<double>(inRate) / static_cast<double>(outRate);
    const int outFrames = std::min(outCapacityFrames,
                                   static_cast<int>(frames / ratio) + 1);
    for (int i = 0; i < outFrames; ++i) {
        const double srcPos = i * ratio;
        const int i0 = std::min(frames - 1, static_cast<int>(srcPos));
        const int i1 = std::min(frames - 1, i0 + 1);
        const float t = static_cast<float>(srcPos - i0);
        for (int c = 0; c < channels; ++c) {
            const float s0 = in[i0 * channels + c];
            const float s1 = in[i1 * channels + c];
            out[i * channels + c] = static_cast<int16_t>(clampf(lerpf(s0, s1, t), -32768.0f, 32767.0f));
        }
    }
    return outFrames;
}

void computeWaveformPeaks(const int16_t* pcm, int frames, int channels, int buckets, float* outPeaks) {
    if (!pcm || !outPeaks || frames <= 0 || buckets <= 0 || channels <= 0) return;
    const int framesPerBucket = std::max(1, frames / buckets);
    for (int b = 0; b < buckets; ++b) {
        const int startFrame = b * framesPerBucket;
        const int endFrame = std::min(frames, startFrame + framesPerBucket);
        float peak = 0.0f;
        for (int f = startFrame; f < endFrame; ++f) {
            for (int c = 0; c < channels; ++c) {
                const float v = std::fabs(pcm[f * channels + c]) / 32768.0f;
                if (v > peak) peak = v;
            }
        }
        outPeaks[b] = peak;
    }
}

float rmsLevel(const int16_t* pcm, int samples) {
    if (!pcm || samples <= 0) return 0.0f;
    double acc = 0.0;
    for (int i = 0; i < samples; ++i) {
        const double v = pcm[i] / 32768.0;
        acc += v * v;
    }
    return static_cast<float>(std::sqrt(acc / samples));
}

void softClip(int16_t* pcm, int samples, float drive) {
    if (!pcm || samples <= 0) return;
    const float d = std::max(1.0f, drive);
    const float norm = 32767.0f;
    for (int i = 0; i < samples; ++i) {
        const float v = pcm[i] / norm;
        const float shaped = std::tanh(v * d) / std::tanh(d);
        pcm[i] = static_cast<int16_t>(clampf(shaped * norm, -32768.0f, 32767.0f));
    }
}

int64_t framesToUs(int64_t frames, int sampleRate) {
    if (sampleRate <= 0) return 0;
    return frames * 1'000'000LL / sampleRate;
}

}  // namespace cutvideo
