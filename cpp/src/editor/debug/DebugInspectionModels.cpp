#include "libreshockwave/editor/debug/DebugInspectionModels.hpp"

#include <iomanip>
#include <sstream>
#include <cctype>
#include <utility>

#include "libreshockwave/lingo/vm/datum/DatumFormatter.hpp"

namespace libreshockwave::editor::debug {
namespace {

constexpr DebugSize DATUM_DETAILS_SIZE{500, 400};
constexpr DebugSize DETAILED_STACK_SIZE{500, 600};
constexpr const char* DETAILED_STACK_TITLE = "Detailed Stack View";
constexpr const char* WAITING_STATUS = "Waiting for debugger pause...";
constexpr const char* RUNNING_STATUS = "Running...";
constexpr const char* CURRENT_FRAME_PREFIX = "\xE2\x96\xBA ";
constexpr const char* SEPARATOR_LINE = "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80"
                                       "\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80\xE2\x94\x80";

[[nodiscard]] DebugTextAreaPresentation monospacedTextArea(bool lineWrap = false, bool wrapStyleWord = false) {
    return DebugTextAreaPresentation{"Monospaced", 12, false, lineWrap, wrapStyleWord};
}

[[nodiscard]] std::vector<DetailedStackTabView> detailedStackTabs(std::string callStack,
                                                                  std::string stack,
                                                                  std::string arguments,
                                                                  std::string receiver) {
    return {
        DetailedStackTabView{"Call Stack", monospacedTextArea(), std::move(callStack), false},
        DetailedStackTabView{"VM Stack", monospacedTextArea(), std::move(stack), true},
        DetailedStackTabView{"Arguments", monospacedTextArea(), std::move(arguments), false},
        DetailedStackTabView{"Receiver (me)", monospacedTextArea(true, true), std::move(receiver), false},
    };
}

[[nodiscard]] DetailedStackView detailedStackViewWithStatus(std::string statusText) {
    return DetailedStackView{
        DETAILED_STACK_TITLE,
        DETAILED_STACK_SIZE,
        std::move(statusText),
        detailedStackTabs({}, {}, {}, {}),
    };
}

[[nodiscard]] std::string trimAscii(std::string_view value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

[[nodiscard]] DebugTablePresentation tablePresentation(DebugInspectionTable table,
                                                       std::string title,
                                                       std::vector<std::string> columns,
                                                       std::vector<int> preferredColumnWidths,
                                                       DebugSize preferredScrollSize = {},
                                                       bool doubleClickOpensDatum = false) {
    return DebugTablePresentation{
        table,
        std::move(title),
        std::move(columns),
        std::move(preferredColumnWidths),
        "Monospaced",
        11,
        preferredScrollSize,
        doubleClickOpensDatum,
    };
}

[[nodiscard]] DebugTablePresentation stackTable() {
    return tablePresentation(DebugInspectionTable::Stack, "Stack", {"#", "Type", "Value"}, {40, 80, 200}, {}, true);
}

[[nodiscard]] DebugTablePresentation variablesTable(DebugInspectionTable table, std::string title) {
    return tablePresentation(table, std::move(title), {"Name", "Type", "Value"}, {100, 80, 200}, {}, true);
}

[[nodiscard]] DebugTablePresentation watchesTable() {
    return tablePresentation(DebugInspectionTable::Watches,
                             "Watches",
                             {"Expression", "Type", "Value"},
                             {150, 60, 200},
                             {},
                             false);
}

[[nodiscard]] DebugTablePresentation timeoutsTable() {
    return tablePresentation(DebugInspectionTable::Timeouts,
                             "Timeouts",
                             {"Name", "Period (ms)", "Handler", "Target", "Persistent"},
                             {},
                             DebugSize{0, 120},
                             true);
}

[[nodiscard]] DebugTablePresentation moviePropertiesTable() {
    return tablePresentation(DebugInspectionTable::MovieProperties,
                             "Movie Properties",
                             {"Property", "Value"},
                             {},
                             DebugSize{0, 150},
                             true);
}

} // namespace

DatumDetailsView DebugInspectionModels::datumDetailsView(const lingo::Datum& datum, std::string_view title) {
    std::string body = "Type: " + lingo::vm::datum::getTypeName(datum) + "\n\nValue:\n";
    body += lingo::vm::datum::formatDetailed(datum, 0);
    return DatumDetailsView{
        "Datum Details: " + std::string(title),
        DATUM_DETAILS_SIZE,
        monospacedTextArea(true, true),
        std::move(body),
        "Close",
        false,
    };
}

DetailedStackView DebugInspectionModels::detailedStackInitialView() {
    return detailedStackViewWithStatus(WAITING_STATUS);
}

DetailedStackView DebugInspectionModels::detailedStackRunningView() {
    return detailedStackViewWithStatus(RUNNING_STATUS);
}

DetailedStackView DebugInspectionModels::detailedStackView(const player::debug::DebugSnapshot& snapshot) {
    return DetailedStackView{
        DETAILED_STACK_TITLE,
        DETAILED_STACK_SIZE,
        "Paused at: " + snapshot.handlerName + " (offset " + std::to_string(snapshot.instructionOffset) + ")",
        detailedStackTabs(formatCallStack(snapshot.callStack),
                          formatStackDetailed(snapshot.stack),
                          formatArguments(snapshot.arguments),
                          formatReceiver(snapshot.receiver)),
    };
}

std::string DebugInspectionModels::formatCallStack(const std::vector<player::debug::CallFrame>& callStack) {
    if (callStack.empty()) {
        return "(no call stack)";
    }

    std::string out = "Call Stack (" + std::to_string(callStack.size()) + " frames):\n";
    out += SEPARATOR_LINE;
    out += "\n\n";

    for (int index = static_cast<int>(callStack.size()) - 1; index >= 0; --index) {
        const auto& frame = callStack[static_cast<std::size_t>(index)];
        const int depth = static_cast<int>(callStack.size()) - 1 - index;
        out += depth == 0 ? CURRENT_FRAME_PREFIX : "  ";
        out += "[" + std::to_string(depth) + "] " + frame.handlerName + "(";
        for (std::size_t argIndex = 0; argIndex < frame.arguments.size(); ++argIndex) {
            if (argIndex > 0) {
                out += ", ";
            }
            out += lingo::vm::datum::formatBrief(frame.arguments[argIndex]);
        }
        out += ")\n";
        out += "     in: " + frame.scriptName + "\n";
        if (frame.receiver.has_value()) {
            out += "     me: " + lingo::vm::datum::formatBrief(*frame.receiver) + "\n";
        }
        out += "\n";
    }
    return out;
}

std::string DebugInspectionModels::formatStackDetailed(const std::vector<lingo::Datum>& stack) {
    if (stack.empty()) {
        return "(empty stack)";
    }

    std::ostringstream out;
    for (std::size_t index = 0; index < stack.size(); ++index) {
        out << '[' << std::setw(3) << index << "] " << lingo::vm::datum::formatDetailed(stack[index], 0) << '\n';
    }
    return out.str();
}

std::string DebugInspectionModels::formatArguments(const std::vector<lingo::Datum>& arguments) {
    if (arguments.empty()) {
        return "(no arguments)";
    }

    std::string out;
    for (std::size_t index = 0; index < arguments.size(); ++index) {
        out += "arg" + std::to_string(index + 1) + " = " + lingo::vm::datum::formatDetailed(arguments[index], 0) +
               "\n";
    }
    return out;
}

std::string DebugInspectionModels::formatReceiver(const std::optional<lingo::Datum>& receiver) {
    return receiver.has_value() ? lingo::vm::datum::formatDetailed(*receiver, 0) : "(no receiver)";
}

DebugStateTabsView DebugInspectionModels::stateTabsView() {
    return DebugStateTabsView{
        "top",
        {"Stack", "Locals", "Globals", "Watches", "Objects"},
        4,
        {stackTable(), variablesTable(DebugInspectionTable::Locals, "Locals"),
         variablesTable(DebugInspectionTable::Globals, "Globals"), watchesTable()},
        {DebugObjectSectionView{"Timeouts", timeoutsTable()},
         DebugObjectSectionView{"Globals", variablesTable(DebugInspectionTable::Globals, "Globals")},
         DebugObjectSectionView{"Movie Properties", moviePropertiesTable()}},
    };
}

bool DebugInspectionModels::isObjectsTabSelected(int selectedTabIndex) {
    return selectedTabIndex == stateTabsView().objectsTabIndex;
}

WatchPanelView DebugInspectionModels::watchesPanelView(bool hasSelection) {
    return WatchPanelView{
        watchesTable(),
        {
            WatchPanelButton{WatchPanelAction::Add, "+", "Add watch expression", true},
            WatchPanelButton{WatchPanelAction::Remove, "-", "Remove selected watch", hasSelection},
            WatchPanelButton{WatchPanelAction::Clear, "Clear", "Clear all watches", true},
        },
        "Add Watch",
        "Enter watch expression:",
        "Edit watch expression:",
    };
}

std::optional<std::string> DebugInspectionModels::sanitizedWatchExpression(std::string_view expression) {
    auto trimmed = trimAscii(expression);
    if (trimmed.empty()) {
        return std::nullopt;
    }
    return trimmed;
}

std::optional<std::string> DebugInspectionModels::selectedWatchId(
    const std::vector<player::debug::WatchExpression>& watches,
    int selectedRow) {
    if (selectedRow < 0 || selectedRow >= static_cast<int>(watches.size())) {
        return std::nullopt;
    }
    return watches[static_cast<std::size_t>(selectedRow)].id;
}

std::optional<std::string> DebugInspectionModels::datumDetailsTitleFor(DebugInspectionTable table,
                                                                       std::string_view name,
                                                                       int row) {
    switch (table) {
        case DebugInspectionTable::Stack:
            return "Stack[" + std::to_string(row) + "]";
        case DebugInspectionTable::Locals:
            return "Local: " + std::string(name);
        case DebugInspectionTable::Globals:
            return "Global: " + std::string(name);
        case DebugInspectionTable::Timeouts:
            return "Timeout: " + std::string(name);
        case DebugInspectionTable::MovieProperties:
            return "Movie: " + std::string(name);
        case DebugInspectionTable::Watches:
            return std::nullopt;
    }
    return std::nullopt;
}

} // namespace libreshockwave::editor::debug
