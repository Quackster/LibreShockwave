#include "libreshockwave/lingo/vm/Scope.hpp"

#include <algorithm>
#include <sstream>
#include <utility>

namespace libreshockwave::lingo::vm {

Scope::Scope(const chunks::ScriptChunk* script,
             chunks::ScriptChunk::Handler handler,
             std::vector<Datum> arguments,
             Datum receiver,
             bool firstParamDeclaredMe)
    : script_(script),
      handler_(std::move(handler)),
      arguments_(std::move(arguments)),
      receiver_(std::move(receiver)),
      firstParamDeclaredMe_(firstParamDeclaredMe),
      locals_(static_cast<std::size_t>(std::max(0, handler_.localCount)), Datum::voidValue()) {
    stack_.reserve(16);
    loopReturnStack_.reserve(4);
}

const chunks::ScriptChunk* Scope::script() const {
    return script_;
}

const chunks::ScriptChunk::Handler& Scope::handler() const {
    return handler_;
}

const std::vector<Datum>& Scope::arguments() const {
    return arguments_;
}

std::vector<Datum> Scope::displayArguments() const {
    const int offset = displayArgumentOffset();
    const int explicitCount = std::max(0, static_cast<int>(arguments_.size()) - offset);
    std::vector<Datum> result;
    result.reserve(static_cast<std::size_t>(explicitCount));
    for (int index = 0; index < explicitCount; ++index) {
        const auto modifiedIndex = static_cast<std::size_t>(index);
        if (modifiedIndex < modifiedParams_.size() && modifiedParams_[modifiedIndex]) {
            result.push_back(*modifiedParams_[modifiedIndex]);
            continue;
        }

        const int originalIndex = index + offset;
        if (originalIndex >= 0 && originalIndex < static_cast<int>(arguments_.size())) {
            result.push_back(arguments_[static_cast<std::size_t>(originalIndex)]);
        } else {
            result.push_back(Datum::voidValue());
        }
    }
    return result;
}

const Datum& Scope::receiver() const {
    return receiver_;
}

int Scope::bytecodeIndex() const {
    return bytecodeIndex_;
}

void Scope::setBytecodeIndex(int index) {
    bytecodeIndex_ = index;
}

void Scope::advanceBytecodeIndex() {
    ++bytecodeIndex_;
}

bool Scope::hasMoreInstructions() const {
    return bytecodeIndex_ >= 0 && bytecodeIndex_ < static_cast<int>(handler_.instructions.size());
}

const chunks::ScriptChunk::Instruction* Scope::currentInstruction() const {
    if (!hasMoreInstructions()) {
        return nullptr;
    }
    return &handler_.instructions[static_cast<std::size_t>(bytecodeIndex_)];
}

void Scope::push(Datum value) {
    stack_.push_back(std::move(value));
}

Datum Scope::pop() {
    if (stack_.empty()) {
        return Datum::voidValue();
    }
    Datum value = stack_.back();
    stack_.pop_back();
    return value;
}

Datum Scope::peek() const {
    return peek(0);
}

Datum Scope::peek(int depth) const {
    const int index = static_cast<int>(stack_.size()) - 1 - depth;
    if (index < 0 || index >= static_cast<int>(stack_.size())) {
        return Datum::voidValue();
    }
    return stack_[static_cast<std::size_t>(index)];
}

int Scope::stackSize() const {
    return static_cast<int>(stack_.size());
}

void Scope::swap() {
    if (stack_.size() >= 2) {
        std::iter_swap(stack_.end() - 1, stack_.end() - 2);
    }
}

void Scope::replaceTop(Datum value) {
    if (stack_.empty()) {
        push(std::move(value));
        return;
    }
    stack_.back() = std::move(value);
}

void Scope::replaceTopTwo(Datum value) {
    if (stack_.size() >= 2) {
        stack_[stack_.size() - 2] = std::move(value);
        stack_.pop_back();
        return;
    }
    stack_.clear();
    push(std::move(value));
}

void Scope::drop(int count) {
    if (count <= 0 || stack_.empty()) {
        return;
    }
    const auto newSize = static_cast<std::size_t>(std::max(0, static_cast<int>(stack_.size()) - count));
    stack_.resize(newSize);
}

Datum Scope::getParam(int index) const {
    const int actualIndex = index + paramOffset();
    if (index >= 0 && index < static_cast<int>(modifiedParams_.size())) {
        const auto& modified = modifiedParams_[static_cast<std::size_t>(index)];
        if (modified) {
            return *modified;
        }
    }
    if (actualIndex >= 0 && actualIndex < static_cast<int>(arguments_.size())) {
        return arguments_[static_cast<std::size_t>(actualIndex)];
    }
    return Datum::voidValue();
}

void Scope::setParam(int index, Datum value) {
    if (index < 0) {
        return;
    }
    const auto targetSize = static_cast<std::size_t>(index + 1);
    if (modifiedParams_.size() < targetSize) {
        modifiedParams_.resize(targetSize);
    }
    modifiedParams_[static_cast<std::size_t>(index)] = std::move(value);
}

Datum Scope::getLocal(int index) const {
    if (index >= 0 && index < static_cast<int>(locals_.size())) {
        return locals_[static_cast<std::size_t>(index)];
    }
    return Datum::voidValue();
}

void Scope::setLocal(int index, Datum value) {
    if (index >= 0 && index < static_cast<int>(locals_.size())) {
        locals_[static_cast<std::size_t>(index)] = std::move(value);
    }
}

bool Scope::returned() const {
    return returned_;
}

void Scope::setReturned(bool returned) {
    returned_ = returned;
}

Datum Scope::returnValue() const {
    return returnValue_;
}

void Scope::setReturnValue(Datum value) {
    returnValue_ = std::move(value);
    returned_ = true;
}

void Scope::pushLoopReturnIndex(int index) {
    loopReturnStack_.push_back(index);
}

int Scope::popLoopReturnIndex() {
    if (loopReturnStack_.empty()) {
        return -1;
    }
    const int index = loopReturnStack_.back();
    loopReturnStack_.pop_back();
    return index;
}

bool Scope::inLoop() const {
    return !loopReturnStack_.empty();
}

std::string Scope::toString() const {
    std::ostringstream out;
    out << "Scope{handler#" << handler_.nameId << ", bytecodeIndex=" << bytecodeIndex_
        << ", stackSize=" << stack_.size() << ", returned=" << (returned_ ? "true" : "false") << "}";
    return out.str();
}

int Scope::paramOffset() const {
    if (cachedParamOffset_ < 0) {
        if (!receiver_.isVoid() && !receiver_.isNull() && !arguments_.empty() &&
            arguments_.front() == receiver_ && !firstParamDeclaredMe_) {
            cachedParamOffset_ = 1;
        } else {
            cachedParamOffset_ = 0;
        }
    }
    return cachedParamOffset_;
}

int Scope::displayArgumentOffset() const {
    if (!receiver_.isVoid() && !receiver_.isNull() && !arguments_.empty() && arguments_.front() == receiver_) {
        return 1;
    }
    return 0;
}

} // namespace libreshockwave::lingo::vm
