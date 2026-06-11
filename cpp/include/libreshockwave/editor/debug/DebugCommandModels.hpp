#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/debug/DebugDisplayItems.hpp"

namespace libreshockwave::editor::debug {

enum class DebugCommand {
    StepInto,
    StepOver,
    StepOut,
    ContinueExecution,
    Pause,
    ClearBreakpoints,
    ToggleBreakpoint,
    ToggleBreakpointEnabled,
    GoToDefinition,
    ViewHandlerDetails
};

struct DebugToolbarLayout {
    std::string flowAlignment;
    int horizontalGap{};
    int verticalGap{};
    int borderTop{};
    int borderLeft{};
    int borderBottom{};
    int borderRight{};

    friend bool operator==(const DebugToolbarLayout&, const DebugToolbarLayout&) = default;
};

struct DebugToolbarButton {
    DebugCommand command{DebugCommand::StepInto};
    std::string label;
    std::string tooltip;
    bool enabled{false};
    int gapBeforePx{0};

    friend bool operator==(const DebugToolbarButton&, const DebugToolbarButton&) = default;
};

struct DebugShortcut {
    DebugCommand command{DebugCommand::ContinueExecution};
    std::string actionName;
    std::string key;
    bool shift{false};
    bool ctrl{false};
    bool alt{false};
    bool meta{false};

    friend bool operator==(const DebugShortcut&, const DebugShortcut&) = default;
};

struct BytecodeContextMenuItem {
    DebugCommand command{DebugCommand::ToggleBreakpoint};
    std::string label;
    bool visible{true};
    bool enabled{false};
    bool separatorBefore{false};
    std::optional<int> offset;
    std::optional<std::string> targetHandlerName;

    friend bool operator==(const BytecodeContextMenuItem&, const BytecodeContextMenuItem&) = default;
};

class DebugCommandModels {
public:
    [[nodiscard]] static std::string commandId(DebugCommand command);
    [[nodiscard]] static std::optional<DebugCommand> commandForActionName(std::string_view actionName);

    [[nodiscard]] static DebugToolbarLayout toolbarLayout();
    [[nodiscard]] static std::vector<DebugToolbarButton> toolbarButtons(bool stepButtonsEnabled = false);
    [[nodiscard]] static std::vector<DebugShortcut> keyboardShortcuts();
    [[nodiscard]] static bool canExecute(DebugCommand command,
                                         bool controllerAvailable,
                                         bool paused,
                                         bool breakpointPresent = true);

    [[nodiscard]] static std::vector<BytecodeContextMenuItem> bytecodeContextMenu(
        const std::optional<InstructionDisplayItem>& selectedItem,
        bool controllerAvailable,
        int currentScriptId,
        std::string_view currentHandlerName,
        bool breakpointPresent);
};

} // namespace libreshockwave::editor::debug
