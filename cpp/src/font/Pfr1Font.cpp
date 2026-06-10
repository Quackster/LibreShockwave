#include "libreshockwave/font/Pfr1Font.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
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

int sign8(int value) {
    value &= 0xFF;
    return (value & 0x80) != 0 ? value | ~0xFF : value;
}

struct ByteRead {
    int value = 0;
    int pos = 0;
};

struct CoordRead {
    int value = 0;
    int pos = 0;
    bool nibbleHigh = false;
};

struct CoordPairRead {
    int x = 0;
    int y = 0;
    int pos = 0;
    bool nibbleHigh = false;
};

ByteRead readByteAligned(const std::vector<std::uint8_t>& data, int pos, bool nibbleHigh) {
    if (pos >= static_cast<int>(data.size())) {
        return {0, pos};
    }
    if (nibbleHigh) {
        const int lo = data[static_cast<std::size_t>(pos)] & 0x0F;
        ++pos;
        const int hi = pos < static_cast<int>(data.size())
                           ? (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F
                           : 0;
        return {(lo << 4) | hi, pos};
    }
    return {data[static_cast<std::size_t>(pos)] & 0xFF, pos + 1};
}

int orusLookup(const std::vector<int>& controlValues, int current, int direction) {
    if (controlValues.empty() || direction == 0) {
        return current;
    }

    if (direction > 0) {
        std::size_t pos = 0;
        while (pos < controlValues.size() && controlValues[pos] <= current) {
            ++pos;
        }
        if (pos >= controlValues.size()) {
            return current;
        }
        const auto target = std::min<std::size_t>(pos + static_cast<std::size_t>(direction - 1),
                                                  controlValues.size() - 1);
        return controlValues[target];
    }

    for (int i = static_cast<int>(controlValues.size()) - 1; i >= 0; --i) {
        if (controlValues[static_cast<std::size_t>(i)] < current) {
            return controlValues[static_cast<std::size_t>(std::max(i + direction + 1, 0))];
        }
    }
    return current;
}

CoordRead readCe9dCoordValue(const std::vector<std::uint8_t>& data,
                             int pos,
                             int v8,
                             bool threeByteMode,
                             bool nibbleAligned) {
    if (pos >= static_cast<int>(data.size())) {
        return {0, pos, nibbleAligned};
    }

    if ((v8 & 1) == 0) {
        int result = 0;
        if (nibbleAligned) {
            const int lo = data[static_cast<std::size_t>(pos)] & 0x0F;
            ++pos;
            const int hi = pos < static_cast<int>(data.size())
                               ? (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F
                               : 0;
            result = sign16((lo << 4) | hi);
        } else {
            result = sign16(data[static_cast<std::size_t>(pos)] & 0xFF);
            ++pos;
        }
        return {result, pos, nibbleAligned};
    }

    if (threeByteMode) {
        int result = 0;
        if (nibbleAligned) {
            const int b0Low = pos > 0 ? data[static_cast<std::size_t>(pos - 1)] & 0x0F : 0;
            const int b1 = pos < static_cast<int>(data.size())
                               ? data[static_cast<std::size_t>(pos)] & 0xFF
                               : 0;
            ++pos;
            const int b2High = pos < static_cast<int>(data.size())
                                   ? (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F
                                   : 0;
            result = sign16((b0Low << 12) | (b1 << 4) | b2High);
        } else {
            const int b0 = pos < static_cast<int>(data.size())
                               ? data[static_cast<std::size_t>(pos)] & 0xFF
                               : 0;
            ++pos;
            const int b1 = pos < static_cast<int>(data.size())
                               ? data[static_cast<std::size_t>(pos)] & 0xFF
                               : 0;
            ++pos;
            result = sign16((b0 << 8) | b1);
        }
        return {result, pos, nibbleAligned};
    }

    int result = 0;
    if (nibbleAligned) {
        const int lo = data[static_cast<std::size_t>(pos)] & 0x0F;
        ++pos;
        const int nextByte = pos < static_cast<int>(data.size())
                                 ? data[static_cast<std::size_t>(pos)] & 0xFF
                                 : 0;
        ++pos;
        result = sign16(nextByte + 16 * sign8(lo << 4));
        nibbleAligned = false;
    } else {
        const int signedByte = sign8(data[static_cast<std::size_t>(pos)]);
        ++pos;
        const int nextHigh = pos < static_cast<int>(data.size())
                                 ? (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F
                                 : 0;
        result = sign16(nextHigh + 16 * signedByte);
        nibbleAligned = true;
    }
    return {result, pos, nibbleAligned};
}

int parseControlValues(const std::vector<std::uint8_t>& data,
                       int pos,
                       int flags,
                       std::vector<int>& controlX,
                       std::vector<int>& controlY) {
    if (controlX.empty() && controlY.empty()) {
        return pos;
    }

    const bool threeByteMode = (flags & 3) == 3;
    const bool flagPerCoordinate = (flags & 0x40) != 0;
    bool nibbleAligned = false;
    int flagCache = 0;
    int flagCacheCount = 0;

    int accumulatedX = 0;
    for (std::size_t i = 0; i < controlX.size(); ++i) {
        if (pos >= static_cast<int>(data.size())) {
            break;
        }

        int v8 = 0;
        if (i == 0) {
            v8 = (flags >> 4) & 1;
        } else if (flagPerCoordinate) {
            if (flagCacheCount > 0) {
                v8 = (flagCache >> 1) & 1;
                flagCache >>= 1;
                --flagCacheCount;
            } else if (pos < static_cast<int>(data.size())) {
                if (nibbleAligned) {
                    const int nibbleValue = data[static_cast<std::size_t>(pos)] & 0x0F;
                    ++pos;
                    nibbleAligned = false;
                    v8 = nibbleValue & 1;
                    flagCache = nibbleValue;
                    flagCacheCount = 3;
                } else {
                    const int nibbleValue = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                    nibbleAligned = true;
                    v8 = nibbleValue & 1;
                    flagCache = nibbleValue;
                    flagCacheCount = 3;
                }
            }
        }

        const auto read = readCe9dCoordValue(data, pos, v8, threeByteMode, nibbleAligned);
        pos = read.pos;
        nibbleAligned = read.nibbleHigh;
        accumulatedX += read.value;
        controlX[i] = accumulatedX;
    }

    int accumulatedY = 0;
    for (std::size_t i = 0; i < controlY.size(); ++i) {
        if (pos >= static_cast<int>(data.size())) {
            break;
        }

        int v8 = 0;
        if (i == 0) {
            v8 = (flags >> 5) & 1;
        } else if (flagPerCoordinate) {
            if (flagCacheCount > 0) {
                v8 = (flagCache >> 1) & 1;
                flagCache >>= 1;
                --flagCacheCount;
            } else if (pos < static_cast<int>(data.size())) {
                if (nibbleAligned) {
                    const int nibbleValue = data[static_cast<std::size_t>(pos)] & 0x0F;
                    ++pos;
                    nibbleAligned = false;
                    v8 = nibbleValue & 1;
                    flagCache = nibbleValue;
                    flagCacheCount = 3;
                } else {
                    const int nibbleValue = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                    nibbleAligned = true;
                    v8 = nibbleValue & 1;
                    flagCache = nibbleValue;
                    flagCacheCount = 3;
                }
            }
        }

        const auto read = readCe9dCoordValue(data, pos, v8, threeByteMode, nibbleAligned);
        pos = read.pos;
        nibbleAligned = read.nibbleHigh;
        accumulatedY += read.value;
        controlY[i] = accumulatedY;
    }

    if (nibbleAligned) {
        ++pos;
    }
    return pos;
}

CoordRead readEncodedCoordValue(const std::vector<std::uint8_t>& data,
                                int pos,
                                int encoding,
                                bool nibbleHigh,
                                int current,
                                const std::vector<int>& controlValues) {
    switch (encoding) {
        case 1: {
            nibbleHigh = !nibbleHigh;
            if (pos >= static_cast<int>(data.size())) {
                return {current, pos, nibbleHigh};
            }
            int nibble = 0;
            if (nibbleHigh) {
                nibble = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
            } else {
                nibble = data[static_cast<std::size_t>(pos)] & 0x0F;
                ++pos;
            }
            return {sign16(current + nibble - 8), pos, nibbleHigh};
        }
        case 2: {
            const auto read = readByteAligned(data, pos, nibbleHigh);
            const int signedByte = sign8(read.value);
            if (signedByte >= -8 && signedByte < 8) {
                const int direction = (signedByte & 0x80) == 0 ? signedByte + 1 : signedByte;
                return {orusLookup(controlValues, current, direction), read.pos, nibbleHigh};
            }
            return {sign16(current + signedByte), read.pos, nibbleHigh};
        }
        case 3: {
            const auto highRead = readByteAligned(data, pos, nibbleHigh);
            pos = highRead.pos;
            nibbleHigh = !nibbleHigh;
            int low = 0;
            if (pos >= static_cast<int>(data.size())) {
                low = 0;
            } else if (nibbleHigh) {
                low = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
            } else {
                low = data[static_cast<std::size_t>(pos)] & 0x0F;
                ++pos;
            }

            const int delta12 = (sign8(highRead.value) << 4) | low;
            if (delta12 >= -128 && delta12 < 128) {
                const auto extraRead = readByteAligned(data, pos, nibbleHigh);
                const int delta16 = (delta12 << 8) | (extraRead.value & 0xFF);
                return {sign16(current + delta16), extraRead.pos, nibbleHigh};
            }
            return {sign16(current + delta12), pos, nibbleHigh};
        }
        default:
            return {current, pos, nibbleHigh};
    }
}

CoordPairRead readEncodedCoords(const std::vector<std::uint8_t>& data,
                                int pos,
                                int encoding,
                                int currentX,
                                int currentY,
                                bool nibbleHigh,
                                const std::vector<int>& controlX,
                                const std::vector<int>& controlY) {
    const int xEncoding = encoding & 3;
    const int yEncoding = (encoding >> 2) & 3;

    int x = currentX;
    int y = currentY;
    if (xEncoding != 0) {
        const auto read = readEncodedCoordValue(data, pos, xEncoding, nibbleHigh, currentX, controlX);
        x = read.value;
        pos = read.pos;
        nibbleHigh = read.nibbleHigh;
    }

    currentX = x;
    if (yEncoding != 0) {
        const auto read = readEncodedCoordValue(data, pos, yEncoding, nibbleHigh, currentY, controlY);
        y = read.value;
        pos = read.pos;
        nibbleHigh = read.nibbleHigh;
    }

    return {currentX, y, pos, nibbleHigh};
}

void parseNibbleCommands(const std::vector<std::uint8_t>& data,
                         int startPos,
                         int endLimit,
                         Pfr1Font::OutlineGlyph& glyph,
                         const std::vector<int>& controlX,
                         const std::vector<int>& controlY) {
    int pos = startPos;
    const int endPos = endLimit > 0 ? endLimit - 1 : 0;
    bool nibbleHigh = false;
    int currentX = 0;
    int currentY = 0;
    Pfr1Font::Contour currentContour;
    bool firstIteration = true;

    for (int iterations = 0; iterations < 500; ++iterations) {
        if (pos >= endPos && (pos != endPos || nibbleHigh)) {
            break;
        }
        if (pos >= static_cast<int>(data.size())) {
            break;
        }

        int command = 0;
        if (firstIteration) {
            command = 6;
            firstIteration = false;
        } else {
            nibbleHigh = !nibbleHigh;
            if (nibbleHigh) {
                command = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
            } else {
                command = data[static_cast<std::size_t>(pos)] & 0x0F;
                ++pos;
            }
        }

        const int beforeX = currentX;
        const int beforeY = currentY;

        switch (command) {
            case 0: {
                nibbleHigh = !nibbleHigh;
                if (pos >= static_cast<int>(data.size())) {
                    break;
                }
                int nibble = 0;
                if (nibbleHigh) {
                    nibble = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                } else {
                    nibble = data[static_cast<std::size_t>(pos)] & 0x0F;
                    ++pos;
                }
                const int direction = (nibble & 4) != 0 ? (nibble & 7) - 8 : (nibble & 7) + 1;
                if ((nibble & 8) != 0) {
                    currentY = orusLookup(controlY, currentY, direction);
                } else {
                    currentX = orusLookup(controlX, currentX, direction);
                }
                currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                break;
            }
            case 1: {
                const auto read = readByteAligned(data, pos, nibbleHigh);
                pos = read.pos;
                currentX = sign16(currentX + sign8(read.value));
                currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                break;
            }
            case 2: {
                const auto read = readByteAligned(data, pos, nibbleHigh);
                pos = read.pos;
                currentY = sign16(currentY + sign8(read.value));
                currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                break;
            }
            case 3:
            case 4: {
                const auto highRead = readByteAligned(data, pos, nibbleHigh);
                pos = highRead.pos;
                nibbleHigh = !nibbleHigh;
                int low = 0;
                if (pos >= static_cast<int>(data.size())) {
                    low = 0;
                } else if (nibbleHigh) {
                    low = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                } else {
                    low = data[static_cast<std::size_t>(pos)] & 0x0F;
                    ++pos;
                }

                const int delta12 = (sign8(highRead.value) << 4) | low;
                int delta = delta12;
                if (delta12 >= -128 && delta12 < 128) {
                    const auto extraRead = readByteAligned(data, pos, nibbleHigh);
                    pos = extraRead.pos;
                    delta = (delta12 << 8) | (extraRead.value & 0xFF);
                }
                if (command == 3) {
                    currentX += sign16(delta);
                } else {
                    currentY += sign16(delta);
                }
                currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                break;
            }
            case 5:
            case 6: {
                nibbleHigh = !nibbleHigh;
                if (pos >= static_cast<int>(data.size())) {
                    break;
                }
                int encoding = 0;
                if (nibbleHigh) {
                    encoding = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                } else {
                    encoding = data[static_cast<std::size_t>(pos)] & 0x0F;
                    ++pos;
                }
                const auto read = readEncodedCoords(
                    data, pos, encoding, currentX, currentY, nibbleHigh, controlX, controlY);
                currentX = read.x;
                currentY = read.y;
                pos = read.pos;
                nibbleHigh = read.nibbleHigh;

                if (command == 6) {
                    if (!currentContour.commands.empty()) {
                        glyph.contours.push_back(std::move(currentContour));
                        currentContour = Pfr1Font::Contour();
                    }
                    currentContour.moveTo(static_cast<float>(currentX), static_cast<float>(currentY));
                } else {
                    currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                }
                break;
            }
            default: {
                if (command >= 7) {
                    for (int i = 0; i < 3; ++i) {
                        nibbleHigh = !nibbleHigh;
                        if (pos >= static_cast<int>(data.size())) {
                            break;
                        }
                        int encoding = 0;
                        if (nibbleHigh) {
                            encoding = (data[static_cast<std::size_t>(pos)] >> 4) & 0x0F;
                        } else {
                            encoding = data[static_cast<std::size_t>(pos)] & 0x0F;
                            ++pos;
                        }
                        const auto read = readEncodedCoords(
                            data, pos, encoding, currentX, currentY, nibbleHigh, controlX, controlY);
                        currentX = read.x;
                        currentY = read.y;
                        pos = read.pos;
                        nibbleHigh = read.nibbleHigh;
                    }
                    currentContour.lineTo(static_cast<float>(currentX), static_cast<float>(currentY));
                }
                break;
            }
        }

        if (std::abs(currentX - beforeX) > 8192 || std::abs(currentY - beforeY) > 8192) {
            currentX = beforeX;
            currentY = beforeY;
        }
        if (currentContour.commands.size() > 300) {
            break;
        }
    }

    if (!currentContour.commands.empty()) {
        glyph.contours.push_back(std::move(currentContour));
    }
}

Pfr1Font::OutlineGlyph parseSimpleOutlineGlyph(const std::vector<std::uint8_t>& data,
                                               int start,
                                               int size,
                                               const Pfr1Font::CharacterRecord& record) {
    Pfr1Font::OutlineGlyph glyph{record.charCode, static_cast<float>(record.setWidth), {}};
    if (size <= 0 || start < 0 || start + size > static_cast<int>(data.size())) {
        return glyph;
    }

    const int flags = data[static_cast<std::size_t>(start)] & 0xFF;
    int pos = start + 1;
    const int countEncoding = flags & 3;

    int xOrusCount = 0;
    int yOrusCount = 0;
    switch (countEncoding) {
        case 1:
            if (pos < start + size) {
                const int countByte = data[static_cast<std::size_t>(pos++)] & 0xFF;
                xOrusCount = countByte & 0x0F;
                yOrusCount = (countByte >> 4) & 0x0F;
            }
            break;
        case 2:
        case 3:
            if (pos + 1 < start + size) {
                xOrusCount = data[static_cast<std::size_t>(pos++)] & 0xFF;
                yOrusCount = data[static_cast<std::size_t>(pos++)] & 0xFF;
            }
            break;
        default:
            break;
    }

    std::vector<int> controlX(static_cast<std::size_t>(xOrusCount), 0);
    std::vector<int> controlY(static_cast<std::size_t>(yOrusCount), 0);
    pos = parseControlValues(data, pos, flags, controlX, controlY);

    if ((flags & 0x08) != 0 && pos < start + size) {
        const int extraCount = data[static_cast<std::size_t>(pos++)] & 0xFF;
        for (int i = 0; i < extraCount; ++i) {
            if (pos + 1 >= start + size) {
                break;
            }
            const int itemLength = data[static_cast<std::size_t>(pos)] & 0xFF;
            pos += itemLength + 2;
        }
    }

    parseNibbleCommands(data, pos, start + size, glyph, controlX, controlY);
    return glyph;
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

        const int flags = data[static_cast<std::size_t>(start)] & 0xFF;
        const int outlineFormat = (flags >> 6) & 0x03;
        const bool compoundGlyph = outlineFormat >= 2 && (flags & 0x3F) > 0;
        if (compoundGlyph) {
            glyphs[record.charCode] = OutlineGlyph{record.charCode, static_cast<float>(record.setWidth), {}};
        } else {
            glyphs[record.charCode] = parseSimpleOutlineGlyph(data, start, size, record);
        }
    }

    for (int lower = 'a'; lower <= 'z'; ++lower) {
        const int upper = lower - 32;
        const auto lowerIt = glyphs.find(lower);
        if (lowerIt != glyphs.end() && !lowerIt->second.contours.empty()) {
            continue;
        }
        const auto upperIt = glyphs.find(upper);
        if (upperIt != glyphs.end() && !upperIt->second.contours.empty()) {
            auto copy = upperIt->second;
            copy.charCode = lower;
            glyphs[lower] = std::move(copy);
        }
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
