#include "libreshockwave/editor/debug/DebugInspectionModels.hpp"

#include <cstdint>
#include <iomanip>
#include <sstream>
#include <cctype>
#include <utility>
#include <variant>

#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lingo/vm/trace/InstructionAnnotator.hpp"
#include "libreshockwave/lingo/vm/datum/DatumFormatter.hpp"
#include "libreshockwave/util/StringUtils.hpp"

namespace libreshockwave::editor::debug {
namespace {

constexpr DebugSize DATUM_DETAILS_SIZE{500, 400};
constexpr DebugSize DETAILED_STACK_SIZE{500, 600};
constexpr DebugSize HANDLER_DETAILS_SIZE{600, 500};
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

[[nodiscard]] DebugTablePresentation objectGlobalsTable() {
    return tablePresentation(DebugInspectionTable::Globals,
                             "Globals",
                             {"Name", "Type", "Value"},
                             {100, 80, 200},
                             DebugSize{0, 150},
                             true);
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

[[nodiscard]] std::vector<DebugObjectSectionView> objectSections() {
    return {
        DebugObjectSectionView{"Timeouts", timeoutsTable()},
        DebugObjectSectionView{"Globals", objectGlobalsTable()},
        DebugObjectSectionView{"Movie Properties", moviePropertiesTable()},
    };
}

[[nodiscard]] const char* handlerDetailsScriptTypeName(chunks::ScriptChunkType scriptType) {
    switch (scriptType) {
        case chunks::ScriptChunkType::Score:
            return "SCORE";
        case chunks::ScriptChunkType::Behavior:
            return "BEHAVIOR";
        case chunks::ScriptChunkType::MovieScript:
            return "MOVIE_SCRIPT";
        case chunks::ScriptChunkType::Parent:
            return "PARENT";
        case chunks::ScriptChunkType::Unknown:
            return "UNKNOWN";
    }
    return "UNKNOWN";
}

[[nodiscard]] std::string handlerDetailsScriptDisplayName(const chunks::ScriptChunk& script,
                                                          std::string_view overrideName) {
    if (!overrideName.empty()) {
        return std::string(overrideName);
    }
    return std::string(handlerDetailsScriptTypeName(script.scriptType())) + " #" + std::to_string(script.id().value());
}

[[nodiscard]] std::string literalTypeNameForDetails(int type) {
    switch (type) {
        case 1:
            return "String";
        case 4:
            return "Int";
        case 9:
            return "Float";
        default:
            return "Type " + std::to_string(type);
    }
}

[[nodiscard]] std::string escapedLiteralString(std::string_view value) {
    std::string result;
    for (const char ch : value) {
        switch (ch) {
            case '\\':
                result += "\\\\";
                break;
            case '"':
                result += "\\\"";
                break;
            case '\n':
                result += "\\n";
                break;
            case '\r':
                result += "\\r";
                break;
            default:
                result.push_back(ch);
                break;
        }
    }
    return result;
}

[[nodiscard]] std::string literalValueForDetails(const chunks::ScriptChunk::LiteralEntry& literal) {
    if (const auto* value = std::get_if<std::string>(&literal.value)) {
        return "\"" + escapedLiteralString(*value) + "\"";
    }
    if (const auto* value = std::get_if<int>(&literal.value)) {
        return std::to_string(*value);
    }
    if (const auto* bytes = std::get_if<std::vector<std::uint8_t>>(&literal.value)) {
        if (bytes->size() <= 20) {
            std::ostringstream out;
            out << "bytes[";
            for (std::size_t index = 0; index < bytes->size(); ++index) {
                if (index > 0) {
                    out << ' ';
                }
                out << std::uppercase << std::hex << std::setw(2) << std::setfill('0')
                    << static_cast<int>((*bytes)[index] & 0xFF);
            }
            out << ']';
            return out.str();
        }
        return "bytes[" + std::to_string(bytes->size()) + "]";
    }
    return "(null)";
}

[[nodiscard]] std::string bytecodeDisplayText(const chunks::ScriptChunk::Instruction& instruction,
                                              std::string_view opcode,
                                              const std::optional<int>& argument,
                                              std::string_view annotation) {
    std::ostringstream out;
    out << '[' << std::setw(3) << instruction.offset << "] " << std::left << std::setw(14) << opcode;
    if (argument.has_value()) {
        out << ' ' << std::left << std::setw(4) << *argument;
    } else {
        out << "     ";
    }
    if (!annotation.empty()) {
        out << ' ' << annotation;
    }
    return out.str();
}

[[nodiscard]] std::vector<HandlerDetailsBytecodeRow> handlerDetailsBytecodeRows(
    const chunks::ScriptChunk& script,
    const chunks::ScriptChunk::Handler& handler,
    const chunks::ScriptNamesChunk* names) {
    std::vector<HandlerDetailsBytecodeRow> rows;
    rows.reserve(handler.instructions.size());
    for (const auto& instruction : handler.instructions) {
        std::optional<int> argument;
        if (instruction.argument != 0 || instruction.rawOpcode >= 0x40) {
            argument = instruction.argument;
        }
        const std::string opcode(lingo::mnemonic(instruction.opcode));
        const std::string annotation =
            lingo::vm::trace::InstructionAnnotator::annotate(script, &handler, instruction, names, true);
        rows.push_back(HandlerDetailsBytecodeRow{
            instruction.offset,
            opcode,
            argument,
            annotation,
            bytecodeDisplayText(instruction, opcode, argument, annotation),
        });
    }
    return rows;
}

[[nodiscard]] std::vector<HandlerDetailsLiteralRow> handlerDetailsLiteralRows(
    const std::vector<chunks::ScriptChunk::LiteralEntry>& literals) {
    std::vector<HandlerDetailsLiteralRow> rows;
    rows.reserve(literals.size());
    for (std::size_t index = 0; index < literals.size(); ++index) {
        rows.push_back(HandlerDetailsLiteralRow{
            static_cast<int>(index),
            literalTypeNameForDetails(literals[index].type),
            literalValueForDetails(literals[index]),
        });
    }
    return rows;
}

[[nodiscard]] std::vector<HandlerDetailsNameRow> handlerDetailsNameRows(const std::vector<std::string>& names) {
    std::vector<HandlerDetailsNameRow> rows;
    rows.reserve(names.size());
    for (std::size_t index = 0; index < names.size(); ++index) {
        rows.push_back(HandlerDetailsNameRow{
            static_cast<int>(index),
            names[index],
            "[" + std::to_string(index) + "] " + names[index],
        });
    }
    return rows;
}

[[nodiscard]] std::string handlerDetailsOverviewHtml(const chunks::ScriptChunk& script,
                                                     const chunks::ScriptChunk::Handler& handler,
                                                     const chunks::ScriptNamesChunk* names,
                                                     std::string_view scriptDisplayName) {
    const std::string handlerName = script.getHandlerName(handler, names);
    std::string out;
    out += "<html><body style='font-family: monospace; font-size: 11px;'>";
    out += "<h3>" + util::escapeHtml(handlerName) + "</h3>";
    out += "<b>Script:</b> " + util::escapeHtml(handlerDetailsScriptDisplayName(script, scriptDisplayName)) + "<br>";
    out += "<b>Script Type:</b> ";
    out += handlerDetailsScriptTypeName(script.scriptType());
    out += "<br>";
    out += "<b>Script ID:</b> " + std::to_string(script.id().value()) + "<br><br>";
    out += "<b>Bytecode Length:</b> " + std::to_string(handler.bytecodeLength) + " bytes<br>";
    out += "<b>Instruction Count:</b> " + std::to_string(handler.instructions.size()) + "<br><br>";

    out += "<b>Arguments (" + std::to_string(handler.argCount) + "):</b><br>";
    if (handler.argCount > 0) {
        out += "<ul>";
        for (const int nameId : handler.argNameIds) {
            out += "<li>" + util::escapeHtml(script.resolveName(nameId, names)) + "</li>";
        }
        out += "</ul>";
    } else {
        out += "&nbsp;&nbsp;(none)<br>";
    }

    out += "<b>Local Variables (" + std::to_string(handler.localCount) + "):</b><br>";
    if (handler.localCount > 0) {
        out += "<ul>";
        for (const int nameId : handler.localNameIds) {
            out += "<li>" + util::escapeHtml(script.resolveName(nameId, names)) + "</li>";
        }
        out += "</ul>";
    } else {
        out += "&nbsp;&nbsp;(none)<br>";
    }

    out += "<b>Globals Used:</b> " + std::to_string(handler.globalsCount) + "<br>";
    out += "</body></html>";
    return out;
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
        objectSections(),
    };
}

DebugObjectsPanelView DebugInspectionModels::objectsPanelView() {
    return DebugObjectsPanelView{"border", "vertical", true, objectSections()};
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
        true,
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

std::optional<WatchEditRequest> DebugInspectionModels::selectedWatchEditRequest(
    const std::vector<player::debug::WatchExpression>& watches,
    int selectedRow) {
    if (selectedRow < 0 || selectedRow >= static_cast<int>(watches.size())) {
        return std::nullopt;
    }

    const auto& watch = watches[static_cast<std::size_t>(selectedRow)];
    return WatchEditRequest{watch.id, watch.expression, watchesPanelView(true).editDialogPrompt};
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

HandlerDetailsView DebugInspectionModels::handlerDetailsView(const chunks::ScriptChunk& script,
                                                             const chunks::ScriptChunk::Handler& handler,
                                                             const chunks::ScriptNamesChunk* names,
                                                             std::string_view scriptDisplayName) {
    std::vector<HandlerDetailsTab> tabs{HandlerDetailsTab::Overview, HandlerDetailsTab::Bytecode};
    std::vector<std::string> tabTitles{"Overview", "Bytecode"};
    if (!script.literals().empty()) {
        tabs.push_back(HandlerDetailsTab::Literals);
        tabTitles.push_back("Literals");
    }
    if (!script.properties().empty()) {
        tabs.push_back(HandlerDetailsTab::Properties);
        tabTitles.push_back("Properties");
    }
    if (!script.globals().empty()) {
        tabs.push_back(HandlerDetailsTab::Globals);
        tabTitles.push_back("Globals");
    }

    return HandlerDetailsView{
        "Handler: " + script.getHandlerName(handler, names),
        HANDLER_DETAILS_SIZE,
        std::move(tabs),
        std::move(tabTitles),
        handlerDetailsOverviewHtml(script, handler, names, scriptDisplayName),
        handlerDetailsBytecodeRows(script, handler, names),
        handlerDetailsLiteralRows(script.literals()),
        handlerDetailsNameRows(script.getPropertyNames(names)),
        handlerDetailsNameRows(script.getGlobalNames(names)),
        "Close",
        false,
    };
}

std::optional<HandlerDetailsView> DebugInspectionModels::findHandlerDetailsView(
    const std::vector<std::shared_ptr<chunks::ScriptChunk>>& scripts,
    const chunks::ScriptNamesChunk* names,
    std::string_view handlerName) {
    for (const auto& script : scripts) {
        if (script == nullptr) {
            continue;
        }
        auto handler = script->findHandler(handlerName, names);
        if (handler.has_value()) {
            return handlerDetailsView(*script, *handler, names);
        }
    }
    return std::nullopt;
}

} // namespace libreshockwave::editor::debug
