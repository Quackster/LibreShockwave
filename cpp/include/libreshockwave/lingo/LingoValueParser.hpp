#pragma once

#include <string_view>

#include "libreshockwave/lingo/Datum.hpp"

namespace libreshockwave::lingo {

class LingoValueParser {
public:
    [[nodiscard]] static Datum parseLiteral(std::string_view expression);
    [[nodiscard]] static Datum parseWithPartial(std::string_view expression);
};

} // namespace libreshockwave::lingo
