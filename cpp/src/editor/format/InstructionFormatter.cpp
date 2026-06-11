#include "libreshockwave/editor/format/InstructionFormatter.hpp"

#include <cstddef>
#include <iomanip>
#include <sstream>

#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"
#include "libreshockwave/lingo/Opcode.hpp"

namespace libreshockwave::editor::format {
namespace {

bool hasName(const chunks::ScriptNamesChunk* names, int index) {
    return names != nullptr && index >= 0 && index < static_cast<int>(names->names().size());
}

bool isNamedVariableOpcode(lingo::Opcode opcode) {
    switch (opcode) {
        case lingo::Opcode::GET_GLOBAL:
        case lingo::Opcode::SET_GLOBAL:
        case lingo::Opcode::GET_GLOBAL2:
        case lingo::Opcode::SET_GLOBAL2:
        case lingo::Opcode::GET_PROP:
        case lingo::Opcode::SET_PROP:
        case lingo::Opcode::GET_OBJ_PROP:
        case lingo::Opcode::SET_OBJ_PROP:
        case lingo::Opcode::GET_MOVIE_PROP:
        case lingo::Opcode::SET_MOVIE_PROP:
        case lingo::Opcode::GET_TOP_LEVEL_PROP:
        case lingo::Opcode::GET_CHAINED_PROP:
            return true;
        default:
            return false;
    }
}

bool isNamedCallOpcode(lingo::Opcode opcode) {
    switch (opcode) {
        case lingo::Opcode::LOCAL_CALL:
        case lingo::Opcode::EXT_CALL:
        case lingo::Opcode::OBJ_CALL:
        case lingo::Opcode::OBJ_CALL_V4:
        case lingo::Opcode::TELL_CALL:
            return true;
        default:
            return false;
    }
}

} // namespace

std::string InstructionFormatter::format(const chunks::ScriptChunk::Instruction& instruction,
                                         const chunks::ScriptChunk& script,
                                         const chunks::ScriptNamesChunk* names) {
    std::ostringstream out;
    out << "[" << std::setfill('0') << std::setw(4) << instruction.offset << "] "
        << std::setfill(' ') << std::left << std::setw(16) << lingo::mnemonic(instruction.opcode);
    if (instruction.rawOpcode >= 0x40) {
        out << " " << formatArgument(instruction, script, names);
    }
    return out.str();
}

std::string InstructionFormatter::formatArgument(const chunks::ScriptChunk::Instruction& instruction,
                                                 const chunks::ScriptChunk& script,
                                                 const chunks::ScriptNamesChunk* names) {
    const int argument = instruction.argument;
    const auto opcode = instruction.opcode;

    if (opcode == lingo::Opcode::PUSH_CONS) {
        const auto& literals = script.literals();
        if (argument >= 0 && argument < static_cast<int>(literals.size())) {
            const auto& literal = literals[static_cast<std::size_t>(argument)];
            return std::to_string(argument) + " <" +
                   ::libreshockwave::format::getLiteralTypeNameShort(literal.type) + "> " +
                   ::libreshockwave::format::formatLiteralValue(literal.value, 40);
        }
    }

    if (opcode == lingo::Opcode::PUSH_SYMB && hasName(names, argument)) {
        return std::to_string(argument) + " #" + names->getName(argument);
    }

    if (isNamedVariableOpcode(opcode) && hasName(names, argument)) {
        return std::to_string(argument) + " (" + names->getName(argument) + ")";
    }

    if (isNamedCallOpcode(opcode) && hasName(names, argument)) {
        return std::to_string(argument) + " [" + names->getName(argument) + "]";
    }

    if ((opcode == lingo::Opcode::PUT || opcode == lingo::Opcode::GET || opcode == lingo::Opcode::SET) &&
        hasName(names, argument)) {
        return std::to_string(argument) + " (" + names->getName(argument) + ")";
    }

    if (opcode == lingo::Opcode::THE_BUILTIN && hasName(names, argument)) {
        return std::to_string(argument) + " the " + names->getName(argument);
    }

    if (opcode == lingo::Opcode::NEW_OBJ && hasName(names, argument)) {
        return std::to_string(argument) + " new(" + names->getName(argument) + ")";
    }

    if (opcode == lingo::Opcode::PUSH_VAR_REF && hasName(names, argument)) {
        return std::to_string(argument) + " @" + names->getName(argument);
    }

    if (lingo::isJump(opcode)) {
        return std::to_string(argument) + " -> offset " + std::to_string(argument);
    }

    return std::to_string(argument);
}

} // namespace libreshockwave::editor::format
