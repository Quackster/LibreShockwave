#include "libreshockwave/editor/audio/EditorAudioModels.hpp"

#include <algorithm>
#include <iomanip>
#include <sstream>
#include <utility>

#include "libreshockwave/chunks/SoundChunk.hpp"

namespace libreshockwave::editor::audio {

void AudioPlaybackController::setSoundResolver(SoundResolver resolver) {
    soundResolver_ = std::move(resolver);
}

void AudioPlaybackController::setClipFactory(ClipFactory factory) {
    clipFactory_ = std::move(factory);
}

void AudioPlaybackController::setStatusCallback(StatusCallback callback) {
    statusCallback_ = std::move(callback);
}

void AudioPlaybackController::setOnPlaybackStopped(StoppedCallback callback) {
    onPlaybackStopped_ = std::move(callback);
}

void AudioPlaybackController::setOnStateChanged(StateCallback callback) {
    onStateChanged_ = std::move(callback);
}

void AudioPlaybackController::setCurrentMember(std::optional<model::MemberNodeData> memberData) {
    currentSoundMember_ = std::move(memberData);
}

const std::optional<model::MemberNodeData>& AudioPlaybackController::currentMember() const {
    return currentSoundMember_;
}

const PlaybackState& AudioPlaybackController::lastState() const {
    return lastState_;
}

std::string_view AudioPlaybackController::lastStatus() const {
    return lastStatus_;
}

bool AudioPlaybackController::play() {
    if (!currentSoundMember_.has_value()) {
        return false;
    }

    stop();
    if (!soundResolver_) {
        setStatus("No audio resolver configured");
        return false;
    }
    if (!clipFactory_) {
        setStatus("No audio clip factory configured");
        return false;
    }

    auto soundChunk = soundResolver_(*currentSoundMember_);
    if (!soundChunk) {
        return false;
    }

    auto audioData = player::audio::SoundManager::convertSoundToPlayable(*soundChunk);
    if (!audioData.has_value() || audioData->empty()) {
        setStatus(soundChunk->isMp3() ? "Failed to extract MP3 data" : "No audio data to play");
        return false;
    }

    const auto format = player::audio::SoundManager::detectFormat(*audioData);
    if (format == "wav" && audioData->size() <= 44) {
        setStatus("No audio data to play");
        return false;
    }

    auto clip = clipFactory_(*audioData, format);
    if (!clip) {
        setStatus("No audio clip available");
        return false;
    }

    clip->start();
    const auto length = clip->microsecondLength();
    currentClip_ = std::move(clip);
    setStatus("Playing: " + currentSoundMember_->memberInfo.name);
    notifyState(true, 0, formatTimeLabel(0, length));
    return true;
}

void AudioPlaybackController::stop() {
    if (currentClip_) {
        currentClip_->stop();
        currentClip_->close();
        currentClip_.reset();
    }
    notifyState(false, 0, "0.0s / 0.0s");
}

bool AudioPlaybackController::togglePause() {
    if (!currentClip_) {
        return false;
    }
    if (currentClip_->isRunning()) {
        currentClip_->stop();
    } else {
        currentClip_->start();
    }
    return true;
}

bool AudioPlaybackController::isPlaying() const {
    return currentClip_ != nullptr && currentClip_->isRunning();
}

bool AudioPlaybackController::seekTo(int percent) {
    if (!currentClip_) {
        return false;
    }
    const auto newPosition = static_cast<long long>((percent / 100.0) * currentClip_->microsecondLength());
    currentClip_->setMicrosecondPosition(newPosition);
    return true;
}

long long AudioPlaybackController::getDurationMicros() const {
    return currentClip_ ? currentClip_->microsecondLength() : 0;
}

void AudioPlaybackController::dispose() {
    stop();
}

void AudioPlaybackController::updatePlaybackPosition() {
    if (!currentClip_ || !currentClip_->isRunning()) {
        return;
    }
    const auto position = currentClip_->microsecondPosition();
    const auto length = currentClip_->microsecondLength();
    notifyState(true, progressPercent(position, length), formatTimeLabel(position, length));
}

void AudioPlaybackController::notifyPlaybackStopped() {
    if (currentClip_) {
        currentClip_->stop();
    }
    if (onPlaybackStopped_) {
        onPlaybackStopped_();
    }
    const auto length = currentClip_ ? currentClip_->microsecondLength() : 0;
    notifyState(false, 0, formatTimeLabel(0, length));
}

std::string AudioPlaybackController::formatTimeLabel(long long posMicros, long long lenMicros) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(1)
        << (static_cast<double>(posMicros) / 1'000'000.0)
        << "s / "
        << (static_cast<double>(lenMicros) / 1'000'000.0)
        << "s";
    return out.str();
}

int AudioPlaybackController::progressPercent(long long posMicros, long long lenMicros) {
    if (lenMicros <= 0) {
        return 0;
    }
    return static_cast<int>((posMicros * 100.0) / lenMicros);
}

void AudioPlaybackController::setStatus(std::string status) {
    lastStatus_ = std::move(status);
    if (statusCallback_) {
        statusCallback_(lastStatus_);
    }
}

void AudioPlaybackController::notifyState(bool playing, int percent, std::string timeLabel) {
    lastState_ = PlaybackState{playing, percent, std::move(timeLabel)};
    if (onStateChanged_) {
        onStateChanged_(lastState_);
    }
}

EditorAudioBackend::EditorAudioBackend()
    : channels_(MAX_CHANNELS + 1) {
    channels_[0].volume = 0;
}

void EditorAudioBackend::play(int channelNum,
                              const std::vector<std::uint8_t>& audioData,
                              std::string_view format,
                              int loopCount) {
    if (!validChannel(channelNum)) {
        return;
    }
    auto& channel = channels_[static_cast<std::size_t>(channelNum)];
    channel.playing = true;
    channel.elapsedMillis = 0;
    channel.loopCount = loopCount;
    channel.format = std::string(format);
    channel.audioData = audioData;
}

void EditorAudioBackend::stop(int channelNum) {
    if (!validChannel(channelNum)) {
        return;
    }
    auto& channel = channels_[static_cast<std::size_t>(channelNum)];
    channel.playing = false;
    channel.elapsedMillis = 0;
    channel.audioData.clear();
    channel.format.clear();
    channel.loopCount = 1;
}

void EditorAudioBackend::stopAll() {
    for (int channelNum = 1; channelNum <= MAX_CHANNELS; ++channelNum) {
        stop(channelNum);
    }
}

void EditorAudioBackend::setVolume(int channelNum, int volume) {
    if (!validChannel(channelNum)) {
        return;
    }
    channels_[static_cast<std::size_t>(channelNum)].volume = player::audio::SoundManager::clampVolume(volume);
}

bool EditorAudioBackend::isPlaying(int channelNum) const {
    return validChannel(channelNum) && channels_[static_cast<std::size_t>(channelNum)].playing;
}

int EditorAudioBackend::getElapsedTime(int channelNum) const {
    return validChannel(channelNum) ? channels_[static_cast<std::size_t>(channelNum)].elapsedMillis : 0;
}

const EditorAudioChannelState* EditorAudioBackend::channelState(int channelNum) const {
    if (!validChannel(channelNum)) {
        return nullptr;
    }
    return &channels_[static_cast<std::size_t>(channelNum)];
}

void EditorAudioBackend::setElapsedTimeForTesting(int channelNum, int elapsedMillis) {
    if (!validChannel(channelNum)) {
        return;
    }
    channels_[static_cast<std::size_t>(channelNum)].elapsedMillis = std::max(0, elapsedMillis);
}

bool EditorAudioBackend::validChannel(int channelNum) {
    return channelNum >= 1 && channelNum <= MAX_CHANNELS;
}

} // namespace libreshockwave::editor::audio
