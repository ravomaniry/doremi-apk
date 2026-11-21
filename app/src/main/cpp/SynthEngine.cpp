#include "SynthEngine.h"

#include <fluidsynth.h>

#include <android/log.h>

#include <algorithm>
#include <sstream>

namespace {

constexpr char kLogTag[] = "doremi_synth";
constexpr float kGain = 0.6f;

std::string presetRow(int bank, int program, const char *name) {
    std::string label = name == nullptr ? "" : name;
    for (char &character : label) {
        if (character == '\t' || character == '\n' || character == '\r') {
            character = ' ';
        }
    }
    std::ostringstream row;
    row << bank << '\t' << program << '\t' << label;
    return row.str();
}

std::vector<std::string> collectPresets(fluid_synth_t *synth, int sfontId) {
    std::vector<std::string> rows;
    fluid_sfont_t *sfont = fluid_synth_get_sfont_by_id(synth, sfontId);
    if (sfont == nullptr) {
        return rows;
    }
    fluid_sfont_iteration_start(sfont);
    while (fluid_preset_t *preset = fluid_sfont_iteration_next(sfont)) {
        rows.push_back(presetRow(fluid_preset_get_banknum(preset), fluid_preset_get_num(preset),
                                 fluid_preset_get_name(preset)));
    }
    return rows;
}

}  // namespace

std::vector<std::string> probeSoundFont(const std::string &path) {
    fluid_settings_t *settings = new_fluid_settings();
    if (settings == nullptr) {
        return {};
    }
    fluid_settings_setnum(settings, "synth.sample-rate", 44100);
    fluid_synth_t *synth = new_fluid_synth(settings);
    if (synth == nullptr) {
        delete_fluid_settings(settings);
        return {};
    }
    const int id = fluid_synth_sfload(synth, path.c_str(), 0);
    std::vector<std::string> rows;
    if (id != FLUID_FAILED) {
        rows = collectPresets(synth, id);
    }
    delete_fluid_synth(synth);
    delete_fluid_settings(settings);
    if (id == FLUID_FAILED || rows.empty()) {
        return {};
    }
    return rows;
}

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
    clearEventsLocked();
    if (synth != nullptr) {
        delete_fluid_synth(synth);
        synth = nullptr;
    }
    if (settings != nullptr) {
        delete_fluid_settings(settings);
        settings = nullptr;
    }
    fonts.clear();
}

int SynthEngine::loadSoundFont(const std::string &path) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        return -1;
    }
    const int existing = fontIdLocked(path);
    if (existing >= 0) {
        return existing;
    }

    const int id = fluid_synth_sfload(synth, path.c_str(), 0);
    if (id == FLUID_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sfload failed: %s", path.c_str());
        return -1;
    }
    fonts.push_back(LoadedFont{path, id});
    fluid_synth_set_gain(synth, kGain);
    return id;
}

std::vector<std::string> SynthEngine::presets(int sfontId) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        return {};
    }
    return collectPresets(synth, sfontId);
}

bool SynthEngine::selectInstrument(int channel, int sfontId, int bank, int program) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr || channel < 0 || channel >= kChannels) {
        return false;
    }
    std::string path;
    for (const LoadedFont &font : fonts) {
        if (font.id == sfontId) {
            path = font.path;
            break;
        }
    }
    if (path.empty()) {
        return false;
    }
    if (fluid_synth_program_select(synth, channel, sfontId, bank, program) != FLUID_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "program select failed channel=%d bank=%d program=%d", channel, bank,
                            program);
        return false;
    }
    channels[static_cast<size_t>(channel)] = ChannelPatch{path, bank, program, true};
    return true;
}

void SynthEngine::releaseUnused(const std::vector<int> &keepIds) {
    std::lock_guard<std::mutex> lock(synthMutex);
    auto kept = [&keepIds](int id) {
        return std::find(keepIds.begin(), keepIds.end(), id) != keepIds.end();
    };
    for (ChannelPatch &patch : channels) {
        if (!patch.active) {
            continue;
        }
        const int id = fontIdLocked(patch.path);
        if (id < 0 || !kept(id)) {
            patch.active = false;
        }
    }
    std::vector<LoadedFont> retained;
    retained.reserve(fonts.size());
    for (const LoadedFont &font : fonts) {
        if (kept(font.id)) {
            retained.push_back(font);
            continue;
        }
        if (synth != nullptr) {
            fluid_synth_sfunload(synth, font.id, 0);
        }
    }
    fonts.swap(retained);
}

void SynthEngine::replaceScore(const int *pitches, const int *velocities, const int *channelsIn,
                                const int64_t *onMs, const int64_t *offMs, int count) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    clearEventsLocked();
    if (synth != nullptr) {
        fluid_synth_all_sounds_off(synth, -1);
    }
    if (synth == nullptr || count <= 0) {
        return;
    }

    originFrame = renderedFrames + millisecondsToFrames(kPrerollMs);
    scoreEvents.reserve(static_cast<size_t>(count) * 2);
    for (int i = 0; i < count; ++i) {
        int channel = channelsIn == nullptr ? 0 : channelsIn[i];
        if (channel < 0 || channel >= kChannels) {
            channel = 0;
        }
        const int64_t onFrame = millisecondsToFrames(onMs[i]);
        const int64_t offFrame = millisecondsToFrames(offMs[i]);
        if (offMs[i] > scoreLengthMs) {
            scoreLengthMs = offMs[i];
        }
        scoreEvents.push_back(ScheduledEvent{onFrame, channel, pitches[i], velocities[i], true});
        scoreEvents.push_back(ScheduledEvent{offFrame, channel, pitches[i], 0, false});
    }
    std::sort(scoreEvents.begin(), scoreEvents.end(), eventBefore);
}

void SynthEngine::addNote(int channel, int pitch, int velocity, int64_t durationMs) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth == nullptr) {
        return;
    }
    if (channel < 0 || channel >= kChannels) {
        channel = 0;
    }
    compactLiveLocked();
    const int64_t onFrame = renderedFrames + millisecondsToFrames(kPrerollMs);
    const int64_t offFrame = onFrame + millisecondsToFrames(durationMs);
    insertLiveLocked(ScheduledEvent{onFrame, channel, pitch, velocity, true});
    insertLiveLocked(ScheduledEvent{offFrame, channel, pitch, 0, false});
}

void SynthEngine::stop() {
    std::lock_guard<std::mutex> lock(synthMutex);
    clearEventsLocked();
    if (synth != nullptr) {
        fluid_synth_all_sounds_off(synth, -1);
    }
}

bool SynthEngine::seek(int64_t positionMs) {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    return seekLocked(positionMs);
}

bool SynthEngine::setPaused(bool shouldPause) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (shouldPause && (synth == nullptr || scoreLengthMs <= 0)) {
        return false;
    }
    if (paused == shouldPause) {
        return true;
    }
    paused = shouldPause;
    if (paused && synth != nullptr) {
        fluid_synth_all_sounds_off(synth, -1);
    }
    return true;
}

bool SynthEngine::resume() {
    ensureStream();
    std::lock_guard<std::mutex> lock(synthMutex);
    if (!paused || synth == nullptr || scoreLengthMs <= 0) {
        return false;
    }
    const int64_t position = positionMsLocked();
    paused = false;
    return seekLocked(position);
}

bool SynthEngine::seekLocked(int64_t positionMs) {
    if (synth == nullptr || scoreLengthMs <= 0) {
        return false;
    }
    if (positionMs < 0) {
        positionMs = 0;
    }
    if (positionMs > scoreLengthMs) {
        positionMs = scoreLengthMs;
    }

    fluid_synth_all_sounds_off(synth, -1);
    const int64_t positionFrames = millisecondsToFrames(positionMs);
    const int64_t resumeFrame = renderedFrames + millisecondsToFrames(kPrerollMs);
    originFrame = resumeFrame - positionFrames;
    const auto cursor = std::lower_bound(
            scoreEvents.begin(), scoreEvents.end(), positionFrames,
            [](const ScheduledEvent &event, int64_t frame) { return event.frame < frame; });
    scoreCursor = static_cast<size_t>(cursor - scoreEvents.begin());

    struct OpenNote {
        int channel;
        int pitch;
        int velocity;
        int64_t onFrame;
        bool matched;
    };
    std::vector<OpenNote> open;
    open.reserve(scoreEvents.size() / 2);
    resumeEvents.clear();
    resumeCursor = 0;
    for (const ScheduledEvent &event : scoreEvents) {
        if (event.noteOn) {
            open.push_back(OpenNote{
                    event.channel, event.pitch, event.velocity, event.frame, false});
            continue;
        }
        for (size_t index = open.size(); index > 0; --index) {
            OpenNote &note = open[index - 1];
            if (note.matched || note.channel != event.channel || note.pitch != event.pitch) {
                continue;
            }
            note.matched = true;
            if (note.onFrame < positionFrames && event.frame > positionFrames) {
                resumeEvents.push_back(
                        ScheduledEvent{resumeFrame, note.channel, note.pitch, note.velocity, true});
            }
            break;
        }
    }
    return true;
}

int64_t SynthEngine::positionMs() {
    std::lock_guard<std::mutex> lock(synthMutex);
    return positionMsLocked();
}

int64_t SynthEngine::positionMsLocked() const {
    if (scoreLengthMs <= 0) {
        return 0;
    }
    const int64_t logical = renderedFrames + millisecondsToFrames(kPrerollMs) - originFrame;
    const int64_t position = framesToMilliseconds(logical);
    if (position > scoreLengthMs) {
        return scoreLengthMs;
    }
    return position;
}

int64_t SynthEngine::durationMs() {
    std::lock_guard<std::mutex> lock(synthMutex);
    return scoreLengthMs;
}

oboe::DataCallbackResult SynthEngine::onAudioReady(oboe::AudioStream *, void *audioData,
                                                    int32_t numFrames) {
    failedRestarts.store(0);
    auto *output = static_cast<float *>(audioData);
    if (output == nullptr || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    std::lock_guard<std::mutex> lock(synthMutex);
    if (paused) {
        // Leave renderedFrames where it is. Seeking while paused schedules
        // resume notes against this clock, and positionMs stays on the bar.
        std::fill_n(output, static_cast<size_t>(numFrames) * 2, 0.f);
        return oboe::DataCallbackResult::Continue;
    }
    if (synth == nullptr) {
        std::fill_n(output, static_cast<size_t>(numFrames) * 2, 0.f);
        renderedFrames += numFrames;
        return oboe::DataCallbackResult::Continue;
    }

    int32_t frame = 0;
    while (frame < numFrames) {
        const int64_t now = renderedFrames + frame;
        while (resumeCursor < resumeEvents.size() && resumeEvents[resumeCursor].frame <= now) {
            applyEventLocked(resumeEvents[resumeCursor++]);
        }
        while (scoreCursor < scoreEvents.size() &&
               originFrame + scoreEvents[scoreCursor].frame <= now) {
            applyEventLocked(scoreEvents[scoreCursor++]);
        }
        while (liveCursor < liveEvents.size() && liveEvents[liveCursor].frame <= now) {
            applyEventLocked(liveEvents[liveCursor++]);
        }

        int64_t nextFrame = renderedFrames + numFrames;
        if (resumeCursor < resumeEvents.size() && resumeEvents[resumeCursor].frame < nextFrame) {
            nextFrame = resumeEvents[resumeCursor].frame;
        }
        if (scoreCursor < scoreEvents.size()) {
            const int64_t due = originFrame + scoreEvents[scoreCursor].frame;
            if (due < nextFrame) {
                nextFrame = due;
            }
        }
        if (liveCursor < liveEvents.size() && liveEvents[liveCursor].frame < nextFrame) {
            nextFrame = liveEvents[liveCursor].frame;
        }

        int64_t due = nextFrame - renderedFrames;
        if (due < frame + 1) {
            due = frame + 1;
        }
        if (due > numFrames) {
            due = numFrames;
        }
        const auto renderUntil = static_cast<int32_t>(due);
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
    std::vector<std::string> paths;
    paths.reserve(fonts.size());
    for (const LoadedFont &font : fonts) {
        paths.push_back(font.path);
    }
    const std::array<ChannelPatch, kChannels> savedChannels = channels;
    fonts.clear();

    settings = new_fluid_settings();
    if (settings == nullptr) {
        return;
    }
    fluid_settings_setint(settings, "synth.midi-channels", kChannels);
    fluid_settings_setnum(settings, "synth.sample-rate", sampleRate);
    fluid_settings_setnum(settings, "synth.gain", kGain);
    synth = new_fluid_synth(settings);
    if (synth == nullptr) {
        return;
    }
    currentSampleRate = sampleRate;
    fluid_synth_set_gain(synth, kGain);
    for (const std::string &path : paths) {
        const int id = fluid_synth_sfload(synth, path.c_str(), 0);
        if (id == FLUID_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sfload after stream restart failed");
            continue;
        }
        fonts.push_back(LoadedFont{path, id});
    }
    channels = savedChannels;
    for (int channel = 0; channel < kChannels; ++channel) {
        const ChannelPatch &patch = channels[static_cast<size_t>(channel)];
        if (!patch.active) {
            continue;
        }
        const int id = fontIdLocked(patch.path);
        if (id < 0) {
            channels[static_cast<size_t>(channel)].active = false;
            continue;
        }
        fluid_synth_program_select(synth, channel, id, patch.bank, patch.program);
    }
}

int SynthEngine::fontIdLocked(const std::string &path) const {
    for (const LoadedFont &font : fonts) {
        if (font.path == path) {
            return font.id;
        }
    }
    return -1;
}

void SynthEngine::clearEventsLocked() {
    scoreEvents.clear();
    scoreCursor = 0;
    liveEvents.clear();
    liveCursor = 0;
    resumeEvents.clear();
    resumeCursor = 0;
    scoreLengthMs = 0;
    originFrame = renderedFrames;
    paused = false;
}

void SynthEngine::applyEventLocked(const ScheduledEvent &event) {
    if (synth == nullptr) {
        return;
    }
    if (event.noteOn) {
        fluid_synth_noteon(synth, event.channel, event.pitch, event.velocity);
    } else {
        fluid_synth_noteoff(synth, event.channel, event.pitch);
    }
}

void SynthEngine::insertLiveLocked(const ScheduledEvent &event) {
    const auto it = std::lower_bound(liveEvents.begin(), liveEvents.end(), event, eventBefore);
    const size_t index = static_cast<size_t>(it - liveEvents.begin());
    liveEvents.insert(it, event);
    if (index < liveCursor) {
        ++liveCursor;
    }
}

void SynthEngine::compactLiveLocked() {
    if (liveCursor == 0 || liveCursor > liveEvents.size()) {
        return;
    }
    liveEvents.erase(liveEvents.begin(),
                     liveEvents.begin() + static_cast<std::ptrdiff_t>(liveCursor));
    liveCursor = 0;
}

int64_t SynthEngine::millisecondsToFrames(int64_t milliseconds) const {
    if (milliseconds <= 0) {
        return 0;
    }
    return milliseconds * currentSampleRate / 1000;
}

int64_t SynthEngine::framesToMilliseconds(int64_t frames) const {
    if (frames <= 0 || currentSampleRate <= 0) {
        return 0;
    }
    return frames * 1000 / currentSampleRate;
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
    if (left.channel != right.channel) {
        return left.channel < right.channel;
    }
    return left.velocity < right.velocity;
}
