#include "libreshockwave/lingo/vm/LingoVM.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <stdexcept>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"

namespace libreshockwave::lingo::vm {
namespace {

constexpr int MAX_CALL_STACK_DEPTH = 50;
constexpr std::int64_t RANDOM_MULTIPLIER = 0x5DEECE66DLL;
constexpr std::int64_t RANDOM_ADDEND = 0xBLL;
constexpr std::int64_t RANDOM_MASK = (1LL << 48) - 1;

bool equalsIgnoreCase(std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (std::size_t index = 0; index < lhs.size(); ++index) {
        if (std::tolower(static_cast<unsigned char>(lhs[index])) !=
            std::tolower(static_cast<unsigned char>(rhs[index]))) {
            return false;
        }
    }
    return true;
}

std::string lower(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return result;
}

std::string datumDisplay(const Datum& value) {
    if (value.isVoid()) {
        return "<VOID>";
    }
    if (const auto* symbol = value.asSymbol()) {
        return "#" + symbol->name;
    }
    if (const auto* string = value.asString()) {
        return "\"" + string->value + "\"";
    }
    return value.stringValue();
}

} // namespace

LingoVM::LingoVM(DirectorFile* file)
    : file_(file) {
    setRandomSeed(0);
    builtinContext_.randomIntHandler = [this](int max) {
        return randomInt(max);
    };
    builtinContext_.getPrefHandler = [this](const std::string& name) {
        return getPref(name);
    };
    builtinContext_.setPrefHandler = [this](const std::string& name, const Datum& value) {
        return setPref(name, value);
    };
    registerRuntimeBuiltins();
}

DirectorFile* LingoVM::file() const { return file_; }
builtin::BuiltinRegistry& LingoVM::builtinRegistry() { return builtinRegistry_; }
const builtin::BuiltinRegistry& LingoVM::builtinRegistry() const { return builtinRegistry_; }
builtin::BuiltinContext& LingoVM::builtinContext() { return builtinContext_; }
const builtin::BuiltinContext& LingoVM::builtinContext() const { return builtinContext_; }
OpcodeRegistry& LingoVM::opcodeRegistry() { return opcodeRegistry_; }
const OpcodeRegistry& LingoVM::opcodeRegistry() const { return opcodeRegistry_; }

Datum LingoVM::getGlobal(std::string_view name) const {
    const auto found = globals_.find(std::string(name));
    return found == globals_.end() ? Datum::voidValue() : found->second;
}

void LingoVM::setGlobal(std::string name, Datum value) {
    globals_[std::move(name)] = std::move(value);
}

const std::unordered_map<std::string, Datum>& LingoVM::globals() const {
    return globals_;
}

void LingoVM::clearGlobals() {
    globals_.clear();
}

Datum LingoVM::getPref(std::string_view name) const {
    const auto found = prefs_.find(lower(name));
    return found == prefs_.end() ? Datum::voidValue() : found->second;
}

Datum LingoVM::setPref(std::string name, const Datum& value) {
    if (name.empty()) {
        return Datum::voidValue();
    }
    Datum stored = Datum::of(value.stringValue());
    prefs_[lower(name)] = stored;
    return stored;
}

const std::map<std::string, Datum>& LingoVM::prefs() const {
    return prefs_;
}

void LingoVM::setRandomSeed(int seed) {
    randomSeed_ = seed;
    randomState_ = (static_cast<std::int64_t>(seed) ^ RANDOM_MULTIPLIER) & RANDOM_MASK;
}

int LingoVM::randomSeed() const {
    return randomSeed_;
}

int LingoVM::randomInt(int max) {
    if (max <= 0) {
        return 1;
    }

    auto nextBits = [this](int bits) {
        randomState_ = (randomState_ * RANDOM_MULTIPLIER + RANDOM_ADDEND) & RANDOM_MASK;
        return static_cast<int>(randomState_ >> (48 - bits));
    };

    int result = 0;
    if ((max & -max) == max) {
        result = static_cast<int>((static_cast<std::int64_t>(max) * nextBits(31)) >> 31);
    } else {
        int bits = 0;
        do {
            bits = nextBits(31);
            result = bits % max;
        } while (bits - result + (max - 1) < 0);
    }
    return result + 1;
}

void LingoVM::setStepLimit(int limit) {
    stepLimit_ = std::max(0, limit);
}

int LingoVM::stepLimit() const {
    return stepLimit_;
}

void LingoVM::setPassCallback(std::function<void()> callback) {
    passCallback_ = std::move(callback);
}

void LingoVM::clearPassCallback() {
    passCallback_ = nullptr;
}

bool LingoVM::eventStopped() const {
    return eventStopped_;
}

void LingoVM::resetEventStopped() {
    eventStopped_ = false;
}

void LingoVM::setErrorState(bool errorState) {
    inErrorState_ = errorState;
}

bool LingoVM::isInErrorState() const {
    return inErrorState_;
}

void LingoVM::resetErrorState() {
    inErrorState_ = false;
}

int LingoVM::callStackDepth() const {
    return static_cast<int>(callStack_.size());
}

Scope* LingoVM::currentScope() {
    return callStack_.empty() ? nullptr : &callStack_.back();
}

const Scope* LingoVM::currentScope() const {
    return callStack_.empty() ? nullptr : &callStack_.back();
}

std::vector<LingoVM::CallStackFrame> LingoVM::callStack() const {
    std::vector<CallStackFrame> frames;
    frames.reserve(callStack_.size());
    for (auto it = callStack_.rbegin(); it != callStack_.rend(); ++it) {
        frames.push_back(toCallStackFrame(*it));
    }
    return frames;
}

std::string LingoVM::formatCallStack() const {
    if (callStack_.empty()) {
        return "Lingo call stack: (empty)";
    }

    std::ostringstream out;
    out << "Lingo call stack:\n";
    for (const auto& frame : callStack()) {
        out << "  at " << frame.handlerName << '(';
        for (std::size_t index = 0; index < frame.arguments.size(); ++index) {
            if (index > 0) {
                out << ", ";
            }
            out << frame.arguments[index];
        }
        out << ") (" << frame.scriptName << ") [bytecode " << frame.bytecodeIndex << "]\n";
    }
    return out.str();
}

std::optional<HandlerRef> LingoVM::findHandler(std::string_view handlerNameValue) {
    if (handlerNameValue.empty()) {
        return std::nullopt;
    }

    const std::string cacheKey = normalizeLookupName(handlerNameValue);
    if (const auto found = handlerCache_.find(cacheKey); found != handlerCache_.end()) {
        return found->second;
    }
    if (missingHandlerCache_.contains(cacheKey)) {
        return std::nullopt;
    }

    if (file_ != nullptr) {
        for (const auto& script : file_->scripts()) {
            if (!script || !isGlobalHandlerScriptType(script->scriptType())) {
                continue;
            }
            if (auto handler = findHandler(*script, handlerNameValue)) {
                handlerCache_[cacheKey] = *handler;
                return handler;
            }
        }
    }

    missingHandlerCache_.insert(cacheKey);
    return std::nullopt;
}

std::optional<HandlerRef> LingoVM::findHandler(const chunks::ScriptChunk& script,
                                               std::string_view handlerNameValue) const {
    const auto names = scriptNamesForScript(script);
    if (auto found = script.findHandler(handlerNameValue, names.get())) {
        return HandlerRef{&script, *found};
    }
    if (names != nullptr) {
        return std::nullopt;
    }

    for (const auto& handler : script.handlers()) {
        if (equalsIgnoreCase(script.getHandlerName(handler, nullptr), handlerNameValue) ||
            equalsIgnoreCase("#" + std::to_string(handler.nameId), handlerNameValue)) {
            return HandlerRef{&script, handler};
        }
    }
    return std::nullopt;
}

void LingoVM::invalidateHandlerCache() {
    handlerCache_.clear();
    missingHandlerCache_.clear();
}

Datum LingoVM::callHandler(std::string_view handlerNameValue, const std::vector<Datum>& args) {
    if (auto handler = findHandler(handlerNameValue)) {
        return executeHandler(*handler->script, handler->handler, args);
    }
    return callBuiltin(handlerNameValue, args);
}

Datum LingoVM::callHandler(std::string_view handlerNameValue,
                           const std::vector<Datum>& args,
                           const Datum& receiver) {
    if (auto handler = findHandler(handlerNameValue)) {
        return executeHandler(*handler->script, handler->handler, args, receiver);
    }
    return Datum::voidValue();
}

Datum LingoVM::callBuiltin(std::string_view handlerNameValue, const std::vector<Datum>& args) {
    if (const auto result = builtinRegistry_.invokeIfPresent(handlerNameValue, builtinContext_, args)) {
        return *result;
    }
    return Datum::voidValue();
}

Datum LingoVM::executeHandler(const chunks::ScriptChunk& script,
                              const chunks::ScriptChunk::Handler& handler,
                              const std::vector<Datum>& args,
                              const Datum& receiver) {
    if (inErrorState_) {
        return Datum::voidValue();
    }
    if (callStack_.size() >= MAX_CALL_STACK_DEPTH) {
        throw LingoException("Call stack overflow (max " + std::to_string(MAX_CALL_STACK_DEPTH) + " frames)");
    }

    std::vector<Datum> effectiveArgs = args;
    bool firstParamDeclaredMe = false;
    if (!receiver.isVoid() && !receiver.isNull()) {
        firstParamDeclaredMe = handlerDeclaresMeAsFirstParam(script, handler);
        effectiveArgs.insert(effectiveArgs.begin(), receiver);
    }

    callStack_.emplace_back(&script, handler, std::move(effectiveArgs), receiver, firstParamDeclaredMe);
    Scope& scope = callStack_.back();
    Datum result = Datum::voidValue();
    try {
        if (const auto* first = scope.currentInstruction()) {
            auto callbacks = callbacksFor(script);
            ExecutionContext context(scope, *first, &builtinRegistry_, &builtinContext_, std::move(callbacks));
            int steps = 0;
            while (scope.hasMoreInstructions() && !scope.returned()) {
                ++steps;
                if (stepLimit_ > 0 && steps > stepLimit_) {
                    throw LingoException("Step limit exceeded (" + std::to_string(stepLimit_) +
                                         " instructions) in handler '" + handlerName(script, handler) + "'");
                }
                executeInstruction(scope, context);
            }
        }
        result = scope.returnValue();
    } catch (...) {
        callStack_.pop_back();
        throw;
    }
    callStack_.pop_back();
    return result;
}

std::string LingoVM::normalizeLookupName(std::string_view name) {
    return lower(name);
}

bool LingoVM::isGlobalHandlerScriptType(chunks::ScriptChunkType scriptType) {
    return scriptType == chunks::ScriptChunkType::MovieScript;
}

ExecutionContext::Callbacks LingoVM::callbacksFor(const chunks::ScriptChunk& script) {
    ExecutionContext::Callbacks callbacks;
    callbacks.nameResolver = [this, &script](int nameId) {
        return resolveName(script, nameId);
    };
    callbacks.handlerFinder = [this](std::string_view name) {
        return findHandler(name);
    };
    callbacks.handlerExecutor = [this](const chunks::ScriptChunk& targetScript,
                                       const chunks::ScriptChunk::Handler& targetHandler,
                                       const std::vector<Datum>& args,
                                       const Datum& receiver) {
        return executeHandler(targetScript, targetHandler, args, receiver);
    };
    callbacks.globalGetter = [this](std::string_view name) {
        return getGlobal(name);
    };
    callbacks.globalSetter = [this](std::string_view name, const Datum& value) {
        setGlobal(std::string(name), value);
    };
    callbacks.builtinInvoker = [this](std::string_view name, const std::vector<Datum>& args) {
        return callBuiltin(name, args);
    };
    callbacks.errorStateSetter = [this](bool errorState) {
        setErrorState(errorState);
    };
    callbacks.callStackFormatter = [this] {
        return formatCallStack();
    };
    return callbacks;
}

std::shared_ptr<chunks::ScriptNamesChunk> LingoVM::scriptNamesForScript(const chunks::ScriptChunk& script) const {
    if (file_ == nullptr) {
        return nullptr;
    }
    for (const auto& candidate : file_->scripts()) {
        if (candidate.get() == &script) {
            return file_->getScriptNamesForScript(candidate);
        }
    }
    return nullptr;
}

std::string LingoVM::resolveName(const chunks::ScriptChunk& script, int nameId) const {
    const auto names = scriptNamesForScript(script);
    return script.resolveName(nameId, names.get());
}

std::string LingoVM::handlerName(const chunks::ScriptChunk& script,
                                 const chunks::ScriptChunk::Handler& handler) const {
    const auto names = scriptNamesForScript(script);
    return script.getHandlerName(handler, names.get());
}

bool LingoVM::handlerDeclaresMeAsFirstParam(const chunks::ScriptChunk& script,
                                            const chunks::ScriptChunk::Handler& handler) const {
    if (handler.argNameIds.empty()) {
        return false;
    }
    return equalsIgnoreCase(resolveName(script, handler.argNameIds.front()), "me");
}

void LingoVM::executeInstruction(Scope& scope, ExecutionContext& context) {
    const auto* instruction = scope.currentInstruction();
    if (instruction == nullptr) {
        scope.setReturned(true);
        return;
    }

    const auto* handler = opcodeRegistry_.get(instruction->opcode);
    if (handler == nullptr) {
        scope.advanceBytecodeIndex();
        return;
    }

    context.setInstruction(*instruction);
    const bool advance = (*handler)(context);
    if (advance) {
        scope.advanceBytecodeIndex();
    }
}

LingoVM::CallStackFrame LingoVM::toCallStackFrame(const Scope& scope) const {
    std::vector<std::string> args;
    const auto displayArgs = scope.displayArguments();
    args.reserve(displayArgs.size());
    for (const auto& arg : displayArgs) {
        args.push_back(datumDisplay(arg));
    }

    return CallStackFrame{
        handlerName(*scope.script(), scope.handler()),
        "script#" + std::to_string(scope.script() != nullptr ? scope.script()->id().value() : 0),
        scope.bytecodeIndex(),
        std::move(args)
    };
}

void LingoVM::registerRuntimeBuiltins() {
    builtinRegistry_.registerBuiltin("pass", [this](builtin::BuiltinContext&, const std::vector<Datum>&) {
        if (passCallback_) {
            passCallback_();
        }
        return Datum::voidValue();
    });
    builtinRegistry_.registerBuiltin("stopevent", [this](builtin::BuiltinContext&, const std::vector<Datum>&) {
        eventStopped_ = true;
        return Datum::voidValue();
    });
}

} // namespace libreshockwave::lingo::vm
