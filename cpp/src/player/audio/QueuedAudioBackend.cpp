#include "libreshockwave/player/audio/QueuedAudioBackend.hpp"

#include <algorithm>
#include <cstddef>
#include <utility>

namespace libreshockwave::player::audio {

QueuedAudioBackend::QueuedAudioBackend() {
    volumes_.fill(0);
    for (int channel = 1; channel <= MAX_CHANNELS; ++channel) {
        volumes_[static_cast<std::size_t>(channel)] = 255;
    }
}

void QueuedAudioBackend::play(int channelNum,
                              const std::vector<std::uint8_t>& audioData,
                              std::string_view format,
                              int loopCount) {
    if (!isValidChannel(channelNum)) {
        return;
    }

    SoundCommand command;
    command.action = "play";
    command.channelNum = channelNum;
    command.audioData = audioData;
    command.format = std::string(format);
    command.loopCount = loopCount;
    command.volume = volumes_[static_cast<std::size_t>(channelNum)];
    pendingCommands_.push_back(std::move(command));
    playing_[static_cast<std::size_t>(channelNum)] = true;
}

void QueuedAudioBackend::stop(int channelNum) {
    if (!isValidChannel(channelNum)) {
        return;
    }

    SoundCommand command;
    command.action = "stop";
    command.channelNum = channelNum;
    pendingCommands_.push_back(std::move(command));
    playing_[static_cast<std::size_t>(channelNum)] = false;
}

void QueuedAudioBackend::stopAll() {
    for (int channel = 1; channel <= MAX_CHANNELS; ++channel) {
        stop(channel);
    }
}

void QueuedAudioBackend::setVolume(int channelNum, int volume) {
    if (!isValidChannel(channelNum)) {
        return;
    }

    const int clamped = std::clamp(volume, 0, 255);
    volumes_[static_cast<std::size_t>(channelNum)] = clamped;

    SoundCommand command;
    command.action = "volume";
    command.channelNum = channelNum;
    command.volume = clamped;
    pendingCommands_.push_back(std::move(command));
}

bool QueuedAudioBackend::isPlaying(int channelNum) const {
    return isValidChannel(channelNum) && playing_[static_cast<std::size_t>(channelNum)];
}

int QueuedAudioBackend::getElapsedTime(int channelNum) const {
    (void)channelNum;
    return 0;
}

int QueuedAudioBackend::pendingCount() const {
    return static_cast<int>(pendingCommands_.size());
}

const QueuedAudioBackend::SoundCommand* QueuedAudioBackend::getPending(int index) const {
    if (index < 0 || index >= pendingCount()) {
        return nullptr;
    }
    return &pendingCommands_[static_cast<std::size_t>(index)];
}

const std::vector<QueuedAudioBackend::SoundCommand>& QueuedAudioBackend::pendingCommands() const {
    return pendingCommands_;
}

void QueuedAudioBackend::drainPending() {
    pendingCommands_.clear();
}

void QueuedAudioBackend::notifyStopped(int channelNum) {
    if (isValidChannel(channelNum)) {
        playing_[static_cast<std::size_t>(channelNum)] = false;
    }
}

int QueuedAudioBackend::volume(int channelNum) const {
    return isValidChannel(channelNum) ? volumes_[static_cast<std::size_t>(channelNum)] : 0;
}

bool QueuedAudioBackend::isValidChannel(int channelNum) {
    return channelNum >= 1 && channelNum <= MAX_CHANNELS;
}

} // namespace libreshockwave::player::audio
