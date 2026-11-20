// Host-side unit tests for the CutVideo native engine.
//
// These compile the *exact* source files that ship in the APK (everything except jni_bridge.cpp,
// which needs <jni.h>), so the colour pipeline, audio DSP and timeline math are verified before the
// Android build. Run with: tools/run_native_tests.sh
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../app/src/main/cpp/audio_processor.h"
#include "../app/src/main/cpp/filter_engine.h"
#include "../app/src/main/cpp/native_engine.h"
#include "../app/src/main/cpp/timeline_renderer.h"
#include "../app/src/main/cpp/video_processor.h"

using namespace cutvideo;

namespace {

int gChecks = 0;
int gFailures = 0;

void check(bool condition, const std::string& what) {
    ++gChecks;
    if (!condition) {
        ++gFailures;
        std::printf("  FAIL: %s\n", what.c_str());
    }
}

void checkNear(float a, float b, float tolerance, const std::string& what) {
    ++gChecks;
    if (std::fabs(a - b) > tolerance) {
        ++gFailures;
        std::printf("  FAIL: %s (%.4f vs %.4f, tol %.4f)\n", what.c_str(), a, b, tolerance);
    }
}

struct TestImage {
    std::vector<uint8_t> pixels;
    Image view;
    TestImage(int w, int h) : pixels(static_cast<size_t>(w) * h * 4, 0), view(pixels.data(), w, h) {}
};

void setPixel(TestImage& img, int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a = 255) {
    uint8_t* p = img.view.row(y) + x * 4;
    p[0] = r; p[1] = g; p[2] = b; p[3] = a;
}

void getPixel(const TestImage& img, int x, int y, uint8_t out[4]) {
    const uint8_t* p = img.view.row(y) + x * 4;
    std::memcpy(out, p, 4);
}

void section(const char* name) { std::printf("[test] %s\n", name); }

// ------------------------------------------------------------------------------------------------
void testImagePrimitives() {
    section("image primitives");
    TestImage img(2, 2);
    setPixel(img, 0, 0, 255, 0, 0);
    setPixel(img, 1, 0, 0, 255, 0);
    setPixel(img, 0, 1, 0, 0, 255);
    setPixel(img, 1, 1, 255, 255, 255);

    TestImage flipped(2, 2);
    copyImage(img.view, flipped.view);
    flipHorizontal(flipped.view);
    uint8_t px[4];
    getPixel(flipped, 0, 0, px);
    check(px[1] == 255 && px[0] == 0, "flipHorizontal moves right pixel to the left");

    TestImage rotated(2, 4);  // dst must be height x width for the 2x2 source? 2x2 -> 2x2
    TestImage square(2, 2);
    copyImage(img.view, square.view);
    TestImage rotDst(2, 2);
    rotate90(square.view, rotDst.view, true);
    getPixel(rotDst, 1, 0, px);
    check(px[0] == 255 && px[1] == 0, "rotate90 clockwise maps (0,0) -> (w-1,0)");

    TestImage cropSrc(4, 4);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) setPixel(cropSrc, x, y, static_cast<uint8_t>(x * 20), static_cast<uint8_t>(y * 20), 0);
    }
    TestImage cropDst(2, 2);
    cropImage(cropSrc.view, cropDst.view, 1, 2, 2, 2);
    getPixel(cropDst, 0, 0, px);
    check(px[0] == 20 && px[1] == 40, "cropImage copies the requested rect");

    TestImage small(2, 2);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) setPixel(small, x, y, 100, 150, 200);
    }
    TestImage scaled(4, 4);
    scaleBilinear(small.view, scaled.view);
    getPixel(scaled, 2, 2, px);
    check(std::abs(px[0] - 100) <= 2 && std::abs(px[2] - 200) <= 2, "scaleBilinear upscales a flat colour exactly");

    TestImage blur(9, 9);
    fillImage(blur.view, 0, 0, 0, 255);
    setPixel(blur, 4, 4, 255, 255, 255);
    applyBlur(blur.view, 2.0f);
    getPixel(blur, 5, 4, px);
    check(px[0] > 0, "applyBlur spreads an impulse to neighbours");
    getPixel(blur, 4, 4, px);
    check(px[0] < 255, "applyBlur lowers the impulse peak");
}

// ------------------------------------------------------------------------------------------------
ColorParams neutralParams() {
    ColorParams p;
    p.filterIntensity = 0.0f;
    p.aspect = 16.0f / 9.0f;
    return p;
}

void testColorPipeline() {
    section("colour pipeline");
    ColorParams p = neutralParams();

    TestImage img(3, 3);
    fillImage(img.view, 40, 90, 160, 255);
    applyColorPipelineWithEffects(img.view, p, nullptr, 0);
    uint8_t px[4];
    getPixel(img, 1, 1, px);
    check(px[0] == 40 && px[1] == 90 && px[2] == 160, "neutral params leave pixels untouched");

    ColorParams bright = neutralParams();
    bright.brightness = 0.2f;  // +0.1 in 0..1 space -> +25.5 levels
    TestImage brightImg(1, 1);
    fillImage(brightImg.view, 100, 100, 100, 255);
    applyColorPipelineWithEffects(brightImg.view, bright, nullptr, 0);
    getPixel(brightImg, 0, 0, px);
    check(std::abs(px[0] - 126) <= 2, "brightness +0.2 raises luminance by ~25 levels");

    ColorParams bw = neutralParams();
    bw.filterIntensity = 1.0f;
    const float bwMatrix[9] = {0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f, 0.299f, 0.587f, 0.114f};
    std::memcpy(bw.filterMatrix, bwMatrix, sizeof(bwMatrix));
    TestImage bwImg(1, 1);
    fillImage(bwImg.view, 200, 100, 50, 255);
    applyColorPipelineWithEffects(bwImg.view, bw, nullptr, 0);
    getPixel(bwImg, 0, 0, px);
    check(std::abs(px[0] - px[1]) <= 1 && std::abs(px[1] - px[2]) <= 1, "B&W matrix produces grey");

    ColorParams desat = neutralParams();
    desat.saturation = -1.0f;
    TestImage desatImg(1, 1);
    fillImage(desatImg.view, 200, 40, 40, 255);
    applyColorPipelineWithEffects(desatImg.view, desat, nullptr, 0);
    getPixel(desatImg, 0, 0, px);
    check(std::abs(px[0] - px[1]) <= 2, "saturation -1 desaturates");

    ColorParams half = neutralParams();
    half.filterIntensity = 0.5f;
    std::memcpy(half.filterMatrix, bwMatrix, sizeof(bwMatrix));
    TestImage halfImg(1, 1);
    fillImage(halfImg.view, 200, 100, 50, 255);
    applyColorPipelineWithEffects(halfImg.view, half, nullptr, 0);
    getPixel(halfImg, 0, 0, px);
    check(px[0] < 200 && px[0] > px[2], "filterIntensity 0.5 blends halfway toward the preset");

    // Filter matrix resolution helper.
    const float preset3x4[12] = {2, 0, 0, 0.1f, 0, 2, 0, 0.1f, 0, 0, 2, 0.1f};
    const ColorMatrix cm = blendMatrix3x4(preset3x4, 0.5f);
    checkNear(cm.m[0], 1.5f, 0.0001f, "blendMatrix3x4 blends the diagonal");
    checkNear(cm.o[0], 0.05f, 0.0001f, "blendMatrix3x4 blends the offset");
}

// ------------------------------------------------------------------------------------------------
void testEffects() {
    section("effects");
    uint8_t px[4];

    TestImage posterize(4, 4);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) setPixel(posterize, x, y, static_cast<uint8_t>(x * 60), 0, 0);
    }
    applyEffect(posterize.view, EffectId::kPosterize, 1.0f, 0.0f, 1.0f);
    getPixel(posterize, 1, 0, px);
    check(px[0] == 0 || px[0] == 255, "posterize at full strength collapses to 2 levels");

    TestImage vignette(21, 21);
    fillImage(vignette.view, 200, 200, 200, 255);
    applyVignette(vignette.view, 1.0f);
    uint8_t centre[4], corner[4];
    getPixel(vignette, 10, 10, centre);
    getPixel(vignette, 0, 0, corner);
    check(corner[0] < centre[0], "vignette darkens the corners");

    TestImage key(2, 1);
    setPixel(key, 0, 0, 0, 255, 0);
    setPixel(key, 1, 0, 220, 30, 30);
    ColorParams p = neutralParams();
    p.chromaEnabled = true;
    p.chromaKey[0] = 0.0f;
    p.chromaKey[1] = 1.0f;
    p.chromaKey[2] = 0.0f;
    p.chromaSimilarity = 0.35f;
    p.chromaSmoothness = 0.0f;
    p.chromaSpill = 0.0f;
    applyChromaKey(key.view, p);
    getPixel(key, 0, 0, px);
    check(px[3] == 0, "chroma key makes the key colour transparent");
    getPixel(key, 1, 0, px);
    check(px[3] == 255, "chroma key keeps non-key colours opaque");

    TestImage mirror(8, 4);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 8; ++x) setPixel(mirror, x, y, static_cast<uint8_t>(x * 30), 0, 0);
    }
    applyEffect(mirror.view, EffectId::kMirror, 1.0f, 0.0f, 2.0f);
    getPixel(mirror, 7, 0, px);
    check(px[0] == 0, "mirror copies the left half onto the right");

    TestImage grain(8, 8);
    fillImage(grain.view, 128, 128, 128, 255);
    applyGrain(grain.view, 1.0f, 0.5f);
    int changed = 0;
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            getPixel(grain, x, y, px);
            if (px[0] != 128) ++changed;
        }
    }
    check(changed > 10, "grain perturbs a flat frame");

    // Second-wave effects (native ids 16..23): each must be reachable, change real pixels and
    // leave alpha untouched, so the GLSL/CPU pair can never silently no-op.
    for (int id = 16; id <= 23; ++id) {
        TestImage img(24, 24);
        for (int y = 0; y < 24; ++y) {
            for (int x = 0; x < 24; ++x) {
                setPixel(img, x, y, static_cast<uint8_t>(x * 9), static_cast<uint8_t>(y * 9),
                         static_cast<uint8_t>((x + y) * 4), 255);
            }
        }
        std::vector<uint8_t> before(static_cast<size_t>(img.view.stride) * 24);
        std::memcpy(before.data(), img.view.data, before.size());
        applyEffect(img.view, static_cast<EffectId>(id), 1.0f, 1.5f, 1.0f);
        int changedPixels = 0;
        bool alphaIntact = true;
        for (int y = 0; y < 24; ++y) {
            const uint8_t* row = img.view.row(y);
            for (int x = 0; x < 24; ++x) {
                if (std::memcmp(row + x * 4, before.data() + static_cast<size_t>(y) * img.view.stride + x * 4, 4) != 0) {
                    ++changedPixels;
                }
                if (row[x * 4 + 3] != 255) alphaIntact = false;
            }
        }
        check(changedPixels > 20, "effect " + std::to_string(id) + " changes the frame");
        check(alphaIntact, "effect " + std::to_string(id) + " keeps the frame opaque");
    }

    // Both channels of the effect stack must run, in order.
    TestImage stack(4, 4);
    fillImage(stack.view, 90, 60, 30, 255);
    ColorParams sp = neutralParams();
    sp.alpha = 0.5f;
    applyColorPipelineWithEffects(stack.view, sp, nullptr, 0);
    getPixel(stack, 0, 0, px);
    check(std::abs(px[3] - 128) <= 2, "alpha 0.5 halves the frame opacity");
}

// ------------------------------------------------------------------------------------------------
void testLut() {
    section("3D LUT");
    ColorParams p = neutralParams();
    p.brightness = 0.1f;
    p.saturation = 0.2f;

    std::vector<uint8_t> lut(16 * 16 * 16 * 4);
    generateLut3d(p, lut.data());
    check(lut[3] == 255, "LUT alpha channel is opaque");

    // Direct transform vs LUT lookup must agree away from the clipping edges.
    float direct[3] = {0.4f, 0.5f, 0.6f};
    applyColorTransform(direct[0], direct[1], direct[2], p);
    float viasLut[3];
    sampleLut3d(lut.data(), 0.4f, 0.5f, 0.6f, viasLut);
    checkNear(viasLut[0], direct[0], 0.03f, "LUT lookup matches the direct transform (R)");
    checkNear(viasLut[2], direct[2], 0.03f, "LUT lookup matches the direct transform (B)");

    // Neutral LUT should be near-identity.
    std::vector<uint8_t> identity(16 * 16 * 16 * 4);
    generateLut3d(neutralParams(), identity.data());
    float id[3];
    sampleLut3d(identity.data(), 0.25f, 0.5f, 0.75f, id);
    checkNear(id[0], 0.25f, 0.02f, "neutral LUT is identity in R");
    checkNear(id[1], 0.50f, 0.02f, "neutral LUT is identity in G");
}

// ------------------------------------------------------------------------------------------------
void testAudio() {
    section("audio DSP");
    const int rate = 48000;

    std::vector<int16_t> pcm(4800 * 2, 16000);  // 0.1s stereo, constant level
    GainSegment ramp{0, 100000, 0.0f, 1.0f};
    applyGainAutomation(pcm.data(), 4800, 2, rate, &ramp, 1, 0, 1.0f);
    check(std::abs(pcm[0]) < 300, "gain automation starts near silence");
    check(std::abs(pcm[(4799) * 2]) > 15000, "gain automation reaches full level");

    std::vector<int16_t> fade(4800, 16000);
    applyFade(fade.data(), 4800, 1, rate, 100000, 0, 0, 200000);  // 0.1s fade-in of a 0.2s clip
    check(std::abs(fade[0]) < 200, "fade-in starts at zero");
    check(std::abs(fade[2400]) > 6000 && std::abs(fade[2400]) < 10000, "fade-in is ~50% at the midpoint");
    check(std::abs(fade[4799]) > 15000, "fade-in is complete at the end of the fade");

    std::vector<int16_t> a = {10000, -10000, 30000};
    std::vector<int16_t> b = {5000, 5000, 5000};
    std::vector<int16_t> mix(3);
    mixPcm16(a.data(), b.data(), mix.data(), 3, 1.0f, 1.0f);
    check(mix[0] == 15000, "mix sums samples with unity gain");
    check(mix[1] == -5000, "mix handles negative samples");
    check(static_cast<int>(mix[2]) > 20000 && static_cast<int>(mix[2]) <= 32767, "mix soft-limits overflow");

    std::vector<int16_t> wave(1000, 0);
    for (int i = 0; i < 1000; ++i) wave[i] = (i % 2 == 0) ? 30000 : -30000;
    std::vector<float> peaks(10, 0.0f);
    computeWaveformPeaks(wave.data(), 1000, 1, 10, peaks.data());
    check(peaks.size() == 10, "waveform returns the requested bucket count");
    checkNear(peaks[0], 0.915f, 0.01f, "waveform peak reflects full-scale audio");

    std::vector<int16_t> src(4800 * 2, 1000);
    std::vector<int16_t> dst(4800 * 2, 0);
    const int outFrames = resampleLinear(src.data(), 4800, 2, 48000, 24000, dst.data(), 4800);
    check(std::abs(outFrames - 2400) <= 2, "resample halves frame count for 48k -> 24k");

    std::vector<int16_t> loud = {32000, -32000};
    softClip(loud.data(), 2, 3.0f);
    check(std::abs(loud[0]) < 32767, "soft clip keeps samples in range");

    check(gainAtTime(nullptr, 0, 0) == 1.0f, "gainAtTime without automation is unity");
    GainSegment two[2] = {{0, 1000, 0.0f, 1.0f}, {1000, 2000, 1.0f, 0.0f}};
    checkNear(gainAtTime(two, 2, 1500), 0.5f, 0.01f, "gainAtTime interpolates the second segment");
}

// ------------------------------------------------------------------------------------------------
ClipDesc makeClip(int64_t start, int64_t duration, float speed = 1.0f) {
    ClipDesc c;
    c.trackType = static_cast<int32_t>(TrackType::kVideo);
    c.startUs = start;
    c.durationUs = duration;
    c.sourceInUs = 0;
    c.sourceDurationUs = duration;
    c.speed = speed;
    c.volume = 1.0f;
    return c;
}

void testTimeline() {
    section("timeline math");
    // Every motion/transition declared in the header must resolve to a finished, fully visible
    // state at progress 1 so the Kotlin mirror (AnimationEngine) and the pre-render agree.
    for (int i = 1; i <= static_cast<int>(AnimationType::kGlitchIn); ++i) {
        const AnimationState s = sampleAnimation(static_cast<AnimationType>(i), 1.0f);
        check(s.alpha >= 0.999f && s.alpha <= 1.0001f,
              "animation " + std::to_string(i) + " ends fully opaque");
        check(s.scale > 0.0f, "animation " + std::to_string(i) + " keeps a positive scale");
    }
    for (int i = 0; i <= static_cast<int>(TransitionType::kBlur); ++i) {
        const AnimationState s = sampleTransition(static_cast<TransitionType>(i), 1.0f);
        check(s.alpha >= 0.999f && s.wipe >= 0.999f && std::fabs(s.translateX) < 0.001f &&
                  std::fabs(s.translateY) < 0.001f,
              "transition " + std::to_string(i) + " completes at progress 1");
    }
    ClipDesc clip = makeClip(1'000'000, 2'000'000, 2.0f);
    check(!clipContains(clip, 999'999), "clip does not contain time before the start");
    check(clipContains(clip, 1'000'000), "clip contains its start time");
    check(!clipContains(clip, 3'000'000), "clip end is exclusive");
    check(sourceTimeUs(clip, 1'000'000) == 0, "source time at clip start is sourceIn");
    check(sourceTimeUs(clip, 2'000'000) == 2'000'000, "speed 2 doubles source consumption");

    ClipDesc reversed = makeClip(0, 2'000'000, 1.0f);
    reversed.reversed = 1;
    check(sourceTimeUs(reversed, 0) == 2'000'000, "reverse starts at the source tail");

    ClipDesc fadeClip = makeClip(0, 1'000'000, 1.0f);
    fadeClip.fadeInUs = 500'000;
    fadeClip.fadeOutUs = 200'000;
    checkNear(gainAtTimeline(fadeClip, 0), 0.0f, 0.001f, "fade in starts silent");
    checkNear(gainAtTimeline(fadeClip, 250'000), 0.5f, 0.01f, "fade in is half way at half the fade");
    checkNear(gainAtTimeline(fadeClip, 1'000'000), 0.0f, 0.01f, "fade out ends silent");
    fadeClip.muted = 1;
    checkNear(gainAtTimeline(fadeClip, 500'000), 0.0f, 0.001f, "muted clip has zero gain");

    ClipDesc anim = makeClip(0, 1'000'000, 1.0f);
    anim.animationInType = static_cast<int32_t>(AnimationType::kFade);
    anim.animationInUs = 400'000;
    checkNear(composeState(anim, 0).alpha, 0.0f, 0.01f, "fade animation starts transparent");
    checkNear(composeState(anim, 400'000).alpha, 1.0f, 0.01f, "fade animation ends opaque");
    checkNear(composeState(anim, 800'000).alpha, 1.0f, 0.01f, "animation does not affect later frames");

    ClipDesc zoom = makeClip(0, 1'000'000, 1.0f);
    zoom.animationInType = static_cast<int32_t>(AnimationType::kZoomIn);
    zoom.animationInUs = 500'000;
    check(composeState(zoom, 0).scale < 0.7f, "zoom-in starts smaller than the frame");
    checkNear(composeState(zoom, 500'000).scale, 1.0f, 0.01f, "zoom-in settles at 1.0");

    ClipDesc pop = makeClip(0, 1'000'000, 1.0f);
    pop.animationInType = static_cast<int32_t>(AnimationType::kPop);
    pop.animationInUs = 500'000;
    check(composeState(pop, 0).scale <= 0.01f, "pop starts from zero scale");
    check(composeState(pop, 500'000).scale > 0.99f, "pop settles at full scale");

    ClipDesc wipeClip = makeClip(0, 1'000'000, 1.0f);
    wipeClip.transitionInType = static_cast<int32_t>(TransitionType::kWipe);
    wipeClip.transitionInUs = 500'000;
    checkNear(composeState(wipeClip, 250'000).wipe, 0.5f, 0.01f, "wipe transition is 50% at the midpoint");

    ClipDesc a = makeClip(0, 1'000'000);
    ClipDesc b = makeClip(1'000'000, 1'000'000);
    ClipDesc clips[2] = {a, b};
    check(findActiveClip(clips, 2, TrackType::kVideo, 500'000) == 0, "active clip resolution finds the first clip");
    check(findActiveClip(clips, 2, TrackType::kVideo, 1'500'000) == 1, "active clip resolution finds the second clip");
    check(findActiveClip(clips, 2, TrackType::kVideo, 2'500'000) == -1, "no active clip after the timeline ends");
    check(findActiveClip(clips, 2, TrackType::kAudio, 500'000) == -1, "audio track query ignores video clips");

    const std::vector<GainSegment> automation = buildGainAutomation(clips, 2, TrackType::kVideo);
    check(!automation.empty(), "gain automation is generated for the video track");
}

// ------------------------------------------------------------------------------------------------
void testEngine() {
    section("native engine");
    NativeVideoEngine engine;
    check(engine.configure(64, 36), "engine configures a 64x36 output");
    check(!engine.configure(0, 10), "engine rejects an invalid size");

    ClipDesc clips[2] = {makeClip(0, 1'000'000), makeClip(1'000'000, 1'000'000)};
    engine.setTimeline(clips, 2);
    check(engine.clipCount() == 2, "engine stores the timeline");
    check(engine.activeVideoIndex(1'500'000) == 1, "engine resolves the second clip");
    check(engine.sourceTimeAt(1'500'000) == 500'000, "engine maps timeline to source time");

    ColorParams bright = neutralParams();
    bright.brightness = 0.3f;
    engine.setLook(1, bright, nullptr, 0);

    TestImage frame(4, 4);
    fillImage(frame.view, 80, 80, 80, 255);
    const int rendered = engine.renderFrame(frame.view, 1'200'000);
    check(rendered == 1, "renderFrame reports the active clip");
    uint8_t px[4];
    getPixel(frame, 0, 0, px);
    check(px[0] > 80, "renderFrame applies the clip look");

    TestImage untouched(4, 4);
    fillImage(untouched.view, 80, 80, 80, 255);
    check(engine.renderFrame(untouched.view, 2'500'000) == -1, "renderFrame returns -1 past the last clip");
    getPixel(untouched, 0, 0, px);
    check(px[0] == 0, "the engine fills the frame black when no clip is active");

    const std::string description = engine.describe();
    check(description.find("CutVideoEngine") != std::string::npos, "describe() reports the engine identity");
}

}  // namespace

int main() {
    std::printf("CutVideo native engine tests\n============================\n");
    testImagePrimitives();
    testColorPipeline();
    testEffects();
    testLut();
    testAudio();
    testTimeline();
    testEngine();
    std::printf("============================\n%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
