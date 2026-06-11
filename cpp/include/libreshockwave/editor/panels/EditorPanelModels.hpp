#pragma once

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor::panels {

struct ToolPaletteTool {
    std::string label;
    int row{};
    int column{};

    friend bool operator==(const ToolPaletteTool&, const ToolPaletteTool&) = default;
};

struct DisabledPanelAction {
    std::string label;
    std::string tooltip;

    friend bool operator==(const DisabledPanelAction&, const DisabledPanelAction&) = default;
};

struct FieldEditorState {
    std::string title;
    std::string text;
    std::string status;

    friend bool operator==(const FieldEditorState&, const FieldEditorState&) = default;
};

struct PaintPanelState {
    std::string title;
    std::string imageText;
    std::string status;
    bool hasImage{false};

    friend bool operator==(const PaintPanelState&, const PaintPanelState&) = default;
};

struct TextEditorToolbar {
    std::vector<DisabledPanelAction> styleButtons;
    std::vector<std::string> fontOptions;
    std::vector<std::string> sizeOptions;

    friend bool operator==(const TextEditorToolbar&, const TextEditorToolbar&) = default;
};

struct TextEditorState {
    std::string title;
    std::string text;
    std::string status;

    friend bool operator==(const TextEditorState&, const TextEditorState&) = default;
};

struct SoundPanelInfo {
    bool mp3{false};
    int sampleRate{};
    int bitsPerSample{};
    int channelCount{};
    double durationSeconds{};
    std::size_t audioDataSize{};

    friend bool operator==(const SoundPanelInfo&, const SoundPanelInfo&) = default;
};

struct SoundPanelState {
    std::string title;
    std::string infoText;
    std::string status;
    bool playEnabled{false};
    bool stopEnabled{false};
    int progressPercent{};
    std::string timeLabel;

    friend bool operator==(const SoundPanelState&, const SoundPanelState&) = default;
};

class MessageConsoleModel {
public:
    MessageConsoleModel();

    [[nodiscard]] const std::string& output() const;
    void reset();
    void appendOutput(std::string_view text);
    bool executeCommand(std::string_view command);

    [[nodiscard]] static std::string welcomeText();
    [[nodiscard]] static std::string commandPromptLine(std::string_view command);
    [[nodiscard]] static std::string notImplementedLine();

private:
    std::string output_;
};

class ToolPaletteModel {
public:
    [[nodiscard]] static int columnCount();
    [[nodiscard]] static std::vector<ToolPaletteTool> tools();
};

class ColorPalettesModel {
public:
    [[nodiscard]] static std::vector<std::string> paletteOptions();
    [[nodiscard]] static std::string selectorLabel();
    [[nodiscard]] static std::string placeholderText();
};

class FieldEditorModel {
public:
    [[nodiscard]] static std::vector<DisabledPanelAction> toolbarActions();
    [[nodiscard]] static FieldEditorState emptyState();
    [[nodiscard]] static FieldEditorState missingDataState();
    [[nodiscard]] static FieldEditorState loadedState(std::string_view memberName,
                                                      int memberNumber,
                                                      std::string_view text);
};

class PaintPanelModel {
public:
    [[nodiscard]] static std::vector<DisabledPanelAction> toolbarActions();
    [[nodiscard]] static PaintPanelState emptyState();
    [[nodiscard]] static PaintPanelState decodeFailedState();
    [[nodiscard]] static PaintPanelState loadedState(std::string_view memberName,
                                                     int memberNumber,
                                                     int width,
                                                     int height,
                                                     int bitDepth);
};

class VectorShapePanelModel {
public:
    [[nodiscard]] static std::vector<std::string> toolbarActions();
    [[nodiscard]] static std::string placeholderText();
};

class TextEditorPanelModel {
public:
    [[nodiscard]] static TextEditorToolbar toolbar();
    [[nodiscard]] static TextEditorState emptyState();
    [[nodiscard]] static TextEditorState missingDataState();
    [[nodiscard]] static TextEditorState loadedState(std::string_view memberName,
                                                     int memberNumber,
                                                     std::string_view text,
                                                     std::size_t formattingRunCount);
};

class SoundPanelModel {
public:
    [[nodiscard]] static SoundPanelState emptyState();
    [[nodiscard]] static SoundPanelState missingDataState(std::string_view memberName, int memberNumber);
    [[nodiscard]] static SoundPanelState loadedState(std::string_view memberName,
                                                     int memberNumber,
                                                     const SoundPanelInfo& info);
    [[nodiscard]] static SoundPanelState playingState(std::string_view memberName,
                                                      int memberNumber,
                                                      int progressPercent,
                                                      std::string_view timeLabel);
    [[nodiscard]] static SoundPanelState stoppedState(std::string_view memberName, int memberNumber);
};

} // namespace libreshockwave::editor::panels
