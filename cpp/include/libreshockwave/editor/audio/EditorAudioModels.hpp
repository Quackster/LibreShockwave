#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/model/MemberNodeData.hpp"
#include "libreshockwave/player/audio/AudioBackend.hpp"
#include "libreshockwave/player/audio/SoundManager.hpp"

namespace libreshockwave::chunks {
class SoundChunk;
}

namespace libreshockwave::editor::audio {

struct PlaybackState {
    bool isPlaying{false};
    int progressPercent{0};
    std::string timeLabel{"0.0s / 0.0s"};

    friend bool operator==(const PlaybackState&, const PlaybackState&) = default;
};

class EditorAudioClip {
public:
    virtual ~EditorAudioClip() = default;

    virtual void start() = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    [[nodiscard]] virtual bool isRunning() const = 0;
    [[nodiscard]] virtual long long microsecondPosition() const = 0;
    [[nodiscard]] virtual long long microsecondLength() const = 0;
    virtual void setMicrosecondPosition(long long position) = 0;
};

class AudioPlaybackController {
public:
    using SoundResolver = std::function<std::shared_ptr<chunks::SoundChunk>(const model::MemberNodeData&)>;
    using ClipFactory = std::function<std::unique_ptr<EditorAudioClip>(
        const std::vector<std::uint8_t>& audioData,
        std::string_view format)>;
    using StatusCallback = std::function<void(std::string_view)>;
    using StoppedCallback = std::function<void()>;
    using StateCallback = std::function<void(const PlaybackState&)>;

    void setSoundResolver(SoundResolver resolver);
    void setClipFactory(ClipFactory factory);
    void setStatusCallback(StatusCallback callback);
    void setOnPlaybackStopped(StoppedCallback callback);
    void setOnStateChanged(StateCallback callback);

    void setCurrentMember(std::optional<model::MemberNodeData> memberData);
    [[nodiscard]] const std::optional<model::MemberNodeData>& currentMember() const;
    [[nodiscard]] const PlaybackState& lastState() const;
    [[nodiscard]] std::string_view lastStatus() const;

    [[nodiscard]] bool play();
    void stop();
    [[nodiscard]] bool togglePause();
    [[nodiscard]] bool isPlaying() const;
    [[nodiscard]] bool seekTo(int percent);
    [[nodiscard]] long long getDurationMicros() const;
    void dispose();
    void updatePlaybackPosition();
    void notifyPlaybackStopped();

    [[nodiscard]] static std::string formatTimeLabel(long long posMicros, long long lenMicros);
    [[nodiscard]] static int progressPercent(long long posMicros, long long lenMicros);

private:
    void setStatus(std::string status);
    void notifyState(bool playing, int percent, std::string timeLabel);

    std::optional<model::MemberNodeData> currentSoundMember_;
    SoundResolver soundResolver_;
    ClipFactory clipFactory_;
    std::unique_ptr<EditorAudioClip> currentClip_;
    StatusCallback statusCallback_;
    StoppedCallback onPlaybackStopped_;
    StateCallback onStateChanged_;
    PlaybackState lastState_{};
    std::string lastStatus_;
};

struct EditorAudioChannelState {
    bool playing{false};
    int volume{255};
    int elapsedMillis{0};
    int loopCount{1};
    std::string format;
    std::vector<std::uint8_t> audioData;

    friend bool operator==(const EditorAudioChannelState&, const EditorAudioChannelState&) = default;
};

class EditorAudioBackend final : public player::audio::AudioBackend {
public:
    static constexpr int MAX_CHANNELS = player::audio::SoundManager::MAX_CHANNELS;

    EditorAudioBackend();

    void play(int channelNum,
              const std::vector<std::uint8_t>& audioData,
              std::string_view format,
              int loopCount) override;
    void stop(int channelNum) override;
    void stopAll() override;
    void setVolume(int channelNum, int volume) override;
    [[nodiscard]] bool isPlaying(int channelNum) const override;
    [[nodiscard]] int getElapsedTime(int channelNum) const override;

    [[nodiscard]] const EditorAudioChannelState* channelState(int channelNum) const;
    void setElapsedTimeForTesting(int channelNum, int elapsedMillis);

private:
    [[nodiscard]] static bool validChannel(int channelNum);

    std::vector<EditorAudioChannelState> channels_;
};

} // namespace libreshockwave::editor::audio
