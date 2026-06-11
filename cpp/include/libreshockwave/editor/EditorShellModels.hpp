#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor {

enum class EditorCommand {
    None,
    NewMovie,
    NewCast,
    Open,
    Close,
    Save,
    SaveAs,
    SaveAll,
    Import,
    Export,
    Exit,
    PreferencesGeneral,
    PreferencesNetwork,
    PreferencesScript,
    PreferencesSprite,
    PreferencesPaint,
    Undo,
    Redo,
    Cut,
    Copy,
    Paste,
    Clear,
    SelectAll,
    Find,
    FindAgain,
    Replace,
    FindSelection,
    EditSpriteFrames,
    EditEntireSprite,
    ExchangeCastMembers,
    ExternalParameters,
    Play,
    Stop,
    Rewind,
    StepForward,
    StepBackward,
    LoopPlayback,
    DebugStepInto,
    DebugStepOver,
    DebugStepOut,
    DebugContinue,
    ToggleBreakpoint,
    ClearAllBreakpoints,
    DetailedStackWindow,
    TraceHandler,
    ResetLayout,
    About
};

struct EditorAccelerator {
    std::string key;
    bool ctrl{false};
    bool alt{false};
    bool shift{false};

    friend bool operator==(const EditorAccelerator&, const EditorAccelerator&) = default;
};

struct EditorMenuItem {
    enum class Kind {
        Command,
        Check,
        Separator,
        Submenu
    };

    Kind kind{Kind::Command};
    std::string label;
    EditorCommand command{EditorCommand::None};
    std::optional<EditorAccelerator> accelerator;
    bool enabled{true};
    bool checked{false};
    std::string panelId;
    std::vector<EditorMenuItem> children;

    friend bool operator==(const EditorMenuItem&, const EditorMenuItem&) = default;
};

struct EditorMenu {
    std::string label;
    char mnemonic{'\0'};
    std::vector<EditorMenuItem> items;

    friend bool operator==(const EditorMenu&, const EditorMenu&) = default;
};

struct ToolbarItem {
    enum class Kind {
        Button,
        Separator,
        Label
    };

    Kind kind{Kind::Button};
    std::string label;
    std::string tooltip;
    EditorCommand command{EditorCommand::None};

    friend bool operator==(const ToolbarItem&, const ToolbarItem&) = default;
};

class EditorMenuModel {
public:
    EditorMenuModel();

    [[nodiscard]] const std::vector<EditorMenu>& menus() const;
    [[nodiscard]] const EditorMenu* findMenu(std::string_view label) const;
    [[nodiscard]] const EditorMenuItem* findCommand(EditorCommand command) const;
    [[nodiscard]] std::vector<EditorMenuItem> windowItems(const std::vector<std::pair<std::string, bool>>& panelVisibility) const;
    [[nodiscard]] static std::vector<std::string> traceHandlersFromInput(std::string_view input);

private:
    [[nodiscard]] static const EditorMenuItem* findCommandInItems(const std::vector<EditorMenuItem>& items,
                                                                  EditorCommand command);
    void buildMenus();

    std::vector<EditorMenu> menus_;
};

class EditorToolBarModel {
public:
    EditorToolBarModel();

    [[nodiscard]] const std::vector<ToolbarItem>& items() const;
    [[nodiscard]] static std::string frameLabel(int currentFrame, std::optional<int> frameCount = std::nullopt);

private:
    std::vector<ToolbarItem> items_;
};

[[nodiscard]] std::string_view commandName(EditorCommand command);

} // namespace libreshockwave::editor
