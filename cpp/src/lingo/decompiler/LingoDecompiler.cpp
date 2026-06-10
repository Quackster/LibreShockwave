#include "libreshockwave/lingo/decompiler/LingoDecompiler.hpp"

#include <algorithm>
#include <bit>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string_view>
#include <variant>

#include "libreshockwave/DirectorFile.hpp"
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
    initFileInfo(script);

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
    initFileInfo(script);
    return translateHandler(handler)->toLingo(dotSyntax_);
}

LingoDecompiler::DecompiledHandler LingoDecompiler::decompileHandlerWithMapping(
    const chunks::ScriptChunk::Handler& handler,
    const chunks::ScriptChunk& script,
    const chunks::ScriptNamesChunk* names) {
    script_ = &script;
    names_ = names;
    initFileInfo(script);
    return buildLineMapping(*translateHandler(handler), dotSyntax_);
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

void LingoDecompiler::initFileInfo(const chunks::ScriptChunk& script) {
    version_ = 0x4C1;
    capitalX_ = false;
    if (script.file() != nullptr) {
        version_ = script.file()->version();
        capitalX_ = script.file()->isCapitalX();
    }
    dotSyntax_ = version_ >= 700;
}

std::unique_ptr<HandlerNode> LingoDecompiler::translateHandler(const chunks::ScriptChunk::Handler& handler) {
    currentHandler_ = &handler;
    stack_.clear();

    std::vector<std::string> argumentNames;
    argumentNames.reserve(handler.argNameIds.size());
    for (const auto nameId : handler.argNameIds) {
        argumentNames.push_back(resolveName(nameId));
    }

    auto root = std::make_unique<HandlerNode>(resolveName(handler.nameId), std::move(argumentNames), std::vector<std::string>{});
    for (std::size_t index = 0; index < handler.instructions.size(); ++index) {
        translateInstruction(handler.instructions[index], index, root->block());
    }
    return root;
}

void LingoDecompiler::translateInstruction(const chunks::ScriptChunk::Instruction& instruction,
                                           std::size_t index,
                                           BlockNode& block) {
    NodePtr translation;

    switch (instruction.opcode) {
        case Opcode::RET:
        case Opcode::RET_FACTORY:
            if (script_ != nullptr && currentHandler_ != nullptr && index + 1 == currentHandler_->instructions.size()) {
                return;
            }
            translation = std::make_unique<ExitStmtNode>();
            break;

        case Opcode::PUSH_ZERO:
            translation = std::make_unique<LiteralNode>(0);
            break;

        case Opcode::MUL:
        case Opcode::ADD:
        case Opcode::SUB:
        case Opcode::DIV:
        case Opcode::MOD:
        case Opcode::JOIN_STR:
        case Opcode::JOIN_PAD_STR:
        case Opcode::LT:
        case Opcode::LT_EQ:
        case Opcode::NT_EQ:
        case Opcode::EQ:
        case Opcode::GT:
        case Opcode::GT_EQ:
        case Opcode::AND:
        case Opcode::OR:
        case Opcode::CONTAINS_STR:
        case Opcode::CONTAINS_0_STR: {
            auto right = popNode();
            auto left = popNode();
            translation = std::make_unique<BinaryOpNode>(instruction.opcode, std::move(left), std::move(right));
            break;
        }

        case Opcode::INV:
            translation = std::make_unique<InverseOpNode>(popNode());
            break;

        case Opcode::NOT:
            translation = std::make_unique<NotOpNode>(popNode());
            break;

        case Opcode::PUSH_INT8:
        case Opcode::PUSH_INT16:
        case Opcode::PUSH_INT32:
            translation = std::make_unique<LiteralNode>(instruction.argument);
            break;

        case Opcode::PUSH_FLOAT32: {
            const auto bits = static_cast<std::uint32_t>(instruction.argument);
            translation = std::make_unique<LiteralNode>(static_cast<double>(std::bit_cast<float>(bits)));
            break;
        }

        case Opcode::PUSH_ARG_LIST:
        case Opcode::PUSH_ARG_LIST_NO_RET: {
            std::vector<NodePtr> args;
            const int count = std::max(0, instruction.argument);
            args.reserve(static_cast<std::size_t>(count));
            for (int argIndex = 0; argIndex < count; ++argIndex) {
                args.insert(args.begin(), popNode());
            }
            translation = std::make_unique<LiteralNode>(
                instruction.opcode == Opcode::PUSH_ARG_LIST ? ValueType::ArgList : ValueType::ArgListNoRet,
                std::move(args));
            break;
        }

        case Opcode::PUSH_LIST:
        case Opcode::PUSH_PROP_LIST:
            translation = popNode();
            translation->setValueType(instruction.opcode == Opcode::PUSH_LIST ? ValueType::List : ValueType::PropList);
            break;

        case Opcode::PUSH_CONS: {
            const int literalId = instruction.argument / variableMultiplier();
            if (script_ != nullptr && literalId >= 0 && literalId < static_cast<int>(script_->literals().size())) {
                translation = literalToNode(script_->literals()[static_cast<std::size_t>(literalId)]);
            } else {
                translation = std::make_unique<ErrorNode>();
            }
            break;
        }

        case Opcode::PUSH_SYMB:
            translation = std::make_unique<LiteralNode>(ValueType::Symbol, resolveName(instruction.argument));
            break;

        case Opcode::PUSH_VAR_REF:
            translation = std::make_unique<LiteralNode>(ValueType::VarRef, resolveName(instruction.argument));
            break;

        case Opcode::GET_GLOBAL:
        case Opcode::GET_GLOBAL2:
        case Opcode::GET_PROP:
        case Opcode::GET_TOP_LEVEL_PROP:
            translation = std::make_unique<VarNode>(resolveName(instruction.argument));
            break;

        case Opcode::GET_PARAM:
            translation = std::make_unique<VarNode>(getArgumentName(instruction.argument));
            break;

        case Opcode::GET_LOCAL:
            translation = std::make_unique<VarNode>(getLocalName(instruction.argument));
            break;

        case Opcode::SET_GLOBAL:
        case Opcode::SET_GLOBAL2:
        case Opcode::SET_PROP:
            translation = std::make_unique<AssignmentStmtNode>(
                std::make_unique<VarNode>(resolveName(instruction.argument)),
                popNode());
            break;

        case Opcode::SET_PARAM:
            translation = std::make_unique<AssignmentStmtNode>(
                std::make_unique<VarNode>(getArgumentName(instruction.argument)),
                popNode());
            break;

        case Opcode::SET_LOCAL:
            translation = std::make_unique<AssignmentStmtNode>(
                std::make_unique<VarNode>(getLocalName(instruction.argument)),
                popNode());
            break;

        case Opcode::LOCAL_CALL: {
            auto argList = popNode();
            std::string callName = "handler#" + std::to_string(instruction.argument);
            if (script_ != nullptr &&
                instruction.argument >= 0 &&
                instruction.argument < static_cast<int>(script_->handlers().size())) {
                callName = resolveName(script_->handlers()[static_cast<std::size_t>(instruction.argument)].nameId);
            }
            translation = std::make_unique<CallNode>(std::move(callName), std::move(argList));
            break;
        }

        case Opcode::EXT_CALL:
        case Opcode::TELL_CALL:
            translation = std::make_unique<CallNode>(resolveName(instruction.argument), popNode());
            break;

        case Opcode::GET_MOVIE_PROP:
            translation = std::make_unique<TheExprNode>(resolveName(instruction.argument));
            break;

        case Opcode::SET_MOVIE_PROP:
            translation = std::make_unique<AssignmentStmtNode>(
                std::make_unique<TheExprNode>(resolveName(instruction.argument)),
                popNode());
            break;

        case Opcode::GET_OBJ_PROP:
        case Opcode::GET_CHAINED_PROP:
            translation = std::make_unique<ObjPropExprNode>(popNode(), resolveName(instruction.argument));
            break;

        case Opcode::SET_OBJ_PROP: {
            auto value = popNode();
            auto object = popNode();
            translation = std::make_unique<AssignmentStmtNode>(
                std::make_unique<ObjPropExprNode>(std::move(object), resolveName(instruction.argument)),
                std::move(value));
            break;
        }

        case Opcode::THE_BUILTIN:
            (void)popNode();
            translation = std::make_unique<TheExprNode>(resolveName(instruction.argument));
            break;

        case Opcode::NEW_OBJ:
            translation = std::make_unique<NewObjNode>(resolveName(instruction.argument), popNode());
            break;

        case Opcode::SWAP:
            if (stack_.size() >= 2) {
                std::swap(stack_[stack_.size() - 1], stack_[stack_.size() - 2]);
            }
            return;

        case Opcode::POP:
            for (int count = 0; count < instruction.argument; ++count) {
                (void)popNode();
            }
            return;

        case Opcode::CALL_JAVASCRIPT:
            stack_.clear();
            translation = std::make_unique<CommentNode>("@js");
            break;

        default: {
            std::string text(lingo::mnemonic(instruction.opcode));
            if (instruction.rawOpcode >= 0x40) {
                text.push_back(' ');
                text.append(std::to_string(instruction.argument));
            }
            translation = std::make_unique<CommentNode>(std::move(text));
            stack_.clear();
            break;
        }
    }

    if (!translation) {
        translation = std::make_unique<ErrorNode>();
    }
    translation->setBytecodeOffset(instruction.offset);

    if (translation->isExpression()) {
        stack_.push_back(std::move(translation));
    } else {
        block.addChild(std::move(translation));
    }
}

NodePtr LingoDecompiler::popNode() {
    if (stack_.empty()) {
        return std::make_unique<ErrorNode>();
    }
    auto node = std::move(stack_.back());
    stack_.pop_back();
    return node;
}

int LingoDecompiler::variableMultiplier() const {
    if (capitalX_) {
        return 1;
    }
    if (version_ >= 500) {
        return 8;
    }
    return 6;
}

std::string LingoDecompiler::getArgumentName(int rawIndex) const {
    const int index = rawIndex / variableMultiplier();
    if (currentHandler_ != nullptr &&
        index >= 0 &&
        index < static_cast<int>(currentHandler_->argNameIds.size())) {
        return resolveName(currentHandler_->argNameIds[static_cast<std::size_t>(index)]);
    }
    return "UNKNOWN_ARG_" + std::to_string(index);
}

std::string LingoDecompiler::getLocalName(int rawIndex) const {
    const int index = rawIndex / variableMultiplier();
    if (currentHandler_ != nullptr &&
        index >= 0 &&
        index < static_cast<int>(currentHandler_->localNameIds.size())) {
        return resolveName(currentHandler_->localNameIds[static_cast<std::size_t>(index)]);
    }
    return "UNKNOWN_LOCAL_" + std::to_string(index);
}

NodePtr LingoDecompiler::literalToNode(const chunks::ScriptChunk::LiteralEntry& literal) const {
    switch (literal.type) {
        case 1:
            if (std::holds_alternative<std::string>(literal.value)) {
                return std::make_unique<LiteralNode>(ValueType::String, std::get<std::string>(literal.value));
            }
            return std::make_unique<LiteralNode>(ValueType::String, "");
        case 4:
            if (std::holds_alternative<int>(literal.value)) {
                return std::make_unique<LiteralNode>(std::get<int>(literal.value));
            }
            return std::make_unique<LiteralNode>(0);
        case 9:
            return std::make_unique<LiteralNode>(literal.numericValue);
        default:
            if (std::holds_alternative<std::string>(literal.value)) {
                return std::make_unique<LiteralNode>(ValueType::String, std::get<std::string>(literal.value));
            }
            return std::make_unique<LiteralNode>(ValueType::String, "");
    }
}

} // namespace libreshockwave::lingo::decompiler
