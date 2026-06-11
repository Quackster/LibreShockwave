#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor::panels {

struct ToolPaletteTool {
    std::string label;
    int row{};
    int column{};

    friend bool operator==(const ToolPaletteTool&, const ToolPaletteTool&) = default;
};

struct DisabledPanelAction {
    std::string label;
    std::string tooltip;

    friend bool operator==(const DisabledPanelAction&, const DisabledPanelAction&) = default;
};

struct FieldEditorState {
    std::string title;
    std::string text;
    std::string status;

    friend bool operator==(const FieldEditorState&, const FieldEditorState&) = default;
};

class MessageConsoleModel {
public:
    MessageConsoleModel();

    [[nodiscard]] const std::string& output() const;
    void reset();
    void appendOutput(std::string_view text);
    bool executeCommand(std::string_view command);

    [[nodiscard]] static std::string welcomeText();
    [[nodiscard]] static std::string commandPromptLine(std::string_view command);
    [[nodiscard]] static std::string notImplementedLine();

private:
    std::string output_;
};

class ToolPaletteModel {
public:
    [[nodiscard]] static int columnCount();
    [[nodiscard]] static std::vector<ToolPaletteTool> tools();
};

class ColorPalettesModel {
public:
    [[nodiscard]] static std::vector<std::string> paletteOptions();
    [[nodiscard]] static std::string selectorLabel();
    [[nodiscard]] static std::string placeholderText();
};

class FieldEditorModel {
public:
    [[nodiscard]] static std::vector<DisabledPanelAction> toolbarActions();
    [[nodiscard]] static FieldEditorState emptyState();
    [[nodiscard]] static FieldEditorState missingDataState();
    [[nodiscard]] static FieldEditorState loadedState(std::string_view memberName,
                                                      int memberNumber,
                                                      std::string_view text);
};

} // namespace libreshockwave::editor::panels
