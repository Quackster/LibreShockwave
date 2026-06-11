#include "libreshockwave/editor/AppModels.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <utility>

#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::editor {
namespace {

std::string trim(std::string value) {
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

std::optional<std::size_t> findMatching(std::string_view text, std::size_t openIndex, char open, char close) {
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (std::size_t index = openIndex; index < text.size(); ++index) {
        const char ch = text[index];
        if (inString) {
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                inString = false;
            }
            continue;
        }
        if (ch == '"') {
            inString = true;
        } else if (ch == open) {
            ++depth;
        } else if (ch == close) {
            --depth;
            if (depth == 0) {
                return index;
            }
        }
    }
    return std::nullopt;
}

std::string parseQuoted(std::string_view text, std::size_t quoteIndex, std::size_t* endOut = nullptr) {
    std::string result;
    bool escaped = false;
    for (std::size_t index = quoteIndex + 1; index < text.size(); ++index) {
        const char ch = text[index];
        if (escaped) {
            switch (ch) {
                case '"': result += '"'; break;
                case '\\': result += '\\'; break;
                case 'n': result += '\n'; break;
                case 'r': result += '\r'; break;
                case 't': result += '\t'; break;
                default: result += ch; break;
            }
            escaped = false;
        } else if (ch == '\\') {
            escaped = true;
        } else if (ch == '"') {
            if (endOut != nullptr) {
                *endOut = index + 1;
            }
            return result;
        } else {
            result += ch;
        }
    }
    if (endOut != nullptr) {
        *endOut = text.size();
    }
    return result;
}

std::optional<std::string_view> findJsonValue(std::string_view json, std::string_view key) {
    const std::string pattern = "\"" + std::string(key) + "\":";
    const auto keyIndex = json.find(pattern);
    if (keyIndex == std::string_view::npos) {
        return std::nullopt;
    }
    std::size_t start = keyIndex + pattern.size();
    while (start < json.size() && std::isspace(static_cast<unsigned char>(json[start]))) {
        ++start;
    }
    if (start >= json.size()) {
        return std::nullopt;
    }
    if (json[start] == '"') {
        std::size_t end = start;
        (void)parseQuoted(json, start, &end);
        return json.substr(start, end - start);
    }
    if (json[start] == '[') {
        const auto end = findMatching(json, start, '[', ']');
        return end.has_value() ? std::optional(json.substr(start, *end - start + 1)) : std::nullopt;
    }
    if (json[start] == '{') {
        const auto end = findMatching(json, start, '{', '}');
        return end.has_value() ? std::optional(json.substr(start, *end - start + 1)) : std::nullopt;
    }
    return std::nullopt;
}

std::string parentPath(std::string_view path) {
    const auto slash = path.find_last_of("/\\");
    if (slash == std::string_view::npos) {
        return {};
    }
    return std::string(path.substr(0, slash));
}

} // namespace

const std::optional<std::string>& PreferencesModel::lastOpenDirectory() const {
    return lastOpenDirectory_;
}

void PreferencesModel::setLastOpenDirectory(std::string path) {
    lastOpenDirectory_ = std::move(path);
}

void PreferencesModel::clearLastOpenDirectory() {
    lastOpenDirectory_.reset();
}

const std::vector<std::string>& PreferencesModel::recentProjects() const {
    return recentProjects_;
}

void PreferencesModel::addRecentProject(std::string path) {
    recentProjects_.erase(std::remove(recentProjects_.begin(), recentProjects_.end(), path), recentProjects_.end());
    recentProjects_.insert(recentProjects_.begin(), std::move(path));
    if (recentProjects_.size() > MAX_RECENT_PROJECTS) {
        recentProjects_.resize(MAX_RECENT_PROJECTS);
    }
}

void PreferencesModel::clearRecentProjects() {
    recentProjects_.clear();
}

std::optional<std::vector<ExternalParamRow>> PreferencesModel::movieParams(std::string_view moviePath) const {
    const auto found = std::find_if(movieParams_.begin(), movieParams_.end(), [moviePath](const MovieParamsEntry& entry) {
        return entry.moviePath == moviePath;
    });
    if (found == movieParams_.end()) {
        return std::nullopt;
    }
    return found->params;
}

void PreferencesModel::setMovieParams(std::string moviePath, std::vector<ExternalParamRow> params) {
    const auto found = std::find_if(movieParams_.begin(), movieParams_.end(), [&moviePath](const MovieParamsEntry& entry) {
        return entry.moviePath == moviePath;
    });
    if (params.empty()) {
        if (found != movieParams_.end()) {
            movieParams_.erase(found);
        }
        return;
    }
    if (found == movieParams_.end()) {
        movieParams_.push_back(MovieParamsEntry{std::move(moviePath), std::move(params)});
    } else {
        found->params = std::move(params);
    }
}

const std::vector<MovieParamsEntry>& PreferencesModel::allMovieParams() const {
    return movieParams_;
}

std::string PreferencesModel::serializeJson() const {
    std::ostringstream out;
    out << "{";
    bool needsComma = false;
    if (lastOpenDirectory_.has_value()) {
        out << "\"lastOpenDirectory\":\"" << escapeJson(*lastOpenDirectory_) << "\"";
        needsComma = true;
    }
    if (!recentProjects_.empty()) {
        if (needsComma) {
            out << ",";
        }
        out << "\"recentProjects\":[";
        for (std::size_t index = 0; index < recentProjects_.size(); ++index) {
            if (index > 0) {
                out << ",";
            }
            out << "\"" << escapeJson(recentProjects_[index]) << "\"";
        }
        out << "]";
        needsComma = true;
    }
    if (!movieParams_.empty()) {
        if (needsComma) {
            out << ",";
        }
        out << "\"movieParams\":{";
        for (std::size_t movieIndex = 0; movieIndex < movieParams_.size(); ++movieIndex) {
            if (movieIndex > 0) {
                out << ",";
            }
            const auto& movie = movieParams_[movieIndex];
            out << "\"" << escapeJson(movie.moviePath) << "\":{";
            for (std::size_t paramIndex = 0; paramIndex < movie.params.size(); ++paramIndex) {
                if (paramIndex > 0) {
                    out << ",";
                }
                out << "\"" << escapeJson(movie.params[paramIndex].key)
                    << "\":\"" << escapeJson(movie.params[paramIndex].value) << "\"";
            }
            out << "}";
        }
        out << "}";
    }
    out << "}";
    return out.str();
}

void PreferencesModel::deserializeJson(std::string_view json) {
    lastOpenDirectory_ = parseJsonString(json, "lastOpenDirectory");
    recentProjects_ = parseJsonStringArray(json, "recentProjects");
    if (recentProjects_.size() > MAX_RECENT_PROJECTS) {
        recentProjects_.resize(MAX_RECENT_PROJECTS);
    }
    movieParams_ = parseMovieParams(json);
}

std::string PreferencesModel::escapeJson(std::string_view value) {
    std::string result;
    result.reserve(value.size());
    for (const char ch : value) {
        switch (ch) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default: result += ch; break;
        }
    }
    return result;
}

std::optional<std::string> PreferencesModel::parseJsonString(std::string_view json, std::string_view key) {
    const auto value = findJsonValue(json, key);
    if (!value.has_value() || value->empty() || (*value)[0] != '"') {
        return std::nullopt;
    }
    return parseQuoted(*value, 0);
}

std::vector<std::string> PreferencesModel::parseJsonStringArray(std::string_view json, std::string_view key) {
    std::vector<std::string> result;
    const auto value = findJsonValue(json, key);
    if (!value.has_value() || value->size() < 2 || (*value)[0] != '[') {
        return result;
    }
    for (std::size_t index = 1; index + 1 < value->size();) {
        if ((*value)[index] == '"') {
            std::size_t end = index;
            result.push_back(parseQuoted(*value, index, &end));
            index = end;
        } else {
            ++index;
        }
    }
    return result;
}

std::vector<MovieParamsEntry> PreferencesModel::parseMovieParams(std::string_view json) {
    std::vector<MovieParamsEntry> result;
    const auto value = findJsonValue(json, "movieParams");
    if (!value.has_value() || value->size() < 2 || (*value)[0] != '{') {
        return result;
    }
    for (std::size_t index = 1; index + 1 < value->size();) {
        if ((*value)[index] != '"') {
            ++index;
            continue;
        }
        std::size_t movieKeyEnd = index;
        auto moviePath = parseQuoted(*value, index, &movieKeyEnd);
        std::size_t objectStart = value->find('{', movieKeyEnd);
        if (objectStart == std::string_view::npos) {
            break;
        }
        const auto objectEnd = findMatching(*value, objectStart, '{', '}');
        if (!objectEnd.has_value()) {
            break;
        }
        std::vector<ExternalParamRow> rows;
        const auto object = value->substr(objectStart + 1, *objectEnd - objectStart - 1);
        for (std::size_t paramIndex = 0; paramIndex < object.size();) {
            if (object[paramIndex] != '"') {
                ++paramIndex;
                continue;
            }
            std::size_t keyEnd = paramIndex;
            auto paramKey = parseQuoted(object, paramIndex, &keyEnd);
            const auto valueStart = object.find('"', keyEnd);
            if (valueStart == std::string_view::npos) {
                break;
            }
            std::size_t valueEnd = valueStart;
            auto paramValue = parseQuoted(object, valueStart, &valueEnd);
            rows.push_back(ExternalParamRow{std::move(paramKey), std::move(paramValue)});
            paramIndex = valueEnd;
        }
        result.push_back(MovieParamsEntry{std::move(moviePath), std::move(rows)});
        index = *objectEnd + 1;
    }
    return result;
}

std::vector<std::string> StartScreenModel::directorFileExtensions() {
    return {"dir", "dxr", "dcr", "cct", "cst"};
}

RecentProjectEntry StartScreenModel::makeRecentEntry(std::string_view path, bool exists) {
    return RecentProjectEntry{
        std::string(path),
        util::getFileName(path),
        parentPath(path),
        exists,
    };
}

std::vector<RecentProjectEntry> StartScreenModel::recentEntries(const PreferencesModel& preferences,
                                                                ExistsCallback exists) {
    std::vector<RecentProjectEntry> result;
    for (const auto& path : preferences.recentProjects()) {
        result.push_back(makeRecentEntry(path, exists ? exists(path) : false));
    }
    return result;
}

std::optional<std::string> StartScreenModel::selectRecentPath(const std::vector<RecentProjectEntry>& entries,
                                                              int index) {
    if (index < 0 || index >= static_cast<int>(entries.size()) || !entries[static_cast<std::size_t>(index)].exists) {
        return std::nullopt;
    }
    return entries[static_cast<std::size_t>(index)].path;
}

bool StartScreenModel::createNewMovieEnabled() {
    return false;
}

ExternalParamsTableModel::ExternalParamsTableModel(std::vector<ExternalParamRow> rows)
    : rows_(std::move(rows)) {}

int ExternalParamsTableModel::rowCount() const {
    return static_cast<int>(rows_.size());
}

int ExternalParamsTableModel::columnCount() const {
    return 2;
}

std::string_view ExternalParamsTableModel::columnName(int column) const {
    return column == 0 ? "Key" : "Value";
}

const std::vector<ExternalParamRow>& ExternalParamsTableModel::rows() const {
    return rows_;
}

std::string ExternalParamsTableModel::valueAt(int row, int column) const {
    if (row < 0 || row >= rowCount() || column < 0 || column >= columnCount()) {
        return {};
    }
    return column == 0 ? rows_[static_cast<std::size_t>(row)].key : rows_[static_cast<std::size_t>(row)].value;
}

bool ExternalParamsTableModel::setValueAt(int row, int column, std::string value) {
    if (row < 0 || row >= rowCount() || column < 0 || column >= columnCount()) {
        return false;
    }
    auto& target = column == 0 ? rows_[static_cast<std::size_t>(row)].key : rows_[static_cast<std::size_t>(row)].value;
    target = std::move(value);
    return true;
}

void ExternalParamsTableModel::addRow(std::string key, std::string value) {
    rows_.push_back(ExternalParamRow{std::move(key), std::move(value)});
}

bool ExternalParamsTableModel::removeRow(int index) {
    if (index < 0 || index >= rowCount()) {
        return false;
    }
    rows_.erase(rows_.begin() + index);
    return true;
}

void ExternalParamsTableModel::removeRows(std::vector<int> indices) {
    std::sort(indices.begin(), indices.end());
    indices.erase(std::unique(indices.begin(), indices.end()), indices.end());
    for (auto it = indices.rbegin(); it != indices.rend(); ++it) {
        removeRow(*it);
    }
}

void ExternalParamsTableModel::clear() {
    rows_.clear();
}

void ExternalParamsTableModel::loadHabboPreset() {
    rows_ = habboPresetRows();
}

std::vector<ExternalParamRow> ExternalParamsTableModel::toParams() const {
    std::vector<ExternalParamRow> result;
    for (const auto& row : rows_) {
        auto key = trim(row.key);
        if (key.empty()) {
            continue;
        }
        const auto found = std::find_if(result.begin(), result.end(), [&key](const ExternalParamRow& existing) {
            return existing.key == key;
        });
        if (found == result.end()) {
            result.push_back(ExternalParamRow{std::move(key), row.value});
        } else {
            found->value = row.value;
        }
    }
    return result;
}

std::vector<ExternalParamRow> ExternalParamsTableModel::habboPresetRows() {
    return {
        {"sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk"},
        {"sw2", "connection.info.host=au.h4bbo.net;connection.info.port=30101"},
        {"sw3", "client.reload.url=http://h4bbo.net/"},
        {"sw4", "connection.mus.host=au.h4bbo.net;connection.mus.port=38101"},
        {"sw5", "external.variables.txt=http://sandbox.h4bbo.net/gamedata/external_variables.txt;"
                "external.texts.txt=http://sandbox.h4bbo.net/gamedata/external_texts.txt"},
    };
}

} // namespace libreshockwave::editor
