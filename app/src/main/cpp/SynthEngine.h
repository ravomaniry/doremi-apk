#pragma once

#include <fluidsynth.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Renders FluidSynth into one Oboe stream. The audio callback owns the frame
// clock and applies scheduled note events between render slices.
class SynthEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    static constexpr int32_t kSampleRate = 48000;
    static constexpr int64_t kPrerollMs = 100;

    SynthEngine() = default;
    ~SynthEngine() override;

    SynthEngine(const SynthEngine &) = delete;
    SynthEngine &operator=(const SynthEngine &) = delete;

    void start();
    void shutdown();

    bool loadSoundFont(const std::string &path);
    void programChange(int program);
    void replaceScore(const int *pitches, const int *velocities, const int64_t *onMs,
                       const int64_t *offMs, int count);
    void addNote(int pitch, int velocity, int64_t durationMs);
    void stop();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                           int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    struct ScheduledEvent {
        int64_t frame;
        int pitch;
        int velocity;
        bool noteOn;
    };

    void restart();
    void openNewStream();
    void ensureStream();
    std::shared_ptr<oboe::AudioStream> buildStream(oboe::PerformanceMode mode);
    void ensureSynthLocked(int32_t sampleRate);
    void insertEventLocked(const ScheduledEvent &event);
    void compactLocked();
    int64_t millisecondsToFrames(int64_t milliseconds) const;

    static bool eventBefore(const ScheduledEvent &left, const ScheduledEvent &right);

    std::mutex streamMutex;
    std::mutex synthMutex;
    std::shared_ptr<oboe::AudioStream> stream;
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;

    std::vector<ScheduledEvent> events;
    size_t cursor = 0;
    int64_t renderedFrames = 0;
    int32_t currentSampleRate = kSampleRate;
    int soundFontId = -1;
    int currentProgram = 0;
    std::string soundFontPath;

    std::atomic<bool> alive{true};
    std::atomic<bool> restarting{false};
    std::atomic<int> failedRestarts{0};
};
