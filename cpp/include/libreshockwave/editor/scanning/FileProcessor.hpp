#pragma once

#include <memory>
#include <string>
#include <vector>

#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::chunks {
class CastMemberChunk;
}

namespace libreshockwave::editor::scanning {

class FileProcessor {
public:
    [[nodiscard]] std::vector<model::CastMemberInfo> processMembers(DirectorFile& dirFile) const;
    [[nodiscard]] std::string buildMemberDetails(DirectorFile& dirFile,
                                                 const std::shared_ptr<chunks::CastMemberChunk>& member) const;

private:
    [[nodiscard]] std::string buildScriptDetails(DirectorFile& dirFile,
                                                 const std::shared_ptr<chunks::CastMemberChunk>& member) const;
    [[nodiscard]] std::string buildSoundDetails(DirectorFile& dirFile,
                                                const std::shared_ptr<chunks::CastMemberChunk>& member) const;
    [[nodiscard]] std::string buildPaletteDetails(DirectorFile& dirFile,
                                                  const std::shared_ptr<chunks::CastMemberChunk>& member) const;
    [[nodiscard]] std::string buildTextDetails(DirectorFile& dirFile,
                                               const std::shared_ptr<chunks::CastMemberChunk>& member) const;
};

} // namespace libreshockwave::editor::scanning
