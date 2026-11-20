// CutVideo native engine — JNI bridge.
//
// Every function here is a thin, allocation-light conversion layer: the actual work happens in
// native_engine.cpp / filter_engine.cpp / audio_processor.cpp / timeline_renderer.cpp so it can be
// unit tested on the host (see tools/run_native_tests.sh).
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "audio_processor.h"
#include "filter_engine.h"
#include "native_engine.h"
#include "timeline_renderer.h"
#include "video_processor.h"

using namespace cutvideo;

namespace {

/** Must match `NativeParams` in Kotlin (`nativebridge/NativeParams.kt`). */
constexpr int kParamBaseCount = 33;   // colour/look slots
constexpr int kParamCount = 35;       // + wipe mask slots (33 = wipe, 34 = wipe mode)

ColorParams readParams(JNIEnv* env, jfloatArray array) {
    ColorParams p;
    if (!array) return p;
    const jsize len = env->GetArrayLength(array);
    if (len < kParamBaseCount) return p;
    const jsize readCount = std::min<jsize>(len, kParamCount);
    jfloat buf[kParamCount] = {0};
    env->GetFloatArrayRegion(array, 0, readCount, buf);
    for (int i = 0; i < 9; ++i) p.filterMatrix[i] = buf[i];
    for (int i = 0; i < 3; ++i) p.filterOffset[i] = buf[9 + i];
    p.filterIntensity = buf[12];
    p.gamma = buf[13];
    p.brightness = buf[14];
    p.contrast = buf[15];
    p.saturation = buf[16];
    p.exposure = buf[17];
    p.temperature = buf[18];
    p.tint = buf[19];
    p.vignette = buf[20];
    p.sharpen = buf[21];
    p.blurRadiusPx = buf[22];
    p.chromaEnabled = buf[23] > 0.5f;
    p.chromaKey[0] = buf[24];
    p.chromaKey[1] = buf[25];
    p.chromaKey[2] = buf[26];
    p.chromaSimilarity = buf[27];
    p.chromaSmoothness = buf[28];
    p.chromaSpill = buf[29];
    p.alpha = buf[30];
    p.timeSeconds = buf[31];
    p.aspect = buf[32] > 0.05f ? buf[32] : 16.0f / 9.0f;
    if (readCount > 33) p.wipe = buf[33];
    if (readCount > 34) p.wipeMode = static_cast<int32_t>(buf[34]);
    return p;
}

std::vector<EffectSetting> readEffects(JNIEnv* env, jintArray ids, jfloatArray intensities) {
    std::vector<EffectSetting> out;
    if (!ids || !intensities) return out;
    const jsize len = env->GetArrayLength(ids);
    if (len <= 0) return out;
    std::vector<jint> idBuf(static_cast<size_t>(len));
    std::vector<jfloat> intBuf(static_cast<size_t>(len));
    env->GetIntArrayRegion(ids, 0, len, idBuf.data());
    env->GetFloatArrayRegion(intensities, 0, len, intBuf.data());
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        EffectSetting s;
        s.id = static_cast<EffectId>(idBuf[i]);
        s.intensity = intBuf[i];
        out.push_back(s);
    }
    return out;
}

/** Reads one ClipDesc from the parallel arrays written by `NativeTimelineEncoder` (Kotlin). */
ClipDesc readClip(JNIEnv* env, jlongArray longs, jintArray ints, jfloatArray floats, jsize index) {
    ClipDesc c;
    const jsize longStride = 10, intStride = 7, floatStride = 2;
    jlong l[10] = {0};
    jint i[7] = {0};
    jfloat f[2] = {1.0f, 1.0f};
    if (longs) env->GetLongArrayRegion(longs, index * longStride, longStride, l);
    if (ints) env->GetIntArrayRegion(ints, index * intStride, intStride, i);
    if (floats) env->GetFloatArrayRegion(floats, index * floatStride, floatStride, f);
    c.startUs = l[0];
    c.durationUs = l[1];
    c.sourceInUs = l[2];
    c.sourceDurationUs = l[3];
    c.fadeInUs = l[4];
    c.fadeOutUs = l[5];
    c.animationInUs = l[6];
    c.animationOutUs = l[7];
    c.transitionInUs = l[8];
    c.transitionOutUs = l[9];
    c.trackType = i[0];
    c.muted = i[1];
    c.reversed = i[2];
    c.animationInType = i[3];
    c.animationOutType = i[4];
    c.transitionInType = i[5];
    c.transitionOutType = i[6];
    c.speed = f[0] <= 0.01f ? 1.0f : f[0];
    c.volume = f[1];
    return c;
}

NativeVideoEngine* engine(jlong handle) { return reinterpret_cast<NativeVideoEngine*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    std::string v = std::string("CutVideo native ") + kEngineVersion;
    return env->NewStringUTF(v.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineCreate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                     jint width, jint height) {
    auto* e = new NativeVideoEngine();
    if (!e->configure(width, height)) {
        delete e;
        return 0;
    }
    CV_LOGI("NativeVideoEngine created (%dx%d)", width, height);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                      jlong handle) {
    delete engine(handle);
}

JNIEXPORT void JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineSetTimeline(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlongArray longs, jintArray ints, jfloatArray floats,
    jint count) {
    NativeVideoEngine* e = engine(handle);
    if (!e || count < 0) return;
    std::vector<ClipDesc> clips(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        clips[static_cast<size_t>(i)] = readClip(env, longs, ints, floats, i);
    }
    e->setTimeline(clips.data(), count);
}

JNIEXPORT void JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineSetLook(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint index, jfloatArray params, jintArray effectIds,
    jfloatArray effectIntensities) {
    NativeVideoEngine* e = engine(handle);
    if (!e) return;
    const ColorParams p = readParams(env, params);
    const std::vector<EffectSetting> fx = readEffects(env, effectIds, effectIntensities);
    e->setLook(index, p, fx.data(), static_cast<int>(fx.size()));
}

JNIEXPORT jint JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineRenderFrame(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject buffer, jint width, jint height, jint stride,
    jlong timelineUs) {
    NativeVideoEngine* e = engine(handle);
    if (!e || !buffer) return -1;
    void* address = env->GetDirectBufferAddress(buffer);
    if (!address) {
        CV_LOGE("renderFrame requires a direct ByteBuffer");
        return -1;
    }
    Image frame(static_cast<uint8_t*>(address), width, height, stride);
    if (!frame.valid()) return -1;
    return e->renderFrame(frame, timelineUs);
}

JNIEXPORT jfloat JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineGainAt(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                     jlong handle, jlong timelineUs) {
    NativeVideoEngine* e = engine(handle);
    return e ? e->gainAt(timelineUs) : 1.0f;
}

JNIEXPORT jlong JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineSourceTimeAt(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong timelineUs) {
    NativeVideoEngine* e = engine(handle);
    return e ? e->sourceTimeAt(timelineUs) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeEngineDescribe(JNIEnv* env, jobject /*thiz*/,
                                                                       jlong handle) {
    NativeVideoEngine* e = engine(handle);
    std::string s = e ? e->describe() : std::string("no engine");
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeApplyLook(
    JNIEnv* env, jobject /*thiz*/, jobject buffer, jint width, jint height, jint stride,
    jfloatArray params, jintArray effectIds, jfloatArray effectIntensities) {
    if (!buffer) return JNI_FALSE;
    void* address = env->GetDirectBufferAddress(buffer);
    if (!address) return JNI_FALSE;
    Image img(static_cast<uint8_t*>(address), width, height, stride);
    if (!img.valid()) return JNI_FALSE;
    const ColorParams p = readParams(env, params);
    const std::vector<EffectSetting> fx = readEffects(env, effectIds, effectIntensities);
    applyColorPipelineWithEffects(img, p, fx.data(), static_cast<int>(fx.size()));
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeGenerateLut(JNIEnv* env, jobject /*thiz*/,
                                                                    jfloatArray params) {
    const ColorParams p = readParams(env, params);
    constexpr int kLutBytes = 16 * 16 * 16 * 4;
    std::vector<uint8_t> lut(kLutBytes);
    generateLut3d(p, lut.data());
    jbyteArray out = env->NewByteArray(kLutBytes);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, kLutBytes, reinterpret_cast<const jbyte*>(lut.data()));
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeComputeWaveform(JNIEnv* env, jobject /*thiz*/,
                                                                        jshortArray pcm, jint channels,
                                                                        jint buckets) {
    if (!pcm || channels <= 0 || buckets <= 0) return env->NewFloatArray(0);
    const jsize samples = env->GetArrayLength(pcm);
    const jsize frames = samples / channels;
    std::vector<jshort> data(static_cast<size_t>(samples));
    env->GetShortArrayRegion(pcm, 0, samples, data.data());
    std::vector<float> peaks(static_cast<size_t>(buckets), 0.0f);
    computeWaveformPeaks(reinterpret_cast<const int16_t*>(data.data()), frames, channels, buckets,
                         peaks.data());
    jfloatArray out = env->NewFloatArray(buckets);
    if (out) env->SetFloatArrayRegion(out, 0, buckets, peaks.data());
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeApplyGain(
    JNIEnv* env, jobject /*thiz*/, jshortArray pcm, jint channels, jint sampleRate, jfloat gainStart,
    jfloat gainEnd, jfloat master) {
    if (!pcm || channels <= 0) return JNI_FALSE;
    const jsize samples = env->GetArrayLength(pcm);
    std::vector<jshort> data(static_cast<size_t>(samples));
    env->GetShortArrayRegion(pcm, 0, samples, data.data());
    GainSegment seg{0, 1'000'000'000'000LL, gainStart, gainEnd};
    applyGainAutomation(reinterpret_cast<int16_t*>(data.data()), samples / channels, channels,
                        sampleRate > 0 ? sampleRate : 48000, &seg, 1, 0, master);
    env->SetShortArrayRegion(pcm, 0, samples, data.data());
    return JNI_TRUE;
}

JNIEXPORT jshortArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeMixPcm(JNIEnv* env, jobject /*thiz*/,
                                                               jshortArray a, jshortArray b, jfloat gainA,
                                                               jfloat gainB) {
    const jsize lenA = a ? env->GetArrayLength(a) : 0;
    const jsize lenB = b ? env->GetArrayLength(b) : 0;
    const jsize len = std::max(lenA, lenB);
    if (len <= 0) return env->NewShortArray(0);
    std::vector<jshort> bufA(static_cast<size_t>(lenA));
    std::vector<jshort> bufB(static_cast<size_t>(lenB));
    std::vector<jshort> out(static_cast<size_t>(len));
    if (lenA > 0) env->GetShortArrayRegion(a, 0, lenA, bufA.data());
    if (lenB > 0) env->GetShortArrayRegion(b, 0, lenB, bufB.data());
    // Zero-pad the shorter buffer so both are addressable for `len` samples.
    std::vector<int16_t> a16(static_cast<size_t>(len), 0);
    std::vector<int16_t> b16(static_cast<size_t>(len), 0);
    if (lenA > 0) std::memcpy(a16.data(), bufA.data(), static_cast<size_t>(lenA) * sizeof(int16_t));
    if (lenB > 0) std::memcpy(b16.data(), bufB.data(), static_cast<size_t>(lenB) * sizeof(int16_t));
    mixPcm16(a16.data(), b16.data(), reinterpret_cast<int16_t*>(out.data()), len, gainA, gainB);
    jshortArray result = env->NewShortArray(len);
    if (result) env->SetShortArrayRegion(result, 0, len, out.data());
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeResample(JNIEnv* env, jobject /*thiz*/,
                                                                 jshortArray pcm, jint channels,
                                                                 jint inRate, jint outRate) {
    if (!pcm || channels <= 0 || inRate <= 0 || outRate <= 0) return env->NewShortArray(0);
    const jsize samples = env->GetArrayLength(pcm);
    const jsize frames = samples / channels;
    std::vector<jshort> data(static_cast<size_t>(samples));
    env->GetShortArrayRegion(pcm, 0, samples, data.data());
    const int capacity = static_cast<int>(static_cast<double>(frames) * outRate / inRate) + 2;
    std::vector<int16_t> out(static_cast<size_t>(capacity) * channels, 0);
    const int outFrames = resampleLinear(reinterpret_cast<const int16_t*>(data.data()), frames, channels,
                                         inRate, outRate, out.data(), capacity);
    const jsize outSamples = static_cast<jsize>(outFrames) * channels;
    jshortArray result = env->NewShortArray(outSamples);
    if (result) {
        env->SetShortArrayRegion(result, 0, outSamples, reinterpret_cast<const jshort*>(out.data()));
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeSampleAnimation(JNIEnv* env, jobject /*thiz*/,
                                                                        jint type, jfloat progress) {
    const AnimationState s = sampleAnimation(static_cast<AnimationType>(type), progress);
    float out[kStateFloatCount];
    animationToArray(s, out);
    jfloatArray result = env->NewFloatArray(kStateFloatCount);
    if (result) env->SetFloatArrayRegion(result, 0, kStateFloatCount, out);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeSampleTransition(JNIEnv* env, jobject /*thiz*/,
                                                                         jint type, jfloat progress) {
    const AnimationState s = sampleTransition(static_cast<TransitionType>(type), progress);
    float out[kStateFloatCount];
    animationToArray(s, out);
    jfloatArray result = env->NewFloatArray(kStateFloatCount);
    if (result) env->SetFloatArrayRegion(result, 0, kStateFloatCount, out);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_cutvideo_editor_nativebridge_NativeBridge_nativeSourceTime(
    JNIEnv* env, jobject /*thiz*/, jlongArray longs, jintArray ints, jfloatArray floats, jlong timelineUs) {
    const ClipDesc clip = readClip(env, longs, ints, floats, 0);
    return sourceTimeUs(clip, timelineUs);
}

}  // extern "C"
