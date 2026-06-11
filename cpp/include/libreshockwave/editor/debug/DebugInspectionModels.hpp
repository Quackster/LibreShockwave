#pragma once

#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/debug/WatchExpression.hpp"
#include "libreshockwave/player/debug/DebugSnapshot.hpp"

namespace libreshockwave::chunks {
class ScriptNamesChunk;
} // namespace libreshockwave::chunks

namespace libreshockwave::editor::debug {

struct DebugSize {
    int width{};
    int height{};

    friend bool operator==(const DebugSize&, const DebugSize&) = default;
};

struct DebugTextAreaPresentation {
    std::string fontFamily{"Monospaced"};
    int fontSize{};
    bool editable{false};
    bool lineWrap{false};
    bool wrapStyleWord{false};

    friend bool operator==(const DebugTextAreaPresentation&, const DebugTextAreaPresentation&) = default;
};

struct DatumDetailsView {
    std::string title;
    DebugSize size;
    DebugTextAreaPresentation textArea;
    std::string body;
    std::string closeButtonLabel;
    bool modal{false};

    friend bool operator==(const DatumDetailsView&, const DatumDetailsView&) = default;
};

struct DetailedStackTabView {
    std::string title;
    DebugTextAreaPresentation textArea;
    std::string body;
    bool scrollToEnd{false};

    friend bool operator==(const DetailedStackTabView&, const DetailedStackTabView&) = default;
};

struct DetailedStackView {
    std::string title;
    DebugSize size;
    std::string statusText;
    std::vector<DetailedStackTabView> tabs;

    friend bool operator==(const DetailedStackView&, const DetailedStackView&) = default;
};

enum class DebugInspectionTable {
    Stack,
    Locals,
    Globals,
    Watches,
    Timeouts,
    MovieProperties
};

struct DebugTablePresentation {
    DebugInspectionTable table{DebugInspectionTable::Stack};
    std::string title;
    std::vector<std::string> columns;
    std::vector<int> preferredColumnWidths;
    std::string fontFamily{"Monospaced"};
    int fontSize{11};
    DebugSize preferredScrollSize;
    bool doubleClickOpensDatum{false};

    friend bool operator==(const DebugTablePresentation&, const DebugTablePresentation&) = default;
};

struct DebugObjectSectionView {
    std::string title;
    DebugTablePresentation table;

    friend bool operator==(const DebugObjectSectionView&, const DebugObjectSectionView&) = default;
};

struct DebugObjectsPanelView {
    std::string rootLayout;
    std::string sectionAxis;
    bool outerScrollPane{false};
    std::vector<DebugObjectSectionView> sections;

    friend bool operator==(const DebugObjectsPanelView&, const DebugObjectsPanelView&) = default;
};

struct DebugStateTabsView {
    std::string tabPlacement;
    std::vector<std::string> tabTitles;
    int objectsTabIndex{};
    std::vector<DebugTablePresentation> primaryTables;
    std::vector<DebugObjectSectionView> objectSections;

    friend bool operator==(const DebugStateTabsView&, const DebugStateTabsView&) = default;
};

enum class WatchPanelAction {
    Add,
    Remove,
    Clear
};

struct WatchPanelButton {
    WatchPanelAction action{WatchPanelAction::Add};
    std::string label;
    std::string tooltip;
    bool enabled{true};

    friend bool operator==(const WatchPanelButton&, const WatchPanelButton&) = default;
};

struct WatchPanelView {
    DebugTablePresentation table;
    std::vector<WatchPanelButton> buttons;
    std::string addDialogTitle;
    std::string addDialogPrompt;
    std::string editDialogPrompt;

    friend bool operator==(const WatchPanelView&, const WatchPanelView&) = default;
};

enum class HandlerDetailsTab {
    Overview,
    Bytecode,
    Literals,
    Properties,
    Globals
};

struct HandlerDetailsBytecodeRow {
    int offset{};
    std::string opcode;
    std::optional<int> argument;
    std::string annotation;
    std::string displayText;

    friend bool operator==(const HandlerDetailsBytecodeRow&, const HandlerDetailsBytecodeRow&) = default;
};

struct HandlerDetailsLiteralRow {
    int index{};
    std::string type;
    std::string value;

    friend bool operator==(const HandlerDetailsLiteralRow&, const HandlerDetailsLiteralRow&) = default;
};

struct HandlerDetailsNameRow {
    int index{};
    std::string name;
    std::string displayText;

    friend bool operator==(const HandlerDetailsNameRow&, const HandlerDetailsNameRow&) = default;
};

struct HandlerDetailsView {
    std::string title;
    DebugSize size;
    std::vector<HandlerDetailsTab> tabs;
    std::vector<std::string> tabTitles;
    std::string overviewHtml;
    std::vector<HandlerDetailsBytecodeRow> bytecodeRows;
    std::vector<HandlerDetailsLiteralRow> literalRows;
    std::vector<HandlerDetailsNameRow> propertyRows;
    std::vector<HandlerDetailsNameRow> globalRows;
    std::string closeButtonLabel;
    bool modal{false};

    friend bool operator==(const HandlerDetailsView&, const HandlerDetailsView&) = default;
};

class DebugInspectionModels {
public:
    [[nodiscard]] static DatumDetailsView datumDetailsView(const lingo::Datum& datum, std::string_view title);

    [[nodiscard]] static DetailedStackView detailedStackInitialView();
    [[nodiscard]] static DetailedStackView detailedStackRunningView();
    [[nodiscard]] static DetailedStackView detailedStackView(const player::debug::DebugSnapshot& snapshot);

    [[nodiscard]] static std::string formatCallStack(const std::vector<player::debug::CallFrame>& callStack);
    [[nodiscard]] static std::string formatStackDetailed(const std::vector<lingo::Datum>& stack);
    [[nodiscard]] static std::string formatArguments(const std::vector<lingo::Datum>& arguments);
    [[nodiscard]] static std::string formatReceiver(const std::optional<lingo::Datum>& receiver);

    [[nodiscard]] static DebugStateTabsView stateTabsView();
    [[nodiscard]] static DebugObjectsPanelView objectsPanelView();
    [[nodiscard]] static bool isObjectsTabSelected(int selectedTabIndex);
    [[nodiscard]] static WatchPanelView watchesPanelView(bool hasSelection);
    [[nodiscard]] static std::optional<std::string> sanitizedWatchExpression(std::string_view expression);
    [[nodiscard]] static std::optional<std::string> selectedWatchId(
        const std::vector<player::debug::WatchExpression>& watches,
        int selectedRow);
    [[nodiscard]] static std::optional<std::string> datumDetailsTitleFor(DebugInspectionTable table,
                                                                         std::string_view name,
                                                                         int row);
    [[nodiscard]] static HandlerDetailsView handlerDetailsView(
        const chunks::ScriptChunk& script,
        const chunks::ScriptChunk::Handler& handler,
        const chunks::ScriptNamesChunk* names = nullptr,
        std::string_view scriptDisplayName = {});
    [[nodiscard]] static std::optional<HandlerDetailsView> findHandlerDetailsView(
        const std::vector<std::shared_ptr<chunks::ScriptChunk>>& scripts,
        const chunks::ScriptNamesChunk* names,
        std::string_view handlerName);
};

} // namespace libreshockwave::editor::debug
