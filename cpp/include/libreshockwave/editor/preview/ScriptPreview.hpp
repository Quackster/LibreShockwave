#pragma once

#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::editor::preview {

class ScriptPreview {
public:
    [[nodiscard]] std::string format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const;

private:
    void formatHandler(std::string& out,
                       const chunks::ScriptChunk::Handler& handler,
                       const chunks::ScriptChunk& script,
                       const chunks::ScriptNamesChunk* names) const;
};

} // namespace libreshockwave::editor::preview
