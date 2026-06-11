#pragma once

#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/player/debug/Breakpoint.hpp"
#include "libreshockwave/player/debug/DebugSnapshot.hpp"

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::editor::debug {

struct HandlerItem {
    std::shared_ptr<chunks::ScriptChunk> script;
    chunks::ScriptChunk::Handler handler;
    std::string displayName;

    HandlerItem(std::shared_ptr<chunks::ScriptChunk> script,
                chunks::ScriptChunk::Handler handler,
                std::string displayName);

    [[nodiscard]] static HandlerItem fromScript(std::shared_ptr<chunks::ScriptChunk> script,
                                                chunks::ScriptChunk::Handler handler,
                                                const chunks::ScriptNamesChunk* names);
    [[nodiscard]] std::string toString() const;
    [[nodiscard]] bool matchesFilter(std::string_view filter) const;

    friend bool operator==(const HandlerItem&, const HandlerItem&) = default;
};

struct ScriptItem {
    std::shared_ptr<chunks::ScriptChunk> script;
    std::string displayName;
    std::string sourceName;
    int loadOrder{0};

    ScriptItem(std::shared_ptr<chunks::ScriptChunk> script,
               std::string displayName,
               std::string sourceName,
               int loadOrder);

    [[nodiscard]] std::string toString() const;
    [[nodiscard]] bool matchesFilter(std::string_view filter) const;

    friend bool operator==(const ScriptItem&, const ScriptItem&) = default;
};

struct InstructionDisplayItem {
    int offset{0};
    int index{0};
    std::string opcode;
    int argument{0};
    std::string annotation;
    bool hasBreakpoint{false};
    std::optional<player::debug::Breakpoint> breakpoint;
    bool isCurrent{false};
    bool navigable{false};
    bool lingoLine{false};

    [[nodiscard]] static InstructionDisplayItem fromInstruction(const player::debug::InstructionDisplay& instruction);
    [[nodiscard]] bool isCallInstruction() const;
    [[nodiscard]] bool isNavigableCall() const;
    [[nodiscard]] std::optional<std::string> getCallTargetName() const;
    [[nodiscard]] std::string toString() const;

    friend bool operator==(const InstructionDisplayItem&, const InstructionDisplayItem&) = default;
};

struct DebugDisplayColor {
    int r{};
    int g{};
    int b{};

    friend bool operator==(const DebugDisplayColor&, const DebugDisplayColor&) = default;
};

struct BytecodeLinePresentation {
    std::string html;
    std::optional<DebugDisplayColor> background;
    bool opaque{false};

    friend bool operator==(const BytecodeLinePresentation&, const BytecodeLinePresentation&) = default;
};

class BytecodeCellPresentation {
public:
    [[nodiscard]] static std::string breakpointMarker(const InstructionDisplayItem& item);
    [[nodiscard]] static BytecodeLinePresentation line(const InstructionDisplayItem& item,
                                                       bool selected = false);
};

} // namespace libreshockwave::editor::debug
