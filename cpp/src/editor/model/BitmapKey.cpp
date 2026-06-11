#include "libreshockwave/editor/model/BitmapKey.hpp"

namespace libreshockwave::editor::model {

std::size_t BitmapKeyHash::operator()(const BitmapKey& key) const {
    const std::size_t pathHash = std::hash<std::string>{}(key.filePath);
    const std::size_t memberHash = std::hash<int>{}(key.memberNum);
    return pathHash ^ (memberHash + 0x9e3779b9U + (pathHash << 6U) + (pathHash >> 2U));
}

} // namespace libreshockwave::editor::model
