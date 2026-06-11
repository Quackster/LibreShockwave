#include "libreshockwave/editor/debug/DebugDisplayItems.hpp"

#include <cctype>
#include <iomanip>
#include <sstream>
#include <utility>

#include "libreshockwave/chunks/ScriptNamesChunk.hpp"

namespace libreshockwave::editor::debug {
namespace {

[[nodiscard]] std::string toLower(std::string_view value) {
    std::string lowered;
    lowered.reserve(value.size());
    for (char ch : value) {
        lowered.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(ch))));
    }
    return lowered;
}

[[nodiscard]] bool matchesCaseInsensitive(std::string_view value, std::string_view filter) {
    if (filter.empty()) {
        return true;
    }
    return toLower(value).find(toLower(filter)) != std::string::npos;
}

[[nodiscard]] bool endsWith(std::string_view value, std::string_view suffix) {
    return value.size() >= suffix.size() &&
           value.substr(value.size() - suffix.size()) == suffix;
}

} // namespace

HandlerItem::HandlerItem(std::shared_ptr<chunks::ScriptChunk> scriptValue,
                         chunks::ScriptChunk::Handler handlerValue,
                         std::string displayNameValue)
    : script(std::move(scriptValue)),
      handler(std::move(handlerValue)),
      displayName(std::move(displayNameValue)) {}

HandlerItem HandlerItem::fromScript(std::shared_ptr<chunks::ScriptChunk> script,
                                    chunks::ScriptChunk::Handler handler,
                                    const chunks::ScriptNamesChunk* names) {
    const auto resolvedName = script ? script->getHandlerName(handler, names) : std::string{};
    return HandlerItem(std::move(script), std::move(handler), resolvedName);
}

std::string HandlerItem::toString() const {
    return displayName;
}

bool HandlerItem::matchesFilter(std::string_view filter) const {
    return matchesCaseInsensitive(displayName, filter);
}

ScriptItem::ScriptItem(std::shared_ptr<chunks::ScriptChunk> scriptValue,
                       std::string displayNameValue,
                       std::string sourceNameValue,
                       int loadOrderValue)
    : script(std::move(scriptValue)),
      displayName(std::move(displayNameValue)),
      sourceName(std::move(sourceNameValue)),
      loadOrder(loadOrderValue) {}

std::string ScriptItem::toString() const {
    if (!sourceName.empty()) {
        return displayName + " (" + sourceName + ")";
    }
    return displayName;
}

bool ScriptItem::matchesFilter(std::string_view filter) const {
    if (filter.empty()) {
        return true;
    }
    return matchesCaseInsensitive(displayName, filter) || matchesCaseInsensitive(sourceName, filter);
}

InstructionDisplayItem InstructionDisplayItem::fromInstruction(const player::debug::InstructionDisplay& instruction) {
    InstructionDisplayItem item;
    item.offset = instruction.offset;
    item.index = instruction.index;
    item.opcode = instruction.opcode;
    item.argument = instruction.argument;
    item.annotation = instruction.annotation;
    item.hasBreakpoint = instruction.hasBreakpoint;
    return item;
}

bool InstructionDisplayItem::isCallInstruction() const {
    return opcode == "EXT_CALL" || opcode == "extCall" ||
           opcode == "OBJ_CALL" || opcode == "objCall" ||
           opcode == "LOCAL_CALL" || opcode == "localCall";
}

bool InstructionDisplayItem::isNavigableCall() const {
    return isCallInstruction() && navigable && getCallTargetName().has_value();
}

std::optional<std::string> InstructionDisplayItem::getCallTargetName() const {
    if (annotation.empty() || annotation.front() != '<' || !endsWith(annotation, "()>")) {
        return std::nullopt;
    }
    return annotation.substr(1, annotation.size() - 4);
}

std::string InstructionDisplayItem::toString() const {
    std::ostringstream out;
    out << '[' << std::setw(3) << offset << "] " << std::left << std::setw(14) << opcode;
    if (argument != 0) {
        out << ' ' << std::left << std::setw(4) << argument;
    } else {
        out << "     ";
    }
    if (!annotation.empty()) {
        out << ' ' << annotation;
    }
    return out.str();
}

} // namespace libreshockwave::editor::debug
