#include "SynthEngine.h"

#include <fluidsynth.h>

#include <android/log.h>

#include <algorithm>

namespace {

constexpr char kLogTag[] = "doremi_synth";
constexpr float kGain = 0.6f;

}  // namespace

SynthEngine::~SynthEngine() {
    shutdown();
}

void SynthEngine::start() {
    alive.store(true);
    failedRestarts.store(0);
    restart();
}

void SynthEngine::shutdown() {
    alive.store(false);
    std::shared_ptr<oboe::AudioStream> local;
    {
        std::lock_guard<std::mutex> lock(streamMutex);
        local = stream;
        stream.reset();
    }
    if (local) {
        local->stop();
        local->close();
    }

    std::lock_guard<std::mutex> lock(synthMutex);
    events.clear();
    cursor = 0;
    if (synth != nullptr) {
        delete_fluid_synth(synth);
        synth = nullptr;
    }
    if (settings != nullptr) {
        delete_fluid_settings(settings);
        settings = nullptr;
    }
    soundFontId = -1;
}

bool SynthEngine::loadSoundFont(const std::string &path) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        return false;
    }

    fluid_synth_all_sounds_off(synth, -1);
    if (soundFontId >= 0) {
        fluid_synth_sfunload(synth, soundFontId, 0);
        soundFontId = -1;
        soundFontPath.clear();
    }

    const int id = fluid_synth_sfload(synth, path.c_str(), 1);
    if (id == FLUID_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sfload failed: %s", path.c_str());
        return false;
    }

    soundFontId = id;
    soundFontPath = path;
    fluid_synth_set_gain(synth, kGain);
    fluid_synth_program_change(synth, 0, currentProgram);
    return true;
}

void SynthEngine::programChange(int program) {
    std::lock_guard<std::mutex> lock(synthMutex);
    currentProgram = program;
    if (synth != nullptr) {
        fluid_synth_program_change(synth, 0, program);
    }
}

void SynthEngine::replaceScore(const int *pitches, const int *velocities, const int64_t *onMs,
                                const int64_t *offMs, int count) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    events.clear();
    cursor = 0;
    if (synth != nullptr) {
        fluid_synth_all_sounds_off(synth, -1);
    }
    if (synth == nullptr || count <= 0) {
        return;
    }

    const int64_t origin = renderedFrames + millisecondsToFrames(kPrerollMs);
    events.reserve(static_cast<size_t>(count) * 2);
    for (int i = 0; i < count; ++i) {
        events.push_back(ScheduledEvent{
                origin + millisecondsToFrames(onMs[i]),
                pitches[i],
                velocities[i],
                true,
        });
        events.push_back(ScheduledEvent{
                origin + millisecondsToFrames(offMs[i]),
                pitches[i],
                0,
                false,
        });
    }
    std::sort(events.begin(), events.end(), eventBefore);
}

void SynthEngine::addNote(int pitch, int velocity, int64_t durationMs) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        return;
    }
    compactLocked();
    const int64_t onFrame = renderedFrames + millisecondsToFrames(kPrerollMs);
    const int64_t offFrame = onFrame + millisecondsToFrames(durationMs);
    insertEventLocked(ScheduledEvent{onFrame, pitch, velocity, true});
    insertEventLocked(ScheduledEvent{offFrame, pitch, 0, false});
}

void SynthEngine::stop() {
    std::lock_guard<std::mutex> lock(synthMutex);
    events.clear();
    cursor = 0;
    if (synth != nullptr) {
        fluid_synth_all_sounds_off(synth, -1);
    }
}

oboe::DataCallbackResult SynthEngine::onAudioReady(oboe::AudioStream *, void *audioData,
                                                    int32_t numFrames) {
    failedRestarts.store(0);
    auto *output = static_cast<float *>(audioData);
    if (output == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        std::fill_n(output, static_cast<size_t>(numFrames) * 2, 0.f);
        renderedFrames += numFrames;
        return oboe::DataCallbackResult::Continue;
    }

    int32_t frame = 0;
    while (frame < numFrames) {
        while (cursor < events.size() && events[cursor].frame <= renderedFrames + frame) {
            const ScheduledEvent &event = events[cursor++];
            if (event.noteOn) {
                fluid_synth_noteon(synth, 0, event.pitch, event.velocity);
            } else {
                fluid_synth_noteoff(synth, 0, event.pitch);
            }
        }

        int32_t renderUntil = numFrames;
        if (cursor < events.size()) {
            const int64_t due = events[cursor].frame - renderedFrames;
            if (due < renderUntil) {
                renderUntil = static_cast<int32_t>(std::max<int64_t>(due, frame + 1));
            }
        }
        const int32_t chunk = renderUntil - frame;
        float *slice = output + static_cast<size_t>(frame) * 2;
        fluid_synth_write_float(synth, chunk, slice, 0, 2, slice, 1, 2);
        frame = renderUntil;
    }

    renderedFrames += numFrames;
    return oboe::DataCallbackResult::Continue;
}

void SynthEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "audio stream closed: %s",
                        oboe::convertToText(error));
    if (!alive.load()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(streamMutex);
        // The stream is already closed. Drop it without calling close() again.
        if (!stream || stream.get() != audioStream) {
            return;
        }
        stream.reset();
    }
    if (failedRestarts.fetch_add(1) >= 4) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "stopped reopening the audio stream");
        return;
    }
    openNewStream();
}

void SynthEngine::restart() {
    if (!alive.load()) {
        return;
    }
    std::shared_ptr<oboe::AudioStream> previous;
    {
        std::lock_guard<std::mutex> lock(streamMutex);
        previous = stream;
        stream.reset();
    }
    if (previous) {
        previous->stop();
        previous->close();
    }
    if (!alive.load()) {
        return;
    }
    openNewStream();
}

void SynthEngine::openNewStream() {
    if (!alive.load()) {
        return;
    }
    bool expected = false;
    if (!restarting.compare_exchange_strong(expected, true)) {
        return;
    }
    struct Guard {
        std::atomic<bool> &flag;
        ~Guard() { flag.store(false); }
    } guard{restarting};

    std::shared_ptr<oboe::AudioStream> opened = buildStream(oboe::PerformanceMode::LowLatency);
    if (!opened) {
        opened = buildStream(oboe::PerformanceMode::None);
    }
    if (!opened) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "unable to open the audio stream");
        return;
    }

    {
        std::lock_guard<std::mutex> lock(streamMutex);
        if (!alive.load()) {
            opened->stop();
            opened->close();
            return;
        }
        if (stream && stream->getState() == oboe::StreamState::Started) {
            opened->stop();
            opened->close();
            return;
        }
        stream = opened;
    }

    std::lock_guard<std::mutex> lock(synthMutex);
    ensureSynthLocked(opened->getSampleRate());
}

void SynthEngine::ensureStream() {
    std::shared_ptr<oboe::AudioStream> current;
    {
        std::lock_guard<std::mutex> lock(streamMutex);
        current = stream;
    }
    if (current && current->getState() == oboe::StreamState::Started) {
        return;
    }
    failedRestarts.store(0);
    restart();
}

std::shared_ptr<oboe::AudioStream> SynthEngine::buildStream(oboe::PerformanceMode mode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(mode)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(kSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> opened;
    const oboe::Result result = builder.openStream(opened);
    if (result != oboe::Result::OK || !opened) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "openStream(%s) failed: %s",
                            oboe::convertToText(mode), oboe::convertToText(result));
        return nullptr;
    }
    if (opened->getFormat() != oboe::AudioFormat::Float || opened->getChannelCount() != 2) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "stream format is not stereo float (rate=%d format=%s channels=%d)",
                            opened->getSampleRate(), oboe::convertToText(opened->getFormat()),
                            opened->getChannelCount());
        opened->close();
        return nullptr;
    }
    const oboe::Result started = opened->requestStart();
    if (started != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "requestStart failed: %s",
                            oboe::convertToText(started));
        opened->close();
        return nullptr;
    }
    return opened;
}

void SynthEngine::ensureSynthLocked(int32_t sampleRate) {
    if (sampleRate <= 0) {
        sampleRate = kSampleRate;
    }
    if (synth != nullptr && sampleRate == currentSampleRate) {
        return;
    }

    if (synth != nullptr) {
        delete_fluid_synth(synth);
        synth = nullptr;
    }
    if (settings != nullptr) {
        delete_fluid_settings(settings);
        settings = nullptr;
    }
    soundFontId = -1;

    settings = new_fluid_settings();
    if (settings == nullptr) {
        return;
    }
    fluid_settings_setnum(settings, "synth.sample-rate", sampleRate);
    fluid_settings_setnum(settings, "synth.gain", kGain);
    synth = new_fluid_synth(settings);
    if (synth == nullptr) {
        return;
    }
    currentSampleRate = sampleRate;
    fluid_synth_set_gain(synth, kGain);
    if (soundFontPath.empty()) {
        return;
    }
    const int id = fluid_synth_sfload(synth, soundFontPath.c_str(), 1);
    if (id == FLUID_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sfload after stream restart failed");
        soundFontPath.clear();
        return;
    }
    soundFontId = id;
    fluid_synth_program_change(synth, 0, currentProgram);
}

void SynthEngine::insertEventLocked(const ScheduledEvent &event) {
    const auto it = std::lower_bound(events.begin(), events.end(), event, eventBefore);
    const size_t index = static_cast<size_t>(it - events.begin());
    events.insert(it, event);
    if (index < cursor) {
        ++cursor;
    }
}

void SynthEngine::compactLocked() {
    if (cursor == 0 || cursor > events.size()) {
        return;
    }
    events.erase(events.begin(), events.begin() + static_cast<std::ptrdiff_t>(cursor));
    cursor = 0;
}

int64_t SynthEngine::millisecondsToFrames(int64_t milliseconds) const {
    if (milliseconds <= 0) {
        return 0;
    }
    return milliseconds * currentSampleRate / 1000;
}

bool SynthEngine::eventBefore(const ScheduledEvent &left, const ScheduledEvent &right) {
    if (left.frame != right.frame) {
        return left.frame < right.frame;
    }
    if (left.noteOn != right.noteOn) {
        return left.noteOn;
    }
    if (left.pitch != right.pitch) {
        return left.pitch < right.pitch;
    }
    return left.velocity < right.velocity;
}
