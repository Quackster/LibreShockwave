#include "libreshockwave/editor/preview/PreviewContextModels.hpp"

#include <utility>

namespace libreshockwave::editor::preview {

PreviewContextModel::PreviewContextModel(DirectorFile* dirFile, model::MemberNodeData memberData)
    : dirFile_(dirFile),
      memberData_(std::move(memberData)) {}

DirectorFile* PreviewContextModel::dirFile() const {
    return dirFile_;
}

const model::MemberNodeData& PreviewContextModel::memberData() const {
    return memberData_;
}

const std::string& PreviewContextModel::statusText() const {
    return textState_.statusText;
}

const std::string& PreviewContextModel::detailsText() const {
    return textState_.detailsText;
}

std::size_t PreviewContextModel::detailsCaretPosition() const {
    return textState_.detailsCaretPosition;
}

PreviewTextState PreviewContextModel::textState() const {
    return textState_;
}

void PreviewContextModel::setStatus(std::string text) {
    textState_.statusText = std::move(text);
}

void PreviewContextModel::setDetailsText(std::string text) {
    textState_.detailsText = std::move(text);
    textState_.detailsCaretPosition = 0;
}

void PreviewContextModel::setDetailsCaretPosition(std::size_t position) {
    textState_.detailsCaretPosition = position;
}

} // namespace libreshockwave::editor::preview
