#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/debug/DebugSnapshot.hpp"

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
};

} // namespace libreshockwave::editor::debug
