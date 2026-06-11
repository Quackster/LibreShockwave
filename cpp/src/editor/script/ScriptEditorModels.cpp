#include "libreshockwave/editor/script/ScriptEditorModels.hpp"

#include <algorithm>
#include <utility>

namespace libreshockwave::editor::script {

ScriptStyle LingoSyntaxHighlighterModel::defaultStyle() {
    return ScriptStyle{COLOR_DEFAULT, false};
}

bool LingoSyntaxHighlighterModel::hasExplicitStyle(LingoTokenType type) {
    switch (type) {
        case LingoTokenType::Keyword:
        case LingoTokenType::Command:
        case LingoTokenType::Function:
        case LingoTokenType::Event:
        case LingoTokenType::String:
        case LingoTokenType::Comment:
        case LingoTokenType::Number:
        case LingoTokenType::Symbol:
            return true;
        case LingoTokenType::Identifier:
        case LingoTokenType::Operator:
        case LingoTokenType::Whitespace:
        case LingoTokenType::Newline:
            return false;
    }
    return false;
}

ScriptStyle LingoSyntaxHighlighterModel::styleForToken(LingoTokenType type) {
    switch (type) {
        case LingoTokenType::Keyword:
            return ScriptStyle{COLOR_KEYWORD, true};
        case LingoTokenType::Command:
            return ScriptStyle{COLOR_COMMAND, true};
        case LingoTokenType::Function:
            return ScriptStyle{COLOR_FUNCTION, false};
        case LingoTokenType::Event:
            return ScriptStyle{COLOR_EVENT, false};
        case LingoTokenType::String:
            return ScriptStyle{COLOR_STRING, false};
        case LingoTokenType::Comment:
            return ScriptStyle{COLOR_COMMENT, true};
        case LingoTokenType::Number:
            return ScriptStyle{COLOR_NUMBER, false};
        case LingoTokenType::Symbol:
            return ScriptStyle{COLOR_SYMBOL, false};
        case LingoTokenType::Identifier:
        case LingoTokenType::Operator:
        case LingoTokenType::Whitespace:
        case LingoTokenType::Newline:
            return defaultStyle();
    }
    return defaultStyle();
}

std::vector<ScriptStyleRange> LingoSyntaxHighlighterModel::highlight(std::string_view text) {
    std::vector<ScriptStyleRange> ranges;
    for (const auto& token : LingoTokenizer::tokenize(text)) {
        if (!hasExplicitStyle(token.type)) {
            continue;
        }
        ranges.push_back(ScriptStyleRange{
            token.start,
            token.end,
            token.type,
            styleForToken(token.type),
        });
    }
    return ranges;
}

HandlerDropdownModel::HandlerDropdownModel() {
    clearHandlers();
}

std::string_view HandlerDropdownModel::noHandlersLabel() {
    return "(No handlers)";
}

const std::vector<std::string>& HandlerDropdownModel::items() const {
    return items_;
}

int HandlerDropdownModel::selectedIndex() const {
    return selectedIndex_;
}

std::string HandlerDropdownModel::selectedItem() const {
    if (selectedIndex_ < 0 || selectedIndex_ >= static_cast<int>(items_.size())) {
        return {};
    }
    return items_[static_cast<std::size_t>(selectedIndex_)];
}

bool HandlerDropdownModel::hasHandlers() const {
    return !(items_.size() == 1 && items_.front() == noHandlersLabel());
}

void HandlerDropdownModel::setHandlers(const std::vector<std::string>& handlerNames) {
    items_.clear();
    if (handlerNames.empty()) {
        items_.push_back(std::string(noHandlersLabel()));
    } else {
        items_ = handlerNames;
    }
    selectedIndex_ = 0;
}

void HandlerDropdownModel::clearHandlers() {
    items_.clear();
    items_.push_back(std::string(noHandlersLabel()));
    selectedIndex_ = 0;
}

bool HandlerDropdownModel::selectHandler(std::string_view handlerName) {
    const auto found = std::find(items_.begin(), items_.end(), handlerName);
    if (found == items_.end()) {
        return false;
    }
    selectedIndex_ = static_cast<int>(std::distance(items_.begin(), found));
    return true;
}

LingoDocumentModel::LingoDocumentModel() {
    rehighlight();
}

LingoDocumentModel::LingoDocumentModel(std::string text)
    : text_(std::move(text)) {
    rehighlight();
}

const std::string& LingoDocumentModel::text() const {
    return text_;
}

const std::vector<ScriptStyleRange>& LingoDocumentModel::highlights() const {
    return highlights_;
}

std::size_t LingoDocumentModel::length() const {
    return text_.size();
}

bool LingoDocumentModel::isHighlighting() const {
    return highlighting_;
}

bool LingoDocumentModel::setText(std::string text) {
    text_ = std::move(text);
    rehighlight();
    return true;
}

bool LingoDocumentModel::insertString(std::size_t offset, std::string_view value) {
    if (offset > text_.size()) {
        return false;
    }
    text_.insert(offset, value);
    rehighlight();
    return true;
}

bool LingoDocumentModel::remove(std::size_t offset, std::size_t length) {
    if (offset > text_.size()) {
        return false;
    }
    if (offset + length > text_.size()) {
        return false;
    }
    text_.erase(offset, length);
    rehighlight();
    return true;
}

void LingoDocumentModel::rehighlight() {
    if (highlighting_) {
        return;
    }
    highlighting_ = true;
    highlights_ = LingoSyntaxHighlighterModel::highlight(text_);
    highlighting_ = false;
}

} // namespace libreshockwave::editor::script
