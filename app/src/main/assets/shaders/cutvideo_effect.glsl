#version 300 es
// CutVideo effect shader.
//
// The colour math below is a line-by-line mirror of `cpp/filter_engine.cpp::applyColorTransform`
// and the effect functions in `applyEffect()`. Uniform values come from `NativeParamWriter` so the
// GPU preview, the CPU filter cards and the exported file all agree.
precision highp float;

in vec2 vTex;
out vec4 fragColor;

uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;

// --- look -----------------------------------------------------------------------------------------
uniform mat3 uFilterMatrix;
uniform vec3 uFilterOffset;
uniform float uFilterIntensity;
uniform float uGamma;

uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uExposure;
uniform float uTemperature;
uniform float uTint;
uniform float uVignette;
uniform float uSharpen;
uniform float uBlurRadius;

uniform int uChromaEnabled;
uniform vec3 uChromaKey;
uniform float uChromaSimilarity;
uniform float uChromaSmoothness;
uniform float uChromaSpill;

// --- frame ----------------------------------------------------------------------------------------
uniform float uAlpha;
uniform float uWipe;
uniform int uWipeMode;
uniform float uTime;
uniform float uAspect;

// --- keyframed transform --------------------------------------------------------------------------
uniform float uTransformScale;
uniform float uTransformRotation;   // degrees, clockwise
uniform vec2 uTransformOffset;      // frame fractions

// --- masks (real alpha masking; up to two stacked) -------------------------------------------------
uniform int uMaskCount;
uniform int uMaskType[2];           // 0 circle, 1 rect, 2 linear, 3 mirror, 4 freehand
uniform vec3 uMaskCenterScale[2];   // x, y, scale
uniform vec4 uMaskParams[2];        // rotation (radians), feather, opacity, expansion
uniform vec2 uMaskExtras[2];        // roundness, invert
uniform int uMaskFreehandIndex;     // which mask samples uMaskTexture, -1 when none
uniform sampler2D uMaskTexture;

// Up to four simultaneous effects, in panel order.
uniform int uEffectIds[4];
uniform float uEffectAmounts[4];

uniform vec3 uDuotoneShadow;
uniform vec3 uDuotoneHighlight;

const float PI = 3.14159265359;

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

vec3 sampleRgb(vec2 uv) {
    return texture(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

// --- transform & masks -------------------------------------------------------------------------------

vec2 transformUv(vec2 uv) {
    if (abs(uTransformScale - 1.0) < 0.001 && abs(uTransformRotation) < 0.001
        && abs(uTransformOffset.x) < 0.0005 && abs(uTransformOffset.y) < 0.0005) {
        return uv;
    }
    vec2 p = uv - 0.5 - uTransformOffset;
    p.x *= uAspect;
    float r = radians(-uTransformRotation);
    float cs = cos(r);
    float sn = sin(r);
    p = vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
    p /= max(uTransformScale, 0.001);
    p.x /= uAspect;
    return p + 0.5;
}

float uvInBounds(vec2 uv) {
    return step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
}

// Rotates `uv` into a mask's local frame (aspect-corrected space).
vec2 maskLocal(vec2 uv, vec2 center, float rot) {
    vec2 p = (uv - center) * vec2(uAspect, 1.0);
    float cs = cos(-rot);
    float sn = sin(-rot);
    return vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
}

// All mask values are passed as plain parameters (not uniform-array indices) so the ES 2.0 build
// of this shader only ever indexes uniform arrays from the constant loop index in main().
float maskCoverageValues(vec2 uv, int type, vec2 center, float scale, float rot,
                         float feather, float expansion, float roundness, int isFreehand) {
    float featherC = clamp(feather, 0.0, 1.0);
    if (type == 4) {
        // Freehand: sample the painted coverage through the mask transform.
        if (isFreehand == 0) return 1.0;
        vec2 p = uv - center;
        float cs = cos(-rot);
        float sn = sin(-rot);
        p = vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
        p /= max(scale, 0.001);
        vec2 tuv = p + 0.5;
        if (tuv.x < 0.0 || tuv.x > 1.0 || tuv.y < 0.0 || tuv.y > 1.0) return 0.0;
        return texture(uMaskTexture, tuv).r;
    }
    vec2 p = maskLocal(uv, center, rot);
    if (type == 0) {
        float radius = max(scale * (1.0 + expansion), 0.0005);
        float d = length(p);
        return 1.0 - smoothstep(radius * (1.0 - featherC), radius + featherC * scale + 1e-5, d);
    }
    if (type == 1) {
        vec2 h = vec2(max(scale * (1.0 + expansion), 0.0005));
        float round = clamp(roundness, 0.0, 1.0) * min(h.x, h.y);
        vec2 q = abs(p) - h + vec2(round);
        float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - round;
        return 1.0 - smoothstep(-featherC * scale, featherC * scale + 1e-5, d);
    }
    if (type == 2) {
        // Linear: a feathered ramp along the mask's local Y axis.
        float halfSpan = max(scale * (1.0 + expansion), 0.0005);
        float t = abs(p.y) / halfSpan;
        return 1.0 - smoothstep(1.0 - max(featherC, 0.001), 1.0, t);
    }
    if (type == 3) {
        // Mirror: a symmetric feathered band around the reflection axis.
        float halfSpan = max(scale * (1.0 + expansion), 0.0005);
        float t = abs(p.y) / halfSpan;
        return 1.0 - smoothstep(1.0 - max(featherC, 0.001), 1.0, t);
    }
    return 1.0;
}

// Mirrors `uv` across a mask's local axis (used by the reflection-style mask).
vec2 maskMirrorUv(vec2 uv, vec2 center, float rot) {
    vec2 p = maskLocal(uv, center, rot);
    p.y = -p.y;
    float cs = cos(rot);
    float sn = sin(rot);
    p = vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
    p.x /= uAspect;
    return p + center;
}

vec3 applyColor(vec3 c) {
    if (uFilterIntensity > 0.0001) {
        c = uFilterMatrix * clamp(c, 0.0, 1.0) + uFilterOffset;
    }
    c *= pow(2.0, uExposure);
    c += uBrightness * 0.5;
    c = (c - 0.5) * (1.0 + uContrast) + 0.5;
    float l = luma(c);
    c = mix(vec3(l), c, 1.0 + uSaturation);
    c.r += uTemperature * 0.10;
    c.b -= uTemperature * 0.10;
    c.g -= uTint * 0.07;
    c.r += uTint * 0.02;
    c.b += uTint * 0.02;
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / max(uGamma, 0.05)));
    return clamp(c, 0.0, 1.0);
}

vec3 blur9(vec2 uv, float radiusPx) {
    vec2 step = uTexelSize * max(radiusPx, 0.0) * 0.5;
    vec3 acc = vec3(0.0);
    acc += sampleRgb(uv + vec2(-step.x, -step.y));
    acc += sampleRgb(uv + vec2(0.0, -step.y));
    acc += sampleRgb(uv + vec2(step.x, -step.y));
    acc += sampleRgb(uv + vec2(-step.x, 0.0));
    acc += sampleRgb(uv);
    acc += sampleRgb(uv + vec2(step.x, 0.0));
    acc += sampleRgb(uv + vec2(-step.x, step.y));
    acc += sampleRgb(uv + vec2(0.0, step.y));
    acc += sampleRgb(uv + vec2(step.x, step.y));
    return acc / 9.0;
}

vec3 applyOneEffect(vec3 color, vec2 uv, int id, float amount) {
    if (amount <= 0.001) return color;

    if (id == 1) {           // film grain
        float n = hash21(uv * 1024.0 + vec2(floor(uTime * 24.0) * 7.13));
        return clamp(color + (n - 0.5) * 2.0 * amount * 0.18, 0.0, 1.0);
    } else if (id == 2) {    // vignette
        vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
        float r = clamp(length(p) / 0.72, 0.0, 1.0);
        float f = 1.0 - amount * smoothstep(0.35, 1.0, r);
        return color * f;
    } else if (id == 3) {    // scanlines
        float line = mod(floor(uv.y * 480.0), 3.0);
        float f = line < 1.0 ? 1.0 - 0.35 * amount : 1.0;
        return vec3(color.r * f, color.g * f * (1.0 + 0.05 * amount), color.b * f);
    } else if (id == 4) {    // glitch
        float band = floor(uv.y * 40.0);
        float rnd = hash11(band * 0.37 + floor(uTime * 9.0) * 1.7);
        vec2 shift = vec2((rnd > 0.82 ? (rnd - 0.82) * 4.0 * amount : 0.0), 0.0);
        float ca = amount * 0.006;
        vec3 c;
        c.r = sampleRgb(uv + shift + vec2(ca, 0.0)).r;
        c.g = sampleRgb(uv + shift).g;
        c.b = sampleRgb(uv + shift - vec2(ca, 0.0)).b;
        return c;
    } else if (id == 5) {    // chromatic aberration
        float ca = amount * 0.004;
        vec3 c;
        c.r = sampleRgb(uv + vec2(ca, 0.0)).r;
        c.g = color.g;
        c.b = sampleRgb(uv - vec2(ca, 0.0)).b;
        return c;
    } else if (id == 6) {    // bloom
        vec3 glow = blur9(uv, 6.0 + 10.0 * amount);
        glow = max(glow - 0.55, 0.0) * 2.2;
        return clamp(color + glow * amount * 1.1, 0.0, 1.0);
    } else if (id == 7) {    // light leak
        float cx = 0.15 + 0.7 * (0.5 + 0.5 * sin(uTime * 0.6));
        float cy = 0.85 - 0.2 * sin(uTime * 0.43);
        vec2 d = vec2((uv.x - cx) * uAspect, uv.y - cy);
        float leak = (1.0 - smoothstep(0.0, 0.9, length(d))) * amount * 0.85;
        return clamp(color + vec3(0.95, 0.55, 0.18) * leak, 0.0, 1.0);
    } else if (id == 8) {    // pulse
        float amp = 1.0 + 0.12 * amount * sin(uTime * 6.0);
        return color * amp;
    } else if (id == 9) {    // mirror
        vec2 muv = vec2(uv.x < 0.5 ? uv.x : 1.0 - uv.x, uv.y);
        return mix(color, sampleRgb(muv), amount);
    } else if (id == 10) {   // posterize
        float levels = max(2.0, 2.0 + (1.0 - amount) * 10.0);
        return floor(color * levels) / (levels - 1.0);
    } else if (id == 11) {   // duotone
        float l = luma(color);
        vec3 duo = mix(uDuotoneShadow, uDuotoneHighlight, l);
        return mix(color, duo, amount);
    } else if (id == 12) {   // sharpen
        vec3 blur = blur9(uv, 2.0);
        return clamp(color + (color - blur) * amount * 1.4, 0.0, 1.0);
    } else if (id == 13) {   // vhs
        float jitter = (hash11(floor(uv.y * 240.0) * 0.21 + floor(uTime * 12.0)) - 0.5) * 0.004 * amount;
        vec3 c = sampleRgb(uv + vec2(jitter, 0.0));
        if (mod(floor(uv.y * 480.0), 2.0) < 1.0) c *= 1.0 - 0.18 * amount;
        float n = hash21(uv * 900.0 + uTime);
        c += (n - 0.5) * 0.10 * amount;
        return mix(c, vec3(luma(c)), 0.25 * amount);
    } else if (id == 14) {   // snow
        float cells = 260.0;
        vec2 g = floor(uv * cells);
        float n = hash21(g);
        float fall = fract(hash21(g + 3.7) + uTime * (0.15 + n * 0.4));
        float dotSize = smoothstep(0.30, 0.0, length(fract(uv * cells) - 0.5));
        float visible = step(0.62 - amount * 0.35, hash21(g * 1.7));
        float snow = dotSize * visible * step(0.02, abs(uv.y - fall) + 0.02);
        return mix(color, vec3(1.0), clamp(snow * (0.35 + 0.5 * amount), 0.0, 1.0));
    } else if (id == 15) {   // zoom blur
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 6; ++i) {
            float s = 1.0 - 0.06 * amount * (float(i) / 5.0);
            acc += sampleRgb((uv - 0.5) * s + 0.5);
        }
        return mix(color, acc / 6.0, 0.85);
    } else if (id == 16) {   // blur
        return blur9(uv, 3.0 + 24.0 * amount);
    } else if (id == 17) {   // pixelate
        float cells = 22.0 + (1.0 - amount) * 170.0;
        vec2 snapped = (floor(uv * cells) + 0.5) / cells;
        return sampleRgb(snapped);
    } else if (id == 18) {   // neon edge glow
        float lx = luma(sampleRgb(uv + vec2(uTexelSize.x * 2.0, 0.0))) -
                   luma(sampleRgb(uv - vec2(uTexelSize.x * 2.0, 0.0)));
        float ly = luma(sampleRgb(uv + vec2(0.0, uTexelSize.y * 2.0))) -
                   luma(sampleRgb(uv - vec2(0.0, uTexelSize.y * 2.0)));
        float edge = clamp(length(vec2(lx, ly)) * 3.0, 0.0, 1.0);
        vec3 tint = mix(vec3(0.15, 0.95, 1.0), vec3(1.0, 0.25, 0.85),
                        0.5 + 0.5 * sin(uv.y * 3.2 + uTime * 0.8));
        return clamp(color * (1.0 - 0.45 * amount) + tint * edge * amount * 1.6, 0.0, 1.0);
    } else if (id == 19) {   // halftone print
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float cellPx = 7.0 - 2.0 * amount;
        vec2 f = fract(px / cellPx) - 0.5;
        float d = length(f);
        float l = luma(color);
        float radius = 0.5 * sqrt(max(0.0, 1.0 - l));
        float ink = 1.0 - smoothstep(radius - 0.05, radius + 0.05, d);
        float v = mix(0.94 - 0.08 * l, 0.05, ink);
        return mix(color, vec3(v * 1.04, v, v * 0.93), amount);
    } else if (id == 20) {   // tilt shift
        float ny = abs(uv.y - 0.5) * 2.0;
        float strength = smoothstep(0.12, 0.8, ny) * amount;
        return strength > 0.01 ? blur9(uv, strength * 30.0) : color;
    } else if (id == 21) {   // old film
        vec3 sepia = vec3(0.393 * color.r + 0.769 * color.g + 0.189 * color.b,
                          0.349 * color.r + 0.686 * color.g + 0.168 * color.b,
                          0.272 * color.r + 0.534 * color.g + 0.131 * color.b);
        float flick = 1.0 + 0.05 * sin(uTime * 9.0) + 0.04 * (hash11(floor(uTime * 12.0)) - 0.5);
        vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
        float vig = 1.0 - amount * 0.65 * smoothstep(0.15, 0.85, length(p));
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float scratch = step(0.9965, hash21(vec2(floor(px.x * 0.5), floor(uTime * 8.0))));
        vec3 c = mix(color, sepia, amount) * flick * vig;
        return mix(c, vec3(0.96), scratch * amount * 0.35);
    } else if (id == 22) {   // heat wave
        vec2 px = uv / max(uTexelSize, vec2(1e-6));
        float shift = (sin(px.y * 0.09 + uTime * 3.1) * 7.0 + sin(px.y * 0.31 - uTime * 1.7) * 3.0) * amount;
        return sampleRgb(uv + vec2(shift * uTexelSize.x, 0.0));
    } else if (id == 23) {   // spotlight
        vec2 p = (uv - vec2(0.5, 0.42)) * vec2(uAspect, 1.0);
        float r = length(p) / 0.62;
        float light = 1.0 - smoothstep(0.1, 1.0, r);
        float gain = mix(1.0 - 0.8 * amount, 1.0 + 0.25 * amount, light);
        return clamp(color * gain * vec3(1.0 + 0.06 * amount * light,
                                         1.0 + 0.02 * amount * light,
                                         1.0 - 0.04 * amount * light), 0.0, 1.0);
    }
    return color;
}

void main() {
    vec2 uv = transformUv(vTex);
    float bounds = uvInBounds(uv);

    vec3 color;
    if (uBlurRadius > 0.4) {
        color = blur9(uv, uBlurRadius);
    } else {
        color = sampleRgb(uv);
    }

    color = applyColor(color);

    for (int i = 0; i < 4; ++i) {
        color = applyOneEffect(color, uv, uEffectIds[i], uEffectAmounts[i]);
    }

    if (uSharpen > 0.001) {
        vec3 blur = blur9(uv, 2.0);
        color = clamp(color + (color - blur) * uSharpen * 1.4, 0.0, 1.0);
    }

    if (uVignette > 0.001) {
        vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
        float r = clamp(length(p) / 0.72, 0.0, 1.0);
        color *= 1.0 - uVignette * smoothstep(0.35, 1.0, r);
    }

    float alpha = uAlpha;

    if (uChromaEnabled == 1) {
        float y = luma(color);
        vec2 chroma = vec2(color.b - y, color.r - y);
        float keyY = luma(uChromaKey);
        vec2 keyChroma = vec2(uChromaKey.b - keyY, uChromaKey.r - keyY);
        float dist = length(chroma - keyChroma) * 1.6;
        float sim = clamp(uChromaSimilarity, 0.01, 1.0);
        float smoothness = clamp(uChromaSmoothness, 0.0, 0.9);
        float a = smoothness <= 0.001
            ? (dist < sim ? 0.0 : 1.0)
            : smoothstep(sim * (1.0 - smoothness), sim * (1.0 + smoothness * 0.5) + 0.0001, dist);
        alpha *= a;
        // Spill suppression pulls the dominant key channel toward luma.
        vec3 keyAbs = abs(uChromaKey);
        if (uChromaSpill > 0.0 && a > 0.01) {
            float dominant = max(keyAbs.r, max(keyAbs.g, keyAbs.b));
            if (keyAbs.g == dominant) {
                color.g = mix(color.g, (color.r + color.b) * 0.5, uChromaSpill);
            } else if (keyAbs.r == dominant) {
                color.r = mix(color.r, (color.g + color.b) * 0.5, uChromaSpill);
            } else {
                color.b = mix(color.b, (color.r + color.g) * 0.5, uChromaSpill);
            }
        }
    }

    if (uWipe < 0.999) {
        if (uWipeMode == 1) {
            vec2 p = uv - 0.5;
            float r = length(p * vec2(uAspect, 1.0)) / 0.72;
            if (r > uWipe) alpha = 0.0;
        } else {
            if (uv.x > uWipe) alpha = 0.0;
        }
    }

    // Keyframed transforms crop the frame to the transformed region.
    alpha *= bounds;

    // Masks: each one multiplies the frame's real alpha; reflection-style masks also mirror the
    // visible content across their axis. Uniform arrays are only indexed by the constant loop
    // index (required for the ES 2.0 build).
    if (uMaskCount > 0) {
        float cover = 1.0;
        for (int i = 0; i < 2; ++i) {
            if (i < uMaskCount) {
                float c = maskCoverageValues(
                    uv, uMaskType[i], uMaskCenterScale[i].xy, uMaskCenterScale[i].z,
                    uMaskParams[i].x, uMaskParams[i].y, uMaskParams[i].w, uMaskExtras[i].x,
                    uMaskFreehandIndex == i ? 1 : 0);
                if (uMaskType[i] == 3 && c > 0.001) {
                    vec2 muv = maskMirrorUv(uv, uMaskCenterScale[i].xy, uMaskParams[i].x);
                    color = mix(color, sampleRgb(muv), c * clamp(uMaskParams[i].z, 0.0, 1.0));
                }
                c = mix(c, 1.0 - c, clamp(uMaskExtras[i].y, 0.0, 1.0));
                cover *= mix(1.0, clamp(c, 0.0, 1.0), clamp(uMaskParams[i].z, 0.0, 1.0));
            }
        }
        alpha *= cover;
    }

    fragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
