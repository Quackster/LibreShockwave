#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"
#include "libreshockwave/lingo/vm/ExecutionContext.hpp"
#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"
#include "libreshockwave/lingo/vm/Scope.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::lingo::vm {

class LingoVM {
public:
    struct CallStackFrame {
        std::string handlerName;
        std::string scriptName;
        int bytecodeIndex{0};
        std::vector<std::string> arguments;

        friend bool operator==(const CallStackFrame&, const CallStackFrame&) = default;
    };

    explicit LingoVM(DirectorFile* file = nullptr);

    [[nodiscard]] DirectorFile* file() const;
    [[nodiscard]] builtin::BuiltinRegistry& builtinRegistry();
    [[nodiscard]] const builtin::BuiltinRegistry& builtinRegistry() const;
    [[nodiscard]] builtin::BuiltinContext& builtinContext();
    [[nodiscard]] const builtin::BuiltinContext& builtinContext() const;
    [[nodiscard]] OpcodeRegistry& opcodeRegistry();
    [[nodiscard]] const OpcodeRegistry& opcodeRegistry() const;

    [[nodiscard]] Datum getGlobal(std::string_view name) const;
    void setGlobal(std::string name, Datum value);
    [[nodiscard]] const std::unordered_map<std::string, Datum>& globals() const;
    void clearGlobals();

    [[nodiscard]] Datum getPref(std::string_view name) const;
    [[nodiscard]] Datum setPref(std::string name, const Datum& value);
    [[nodiscard]] const std::map<std::string, Datum>& prefs() const;

    void setRandomSeed(int seed);
    [[nodiscard]] int randomSeed() const;
    [[nodiscard]] int randomInt(int max);

    void setStepLimit(int limit);
    [[nodiscard]] int stepLimit() const;

    void setPassCallback(std::function<void()> callback);
    void clearPassCallback();
    [[nodiscard]] bool eventStopped() const;
    void resetEventStopped();

    void setErrorState(bool errorState);
    [[nodiscard]] bool isInErrorState() const;
    void resetErrorState();

    [[nodiscard]] int callStackDepth() const;
    [[nodiscard]] bool hasActiveCallStack() const;
    [[nodiscard]] Scope* currentScope();
    [[nodiscard]] const Scope* currentScope() const;
    [[nodiscard]] std::vector<CallStackFrame> callStack() const;
    [[nodiscard]] std::string formatCallStack() const;

    [[nodiscard]] bool isFlushingDeferredScriptInstanceCalls() const;
    void deferScriptInstanceCall(Datum instance, std::string methodName, std::vector<Datum> args);
    void deferTask(std::function<void()> task);
    void flushDeferredTasks();
    [[nodiscard]] bool isFlushingDeferredTasks() const;

    [[nodiscard]] std::optional<HandlerRef> findHandler(std::string_view handlerName);
    [[nodiscard]] std::optional<HandlerRef> findHandler(const chunks::ScriptChunk& script,
                                                        std::string_view handlerName) const;
    void invalidateHandlerCache();

    [[nodiscard]] Datum callHandler(std::string_view handlerName, const std::vector<Datum>& args = {});
    [[nodiscard]] Datum callHandler(std::string_view handlerName,
                                    const std::vector<Datum>& args,
                                    const Datum& receiver);
    [[nodiscard]] Datum callBuiltin(std::string_view handlerName, const std::vector<Datum>& args = {});
    [[nodiscard]] Datum executeHandler(const chunks::ScriptChunk& script,
                                       const chunks::ScriptChunk::Handler& handler,
                                       const std::vector<Datum>& args = {},
                                       const Datum& receiver = Datum::voidValue());

    [[nodiscard]] static std::string normalizeLookupName(std::string_view name);
    [[nodiscard]] static bool isGlobalHandlerScriptType(chunks::ScriptChunkType scriptType);

private:
    [[nodiscard]] ExecutionContext::Callbacks callbacksFor(const chunks::ScriptChunk& script);
    [[nodiscard]] std::shared_ptr<chunks::ScriptNamesChunk> scriptNamesForScript(
        const chunks::ScriptChunk& script) const;
    [[nodiscard]] std::string resolveName(const chunks::ScriptChunk& script, int nameId) const;
    [[nodiscard]] std::string handlerName(const chunks::ScriptChunk& script,
                                          const chunks::ScriptChunk::Handler& handler) const;
    [[nodiscard]] bool handlerDeclaresMeAsFirstParam(const chunks::ScriptChunk& script,
                                                     const chunks::ScriptChunk::Handler& handler) const;
    void executeInstruction(Scope& scope, ExecutionContext& context);
    [[nodiscard]] CallStackFrame toCallStackFrame(const Scope& scope) const;
    void registerRuntimeBuiltins();
    void flushDeferredScriptInstanceCalls();

    struct DeferredScriptInstanceCall {
        Datum instance{Datum::voidValue()};
        std::string methodName;
        std::vector<Datum> args;
    };

    DirectorFile* file_{nullptr};
    std::unordered_map<std::string, Datum> globals_;
    std::map<std::string, Datum> prefs_;
    std::deque<Scope> callStack_;
    std::deque<DeferredScriptInstanceCall> deferredScriptInstanceCalls_;
    std::deque<std::function<void()>> deferredTasks_;
    builtin::BuiltinRegistry builtinRegistry_;
    builtin::BuiltinContext builtinContext_;
    OpcodeRegistry opcodeRegistry_;
    std::unordered_map<std::string, HandlerRef> handlerCache_;
    std::unordered_set<std::string> missingHandlerCache_;
    std::function<void()> passCallback_;
    bool eventStopped_{false};
    bool inErrorState_{false};
    bool flushingDeferredScriptInstanceCalls_{false};
    bool flushingDeferredTasks_{false};
    int stepLimit_{0};
    int randomSeed_{0};
    std::int64_t randomState_{0};
};

} // namespace libreshockwave::lingo::vm
