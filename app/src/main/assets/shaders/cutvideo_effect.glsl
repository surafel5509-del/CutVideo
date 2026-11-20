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
    }
    return color;
}

void main() {
    vec2 uv = vTex;

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

    fragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
