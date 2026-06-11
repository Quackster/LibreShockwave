#include "libreshockwave/editor/panels/EditorPanelModels.hpp"

#include <cctype>
#include <iomanip>
#include <sstream>
#include <string>
#include <utility>

namespace libreshockwave::editor::panels {
namespace {

std::string trim(std::string_view value) {
    std::size_t begin = 0;
    while (begin < value.size() && std::isspace(static_cast<unsigned char>(value[begin]))) {
        ++begin;
    }
    std::size_t end = value.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return std::string(value.substr(begin, end - begin));
}

std::string normalizeLineEndings(std::string_view text) {
    std::string normalized;
    normalized.reserve(text.size());
    for (std::size_t index = 0; index < text.size(); ++index) {
        const char ch = text[index];
        if (ch == '\r') {
            normalized += '\n';
            if (index + 1 < text.size() && text[index + 1] == '\n') {
                ++index;
            }
        } else {
            normalized += ch;
        }
    }
    return normalized;
}

std::string memberDisplayName(std::string_view memberName, int memberNumber) {
    if (!memberName.empty()) {
        return std::string(memberName);
    }
    return "#" + std::to_string(memberNumber);
}

std::string disabledTooltip(std::string_view label) {
    return std::string(label) + " (not yet implemented)";
}

DisabledPanelAction disabledAction(std::string label) {
    return DisabledPanelAction{label, disabledTooltip(label)};
}

std::string formatFixed(double value, int precision) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(precision) << value;
    return out.str();
}

std::string soundInfoText(const SoundPanelInfo& info) {
    std::string out;
    out += "Codec: " + std::string(info.mp3 ? "MP3" : "PCM (16-bit)") + "\n";
    out += "Sample Rate: " + std::to_string(info.sampleRate) + " Hz\n";
    out += "Bits Per Sample: " + std::to_string(info.bitsPerSample) + "\n";
    out += "Channels: " + std::string(info.channelCount == 1 ? "Mono" : "Stereo") + "\n";
    out += "Duration: " + formatFixed(info.durationSeconds, 2) + " seconds\n";
    out += "Audio Data Size: " + std::to_string(info.audioDataSize) + " bytes\n";
    return out;
}

} // namespace

MessageConsoleModel::MessageConsoleModel() : output_(welcomeText()) {}

const std::string& MessageConsoleModel::output() const {
    return output_;
}

void MessageConsoleModel::reset() {
    output_ = welcomeText();
}

void MessageConsoleModel::appendOutput(std::string_view text) {
    output_ += text;
    output_ += '\n';
}

bool MessageConsoleModel::executeCommand(std::string_view command) {
    const auto trimmed = trim(command);
    if (trimmed.empty()) {
        return false;
    }
    output_ += commandPromptLine(trimmed);
    output_ += notImplementedLine();
    return true;
}

std::string MessageConsoleModel::welcomeText() {
    return "Welcome to LibreShockwave Editor\n-- Type Lingo commands below\n\n";
}

std::string MessageConsoleModel::commandPromptLine(std::string_view command) {
    return ">> " + std::string(command) + "\n";
}

std::string MessageConsoleModel::notImplementedLine() {
    return "-- (command execution not yet implemented)\n";
}

int ToolPaletteModel::columnCount() {
    return 2;
}

std::vector<ToolPaletteTool> ToolPaletteModel::tools() {
    const std::vector<std::string> labels{
        "Arrow",
        "Rotate",
        "Hand",
        "Zoom",
        "Line",
        "Rect",
        "Round Rect",
        "Ellipse",
        "Text",
        "Field",
        "Button",
        "Check Box",
        "Radio",
        "Color",
    };

    std::vector<ToolPaletteTool> tools;
    tools.reserve(labels.size());
    for (std::size_t index = 0; index < labels.size(); ++index) {
        tools.push_back(ToolPaletteTool{labels[index],
                                        static_cast<int>(index / columnCount()),
                                        static_cast<int>(index % columnCount())});
    }
    return tools;
}

std::vector<std::string> ColorPalettesModel::paletteOptions() {
    return {
        "System - Win",
        "System - Mac",
        "Rainbow",
        "Grayscale",
        "Pastels",
        "Vivid",
        "NTSC",
        "Metallic",
        "Web 216",
    };
}

std::string ColorPalettesModel::selectorLabel() {
    return "Palette: ";
}

std::string ColorPalettesModel::placeholderText() {
    return "Color Palettes - Not yet implemented";
}

std::vector<DisabledPanelAction> FieldEditorModel::toolbarActions() {
    return {
        disabledAction("Wrap"),
        disabledAction("Scroll"),
    };
}

FieldEditorState FieldEditorModel::emptyState() {
    return FieldEditorState{"Field", "No field member selected", " Ready"};
}

FieldEditorState FieldEditorModel::missingDataState() {
    return FieldEditorState{"Field", "[Field data not found]", " No data"};
}

FieldEditorState FieldEditorModel::loadedState(std::string_view memberName,
                                               int memberNumber,
                                               std::string_view text) {
    auto normalized = normalizeLineEndings(text);
    const auto characterCount = normalized.size();
    const auto name = memberDisplayName(memberName, memberNumber);
    return FieldEditorState{
        "Field: " + name,
        std::move(normalized),
        " " + name + "  " + std::to_string(characterCount) + " characters",
    };
}

std::vector<DisabledPanelAction> PaintPanelModel::toolbarActions() {
    return {
        disabledAction("Pencil"),
        disabledAction("Brush"),
        disabledAction("Eraser"),
        disabledAction("Fill"),
        disabledAction("Line"),
        disabledAction("Rect"),
        disabledAction("Oval"),
        disabledAction("Select"),
        disabledAction("Lasso"),
    };
}

PaintPanelState PaintPanelModel::emptyState() {
    return PaintPanelState{"Paint", "No bitmap selected", " Ready", false};
}

PaintPanelState PaintPanelModel::decodeFailedState() {
    return PaintPanelState{"Paint", "Failed to decode bitmap", " Error", false};
}

PaintPanelState PaintPanelModel::loadedState(std::string_view memberName,
                                             int memberNumber,
                                             int width,
                                             int height,
                                             int bitDepth) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return PaintPanelState{
        "Paint: " + name,
        {},
        " " + name + "  " + std::to_string(width) + "x" + std::to_string(height) + "  " +
            std::to_string(bitDepth) + "-bit",
        true,
    };
}

std::vector<std::string> VectorShapePanelModel::toolbarActions() {
    return {"Pen", "Line", "Rect", "Ellipse", "Select"};
}

std::string VectorShapePanelModel::placeholderText() {
    return "Vector Shape Editor - Not yet implemented";
}

TextEditorToolbar TextEditorPanelModel::toolbar() {
    return TextEditorToolbar{
        {disabledAction("B"), disabledAction("I"), disabledAction("U")},
        {"Arial", "Times New Roman", "Courier New"},
        {"12", "14", "16", "18", "24", "36"},
    };
}

TextEditorState TextEditorPanelModel::emptyState() {
    return TextEditorState{"Text", "No text member selected", " Ready"};
}

TextEditorState TextEditorPanelModel::missingDataState() {
    return TextEditorState{"Text", "[Text data not found]", " No data"};
}

TextEditorState TextEditorPanelModel::loadedState(std::string_view memberName,
                                                  int memberNumber,
                                                  std::string_view text,
                                                  std::size_t formattingRunCount) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return TextEditorState{
        "Text: " + name,
        normalizeLineEndings(text),
        " " + name + "  " + std::to_string(formattingRunCount) + " formatting run(s)",
    };
}

SoundPanelState SoundPanelModel::emptyState() {
    return SoundPanelState{"Sound", "No sound member selected", " Ready", false, false, 0, "0.0s / 0.0s"};
}

SoundPanelState SoundPanelModel::missingDataState(std::string_view memberName, int memberNumber) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return SoundPanelState{"Sound: " + name, "[Sound data not found]", " No data", false, false, 0, "0.0s / 0.0s"};
}

SoundPanelState SoundPanelModel::loadedState(std::string_view memberName,
                                             int memberNumber,
                                             const SoundPanelInfo& info) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return SoundPanelState{
        "Sound: " + name,
        soundInfoText(info),
        " " + name,
        true,
        false,
        0,
        "0.0s / " + formatFixed(info.durationSeconds, 1) + "s",
    };
}

SoundPanelState SoundPanelModel::playingState(std::string_view memberName,
                                              int memberNumber,
                                              int progressPercent,
                                              std::string_view timeLabel) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return SoundPanelState{"Sound: " + name, {}, " Playing...", false, true, progressPercent, std::string(timeLabel)};
}

SoundPanelState SoundPanelModel::stoppedState(std::string_view memberName, int memberNumber) {
    const auto name = memberDisplayName(memberName, memberNumber);
    return SoundPanelState{"Sound: " + name, {}, " Stopped", true, false, 0, "0.0s / 0.0s"};
}

} // namespace libreshockwave::editor::panels
