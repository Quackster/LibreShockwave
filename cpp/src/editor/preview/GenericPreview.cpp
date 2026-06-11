#include "libreshockwave/editor/preview/GenericPreview.hpp"

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <exception>
#include <iomanip>
#include <sstream>
#include <string>

#include "libreshockwave/cast/FilmLoopInfo.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/cast/ShapeInfo.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"
#include "libreshockwave/editor/score/FrameAppearanceFinder.hpp"

namespace libreshockwave::editor::preview {
namespace {

std::string uppercase(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::toupper(c));
    });
    return value;
}

std::string shapeTypeName(cast::ShapeType shapeType) {
    switch (shapeType) {
        case cast::ShapeType::Rect:
            return "RECT";
        case cast::ShapeType::OvalRect:
            return "OVAL_RECT";
        case cast::ShapeType::Oval:
            return "OVAL";
        case cast::ShapeType::Line:
            return "LINE";
        case cast::ShapeType::Unknown:
            return "UNKNOWN";
    }
    return "UNKNOWN";
}

std::string formatSeconds(double value) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(2) << value;
    return out.str();
}

} // namespace

std::string GenericPreview::format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    std::string out;
    const auto typeName = std::string(cast::name(memberInfo.memberType));
    out += "=== " + uppercase(typeName) + ": " + memberInfo.name + " ===\n\n";
    out += "Member ID: " + std::to_string(memberInfo.memberNum) + "\n";
    out += "Type: " + typeName + " (" + std::to_string(cast::code(memberInfo.memberType)) + ")\n";

    if (!memberInfo.details.empty()) {
        out += "Details: " + memberInfo.details + "\n";
    }

    if (memberInfo.member != nullptr && !memberInfo.member->specificData().empty()) {
        out += "\nSpecific Data: " + std::to_string(memberInfo.member->specificData().size()) + " bytes\n";
        formatTypeSpecificData(out, dirFile, memberInfo);
    }

    if (dirFile.hasScore()) {
        out += "\n--- Score Appearances ---\n";
        const score::FrameAppearanceFinder appearanceFinder;
        const auto appearances = appearanceFinder.find(dirFile, memberInfo.memberNum);
        PreviewFormatUtils::appendScoreAppearances(out, appearances, appearanceFinder, true);
    }

    return out;
}

void GenericPreview::formatTypeSpecificData(std::string& out,
                                            DirectorFile& dirFile,
                                            const model::CastMemberInfo& memberInfo) const {
    const auto member = memberInfo.member;
    if (member == nullptr) {
        return;
    }

    switch (memberInfo.memberType) {
        case cast::MemberType::Shape: {
            try {
                const auto info = cast::ShapeInfo::parse(member->specificData());
                out += "\n--- Shape Info ---\n";
                out += "Shape Type: " + shapeTypeName(info.shapeType) + "\n";
                out += "Dimensions: " + std::to_string(info.width) + "x" + std::to_string(info.height) + "\n";
                out += "Reg Point: (" + std::to_string(info.regX) + ", " + std::to_string(info.regY) + ")\n";
                out += "Color: " + std::to_string(info.color) + "\n";
            } catch (const std::exception& e) {
                out += "Error parsing shape info: ";
                out += e.what();
                out += "\n";
            }
            break;
        }
        case cast::MemberType::FilmLoop: {
            try {
                const auto info = cast::FilmLoopInfo::parse(member->specificData());
                out += "\n--- Film Loop Info ---\n";
                out += "Dimensions: " + std::to_string(info.width()) + "x" + std::to_string(info.height()) + "\n";
                out += "Reg Point: (" + std::to_string(info.regX()) + ", " + std::to_string(info.regY()) + ")\n";
            } catch (const std::exception& e) {
                out += "Error parsing film loop info: ";
                out += e.what();
                out += "\n";
            }
            break;
        }
        case cast::MemberType::Sound: {
            const auto sound = scanning::MemberResolver::findSoundForMember(dirFile, member);
            if (sound != nullptr) {
                out += "\n--- Sound Info ---\n";
                out += "Codec: " + std::string(sound->isMp3() ? "MP3" : "PCM") + "\n";
                out += "Sample Rate: " + std::to_string(sound->sampleRate()) + " Hz\n";
                out += "Bits Per Sample: " + std::to_string(sound->bitsPerSample()) + "\n";
                out += "Channels: " + std::to_string(sound->channelCount()) + "\n";
                out += "Duration: " + formatSeconds(sound->durationSeconds()) + " seconds\n";
                out += "Audio Data Size: " + std::to_string(sound->audioData().size()) + " bytes\n";
            } else {
                out += "\n[Sound data not found]\n";
            }
            break;
        }
        case cast::MemberType::Palette: {
            const auto palette = scanning::MemberResolver::findPaletteForMember(dirFile, member);
            if (palette != nullptr) {
                out += "\n";
                PreviewFormatUtils::appendPaletteInfo(out, palette->colors());
            } else {
                out += "\n[Palette data not found]\n";
            }
            break;
        }
        case cast::MemberType::DigitalVideo:
            out += "\n[Video/Flash member]\n";
            break;
        default: {
            out += "\n--- Hex Dump (first 256 bytes) ---\n";
            const auto& data = member->specificData();
            const auto len = std::min<std::size_t>(data.size(), 256);
            std::ostringstream row;
            for (std::size_t i = 0; i < len; i += 16) {
                row.str("");
                row.clear();
                row << std::uppercase << std::hex << std::setfill('0') << std::setw(4) << i << ": ";
                for (std::size_t j = 0; j < 16 && i + j < len; ++j) {
                    row << std::setw(2) << static_cast<int>(data[i + j]) << " ";
                }
                row << std::dec << std::setfill(' ') << "\n";
                out += row.str();
            }
            break;
        }
    }
}

} // namespace libreshockwave::editor::preview
