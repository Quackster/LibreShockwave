#include "libreshockwave/editor/preview/SoundPreview.hpp"

#include <iomanip>
#include <sstream>
#include <string>

#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"
#include "libreshockwave/editor/score/FrameAppearanceFinder.hpp"

namespace libreshockwave::editor::preview {
namespace {

std::string formatSeconds(double value) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(2) << value;
    return out.str();
}

} // namespace

std::string SoundPreview::format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    const auto sound = scanning::MemberResolver::findSoundForMember(dirFile, memberInfo.member);

    std::string out;
    PreviewFormatUtils::appendMemberHeader(out, "SOUND", memberInfo, true);
    if (sound != nullptr) {
        out += "--- Audio Properties ---\n";
        out += "Codec: " + std::string(sound->isMp3() ? "MP3" : "PCM (16-bit)") + "\n";
        out += "Sample Rate: " + std::to_string(sound->sampleRate()) + " Hz\n";
        out += "Bits Per Sample: " + std::to_string(sound->bitsPerSample()) + "\n";
        out += "Channels: " + std::string(sound->channelCount() == 1 ? "Mono" : "Stereo") + "\n";
        out += "Duration: " + formatSeconds(sound->durationSeconds()) + " seconds\n";
        out += "Audio Data Size: " + std::to_string(sound->audioData().size()) + " bytes\n";
    } else {
        out += "[Sound data not found]\n";
    }

    out += "\n--- Score Appearances ---\n";
    const score::FrameAppearanceFinder appearanceFinder;
    const auto appearances = appearanceFinder.find(dirFile, memberInfo.memberNum);
    PreviewFormatUtils::appendScoreAppearances(out, appearances, appearanceFinder, false);
    return out;
}

bool SoundPreview::isPlayable(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    return scanning::MemberResolver::findSoundForMember(dirFile, memberInfo.member) != nullptr;
}

} // namespace libreshockwave::editor::preview
