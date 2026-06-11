#include "libreshockwave/editor/score/ScoreColors.hpp"

#include <algorithm>
#include <cctype>
#include <string>

namespace libreshockwave::editor::score {
namespace {

std::string upperAscii(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char ch) {
        return static_cast<char>(std::toupper(ch));
    });
    return result;
}

} // namespace

std::optional<ScoreColor> ScoreColors::getColor(std::string_view memberType) {
    const std::string normalized = upperAscii(memberType);
    if (normalized == "BITMAP") return BITMAP;
    if (normalized == "TEXT") return TEXT;
    if (normalized == "SHAPE") return SHAPE;
    if (normalized == "SCRIPT") return SCRIPT;
    if (normalized == "SOUND") return SOUND;
    if (normalized == "FILM_LOOP" || normalized == "FILMLOOP") return FILM_LOOP;
    if (normalized == "BUTTON") return BUTTON;
    if (normalized == "FONT") return FONT;
    if (normalized == "PALETTE") return PALETTE;
    if (normalized == "FIELD") return FIELD;
    if (normalized == "TRANSITION") return TRANSITION;
    if (normalized == "XTRA") return XTRA;
    return UNKNOWN;
}

} // namespace libreshockwave::editor::score
