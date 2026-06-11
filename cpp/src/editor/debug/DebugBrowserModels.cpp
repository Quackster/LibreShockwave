#include "libreshockwave/editor/debug/DebugBrowserModels.hpp"

#include <algorithm>
#include <cctype>
#include <set>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/format/ScriptFormatUtils.hpp"
#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lingo/decompiler/LingoDecompiler.hpp"
#include "libreshockwave/lingo/vm/trace/InstructionAnnotator.hpp"
#include "libreshockwave/player/cast/CastLibManager.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::editor::debug {
namespace {

[[nodiscard]] std::string trim(std::string_view value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

[[nodiscard]] bool sameScript(const std::shared_ptr<chunks::ScriptChunk>& left,
                              const std::shared_ptr<chunks::ScriptChunk>& right) {
    if (left == right) {
        return true;
    }
    return left && right && left->id().value() == right->id().value();
}

[[nodiscard]] std::shared_ptr<chunks::ScriptNamesChunk> namesForScript(
    const std::shared_ptr<DirectorFile>& file,
    const std::shared_ptr<chunks::ScriptChunk>& script) {
    return file ? file->getScriptNamesForScript(script) : nullptr;
}

[[nodiscard]] ScriptSource makeSource(const std::shared_ptr<DirectorFile>& file,
                                      std::shared_ptr<chunks::ScriptChunk> script,
                                      std::string sourceName,
                                      int loadOrder) {
    auto names = namesForScript(file, script);
    const auto displayName = scriptDisplayName(file, script);
    return ScriptSource{
        std::move(script),
        std::move(names),
        displayName,
        std::move(sourceName),
        loadOrder,
    };
}

[[nodiscard]] std::optional<player::debug::Breakpoint> breakpointFor(
    const player::debug::BreakpointManager* breakpoints,
    int scriptId,
    const std::string& handlerName,
    int offset) {
    if (breakpoints == nullptr || scriptId < 0 || handlerName.empty() || offset < 0) {
        return std::nullopt;
    }
    return breakpoints->getBreakpoint(scriptId, handlerName, offset);
}

} // namespace

ScriptItem ScriptSource::toScriptItem() const {
    return ScriptItem(script, displayName, sourceName, loadOrder);
}

HandlerItem ScriptSource::toHandlerItem(const chunks::ScriptChunk::Handler& handler) const {
    return HandlerItem::fromScript(script, handler, names.get());
}

bool HandlerLocation::found() const {
    return script != nullptr && hasHandler;
}

std::string HandlerLocation::handlerName() const {
    return found() ? script->getHandlerName(handler, names.get()) : std::string{};
}

int HandlerLocation::scriptId() const {
    return found() ? script->id().value() : -1;
}

HandlerNavigator::HandlerNavigator(std::vector<ScriptSource> scripts)
    : scripts_(std::move(scripts)) {}

void HandlerNavigator::setScripts(std::vector<ScriptSource> scripts) {
    scripts_ = std::move(scripts);
}

const std::vector<ScriptSource>& HandlerNavigator::allScripts() const {
    return scripts_;
}

std::optional<ScriptSource> HandlerNavigator::findSourceById(int scriptId) const {
    for (const auto& source : scripts_) {
        if (source.script && source.script->id().value() == scriptId) {
            return source;
        }
    }
    return std::nullopt;
}

std::shared_ptr<chunks::ScriptChunk> HandlerNavigator::findScriptById(int scriptId) const {
    const auto source = findSourceById(scriptId);
    return source.has_value() ? source->script : nullptr;
}

HandlerLocation HandlerNavigator::findHandler(std::string_view handlerName) const {
    const auto name = trim(handlerName);
    if (name.empty()) {
        return {};
    }

    for (const auto& source : scripts_) {
        if (!source.script) {
            continue;
        }
        auto handler = source.script->findHandler(name, source.names.get());
        if (handler.has_value()) {
            return HandlerLocation{source.script, *handler, source.names, true};
        }
    }
    return {};
}

HandlerLocation HandlerNavigator::findHandlerInScript(int scriptId, std::string_view handlerName) const {
    const auto name = trim(handlerName);
    if (name.empty()) {
        return {};
    }

    const auto source = findSourceById(scriptId);
    if (!source.has_value() || !source->script) {
        return {};
    }
    auto handler = source->script->findHandler(name, source->names.get());
    if (!handler.has_value()) {
        return {};
    }
    return HandlerLocation{source->script, *handler, source->names, true};
}

void ScriptBrowserModel::clear() {
    sources_.clear();
    allScriptItems_.clear();
    scriptItems_.clear();
    allHandlerItems_.clear();
    handlerItems_.clear();
    selectedScriptIndex_ = -1;
    selectedHandlerIndex_ = -1;
}

void ScriptBrowserModel::setSources(std::vector<ScriptSource> sources) {
    clear();
    sources_ = std::move(sources);
    rebuildScriptItems();
}

void ScriptBrowserModel::setDirectorFile(std::shared_ptr<DirectorFile> file,
                                         player::cast::CastLibManager* castLibManager) {
    clear();
    if (!file) {
        return;
    }

    std::set<int> seenScriptIds;
    int loadOrder = 0;
    const auto mainSourceName = sourceDisplayName(file->basePath(), "Main");
    for (const auto& script : file->scripts()) {
        if (!script || !seenScriptIds.insert(script->id().value()).second) {
            continue;
        }
        sources_.push_back(makeSource(file, script, mainSourceName, loadOrder++));
    }

    if (castLibManager != nullptr) {
        for (const auto& [castNumber, castLib] : castLibManager->castLibs()) {
            (void)castNumber;
            if (!castLib) {
                continue;
            }
            std::string fallback = castLib->name();
            if (fallback.empty()) {
                fallback = "Cast " + std::to_string(castLib->number());
            }
            const auto sourceName = sourceDisplayName(castLib->fileName(), fallback);
            auto sourceFile = castLib->sourceFile();
            for (const auto& script : castLib->allScripts()) {
                if (!script || !seenScriptIds.insert(script->id().value()).second) {
                    continue;
                }
                auto names = sourceFile ? sourceFile->getScriptNamesForScript(script) : castLib->scriptNames();
                sources_.push_back(ScriptSource{
                    script,
                    std::move(names),
                    scriptDisplayName(sourceFile, script),
                    sourceName,
                    loadOrder++,
                });
            }
        }
    }

    rebuildScriptItems();
}

const std::vector<ScriptSource>& ScriptBrowserModel::sources() const {
    return sources_;
}

const std::vector<ScriptItem>& ScriptBrowserModel::allScriptItems() const {
    return allScriptItems_;
}

const std::vector<ScriptItem>& ScriptBrowserModel::scriptItems() const {
    return scriptItems_;
}

const std::vector<HandlerItem>& ScriptBrowserModel::allHandlerItems() const {
    return allHandlerItems_;
}

const std::vector<HandlerItem>& ScriptBrowserModel::handlerItems() const {
    return handlerItems_;
}

const ScriptItem* ScriptBrowserModel::selectedScript() const {
    if (selectedScriptIndex_ < 0 || selectedScriptIndex_ >= static_cast<int>(scriptItems_.size())) {
        return nullptr;
    }
    return &scriptItems_[static_cast<std::size_t>(selectedScriptIndex_)];
}

const HandlerItem* ScriptBrowserModel::selectedHandler() const {
    if (selectedHandlerIndex_ < 0 || selectedHandlerIndex_ >= static_cast<int>(handlerItems_.size())) {
        return nullptr;
    }
    return &handlerItems_[static_cast<std::size_t>(selectedHandlerIndex_)];
}

int ScriptBrowserModel::selectedScriptIndex() const {
    return selectedScriptIndex_;
}

int ScriptBrowserModel::selectedHandlerIndex() const {
    return selectedHandlerIndex_;
}

ScriptFilterResult ScriptBrowserModel::filterScripts(std::string_view filter, int currentScriptId) {
    const auto normalizedFilter = trim(filter);
    if (currentScriptId < 0) {
        if (const auto* selected = selectedScript(); selected != nullptr && selected->script) {
            currentScriptId = selected->script->id().value();
        }
    }

    scriptItems_.clear();
    int firstMatch = -1;
    int selectedMatch = -1;
    for (const auto& item : allScriptItems_) {
        if (!item.matchesFilter(normalizedFilter)) {
            continue;
        }
        if (firstMatch < 0) {
            firstMatch = static_cast<int>(scriptItems_.size());
        }
        if (item.script && item.script->id().value() == currentScriptId) {
            selectedMatch = static_cast<int>(scriptItems_.size());
        }
        scriptItems_.push_back(item);
    }

    selectedScriptIndex_ = selectedMatch >= 0 ? selectedMatch : firstMatch;
    if (selectedScriptIndex_ >= 0) {
        loadHandlersForSelectedScript();
    } else {
        allHandlerItems_.clear();
        handlerItems_.clear();
        selectedHandlerIndex_ = -1;
    }

    return ScriptFilterResult{scriptItems_, selectedScriptIndex_};
}

HandlerFilterResult ScriptBrowserModel::filterHandlers(std::string_view filter, std::string_view currentHandlerName) {
    const auto normalizedFilter = trim(filter);
    auto currentName = trim(currentHandlerName);
    if (currentName.empty()) {
        if (const auto* selected = selectedHandler(); selected != nullptr) {
            currentName = selected->displayName;
        }
    }

    handlerItems_.clear();
    int firstMatch = -1;
    int selectedMatch = -1;
    for (const auto& item : allHandlerItems_) {
        if (!item.matchesFilter(normalizedFilter)) {
            continue;
        }
        if (firstMatch < 0) {
            firstMatch = static_cast<int>(handlerItems_.size());
        }
        if (!currentName.empty() && item.displayName == currentName) {
            selectedMatch = static_cast<int>(handlerItems_.size());
        }
        handlerItems_.push_back(item);
    }

    selectedHandlerIndex_ = selectedMatch >= 0 ? selectedMatch : firstMatch;
    return HandlerFilterResult{handlerItems_, selectedHandlerIndex_};
}

bool ScriptBrowserModel::selectScriptById(int scriptId) {
    for (int index = 0; index < static_cast<int>(scriptItems_.size()); ++index) {
        const auto& item = scriptItems_[static_cast<std::size_t>(index)];
        if (item.script && item.script->id().value() == scriptId) {
            selectedScriptIndex_ = index;
            loadHandlersForSelectedScript();
            return true;
        }
    }
    return false;
}

bool ScriptBrowserModel::selectScript(const std::shared_ptr<chunks::ScriptChunk>& script) {
    if (!script) {
        return false;
    }
    for (int index = 0; index < static_cast<int>(scriptItems_.size()); ++index) {
        if (sameScript(scriptItems_[static_cast<std::size_t>(index)].script, script)) {
            selectedScriptIndex_ = index;
            loadHandlersForSelectedScript();
            return true;
        }
    }
    return false;
}

bool ScriptBrowserModel::selectHandler(std::string_view handlerName) {
    const auto name = trim(handlerName);
    for (int index = 0; index < static_cast<int>(allHandlerItems_.size()); ++index) {
        if (allHandlerItems_[static_cast<std::size_t>(index)].displayName == name) {
            handlerItems_ = allHandlerItems_;
            selectedHandlerIndex_ = index;
            return true;
        }
    }
    return false;
}

HandlerNavigator ScriptBrowserModel::navigator() const {
    return HandlerNavigator(sources_);
}

void ScriptBrowserModel::rebuildScriptItems() {
    allScriptItems_.clear();
    allScriptItems_.reserve(sources_.size());
    for (const auto& source : sources_) {
        if (source.script && !source.script->handlers().empty()) {
            allScriptItems_.push_back(source.toScriptItem());
        }
    }
    scriptItems_ = allScriptItems_;
    selectedScriptIndex_ = scriptItems_.empty() ? -1 : 0;
    if (selectedScriptIndex_ >= 0) {
        loadHandlersForSelectedScript();
    }
}

void ScriptBrowserModel::loadHandlersForSelectedScript(std::string_view preferredHandlerName) {
    allHandlerItems_.clear();
    handlerItems_.clear();
    selectedHandlerIndex_ = -1;
    const auto* scriptItem = selectedScript();
    if (scriptItem == nullptr || !scriptItem->script) {
        return;
    }
    const auto* source = sourceForScript(scriptItem->script);
    if (source == nullptr) {
        return;
    }

    const auto preferredName = trim(preferredHandlerName);
    int preferredIndex = -1;
    for (const auto& handler : scriptItem->script->handlers()) {
        auto item = source->toHandlerItem(handler);
        if (!preferredName.empty() && item.displayName == preferredName) {
            preferredIndex = static_cast<int>(allHandlerItems_.size());
        }
        allHandlerItems_.push_back(std::move(item));
    }
    handlerItems_ = allHandlerItems_;
    if (preferredIndex >= 0) {
        selectedHandlerIndex_ = preferredIndex;
    } else if (!handlerItems_.empty()) {
        selectedHandlerIndex_ = 0;
    }
}

const ScriptSource* ScriptBrowserModel::sourceForScript(const std::shared_ptr<chunks::ScriptChunk>& script) const {
    if (!script) {
        return nullptr;
    }
    for (const auto& source : sources_) {
        if (sameScript(source.script, script)) {
            return &source;
        }
    }
    return nullptr;
}

void BytecodeListModel::clear() {
    items_.clear();
    showLingoView_ = false;
    currentScript_.reset();
    currentHandler_ = {};
    currentNames_.reset();
    hasCurrentHandler_ = false;
    currentScriptId_ = -1;
    currentHandlerName_.clear();
    currentInstructionIndex_ = -1;
    selectedRow_ = -1;
}

void BytecodeListModel::loadHandler(std::shared_ptr<chunks::ScriptChunk> script,
                                    chunks::ScriptChunk::Handler handler,
                                    std::shared_ptr<chunks::ScriptNamesChunk> names,
                                    const BytecodeListBuildOptions& options) {
    clear();
    showLingoView_ = options.showLingoView;
    currentScript_ = std::move(script);
    currentHandler_ = std::move(handler);
    currentNames_ = std::move(names);
    hasCurrentHandler_ = currentScript_ != nullptr;
    if (!hasCurrentHandler_) {
        return;
    }

    currentScriptId_ = currentScript_->id().value();
    currentHandlerName_ = currentScript_->getHandlerName(currentHandler_, currentNames_.get());
    if (showLingoView_) {
        loadLingoView(options.breakpoints);
    } else {
        loadBytecodeView(options.breakpoints, options.navigator);
    }
}

int BytecodeListModel::highlightCurrentInstruction(int bytecodeIndex) {
    selectedRow_ = -1;
    if (!hasCurrentHandler_) {
        currentInstructionIndex_ = -1;
        return selectedRow_;
    }

    int targetOffset = -1;
    if (showLingoView_ &&
        bytecodeIndex >= 0 &&
        bytecodeIndex < static_cast<int>(currentHandler_.instructions.size())) {
        targetOffset = currentHandler_.instructions[static_cast<std::size_t>(bytecodeIndex)].offset;
    }

    for (int index = 0; index < static_cast<int>(items_.size()); ++index) {
        auto& item = items_[static_cast<std::size_t>(index)];
        const bool current = showLingoView_
            ? item.offset >= 0 && item.offset == targetOffset
            : item.index == bytecodeIndex;
        item.isCurrent = current;
        if (current && selectedRow_ < 0) {
            selectedRow_ = index;
        }
    }

    currentInstructionIndex_ = selectedRow_ >= 0 ? bytecodeIndex : -1;
    return selectedRow_;
}

void BytecodeListModel::refreshBreakpointMarkers(const player::debug::BreakpointManager* breakpoints) {
    if (!hasCurrentHandler_) {
        return;
    }
    for (auto& item : items_) {
        if (item.offset < 0) {
            continue;
        }
        item.breakpoint = breakpointFor(breakpoints, currentScriptId_, currentHandlerName_, item.offset);
        item.hasBreakpoint = item.breakpoint.has_value();
    }
}

const std::vector<InstructionDisplayItem>& BytecodeListModel::items() const {
    return items_;
}

bool BytecodeListModel::showLingoView() const {
    return showLingoView_;
}

int BytecodeListModel::currentScriptId() const {
    return currentScriptId_;
}

const std::string& BytecodeListModel::currentHandlerName() const {
    return currentHandlerName_;
}

int BytecodeListModel::currentInstructionIndex() const {
    return currentInstructionIndex_;
}

int BytecodeListModel::selectedRow() const {
    return selectedRow_;
}

void BytecodeListModel::loadBytecodeView(const player::debug::BreakpointManager* breakpoints,
                                         const HandlerNavigator* navigator) {
    items_.reserve(currentHandler_.instructions.size());
    for (int index = 0; index < static_cast<int>(currentHandler_.instructions.size()); ++index) {
        const auto& instruction = currentHandler_.instructions[static_cast<std::size_t>(index)];
        auto breakpoint = breakpointFor(breakpoints, currentScriptId_, currentHandlerName_, instruction.offset);
        InstructionDisplayItem item;
        item.offset = instruction.offset;
        item.index = index;
        item.opcode = std::string(lingo::mnemonic(instruction.opcode));
        item.argument = instruction.argument;
        item.annotation = lingo::vm::trace::InstructionAnnotator::annotate(
            *currentScript_,
            &currentHandler_,
            instruction,
            currentNames_.get(),
            true);
        item.breakpoint = breakpoint;
        item.hasBreakpoint = breakpoint.has_value();

        if (item.isCallInstruction() && navigator != nullptr) {
            const auto targetName = item.getCallTargetName();
            if (targetName.has_value()) {
                item.navigable = navigator->findHandler(*targetName).found();
            }
        }
        items_.push_back(std::move(item));
    }
}

void BytecodeListModel::loadLingoView(const player::debug::BreakpointManager* breakpoints) {
    lingo::decompiler::LingoDecompiler decompiler;
    const auto result = decompiler.decompileHandlerWithMapping(currentHandler_, *currentScript_, currentNames_.get());
    items_.reserve(result.lines.size());
    for (int index = 0; index < static_cast<int>(result.lines.size()); ++index) {
        const auto& line = result.lines[static_cast<std::size_t>(index)];
        auto breakpoint = breakpointFor(breakpoints, currentScriptId_, currentHandlerName_, line.bytecodeOffset);
        InstructionDisplayItem item;
        item.offset = line.bytecodeOffset;
        item.index = index;
        item.annotation = line.text;
        item.breakpoint = breakpoint;
        item.hasBreakpoint = breakpoint.has_value();
        item.lingoLine = true;
        items_.push_back(std::move(item));
    }
}

std::string scriptDisplayName(const std::shared_ptr<DirectorFile>& file,
                              const std::shared_ptr<chunks::ScriptChunk>& script) {
    if (!script) {
        return {};
    }

    const auto typeName = format::getScriptTypeName(script->scriptType());
    if (file) {
        const auto name = file->getScriptName(script);
        if (!name.empty()) {
            return "\"" + name + "\" (" + typeName + ")";
        }
    }
    return typeName + " #" + std::to_string(script->id().value());
}

std::string sourceDisplayName(std::string_view sourcePath, std::string fallback) {
    auto fileName = util::getFileName(sourcePath);
    if (!fileName.empty()) {
        return fileName;
    }
    return std::move(fallback);
}

} // namespace libreshockwave::editor::debug
