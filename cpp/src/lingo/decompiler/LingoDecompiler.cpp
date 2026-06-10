#include "libreshockwave/lingo/decompiler/LingoDecompiler.hpp"

#include <iomanip>
#include <sstream>
#include <string_view>

#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"
#include "libreshockwave/lingo/Opcode.hpp"

namespace libreshockwave::lingo::decompiler {
namespace {

std::string indent(int level) {
    return std::string(static_cast<std::size_t>(level) * 2U, ' ');
}

std::string joinStrings(const std::vector<std::string>& values, std::string_view separator) {
    std::string result;
    for (std::size_t index = 0; index < values.size(); ++index) {
        if (index > 0) {
            result.append(separator);
        }
        result.append(values[index]);
    }
    return result;
}

void emitBlock(const BlockNode& block,
               std::vector<LingoDecompiler::DecompiledLine>& lines,
               int indentLevel,
               bool dotSyntax);

void emitCaseNode(const CaseNode& caseNode,
                  std::vector<LingoDecompiler::DecompiledLine>& lines,
                  int indentLevel,
                  bool dotSyntax) {
    const auto prefix = indent(indentLevel);
    std::string caseLine = caseNode.value().toLingo(dotSyntax);
    const auto* orCase = caseNode.nextOr();
    while (orCase != nullptr) {
        caseLine.append(", ");
        caseLine.append(orCase->value().toLingo(dotSyntax));
        orCase = orCase->nextOr();
    }
    caseLine.push_back(':');
    lines.push_back(LingoDecompiler::DecompiledLine{prefix + caseLine, caseNode.bytecodeOffset()});

    if (caseNode.block() != nullptr) {
        emitBlock(*caseNode.block(), lines, indentLevel + 1, dotSyntax);
    }
    if (caseNode.nextCase() != nullptr) {
        emitCaseNode(*caseNode.nextCase(), lines, indentLevel, dotSyntax);
    }
    if (caseNode.otherwise() != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "otherwise:", -1});
        emitBlock(*caseNode.otherwise(), lines, indentLevel + 1, dotSyntax);
    }
}

void emitSimpleLines(const LingoNode& node,
                     std::vector<LingoDecompiler::DecompiledLine>& lines,
                     std::string_view prefix,
                     bool dotSyntax) {
    auto text = node.toLingo(dotSyntax);
    int offset = node.bytecodeOffset();
    std::size_t start = 0;
    while (start <= text.size()) {
        const auto end = text.find('\n', start);
        const auto lineEnd = end == std::string::npos ? text.size() : end;
        lines.push_back(LingoDecompiler::DecompiledLine{
            std::string(prefix) + text.substr(start, lineEnd - start),
            offset
        });
        offset = -1;
        if (end == std::string::npos) {
            break;
        }
        start = end + 1;
    }
}

void emitNode(const LingoNode& node,
              std::vector<LingoDecompiler::DecompiledLine>& lines,
              int indentLevel,
              bool dotSyntax) {
    const auto prefix = indent(indentLevel);
    const int offset = node.bytecodeOffset();

    if (const auto* ifStmt = dynamic_cast<const IfStmtNode*>(&node); ifStmt != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "if " + ifStmt->condition().toLingo(dotSyntax) + " then",
            offset
        });
        emitBlock(ifStmt->trueBlock(), lines, indentLevel + 1, dotSyntax);
        if (ifStmt->hasElse()) {
            lines.push_back(LingoDecompiler::DecompiledLine{prefix + "else", -1});
            emitBlock(ifStmt->falseBlock(), lines, indentLevel + 1, dotSyntax);
        }
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end if", -1});
    } else if (const auto* repeatWhile = dynamic_cast<const RepeatWhileStmtNode*>(&node);
               repeatWhile != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "repeat while " + repeatWhile->condition().toLingo(dotSyntax),
            offset
        });
        emitBlock(repeatWhile->block(), lines, indentLevel + 1, dotSyntax);
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end repeat", -1});
    } else if (const auto* repeatIn = dynamic_cast<const RepeatWithInStmtNode*>(&node);
               repeatIn != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "repeat with " + repeatIn->variableName() + " in " + repeatIn->list().toLingo(dotSyntax),
            offset
        });
        emitBlock(repeatIn->block(), lines, indentLevel + 1, dotSyntax);
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end repeat", -1});
    } else if (const auto* repeatTo = dynamic_cast<const RepeatWithToStmtNode*>(&node);
               repeatTo != nullptr) {
        const std::string direction = repeatTo->up() ? " to " : " down to ";
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "repeat with " + repeatTo->variableName() + " = " +
                repeatTo->start().toLingo(dotSyntax) + direction + repeatTo->end().toLingo(dotSyntax),
            offset
        });
        emitBlock(repeatTo->block(), lines, indentLevel + 1, dotSyntax);
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end repeat", -1});
    } else if (const auto* tell = dynamic_cast<const TellStmtNode*>(&node); tell != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "tell " + tell->window().toLingo(dotSyntax),
            offset
        });
        emitBlock(tell->block(), lines, indentLevel + 1, dotSyntax);
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end tell", -1});
    } else if (const auto* cases = dynamic_cast<const CasesStmtNode*>(&node); cases != nullptr) {
        lines.push_back(LingoDecompiler::DecompiledLine{
            prefix + "case " + cases->value().toLingo(dotSyntax) + " of",
            offset
        });
        if (cases->firstCase() != nullptr) {
            emitCaseNode(*cases->firstCase(), lines, indentLevel + 1, dotSyntax);
        }
        lines.push_back(LingoDecompiler::DecompiledLine{prefix + "end case", -1});
    } else {
        emitSimpleLines(node, lines, prefix, dotSyntax);
    }
}

void emitBlock(const BlockNode& block,
               std::vector<LingoDecompiler::DecompiledLine>& lines,
               int indentLevel,
               bool dotSyntax) {
    for (const auto& child : block.children()) {
        if (child) {
            emitNode(*child, lines, indentLevel, dotSyntax);
        }
    }
}

} // namespace

std::string LingoDecompiler::DecompiledHandler::toText() const {
    std::string result;
    for (const auto& line : lines) {
        result.append(line.text);
        result.push_back('\n');
    }
    return result;
}

std::string LingoDecompiler::decompile(const chunks::ScriptChunk& script,
                                       const chunks::ScriptNamesChunk* names) {
    script_ = &script;
    names_ = names;

    std::string result = "-- " + format::getScriptTypeName(script.scriptType()) + "\n\n";

    for (const auto& property : script.properties()) {
        result.append("property ");
        result.append(resolveName(property.nameId));
        result.push_back('\n');
    }
    if (!script.properties().empty()) {
        result.push_back('\n');
    }

    for (const auto& global : script.globals()) {
        result.append("global ");
        result.append(resolveName(global.nameId));
        result.push_back('\n');
    }
    if (!script.globals().empty()) {
        result.push_back('\n');
    }

    for (const auto& handler : script.handlers()) {
        result.append(decompileHandler(handler, script, names));
        result.push_back('\n');
    }

    return result;
}

std::string LingoDecompiler::decompileHandler(const chunks::ScriptChunk::Handler& handler,
                                              const chunks::ScriptChunk& script,
                                              const chunks::ScriptNamesChunk* names) {
    script_ = &script;
    names_ = names;
    return formatHandlerBytecodeOnly(handler, names);
}

LingoDecompiler::DecompiledHandler LingoDecompiler::decompileHandlerWithMapping(
    const chunks::ScriptChunk::Handler& handler,
    const chunks::ScriptChunk& script,
    const chunks::ScriptNamesChunk* names) {
    script_ = &script;
    names_ = names;

    DecompiledHandler result;
    result.lines.push_back(DecompiledLine{"on " + resolveName(handler.nameId), -1});
    for (const auto& instruction : handler.instructions) {
        result.lines.push_back(DecompiledLine{"  " + instruction.toString(), instruction.offset});
    }
    result.lines.push_back(DecompiledLine{"end", -1});
    return result;
}

std::string LingoDecompiler::formatHandlerBytecodeOnly(const chunks::ScriptChunk::Handler& handler,
                                                       const chunks::ScriptNamesChunk* names) const {
    const auto resolve = [names](int nameId) {
        if (names != nullptr && nameId >= 0 && nameId < static_cast<int>(names->names().size())) {
            return names->getName(nameId);
        }
        return "#" + std::to_string(nameId);
    };

    std::ostringstream out;
    out << "on " << resolve(handler.nameId) << "\n";
    for (const auto& instruction : handler.instructions) {
        out << "  [" << std::setfill('0') << std::setw(4) << instruction.offset << std::setfill(' ') << "] ";
        out << std::left << std::setw(16) << std::string(lingo::mnemonic(instruction.opcode)) << std::right;
        if (instruction.rawOpcode >= 0x40) {
            out << " " << instruction.argument;
        }
        out << "\n";
    }
    out << "end\n";
    return out.str();
}

LingoDecompiler::DecompiledHandler LingoDecompiler::buildLineMapping(const HandlerNode& handler, bool dotSyntax) {
    DecompiledHandler result;
    std::string signature = "on " + handler.handlerName();
    if (!handler.argumentNames().empty()) {
        signature.push_back(' ');
        signature.append(joinStrings(handler.argumentNames(), ", "));
    }
    result.lines.push_back(DecompiledLine{signature, -1});

    if (!handler.globalNames().empty()) {
        result.lines.push_back(DecompiledLine{"  global " + joinStrings(handler.globalNames(), ", "), -1});
    }

    emitBlock(handler.block(), result.lines, 1, dotSyntax);
    result.lines.push_back(DecompiledLine{"end", -1});
    return result;
}

std::string LingoDecompiler::resolveName(int nameId) const {
    if (names_ != nullptr && nameId >= 0 && nameId < static_cast<int>(names_->names().size())) {
        return names_->getName(nameId);
    }
    return "#" + std::to_string(nameId);
}

} // namespace libreshockwave::lingo::decompiler
