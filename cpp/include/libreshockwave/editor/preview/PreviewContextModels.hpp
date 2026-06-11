#pragma once

#include <cstddef>
#include <string>

#include "libreshockwave/editor/model/MemberNodeData.hpp"

namespace libreshockwave {
class DirectorFile;
} // namespace libreshockwave

namespace libreshockwave::editor::preview {

struct PreviewTextState {
    std::string statusText;
    std::string detailsText;
    std::size_t detailsCaretPosition{0};

    friend bool operator==(const PreviewTextState&, const PreviewTextState&) = default;
};

class PreviewContextModel {
public:
    PreviewContextModel(DirectorFile* dirFile, model::MemberNodeData memberData);

    [[nodiscard]] DirectorFile* dirFile() const;
    [[nodiscard]] const model::MemberNodeData& memberData() const;
    [[nodiscard]] const std::string& statusText() const;
    [[nodiscard]] const std::string& detailsText() const;
    [[nodiscard]] std::size_t detailsCaretPosition() const;
    [[nodiscard]] PreviewTextState textState() const;

    void setStatus(std::string text);
    void setDetailsText(std::string text);
    void setDetailsCaretPosition(std::size_t position);

private:
    DirectorFile* dirFile_{nullptr};
    model::MemberNodeData memberData_;
    PreviewTextState textState_;
};

} // namespace libreshockwave::editor::preview
