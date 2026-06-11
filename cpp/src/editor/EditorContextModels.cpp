#include "libreshockwave/editor/EditorContextModels.hpp"

#include <algorithm>
#include <cstdint>
#include <string>
#include <utility>

namespace libreshockwave::editor {
namespace {

std::string normalizePathSeparators(std::string_view path) {
    std::string normalized(path);
    std::replace(normalized.begin(), normalized.end(), '\\', '/');
    return normalized;
}

std::string lowerAscii(std::string value) {
    for (char& ch : value) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }
    return value;
}

} // namespace

bool EditorContextModel::hasFile() const {
    return fileOpen_;
}

bool EditorContextModel::isPlaying() const {
    return playing_;
}

int EditorContextModel::currentFrame() const {
    return currentFrame_;
}

const std::optional<std::string>& EditorContextModel::currentPath() const {
    return currentPath_;
}

const std::optional<std::string>& EditorContextModel::currentMovieKey() const {
    return currentMovieKey_;
}

const std::optional<int>& EditorContextModel::timerDelayMillis() const {
    return timerDelayMillis_;
}

std::vector<EditorContextEvent> EditorContextModel::openFile(std::string path) {
    auto events = closeFile();
    fileOpen_ = true;
    playing_ = false;
    currentFrame_ = 1;
    currentPath_ = std::move(path);
    currentMovieKey_ = currentPath_;
    events.push_back(fileEvent(std::nullopt, currentPath_));
    return events;
}

std::vector<EditorContextEvent> EditorContextModel::closeFile() {
    std::vector<EditorContextEvent> events;
    timerDelayMillis_.reset();
    playing_ = false;
    currentFrame_ = 1;
    const auto oldPath = currentPath_;
    fileOpen_ = false;
    currentPath_.reset();
    currentMovieKey_.reset();
    if (oldPath.has_value()) {
        events.push_back(fileEvent(oldPath, std::nullopt));
    }
    return events;
}

std::optional<EditorContextEvent> EditorContextModel::setCurrentFrame(int frame) {
    const int oldFrame = currentFrame_;
    currentFrame_ = frame;
    if (oldFrame == frame) {
        return std::nullopt;
    }
    return frameEvent(oldFrame, frame, false);
}

std::optional<EditorContextEvent> EditorContextModel::play() {
    if (!fileOpen_) {
        return std::nullopt;
    }
    playing_ = true;
    return playingEvent(false, true);
}

std::optional<EditorContextEvent> EditorContextModel::stop() {
    if (!fileOpen_) {
        return std::nullopt;
    }
    playing_ = false;
    timerDelayMillis_.reset();
    return playingEvent(true, false);
}

std::vector<EditorContextEvent> EditorContextModel::rewind() {
    std::vector<EditorContextEvent> events;
    if (!fileOpen_) {
        return events;
    }
    playing_ = false;
    timerDelayMillis_.reset();
    if (auto frameChanged = setCurrentFrame(1); frameChanged.has_value()) {
        events.push_back(*frameChanged);
    }
    events.push_back(playingEvent(true, false));
    return events;
}

std::optional<EditorContextEvent> EditorContextModel::stepBackward(int playerCurrentFrame) {
    if (!fileOpen_ || playerCurrentFrame <= 1) {
        return std::nullopt;
    }
    return setCurrentFrame(playerCurrentFrame - 1);
}

EditorContextEvent EditorContextModel::repaintFrameEvent() const {
    return frameEvent(std::nullopt, currentFrame_, true);
}

std::optional<EditorContextEvent> EditorContextModel::playbackTick(bool debuggerPaused,
                                                                   bool vmRunning,
                                                                   int playerCurrentFrame,
                                                                   int playerTempo) {
    if (!fileOpen_ || !playing_ || debuggerPaused) {
        return std::nullopt;
    }
    if (vmRunning) {
        return repaintFrameEvent();
    }

    currentFrame_ = playerCurrentFrame;
    (void)updateTimerDelay(playerTempo);
    return repaintFrameEvent();
}

std::optional<int> EditorContextModel::updateTimerDelay(int tempo) {
    if (tempo <= 0) {
        return std::nullopt;
    }
    timerDelayMillis_ = 1000 / tempo;
    return timerDelayMillis_;
}

EditorContextEvent EditorContextModel::castsLoadedEvent() {
    return EditorContextEvent{
        EditorContextProperty::CastsLoaded,
        propertyName(EditorContextProperty::CastsLoaded),
        std::nullopt,
        std::nullopt,
        std::nullopt,
        std::nullopt,
        false,
        true,
        false,
    };
}

std::string EditorContextModel::propertyName(EditorContextProperty property) {
    switch (property) {
        case EditorContextProperty::File: return std::string(PROP_FILE);
        case EditorContextProperty::Frame: return std::string(PROP_FRAME);
        case EditorContextProperty::Playing: return std::string(PROP_PLAYING);
        case EditorContextProperty::CastsLoaded: return std::string(PROP_CASTS_LOADED);
    }
    return {};
}

std::optional<std::string> EditorContextModel::detectLocalHttpRoot(std::string_view moviePath) {
    const auto normalized = normalizePathSeparators(moviePath);
    const auto lower = lowerAscii(normalized);
    constexpr std::string_view marker = "/htdocs/";
    const auto htdocsIndex = lower.find(marker);
    if (htdocsIndex == std::string::npos) {
        return std::nullopt;
    }
    return normalized.substr(0, htdocsIndex + marker.size() - 1);
}

std::string EditorContextModel::sanitizePreferenceKey(std::string_view key) {
    if (key.size() > 80) {
        return "hash_" + std::to_string(javaStringHashCode(key));
    }

    std::string sanitized(key);
    for (char& ch : sanitized) {
        if (ch == '/' || ch == '\\' || ch == ':') {
            ch = '_';
        }
    }
    return sanitized;
}

std::string EditorContextModel::breakpointPreferenceKey(std::string_view movieKey) {
    return std::string(BREAKPOINTS_PREFIX) + sanitizePreferenceKey(movieKey);
}

int EditorContextModel::javaStringHashCode(std::string_view value) {
    std::uint32_t hash = 0;
    for (unsigned char ch : value) {
        hash = hash * 31u + ch;
    }
    std::int64_t signedHash = hash;
    if (hash >= 0x80000000u) {
        signedHash -= 0x100000000LL;
    }
    return static_cast<int>(signedHash);
}

EditorContextEvent EditorContextModel::fileEvent(std::optional<std::string> oldPath,
                                                 std::optional<std::string> newPath) {
    return EditorContextEvent{
        EditorContextProperty::File,
        propertyName(EditorContextProperty::File),
        std::move(oldPath),
        std::move(newPath),
        std::nullopt,
        std::nullopt,
        std::nullopt,
        std::nullopt,
        false,
    };
}

EditorContextEvent EditorContextModel::frameEvent(std::optional<int> oldFrame,
                                                  int newFrame,
                                                  bool forced) {
    return EditorContextEvent{
        EditorContextProperty::Frame,
        propertyName(EditorContextProperty::Frame),
        std::nullopt,
        std::nullopt,
        oldFrame,
        newFrame,
        std::nullopt,
        std::nullopt,
        forced,
    };
}

EditorContextEvent EditorContextModel::playingEvent(bool oldValue, bool newValue) {
    return EditorContextEvent{
        EditorContextProperty::Playing,
        propertyName(EditorContextProperty::Playing),
        std::nullopt,
        std::nullopt,
        std::nullopt,
        std::nullopt,
        oldValue,
        newValue,
        false,
    };
}

} // namespace libreshockwave::editor
