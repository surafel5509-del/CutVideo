// CutVideo native engine — colour science, effect library and LUT generation.
#pragma once

#include "video_processor.h"

namespace cutvideo {

/** RGB colour matrix helpers shared with the GLSL shader. */
struct ColorMatrix {
    float m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    float o[3] = {0, 0, 0};
};

/** Convenience overload for the 3x4 (with offsets) matrices stored in FilterCatalog. */
ColorMatrix blendMatrix3x4(const float* matrix3x4, float intensity);

/** Blends a preset 3x3 matrix + offsets with identity by [intensity]. */
ColorMatrix blendMatrix(const float* matrix3x3, const float* offsets, float intensity);

/** Applies only the colour transform (matrix, adjustments, gamma) to one RGB triple, 0..1. */
void applyColorTransform(float& r, float& g, float& b, const ColorParams& p);

/** Full colour pipeline: blur -> colour transform -> effect list -> sharpen -> vignette -> key -> alpha. */
void applyColorPipeline(Image& img, const ColorParams& p);
void applyColorPipelineWithEffects(Image& img, const ColorParams& p, const EffectSetting* effects, int count);

/** Builds a 16x16x16 RGBA LUT for a colour configuration. outLut must hold 16*16*16*4 bytes. */
void generateLut3d(const ColorParams& p, uint8_t* outLut);

/** Samples a 16^3 LUT with trilinear interpolation, rgb in 0..1. */
void sampleLut3d(const uint8_t* lut, float r, float g, float b, float out[3]);

}  // namespace cutvideo
