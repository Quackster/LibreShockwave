#pragma once

#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/editor/debug/DebugDisplayItems.hpp"
#include "libreshockwave/player/debug/BreakpointManager.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::chunks {
class ScriptNamesChunk;
}

namespace libreshockwave::player::cast {
class CastLibManager;
}

namespace libreshockwave::editor::debug {

struct ScriptSource {
    std::shared_ptr<chunks::ScriptChunk> script;
    std::shared_ptr<chunks::ScriptNamesChunk> names;
    std::string displayName;
    std::string sourceName;
    int loadOrder{0};

    [[nodiscard]] ScriptItem toScriptItem() const;
    [[nodiscard]] HandlerItem toHandlerItem(const chunks::ScriptChunk::Handler& handler) const;

    friend bool operator==(const ScriptSource&, const ScriptSource&) = default;
};

struct HandlerLocation {
    std::shared_ptr<chunks::ScriptChunk> script;
    chunks::ScriptChunk::Handler handler{};
    std::shared_ptr<chunks::ScriptNamesChunk> names;
    bool hasHandler{false};

    [[nodiscard]] bool found() const;
    [[nodiscard]] std::string handlerName() const;
    [[nodiscard]] int scriptId() const;
};

class HandlerNavigator {
public:
    HandlerNavigator() = default;
    explicit HandlerNavigator(std::vector<ScriptSource> scripts);

    void setScripts(std::vector<ScriptSource> scripts);
    [[nodiscard]] const std::vector<ScriptSource>& allScripts() const;
    [[nodiscard]] std::optional<ScriptSource> findSourceById(int scriptId) const;
    [[nodiscard]] std::shared_ptr<chunks::ScriptChunk> findScriptById(int scriptId) const;
    [[nodiscard]] HandlerLocation findHandler(std::string_view handlerName) const;
    [[nodiscard]] HandlerLocation findHandlerInScript(int scriptId, std::string_view handlerName) const;

private:
    std::vector<ScriptSource> scripts_;
};

struct ScriptFilterResult {
    std::vector<ScriptItem> items;
    int selectedIndex{-1};
};

struct HandlerFilterResult {
    std::vector<HandlerItem> items;
    int selectedIndex{-1};
};

class ScriptBrowserModel {
public:
    void clear();
    void setSources(std::vector<ScriptSource> sources);
    void setDirectorFile(std::shared_ptr<DirectorFile> file,
                         player::cast::CastLibManager* castLibManager = nullptr);

    [[nodiscard]] const std::vector<ScriptSource>& sources() const;
    [[nodiscard]] const std::vector<ScriptItem>& allScriptItems() const;
    [[nodiscard]] const std::vector<ScriptItem>& scriptItems() const;
    [[nodiscard]] const std::vector<HandlerItem>& allHandlerItems() const;
    [[nodiscard]] const std::vector<HandlerItem>& handlerItems() const;
    [[nodiscard]] const ScriptItem* selectedScript() const;
    [[nodiscard]] const HandlerItem* selectedHandler() const;
    [[nodiscard]] int selectedScriptIndex() const;
    [[nodiscard]] int selectedHandlerIndex() const;

    [[nodiscard]] ScriptFilterResult filterScripts(std::string_view filter, int currentScriptId = -1);
    [[nodiscard]] HandlerFilterResult filterHandlers(std::string_view filter, std::string_view currentHandlerName = {});
    [[nodiscard]] bool selectScriptById(int scriptId);
    [[nodiscard]] bool selectScript(const std::shared_ptr<chunks::ScriptChunk>& script);
    [[nodiscard]] bool selectHandler(std::string_view handlerName);
    [[nodiscard]] HandlerNavigator navigator() const;

private:
    void rebuildScriptItems();
    void loadHandlersForSelectedScript(std::string_view preferredHandlerName = {});
    [[nodiscard]] const ScriptSource* sourceForScript(const std::shared_ptr<chunks::ScriptChunk>& script) const;

    std::vector<ScriptSource> sources_;
    std::vector<ScriptItem> allScriptItems_;
    std::vector<ScriptItem> scriptItems_;
    std::vector<HandlerItem> allHandlerItems_;
    std::vector<HandlerItem> handlerItems_;
    int selectedScriptIndex_{-1};
    int selectedHandlerIndex_{-1};
};

struct BytecodeListBuildOptions {
    bool showLingoView{false};
    const player::debug::BreakpointManager* breakpoints{nullptr};
    const HandlerNavigator* navigator{nullptr};
};

class BytecodeListModel {
public:
    void clear();
    void loadHandler(std::shared_ptr<chunks::ScriptChunk> script,
                     chunks::ScriptChunk::Handler handler,
                     std::shared_ptr<chunks::ScriptNamesChunk> names,
                     const BytecodeListBuildOptions& options = {});
    [[nodiscard]] int highlightCurrentInstruction(int bytecodeIndex);
    void refreshBreakpointMarkers(const player::debug::BreakpointManager* breakpoints);

    [[nodiscard]] const std::vector<InstructionDisplayItem>& items() const;
    [[nodiscard]] bool showLingoView() const;
    [[nodiscard]] int currentScriptId() const;
    [[nodiscard]] const std::string& currentHandlerName() const;
    [[nodiscard]] int currentInstructionIndex() const;
    [[nodiscard]] int selectedRow() const;

private:
    void loadBytecodeView(const player::debug::BreakpointManager* breakpoints,
                          const HandlerNavigator* navigator);
    void loadLingoView(const player::debug::BreakpointManager* breakpoints);

    std::vector<InstructionDisplayItem> items_;
    bool showLingoView_{false};
    std::shared_ptr<chunks::ScriptChunk> currentScript_;
    chunks::ScriptChunk::Handler currentHandler_{};
    std::shared_ptr<chunks::ScriptNamesChunk> currentNames_;
    bool hasCurrentHandler_{false};
    int currentScriptId_{-1};
    std::string currentHandlerName_;
    int currentInstructionIndex_{-1};
    int selectedRow_{-1};
};

[[nodiscard]] std::string scriptDisplayName(const std::shared_ptr<DirectorFile>& file,
                                            const std::shared_ptr<chunks::ScriptChunk>& script);
[[nodiscard]] std::string sourceDisplayName(std::string_view sourcePath, std::string fallback);

} // namespace libreshockwave::editor::debug
