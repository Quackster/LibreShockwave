#include "libreshockwave/editor/scanning/MemberResolver.hpp"

#include <cctype>
#include <cstddef>
#include <memory>
#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/ScriptContextChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"

namespace libreshockwave::editor::scanning {
namespace {

std::string trimAscii(std::string value) {
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back()))) {
        value.pop_back();
    }
    std::size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    return value.substr(start);
}

} // namespace

std::shared_ptr<chunks::ScriptChunk> MemberResolver::findScriptForMember(
    DirectorFile& dirFile,
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (member == nullptr) {
        return nullptr;
    }

    const int scriptId = member->scriptId();
    const auto context = dirFile.scriptContext();
    if (context != nullptr && scriptId > 0) {
        const auto& entries = context->entries();
        if (scriptId <= static_cast<int>(entries.size())) {
            const auto chunkId = entries[static_cast<std::size_t>(scriptId - 1)].id;
            for (const auto& script : dirFile.scripts()) {
                if (script != nullptr && script->id().value() == chunkId.value()) {
                    return script;
                }
            }
        }
    }

    for (const auto& script : dirFile.scripts()) {
        if (script != nullptr && script->id().value() == member->id().value()) {
            return script;
        }
    }

    const auto keyTable = dirFile.keyTable();
    if (keyTable != nullptr) {
        for (const auto& entry : keyTable->getEntriesForOwner(member->id())) {
            const auto fourcc = trimAscii(entry.fourccString());
            if (fourcc != "Lscr" && fourcc != "rcsL") {
                continue;
            }
            for (const auto& script : dirFile.scripts()) {
                if (script != nullptr && script->id().value() == entry.sectionId.value()) {
                    return script;
                }
            }
        }
    }

    return nullptr;
}

std::shared_ptr<chunks::SoundChunk> MemberResolver::findSoundForMember(
    DirectorFile& dirFile,
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (member == nullptr) {
        return nullptr;
    }

    const auto keyTable = dirFile.keyTable();
    if (keyTable == nullptr) {
        return nullptr;
    }

    for (const auto& entry : keyTable->getEntriesForOwner(member->id())) {
        const auto chunk = dirFile.getChunk(entry.sectionId);
        if (auto sound = std::dynamic_pointer_cast<chunks::SoundChunk>(chunk)) {
            return sound;
        }
        if (auto media = std::dynamic_pointer_cast<chunks::MediaChunk>(chunk)) {
            return std::make_shared<chunks::SoundChunk>(media->toSoundChunk());
        }
    }
    return nullptr;
}

std::shared_ptr<chunks::PaletteChunk> MemberResolver::findPaletteForMember(
    DirectorFile& dirFile,
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (member == nullptr) {
        return nullptr;
    }

    const auto keyTable = dirFile.keyTable();
    if (keyTable != nullptr) {
        for (const auto& entry : keyTable->getEntriesForOwner(member->id())) {
            const auto fourcc = trimAscii(entry.fourccString());
            if (fourcc != "CLUT" && fourcc != "TULC") {
                continue;
            }
            if (auto palette = std::dynamic_pointer_cast<chunks::PaletteChunk>(dirFile.getChunk(entry.sectionId))) {
                return palette;
            }
        }
    }

    for (const auto& palette : dirFile.palettes()) {
        if (palette != nullptr && palette->id().value() == member->id().value()) {
            return palette;
        }
    }
    return nullptr;
}

std::shared_ptr<chunks::TextChunk> MemberResolver::findTextForMember(
    DirectorFile& dirFile,
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (member == nullptr) {
        return nullptr;
    }

    const auto keyTable = dirFile.keyTable();
    if (keyTable == nullptr) {
        return nullptr;
    }

    const auto entries = keyTable->getEntriesForOwner(member->id());
    for (const auto& entry : entries) {
        const auto fourcc = trimAscii(entry.fourccString());
        if (fourcc != "STXT" && fourcc != "TXTS") {
            continue;
        }
        if (auto text = std::dynamic_pointer_cast<chunks::TextChunk>(dirFile.getChunk(entry.sectionId))) {
            return text;
        }
    }

    for (const auto& entry : entries) {
        if (auto text = std::dynamic_pointer_cast<chunks::TextChunk>(dirFile.getChunk(entry.sectionId))) {
            return text;
        }
    }
    return nullptr;
}

std::string MemberResolver::getScriptTypeName(chunks::ScriptChunkType scriptType) {
    return format::getScriptTypeName(scriptType);
}

} // namespace libreshockwave::editor::scanning
