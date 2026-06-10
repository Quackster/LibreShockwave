#pragma once

#include <functional>
#include <string_view>

#include "libreshockwave/lingo/Datum.hpp"

namespace libreshockwave::lingo {

class LingoValueParser {
public:
    using IdentifierResolver = std::function<Datum(std::string_view identifier)>;

    [[nodiscard]] static Datum parseLiteral(std::string_view expression);
    [[nodiscard]] static Datum parseWithPartial(std::string_view expression,
                                                IdentifierResolver identifierResolver = {});
};

} // namespace libreshockwave::lingo
