#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor {

enum class EditorContextProperty {
    File,
    Frame,
    Playing,
    CastsLoaded
};

struct EditorContextEvent {
    EditorContextProperty property{EditorContextProperty::File};
    std::string propertyName;
    std::optional<std::string> oldPath;
    std::optional<std::string> newPath;
    std::optional<int> oldFrame;
    std::optional<int> newFrame;
    std::optional<bool> oldBool;
    std::optional<bool> newBool;
    bool forced{false};

    friend bool operator==(const EditorContextEvent&, const EditorContextEvent&) = default;
};

class EditorContextModel {
public:
    static constexpr std::string_view PROP_FILE = "file";
    static constexpr std::string_view PROP_FRAME = "currentFrame";
    static constexpr std::string_view PROP_PLAYING = "playing";
    static constexpr std::string_view PROP_CASTS_LOADED = "castsLoaded";
    static constexpr std::string_view BREAKPOINTS_PREFIX = "breakpoints:";

    [[nodiscard]] bool hasFile() const;
    [[nodiscard]] bool isPlaying() const;
    [[nodiscard]] int currentFrame() const;
    [[nodiscard]] const std::optional<std::string>& currentPath() const;
    [[nodiscard]] const std::optional<std::string>& currentMovieKey() const;
    [[nodiscard]] const std::optional<int>& timerDelayMillis() const;

    std::vector<EditorContextEvent> openFile(std::string path);
    std::vector<EditorContextEvent> closeFile();
    [[nodiscard]] std::optional<EditorContextEvent> setCurrentFrame(int frame);
    [[nodiscard]] std::optional<EditorContextEvent> play();
    [[nodiscard]] std::optional<EditorContextEvent> stop();
    std::vector<EditorContextEvent> rewind();
    [[nodiscard]] std::optional<EditorContextEvent> stepBackward(int playerCurrentFrame);
    [[nodiscard]] EditorContextEvent repaintFrameEvent() const;
    [[nodiscard]] std::optional<EditorContextEvent> playbackTick(bool debuggerPaused,
                                                                 bool vmRunning,
                                                                 int playerCurrentFrame,
                                                                 int playerTempo);
    [[nodiscard]] std::optional<int> updateTimerDelay(int tempo);

    [[nodiscard]] static EditorContextEvent castsLoadedEvent();
    [[nodiscard]] static std::string propertyName(EditorContextProperty property);
    [[nodiscard]] static std::optional<std::string> detectLocalHttpRoot(std::string_view moviePath);
    [[nodiscard]] static std::string sanitizePreferenceKey(std::string_view key);
    [[nodiscard]] static std::string breakpointPreferenceKey(std::string_view movieKey);
    [[nodiscard]] static int javaStringHashCode(std::string_view value);

private:
    [[nodiscard]] static EditorContextEvent fileEvent(std::optional<std::string> oldPath,
                                                      std::optional<std::string> newPath);
    [[nodiscard]] static EditorContextEvent frameEvent(std::optional<int> oldFrame,
                                                       int newFrame,
                                                       bool forced);
    [[nodiscard]] static EditorContextEvent playingEvent(bool oldValue, bool newValue);

    bool fileOpen_{false};
    bool playing_{false};
    int currentFrame_{1};
    std::optional<std::string> currentPath_;
    std::optional<std::string> currentMovieKey_;
    std::optional<int> timerDelayMillis_;
};

} // namespace libreshockwave::editor
