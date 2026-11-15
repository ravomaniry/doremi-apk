#include "SynthEngine.h"

#include <jni.h>

#include <vector>

namespace {

SynthEngine *engineFrom(jlong handle) {
    return reinterpret_cast<SynthEngine *>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeCreate(JNIEnv *, jobject) {
    auto *engine = new SynthEngine();
    engine->start();
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete engineFrom(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeLoadSoundFont(JNIEnv *env, jobject,
                                                                     jlong handle, jstring path) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr || path == nullptr) {
        return JNI_FALSE;
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const bool loaded = engine->loadSoundFont(chars);
    env->ReleaseStringUTFChars(path, chars);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeProgramChange(JNIEnv *, jobject, jlong handle,
                                                                     jint program) {
    if (SynthEngine *engine = engineFrom(handle)) {
        engine->programChange(program);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeReplaceScore(JNIEnv *env, jobject,
                                                                    jlong handle, jintArray pitches,
                                                                    jintArray velocities,
                                                                    jlongArray onMs,
                                                                    jlongArray offMs) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr || pitches == nullptr || velocities == nullptr || onMs == nullptr ||
        offMs == nullptr) {
        return;
    }

    const jsize count = env->GetArrayLength(pitches);
    if (count != env->GetArrayLength(velocities) || count != env->GetArrayLength(onMs) ||
        count != env->GetArrayLength(offMs)) {
        return;
    }
    if (count == 0) {
        engine->replaceScore(nullptr, nullptr, nullptr, nullptr, 0);
        return;
    }

    std::vector<jint> pitchValues(static_cast<size_t>(count));
    std::vector<jint> velocityValues(static_cast<size_t>(count));
    std::vector<jlong> onValues(static_cast<size_t>(count));
    std::vector<jlong> offValues(static_cast<size_t>(count));
    env->GetIntArrayRegion(pitches, 0, count, pitchValues.data());
    env->GetIntArrayRegion(velocities, 0, count, velocityValues.data());
    env->GetLongArrayRegion(onMs, 0, count, onValues.data());
    env->GetLongArrayRegion(offMs, 0, count, offValues.data());
    engine->replaceScore(pitchValues.data(), velocityValues.data(), onValues.data(),
                          offValues.data(), count);
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeAddNote(JNIEnv *, jobject, jlong handle,
                                                               jint pitch, jint velocity,
                                                               jlong durationMs) {
    if (SynthEngine *engine = engineFrom(handle)) {
        engine->addNote(pitch, velocity, durationMs);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (SynthEngine *engine = engineFrom(handle)) {
        engine->stop();
    }
}
