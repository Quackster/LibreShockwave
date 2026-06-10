#pragma once

#include <string>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/lingo/decompiler/LingoNode.hpp"

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::lingo::decompiler {

class LingoDecompiler {
public:
    struct DecompiledLine {
        std::string text;
        int bytecodeOffset = -1;
    };

    struct DecompiledHandler {
        std::vector<DecompiledLine> lines;

        [[nodiscard]] std::string toText() const;
    };

    [[nodiscard]] std::string decompile(const chunks::ScriptChunk& script,
                                        const chunks::ScriptNamesChunk* names = nullptr);
    [[nodiscard]] std::string decompileHandler(const chunks::ScriptChunk::Handler& handler,
                                               const chunks::ScriptChunk& script,
                                               const chunks::ScriptNamesChunk* names = nullptr);
    [[nodiscard]] DecompiledHandler decompileHandlerWithMapping(const chunks::ScriptChunk::Handler& handler,
                                                                const chunks::ScriptChunk& script,
                                                                const chunks::ScriptNamesChunk* names = nullptr);
    [[nodiscard]] std::string formatHandlerBytecodeOnly(const chunks::ScriptChunk::Handler& handler,
                                                        const chunks::ScriptNamesChunk* names = nullptr) const;

    [[nodiscard]] static DecompiledHandler buildLineMapping(const HandlerNode& handler, bool dotSyntax);

private:
    const chunks::ScriptChunk* script_ = nullptr;
    const chunks::ScriptNamesChunk* names_ = nullptr;

    [[nodiscard]] std::string resolveName(int nameId) const;
};

} // namespace libreshockwave::lingo::decompiler
