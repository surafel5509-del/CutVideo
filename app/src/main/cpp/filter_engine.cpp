#include "filter_engine.h"

#include <cmath>
#include <cstring>
#include <vector>

namespace cutvideo {

ColorMatrix blendMatrix3x4(const float* matrix3x4, float intensity) {
    float m[9];
    for (int i = 0; i < 9; ++i) m[i] = matrix3x4[i];
    // 3x4 layout: row-major with the translation in the fourth column (indices 3, 7, 11).
    float o[3] = {matrix3x4[3], matrix3x4[7], matrix3x4[11]};
    return blendMatrix(m, o, intensity);
}

ColorMatrix blendMatrix(const float* m, const float* offsets, float intensity) {
    ColorMatrix out;
    const float t = clamp01(intensity);
    const float identity[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (int i = 0; i < 9; ++i) out.m[i] = lerpf(identity[i], m[i], t);
    for (int i = 0; i < 3; ++i) out.o[i] = offsets ? offsets[i] * t : 0.0f;
    return out;
}

void applyColorTransform(float& r, float& g, float& b, const ColorParams& p) {
    // 1. Preset colour matrix (already blended with identity by filterIntensity).
    if (p.filterIntensity > 0.0001f) {
        const ColorMatrix cm = blendMatrix(p.filterMatrix, p.filterOffset, p.filterIntensity);
        const float nr = cm.m[0] * r + cm.m[1] * g + cm.m[2] * b + cm.o[0];
        const float ng = cm.m[3] * r + cm.m[4] * g + cm.m[5] * b + cm.o[1];
        const float nb = cm.m[6] * r + cm.m[7] * g + cm.m[8] * b + cm.o[2];
        r = nr; g = ng; b = nb;
    }

    // 2. Exposure in stops.
    if (std::fabs(p.exposure) > 0.0001f) {
        const float gain = std::pow(2.0f, p.exposure);
        r *= gain; g *= gain; b *= gain;
    }

    // 3. Brightness (additive, scaled so +/-1 is a strong but usable shift).
    if (std::fabs(p.brightness) > 0.0001f) {
        const float add = p.brightness * 0.5f;
        r += add; g += add; b += add;
    }

    // 4. Contrast around mid grey.
    if (std::fabs(p.contrast) > 0.0001f) {
        const float c = 1.0f + p.contrast;
        r = (r - 0.5f) * c + 0.5f;
        g = (g - 0.5f) * c + 0.5f;
        b = (b - 0.5f) * c + 0.5f;
    }

    // 5. Saturation around Rec.709 luma.
    if (std::fabs(p.saturation) > 0.0001f) {
        const float l = luma(r, g, b);
        const float s = 1.0f + p.saturation;
        r = lerpf(l, r, s);
        g = lerpf(l, g, s);
        b = lerpf(l, b, s);
    }

    // 6. White balance.
    if (std::fabs(p.temperature) > 0.0001f) {
        r += p.temperature * 0.10f;
        b -= p.temperature * 0.10f;
    }
    if (std::fabs(p.tint) > 0.0001f) {
        g -= p.tint * 0.07f;
        r += p.tint * 0.02f;
        b += p.tint * 0.02f;
    }

    // 7. Gamma.
    if (std::fabs(p.gamma - 1.0f) > 0.0001f) {
        const float inv = 1.0f / std::max(0.05f, p.gamma);
        r = std::pow(clamp01(r), inv);
        g = std::pow(clamp01(g), inv);
        b = std::pow(clamp01(b), inv);
    }

    r = clamp01(r);
    g = clamp01(g);
    b = clamp01(b);
}

namespace {

inline void shiftChannel(Image& img, int channel, int dx) {
    if (dx == 0) return;
    const int w = img.width, h = img.height;
    std::vector<uint8_t> src(static_cast<size_t>(img.stride) * h);
    Image srcView(src.data(), w, h, img.stride);
    copyImage(img, srcView);
    for (int y = 0; y < h; ++y) {
        uint8_t* out = img.row(y);
        const uint8_t* in = src.data() + static_cast<size_t>(y) * img.stride;
        for (int x = 0; x < w; ++x) {
            int sx = std::max(0, std::min(w - 1, x - dx));
            out[x * 4 + channel] = in[sx * 4 + channel];
        }
    }
}

}  // namespace

void applyEffect(Image& img, EffectId id, float intensity, float time, float aspect) {
    if (!img.valid()) return;
    const float t = clamp01(intensity);
    if (t <= 0.001f || id == EffectId::kNone) return;
    const int w = img.width, h = img.height;

    switch (id) {
        case EffectId::kNone:
            break;

        case EffectId::kGrain:
            applyGrain(img, t * 0.85f, time);
            break;

        case EffectId::kVignette:
            applyVignette(img, t);
            break;

        case EffectId::kScanlines: {
            const float dark = 1.0f - 0.35f * t;
            for (int y = 0; y < h; ++y) {
                float f = (y % 3 == 0) ? dark : 1.0f;
                uint8_t* p = img.row(y);
                for (int x = 0; x < w; ++x) {
                    p[x * 4 + 0] = clamp8(p[x * 4 + 0] * f);
                    p[x * 4 + 1] = clamp8(p[x * 4 + 1] * f * (1.0f + 0.05f * t));
                    p[x * 4 + 2] = clamp8(p[x * 4 + 2] * f);
                }
            }
            break;
        }

        case EffectId::kGlitch: {
            const float phase = std::floor(time * 9.0f);
            shiftChannel(img, 0, static_cast<int>(t * 6.0f));
            shiftChannel(img, 2, -static_cast<int>(t * 6.0f));
            for (int y = 0; y < h; ++y) {
                float rnd = hash11(static_cast<float>(y) * 0.37f + phase * 1.7f);
                if (rnd > 0.86f) {
                    int shift = static_cast<int>((rnd - 0.86f) * 6.0f * t * 40.0f);
                    uint8_t* p = img.row(y);
                    std::vector<uint8_t> rowCopy(p, p + w * 4);
                    for (int x = 0; x < w; ++x) {
                        int sx = (x + shift) % w;
                        if (sx < 0) sx += w;
                        std::memcpy(p + x * 4, rowCopy.data() + sx * 4, 4);
                    }
                }
            }
            break;
        }

        case EffectId::kChromaAberration: {
            const int px = static_cast<int>(t * 5.0f);
            shiftChannel(img, 0, px);
            shiftChannel(img, 2, -px);
            break;
        }

        case EffectId::kBloom: {
            std::vector<uint8_t> layer(static_cast<size_t>(img.stride) * h);
            Image glow(layer.data(), w, h, img.stride);
            copyImage(img, glow);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = glow.row(y);
                for (int x = 0; x < w; ++x) {
                    float r = p[x * 4 + 0] / 255.0f, g = p[x * 4 + 1] / 255.0f, b = p[x * 4 + 2] / 255.0f;
                    float l = luma(r, g, b);
                    float bright = smoothstepf(0.55f, 1.0f, l);
                    p[x * 4 + 0] = clamp8(r * bright * 255.0f);
                    p[x * 4 + 1] = clamp8(g * bright * 255.0f);
                    p[x * 4 + 2] = clamp8(b * bright * 255.0f);
                }
            }
            applyBlur(glow, 6.0f + 10.0f * t);
            const float amount = t * 1.1f;
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                const uint8_t* g = glow.row(y);
                for (int x = 0; x < w; ++x) {
                    for (int c = 0; c < 3; ++c) {
                        p[x * 4 + c] = clamp8(p[x * 4 + c] + g[x * 4 + c] * amount);
                    }
                }
            }
            break;
        }

        case EffectId::kLightLeak: {
            const float cx = 0.15f + 0.7f * (0.5f + 0.5f * std::sin(time * 0.6f));
            const float cy = 0.85f - 0.2f * std::sin(time * 0.43f);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                float ny = static_cast<float>(y) / std::max(1, h - 1);
                for (int x = 0; x < w; ++x) {
                    float nx = static_cast<float>(x) / std::max(1, w - 1);
                    float dx = (nx - cx) * aspect;
                    float dy = ny - cy;
                    float d = std::sqrt(dx * dx + dy * dy);
                    float leak = (1.0f - smoothstepf(0.0f, 0.9f, d)) * t * 0.85f;
                    p[x * 4 + 0] = clamp8(p[x * 4 + 0] + leak * 255.0f * 0.95f);
                    p[x * 4 + 1] = clamp8(p[x * 4 + 1] + leak * 255.0f * 0.55f);
                    p[x * 4 + 2] = clamp8(p[x * 4 + 2] + leak * 255.0f * 0.18f);
                }
            }
            break;
        }

        case EffectId::kPulse: {
            const float amp = 1.0f + 0.12f * t * std::sin(time * 6.0f);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                for (int x = 0; x < w; ++x) {
                    for (int c = 0; c < 3; ++c) p[x * 4 + c] = clamp8(p[x * 4 + c] * amp);
                }
            }
            break;
        }

        case EffectId::kMirror: {
            std::vector<uint8_t> src(static_cast<size_t>(img.stride) * h);
            Image srcView(src.data(), w, h, img.stride);
            copyImage(img, srcView);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                const uint8_t* s = src.data() + static_cast<size_t>(y) * img.stride;
                for (int x = 0; x < w; ++x) {
                    if (x < w / 2) continue;
                    int mx = (w - 1) - x;
                    for (int c = 0; c < 4; ++c) {
                        p[x * 4 + c] = clamp8(lerpf(p[x * 4 + c], s[mx * 4 + c], t));
                    }
                }
            }
            break;
        }

        case EffectId::kPosterize: {
            const float levels = std::max(2.0f, 2.0f + (1.0f - t) * 10.0f);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                for (int x = 0; x < w * 4; x += 4) {
                    for (int c = 0; c < 3; ++c) {
                        float v = p[x + c] / 255.0f;
                        float q = std::floor(v * levels) / (levels - 1.0f);
                        p[x + c] = clamp8(clamp01(q) * 255.0f);
                    }
                }
            }
            break;
        }

        case EffectId::kDuotone: {
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                for (int x = 0; x < w * 4; x += 4) {
                    float l = luma(p[x] / 255.0f, p[x + 1] / 255.0f, p[x + 2] / 255.0f);
                    // keep some original colour at low intensity
                    float dr = lerpf(0.06f, 0.96f, l);
                    float dg = lerpf(0.09f, 0.83f, l);
                    float db = lerpf(0.16f, 1.00f, l);
                    p[x + 0] = clamp8(lerpf(p[x + 0] / 255.0f, dr, t) * 255.0f);
                    p[x + 1] = clamp8(lerpf(p[x + 1] / 255.0f, dg, t) * 255.0f);
                    p[x + 2] = clamp8(lerpf(p[x + 2] / 255.0f, db, t) * 255.0f);
                }
            }
            break;
        }

        case EffectId::kSharpen:
            applySharpen(img, t);
            break;

        case EffectId::kVhs: {
            const float phase = std::floor(time * 12.0f);
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                int jitter = static_cast<int>((hash11(static_cast<float>(y) * 0.21f + phase) - 0.5f) * 8.0f * t);
                if (jitter != 0) {
                    std::vector<uint8_t> rowCopy(p, p + w * 4);
                    for (int x = 0; x < w; ++x) {
                        int sx = std::max(0, std::min(w - 1, x + jitter));
                        std::memcpy(p + x * 4, rowCopy.data() + sx * 4, 4);
                    }
                }
                if (y % 2 == 0) {
                    for (int x = 0; x < w; ++x) {
                        for (int c = 0; c < 3; ++c) p[x * 4 + c] = clamp8(p[x * 4 + c] * (1.0f - 0.18f * t));
                    }
                }
            }
            // Tape noise.
            applyGrain(img, t * 0.5f, time);
            // Slight desaturation.
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                for (int x = 0; x < w; ++x) {
                    float r = p[x * 4] / 255.0f, g = p[x * 4 + 1] / 255.0f, b = p[x * 4 + 2] / 255.0f;
                    float l = luma(r, g, b);
                    p[x * 4 + 0] = clamp8(lerpf(r, l, 0.25f * t) * 255.0f);
                    p[x * 4 + 1] = clamp8(lerpf(g, l, 0.25f * t) * 255.0f);
                    p[x * 4 + 2] = clamp8(lerpf(b, l, 0.25f * t) * 255.0f);
                }
            }
            break;
        }

        case EffectId::kSnow: {
            const int count = static_cast<int>(60 + 400 * t);
            for (int i = 0; i < count; ++i) {
                float seed = static_cast<float>(i);
                float sx = hash11(seed * 1.37f) * w;
                float speed = 12.0f + hash11(seed * 2.11f) * 40.0f;
                float sy = std::fmod(hash11(seed * 3.71f) * h + time * speed, static_cast<float>(h));
                int px = static_cast<int>(sx);
                int py = static_cast<int>(sy);
                if (px < 0 || px >= w || py < 0 || py >= h) continue;
                uint8_t* p = img.row(py) + px * 4;
                const float alpha = 0.55f * t + 0.25f;
                for (int c = 0; c < 3; ++c) {
                    p[c] = clamp8(lerpf(p[c] / 255.0f, 1.0f, alpha) * 255.0f);
                }
            }
            break;
        }

        case EffectId::kZoomBlur: {
            std::vector<uint8_t> src(static_cast<size_t>(img.stride) * h);
            Image srcView(src.data(), w, h, img.stride);
            copyImage(img, srcView);
            const float cx = (w - 1) * 0.5f, cy = (h - 1) * 0.5f;
            const float strength = 0.06f * t;
            const int taps = 6;
            for (int y = 0; y < h; ++y) {
                uint8_t* p = img.row(y);
                for (int x = 0; x < w; ++x) {
                    float acc[3] = {0, 0, 0};
                    for (int k = 0; k < taps; ++k) {
                        float s = 1.0f - strength * (static_cast<float>(k) / (taps - 1));
                        int sx = std::max(0, std::min(w - 1, static_cast<int>(cx + (x - cx) * s)));
                        int sy = std::max(0, std::min(h - 1, static_cast<int>(cy + (y - cy) * s)));
                        const uint8_t* q = src.data() + static_cast<size_t>(sy) * img.stride + sx * 4;
                        acc[0] += q[0]; acc[1] += q[1]; acc[2] += q[2];
                    }
                    for (int c = 0; c < 3; ++c) {
                        float avg = acc[c] / taps;
                        p[x * 4 + c] = clamp8(lerpf(p[x * 4 + c], avg, 0.85f));
                    }
                }
            }
            break;
        }
    }
}

void applyColorPipeline(Image& img, const ColorParams& p) {
    if (!img.valid()) return;

    if (p.blurRadiusPx > 0.4f) {
        applyBlur(img, p.blurRadiusPx);
    }

    for (int y = 0; y < img.height; ++y) {
        uint8_t* row = img.row(y);
        for (int x = 0; x < img.width; ++x) {
            float r = row[x * 4 + 0] / 255.0f;
            float g = row[x * 4 + 1] / 255.0f;
            float b = row[x * 4 + 2] / 255.0f;
            applyColorTransform(r, g, b, p);
            row[x * 4 + 0] = clamp8(r * 255.0f);
            row[x * 4 + 1] = clamp8(g * 255.0f);
            row[x * 4 + 2] = clamp8(b * 255.0f);
        }
    }

    // Note: the Kotlin/GLSL implementation applies the effect list after adjustments too.
    // The caller passes effect settings through `ColorParams` indirectly via `applyEffect`, which the
    // engine calls in timeline order (see native_engine.cpp).
}

void applyColorPipelineWithEffects(Image& img, const ColorParams& p, const EffectSetting* effects, int count) {
    applyColorPipeline(img, p);
    for (int i = 0; i < count; ++i) {
        applyEffect(img, effects[i].id, effects[i].intensity, p.timeSeconds, p.aspect);
    }
    if (p.sharpen > 0.001f) applySharpen(img, p.sharpen);
    if (p.vignette > 0.001f) applyVignette(img, p.vignette);
    applyChromaKey(img, p);
    if (p.wipe < 0.999f) {
        const float w = clamp01(p.wipe);
        const float cx = (img.width - 1) * 0.5f;
        const float cy = (img.height - 1) * 0.5f;
        const float maxR = std::sqrt(cx * cx + cy * cy);
        for (int y = 0; y < img.height; ++y) {
            uint8_t* row = img.row(y);
            for (int x = 0; x < img.width; ++x) {
                bool visible;
                if (p.wipeMode == 1) {
                    const float dx = x - cx, dy = y - cy;
                    visible = std::sqrt(dx * dx + dy * dy) <= w * maxR;
                } else {
                    visible = x <= static_cast<int>(w * img.width);
                }
                if (!visible) row[x * 4 + 3] = 0;
            }
        }
    }
    if (p.alpha < 0.999f) {
        const float a = clamp01(p.alpha);
        for (int y = 0; y < img.height; ++y) {
            uint8_t* row = img.row(y);
            for (int x = 0; x < img.width; ++x) {
                row[x * 4 + 3] = clamp8(row[x * 4 + 3] * a);
            }
        }
    }
}

void generateLut3d(const ColorParams& p, uint8_t* out) {
    if (!out) return;
    int index = 0;
    for (int bi = 0; bi < 16; ++bi) {
        for (int gi = 0; gi < 16; ++gi) {
            for (int ri = 0; ri < 16; ++ri) {
                float r = ri / 15.0f;
                float g = gi / 15.0f;
                float b = bi / 15.0f;
                applyColorTransform(r, g, b, p);
                out[index++] = clamp8(r * 255.0f);
                out[index++] = clamp8(g * 255.0f);
                out[index++] = clamp8(b * 255.0f);
                out[index++] = 255;
            }
        }
    }
}

void sampleLut3d(const uint8_t* lut, float r, float g, float b, float out[3]) {
    if (!lut) {
        out[0] = r; out[1] = g; out[2] = b;
        return;
    }
    const float fr = clamp01(r) * 15.0f;
    const float fg = clamp01(g) * 15.0f;
    const float fb = clamp01(b) * 15.0f;
    const int r0 = static_cast<int>(fr), g0 = static_cast<int>(fg), b0 = static_cast<int>(fb);
    const int r1 = std::min(r0 + 1, 15), g1 = std::min(g0 + 1, 15), b1 = std::min(b0 + 1, 15);
    const float tr = fr - r0, tg = fg - g0, tb = fb - b0;

    auto fetch = [&](int ri, int gi, int bi, int c) -> float {
        const int idx = ((bi * 16 + gi) * 16 + ri) * 4 + c;
        return lut[idx] / 255.0f;
    };
    for (int c = 0; c < 3; ++c) {
        float c000 = fetch(r0, g0, b0, c), c100 = fetch(r1, g0, b0, c);
        float c010 = fetch(r0, g1, b0, c), c110 = fetch(r1, g1, b0, c);
        float c001 = fetch(r0, g0, b1, c), c101 = fetch(r1, g0, b1, c);
        float c011 = fetch(r0, g1, b1, c), c111 = fetch(r1, g1, b1, c);
        float c00 = lerpf(c000, c100, tr), c10 = lerpf(c010, c110, tr);
        float c01 = lerpf(c001, c101, tr), c11 = lerpf(c011, c111, tr);
        float c0 = lerpf(c00, c10, tg), c1 = lerpf(c01, c11, tg);
        out[c] = lerpf(c0, c1, tb);
    }
}

}  // namespace cutvideo
