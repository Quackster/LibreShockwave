#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor {

struct ExternalParamRow {
    std::string key;
    std::string value;

    friend bool operator==(const ExternalParamRow&, const ExternalParamRow&) = default;
};

struct MovieParamsEntry {
    std::string moviePath;
    std::vector<ExternalParamRow> params;

    friend bool operator==(const MovieParamsEntry&, const MovieParamsEntry&) = default;
};

class PreferencesModel {
public:
    static constexpr int MAX_RECENT_PROJECTS = 10;

    [[nodiscard]] const std::optional<std::string>& lastOpenDirectory() const;
    void setLastOpenDirectory(std::string path);
    void clearLastOpenDirectory();

    [[nodiscard]] const std::vector<std::string>& recentProjects() const;
    void addRecentProject(std::string path);
    void clearRecentProjects();

    [[nodiscard]] std::optional<std::vector<ExternalParamRow>> movieParams(std::string_view moviePath) const;
    void setMovieParams(std::string moviePath, std::vector<ExternalParamRow> params);
    [[nodiscard]] const std::vector<MovieParamsEntry>& allMovieParams() const;

    [[nodiscard]] std::string serializeJson() const;
    void deserializeJson(std::string_view json);

private:
    [[nodiscard]] static std::string escapeJson(std::string_view value);
    [[nodiscard]] static std::optional<std::string> parseJsonString(std::string_view json, std::string_view key);
    [[nodiscard]] static std::vector<std::string> parseJsonStringArray(std::string_view json, std::string_view key);
    [[nodiscard]] static std::vector<MovieParamsEntry> parseMovieParams(std::string_view json);

    std::optional<std::string> lastOpenDirectory_;
    std::vector<std::string> recentProjects_;
    std::vector<MovieParamsEntry> movieParams_;
};

struct RecentProjectEntry {
    std::string path;
    std::string fileName;
    std::string parentDirectory;
    bool exists{false};

    friend bool operator==(const RecentProjectEntry&, const RecentProjectEntry&) = default;
};

class StartScreenModel {
public:
    using ExistsCallback = std::function<bool(std::string_view)>;

    static constexpr std::string_view TITLE = "LibreShockwave Editor";
    static constexpr std::string_view SUBTITLE = "Director MX 2004";
    static constexpr std::string_view EMPTY_RECENTS_MESSAGE = "No recent projects. Open a movie to get started.";

    [[nodiscard]] static std::vector<std::string> directorFileExtensions();
    [[nodiscard]] static RecentProjectEntry makeRecentEntry(std::string_view path, bool exists);
    [[nodiscard]] static std::vector<RecentProjectEntry> recentEntries(const PreferencesModel& preferences,
                                                                       ExistsCallback exists);
    [[nodiscard]] static std::optional<std::string> selectRecentPath(const std::vector<RecentProjectEntry>& entries,
                                                                     int index);
    [[nodiscard]] static bool createNewMovieEnabled();
};

class ExternalParamsTableModel {
public:
    ExternalParamsTableModel() = default;
    explicit ExternalParamsTableModel(std::vector<ExternalParamRow> rows);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string_view columnName(int column) const;
    [[nodiscard]] const std::vector<ExternalParamRow>& rows() const;
    [[nodiscard]] std::string valueAt(int row, int column) const;

    bool setValueAt(int row, int column, std::string value);
    void addRow(std::string key, std::string value);
    bool removeRow(int index);
    void removeRows(std::vector<int> indices);
    void clear();
    void loadHabboPreset();
    [[nodiscard]] std::vector<ExternalParamRow> toParams() const;

    [[nodiscard]] static std::vector<ExternalParamRow> habboPresetRows();

private:
    std::vector<ExternalParamRow> rows_;
};

} // namespace libreshockwave::editor
