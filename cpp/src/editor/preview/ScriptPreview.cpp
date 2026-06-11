#include "libreshockwave/editor/preview/ScriptPreview.hpp"

#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/editor/format/InstructionFormatter.hpp"
#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"

namespace libreshockwave::editor::preview {
namespace {

std::string resolveName(const chunks::ScriptNamesChunk* names, int nameId) {
    if (names != nullptr) {
        return names->getName(nameId);
    }
    return "#" + std::to_string(nameId);
}

std::string join(const std::vector<std::string>& values, const std::string& separator) {
    std::string result;
    for (const auto& value : values) {
        if (!result.empty()) {
            result += separator;
        }
        result += value;
    }
    return result;
}

std::string hexString(int value) {
    std::ostringstream out;
    out << std::hex << value;
    return out.str();
}

} // namespace

std::string ScriptPreview::format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    const auto script = scanning::MemberResolver::findScriptForMember(dirFile, memberInfo.member);
    const auto names = dirFile.scriptNames();

    std::string out;
    PreviewFormatUtils::appendMemberHeader(out, "SCRIPT", memberInfo, false);

    if (script == nullptr) {
        out += "\n[No bytecode found for this script member]\n";
        return out;
    }

    out += "Script Type: " + ::libreshockwave::format::getScriptTypeName(script->scriptType()) + "\n";
    out += "Behavior Flags: 0x" + hexString(script->behaviorFlags()) + "\n\n";

    if (!script->properties().empty()) {
        out += "--- PROPERTIES ---\n";
        for (const auto& property : script->properties()) {
            out += "  property " + resolveName(names.get(), property.nameId) + "\n";
        }
        out += "\n";
    }

    if (!script->globals().empty()) {
        out += "--- GLOBALS ---\n";
        for (const auto& global : script->globals()) {
            out += "  global " + resolveName(names.get(), global.nameId) + "\n";
        }
        out += "\n";
    }

    out += "--- HANDLERS (" + std::to_string(script->handlers().size()) + ") ---\n\n";
    for (const auto& handler : script->handlers()) {
        formatHandler(out, handler, *script, names.get());
    }

    if (!script->literals().empty()) {
        out += "--- LITERALS (" + std::to_string(script->literals().size()) + ") ---\n";
        int index = 0;
        for (const auto& literal : script->literals()) {
            out += "  [" + std::to_string(index++) + "] " + ::libreshockwave::format::formatLiteral(literal) + "\n";
        }
    }

    return out;
}

void ScriptPreview::formatHandler(std::string& out,
                                  const chunks::ScriptChunk::Handler& handler,
                                  const chunks::ScriptChunk& script,
                                  const chunks::ScriptNamesChunk* names) const {
    std::vector<std::string> argNames;
    argNames.reserve(handler.argNameIds.size());
    for (int nameId : handler.argNameIds) {
        argNames.push_back(resolveName(names, nameId));
    }

    out += "on " + resolveName(names, handler.nameId);
    const auto args = join(argNames, ", ");
    if (!args.empty()) {
        out += " " + args;
    }
    out += "\n";

    if (!handler.localNameIds.empty()) {
        std::vector<std::string> localNames;
        localNames.reserve(handler.localNameIds.size());
        for (int nameId : handler.localNameIds) {
            localNames.push_back(resolveName(names, nameId));
        }
        out += "  -- locals: " + join(localNames, ", ") + "\n";
    }

    out += "  -- bytecode (" + std::to_string(handler.bytecodeLength) + " bytes, " +
           std::to_string(handler.instructions.size()) + " instructions):\n";
    for (const auto& instruction : handler.instructions) {
        out += "    " + ::libreshockwave::editor::format::InstructionFormatter::format(instruction, script, names) + "\n";
    }
    out += "end\n\n";
}

} // namespace libreshockwave::editor::preview
