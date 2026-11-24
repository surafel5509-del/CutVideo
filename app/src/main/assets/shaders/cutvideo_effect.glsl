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

// Effects are applied by the ordered effect-chain passes (`cutvideo_pass.glsl`), not inline here:
// this shader owns colour grading, masks, keyframed transform and wipes only.

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

    // Effects render as ordered chain passes (cutvideo_pass.glsl) after this program.

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
