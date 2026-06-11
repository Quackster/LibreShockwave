#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::castbrowser {

enum class CastViewMode {
    Grid,
    List
};

enum class CastContextAction {
    Export,
    ExportSelected,
    SelectAll,
    CopyName
};

struct CastLibDescriptor {
    int castLibNumber{};
    std::string name;
    bool external{false};
    bool loaded{true};

    friend bool operator==(const CastLibDescriptor&, const CastLibDescriptor&) = default;
};

struct CastLibEntry {
    int castLibNumber{};
    std::string label;
    bool external{false};
    bool loaded{true};

    friend bool operator==(const CastLibEntry&, const CastLibEntry&) = default;
};

struct CastGridCell {
    int memberNum{};
    int memberIndex{};
    std::string nameLabel;
    std::string typeAbbreviation;
    bool selected{false};
    bool bitmapThumbnailPending{false};

    friend bool operator==(const CastGridCell&, const CastGridCell&) = default;
};

struct CastListRow {
    int memberNum{};
    std::string displayText;
    bool selected{false};

    friend bool operator==(const CastListRow&, const CastListRow&) = default;
};

struct CastContextActionState {
    CastContextAction action{CastContextAction::Export};
    std::string label;
    bool enabled{true};

    friend bool operator==(const CastContextActionState&, const CastContextActionState&) = default;
};

struct ThumbnailPlacement {
    int thumbSize{};
    int scaledWidth{};
    int scaledHeight{};
    int offsetX{};
    int offsetY{};
    bool valid{false};

    friend bool operator==(const ThumbnailPlacement&, const ThumbnailPlacement&) = default;
};

struct CastGridInsets {
    int top{};
    int left{};
    int bottom{};
    int right{};

    friend bool operator==(const CastGridInsets&, const CastGridInsets&) = default;
};

struct CastGridCellBounds {
    int memberIndex{};
    int x{};
    int y{};
    int width{};
    int height{};

    friend bool operator==(const CastGridCellBounds&, const CastGridCellBounds&) = default;
};

struct CastGridLayout {
    int width{};
    int height{};
    std::vector<CastGridCellBounds> cells;
    bool tracksViewportWidth{true};
    bool tracksViewportHeight{false};
    int scrollUnitIncrement{};

    friend bool operator==(const CastGridLayout&, const CastGridLayout&) = default;
};

class CastBrowserModel {
public:
    static constexpr int CELL_WIDTH = 72;
    static constexpr int CELL_HEIGHT = 80;
    static constexpr int CELL_GAP = 4;
    static constexpr int DEFAULT_THUMB_SIZE = 48;
    static constexpr int DEFAULT_GRID_WIDTH = 400;

    [[nodiscard]] static std::vector<std::string> typeFilterItems();
    [[nodiscard]] static std::vector<CastLibEntry> buildCastLibEntries(
        const std::vector<CastLibDescriptor>& descriptors);
    [[nodiscard]] static std::string typeAbbreviation(::libreshockwave::cast::MemberType type);
    [[nodiscard]] static std::optional<std::string> editorPanelIdFor(::libreshockwave::cast::MemberType type);
    [[nodiscard]] static ThumbnailPlacement thumbnailPlacement(int sourceWidth,
                                                               int sourceHeight,
                                                               int thumbSize = DEFAULT_THUMB_SIZE);
    [[nodiscard]] static CastGridLayout gridLayout(std::size_t cellCount,
                                                   int viewportWidth,
                                                   CastGridInsets insets = {});
    [[nodiscard]] static int scrollBlockIncrement(int visibleWidth, int visibleHeight, bool vertical);
    [[nodiscard]] static bool shouldPreserveListSelection(bool rightButton,
                                                          bool popupTrigger,
                                                          bool pointerOnSelectedRow,
                                                          int selectedRowCount);

    [[nodiscard]] CastViewMode viewMode() const;
    void setViewMode(CastViewMode mode);

    [[nodiscard]] const std::vector<CastLibEntry>& castLibraries() const;
    void setCastLibraries(std::vector<CastLibEntry> entries);
    [[nodiscard]] int selectedCastLibNumber() const;
    bool selectCastLibNumber(int castLibNumber);

    void setMembers(std::vector<model::CastMemberInfo> members);
    void resetForNoMovie();

    [[nodiscard]] const std::vector<model::CastMemberInfo>& members() const;
    [[nodiscard]] const std::vector<model::CastMemberInfo>& filteredMembers() const;

    [[nodiscard]] std::string_view searchText() const;
    void setSearchText(std::string text);
    [[nodiscard]] std::string_view typeFilter() const;
    void setTypeFilter(std::string filter);

    [[nodiscard]] std::string statusText() const;
    [[nodiscard]] std::vector<CastGridCell> gridCells() const;
    [[nodiscard]] std::vector<CastListRow> listRows() const;
    [[nodiscard]] std::vector<int> bitmapThumbnailRequests() const;

    [[nodiscard]] const std::vector<int>& selectedMemberNums() const;
    [[nodiscard]] bool isSelected(int memberNum) const;
    void clearSelection();
    bool selectSingleMember(int memberNum);
    bool toggleSelectedMember(int memberNum);
    void selectAllFilteredMembers();
    void syncSelectionFromRowIndices(const std::vector<int>& selectedRows);

    [[nodiscard]] std::vector<CastContextActionState> contextActions(std::optional<int> memberNum) const;
    [[nodiscard]] std::vector<CastContextActionState> openContextForMember(std::optional<int> memberNum);
    [[nodiscard]] std::vector<model::CastMemberInfo> selectedMembersInFilterOrder() const;

private:
    [[nodiscard]] static std::string lowerAscii(std::string_view value);
    [[nodiscard]] static std::string trimAscii(std::string_view value);
    [[nodiscard]] static bool matchesTypeFilter(::libreshockwave::cast::MemberType type,
                                                std::string_view filter);
    [[nodiscard]] static std::string displayNameFor(const model::CastMemberInfo& info);
    [[nodiscard]] static std::string listDisplayFor(const model::CastMemberInfo& info);
    [[nodiscard]] const model::CastMemberInfo* findFilteredMember(int memberNum) const;
    [[nodiscard]] int selectedCastEntryIndex() const;
    void applyFilter();
    void retainVisibleSelection();

    CastViewMode viewMode_{CastViewMode::Grid};
    std::vector<CastLibEntry> castLibraries_{CastLibEntry{0, "Internal", false, true}};
    int selectedCastLibNumber_{0};
    std::vector<model::CastMemberInfo> members_;
    std::vector<model::CastMemberInfo> filteredMembers_;
    std::vector<int> selectedMemberNums_;
    std::string searchText_;
    std::string typeFilter_{"All Types"};
};

} // namespace libreshockwave::editor::castbrowser
