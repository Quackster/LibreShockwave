#include <cassert>
#include <bit>
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
#include "libreshockwave/cast/BitmapInfo.hpp"
#include "libreshockwave/cast/FilmLoopInfo.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/cast/ScriptType.hpp"
#include "libreshockwave/cast/ShapeInfo.hpp"
#include "libreshockwave/cast/Shockwave3DInfo.hpp"
#include "libreshockwave/cast/TextInfo.hpp"
#include "libreshockwave/cast/XmedStyledText.hpp"
#include "libreshockwave/chunks/BitmapChunk.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/ConfigChunk.hpp"
#include "libreshockwave/chunks/FontMapChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
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
using libreshockwave::cast::BitmapInfo;
using libreshockwave::cast::FilmLoopInfo;
using libreshockwave::cast::MemberType;
using libreshockwave::cast::ScriptType;
using libreshockwave::cast::ShapeInfo;
using libreshockwave::cast::ShapeType;
using libreshockwave::cast::Shockwave3DInfo;
using libreshockwave::cast::StyledSpan;
using libreshockwave::cast::TextInfo;
using libreshockwave::cast::XmedStyledText;
using libreshockwave::chunks::BitmapChunk;
using libreshockwave::chunks::CastChunk;
using libreshockwave::chunks::ConfigChunk;
using libreshockwave::chunks::FontMapChunk;
using libreshockwave::chunks::FrameLabelsChunk;
using libreshockwave::chunks::KeyTableChunk;
using libreshockwave::chunks::MediaChunk;
using libreshockwave::chunks::PaletteChunk;
using libreshockwave::chunks::RawChunk;
using libreshockwave::chunks::ScriptNamesChunk;
using libreshockwave::chunks::SoundChunk;
using libreshockwave::chunks::TextChunk;
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

void testCastMetadataTypes() {
    assert(libreshockwave::cast::memberTypeFromCode(1) == MemberType::Bitmap);
    assert(libreshockwave::cast::memberTypeFromCode(17) == MemberType::Shockwave3D);
    assert(libreshockwave::cast::memberTypeFromCode(1234) == MemberType::Unknown);
    assert(libreshockwave::cast::name(MemberType::FilmLoop) == "filmLoop");

    assert(libreshockwave::cast::scriptTypeFromCode(1) == ScriptType::Score);
    assert(libreshockwave::cast::scriptTypeFromCode(3) == ScriptType::Movie);
    assert(libreshockwave::cast::scriptTypeFromCode(7) == ScriptType::Parent);
    assert(libreshockwave::cast::isBehavior(ScriptType::Score));
    assert(libreshockwave::cast::isMovieScript(ScriptType::Movie));
    assert(libreshockwave::cast::isParentScript(ScriptType::Parent));

    XmedStyledText text{
        "hello",
        {StyledSpan{0, 5, "Verdana", 12, false, true, true, 0x12, 0x34, 0x56}},
        {"Verdana"},
        "left",
        0,
        0,
        1,
        true,
        0,
        100,
        20,
        "Verdana",
        12,
        true,
        2,
        true,
        0x12,
        0x34,
        0x56
    };
    assert(text.fontStyleString() == "bold,italic,underline");
    assert(text.textColorARGB() == 0xFF123456U);
}

void testCastInfoParsers() {
    const std::vector<std::uint8_t> bitmapData{
        0x00, 0x18,
        0x00, 0x02,
        0x00, 0x03,
        0x00, 0x12,
        0x00, 0x13,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x07,
        0x00, 0x08,
        0x00,
        0x08,
        0x00, 0x00,
        0xFF, 0xFA
    };
    BitmapInfo bitmap = BitmapInfo::parse(bitmapData, 1100);
    assert(bitmap.width == 16);
    assert(bitmap.height == 16);
    assert(bitmap.regX == 8);
    assert(bitmap.regY == 7);
    assert(bitmap.regXLocal() == 5);
    assert(bitmap.regYLocal() == 5);
    assert(bitmap.bitDepth == 8);
    assert(bitmap.paletteId == Palette::METALLIC);
    assert(bitmap.pitch == 24);
    assert(bitmap.bytesPerPixel() == 1);
    assert(bitmap.isPaletted());

    const std::vector<std::uint8_t> textData{
        0x00, 0x05,
        0x00, 0x02,
        0x00, 0x01,
        0xFF, 0xCC,
        0xFF, 0xDD,
        0xFF, 0xEE,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x14, 0x00, 0xAA,
        0x00, 0x0B,
        0x00, 0x00, 0x00, 0x00
    };
    TextInfo text = TextInfo::parse(textData);
    assert(text.textAlign == 1);
    assert(text.bgRed == 0xCC);
    assert(text.bgGreen == 0xDD);
    assert(text.bgBlue == 0xEE);
    assert(text.borderSize == 0);
    assert(text.gutterSize == 5);
    assert(text.textHeight == 11);

    const std::vector<std::uint8_t> shapeData{
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x39,
        0x00, 0x39,
        0x00, 0x01,
        0xF9,
        0x00,
        0x00,
        0x01,
        0x05
    };
    ShapeInfo shape = ShapeInfo::parse(shapeData);
    assert(shape.shapeType == ShapeType::Rect);
    assert(shape.width == 57);
    assert(shape.height == 57);
    assert(shape.color == 0xF9);
    assert(shape.backColor == 0);
    assert(shape.fillType == 0);
    assert(shape.lineThickness == 1);
    assert(shape.lineDirection == 5);
    assert(!shape.isFilled());
    assert(shape.isOutlineInvisible());

    const std::vector<std::uint8_t> filmLoopData{
        0xFF, 0xF6,
        0xFF, 0xEC,
        0x00, 0x1E,
        0x00, 0x28,
        0x00, 0x00, 0x00,
        0b00001001
    };
    FilmLoopInfo filmLoop = FilmLoopInfo::parse(filmLoopData);
    assert(filmLoop.rectTop == -10);
    assert(filmLoop.rectLeft == -20);
    assert(filmLoop.width() == 60);
    assert(filmLoop.height() == 40);
    assert(filmLoop.regX() == 20);
    assert(filmLoop.regY() == 10);
    assert(filmLoop.center);
    assert(filmLoop.crop);
    assert(filmLoop.sound);
    assert(filmLoop.loops);
}

void testShockwave3DInfoParser() {
    std::vector<std::uint8_t> data;
    auto appendI32 = [&](std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto appendF32 = [&](float value) {
        appendI32(std::bit_cast<std::uint32_t>(value));
    };
    auto appendPascal = [&](const std::string& value) {
        data.push_back(static_cast<std::uint8_t>(value.size()));
        data.insert(data.end(), value.begin(), value.end());
    };

    appendI32(0x01020304);
    appendI32(0x05060708);
    appendF32(100.0F);
    appendF32(1.0F);
    appendF32(2.0F);
    appendF32(3.0F);
    appendF32(4.0F);
    appendF32(5.0F);
    appendF32(6.0F);
    data.insert(data.end(), {1, 2, 3, 4, 5, 6});
    appendPascal("shader");
    appendPascal("world");
    appendPascal("tex");
    appendPascal("cam");

    assert(Shockwave3DInfo::isShockwave3D(data));
    Shockwave3DInfo info = Shockwave3DInfo::parse(data);
    assert(info.headerFlags.size() == 2);
    assert(info.headerFlags[0] == 0x01020304);
    assert(std::fabs(info.drawDistance - 100.0F) < 0.0001F);
    assert(std::fabs(info.cameraPosition[1] - 2.0F) < 0.0001F);
    assert(std::fabs(info.cameraTarget[2] - 6.0F) < 0.0001F);
    assert(info.ambientR == 1);
    assert(info.ambientG == 2);
    assert(info.ambientB == 3);
    assert(info.bgColorR == 4);
    assert(info.bgColorG == 5);
    assert(info.bgColorB == 6);
    assert(info.defaultShaderName == "shader");
    assert(info.worldName == "world");
    assert(info.textureName == "tex");
    assert(info.cameraName == "cam");
}

void testCompactChunkParsers() {
    auto putI16 = [](std::vector<std::uint8_t>& data, int offset, int value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(value & 0xFF);
    };
    auto putI32At = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>(value & 0xFF);
    };
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };

    std::vector<std::uint8_t> configData(80, 0);
    putI16(configData, 0, 80);
    putI16(configData, 2, 1200);
    putI16(configData, 4, 10);
    putI16(configData, 6, 20);
    putI16(configData, 8, 250);
    putI16(configData, 10, 340);
    putI16(configData, 12, 1);
    putI16(configData, 14, 99);
    putI16(configData, 20, 3);
    putI16(configData, 22, 12);
    putI16(configData, 24, 1);
    putI16(configData, 26, 5);
    putI16(configData, 28, 8);
    putI16(configData, 36, 0x0207);
    putI16(configData, 54, 30);
    putI16(configData, 56, 2);
    putI16(configData, 76, 4);
    putI16(configData, 78, 5);
    BinaryReader configReader(configData, ByteOrder::LittleEndian);
    ConfigChunk config = ConfigChunk::read(nullptr, configReader, ChunkId(20), 12);
    assert(config.type() == ChunkType::DRCF);
    assert(config.directorVersion() == 0x0207);
    assert(config.stageWidth() == 320);
    assert(config.stageHeight() == 240);
    assert(config.minMember() == 1);
    assert(config.maxMember() == 99);
    assert(config.tempo() == 30);
    assert(config.bgColor() == 8);
    assert(config.stageColor() == 5);
    assert(config.stageColorRGB() == static_cast<int>(Palette::systemMacPalette().getColor(5)));
    assert(config.defaultPaletteCastLib() == 4);
    assert(config.defaultPaletteMember() == 5);
    assert(config.movieVersion() == 1200);
    assert(config.platform() == 2);

    std::vector<std::uint8_t> configD7(80, 0);
    putI16(configD7, 2, 1950);
    putI16(configD7, 4, 0);
    putI16(configD7, 6, 0);
    putI16(configD7, 8, 100);
    putI16(configD7, 10, 200);
    configD7[18] = 0x22;
    configD7[19] = 0x33;
    putI16(configD7, 36, 0x0208);
    configD7[26] = 1;
    configD7[27] = 0x11;
    putI16(configD7, 54, 15);
    putI16(configD7, 56, 1);
    BinaryReader configD7Reader(configD7);
    ConfigChunk d7 = ConfigChunk::read(nullptr, configD7Reader, ChunkId(21), 12);
    assert(d7.stageColorRGB() == 0x112233);

    std::vector<std::uint8_t> labelsData;
    appendI16(labelsData, 2);
    appendI16(labelsData, 10);
    appendI16(labelsData, 6);
    appendI16(labelsData, 2);
    appendI16(labelsData, 0);
    appendI32(labelsData, 11);
    labelsData.insert(labelsData.end(), {'S', 't', 'a', 'r', 't', 0, 'L', 'a', 't', 'e', 'r'});
    BinaryReader labelsReader(labelsData);
    FrameLabelsChunk labels = FrameLabelsChunk::read(nullptr, labelsReader, ChunkId(22), 12);
    assert(labels.labels().size() == 2);
    assert(labels.getFrameByLabel("start") == 2);
    assert(labels.getLabelForFrame(10) == "Later");
    assert(labels.getFrameByLabel("missing") == -1);

    std::vector<std::uint8_t> namesData(20, 0);
    putI16(namesData, 16, 20);
    putI16(namesData, 18, 3);
    namesData.push_back(5);
    namesData.insert(namesData.end(), {'s', 't', 'a', 'r', 't'});
    namesData.push_back(6);
    namesData.insert(namesData.end(), {'M', 'o', 'u', 's', 'e', 'U'});
    namesData.push_back(1);
    namesData.push_back(0x8E);
    BinaryReader namesReader(namesData);
    ScriptNamesChunk names = ScriptNamesChunk::read(nullptr, namesReader, ChunkId(23), 12);
    assert(names.names().size() == 3);
    assert(names.getName(0) == "start");
    assert(names.findName("mouseu") == 1);
    assert(names.getName(99) == "<unknown:99>");
    assert(names.getName(2) == "\xC3\xA9");

    std::vector<std::uint8_t> castData;
    appendI32(castData, 100);
    appendI32(castData, 200);
    appendI32(castData, 300);
    BinaryReader castReader(castData);
    CastChunk cast = CastChunk::read(nullptr, castReader, ChunkId(24), 12);
    assert(cast.type() == ChunkType::CASp);
    assert(cast.memberCount() == 3);
    assert(cast.memberIds()[1] == 200);

    std::vector<std::uint8_t> keyData;
    appendI16(keyData, 12);
    appendI16(keyData, 12);
    appendI32(keyData, 2);
    appendI32(keyData, 2);
    appendI32(keyData, 111);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("BITD"));
    appendI32(keyData, 112);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("ALFA"));
    BinaryReader keyReader(keyData);
    KeyTableChunk key = KeyTableChunk::read(nullptr, keyReader, ChunkId(25), 12);
    assert(key.type() == ChunkType::KEYp);
    assert(key.entries().size() == 2);
    assert(key.entries()[0].fourccString() == "BITD");
    assert(key.getEntriesForOwner(ChunkId(7)).size() == 2);
    assert(key.findEntry(ChunkId(7), BinaryReader::fourCC("ALFA")).has_value());
    assert(key.getOwnerCastId(ChunkId(111))->value() == 7);
    assert(key.getEntryBySectionId(ChunkId(112))->fourccString() == "ALFA");
}

void testTextAndFontMapChunks() {
    auto putI16 = [](std::vector<std::uint8_t>& data, int offset, int value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(value & 0xFF);
    };
    auto putI32At = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>(value & 0xFF);
    };

    const std::vector<std::uint8_t> legacyRaw{
        0x00, 0x00, 0x00, 0x0c,
        0x00, 0x00, 0x00, 0x18,
        0x00, 0x00, 0x00, 0x16,
        'C', 'o', 'p', 'y', 'r', 'i', 'g', 'h',
        't', ' ', 'H', 'a', 'b', 'b', 'o', ' ',
        'L', 't', 'd', ' ', '2', '0', '0', '1',
        0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x80,
        0x12, 0x00, 0x00,
        0x00, 0x09,
        0xFF, 0xFF, 0xFF,
        0xFF
    };
    BinaryReader legacyReader(legacyRaw, ByteOrder::LittleEndian);
    TextChunk legacy = TextChunk::read(nullptr, legacyReader, ChunkId(26), 1600);
    assert(legacy.text() == "Copyright Habbo Ltd 2001");
    assert(legacy.runs().size() == 1);
    assert(legacy.runs()[0].fontSize == 9);
    assert(legacy.runs()[0].fontStyle == 128);
    assert(legacy.runs()[0].colorR == 255);
    assert(legacyReader.order() == ByteOrder::LittleEndian);

    const std::vector<std::uint8_t> modernRaw{
        0x00, 0x00, 0x00, 0x0c,
        0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00,
        'X',
        0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x00,
        0x00,
        0x00, 0x0c,
        0x00, 0x00,
        0x11, 0x22, 0x33,
        0x44
    };
    BinaryReader modernReader(modernRaw);
    TextChunk modern = TextChunk::read(nullptr, modernReader, ChunkId(27), 1950);
    assert(modern.text() == "X");
    assert(modern.runs().size() == 1);
    assert(modern.runs()[0].fontSize == 12);
    assert(modern.runs()[0].colorR == 0x11);
    assert(modern.runs()[0].colorG == 0x22);
    assert(modern.runs()[0].colorB == 0x33);

    std::vector<std::uint8_t> fontMap(118, 0);
    putI32At(fontMap, 0, 0x34);
    putI32At(fontMap, 4, 0x3A);
    putI32At(fontMap, 16, 3);
    putI32At(fontMap, 20, 3);
    auto putEntry = [&](int offset, int nameOffset, int platform, int fontId) {
        putI32At(fontMap, offset, static_cast<std::uint32_t>(nameOffset));
        putI16(fontMap, offset + 4, platform);
        putI16(fontMap, offset + 6, fontId);
    };
    auto putName = [&](int offset, const std::string& value) {
        putI16(fontMap, offset, static_cast<int>(value.size()));
        for (int index = 0; index < static_cast<int>(value.size()); ++index) {
            fontMap[static_cast<std::size_t>(offset + 2 + index)] = static_cast<std::uint8_t>(value[static_cast<std::size_t>(index)]);
        }
    };
    putEntry(36, 0x12, 1, 0x8001);
    putEntry(44, 0x22, 1, 0x8002);
    putEntry(52, 0x2E, 2, 0x8003);
    putName(80, "Volter-Bold");
    putName(96, "Charcoal");
    putName(108, "Arial");

    BinaryReader fontMapReader(fontMap, ByteOrder::LittleEndian);
    FontMapChunk fonts = FontMapChunk::read(nullptr, fontMapReader, ChunkId(28));
    assert(fonts.type() == ChunkType::Fmap);
    assert(fonts.entries().size() == 3);
    assert(fonts.fontNameForId(0x8001).value() == "Volter-Bold");
    assert(fonts.fontNameForId(0x8002).value() == "Charcoal");
    assert(fonts.fontNameForId(0x8003).value() == "Arial");
    assert(fonts.entries()[2].platform == 2);
    assert(fontMapReader.order() == ByteOrder::LittleEndian);
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
    testCastMetadataTypes();
    testCastInfoParsers();
    testShockwave3DInfoParser();
    testCompactChunkParsers();
    testTextAndFontMapChunks();

    std::cout << "LibreShockwave C++ SDK foundation tests passed\n";
    return 0;
}
