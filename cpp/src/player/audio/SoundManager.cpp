#include "libreshockwave/player/audio/SoundManager.hpp"

#include <algorithm>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/audio/SoundConverter.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"

namespace libreshockwave::player::audio {

namespace {

lingo::Datum getProp(const lingo::Datum::PropList& props, std::string_view name) {
    auto value = props.get(lingo::Datum::symbol(std::string(name)));
    if (!value.isVoid()) {
        return value;
    }
    return props.get(lingo::Datum::of(std::string(name)));
}

} // namespace

SoundManager::SoundManager() {
    volumes_.fill(255);
    volumes_[0] = 0;
}

SoundManager::SoundManager(DirectorFile* sourceFile) : SoundManager() {
    setCastLibSourceFile(1, sourceFile);
}

void SoundManager::setBackend(AudioBackend* backend) {
    backend_ = backend;
}

AudioBackend* SoundManager::backend() const {
    return backend_;
}

void SoundManager::setAudioResolver(AudioResolver resolver) {
    resolver_ = std::move(resolver);
}

void SoundManager::setCastLibSourceFile(int castLib, DirectorFile* sourceFile) {
    if (castLib <= 0) {
        return;
    }
    if (sourceFile == nullptr) {
        castLibFiles_.erase(castLib);
        return;
    }
    castLibFiles_[castLib] = sourceFile;
}

DirectorFile* SoundManager::castLibSourceFile(int castLib) const {
    const auto found = castLibFiles_.find(castLib);
    return found == castLibFiles_.end() ? nullptr : found->second;
}

void SoundManager::play(int channelNum, const lingo::Datum& args) {
    if (backend_ == nullptr || !isValidChannel(channelNum)) {
        return;
    }

    const auto parsed = extractPlayArgs(args);
    if (!parsed.memberRef.has_value()) {
        return;
    }

    const auto audioData = resolveAudioData(*parsed.memberRef);
    if (!audioData.has_value() || audioData->empty()) {
        return;
    }

    backend_->play(channelNum, *audioData, detectFormat(*audioData), parsed.loopCount);
    backend_->setVolume(channelNum, volumes_[static_cast<std::size_t>(channelNum)]);
}

void SoundManager::stop(int channelNum) {
    if (backend_ == nullptr || !isValidChannel(channelNum)) {
        return;
    }
    backend_->stop(channelNum);
}

void SoundManager::stopAll() {
    if (backend_ != nullptr) {
        backend_->stopAll();
    }
}

void SoundManager::setVolume(int channelNum, int volume) {
    if (!isValidChannel(channelNum)) {
        return;
    }
    const int clamped = clampVolume(volume);
    volumes_[static_cast<std::size_t>(channelNum)] = clamped;
    if (backend_ != nullptr) {
        backend_->setVolume(channelNum, clamped);
    }
}

int SoundManager::getVolume(int channelNum) const {
    if (!isValidChannel(channelNum)) {
        return 255;
    }
    return volumes_[static_cast<std::size_t>(channelNum)];
}

bool SoundManager::isPlaying(int channelNum) const {
    return backend_ != nullptr && isValidChannel(channelNum) && backend_->isPlaying(channelNum);
}

int SoundManager::getElapsedTime(int channelNum) const {
    return backend_ != nullptr && isValidChannel(channelNum) ? backend_->getElapsedTime(channelNum) : 0;
}

std::optional<std::vector<std::uint8_t>> SoundManager::resolveAudioData(const lingo::Datum& memberRef) const {
    const auto* castMemberRef = memberRef.asCastMemberRef();
    if (castMemberRef == nullptr) {
        return std::nullopt;
    }
    return resolveAudioData(*castMemberRef);
}

std::optional<std::vector<std::uint8_t>> SoundManager::resolveAudioData(
    const lingo::Datum::CastMemberRef& memberRef) const {
    if (resolver_) {
        return resolver_(memberRef);
    }

    auto* sourceFile = castLibSourceFile(memberRef.castLib);
    if (sourceFile == nullptr) {
        return std::nullopt;
    }

    auto member = sourceFile->getCastMemberByNumber(memberRef.castLib, memberRef.memberNum());
    if (!member) {
        return std::nullopt;
    }

    auto sound = findSoundForMember(*sourceFile, member);
    return sound ? convertSoundToPlayable(*sound) : std::nullopt;
}

bool SoundManager::isValidChannel(int channelNum) {
    return channelNum >= 1 && channelNum <= MAX_CHANNELS;
}

int SoundManager::clampVolume(int volume) {
    return std::clamp(volume, 0, 255);
}

std::string_view SoundManager::detectFormat(const std::vector<std::uint8_t>& audioData) {
    if (audioData.size() > 2 && audioData[0] == 'R' && audioData[1] == 'I') {
        return "wav";
    }
    return "mp3";
}

std::optional<std::vector<std::uint8_t>> SoundManager::convertSoundToPlayable(const chunks::SoundChunk& sound) {
    if (sound.isMp3()) {
        auto mp3 = libreshockwave::audio::SoundConverter::extractMp3(sound);
        if (mp3.has_value() && !mp3->empty()) {
            return mp3;
        }
        return std::nullopt;
    }

    if (sound.isAdpcm()) {
        return libreshockwave::audio::SoundConverter::imaAdpcmToWav(
            sound.audioData(),
            sound.sampleRate(),
            sound.channelCount(),
            0,
            0);
    }

    return libreshockwave::audio::SoundConverter::toWav(sound);
}

std::shared_ptr<chunks::SoundChunk> SoundManager::findSoundForMember(
    DirectorFile& dirFile,
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (!member || !dirFile.keyTable()) {
        return nullptr;
    }

    for (const auto& entry : dirFile.keyTable()->getEntriesForOwner(member->id())) {
        if (auto sound = std::dynamic_pointer_cast<chunks::SoundChunk>(dirFile.getChunk(entry.sectionId))) {
            return sound;
        }
        if (auto media = std::dynamic_pointer_cast<chunks::MediaChunk>(dirFile.getChunk(entry.sectionId))) {
            return std::make_shared<chunks::SoundChunk>(media->toSoundChunk());
        }
    }
    return nullptr;
}

SoundManager::PlayArgs SoundManager::extractPlayArgs(const lingo::Datum& args) {
    PlayArgs result;

    if (const auto* castMember = args.asCastMemberRef()) {
        result.memberRef = *castMember;
        return result;
    }

    if (args.isPropList()) {
        const auto& props = args.propListValue();
        const auto member = getProp(props, "member");
        if (const auto* castMember = member.asCastMemberRef()) {
            result.memberRef = *castMember;
        }
        const auto loopCount = getProp(props, "loopCount");
        if (!loopCount.isVoid()) {
            result.loopCount = loopCount.intValue();
        }
        return result;
    }

    if (args.isList()) {
        const auto& items = args.listValue().items();
        if (!items.empty() && items.front().isPropList()) {
            return extractPlayArgs(items.front());
        }
    }

    return result;
}

} // namespace libreshockwave::player::audio
