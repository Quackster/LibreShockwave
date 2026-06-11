#pragma once

#include <string>
#include <vector>

#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::model {

struct FileNode {
    std::string filePath;
    std::string fileName;
    std::vector<CastMemberInfo> members;

    [[nodiscard]] std::string toString() const;

    friend bool operator==(const FileNode&, const FileNode&) = default;
};

} // namespace libreshockwave::editor::model
