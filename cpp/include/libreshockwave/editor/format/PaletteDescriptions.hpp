#pragma once

#include <string>

namespace libreshockwave::editor::format {

class PaletteDescriptions {
public:
    [[nodiscard]] static std::string get(int paletteId);
};

} // namespace libreshockwave::editor::format
