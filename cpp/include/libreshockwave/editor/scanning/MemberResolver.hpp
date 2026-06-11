#pragma once

#include <memory>
#include <string>

#include "libreshockwave/chunks/ScriptChunk.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::chunks {
class CastMemberChunk;
class PaletteChunk;
class SoundChunk;
class TextChunk;
}

namespace libreshockwave::editor::scanning {

class MemberResolver {
public:
    MemberResolver() = delete;

    [[nodiscard]] static std::shared_ptr<chunks::ScriptChunk> findScriptForMember(
        DirectorFile& dirFile,
        const std::shared_ptr<chunks::CastMemberChunk>& member);
    [[nodiscard]] static std::shared_ptr<chunks::SoundChunk> findSoundForMember(
        DirectorFile& dirFile,
        const std::shared_ptr<chunks::CastMemberChunk>& member);
    [[nodiscard]] static std::shared_ptr<chunks::PaletteChunk> findPaletteForMember(
        DirectorFile& dirFile,
        const std::shared_ptr<chunks::CastMemberChunk>& member);
    [[nodiscard]] static std::shared_ptr<chunks::TextChunk> findTextForMember(
        DirectorFile& dirFile,
        const std::shared_ptr<chunks::CastMemberChunk>& member);

    [[nodiscard]] static std::string getScriptTypeName(chunks::ScriptChunkType scriptType);
};

} // namespace libreshockwave::editor::scanning
