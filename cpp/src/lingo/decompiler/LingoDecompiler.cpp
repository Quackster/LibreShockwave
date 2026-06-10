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
#include "libreshockwave/lingo/decompiler/LingoProperties.hpp"

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
    currentBlock_ = nullptr;

    std::vector<std::string> argumentNames;
    argumentNames.reserve(handler.argNameIds.size());
    for (const auto nameId : handler.argNameIds) {
        argumentNames.push_back(resolveName(nameId));
    }

    auto root = std::make_unique<HandlerNode>(resolveName(handler.nameId), std::move(argumentNames), std::vector<std::string>{});
    currentBlock_ = &root->block();
    for (std::size_t index = 0; index < handler.instructions.size(); ++index) {
        const auto& instruction = handler.instructions[index];
        while (currentBlock_ != nullptr && instruction.offset == currentBlock_->endPos) {
            auto* exitedBlock = currentBlock_;
            auto* ancestorStatement = currentBlock_->ancestorStatement();
            exitBlock();
            if (auto* ifStmt = dynamic_cast<IfStmtNode*>(ancestorStatement);
                ifStmt != nullptr && ifStmt->hasElse() && exitedBlock == &ifStmt->trueBlock()) {
                enterBlock(ifStmt->falseBlock());
            }
        }
        if (currentBlock_ == nullptr) {
            currentBlock_ = &root->block();
        }
        translateInstruction(instruction, index, *currentBlock_);
    }
    currentBlock_ = nullptr;
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

        case Opcode::GET_CHUNK:
            translation = readChunkRef(popNode());
            break;

        case Opcode::HILITE_CHUNK: {
            NodePtr castId;
            if (version_ >= 500) {
                castId = popNode();
            }
            auto fieldId = popNode();
            auto field = std::make_unique<MemberExprNode>("field", std::move(fieldId), std::move(castId));
            translation = std::make_unique<ChunkHiliteStmtNode>(readChunkRef(std::move(field)));
            break;
        }

        case Opcode::ONTO_SPR: {
            auto right = popNode();
            auto left = popNode();
            translation = std::make_unique<SpriteIntersectsExprNode>(std::move(left), std::move(right));
            break;
        }

        case Opcode::INTO_SPR: {
            auto right = popNode();
            auto left = popNode();
            translation = std::make_unique<SpriteWithinExprNode>(std::move(left), std::move(right));
            break;
        }

        case Opcode::GET_FIELD: {
            NodePtr castId;
            if (version_ >= 500) {
                castId = popNode();
            }
            translation = std::make_unique<MemberExprNode>("field", popNode(), std::move(castId));
            break;
        }

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

        case Opcode::PUT: {
            const int putType = (instruction.argument >> 4) & 0xF;
            const int varType = instruction.argument & 0xF;
            auto variable = readVar(varType);
            auto value = popNode();
            translation = std::make_unique<PutStmtNode>(putType, std::move(variable), std::move(value));
            break;
        }

        case Opcode::PUT_CHUNK: {
            const int putType = (instruction.argument >> 4) & 0xF;
            const int varType = instruction.argument & 0xF;
            auto chunk = readChunkRef(readVar(varType));
            auto value = popNode();
            translation = std::make_unique<PutStmtNode>(putType, std::move(chunk), std::move(value));
            break;
        }

        case Opcode::DELETE_CHUNK:
            translation = std::make_unique<ChunkDeleteStmtNode>(readChunkRef(readVar(instruction.argument)));
            break;

        case Opcode::GET: {
            const auto propId = popNode()->intValue();
            translation = readV4Property(instruction.argument, propId);
            break;
        }

        case Opcode::SET: {
            const auto propId = popNode()->intValue();
            auto value = popNode();
            if (instruction.argument == 0x00 &&
                propId >= 0x01 &&
                propId <= 0x05 &&
                value->valueType() == ValueType::String) {
                auto script = value->stringValue();
                if (!script.empty() && (script.front() == ' ' || script.find('\r') != std::string::npos)) {
                    translation = std::make_unique<WhenStmtNode>(propId, std::move(script));
                }
            }
            if (!translation) {
                auto property = readV4Property(instruction.argument, propId);
                if (dynamic_cast<CommentNode*>(property.get()) != nullptr) {
                    translation = std::move(property);
                } else {
                    translation = std::make_unique<AssignmentStmtNode>(
                        std::move(property),
                        std::move(value),
                        true);
                }
            }
            break;
        }

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

        case Opcode::OBJ_CALL_V4: {
            auto object = readVar(instruction.argument);
            auto argList = popNode();
            const auto argListType = argList->valueType() == ValueType::ArgListNoRet
                ? ValueType::ArgListNoRet
                : ValueType::ArgList;
            auto args = takeArgNodes(std::move(argList));
            if (!args.empty() && args[0]->valueType() == ValueType::Symbol) {
                args[0] = std::make_unique<VarNode>(args[0]->stringValue());
            }
            translation = std::make_unique<ObjCallV4Node>(
                std::move(object),
                std::make_unique<LiteralNode>(argListType, std::move(args)));
            break;
        }

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

        case Opcode::OBJ_CALL:
            translateObjCall(instruction.offset, instruction.argument, block);
            return;

        case Opcode::JMP: {
            const int targetOffset = instruction.offset + instruction.argument;
            if (currentBlock_ != nullptr &&
                currentHandler_ != nullptr &&
                index + 1 < currentHandler_->instructions.size() &&
                currentHandler_->instructions[index + 1].offset == currentBlock_->endPos) {
                auto* ancestorStatement = currentBlock_->ancestorStatement();
                if (auto* ifStmt = dynamic_cast<IfStmtNode*>(ancestorStatement);
                    ifStmt != nullptr && currentBlock_ == &ifStmt->trueBlock()) {
                    ifStmt->setHasElse(true);
                    ifStmt->falseBlock().endPos = targetOffset;
                    return;
                }
            }
            translation = std::make_unique<CommentNode>("ERROR: Could not identify jmp");
            break;
        }

        case Opcode::JMP_IF_Z: {
            auto ifStmt = std::make_unique<IfStmtNode>(popNode());
            ifStmt->setBytecodeOffset(instruction.offset);
            ifStmt->trueBlock().endPos = instruction.offset + instruction.argument;
            auto* nextBlock = &ifStmt->trueBlock();
            block.addChild(std::move(ifStmt));
            enterBlock(*nextBlock);
            return;
        }

        case Opcode::END_REPEAT:
            translation = std::make_unique<CommentNode>("ERROR: Stray endrepeat");
            break;

        case Opcode::PUSH_CHUNK_VAR_REF:
            translation = readVar(instruction.argument);
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

void LingoDecompiler::translateObjCall(int bytecodeOffset, int nameId, BlockNode& block) {
    const auto method = resolveName(nameId);
    auto argList = popNode();
    const auto& rawArgs = argList->argNodes();
    const auto nargs = rawArgs.size();

    NodePtr translation;

    if (method == "getAt" && nargs == 2) {
        auto args = takeArgNodes(std::move(argList));
        translation = std::make_unique<ObjBracketExprNode>(std::move(args[0]), std::move(args[1]));
    } else if (method == "setAt" && nargs == 3) {
        auto args = takeArgNodes(std::move(argList));
        auto propExpr = std::make_unique<ObjBracketExprNode>(std::move(args[0]), std::move(args[1]));
        translation = std::make_unique<AssignmentStmtNode>(std::move(propExpr), std::move(args[2]));
    } else if ((method == "getProp" || method == "getPropRef") &&
               (nargs == 3 || nargs == 4) &&
               rawArgs[1]->valueType() == ValueType::Symbol) {
        const auto propName = rawArgs[1]->stringValue();
        auto args = takeArgNodes(std::move(argList));
        NodePtr index2;
        if (nargs == 4) {
            index2 = std::move(args[3]);
        }
        translation = std::make_unique<ObjPropIndexExprNode>(
            std::move(args[0]),
            propName,
            std::move(args[2]),
            std::move(index2));
    } else if (method == "setProp" &&
               (nargs == 4 || nargs == 5) &&
               rawArgs[1]->valueType() == ValueType::Symbol) {
        const auto propName = rawArgs[1]->stringValue();
        auto args = takeArgNodes(std::move(argList));
        NodePtr index2;
        if (nargs == 5) {
            index2 = std::move(args[3]);
        }
        auto propExpr = std::make_unique<ObjPropIndexExprNode>(
            std::move(args[0]),
            propName,
            std::move(args[2]),
            std::move(index2));
        translation = std::make_unique<AssignmentStmtNode>(std::move(propExpr), std::move(args[nargs - 1]));
    } else if (method == "count" &&
               nargs == 2 &&
               rawArgs[1]->valueType() == ValueType::Symbol) {
        const auto propName = rawArgs[1]->stringValue();
        auto args = takeArgNodes(std::move(argList));
        auto propExpr = std::make_unique<ObjPropExprNode>(std::move(args[0]), propName);
        translation = std::make_unique<ObjPropExprNode>(std::move(propExpr), "count");
    } else if ((method == "setContents" || method == "setContentsAfter" || method == "setContentsBefore") &&
               nargs == 2) {
        const int putType = method == "setContents" ? 1 : method == "setContentsAfter" ? 2 : 3;
        auto args = takeArgNodes(std::move(argList));
        translation = std::make_unique<PutStmtNode>(putType, std::move(args[0]), std::move(args[1]));
    } else if (method == "hilite" && nargs == 1) {
        auto args = takeArgNodes(std::move(argList));
        translation = std::make_unique<ChunkHiliteStmtNode>(std::move(args[0]));
    } else if (method == "delete" && nargs == 1) {
        auto args = takeArgNodes(std::move(argList));
        translation = std::make_unique<ChunkDeleteStmtNode>(std::move(args[0]));
    } else {
        translation = std::make_unique<ObjCallNode>(method, std::move(argList));
    }

    translation->setBytecodeOffset(bytecodeOffset);
    if (translation->isExpression()) {
        stack_.push_back(std::move(translation));
    } else {
        block.addChild(std::move(translation));
    }
}

void LingoDecompiler::enterBlock(BlockNode& block) {
    currentBlock_ = &block;
}

void LingoDecompiler::exitBlock() {
    if (currentBlock_ == nullptr) {
        return;
    }
    auto* ancestorStatement = currentBlock_->ancestorStatement();
    if (ancestorStatement == nullptr) {
        currentBlock_ = nullptr;
        return;
    }
    if (auto* parentBlock = dynamic_cast<BlockNode*>(ancestorStatement->parent()); parentBlock != nullptr) {
        currentBlock_ = parentBlock;
    } else {
        currentBlock_ = nullptr;
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

std::vector<NodePtr> LingoDecompiler::takeArgNodes(NodePtr argList) const {
    if (auto* literal = dynamic_cast<LiteralNode*>(argList.get()); literal != nullptr) {
        return literal->takeItems();
    }
    return {};
}

NodePtr LingoDecompiler::readVar(int varType) {
    NodePtr castId;
    if (varType == 0x6 && version_ >= 500) {
        castId = popNode();
    }
    auto id = popNode();

    switch (varType) {
        case 0x1:
        case 0x2:
        case 0x3:
            return id;
        case 0x4:
            return std::make_unique<LiteralNode>(ValueType::VarRef, getArgumentName(id->intValue()));
        case 0x5:
            return std::make_unique<LiteralNode>(ValueType::VarRef, getLocalName(id->intValue()));
        case 0x6:
            return std::make_unique<MemberExprNode>("field", std::move(id), std::move(castId));
        default:
            return std::make_unique<ErrorNode>();
    }
}

NodePtr LingoDecompiler::readChunkRef(NodePtr string) {
    auto lastLine = popNode();
    auto firstLine = popNode();
    auto lastItem = popNode();
    auto firstItem = popNode();
    auto lastWord = popNode();
    auto firstWord = popNode();
    auto lastChar = popNode();
    auto firstChar = popNode();

    if (!isZeroLiteral(*firstLine)) {
        string = std::make_unique<ChunkExprNode>(
            4,
            std::move(firstLine),
            std::move(lastLine),
            std::move(string));
    }
    if (!isZeroLiteral(*firstItem)) {
        string = std::make_unique<ChunkExprNode>(
            3,
            std::move(firstItem),
            std::move(lastItem),
            std::move(string));
    }
    if (!isZeroLiteral(*firstWord)) {
        string = std::make_unique<ChunkExprNode>(
            2,
            std::move(firstWord),
            std::move(lastWord),
            std::move(string));
    }
    if (!isZeroLiteral(*firstChar)) {
        string = std::make_unique<ChunkExprNode>(
            1,
            std::move(firstChar),
            std::move(lastChar),
            std::move(string));
    }

    return string;
}

NodePtr LingoDecompiler::readV4Property(int propertyType, int propertyId) {
    switch (propertyType) {
        case 0x00:
            if (propertyId <= 0x0b) {
                return std::make_unique<TheExprNode>(std::string(moviePropertyName(propertyId)));
            }
            return std::make_unique<LastStringChunkExprNode>(propertyId - 0x0b, popNode());

        case 0x01:
            return std::make_unique<StringChunkCountExprNode>(propertyId, popNode());

        case 0x02:
            return std::make_unique<MenuPropExprNode>(popNode(), propertyId);

        case 0x03: {
            auto menuId = popNode();
            return std::make_unique<MenuItemPropExprNode>(std::move(menuId), popNode(), propertyId);
        }

        case 0x04:
            return std::make_unique<SoundPropExprNode>(popNode(), propertyId);

        case 0x05:
            return std::make_unique<CommentNode>("ERROR: Resource property");

        case 0x06:
            return std::make_unique<SpritePropExprNode>(popNode(), propertyId);

        case 0x07:
            return std::make_unique<TheExprNode>(std::string(animationPropertyName(propertyId)));

        case 0x08:
            if (propertyId == 0x02 && version_ >= 500) {
                auto castLib = popNode();
                if (!isZeroLiteral(*castLib)) {
                    return std::make_unique<ThePropExprNode>(
                        std::make_unique<MemberExprNode>("castLib", std::move(castLib)),
                        std::string(animation2PropertyName(propertyId)));
                }
            }
            return std::make_unique<TheExprNode>(std::string(animation2PropertyName(propertyId)));

        case 0x09:
        case 0x0a:
        case 0x0b:
        case 0x0c:
        case 0x0d:
        case 0x0e:
        case 0x0f:
        case 0x10:
        case 0x11:
        case 0x12:
        case 0x13:
        case 0x14:
        case 0x15: {
            NodePtr castId;
            if (version_ >= 500) {
                castId = popNode();
            }
            auto memberId = popNode();
            std::string prefix;
            if (propertyType == 0x0b || propertyType == 0x0c) {
                prefix = "field";
            } else if (propertyType == 0x14 || propertyType == 0x15) {
                prefix = "script";
            } else {
                prefix = version_ >= 500 ? "member" : "cast";
            }

            NodePtr entity = std::make_unique<MemberExprNode>(std::move(prefix), std::move(memberId), std::move(castId));
            if (propertyType == 0x0a || propertyType == 0x0c || propertyType == 0x15) {
                entity = readChunkRef(std::move(entity));
            }
            return std::make_unique<ThePropExprNode>(std::move(entity), std::string(memberPropertyName(propertyId)));
        }

        default:
            return std::make_unique<CommentNode>("ERROR: Unknown property type " + std::to_string(propertyType));
    }
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

bool LingoDecompiler::isZeroLiteral(const LingoNode& node) {
    return node.valueType() == ValueType::Int && node.intValue() == 0;
}

} // namespace libreshockwave::lingo::decompiler
