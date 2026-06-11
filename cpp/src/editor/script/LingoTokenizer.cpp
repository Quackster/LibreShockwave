#include "libreshockwave/editor/script/LingoTokenizer.hpp"

#include <cctype>
#include <string>

#include "libreshockwave/editor/script/LingoKeywords.hpp"

namespace libreshockwave::editor::script {
namespace {

bool isWhitespace(char ch) {
    return std::isspace(static_cast<unsigned char>(ch)) != 0;
}

bool isLetter(char ch) {
    return std::isalpha(static_cast<unsigned char>(ch)) != 0;
}

bool isDigit(char ch) {
    return std::isdigit(static_cast<unsigned char>(ch)) != 0;
}

bool isLetterOrDigit(char ch) {
    return std::isalnum(static_cast<unsigned char>(ch)) != 0;
}

std::string lowerAscii(std::string_view value) {
    std::string result(value);
    for (char& ch : result) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return result;
}

std::string slice(std::string_view source, int start, int end) {
    return std::string(source.substr(static_cast<std::size_t>(start),
                                     static_cast<std::size_t>(end - start)));
}

} // namespace

std::vector<LingoToken> LingoTokenizer::tokenize(std::string_view source) {
    std::vector<LingoToken> tokens;
    int i = 0;
    const int len = static_cast<int>(source.size());

    while (i < len) {
        const char ch = source[static_cast<std::size_t>(i)];

        if (ch == '\n') {
            tokens.push_back({LingoTokenType::Newline, i, i + 1, "\n"});
            ++i;
            continue;
        }

        if (isWhitespace(ch)) {
            const int start = i;
            while (i < len && source[static_cast<std::size_t>(i)] != '\n' &&
                   isWhitespace(source[static_cast<std::size_t>(i)])) {
                ++i;
            }
            tokens.push_back({LingoTokenType::Whitespace, start, i, slice(source, start, i)});
            continue;
        }

        if (ch == '-' && i + 1 < len && source[static_cast<std::size_t>(i + 1)] == '-') {
            const int start = i;
            while (i < len && source[static_cast<std::size_t>(i)] != '\n') {
                ++i;
            }
            tokens.push_back({LingoTokenType::Comment, start, i, slice(source, start, i)});
            continue;
        }

        if (ch == '"') {
            const int start = i;
            ++i;
            while (i < len && source[static_cast<std::size_t>(i)] != '"' &&
                   source[static_cast<std::size_t>(i)] != '\n') {
                ++i;
            }
            if (i < len && source[static_cast<std::size_t>(i)] == '"') {
                ++i;
            }
            tokens.push_back({LingoTokenType::String, start, i, slice(source, start, i)});
            continue;
        }

        if (ch == '#') {
            const int start = i;
            ++i;
            while (i < len &&
                   (isLetterOrDigit(source[static_cast<std::size_t>(i)]) ||
                    source[static_cast<std::size_t>(i)] == '_')) {
                ++i;
            }
            tokens.push_back({LingoTokenType::Symbol, start, i, slice(source, start, i)});
            continue;
        }

        if (isDigit(ch) || (ch == '.' && i + 1 < len && isDigit(source[static_cast<std::size_t>(i + 1)]))) {
            const int start = i;
            while (i < len &&
                   (isDigit(source[static_cast<std::size_t>(i)]) ||
                    source[static_cast<std::size_t>(i)] == '.')) {
                ++i;
            }
            tokens.push_back({LingoTokenType::Number, start, i, slice(source, start, i)});
            continue;
        }

        if (isLetter(ch) || ch == '_') {
            const int start = i;
            while (i < len &&
                   (isLetterOrDigit(source[static_cast<std::size_t>(i)]) ||
                    source[static_cast<std::size_t>(i)] == '_')) {
                ++i;
            }
            std::string word = slice(source, start, i);
            const std::string lower = lowerAscii(word);

            LingoTokenType type = LingoTokenType::Identifier;
            if (LingoKeywords::isKeyword(word) || LingoKeywords::isKeyword(lower)) {
                type = LingoTokenType::Keyword;
            } else if (LingoKeywords::isCommand(lower)) {
                type = LingoTokenType::Command;
            } else if (LingoKeywords::isFunction(lower)) {
                type = LingoTokenType::Function;
            } else if (LingoKeywords::isEvent(lower)) {
                type = LingoTokenType::Event;
            }

            tokens.push_back({type, start, i, std::move(word)});
            continue;
        }

        tokens.push_back({LingoTokenType::Operator, i, i + 1, slice(source, i, i + 1)});
        ++i;
    }

    return tokens;
}

std::string_view tokenTypeName(LingoTokenType type) {
    switch (type) {
        case LingoTokenType::Keyword:
            return "KEYWORD";
        case LingoTokenType::Command:
            return "COMMAND";
        case LingoTokenType::Function:
            return "FUNCTION";
        case LingoTokenType::Event:
            return "EVENT";
        case LingoTokenType::String:
            return "STRING";
        case LingoTokenType::Number:
            return "NUMBER";
        case LingoTokenType::Comment:
            return "COMMENT";
        case LingoTokenType::Symbol:
            return "SYMBOL";
        case LingoTokenType::Identifier:
            return "IDENTIFIER";
        case LingoTokenType::Operator:
            return "OPERATOR";
        case LingoTokenType::Whitespace:
            return "WHITESPACE";
        case LingoTokenType::Newline:
            return "NEWLINE";
    }
    return "IDENTIFIER";
}

} // namespace libreshockwave::editor::script
