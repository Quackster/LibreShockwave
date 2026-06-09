#pragma once

#include <functional>
#include <unordered_map>

#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lingo/vm/ExecutionContext.hpp"

namespace libreshockwave::lingo::vm {

using OpcodeHandler = std::function<bool(ExecutionContext& context)>;

class OpcodeRegistry {
public:
    OpcodeRegistry();

    [[nodiscard]] const OpcodeHandler* get(Opcode opcode) const;
    [[nodiscard]] bool hasHandler(Opcode opcode) const;
    bool execute(Opcode opcode, ExecutionContext& context) const;
    void registerHandler(Opcode opcode, OpcodeHandler handler);

private:
    std::unordered_map<Opcode, OpcodeHandler> handlers_;
};

class StackOpcodes {
public:
    static void registerHandlers(OpcodeRegistry& registry);
};

class ControlFlowOpcodes {
public:
    static void registerHandlers(OpcodeRegistry& registry);
};

class ArithmeticOpcodes {
public:
    static void registerHandlers(OpcodeRegistry& registry);
};

class ComparisonOpcodes {
public:
    static void registerHandlers(OpcodeRegistry& registry);
};

class LogicalOpcodes {
public:
    static void registerHandlers(OpcodeRegistry& registry);
};

} // namespace libreshockwave::lingo::vm
