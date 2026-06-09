#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/bitmap/ColorRef.hpp"
#include "libreshockwave/bitmap/Palette.hpp"
#include "libreshockwave/chunks/BitmapChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/format/ChunkInfo.hpp"
#include "libreshockwave/format/ChunkType.hpp"
#include "libreshockwave/format/MoaID.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/io/BinaryReader.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/util/AudioCodecUtils.hpp"

using libreshockwave::format::ChunkInfo;
using libreshockwave::format::ChunkType;
using libreshockwave::format::MoaID;
using libreshockwave::id::CastLibId;
using libreshockwave::id::ChannelId;
using libreshockwave::id::ChunkId;
using libreshockwave::id::FrameId;
using libreshockwave::id::InkMode;
using libreshockwave::id::MemberId;
using libreshockwave::id::PaletteId;
using libreshockwave::id::SlotId;
using libreshockwave::id::VarType;
using libreshockwave::io::BinaryReader;
using libreshockwave::io::ByteOrder;
using libreshockwave::bitmap::Bitmap;
using libreshockwave::bitmap::ColorRef;
using libreshockwave::bitmap::Palette;
using libreshockwave::chunks::BitmapChunk;
using libreshockwave::chunks::MediaChunk;
using libreshockwave::chunks::PaletteChunk;
using libreshockwave::chunks::RawChunk;
using libreshockwave::chunks::SoundChunk;
using libreshockwave::lingo::Datum;
using libreshockwave::lingo::DatumType;
using libreshockwave::lingo::LingoException;
using libreshockwave::lingo::StringChunkType;

void testBinaryReaderEndianAndBounds() {
    BinaryReader big({0x01, 0x02, 0x03, 0x04});
    assert(big.readU16() == 0x0102);
    assert(big.readU16() == 0x0304);
    assert(big.eof());

    BinaryReader little({0x01, 0x02, 0x03, 0x04}, ByteOrder::LittleEndian);
    assert(little.readU16() == 0x0201);
    assert(little.readI16() == 0x0403);

    bool threw = false;
    try {
        (void)little.readU8();
    } catch (const std::out_of_range&) {
        threw = true;
    }
    assert(threw);
}

void testBinaryReaderStringsAndFourCC() {
    assert(BinaryReader::fourCC("RIFX") == 0x52494658U);
    assert(BinaryReader::fourCCToString(0x4341532AU) == "CAS*");

    BinaryReader reader({0x52, 0x49, 0x46, 0x58, 0x02, 0x48, 0x69});
    assert(reader.readFourCCString() == "RIFX");
    assert(reader.readPascalString() == "Hi\0");

    BinaryReader macRoman({0x41, 0x8E});
    const std::string expected = std::string("A") + "\xC3\xA9";
    assert(macRoman.readStringMacRoman(2) == expected);
}

void testBinaryReaderNumbers() {
    BinaryReader varInt({0x81, 0x02});
    assert(varInt.readVarInt() == 130);

    BinaryReader f32({0x3F, 0x80, 0x00, 0x00});
    assert(std::fabs(f32.readF32() - 1.0F) < 0.0001F);

    BinaryReader f64({0x3F, 0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
    assert(std::fabs(f64.readF64() - 1.0) < 0.0001);

    BinaryReader apple80({0x3F, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0});
    assert(std::fabs(apple80.readAppleFloat80() - 1.0) < 0.0001);
}

void testBinaryReaderZlib() {
#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
    const std::vector<std::uint8_t> compressed{
        0x78, 0x9C, 0xCB, 0x48, 0xCD, 0xC9, 0xC9, 0x07, 0x00, 0x06, 0x2C, 0x02, 0x15
    };
    const auto decompressed = BinaryReader::decompressZlib(compressed);
    assert(std::string(decompressed.begin(), decompressed.end()) == "hello");
#endif
}

void testIdsAndEnums() {
    assert(FrameId(1).toIndex().value() == 0);
    assert(FrameId(9).toIndex().toFrameId().value() == 9);
    assert(ChannelId(0).value() == 0);
    assert(PaletteId(-1).isBuiltIn());

    const auto slot = SlotId::of(CastLibId(2), MemberId(5));
    assert(slot.value() == 0x00020005);
    assert(slot.castLib() == 2);
    assert(slot.member() == 5);
    assert(slot.castLibId().has_value());
    assert(slot.castLibId()->value() == 2);

    assert(libreshockwave::id::code(InkMode::ADD_PIN) == 33);
    assert(libreshockwave::id::usesBlend(InkMode::DARKEN));
    assert(libreshockwave::id::inkModeFromCode(999) == InkMode::COPY);
    assert(libreshockwave::id::inkModeFromName("background transparent").value() == InkMode::BACKGROUND_TRANSPARENT);

    assert(libreshockwave::id::code(VarType::FIELD) == 0x6);
    assert(libreshockwave::id::varTypeFromCode(0x5) == VarType::LOCAL);

    bool threw = false;
    try {
        CastLibId(0);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    assert(threw);
}

void testFormatTypes() {
    assert(libreshockwave::format::fourCC(ChunkType::CASp) == BinaryReader::fourCC("CAS*"));
    assert(libreshockwave::format::chunkTypeFromString("KEY*") == ChunkType::KEYp);
    assert(libreshockwave::format::chunkTypeFromFourCC(BinaryReader::fourCC("snd ")) == ChunkType::snd_);
    assert(libreshockwave::format::isContainer(ChunkType::RIFX));
    assert(libreshockwave::format::isAfterburner(ChunkType::FGDM));
    assert(libreshockwave::format::isScript(ChunkType::Lscr));
    assert(libreshockwave::format::isMedia(ChunkType::STXT));
    assert(libreshockwave::format::toString(ChunkType::CLUT) == "CLUT (Color Lookup Table (Palette))");

    assert(MoaID::ZLIB_COMPRESSION.isZlib());
    assert(MoaID::NULL_COMPRESSION.isNull());
    assert(MoaID::ZLIB_COMPRESSION.toString() == "MoaID[AC99E904-0070-0B36-00080000-347A3707]");

    const ChunkInfo compressed{12, "CAS*", 100, 20, 80, MoaID::ZLIB_COMPRESSION};
    assert(compressed.isCompressed());
    assert(compressed.isZlibCompressed());

    const ChunkInfo raw{13, "STXT", 180, 40, 40, MoaID::NULL_COMPRESSION};
    assert(!raw.isCompressed());
    assert(raw.toString() == "ChunkInfo[id=13, type=STXT, offset=180, size=40->40, compression=none]");
}

void testLingoDatumTypes() {
    assert(libreshockwave::lingo::typeName(DatumType::String) == "string");
    assert(libreshockwave::lingo::code(StringChunkType::Line) == 4);
    assert(libreshockwave::lingo::stringChunkTypeFromName("WORD") == StringChunkType::Word);

    assert(Datum::of(42).isInt());
    assert(Datum::of(42).intValue() == 42);
    assert(Datum::of(3.7F).intValue() == 3);
    assert(Datum::of("42").intValue() == 42);
    assert(Datum::of("3.7").intValue() == 0);
    assert(std::fabs(Datum::of("3.7").floatValue() - 3.7F) < 0.0001F);
    assert(Datum::voidValue().intValue() == 0);
    assert(Datum::nullValue().stringValue().empty());
    assert(Datum::symbol("mouseUp").stringValue() == "mouseUp");
    assert(!Datum::of("").boolValue());
    assert(Datum::symbol("anything").boolValue());

    bool threw = false;
    try {
        (void)Datum::list().intValue();
    } catch (const LingoException&) {
        threw = true;
    }
    assert(threw);

    auto list = Datum::list();
    list.listValue().add(Datum::of(1));
    list.listValue().add(Datum::of(2));
    assert(list.isList());
    assert(list.listValue().count() == 2);
    assert(list.listValue().getAt(2).intValue() == 2);
    list.listValue().setAt(2, Datum::of(9));
    assert(list.listValue().getAt(2).intValue() == 9);

    auto propList = Datum::propList();
    propList.propListValue().put(Datum::symbol("key"), Datum::of(7));
    assert(propList.isPropList());
    assert(propList.propListValue().count() == 1);
    assert(propList.propListValue().contains(Datum::symbol("key")));
    assert(propList.propListValue().get(Datum::symbol("key")).intValue() == 7);
    assert(propList.propListValue().get(Datum::symbol("missing")).isVoid());

    const auto member = Datum::castMemberRef(CastLibId(2), MemberId(5));
    assert(member.type() == DatumType::CastMemberRef);
    assert(member.asCastMemberRef()->castLibId()->value() == 2);
    assert(member.asCastMemberRef()->memberNum() == 5);

    const auto point = Datum::intPoint(10, 20);
    assert(point.stringValue() == "point(10, 20)");

    const auto rect = Datum::intRect(1, 2, 11, 22);
    assert(rect.asIntRect()->width() == 10);
    assert(rect.stringValue() == "rect(1, 2, 11, 22)");

    const auto chunk = Datum::stringChunk(Datum::of("hello"), StringChunkType::Char, 2, 2, ',', "e");
    assert(chunk.isString());
    assert(chunk.stringValue() == "e");

    auto ancestor = Datum::scriptInstance("base");
    ancestor.scriptInstanceValue().setProperty("baseValue", Datum::of(3));
    auto instance = Datum::scriptInstance("child");
    instance.scriptInstanceValue().setProperty("ancestor", ancestor);
    instance.scriptInstanceValue().setProperty("localValue", Datum::of(4));
    assert(instance.scriptInstanceValue().getProperty("localValue").intValue() == 4);
    assert(instance.scriptInstanceValue().getProperty("baseValue").intValue() == 3);
    assert(instance.scriptInstanceValue().getProperty("missing").isVoid());
}

void testPaletteAndColorRefs() {
    const Palette& metallic = Palette::builtIn(Palette::METALLIC);
    assert(&metallic == &Palette::metallicPalette());
    assert(metallic.size() == 256);
    assert(metallic.getColor(0) == 0xFFFFFFU);
    assert(metallic.getColor(64) == 0x51201FU);
    assert(metallic.getColor(110) == 0xD9BBA1U);
    assert(metallic.getColor(255) == 0x000000U);

    const Palette& rainbow = Palette::builtIn(Palette::RAINBOW);
    assert(&rainbow == &Palette::rainbowPalette());
    assert(rainbow.size() == 256);
    assert(rainbow.getColor(0) == 0xFFFFFFU);
    assert(rainbow.getColor(99) == 0xFF0C00U);
    assert(rainbow.getColor(255) == 0x000000U);

    assert(Palette::systemMacPalette().size() == 256);
    assert(Palette::builtInSymbolName(Palette::SYSTEM_WIN).value() == "systemWin");
    assert(Palette::normalizeBuiltInSymbolName(" greyscale ").value() == "grayscale");
    assert(Palette::builtInBySymbolName("systemWindows") == &Palette::systemWinPalette());
    assert(Palette::builtIn(12345).getColor(0) == Palette::systemMacPalette().getColor(0));

    const auto rgb = ColorRef::Rgb::fromHex("#336699");
    assert(rgb.r == 0x33);
    assert(rgb.g == 0x66);
    assert(rgb.b == 0x99);
    assert(rgb.toPacked() == 0x336699U);
    assert(rgb.toArgb() == 0xFF336699U);

    ColorRef indexed(ColorRef::PaletteIndex(255));
    assert(indexed.toRgb(&Palette::grayscalePalette()) == ColorRef::Rgb(0, 0, 0));
    assert(indexed.toNearestPaletteIndex(&Palette::rainbowPalette()) == 255);

    ColorRef direct(ColorRef::Rgb(238, 238, 238));
    assert(Palette::systemWinPalette().getColor(direct.toNearestPaletteIndex(&Palette::systemWinPalette())) == 0xF0F0F0U);
}

void testBitmapAlphaAndPaletteBehavior() {
    Bitmap bitmap(2, 1, 32, {0x00F0F0F0U, 0x80123456U});
    Bitmap opaque = bitmap.copyWithNonNativeAlphaOpaque();
    assert(opaque.getPixel(0, 0) == 0xFFF0F0F0U);
    assert(opaque.getPixel(1, 0) == 0xFF123456U);

    Bitmap nativeAlpha(1, 1, 32, {0x00F0F0F0U});
    nativeAlpha.setNativeAlpha(true);
    Bitmap nativeResult = nativeAlpha.copyWithNonNativeAlphaOpaque();
    assert(nativeResult.getPixel(0, 0) == 0x00F0F0F0U);
    assert(nativeResult.isNativeAlpha());

    Bitmap indexedFill(2, 1, 8);
    indexedFill.setImagePalette(&Palette::systemWinPalette());
    indexedFill.fillRect(0, 0, 2, 1, 0xFFEEEEEEU);
    assert(indexedFill.getPixel(0, 0) == 0xFFF0F0F0U);
    assert(indexedFill.getPixel(1, 0) == 0xFFF0F0F0U);
    assert(indexedFill.paletteIndex(0, 0).has_value());

    Bitmap firstPalette(2, 1, 8);
    firstPalette.fillRect(0, 0, 2, 1, 0xFFEEEEEEU);
    const int changed = firstPalette.remapImagePalette(&Palette::systemWinPalette());
    assert(changed == 2);
    assert(firstPalette.getPixel(0, 0) == 0xFFF0F0F0U);
    assert(firstPalette.getPixel(1, 0) == 0xFFF0F0F0U);

    Bitmap thirtyTwoBitFill(2, 1, 32);
    thirtyTwoBitFill.setImagePalette(&Palette::systemWinPalette());
    thirtyTwoBitFill.fillRect(0, 0, 2, 1, 0xFFEEEEEEU);
    assert(thirtyTwoBitFill.getPixel(0, 0) == 0xFFEEEEEEU);
    assert(thirtyTwoBitFill.getPixel(1, 0) == 0xFFEEEEEEU);

    Bitmap customPaletteFill(2, 1, 32);
    customPaletteFill.setImagePalette(std::make_shared<Palette>(
        std::vector<std::uint32_t>{0x000000U, 0xF0F0F0U}, "custom"));
    customPaletteFill.fillRect(0, 0, 2, 1, 0xFFEEEEEEU);
    assert(customPaletteFill.getPixel(0, 0) == 0xFFEEEEEEU);
}

void testBitmapRegionsAndMetadata() {
    Bitmap bitmap(3, 2, 8, {
        0xFFFFFFFFU, 0xFF000000U, 0xFFFFFFFFU,
        0xFFFFFFFFU, 0xFF336699U, 0xFFFFFFFFU
    });
    bitmap.setPaletteIndices({0, 1, 0, 0, 2, 0});
    bitmap.setPaletteRefCastMember(4, 9);
    bitmap.setAnchorPoint(2, 1);
    bitmap.markScriptModified();

    Bitmap region = bitmap.getRegion(1, 0, 2, 2);
    assert(region.width() == 2);
    assert(region.height() == 2);
    assert(region.getPixel(0, 0) == 0xFF000000U);
    assert(region.getPixel(1, 1) == 0xFFFFFFFFU);
    assert(region.paletteIndex(0, 0).value() == 1);
    assert(region.paletteRefCastLib() == 4);
    assert(region.paletteRefMemberNum() == 9);
    assert(region.hasAnchorPoint());
    assert(region.anchorX() == 1);
    assert(region.anchorY() == 1);
    assert(region.isScriptModified());

    const auto trim = bitmap.trimWhiteSpace();
    assert((trim == Bitmap::Rect{1, 0, 2, 2}));
    assert(bitmap.toString() == "Bitmap[3x2, 8-bit]");

    Bitmap swatch = Bitmap::createPaletteSwatch(std::vector<std::uint32_t>{0x000000U, 0xFFFFFFU, 0x336699U}, 2, 2);
    assert(swatch.width() == 4);
    assert(swatch.height() == 4);
    assert(swatch.getPixel(0, 0) == 0xFF000000U);
    assert(swatch.getPixel(2, 0) == 0xFFFFFFFFU);
    assert(swatch.getPixel(0, 2) == 0xFF336699U);
}

void testBasicChunks() {
    RawChunk raw(nullptr, ChunkId(9), ChunkType::JUNK, {1, 2, 3, 4});
    assert(raw.file() == nullptr);
    assert(raw.id().value() == 9);
    assert(raw.type() == ChunkType::JUNK);
    assert(raw.length() == 4);

    BinaryReader paletteReader({
        0x12, 0x00, 0x34, 0x00, 0x56, 0x00,
        0xAA, 0x00, 0xBB, 0x00, 0xCC, 0x00
    });
    PaletteChunk palette = PaletteChunk::read(nullptr, paletteReader, ChunkId(10), 12);
    assert(palette.type() == ChunkType::CLUT);
    assert(palette.colorCount() == 2);
    assert(palette.getColor(0) == 0x123456U);
    assert(palette.getColor(1) == 0xAABBCCU);
    assert(palette.getColor(2) == 0);

    BinaryReader bitmapReader({0x01, 0x02, 0x03});
    BitmapChunk bitmap = BitmapChunk::read(nullptr, bitmapReader, ChunkId(11), 12);
    assert(bitmap.type() == ChunkType::BITD);
    assert(bitmap.data().size() == 3);
    assert(bitmap.width() == 0);
    BitmapChunk dimensioned = bitmap.withDimensions(nullptr, 640, 480, 8, PaletteId(Palette::SYSTEM_MAC));
    assert(dimensioned.width() == 640);
    assert(dimensioned.height() == 480);
    assert(dimensioned.bitDepth() == 8);
    assert(dimensioned.paletteId().value() == Palette::SYSTEM_MAC);
    assert(dimensioned.data() == bitmap.data());
}

void testAudioAndMediaChunks() {
    const std::vector<std::uint8_t> mp3Like{0x00, 0x11, 0xFF, 0xFB, 0x90, 0x64, 0x00, 0x00};
    assert(libreshockwave::util::containsMp3SyncFrame(mp3Like, 512));
    assert(!libreshockwave::util::containsMp3SyncFrame({0xFF, 0x00, 0x00, 0x00, 0x00}, 512));

    std::vector<std::uint8_t> soundData(68, 0);
    soundData[0x16] = 0x56;
    soundData[0x17] = 0x22;
    soundData[64] = 0x01;
    soundData[65] = 0x02;
    soundData[66] = 0x03;
    soundData[67] = 0x04;
    BinaryReader soundReader(soundData);
    SoundChunk sound = SoundChunk::read(nullptr, soundReader, ChunkId(12));
    assert(sound.type() == ChunkType::snd_);
    assert(sound.sampleRate() == 22050);
    assert(sound.sampleCount() == 2);
    assert(sound.bitsPerSample() == 16);
    assert(sound.channelCount() == 1);
    assert(sound.codec() == "raw_pcm");
    assert(sound.durationSeconds() > 0.0);

    std::vector<std::uint8_t> soundMp3(70, 0);
    soundMp3[10] = 0xFF;
    soundMp3[11] = 0xFB;
    soundMp3[12] = 0x90;
    soundMp3[13] = 0x64;
    BinaryReader soundMp3Reader(soundMp3);
    SoundChunk mp3Sound = SoundChunk::read(nullptr, soundMp3Reader, ChunkId(13));
    assert(mp3Sound.isMp3());
    assert(mp3Sound.sampleCount() == 0);

    BinaryReader rawMp3Reader({'I', 'D', '3', 0x04, 0x00, 0x00});
    MediaChunk rawMp3 = MediaChunk::read(nullptr, rawMp3Reader, ChunkId(14));
    assert(rawMp3.type() == ChunkType::ediM);
    assert(rawMp3.isMp3());
    assert(rawMp3.sampleRate() == 22050);

    std::vector<std::uint8_t> mediaHeader;
    auto appendI32 = [&](std::uint32_t value) {
        mediaHeader.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        mediaHeader.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        mediaHeader.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        mediaHeader.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    appendI32(40);
    appendI32(0);
    appendI32(44100);
    appendI32(44100);
    appendI32(0);
    appendI32(100);
    mediaHeader.insert(mediaHeader.end(), {0x5A, 0x08, 0xCD, 0x40, 0x53, 0x5B, 0x11, 0xD0,
                                           0xA8, 0xBB, 0x00, 0xA0, 0xC9, 0x00, 0x8A, 0x48});
    mediaHeader.insert(mediaHeader.end(), {0x10, 0x20, 0x30, 0x40});
    BinaryReader mediaReader(mediaHeader, ByteOrder::LittleEndian);
    MediaChunk media = MediaChunk::read(nullptr, mediaReader, ChunkId(15));
    assert(media.sampleRate() == 44100);
    assert(media.dataSizeField() == 100);
    assert(media.guid().has_value());
    assert(media.isAdpcm());
    assert(media.audioData().size() == 4);
    assert(mediaReader.order() == ByteOrder::LittleEndian);

    SoundChunk converted = media.toSoundChunk();
    assert(converted.codec() == "ima_adpcm");
    assert(converted.channelCount() == 1);

    std::vector<std::uint8_t> invalidHeader{0, 0, 0, 100, 1, 2, 3, 4, 5, 6, 7, 8,
                                            9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    BinaryReader invalidMediaReader(invalidHeader);
    MediaChunk invalid = MediaChunk::read(nullptr, invalidMediaReader, ChunkId(16));
    assert(invalid.sampleRate() == 22050);
    assert(invalid.audioData() == invalidHeader);
}

int main() {
    testBinaryReaderEndianAndBounds();
    testBinaryReaderStringsAndFourCC();
    testBinaryReaderNumbers();
    testBinaryReaderZlib();
    testIdsAndEnums();
    testFormatTypes();
    testLingoDatumTypes();
    testPaletteAndColorRefs();
    testBitmapAlphaAndPaletteBehavior();
    testBitmapRegionsAndMetadata();
    testBasicChunks();
    testAudioAndMediaChunks();

    std::cout << "LibreShockwave C++ SDK foundation tests passed\n";
    return 0;
}
