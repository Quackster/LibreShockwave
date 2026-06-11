#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor::script {

enum class LingoTokenType {
    Keyword,
    Command,
    Function,
    Event,
    String,
    Number,
    Comment,
    Symbol,
    Identifier,
    Operator,
    Whitespace,
    Newline
};

struct LingoToken {
    LingoTokenType type;
    int start;
    int end;
    std::string text;

    friend bool operator==(const LingoToken&, const LingoToken&) = default;
};

class LingoTokenizer {
public:
    [[nodiscard]] static std::vector<LingoToken> tokenize(std::string_view source);
};

[[nodiscard]] std::string_view tokenTypeName(LingoTokenType type);

} // namespace libreshockwave::editor::script
