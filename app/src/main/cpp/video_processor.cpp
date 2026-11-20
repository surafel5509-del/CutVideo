#include "video_processor.h"

#include <cstring>
#include <cmath>
#include <vector>

namespace cutvideo {

namespace {

inline void sampleBilinear(const Image& src, float fx, float fy, uint8_t out[4]) {
    int x0 = static_cast<int>(std::floor(fx));
    int y0 = static_cast<int>(std::floor(fy));
    float tx = fx - static_cast<float>(x0);
    float ty = fy - static_cast<float>(y0);
    int x1 = std::min(x0 + 1, src.width - 1);
    int y1 = std::min(y0 + 1, src.height - 1);
    x0 = std::max(0, std::min(x0, src.width - 1));
    y0 = std::max(0, std::min(y0, src.height - 1));
    const uint8_t* r0 = src.row(y0);
    const uint8_t* r1 = src.row(y1);
    for (int c = 0; c < 4; ++c) {
        float a = r0[x0 * 4 + c];
        float b = r0[x1 * 4 + c];
        float cc = r1[x0 * 4 + c];
        float d = r1[x1 * 4 + c];
        float top = a + (b - a) * tx;
        float bottom = cc + (d - cc) * tx;
        out[c] = clamp8(top + (bottom - top) * ty);
    }
}

}  // namespace

float luminanceOf(uint8_t r, uint8_t g, uint8_t b) {
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f;
}

void fillImage(Image& img, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (!img.valid()) return;
    for (int y = 0; y < img.height; ++y) {
        uint8_t* p = img.row(y);
        for (int x = 0; x < img.width; ++x) {
            p[x * 4 + 0] = r;
            p[x * 4 + 1] = g;
            p[x * 4 + 2] = b;
            p[x * 4 + 3] = a;
        }
    }
}

void copyImage(const Image& src, Image& dst) {
    if (!src.valid() || !dst.valid()) return;
    int rows = std::min(src.height, dst.height);
    int bytes = std::min(src.stride, dst.stride);
    for (int y = 0; y < rows; ++y) {
        std::memcpy(dst.row(y), src.row(y), static_cast<size_t>(bytes));
    }
}

void rotate90(const Image& src, Image& dst, bool clockwise) {
    if (!src.valid() || dst.width != src.height || dst.height != src.width) return;
    const int w = src.width, h = src.height;
    for (int y = 0; y < h; ++y) {
        const uint8_t* srcRow = src.row(y);
        for (int x = 0; x < w; ++x) {
            // Clockwise: (x,y) -> (h-1-y, x); counter-clockwise: (x,y) -> (y, w-1-x)
            int nx = clockwise ? (h - 1 - y) : y;
            int ny = clockwise ? x : (w - 1 - x);
            std::memcpy(dst.row(ny) + nx * 4, srcRow + x * 4, 4);
        }
    }
}

void flipHorizontal(Image& img) {
    if (!img.valid()) return;
    for (int y = 0; y < img.height; ++y) {
        uint8_t* p = img.row(y);
        for (int x = 0; x < img.width / 2; ++x) {
            for (int c = 0; c < 4; ++c) {
                std::swap(p[x * 4 + c], p[(img.width - 1 - x) * 4 + c]);
            }
        }
    }
}

void flipVertical(Image& img) {
    if (!img.valid()) return;
    for (int y = 0; y < img.height / 2; ++y) {
        uint8_t* a = img.row(y);
        uint8_t* b = img.row(img.height - 1 - y);
        int bytes = img.width * 4;
        for (int i = 0; i < bytes; ++i) std::swap(a[i], b[i]);
    }
}

void cropImage(const Image& src, Image& dst, int x, int y, int w, int h) {
    if (!src.valid() || !dst.valid()) return;
    for (int row = 0; row < dst.height && row < h; ++row) {
        int sy = y + row;
        if (sy < 0 || sy >= src.height) continue;
        for (int col = 0; col < dst.width && col < w; ++col) {
            int sx = x + col;
            if (sx < 0 || sx >= src.width) continue;
            std::memcpy(dst.row(row) + col * 4, src.row(sy) + sx * 4, 4);
        }
    }
}

void scaleBilinear(const Image& src, Image& dst) {
    if (!src.valid() || !dst.valid()) return;
    float sx = static_cast<float>(src.width) / dst.width;
    float sy = static_cast<float>(src.height) / dst.height;
    for (int y = 0; y < dst.height; ++y) {
        uint8_t* out = dst.row(y);
        float fy = (y + 0.5f) * sy - 0.5f;
        for (int x = 0; x < dst.width; ++x) {
            float fx = (x + 0.5f) * sx - 0.5f;
            uint8_t px[4];
            sampleBilinear(src, fx, fy, px);
            std::memcpy(out + x * 4, px, 4);
        }
    }
}

void scaleAreaAverage(const Image& src, Image& dst) {
    if (!src.valid() || !dst.valid()) return;
    for (int y = 0; y < dst.height; ++y) {
        int y0 = y * src.height / dst.height;
        int y1 = std::max(y0 + 1, (y + 1) * src.height / dst.height);
        uint8_t* out = dst.row(y);
        for (int x = 0; x < dst.width; ++x) {
            int x0 = x * src.width / dst.width;
            int x1 = std::max(x0 + 1, (x + 1) * src.width / dst.width);
            int64_t r = 0, g = 0, b = 0, a = 0, n = 0;
            for (int yy = y0; yy < y1 && yy < src.height; ++yy) {
                const uint8_t* p = src.row(yy);
                for (int xx = x0; xx < x1 && xx < src.width; ++xx) {
                    r += p[xx * 4 + 0];
                    g += p[xx * 4 + 1];
                    b += p[xx * 4 + 2];
                    a += p[xx * 4 + 3];
                    ++n;
                }
            }
            if (n == 0) n = 1;
            out[x * 4 + 0] = static_cast<uint8_t>(r / n);
            out[x * 4 + 1] = static_cast<uint8_t>(g / n);
            out[x * 4 + 2] = static_cast<uint8_t>(b / n);
            out[x * 4 + 3] = static_cast<uint8_t>(a / n);
        }
    }
}

void applyBlur(Image& img, float radiusPx) {
    if (!img.valid() || radiusPx <= 0.4f) return;
    int radius = static_cast<int>(std::min(radiusPx, 40.0f));
    if (radius < 1) radius = 1;
    const int w = img.width, h = img.height;
    std::vector<uint8_t> tmp(static_cast<size_t>(img.stride) * h);
    const float inv = 1.0f / (2 * radius + 1);

    // Horizontal pass.
    for (int y = 0; y < h; ++y) {
        const uint8_t* src = img.row(y);
        uint8_t* dst = tmp.data() + static_cast<size_t>(y) * img.stride;
        int acc[4] = {0, 0, 0, 0};
        for (int k = -radius; k <= radius; ++k) {
            int xx = std::max(0, std::min(w - 1, k));
            for (int c = 0; c < 4; ++c) acc[c] += src[xx * 4 + c];
        }
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 4; ++c) dst[x * 4 + c] = static_cast<uint8_t>(acc[c] * inv);
            int addX = std::min(w - 1, x + radius + 1);
            int subX = std::max(0, x - radius);
            for (int c = 0; c < 4; ++c) {
                acc[c] += src[addX * 4 + c] - src[subX * 4 + c];
            }
        }
    }

    // Vertical pass back into the image.
    for (int x = 0; x < w; ++x) {
        int acc[4] = {0, 0, 0, 0};
        for (int k = -radius; k <= radius; ++k) {
            int yy = std::max(0, std::min(h - 1, k));
            const uint8_t* p = tmp.data() + static_cast<size_t>(yy) * img.stride + x * 4;
            for (int c = 0; c < 4; ++c) acc[c] += p[c];
        }
        for (int y = 0; y < h; ++y) {
            uint8_t* out = img.row(y) + x * 4;
            for (int c = 0; c < 4; ++c) out[c] = static_cast<uint8_t>(acc[c] * inv);
            int addY = std::min(h - 1, y + radius + 1);
            int subY = std::max(0, y - radius);
            const uint8_t* pa = tmp.data() + static_cast<size_t>(addY) * img.stride + x * 4;
            const uint8_t* ps = tmp.data() + static_cast<size_t>(subY) * img.stride + x * 4;
            for (int c = 0; c < 4; ++c) acc[c] += pa[c] - ps[c];
        }
    }
}

void applySharpen(Image& img, float amount) {
    if (!img.valid() || amount <= 0.001f) return;
    const int w = img.width, h = img.height;
    std::vector<uint8_t> blurred(static_cast<size_t>(img.stride) * h);
    Image blurView(blurred.data(), w, h, img.stride);
    copyImage(img, blurView);
    applyBlur(blurView, 2.0f);
    const float a = clamp01(amount) * 1.4f;
    for (int y = 0; y < h; ++y) {
        uint8_t* p = img.row(y);
        const uint8_t* bp = blurView.row(y);
        for (int x = 0; x < w * 4; x += 4) {
            for (int c = 0; c < 3; ++c) {
                float orig = p[x + c];
                float b = bp[x + c];
                p[x + c] = clamp8(orig + (orig - b) * a);
            }
        }
    }
}

void applyVignette(Image& img, float amount) {
    if (!img.valid() || amount <= 0.001f) return;
    const float a = clamp01(amount);
    const float cx = (img.width - 1) * 0.5f;
    const float cy = (img.height - 1) * 0.5f;
    const float maxR = std::sqrt(cx * cx + cy * cy);
    for (int y = 0; y < img.height; ++y) {
        uint8_t* p = img.row(y);
        for (int x = 0; x < img.width; ++x) {
            float dx = x - cx;
            float dy = y - cy;
            float r = std::sqrt(dx * dx + dy * dy) / (maxR > 0 ? maxR : 1.0f);
            float factor = 1.0f - a * smoothstepf(0.35f, 1.0f, r);
            for (int c = 0; c < 3; ++c) p[x * 4 + c] = clamp8(p[x * 4 + c] * factor);
        }
    }
}

void applyGrain(Image& img, float amount, float time) {
    if (!img.valid() || amount <= 0.001f) return;
    const float a = clamp01(amount) * 46.0f;
    const float seed = std::floor(time * 24.0f);
    for (int y = 0; y < img.height; ++y) {
        uint8_t* p = img.row(y);
        for (int x = 0; x < img.width; ++x) {
            float n = hash21(static_cast<float>(x) + seed * 7.13f, static_cast<float>(y) + seed * 3.71f);
            float d = (n - 0.5f) * 2.0f * a;
            for (int c = 0; c < 3; ++c) p[x * 4 + c] = clamp8(p[x * 4 + c] + d);
        }
    }
}

void applyChromaKey(Image& img, const ColorParams& p) {
    if (!img.valid() || !p.chromaEnabled) return;
    const float kr = p.chromaKey[0], kg = p.chromaKey[1], kb = p.chromaKey[2];
    const float sim = clampf(p.chromaSimilarity, 0.01f, 1.0f);
    const float smooth = clampf(p.chromaSmoothness, 0.0f, 0.9f);
    const float spill = clamp01(p.chromaSpill);
    // Distance in a chroma-weighted space so lighting changes are tolerated.
    for (int y = 0; y < img.height; ++y) {
        uint8_t* px = img.row(y);
        for (int x = 0; x < img.width; ++x) {
            float r = px[x * 4 + 0] / 255.0f;
            float g = px[x * 4 + 1] / 255.0f;
            float b = px[x * 4 + 2] / 255.0f;
            float y0 = luma(r, g, b);
            float cbPix = b - y0;
            float crPix = r - y0;
            float keyY = luma(kr, kg, kb);
            float cbKey = kb - keyY;
            float crKey = kr - keyY;
            float dCb = cbPix - cbKey;
            float dCr = crPix - crKey;
            float dist = std::sqrt(dCb * dCb + dCr * dCr) * 1.6f;
            float alpha;
            if (smooth <= 0.001f) {
                alpha = dist < sim ? 0.0f : 1.0f;
            } else {
                alpha = smoothstepf(sim * (1.0f - smooth), sim * (1.0f + smooth * 0.5f) + 0.0001f, dist);
            }
            float existing = px[x * 4 + 3] / 255.0f;
            px[x * 4 + 3] = clamp8(existing * alpha * 255.0f);
            // Spill suppression: pull the dominant key channel back toward luma.
            if (spill > 0.0f && alpha > 0.01f) {
                float keyDominant = std::max(kg, std::max(kr, kb));
                if (keyDominant == kg) {
                    float limit = (r + b) * 0.5f;
                    if (g > limit) g = lerpf(g, limit, spill);
                } else if (keyDominant == kr) {
                    float limit = (g + b) * 0.5f;
                    if (r > limit) r = lerpf(r, limit, spill);
                } else {
                    float limit = (r + g) * 0.5f;
                    if (b > limit) b = lerpf(b, limit, spill);
                }
                px[x * 4 + 0] = clamp8(r * 255.0f);
                px[x * 4 + 1] = clamp8(g * 255.0f);
                px[x * 4 + 2] = clamp8(b * 255.0f);
            }
        }
    }
}

}  // namespace cutvideo
