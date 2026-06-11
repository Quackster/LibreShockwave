#pragma once

#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::preview {

class GenericPreview {
public:
    [[nodiscard]] std::string format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const;

private:
    void formatTypeSpecificData(std::string& out,
                                DirectorFile& dirFile,
                                const model::CastMemberInfo& memberInfo) const;
};

} // namespace libreshockwave::editor::preview
