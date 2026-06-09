#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"
#include "libreshockwave/lingo/vm/Scope.hpp"

namespace libreshockwave::lingo::vm {

struct HandlerRef {
    const chunks::ScriptChunk* script;
    chunks::ScriptChunk::Handler handler;
};

class ExecutionContext {
public:
    using HandlerExecutor = std::function<Datum(const chunks::ScriptChunk& script,
                                                const chunks::ScriptChunk::Handler& handler,
                                                const std::vector<Datum>& args,
                                                const Datum& receiver)>;
    using NameResolver = std::function<std::string(int nameId)>;
    using HandlerFinder = std::function<std::optional<HandlerRef>(std::string_view name)>;
    using GlobalGetter = std::function<Datum(std::string_view name)>;
    using GlobalSetter = std::function<void(std::string_view name, const Datum& value)>;
    using BuiltinInvoker = std::function<Datum(std::string_view name, const std::vector<Datum>& args)>;
    using ErrorStateSetter = std::function<void(bool errorState)>;
    using CallStackFormatter = std::function<std::string()>;

    struct Callbacks {
        HandlerExecutor handlerExecutor;
        NameResolver nameResolver;
        HandlerFinder handlerFinder;
        GlobalGetter globalGetter;
        GlobalSetter globalSetter;
        BuiltinInvoker builtinInvoker;
        ErrorStateSetter errorStateSetter;
        CallStackFormatter callStackFormatter;
    };

    ExecutionContext(Scope& scope,
                     chunks::ScriptChunk::Instruction instruction,
                     builtin::BuiltinRegistry* builtins = nullptr,
                     builtin::BuiltinContext* builtinContext = nullptr,
                     Callbacks callbacks = {},
                     int variableMultiplier = 1);

    void setInstruction(chunks::ScriptChunk::Instruction instruction);

    [[nodiscard]] Scope& scope();
    [[nodiscard]] const Scope& scope() const;
    [[nodiscard]] const chunks::ScriptChunk::Instruction& instruction() const;
    [[nodiscard]] int argument() const;
    [[nodiscard]] int scaledArgument() const;
    [[nodiscard]] int variableMultiplier() const;
    [[nodiscard]] int instructionOffset() const;

    void push(Datum value);
    [[nodiscard]] Datum pop();
    [[nodiscard]] Datum peek() const;
    [[nodiscard]] Datum peek(int depth) const;
    void swap();
    [[nodiscard]] std::vector<Datum> popArgs(int count);

    [[nodiscard]] Datum getLocal(int index) const;
    void setLocal(int index, Datum value);
    [[nodiscard]] Datum getParam(int index) const;
    void setParam(int index, Datum value);

    [[nodiscard]] Datum getGlobal(std::string_view name) const;
    void setGlobal(std::string_view name, Datum value);

    void setReturnValue(Datum value);
    void setReturned(bool returned);
    void setErrorState(bool errorState);

    void jumpTo(int targetOffset);
    [[nodiscard]] std::optional<chunks::ScriptChunk::Handler> findLocalHandler(int index) const;
    [[nodiscard]] const std::vector<chunks::ScriptChunk::LiteralEntry>& literals() const;
    [[nodiscard]] std::string resolveName(int nameId) const;
    [[nodiscard]] std::optional<HandlerRef> findHandler(std::string_view name) const;
    [[nodiscard]] Datum executeHandler(const chunks::ScriptChunk& script,
                                       const chunks::ScriptChunk::Handler& handler,
                                       const std::vector<Datum>& args,
                                       const Datum& receiver) const;

    [[nodiscard]] bool isBuiltin(std::string_view name) const;
    [[nodiscard]] Datum invokeBuiltin(std::string_view name, const std::vector<Datum>& args);
    [[nodiscard]] std::optional<Datum> invokeBuiltinIfPresent(std::string_view name, const std::vector<Datum>& args);
    [[nodiscard]] builtin::BuiltinRegistry* builtins();
    [[nodiscard]] const builtin::BuiltinRegistry* builtins() const;

    [[nodiscard]] std::string formatCallStack() const;
    [[nodiscard]] LingoException error(const std::string& message) const;

private:
    Scope* scope_;
    chunks::ScriptChunk::Instruction instruction_;
    int argument_;
    int scaledArgument_;
    int variableMultiplier_;
    int cachedJumpOffset_ = -2147483648;
    int cachedJumpIndex_ = -1;
    builtin::BuiltinRegistry* builtins_;
    builtin::BuiltinContext* builtinContext_;
    Callbacks callbacks_;
};

} // namespace libreshockwave::lingo::vm
