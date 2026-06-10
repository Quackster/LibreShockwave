#include "libreshockwave/cast/XmedTextParser.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <exception>
#include <map>
#include <string>
#include <string_view>

#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave::cast {
namespace {

bool isHexDigit(std::uint8_t byte) {
    return (byte >= '0' && byte <= '9') ||
           (byte >= 'A' && byte <= 'F') ||
           (byte >= 'a' && byte <= 'f');
}

std::string toAsciiString(const std::vector<std::uint8_t>& data) {
    std::string result;
    result.reserve(data.size());
    for (const auto byte : data) {
        result.push_back(byte >= 0x20 && byte < 0x7F ? static_cast<char>(byte) : '.');
    }
    return result;
}

int parseHex(std::string_view text) {
    if (text.empty()) {
        return 0;
    }
    try {
        return std::stoi(std::string(text), nullptr, 16);
    } catch (const std::exception&) {
        return 0;
    }
}

int parseSectionLength(std::string_view ascii, std::size_t sectionOffset) {
    if (sectionOffset + 4 >= ascii.size()) {
        return 0;
    }
    const auto start = sectionOffset + 4;
    const auto end = std::min<std::size_t>(sectionOffset + 12, ascii.size());
    return parseHex(ascii.substr(start, end - start));
}

std::optional<std::size_t> findSection(const std::vector<std::uint8_t>& data, std::string_view tag) {
    if (tag.empty() || data.size() < tag.size() + 1) {
        return std::nullopt;
    }
    for (std::size_t i = 0; i + tag.size() < data.size(); ++i) {
        if (data[i] != 0x03) {
            continue;
        }
        bool match = true;
        for (std::size_t j = 0; j < tag.size(); ++j) {
            if (data[i + 1 + j] != static_cast<std::uint8_t>(tag[j])) {
                match = false;
                break;
            }
        }
        if (match) {
            return i + 1;
        }
    }
    return std::nullopt;
}

std::string macRomanString(const std::vector<std::uint8_t>& bytes) {
    io::BinaryReader reader(bytes, io::ByteOrder::BigEndian);
    return reader.readStringMacRoman(bytes.size());
}

std::optional<std::string> extractCountCommaText(const std::vector<std::uint8_t>& data, std::size_t pos) {
    if (pos >= data.size()) {
        return std::nullopt;
    }
    std::size_t end = data.size();
    for (std::size_t i = pos; i < data.size(); ++i) {
        if (data[i] == 0x03) {
            end = i;
            break;
        }
    }
    if (end <= pos) {
        return std::nullopt;
    }

    auto comma = end;
    for (std::size_t i = pos; i < end; ++i) {
        if (data[i] == ',') {
            comma = i;
            break;
        }
    }
    if (comma == end || comma + 1 >= end) {
        return std::nullopt;
    }

    std::vector<std::uint8_t> textBytes(data.begin() + static_cast<std::ptrdiff_t>(comma + 1),
                                        data.begin() + static_cast<std::ptrdiff_t>(end));
    return macRomanString(textBytes);
}

std::optional<std::string> extractTextAfterTag(const std::vector<std::uint8_t>& data, std::size_t start) {
    for (std::size_t i = start; i + 2 < data.size(); ++i) {
        if (data[i] == 0x00) {
            return extractCountCommaText(data, i + 1);
        }
    }
    return std::nullopt;
}

bool tryParseTextAt(const std::vector<std::uint8_t>& data, std::size_t pos) {
    std::optional<std::size_t> comma;
    for (std::size_t i = pos; i < std::min(pos + 10, data.size()); ++i) {
        if (data[i] == ',') {
            comma = i;
            break;
        }
        if (!isHexDigit(data[i])) {
            return false;
        }
    }
    return comma.has_value() && *comma > pos && *comma + 1 < data.size() &&
           data[*comma + 1] >= 0x20 && data[*comma + 1] != 0x03;
}

std::optional<std::string> extractText(const std::vector<std::uint8_t>& data, std::string_view ascii) {
    const auto tag = ascii.find("0002");
    if (tag != std::string_view::npos) {
        return extractTextAfterTag(data, tag + 4);
    }

    for (std::size_t i = 0; i + 5 < data.size(); ++i) {
        if (data[i] == 0x00 && tryParseTextAt(data, i + 1)) {
            return extractCountCommaText(data, i + 1);
        }
    }
    return std::nullopt;
}

std::vector<std::string> extractFontNames(const std::vector<std::uint8_t>& data, std::string_view ascii) {
    const auto tag = ascii.find("0008");
    if (tag == std::string_view::npos) {
        return {};
    }

    std::vector<std::string> fonts;
    for (std::size_t i = tag + 20; i + 10 < data.size(); ++i) {
        if (data[i] == 0x03 && i + 3 < data.size() &&
            data[i + 1] == '0' && data[i + 2] == '0' && data[i + 3] == '0') {
            break;
        }
        if (data[i] == 0x00 && i + 4 < data.size() &&
            data[i + 1] == '4' && data[i + 2] == '0' && data[i + 3] == ',') {
            const int nameLen = data[i + 4] & 0xFF;
            if (nameLen > 0 && nameLen < 64 && i + 5 + static_cast<std::size_t>(nameLen) <= data.size()) {
                std::string font(data.begin() + static_cast<std::ptrdiff_t>(i + 5),
                                 data.begin() + static_cast<std::ptrdiff_t>(i + 5 + nameLen));
                while (!font.empty() && std::isspace(static_cast<unsigned char>(font.back()))) {
                    font.pop_back();
                }
                if (!font.empty() && std::find(fonts.begin(), fonts.end(), font) == fonts.end()) {
                    fonts.push_back(std::move(font));
                }
            }
            i += 4 + 64 - 1;
        }
    }
    return fonts;
}

std::string selectPrimaryFontName(const std::vector<std::string>& fonts) {
    if (fonts.empty()) {
        return "Geneva";
    }
    const auto& macFont = fonts.front();
    for (std::size_t i = 1; i < fonts.size(); ++i) {
        if (fonts[i] != macFont) {
            return fonts[i];
        }
    }
    return macFont;
}

std::array<int, 3> extractColor(const std::vector<std::uint8_t>& data, std::string_view ascii) {
    const auto tag = ascii.find("0003");
    if (tag != std::string_view::npos) {
        for (std::size_t i = tag + 4; i + 6 < data.size(); ++i) {
            if (data[i] != 0x00) {
                continue;
            }
            const auto text = extractCountCommaText(data, i + 1);
            if (!text.has_value()) {
                break;
            }
            std::array<int, 3> color{255, 255, 255};
            std::size_t start = 0;
            for (int component = 0; component < 3; ++component) {
                const auto comma = text->find(',', start);
                const auto part = text->substr(start, comma == std::string::npos ? std::string::npos : comma - start);
                try {
                    color[static_cast<std::size_t>(component)] = std::stoi(part);
                } catch (const std::exception&) {
                    return {255, 255, 255};
                }
                if (comma == std::string::npos && component < 2) {
                    return {255, 255, 255};
                }
                start = comma == std::string::npos ? text->size() : comma + 1;
            }
            return color;
        }
    }
    return {255, 255, 255};
}

std::array<int, 2> extractFontSizeAndStyle(const std::vector<std::uint8_t>& data, std::string_view ascii) {
    const auto section = findSection(data, "0006");
    if (!section.has_value()) {
        return {9, 0};
    }

    const auto secStart = *section + 20;
    const int secLen = parseSectionLength(ascii, *section);
    const auto secEnd = secLen > 0 ? std::min(secStart + static_cast<std::size_t>(secLen), data.size()) : data.size();
    std::map<int, int> sizeCounts;
    int firstSize = -1;
    for (std::size_t i = secStart; i + 6 < secEnd; ++i) {
        if (data[i] != 0x02) {
            continue;
        }
        std::string hex;
        std::size_t j = i + 1;
        while (j < secEnd && isHexDigit(data[j])) {
            hex.push_back(static_cast<char>(data[j]));
            ++j;
        }
        if (hex.size() >= 5 && hex.ends_with("0000") && j < secEnd && data[j] == 0x02) {
            const int size = parseHex(std::string_view(hex).substr(0, hex.size() - 4));
            if (size >= 6 && size <= 200) {
                ++sizeCounts[size];
                if (firstSize < 0) {
                    firstSize = size;
                }
            }
        }
    }

    int fontSize = firstSize > 0 ? firstSize : 9;
    int maxCount = 0;
    for (const auto& [size, count] : sizeCounts) {
        if (count > maxCount || (count == maxCount && size < fontSize && fontSize - size <= 2)) {
            fontSize = size;
            maxCount = count;
        }
    }
    return {fontSize, 0};
}

int parseHexValue(const std::vector<std::uint8_t>& data, std::size_t pos) {
    std::string hex;
    while (pos < data.size() && isHexDigit(data[pos])) {
        hex.push_back(static_cast<char>(data[pos]));
        ++pos;
    }
    return parseHex(hex);
}

int extractPrimaryParagraphStyleIndex(const std::vector<std::uint8_t>& data, std::string_view ascii) {
    const auto section = findSection(data, "0005");
    if (!section.has_value() || *section + 20 >= data.size()) {
        return 0;
    }
    const auto lenStart = *section + 4;
    const auto lenEnd = std::min<std::size_t>(*section + 12, ascii.size());
    if (lenStart >= lenEnd ||
        !std::all_of(ascii.begin() + static_cast<std::ptrdiff_t>(lenStart),
                     ascii.begin() + static_cast<std::ptrdiff_t>(lenEnd),
                     [](char c) { return std::isxdigit(static_cast<unsigned char>(c)) != 0; })) {
        return 0;
    }

    const auto bodyStart = *section + 20;
    std::size_t pos = bodyStart;
    if (pos < data.size() && data[pos] == 0x02) {
        ++pos;
    }
    for (std::size_t i = pos; i < std::min(bodyStart + 30, data.size()); ++i) {
        if (data[i] == 0x01 && i + 1 < data.size()) {
            return parseHexValue(data, i + 1);
        }
    }
    return 0;
}

std::string alignmentFromParagraphStyleIndex(int value) {
    switch (value) {
        case 1: return "center";
        case 2: return "right";
        default: return "left";
    }
}

int readU32BE(const std::vector<std::uint8_t>& data, std::size_t offset) {
    if (offset + 4 > data.size()) {
        return 0;
    }
    const auto value = (static_cast<std::uint32_t>(data[offset]) << 24U) |
                       (static_cast<std::uint32_t>(data[offset + 1]) << 16U) |
                       (static_cast<std::uint32_t>(data[offset + 2]) << 8U) |
                       static_cast<std::uint32_t>(data[offset + 3]);
    return static_cast<int>(value);
}

} // namespace

bool XmedTextParser::isTextXtra(const std::vector<std::uint8_t>& specificData) {
    return specificData.size() >= 8 &&
           specificData[4] == 't' &&
           specificData[5] == 'e' &&
           specificData[6] == 'x' &&
           specificData[7] == 't';
}

std::optional<XmedStyledText> XmedTextParser::parseStyled(const std::vector<std::uint8_t>& xmedData,
                                                          const std::vector<std::uint8_t>& specificData) {
    if (xmedData.size() < 10) {
        return std::nullopt;
    }

    const std::string ascii = toAsciiString(xmedData);
    auto text = extractText(xmedData, ascii);
    if (!text.has_value()) {
        text = "";
    }
    auto fontCandidates = extractFontNames(xmedData, ascii);
    std::string fontName = selectPrimaryFontName(fontCandidates);
    const auto [fontSize, fontStyle] = extractFontSizeAndStyle(xmedData, ascii);
    const auto color = extractColor(xmedData, ascii);
    const int paragraphStyleIndex = extractPrimaryParagraphStyleIndex(xmedData, ascii);
    const std::string alignment = alignmentFromParagraphStyleIndex(paragraphStyleIndex);

    int width = 0;
    int height = 0;
    bool antialias = false;
    int aaThreshold = 14;
    if (specificData.size() >= 56) {
        height = readU32BE(specificData, 48);
        width = readU32BE(specificData, 52);
    }
    if (specificData.size() >= 40) {
        const int aaDisabled = readU32BE(specificData, 12);
        if (aaDisabled == 0) {
            const int thresholdEnabled = readU32BE(specificData, 24);
            if (thresholdEnabled == 0) {
                antialias = true;
            } else {
                aaThreshold = readU32BE(specificData, 36);
                antialias = fontSize >= aaThreshold;
            }
        }
    }

    const bool bold = (fontStyle & 1) != 0;
    const bool italic = (fontStyle & 2) != 0;
    std::vector<StyledSpan> spans{
        StyledSpan{0,
                   static_cast<int>(text->size()),
                   fontName,
                   fontSize,
                   bold,
                   italic,
                   false,
                   color[0],
                   color[1],
                   color[2]}
    };

    return XmedStyledText{
        *text,
        spans,
        fontCandidates,
        alignment,
        paragraphStyleIndex,
        -1,
        0,
        true,
        0,
        width,
        height,
        fontName,
        fontSize,
        antialias,
        aaThreshold,
        false,
        color[0],
        color[1],
        color[2]
    };
}

} // namespace libreshockwave::cast
