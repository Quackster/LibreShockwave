#pragma once

#include <string>

#include "libreshockwave/chunks/ScriptChunk.hpp"

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::editor::format {

class InstructionFormatter {
public:
    InstructionFormatter() = delete;

    [[nodiscard]] static std::string format(const chunks::ScriptChunk::Instruction& instruction,
                                            const chunks::ScriptChunk& script,
                                            const chunks::ScriptNamesChunk* names);
    [[nodiscard]] static std::string formatArgument(const chunks::ScriptChunk::Instruction& instruction,
                                                    const chunks::ScriptChunk& script,
                                                    const chunks::ScriptNamesChunk* names);
};

} // namespace libreshockwave::editor::format
