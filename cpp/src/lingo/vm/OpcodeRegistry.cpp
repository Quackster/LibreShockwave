#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"

#include <bit>
#include <utility>
#include <variant>

namespace libreshockwave::lingo::vm {
namespace {

Datum literalToDatum(const chunks::ScriptChunk::LiteralEntry& literal) {
    switch (literal.type) {
        case 1:
            if (const auto* value = std::get_if<std::string>(&literal.value)) {
                return Datum::of(*value);
            }
            break;
        case 4:
            if (const auto* value = std::get_if<int>(&literal.value)) {
                return Datum::of(*value);
            }
            break;
        case 9:
            return Datum::of(literal.numericValue);
        default:
            break;
    }
    return Datum::voidValue();
}

bool truthy(const Datum& datum) {
    if (datum.isVoid() || datum.isNull()) {
        return false;
    }
    if (const auto* value = datum.asInt()) {
        return value->value != 0;
    }
    if (const auto* value = datum.asFloat()) {
        return value->value != 0.0F;
    }
    if (datum.isString()) {
        return !datum.stringValue().empty();
    }
    return true;
}

bool pushZero(ExecutionContext& context) {
    context.push(Datum::of(0));
    return true;
}

bool pushInt(ExecutionContext& context) {
    context.push(Datum::of(context.argument()));
    return true;
}

bool pushFloat(ExecutionContext& context) {
    context.push(Datum::of(std::bit_cast<float>(context.argument())));
    return true;
}

bool pushCons(ExecutionContext& context) {
    const auto& literals = context.literals();
    const int index = context.scaledArgument();
    if (index >= 0 && index < static_cast<int>(literals.size())) {
        context.push(literalToDatum(literals[static_cast<std::size_t>(index)]));
    } else {
        context.push(Datum::voidValue());
    }
    return true;
}

bool pushSymb(ExecutionContext& context) {
    context.push(Datum::symbol(context.resolveName(context.argument())));
    return true;
}

bool swap(ExecutionContext& context) {
    context.swap();
    return true;
}

bool pop(ExecutionContext& context) {
    const int count = context.argument();
    if (count <= 1) {
        (void)context.pop();
    } else {
        for (int index = 0; index < count; ++index) {
            (void)context.pop();
        }
    }
    return true;
}

bool peek(ExecutionContext& context) {
    context.push(context.peek(context.argument()));
    return true;
}

bool ret(ExecutionContext& context) {
    context.setReturnValue(context.pop());
    return true;
}

bool retFactory(ExecutionContext& context) {
    context.setReturnValue(Datum::voidValue());
    return true;
}

bool jmp(ExecutionContext& context) {
    context.jumpTo(context.instructionOffset() + context.argument());
    return false;
}

bool jmpIfZero(ExecutionContext& context) {
    if (!truthy(context.pop())) {
        context.jumpTo(context.instructionOffset() + context.argument());
        return false;
    }
    return true;
}

bool endRepeat(ExecutionContext& context) {
    context.jumpTo(context.instructionOffset() - context.argument());
    return false;
}

} // namespace

OpcodeRegistry::OpcodeRegistry() {
    StackOpcodes::registerHandlers(*this);
    ControlFlowOpcodes::registerHandlers(*this);
}

const OpcodeHandler* OpcodeRegistry::get(Opcode opcode) const {
    const auto found = handlers_.find(opcode);
    return found == handlers_.end() ? nullptr : &found->second;
}

bool OpcodeRegistry::hasHandler(Opcode opcode) const {
    return get(opcode) != nullptr;
}

bool OpcodeRegistry::execute(Opcode opcode, ExecutionContext& context) const {
    const auto* handler = get(opcode);
    return handler != nullptr && (*handler)(context);
}

void OpcodeRegistry::registerHandler(Opcode opcode, OpcodeHandler handler) {
    handlers_[opcode] = std::move(handler);
}

void StackOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::PUSH_ZERO, pushZero);
    registry.registerHandler(Opcode::PUSH_INT8, pushInt);
    registry.registerHandler(Opcode::PUSH_INT16, pushInt);
    registry.registerHandler(Opcode::PUSH_INT32, pushInt);
    registry.registerHandler(Opcode::PUSH_FLOAT32, pushFloat);
    registry.registerHandler(Opcode::PUSH_CONS, pushCons);
    registry.registerHandler(Opcode::PUSH_SYMB, pushSymb);
    registry.registerHandler(Opcode::SWAP, swap);
    registry.registerHandler(Opcode::POP, pop);
    registry.registerHandler(Opcode::PEEK, peek);
}

void ControlFlowOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::RET, ret);
    registry.registerHandler(Opcode::RET_FACTORY, retFactory);
    registry.registerHandler(Opcode::JMP, jmp);
    registry.registerHandler(Opcode::JMP_IF_Z, jmpIfZero);
    registry.registerHandler(Opcode::END_REPEAT, endRepeat);
}

} // namespace libreshockwave::lingo::vm
