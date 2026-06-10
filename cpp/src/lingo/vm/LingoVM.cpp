#include "libreshockwave/lingo/vm/LingoVM.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lingo/vm/DebugConfig.hpp"
#include "libreshockwave/lingo/vm/trace/ConsoleTracePrinter.hpp"
#include "libreshockwave/lingo/vm/trace/InstructionAnnotator.hpp"

namespace libreshockwave::lingo::vm {
namespace {

constexpr int MAX_CALL_STACK_DEPTH = 50;
constexpr int SAFEPOINT_CHECK_INTERVAL = 0x10000;
constexpr std::int64_t GC_SAFEPOINT_INTERVAL_MS = 1000;
constexpr std::int64_t HANDLER_TIMEOUT_MS = 60000;
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

std::string_view trimView(std::string_view value) {
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.front()))) {
        value.remove_prefix(1);
    }
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back()))) {
        value.remove_suffix(1);
    }
    return value;
}

std::optional<std::string> leadingValueIdentifier(std::string_view value) {
    const std::string_view text = trimView(value);
    if (text.empty()) {
        return std::nullopt;
    }
    const auto first = static_cast<unsigned char>(text.front());
    if (!std::isalpha(first) && text.front() != '_') {
        return std::nullopt;
    }

    std::size_t end = 1;
    while (end < text.size()) {
        const auto ch = static_cast<unsigned char>(text[end]);
        if (!std::isalnum(ch) && text[end] != '_') {
            break;
        }
        ++end;
    }
    if (end < text.size() && text[end] == '(') {
        return std::nullopt;
    }
    return std::string(text.substr(0, end));
}

bool isValueLiteralKeyword(std::string_view identifier) {
    return equalsIgnoreCase(identifier, "TRUE") ||
           equalsIgnoreCase(identifier, "FALSE") ||
           equalsIgnoreCase(identifier, "VOID") ||
           equalsIgnoreCase(identifier, "EMPTY");
}

std::optional<int> parseInitialRandomSeed(const char* rawValue) {
    if (rawValue == nullptr) {
        return std::nullopt;
    }

    std::string_view text(rawValue);
    while (!text.empty() && std::isspace(static_cast<unsigned char>(text.front()))) {
        text.remove_prefix(1);
    }
    while (!text.empty() && std::isspace(static_cast<unsigned char>(text.back()))) {
        text.remove_suffix(1);
    }
    if (text.empty()) {
        return std::nullopt;
    }

    const std::string seedText(text);
    char* end = nullptr;
    errno = 0;
    const long parsed = std::strtol(seedText.c_str(), &end, 10);
    if (errno == ERANGE || end == seedText.c_str() || *end != '\0' ||
        parsed < std::numeric_limits<int>::min() || parsed > std::numeric_limits<int>::max()) {
        return std::nullopt;
    }
    return static_cast<int>(parsed);
}

} // namespace

std::function<void()> LingoVM::gcCallback_;

LingoVM::LingoVM(DirectorFile* file)
    : file_(file) {
    setRandomSeed(0);
    if (const auto initialRandomSeed = parseInitialRandomSeed(std::getenv("LS_INITIAL_RANDOM_SEED"))) {
        setRandomSeed(*initialRandomSeed);
    }
    builtinContext_.valueEvaluator = [this](const Datum& value) {
        if (!value.isString()) {
            return Datum::voidValue();
        }
        const auto identifier = leadingValueIdentifier(value.stringValue());
        if (!identifier.has_value() || isValueLiteralKeyword(*identifier)) {
            return Datum::voidValue();
        }

        Datum globalValue = getGlobal(*identifier);
        if (!globalValue.isVoid()) {
            return globalValue;
        }
        if (auto handler = findHandler(*identifier)) {
            return executeHandler(*handler->script, handler->handler);
        }
        return Datum::voidValue();
    };
    builtinContext_.randomIntHandler = [this](int max) {
        return randomInt(max);
    };
    builtinContext_.debugPlaybackEnabled = DebugConfig::isDebugPlaybackEnabled();
    builtinContext_.getPrefHandler = [this](const std::string& name) {
        return getPref(name);
    };
    builtinContext_.setPrefHandler = [this](const std::string& name, const Datum& value) {
        return setPref(name, value);
    };
    builtinContext_.scriptInstanceMethodDeferrer = [this](const Datum& instance,
                                                          const std::string& methodName,
                                                          const std::vector<Datum>& args) {
        if (instance.type() != DatumType::ScriptInstanceRef || methodName.empty() ||
            isFlushingDeferredScriptInstanceCalls() || isFlushingDeferredTasks() || !hasActiveCallStack()) {
            return false;
        }

        deferTask([this, instance, methodName, args] {
            try {
                if (builtinContext_.callTargetHandler) {
                    (void)builtinContext_.callTargetHandler(instance, methodName, args);
                } else {
                    (void)callHandler(methodName, args, instance);
                }
            } catch (...) {
                // Java's deferred task path suppresses individual script-instance failures.
            }
        });
        return true;
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
    explicitRandomSeed_ = true;
    randomState_ = (static_cast<std::int64_t>(seed) ^ RANDOM_MULTIPLIER) & RANDOM_MASK;
}

int LingoVM::randomSeed() const {
    return randomSeed_;
}

int LingoVM::randomInt(int max) {
    int finalResult = 1;
    if (max > 0) {
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
        finalResult = result + 1;
    } else {
        finalResult = 1;
    }
    traceRandomCall(max, finalResult);
    return finalResult;
}

void LingoVM::setStepLimit(int limit) {
    stepLimit_ = std::max(0, limit);
}

int LingoVM::stepLimit() const {
    return stepLimit_;
}

void LingoVM::setTickDeadlineMs(std::int64_t milliseconds) {
    tickDeadlineMs_ = std::max<std::int64_t>(0, milliseconds);
}

std::int64_t LingoVM::tickDeadlineMs() const {
    return tickDeadlineMs_;
}

void LingoVM::setTickDeadline(std::int64_t deadlineMillis) {
    tickDeadline_ = std::max<std::int64_t>(0, deadlineMillis);
}

std::int64_t LingoVM::tickDeadline() const {
    return tickDeadline_;
}

void LingoVM::armTickDeadline() {
    tickDeadline_ = tickDeadlineMs_ > 0 ? currentTimeMillis() + tickDeadlineMs_ : 0;
}

void LingoVM::setTimeProvider(std::function<std::int64_t()> provider) {
    timeProvider_ = std::move(provider);
}

void LingoVM::setGcCallback(std::function<void()> callback) {
    gcCallback_ = std::move(callback);
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

void LingoVM::setTraceListener(std::shared_ptr<TraceListener> listener) {
    traceListener_ = std::move(listener);
}

std::shared_ptr<TraceListener> LingoVM::traceListener() const {
    return traceListener_;
}

void LingoVM::fireTraceError(std::string_view message, std::string_view error) {
    if (traceListener_) {
        traceListener_->onError(message, error);
    }
}

void LingoVM::setTraceEnabled(bool enabled) {
    traceEnabled_ = enabled;
}

bool LingoVM::traceEnabled() const {
    return traceEnabled_;
}

void LingoVM::setTraceOutputHandler(TraceOutputHandler handler) {
    traceOutputHandler_ = std::move(handler);
}

void LingoVM::addTraceHandler(std::string_view name) {
    if (!name.empty()) {
        tracedHandlers_.insert(normalizeLookupName(name));
    }
}

void LingoVM::removeTraceHandler(std::string_view name) {
    tracedHandlers_.erase(normalizeLookupName(name));
}

void LingoVM::clearTraceHandlers() {
    tracedHandlers_.clear();
}

const std::unordered_set<std::string>& LingoVM::tracedHandlers() const {
    return tracedHandlers_;
}

int LingoVM::callStackDepth() const {
    return static_cast<int>(callStack_.size());
}

bool LingoVM::hasActiveCallStack() const {
    return !callStack_.empty();
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

bool LingoVM::isFlushingDeferredScriptInstanceCalls() const {
    return flushingDeferredScriptInstanceCalls_;
}

void LingoVM::deferScriptInstanceCall(Datum instance, std::string methodName, std::vector<Datum> args) {
    if (instance.type() != DatumType::ScriptInstanceRef || methodName.empty()) {
        return;
    }
    deferredScriptInstanceCalls_.push_back(
        DeferredScriptInstanceCall{std::move(instance), std::move(methodName), std::move(args)});
}

void LingoVM::deferTask(std::function<void()> task) {
    if (!task) {
        return;
    }
    deferredTasks_.push_back(std::move(task));
}

void LingoVM::flushDeferredTasks() {
    if (flushingDeferredTasks_ || deferredTasks_.empty() || !callStack_.empty()) {
        return;
    }

    flushingDeferredTasks_ = true;
    try {
        while (!deferredTasks_.empty()) {
            auto task = std::move(deferredTasks_.front());
            deferredTasks_.pop_front();
            task();
            if (!callStack_.empty()) {
                break;
            }
        }
    } catch (...) {
        flushingDeferredTasks_ = false;
        throw;
    }
    flushingDeferredTasks_ = false;
}

bool LingoVM::isFlushingDeferredTasks() const {
    return flushingDeferredTasks_;
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

bool LingoVM::fireAlertHook(std::string_view errorMessage) {
    return fireAlertHook("Alert", errorMessage);
}

bool LingoVM::fireAlertHook(std::string_view errorType, std::string_view errorMessage) {
    if (alertHookDepth_ > 0 || !builtinContext_.alertHookHandler) {
        return false;
    }

    try {
        return builtinContext_.alertHookHandler(std::string(errorType), std::string(errorMessage));
    } catch (...) {
        return false;
    }
}

Datum LingoVM::executeHandler(const chunks::ScriptChunk& script,
                              const chunks::ScriptChunk::Handler& handler,
                              const std::vector<Datum>& args,
                              const Datum& receiver) {
    if (inErrorState_) {
        return Datum::voidValue();
    }

    const std::string currentHandlerName = handlerName(script, handler);
    const std::string normalizedHandlerName = normalizeLookupName(currentHandlerName);
    const bool isAlertHookHandler = equalsIgnoreCase(currentHandlerName, "alertHook");
    if (isAlertHookHandler && alertHookDepth_ > 0) {
        return Datum::voidValue();
    }

    Datum scopeReceiver = receiver;
    const Datum* effectiveReceiver = nullptr;
    if (!receiver.isVoid() && !receiver.isNull()) {
        effectiveReceiver = &receiver;
    } else if (!args.empty() && args.front().type() == DatumType::ScriptInstanceRef) {
        scopeReceiver = args.front();
        effectiveReceiver = &args.front();
    }
    if (normalizedHandlerName == "deconstruct" && effectiveReceiver != nullptr) {
        for (const auto& existing : callStack_) {
            const auto* existingScript = existing.script();
            if (existingScript == nullptr) {
                continue;
            }
            const std::string existingHandlerName = normalizeLookupName(handlerName(*existingScript, existing.handler()));
            if (shouldSkipDeconstructReentry(normalizedHandlerName,
                                             *effectiveReceiver,
                                             script,
                                             existingHandlerName,
                                             existing.receiver(),
                                             *existingScript)) {
                return Datum::voidValue();
            }
        }
    }

    if (callStack_.size() >= MAX_CALL_STACK_DEPTH) {
        throw LingoException("Call stack overflow (max " + std::to_string(MAX_CALL_STACK_DEPTH) + " frames)");
    }
    if (tracedHandlers_.contains(normalizedHandlerName)) {
        emitTracedHandlerCall(currentHandlerName, script, args);
    }

    std::vector<Datum> effectiveArgs = args;
    bool firstParamDeclaredMe = false;
    if (!receiver.isVoid() && !receiver.isNull()) {
        firstParamDeclaredMe = handlerDeclaresMeAsFirstParam(script, handler);
        effectiveArgs.insert(effectiveArgs.begin(), receiver);
    }

    callStack_.emplace_back(&script, handler, std::move(effectiveArgs), scopeReceiver, firstParamDeclaredMe);
    Scope& scope = callStack_.back();
    Datum result = Datum::voidValue();
    std::optional<TraceListener::HandlerInfo> handlerInfo;
    bool alertHookDepthIncremented = false;
    if (isAlertHookHandler) {
        ++alertHookDepth_;
        alertHookDepthIncremented = true;
    }
    auto leaveHandler = [&] {
        if (traceListener_ && handlerInfo.has_value()) {
            traceListener_->onHandlerExit(*handlerInfo, result);
        }
        if (traceEnabled_ && handlerInfo.has_value()) {
            emitConsoleHandlerExit(*handlerInfo, result);
        }
        if (alertHookDepthIncremented) {
            --alertHookDepth_;
            alertHookDepthIncremented = false;
        }
        callStack_.pop_back();
        flushDeferredScriptInstanceCalls();
    };
    try {
        if (traceListener_ || traceEnabled_) {
            handlerInfo = buildHandlerInfo(script, handler, args, receiver);
            if (traceEnabled_) {
                emitConsoleHandlerEnter(*handlerInfo);
            }
        }
        if (traceListener_ && handlerInfo.has_value()) {
            traceListener_->onHandlerEnter(*handlerInfo);
        }
        if (const auto* first = scope.currentInstruction()) {
            auto callbacks = callbacksFor(script);
            ExecutionContext context(scope, *first, &builtinRegistry_, &builtinContext_, std::move(callbacks));
            int steps = 0;
            const std::int64_t startTime = currentTimeMillis();
            std::int64_t lastGcTime = startTime;
            while (scope.hasMoreInstructions() && !scope.returned()) {
                ++steps;
                if (stepLimit_ > 0 && steps > stepLimit_) {
                    throw LingoException("Step limit exceeded (" + std::to_string(stepLimit_) +
                                         " instructions) in handler '" + handlerName(script, handler) + "'");
                }
                if ((steps % SAFEPOINT_CHECK_INTERVAL) == 0) {
                    const std::int64_t now = currentTimeMillis();
                    if (now - lastGcTime >= GC_SAFEPOINT_INTERVAL_MS) {
                        if (gcCallback_) {
                            gcCallback_();
                        }
                        lastGcTime = now;
                    }
                    if (now - startTime > HANDLER_TIMEOUT_MS) {
                        throw LingoException("Handler timeout (60s, " + std::to_string(steps) +
                                             " instructions) in handler '" + currentHandlerName + "'");
                    }
                    if (tickDeadline_ > 0 && now > tickDeadline_) {
                        throw LingoException("Tick deadline exceeded in handler '" + currentHandlerName +
                                             "' (" + std::to_string(steps) + " instructions)");
                    }
                }
                executeInstruction(scope, context);
            }
        }
        result = scope.returnValue();
    } catch (const std::exception& error) {
        fireTraceError("Error in " + currentHandlerName, error.what());
        if (!isAlertHookHandler && fireAlertHook("Script Error", error.what())) {
            result = Datum::voidValue();
            leaveHandler();
            return Datum::voidValue();
        }
        leaveHandler();
        throw;
    } catch (...) {
        fireTraceError("Error in " + currentHandlerName, "Unknown script error");
        if (!isAlertHookHandler && fireAlertHook("Script Error", "Unknown script error")) {
            result = Datum::voidValue();
            leaveHandler();
            return Datum::voidValue();
        }
        leaveHandler();
        throw;
    }
    leaveHandler();
    return result;
}

std::string LingoVM::normalizeLookupName(std::string_view name) {
    return lower(name);
}

std::string LingoVM::formatTraceArgument(const Datum& value) {
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

bool LingoVM::isGlobalHandlerScriptType(chunks::ScriptChunkType scriptType) {
    return scriptType == chunks::ScriptChunkType::MovieScript;
}

bool LingoVM::shouldSkipDeconstructReentry(std::string_view normalizedHandlerName,
                                           const Datum& effectiveReceiver,
                                           const chunks::ScriptChunk& currentScript,
                                           std::string_view existingNormalizedHandlerName,
                                           const Datum& existingReceiver,
                                           const chunks::ScriptChunk& existingScript) {
    return normalizedHandlerName == "deconstruct" &&
           existingNormalizedHandlerName == "deconstruct" &&
           effectiveReceiver.type() == DatumType::ScriptInstanceRef &&
           existingReceiver.type() == DatumType::ScriptInstanceRef &&
           effectiveReceiver == existingReceiver &&
           &currentScript == &existingScript;
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
    callbacks.variableSetListener = [this](std::string_view type, std::string_view name, const Datum& value) {
        if (traceListener_) {
            traceListener_->onVariableSet(type, name, value);
        }
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

std::string LingoVM::scriptDisplayName(const chunks::ScriptChunk& script) const {
    if (file_ != nullptr) {
        for (const auto& candidate : file_->scripts()) {
            if (candidate.get() == &script) {
                const std::string name = file_->getScriptName(candidate);
                if (!name.empty()) {
                    return name;
                }
                break;
            }
        }
    }
    return "script#" + std::to_string(script.id().value());
}

TraceListener::HandlerInfo LingoVM::buildHandlerInfo(
    const chunks::ScriptChunk& script,
    const chunks::ScriptChunk::Handler& handler,
    const std::vector<Datum>& args,
    const Datum& receiver) const {
    return TraceListener::HandlerInfo{
        handlerName(script, handler),
        script.id().value(),
        scriptDisplayName(script),
        args,
        receiver,
        globals_,
        script.literals(),
        handler.localCount,
        handler.argCount
    };
}

TraceListener::InstructionInfo LingoVM::buildInstructionInfo(
    const Scope& scope,
    const chunks::ScriptChunk::Instruction& instruction) const {
    std::vector<Datum> stackSnapshot;
    const int snapshotCount = std::min(10, scope.stackSize());
    stackSnapshot.reserve(static_cast<std::size_t>(snapshotCount));
    for (int index = 0; index < snapshotCount; ++index) {
        stackSnapshot.push_back(scope.peek(index));
    }

    const auto* script = scope.script();
    std::shared_ptr<chunks::ScriptNamesChunk> names;
    std::string annotation = instruction.toString();
    if (script != nullptr) {
        names = scriptNamesForScript(*script);
        annotation = trace::InstructionAnnotator::annotate(*script, instruction, names.get());
    }

    return TraceListener::InstructionInfo{
        scope.bytecodeIndex(),
        instruction.offset,
        std::string(mnemonic(instruction.opcode)),
        instruction.argument,
        std::move(annotation),
        scope.stackSize(),
        std::move(stackSnapshot),
        captureLocals(scope),
        globals_
    };
}

std::unordered_map<std::string, Datum> LingoVM::captureLocals(const Scope& scope) const {
    std::unordered_map<std::string, Datum> locals;
    const auto* script = scope.script();
    if (script == nullptr) {
        return locals;
    }

    const auto& handler = scope.handler();
    for (int index = 0; index < static_cast<int>(handler.argNameIds.size()); ++index) {
        locals[resolveName(*script, handler.argNameIds[static_cast<std::size_t>(index)])] = scope.getParam(index);
    }
    for (int index = 0; index < static_cast<int>(handler.localNameIds.size()); ++index) {
        locals[resolveName(*script, handler.localNameIds[static_cast<std::size_t>(index)])] = scope.getLocal(index);
    }
    return locals;
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

    if (traceEnabled_ || (traceListener_ && traceListener_->needsInstructionTrace())) {
        auto info = buildInstructionInfo(scope, *instruction);
        if (traceEnabled_) {
            emitConsoleInstruction(info);
        }
        if (traceListener_) {
            traceListener_->onInstruction(info);
        }
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

std::int64_t LingoVM::currentTimeMillis() const {
    if (timeProvider_) {
        return timeProvider_();
    }
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void LingoVM::traceRandomCall(int max, int result) {
    if (!tracedHandlers_.contains("random")) {
        return;
    }

    std::ostringstream out;
    out << "[TRACE] random(" << max << ")=" << result;
    if (explicitRandomSeed_) {
        out << " seed=" << randomSeed_;
    }
    if (const Scope* scope = currentScope(); scope != nullptr && scope->script() != nullptr) {
        out << " at " << handlerName(*scope->script(), scope->handler())
            << " in \"" << scriptDisplayName(*scope->script()) << "\"";
    }
    traceOutput(out.str());
}

void LingoVM::emitTracedHandlerCall(std::string_view handlerNameValue,
                                    const chunks::ScriptChunk& script,
                                    const std::vector<Datum>& args) {
    std::ostringstream out;
    out << "[TRACE] " << handlerNameValue << '(';
    for (std::size_t index = 0; index < args.size(); ++index) {
        if (index > 0) {
            out << ", ";
        }
        out << formatTraceArgument(args[index]);
    }
    out << ')';
    const std::string scriptName = scriptDisplayName(script);
    if (!scriptName.empty()) {
        out << " in \"" << scriptName << "\"";
    }
    traceOutput(out.str());
    traceOutput(formatCallStack());
}

void LingoVM::traceOutput(const std::string& line) const {
    if (traceOutputHandler_) {
        traceOutputHandler_(line);
        return;
    }
    std::cout << line << '\n';
}

void LingoVM::emitConsoleHandlerEnter(const TraceListener::HandlerInfo& info) {
    resetConsoleTraceForHandler(info.scriptId);
    traceOutput(trace::ConsoleTracePrinter::formatHandlerEnter(info));
}

void LingoVM::emitConsoleHandlerExit(const TraceListener::HandlerInfo& info, const Datum& returnValue) {
    if (auto line = trace::ConsoleTracePrinter::formatHandlerExit(info, returnValue)) {
        traceOutput(*line);
    }
}

void LingoVM::emitConsoleInstruction(const TraceListener::InstructionInfo& info) {
    if (shouldSuppressConsoleInstruction(info.offset)) {
        return;
    }
    traceOutput(trace::ConsoleTracePrinter::formatInstruction(info));
}

void LingoVM::resetConsoleTraceForHandler(int scriptId) {
    if (scriptId == consoleCurrentHandlerId_) {
        return;
    }
    consoleVisitedOffsets_.clear();
    consoleLoopSuppressed_ = false;
    consoleCurrentHandlerId_ = scriptId;
}

bool LingoVM::shouldSuppressConsoleInstruction(int offset) {
    if (consoleVisitedOffsets_.contains(offset)) {
        if (!consoleLoopSuppressed_) {
            traceOutput("    ... [loop iterations suppressed] ...");
            consoleLoopSuppressed_ = true;
        }
        return true;
    }
    consoleVisitedOffsets_.insert(offset);
    consoleLoopSuppressed_ = false;
    return false;
}

LingoVM::CallStackFrame LingoVM::toCallStackFrame(const Scope& scope) const {
    std::vector<std::string> args;
    const auto displayArgs = scope.displayArguments();
    args.reserve(displayArgs.size());
    for (const auto& arg : displayArgs) {
        args.push_back(formatTraceArgument(arg));
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

void LingoVM::flushDeferredScriptInstanceCalls() {
    if (flushingDeferredScriptInstanceCalls_ || deferredScriptInstanceCalls_.empty() || !callStack_.empty()) {
        return;
    }

    flushingDeferredScriptInstanceCalls_ = true;
    while (!deferredScriptInstanceCalls_.empty()) {
        auto call = std::move(deferredScriptInstanceCalls_.front());
        deferredScriptInstanceCalls_.pop_front();

        try {
            if (builtinContext_.callTargetHandler) {
                (void)builtinContext_.callTargetHandler(call.instance, call.methodName, call.args);
            } else {
                (void)callHandler(call.methodName, call.args, call.instance);
            }
        } catch (...) {
            // Java's deferred script-instance dispatcher suppresses per-target failures.
        }
    }
    flushingDeferredScriptInstanceCalls_ = false;
}

} // namespace libreshockwave::lingo::vm
