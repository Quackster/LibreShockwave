#include "libreshockwave/editor/scanning/FileProcessor.hpp"

#include <exception>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/cast/BitmapInfo.hpp"
#include "libreshockwave/cast/FilmLoopInfo.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/cast/ShapeInfo.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"

namespace libreshockwave::editor::scanning {
namespace {

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

std::string formatOneDecimal(double value) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(1) << value;
    return out.str();
}

} // namespace

std::vector<model::CastMemberInfo> FileProcessor::processMembers(DirectorFile& dirFile) const {
    std::vector<model::CastMemberInfo> members;
    for (const auto& member : dirFile.castMembers()) {
        if (member == nullptr || member->memberType() == cast::MemberType::Null) {
            continue;
        }

        auto name = member->name();
        if (name.empty()) {
            name = "Unnamed #" + std::to_string(member->id().value());
        }
        members.push_back(model::CastMemberInfo{
            member->id().value(),
            std::move(name),
            member,
            member->memberType(),
            buildMemberDetails(dirFile, member)
        });
    }
    return members;
}

std::string FileProcessor::buildMemberDetails(DirectorFile& dirFile,
                                              const std::shared_ptr<chunks::CastMemberChunk>& member) const {
    if (member == nullptr) {
        return "";
    }

    const auto type = member->memberType();
    const auto& specificData = member->specificData();
    if (type == cast::MemberType::Bitmap && !specificData.empty()) {
        try {
            const auto info = cast::BitmapInfo::parse(specificData);
            return std::to_string(info.width) + "x" + std::to_string(info.height) + ", " +
                   std::to_string(info.bitDepth) + "-bit";
        } catch (const std::exception&) {
            return "";
        }
    }
    if (type == cast::MemberType::Shape && !specificData.empty()) {
        try {
            const auto info = cast::ShapeInfo::parse(specificData);
            return shapeTypeName(info.shapeType) + " " + std::to_string(info.width) + "x" +
                   std::to_string(info.height);
        } catch (const std::exception&) {
            return "";
        }
    }
    if (type == cast::MemberType::FilmLoop && !specificData.empty()) {
        try {
            const auto info = cast::FilmLoopInfo::parse(specificData);
            return std::to_string(info.width()) + "x" + std::to_string(info.height());
        } catch (const std::exception&) {
            return "";
        }
    }
    if (type == cast::MemberType::Script) {
        return buildScriptDetails(dirFile, member);
    }
    if (type == cast::MemberType::Sound) {
        return buildSoundDetails(dirFile, member);
    }
    if (type == cast::MemberType::Palette) {
        return buildPaletteDetails(dirFile, member);
    }
    if (type == cast::MemberType::Text || type == cast::MemberType::Button) {
        return buildTextDetails(dirFile, member);
    }
    return "";
}

std::string FileProcessor::buildScriptDetails(DirectorFile& dirFile,
                                              const std::shared_ptr<chunks::CastMemberChunk>& member) const {
    const auto script = MemberResolver::findScriptForMember(dirFile, member);
    if (script == nullptr) {
        return "";
    }

    const auto scriptNames = dirFile.scriptNames();
    const auto scriptTypeName = format::getScriptTypeName(script->scriptType());
    std::vector<std::string> handlerNames;
    if (scriptNames != nullptr) {
        handlerNames.reserve(script->handlers().size());
        for (const auto& handler : script->handlers()) {
            auto handlerName = format::resolveName(scriptNames.get(), handler.nameId);
            if (!handlerName.starts_with("<")) {
                handlerNames.push_back(std::move(handlerName));
            }
        }
    }

    if (handlerNames.empty()) {
        return scriptTypeName;
    }

    std::string handlers;
    if (handlerNames.size() <= 3) {
        handlers = handlerNames.front();
        for (std::size_t index = 1; index < handlerNames.size(); ++index) {
            handlers += ", " + handlerNames[index];
        }
    } else {
        handlers = handlerNames[0] + ", " + handlerNames[1] + "... +" +
                   std::to_string(handlerNames.size() - 2);
    }
    return scriptTypeName + " [" + handlers + "]";
}

std::string FileProcessor::buildSoundDetails(DirectorFile& dirFile,
                                             const std::shared_ptr<chunks::CastMemberChunk>& member) const {
    const auto sound = MemberResolver::findSoundForMember(dirFile, member);
    if (sound == nullptr) {
        return "sound data";
    }
    const std::string codec = sound->isMp3() ? "MP3" : "PCM";
    return codec + ", " + std::to_string(sound->sampleRate()) + "Hz, " +
           formatOneDecimal(sound->durationSeconds()) + "s";
}

std::string FileProcessor::buildPaletteDetails(DirectorFile& dirFile,
                                               const std::shared_ptr<chunks::CastMemberChunk>& member) const {
    const auto palette = MemberResolver::findPaletteForMember(dirFile, member);
    if (palette != nullptr) {
        return std::to_string(palette->colors().size()) + " colors";
    }
    return "palette";
}

std::string FileProcessor::buildTextDetails(DirectorFile& dirFile,
                                            const std::shared_ptr<chunks::CastMemberChunk>& member) const {
    const auto text = MemberResolver::findTextForMember(dirFile, member);
    if (text == nullptr) {
        return "";
    }
    auto normalized = format::normalizeLineEndings(text->text());
    normalized = format::truncate(normalized, 50);
    return "\"" + normalized + "\"";
}

} // namespace libreshockwave::editor::scanning
