#pragma once

#include <cstddef>
#include <functional>
#include <string>

namespace libreshockwave::editor::model {

struct BitmapKey {
    std::string filePath;
    int memberNum{};

    friend bool operator==(const BitmapKey&, const BitmapKey&) = default;
};

struct BitmapKeyHash {
    [[nodiscard]] std::size_t operator()(const BitmapKey& key) const;
};

} // namespace libreshockwave::editor::model
