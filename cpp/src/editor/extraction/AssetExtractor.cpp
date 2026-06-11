#include "libreshockwave/editor/extraction/AssetExtractor.hpp"

#include <cctype>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <fstream>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "libreshockwave/audio/SoundConverter.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/editor/format/InstructionFormatter.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"

namespace libreshockwave::editor::extraction {
namespace {

bool writeBytes(const std::filesystem::path& path, const std::vector<std::uint8_t>& data) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        return false;
    }
    out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    return out.good();
}

bool writeText(const std::filesystem::path& path, const std::string& data) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        return false;
    }
    out.write(data.data(), static_cast<std::streamsize>(data.size()));
    return out.good();
}

std::string normalizeTextLineEndings(std::string value) {
    std::string normalized;
    normalized.reserve(value.size());
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (value[index] == '\r') {
            normalized.push_back('\n');
            if (index + 1 < value.size() && value[index + 1] == '\n') {
                ++index;
            }
        } else {
            normalized.push_back(value[index]);
        }
    }
    return normalized;
}

std::string resolveName(const chunks::ScriptNamesChunk* names, int nameId) {
    if (names != nullptr) {
        return names->getName(nameId);
    }
    return "#" + std::to_string(nameId);
}

std::string join(const std::vector<std::string>& values, const std::string& separator) {
    std::string result;
    for (const auto& value : values) {
        if (!result.empty()) {
            result += separator;
        }
        result += value;
    }
    return result;
}

} // namespace

AssetExtractor::AssetExtractor(BitmapWriter bitmapWriter)
    : bitmapWriter_(std::move(bitmapWriter)) {}

void AssetExtractor::setBitmapWriter(BitmapWriter bitmapWriter) {
    bitmapWriter_ = std::move(bitmapWriter);
}

bool AssetExtractor::extract(DirectorFile& dirFile,
                             const model::CastMemberInfo& memberInfo,
                             const std::filesystem::path& outputDir) const {
    try {
        std::filesystem::create_directories(outputDir);
        auto& memberFile = resolveMemberFile(dirFile, memberInfo);
        const auto safeName = sanitizeFileName(memberInfo.name);

        switch (memberInfo.memberType) {
            case cast::MemberType::Bitmap:
                return extractBitmap(memberFile, memberInfo, outputDir, safeName);
            case cast::MemberType::Sound:
                return extractSound(memberFile, memberInfo, outputDir, safeName);
            case cast::MemberType::Script:
                return extractScript(memberFile, memberInfo, outputDir, safeName);
            case cast::MemberType::Text:
            case cast::MemberType::Button:
                return extractText(memberFile, memberInfo, outputDir, safeName);
            case cast::MemberType::Palette:
                return extractPalette(memberFile, memberInfo, outputDir, safeName);
            default:
                return extractGeneric(memberFile, memberInfo, outputDir, safeName);
        }
    } catch (const std::exception&) {
        return false;
    }
}

std::string AssetExtractor::sanitizeFileName(const std::string& name) {
    std::string result;
    result.reserve(name.size());
    for (unsigned char c : name) {
        if (std::isalnum(c) || c == '.' || c == '_' || c == '-') {
            result.push_back(static_cast<char>(c));
        } else {
            result.push_back('_');
        }
    }
    return result;
}

std::filesystem::path AssetExtractor::resolveUnique(const std::filesystem::path& dir,
                                                    const std::string& baseName,
                                                    const std::string& extension) {
    auto path = dir / (baseName + extension);
    int counter = 1;
    while (std::filesystem::exists(path)) {
        path = dir / (baseName + "_" + std::to_string(counter++) + extension);
    }
    return path;
}

DirectorFile& AssetExtractor::resolveMemberFile(DirectorFile& fallback, const model::CastMemberInfo& memberInfo) {
    if (memberInfo.member != nullptr && memberInfo.member->file() != nullptr) {
        return *const_cast<DirectorFile*>(memberInfo.member->file());
    }
    return fallback;
}

bool AssetExtractor::extractBitmap(DirectorFile& dirFile,
                                   const model::CastMemberInfo& memberInfo,
                                   const std::filesystem::path& outputDir,
                                   std::string safeName) const {
    if (!bitmapWriter_) {
        return false;
    }
    if (safeName.empty()) {
        safeName = "bitmap_" + std::to_string(memberInfo.memberNum);
    }
    const auto bitmap = dirFile.decodeBitmap(memberInfo.member);
    if (!bitmap.has_value()) {
        return false;
    }
    return bitmapWriter_(bitmap.value(), resolveUnique(outputDir, safeName, ".png"));
}

bool AssetExtractor::extractSound(DirectorFile& dirFile,
                                  const model::CastMemberInfo& memberInfo,
                                  const std::filesystem::path& outputDir,
                                  std::string safeName) const {
    if (safeName.empty()) {
        safeName = "sound_" + std::to_string(memberInfo.memberNum);
    }

    const auto sound = scanning::MemberResolver::findSoundForMember(dirFile, memberInfo.member);
    if (sound == nullptr) {
        return false;
    }

    std::vector<std::uint8_t> audioData;
    std::string extension;
    if (sound->isMp3()) {
        const auto mp3 = audio::SoundConverter::extractMp3(*sound);
        if (!mp3.has_value()) {
            return false;
        }
        audioData = mp3.value();
        extension = ".mp3";
    } else {
        audioData = audio::SoundConverter::toWav(*sound);
        extension = ".wav";
    }

    if (audioData.empty()) {
        return false;
    }
    return writeBytes(resolveUnique(outputDir, safeName, extension), audioData);
}

bool AssetExtractor::extractScript(DirectorFile& dirFile,
                                   const model::CastMemberInfo& memberInfo,
                                   const std::filesystem::path& outputDir,
                                   std::string safeName) const {
    if (safeName.empty()) {
        safeName = "script_" + std::to_string(memberInfo.memberNum);
    }

    const auto script = scanning::MemberResolver::findScriptForMember(dirFile, memberInfo.member);
    const auto names = dirFile.scriptNames();
    std::string out;
    if (script == nullptr) {
        out += "-- No bytecode found for script member #" + std::to_string(memberInfo.memberNum) + "\n";
    } else {
        out += "-- Script: " + memberInfo.name + "\n";
        out += "-- Type: " + ::libreshockwave::format::getScriptTypeName(script->scriptType()) + "\n\n";

        for (const auto& property : script->properties()) {
            out += "property " + resolveName(names.get(), property.nameId) + "\n";
        }
        if (!script->properties().empty()) {
            out += "\n";
        }

        for (const auto& global : script->globals()) {
            out += "global " + resolveName(names.get(), global.nameId) + "\n";
        }
        if (!script->globals().empty()) {
            out += "\n";
        }

        for (const auto& handler : script->handlers()) {
            std::vector<std::string> argNames;
            argNames.reserve(handler.argNameIds.size());
            for (int nameId : handler.argNameIds) {
                argNames.push_back(resolveName(names.get(), nameId));
            }

            out += "on " + resolveName(names.get(), handler.nameId);
            const auto args = join(argNames, ", ");
            if (!args.empty()) {
                out += " " + args;
            }
            out += "\n";

            for (const auto& instruction : handler.instructions) {
                out += "  " + ::libreshockwave::editor::format::InstructionFormatter::format(
                    instruction,
                    *script,
                    names.get()) + "\n";
            }
            out += "end\n\n";
        }
    }

    return writeText(resolveUnique(outputDir, safeName, ".ls"), out);
}

bool AssetExtractor::extractText(DirectorFile& dirFile,
                                 const model::CastMemberInfo& memberInfo,
                                 const std::filesystem::path& outputDir,
                                 std::string safeName) const {
    if (safeName.empty()) {
        safeName = "text_" + std::to_string(memberInfo.memberNum);
    }

    const auto text = scanning::MemberResolver::findTextForMember(dirFile, memberInfo.member);
    if (text == nullptr) {
        return false;
    }
    return writeText(resolveUnique(outputDir, safeName, ".txt"), normalizeTextLineEndings(text->text()));
}

bool AssetExtractor::extractPalette(DirectorFile& dirFile,
                                    const model::CastMemberInfo& memberInfo,
                                    const std::filesystem::path& outputDir,
                                    std::string safeName) const {
    if (safeName.empty()) {
        safeName = "palette_" + std::to_string(memberInfo.memberNum);
    }

    const auto palette = scanning::MemberResolver::findPaletteForMember(dirFile, memberInfo.member);
    if (palette == nullptr) {
        return false;
    }

    std::string out = "JASC-PAL\n0100\n" + std::to_string(palette->colors().size()) + "\n";
    for (const auto color : palette->colors()) {
        const int r = static_cast<int>((color >> 16U) & 0xFFU);
        const int g = static_cast<int>((color >> 8U) & 0xFFU);
        const int b = static_cast<int>(color & 0xFFU);
        out += std::to_string(r) + " " + std::to_string(g) + " " + std::to_string(b) + "\n";
    }
    return writeText(resolveUnique(outputDir, safeName, ".pal"), out);
}

bool AssetExtractor::extractGeneric(DirectorFile&,
                                    const model::CastMemberInfo& memberInfo,
                                    const std::filesystem::path& outputDir,
                                    std::string safeName) const {
    if (memberInfo.member == nullptr || memberInfo.member->specificData().empty()) {
        return false;
    }
    if (safeName.empty()) {
        safeName = std::string(cast::name(memberInfo.memberType)) + "_" + std::to_string(memberInfo.memberNum);
    }
    return writeBytes(resolveUnique(outputDir, safeName, ".bin"), memberInfo.member->specificData());
}

} // namespace libreshockwave::editor::extraction
