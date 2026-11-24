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
    // Score playback already waits kPrerollMs, so a short low-latency buffer
    // only makes the callback miss its deadline.
    static constexpr int32_t kBufferMs = 40;
    static constexpr int32_t kCapacityMs = 100;

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
    // Moves the loaded score. Returns false when no score is loaded.
    bool seek(int64_t positionMs);
    // Freezes the score clock and silences sounding notes. Position stays put.
    bool setPaused(bool paused);
    // Continues from the frozen position and retriggers notes that are on.
    bool resume();
    int64_t positionMs();
    // Speaker delay after the score clock. The follow UI waits this long; seek does not.
    int64_t outputLatencyMs() const;
    int64_t durationMs();

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
    void clearEventsLocked();
    bool seekLocked(int64_t positionMs);
    int64_t positionMsLocked() const;
    void applyEventLocked(const ScheduledEvent &event);
    void insertLiveLocked(const ScheduledEvent &event);
    void compactLiveLocked();
    int64_t millisecondsToFrames(int64_t milliseconds) const;
    int64_t positionFrom(int64_t rendered, int64_t origin, int64_t length, int32_t sampleRate) const;
    void rememberLatency(oboe::AudioStream *audioStream);
    int fontIdLocked(const std::string &path) const;

    // Sets suspend, then locks the synth. The callback writes silence instead of
    // waiting, so a SoundFont load cannot stall the audio thread.
    class SuspendedSynth {
    public:
        explicit SuspendedSynth(SynthEngine &engine);
        ~SuspendedSynth();

        SuspendedSynth(const SuspendedSynth &) = delete;
        SuspendedSynth &operator=(const SuspendedSynth &) = delete;

    private:
        SynthEngine &engine;
        std::unique_lock<std::mutex> lock;
    };

    static bool eventBefore(const ScheduledEvent &left, const ScheduledEvent &right);

    std::mutex streamMutex;
    std::mutex synthMutex;
    std::shared_ptr<oboe::AudioStream> stream;
    fluid_settings_t *settings = nullptr;
    fluid_synth_t *synth = nullptr;

    // Score frames are measured from the start of the score. originFrame is the
    // audio-clock frame where that start falls. Live frames are absolute, for
    // one-shot preview notes. Resume frames are absolute note-ons for voices
    // that were already sounding at a seek point.
    std::vector<ScheduledEvent> scoreEvents;
    size_t scoreCursor = 0;
    // The UI reads these without synthMutex. The callback still uses them
    // while holding that lock. Playback follow subtracts latencyMs from the score clock.
    std::atomic<int64_t> originFrame{0};
    std::atomic<int64_t> scoreLengthMs{0};
    std::atomic<int64_t> renderedFrames{0};
    std::atomic<int32_t> currentSampleRate{kSampleRate};
    // Output delay in milliseconds. kBufferMs until the stream reports a measurement.
    std::atomic<int32_t> latencyMs{kBufferMs};
    std::atomic<bool> latencyMeasured{false};
    // While set, the callback writes silence and does not take synthMutex
    // or move the clock.
    std::atomic<bool> suspend{false};
    bool paused = false;
    std::vector<ScheduledEvent> liveEvents;
    size_t liveCursor = 0;
    std::vector<ScheduledEvent> resumeEvents;
    size_t resumeCursor = 0;
    std::vector<LoadedFont> fonts;
    std::array<ChannelPatch, kChannels> channels{};

    std::atomic<bool> alive{true};
    std::atomic<bool> restarting{false};
    std::atomic<int> failedRestarts{0};
};

// Loads a SoundFont on a throwaway synth and returns "bank\\tprogram\\tname" rows.
// An empty result means the file did not load or it has no presets.
std::vector<std::string> probeSoundFont(const std::string &path);
