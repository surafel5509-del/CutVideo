// CutVideo native engine — RGBA image primitives and the geometry/colour pipeline.
#pragma once

#include <cstdint>
#include <vector>
#include "platform.h"

namespace cutvideo {

/** Non-owning RGBA8888 (or RGBA with alpha) image view. */
struct Image {
    uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    int stride = 0;  // bytes per row

    Image() = default;
    Image(uint8_t* d, int w, int h, int s) : data(d), width(w), height(h), stride(s) {}
    Image(uint8_t* d, int w, int h) : data(d), width(w), height(h), stride(w * 4) {}

    bool valid() const { return data != nullptr && width > 0 && height > 0 && stride >= width * 4; }
    uint8_t* row(int y) const { return data + static_cast<size_t>(y) * static_cast<size_t>(stride); }
};

/** Effect identifiers. Order must match `EffectsCatalog` / `NativeEffectIds` in Kotlin. */
enum class EffectId : int32_t {
    kNone = 0,
    kGrain = 1,
    kVignette = 2,
    kScanlines = 3,
    kGlitch = 4,
    kChromaAberration = 5,
    kBloom = 6,
    kLightLeak = 7,
    kPulse = 8,
    kMirror = 9,
    kPosterize = 10,
    kDuotone = 11,
    kSharpen = 12,
    kVhs = 13,
    kSnow = 14,
    kZoomBlur = 15,
    // Appended in catalogue order — ids are part of the Kotlin/native contract
    // (`NativeEffectIds.order`), so new effects always go at the end.
    kBlur = 16,
    kPixelate = 17,
    kNeon = 18,
    kHalftone = 19,
    kTiltShift = 20,
    kOldFilm = 21,
    kHeatWave = 22,
    kSpotlight = 23
};

struct EffectSetting {
    EffectId id = EffectId::kNone;
    float intensity = 0.5f;
};

/**
 * Everything the colour/effect pipeline needs for one frame. Field order and semantics are shared
 * with the GLSL shader in `assets/shaders/cutvideo_effect.glsl` and with the Kotlin
 * `FilterChain` builder — keep the three in sync.
 */
struct ColorParams {
    float filterMatrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // row-major 3x3
    float filterOffset[3] = {0, 0, 0};
    float filterIntensity = 0.0f;  // 0 = off, 1 = full preset
    float gamma = 1.0f;

    float brightness = 0.0f;   // -1..1
    float contrast = 0.0f;     // -1..1
    float saturation = 0.0f;   // -1..1
    float exposure = 0.0f;     // -1..1 (stops)
    float temperature = 0.0f;  // -1..1
    float tint = 0.0f;         // -1..1
    float vignette = 0.0f;     // 0..1
    float sharpen = 0.0f;      // 0..1
    float blurRadiusPx = 0.0f;

    bool chromaEnabled = false;
    float chromaKey[3] = {0.0f, 1.0f, 0.0f};
    float chromaSimilarity = 0.35f;
    float chromaSmoothness = 0.12f;
    float chromaSpill = 0.30f;

    float alpha = 1.0f;         // fade / transition opacity
    float wipe = 1.0f;          // 1 = fully revealed; used by wipe/iris transitions
    int32_t wipeMode = 0;       // 0 = horizontal sweep, 1 = circular iris
    float timeSeconds = 0.0f;   // drives animated effects
    float aspect = 16.0f / 9.0f;
    float duotoneShadow[3] = {0.06f, 0.09f, 0.16f};
    float duotoneHighlight[3] = {0.96f, 0.83f, 1.0f};
};

// --- primitives ---------------------------------------------------------------------------------
void fillImage(Image& img, uint8_t r, uint8_t g, uint8_t b, uint8_t a);
void copyImage(const Image& src, Image& dst);
/** Rotates `src` into `dst`; dst must be src.height wide and src.width tall. */
void rotate90(const Image& src, Image& dst, bool clockwise);
void flipHorizontal(Image& img);
void flipVertical(Image& img);
/** Crops the rect (x,y,w,h) of `src` into `dst`; coordinates are clamped to the source. */
void cropImage(const Image& src, Image& dst, int x, int y, int w, int h);
/** Bilinear resample. */
void scaleBilinear(const Image& src, Image& dst);
/** Fast box-ish resize used for thumbnails (area average, good for large downscales). */
void scaleAreaAverage(const Image& src, Image& dst);

// --- pixel operations ---------------------------------------------------------------------------
void applyBlur(Image& img, float radiusPx);
void applySharpen(Image& img, float amount);
void applyVignette(Image& img, float amount);
void applyGrain(Image& img, float amount, float time);
void applyChromaKey(Image& img, const ColorParams& p);
void applyEffect(Image& img, EffectId id, float intensity, float time, float aspect);

/** Rec.709 grayscale helper used by several effects. */
float luminanceOf(uint8_t r, uint8_t g, uint8_t b);

}  // namespace cutvideo
