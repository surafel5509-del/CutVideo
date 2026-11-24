#version 300 es
// CutVideo effect-chain pass shader.
//
// One shader serves every stage of the effect pipeline; `uStage` selects what this draw does.
// The 23 classic effects live here with their professional parameters wired in (neutral parameter
// defaults reproduce the historical single-pass look exactly), plus the real multi-pass stages:
// separable gaussian, bright-pass, multi-scale bloom combine and drop-shadow construction.
//
// Blend formulas mirror `BlendModes.kt` — keep the two in sync (EffectChainTest pins them).
precision highp float;

in vec2 vTex;
out vec4 fragColor;

uniform sampler2D uTexA;      // primary source / base (stage dependent)
uniform sampler2D uTexB;      // secondary source (processed / scale-2 / base)
uniform sampler2D uTexC;      // base for combine
uniform vec2 uTexelSize;
uniform float uTime;
uniform float uAspect;

uniform int uStage;
uniform int uEffectId;
uniform float uStrength;      // effect intensity / mix amount, 0..1
uniform int uBlend;           // BlendMode ordinal
uniform vec4 uP0;             // effect parameters 0..3 (definition order)
uniform vec4 uP1;             // effect parameters 4..7
uniform vec2 uBlurDir;        // gaussian direction (texel units, radius folded in)
uniform vec3 uTint;           // bloom tint
uniform vec2 uCombineW;       // combine weights (scale-1, scale-2)
uniform vec3 uDuotoneShadow;
uniform vec3 uDuotoneHighlight;

// .cube LUT: tiled 2D texture (size*size wide, size tall), 0 = none bound.
uniform sampler2D uLutTexture;
uniform float uLutSize;

const float PI = 3.14159265359;

// --- stages -----------------------------------------------------------------------------------------
const int STAGE_SINGLE = 0;
const int STAGE_BLUR = 1;              // separable gaussian along uBlurDir
const int STAGE_MIX = 2;               // composite uTexB over uTexA
const int STAGE_BRIGHT = 3;            // threshold bright-pass from uTexA
const int STAGE_COMBINE = 4;           // weighted scales over base uTexC
const int STAGE_SHADOW_SIL = 5;        // offset+dilated alpha silhouette from uTexA
const int STAGE_SHADOW_COMPOSITE = 6;  // uTexB over the shadow mask uTexA

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash21(vec2 p) {
    return hash11(p.x * 127.1 + p.y * 311.7);
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 sampleA(vec2 uv) {
    return texture(uTexA, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

// --- colour helpers ---------------------------------------------------------------------------------

vec3 hueToRgb(float h) {
    float r = abs(h * 6.0 - 3.0) - 1.0;
    float g = 2.0 - abs(h * 6.0 - 2.0);
    float b = 2.0 - abs(h * 6.0 - 4.0);
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

void rgbToHsl(vec3 c, out float h, out float s, out float l) {
    float maxc = max(c.r, max(c.g, c.b));
    float minc = min(c.r, min(c.g, c.b));
    l = (maxc + minc) * 0.5;
    float d = maxc - minc;
    if (d < 0.0001) {
        h = 0.0;
        s = 0.0;
        return;
    }
    s = l > 0.5 ? d / (2.0 - maxc - minc) : d / (maxc + minc);
    if (maxc == c.r) {
        h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    } else if (maxc == c.g) {
        h = (c.b - c.r) / d + 2.0;
    } else {
        h = (c.r - c.g) / d + 4.0;
    }
    h /= 6.0;
}

float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 hslToRgb(float h, float s, float l) {
    if (s < 0.0001) return vec3(l);
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(hue2rgb(p, q, h + 1.0 / 3.0), hue2rgb(p, q, h), hue2rgb(p, q, h - 1.0 / 3.0));
}

// Tiled .cube LUT lookup: blue slices are laid out along X (slice b at x in [b*size, (b+1)*size)).
vec3 lutLookup(vec3 c) {
    float size = max(uLutSize, 2.0);
    float rf = clamp(c.r, 0.0, 1.0) * (size - 1.0);
    float gf = clamp(c.g, 0.0, 1.0) * (size - 1.0);
    float bf = clamp(c.b, 0.0, 1.0) * (size - 1.0);
    float b0 = floor(bf);
    float b1 = min(b0 + 1.0, size - 1.0);
    vec2 dims = vec2(size * size, size);
    vec2 uv0 = vec2(b0 * size + rf + 0.5, gf + 0.5) / dims;
    vec2 uv1 = vec2(b1 * size + rf + 0.5, gf + 0.5) / dims;
    return mix(texture(uLutTexture, uv0).rgb, texture(uLutTexture, uv1).rgb, bf - b0);
}

vec4 sampleA4(vec2 uv) {
    return texture(uTexA, clamp(uv, vec2(0.0), vec2(1.0)));
}

// --- blend modes (mirror of BlendModes.kt) -----------------------------------------------------------

vec3 blendCh(vec3 b, vec3 s, int mode) {
    if (mode == 1) return clamp(b + s, 0.0, 1.0);                                    // add
    if (mode == 2) return clamp(1.0 - (1.0 - b) * (1.0 - s), 0.0, 1.0);              // screen
    if (mode == 3) return clamp(b * s, 0.0, 1.0);                                    // multiply
    if (mode == 4) return clamp(mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s),  // overlay
        step(0.5, b)), 0.0, 1.0);
    if (mode == 5) {                                                                 // soft light
        vec3 lo = b - (1.0 - 2.0 * s) * b * (1.0 - b);
        vec3 d = mix(((16.0 * b - 12.0) * b + 4.0) * b, sqrt(b), step(0.25, b));
        return clamp(mix(lo, b + (2.0 * s - 1.0) * (d - b), step(0.5, s)), 0.0, 1.0);
    }
    if (mode == 6) return clamp(mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s),  // hard light
        step(0.5, s)), 0.0, 1.0);
    if (mode == 7) return clamp(abs(b - s), 0.0, 1.0);                               // difference
    return s;                                                                        // normal
}

vec4 composite(vec4 base, vec4 src, int mode, float amount) {
    float a = clamp(amount, 0.0, 1.0);
    vec3 mixed = base.rgb + (blendCh(base.rgb, src.rgb, mode) - base.rgb) * a;
    return vec4(mixed, base.a);
}

// --- classic effects (ids 1..23, neutral params reproduce the legacy look) ---------------------------

vec3 blur9(vec2 uv, float radiusPx) {
    vec2 st = uTexelSize * max(radiusPx, 0.0) * 0.5;
    vec3 acc = vec3(0.0);
    acc += sampleA(uv + vec2(-st.x, -st.y));
    acc += sampleA(uv + vec2(0.0, -st.y));
    acc += sampleA(uv + vec2(st.x, -st.y));
    acc += sampleA(uv + vec2(-st.x, 0.0));
    acc += sampleA(uv);
    acc += sampleA(uv + vec2(st.x, 0.0));
    acc += sampleA(uv + vec2(-st.x, st.y));
    acc += sampleA(uv + vec2(0.0, st.y));
    acc += sampleA(uv + vec2(st.x, st.y));
    return acc / 9.0;
}

vec3 effectColor(int id, vec3 color, vec2 uv) {
    if (id == 1) {           // film grain: p0 = size, speed
        float n = hash21(uv * 1024.0 / max(uP0.x, 0.05) + vec2(floor(uTime * 24.0 * max(uP0.y, 0.0)) * 7.13));
        return clamp(color + (n - 0.5) * 2.0 * uStrength * 0.18, 0.0, 1.0);
    } else if (id == 2) {    // vignette: p0 = roundness, softness, centerX, centerY
        vec2 p = (uv - vec2(uP0.z, uP0.w)) * vec2(uAspect, 1.0);
        float r = clamp(length(p) / (0.72 * max(uP0.x, 0.05)), 0.0, 1.0);
        float f = 1.0 - uStrength * smoothstep(max(0.02, 1.0 - uP0.y * 1.3), 1.0, r);
        return color * f;
    } else if (id == 3) {    // scanlines: p0 = frequency, opacity
        float line = mod(floor(uv.y * uP0.x), 3.0);
        float f = line < 1.0 ? 1.0 - uP0.y * uStrength * 0.7 : 1.0;
        return vec3(color.r * f, color.g * f * (1.0 + 0.05 * uStrength), color.b * f);
    } else if (id == 4) {    // glitch: p0 = speed, bands
        float band = floor(uv.y * uP0.y);
        float rnd = hash11(band * 0.37 + floor(uTime * 9.0 * max(uP0.x, 0.0)) * 1.7);
        vec2 shift = vec2((rnd > 0.82 ? (rnd - 0.82) * 4.0 * uStrength : 0.0), 0.0);
        float ca = uStrength * 0.006;
        vec3 c;
        c.r = sampleA(uv + shift + vec2(ca, 0.0)).r;
        c.g = sampleA(uv + shift).g;
        c.b = sampleA(uv + shift - vec2(ca, 0.0)).b;
        return c;
    } else if (id == 5) {    // chromatic aberration: p0 = spread
        float ca = uStrength * 0.004 * uP0.x;
        vec3 c;
        c.r = sampleA(uv + vec2(ca, 0.0)).r;
        c.g = color.g;
        c.b = sampleA(uv - vec2(ca, 0.0)).b;
        return c;
    } else if (id == 6) {    // bloom (classic)
        vec3 glow = blur9(uv, 6.0 + 10.0 * uStrength);
        glow = max(glow - 0.55, 0.0) * 2.2;
        return clamp(color + glow * uStrength * 1.1, 0.0, 1.0);
    } else if (id == 7) {    // light leak: p0 = speed
        float cx = 0.15 + 0.7 * (0.5 + 0.5 * sin(uTime * 0.6 * uP0.x));
        float cy = 0.85 - 0.2 * sin(uTime * 0.43 * uP0.x);
        vec2 d = vec2((uv.x - cx) * uAspect, uv.y - cy);
        float leak = (1.0 - smoothstep(0.0, 0.9, length(d))) * uStrength * 0.85;
        return clamp(color + vec3(0.95, 0.55, 0.18) * leak, 0.0, 1.0);
    } else if (id == 8) {    // pulse: p0 = speed
        float amp = 1.0 + 0.12 * uStrength * sin(uTime * 6.0 * uP0.x);
        return color * amp;
    } else if (id == 9) {    // mirror: p0 = axis position
        vec2 muv = vec2(uv.x < uP0.x ? uv.x : 2.0 * uP0.x - uv.x, uv.y);
        return mix(color, sampleA(muv), uStrength);
    } else if (id == 10) {   // posterize: p0 = levels bias
        float levels = max(2.0, (2.0 + (1.0 - uStrength) * 10.0) * (uP0.x / 6.0));
        return mix(color, floor(color * levels) / (levels - 1.0), uStrength);
    } else if (id == 11) {   // duotone
        float l = luma(color);
        vec3 duo = mix(uDuotoneShadow, uDuotoneHighlight, l);
        return mix(color, duo, uStrength);
    } else if (id == 12) {   // sharpen: p0 = radius
        vec3 blur = blur9(uv, 2.0 * uP0.x);
        return clamp(color + (color - blur) * uStrength * 1.4, 0.0, 1.0);
    } else if (id == 13) {   // vhs: p0 = wobble, noise
        float jitter = (hash11(floor(uv.y * 240.0) * 0.21 + floor(uTime * 12.0)) - 0.5) * 0.004 * uStrength * uP0.x;
        vec3 c = sampleA(uv + vec2(jitter, 0.0));
        if (mod(floor(uv.y * 480.0), 2.0) < 1.0) c *= 1.0 - 0.18 * uStrength;
        float n = hash21(uv * 900.0 + uTime);
        c += (n - 0.5) * 0.10 * uStrength * uP0.y;
        return mix(c, vec3(luma(c)), 0.25 * uStrength);
    } else if (id == 14) {   // snow: p0 = density, speed
        float cells = 260.0;
        vec2 g = floor(uv * cells);
        float n = hash21(g);
        float fall = fract(hash21(g + 3.7) + uTime * (0.15 + n * 0.4) * uP0.y);
        float dotSize = smoothstep(0.30, 0.0, length(fract(uv * cells) - 0.5));
        float visible = step(0.62 - uStrength * 0.35 * uP0.x, hash21(g * 1.7));
        float snow = dotSize * visible * step(0.02, abs(uv.y - fall) + 0.02);
        return mix(color, vec3(1.0), clamp(snow * (0.35 + 0.5 * uStrength), 0.0, 1.0));
    } else if (id == 15) {   // zoom blur: p0 = strength
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 6; ++i) {
            float s = 1.0 - 0.06 * uStrength * uP0.x * (float(i) / 5.0);
            acc += sampleA((uv - 0.5) * s + 0.5);
        }
        return mix(color, acc / 6.0, 0.85);
    } else if (id == 16) {   // blur (classic): p0 = radius
        return blur9(uv, uP0.x);
    } else if (id == 17) {   // pixelate: p0 = pixel size (14 = legacy look)
        float cells = max(2.0, (22.0 + (1.0 - uStrength) * 170.0) * (14.0 / max(uP0.x, 0.05)));
        vec2 snapped = (floor(uv * cells) + 0.5) / cells;
        return sampleA(snapped);
    } else if (id == 18) {   // neon edge glow: p0 = threshold
        float lx = luma(sampleA(uv + vec2(uTexelSize.x * 2.0, 0.0))) -
                   luma(sampleA(uv - vec2(uTexelSize.x * 2.0, 0.0)));
        float ly = luma(sampleA(uv + vec2(0.0, uTexelSize.y * 2.0))) -
                   luma(sampleA(uv - vec2(0.0, uTexelSize.y * 2.0)));
        float edge = clamp(length(vec2(lx, ly)) * 3.0 * (0.12 / max(uP0.x, 0.01)), 0.0, 1.0);
        vec3 tint = mix(vec3(0.15, 0.95, 1.0), vec3(1.0, 0.25, 0.85),
                        0.5 + 0.5 * sin(uv.y * 3.2 + uTime * 0.8));
        return clamp(color * (1.0 - 0.45 * uStrength) + tint * edge * uStrength * 1.6, 0.0, 1.0);
    } else if (id == 19) {   // halftone: p0 = dot scale
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float cellPx = (7.0 - 2.0 * uStrength) * (uP0.x / 8.0);
        vec2 f = fract(px / max(cellPx, 0.5)) - 0.5;
        float d = length(f);
        float l = luma(color);
        float radius = 0.5 * sqrt(max(0.0, 1.0 - l));
        float ink = 1.0 - smoothstep(radius - 0.05, radius + 0.05, d);
        float v = mix(0.94 - 0.08 * l, 0.05, ink);
        return mix(color, vec3(v * 1.04, v, v * 0.93), uStrength);
    } else if (id == 20) {   // tilt shift: p0 = focus, band
        float ny = abs(uv.y - uP0.x) * 2.0;
        float strength = smoothstep(max(0.03, uP0.y * 0.48), uP0.y * 3.2, ny) * uStrength;
        return strength > 0.01 ? blur9(uv, strength * 30.0) : color;
    } else if (id == 21) {   // old film: p0 = flicker
        vec3 sepia = vec3(0.393 * color.r + 0.769 * color.g + 0.189 * color.b,
                          0.349 * color.r + 0.686 * color.g + 0.168 * color.b,
                          0.272 * color.r + 0.534 * color.g + 0.131 * color.b);
        float flick = 1.0 + uP0.x * (0.05 * sin(uTime * 9.0) + 0.04 * (hash11(floor(uTime * 12.0)) - 0.5));
        vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
        float vig = 1.0 - uStrength * 0.65 * smoothstep(0.15, 0.85, length(p));
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float scratch = step(0.9965, hash21(vec2(floor(px.x * 0.5), floor(uTime * 8.0))));
        vec3 c = mix(color, sepia, uStrength) * flick * vig;
        return mix(c, vec3(0.96), scratch * uStrength * 0.35);
    } else if (id == 22) {   // heat wave: p0 = speed, amplitude
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float shift = (sin(px.y * 0.09 + uTime * 3.1 * uP0.x) * 7.0 +
                       sin(px.y * 0.31 - uTime * 1.7 * uP0.x) * 3.0) * uStrength * uP0.y;
        return sampleA(uv + vec2(shift * uTexelSize.x, 0.0));
    } else if (id == 23) {   // spotlight: p0 = x, y, radius
        vec2 p = (uv - vec2(uP0.x, uP0.y)) * vec2(uAspect, 1.0);
        float r = length(p) / max(uP0.z, 0.05);
        float light = 1.0 - smoothstep(0.1, 1.0, r);
        float gain = mix(1.0 - 0.8 * uStrength, 1.0 + 0.25 * uStrength, light);
        return clamp(color * gain * vec3(1.0 + 0.06 * uStrength * light,
                                         1.0 + 0.02 * uStrength * light,
                                         1.0 - 0.04 * uStrength * light), 0.0, 1.0);
    } else if (id == 28) {   // vignette pro: p0 = roundness, softness, centerX, centerY
        vec2 p = (uv - vec2(uP0.z, uP0.w)) * vec2(uAspect, 1.0);
        float r = length(p) / max(0.72 * uP0.x, 0.001);
        float f = 1.0 - uStrength * smoothstep(max(0.02, 1.0 - uP0.y), 1.0, r);
        return color * f;
    } else if (id == 29) {   // grain pro: p0 = size, speed, roughness
        float n = hash21(uv * 1024.0 / max(uP0.x, 0.05) + vec2(floor(uTime * 24.0 * max(uP0.y, 0.0)) * 7.13));
        float coarse = hash21(floor(uv * 256.0 / max(uP0.x, 0.05)) + 31.0);
        float amt = uStrength * 0.18 * (1.0 - 0.5 * uP0.z + uP0.z * coarse);
        return clamp(color + (n - 0.5) * 2.0 * amt, 0.0, 1.0);
    } else if (id == 30) {   // lift / gamma / gain: p0 = liftR, liftG, liftB, gamma; p1 = gainR, gainG, gainB
        vec3 c = color + vec3(uP0.x, uP0.y, uP0.z) * 0.5;
        c = pow(max(c, vec3(0.0)), vec3(1.0 / max(uP0.w, 0.05)));
        c = c * vec3(uP1.x, uP1.y, uP1.z);
        return clamp(mix(color, clamp(c, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 31) {   // RGB curves (parametric): p0 = shadows, midtones, highlights, contrast
        float l = luma(color);
        float curve = uP0.x * clamp(1.0 - l * 2.0, 0.0, 1.0)
            + uP0.y * (1.0 - abs(l - 0.5) * 2.0)
            + uP0.z * clamp(l * 2.0 - 1.0, 0.0, 1.0);
        vec3 c = color + curve * 0.5;
        c = (c - 0.5) * (1.0 + uP0.w) + 0.5;
        return clamp(mix(color, clamp(c, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 32) {   // colour wheels: p0 = shadowsHue, shadowsSat, midsHue, midsSat; p1 = highsHue, highsSat, balance
        float l = luma(color);
        vec3 shadowTint = hueToRgb(uP0.x) * uP0.y - vec3(uP0.y) * 0.33;
        vec3 midTint = hueToRgb(uP0.z) * uP0.w - vec3(uP0.w) * 0.33;
        vec3 highTint = hueToRgb(uP1.x) * uP1.y - vec3(uP1.y) * 0.33;
        float shadowW = clamp(1.0 - l * 2.0, 0.0, 1.0);
        float highW = clamp(l * 2.0 - 1.0, 0.0, 1.0);
        float midW = clamp(1.0 - shadowW - highW, 0.0, 1.0);
        vec3 tinted = color + (shadowTint * shadowW + midTint * midW + highTint * highW) * 0.35;
        tinted *= 1.0 + uP1.z * 0.3 * (l - 0.5);
        return clamp(mix(color, clamp(tinted, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 33) {   // hue vs sat/lum: p0 = hueCenter, hueRange, hueShift; p1 = satAdjust, lumAdjust
        float h, s, l2;
        rgbToHsl(color, h, s, l2);
        float d = abs(fract(h - uP0.x + 0.5) - 0.5);
        float w = 1.0 - smoothstep(uP0.y * 0.5, max(uP0.y, 0.01), d);
        h = fract(h + uP0.z * 0.5 * w);
        s = clamp(s * (1.0 + uP1.x * w), 0.0, 1.0);
        l2 = clamp(l2 * (1.0 + uP1.y * w), 0.0, 1.0);
        return mix(color, hslToRgb(h, s, l2), uStrength);
    } else if (id == 34) {   // film emulation: p0 = density, fade, warmth; p1 = saturation
        vec3 c = (color - 0.5) * (1.0 + uP0.x) + 0.5;
        c = mix(c, c * 0.85 + 0.12, clamp(uP0.y, 0.0, 1.0));
        c.r += uP0.z * 0.08;
        c.b -= uP0.z * 0.08;
        float l = luma(c);
        c = mix(vec3(l), c, 1.0 + uP1.x);
        return clamp(mix(color, clamp(c, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 35) {   // teal & orange: p0 = shadowTint, highlightTint, balance
        float l = luma(color);
        vec3 teal = vec3(-0.06, 0.02, 0.10);
        vec3 orange = vec3(0.10, 0.03, -0.06);
        float sw = clamp(1.0 - l * 1.6, 0.0, 1.0);
        float hw = clamp(l * 1.6 - 0.6, 0.0, 1.0);
        vec3 c = color + teal * sw * uP0.x * (1.0 + uP0.z) + orange * hw * uP0.y * (1.0 - uP0.z);
        return clamp(mix(color, clamp(c, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 36) {   // bleach bypass: p0 = contrast, desaturation
        float l = luma(color);
        vec3 c = (color - 0.5) * (1.0 + uP0.x * 1.2) + 0.5;
        c = mix(c, vec3(l), clamp(uP0.y, 0.0, 1.0));
        c = mix(vec3(luma(c)), c, 1.0 + uP0.x * 0.4);
        return clamp(mix(color, clamp(c, 0.0, 1.0), uStrength), 0.0, 1.0);
    } else if (id == 37) {   // .cube LUT (uses uLutTexture)
        if (uLutSize < 1.5) return color;
        return mix(color, lutLookup(color), uStrength);
    } else if (id == 38) {   // RGB split: p0 = angle(turns), spread, radial
        float a = uP0.x * 6.2831853;
        vec2 dir = vec2(cos(a), sin(a)) * uP0.y * 0.01;
        vec2 radial = (vTex - 0.5) * uP0.y * 0.02 * uP0.z;
        vec2 d = dir + radial;
        vec3 c;
        c.r = sampleA(vTex + d).r;
        c.g = color.g;
        c.b = sampleA(vTex - d).b;
        return mix(color, c, uStrength);
    } else if (id == 39) {   // pixel sort: p0 = threshold, streak length, direction (0 horiz, 1 vert)
        vec2 stepDir = uP0.z > 0.5 ? vec2(0.0, uTexelSize.y) : vec2(uTexelSize.x, 0.0);
        float maxLen = uP0.y * 24.0;
        vec3 best = color;
        float bestL = luma(color);
        for (int i = 1; i <= 8; ++i) {
            float fi = float(i);
            vec3 s1 = sampleA(vTex + stepDir * fi * 3.0);
            vec3 s2 = sampleA(vTex - stepDir * fi * 3.0);
            float l1 = luma(s1);
            float l2v = luma(s2);
            if (fi <= maxLen && l1 > bestL && l1 > uP0.x) {
                best = s1;
                bestL = l1;
            }
            if (fi <= maxLen && l2v > bestL && l2v > uP0.x) {
                best = s2;
                bestL = l2v;
            }
        }
        return mix(color, best, uStrength);
    } else if (id == 40) {   // datamosh: p0 = block density, smear, seed
        vec2 block = floor(vTex * uP0.x + uP0.z) / max(uP0.x, 1.0);
        float n = hash21(block * 3.1 + floor(uTime * 2.0));
        vec2 shift = (vec2(n, hash21(block + 9.7)) - 0.5) * uP0.y * 0.12 * step(0.55, n);
        vec3 c = sampleA(vTex + shift);
        c = mix(c, sampleA(vTex + shift * 2.0), 0.5 * clamp(uP0.y, 0.0, 1.0));
        return mix(color, c, uStrength);
    } else if (id == 41) {   // liquid warp: p0 = amplitude, frequency, speed
        vec2 p = vTex * uP0.y * 4.0;
        float t = uTime * uP0.z;
        vec2 warp = vec2(
            sin(p.y * 2.1 + t) + 0.5 * sin(p.y * 4.7 - t * 1.3),
            cos(p.x * 1.7 - t * 0.8) + 0.5 * cos(p.x * 3.9 + t)
        ) * uTexelSize * uP0.x * 40.0;
        return mix(color, sampleA(vTex + warp), uStrength);
    } else if (id == 42) {   // displacement: p0 = angle(turns), amount, scale
        float a = uP0.x * 6.2831853;
        float wave = sin((vTex.x * cos(a) + vTex.y * sin(a)) * uP0.z * 20.0 + uTime);
        vec2 d = vec2(cos(a), sin(a)) * wave * uP0.y * 0.02;
        return mix(color, sampleA(vTex + d), uStrength);
    } else if (id == 43) {   // motion blur: p0 = angle(turns), distance
        float a = uP0.x * 6.2831853;
        vec2 dir = vec2(cos(a), sin(a)) * uP0.y * 0.02;
        vec3 acc = color;
        for (int i = 1; i <= 6; ++i) {
            float t = float(i) / 6.0;
            acc += sampleA(vTex + dir * t);
            acc += sampleA(vTex - dir * t);
        }
        return mix(color, acc / 13.0, uStrength);
    } else if (id == 44) {   // radial / zoom motion: p0 = centerX, centerY, strength
        vec2 c0 = vec2(uP0.x, uP0.y);
        vec3 acc = color;
        for (int i = 1; i <= 6; ++i) {
            float s = 1.0 - uP0.z * 0.08 * (float(i) / 6.0);
            acc += sampleA((vTex - c0) * s + c0);
        }
        return mix(color, acc / 7.0, uStrength);
    }
    return color;
}

// --- multi-pass stages -------------------------------------------------------------------------------

vec3 gaussianTap(vec2 uv) {
    // 9-tap gaussian along uBlurDir (direction * texelSize * radius).
    vec3 acc = sampleA(uv) * 0.227027;
    acc += sampleA(uv + uBlurDir * 1.384615) * 0.316216;
    acc += sampleA(uv - uBlurDir * 1.384615) * 0.316216;
    acc += sampleA(uv + uBlurDir * 3.230769) * 0.070270;
    acc += sampleA(uv - uBlurDir * 3.230769) * 0.070270;
    return acc;
}

void main() {
    vec4 baseA = sampleA4(vTex);

    if (uStage == STAGE_BLUR) {
        fragColor = vec4(gaussianTap(vTex), baseA.a);
        return;
    }

    if (uStage == STAGE_BRIGHT) {
        // p0.x = threshold
        vec3 c = max(baseA.rgb - vec3(uP0.x), vec3(0.0));
        fragColor = vec4(c, baseA.a);
        return;
    }

    if (uStage == STAGE_MIX) {
        vec4 processed = texture(uTexB, clamp(vTex, vec2(0.0), vec2(1.0)));
        fragColor = composite(baseA, processed, uBlend, uStrength);
        return;
    }

    if (uStage == STAGE_COMBINE) {
        vec4 s1 = texture(uTexA, clamp(vTex, vec2(0.0), vec2(1.0)));
        vec4 s2 = texture(uTexB, clamp(vTex, vec2(0.0), vec2(1.0)));
        vec3 combined = (s1.rgb * uCombineW.x + s2.rgb * uCombineW.y) * uTint;
        vec4 baseC = texture(uTexC, clamp(vTex, vec2(0.0), vec2(1.0)));
        fragColor = composite(baseC, vec4(combined, 1.0), uBlend, uStrength);
        return;
    }

    if (uStage == STAGE_SHADOW_SIL) {
        // p0 = offsetX, offsetY, spread (blur radius handled by the follow-up blur passes)
        vec2 offset = vec2(uP0.x, uP0.y);
        float spread = uP0.z;
        float m = 0.0;
        // Offset silhouette with a cheap dilation so `spread` fattens the shadow. Ternaries, not
        // `%` — the ES 2.0 build of this shader has no integer modulus.
        for (int i = 0; i < 4; ++i) {
            float ox = i == 0 ? -2.0 : (i == 1 ? -1.0 : (i == 2 ? 1.0 : 2.0));
            float oy = i == 0 ? -1.0 : (i == 1 ? -2.0 : (i == 2 ? 2.0 : 1.0));
            vec2 o = offset + vec2(ox, oy) * uTexelSize * spread * 6.0;
            m = max(m, sampleA4(vTex - o).a);
        }
        fragColor = vec4(vec3(m), 1.0);
        return;
    }

    if (uStage == STAGE_SHADOW_COMPOSITE) {
        // uTexA = blurred shadow mask, uTexB = base frame.
        vec4 base = texture(uTexB, clamp(vTex, vec2(0.0), vec2(1.0)));
        float mask = texture(uTexA, clamp(vTex, vec2(0.0), vec2(1.0))).r;
        // Behind: shadow shows through transparent/masked regions; plus a subtle inner-edge
        // shading so opaque frames still read as "lit from above-left".
        vec3 shadowColor = vec3(0.02, 0.02, 0.05);
        float behind = mask * (1.0 - base.a) * uStrength;
        vec3 rgb = base.rgb * base.a + shadowColor * behind;
        float inner = clamp(1.0 - base.a + mask, 0.0, 1.0);
        rgb *= 1.0 - 0.25 * uStrength * (1.0 - inner);
        fragColor = vec4(rgb, max(base.a, behind * 0.9));
        return;
    }

    // STAGE_SINGLE: full classic effect. The effect function folds `uStrength` into its own look
    // (legacy behaviour), so the composite uses full weight — the blend *mode* still applies.
    vec3 processed = effectColor(uEffectId, baseA.rgb, vTex);
    fragColor = composite(baseA, vec4(processed, baseA.a), uBlend, 1.0);
}
