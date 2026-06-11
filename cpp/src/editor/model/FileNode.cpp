#include "libreshockwave/editor/model/FileNode.hpp"

namespace libreshockwave::editor::model {

std::string FileNode::toString() const {
    return fileName + " (" + std::to_string(members.size()) + " members)";
}

} // namespace libreshockwave::editor::model
