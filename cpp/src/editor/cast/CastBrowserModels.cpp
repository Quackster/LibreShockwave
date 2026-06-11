#include "libreshockwave/editor/cast/CastBrowserModels.hpp"

#include <algorithm>
#include <cctype>
#include <iterator>
#include <string>
#include <utility>

namespace libreshockwave::editor::castbrowser {
namespace {

constexpr std::uint32_t PLACEHOLDER_BACKGROUND = 0xFFF0F0F0U;
constexpr std::uint32_t PLACEHOLDER_BORDER = 0xFF808080U;
constexpr std::uint32_t PLACEHOLDER_TEXT = 0xFF404040U;
constexpr std::uint32_t BITMAP_BACKGROUND = 0xFFFFFFFFU;
constexpr std::uint32_t CHECKER_COLOR = 0xFFCCCCCCU;

bool containsInt(const std::vector<int>& values, int value) {
    return std::find(values.begin(), values.end(), value) != values.end();
}

} // namespace

std::vector<std::string> CastBrowserModel::typeFilterItems() {
    return {"All Types", "Bitmap", "Script", "Sound", "Text", "Button",
            "Shape",     "Film Loop", "Palette", "Field", "Transition"};
}

std::vector<CastLibEntry> CastBrowserModel::buildCastLibEntries(
    const std::vector<CastLibDescriptor>& descriptors) {
    if (descriptors.empty()) {
        return {CastLibEntry{0, "Internal", false, true}};
    }

    std::vector<CastLibDescriptor> sorted = descriptors;
    std::sort(sorted.begin(), sorted.end(), [](const CastLibDescriptor& left, const CastLibDescriptor& right) {
        return left.castLibNumber < right.castLibNumber;
    });

    std::vector<CastLibEntry> entries;
    entries.reserve(sorted.size());
    for (const auto& descriptor : sorted) {
        std::string label = descriptor.name.empty() ? "Cast " + std::to_string(descriptor.castLibNumber)
                                                    : descriptor.name;
        if (descriptor.external && !descriptor.loaded) {
            label += " (not loaded)";
        }
        entries.push_back(
            CastLibEntry{descriptor.castLibNumber, std::move(label), descriptor.external, descriptor.loaded});
    }
    return entries;
}

std::string CastBrowserModel::typeAbbreviation(::libreshockwave::cast::MemberType type) {
    using ::libreshockwave::cast::MemberType;
    switch (type) {
        case MemberType::Bitmap: return "Bmp";
        case MemberType::Script: return "Scr";
        case MemberType::Sound: return "Snd";
        case MemberType::Text: return "Txt";
        case MemberType::RichText: return "RTx";
        case MemberType::Button: return "Btn";
        case MemberType::Shape: return "Shp";
        case MemberType::FilmLoop: return "Flm";
        case MemberType::Palette: return "Pal";
        case MemberType::Transition: return "Trn";
        case MemberType::Font: return "Fnt";
        case MemberType::DigitalVideo: return "Vid";
        case MemberType::Shockwave3D: return "3D";
        case MemberType::Movie: return "Mov";
        case MemberType::Picture: return "Pic";
        case MemberType::Xtra: return "Xtr";
        default: return "?";
    }
}

std::optional<std::string> CastBrowserModel::editorPanelIdFor(::libreshockwave::cast::MemberType type) {
    using ::libreshockwave::cast::MemberType;
    switch (type) {
        case MemberType::Bitmap:
        case MemberType::Picture:
            return "paint";
        case MemberType::Text:
        case MemberType::RichText:
        case MemberType::Button:
            return "text";
        case MemberType::Script:
            return "script";
        case MemberType::Sound:
            return "sound";
        case MemberType::Shape:
            return "vector-shape";
        default:
            return std::nullopt;
    }
}

CastThumbnailPresentation CastBrowserModel::placeholderThumbnail(std::string_view typeAbbreviation, int thumbSize) {
    return CastThumbnailPresentation{
        CastThumbnailKind::Placeholder,
        thumbSize,
        typeAbbreviation.empty() ? "?" : std::string(typeAbbreviation),
        PLACEHOLDER_BACKGROUND,
        PLACEHOLDER_BORDER,
        PLACEHOLDER_TEXT,
        "SansSerif",
        10,
        true,
        0,
        0,
        ThumbnailPlacement{thumbSize, 0, 0, 0, 0, false},
    };
}

CastThumbnailPresentation CastBrowserModel::bitmapThumbnail(int sourceWidth, int sourceHeight, int thumbSize) {
    return CastThumbnailPresentation{
        CastThumbnailKind::Bitmap,
        thumbSize,
        "",
        BITMAP_BACKGROUND,
        0,
        0,
        "",
        0,
        false,
        CHECKER_COLOR,
        CHECKER_SIZE,
        thumbnailPlacement(sourceWidth, sourceHeight, thumbSize),
    };
}

ThumbnailPlacement CastBrowserModel::thumbnailPlacement(int sourceWidth, int sourceHeight, int thumbSize) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || thumbSize <= 0) {
        return ThumbnailPlacement{thumbSize, 0, 0, 0, 0, false};
    }

    const double scale = std::min(static_cast<double>(thumbSize) / static_cast<double>(sourceWidth),
                                  static_cast<double>(thumbSize) / static_cast<double>(sourceHeight));
    const int scaledWidth = std::max(1, static_cast<int>(static_cast<double>(sourceWidth) * scale));
    const int scaledHeight = std::max(1, static_cast<int>(static_cast<double>(sourceHeight) * scale));
    return ThumbnailPlacement{thumbSize,
                              scaledWidth,
                              scaledHeight,
                              (thumbSize - scaledWidth) / 2,
                              (thumbSize - scaledHeight) / 2,
                              true};
}

CastGridLayout CastBrowserModel::gridLayout(std::size_t cellCount, int viewportWidth, CastGridInsets insets) {
    const int layoutWidth = viewportWidth > 0 ? viewportWidth : DEFAULT_GRID_WIDTH;
    const int rightLimit = layoutWidth - insets.right;

    CastGridLayout layout;
    layout.width = layoutWidth;
    layout.tracksViewportWidth = true;
    layout.tracksViewportHeight = false;
    layout.scrollUnitIncrement = CELL_HEIGHT + CELL_GAP;
    layout.cells.reserve(cellCount);

    int x = insets.left + CELL_GAP;
    int y = insets.top + CELL_GAP;
    int rowHeight = 0;
    for (std::size_t index = 0; index < cellCount; ++index) {
        if (x + CELL_WIDTH + CELL_GAP > rightLimit && x > insets.left + CELL_GAP) {
            x = insets.left + CELL_GAP;
            y += rowHeight + CELL_GAP;
            rowHeight = 0;
        }

        layout.cells.push_back(
            CastGridCellBounds{static_cast<int>(index), x, y, CELL_WIDTH, CELL_HEIGHT});
        x += CELL_WIDTH + CELL_GAP;
        rowHeight = std::max(rowHeight, CELL_HEIGHT);
    }

    layout.height = y + rowHeight + CELL_GAP + insets.bottom;
    return layout;
}

int CastBrowserModel::scrollBlockIncrement(int visibleWidth, int visibleHeight, bool vertical) {
    return vertical ? visibleHeight : visibleWidth;
}

bool CastBrowserModel::shouldPreserveListSelection(bool rightButton,
                                                   bool popupTrigger,
                                                   bool pointerOnSelectedRow,
                                                   int selectedRowCount) {
    return (rightButton || popupTrigger) && pointerOnSelectedRow && selectedRowCount > 1;
}

CastViewMode CastBrowserModel::viewMode() const {
    return viewMode_;
}

void CastBrowserModel::setViewMode(CastViewMode mode) {
    viewMode_ = mode;
}

const std::vector<CastLibEntry>& CastBrowserModel::castLibraries() const {
    return castLibraries_;
}

void CastBrowserModel::setCastLibraries(std::vector<CastLibEntry> entries) {
    if (entries.empty()) {
        entries = {CastLibEntry{0, "Internal", false, true}};
    }
    castLibraries_ = std::move(entries);
    if (selectedCastEntryIndex() < 0) {
        selectedCastLibNumber_ = castLibraries_.front().castLibNumber;
    }
}

int CastBrowserModel::selectedCastLibNumber() const {
    return selectedCastLibNumber_;
}

bool CastBrowserModel::selectCastLibNumber(int castLibNumber) {
    const auto found = std::find_if(castLibraries_.begin(), castLibraries_.end(), [castLibNumber](const auto& entry) {
        return entry.castLibNumber == castLibNumber;
    });
    if (found == castLibraries_.end()) {
        if (!castLibraries_.empty()) {
            selectedCastLibNumber_ = castLibraries_.front().castLibNumber;
        }
        return false;
    }
    selectedCastLibNumber_ = castLibNumber;
    return true;
}

void CastBrowserModel::setMembers(std::vector<model::CastMemberInfo> members) {
    members_ = std::move(members);
    searchText_.clear();
    typeFilter_ = "All Types";
    selectedMemberNums_.clear();
    applyFilter();
}

void CastBrowserModel::resetForNoMovie() {
    members_.clear();
    filteredMembers_.clear();
    selectedMemberNums_.clear();
    searchText_.clear();
    typeFilter_ = "All Types";
    castLibraries_ = {CastLibEntry{0, "Internal", false, true}};
    selectedCastLibNumber_ = 0;
}

const std::vector<model::CastMemberInfo>& CastBrowserModel::members() const {
    return members_;
}

const std::vector<model::CastMemberInfo>& CastBrowserModel::filteredMembers() const {
    return filteredMembers_;
}

std::string_view CastBrowserModel::searchText() const {
    return searchText_;
}

void CastBrowserModel::setSearchText(std::string text) {
    searchText_ = std::move(text);
    applyFilter();
}

std::string_view CastBrowserModel::typeFilter() const {
    return typeFilter_;
}

void CastBrowserModel::setTypeFilter(std::string filter) {
    typeFilter_ = std::move(filter);
    applyFilter();
}

std::string CastBrowserModel::statusText() const {
    return " " + std::to_string(filteredMembers_.size()) + " of " + std::to_string(members_.size()) + " members";
}

std::vector<CastGridCell> CastBrowserModel::gridCells() const {
    std::vector<CastGridCell> cells;
    cells.reserve(filteredMembers_.size());
    for (std::size_t index = 0; index < filteredMembers_.size(); ++index) {
        const auto& info = filteredMembers_[index];
        cells.push_back(CastGridCell{info.memberNum,
                                     static_cast<int>(index),
                                     displayNameFor(info),
                                     typeAbbreviation(info.memberType),
                                     isSelected(info.memberNum),
                                     info.memberType == ::libreshockwave::cast::MemberType::Bitmap});
    }
    return cells;
}

std::vector<CastListRow> CastBrowserModel::listRows() const {
    std::vector<CastListRow> rows;
    rows.reserve(filteredMembers_.size());
    for (const auto& info : filteredMembers_) {
        rows.push_back(CastListRow{info.memberNum, listDisplayFor(info), isSelected(info.memberNum)});
    }
    return rows;
}

std::vector<int> CastBrowserModel::bitmapThumbnailRequests() const {
    std::vector<int> requests;
    for (const auto& info : filteredMembers_) {
        if (info.memberType == ::libreshockwave::cast::MemberType::Bitmap) {
            requests.push_back(info.memberNum);
        }
    }
    return requests;
}

const std::vector<int>& CastBrowserModel::selectedMemberNums() const {
    return selectedMemberNums_;
}

bool CastBrowserModel::isSelected(int memberNum) const {
    return containsInt(selectedMemberNums_, memberNum);
}

void CastBrowserModel::clearSelection() {
    selectedMemberNums_.clear();
}

bool CastBrowserModel::selectSingleMember(int memberNum) {
    if (findFilteredMember(memberNum) == nullptr) {
        return false;
    }
    selectedMemberNums_ = {memberNum};
    return true;
}

bool CastBrowserModel::toggleSelectedMember(int memberNum) {
    if (findFilteredMember(memberNum) == nullptr) {
        return false;
    }
    if (const auto found = std::find(selectedMemberNums_.begin(), selectedMemberNums_.end(), memberNum);
        found != selectedMemberNums_.end()) {
        selectedMemberNums_.erase(found);
    } else {
        selectedMemberNums_.push_back(memberNum);
    }
    return true;
}

void CastBrowserModel::selectAllFilteredMembers() {
    selectedMemberNums_.clear();
    selectedMemberNums_.reserve(filteredMembers_.size());
    for (const auto& info : filteredMembers_) {
        selectedMemberNums_.push_back(info.memberNum);
    }
}

void CastBrowserModel::syncSelectionFromRowIndices(const std::vector<int>& selectedRows) {
    selectedMemberNums_.clear();
    for (const int row : selectedRows) {
        if (row >= 0 && row < static_cast<int>(filteredMembers_.size())) {
            const int memberNum = filteredMembers_[static_cast<std::size_t>(row)].memberNum;
            if (!containsInt(selectedMemberNums_, memberNum)) {
                selectedMemberNums_.push_back(memberNum);
            }
        }
    }
}

std::vector<CastContextActionState> CastBrowserModel::contextActions(std::optional<int> memberNum) const {
    std::vector<CastContextActionState> actions;
    if (memberNum.has_value() && findFilteredMember(*memberNum) != nullptr) {
        actions.push_back(CastContextActionState{CastContextAction::Export, "Export...", true});
    }

    if (selectedMemberNums_.size() > 1) {
        actions.push_back(
            CastContextActionState{CastContextAction::ExportSelected, "Export All Selected (Ctrl+Shift+E)", true});
    } else {
        actions.push_back(
            CastContextActionState{CastContextAction::SelectAll, "Select All (Ctrl+A)", !filteredMembers_.empty()});
    }

    if (memberNum.has_value() && findFilteredMember(*memberNum) != nullptr) {
        actions.push_back(CastContextActionState{CastContextAction::CopyName, "Copy Name", true});
    }
    return actions;
}

std::vector<CastContextActionState> CastBrowserModel::openContextForMember(std::optional<int> memberNum) {
    if (memberNum.has_value() && findFilteredMember(*memberNum) != nullptr && !isSelected(*memberNum)) {
        (void)selectSingleMember(*memberNum);
    }
    return contextActions(memberNum);
}

std::vector<model::CastMemberInfo> CastBrowserModel::selectedMembersInFilterOrder() const {
    std::vector<model::CastMemberInfo> result;
    for (const auto& info : filteredMembers_) {
        if (isSelected(info.memberNum)) {
            result.push_back(info);
        }
    }
    return result;
}

std::string CastBrowserModel::lowerAscii(std::string_view value) {
    std::string result;
    result.reserve(value.size());
    std::transform(value.begin(), value.end(), std::back_inserter(result), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return result;
}

std::string CastBrowserModel::trimAscii(std::string_view value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin)) != 0) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1))) != 0) {
        --end;
    }
    return std::string(begin, end);
}

bool CastBrowserModel::matchesTypeFilter(::libreshockwave::cast::MemberType type, std::string_view filter) {
    if (filter == "All Types") {
        return true;
    }
    return lowerAscii(::libreshockwave::cast::name(type)) == lowerAscii(filter);
}

std::string CastBrowserModel::displayNameFor(const model::CastMemberInfo& info) {
    if (!info.name.empty()) {
        return info.name;
    }
    return "[" + typeAbbreviation(info.memberType) + "]";
}

std::string CastBrowserModel::listDisplayFor(const model::CastMemberInfo& info) {
    return std::to_string(info.memberNum) + ": " + (info.name.empty() ? "(unnamed)" : info.name) + " [" +
           std::string(::libreshockwave::cast::name(info.memberType)) + "]";
}

const model::CastMemberInfo* CastBrowserModel::findFilteredMember(int memberNum) const {
    const auto found =
        std::find_if(filteredMembers_.begin(), filteredMembers_.end(), [memberNum](const auto& info) {
            return info.memberNum == memberNum;
        });
    return found == filteredMembers_.end() ? nullptr : &*found;
}

int CastBrowserModel::selectedCastEntryIndex() const {
    for (std::size_t index = 0; index < castLibraries_.size(); ++index) {
        if (castLibraries_[index].castLibNumber == selectedCastLibNumber_) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

void CastBrowserModel::applyFilter() {
    const std::string search = lowerAscii(trimAscii(searchText_));
    filteredMembers_.clear();
    for (const auto& info : members_) {
        if (!matchesTypeFilter(info.memberType, typeFilter_)) {
            continue;
        }
        if (!search.empty()) {
            const std::string name = lowerAscii(info.name);
            const std::string details = lowerAscii(info.details);
            if (name.find(search) == std::string::npos && details.find(search) == std::string::npos) {
                continue;
            }
        }
        filteredMembers_.push_back(info);
    }
    retainVisibleSelection();
}

void CastBrowserModel::retainVisibleSelection() {
    std::vector<int> retained;
    for (const int memberNum : selectedMemberNums_) {
        if (findFilteredMember(memberNum) != nullptr) {
            retained.push_back(memberNum);
        }
    }
    selectedMemberNums_ = std::move(retained);
}

} // namespace libreshockwave::editor::castbrowser
