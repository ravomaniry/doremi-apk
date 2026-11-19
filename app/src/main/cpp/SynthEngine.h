#pragma once

#include <fluidsynth.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <array>
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
    static constexpr int kChannels = 16;

    SynthEngine() = default;
    ~SynthEngine() override;

    SynthEngine(const SynthEngine &) = delete;
    SynthEngine &operator=(const SynthEngine &) = delete;

    void start();
    void shutdown();

    // Loads a SoundFont without unloading the others. Returns its id, or -1.
    int loadSoundFont(const std::string &path);
    std::vector<std::string> presets(int sfontId);
    bool selectInstrument(int channel, int sfontId, int bank, int program);
    void releaseUnused(const std::vector<int> &keepIds);
    void replaceScore(const int *pitches, const int *velocities, const int *channels,
                       const int64_t *onMs, const int64_t *offMs, int count);
    void addNote(int channel, int pitch, int velocity, int64_t durationMs);
    void stop();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                           int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    struct ScheduledEvent {
        int64_t frame;
        int channel;
        int pitch;
        int velocity;
        bool noteOn;
    };

    struct LoadedFont {
        std::string path;
        int id;
    };

    struct ChannelPatch {
        std::string path;
        int bank = 0;
        int program = 0;
        bool active = false;
    };

    void restart();
    void openNewStream();
    void ensureStream();
    std::shared_ptr<oboe::AudioStream> buildStream(oboe::PerformanceMode mode);
    void ensureSynthLocked(int32_t sampleRate);
    void insertEventLocked(const ScheduledEvent &event);
    void compactLocked();
    int64_t millisecondsToFrames(int64_t milliseconds) const;
    int fontIdLocked(const std::string &path) const;

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
    std::vector<LoadedFont> fonts;
    std::array<ChannelPatch, kChannels> channels{};

    std::atomic<bool> alive{true};
    std::atomic<bool> restarting{false};
    std::atomic<int> failedRestarts{0};
};

// Loads a SoundFont on a throwaway synth and returns "bank\\tprogram\\tname" rows.
// An empty result means the file did not load or it has no presets.
std::vector<std::string> probeSoundFont(const std::string &path);
