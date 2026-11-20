// CutVideo native engine — shared platform helpers.
#pragma once

#include <cstdint>
#include <cmath>
#include <algorithm>

#ifdef __ANDROID__
#include <android/log.h>
#define CV_TAG "CutVideoNative"
#define CV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, CV_TAG, __VA_ARGS__)
#define CV_LOGW(...) __android_log_print(ANDROID_LOG_WARN, CV_TAG, __VA_ARGS__)
#define CV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CV_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define CV_LOGI(...) do { std::printf("[I] "); std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define CV_LOGW(...) do { std::printf("[W] "); std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#define CV_LOGE(...) do { std::printf("[E] "); std::printf(__VA_ARGS__); std::printf("\n"); } while (0)
#endif

namespace cutvideo {

constexpr float kPi = 3.14159265358979f;

inline uint8_t clamp8(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }

inline float smoothstepf(float edge0, float edge1, float x) {
    if (edge1 <= edge0) return x < edge0 ? 0.0f : 1.0f;
    float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.0f - 2.0f * t);
}

/** Deterministic 1D hash in [0,1) — used for grain and particle effects. */
inline float hash11(float p) {
    p = std::fmod(p * 0.1031f, 1.0f);
    p *= p + 33.33f;
    p *= p + p;
    return std::fmod(p, 1.0f);
}

/** Deterministic 2D hash in [0,1). */
inline float hash21(float x, float y) {
    float p = x * 127.1f + y * 311.7f;
    return hash11(p);
}

inline float luma(float r, float g, float b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

}  // namespace cutvideo
