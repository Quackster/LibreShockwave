#include "libreshockwave/editor/debug/DebugInspectionModels.hpp"

#include <iomanip>
#include <sstream>
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

} // namespace libreshockwave::editor::debug
