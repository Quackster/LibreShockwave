#pragma once

#include <optional>
#include <string>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"

namespace libreshockwave::lingo::vm {

class Scope {
public:
    Scope(const chunks::ScriptChunk* script,
          chunks::ScriptChunk::Handler handler,
          std::vector<Datum> arguments = {},
          Datum receiver = Datum::voidValue(),
          bool firstParamDeclaredMe = false);

    [[nodiscard]] const chunks::ScriptChunk* script() const;
    [[nodiscard]] const chunks::ScriptChunk::Handler& handler() const;
    [[nodiscard]] const std::vector<Datum>& arguments() const;
    [[nodiscard]] std::vector<Datum> displayArguments() const;
    [[nodiscard]] const Datum& receiver() const;

    [[nodiscard]] int bytecodeIndex() const;
    void setBytecodeIndex(int index);
    void advanceBytecodeIndex();
    [[nodiscard]] bool hasMoreInstructions() const;
    [[nodiscard]] const chunks::ScriptChunk::Instruction* currentInstruction() const;

    void push(Datum value);
    [[nodiscard]] Datum pop();
    [[nodiscard]] Datum peek() const;
    [[nodiscard]] Datum peek(int depth) const;
    [[nodiscard]] int stackSize() const;
    void swap();
    void replaceTop(Datum value);
    void replaceTopTwo(Datum value);
    void drop(int count);

    [[nodiscard]] Datum getParam(int index) const;
    void setParam(int index, Datum value);
    [[nodiscard]] Datum getLocal(int index) const;
    void setLocal(int index, Datum value);

    [[nodiscard]] bool returned() const;
    void setReturned(bool returned);
    [[nodiscard]] Datum returnValue() const;
    void setReturnValue(Datum value);

    void pushLoopReturnIndex(int index);
    [[nodiscard]] int popLoopReturnIndex();
    [[nodiscard]] bool inLoop() const;

    [[nodiscard]] std::string toString() const;

private:
    [[nodiscard]] int paramOffset() const;
    [[nodiscard]] int displayArgumentOffset() const;

    const chunks::ScriptChunk* script_;
    chunks::ScriptChunk::Handler handler_;
    std::vector<Datum> arguments_;
    Datum receiver_;
    bool firstParamDeclaredMe_;
    mutable int cachedParamOffset_ = -1;

    std::vector<Datum> locals_;
    std::vector<std::optional<Datum>> modifiedParams_;
    std::vector<Datum> stack_;
    int bytecodeIndex_ = 0;
    Datum returnValue_{Datum::voidValue()};
    bool returned_ = false;
    std::vector<int> loopReturnStack_;
};

} // namespace libreshockwave::lingo::vm
