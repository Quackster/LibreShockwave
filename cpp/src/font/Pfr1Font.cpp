#include "libreshockwave/font/Pfr1Font.hpp"

#include <algorithm>
#include <cstdint>
#include <exception>
#include <string>
#include <utility>

#include "libreshockwave/font/PfrBitReader.hpp"

namespace libreshockwave::font {
namespace {

int sign16(int value) {
    return (value & 0x8000) != 0 ? value | ~0xFFFF : value;
}

int readUnsignedN(const std::vector<std::uint8_t>& data, int pos, int n) {
    int value = 0;
    for (int i = 0; i < n; ++i) {
        if (pos + i >= static_cast<int>(data.size())) {
            break;
        }
        value = (value << 8) | (data[static_cast<std::size_t>(pos + i)] & 0xFF);
    }
    return value;
}

int readSignedN(const std::vector<std::uint8_t>& data, int pos, int n) {
    const int value = readUnsignedN(data, pos, n);
    if (n == 0) {
        return 0;
    }
    const int signBit = 1 << (n * 8 - 1);
    if ((value & signBit) != 0) {
        const int mask = (-1) << (n * 8);
        return value | mask;
    }
    return value;
}

std::vector<std::uint8_t> decodeRleBitmap(const std::vector<std::uint8_t>& data,
                                          int offset,
                                          int dataLen,
                                          int width,
                                          int height) {
    const int totalBits = width * height;
    if (totalBits <= 0 || totalBits > 1'000'000) {
        return {};
    }
    const int totalBytes = (totalBits + 7) / 8;
    std::vector<std::uint8_t> result(static_cast<std::size_t>(totalBytes), 0);
    int outPos = 0;
    int pos = offset;
    const int end = offset + dataLen;

    while (pos < end && pos < static_cast<int>(data.size()) && outPos < totalBits) {
        const int byte = data[static_cast<std::size_t>(pos++)] & 0xFF;
        const int count = (byte >> 4) & 0x0F;
        const int value = byte & 0x0F;
        for (int i = 0; i < count && outPos < totalBits; ++i) {
            if (value != 0) {
                result[static_cast<std::size_t>(outPos / 8)] |=
                    static_cast<std::uint8_t>(1U << (7 - (outPos % 8)));
            }
            ++outPos;
        }
    }

    return result;
}

void skipU8(PfrBitReader& reader) {
    (void)reader.readU8();
}

void skipU16(PfrBitReader& reader) {
    (void)reader.readU16();
}

void skipU24(PfrBitReader& reader) {
    (void)reader.readU24();
}

void skipBit(PfrBitReader& reader) {
    (void)reader.readBit();
}

} // namespace

void Pfr1Font::Contour::moveTo(float xValue, float yValue) {
    commands.push_back(Command{0, xValue, yValue});
}

void Pfr1Font::Contour::lineTo(float xValue, float yValue) {
    commands.push_back(Command{1, xValue, yValue});
}

void Pfr1Font::Contour::curveTo(float x1Value,
                                float y1Value,
                                float x2Value,
                                float y2Value,
                                float xValue,
                                float yValue) {
    commands.push_back(Command{2, xValue, yValue, x1Value, y1Value, x2Value, y2Value});
}

std::shared_ptr<Pfr1Font> Pfr1Font::parse(const std::vector<std::uint8_t>& data) {
    if (data.size() < 58 || data[0] != 'P' || data[1] != 'F' || data[2] != 'R' || data[3] != '1') {
        return nullptr;
    }

    auto font = std::shared_ptr<Pfr1Font>(new Pfr1Font());
    try {
        font->parseHeader(data);
        font->parsePhysicalFont(data);
        font->parseGlyphStubsAndBitmaps(data);
    } catch (const std::exception&) {
        // Match the Java parser's lenient behavior: keep whatever was parsed.
    }
    return font;
}

void Pfr1Font::parseHeader(const std::vector<std::uint8_t>& data) {
    PfrBitReader reader(data, 4);

    skipU16(reader);
    skipU16(reader);
    skipU16(reader);
    const int logFontDirSize = reader.readU16();
    const int logFontDirOffset = reader.readU16();
    skipU16(reader);
    const int logFontSectionSize = reader.readU24();
    const int logFontSectionOffset = reader.readU24();
    skipU16(reader);
    skipU24(reader);
    skipU24(reader);
    skipU16(reader);
    gpsSize = reader.readU24();
    gpsOffset = reader.readU24();
    skipU8(reader);
    skipU8(reader);
    skipU8(reader);
    skipU8(reader);

    const int flagsByte = reader.readU8();
    pfrBlackPixel = (flagsByte & 0x01) != 0;

    skipU24(reader);
    skipU24(reader);
    skipU24(reader);
    skipU16(reader);
    skipU8(reader);
    skipU8(reader);
    storedMaxChars_ = reader.readU16();

    parseLogicalFontDirectory(data, logFontDirSize, logFontDirOffset, logFontSectionSize, logFontSectionOffset);
}

void Pfr1Font::parseLogicalFontDirectory(const std::vector<std::uint8_t>& data,
                                         int dirSize,
                                         int dirOffset,
                                         int sectionSize,
                                         int sectionOffset) {
    if (dirOffset == 0 || dirSize == 0) {
        return;
    }

    if (dirSize < 14) {
        if (sectionSize >= 18 && sectionOffset > 0 && sectionOffset < static_cast<int>(data.size())) {
            PfrBitReader reader(data, sectionOffset);
            for (int i = 0; i < 4; ++i) {
                fontMatrix[static_cast<std::size_t>(i)] = reader.readI24();
            }
        }
        return;
    }

    if (dirOffset >= static_cast<int>(data.size())) {
        return;
    }
    PfrBitReader reader(data, dirOffset);
    const int nLogFonts = reader.readU16();
    if (nLogFonts > 0) {
        for (int i = 0; i < 4; ++i) {
            fontMatrix[static_cast<std::size_t>(i)] = reader.readI24();
        }
    }
}

void Pfr1Font::parsePhysicalFont(const std::vector<std::uint8_t>& data) {
    PfrBitReader header(data, 4);
    skipU16(header);
    skipU16(header);
    skipU16(header);
    skipU16(header);
    skipU16(header);
    skipU16(header);
    skipU24(header);
    skipU24(header);
    skipU16(header);
    const int physFontSectionSize = header.readU24();
    const int physOffset = header.readU24();

    if (physOffset >= static_cast<int>(data.size())) {
        return;
    }
    int physEnd = std::min(static_cast<int>(data.size()), physOffset + physFontSectionSize);
    if (gpsOffset > physOffset && gpsOffset <= static_cast<int>(data.size())) {
        physEnd = std::min(physEnd, gpsOffset);
    }

    PfrBitReader reader(data, physOffset);
    skipU16(reader);
    metrics.outlineResolution = reader.readU16();
    if (metrics.outlineResolution == 0) {
        metrics.outlineResolution = 2048;
    }
    metrics.metricsResolution = reader.readU16();
    if (metrics.metricsResolution == 0) {
        metrics.metricsResolution = metrics.outlineResolution;
    }

    metrics.xMin = sign16(reader.readU16());
    metrics.yMin = sign16(reader.readU16());
    metrics.xMax = sign16(reader.readU16());
    metrics.yMax = sign16(reader.readU16());
    metrics.ascender = metrics.yMax;
    metrics.descender = metrics.yMin;

    const bool extraItemsPresent = reader.readBit();
    skipBit(reader);
    skipBit(reader);
    skipBit(reader);
    skipBit(reader);
    const bool proportionalEscapement = reader.readBit();
    skipBit(reader);
    skipBit(reader);

    int standardSetWidth = 0;
    if (!proportionalEscapement) {
        standardSetWidth = sign16(reader.readU16());
    }

    if (extraItemsPresent) {
        const int nExtraItems = reader.readU8();
        for (int i = 0; i < nExtraItems; ++i) {
            if (reader.remaining() < 2) {
                break;
            }
            const int itemSize = reader.readU8();
            const int itemType = reader.readU8();
            const int itemStart = reader.position();

            if (itemType == 1) {
                metrics.hasBitmapSection = true;
                reader.skip(itemSize);
            } else if (itemType == 2) {
                std::string id;
                for (int j = 0; j < itemSize; ++j) {
                    const int ch = reader.readU8();
                    if (ch == 0) {
                        break;
                    }
                    id.push_back(static_cast<char>(ch));
                }
                metrics.fontId = id;
                fontName = id;
                const int consumed = reader.position() - itemStart;
                if (consumed < itemSize) {
                    reader.skip(itemSize - consumed);
                }
            } else {
                reader.skip(itemSize);
            }
        }
    }

    const int nAuxBytes = reader.readU24();
    if (nAuxBytes > 0 && nAuxBytes < 10000) {
        reader.skip(nAuxBytes);
    } else if (nAuxBytes >= 10000) {
        while (reader.position() < physEnd) {
            const int probePos = reader.position();
            const int nBlueValues = reader.readU8();
            const int byteCounter = nBlueValues * 2 + 6;
            const int nCharsPos = reader.position() + byteCounter;
            if (nCharsPos + 2 > physEnd) {
                reader.setPosition(probePos + 1);
                continue;
            }
            reader.setPosition(nCharsPos);
            const int nCharacters = reader.readU16();
            if (nCharacters == storedMaxChars_) {
                reader.setPosition(probePos);
                break;
            }
            reader.setPosition(probePos + 1);
        }
    }

    const int nBlueValues = reader.readU8();
    for (int i = 0; i < nBlueValues; ++i) {
        skipU16(reader);
    }
    skipU8(reader);
    skipU8(reader);

    metrics.stdVW = sign16(reader.readU16());
    metrics.stdHW = sign16(reader.readU16());
    const int nCharacters = reader.readU16();
    parseDeltaEncodedCharRecords(reader, nCharacters, standardSetWidth);
}

void Pfr1Font::parseDeltaEncodedCharRecords(PfrBitReader& reader,
                                            int nCharacters,
                                            int standardSetWidth) {
    int charCode = -1;
    int setWidth = standardSetWidth;
    int glyphSize = 0;
    int glyphOffset = 0;

    for (int i = 0; i < nCharacters; ++i) {
        if (reader.remaining() < 1) {
            break;
        }

        const int flags = reader.readU8();
        const int nextGpsOffset = glyphOffset + glyphSize;

        const int charCodeMode = flags & 0x03;
        ++charCode;
        switch (charCodeMode) {
            case 1:
                charCode += reader.readU8();
                break;
            case 2:
                charCode += reader.readU16();
                break;
            default:
                break;
        }

        const int setWidthMode = (flags >> 2) & 0x03;
        switch (setWidthMode) {
            case 1:
                setWidth += reader.readU8();
                break;
            case 2:
                setWidth -= reader.readU8();
                break;
            case 3:
                setWidth = sign16(reader.readU16());
                break;
            default:
                break;
        }

        const int gpsSizeMode = (flags >> 4) & 0x03;
        switch (gpsSizeMode) {
            case 0:
                glyphSize = reader.readU8();
                break;
            case 1:
                glyphSize = reader.readU8() + 256;
                break;
            case 2:
                glyphSize = reader.readU8() + 512;
                break;
            case 3:
                glyphSize = reader.readU16();
                break;
            default:
                break;
        }

        const int gpsOffsetMode = (flags >> 6) & 0x03;
        switch (gpsOffsetMode) {
            case 0:
                glyphOffset = nextGpsOffset;
                break;
            case 1:
                glyphOffset = nextGpsOffset + reader.readU8();
                break;
            case 2:
                glyphOffset = reader.readU16();
                break;
            case 3:
                glyphOffset = reader.readU24();
                break;
            default:
                break;
        }

        charRecords.push_back(CharacterRecord{charCode, setWidth, glyphSize, glyphOffset});
    }
}

void Pfr1Font::parseGlyphStubsAndBitmaps(const std::vector<std::uint8_t>& data) {
    if (gpsOffset + gpsSize > static_cast<int>(data.size())) {
        return;
    }

    for (const auto& record : charRecords) {
        if (record.gpsSize <= 1) {
            glyphs[record.charCode] = OutlineGlyph{record.charCode, static_cast<float>(record.setWidth), {}};
            continue;
        }

        const int start = gpsOffset + record.gpsOffset;
        const int size = record.gpsSize;
        if (start < 0 || size <= 0 || start + size > static_cast<int>(data.size())) {
            continue;
        }

        if (metrics.hasBitmapSection) {
            const int zerosField = (data[static_cast<std::size_t>(start)] >> 4) & 0x07;
            if (zerosField != 0) {
                try {
                    auto glyph = parseBitmapGlyph(data, start, size, record.charCode);
                    if (record.setWidth > 0) {
                        glyph.setWidth = record.setWidth;
                    }
                    bitmapGlyphs[record.charCode] = std::move(glyph);
                } catch (const std::exception&) {
                }
            }
        }

        glyphs[record.charCode] = OutlineGlyph{record.charCode, static_cast<float>(record.setWidth), {}};
    }
}

Pfr1Font::BitmapGlyph Pfr1Font::parseBitmapGlyph(const std::vector<std::uint8_t>& data,
                                                 int start,
                                                 int size,
                                                 int charCode) const {
    if (size < 2) {
        throw std::runtime_error("bitmap glyph too small");
    }

    const int end = start + size;
    int pos = start;
    const int formatByte = data[static_cast<std::size_t>(pos++)] & 0xFF;

    const int imageFormat = (formatByte >> 6) & 0x03;
    const int escapementFormat = (formatByte >> 4) & 0x03;
    const int sizeFormat = (formatByte >> 2) & 0x03;
    const int positionFormat = formatByte & 0x03;

    const int posBytes = positionFormat == 0 ? 0 : positionFormat == 1 ? 2 : positionFormat == 2 ? 4 : 8;
    const int sizeBytes = sizeFormat == 0 ? 2 : sizeFormat == 1 ? 4 : sizeFormat == 2 ? 6 : 8;
    const int escBytes = escapementFormat == 0 ? 0 : escapementFormat == 1 ? 1 : escapementFormat == 2 ? 2 : 4;
    if (pos + posBytes + sizeBytes + escBytes > end ||
        pos + posBytes + sizeBytes + escBytes > static_cast<int>(data.size())) {
        throw std::runtime_error("bitmap glyph bounds exceeded");
    }

    int xPos = 0;
    int yPos = 0;
    switch (positionFormat) {
        case 1:
            xPos = readSignedN(data, pos, 1);
            ++pos;
            yPos = readSignedN(data, pos, 1);
            ++pos;
            break;
        case 2:
            xPos = readSignedN(data, pos, 2);
            pos += 2;
            yPos = readSignedN(data, pos, 2);
            pos += 2;
            break;
        case 3:
            xPos = readSignedN(data, pos, 4);
            pos += 4;
            yPos = readSignedN(data, pos, 4);
            pos += 4;
            break;
        default:
            break;
    }

    int xSize = 0;
    int ySize = 0;
    switch (sizeFormat) {
        case 0:
            xSize = readUnsignedN(data, pos, 1);
            ++pos;
            ySize = readUnsignedN(data, pos, 1);
            ++pos;
            break;
        case 1:
            xSize = readUnsignedN(data, pos, 2);
            pos += 2;
            ySize = readUnsignedN(data, pos, 2);
            pos += 2;
            break;
        case 2:
            xSize = readUnsignedN(data, pos, 3);
            pos += 3;
            ySize = readUnsignedN(data, pos, 3);
            pos += 3;
            break;
        default:
            xSize = readUnsignedN(data, pos, 4);
            pos += 4;
            ySize = readUnsignedN(data, pos, 4);
            pos += 4;
            break;
    }

    int setWidth = xSize;
    switch (escapementFormat) {
        case 1:
            setWidth = readUnsignedN(data, pos, 1);
            ++pos;
            break;
        case 2:
            setWidth = readUnsignedN(data, pos, 2);
            pos += 2;
            break;
        case 3:
            setWidth = readUnsignedN(data, pos, 4);
            pos += 4;
            break;
        default:
            break;
    }

    if (xSize <= 0 || ySize <= 0 || xSize > 4096 || ySize > 4096) {
        throw std::runtime_error("bitmap glyph size invalid");
    }

    const int totalBits = xSize * ySize;
    if (totalBits <= 0 || totalBits > 1'000'000) {
        throw std::runtime_error("bitmap glyph bit count invalid");
    }

    const int remaining = end - pos;
    if (remaining <= 0 || pos > static_cast<int>(data.size())) {
        throw std::runtime_error("bitmap glyph data missing");
    }

    std::vector<std::uint8_t> imageData;
    if (imageFormat == 0) {
        const int expected = (totalBits + 7) / 8;
        if (expected > remaining || expected <= 0) {
            throw std::runtime_error("bitmap glyph packed data invalid");
        }
        imageData.assign(data.begin() + pos, data.begin() + pos + expected);
    } else if (imageFormat == 1) {
        imageData = decodeRleBitmap(data, pos, remaining, xSize, ySize);
    } else {
        imageData.assign(data.begin() + pos, data.begin() + end);
    }

    return BitmapGlyph{
        charCode,
        imageFormat,
        sign16(xPos & 0xFFFF),
        sign16(yPos & 0xFFFF),
        xSize,
        ySize,
        setWidth,
        std::move(imageData)
    };
}

} // namespace libreshockwave::font
