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
    const chunks::ScriptChunk::Handler* currentHandler_ = nullptr;
    int version_ = 0x4C1;
    bool capitalX_ = false;
    bool dotSyntax_ = false;
    std::vector<NodePtr> stack_;

    void initFileInfo(const chunks::ScriptChunk& script);
    [[nodiscard]] std::unique_ptr<HandlerNode> translateHandler(const chunks::ScriptChunk::Handler& handler);
    void translateInstruction(const chunks::ScriptChunk::Instruction& instruction,
                              std::size_t index,
                              BlockNode& block);
    [[nodiscard]] NodePtr popNode();
    [[nodiscard]] NodePtr readVar(int varType);
    [[nodiscard]] NodePtr readChunkRef(NodePtr string);
    [[nodiscard]] NodePtr readV4Property(int propertyType, int propertyId);
    [[nodiscard]] int variableMultiplier() const;
    [[nodiscard]] std::string resolveName(int nameId) const;
    [[nodiscard]] std::string getArgumentName(int rawIndex) const;
    [[nodiscard]] std::string getLocalName(int rawIndex) const;
    [[nodiscard]] NodePtr literalToNode(const chunks::ScriptChunk::LiteralEntry& literal) const;
    [[nodiscard]] static bool isZeroLiteral(const LingoNode& node);
};

} // namespace libreshockwave::lingo::decompiler
