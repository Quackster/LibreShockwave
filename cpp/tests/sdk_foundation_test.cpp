#include <cassert>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <variant>
#include <vector>

#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
#include <zlib.h>
#endif

#include "libreshockwave/DirectorFile.hpp"
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
#include "libreshockwave/chunks/CastListChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/ConfigChunk.hpp"
#include "libreshockwave/chunks/FontMapChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/chunks/ScriptContextChunk.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/ScoreChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/format/ChunkInfo.hpp"
#include "libreshockwave/format/AfterburnerReader.hpp"
#include "libreshockwave/format/ChunkType.hpp"
#include "libreshockwave/format/MoaID.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/io/BinaryReader.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lookup/CastMemberLookup.hpp"
#include "libreshockwave/lookup/PaletteResolver.hpp"
#include "libreshockwave/lookup/ScriptLookup.hpp"
#include "libreshockwave/util/AudioCodecUtils.hpp"

using libreshockwave::format::ChunkInfo;
using libreshockwave::format::AfterburnerReader;
using libreshockwave::format::ChunkType;
using libreshockwave::format::MoaID;
using libreshockwave::DirectorFile;
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
using libreshockwave::chunks::CastListChunk;
using libreshockwave::chunks::CastMemberChunk;
using libreshockwave::chunks::CastMemberScriptType;
using libreshockwave::chunks::ConfigChunk;
using libreshockwave::chunks::FontMapChunk;
using libreshockwave::chunks::FrameLabelsChunk;
using libreshockwave::chunks::KeyTableChunk;
using libreshockwave::chunks::MediaChunk;
using libreshockwave::chunks::PaletteChunk;
using libreshockwave::chunks::RawChunk;
using libreshockwave::chunks::ScriptContextChunk;
using libreshockwave::chunks::ScriptChunk;
using libreshockwave::chunks::ScriptChunkType;
using libreshockwave::chunks::ScriptNamesChunk;
using libreshockwave::chunks::ScoreChunk;
using libreshockwave::chunks::SoundChunk;
using libreshockwave::chunks::TextChunk;
using libreshockwave::lingo::Datum;
using libreshockwave::lingo::DatumType;
using libreshockwave::lingo::LingoException;
using libreshockwave::lingo::Opcode;
using libreshockwave::lingo::StringChunkType;
using libreshockwave::lookup::CastMemberLookup;
using libreshockwave::lookup::PaletteResolver;
using libreshockwave::lookup::ScriptLookup;

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

void testLingoOpcodeHelpers() {
    assert(libreshockwave::lingo::code(Opcode::RET) == 0x01);
    assert(libreshockwave::lingo::mnemonic(Opcode::PUSH_INT16) == "pushInt16");
    assert(libreshockwave::lingo::argBytes(Opcode::PUSH_INT16) == 2);
    assert(libreshockwave::lingo::argBytes(Opcode::PUSH_INT32) == 4);
    assert(libreshockwave::lingo::argBytes(Opcode::GET_GLOBAL) == 1);
    assert(libreshockwave::lingo::opcodeFromCode(0x6F) == Opcode::PUSH_INT32);
    assert(libreshockwave::lingo::opcodeFromCode(0x99) == Opcode::INVALID);
    assert(libreshockwave::lingo::isSingleByte(Opcode::RET));
    assert(libreshockwave::lingo::isMultiByte(Opcode::PUSH_INT8));
    assert(libreshockwave::lingo::isJump(Opcode::JMP_IF_Z));
    assert(libreshockwave::lingo::isCall(Opcode::OBJ_CALL));
    assert(libreshockwave::lingo::isReturn(Opcode::RET_FACTORY));
    assert(libreshockwave::lingo::isPush(Opcode::PUSH_FLOAT32));
    assert(!libreshockwave::lingo::isPush(Opcode::ADD));
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
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
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

void testCastListAndMemberChunks() {
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
    auto appendPascal = [](std::vector<std::uint8_t>& data, const std::string& value) {
        data.push_back(static_cast<std::uint8_t>(value.size()));
        data.insert(data.end(), value.begin(), value.end());
    };

    std::vector<std::uint8_t> castListData;
    appendI32(castListData, 12);
    appendI16(castListData, 0);
    appendI16(castListData, 1);
    appendI16(castListData, 4);
    appendI16(castListData, 0);
    appendI16(castListData, 5);
    appendI32(castListData, 0);
    appendI32(castListData, 0);
    appendI32(castListData, 5);
    appendI32(castListData, 14);
    appendI32(castListData, 16);
    appendI32(castListData, 24);
    appendPascal(castListData, "Main");
    appendPascal(castListData, "cast.dir");
    appendI16(castListData, 0x1234);
    appendI16(castListData, 5);
    appendI16(castListData, 9);
    appendI32(castListData, 42);

    BinaryReader castListReader(castListData, ByteOrder::LittleEndian);
    CastListChunk castList = CastListChunk::read(nullptr, castListReader, ChunkId(29), 0x4B1);
    assert(castList.type() == ChunkType::MCsL);
    assert(castList.dataOffset() == 12);
    assert(castList.itemCount() == 1);
    assert(castList.itemsPerEntry() == 4);
    assert(castList.entries().size() == 1);
    assert(castList.entries()[0].name == "Main");
    assert(castList.entries()[0].path == "cast.dir");
    assert(castList.entries()[0].preloadSettings == 0x1234);
    assert(castList.entries()[0].minMember == 5);
    assert(castList.entries()[0].maxMember == 9);
    assert(castList.entries()[0].memberCount == 5);
    assert(castList.entries()[0].id == 42);
    assert(castListReader.order() == ByteOrder::LittleEndian);

    std::vector<std::uint8_t> memberInfo;
    appendI32(memberInfo, 20);
    appendI32(memberInfo, 0);
    appendI32(memberInfo, 0);
    appendI32(memberInfo, 0);
    appendI32(memberInfo, 77);
    appendI16(memberInfo, 3);
    appendI32(memberInfo, 0);
    appendI32(memberInfo, 0);
    appendI32(memberInfo, 11);
    appendI32(memberInfo, 11);
    appendPascal(memberInfo, "BitmapName");

    std::vector<std::uint8_t> bitmapSpecific;
    appendI16(bitmapSpecific, 0x8018);
    appendI16(bitmapSpecific, 2);
    appendI16(bitmapSpecific, 3);
    appendI16(bitmapSpecific, 18);
    appendI16(bitmapSpecific, 19);
    bitmapSpecific.insert(bitmapSpecific.end(), {0, 0, 0, 0, 0, 0, 0, 0});
    appendI16(bitmapSpecific, 7);
    appendI16(bitmapSpecific, 8);
    bitmapSpecific.push_back(0);
    bitmapSpecific.push_back(8);
    appendI16(bitmapSpecific, 0);
    appendI16(bitmapSpecific, 1);

    std::vector<std::uint8_t> d5Member;
    appendI32(d5Member, 1);
    appendI32(d5Member, static_cast<std::uint32_t>(memberInfo.size()));
    appendI32(d5Member, static_cast<std::uint32_t>(bitmapSpecific.size()));
    d5Member.insert(d5Member.end(), memberInfo.begin(), memberInfo.end());
    d5Member.insert(d5Member.end(), bitmapSpecific.begin(), bitmapSpecific.end());
    BinaryReader d5MemberReader(d5Member, ByteOrder::LittleEndian);
    CastMemberChunk bitmapMember = CastMemberChunk::read(nullptr, d5MemberReader, ChunkId(30), 0x4B1);
    assert(bitmapMember.type() == ChunkType::CASt);
    assert(bitmapMember.memberType() == MemberType::Bitmap);
    assert(bitmapMember.isBitmap());
    assert(bitmapMember.name() == "BitmapName");
    assert(bitmapMember.scriptId() == 77);
    assert(bitmapMember.regPointX() == 8);
    assert(bitmapMember.regPointY() == 7);
    assert(bitmapMember.infoLen() == static_cast<int>(memberInfo.size()));
    assert(bitmapMember.dataLen() == static_cast<int>(bitmapSpecific.size()));
    assert(bitmapMember.info() == memberInfo);
    assert(bitmapMember.specificData() == bitmapSpecific);
    assert(d5MemberReader.order() == ByteOrder::LittleEndian);

    std::vector<std::uint8_t> d4Member;
    appendI16(d4Member, 4);
    appendI32(d4Member, 0);
    d4Member.push_back(static_cast<std::uint8_t>(libreshockwave::cast::code(MemberType::Script)));
    d4Member.push_back(0x99);
    appendI16(d4Member, 2);
    BinaryReader d4MemberReader(d4Member);
    CastMemberChunk scriptMember = CastMemberChunk::read(nullptr, d4MemberReader, ChunkId(31), 0x400);
    assert(scriptMember.isScript());
    assert(scriptMember.dataLen() == 2);
    assert(scriptMember.specificData().size() == 2);
    assert(scriptMember.getScriptType().has_value());
    assert(scriptMember.getScriptType().value() == CastMemberScriptType::Behavior);

    CastMemberChunk textXtra(nullptr,
                             ChunkId(32),
                             MemberType::Xtra,
                             0,
                             8,
                             {},
                             {0, 0, 0, 0, 't', 'e', 'x', 't'},
                             "",
                             0,
                             0,
                             0);
    assert(textXtra.isTextXtra());
}

void testScriptContextChunk() {
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };

    std::vector<std::uint8_t> contextData;
    appendI32(contextData, 0x11111111);
    appendI32(contextData, 0x22222222);
    appendI32(contextData, 2);
    appendI32(contextData, 2);
    appendI16(contextData, 42);
    appendI16(contextData, 0);
    appendI32(contextData, 0);
    appendI32(contextData, 0);
    appendI32(contextData, 0);
    appendI32(contextData, 123);
    appendI16(contextData, 1);
    appendI16(contextData, 0x00A5);
    appendI16(contextData, -3);
    appendI32(contextData, 7);
    appendI32(contextData, 99);
    appendI16(contextData, 0x0011);
    appendI16(contextData, 0);
    appendI32(contextData, 8);
    appendI32(contextData, 0xFFFFFFFFU);
    appendI16(contextData, 0x0022);
    appendI16(contextData, -1);

    BinaryReader contextReader(contextData, ByteOrder::LittleEndian);
    ScriptContextChunk context = ScriptContextChunk::read(nullptr, contextReader, ChunkId(33), 0x4B1);
    assert(context.type() == ChunkType::Lctx);
    assert(context.id().value() == 33);
    assert(context.unknown1() == 0x11111111);
    assert(context.unknown2() == 0x22222222);
    assert(context.entryCount() == 2);
    assert(context.lnamSectionId().value() == 123);
    assert(context.validCount() == 1);
    assert(context.flags() == 0x00A5);
    assert(context.freePtr() == -3);
    assert(context.entries().size() == 2);
    assert(context.entries()[0].unknown == 7);
    assert(context.entries()[0].id.value() == 99);
    assert(context.entries()[0].flags == 0x0011);
    assert(context.entries()[1].unknown == 8);
    assert(context.entries()[1].id.value() == 0);
    assert(context.entries()[1].flags == 0x0022);
    assert(contextReader.order() == ByteOrder::LittleEndian);
}

void testScriptChunkParser() {
    auto putI16 = [](std::vector<std::uint8_t>& data, int offset, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((raw >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(raw & 0xFF);
    };
    auto putI32 = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>(value & 0xFF);
    };

    std::vector<std::uint8_t> scriptData(260, 0);
    putI32(scriptData, 8, 260);
    putI32(scriptData, 12, 260);
    putI16(scriptData, 16, 50);
    putI16(scriptData, 18, 0);
    putI32(scriptData, 38, 0x00000002);

    putI16(scriptData, 50, 0);
    putI32(scriptData, 52, 0);
    putI32(scriptData, 56, 0);
    putI16(scriptData, 60, 2);
    putI32(scriptData, 62, 100);
    putI16(scriptData, 66, 1);
    putI32(scriptData, 68, 104);
    putI16(scriptData, 72, 1);
    putI32(scriptData, 74, 110);
    putI16(scriptData, 78, 3);
    putI32(scriptData, 80, 160);
    putI32(scriptData, 84, 24);
    putI32(scriptData, 88, 190);

    putI16(scriptData, 100, 12);
    putI16(scriptData, 102, 13);
    putI16(scriptData, 104, 14);

    putI16(scriptData, 110, 5);
    putI16(scriptData, 112, 2);
    putI32(scriptData, 114, 11);
    putI32(scriptData, 118, 230);
    putI16(scriptData, 122, 2);
    putI32(scriptData, 124, 152);
    putI16(scriptData, 128, 1);
    putI32(scriptData, 130, 156);
    putI16(scriptData, 134, 1);
    putI32(scriptData, 136, 0);
    putI32(scriptData, 140, 0);
    putI16(scriptData, 144, 0);
    putI16(scriptData, 146, 0);
    putI32(scriptData, 148, 0);
    putI16(scriptData, 152, 7);
    putI16(scriptData, 154, 8);
    putI16(scriptData, 156, 9);

    putI32(scriptData, 160, 1);
    putI32(scriptData, 164, 0);
    putI32(scriptData, 168, 4);
    putI32(scriptData, 172, 1234);
    putI32(scriptData, 176, 9);
    putI32(scriptData, 180, 8);

    putI32(scriptData, 190, 3);
    scriptData[194] = 'h';
    scriptData[195] = 'i';
    scriptData[196] = 0;
    putI32(scriptData, 198, 4);
    putI32(scriptData, 202, 0x3FC00000);

    scriptData[230] = 0x41;
    scriptData[231] = 0xFE;
    scriptData[232] = 0x8E;
    scriptData[233] = 0x12;
    scriptData[234] = 0x34;
    scriptData[235] = 0xC6;
    putI32(scriptData, 236, 5);
    scriptData[240] = 0x01;

    BinaryReader scriptReader(scriptData, ByteOrder::LittleEndian);
    ScriptChunk script = ScriptChunk::read(nullptr, scriptReader, ChunkId(34), 0x4B1, false);
    assert(script.type() == ChunkType::Lscr);
    assert(script.id().value() == 34);
    assert(script.scriptType() == ScriptChunkType::Behavior);
    assert(script.behaviorFlags() == 2);
    assert(script.hasProperties());
    assert(script.properties().size() == 2);
    assert(script.properties()[0].nameId == 12);
    assert(script.properties()[1].nameId == 13);
    assert(script.hasGlobals());
    assert(script.globals().size() == 1);
    assert(script.globals()[0].nameId == 14);

    assert(script.literals().size() == 3);
    assert(script.literals()[0].type == 1);
    assert(std::get<std::string>(script.literals()[0].value) == "hi");
    assert(script.literals()[1].type == 4);
    assert(std::get<int>(script.literals()[1].value) == 1234);
    assert(script.literals()[2].type == 9);
    assert(std::fabs(script.literals()[2].numericValue - 1.5) < 0.0001);
    assert(std::get<std::string>(script.literals()[2].value) == "1.5");

    assert(script.handlers().size() == 1);
    const auto& handler = script.handlers()[0];
    assert(handler.nameId == 5);
    assert(handler.handlerVectorPos == 2);
    assert(handler.bytecodeLength == 11);
    assert(handler.bytecodeOffset == 230);
    assert(handler.argCount == 2);
    assert(handler.localCount == 1);
    assert(handler.globalsCount == 1);
    assert(handler.argNameIds.size() == 2);
    assert(handler.argNameIds[0] == 7);
    assert(handler.argNameIds[1] == 8);
    assert(handler.localNameIds.size() == 1);
    assert(handler.localNameIds[0] == 9);
    assert(handler.instructions.size() == 4);
    assert(handler.getInstructionIndex(0) == 0);
    assert(handler.getInstructionIndex(2) == 1);
    assert(handler.getInstructionIndex(99) == -1);
    assert(handler.instructions[0].opcode == Opcode::PUSH_INT8);
    assert(handler.instructions[0].rawOpcode == 0x41);
    assert(handler.instructions[0].argument == -2);
    assert(handler.instructions[1].opcode == Opcode::SET_GLOBAL2);
    assert(handler.instructions[1].argument == 0x1234);
    assert(handler.instructions[2].opcode == Opcode::PUSH_VAR_REF);
    assert(handler.instructions[2].rawOpcode == 0xC6);
    assert(handler.instructions[2].argument == 5);
    assert(handler.instructions[3].opcode == Opcode::RET);
    assert(handler.instructions[3].toString() == "[10] ret");
    assert(script.findHandlerByNameId(5).has_value());
    assert(!script.findHandlerByNameId(99).has_value());
    assert(script.rawBytecode().empty());
    assert(scriptReader.order() == ByteOrder::LittleEndian);
}

void testScoreChunkParser() {
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };

    auto appendDelta = [&](std::vector<std::uint8_t>& data, int channelOffset, const std::vector<std::uint8_t>& payload) {
        appendI16(data, static_cast<int>(payload.size()));
        appendI16(data, channelOffset);
        data.insert(data.end(), payload.begin(), payload.end());
    };

    std::vector<std::uint8_t> frameEntry;
    appendI32(frameEntry, 0);
    appendI32(frameEntry, 0);
    appendI32(frameEntry, 2);
    appendI16(frameEntry, 14);
    appendI16(frameEntry, 28);
    appendI16(frameEntry, 6);
    appendI16(frameEntry, 6);

    std::vector<std::uint8_t> sprite(28, 0);
    sprite[0] = 1;
    sprite[1] = 0xC8;
    sprite[2] = 0x11;
    sprite[3] = 0x22;
    sprite[4] = 0x00;
    sprite[5] = 0x01;
    sprite[6] = 0x00;
    sprite[7] = 0x09;
    sprite[8] = 0x33;
    sprite[9] = 0x33;
    sprite[10] = 0x44;
    sprite[11] = 0x44;
    sprite[12] = 0x00;
    sprite[13] = 0x32;
    sprite[14] = 0x00;
    sprite[15] = 0x3C;
    sprite[16] = 0x00;
    sprite[17] = 0x14;
    sprite[18] = 0x00;
    sprite[19] = 0x1E;
    sprite[20] = 0x30;
    sprite[21] = 0x80;
    sprite[22] = 0x60;
    sprite[24] = 0x12;
    sprite[25] = 0x23;
    sprite[26] = 0x13;
    sprite[27] = 0x24;

    std::vector<std::uint8_t> tempo(7, 0);
    tempo[6] = 30;
    std::vector<std::uint8_t> palette{0x00, 0x01, 0x00, 0x07};

    std::vector<std::uint8_t> frame0;
    appendDelta(frame0, 2 * 28, sprite);
    appendDelta(frame0, 4 * 28, tempo);
    appendDelta(frame0, 5 * 28, palette);
    appendI16(frameEntry, static_cast<int>(frame0.size()) + 2);
    frameEntry.insert(frameEntry.end(), frame0.begin(), frame0.end());

    std::vector<std::uint8_t> tempo2(7, 0);
    tempo2[6] = 45;
    std::vector<std::uint8_t> frame1;
    appendDelta(frame1, 4 * 28, tempo2);
    appendI16(frameEntry, static_cast<int>(frame1.size()) + 2);
    frameEntry.insert(frameEntry.end(), frame1.begin(), frame1.end());

    std::vector<std::uint8_t> primary;
    appendI32(primary, 1);
    appendI32(primary, 2);
    appendI32(primary, 0);
    appendI32(primary, 0);
    appendI32(primary, 2);
    appendI16(primary, 0);
    appendI32(primary, 0);
    appendI16(primary, 0);
    appendI32(primary, 0);
    appendI32(primary, 0);
    appendI32(primary, 0);
    appendI32(primary, 0);

    std::vector<std::uint8_t> secondary;
    appendI16(secondary, 1);
    appendI16(secondary, 12);
    appendI32(secondary, 99);

    std::vector<std::uint8_t> scoreData;
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size() + primary.size() + secondary.size()));
    appendI32(scoreData, 0x11111111);
    appendI32(scoreData, 0x22222222);
    appendI32(scoreData, 4);
    appendI32(scoreData, 0x33333333);
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size() + primary.size() + secondary.size()));
    appendI32(scoreData, 0);
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size()));
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size()));
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size() + primary.size()));
    appendI32(scoreData, static_cast<std::uint32_t>(frameEntry.size() + primary.size() + secondary.size()));
    scoreData.insert(scoreData.end(), frameEntry.begin(), frameEntry.end());
    scoreData.insert(scoreData.end(), primary.begin(), primary.end());
    scoreData.insert(scoreData.end(), secondary.begin(), secondary.end());

    BinaryReader scoreReader(scoreData, ByteOrder::LittleEndian);
    ScoreChunk score = ScoreChunk::read(nullptr, scoreReader, ChunkId(35), 0x4B1);
    assert(score.type() == ChunkType::VWSC);
    assert(score.header().entryCount == 4);
    assert(score.entries().size() == 4);
    assert(score.entries()[0].size() == frameEntry.size());
    assert(score.entries()[1].empty());
    assert(score.getFrameCount() == 2);
    assert(score.getChannelCount() == 6);
    assert(score.getSpriteRecordSize() == 28);
    assert(score.getRawChannelData().size() == 2 * 6 * 28);
    assert(score.getFrameTempo(0) == 30);
    assert(score.getFrameTempo(1) == 45);
    assert(score.getFrameTempo(99) == 45);
    assert(score.getFramePalette(0).has_value());
    assert(score.getFramePalette(0)->castLib == 1);
    assert(score.getFramePalette(0)->castMember == 7);
    assert(score.getFramePalette(1).has_value());
    assert(score.getFramePalette(1)->castMember == 7);

    assert(score.frameData().frameChannelData.size() == 2);
    const auto& channelEntry = score.frameData().frameChannelData[0];
    assert(channelEntry.frameIndex.value() == 0);
    assert(channelEntry.channelIndex.value() == 2);
    const auto& channel = channelEntry.data;
    assert(channel.spriteType == 1);
    assert(channel.ink == 8);
    assert(channel.trails == 1);
    assert(channel.stretch == 1);
    assert(channel.castLib == 1);
    assert(channel.castMember == 9);
    assert(channel.castLibId()->value() == 1);
    assert(channel.memberId()->value() == 9);
    assert(channel.posY == 50);
    assert(channel.posX == 60);
    assert(channel.height == 20);
    assert(channel.width == 30);
    assert(channel.blendByte == 0x80);
    assert(channel.isForeColorRGB());
    assert(channel.isBackColorRGB());
    assert(channel.resolvedForeColor() == 0x111213);
    assert(channel.resolvedBackColor() == 0x222324);
    assert(channel.isFlipH());
    assert(channel.isFlipV());

    assert(score.frameIntervals().size() == 1);
    const auto& interval = score.frameIntervals()[0];
    assert(interval.primary.startFrameId().value() == 1);
    assert(interval.primary.endFrameId().value() == 2);
    assert(interval.primary.channelId().value() == 2);
    assert(interval.secondary.has_value());
    assert(interval.secondary->castLib == 1);
    assert(interval.secondary->castMember == 12);
    assert(interval.secondary->castLibId()->value() == 1);
    assert(interval.secondary->memberId()->value() == 12);
    assert(interval.secondary->unk0 == 99);
    assert(scoreReader.order() == ByteOrder::LittleEndian);
}

void testDirectorFileRifxLoader() {
    auto appendFourCC = [](std::vector<std::uint8_t>& data, const std::string& value) {
        data.insert(data.end(), value.begin(), value.end());
    };
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto putI32 = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>(value & 0xFF);
    };

    std::vector<std::uint8_t> configData(80, 0);
    auto putI16At = [](std::vector<std::uint8_t>& data, int offset, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((raw >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(raw & 0xFF);
    };
    putI16At(configData, 2, 1200);
    putI16At(configData, 4, 10);
    putI16At(configData, 6, 20);
    putI16At(configData, 8, 250);
    putI16At(configData, 10, 340);
    putI16At(configData, 12, 1);
    putI16At(configData, 14, 99);
    putI16At(configData, 20, 3);
    putI16At(configData, 22, 12);
    putI16At(configData, 24, 1);
    putI16At(configData, 26, 5);
    putI16At(configData, 28, 8);
    putI16At(configData, 36, 0x0207);
    putI16At(configData, 54, 30);
    putI16At(configData, 56, 2);

    std::vector<std::uint8_t> namesData(20, 0);
    putI16At(namesData, 16, 20);
    putI16At(namesData, 18, 2);
    namesData.push_back(2);
    namesData.insert(namesData.end(), {'g', 'o'});
    namesData.push_back(4);
    namesData.insert(namesData.end(), {'s', 't', 'o', 'p'});
    const std::vector<std::uint8_t> rawData{0xDE, 0xAD, 0xBE, 0xEF};
    std::vector<std::uint8_t> keyData;
    appendI16(keyData, 12);
    appendI16(keyData, 12);
    appendI32(keyData, 2);
    appendI32(keyData, 2);
    appendI32(keyData, 4);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("STXT"));
    appendI32(keyData, 5);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("VWSC"));
    std::vector<std::uint8_t> textData;
    appendI32(textData, 8);
    appendI32(textData, 5);
    textData.insert(textData.end(), {'H', 'e', 'l', 'l', 'o'});
    const std::vector<std::uint8_t> scoreData;

    constexpr int mmapOffset = 32;
    constexpr int mmapDataStart = mmapOffset + 8 + 24 + 120;
    const int configOffset = mmapDataStart - 8;
    const int namesDataStart = mmapDataStart + static_cast<int>(configData.size());
    const int namesOffset = namesDataStart - 8;
    const int rawDataStart = namesDataStart + static_cast<int>(namesData.size());
    const int rawOffset = rawDataStart - 8;
    const int keyDataStart = rawDataStart + static_cast<int>(rawData.size());
    const int keyOffset = keyDataStart - 8;
    const int textDataStart = keyDataStart + static_cast<int>(keyData.size());
    const int textOffset = textDataStart - 8;
    const int scoreDataStart = textDataStart + static_cast<int>(textData.size());
    const int scoreOffset = scoreDataStart - 8;

    std::vector<std::uint8_t> fileData;
    appendFourCC(fileData, "RIFX");
    appendI32(fileData, 0);
    appendFourCC(fileData, "MV93");
    appendFourCC(fileData, "imap");
    appendI32(fileData, 12);
    appendI32(fileData, 1);
    appendI32(fileData, mmapOffset);
    appendI32(fileData, 0x0207);
    appendFourCC(fileData, "mmap");
    appendI32(fileData, 24 + 120);
    appendI16(fileData, 24);
    appendI16(fileData, 20);
    appendI32(fileData, 6);
    appendI32(fileData, 6);
    appendI32(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("DRCF"));
    appendI32(fileData, static_cast<std::uint32_t>(configData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(configOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("Lnam"));
    appendI32(fileData, static_cast<std::uint32_t>(namesData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(namesOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("junk"));
    appendI32(fileData, static_cast<std::uint32_t>(rawData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(rawOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("KEY*"));
    appendI32(fileData, static_cast<std::uint32_t>(keyData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(keyOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("STXT"));
    appendI32(fileData, static_cast<std::uint32_t>(textData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(textOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("VWSC"));
    appendI32(fileData, static_cast<std::uint32_t>(scoreData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(scoreOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    fileData.insert(fileData.end(), configData.begin(), configData.end());
    fileData.insert(fileData.end(), namesData.begin(), namesData.end());
    fileData.insert(fileData.end(), rawData.begin(), rawData.end());
    fileData.insert(fileData.end(), keyData.begin(), keyData.end());
    fileData.insert(fileData.end(), textData.begin(), textData.end());
    fileData.insert(fileData.end(), scoreData.begin(), scoreData.end());
    putI32(fileData, 4, static_cast<std::uint32_t>(fileData.size() - 8));

    auto file = DirectorFile::load(fileData);
    assert(file->endian() == ByteOrder::BigEndian);
    assert(!file->isAfterburner());
    assert(file->movieType() == ChunkType::MV93);
    assert(file->version() == 0x0207);
    assert(file->chunkInfo().size() == 6);
    assert(file->chunks().size() == 6);
    assert(file->config().get() != nullptr);
    assert(file->config()->file() == file.get());
    assert(file->config()->stageWidth() == 320);
    assert(file->stageWidth() == 320);
    assert(file->stageHeight() == 240);
    assert(file->config()->tempo() == 30);
    assert(file->tempo() == 30);
    assert(file->scriptNames().get() != nullptr);
    assert(file->scriptNames()->names().size() == 2);
    assert(file->scriptNames()->getName(1) == "stop");
    assert(file->keyTable().get() != nullptr);
    assert(file->scoreChunk().get() != nullptr);
    assert(file->getScoreTempo(0) == -1);
    assert(!file->getScorePalette(0).has_value());
    assert(file->getChunkInfo(ChunkId(0))->type() == ChunkType::DRCF);
    assert(file->getChunk(ChunkId(1))->type() == ChunkType::Lnam);
    assert(file->getChunk(ChunkId(2))->type() == ChunkType::JUNK);
    assert(file->getChunk(ChunkId(3))->type() == ChunkType::KEYp);
    assert(file->getChunk(ChunkId(4))->type() == ChunkType::STXT);
    assert(file->getChunk(ChunkId(5))->type() == ChunkType::VWSC);
    auto member7 = std::make_shared<CastMemberChunk>(file.get(),
                                                     ChunkId(7),
                                                     MemberType::FilmLoop,
                                                     0,
                                                     0,
                                                     std::vector<std::uint8_t>{},
                                                     std::vector<std::uint8_t>{},
                                                     "Loop",
                                                     0,
                                                     0,
                                                     0);
    auto textChunks = file->getTextChunksForMember(member7);
    assert(textChunks.size() == 1);
    assert(textChunks.front()->text() == "Hello");
    assert(file->getTextForMember(member7) == textChunks.front());
    assert(file->getScoreForMember(member7) == file->scoreChunk());
    auto directTextMember = std::make_shared<CastMemberChunk>(file.get(),
                                                              ChunkId(4),
                                                              MemberType::Text,
                                                              0,
                                                              0,
                                                              std::vector<std::uint8_t>{},
                                                              std::vector<std::uint8_t>{},
                                                              "Direct Text",
                                                              0,
                                                              0,
                                                              0);
    assert(file->getTextForMember(directTextMember)->text() == "Hello");
    file->releaseNonEssentialChunks();
    assert(file->chunks().size() == 5);
    auto reparsedRaw = file->getChunk(ChunkId(2));
    assert(reparsedRaw.get() != nullptr);
    assert(reparsedRaw->type() == ChunkType::JUNK);
    assert(file->chunks().size() == 6);
}

void testAfterburnerReader() {
#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
    auto appendFourCC = [](std::vector<std::uint8_t>& data, const std::string& value) {
        data.insert(data.end(), value.begin(), value.end());
    };
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto appendVarInt = [](std::vector<std::uint8_t>& data, int value) {
        std::vector<std::uint8_t> groups;
        groups.push_back(static_cast<std::uint8_t>(value & 0x7F));
        value >>= 7;
        while (value > 0) {
            groups.push_back(static_cast<std::uint8_t>(value & 0x7F));
            value >>= 7;
        }
        for (auto it = groups.rbegin(); it != groups.rend(); ++it) {
            std::uint8_t byte = *it;
            if (it + 1 != groups.rend()) {
                byte |= 0x80;
            }
            data.push_back(byte);
        }
    };
    auto appendMoa = [&](std::vector<std::uint8_t>& data, const MoaID& value) {
        appendI32(data, value.data1);
        appendI16(data, value.data2);
        appendI16(data, value.data3);
        appendI32(data, value.data4);
        appendI32(data, value.data5);
    };
    auto compressZlib = [](const std::vector<std::uint8_t>& input) {
        uLongf length = compressBound(static_cast<uLong>(input.size()));
        std::vector<std::uint8_t> output(static_cast<std::size_t>(length));
        const int status = compress2(output.data(), &length, input.data(), static_cast<uLong>(input.size()), Z_BEST_SPEED);
        assert(status == Z_OK);
        output.resize(static_cast<std::size_t>(length));
        return output;
    };

    const std::vector<std::uint8_t> chunkData{0xAA, 0xBB, 0xCC};
    std::vector<std::uint8_t> ilsData;
    appendVarInt(ilsData, 10);
    ilsData.insert(ilsData.end(), chunkData.begin(), chunkData.end());
    const auto compressedIls = compressZlib(ilsData);

    std::vector<std::uint8_t> fcdrData;
    appendI16(fcdrData, 2);
    appendMoa(fcdrData, MoaID::NULL_COMPRESSION);
    appendMoa(fcdrData, MoaID::ZLIB_COMPRESSION);
    fcdrData.insert(fcdrData.end(), {'n', 'o', 'n', 'e', 0, 'z', 'l', 'i', 'b', 0});
    const auto compressedFcdr = compressZlib(fcdrData);

    std::vector<std::uint8_t> abmpData;
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, 2);
    appendVarInt(abmpData, 2);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, static_cast<int>(compressedIls.size()));
    appendVarInt(abmpData, static_cast<int>(ilsData.size()));
    appendVarInt(abmpData, 1);
    appendFourCC(abmpData, "ILS ");
    appendVarInt(abmpData, 10);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, static_cast<int>(chunkData.size()));
    appendVarInt(abmpData, static_cast<int>(chunkData.size()));
    appendVarInt(abmpData, 0);
    appendFourCC(abmpData, "Lnam");
    const auto compressedAbmp = compressZlib(abmpData);

    std::vector<std::uint8_t> fverData;
    appendVarInt(fverData, 5);
    appendVarInt(fverData, 1200);
    appendVarInt(fverData, 2);
    fverData.insert(fverData.end(), {'D', '6'});

    std::vector<std::uint8_t> data;
    appendFourCC(data, "Fver");
    appendVarInt(data, static_cast<int>(fverData.size()));
    data.insert(data.end(), fverData.begin(), fverData.end());
    appendFourCC(data, "Fcdr");
    appendVarInt(data, static_cast<int>(compressedFcdr.size()));
    data.insert(data.end(), compressedFcdr.begin(), compressedFcdr.end());
    appendFourCC(data, "ABMP");
    std::vector<std::uint8_t> abmpContent;
    appendVarInt(abmpContent, 0);
    appendVarInt(abmpContent, static_cast<int>(abmpData.size()));
    abmpContent.insert(abmpContent.end(), compressedAbmp.begin(), compressedAbmp.end());
    appendVarInt(data, static_cast<int>(abmpContent.size()));
    data.insert(data.end(), abmpContent.begin(), abmpContent.end());
    appendFourCC(data, "FGEI");
    appendVarInt(data, 0);
    data.insert(data.end(), compressedIls.begin(), compressedIls.end());

    AfterburnerReader reader(BinaryReader(data), ByteOrder::BigEndian);
    reader.parse();
    assert(reader.imapVersion() == 5);
    assert(reader.directorVersion() == 1200);
    assert(reader.versionString() == "D6");
    assert(reader.compressionTypes().size() == 2);
    assert(reader.compressionTypes()[1].isZlib());
    assert(reader.chunkCount() == 2);
    const auto* info = reader.getChunkInfo(10);
    assert(info != nullptr);
    assert(info->fourCC == "Lnam");
    assert(info->compressedSize == 3);
    assert(reader.getChunksByType("Lnam").size() == 1);
    const auto loadedChunk = reader.getChunkData(10);
    assert(loadedChunk.has_value());
    assert(loadedChunk.value() == chunkData);
    const auto loadedByType = reader.getChunkDataByType("Lnam");
    assert(loadedByType.has_value());
    assert(loadedByType.value() == chunkData);
    const auto ils = reader.getChunkData(2);
    assert(ils.has_value());
    assert(ils.value() == ilsData);
#endif
}

void testDirectorFileAfterburnerLoader() {
#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
    auto appendFourCC = [](std::vector<std::uint8_t>& data, const std::string& value) {
        data.insert(data.end(), value.begin(), value.end());
    };
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto putI16At = [](std::vector<std::uint8_t>& data, int offset, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((raw >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(raw & 0xFF);
    };
    auto putI32At = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>(value & 0xFF);
    };
    auto appendVarInt = [](std::vector<std::uint8_t>& data, int value) {
        std::vector<std::uint8_t> groups;
        groups.push_back(static_cast<std::uint8_t>(value & 0x7F));
        value >>= 7;
        while (value > 0) {
            groups.push_back(static_cast<std::uint8_t>(value & 0x7F));
            value >>= 7;
        }
        for (auto it = groups.rbegin(); it != groups.rend(); ++it) {
            std::uint8_t byte = *it;
            if (it + 1 != groups.rend()) {
                byte |= 0x80;
            }
            data.push_back(byte);
        }
    };
    auto appendMoa = [&](std::vector<std::uint8_t>& data, const MoaID& value) {
        appendI32(data, value.data1);
        appendI16(data, value.data2);
        appendI16(data, value.data3);
        appendI32(data, value.data4);
        appendI32(data, value.data5);
    };
    auto compressZlib = [](const std::vector<std::uint8_t>& input) {
        uLongf length = compressBound(static_cast<uLong>(input.size()));
        std::vector<std::uint8_t> output(static_cast<std::size_t>(length));
        const int status = compress2(output.data(), &length, input.data(), static_cast<uLong>(input.size()), Z_BEST_SPEED);
        assert(status == Z_OK);
        output.resize(static_cast<std::size_t>(length));
        return output;
    };

    std::vector<std::uint8_t> configData(80, 0);
    putI16At(configData, 2, 1200);
    putI16At(configData, 4, 0);
    putI16At(configData, 6, 0);
    putI16At(configData, 8, 240);
    putI16At(configData, 10, 320);
    putI16At(configData, 12, 1);
    putI16At(configData, 14, 50);
    putI16At(configData, 20, 3);
    putI16At(configData, 22, 12);
    putI16At(configData, 24, 1);
    putI16At(configData, 26, 5);
    putI16At(configData, 28, 8);
    putI16At(configData, 36, 0x0207);
    putI16At(configData, 54, 24);
    putI16At(configData, 56, 2);

    std::vector<std::uint8_t> namesData(20, 0);
    putI16At(namesData, 16, 20);
    putI16At(namesData, 18, 1);
    namesData.push_back(5);
    namesData.insert(namesData.end(), {'s', 't', 'a', 'r', 't'});
    const std::vector<std::uint8_t> rawData{0xCA, 0xFE, 0xBA, 0xBE};

    std::vector<std::uint8_t> ilsData;
    appendVarInt(ilsData, 10);
    ilsData.insert(ilsData.end(), configData.begin(), configData.end());
    appendVarInt(ilsData, 11);
    ilsData.insert(ilsData.end(), namesData.begin(), namesData.end());
    appendVarInt(ilsData, 12);
    ilsData.insert(ilsData.end(), rawData.begin(), rawData.end());
    const auto compressedIls = compressZlib(ilsData);

    std::vector<std::uint8_t> fcdrData;
    appendI16(fcdrData, 2);
    appendMoa(fcdrData, MoaID::NULL_COMPRESSION);
    appendMoa(fcdrData, MoaID::ZLIB_COMPRESSION);
    fcdrData.insert(fcdrData.end(), {'n', 'o', 'n', 'e', 0, 'z', 'l', 'i', 'b', 0});
    const auto compressedFcdr = compressZlib(fcdrData);

    std::vector<std::uint8_t> abmpData;
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, 4);
    appendVarInt(abmpData, 2);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, static_cast<int>(compressedIls.size()));
    appendVarInt(abmpData, static_cast<int>(ilsData.size()));
    appendVarInt(abmpData, 1);
    appendFourCC(abmpData, "ILS ");
    appendVarInt(abmpData, 10);
    appendVarInt(abmpData, 0);
    appendVarInt(abmpData, static_cast<int>(configData.size()));
    appendVarInt(abmpData, static_cast<int>(configData.size()));
    appendVarInt(abmpData, 0);
    appendFourCC(abmpData, "DRCF");
    appendVarInt(abmpData, 11);
    appendVarInt(abmpData, static_cast<int>(configData.size()));
    appendVarInt(abmpData, static_cast<int>(namesData.size()));
    appendVarInt(abmpData, static_cast<int>(namesData.size()));
    appendVarInt(abmpData, 0);
    appendFourCC(abmpData, "Lnam");
    appendVarInt(abmpData, 12);
    appendVarInt(abmpData, static_cast<int>(configData.size() + namesData.size()));
    appendVarInt(abmpData, static_cast<int>(rawData.size()));
    appendVarInt(abmpData, static_cast<int>(rawData.size()));
    appendVarInt(abmpData, 0);
    appendFourCC(abmpData, "junk");
    const auto compressedAbmp = compressZlib(abmpData);

    std::vector<std::uint8_t> fverData;
    appendVarInt(fverData, 5);
    appendVarInt(fverData, 1200);

    std::vector<std::uint8_t> fileData;
    appendFourCC(fileData, "RIFX");
    appendI32(fileData, 0);
    appendFourCC(fileData, "FGDM");
    appendFourCC(fileData, "Fver");
    appendVarInt(fileData, static_cast<int>(fverData.size()));
    fileData.insert(fileData.end(), fverData.begin(), fverData.end());
    appendFourCC(fileData, "Fcdr");
    appendVarInt(fileData, static_cast<int>(compressedFcdr.size()));
    fileData.insert(fileData.end(), compressedFcdr.begin(), compressedFcdr.end());
    appendFourCC(fileData, "ABMP");
    std::vector<std::uint8_t> abmpContent;
    appendVarInt(abmpContent, 0);
    appendVarInt(abmpContent, static_cast<int>(abmpData.size()));
    abmpContent.insert(abmpContent.end(), compressedAbmp.begin(), compressedAbmp.end());
    appendVarInt(fileData, static_cast<int>(abmpContent.size()));
    fileData.insert(fileData.end(), abmpContent.begin(), abmpContent.end());
    appendFourCC(fileData, "FGEI");
    appendVarInt(fileData, 0);
    fileData.insert(fileData.end(), compressedIls.begin(), compressedIls.end());
    putI32At(fileData, 4, static_cast<std::uint32_t>(fileData.size() - 8));

    auto file = DirectorFile::load(fileData);
    assert(file->isAfterburner());
    assert(file->movieType() == ChunkType::FGDM);
    assert(file->version() == 0x0207);
    assert(file->chunkInfo().size() == 4);
    assert(file->chunks().size() == 3);
    assert(file->getChunkInfo(ChunkId(10))->type() == ChunkType::DRCF);
    assert(file->config().get() != nullptr);
    assert(file->config()->file() == file.get());
    assert(file->config()->stageWidth() == 320);
    assert(file->config()->stageHeight() == 240);
    assert(file->config()->tempo() == 24);
    assert(file->scriptNames().get() != nullptr);
    assert(file->scriptNames()->getName(0) == "start");
    assert(file->getChunk(ChunkId(11))->type() == ChunkType::Lnam);
    assert(file->getChunk(ChunkId(12))->type() == ChunkType::JUNK);
    file->releaseNonEssentialChunks();
    assert(file->chunks().size() == 2);
    auto reparsedRaw = file->getChunk(ChunkId(12));
    assert(reparsedRaw.get() != nullptr);
    assert(reparsedRaw->type() == ChunkType::JUNK);
    assert(file->chunks().size() == 3);
#endif
}

void testDirectorFileRiffLoader() {
    auto appendFourCC = [](std::vector<std::uint8_t>& data, const std::string& value) {
        data.insert(data.end(), value.begin(), value.end());
    };
    auto appendI16 = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
    };
    auto appendU32LE = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
    };
    auto appendI32 = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
    };
    auto putI16At = [](std::vector<std::uint8_t>& data, int offset, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>((raw >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(raw & 0xFF);
    };
    auto putU32LE = [](std::vector<std::uint8_t>& data, int offset, std::uint32_t value) {
        data[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>(value & 0xFF);
        data[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>((value >> 8) & 0xFF);
        data[static_cast<std::size_t>(offset + 2)] = static_cast<std::uint8_t>((value >> 16) & 0xFF);
        data[static_cast<std::size_t>(offset + 3)] = static_cast<std::uint8_t>((value >> 24) & 0xFF);
    };

    std::vector<std::uint8_t> configData(80, 0);
    putI16At(configData, 2, 1000);
    putI16At(configData, 4, 0);
    putI16At(configData, 6, 0);
    putI16At(configData, 8, 200);
    putI16At(configData, 10, 300);
    putI16At(configData, 12, 1);
    putI16At(configData, 14, 20);
    putI16At(configData, 20, 3);
    putI16At(configData, 22, 10);
    putI16At(configData, 24, 1);
    putI16At(configData, 26, 4);
    putI16At(configData, 28, 8);
    putI16At(configData, 36, 0x0205);
    putI16At(configData, 54, 18);
    putI16At(configData, 56, 1);

    std::vector<std::uint8_t> namesData(20, 0);
    putI16At(namesData, 16, 20);
    putI16At(namesData, 18, 1);
    namesData.push_back(4);
    namesData.insert(namesData.end(), {'m', 'a', 'i', 'n'});

    std::vector<std::uint8_t> fileData;
    appendFourCC(fileData, "RIFF");
    appendU32LE(fileData, 0);
    appendFourCC(fileData, "RMMP");
    appendFourCC(fileData, "CFTC");
    appendU32LE(fileData, 36);
    appendU32LE(fileData, 0);

    const int firstEntryOffset = static_cast<int>(fileData.size());
    appendFourCC(fileData, "DRCF");
    appendU32LE(fileData, static_cast<std::uint32_t>(configData.size() + 6));
    appendU32LE(fileData, 100);
    appendU32LE(fileData, 0);
    appendFourCC(fileData, "Lnam");
    appendU32LE(fileData, static_cast<std::uint32_t>(namesData.size() + 6));
    appendU32LE(fileData, 101);
    appendU32LE(fileData, 0);

    auto appendResource = [&](const std::string& fourcc, int id, const std::vector<std::uint8_t>& payload) {
        const int offset = static_cast<int>(fileData.size());
        appendFourCC(fileData, fourcc);
        appendU32LE(fileData, static_cast<std::uint32_t>(payload.size() + 6));
        appendU32LE(fileData, static_cast<std::uint32_t>(id));
        fileData.push_back(1);
        fileData.push_back('x');
        fileData.insert(fileData.end(), payload.begin(), payload.end());
        return offset;
    };

    const int configResourceOffset = appendResource("DRCF", 100, configData);
    const int namesResourceOffset = appendResource("Lnam", 101, namesData);
    putU32LE(fileData, firstEntryOffset + 12, static_cast<std::uint32_t>(configResourceOffset));
    putU32LE(fileData, firstEntryOffset + 28, static_cast<std::uint32_t>(namesResourceOffset));
    putU32LE(fileData, 4, static_cast<std::uint32_t>(fileData.size() - 8));

    auto file = DirectorFile::load(fileData);
    assert(file->endian() == ByteOrder::LittleEndian);
    assert(!file->isAfterburner());
    assert(file->movieType() == ChunkType::MV93);
    assert(file->version() == 0x0205);
    assert(file->chunkInfo().size() == 2);
    assert(file->chunks().size() == 2);
    assert(file->config().get() != nullptr);
    assert(file->config()->stageWidth() == 300);
    assert(file->config()->stageHeight() == 200);
    assert(file->config()->tempo() == 18);
    assert(file->scriptNames().get() != nullptr);
    assert(file->scriptNames()->getName(0) == "main");
    assert(file->getChunkInfo(ChunkId(0))->type() == ChunkType::DRCF);
    assert(file->getChunk(ChunkId(1))->type() == ChunkType::Lnam);
}

void testLookupHelpers() {
    auto cast = std::make_shared<CastChunk>(nullptr, ChunkId(80), std::vector<int>{41, 42});
    auto member5 = std::make_shared<CastMemberChunk>(nullptr,
                                                     ChunkId(5),
                                                     MemberType::Bitmap,
                                                     0,
                                                     0,
                                                     std::vector<std::uint8_t>{},
                                                     std::vector<std::uint8_t>{},
                                                     "",
                                                     0,
                                                     0,
                                                     0);
    auto member42 = std::make_shared<CastMemberChunk>(nullptr,
                                                      ChunkId(42),
                                                      MemberType::Bitmap,
                                                      0,
                                                      0,
                                                      std::vector<std::uint8_t>{},
                                                      std::vector<std::uint8_t>{},
                                                      "",
                                                      0,
                                                      0,
                                                      0);
    CastListChunk::CastListEntry entry{"Internal", "", 0, 5, 6, 2, 1};
    auto castList = std::make_shared<CastListChunk>(nullptr, ChunkId(81), 0, 4, 1, std::vector<CastListChunk::CastListEntry>{entry});
    CastMemberLookup castLookup({cast}, {member5, member42}, castList, nullptr);
    assert(castLookup.getMappedCast(1) == cast);
    assert(castLookup.getByIndex(1, 0) == member5);
    assert(castLookup.getByNumber(1, 6) == member42);
    assert(castLookup.getByNumber(1, 5) == nullptr);

    auto script = std::make_shared<ScriptChunk>(nullptr,
                                                ChunkId(200),
                                                ScriptChunkType::Behavior,
                                                0,
                                                std::vector<ScriptChunk::Handler>{},
                                                std::vector<ScriptChunk::LiteralEntry>{},
                                                std::vector<ScriptChunk::PropertyEntry>{},
                                                std::vector<ScriptChunk::GlobalEntry>{},
                                                std::vector<std::uint8_t>{});
    ScriptContextChunk::ScriptEntry scriptEntry{0, ChunkId(200), 0};
    auto context = std::make_shared<ScriptContextChunk>(nullptr,
                                                        ChunkId(201),
                                                        0,
                                                        0,
                                                        1,
                                                        ChunkId(202),
                                                        1,
                                                        0,
                                                        0,
                                                        std::vector<ScriptContextChunk::ScriptEntry>{scriptEntry});
    auto scriptMember = std::make_shared<CastMemberChunk>(nullptr,
                                                          ChunkId(203),
                                                          MemberType::Script,
                                                          0,
                                                          2,
                                                          std::vector<std::uint8_t>{},
                                                          std::vector<std::uint8_t>{0, 2},
                                                          "Behavior",
                                                          1,
                                                          0,
                                                          0);
    ScriptLookup scriptLookup({script}, {context}, {scriptMember});
    assert(scriptLookup.getByContextId(1) == script);
    assert(scriptLookup.getAllByContextId(1).size() == 1);
    assert(scriptLookup.getByContextId(99) == nullptr);
    assert(scriptLookup.getScriptType(script).has_value());
    assert(scriptLookup.getScriptType(script).value() == CastMemberScriptType::Behavior);

    std::vector<std::uint32_t> customColors(256, 0);
    customColors[0] = 0x112233U;
    customColors[1] = 0x445566U;
    const auto systemMacTail = Palette::systemMacPalette().getColor(248);
    assert(systemMacTail != 0);
    auto paletteChunk = std::make_shared<PaletteChunk>(nullptr, ChunkId(91), customColors);
    auto paletteMember = std::make_shared<CastMemberChunk>(nullptr,
                                                           ChunkId(90),
                                                           MemberType::Palette,
                                                           0,
                                                           0,
                                                           std::vector<std::uint8_t>{},
                                                           std::vector<std::uint8_t>{},
                                                           "Palette",
                                                           0,
                                                           0,
                                                           0);
    auto paletteCast = std::make_shared<CastChunk>(nullptr, ChunkId(92), std::vector<int>{0, 90});
    CastListChunk::CastListEntry paletteEntry{"PaletteLib", "", 0, 10, 11, 2, 1};
    auto paletteCastList = std::make_shared<CastListChunk>(
        nullptr, ChunkId(93), 0, 4, 1, std::vector<CastListChunk::CastListEntry>{paletteEntry});
    auto paletteKey = std::make_shared<KeyTableChunk>(
        nullptr,
        ChunkId(94),
        std::vector<KeyTableChunk::KeyTableEntry>{
            KeyTableChunk::KeyTableEntry{ChunkId(91), ChunkId(90), BinaryReader::fourCC("CLUT")}
        });
    PaletteResolver paletteResolver(
        {paletteCast},
        {paletteMember},
        {paletteChunk},
        paletteCastList,
        nullptr,
        paletteKey,
        [paletteChunk](ChunkId id) -> std::shared_ptr<libreshockwave::chunks::Chunk> {
            return id.value() == paletteChunk->id().value() ? paletteChunk : nullptr;
        });
    assert(paletteResolver.resolve(Palette::RAINBOW).get() == &Palette::rainbowPalette());

    auto resolvedByMemberNumber = paletteResolver.resolveExact(10);
    assert(resolvedByMemberNumber.get() != nullptr);
    assert(resolvedByMemberNumber->getColor(0) == 0x112233U);
    assert(resolvedByMemberNumber->getColor(1) == 0x445566U);
    assert(resolvedByMemberNumber->getColor(248) == systemMacTail);
    assert(paletteResolver.resolve(10).get() == resolvedByMemberNumber.get());
    assert(paletteResolver.resolveExact(90).get() == resolvedByMemberNumber.get());
    assert(paletteResolver.resolveExact(91).get() == resolvedByMemberNumber.get());

    PaletteResolver indexedPaletteResolver(
        {},
        {paletteMember},
        {paletteChunk},
        nullptr,
        nullptr,
        paletteKey,
        [paletteChunk](ChunkId id) -> std::shared_ptr<libreshockwave::chunks::Chunk> {
            return id.value() == paletteChunk->id().value() ? paletteChunk : nullptr;
        });
    assert(indexedPaletteResolver.resolveExact(0)->getColor(0) == 0x112233U);
    assert(paletteResolver.resolveExact(999).get() == nullptr);
    assert(paletteResolver.resolve(999)->getColor(0) == 0x112233U);

    PaletteResolver emptyPaletteResolver({}, {}, {}, nullptr, nullptr, nullptr, {});
    assert(emptyPaletteResolver.resolveExact(999).get() == nullptr);
    assert(emptyPaletteResolver.resolve(999).get() == &Palette::systemMacPalette());

    DirectorFile emptyFile(ByteOrder::BigEndian, false, 0, ChunkType::MV93);
    assert(emptyFile.stageWidth() == 0);
    assert(emptyFile.stageHeight() == 0);
    assert(emptyFile.tempo() == 15);
    assert(emptyFile.getScoreTempo(0) == -1);
    assert(!emptyFile.getScorePalette(0).has_value());
    assert(emptyFile.resolvePalette(Palette::SYSTEM_WIN).get() == &Palette::systemWinPalette());
    assert(emptyFile.resolvePaletteExact(999).get() == nullptr);
    assert(emptyFile.resolvePaletteByMemberNumber(42).get() == &Palette::systemMacPalette());
}

int main() {
    testBinaryReaderEndianAndBounds();
    testBinaryReaderStringsAndFourCC();
    testBinaryReaderNumbers();
    testBinaryReaderZlib();
    testIdsAndEnums();
    testFormatTypes();
    testLingoDatumTypes();
    testLingoOpcodeHelpers();
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
    testCastListAndMemberChunks();
    testScriptContextChunk();
    testScriptChunkParser();
    testScoreChunkParser();
    testDirectorFileRifxLoader();
    testAfterburnerReader();
    testDirectorFileAfterburnerLoader();
    testDirectorFileRiffLoader();
    testLookupHelpers();

    std::cout << "LibreShockwave C++ SDK foundation tests passed\n";
    return 0;
}
