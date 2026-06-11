#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor::panels {

struct PanelWindowSize {
    int width{};
    int height{};

    friend bool operator==(const PanelWindowSize&, const PanelWindowSize&) = default;
};

struct MessageConsoleView {
    PanelWindowSize size;
    std::string rootLayout;
    std::string outputPosition;
    std::string inputPosition;
    std::string fontFamily;
    int fontSize{};
    bool outputEditable{false};

    friend bool operator==(const MessageConsoleView&, const MessageConsoleView&) = default;
};

struct ToolPaletteTool {
    std::string label;
    int row{};
    int column{};

    friend bool operator==(const ToolPaletteTool&, const ToolPaletteTool&) = default;
};

struct ToolPaletteView {
    PanelWindowSize size;
    int columnCount{};
    int horizontalGap{};
    int verticalGap{};
    int borderTop{};
    int borderLeft{};
    int borderBottom{};
    int borderRight{};
    float buttonFontSize{};
    int buttonMarginTop{};
    int buttonMarginLeft{};
    int buttonMarginBottom{};
    int buttonMarginRight{};
    bool scrollable{false};
    std::vector<ToolPaletteTool> tools;

    friend bool operator==(const ToolPaletteView&, const ToolPaletteView&) = default;
};

struct ColorPaletteSwatch {
    int index{};
    std::uint32_t rgb{};
    std::string hex;

    friend bool operator==(const ColorPaletteSwatch&, const ColorPaletteSwatch&) = default;
};

struct ColorPalettesView {
    PanelWindowSize size;
    std::string rootLayout;
    std::string selectorPanelLayout;
    std::string selectorPanelAlignment;
    std::string selectorPosition;
    std::string gridPosition;
    std::string gridBackground;
    std::string placeholderAlignment;
    std::string selectorLabel;
    std::vector<std::string> paletteOptions;
    std::string selectedPaletteName;
    int selectedPaletteColorCount{};
    int previewColumnCount{};
    std::vector<ColorPaletteSwatch> previewSwatches;
    std::string previewText;
    std::string statusText;

    friend bool operator==(const ColorPalettesView&, const ColorPalettesView&) = default;
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
    [[nodiscard]] static MessageConsoleView view();

private:
    std::string output_;
};

class ToolPaletteModel {
public:
    [[nodiscard]] static int columnCount();
    [[nodiscard]] static std::vector<ToolPaletteTool> tools();
    [[nodiscard]] static ToolPaletteView view();
};

class ColorPalettesModel {
public:
    [[nodiscard]] static std::vector<std::string> paletteOptions();
    [[nodiscard]] static std::string selectorLabel();
    [[nodiscard]] static std::string selectedPaletteName();
    [[nodiscard]] static int selectedPaletteColorCount();
    [[nodiscard]] static int previewColumnCount();
    [[nodiscard]] static std::vector<ColorPaletteSwatch> previewSwatches(std::size_t maxSwatches = 32);
    [[nodiscard]] static std::string previewText();
    [[nodiscard]] static std::string statusText();
    [[nodiscard]] static ColorPalettesView view();
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
