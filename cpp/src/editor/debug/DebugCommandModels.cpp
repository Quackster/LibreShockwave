#include "libreshockwave/editor/debug/DebugCommandModels.hpp"

#include <array>
#include <utility>

namespace libreshockwave::editor::debug {
namespace {

[[nodiscard]] DebugToolbarButton button(DebugCommand command,
                                        std::string label,
                                        std::string tooltip,
                                        bool enabled,
                                        int gapBeforePx = 0) {
    return DebugToolbarButton{command, std::move(label), std::move(tooltip), enabled, gapBeforePx};
}

[[nodiscard]] DebugShortcut shortcut(DebugCommand command,
                                     std::string actionName,
                                     std::string key,
                                     bool shift = false) {
    return DebugShortcut{command, std::move(actionName), std::move(key), shift, false, false, false};
}

[[nodiscard]] bool hasBreakpointContext(const std::optional<InstructionDisplayItem>& selectedItem,
                                        bool controllerAvailable,
                                        int currentScriptId,
                                        std::string_view currentHandlerName) {
    return selectedItem.has_value() && controllerAvailable && currentScriptId >= 0 && !currentHandlerName.empty();
}

[[nodiscard]] BytecodeContextMenuItem contextItem(DebugCommand command,
                                                  std::string label,
                                                  bool visible,
                                                  bool enabled,
                                                  std::optional<int> offset,
                                                  std::optional<std::string> targetHandlerName = std::nullopt,
                                                  bool separatorBefore = false) {
    return BytecodeContextMenuItem{
        command,
        std::move(label),
        visible,
        enabled,
        separatorBefore,
        offset,
        std::move(targetHandlerName),
    };
}

} // namespace

std::string DebugCommandModels::commandId(DebugCommand command) {
    switch (command) {
        case DebugCommand::StepInto:
            return "debug.stepInto";
        case DebugCommand::StepOver:
            return "debug.stepOver";
        case DebugCommand::StepOut:
            return "debug.stepOut";
        case DebugCommand::ContinueExecution:
            return "debug.continue";
        case DebugCommand::Pause:
            return "debug.pause";
        case DebugCommand::ClearBreakpoints:
            return "debug.clearBreakpoints";
        case DebugCommand::ToggleBreakpoint:
            return "debug.toggleBreakpoint";
        case DebugCommand::ToggleBreakpointEnabled:
            return "debug.toggleBreakpointEnabled";
        case DebugCommand::GoToDefinition:
            return "debug.goToDefinition";
        case DebugCommand::ViewHandlerDetails:
            return "debug.viewHandlerDetails";
    }
    return {};
}

std::optional<DebugCommand> DebugCommandModels::commandForActionName(std::string_view actionName) {
    constexpr std::array commands{DebugCommand::StepInto,
                                  DebugCommand::StepOver,
                                  DebugCommand::StepOut,
                                  DebugCommand::ContinueExecution,
                                  DebugCommand::Pause,
                                  DebugCommand::ClearBreakpoints,
                                  DebugCommand::ToggleBreakpoint,
                                  DebugCommand::ToggleBreakpointEnabled,
                                  DebugCommand::GoToDefinition,
                                  DebugCommand::ViewHandlerDetails};
    for (const auto command : commands) {
        if (commandId(command) == actionName) {
            return command;
        }
    }
    return std::nullopt;
}

DebugToolbarLayout DebugCommandModels::toolbarLayout() {
    return DebugToolbarLayout{"left", 5, 2, 2, 5, 2, 5};
}

std::vector<DebugToolbarButton> DebugCommandModels::toolbarButtons(bool stepButtonsEnabled) {
    return {
        button(DebugCommand::StepInto, "Step Into", "Step Into (F11)", stepButtonsEnabled),
        button(DebugCommand::StepOver, "Step Over", "Step Over (F10)", stepButtonsEnabled),
        button(DebugCommand::StepOut, "Step Out", "Step Out (Shift+F11)", stepButtonsEnabled),
        button(DebugCommand::ContinueExecution, "Continue", "Continue (F5)", stepButtonsEnabled, 10),
        button(DebugCommand::ClearBreakpoints, "Clear BPs", "Clear all breakpoints", true, 20),
    };
}

std::vector<DebugShortcut> DebugCommandModels::keyboardShortcuts() {
    return {
        shortcut(DebugCommand::ContinueExecution, "debug.continue", "F5"),
        shortcut(DebugCommand::Pause, "debug.pause", "F6"),
        shortcut(DebugCommand::StepOver, "debug.stepOver", "F10"),
        shortcut(DebugCommand::StepInto, "debug.stepInto", "F11"),
        shortcut(DebugCommand::StepOut, "debug.stepOut", "F11", true),
    };
}

bool DebugCommandModels::canExecute(DebugCommand command,
                                    bool controllerAvailable,
                                    bool paused,
                                    bool breakpointPresent) {
    if (!controllerAvailable) {
        return false;
    }

    switch (command) {
        case DebugCommand::ContinueExecution:
        case DebugCommand::StepInto:
        case DebugCommand::StepOver:
        case DebugCommand::StepOut:
            return paused;
        case DebugCommand::Pause:
            return !paused;
        case DebugCommand::ToggleBreakpointEnabled:
            return breakpointPresent;
        case DebugCommand::ClearBreakpoints:
        case DebugCommand::ToggleBreakpoint:
            return true;
        case DebugCommand::GoToDefinition:
        case DebugCommand::ViewHandlerDetails:
            return true;
    }
    return false;
}

std::vector<BytecodeContextMenuItem> DebugCommandModels::bytecodeContextMenu(
    const std::optional<InstructionDisplayItem>& selectedItem,
    bool controllerAvailable,
    int currentScriptId,
    std::string_view currentHandlerName,
    bool breakpointPresent) {
    const auto breakpointContext =
        hasBreakpointContext(selectedItem, controllerAvailable, currentScriptId, currentHandlerName);
    const auto offset = selectedItem.has_value() ? std::optional<int>(selectedItem->offset) : std::nullopt;
    const auto targetName = selectedItem.has_value() ? selectedItem->getCallTargetName() : std::nullopt;
    const bool navigable = selectedItem.has_value() && selectedItem->isNavigableCall() && targetName.has_value();

    return {
        contextItem(DebugCommand::ToggleBreakpoint, "Toggle Breakpoint", true, breakpointContext, offset),
        contextItem(DebugCommand::ToggleBreakpointEnabled,
                    "Enable/Disable Breakpoint",
                    true,
                    breakpointContext && breakpointPresent,
                    offset),
        contextItem(DebugCommand::GoToDefinition,
                    "Go to Definition",
                    navigable,
                    navigable,
                    offset,
                    targetName,
                    true),
        contextItem(DebugCommand::ViewHandlerDetails,
                    "View Handler Details...",
                    navigable,
                    navigable,
                    offset,
                    targetName),
    };
}

} // namespace libreshockwave::editor::debug
