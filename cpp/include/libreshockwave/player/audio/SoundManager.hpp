#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/audio/AudioBackend.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::chunks {
class CastMemberChunk;
class SoundChunk;
}

namespace libreshockwave::player::audio {

class SoundManager {
public:
    static constexpr int MAX_CHANNELS = 8;

    using AudioResolver = std::function<std::optional<std::vector<std::uint8_t>>(
        const lingo::Datum::CastMemberRef&)>;

    SoundManager();
    explicit SoundManager(DirectorFile* sourceFile);

    void setBackend(AudioBackend* backend);
    [[nodiscard]] AudioBackend* backend() const;

    void setAudioResolver(AudioResolver resolver);
    void setCastLibSourceFile(int castLib, DirectorFile* sourceFile);
    [[nodiscard]] DirectorFile* castLibSourceFile(int castLib) const;

    void play(int channelNum, const lingo::Datum& args);
    void stop(int channelNum);
    void stopAll();
    void setVolume(int channelNum, int volume);
    [[nodiscard]] int getVolume(int channelNum) const;
    [[nodiscard]] bool isPlaying(int channelNum) const;
    [[nodiscard]] int getElapsedTime(int channelNum) const;

    [[nodiscard]] std::optional<std::vector<std::uint8_t>> resolveAudioData(const lingo::Datum& memberRef) const;
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> resolveAudioData(
        const lingo::Datum::CastMemberRef& memberRef) const;

    [[nodiscard]] static bool isValidChannel(int channelNum);
    [[nodiscard]] static int clampVolume(int volume);
    [[nodiscard]] static std::string_view detectFormat(const std::vector<std::uint8_t>& audioData);
    [[nodiscard]] static std::optional<std::vector<std::uint8_t>> convertSoundToPlayable(
        const chunks::SoundChunk& sound);
    [[nodiscard]] static std::shared_ptr<chunks::SoundChunk> findSoundForMember(
        DirectorFile& dirFile,
        const std::shared_ptr<chunks::CastMemberChunk>& member);

private:
    struct PlayArgs {
        std::optional<lingo::Datum::CastMemberRef> memberRef;
        int loopCount{1};
    };

    [[nodiscard]] static PlayArgs extractPlayArgs(const lingo::Datum& args);

    AudioBackend* backend_{nullptr};
    std::array<int, MAX_CHANNELS + 1> volumes_{};
    std::unordered_map<int, DirectorFile*> castLibFiles_;
    AudioResolver resolver_;
};

} // namespace libreshockwave::player::audio
