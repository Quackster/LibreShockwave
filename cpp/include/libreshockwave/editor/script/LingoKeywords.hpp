#pragma once

#include <string>
#include <string_view>
#include <unordered_set>

namespace libreshockwave::editor::script {

class LingoKeywords {
public:
    [[nodiscard]] static const std::unordered_set<std::string>& keywords();
    [[nodiscard]] static const std::unordered_set<std::string>& commands();
    [[nodiscard]] static const std::unordered_set<std::string>& functions();
    [[nodiscard]] static const std::unordered_set<std::string>& events();

    [[nodiscard]] static bool isKeyword(std::string_view value);
    [[nodiscard]] static bool isCommand(std::string_view value);
    [[nodiscard]] static bool isFunction(std::string_view value);
    [[nodiscard]] static bool isEvent(std::string_view value);
};

} // namespace libreshockwave::editor::script
