#include "libreshockwave/editor/format/PaletteDescriptions.hpp"

namespace libreshockwave::editor::format {

std::string PaletteDescriptions::get(int paletteId) {
    switch (paletteId) {
        case -1:
            return "System Mac";
        case -2:
            return "Rainbow";
        case -3:
            return "Grayscale";
        case -4:
            return "Pastels";
        case -5:
            return "Vivid";
        case -6:
            return "NTSC";
        case -7:
            return "Metallic";
        case -101:
            return "System Windows";
        case -102:
            return "System Windows (D4)";
        default:
            if (paletteId >= 0) {
                return "Cast Member #" + std::to_string(paletteId + 1);
            }
            return "Unknown (" + std::to_string(paletteId) + ")";
    }
}

} // namespace libreshockwave::editor::format
