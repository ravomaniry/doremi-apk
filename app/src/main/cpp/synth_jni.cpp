#include "SynthEngine.h"

#include <jni.h>

#include <string>
#include <vector>

namespace {

SynthEngine *engineFrom(jlong handle) {
    return reinterpret_cast<SynthEngine *>(handle);
}

jobjectArray toJavaStrings(JNIEnv *env, const std::vector<std::string> &rows) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(rows.size()), stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < rows.size(); ++i) {
        jstring value = env->NewStringUTF(rows[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
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

extern "C" JNIEXPORT jint JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeLoadSoundFont(JNIEnv *env, jobject,
                                                                     jlong handle, jstring path) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr || path == nullptr) {
        return -1;
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return -1;
    }
    const int id = engine->loadSoundFont(chars);
    env->ReleaseStringUTFChars(path, chars);
    return id;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativePresets(JNIEnv *env, jobject, jlong handle,
                                                               jint sfontId) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return nullptr;
    }
    return toJavaStrings(env, engine->presets(sfontId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeSelectInstrument(JNIEnv *, jobject,
                                                                        jlong handle, jint channel,
                                                                        jint sfontId, jint bank,
                                                                        jint program) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    return engine->selectInstrument(channel, sfontId, bank, program) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeReleaseUnused(JNIEnv *env, jobject,
                                                                     jlong handle,
                                                                     jintArray keepIds) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    std::vector<int> ids;
    if (keepIds != nullptr) {
        const jsize count = env->GetArrayLength(keepIds);
        if (count > 0) {
            std::vector<jint> values(static_cast<size_t>(count));
            env->GetIntArrayRegion(keepIds, 0, count, values.data());
            ids.assign(values.begin(), values.end());
        }
    }
    engine->releaseUnused(ids);
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeReplaceScore(
        JNIEnv *env, jobject, jlong handle, jintArray pitches, jintArray velocities,
        jintArray channels, jlongArray onMs, jlongArray offMs) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr || pitches == nullptr || velocities == nullptr || channels == nullptr ||
        onMs == nullptr || offMs == nullptr) {
        return;
    }

    const jsize count = env->GetArrayLength(pitches);
    if (count != env->GetArrayLength(velocities) || count != env->GetArrayLength(channels) ||
        count != env->GetArrayLength(onMs) || count != env->GetArrayLength(offMs)) {
        return;
    }
    if (count == 0) {
        engine->replaceScore(nullptr, nullptr, nullptr, nullptr, nullptr, 0);
        return;
    }

    std::vector<jint> pitchValues(static_cast<size_t>(count));
    std::vector<jint> velocityValues(static_cast<size_t>(count));
    std::vector<jint> channelValues(static_cast<size_t>(count));
    std::vector<jlong> onValues(static_cast<size_t>(count));
    std::vector<jlong> offValues(static_cast<size_t>(count));
    env->GetIntArrayRegion(pitches, 0, count, pitchValues.data());
    env->GetIntArrayRegion(velocities, 0, count, velocityValues.data());
    env->GetIntArrayRegion(channels, 0, count, channelValues.data());
    env->GetLongArrayRegion(onMs, 0, count, onValues.data());
    env->GetLongArrayRegion(offMs, 0, count, offValues.data());
    engine->replaceScore(pitchValues.data(), velocityValues.data(), channelValues.data(),
                          onValues.data(), offValues.data(), count);
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeAddNote(JNIEnv *, jobject, jlong handle,
                                                               jint channel, jint pitch,
                                                               jint velocity, jlong durationMs) {
    if (SynthEngine *engine = engineFrom(handle)) {
        engine->addNote(channel, pitch, velocity, durationMs);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (SynthEngine *engine = engineFrom(handle)) {
        engine->stop();
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeProbe(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) {
        return nullptr;
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return nullptr;
    }
    const std::vector<std::string> rows = probeSoundFont(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (rows.empty()) {
        return nullptr;
    }
    return toJavaStrings(env, rows);
}
