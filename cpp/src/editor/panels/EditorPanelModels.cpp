#include "libreshockwave/editor/panels/EditorPanelModels.hpp"

#include <cctype>
#include <string>
#include <utility>

namespace libreshockwave::editor::panels {
namespace {

std::string trim(std::string_view value) {
    std::size_t begin = 0;
    while (begin < value.size() && std::isspace(static_cast<unsigned char>(value[begin]))) {
        ++begin;
    }
    std::size_t end = value.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return std::string(value.substr(begin, end - begin));
}

std::string normalizeLineEndings(std::string_view text) {
    std::string normalized;
    normalized.reserve(text.size());
    for (std::size_t index = 0; index < text.size(); ++index) {
        const char ch = text[index];
        if (ch == '\r') {
            normalized += '\n';
            if (index + 1 < text.size() && text[index + 1] == '\n') {
                ++index;
            }
        } else {
            normalized += ch;
        }
    }
    return normalized;
}

std::string memberDisplayName(std::string_view memberName, int memberNumber) {
    if (!memberName.empty()) {
        return std::string(memberName);
    }
    return "#" + std::to_string(memberNumber);
}

} // namespace

MessageConsoleModel::MessageConsoleModel() : output_(welcomeText()) {}

const std::string& MessageConsoleModel::output() const {
    return output_;
}

void MessageConsoleModel::reset() {
    output_ = welcomeText();
}

void MessageConsoleModel::appendOutput(std::string_view text) {
    output_ += text;
    output_ += '\n';
}

bool MessageConsoleModel::executeCommand(std::string_view command) {
    const auto trimmed = trim(command);
    if (trimmed.empty()) {
        return false;
    }
    output_ += commandPromptLine(trimmed);
    output_ += notImplementedLine();
    return true;
}

std::string MessageConsoleModel::welcomeText() {
    return "Welcome to LibreShockwave Editor\n-- Type Lingo commands below\n\n";
}

std::string MessageConsoleModel::commandPromptLine(std::string_view command) {
    return ">> " + std::string(command) + "\n";
}

std::string MessageConsoleModel::notImplementedLine() {
    return "-- (command execution not yet implemented)\n";
}

int ToolPaletteModel::columnCount() {
    return 2;
}

std::vector<ToolPaletteTool> ToolPaletteModel::tools() {
    const std::vector<std::string> labels{
        "Arrow",
        "Rotate",
        "Hand",
        "Zoom",
        "Line",
        "Rect",
        "Round Rect",
        "Ellipse",
        "Text",
        "Field",
        "Button",
        "Check Box",
        "Radio",
        "Color",
    };

    std::vector<ToolPaletteTool> tools;
    tools.reserve(labels.size());
    for (std::size_t index = 0; index < labels.size(); ++index) {
        tools.push_back(ToolPaletteTool{labels[index],
                                        static_cast<int>(index / columnCount()),
                                        static_cast<int>(index % columnCount())});
    }
    return tools;
}

std::vector<std::string> ColorPalettesModel::paletteOptions() {
    return {
        "System - Win",
        "System - Mac",
        "Rainbow",
        "Grayscale",
        "Pastels",
        "Vivid",
        "NTSC",
        "Metallic",
        "Web 216",
    };
}

std::string ColorPalettesModel::selectorLabel() {
    return "Palette: ";
}

std::string ColorPalettesModel::placeholderText() {
    return "Color Palettes - Not yet implemented";
}

std::vector<DisabledPanelAction> FieldEditorModel::toolbarActions() {
    return {
        DisabledPanelAction{"Wrap", "Wrap (not yet implemented)"},
        DisabledPanelAction{"Scroll", "Scroll (not yet implemented)"},
    };
}

FieldEditorState FieldEditorModel::emptyState() {
    return FieldEditorState{"Field", "No field member selected", " Ready"};
}

FieldEditorState FieldEditorModel::missingDataState() {
    return FieldEditorState{"Field", "[Field data not found]", " No data"};
}

FieldEditorState FieldEditorModel::loadedState(std::string_view memberName,
                                               int memberNumber,
                                               std::string_view text) {
    auto normalized = normalizeLineEndings(text);
    const auto characterCount = normalized.size();
    const auto name = memberDisplayName(memberName, memberNumber);
    return FieldEditorState{
        "Field: " + name,
        std::move(normalized),
        " " + name + "  " + std::to_string(characterCount) + " characters",
    };
}

} // namespace libreshockwave::editor::panels
