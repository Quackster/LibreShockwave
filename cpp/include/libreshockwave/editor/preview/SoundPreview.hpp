#pragma once

#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::preview {

class SoundPreview {
public:
    [[nodiscard]] std::string format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const;
    [[nodiscard]] bool isPlayable(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const;
};

} // namespace libreshockwave::editor::preview
