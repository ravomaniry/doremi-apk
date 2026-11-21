#include "SynthEngine.h"

#include <jni.h>

#include <string>
#include <vector>

namespace {

SynthEngine *engineFrom(jlong handle) {
    return reinterpret_cast<SynthEngine *>(handle);
}

// NewStringUTF aborts the process unless every byte is modified UTF-8.
// Keep sequences it can accept (ASCII plus valid 2- and 3-byte UTF-8) and
// drop the rest, so a Latin-1 preset name still yields its readable letters.
size_t modifiedUtf8Sequence(const std::string &text, size_t index) {
    if (index >= text.size()) {
        return 0;
    }
    const auto lead = static_cast<unsigned char>(text[index]);
    if (lead == 0x00) {
        return 0;
    }
    if (lead < 0x80) {
        return 1;
    }
    auto continuation = [&](size_t offset) -> bool {
        if (offset >= text.size()) {
            return false;
        }
        return (static_cast<unsigned char>(text[offset]) & 0xC0) == 0x80;
    };
    if ((lead & 0xE0) == 0xC0) {
        if (lead < 0xC2 || !continuation(index + 1)) {
            return 0;
        }
        return 2;
    }
    if ((lead & 0xF0) == 0xE0) {
        if (!continuation(index + 1) || !continuation(index + 2)) {
            return 0;
        }
        const auto second = static_cast<unsigned char>(text[index + 1]);
        if (lead == 0xE0 && second < 0xA0) {
            return 0;
        }
        if (lead == 0xED && second >= 0xA0) {
            return 0;
        }
        return 3;
    }
    return 0;
}

std::string modifiedUtf8(const std::string &text) {
    std::string out;
    out.reserve(text.size());
    for (size_t index = 0; index < text.size();) {
        const size_t length = modifiedUtf8Sequence(text, index);
        if (length == 0) {
            ++index;
            continue;
        }
        out.append(text, index, length);
        index += length;
    }
    return out;
}

jobjectArray toJavaStrings(JNIEnv *env, const std::vector<std::string> &rows) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    std::vector<std::string> safe;
    safe.reserve(rows.size());
    for (const std::string &row : rows) {
        safe.push_back(modifiedUtf8(row));
    }
    if (env->PushLocalFrame(static_cast<jint>(safe.size()) + 16) < 0) {
        return nullptr;
    }
    std::vector<jstring> values;
    values.reserve(safe.size());
    for (const std::string &row : safe) {
        jstring value = env->NewStringUTF(row.c_str());
        if (value == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            if (value != nullptr) {
                env->DeleteLocalRef(value);
            }
            continue;
        }
        values.push_back(value);
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < values.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i), values[i]);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    return static_cast<jobjectArray>(env->PopLocalFrame(result));
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

extern "C" JNIEXPORT jboolean JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeSeek(JNIEnv *, jobject, jlong handle,
                                                            jlong positionMs) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    return engine->seek(positionMs) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativePositionMs(JNIEnv *, jobject, jlong handle) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return 0;
    }
    return engine->positionMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_mg_maniry_doremii_editor_sfplayer_SfPlayer_nativeDurationMs(JNIEnv *, jobject, jlong handle) {
    SynthEngine *engine = engineFrom(handle);
    if (engine == nullptr) {
        return 0;
    }
    return engine->durationMs();
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
