#include <cassert>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <variant>
#include <vector>

#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
#include <zlib.h>
#endif

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/W3DFile.hpp"
#include "libreshockwave/audio/SoundConverter.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/bitmap/BitmapColorizer.hpp"
#include "libreshockwave/bitmap/BitmapDecoder.hpp"
#include "libreshockwave/bitmap/ColorRef.hpp"
#include "libreshockwave/bitmap/Palette.hpp"
#include "libreshockwave/cast/BitmapInfo.hpp"
#include "libreshockwave/cast/CastMember.hpp"
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
#include "libreshockwave/format/ScriptFormatUtils.hpp"
#include "libreshockwave/font/PfrBitReader.hpp"
#include "libreshockwave/fonts/FontDataDecoder.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/io/BinaryReader.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/Opcode.hpp"
#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"
#include "libreshockwave/lingo/vm/ExecutionContext.hpp"
#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"
#include "libreshockwave/lingo/vm/Scope.hpp"
#include "libreshockwave/lookup/CastMemberLookup.hpp"
#include "libreshockwave/lookup/PaletteResolver.hpp"
#include "libreshockwave/lookup/ScriptLookup.hpp"
#include "libreshockwave/player/ExternalCastLoadEvent.hpp"
#include "libreshockwave/player/PlayerEvent.hpp"
#include "libreshockwave/player/PlayerEventInfo.hpp"
#include "libreshockwave/player/PlayerState.hpp"
#include "libreshockwave/player/CursorManager.hpp"
#include "libreshockwave/player/MovieProperties.hpp"
#include "libreshockwave/player/SpriteProperties.hpp"
#include "libreshockwave/player/audio/AudioBackend.hpp"
#include "libreshockwave/player/audio/SoundManager.hpp"
#include "libreshockwave/player/behavior/BehaviorInstance.hpp"
#include "libreshockwave/player/behavior/BehaviorManager.hpp"
#include "libreshockwave/player/debug/Breakpoint.hpp"
#include "libreshockwave/player/debug/BreakpointManager.hpp"
#include "libreshockwave/player/debug/DebugSnapshot.hpp"
#include "libreshockwave/player/debug/WatchExpression.hpp"
#include "libreshockwave/player/event/EventDispatcher.hpp"
#include "libreshockwave/player/frame/FrameEvent.hpp"
#include "libreshockwave/player/input/DirectorKeyCodes.hpp"
#include "libreshockwave/player/input/HitTester.hpp"
#include "libreshockwave/player/input/InputEvent.hpp"
#include "libreshockwave/player/input/InputState.hpp"
#include "libreshockwave/player/net/NetManager.hpp"
#include "libreshockwave/player/net/NetTask.hpp"
#include "libreshockwave/player/render/RenderConfig.hpp"
#include "libreshockwave/player/render/RenderType.hpp"
#include "libreshockwave/player/render/SpriteRegistry.hpp"
#include "libreshockwave/player/render/output/SoftwareFrameRenderer.hpp"
#include "libreshockwave/player/render/output/TextRenderer.hpp"
#include "libreshockwave/player/render/pipeline/BitmapCache.hpp"
#include "libreshockwave/player/render/pipeline/FrameRenderPipeline.hpp"
#include "libreshockwave/player/render/pipeline/FrameSnapshot.hpp"
#include "libreshockwave/player/render/pipeline/FrameRenderPipelineContext.hpp"
#include "libreshockwave/player/render/pipeline/FrameRenderPipelineStep.hpp"
#include "libreshockwave/player/render/pipeline/InkProcessor.hpp"
#include "libreshockwave/player/render/pipeline/RenderPipelineTrace.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"
#include "libreshockwave/player/score/ScoreBehaviorRef.hpp"
#include "libreshockwave/player/score/ScoreNavigator.hpp"
#include "libreshockwave/player/score/SpriteSpan.hpp"
#include "libreshockwave/player/sprite/SpriteState.hpp"
#include "libreshockwave/player/timeout/TimeoutManager.hpp"
#include "libreshockwave/util/AudioCodecUtils.hpp"
#include "libreshockwave/util/FileUtil.hpp"

using libreshockwave::format::ChunkInfo;
using libreshockwave::format::AfterburnerReader;
using libreshockwave::format::ChunkType;
using libreshockwave::format::MoaID;
using libreshockwave::font::PfrBitReader;
using libreshockwave::fonts::FontDataDecoder;
using libreshockwave::DirectorFile;
using libreshockwave::W3DFile;
using libreshockwave::audio::SoundConverter;
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
using libreshockwave::bitmap::BitmapColorizer;
using libreshockwave::bitmap::BitmapDecoder;
using libreshockwave::bitmap::ColorRef;
using libreshockwave::bitmap::Palette;
using libreshockwave::cast::BitmapInfo;
using libreshockwave::cast::CastMember;
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
using libreshockwave::lingo::builtin::BuiltinContext;
using libreshockwave::lingo::builtin::BuiltinRegistry;
using libreshockwave::lingo::vm::ExecutionContext;
using libreshockwave::lingo::vm::HandlerRef;
using libreshockwave::lingo::vm::OpcodeRegistry;
using libreshockwave::lingo::vm::Scope;
using libreshockwave::lookup::CastMemberLookup;
using libreshockwave::lookup::PaletteResolver;
using libreshockwave::lookup::ScriptLookup;
using libreshockwave::player::ExternalCastLoadEvent;
using libreshockwave::player::CursorManager;
using libreshockwave::player::MovieProperties;
using libreshockwave::player::PlayerEvent;
using libreshockwave::player::PlayerEventInfo;
using libreshockwave::player::PlayerState;
using libreshockwave::player::SpriteProperties;
using libreshockwave::player::allPlayerEvents;
using libreshockwave::player::handlerName;
using libreshockwave::player::name;
using libreshockwave::player::playerEventFromHandlerName;
using libreshockwave::player::audio::AudioBackend;
using libreshockwave::player::audio::SoundManager;
using libreshockwave::player::debug::Breakpoint;
using libreshockwave::player::debug::BreakpointKey;
using libreshockwave::player::debug::BreakpointManager;
using libreshockwave::player::debug::CallFrame;
using libreshockwave::player::behavior::BehaviorInstance;
using libreshockwave::player::behavior::BehaviorManager;
using libreshockwave::player::debug::DebugSnapshot;
using libreshockwave::player::debug::InstructionDisplay;
using libreshockwave::player::debug::WatchExpression;
using libreshockwave::player::event::EventDispatcher;
using libreshockwave::player::event::EventTarget;
using libreshockwave::player::event::EventTargetKind;
using libreshockwave::player::event::HandlerResult;
using libreshockwave::player::frame::FrameEvent;
using libreshockwave::player::input::AlphaHitRule;
using libreshockwave::player::input::DirectorKeyCodes;
using libreshockwave::player::input::HitTester;
using libreshockwave::player::input::InputEvent;
using libreshockwave::player::input::InputEventType;
using libreshockwave::player::input::InputState;
using libreshockwave::player::net::NetManager;
using libreshockwave::player::net::NetTask;
using libreshockwave::player::net::NetTaskMethod;
using libreshockwave::player::net::NetTaskState;
using libreshockwave::player::render::RenderConfig;
using libreshockwave::player::render::RenderType;
using libreshockwave::player::render::SpriteRegistry;
using libreshockwave::player::render::output::SoftwareFrameRenderer;
using libreshockwave::player::render::output::TextRenderer;
using libreshockwave::player::render::pipeline::BitmapCache;
using libreshockwave::player::render::pipeline::FrameRenderPipeline;
using libreshockwave::player::render::pipeline::FrameSnapshot;
using libreshockwave::player::render::pipeline::FrameRenderPipelineContext;
using libreshockwave::player::render::pipeline::FrameRenderPipelineStep;
using libreshockwave::player::render::pipeline::InkProcessor;
using libreshockwave::player::render::pipeline::RenderPipelineStepTrace;
using libreshockwave::player::render::pipeline::RenderPipelineTrace;
using libreshockwave::player::render::pipeline::RenderSprite;
using libreshockwave::player::render::pipeline::SpriteType;
using libreshockwave::player::score::ScoreBehaviorRef;
using libreshockwave::player::score::ScoreNavigator;
using libreshockwave::player::score::SpriteSpan;
using libreshockwave::player::sprite::SpriteState;
using libreshockwave::player::timeout::TimeoutManager;
using libreshockwave::w3d::W3DEntryType;

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

void testFontDataDecoder() {
#ifdef LIBRESHOCKWAVE_HAVE_ZLIB
    const auto decoded = FontDataDecoder::decode({"eJzLSM3J", "yQcABiwCFQ=="}, 13, 5);
    assert(std::string(decoded.begin(), decoded.end()) == "hello");

    const auto wrongSize = FontDataDecoder::decode({"eJzLSM3JyQcABiwCFQ=="}, 13, 6);
    assert(wrongSize.empty());

    const auto invalidDeflate = FontDataDecoder::decode({"AAAA"}, 3, 5);
    assert(invalidDeflate.empty());
#endif
}

void testPfrBitReader() {
    PfrBitReader bytes({0x12, 0x34, 0xFE, 0x80});
    assert(bytes.position() == 0);
    assert(bytes.remaining() == 4);
    assert(bytes.readU8() == 0x12);
    assert(bytes.readU16() == 0x34FE);
    assert(bytes.readI8() == -128);
    assert(bytes.readU8() == 0);

    PfrBitReader signed16({0xFF, 0xFE});
    assert(signed16.readI16() == -2);
    PfrBitReader unsigned24({0x01, 0x02, 0x03});
    assert(unsigned24.readU24() == 0x010203);
    PfrBitReader signed24({0xFF, 0xFF, 0xFE});
    assert(signed24.readI24() == -2);

    PfrBitReader bits({0b10110010, 0b01100000});
    assert(bits.readBits(0) == 0);
    assert(bits.readBits(3) == 0b101);
    assert(bits.readBit());
    assert(bits.readBits(4) == 0b0010);
    assert(bits.readBits(4) == 0b0110);
    assert(bits.position() == 2);
    assert(bits.readU8() == 0);

    PfrBitReader aligned({0xFF, 0x12, 0x34});
    assert(aligned.readBits(1) == 1);
    assert(aligned.readU8() == 0x12);
    aligned.setPosition(0);
    aligned.skip(5);
    assert(aligned.position() == 3);
    assert(aligned.remaining() == 0);

    PfrBitReader partial({0b11000000});
    assert(partial.readBits(10) == 0b11000000);
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

void testUtilityFormatting() {
    assert(libreshockwave::util::getFileName("") == "");
    assert(libreshockwave::util::getFileName("https://example.com/path/file.cct?cache=1") == "file.cct");
    assert(libreshockwave::util::getFileName("D:\\path\\file.cst") == "file.cst");
    assert(libreshockwave::util::getFileName("Mac:Folder:Movie%20Name.dir") == "Movie Name.dir");
    assert(libreshockwave::util::getFileNameWithoutExtension("Mac:Folder:Movie%20Name.dir") == "Movie Name");
    assert(libreshockwave::util::getFileNameWithoutExtension(".gitignore") == ".gitignore");

    const auto castUrls = libreshockwave::util::getUrlsWithFallbacks("cast.cst");
    assert((castUrls == std::vector<std::string>{"cast.cct", "cast.cst"}));
    const auto movieUrls = libreshockwave::util::getUrlsWithFallbacks("movie.dir");
    assert((movieUrls == std::vector<std::string>{"movie.dir", "movie.dcr", "movie.dxr", "movie.dir"}));
    const auto extensionlessUrls = libreshockwave::util::getUrlsWithFallbacks("hosts");
    assert((extensionlessUrls == std::vector<std::string>{"hosts.cct", "hosts.cst"}));
    const auto unknownUrls = libreshockwave::util::getUrlsWithFallbacks("image.png");
    assert((unknownUrls == std::vector<std::string>{"image.png"}));

    using LiteralEntry = ScriptChunk::LiteralEntry;
    using LiteralValue = ScriptChunk::LiteralValue;
    assert(libreshockwave::format::getLiteralTypeName(1) == "string");
    assert(libreshockwave::format::getLiteralTypeName(5) == "type5");
    assert(libreshockwave::format::getLiteralTypeNameShort(9) == "float");
    assert(libreshockwave::format::getLiteralTypeNameShort(12) == "lit");
    assert(libreshockwave::format::formatLiteral(LiteralEntry{1, 0, std::string("hello"), 0.0}) == "string: \"hello\"");
    assert(libreshockwave::format::formatLiteral(LiteralEntry{4, 0, 42, 0.0}) == "int: 42");
    assert(libreshockwave::format::formatLiteralValue(LiteralValue{std::string("abcdef")}, 5) == "\"ab...\"");
    assert(libreshockwave::format::truncate("abcdef", 3) == "...");
    assert(libreshockwave::format::normalizeLineEndings(" a\r\nb\rc\n ") == "a b c");
    assert(libreshockwave::format::getScriptTypeName(ScriptChunkType::MovieScript) == "Movie Script");
    assert(libreshockwave::format::getScriptTypeName(ScriptChunkType::Parent) == "Parent Script");
    assert(libreshockwave::format::getScriptTypeName(ScriptChunkType::Unknown) == "Script");

    ScriptNamesChunk names(nullptr, ChunkId(120), {"zero", "mouseUp"});
    assert(libreshockwave::format::resolveName(&names, 1) == "mouseUp");
    assert(libreshockwave::format::resolveName(&names, 5) == "#5");
    assert(libreshockwave::format::resolveHandlerName(&names, 1) == "mouseUp");
    assert(libreshockwave::format::resolveHandlerName(nullptr, 3) == "handler#3");
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
    const auto castLib = Datum::castLibRef(CastLibId(3));
    assert(castLib.asCastLibRef()->castLib == 3);

    const auto point = Datum::intPoint(10, 20);
    assert(point.stringValue() == "point(10, 20)");

    const auto rect = Datum::intRect(1, 2, 11, 22);
    assert(rect.asIntRect()->width() == 10);
    assert(rect.stringValue() == "rect(1, 2, 11, 22)");

    const auto chunk = Datum::stringChunk(Datum::of("hello"), StringChunkType::Char, 2, 2, ',', "e");
    assert(chunk.isString());
    assert(chunk.stringValue() == "e");

    const auto script = Datum::scriptRef(Datum::CastMemberRef{4, 8});
    assert(script.asScriptRef()->memberRef.castLib == 4);
    const auto sprite = Datum::spriteRef(ChannelId(6));
    assert(sprite.asSpriteRef()->spriteNum() == 6);
    assert(Datum::colorRef(1, 2, 3).asColorRef()->g == 2);

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

void testPlayerCoreFoundation() {
    assert(name(PlayerState::Stopped) == "STOPPED");
    assert(name(PlayerState::Paused) == "PAUSED");
    assert(name(PlayerState::Playing) == "PLAYING");

    const auto& events = allPlayerEvents();
    assert(events.size() == 20);
    assert(events.front() == PlayerEvent::PrepareMovie);
    assert(events.back() == PlayerEvent::KeyUp);

    assert(name(PlayerEvent::PrepareMovie) == "PREPARE_MOVIE");
    assert(handlerName(PlayerEvent::PrepareMovie) == "prepareMovie");
    assert(handlerName(PlayerEvent::StartMovie) == "startMovie");
    assert(handlerName(PlayerEvent::StopMovie) == "stopMovie");
    assert(handlerName(PlayerEvent::PrepareFrame) == "prepareFrame");
    assert(handlerName(PlayerEvent::EnterFrame) == "enterFrame");
    assert(handlerName(PlayerEvent::ExitFrame) == "exitFrame");
    assert(handlerName(PlayerEvent::StepFrame) == "stepFrame");
    assert(handlerName(PlayerEvent::BeginSprite) == "beginSprite");
    assert(handlerName(PlayerEvent::EndSprite) == "endSprite");
    assert(handlerName(PlayerEvent::Idle) == "idle");
    assert(handlerName(PlayerEvent::MouseDown) == "mouseDown");
    assert(handlerName(PlayerEvent::MouseUp) == "mouseUp");
    assert(handlerName(PlayerEvent::MouseEnter) == "mouseEnter");
    assert(handlerName(PlayerEvent::MouseLeave) == "mouseLeave");
    assert(handlerName(PlayerEvent::MouseWithin) == "mouseWithin");
    assert(handlerName(PlayerEvent::MouseUpOutside) == "mouseUpOutside");
    assert(handlerName(PlayerEvent::RightMouseDown) == "rightMouseDown");
    assert(handlerName(PlayerEvent::RightMouseUp) == "rightMouseUp");
    assert(handlerName(PlayerEvent::KeyDown) == "keyDown");
    assert(handlerName(PlayerEvent::KeyUp) == "keyUp");

    assert(playerEventFromHandlerName("mouseUpOutside") == PlayerEvent::MouseUpOutside);
    assert(playerEventFromHandlerName("keyDown") == PlayerEvent::KeyDown);
    assert(!playerEventFromHandlerName("missing").has_value());

    PlayerEventInfo eventInfo{PlayerEvent::EnterFrame, 12, 99};
    assert(eventInfo == (PlayerEventInfo{PlayerEvent::EnterFrame, 12, 99}));
    assert(eventInfo.event == PlayerEvent::EnterFrame);
    assert(eventInfo.frame == 12);
    assert(eventInfo.data == 99);

    FrameEvent frameEvent{PlayerEvent::ExitFrame, 13};
    assert(frameEvent == (FrameEvent{PlayerEvent::ExitFrame, 13}));
    assert(frameEvent.event == PlayerEvent::ExitFrame);
    assert(frameEvent.frame == 13);

    ExternalCastLoadEvent loadEvent{2, "external.cst"};
    assert(loadEvent == (ExternalCastLoadEvent{2, "external.cst"}));
    assert(loadEvent.castLibNumber == 2);
    assert(loadEvent.fileName == "external.cst");

    assert(libreshockwave::player::render::name(RenderType::Software) == "SOFTWARE");
    assert(!RenderConfig::isAntialias());
    RenderConfig::setAntialias(true);
    assert(RenderConfig::isAntialias());
    RenderConfig::setAntialias(false);
    assert(!RenderConfig::isAntialias());
}

void testPlayerInputFoundation() {
    assert(libreshockwave::player::input::name(InputEventType::MouseDown) == "MOUSE_DOWN");
    assert(libreshockwave::player::input::name(InputEventType::KeyUp) == "KEY_UP");

    const auto mouseDown = InputEvent::mouseDown(10, 20);
    assert(mouseDown == (InputEvent{InputEventType::MouseDown, 10, 20, 1, 0, ""}));
    assert(InputEvent::mouseUp(11, 21) == (InputEvent{InputEventType::MouseUp, 11, 21, 1, 0, ""}));
    assert(InputEvent::rightMouseDown(12, 22) == (InputEvent{InputEventType::RightMouseDown, 12, 22, 3, 0, ""}));
    assert(InputEvent::rightMouseUp(13, 23) == (InputEvent{InputEventType::RightMouseUp, 13, 23, 3, 0, ""}));
    assert(InputEvent::keyDown(36, "a") == (InputEvent{InputEventType::KeyDown, 0, 0, 0, 36, "a"}));
    assert(InputEvent::keyUp(36, "a") == (InputEvent{InputEventType::KeyUp, 0, 0, 0, 36, "a"}));

    assert(DirectorKeyCodes::fromJavaKeyCode(10) == 36);
    assert(DirectorKeyCodes::fromJavaKeyCode(127) == 117);
    assert(DirectorKeyCodes::fromJavaKeyCode(37) == 123);
    assert(DirectorKeyCodes::fromJavaKeyCode(65) == 0);
    assert(DirectorKeyCodes::fromJavaKeyCode(90) == 6);
    assert(DirectorKeyCodes::fromJavaKeyCode(48) == 29);
    assert(DirectorKeyCodes::fromJavaKeyCode(57) == 25);
    assert(DirectorKeyCodes::fromJavaKeyCode(999) == 999);
    assert(DirectorKeyCodes::fromBrowserKeyCode(13) == 36);
    assert(DirectorKeyCodes::fromBrowserKeyCode(46) == 117);
    assert(DirectorKeyCodes::fromBrowserKeyCode(65) == 0);
    assert(DirectorKeyCodes::fromBrowserKeyCode(57) == 25);

    InputState state;
    assert(state.mouseH() == InputState::INITIAL_MOUSE_POSITION);
    assert(state.mouseV() == InputState::INITIAL_MOUSE_POSITION);
    assert(!state.isMouseDown());
    assert(!state.isRightMouseDown());
    state.setMousePosition(33, 44);
    assert(state.mouseH() == 33);
    assert(state.mouseV() == 44);
    state.setMouseDown(true);
    state.setRightMouseDown(true);
    assert(state.isMouseDown());
    assert(state.isRightMouseDown());

    state.setClickOnSprite(7);
    state.setClickLoc(30, 40);
    assert(state.clickOnSprite() == 7);
    assert(state.clickLocH() == 30);
    assert(state.clickLocV() == 40);
    state.updateDoubleClick(50, 60);
    assert(!state.isDoubleClick());
    state.updateDoubleClick(54, 64);
    assert(state.isDoubleClick());

    state.setRolloverSprite(9);
    assert(state.rolloverSprite() == 9);
    state.setLastKey("a");
    assert(state.lastKey() == "a");
    state.setLastKey(nullptr);
    assert(state.lastKey().empty());
    state.setLastKeyCode(36);
    state.setShiftDown(true);
    state.setControlDown(true);
    state.setAltDown(true);
    assert(state.lastKeyCode() == 36);
    assert(state.isShiftDown());
    assert(state.isControlDown());
    assert(state.isAltDown());

    state.setKeyboardFocusSprite(3);
    assert(state.keyboardFocusSprite() == 3);
    assert(state.isCaretVisible());
    state.setCaretBlinkRate(15);
    for (int i = 0; i < 8; ++i) {
        state.incrementCaretBlink();
    }
    assert(!state.isCaretVisible());
    state.resetCaretBlink();
    assert(state.isCaretVisible());

    state.setSelStart(2);
    state.setSelEnd(5);
    assert(state.selStart() == 2);
    assert(state.selEnd() == 5);

    assert(!state.hasEvents());
    assert(!state.pollEvent().has_value());
    state.queueEvent(InputEvent::mouseDown(1, 2));
    state.queueEvent(InputEvent::keyDown(36, "x"));
    assert(state.hasEvents());
    assert(state.pollEvent() == InputEvent::mouseDown(1, 2));
    assert(state.pollEvent() == InputEvent::keyDown(36, "x"));
    assert(!state.hasEvents());
    assert(!state.pollEvent().has_value());
}

void testMoviePropertiesFoundation() {
    MovieProperties props;
    assert(props.getMovieProp("return").stringValue() == "\r");
    assert(props.getMovieProp("void").isVoid());
    assert(props.getMovieProp("true") == Datum::TRUE);
    assert(props.getMovieProp("false") == Datum::FALSE);
    assert(props.getMovieProp("frame").intValue() == 0);
    assert(props.getMovieProp("lastFrame").intValue() == 0);
    assert(props.getMovieProp("stageRight").intValue() == 0);
    assert(props.getStageProp("rect") == Datum::intRect(0, 0, 640, 480));
    assert(props.getMovieProp("platform").stringValue() == "Windows,32");
    assert(props.getMovieProp("runMode").stringValue() == "Plugin");
    assert(props.getMovieProp("productVersion").stringValue() == "10.1");
    assert(props.getMovieProp("environment").stringValue() == "Java");
    assert(!props.getMovieProp("date").stringValue().empty());
    assert(!props.getMovieProp("short date").stringValue().empty());
    assert(!props.getMovieProp("long time").stringValue().empty());
    assert(props.getMovieProp("timer").intValue() >= 0);
    assert(props.getMovieProp("milliseconds").intValue() >= 0);
    assert(props.getMovieProp("emptyString").stringValue().empty());
    assert(props.getMovieProp("enter").stringValue() == "\n");
    assert(props.getMovieProp("tab").stringValue() == "\t");
    assert(props.getMovieProp("quote").stringValue() == "\"");
    assert(props.getMovieProp("backspace").stringValue() == "\b");
    assert(props.getMovieProp("space").stringValue() == " ");
    assert(std::abs(props.getMovieProp("pi").floatValue() - 3.14159F) < 0.001F);
    assert(props.getMovieProp("activeWindow").type() == DatumType::StageRef);
    assert(props.getMovieProp("stage").type() == DatumType::StageRef);
    assert(props.getMovieProp("colorDepth").intValue() == 32);
    assert(props.getMovieProp("fullColorPermit") == Datum::TRUE);
    assert(props.getMovieProp("perFrameHook").isVoid());
    assert(props.getMovieProp("unknownMovieProp").isVoid());

    DirectorFile emptyFile(ByteOrder::BigEndian, false, 0, ChunkType::RIFX);
    emptyFile.setBasePath("/movies/example.dir");
    props.setFile(&emptyFile);
    assert(props.getMovieProp("name").stringValue() == "example.dir");
    assert(props.getMovieProp("movieName").stringValue() == "example.dir");
    assert(props.getMovieProp("moviePath").stringValue() == "/movies/example.dir/");
    assert(props.getMovieProp("path").stringValue() == "/movies/example.dir");
    assert(props.getMovieProp("tempo").intValue() == 15);
    assert(props.getMovieProp("number of castMembers").intValue() == 0);
    assert(props.getMovieProp("number of castLibs").intValue() == 0);
    assert(props.getStageProp("rect") == Datum::intRect(0, 0, 0, 0));

    int effectiveFrame = 12;
    int frameCount = 44;
    int tempo = 30;
    int randomSeed = 99;
    int paramCount = 3;
    int castLibCount = 4;
    int stageBg = 0x223344;
    props.setEffectiveFrameSupplier([&effectiveFrame]() { return effectiveFrame; });
    props.setFrameCountSupplier([&frameCount]() { return frameCount; });
    props.setTempoSupplier([&tempo]() { return tempo; });
    props.setTempoSetter([&tempo](int value) { tempo = value; });
    props.setRandomSeedSupplier([&randomSeed]() { return randomSeed; });
    props.setRandomSeedSetter([&randomSeed](int value) { randomSeed = value; });
    props.setParamCountSupplier([&paramCount]() { return paramCount; });
    props.setCastLibCountSupplier([&castLibCount]() { return castLibCount; });
    props.setStageBackgroundColorSupplier([&stageBg]() { return stageBg; });
    props.setStageBackgroundColorSetter([&stageBg](int value) { stageBg = value; });
    props.setStageImageSupplier([]() { return Datum::of(std::string("stage-image")); });
    props.setXtraNamesSupplier([]() { return std::vector<std::string>{"netLingo", "fileIO"}; });
    assert(props.getMovieProp("frame").intValue() == 12);
    assert(props.getMovieProp("lastFrame").intValue() == 44);
    assert(props.getMovieProp("tempo").intValue() == 30);
    assert(props.getMovieProp("randomSeed").intValue() == 99);
    assert(props.getMovieProp("paramCount").intValue() == 3);
    assert(props.getMovieProp("number of castLibs").intValue() == 4);
    assert(props.getMovieProp("number of xtras").intValue() == 2);
    auto xtraList = props.getMovieProp("xtraList");
    const auto& xtras = xtraList.listValue().items();
    assert(xtras.size() == 2);
    assert(xtras[0].propListValue().get(Datum::of(std::string("name"))).stringValue() == "netLingo");
    assert(xtras[0].propListValue().get(Datum::of(std::string("fileName"))).stringValue() == "netLingo.x32");

    InputState input;
    input.setMousePosition(77, 88);
    input.setMouseDown(true);
    input.setRightMouseDown(true);
    input.setClickOnSprite(5);
    input.setClickLoc(10, 11);
    input.updateDoubleClick(10, 11);
    input.updateDoubleClick(12, 13);
    input.setRolloverSprite(6);
    input.setLastKey("x");
    input.setLastKeyCode(7);
    input.setShiftDown(true);
    input.setAltDown(true);
    input.setControlDown(true);
    input.setKeyboardFocusSprite(9);
    input.setSelStart(2);
    input.setSelEnd(4);
    props.setInputState(&input);
    assert(props.getMovieProp("mouseDown").intValue() == 1);
    assert(props.getMovieProp("mouseUp").intValue() == 0);
    assert(props.getMovieProp("mouseH").intValue() == 77);
    assert(props.getMovieProp("mouseV").intValue() == 88);
    assert(props.getMovieProp("clickOn").intValue() == 5);
    assert(props.getMovieProp("clickLoc") == Datum::intPoint(10, 11));
    assert(props.getMovieProp("mouseLoc") == Datum::intPoint(77, 88));
    assert(props.getMovieProp("rightMouseDown").intValue() == 1);
    assert(props.getMovieProp("doubleClick").intValue() == 1);
    assert(props.getMovieProp("rollover").intValue() == 6);
    assert(props.getMovieProp("key").stringValue() == "x");
    assert(props.getMovieProp("keyCode").intValue() == 7);
    assert(props.getMovieProp("keyPressed").intValue() == 7);
    assert(props.getMovieProp("shiftDown").intValue() == 1);
    assert(props.getMovieProp("altDown").intValue() == 1);
    assert(props.getMovieProp("optionDown").intValue() == 1);
    assert(props.getMovieProp("controlDown").intValue() == 1);
    assert(props.getMovieProp("commandDown").intValue() == 1);
    assert(props.getMovieProp("keyboardFocusSprite").intValue() == 9);
    assert(props.getMovieProp("selStart").intValue() == 2);
    assert(props.getMovieProp("selEnd").intValue() == 4);

    assert(props.setMovieProp("exitLock", Datum::TRUE));
    assert(props.setMovieProp("updateLock", Datum::TRUE));
    assert(props.setMovieProp("itemDelimiter", Datum::of(std::string("|rest"))));
    assert(props.setMovieProp("puppetTempo", Datum::of(-7)));
    assert(props.setMovieProp("traceScript", Datum::TRUE));
    assert(props.setMovieProp("traceLogFile", Datum::of(std::string("trace.log"))));
    assert(props.setMovieProp("allowCustomCaching", Datum::TRUE));
    assert(props.setMovieProp("actorList", Datum::list({Datum::of(1)})));
    assert(props.setMovieProp("tempo", Datum::of(24)));
    assert(props.setMovieProp("keyboardFocusSprite", Datum::of(22)));
    assert(props.setMovieProp("alertHook", Datum::symbol("alertHandler")));
    assert(props.setMovieProp("cursor", Datum::of(4)));
    assert(props.setMovieProp("floatPrecision", Datum::of(6)));
    assert(props.setMovieProp("randomSeed", Datum::of(1234)));
    assert(props.setMovieProp("selStart", Datum::of(30)));
    assert(props.setMovieProp("selEnd", Datum::of(40)));
    assert(props.setMovieProp("debugPlaybackEnabled", Datum::TRUE));
    assert(!props.setMovieProp("readOnly", Datum::of(1)));
    assert(props.exitLock());
    assert(props.updateLock());
    assert(props.getItemDelimiter() == '|');
    assert(props.itemDelimiterString() == "|");
    assert(props.puppetTempo() == 0);
    assert(props.getMovieProp("traceScript").intValue() == 1);
    assert(props.getMovieProp("traceLogFile").stringValue() == "trace.log");
    assert(props.getMovieProp("allowCustomCaching").intValue() == 1);
    assert(props.actorList().listValue().count() == 1);
    assert(tempo == 24);
    assert(input.keyboardFocusSprite() == 22);
    assert(props.alertHook().asSymbol()->name == "alertHandler");
    assert(props.getMovieProp("cursor").intValue() == 4);
    assert(props.setMovieProp("cursor", Datum::voidValue()));
    assert(props.getMovieProp("cursor").isVoid());
    assert(props.getMovieProp("floatPrecision").intValue() == 6);
    assert(randomSeed == 1234);
    assert(input.selStart() == 30);
    assert(input.selEnd() == 40);
    props.setItemDelimiter(';');
    assert(props.getItemDelimiter() == ';');
    props.setPuppetTempo(17);
    assert(props.puppetTempo() == 17);

    assert(props.getStageProp("title").stringValue().empty());
    assert(props.setStageProp("title", Datum::of(std::string("Main Stage"))));
    assert(props.getStageProp("title").stringValue() == "Main Stage");
    assert(props.getStageProp("name").stringValue() == "stage");
    assert(props.getStageProp("visible") == Datum::TRUE);
    assert(props.getStageProp("bgColor").intValue() == 0x223344);
    assert(props.setStageProp("bgColor", Datum::colorRef(1, 2, 3)));
    assert(stageBg == 0x010203);
    assert(props.setStageProp("bgColor", Datum::of(static_cast<int>(0xFFAABBCCU))));
    assert(stageBg == 0xAABBCC);
    assert(props.setStageProp("visible", Datum::FALSE));
    assert(props.getStageProp("image").stringValue() == "stage-image");
    assert(props.setStageProp("tempo", Datum::of(31)));
    assert(tempo == 31);

    int navigatedFrame = 0;
    std::string navigatedLabel;
    int markerOffset = 0;
    std::string pageUrl;
    std::string pageTarget;
    std::string movieUrl;
    props.setGoToFrameHandler([&navigatedFrame](int frame) { navigatedFrame = frame; });
    props.setGoToLabelHandler([&navigatedLabel](const std::string& label) { navigatedLabel = label; });
    props.setFrameForLabelResolver([](const std::string& label) { return label == "intro" ? 5 : 0; });
    props.setMarkerFrameResolver([&markerOffset](int offset) {
        markerOffset = offset;
        return offset == -1 ? 2 : 9;
    });
    props.setGotoNetPageHandler([&pageUrl, &pageTarget](const std::string& url, const std::string& target) {
        pageUrl = url;
        pageTarget = target;
    });
    props.setGotoNetMovieHandler([&movieUrl](const std::string& url) {
        movieUrl = url;
        return 77;
    });
    props.goToFrame(42);
    props.goToLabel("intro");
    assert(navigatedFrame == 42);
    assert(navigatedLabel == "intro");
    assert(props.getFrameForLabel("intro") == 5);
    assert(props.getFrameForLabel("missing") == 0);
    assert(props.getMarkerFrame(-1) == 2);
    assert(markerOffset == -1);
    props.gotoNetPage("https://example.invalid", "_blank");
    assert(pageUrl == "https://example.invalid");
    assert(pageTarget == "_blank");
    assert(props.gotoNetMovie("next.dcr") == 77);
    assert(movieUrl == "next.dcr");

    MovieProperties noNavigation;
    assert(noNavigation.getFrameForLabel("intro") == 0);
    assert(noNavigation.getMarkerFrame(1) == 0);
    assert(noNavigation.gotoNetMovie("next.dcr") == -1);
}

void testBuiltinRegistryFoundation() {
    BuiltinRegistry registry;
    BuiltinContext context;

    assert(BuiltinRegistry::normalizeName("PuppetSprite") == "puppetsprite");
    assert(registry.contains("LABEL"));
    assert(registry.contains("setCursor"));
    assert(!registry.contains("missingBuiltin"));
    assert(registry.get("marker") != nullptr);
    assert(!registry.invokeIfPresent("missingBuiltin", context).has_value());
    assert(registry.invoke("missingBuiltin", context).isVoid());
    assert(registry.invoke("label", context, {Datum::of(std::string("intro"))}).intValue() == 0);
    assert(registry.invoke("marker", context, {Datum::of(1)}).intValue() == 0);
    assert(registry.invoke("puppetSprite", context, {Datum::of(1), Datum::TRUE}).isVoid());
    assert(registry.invoke("spriteBox", context, {Datum::of(1)}) == Datum::intRect(0, 0, 0, 0));

    assert(registry.contains("abs"));
    assert(registry.contains("sqrt"));
    assert(registry.contains("sin"));
    assert(registry.contains("cos"));
    assert(registry.contains("random"));
    assert(registry.contains("integer"));
    assert(registry.contains("float"));
    assert(registry.contains("bitAnd"));
    assert(registry.contains("bitOr"));
    assert(registry.contains("bitXor"));
    assert(registry.contains("bitNot"));
    assert(registry.contains("power"));
    assert(registry.contains("min"));
    assert(registry.contains("max"));
    assert(registry.invoke("abs", context).intValue() == 0);
    assert(registry.invoke("abs", context, {Datum::of(-4)}).intValue() == 4);
    assert(std::fabs(registry.invoke("abs", context, {Datum::of(-2.5F)}).floatValue() - 2.5F) < 0.0001F);
    assert(std::fabs(registry.invoke("sqrt", context, {Datum::of(9)}).floatValue() - 3.0F) < 0.0001F);
    assert(std::fabs(registry.invoke("sin", context, {Datum::of(90)}).floatValue() - 1.0F) < 0.0001F);
    assert(std::fabs(registry.invoke("cos", context, {Datum::of(180)}).floatValue() + 1.0F) < 0.0001F);
    assert(registry.invoke("random", context).intValue() == 1);
    assert(registry.invoke("random", context, {Datum::of(0)}).intValue() == 1);
    int randomCalls = 0;
    context.randomIntHandler = [&randomCalls](int max) {
        ++randomCalls;
        assert(max == 5);
        return 3;
    };
    assert(registry.invoke("random", context, {Datum::of(5)}).intValue() == 3);
    assert(randomCalls == 1);
    assert(registry.invoke("integer", context).intValue() == 0);
    assert(registry.invoke("integer", context, {Datum::of(3.7F)}).intValue() == 4);
    assert(registry.invoke("integer", context, {Datum::of(std::string("42"))}).intValue() == 42);
    assert(registry.invoke("integer", context, {Datum::of(std::string("3.7"))}).intValue() == 4);
    assert(registry.invoke("integer", context, {Datum::of(std::string(""))}).intValue() == 0);
    assert(registry.invoke("integer", context, {Datum::of(std::string("*FF"))}).intValue() == 255);
    assert(registry.invoke("integer", context, {Datum::of(std::string("hello"))}).isVoid());
    assert(registry.invoke("integer", context, {Datum::colorRef(1, 2, 3)}).intValue() == 0x010203);
    assert(std::fabs(registry.invoke("float", context).floatValue()) < 0.0001F);
    assert(std::fabs(registry.invoke("float", context, {Datum::of(7)}).floatValue() - 7.0F) < 0.0001F);
    assert(std::fabs(registry.invoke("float", context, {Datum::of(std::string("1.5"))}).floatValue() - 1.5F) < 0.0001F);
    assert(registry.invoke("float", context, {Datum::of(std::string("hello"))}).stringValue() == "hello");
    assert(registry.invoke("bitAnd", context, {Datum::of(0x0F), Datum::of(0x33)}).intValue() == 0x03);
    assert(registry.invoke("bitOr", context, {Datum::of(0x0F), Datum::of(0x30)}).intValue() == 0x3F);
    assert(registry.invoke("bitXor", context, {Datum::of(0x0F), Datum::of(0x33)}).intValue() == 0x3C);
    assert(registry.invoke("bitNot", context, {Datum::of(0)}).intValue() == -1);
    assert(registry.invoke("power", context, {Datum::of(2), Datum::of(3)}).intValue() == 8);
    assert(std::fabs(registry.invoke("power", context, {Datum::of(2), Datum::of(0.5F)}).floatValue() -
                     std::sqrt(2.0F)) < 0.0001F);
    assert(registry.invoke("min", context).intValue() == 0);
    assert(registry.invoke("min", context, {Datum::of(7)}).intValue() == 7);
    assert(registry.invoke("min", context, {Datum::of(4), Datum::of(2)}).intValue() == 2);
    assert(std::fabs(registry.invoke("min", context, {Datum::of(4.5F), Datum::of(2)}).floatValue() - 2.0F) < 0.0001F);
    assert(registry.invoke("max", context, {Datum::of(4), Datum::of(2)}).intValue() == 4);
    assert(std::fabs(registry.invoke("max", context, {Datum::of(4), Datum::of(2.5F)}).floatValue() - 4.0F) < 0.0001F);
    assert(registry.invoke("min", context, {Datum::list({Datum::of(7), Datum::of(2), Datum::of(9)})}).intValue() == 2);
    assert(std::fabs(registry.invoke("max",
                                     context,
                                     {Datum::list({Datum::of(7), Datum::of(2.5F), Datum::of(9)})}).floatValue() -
                     9.0F) < 0.0001F);

    assert(registry.contains("string"));
    assert(registry.contains("length"));
    assert(registry.contains("chars"));
    assert(registry.contains("charToNum"));
    assert(registry.contains("numToChar"));
    assert(registry.contains("offset"));
    assert(registry.contains("getPref"));
    assert(registry.contains("setPref"));
    assert(registry.invoke("string", context).stringValue().empty());
    assert(registry.invoke("string", context, {Datum::of(42)}).stringValue() == "42");
    assert(registry.invoke("string", context, {Datum::symbol("door")}).stringValue() == "door");
    assert(registry.invoke("string", context, {Datum::colorRef(1, 2, 3)}).stringValue() == "color(1, 2, 3)");
    assert(registry.invoke("string", context, {Datum::list({Datum::of(1), Datum::of(std::string("cat"))})}).stringValue() ==
           "[1, \"cat\"]");
    auto stringPropList = Datum::propList();
    stringPropList.propListValue().put(Datum::symbol("name"), Datum::of(std::string("value")));
    assert(registry.invoke("string", context, {stringPropList}).stringValue() == "[#name: \"value\"]");
    assert(registry.invoke("length", context).intValue() == 0);
    assert(registry.invoke("length", context, {Datum::of(std::string("hello"))}).intValue() == 5);
    assert(registry.invoke("length", context, {Datum::symbol("door")}).intValue() == 4);
    assert(registry.invoke("length", context, {Datum::list({Datum::of(1), Datum::of(2), Datum::of(3)})}).intValue() == 3);
    assert(registry.invoke("length", context, {stringPropList}).intValue() == 1);
    assert(registry.invoke("chars", context).stringValue().empty());
    assert(registry.invoke("chars", context, {Datum::of(std::string("hello")), Datum::of(2), Datum::of(4)}).stringValue() ==
           "ell");
    assert(registry.invoke("chars", context, {Datum::of(std::string("hello")), Datum::of(-2), Datum::of(2)}).stringValue() ==
           "he");
    assert(registry.invoke("chars", context, {Datum::of(std::string("hello")), Datum::of(5), Datum::of(2)}).stringValue().empty());
    assert(registry.invoke("charToNum", context).intValue() == 0);
    assert(registry.invoke("charToNum", context, {Datum::of(std::string("A"))}).intValue() == 65);
    assert(registry.invoke("numToChar", context).stringValue().empty());
    assert(registry.invoke("numToChar", context, {Datum::of(65)}).stringValue() == "A");
    assert(registry.invoke("offset", context).intValue() == 0);
    assert(registry.invoke("offset", context, {Datum::of(std::string("LL")), Datum::of(std::string("hello"))}).intValue() == 3);
    assert(registry.invoke("offset", context, {Datum::of(std::string("")), Datum::of(std::string("hello"))}).intValue() == 0);
    assert(registry.invoke("offset", context, {Datum::of(std::string("zz")), Datum::of(std::string("hello"))}).intValue() == 0);
    assert(registry.invoke("getPref", context).isVoid());
    assert(registry.invoke("getPref", context, {Datum::of(std::string("volume"))}).isVoid());
    std::map<std::string, Datum> prefs;
    context.getPrefHandler = [&prefs](const std::string& key) {
        const auto found = prefs.find(key);
        return found == prefs.end() ? Datum::voidValue() : found->second;
    };
    context.setPrefHandler = [&prefs](const std::string& key, const Datum& value) {
        const auto stored = Datum::of(value.isString() ? value.stringValue() : std::to_string(value.intValue()));
        prefs[key] = stored;
        return stored;
    };
    assert(registry.invoke("setPref", context).isVoid());
    assert(registry.invoke("setPref", context, {Datum::of(std::string("")), Datum::of(7)}).isVoid());
    assert(registry.invoke("setPref", context, {Datum::of(std::string("volume")), Datum::of(7)}).stringValue() == "7");
    assert(registry.invoke("getPref", context, {Datum::of(std::string("volume"))}).stringValue() == "7");

    std::vector<std::pair<std::string, std::string>> outputMessages;
    context.outputHandler = [&outputMessages](std::string_view kind, const std::string& text) {
        outputMessages.emplace_back(std::string(kind), text);
    };
    assert(registry.contains("put"));
    assert(registry.contains("alert"));
    assert(registry.invoke("put", context, {Datum::of(std::string("hidden"))}).isVoid());
    assert(outputMessages.empty());
    context.debugPlaybackEnabled = true;
    assert(registry.invoke("put",
                           context,
                           {Datum::of(std::string("score")), Datum::of(7), Datum::symbol("ready")}).isVoid());
    assert(outputMessages.size() == 1);
    assert(outputMessages.back().first == "PUT");
    assert(outputMessages.back().second == "score 7 ready");
    assert(registry.invoke("alert", context).isVoid());
    assert(outputMessages.size() == 2);
    assert(outputMessages.back().first == "ALERT");
    assert(outputMessages.back().second.empty());
    std::string handledAlert;
    context.alertHandler = [&handledAlert](const std::string& text) {
        handledAlert = text;
        return true;
    };
    assert(registry.invoke("alert", context, {Datum::of(std::string("handled"))}).isVoid());
    assert(handledAlert == "handled");
    assert(outputMessages.size() == 2);

    assert(registry.contains("castLib"));
    assert(registry.contains("member"));
    assert(registry.contains("field"));
    assert(registry.contains("createMember"));
    assert(!registry.contains("getMemNum"));
    assert(!registry.contains("memberExists"));
    assert(registry.invoke("castLib", context, {Datum::of(1)}).isVoid());
    assert(registry.invoke("member", context).isVoid());
    const auto placeholderMember = registry.invoke("member", context, {Datum::of(12)});
    assert(placeholderMember.asCastMemberRef()->castLib == 1);
    assert(placeholderMember.asCastMemberRef()->memberNum() == 12);
    assert(registry.invoke("member", context, {Datum::of(-1)}).isVoid());
    assert(registry.invoke("field", context).stringValue().empty());
    assert(registry.invoke("createMember", context, {Datum::of(std::string("new")), Datum::symbol("bitmap")}).isVoid());

    const std::map<std::string, int> castNames{{"main", 1}, {"external", 2}};
    const std::map<std::pair<int, std::string>, std::pair<int, int>> memberNames{
        {{0, "door"}, {2, 4}},
        {{2, "palette"}, {2, 5}},
    };
    const std::set<std::pair<int, int>> existingMembers{{1, 3}, {2, 4}, {2, 5}};
    context.castLibNumberResolver = [](int castLib) {
        return castLib >= 1 && castLib <= 2 ? castLib : -1;
    };
    context.castLibNameResolver = [&castNames](const std::string& name) {
        const auto found = castNames.find(name);
        return found == castNames.end() ? -1 : found->second;
    };
    context.castLibCountSupplier = []() {
        return 2;
    };
    context.castMemberExistsResolver = [&existingMembers](int castLib, int memberNum) {
        return existingMembers.contains({castLib, memberNum});
    };
    context.castMemberResolver = [](int castLib, int memberNum) {
        return Datum::castMemberRef(CastLibId(castLib), MemberId(memberNum));
    };
    context.castMemberNameResolver = [&memberNames](int castLib, const std::string& name) {
        const auto found = memberNames.find({castLib, name});
        if (found == memberNames.end()) {
            return Datum::voidValue();
        }
        return Datum::castMemberRef(CastLibId(found->second.first), MemberId(found->second.second));
    };
    context.fieldResolver = [](const Datum& identifier, int castLib) {
        if (const auto* value = identifier.asInt()) {
            return Datum::of("field#" + std::to_string(value->value) + "@" + std::to_string(castLib));
        }
        return Datum::of("field:" + identifier.stringValue() + "@" + std::to_string(castLib));
    };
    std::string castBuiltinCreatedName;
    std::string castBuiltinCreatedType;
    context.namedCastMemberCreator = [&castBuiltinCreatedName, &castBuiltinCreatedType](const std::string& name,
                                                                                        const std::string& memberType) {
        castBuiltinCreatedName = name;
        castBuiltinCreatedType = memberType;
        return Datum::castMemberRef(CastLibId(1), MemberId(77));
    };
    assert(registry.invoke("castLib", context, {Datum::of(2)}).asCastLibRef()->castLib == 2);
    assert(registry.invoke("castLib", context, {Datum::of(std::string("external"))}).asCastLibRef()->castLib == 2);
    assert(registry.invoke("castLib", context, {Datum::symbol("main")}).asCastLibRef()->castLib == 1);
    assert(registry.invoke("castLib", context, {Datum::of(9)}).isVoid());
    assert(registry.invoke("member", context, {Datum::of(5), Datum::castLibRef(CastLibId(2))}).asCastMemberRef()->castLib == 2);
    assert(registry.invoke("member", context, {Datum::of(5), Datum::castLibRef(CastLibId(2))}).asCastMemberRef()->memberNum() == 5);
    assert(registry.invoke("member", context, {Datum::of(0)}).isVoid());
    assert(registry.invoke("member", context, {Datum::of((2 << 16) | 5)}).asCastMemberRef()->castLib == 2);
    assert(registry.invoke("member", context, {Datum::of(4)}).asCastMemberRef()->castLib == 2);
    assert(registry.invoke("member", context, {Datum::of(99)}).asCastMemberRef()->castLib == 1);
    assert(registry.invoke("member", context, {Datum::of(std::string("door"))}).asCastMemberRef()->memberNum() == 4);
    assert(registry.invoke("member",
                           context,
                           {Datum::symbol("palette"), Datum::of(std::string("external"))}).asCastMemberRef()->memberNum() == 5);
    assert(registry.invoke("member", context, {Datum::of(std::string("missing"))}).isVoid());
    assert(registry.invoke("field", context, {Datum::of(std::string("caption"))}).stringValue() == "field:caption@0");
    assert(registry.invoke("field", context, {Datum::of(3), Datum::castLibRef(CastLibId(2))}).stringValue() == "field#3@2");
    assert(registry.invoke("field", context, {Datum::symbol("caption"), Datum::of(std::string("external"))}).stringValue() ==
           "field:caption@2");
    const auto dynamicMember = registry.invoke("createMember", context, {Datum::of(std::string("dynamic")), Datum::symbol("bitmap")});
    assert(dynamicMember.asCastMemberRef()->memberNum() == 77);
    assert(castBuiltinCreatedName == "dynamic");
    assert(castBuiltinCreatedType == "bitmap");

    assert(registry.contains("xtra"));
    assert(registry.invoke("xtra", context).isVoid());
    assert(registry.invoke("xtra", context, {Datum::of(std::string("Multiuser"))}).isVoid());
    std::vector<std::string> xtraRegistrationChecks;
    context.xtraRegisteredResolver = [&xtraRegistrationChecks](const std::string& name) {
        xtraRegistrationChecks.push_back(name);
        return name == "Multiuser";
    };
    assert(registry.invoke("xtra", context, {Datum::of(std::string("Missing"))}).isVoid());
    const auto xtraRefDatum = registry.invoke("xtra", context, {Datum::of(std::string("Multiuser"))});
    const auto* xtraRef = xtraRefDatum.asXtra();
    assert(xtraRef != nullptr);
    assert(xtraRef->name == "Multiuser");
    assert(registry.invoke("string", context, {xtraRefDatum}).stringValue() == "<Xtra \"Multiuser\">");
    assert(xtraRegistrationChecks.size() == 2);
    assert(xtraRegistrationChecks[0] == "Missing");
    assert(xtraRegistrationChecks[1] == "Multiuser");
    assert(registry.invoke("new", context, {xtraRefDatum, Datum::of(1)}).isVoid());

    std::string createdXtraName;
    std::vector<Datum> createdXtraArgs;
    context.xtraInstanceCreator = [&createdXtraName, &createdXtraArgs](const std::string& name,
                                                                       const std::vector<Datum>& args) {
        createdXtraName = name;
        createdXtraArgs = args;
        return Datum::xtraInstance(name, 42);
    };
    const auto xtraInstanceDatum = registry.invoke("new", context, {xtraRefDatum, Datum::of(7), Datum::symbol("ready")});
    const auto* xtraInstance = xtraInstanceDatum.asXtraInstance();
    assert(xtraInstance != nullptr);
    assert(xtraInstance->xtraName == "Multiuser");
    assert(xtraInstance->instanceId == 42);
    assert(createdXtraName == "Multiuser");
    assert(createdXtraArgs.size() == 2);
    assert(createdXtraArgs[0].intValue() == 7);
    assert(createdXtraArgs[1].asSymbol()->name == "ready");
    assert(registry.invoke("string", context, {xtraInstanceDatum}).stringValue() == "<XtraInstance \"Multiuser\" #42>");

    std::string xtraHandlerName;
    std::vector<Datum> xtraHandlerArgs;
    context.xtraHandler = [&xtraHandlerName, &xtraHandlerArgs](const Datum::XtraInstance& instance,
                                                               const std::string& handlerName,
                                                               const std::vector<Datum>& args) {
        assert(instance.xtraName == "Multiuser");
        assert(instance.instanceId == 42);
        xtraHandlerName = handlerName;
        xtraHandlerArgs = args;
        return Datum::of(std::string("called"));
    };
    assert(libreshockwave::lingo::builtin::XtraBuiltins::callHandler(context,
                                                                     *xtraInstance,
                                                                     "sendMsg",
                                                                     {Datum::of(std::string("hi"))}).stringValue() == "called");
    assert(xtraHandlerName == "sendMsg");
    assert(xtraHandlerArgs.size() == 1);
    assert(xtraHandlerArgs[0].stringValue() == "hi");
    BuiltinContext noXtraContext;
    assert(libreshockwave::lingo::builtin::XtraBuiltins::callHandler(noXtraContext, *xtraInstance, "sendMsg", {}).isVoid());

    std::string xtraPropertySetName;
    Datum xtraPropertySetValue = Datum::voidValue();
    context.xtraPropertyGetter = [](const Datum::XtraInstance& instance, const std::string& propertyName) {
        assert(instance.instanceId == 42);
        return Datum::of("prop:" + propertyName);
    };
    context.xtraPropertySetter = [&xtraPropertySetName, &xtraPropertySetValue](const Datum::XtraInstance& instance,
                                                                              const std::string& propertyName,
                                                                              const Datum& value) {
        assert(instance.instanceId == 42);
        xtraPropertySetName = propertyName;
        xtraPropertySetValue = value;
    };
    assert(libreshockwave::lingo::builtin::XtraBuiltins::getProperty(context, *xtraInstance, "state").stringValue() == "prop:state");
    libreshockwave::lingo::builtin::XtraBuiltins::setProperty(context, *xtraInstance, "state", Datum::of(9));
    assert(xtraPropertySetName == "state");
    assert(xtraPropertySetValue.intValue() == 9);
    assert(libreshockwave::lingo::builtin::XtraBuiltins::getProperty(noXtraContext, *xtraInstance, "state").isVoid());

    assert(registry.contains("return"));
    assert(registry.contains("halt"));
    assert(registry.contains("abort"));
    assert(registry.contains("nothing"));
    assert(registry.contains("param"));
    assert(registry.contains("go"));
    assert(registry.contains("call"));
    assert(!registry.contains("receiveUpdate"));
    assert(!registry.contains("removeUpdate"));
    assert(!context.returned);
    assert(registry.invoke("return", context, {Datum::of(std::string("done"))}).isVoid());
    assert(context.returned);
    assert(context.returnValue.stringValue() == "done");
    context.returned = false;
    context.returnValue = Datum::of(99);
    assert(registry.invoke("return", context).isVoid());
    assert(context.returned);
    assert(context.returnValue.isVoid());
    context.returned = false;
    assert(registry.invoke("abort", context).isVoid());
    assert(context.returned);
    assert(context.aborted);
    assert(registry.invoke("halt", context).isVoid());
    assert(registry.invoke("nothing", context).isVoid());

    context.currentHandlerArgs = {Datum::of(std::string("first")), Datum::of(22)};
    assert(registry.invoke("param", context).isVoid());
    assert(registry.invoke("param", context, {Datum::of(1)}).stringValue() == "first");
    assert(registry.invoke("param", context, {Datum::of(std::string("2"))}).intValue() == 22);
    assert(registry.invoke("param", context, {Datum::of(0)}).isVoid());
    assert(registry.invoke("param", context, {Datum::of(3)}).isVoid());

    std::vector<int> controlFlowFrames;
    std::vector<std::string> controlFlowLabels;
    int controlFlowFrame = 5;
    MovieProperties controlFlowMovie;
    controlFlowMovie.setEffectiveFrameSupplier([&controlFlowFrame]() {
        return controlFlowFrame;
    });
    controlFlowMovie.setGoToFrameHandler([&controlFlowFrames](int frame) {
        controlFlowFrames.push_back(frame);
    });
    controlFlowMovie.setGoToLabelHandler([&controlFlowLabels](const std::string& label) {
        controlFlowLabels.push_back(label);
    });
    context.movieProperties = &controlFlowMovie;
    assert(registry.invoke("go", context).isVoid());
    assert(registry.invoke("go", context, {Datum::of(12)}).isVoid());
    assert(registry.invoke("go", context, {Datum::symbol("next")}).isVoid());
    assert(registry.invoke("go", context, {Datum::symbol("previous")}).isVoid());
    controlFlowFrame = 1;
    assert(registry.invoke("go", context, {Datum::symbol("previous")}).isVoid());
    assert(registry.invoke("go", context, {Datum::symbol("loop")}).isVoid());
    assert(registry.invoke("go", context, {Datum::symbol("intro")}).isVoid());
    assert(registry.invoke("go", context, {Datum::of(std::string("credits"))}).isVoid());
    assert(controlFlowFrames == std::vector<int>({12, 6, 4, 1, 1}));
    assert(controlFlowLabels == std::vector<std::string>({"intro", "credits"}));
    context.movieProperties = nullptr;

    std::vector<int> calledTargets;
    std::string calledHandler;
    std::vector<Datum> calledArgs;
    auto callListTargets = Datum::list({Datum::of(1), Datum::of(2)});
    context.callTargetHandler = [&calledTargets, &calledHandler, &calledArgs, &callListTargets](
                                    const Datum& target,
                                    const std::string& handlerName,
                                    const std::vector<Datum>& args) {
        calledTargets.push_back(target.intValue());
        calledHandler = handlerName;
        calledArgs = args;
        if (handlerName == "tick" && calledTargets.size() == 1) {
            callListTargets.listValue().add(Datum::of(99));
        }
        return Datum::of(static_cast<int>(calledTargets.size()));
    };
    assert(registry.invoke("call", context).isVoid());
    assert(registry.invoke("call", context, {Datum::symbol("update"), Datum::of(7), Datum::of(std::string("arg"))}).intValue() == 1);
    assert(calledTargets == std::vector<int>({7}));
    assert(calledHandler == "update");
    assert(calledArgs.size() == 1);
    assert(calledArgs[0].stringValue() == "arg");
    calledTargets.clear();
    assert(registry.invoke("call", context, {Datum::of(std::string("tick")), callListTargets}).intValue() == 2);
    assert(calledTargets == std::vector<int>({1, 2}));
    assert(callListTargets.listValue().count() == 3);
    auto callPropTargets = Datum::propList();
    callPropTargets.propListValue().put(Datum::symbol("first"), Datum::of(10));
    callPropTargets.propListValue().put(Datum::symbol("second"), Datum::of(11));
    calledTargets.clear();
    assert(registry.invoke("call", context, {Datum::symbol("doIt"), callPropTargets, Datum::of(5)}).intValue() == 2);
    assert(calledTargets == std::vector<int>({10, 11}));
    assert(calledHandler == "doIt");
    assert(calledArgs.size() == 1);
    assert(calledArgs[0].intValue() == 5);
    context.callTargetHandler = nullptr;
    assert(registry.invoke("call", context, {Datum::symbol("noop"), Datum::of(1)}).isVoid());

    assert(registry.contains("count"));
    assert(registry.contains("getAt"));
    assert(registry.contains("setAt"));
    assert(registry.contains("addAt"));
    assert(registry.contains("deleteAt"));
    assert(registry.contains("append"));
    assert(registry.contains("add"));
    assert(registry.contains("getaProp"));
    assert(registry.contains("setaProp"));
    assert(registry.contains("addProp"));
    assert(registry.contains("deleteProp"));
    assert(registry.contains("getPropAt"));
    assert(registry.contains("findPos"));
    assert(registry.contains("getOne"));
    assert(registry.contains("getPos"));
    assert(registry.contains("deleteOne"));
    assert(registry.contains("sort"));
    assert(registry.contains("list"));
    assert(registry.contains("getLast"));
    assert(registry.invoke("count", context).intValue() == 0);
    auto builtinList = Datum::list({Datum::of(2), Datum::of(4)});
    assert(registry.invoke("count", context, {builtinList}).intValue() == 2);
    assert(registry.invoke("getAt", context, {builtinList, Datum::of(2)}).intValue() == 4);
    assert(registry.invoke("getAt", context, {builtinList, Datum::of(9)}).isVoid());
    assert(registry.invoke("setAt", context, {builtinList, Datum::of(2), Datum::of(6)}).isVoid());
    assert(builtinList.listValue().getAt(2).intValue() == 6);
    assert(registry.invoke("addAt", context, {builtinList, Datum::of(2), Datum::of(5)}).isVoid());
    assert(builtinList.listValue().count() == 3);
    assert(builtinList.listValue().getAt(2).intValue() == 5);
    assert(registry.invoke("append", context, {builtinList, Datum::of(9)}).isVoid());
    assert(registry.invoke("add", context, {builtinList, Datum::of(10)}).isVoid());
    assert(builtinList.listValue().count() == 5);
    assert(registry.invoke("getLast", context, {builtinList}).intValue() == 10);
    assert(registry.invoke("deleteAt", context, {builtinList, Datum::of(1)}).isVoid());
    assert(builtinList.listValue().getAt(1).intValue() == 5);
    assert(registry.invoke("getOne", context, {builtinList, Datum::of(9.0F)}).intValue() == 3);
    assert(registry.invoke("getPos", context, {Datum::list({Datum::symbol("Door")}), Datum::of(std::string("door"))}).intValue() == 1);
    assert(registry.invoke("deleteOne", context, {builtinList, Datum::of(9)}).isVoid());
    assert(registry.invoke("getOne", context, {builtinList, Datum::of(9)}).intValue() == 0);
    auto sortableList = Datum::list({Datum::of(3), Datum::of(1), Datum::of(2)});
    assert(registry.invoke("sort", context, {sortableList}).isVoid());
    assert(sortableList.listValue().getAt(1).intValue() == 1);
    assert(sortableList.listValue().getAt(3).intValue() == 3);
    auto stringSortable = Datum::list({Datum::of(std::string("bravo")), Datum::of(std::string("Alpha"))});
    assert(registry.invoke("sort", context, {stringSortable}).isVoid());
    assert(stringSortable.listValue().getAt(1).stringValue() == "Alpha");
    const auto constructedList = registry.invoke("list", context, {Datum::of(1), Datum::of(2), Datum::of(3)});
    assert(constructedList.listValue().count() == 3);
    assert(registry.invoke("listp", context, {constructedList}).boolValue());
    assert(registry.invoke("getAt", context, {Datum::intPoint(7, 8), Datum::of(2)}).intValue() == 8);
    assert(registry.invoke("getAt", context, {Datum::intRect(1, 2, 3, 4), Datum::of(3)}).intValue() == 3);

    auto builtinProps = Datum::propList();
    assert(registry.invoke("setaProp", context, {builtinProps, Datum::symbol("name"), Datum::of(std::string("Ada"))}).isVoid());
    assert(registry.invoke("count", context, {builtinProps}).intValue() == 1);
    assert(registry.invoke("getaProp", context, {builtinProps, Datum::symbol("NAME")}).stringValue() == "Ada");
    assert(registry.invoke("getAt", context, {builtinProps, Datum::of(1)}).stringValue() == "Ada");
    assert(registry.invoke("getAt", context, {builtinProps, Datum::of(std::string("name"))}).stringValue() == "Ada");
    assert(registry.invoke("getPropAt", context, {builtinProps, Datum::of(1)}).asSymbol()->name == "name");
    assert(registry.invoke("findPos", context, {builtinProps, Datum::of(std::string("NAME"))}).intValue() == 1);
    assert(registry.invoke("findPos", context, {builtinProps, Datum::of(std::string("missing"))}).isVoid());
    assert(registry.invoke("setAt", context, {builtinProps, Datum::of(std::string("name")), Datum::of(std::string("string-key"))}).isVoid());
    assert(builtinProps.propListValue().count() == 2);
    assert(registry.invoke("getAt", context, {builtinProps, Datum::symbol("name")}).stringValue() == "Ada");
    assert(registry.invoke("getAt", context, {builtinProps, Datum::of(std::string("name"))}).stringValue() == "string-key");
    assert(registry.invoke("setAt", context, {builtinProps, Datum::of(2), Datum::of(std::string("string-key-updated"))}).isVoid());
    assert(registry.invoke("getAt", context, {builtinProps, Datum::of(2)}).stringValue() == "string-key-updated");
    assert(registry.invoke("addProp", context, {builtinProps, Datum::symbol("name"), Datum::of(std::string("duplicate"))}).isVoid());
    assert(builtinProps.propListValue().count() == 3);
    assert(registry.invoke("getLast", context, {builtinProps}).stringValue() == "duplicate");
    assert(registry.invoke("deleteProp", context, {builtinProps, Datum::of(std::string("name"))}).isVoid());
    assert(builtinProps.propListValue().count() == 2);
    assert(registry.invoke("getAt", context, {builtinProps, Datum::of(std::string("name"))}).stringValue() == "Ada");
    assert(registry.invoke("deleteAt", context, {builtinProps, Datum::of(2)}).isVoid());
    assert(builtinProps.propListValue().count() == 1);

    assert(registry.contains("timeout"));
    assert(registry.invoke("timeout", context).asTimeoutRef()->name.empty());
    assert(registry.invoke("timeout", context, {Datum::of(std::string("timer"))}).asTimeoutRef()->name == "timer");
    assert(registry.invoke("timeout", context, {Datum::symbol("tick")}).asTimeoutRef()->name == "tick");
    TimeoutManager builtinTimeouts;
    context.timeoutManager = &builtinTimeouts;
    const auto timerRefDatum = registry.invoke("timeout", context, {Datum::of(std::string("timer"))});
    const auto* timerRef = timerRefDatum.asTimeoutRef();
    assert(timerRef != nullptr);
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(context,
                                                                         *timerRef,
                                                                         "new",
                                                                         {Datum::of(500),
                                                                          Datum::symbol("onTimer"),
                                                                          Datum::of(std::string("target"))}).asTimeoutRef()->name == "timer");
    assert(builtinTimeouts.timeoutExists("timer"));
    assert(builtinTimeouts.getTimeoutProp("timer", "period").intValue() == 500);
    assert(builtinTimeouts.getTimeoutProp("timer", "handler").asSymbol()->name == "onTimer");
    assert(builtinTimeouts.getTimeoutProp("timer", "target").stringValue() == "target");
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::getProperty(context, *timerRef, "period").intValue() == 500);
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::setProperty(context, *timerRef, "period", Datum::of(250)));
    assert(builtinTimeouts.getTimeoutProp("timer", "period").intValue() == 250);
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(context, *timerRef, "period", {}).intValue() == 250);
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(context, *timerRef, "forget", {}).isVoid());
    assert(!builtinTimeouts.timeoutExists("timer"));
    const auto factoryRefDatum = registry.invoke("timeout", context);
    const auto* factoryRef = factoryRefDatum.asTimeoutRef();
    assert(factoryRef != nullptr);
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(context,
                                                                         *factoryRef,
                                                                         "new",
                                                                         {Datum::of(std::string("factory")),
                                                                          Datum::of(1000),
                                                                          Datum::of(std::string("tick"))}).asTimeoutRef()->name == "factory");
    assert(builtinTimeouts.timeoutExists("factory"));
    assert(builtinTimeouts.getTimeoutProp("factory", "handler").asSymbol()->name == "tick");
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(context, *factoryRef, "new", {}).isVoid());
    assert(!libreshockwave::lingo::builtin::TimeoutBuiltins::setProperty(context, *factoryRef, "period", Datum::of(1)));
    BuiltinContext noTimeoutContext;
    assert(registry.invoke("timeout", noTimeoutContext, {Datum::of(std::string("timer"))}).asTimeoutRef()->name == "timer");
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::handleMethod(noTimeoutContext, *timerRef, "new", {}).isVoid());
    assert(libreshockwave::lingo::builtin::TimeoutBuiltins::getProperty(noTimeoutContext, *timerRef, "period").isVoid());
    assert(!libreshockwave::lingo::builtin::TimeoutBuiltins::setProperty(noTimeoutContext, *timerRef, "period", Datum::of(1)));

    auto builtinStatusProp = [](const Datum& props, const std::string& key) {
        return props.propListValue().get(Datum::of(key));
    };
    assert(registry.contains("preloadNetThing"));
    assert(registry.contains("getNetThing"));
    assert(registry.contains("getNetText"));
    assert(registry.contains("postNetThing"));
    assert(registry.contains("postNetText"));
    assert(registry.contains("netDone"));
    assert(registry.contains("netTextResult"));
    assert(registry.contains("netError"));
    assert(registry.contains("getStreamStatus"));
    assert(registry.contains("tellStreamStatus"));
    assert(registry.contains("gotoNetPage"));
    assert(registry.contains("gotoNetMovie"));
    assert(registry.invoke("preloadNetThing", context, {Datum::of(std::string("movie.dir"))}).intValue() == -1);
    assert(registry.invoke("postNetText", context, {Datum::of(std::string("movie.dir"))}).intValue() == -1);
    assert(registry.invoke("netDone", context).boolValue());
    assert(registry.invoke("netTextResult", context).stringValue().empty());
    assert(registry.invoke("netError", context).stringValue() == "OK");
    const auto missingBuiltinStatus = registry.invoke("getStreamStatus", context);
    assert(builtinStatusProp(missingBuiltinStatus, "state").stringValue() == "Error");
    assert(builtinStatusProp(missingBuiltinStatus, "bytesSoFar").intValue() == 0);
    assert(!registry.invoke("tellStreamStatus", context).boolValue());
    assert(registry.invoke("tellStreamStatus", context, {Datum::TRUE}).boolValue());
    assert(registry.invoke("tellStreamStatus", context).boolValue());
    assert(!registry.invoke("tellStreamStatus", context, {Datum::FALSE}).boolValue());
    assert(!registry.invoke("gotoNetPage", context, {Datum::of(std::string("https://example.invalid"))}).boolValue());
    assert(registry.invoke("gotoNetMovie", context, {Datum::of(std::string("next.dir"))}).intValue() == -1);

    NetManager builtinNet;
    builtinNet.setFetchHandler([](const NetTask& task) {
        if (task.method() == NetTaskMethod::Post) {
            return NetManager::LoadResult::success(std::vector<std::uint8_t>{'P', 'O', 'S', 'T'});
        }
        return NetManager::LoadResult::success(std::vector<std::uint8_t>{'O', 'K'});
    });
    context.netManager = &builtinNet;
    const int builtinGetTask = registry.invoke("preloadNetThing", context, {Datum::of(std::string("movie.dir"))}).intValue();
    assert(builtinGetTask == 1);
    assert(registry.invoke("getNetThing", context, {Datum::of(std::string("cached.dir"))}).intValue() == 2);
    assert(registry.invoke("getNetText", context, {Datum::of(std::string("text.dir"))}).intValue() == 3);
    assert(registry.invoke("netDone", context, {Datum::of(builtinGetTask)}).boolValue());
    assert(registry.invoke("netTextResult", context, {Datum::of(builtinGetTask)}).stringValue() == "OK");
    assert(registry.invoke("netError", context, {Datum::of(builtinGetTask)}).stringValue() == "OK");
    const auto builtinStatus = registry.invoke("getStreamStatus", context, {Datum::of(builtinGetTask)});
    assert(builtinStatusProp(builtinStatus, "state").stringValue() == "Complete");
    assert(builtinStatusProp(registry.invoke("getStreamStatus", context, {Datum::of(std::string("movie.dir"))}), "state").stringValue() == "Complete");
    const int builtinPostTask = registry.invoke("postNetText",
                                                context,
                                                {Datum::of(std::string("submit")), Datum::of(std::string("a=b"))}).intValue();
    assert(builtinPostTask == 4);
    assert(builtinNet.getTask(builtinPostTask)->postData().value() == "a=b");
    assert(registry.invoke("netTextResult", context, {Datum::of(builtinPostTask)}).stringValue() == "POST");

    std::string builtinPageUrl;
    std::string builtinPageTarget;
    std::string builtinMovieUrl;
    MovieProperties builtinNavigationMovie;
    builtinNavigationMovie.setGotoNetPageHandler([&builtinPageUrl, &builtinPageTarget](const std::string& url,
                                                                                       const std::string& target) {
        builtinPageUrl = url;
        builtinPageTarget = target;
    });
    builtinNavigationMovie.setGotoNetMovieHandler([&builtinMovieUrl](const std::string& url) {
        builtinMovieUrl = url;
        return 91;
    });
    context.movieProperties = &builtinNavigationMovie;
    assert(registry.invoke("gotoNetPage",
                           context,
                           {Datum::of(std::string("https://example.invalid")), Datum::of(std::string("_blank"))}).boolValue());
    assert(builtinPageUrl == "https://example.invalid");
    assert(builtinPageTarget == "_blank");
    assert(registry.invoke("gotoNetMovie", context, {Datum::of(std::string("next.dir"))}).intValue() == 91);
    assert(builtinMovieUrl == "next.dir");

    assert(registry.contains("externalParamValue"));
    assert(registry.contains("externalParamName"));
    assert(registry.contains("externalParamCount"));
    assert(registry.invoke("externalParamCount", context).intValue() == 0);
    assert(registry.invoke("externalParamValue", context).isVoid());
    context.externalParams = {{"sw1", "alpha"}, {"CaseKey", "Beta"}};
    assert(registry.invoke("externalParamCount", context).intValue() == 2);
    assert(registry.invoke("externalParamValue", context, {Datum::of(std::string("casekey"))}).stringValue() == "Beta");
    assert(registry.invoke("externalParamValue", context, {Datum::of(1)}).stringValue() == "alpha");
    assert(registry.invoke("externalParamValue", context, {Datum::of(0)}).isVoid());
    assert(registry.invoke("externalParamValue", context, {Datum::symbol("sw1")}).isVoid());
    assert(registry.invoke("externalParamName", context, {Datum::of(std::string("CASEKEY"))}).stringValue() == "CaseKey");
    assert(registry.invoke("externalParamName", context, {Datum::of(2)}).stringValue() == "CaseKey");
    assert(registry.invoke("externalParamName", context, {Datum::of(9)}).isVoid());

    assert(registry.contains("image"));
    assert(registry.contains("importFileInto"));
    assert(registry.invoke("image", context).isVoid());
    assert(registry.invoke("image", context, {Datum::of(0), Datum::of(2)}).isVoid());
    assert(registry.invoke("image", context, {Datum::of(2), Datum::of(-1)}).isVoid());
    const auto imageDatum = registry.invoke("image", context, {Datum::of(std::string("2")), Datum::of(3)});
    const auto* imageRef = imageDatum.asImageRef();
    assert(imageRef != nullptr);
    assert(imageRef->bitmap != nullptr);
    assert(imageRef->bitmap->width() == 2);
    assert(imageRef->bitmap->height() == 3);
    assert(imageRef->bitmap->bitDepth() == 32);
    for (const auto pixel : imageRef->bitmap->pixels()) {
        assert(pixel == 0xFFFFFFFFU);
    }
    assert(registry.invoke("string", context, {imageDatum}).stringValue() == "(image 2x3)");
    assert(registry.invoke("ilk", context, {imageDatum}).asSymbol()->name == "image");

    const auto systemImageDatum = registry.invoke("image",
                                                  context,
                                                  {Datum::of(1), Datum::of(1), Datum::of(8), Datum::symbol("systemMac")});
    const auto* systemImageRef = systemImageDatum.asImageRef();
    assert(systemImageRef != nullptr);
    assert(systemImageRef->bitmap->imagePalette().get() == &Palette::systemMacPalette());
    assert(systemImageRef->bitmap->paletteRefSystemName().has_value());
    assert(systemImageRef->bitmap->paletteRefSystemName().value() == "systemMac");

    const auto customPalette = std::make_shared<Palette>(std::vector<std::uint32_t>{0x000000U, 0xFFFFFFU}, "custom");
    context.imagePaletteResolver = [customPalette](const Datum& paletteArg) -> std::optional<BuiltinContext::ResolvedPalette> {
        const auto* ref = paletteArg.asCastMemberRef();
        assert(ref != nullptr);
        assert(ref->castLib == 2);
        assert(ref->memberNum() == 9);
        return BuiltinContext::ResolvedPalette{customPalette, Datum::CastMemberRef{2, 9}, std::nullopt};
    };
    const auto memberPaletteImageDatum = registry.invoke("image",
                                                         context,
                                                         {Datum::of(1),
                                                          Datum::of(1),
                                                          Datum::of(8),
                                                          Datum::castMemberRef(CastLibId(2), MemberId(9))});
    const auto* memberPaletteImageRef = memberPaletteImageDatum.asImageRef();
    assert(memberPaletteImageRef != nullptr);
    assert(memberPaletteImageRef->bitmap->imagePalette() == customPalette);
    assert(memberPaletteImageRef->bitmap->paletteRefCastLib() == 2);
    assert(memberPaletteImageRef->bitmap->paletteRefMemberNum() == 9);
    assert(!memberPaletteImageRef->bitmap->paletteRefSystemName().has_value());
    context.imagePaletteResolver = nullptr;

    assert(!registry.invoke("importFileInto", context).boolValue());
    assert(!registry.invoke("importFileInto", context, {Datum::castMemberRef(CastLibId(1), MemberId(5)), Datum::of(std::string("a.png"))}).boolValue());
    std::string importedUrl;
    Datum importedOptions = Datum::voidValue();
    Datum::CastMemberRef importedRef{0, 0};
    context.importFileIntoHandler = [&importedRef, &importedUrl, &importedOptions](
                                        const Datum::CastMemberRef& ref,
                                        const std::string& url,
                                        const Datum& options) {
        importedRef = ref;
        importedUrl = url;
        importedOptions = options;
        return true;
    };
    auto importOptions = Datum::propList();
    importOptions.propListValue().put(Datum::symbol("trimWhiteSpace"), Datum::TRUE);
    assert(!registry.invoke("importFileInto", context, {Datum::of(7), Datum::of(std::string("a.png"))}).boolValue());
    assert(registry.invoke("importFileInto",
                           context,
                           {Datum::castMemberRef(CastLibId(3), MemberId(12)),
                            Datum::of(std::string("media/a.png")),
                            importOptions}).boolValue());
    assert(importedRef.castLib == 3);
    assert(importedRef.memberNum() == 12);
    assert(importedUrl == "media/a.png");
    assert(importedOptions.propListValue().get(Datum::symbol("trimWhiteSpace")).boolValue());
    context.importFileIntoHandler = [](const Datum::CastMemberRef&, const std::string&, const Datum&) {
        return false;
    };
    assert(!registry.invoke("importFileInto",
                            context,
                            {Datum::castMemberRef(CastLibId(3), MemberId(12)),
                             Datum::of(std::string("media/a.png"))}).boolValue());
    context.importFileIntoHandler = nullptr;

    assert(registry.contains("sound"));
    assert(registry.contains("soundEnabled"));
    assert(registry.invoke("soundEnabled", context).boolValue());
    assert(registry.invoke("sound", context).isVoid());
    assert(registry.invoke("sound", context, {Datum::of(0)}).isVoid());
    assert(registry.invoke("sound", context, {Datum::of(9)}).isVoid());
    const auto builtinSoundDatum = registry.invoke("sound", context, {Datum::of(std::string("2"))});
    const auto* builtinSoundChannel = builtinSoundDatum.asSoundChannel();
    assert(builtinSoundChannel != nullptr);
    assert(builtinSoundChannel->channel == 2);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "volume", {}).intValue() == 255);
    assert(!libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "isBusy", {}).boolValue());
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "status", {}).intValue() == 0);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "getPlaylist", {}).listValue().count() == 0);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "ilk", {}).asSymbol()->name == "instance");
    assert(libreshockwave::lingo::builtin::SoundBuiltins::getProperty(context, *builtinSoundChannel, "loopCount").intValue() == 1);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::getProperty(context, *builtinSoundChannel, "currentTime").intValue() == 0);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::setProperty(context, *builtinSoundChannel, "volume", Datum::of(50)));
    assert(!libreshockwave::lingo::builtin::SoundBuiltins::setProperty(context, *builtinSoundChannel, "unknown", Datum::of(1)));

    class BuiltinRecordingAudioBackend final : public AudioBackend {
    public:
        void play(int channelNum,
                  const std::vector<std::uint8_t>& audioData,
                  std::string_view format,
                  int loopCount) override {
            ++playCount;
            lastPlayChannel = channelNum;
            lastAudioData = audioData;
            lastFormat = std::string(format);
            lastLoopCount = loopCount;
            playing[channelNum] = true;
        }

        void stop(int channelNum) override {
            ++stopCount;
            lastStopChannel = channelNum;
            playing[channelNum] = false;
        }

        void stopAll() override {
            playing.clear();
        }

        void setVolume(int channelNum, int volume) override {
            ++setVolumeCount;
            lastVolumeChannel = channelNum;
            lastVolume = volume;
        }

        [[nodiscard]] bool isPlaying(int channelNum) const override {
            const auto found = playing.find(channelNum);
            return found != playing.end() && found->second;
        }

        [[nodiscard]] int getElapsedTime(int channelNum) const override {
            const auto found = elapsedTimes.find(channelNum);
            return found == elapsedTimes.end() ? 0 : found->second;
        }

        int playCount{0};
        int stopCount{0};
        int setVolumeCount{0};
        int lastPlayChannel{0};
        int lastStopChannel{0};
        int lastVolumeChannel{0};
        int lastVolume{0};
        int lastLoopCount{0};
        std::vector<std::uint8_t> lastAudioData;
        std::string lastFormat;
        std::map<int, bool> playing;
        std::map<int, int> elapsedTimes;
    };

    SoundManager builtinSoundManager;
    BuiltinRecordingAudioBackend builtinSoundBackend;
    builtinSoundManager.setBackend(&builtinSoundBackend);
    builtinSoundManager.setAudioResolver([](const Datum::CastMemberRef& ref) {
        assert(ref.castLib == 1);
        assert(ref.memberNum() == 8);
        return std::optional<std::vector<std::uint8_t>>{{'R', 'I', 'F', 'F'}};
    });
    context.soundManager = &builtinSoundManager;
    builtinSoundBackend.elapsedTimes[2] = 37;
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "volume", {Datum::of(123)}).isVoid());
    assert(builtinSoundManager.getVolume(2) == 123);
    assert(builtinSoundBackend.lastVolumeChannel == 2);
    assert(builtinSoundBackend.lastVolume == 123);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "volume", {}).intValue() == 123);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::getProperty(context, *builtinSoundChannel, "volume").intValue() == 123);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context,
                                                                       *builtinSoundChannel,
                                                                       "play",
                                                                       {Datum::castMemberRef(CastLibId(1), MemberId(8))}).isVoid());
    assert(builtinSoundBackend.playCount == 1);
    assert(builtinSoundBackend.lastPlayChannel == 2);
    assert(builtinSoundBackend.lastFormat == "wav");
    assert(builtinSoundBackend.lastLoopCount == 1);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "isBusy", {}).boolValue());
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "status", {}).intValue() == 1);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "elapsedTime", {}).intValue() == 37);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::getProperty(context, *builtinSoundChannel, "currentTime").intValue() == 37);
    assert(libreshockwave::lingo::builtin::SoundBuiltins::handleMethod(context, *builtinSoundChannel, "stop", {}).isVoid());
    assert(builtinSoundBackend.stopCount == 1);
    assert(builtinSoundBackend.lastStopChannel == 2);
    assert(!builtinSoundBackend.isPlaying(2));
    assert(libreshockwave::lingo::builtin::SoundBuiltins::setProperty(context, *builtinSoundChannel, "volume", Datum::of(300)));
    assert(builtinSoundManager.getVolume(2) == 255);

    auto assertColor = [](const Datum& datum, int r, int g, int b) {
        const auto* color = datum.asColorRef();
        assert(color != nullptr);
        assert(color->r == r);
        assert(color->g == g);
        assert(color->b == b);
    };

    assert(registry.contains("point"));
    assert(registry.contains("rect"));
    assert(registry.contains("union"));
    assert(registry.contains("intersect"));
    assert(registry.contains("color"));
    assert(registry.contains("rgb"));
    assert(registry.contains("paletteIndex"));
    assert(registry.contains("sprite"));
    assert(registry.contains("new"));

    assert(registry.invoke("point", context) == Datum::intPoint(0, 0));
    assert(registry.invoke("point", context, {Datum::of(3), Datum::of(4)}) == Datum::intPoint(3, 4));
    assert(registry.invoke("rect", context, {Datum::of(1), Datum::of(2), Datum::of(3), Datum::of(4)}) ==
           Datum::intRect(1, 2, 3, 4));
    assert(registry.invoke("rect", context, {Datum::intPoint(1, 2), Datum::intPoint(9, 10)}) ==
           Datum::intRect(1, 2, 9, 10));
    assert(registry.invoke("union", context).isVoid());
    assert(registry.invoke("union", context, {Datum::intRect(0, 0, 0, 0), Datum::of(4)}).isVoid());
    assert(registry.invoke("union", context, {Datum::intRect(0, 0, 0, 0), Datum::intRect(8, 8, 8, 9)}) ==
           Datum::intRect(0, 0, 0, 0));
    assert(registry.invoke("union", context, {Datum::intRect(0, 0, 0, 0), Datum::intRect(1, 2, 3, 4)}) ==
           Datum::intRect(1, 2, 3, 4));
    assert(registry.invoke("union", context, {Datum::intRect(4, 1, 8, 9), Datum::intRect(1, 3, 6, 7)}) ==
           Datum::intRect(1, 1, 8, 9));
    assert(registry.invoke("intersect", context).isVoid());
    assert(registry.invoke("intersect", context, {Datum::intRect(0, 0, 1, 1), Datum::of(4)}).isVoid());
    assert(registry.invoke("intersect", context, {Datum::intRect(0, 0, 2, 2), Datum::intRect(3, 3, 4, 4)}) ==
           Datum::intRect(0, 0, 0, 0));
    assert(registry.invoke("intersect", context, {Datum::intRect(0, 0, 8, 8), Datum::intRect(3, 4, 10, 6)}) ==
           Datum::intRect(3, 4, 8, 6));
    assertColor(registry.invoke("color", context), 0, 0, 0);
    assertColor(registry.invoke("color", context, {Datum::of(11), Datum::of(22), Datum::of(33)}), 11, 22, 33);
    const auto rgbPassThrough = Datum::colorRef(7, 8, 9);
    assert(registry.invoke("rgb", context, {rgbPassThrough}) == rgbPassThrough);
    assertColor(registry.invoke("rgb", context), 0, 0, 0);
    assertColor(registry.invoke("rgb", context, {Datum::of(std::string(" #112233 "))}), 0x11, 0x22, 0x33);
    assertColor(registry.invoke("rgb", context, {Datum::of(std::string("not-hex"))}), 0, 0, 0);
    assertColor(registry.invoke("rgb", context, {Datum::of(1), Datum::of(2), Datum::of(3)}), 1, 2, 3);
    assertColor(registry.invoke("rgb", context, {Datum::of(0x445566)}), 0x44, 0x55, 0x66);
    assertColor(registry.invoke("paletteIndex", context), 0, 0, 0);
    assertColor(registry.invoke("paletteIndex", context, {Datum::of(300)}), 44, 44, 44);
    assert(registry.invoke("sprite", context).isVoid());
    assert(registry.invoke("sprite", context, {Datum::of(-1)}).isVoid());
    assert(registry.invoke("sprite", context, {Datum::of(6)}).asSpriteRef()->spriteNum() == 6);
    assert(registry.invoke("new", context).isVoid());

    int createdCastLib = 0;
    std::string createdMemberType;
    context.castMemberCreator = [&createdCastLib, &createdMemberType](int castLib, const std::string& memberType) {
        createdCastLib = castLib;
        createdMemberType = memberType;
        return Datum::castMemberRef(CastLibId(castLib), MemberId(42));
    };
    const auto createdMember = registry.invoke("new", context, {Datum::symbol("bitmap"), Datum::castLibRef(CastLibId(3))});
    assert(createdCastLib == 3);
    assert(createdMemberType == "bitmap");
    assert(createdMember.asCastMemberRef()->memberNum() == 42);

    int genericNewCalls = 0;
    context.newInstanceHandler = [&genericNewCalls](const Datum& target, const std::vector<Datum>& args) {
        ++genericNewCalls;
        assert(target.asScriptRef() != nullptr);
        assert(target.asScriptRef()->memberRef.castMember == 8);
        assert(args.size() == 1);
        assert(args.front().intValue() == 99);
        return Datum::scriptInstance("created");
    };
    const auto newScript = registry.invoke("new",
                                           context,
                                           {Datum::scriptRef(Datum::CastMemberRef{4, 8}), Datum::of(99)});
    assert(genericNewCalls == 1);
    assert(newScript.scriptInstanceValue().scriptName() == "created");

    assert(registry.contains("objectp"));
    assert(registry.contains("voidp"));
    assert(registry.contains("value"));
    assert(registry.contains("script"));
    assert(registry.contains("ilk"));
    assert(registry.contains("listp"));
    assert(registry.contains("stringp"));
    assert(registry.contains("integerp"));
    assert(registry.contains("floatp"));
    assert(registry.contains("symbolp"));
    assert(registry.contains("symbol"));
    assert(registry.contains("callAncestor"));

    assert(!registry.invoke("objectp", context).boolValue());
    assert(!registry.invoke("objectp", context, {Datum::of(1)}).boolValue());
    assert(!registry.invoke("objectp", context, {Datum::of(std::string("text"))}).boolValue());
    assert(!registry.invoke("objectp", context, {Datum::symbol("name")}).boolValue());
    assert(registry.invoke("objectp", context, {Datum::list()}).boolValue());
    assert(registry.invoke("objectp", context, {Datum::scriptInstance("instance")}).boolValue());
    assert(registry.invoke("voidp", context).boolValue());
    assert(registry.invoke("voidp", context, {Datum::voidValue()}).boolValue());
    assert(!registry.invoke("voidp", context, {Datum::of(1)}).boolValue());
    assert(registry.invoke("value", context).isVoid());
    assert(registry.invoke("value", context, {Datum::of(17)}).intValue() == 17);
    assert(registry.invoke("value", context, {Datum::of(std::string("3"))}).isVoid());
    context.valueEvaluator = [](const Datum& value) {
        assert(value.stringValue() == "3");
        return Datum::of(3);
    };
    assert(registry.invoke("value", context, {Datum::of(std::string("3"))}).intValue() == 3);

    assert(registry.invoke("script", context).isVoid());
    const auto directScript = registry.invoke("script",
                                              context,
                                              {Datum::castMemberRef(CastLibId(2), MemberId(7))});
    assert(directScript.asScriptRef()->memberRef.castLib == 2);
    assert(directScript.asScriptRef()->memberRef.castMember == 7);
    int scriptResolveCalls = 0;
    context.scriptResolver = [&scriptResolveCalls](const Datum& identifier, const std::optional<Datum>& scope) {
        ++scriptResolveCalls;
        if (scope.has_value()) {
            assert(scope->asCastLibRef() != nullptr);
            assert(scope->asCastLibRef()->castLib == 4);
        }
        const std::string name = identifier.asSymbol() != nullptr ? identifier.asSymbol()->name : identifier.stringValue();
        if (name == "Parent") {
            return Datum::scriptRef(Datum::CastMemberRef{4, 9});
        }
        return Datum::voidValue();
    };
    assert(registry.invoke("script", context, {Datum::of(std::string("Missing")), Datum::castLibRef(CastLibId(4))}).isVoid());
    const auto resolvedScript = registry.invoke("script",
                                                context,
                                                {Datum::symbol("Parent"), Datum::castLibRef(CastLibId(4))});
    assert(resolvedScript.asScriptRef()->memberRef.castMember == 9);
    const auto scriptCandidates = Datum::list({Datum::of(std::string("Missing")), Datum::symbol("Parent")});
    const auto resolvedFromList = registry.invoke("script", context, {scriptCandidates, Datum::castLibRef(CastLibId(4))});
    assert(resolvedFromList.asScriptRef()->memberRef.castMember == 9);
    assert(scriptResolveCalls == 4);

    assert(registry.invoke("ilk", context).asSymbol()->name == "void");
    assert(registry.invoke("ilk", context, {Datum::of(1)}).asSymbol()->name == "integer");
    assert(registry.invoke("ilk", context, {Datum::of(1.5F)}).asSymbol()->name == "float");
    assert(registry.invoke("ilk", context, {Datum::of(std::string("x"))}).asSymbol()->name == "string");
    assert(registry.invoke("ilk", context, {Datum::symbol("x")}).asSymbol()->name == "symbol");
    assert(registry.invoke("ilk", context, {Datum::propList()}).asSymbol()->name == "propList");
    assert(registry.invoke("ilk", context, {Datum::colorRef(1, 2, 3)}).asSymbol()->name == "color");
    assert(registry.invoke("ilk", context, {Datum::castMemberRef(CastLibId(1), MemberId(2))}).asSymbol()->name == "member");
    assert(registry.invoke("ilk", context, {Datum::castLibRef(CastLibId(1))}).asSymbol()->name == "castLib");
    assert(registry.invoke("ilk", context, {Datum::spriteRef(ChannelId(2))}).asSymbol()->name == "sprite");
    assert(registry.invoke("ilk", context, {Datum::stageRef()}).asSymbol()->name == "stage");
    assert(registry.invoke("ilk", context, {Datum::propList(), Datum::symbol("list")}).boolValue());
    assert(registry.invoke("ilk", context, {Datum::intPoint(1, 2), Datum::symbol("list")}).boolValue());
    assert(registry.invoke("ilk", context, {Datum::list(), Datum::symbol("linearList")}).boolValue());
    assert(registry.invoke("ilk", context, {Datum::of(1.5F), Datum::symbol("number")}).boolValue());
    assert(registry.invoke("ilk", context, {Datum::scriptInstance("child"), Datum::symbol("object")}).boolValue());
    assert(registry.invoke("ilk", context, {Datum::colorRef(1, 2, 3), Datum::symbol("object")}) == Datum::FALSE);
    assert(registry.invoke("listp", context, {Datum::list()}).boolValue());
    assert(registry.invoke("listp", context, {Datum::propList()}).boolValue());
    assert(!registry.invoke("listp", context, {Datum::intPoint(1, 2)}).boolValue());
    assert(registry.invoke("stringp", context, {Datum::of(std::string("x"))}).boolValue());
    assert(registry.invoke("integerp", context, {Datum::of(1)}).boolValue());
    assert(registry.invoke("floatp", context, {Datum::of(1.0F)}).boolValue());
    assert(registry.invoke("symbolp", context, {Datum::symbol("x")}).boolValue());
    assert(registry.invoke("symbol", context).isVoid());
    assert(registry.invoke("symbol", context, {Datum::of(7)}).isVoid());
    assert(registry.invoke("symbol", context, {Datum::symbol("already")}).asSymbol()->name == "already");
    assert(registry.invoke("symbol", context, {Datum::of(std::string("#door"))}).asSymbol()->name == "door");
    assert(registry.invoke("symbol", context, {Datum::of(std::string(""))}).asSymbol()->name.empty());
    assert(registry.invoke("callAncestor", context).isVoid());
    assert(registry.invoke("callAncestor", context, {Datum::symbol("new"), Datum::scriptInstance("child")}).isVoid());
    int ancestorCalls = 0;
    context.ancestorCallHandler = [&ancestorCalls](const std::vector<Datum>& args) {
        ++ancestorCalls;
        assert(args.size() == 3);
        assert(args[0].asSymbol()->name == "new");
        assert(args[2].intValue() == 5);
        return Datum::of(88);
    };
    assert(registry.invoke("callAncestor",
                           context,
                           {Datum::symbol("new"), Datum::scriptInstance("child"), Datum::of(5)}).intValue() == 88);
    assert(ancestorCalls == 1);

    MovieProperties movie;
    int requestedMarkerOffset = 0;
    movie.setFrameForLabelResolver([](const std::string& label) {
        if (label == "intro") {
            return 5;
        }
        if (label == "negative") {
            return -3;
        }
        return 0;
    });
    movie.setMarkerFrameResolver([&requestedMarkerOffset](int offset) {
        requestedMarkerOffset = offset;
        return offset == -1 ? 2 : -7;
    });
    context.movieProperties = &movie;

    assert(registry.invoke("label", context, {Datum::of(std::string("intro"))}).intValue() == 5);
    assert(registry.invoke("label", context, {Datum::movieRef(), Datum::of(std::string("intro"))}).intValue() == 5);
    assert(registry.invoke("label", context, {Datum::of(std::string("negative"))}).intValue() == 0);
    assert(registry.invoke("marker", context, {Datum::of(std::string("intro"))}).intValue() == 5);
    assert(registry.invoke("marker", context, {Datum::movieRef(), Datum::of(std::string("intro"))}).intValue() == 5);
    assert(registry.invoke("marker", context, {Datum::of(-1)}).intValue() == 2);
    assert(requestedMarkerOffset == -1);
    assert(registry.invoke("marker", context, {Datum::of(4)}).intValue() == 0);
    assert(requestedMarkerOffset == 4);

    SpriteRegistry spriteRegistry;
    SpriteProperties spriteProps(&spriteRegistry);
    context.spriteProperties = &spriteProps;

    assert(registry.invoke("puppetTempo", context, {Datum::of(23)}).isVoid());
    assert(movie.puppetTempo() == 23);
    assert(registry.invoke("cursor", context, {Datum::of(4)}).isVoid());
    assert(movie.getMovieProp("cursor").intValue() == 4);
    assert(registry.invoke("setCursor", context, {Datum::of(2)}).isVoid());
    assert(movie.getMovieProp("cursor").intValue() == 2);
    assert(registry.invoke("cursor", context).isVoid());
    assert(movie.getMovieProp("cursor").intValue() == 2);

    assert(registry.invoke("puppetSprite", context, {Datum::of(4), Datum::TRUE}).isVoid());
    assert(spriteProps.getSpriteProp(4, "puppet").intValue() == 1);
    assert(spriteProps.setSpriteProp(4, "rect", Datum::intRect(1, 2, 11, 22)));
    assert(registry.invoke("spriteBox", context, {Datum::of(4)}) == Datum::intRect(1, 2, 11, 22));
    assert(registry.invoke("puppetSprite", context).isVoid());
    assert(spriteProps.getSpriteProp(4, "puppet").intValue() == 1);

    int paletteCalls = 0;
    std::optional<Datum> capturedPalette;
    context.puppetPaletteHandler = [&paletteCalls, &capturedPalette](std::optional<Datum> paletteRef) {
        ++paletteCalls;
        capturedPalette = std::move(paletteRef);
    };
    assert(registry.invoke("puppetPalette", context, {Datum::of(0)}).isVoid());
    assert(paletteCalls == 1);
    assert(!capturedPalette.has_value());
    assert(registry.invoke("puppetPalette", context, {Datum::of(-1)}).isVoid());
    assert(paletteCalls == 2);
    assert(!capturedPalette.has_value());
    assert(registry.invoke("puppetPalette", context, {Datum::castMemberRef(CastLibId(1), MemberId(9))}).isVoid());
    assert(paletteCalls == 3);
    assert(capturedPalette.has_value());
    assert(capturedPalette->asCastMemberRef()->memberNum() == 9);

    assert(registry.invoke("pauseUpdate", context).isVoid());
    assert(registry.invoke("updateStage", context).isVoid());
    assert(registry.invoke("moveToFront", context, {Datum::of(4)}).isVoid());
    assert(registry.invoke("moveToBack", context, {Datum::of(4)}).isVoid());

    registry.registerBuiltin("Echo", [](BuiltinContext&, const std::vector<Datum>& args) {
        return args.empty() ? Datum::voidValue() : args.front();
    });
    assert(registry.contains("echo"));
    assert(registry.invoke("ECHO", context, {Datum::of(42)}).intValue() == 42);
    assert(registry.map().contains("echo"));
}

void testLingoVmScopeAndExecutionContextFoundation() {
    auto makeHandler = [](int nameId, int localCount, std::vector<int> offsets) {
        std::vector<ScriptChunk::Instruction> instructions;
        std::unordered_map<int, int> indexMap;
        for (std::size_t index = 0; index < offsets.size(); ++index) {
            const int offset = offsets[index];
            indexMap[offset] = static_cast<int>(index);
            instructions.push_back(ScriptChunk::Instruction{offset, Opcode::PUSH_INT8, 0x41, offset + 1});
        }
        return ScriptChunk::Handler{nameId,
                                    0,
                                    static_cast<int>(instructions.size()),
                                    0,
                                    2,
                                    localCount,
                                    0,
                                    0,
                                    {},
                                    {},
                                    std::move(instructions),
                                    std::move(indexMap)};
    };

    auto handler = makeHandler(10, 2, {0, 5, 10});
    auto otherHandler = makeHandler(99, 0, {0});
    ScriptChunk script(nullptr,
                       ChunkId(700),
                       ScriptChunkType::MovieScript,
                       0,
                       {handler, otherHandler},
                       {ScriptChunk::LiteralEntry{1, 0, std::string("hello"), 0.0},
                        ScriptChunk::LiteralEntry{4, 0, 42, 0.0},
                        ScriptChunk::LiteralEntry{9, 0, std::string("1.5"), 1.5}},
                       {},
                       {},
                       {});

    Scope scope(&script, handler, {Datum::of(1), Datum::of(2)});
    assert(scope.script() == &script);
    assert(scope.handler().nameId == 10);
    assert(scope.arguments().size() == 2);
    assert(scope.receiver().isVoid());
    assert(scope.bytecodeIndex() == 0);
    assert(scope.hasMoreInstructions());
    assert(scope.currentInstruction()->offset == 0);
    scope.advanceBytecodeIndex();
    assert(scope.currentInstruction()->offset == 5);
    scope.setBytecodeIndex(42);
    assert(!scope.hasMoreInstructions());
    assert(scope.currentInstruction() == nullptr);

    assert(scope.pop().isVoid());
    scope.push(Datum::of(1));
    scope.push(Datum::of(2));
    scope.push(Datum::of(3));
    assert(scope.stackSize() == 3);
    assert(scope.peek().intValue() == 3);
    assert(scope.peek(2).intValue() == 1);
    assert(scope.peek(3).isVoid());
    scope.swap();
    assert(scope.peek().intValue() == 2);
    scope.replaceTop(Datum::of(4));
    assert(scope.peek().intValue() == 4);
    scope.replaceTopTwo(Datum::of(9));
    assert(scope.stackSize() == 2);
    assert(scope.peek().intValue() == 9);
    scope.drop(9);
    assert(scope.stackSize() == 0);
    scope.replaceTopTwo(Datum::of(11));
    assert(scope.stackSize() == 1);
    assert(scope.pop().intValue() == 11);

    assert(scope.getLocal(0).isVoid());
    scope.setLocal(0, Datum::of(77));
    scope.setLocal(99, Datum::of(88));
    assert(scope.getLocal(0).intValue() == 77);
    assert(scope.getLocal(99).isVoid());

    assert(scope.getParam(0).intValue() == 1);
    scope.setParam(0, Datum::of(33));
    assert(scope.getParam(0).intValue() == 33);
    assert(scope.getParam(9).isVoid());

    assert(!scope.returned());
    scope.setReturnValue(Datum::of(std::string("done")));
    assert(scope.returned());
    assert(scope.returnValue().stringValue() == "done");
    scope.setReturned(false);
    assert(!scope.returned());

    assert(!scope.inLoop());
    assert(scope.popLoopReturnIndex() == -1);
    scope.pushLoopReturnIndex(12);
    scope.pushLoopReturnIndex(20);
    assert(scope.inLoop());
    assert(scope.popLoopReturnIndex() == 20);
    assert(scope.popLoopReturnIndex() == 12);
    assert(!scope.inLoop());
    assert(scope.toString() == "Scope{handler#10, bytecodeIndex=42, stackSize=0, returned=false}");

    const auto receiver = Datum::scriptInstance("receiver");
    Scope receiverScope(&script, handler, {receiver, Datum::of(7)}, receiver);
    assert(receiverScope.getParam(0).intValue() == 7);
    assert(receiverScope.displayArguments().size() == 1);
    assert(receiverScope.displayArguments()[0].intValue() == 7);
    receiverScope.setParam(0, Datum::of(8));
    assert(receiverScope.getParam(0).intValue() == 8);
    assert(receiverScope.displayArguments()[0].intValue() == 8);

    Scope firstParamMeScope(&script, handler, {receiver, Datum::of(7)}, receiver, true);
    assert(firstParamMeScope.getParam(0) == receiver);
    assert(firstParamMeScope.displayArguments().size() == 1);
    assert(firstParamMeScope.displayArguments()[0].intValue() == 7);

    BuiltinRegistry registry;
    BuiltinContext builtinContext;
    std::map<std::string, Datum> globals;
    bool errorState = false;
    ExecutionContext::Callbacks callbacks;
    callbacks.globalGetter = [&globals](std::string_view name) {
        const auto found = globals.find(std::string(name));
        return found == globals.end() ? Datum::voidValue() : found->second;
    };
    callbacks.globalSetter = [&globals](std::string_view name, const Datum& value) {
        globals[std::string(name)] = value;
    };
    callbacks.errorStateSetter = [&errorState](bool value) {
        errorState = value;
    };
    callbacks.callStackFormatter = []() {
        return std::string("stack");
    };
    callbacks.handlerFinder = [&script, &otherHandler](std::string_view name) -> std::optional<HandlerRef> {
        if (name == "known") {
            return HandlerRef{&script, otherHandler};
        }
        return std::nullopt;
    };
    callbacks.handlerExecutor = [](const ScriptChunk& calledScript,
                                   const ScriptChunk::Handler& calledHandler,
                                   const std::vector<Datum>& args,
                                   const Datum& receiverArg) {
        assert(calledScript.id().value() == 700);
        assert(receiverArg.isVoid());
        return Datum::of("exec:" + std::to_string(calledHandler.nameId) + ":" + std::to_string(args.size()));
    };

    Scope contextScope(&script, handler, {});
    ExecutionContext context(contextScope, handler.instructions[1], &registry, &builtinContext, callbacks, 2);
    assert(context.scope().script() == &script);
    assert(context.instruction().offset == 5);
    assert(context.argument() == 6);
    assert(context.scaledArgument() == 3);
    assert(context.variableMultiplier() == 2);
    assert(context.instructionOffset() == 5);

    context.push(Datum::of(4));
    context.push(Datum::of(5));
    assert(context.peek().intValue() == 5);
    assert(context.peek(1).intValue() == 4);
    const auto poppedArgs = context.popArgs(2);
    assert(poppedArgs.size() == 2);
    assert(poppedArgs[0].intValue() == 4);
    assert(poppedArgs[1].intValue() == 5);
    assert(context.popArgs(0).empty());

    context.setLocal(1, Datum::of(44));
    assert(context.getLocal(1).intValue() == 44);
    context.setParam(0, Datum::of(55));
    assert(context.getParam(0).intValue() == 55);

    assert(context.getGlobal("score").isVoid());
    context.setGlobal("score", Datum::of(12));
    assert(context.getGlobal("score").intValue() == 12);
    context.setErrorState(true);
    assert(errorState);

    context.jumpTo(10);
    assert(contextScope.bytecodeIndex() == 2);
    context.jumpTo(99);
    assert(contextScope.bytecodeIndex() == 2);
    assert(context.findLocalHandler(1)->nameId == 99);
    assert(!context.findLocalHandler(99).has_value());
    assert(context.findHandler("known")->handler.nameId == 99);
    assert(!context.findHandler("missing").has_value());
    assert(context.executeHandler(script, handler, {Datum::of(1)}, Datum::voidValue()).stringValue() == "exec:10:1");

    assert(context.isBuiltin("abs"));
    assert(context.invokeBuiltin("abs", {Datum::of(-4)}).intValue() == 4);
    assert(context.invokeBuiltinIfPresent("integer", {Datum::of(std::string("7"))})->intValue() == 7);
    assert(!context.invokeBuiltinIfPresent("missing", {}).has_value());
    assert(context.builtins() == &registry);
    assert(context.formatCallStack() == "stack");
    assert(std::string(context.error("boom").what()) == "boom");

    context.setInstruction(ScriptChunk::Instruction{20, Opcode::PUSH_INT8, 0x41, 8});
    assert(context.argument() == 8);
    assert(context.scaledArgument() == 4);
    assert(context.instructionOffset() == 20);

    OpcodeRegistry opcodeRegistry;
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_ZERO));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_INT8));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_INT16));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_INT32));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_FLOAT32));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_CONS));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_SYMB));
    assert(opcodeRegistry.hasHandler(Opcode::SWAP));
    assert(opcodeRegistry.hasHandler(Opcode::POP));
    assert(opcodeRegistry.hasHandler(Opcode::PEEK));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_LIST));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_PROP_LIST));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_ARG_LIST));
    assert(opcodeRegistry.hasHandler(Opcode::PUSH_ARG_LIST_NO_RET));
    assert(opcodeRegistry.hasHandler(Opcode::ADD));
    assert(opcodeRegistry.hasHandler(Opcode::SUB));
    assert(opcodeRegistry.hasHandler(Opcode::MUL));
    assert(opcodeRegistry.hasHandler(Opcode::DIV));
    assert(opcodeRegistry.hasHandler(Opcode::MOD));
    assert(opcodeRegistry.hasHandler(Opcode::INV));
    assert(opcodeRegistry.hasHandler(Opcode::LT));
    assert(opcodeRegistry.hasHandler(Opcode::LT_EQ));
    assert(opcodeRegistry.hasHandler(Opcode::GT));
    assert(opcodeRegistry.hasHandler(Opcode::GT_EQ));
    assert(opcodeRegistry.hasHandler(Opcode::EQ));
    assert(opcodeRegistry.hasHandler(Opcode::NT_EQ));
    assert(opcodeRegistry.hasHandler(Opcode::AND));
    assert(opcodeRegistry.hasHandler(Opcode::OR));
    assert(opcodeRegistry.hasHandler(Opcode::NOT));
    assert(opcodeRegistry.hasHandler(Opcode::GET_LOCAL));
    assert(opcodeRegistry.hasHandler(Opcode::SET_LOCAL));
    assert(opcodeRegistry.hasHandler(Opcode::GET_PARAM));
    assert(opcodeRegistry.hasHandler(Opcode::SET_PARAM));
    assert(opcodeRegistry.hasHandler(Opcode::GET_GLOBAL));
    assert(opcodeRegistry.hasHandler(Opcode::GET_GLOBAL2));
    assert(opcodeRegistry.hasHandler(Opcode::SET_GLOBAL));
    assert(opcodeRegistry.hasHandler(Opcode::SET_GLOBAL2));
    assert(opcodeRegistry.hasHandler(Opcode::RET));
    assert(opcodeRegistry.hasHandler(Opcode::RET_FACTORY));
    assert(opcodeRegistry.hasHandler(Opcode::JMP));
    assert(opcodeRegistry.hasHandler(Opcode::JMP_IF_Z));
    assert(opcodeRegistry.hasHandler(Opcode::END_REPEAT));
    assert(!opcodeRegistry.hasHandler(Opcode::INVALID));

    Scope opScope(&script, handler, {});
    ExecutionContext opContext(opScope, ScriptChunk::Instruction{0, Opcode::PUSH_ZERO, 0x03, 0});
    assert(opcodeRegistry.execute(Opcode::PUSH_ZERO, opContext));
    assert(opContext.pop().intValue() == 0);
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_INT8, 0x41, -7});
    assert(opcodeRegistry.execute(Opcode::PUSH_INT8, opContext));
    assert(opContext.pop().intValue() == -7);
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_INT16, 0x6E, 300});
    assert(opcodeRegistry.execute(Opcode::PUSH_INT16, opContext));
    assert(opContext.pop().intValue() == 300);
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_FLOAT32, 0x71, std::bit_cast<int>(1.25F)});
    assert(opcodeRegistry.execute(Opcode::PUSH_FLOAT32, opContext));
    assert(std::fabs(opContext.pop().floatValue() - 1.25F) < 0.0001F);

    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_CONS, 0x44, 0});
    assert(opcodeRegistry.execute(Opcode::PUSH_CONS, opContext));
    assert(opContext.pop().stringValue() == "hello");
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_CONS, 0x44, 1});
    assert(opcodeRegistry.execute(Opcode::PUSH_CONS, opContext));
    assert(opContext.pop().intValue() == 42);
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_CONS, 0x44, 2});
    assert(opcodeRegistry.execute(Opcode::PUSH_CONS, opContext));
    assert(std::fabs(opContext.pop().floatValue() - 1.5F) < 0.0001F);
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_CONS, 0x44, 99});
    assert(opcodeRegistry.execute(Opcode::PUSH_CONS, opContext));
    assert(opContext.pop().isVoid());
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_SYMB, 0x45, 123});
    assert(opcodeRegistry.execute(Opcode::PUSH_SYMB, opContext));
    assert(opContext.pop().asSymbol()->name == "#123");

    opContext.push(Datum::of(1));
    opContext.push(Datum::of(2));
    assert(opcodeRegistry.execute(Opcode::SWAP, opContext));
    assert(opContext.pop().intValue() == 1);
    assert(opContext.pop().intValue() == 2);
    opContext.push(Datum::of(1));
    opContext.push(Datum::of(2));
    opContext.push(Datum::of(3));
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::POP, 0x65, 2});
    assert(opcodeRegistry.execute(Opcode::POP, opContext));
    assert(opContext.pop().intValue() == 1);
    opContext.push(Datum::of(4));
    opContext.push(Datum::of(5));
    opContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PEEK, 0x64, 1});
    assert(opcodeRegistry.execute(Opcode::PEEK, opContext));
    assert(opContext.pop().intValue() == 4);
    assert(opContext.pop().intValue() == 5);
    assert(opContext.pop().intValue() == 4);

    Scope variableScope(&script, handler, {Datum::of(11), Datum::of(22)});
    ExecutionContext variableContext(variableScope,
                                     ScriptChunk::Instruction{0, Opcode::SET_LOCAL, libreshockwave::lingo::code(Opcode::SET_LOCAL), 2},
                                     &registry,
                                     &builtinContext,
                                     callbacks,
                                     2);
    variableContext.push(Datum::of(123));
    assert(opcodeRegistry.execute(Opcode::SET_LOCAL, variableContext));
    assert(variableScope.getLocal(1).intValue() == 123);
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::GET_LOCAL, libreshockwave::lingo::code(Opcode::GET_LOCAL), 2});
    assert(opcodeRegistry.execute(Opcode::GET_LOCAL, variableContext));
    assert(variableContext.pop().intValue() == 123);
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::SET_PARAM, libreshockwave::lingo::code(Opcode::SET_PARAM), 0});
    variableContext.push(Datum::of(44));
    assert(opcodeRegistry.execute(Opcode::SET_PARAM, variableContext));
    assert(variableScope.getParam(0).intValue() == 44);
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::GET_PARAM, libreshockwave::lingo::code(Opcode::GET_PARAM), 0});
    assert(opcodeRegistry.execute(Opcode::GET_PARAM, variableContext));
    assert(variableContext.pop().intValue() == 44);
    globals.clear();
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::SET_GLOBAL, libreshockwave::lingo::code(Opcode::SET_GLOBAL), 42});
    variableContext.push(Datum::of(std::string("scoreValue")));
    assert(opcodeRegistry.execute(Opcode::SET_GLOBAL, variableContext));
    assert(globals["#42"].stringValue() == "scoreValue");
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::GET_GLOBAL2, libreshockwave::lingo::code(Opcode::GET_GLOBAL2), 42});
    assert(opcodeRegistry.execute(Opcode::GET_GLOBAL2, variableContext));
    assert(variableContext.pop().stringValue() == "scoreValue");
    variableContext.setInstruction(ScriptChunk::Instruction{0, Opcode::SET_GLOBAL2, libreshockwave::lingo::code(Opcode::SET_GLOBAL2), 43});
    variableContext.push(Datum::of(77));
    assert(opcodeRegistry.execute(Opcode::SET_GLOBAL2, variableContext));
    assert(globals["#43"].intValue() == 77);

    Scope listScope(&script, handler, {});
    ExecutionContext listContext(listScope, ScriptChunk::Instruction{0, Opcode::PUSH_ARG_LIST, libreshockwave::lingo::code(Opcode::PUSH_ARG_LIST), 3});
    listContext.push(Datum::of(1));
    listContext.push(Datum::of(std::string("two")));
    listContext.push(Datum::of(3));
    assert(opcodeRegistry.execute(Opcode::PUSH_ARG_LIST, listContext));
    assert(listContext.peek().type() == DatumType::ArgList);
    assert(listContext.peek().argListValue().args()[1].stringValue() == "two");
    listContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_LIST, libreshockwave::lingo::code(Opcode::PUSH_LIST), 0});
    assert(opcodeRegistry.execute(Opcode::PUSH_LIST, listContext));
    const Datum linearList = listContext.pop();
    assert(linearList.listValue().count() == 3);
    assert(linearList.listValue().getAt(3).intValue() == 3);

    Scope singleListScope(&script, handler, {});
    ExecutionContext singleListContext(singleListScope,
                                       ScriptChunk::Instruction{0, Opcode::PUSH_LIST, libreshockwave::lingo::code(Opcode::PUSH_LIST), 0});
    singleListContext.push(Datum::of(8));
    assert(opcodeRegistry.execute(Opcode::PUSH_LIST, singleListContext));
    assert(singleListContext.pop().listValue().getAt(1).intValue() == 8);

    Scope propListScope(&script, handler, {});
    ExecutionContext propListContext(propListScope,
                                     ScriptChunk::Instruction{0, Opcode::PUSH_ARG_LIST_NO_RET, libreshockwave::lingo::code(Opcode::PUSH_ARG_LIST_NO_RET), 6});
    propListContext.push(Datum::symbol("name"));
    propListContext.push(Datum::of(std::string("first")));
    propListContext.push(Datum::symbol("name"));
    propListContext.push(Datum::of(std::string("duplicate")));
    propListContext.push(Datum::symbol("other"));
    propListContext.push(Datum::of(3));
    assert(opcodeRegistry.execute(Opcode::PUSH_ARG_LIST_NO_RET, propListContext));
    assert(propListContext.peek().type() == DatumType::ArgListNoRet);
    assert(propListContext.peek().argListNoRetValue().args().size() == 6);
    propListContext.setInstruction(ScriptChunk::Instruction{0, Opcode::PUSH_PROP_LIST, libreshockwave::lingo::code(Opcode::PUSH_PROP_LIST), 0});
    assert(opcodeRegistry.execute(Opcode::PUSH_PROP_LIST, propListContext));
    const Datum propList = propListContext.pop();
    assert(propList.propListValue().properties().size() == 3);
    assert(propList.propListValue().properties()[0].second.stringValue() == "first");
    assert(propList.propListValue().properties()[1].second.stringValue() == "duplicate");
    assert(propList.propListValue().properties()[2].second.intValue() == 3);

    auto runUnary = [&](Opcode opcode, Datum value) {
        Scope unaryScope(&script, handler, {});
        ExecutionContext unaryContext(unaryScope, ScriptChunk::Instruction{0, opcode, libreshockwave::lingo::code(opcode), 0});
        unaryContext.push(std::move(value));
        assert(opcodeRegistry.execute(opcode, unaryContext));
        return unaryContext.pop();
    };
    auto runBinary = [&](Opcode opcode, Datum a, Datum b) {
        Scope binaryScope(&script, handler, {});
        ExecutionContext binaryContext(binaryScope, ScriptChunk::Instruction{0, opcode, libreshockwave::lingo::code(opcode), 0});
        binaryContext.push(std::move(a));
        binaryContext.push(std::move(b));
        assert(opcodeRegistry.execute(opcode, binaryContext));
        return binaryContext.pop();
    };

    assert(runBinary(Opcode::ADD, Datum::of(4), Datum::of(5)).intValue() == 9);
    assert(std::fabs(runBinary(Opcode::ADD, Datum::of(1.5F), Datum::of(2)).floatValue() - 3.5F) < 0.0001F);
    assert(runBinary(Opcode::ADD, Datum::castLibRef(libreshockwave::id::CastLibId(7)), Datum::of(2)).intValue() == 9);
    assert(runBinary(Opcode::ADD, Datum::spriteRef(libreshockwave::id::ChannelId(5)), Datum::of(2)).intValue() == 7);
    assert(std::fabs(runBinary(Opcode::ADD,
                               Datum::stringChunk(Datum::of(std::string("2.5")),
                                                  StringChunkType::Char,
                                                  1,
                                                  3,
                                                  ',',
                                                  "2.5"),
                               Datum::of(0.5F)).floatValue() - 3.0F) < 0.0001F);
    assert(runBinary(Opcode::ADD, Datum::intPoint(1, 2), Datum::intPoint(3, 4)) == Datum::intPoint(4, 6));
    assert(runBinary(Opcode::ADD, Datum::intPoint(1, 2), Datum::list({Datum::of(5), Datum::of(6)})) == Datum::intPoint(6, 8));
    assert(runBinary(Opcode::ADD, Datum::intRect(1, 2, 3, 4), Datum::of(10)) == Datum::intRect(11, 12, 13, 14));
    assert(runBinary(Opcode::ADD,
                     Datum::intRect(1, 2, 3, 4),
                     Datum::list({Datum::of(2), Datum::of(3), Datum::of(4), Datum::of(5)})) == Datum::intRect(3, 5, 7, 9));
    assert(runBinary(Opcode::ADD,
                     Datum::list({Datum::of(1), Datum::of(2.5F)}),
                     Datum::list({Datum::of(3), Datum::of(4)})).listValue().getAt(2).floatValue() == 6.5F);
    assert(runBinary(Opcode::ADD, Datum::colorRef(250, 1, 2), Datum::colorRef(10, 20, 30)) == Datum::colorRef(255, 21, 32));

    assert(runBinary(Opcode::SUB, Datum::of(10), Datum::of(3)).intValue() == 7);
    assert(runBinary(Opcode::SUB, Datum::intPoint(10, 20), Datum::of(3)) == Datum::intPoint(7, 17));
    assert(runBinary(Opcode::SUB, Datum::intRect(10, 20, 30, 40), Datum::of(5)) == Datum::intRect(5, 15, 25, 35));
    assert(runBinary(Opcode::SUB,
                     Datum::list({Datum::of(9), Datum::of(7.5F)}),
                     Datum::list({Datum::of(2), Datum::of(1)})).listValue().getAt(1).intValue() == 7);
    assert(runBinary(Opcode::SUB, Datum::colorRef(2, 20, 30), Datum::colorRef(10, 5, 40)) == Datum::colorRef(0, 15, 0));

    assert(runBinary(Opcode::MUL, Datum::of(6), Datum::of(7)).intValue() == 42);
    assert(std::fabs(runBinary(Opcode::MUL, Datum::of(2), Datum::of(2.5F)).floatValue() - 5.0F) < 0.0001F);
    assert(runBinary(Opcode::MUL, Datum::intPoint(3, 4), Datum::of(2)) == Datum::intPoint(6, 8));
    assert(runBinary(Opcode::MUL, Datum::of(3), Datum::intRect(1, 2, 3, 4)) == Datum::intRect(3, 6, 9, 12));
    assert(runBinary(Opcode::MUL, Datum::list({Datum::of(2), Datum::of(3)}), Datum::of(4)).listValue().getAt(2).intValue() == 12);

    assert(runBinary(Opcode::DIV, Datum::of(9), Datum::of(2)).intValue() == 4);
    assert(std::fabs(runBinary(Opcode::DIV, Datum::of(9.0F), Datum::of(2)).floatValue() - 4.5F) < 0.0001F);
    assert(runBinary(Opcode::DIV, Datum::intPoint(9, 4), Datum::of(2)) == Datum::intPoint(4, 2));
    assert(runBinary(Opcode::DIV, Datum::intRect(60, 40, 120, 200), Datum::of(3)) == Datum::intRect(20, 13, 40, 66));
    assert(runBinary(Opcode::DIV, Datum::list({Datum::of(8), Datum::of(6)}), Datum::of(2)).listValue().getAt(1).intValue() == 4);
    bool divisionThrew = false;
    try {
        (void)runBinary(Opcode::DIV, Datum::of(1), Datum::of(0));
    } catch (const LingoException& e) {
        divisionThrew = std::string(e.what()) == "Division by zero";
    }
    assert(divisionThrew);

    assert(runBinary(Opcode::MOD, Datum::of(10), Datum::of(4)).intValue() == 2);
    bool moduloThrew = false;
    try {
        (void)runBinary(Opcode::MOD, Datum::of(1), Datum::of(0));
    } catch (const LingoException& e) {
        moduloThrew = std::string(e.what()) == "Modulo by zero";
    }
    assert(moduloThrew);

    assert(runUnary(Opcode::INV, Datum::of(7)).intValue() == -7);
    assert(std::fabs(runUnary(Opcode::INV, Datum::of(1.5F)).floatValue() + 1.5F) < 0.0001F);
    assert(runUnary(Opcode::INV, Datum::intPoint(3, -4)) == Datum::intPoint(-3, 4));
    assert(runUnary(Opcode::INV, Datum::intRect(1, -2, 3, -4)) == Datum::intRect(-1, 2, -3, 4));

    assert(runBinary(Opcode::LT, Datum::of(2), Datum::of(3)).boolValue());
    assert(runBinary(Opcode::LT_EQ, Datum::of(3), Datum::of(3.0F)).boolValue());
    assert(runBinary(Opcode::GT, Datum::of(5), Datum::of(4)).boolValue());
    assert(runBinary(Opcode::GT_EQ, Datum::of(5.0F), Datum::of(5)).boolValue());
    assert(runBinary(Opcode::EQ, Datum::of(5), Datum::of(5.0F)).boolValue());
    assert(runBinary(Opcode::EQ, Datum::of(std::string("Door")), Datum::symbol("door")).boolValue());
    assert(runBinary(Opcode::NT_EQ, Datum::intPoint(1, 2), Datum::intPoint(1, 3)).boolValue());
    assert(runBinary(Opcode::AND, Datum::TRUE, Datum::of(std::string("x"))).boolValue());
    assert(!runBinary(Opcode::AND, Datum::TRUE, Datum::FALSE).boolValue());
    assert(runBinary(Opcode::OR, Datum::FALSE, Datum::symbol("x")).boolValue());
    assert(runUnary(Opcode::NOT, Datum::FALSE).boolValue());
    assert(!runUnary(Opcode::NOT, Datum::of(3)).boolValue());

    Scope retScope(&script, handler, {});
    ExecutionContext retContext(retScope, ScriptChunk::Instruction{0, Opcode::RET, 0x01, 0});
    retContext.push(Datum::of(std::string("returned")));
    assert(opcodeRegistry.execute(Opcode::RET, retContext));
    assert(retScope.returned());
    assert(retScope.returnValue().stringValue() == "returned");

    Scope factoryScope(&script, handler, {});
    ExecutionContext factoryContext(factoryScope, ScriptChunk::Instruction{0, Opcode::RET_FACTORY, 0x02, 0});
    assert(opcodeRegistry.execute(Opcode::RET_FACTORY, factoryContext));
    assert(factoryScope.returned());
    assert(factoryScope.returnValue().isVoid());

    Scope jumpScope(&script, handler, {});
    ExecutionContext jumpContext(jumpScope, ScriptChunk::Instruction{5, Opcode::JMP, 0x53, 5});
    assert(!opcodeRegistry.execute(Opcode::JMP, jumpContext));
    assert(jumpScope.bytecodeIndex() == 2);

    Scope jumpZeroScope(&script, handler, {});
    ExecutionContext jumpZeroContext(jumpZeroScope, ScriptChunk::Instruction{5, Opcode::JMP_IF_Z, 0x55, 5});
    jumpZeroContext.push(Datum::of(0));
    assert(!opcodeRegistry.execute(Opcode::JMP_IF_Z, jumpZeroContext));
    assert(jumpZeroScope.bytecodeIndex() == 2);

    Scope noJumpScope(&script, handler, {});
    ExecutionContext noJumpContext(noJumpScope, ScriptChunk::Instruction{5, Opcode::JMP_IF_Z, 0x55, 5});
    noJumpContext.push(Datum::of(1));
    assert(opcodeRegistry.execute(Opcode::JMP_IF_Z, noJumpContext));
    assert(noJumpScope.bytecodeIndex() == 0);

    Scope repeatScope(&script, handler, {});
    repeatScope.setBytecodeIndex(2);
    ExecutionContext repeatContext(repeatScope, ScriptChunk::Instruction{10, Opcode::END_REPEAT, 0x54, 10});
    assert(!opcodeRegistry.execute(Opcode::END_REPEAT, repeatContext));
    assert(repeatScope.bytecodeIndex() == 0);

    assert(!opcodeRegistry.execute(Opcode::INVALID, opContext));
    opcodeRegistry.registerHandler(Opcode::INVALID, [](ExecutionContext& customContext) {
        customContext.push(Datum::of(99));
        return true;
    });
    assert(opcodeRegistry.hasHandler(Opcode::INVALID));
    assert(opcodeRegistry.execute(Opcode::INVALID, opContext));
    assert(opContext.pop().intValue() == 99);
}

std::shared_ptr<Bitmap> makeSolidHitBitmap(std::uint32_t argb) {
    auto bitmap = std::make_shared<Bitmap>(3, 3, 32);
    bitmap->fill(argb);
    return bitmap;
}

std::vector<std::uint8_t> makeNativeAlphaBitmapSpecificData(int alphaThreshold) {
    return {
        0x80, 0x0C,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x03,
        0x00, 0x03,
        static_cast<std::uint8_t>(alphaThreshold & 0xFF),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x00, 0x00,
        0x10,
        0x20,
        0x00, 0x00,
        0x00, 0x00
    };
}

std::shared_ptr<CastMemberChunk> makeHitBitmapMember(int id, int alphaThreshold = 0) {
    auto specificData = makeNativeAlphaBitmapSpecificData(alphaThreshold);
    return std::make_shared<CastMemberChunk>(nullptr,
                                             ChunkId(id),
                                             MemberType::Bitmap,
                                             0,
                                             static_cast<int>(specificData.size()),
                                             std::vector<std::uint8_t>{},
                                             specificData,
                                             "hit",
                                             0,
                                             0,
                                             0);
}

RenderSprite makeHitSprite(int channel,
                           std::shared_ptr<const Bitmap> baked,
                           int ink = 0,
                           std::shared_ptr<const CastMemberChunk> castMember = nullptr,
                           std::shared_ptr<const CastMember> dynamicMember = nullptr,
                           bool flipH = false,
                           bool flipV = false,
                           int width = 3,
                           int height = 3) {
    return RenderSprite(channel,
                        10,
                        10,
                        width,
                        height,
                        0,
                        true,
                        SpriteType::Bitmap,
                        std::move(castMember),
                        std::move(dynamicMember),
                        0,
                        0,
                        false,
                        false,
                        ink,
                        100,
                        flipH,
                        flipV,
                        std::move(baked),
                        true);
}

void testHitTesterFoundation() {
    auto copyAlphaBitmap = makeSolidHitBitmap(0xFFFF0000U);
    copyAlphaBitmap->setPixel(1, 1, 0x00000000U);
    auto lower = makeHitSprite(40, makeSolidHitBitmap(0xFF00FF00U));
    auto topCopy = makeHitSprite(41, copyAlphaBitmap, libreshockwave::id::code(InkMode::COPY));
    assert(HitTester::hitTest({topCopy}, 11, 11) == 41);
    assert(HitTester::hitTest({lower, topCopy}, 11, 11) == 41);
    assert(HitTester::hitTestType({lower, topCopy}, 11, 11).value() == SpriteType::Bitmap);
    assert(HitTester::hitTest({lower, topCopy}, 99, 99) == 0);
    assert(!HitTester::hitTestType({lower, topCopy}, 99, 99).has_value());

    auto nativeBitmap = makeSolidHitBitmap(0xFFFF0000U);
    nativeBitmap->setPixel(1, 1, 0x40FF0000U);
    nativeBitmap->setNativeAlpha(true);
    auto nativeTop = makeHitSprite(42,
                                   nativeBitmap,
                                   libreshockwave::id::code(InkMode::COPY),
                                   makeHitBitmapMember(420, 128));
    assert((HitTester::getAlphaHitRule(nativeTop) == AlphaHitRule{true, 128}));
    assert(HitTester::hitTest({lower, nativeTop}, 11, 11) == 40);

    auto thresholdZero = makeHitSprite(43,
                                       nativeBitmap,
                                       libreshockwave::id::code(InkMode::COPY),
                                       makeHitBitmapMember(421, 0));
    assert(HitTester::hitTest({lower, thresholdZero}, 11, 11) == 43);

    auto dynamicChunk = makeHitBitmapMember(430, 0);
    auto dynamicMember = std::make_shared<CastMember>(1, 1, 43, dynamicChunk);
    auto dynamicBaked = makeSolidHitBitmap(0xFFFF0000U);
    dynamicBaked->setPixel(1, 1, 0x00000000U);
    auto dynamicMatte = makeHitSprite(44,
                                      dynamicBaked,
                                      libreshockwave::id::code(InkMode::MATTE),
                                      nullptr,
                                      dynamicMember);
    assert(HitTester::usesDynamicInkTransparency(dynamicMatte));
    assert((HitTester::getAlphaHitRule(dynamicMatte) == AlphaHitRule{true, 1}));
    assert(HitTester::hitTest({lower, dynamicMatte}, 11, 11) == 40);
    assert(HitTester::hitTest({lower, dynamicMatte}, 10, 10) == 44);
    assert(HitTester::hitTest({lower, dynamicMatte}, 11, 11, [](int channel) { return channel == 44; }) == 44);
    assert((HitTester::hitTestAll({lower, dynamicMatte}, 11, 11, [](int channel) { return channel == 44; }) ==
            std::vector<int>{44, 40}));
    assert((HitTester::hitTestAll({lower, dynamicMatte}, 11, 11, [](int) { return false; }) ==
            std::vector<int>{40}));

    auto scaled = std::make_shared<Bitmap>(2, 1, 32, std::vector<std::uint32_t>{0x00000000U, 0xFF112233U});
    scaled->setNativeAlpha(true);
    auto scaledSprite = makeHitSprite(45,
                                      scaled,
                                      libreshockwave::id::code(InkMode::COPY),
                                      makeHitBitmapMember(450, 1),
                                      nullptr,
                                      true,
                                      false,
                                      4,
                                      1);
    assert(HitTester::hitTest({scaledSprite}, 10, 10) == 45);
    assert(HitTester::hitTest({scaledSprite}, 13, 10) == 0);

    auto hidden = makeHitSprite(46, makeSolidHitBitmap(0xFFFFFFFFU));
    hidden = RenderSprite(46,
                          10,
                          10,
                          3,
                          3,
                          0,
                          false,
                          SpriteType::Bitmap,
                          nullptr,
                          nullptr,
                          0,
                          0,
                          false,
                          false,
                          0,
                          100,
                          false,
                          false,
                          hidden.bakedBitmap(),
                          false);
    assert(HitTester::hitTest({hidden, lower}, 11, 11) == 40);
}

void testCursorManagerFoundation() {
    InputState input;
    SpriteRegistry registry;
    std::vector<RenderSprite> sprites;
    CursorManager manager(&input, &registry);
    manager.setSpriteProvider([&sprites]() { return sprites; });

    std::map<std::pair<int, int>, CursorManager::MemberInfo> memberInfo;
    manager.setMemberInfoResolver([&memberInfo](int castLib, int memberNum) -> std::optional<CursorManager::MemberInfo> {
        auto found = memberInfo.find({castLib, memberNum});
        return found == memberInfo.end() ? std::nullopt : std::optional<CursorManager::MemberInfo>(found->second);
    });

    std::map<std::pair<int, int>, Bitmap> cursorBitmaps;
    manager.setBitmapResolver([&cursorBitmaps](int castLib, int memberNum) -> std::optional<Bitmap> {
        auto found = cursorBitmaps.find({castLib, memberNum});
        return found == cursorBitmaps.end() ? std::nullopt : std::optional<Bitmap>(found->second);
    });

    bool interactive = false;
    manager.setInteractivePredicate([&interactive](int channel) {
        return interactive && channel == 3;
    });

    input.setMousePosition(11, 11);
    auto state = registry.getOrCreateDynamic(3);
    state->setDynamicMember(1, 10);
    sprites = {makeHitSprite(3, makeSolidHitBitmap(0xFFCCCCCCU))};

    memberInfo[{1, 10}] = CursorManager::MemberInfo{MemberType::Text, true, 0, 0};
    assert(manager.getCursorAtMouse() == CursorManager::IBEAM_CURSOR);

    memberInfo[{1, 10}] = CursorManager::MemberInfo{MemberType::Button, false, 0, 0};
    assert(manager.getCursorAtMouse() == CursorManager::POINTER_CURSOR);

    memberInfo[{1, 10}] = CursorManager::MemberInfo{MemberType::Bitmap, false, 0, 0};
    state->setCursor(2);
    assert(manager.getCursorAtMouse() == 2);

    state->setCursor(0);
    interactive = true;
    assert(manager.getCursorAtMouse() == CursorManager::POINTER_CURSOR);
    interactive = false;

    state->setCursorMembers((1 << 16) | 20, (1 << 16) | 21);
    cursorBitmaps.emplace(std::pair{1, 20}, Bitmap(2, 1, 32, {0xFFFF0000U, 0xFF00FF00U}));
    cursorBitmaps.emplace(std::pair{1, 21}, Bitmap(2, 1, 32, {0xFFFFFFFFU, 0xFF000000U}));
    memberInfo[{1, 20}] = CursorManager::MemberInfo{MemberType::Bitmap, false, 4, 5};
    assert(manager.getCursorAtMouse() == CursorManager::CUSTOM_BITMAP_CURSOR);
    auto cursorBitmap = manager.getCursorBitmap();
    assert(cursorBitmap.has_value());
    assert(cursorBitmap->width() == 2);
    assert(cursorBitmap->height() == 1);
    assert(cursorBitmap->getPixel(0, 0) == 0x00000000U);
    assert(cursorBitmap->getPixel(1, 0) == 0xFF00FF00U);
    auto regPoint = manager.getCursorRegPoint();
    assert(regPoint.has_value());
    assert((*regPoint)[0] == 4);
    assert((*regPoint)[1] == 5);

    assert(CursorManager::encodeCursorMember(Datum::castMemberRef(CastLibId(2), MemberId(9))) == ((2 << 16) | 9));
    assert(CursorManager::encodeCursorMember(Datum::of((3 << 16) | 7)) == ((3 << 16) | 7));
    assert(CursorManager::isNearWhite(0xFFFAFAFAU));
    assert(!CursorManager::isNearWhite(0xFFF9FAFAU));
    Bitmap masked = CursorManager::applyCursorMask(Bitmap(1, 2, 32, {0xFF010203U, 0xFF040506U}),
                                                   Bitmap(1, 1, 32, {0xFF000000U}));
    assert(masked.getPixel(0, 0) == 0xFF010203U);
    assert(masked.getPixel(0, 1) == 0x00000000U);

    input.setMousePosition(99, 99);
    manager.setGlobalCursorSupplier([]() { return Datum::of(CursorManager::WAIT_CURSOR); });
    assert(manager.getCursorAtMouse() == CursorManager::WAIT_CURSOR);
    manager.setGlobalCursorSupplier([]() {
        return Datum::list({Datum::castMemberRef(CastLibId(1), MemberId(20)), Datum::of((1 << 16) | 21)});
    });
    assert(manager.getCursorAtMouse() == CursorManager::CUSTOM_BITMAP_CURSOR);
    auto globalBitmap = manager.getCursorBitmap();
    assert(globalBitmap.has_value());
    assert(globalBitmap->getPixel(0, 0) == 0x00000000U);
    assert(manager.getCursorRegPoint().value()[0] == 4);

    manager.setGlobalCursorSupplier(nullptr);
    input.setMousePosition(10, 10);
    auto navState = registry.getOrCreateDynamic(4);
    navState->setCursor(CursorManager::WAIT_CURSOR);
    auto navBitmap = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFFFFFFFFU});
    sprites = {makeHitSprite(4, navBitmap, libreshockwave::id::code(InkMode::MATTE))};
    assert(manager.getCursorAtMouse() == CursorManager::ARROW_CURSOR);
    assert(!manager.getCursorBitmap().has_value());
    assert(!manager.getCursorRegPoint().has_value());
}

void testScoreNavigationFoundation() {
    ScoreBehaviorRef behavior(1, 23, std::vector<Datum>{Datum::of(7)});
    assert(behavior.castLib() == 1);
    assert(behavior.castMember() == 23);
    assert(behavior.castLibId().value() == 1);
    assert(behavior.memberId().value() == 23);
    assert(behavior.hasParameters());
    assert(behavior.parameters().size() == 1);
    assert(behavior.toString() == "behavior(member 23, castLib 1)");

    SpriteSpan span(0, 0, 5);
    assert(span.channel() == 0);
    assert(span.startFrame() == 1);
    assert(span.endFrame() == 5);
    assert(span.isFrameBehavior());
    assert(span.containsFrame(1));
    assert(span.containsFrame(5));
    assert(!span.containsFrame(6));
    assert(span.firstBehavior() == nullptr);
    span.addBehavior(behavior);
    assert(span.firstBehavior() != nullptr);
    assert(span.firstBehavior()->castMember() == 23);
    assert(span.toString() == "SpriteSpan{channel=0, frames=1-5, behaviors=1}");

    std::vector<std::vector<std::uint8_t>> entries{
        {},
        {' ', '[', '#', 'p', ':', ' ', '1', ']', 0, 0}
    };
    assert(ScoreNavigator::parseBehaviorParameters(entries, 0).empty());
    assert(ScoreNavigator::parseBehaviorParameters(entries, 1).empty());
    assert(ScoreNavigator::parseBehaviorParameters(entries, 99).empty());

    ScoreChunk::ScoreFrameData frameData = ScoreChunk::ScoreFrameData::empty();
    frameData.header.frameCount = 12;
    ScoreChunk::Header header{0, 0, 0, static_cast<int>(entries.size()), 0, 0};

    std::vector<ScoreChunk::FrameInterval> intervals{
        ScoreChunk::FrameInterval{
            ScoreChunk::FrameIntervalPrimary{1, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            ScoreChunk::FrameIntervalSecondary{1, 7, 1}
        },
        ScoreChunk::FrameInterval{
            ScoreChunk::FrameIntervalPrimary{2, 9, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
            ScoreChunk::FrameIntervalSecondary{1, 8, 0}
        },
        ScoreChunk::FrameInterval{
            ScoreChunk::FrameIntervalPrimary{4, 4, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0},
            std::nullopt
        }
    };
    auto score = std::make_shared<ScoreChunk>(
        nullptr, ChunkId(300), header, entries, frameData, intervals);
    auto labels = std::make_shared<FrameLabelsChunk>(
        nullptr,
        ChunkId(301),
        std::vector<FrameLabelsChunk::FrameLabel>{
            {FrameId(1), "Start"},
            {FrameId(5), "Middle"},
            {FrameId(10), "End"}
        });

    ScoreNavigator navigator(score, labels);
    assert(navigator.getFrameCount() == 12);
    assert(navigator.getAllSpans().size() == 3);
    assert(navigator.getFrameScript(3) != nullptr);
    assert(navigator.getFrameScript(3)->castMember() == 7);
    assert(navigator.getFrameScript(8) == nullptr);

    const auto spriteBehaviors = navigator.getSpriteBehaviors(5, 2);
    assert(spriteBehaviors.size() == 1);
    assert(spriteBehaviors[0].castMember() == 8);
    assert(navigator.getSpriteBehaviors(10, 2).empty());

    const auto activeSprites = navigator.getActiveSprites(5);
    assert(activeSprites.size() == 1);
    assert(activeSprites[0].channel() == 2);
    assert((navigator.getActiveChannels(4) == std::set<int>{2, 3}));
    assert((navigator.getActiveChannels(5) == std::set<int>{2}));

    assert(navigator.getFrameForLabel("middle") == 5);
    assert(navigator.getFrameForLabel("MIDDLE") == 5);
    assert(navigator.getFrameForLabel("missing") == -1);
    assert((navigator.getFrameLabels() == std::set<std::string>{"end", "middle", "start"}));
    assert(navigator.getMarkerFrame(5, 0) == 5);
    assert(navigator.getMarkerFrame(5, 1) == 10);
    assert(navigator.getMarkerFrame(5, -1) == 1);
    assert(navigator.getMarkerFrame(11, 1) == 0);
    assert(ScoreNavigator::resolveMarkerFrame({1, 5, 10}, 2, 0) == 1);
    assert(ScoreNavigator::resolveMarkerFrame({1, 5, 10}, 2, -1) == 0);

    const DirectorFile* noFile = nullptr;
    ScoreNavigator emptyNavigator(noFile);
    assert(emptyNavigator.getFrameCount() == 0);
    assert(emptyNavigator.getAllSpans().empty());
    assert(emptyNavigator.getFrameForLabel("anything") == -1);
    assert(emptyNavigator.getMarkerFrame(1, 0) == 0);
}

ScoreChunk::ChannelData makeSpriteChannelData(int ink, int blendByte, int castMember = 33) {
    return ScoreChunk::ChannelData{
        1,
        ink,
        1,
        0,
        7,
        9,
        2,
        castMember,
        0,
        0,
        22,
        11,
        44,
        33,
        0,
        blendByte,
        0x60,
        0,
        0,
        0,
        0
    };
}

void testSpriteStateFoundation() {
    SpriteState blendMax(1, makeSpriteChannelData(libreshockwave::id::code(InkMode::BLEND), 255));
    assert(blendMax.blend() == 0);
    SpriteState blendMid(1, makeSpriteChannelData(libreshockwave::id::code(InkMode::BLEND), 128));
    assert(blendMid.blend() == 50);
    SpriteState blendBackground(1, makeSpriteChannelData(libreshockwave::id::code(InkMode::BACKGROUND_TRANSPARENT), 204));
    assert(blendBackground.blend() == 20);
    assert(SpriteState::scoreBlendPercent(0) == 100);
    assert(SpriteState::scoreBlendPercent(255) == 0);

    auto baseData = makeSpriteChannelData(libreshockwave::id::code(InkMode::BLEND), 128);
    SpriteState state(5, baseData);
    assert(state.channelId().value() == 5);
    assert(state.channel() == 5);
    assert(state.locH() == 11);
    assert(state.locV() == 22);
    assert(state.locZ() == 0);
    assert(state.width() == 33);
    assert(state.height() == 44);
    assert(state.isVisible());
    assert(!state.isPuppet());
    assert(state.inkMode() == InkMode::BLEND);
    assert(state.ink() == libreshockwave::id::code(InkMode::BLEND));
    assert(state.trails() == 1);
    assert(state.stretch() == 0);
    assert(state.foreColor() == 7);
    assert(state.backColor() == 9);
    assert(!state.hasForeColor());
    assert(!state.hasBackColor());
    assert(state.isFlipH());
    assert(state.isFlipV());
    assert(state.effectiveCastLib() == 2);
    assert(state.effectiveCastMember() == 33);
    assert(state.initialData().has_value());
    assert(state.matchesScoreIdentity(baseData));
    assert((state.snapshotPosition() == SpriteState::PositionSnapshot{11, 22, 0, 33, 44}));

    state.setLocH(77);
    state.setWidth(99);
    state.setFlipH(false);
    state.setInkMode(InkMode::DARKEN);
    state.setBlend(42);
    state.setTrails(3);
    state.setStretch(4);
    state.setForeColor(0x112233);
    state.setBackColor(0x445566);
    auto nextData = makeSpriteChannelData(libreshockwave::id::code(InkMode::MATTE), 64, 44);
    nextData.posX = 123;
    nextData.posY = 234;
    nextData.width = 55;
    nextData.height = 66;
    nextData.thicknessFlags = 0;
    state.syncFromScore(nextData);
    assert(state.locH() == 77);
    assert(state.locV() == 234);
    assert(state.width() == 99);
    assert(state.height() == 44);
    assert(!state.isFlipH());
    assert(!state.isFlipV());
    assert(state.inkMode() == InkMode::DARKEN);
    assert(state.blend() == 42);
    assert(state.trails() == 3);
    assert(state.stretch() == 4);
    assert(state.foreColor() == 0x112233);
    assert(state.backColor() == 0x445566);
    assert(!state.matchesScoreIdentity(baseData));
    assert(state.matchesScoreIdentity(nextData));

    state.setCursor(4);
    assert(state.cursor() == 4);
    assert(!state.hasBitmapCursor());
    state.setCursorMembers(0x00020021, 0x00020022);
    assert(state.cursor() == 0);
    assert(state.hasBitmapCursor());
    assert(state.cursorMemberNum() == 0x00020021);
    assert(state.cursorMaskNum() == 0x00020022);

    SpriteState dynamic(9);
    assert(dynamic.isDynamic());
    assert(dynamic.isPuppet());
    assert(dynamic.effectiveCastLib() == 0);
    assert(dynamic.effectiveCastMember() == 0);
    dynamic.applyIntrinsicSize(20, 30);
    assert(dynamic.width() == 20);
    assert(dynamic.height() == 30);
    dynamic.setWidth(5);
    dynamic.applyIntrinsicSize(100, 100);
    assert(dynamic.width() == 5);
    assert(dynamic.height() == 30);
    assert(dynamic.hasSizeChanged());
    dynamic.applyMemberAssignmentSize(12, 13);
    assert(dynamic.width() == 12);
    assert(dynamic.height() == 13);
    assert(!dynamic.hasSizeChanged());
    dynamic.setBlend(66);
    dynamic.applyScoreDefaults(baseData);
    assert(dynamic.inkMode() == InkMode::BLEND);
    assert(dynamic.blend() == 66);
    assert(dynamic.trails() == 1);
    assert(dynamic.hasForeColor());
    assert(dynamic.hasBackColor());
    dynamic.applyScoreDefaults(nextData);
    assert(dynamic.foreColor() == 7);
    assert(dynamic.backColor() == 9);

    dynamic.setDynamicMember(4, 0);
    assert(dynamic.hasDynamicMember());
    assert(dynamic.effectiveCastLib() == 4);
    assert(dynamic.effectiveCastMember() == 0);
    dynamic.clearDynamicMember();
    assert(!dynamic.hasDynamicMember());
    assert(dynamic.effectiveCastLib() == 0);

    dynamic.setFlipH(true);
    dynamic.setFlipV(true);
    dynamic.setRotation(180.0);
    dynamic.setSkew(45.0);
    dynamic.resetReleasedSpriteTransforms();
    assert(!dynamic.isFlipH());
    assert(!dynamic.isFlipV());
    assert(dynamic.rotation() == 0.0);
    assert(dynamic.skew() == 0.0);
    dynamic.resetReleasedChannelGeometry();
    assert(dynamic.width() == 1);
    assert(dynamic.height() == 1);

    dynamic.setScriptInstanceList({Datum::of(1), Datum::of("behavior")});
    assert(dynamic.hasScriptBehaviors());
    assert(dynamic.scriptInstanceList().size() == 2);
    dynamic.rebindToScorePreservingScriptInstances(baseData);
    assert(!dynamic.isDynamic());
    assert(!dynamic.isPuppet());
    assert(dynamic.scriptInstanceList().size() == 2);
    dynamic.rebindToScore(nextData);
    assert(dynamic.scriptInstanceList().empty());
    assert(dynamic.effectiveCastMember() == 44);
}

void testSpriteRegistryFoundation() {
    SpriteRegistry registry;
    assert(registry.getAll().empty());
    assert(registry.get(5) == nullptr);
    assert(!registry.contains(5));
    assert(registry.getRevision() == 0);

    auto baseData = makeSpriteChannelData(libreshockwave::id::code(InkMode::BLEND), 128, 33);
    auto sprite = registry.getOrCreate(5, baseData);
    assert(sprite != nullptr);
    assert(sprite->channel() == 5);
    assert(sprite->effectiveCastMember() == 33);
    assert(registry.contains(5));
    assert(registry.get(5) == sprite);
    const SpriteRegistry& constRegistry = registry;
    assert(constRegistry.get(5)->channel() == 5);
    assert(registry.getAll().size() == 1);
    assert(registry.getOrCreate(5, baseData) == sprite);
    assert(registry.getDynamicSprites().empty());

    registry.markScoreBehaviorChannel(5);
    assert(registry.hasScoreBehaviorChannel(5));
    assert(!registry.hasScoreBehaviorChannel(6));

    auto movedData = baseData;
    movedData.posX = 77;
    movedData.posY = 88;
    registry.updateFromScore(5, movedData);
    assert(sprite->locH() == 77);
    assert(sprite->locV() == 88);
    assert(sprite->effectiveCastMember() == 33);
    assert(registry.revision() == 0);

    sprite->setScriptInstanceList({Datum::of(std::string("score-behavior"))});
    auto replacementData = makeSpriteChannelData(libreshockwave::id::code(InkMode::MATTE), 64, 44);
    registry.updateFromScore(5, replacementData);
    assert(sprite->effectiveCastMember() == 44);
    assert(sprite->scriptInstanceList().size() == 1);
    assert(sprite->scriptInstanceList()[0].stringValue() == "score-behavior");
    assert(registry.getRevision() == 1);

    auto rebindData = makeSpriteChannelData(libreshockwave::id::code(InkMode::COPY), 0, 55);
    assert(registry.getOrCreate(5, rebindData) == sprite);
    assert(sprite->effectiveCastMember() == 55);
    assert(sprite->scriptInstanceList().size() == 1);
    assert(registry.getRevision() == 1);

    auto dynamic = registry.getOrCreateDynamic(8);
    assert(dynamic->isDynamic());
    assert(dynamic->isPuppet());
    assert(registry.getOrCreateDynamic(8) == dynamic);
    dynamic->setDynamicMember(4, 77);
    dynamic->setWidth(20);
    dynamic->setHeight(30);
    dynamic->setFlipH(true);
    dynamic->setRotation(90.0);
    registry.updateFromScore(8, baseData);
    assert(dynamic->hasDynamicMember());
    assert(dynamic->width() == 20);
    assert(dynamic->rotation() == 90.0);

    auto dynamicSprites = registry.getDynamicSprites();
    assert(dynamicSprites.size() == 1);
    assert(dynamicSprites[0] == dynamic);
    assert(!registry.clearDynamicMemberBindings(4, 78));
    assert(registry.getRevision() == 1);
    assert(registry.clearDynamicMemberBindings(4, 77));
    assert(registry.getRevision() == 2);
    assert(!dynamic->hasDynamicMember());
    assert(dynamic->isDynamic());
    assert(dynamic->width() == 1);
    assert(dynamic->height() == 1);
    assert(!dynamic->isFlipH());
    assert(dynamic->rotation() == 0.0);

    auto scoreWithDynamic = registry.getOrCreate(9, baseData);
    scoreWithDynamic->setScriptInstanceList({Datum::of(std::string("attached"))});
    scoreWithDynamic->setDynamicMember(6, 100);
    scoreWithDynamic->setWidth(70);
    scoreWithDynamic->setFlipH(false);
    assert(registry.clearDynamicMemberBindings(6, 100));
    assert(registry.getRevision() == 3);
    assert(!scoreWithDynamic->hasDynamicMember());
    assert(!scoreWithDynamic->isDynamic());
    assert(scoreWithDynamic->effectiveCastMember() == 33);
    assert(scoreWithDynamic->width() == 33);
    assert(scoreWithDynamic->isFlipH());
    assert(scoreWithDynamic->scriptInstanceList().size() == 1);

    registry.markScoreBehaviorChannel(9);
    registry.remove(9);
    assert(!registry.contains(9));
    assert(!registry.hasScoreBehaviorChannel(9));

    registry.clear();
    assert(registry.getAll().empty());
    assert(!registry.hasScoreBehaviorChannel(5));
    assert(registry.getRevision() == 3);
}

void testSpritePropertiesFoundation() {
    SpriteRegistry registry;
    SpriteProperties props(&registry);

    assert(!props.getScriptInstanceList(1).has_value());
    assert(props.getSpriteProp(7, "visible").intValue() == 0);
    assert(props.getSpriteProp(7, "puppet").intValue() == 0);
    assert(props.getSpriteProp(7, "locH").intValue() == 0);
    assert(props.getSpriteProp(7, "spritenum").intValue() == 7);
    assert(props.getSpriteProp(7, "type").intValue() == 0);
    assert(props.getSpriteProp(7, "member").isVoid());
    assert(props.getSpriteProp(7, "ilk").asSymbol()->name == "sprite");
    const auto* missingLoc = props.getSpriteProp(7, "loc").asIntPoint();
    assert(missingLoc != nullptr && missingLoc->x == 0 && missingLoc->y == 0);
    const auto* missingRect = props.getSpriteProp(7, "rect").asIntRect();
    assert(missingRect != nullptr && missingRect->left == 0 && missingRect->bottom == 0);

    assert(props.setSpriteProp(3, "locH", Datum::of(50)));
    assert(registry.getRevision() == 1);
    auto sprite = registry.get(3);
    assert(sprite != nullptr);
    assert(sprite->isDynamic());
    assert(sprite->isPuppet());
    assert(sprite->locH() == 50);

    assert(props.setSpriteProp(3, "loc", Datum::intPoint(10, 20)));
    assert(props.setSpriteProp(3, "rect", Datum::intRect(1, 2, 41, 52)));
    assert(sprite->locH() == 1);
    assert(sprite->locV() == 2);
    assert(sprite->width() == 40);
    assert(sprite->height() == 50);
    assert(props.getSpriteProp(3, "rect") == Datum::intRect(1, 2, 41, 52));

    assert(props.setSpriteProp(3, "visible", Datum::of(0)));
    assert(!sprite->isVisible());
    assert(props.setSpriteProp(3, "ink", Datum::symbol("matte")));
    assert(sprite->inkMode() == InkMode::MATTE);
    assert(props.setSpriteProp(3, "blend", Datum::of(72)));
    assert(props.setSpriteProp(3, "blend", Datum::voidValue()));
    assert(sprite->blend() == 72);
    assert(props.setSpriteProp(3, "stretch", Datum::of(3)));
    assert(props.setSpriteProp(3, "trails", Datum::of(4)));
    assert(props.setSpriteProp(3, "color", Datum::colorRef(1, 2, 3)));
    assert(props.setSpriteProp(3, "bgColor", Datum::of(0xAABBCC)));
    assert(sprite->stretch() == 3);
    assert(sprite->trails() == 4);
    assert(sprite->foreColor() == 0x010203);
    assert(sprite->backColor() == 0xAABBCC);

    assert(props.setSpriteProp(3, "rotation", Datum::of(180.0)));
    assert(props.setSpriteProp(3, "skew", Datum::of(180.0)));
    assert(props.setSpriteProp(3, "flipH", Datum::of(1)));
    assert(props.setSpriteProp(3, "flipV", Datum::of(1)));
    assert(sprite->rotation() == 180.0F);
    assert(sprite->skew() == 180.0F);
    assert(sprite->isFlipH());
    assert(sprite->isFlipV());
    assert(SpriteProperties::hasDirectorHorizontalMirror(180.2, 179.6));

    auto behavior = Datum::scriptInstance("behavior");
    assert(props.setSpriteProp(3, "scriptInstanceList", Datum::list({behavior, Datum::of(std::string("plain"))})));
    auto scriptList = props.getScriptInstanceList(3);
    assert(scriptList.has_value());
    assert(scriptList->size() == 2);
    assert(behavior.scriptInstanceValue().getProperty("spritenum").intValue() == 3);
    assert(props.getSpriteProp(3, "scriptInstanceList").listValue().count() == 2);

    assert(props.setSpriteProp(3, "cursor", Datum::list({
        Datum::castMemberRef(CastLibId(2), MemberId(9)),
        Datum::of((3 << 16) | 7)
    })));
    assert(sprite->cursor() == 0);
    assert(sprite->cursorMemberNum() == ((2 << 16) | 9));
    assert(sprite->cursorMaskNum() == ((3 << 16) | 7));
    assert(SpriteProperties::encodeCursorMember(Datum::castMemberRef(CastLibId(4), MemberId(5))) == ((4 << 16) | 5));

    std::map<std::pair<int, int>, SpriteProperties::MemberInfo> memberInfo;
    SpriteProperties::MemberInfo bitmapInfo;
    bitmapInfo.bitmap = true;
    bitmapInfo.bitmapWidth = 100;
    bitmapInfo.bitmapHeight = 50;
    bitmapInfo.bitmapRegX = 11;
    bitmapInfo.bitmapRegY = 7;
    bitmapInfo.hasImage = true;
    bitmapInfo.image = Datum::of(std::string("image-ref"));
    memberInfo[{1, 20}] = bitmapInfo;

    SpriteProperties::MemberInfo runtimeInfo;
    runtimeInfo.width = 12;
    runtimeInfo.height = 13;
    runtimeInfo.regX = 2;
    runtimeInfo.regY = 3;
    runtimeInfo.runtimeDynamic = true;
    memberInfo[{2, 30}] = runtimeInfo;

    SpriteProperties::MemberInfo namedInfo;
    namedInfo.bitmap = true;
    namedInfo.bitmapWidth = 21;
    namedInfo.bitmapHeight = 22;
    memberInfo[{3, 31}] = namedInfo;

    props.setMemberInfoResolver([&memberInfo](int castLib, int memberNum) -> std::optional<SpriteProperties::MemberInfo> {
        auto found = memberInfo.find({castLib, memberNum});
        return found == memberInfo.end() ? std::nullopt : std::optional<SpriteProperties::MemberInfo>(found->second);
    });
    props.setMemberNameResolver([](const std::string& name) -> std::optional<Datum::CastMemberRef> {
        if (name == "logo") {
            return Datum::CastMemberRef{3, 31};
        }
        return std::nullopt;
    });

    assert(props.setSpriteProp(4, "member", Datum::castMemberRef(CastLibId(1), MemberId(20))));
    auto bitmapSprite = registry.get(4);
    assert(bitmapSprite->effectiveCastLib() == 1);
    assert(bitmapSprite->effectiveCastMember() == 20);
    assert(bitmapSprite->width() == 100);
    assert(bitmapSprite->height() == 50);
    assert(props.getSpriteProp(4, "image").stringValue() == "image-ref");
    assert(props.getSpriteProp(4, "member").asCastMemberRef()->castLib == 1);
    assert(props.getSpriteProp(4, "member").asCastMemberRef()->memberNum() == 20);
    assert(props.resolveSpriteBounds(*bitmapSprite) == (SpriteProperties::SpriteBounds{-11, -7, 89, 43}));

    assert(props.setSpriteProp(4, "loc", Datum::intPoint(200, 100)));
    assert(props.setSpriteProp(4, "width", Datum::of(50)));
    assert(props.setSpriteProp(4, "height", Datum::of(25)));
    assert(props.resolveSpriteBounds(*bitmapSprite) == (SpriteProperties::SpriteBounds{195, 97, 245, 122}));
    props.setLegacyRoundedRegistrationScale(true);
    assert(props.resolveSpriteBounds(*bitmapSprite) == (SpriteProperties::SpriteBounds{194, 96, 244, 121}));
    props.setLegacyRoundedRegistrationScale(false);
    assert(props.setSpriteProp(4, "flipH", Datum::of(1)));
    assert(props.resolveSpriteBounds(*bitmapSprite).left == 155);
    assert(props.setSpriteProp(4, "memberNum", Datum::of(21)));
    assert(bitmapSprite->effectiveCastLib() == 1);
    assert(bitmapSprite->effectiveCastMember() == 21);

    auto runtimeSprite = registry.getOrCreateDynamic(6);
    runtimeSprite->setWidth(5);
    assert(props.setSpriteMember(6, Datum::castMemberRef(CastLibId(2), MemberId(30))));
    assert(runtimeSprite->width() == 12);
    assert(runtimeSprite->height() == 13);
    assert(!runtimeSprite->hasSizeChanged());

    assert(props.setSpriteProp(7, "member", Datum::of(std::string("logo"))));
    auto namedSprite = registry.get(7);
    assert(namedSprite->effectiveCastLib() == 3);
    assert(namedSprite->effectiveCastMember() == 31);
    assert(namedSprite->width() == 21);
    assert(namedSprite->height() == 22);

    auto releaseSprite = registry.getOrCreateDynamic(8);
    releaseSprite->setDynamicMember(1, 20);
    releaseSprite->setWidth(77);
    releaseSprite->setHeight(88);
    releaseSprite->setCursor(4);
    releaseSprite->setRotation(45.0);
    releaseSprite->setSkew(45.0);
    auto broker = Datum::scriptInstance("broker");
    broker.scriptInstanceValue().setProperty("__spriteEventBroker__", Datum::TRUE);
    auto regular = Datum::scriptInstance("regular");
    assert(props.setSpriteProp(8, "scriptInstanceList", Datum::list({broker, regular})));
    assert(props.setSpriteProp(8, "member", Datum::of(0)));
    assert(releaseSprite->effectiveCastMember() == 0);
    assert(releaseSprite->rotation() == 0.0);
    assert(props.setSpriteProp(8, "puppet", Datum::of(0)));
    assert(!releaseSprite->isPuppet());
    assert(!releaseSprite->isVisible());
    assert(releaseSprite->cursor() == 0);
    assert(releaseSprite->blend() == 100);
    assert(releaseSprite->stretch() == 0);
    assert(releaseSprite->width() == 1);
    assert(releaseSprite->height() == 1);
    assert(releaseSprite->scriptInstanceList().size() == 1);
    assert(releaseSprite->scriptInstanceList()[0].scriptInstanceValue().scriptName() == "broker");

    Datum imageValue = Datum::of(std::string("new-image"));
    bool imageSetterCalled = false;
    props.setMemberImageSetter([&imageSetterCalled, &imageValue](int castLib, int memberNum, const Datum& value) {
        imageSetterCalled = true;
        assert(castLib == 3);
        assert(memberNum == 31);
        assert(value == imageValue);
        return true;
    });
    assert(props.setSpriteProp(7, "image", imageValue));
    assert(imageSetterCalled);

    assert(props.setSpriteProp(7, "moveable", Datum::of(1)));
    assert(!props.setSpriteProp(7, "unknownSpriteProperty", Datum::of(1)));
}

std::shared_ptr<ScriptChunk> makeBehaviorScript(int id, ScriptChunkType type = ScriptChunkType::Behavior) {
    return std::make_shared<ScriptChunk>(nullptr,
                                         ChunkId(id),
                                         type,
                                         0,
                                         std::vector<ScriptChunk::Handler>{},
                                         std::vector<ScriptChunk::LiteralEntry>{},
                                         std::vector<ScriptChunk::PropertyEntry>{},
                                         std::vector<ScriptChunk::GlobalEntry>{},
                                         std::vector<std::uint8_t>{});
}

std::shared_ptr<ScriptChunk> makeScriptWithHandlers(int id, ScriptChunkType type, std::vector<int> handlerNameIds) {
    std::vector<ScriptChunk::Handler> handlers;
    handlers.reserve(handlerNameIds.size());
    for (int nameId : handlerNameIds) {
        handlers.push_back(ScriptChunk::Handler{
            nameId,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            {},
            {},
            {},
            {}
        });
    }
    return std::make_shared<ScriptChunk>(nullptr,
                                         ChunkId(id),
                                         type,
                                         0,
                                         std::move(handlers),
                                         std::vector<ScriptChunk::LiteralEntry>{},
                                         std::vector<ScriptChunk::PropertyEntry>{},
                                         std::vector<ScriptChunk::GlobalEntry>{},
                                         std::vector<std::uint8_t>{});
}

void testBehaviorFoundation() {
    auto script = makeBehaviorScript(701);
    auto params = Datum::propList();
    params.propListValue().put(Datum::symbol("speed"), Datum::of(7));
    params.propListValue().put(Datum::of(std::string("label")), Datum::of("go"));
    ScoreBehaviorRef ref(1, 23, {params});

    BehaviorInstance instance(script, ref, 4);
    assert(instance.id() > 0);
    assert(instance.script() == script);
    assert(instance.behaviorRef() == ref);
    assert(instance.spriteNum() == 4);
    assert(!instance.isFrameBehavior());
    assert(instance.getProperty("spriteNum").intValue() == 4);
    assert(instance.getProperty("missing").isVoid());
    instance.setProperty("health", Datum::of(99));
    assert(instance.getProperty("health").intValue() == 99);
    assert(instance.properties().size() == 2);
    assert(!instance.isBeginSpriteCalled());
    instance.setBeginSpriteCalled(true);
    instance.setEndSpriteCalled(true);
    assert(instance.isBeginSpriteCalled());
    assert(instance.isEndSpriteCalled());
    auto receiver = instance.toDatum();
    assert(receiver.type() == DatumType::ScriptInstanceRef);
    receiver.scriptInstanceValue().setProperty("health", Datum::of(100));
    assert(instance.getProperty("health").intValue() == 100);
    assert(instance.toString().find("spriteNum=4") != std::string::npos);

    BehaviorManager missingFileManager;
    assert(missingFileManager.createInstance(ref, 1) == nullptr);

    BehaviorManager manager;
    assert(!manager.debugEnabled());
    manager.setDebugEnabled(true);
    assert(manager.debugEnabled());
    auto first = manager.createInstanceForScript(script, ref, 4);
    assert(first != nullptr);
    assert(manager.instanceCount() == 1);
    assert(manager.getInstance(first->id()) == first);
    assert(manager.hasInstanceForChannel(4, ref));
    assert(!manager.hasInstanceForChannel(5, ref));
    assert(manager.getInstancesForChannel(4).size() == 1);
    assert(manager.getInstancesForChannel(99).empty());
    assert(first->getProperty("speed").intValue() == 7);
    assert(first->getProperty("label").stringValue() == "go");

    auto second = manager.createInstanceForScript(makeBehaviorScript(702), ScoreBehaviorRef(1, 24), 2);
    auto third = manager.createInstanceForScript(makeBehaviorScript(703), ScoreBehaviorRef(1, 25), 6);
    assert(manager.instanceCount() == 3);
    const auto spriteInstances = manager.getSpriteInstances();
    assert(spriteInstances.size() == 3);
    assert(spriteInstances[0] == second);
    assert(spriteInstances[1] == first);
    assert(spriteInstances[2] == third);
    assert(manager.getAllInstances().size() == 3);

    auto frameRef = ScoreBehaviorRef(1, 30);
    auto frameScript = makeBehaviorScript(710);
    auto frame = manager.getOrCreateFrameScriptForScript(frameScript, frameRef, 12);
    assert(frame != nullptr);
    assert(frame->isFrameBehavior());
    assert(manager.frameScriptInstance() == frame);
    assert(manager.getOrCreateFrameScriptForScript(frameScript, frameRef, 12) == frame);
    auto nextFrame = manager.getOrCreateFrameScriptForScript(makeBehaviorScript(711), frameRef, 13);
    assert(nextFrame != nullptr);
    assert(nextFrame != frame);
    assert(manager.getInstance(frame->id()) == nullptr);
    assert(manager.frameScriptInstance() == nextFrame);
    manager.clearFrameScript();
    assert(manager.frameScriptInstance() == nullptr);
    assert(manager.getInstance(nextFrame->id()) == nullptr);

    manager.removeInstance(second);
    assert(manager.getInstance(second->id()) == nullptr);
    assert(manager.getInstancesForChannel(2).empty());
    manager.removeInstancesForChannel(4);
    assert(!manager.hasInstanceForChannel(4, ref));
    assert(manager.getInstance(first->id()) == nullptr);
    manager.clear();
    assert(manager.instanceCount() == 0);
    assert(manager.getAllInstances().empty());
    assert(manager.getSpriteInstances().empty());
}

void testEventDispatcherFoundation() {
    auto names = std::make_shared<ScriptNamesChunk>(
        nullptr,
        ChunkId(900),
        std::vector<std::string>{"", "mouseUp", "enterFrame", "startMovie", "mouseDown", "mouseEnter"});

    auto mouseScript = makeScriptWithHandlers(901, ScriptChunkType::Behavior, {1});
    auto frameScript = makeScriptWithHandlers(902, ScriptChunkType::Behavior, {1, 2});
    auto movieScript = makeScriptWithHandlers(903, ScriptChunkType::MovieScript, {1, 2, 3});

    BehaviorManager behaviorManager;
    auto first = behaviorManager.createInstanceForScript(mouseScript, ScoreBehaviorRef(1, 11), 1);
    auto second = behaviorManager.createInstanceForScript(mouseScript, ScoreBehaviorRef(1, 12), 1);
    auto third = behaviorManager.createInstanceForScript(mouseScript, ScoreBehaviorRef(1, 13), 2);
    auto frame = behaviorManager.getOrCreateFrameScriptForScript(frameScript, ScoreBehaviorRef(1, 20), 7);
    assert(frame != nullptr);

    SpriteRegistry spriteRegistry;
    auto sprite = spriteRegistry.getOrCreateDynamic(1);
    sprite->setScriptInstanceList({Datum::scriptInstance("dynamic")});

    EventDispatcher dispatcher(&behaviorManager);
    dispatcher.setSpriteRegistry(&spriteRegistry);
    dispatcher.setScriptNamesResolver([names](const std::shared_ptr<ScriptChunk>&) {
        return names;
    });
    dispatcher.addMovieScript(EventDispatcher::MovieScriptTarget{movieScript, names});

    dispatcher.setRespondsPredicate([names](const EventTarget& target, std::string_view handler) {
        if (target.kind == EventTargetKind::ScriptInstance) {
            return handler == "mouseUp" || handler == "mouseDown";
        }
        return target.script && target.script->findHandler(handler, names.get()).has_value();
    });

    auto labelForTarget = [](const EventTarget& target) {
        switch (target.kind) {
            case EventTargetKind::Behavior:
                return std::string("behavior:") + std::to_string(target.channel) + ":" +
                       std::to_string(target.behavior ? target.behavior->behaviorRef().castMember() : 0);
            case EventTargetKind::ScriptInstance:
                return std::string("dynamic:") + std::to_string(target.channel);
            case EventTargetKind::MovieScript:
                return std::string("movie:") + std::to_string(target.script ? target.script->id().value() : 0);
        }
        return std::string("unknown");
    };

    std::vector<std::string> calls;
    dispatcher.setHandlerInvoker([&](const EventTarget& target,
                                     std::string_view handler,
                                     const std::vector<Datum>& args) {
        calls.push_back(labelForTarget(target) + ":" + std::string(handler) + ":" + std::to_string(args.size()));
        if (target.kind == EventTargetKind::Behavior && target.behavior == first) {
            return HandlerResult{true, false};
        }
        return HandlerResult{true, true};
    });

    dispatcher.dispatchGlobalEvent(PlayerEvent::MouseUp, {Datum::of(1)});
    assert((calls == std::vector<std::string>{
        "behavior:1:11:mouseUp:1",
        "behavior:2:13:mouseUp:1",
        "behavior:0:20:mouseUp:1",
        "movie:903:mouseUp:1"
    }));
    assert(!dispatcher.isPropagationStopped());

    calls.clear();
    dispatcher.dispatchFrameAndMovieEvent(PlayerEvent::EnterFrame);
    assert((calls == std::vector<std::string>{
        "behavior:0:20:enterFrame:0",
        "movie:903:enterFrame:0"
    }));

    calls.clear();
    dispatcher.dispatchSpriteAndMovieEvent("mouseUp");
    assert((calls == std::vector<std::string>{
        "behavior:1:11:mouseUp:0",
        "behavior:2:13:mouseUp:0",
        "movie:903:mouseUp:0"
    }));

    calls.clear();
    dispatcher.dispatchSpriteEvent(1, PlayerEvent::MouseUp);
    assert((calls == std::vector<std::string>{
        "behavior:1:11:mouseUp:0",
        "behavior:1:12:mouseUp:0",
        "dynamic:1:mouseUp:0"
    }));

    calls.clear();
    dispatcher.dispatchBehaviorEvent(third, "mouseUp", {Datum::of(7), Datum::of(8)});
    assert((calls == std::vector<std::string>{"behavior:2:13:mouseUp:2"}));

    calls.clear();
    dispatcher.dispatchToMovieScripts(PlayerEvent::StartMovie, {Datum::of("go")});
    assert((calls == std::vector<std::string>{"movie:903:startMovie:1"}));

    assert(dispatcher.spriteHasHandler(1, "mouseUp"));
    assert(dispatcher.spriteHasHandler(1, "mouseDown"));
    assert(!dispatcher.spriteHasHandler(99, "mouseUp"));
    assert(dispatcher.isSpriteMouseInteractive(1));
    assert(dispatcher.isMouseHandler("mouseUpOutSide"));
    assert(!dispatcher.isMouseHandler("startMovie"));

    dispatcher.pass();
    assert(!dispatcher.isPropagationStopped());
    dispatcher.stopEvent();
    assert(dispatcher.isEventStopped());
    dispatcher.resetEventStopped();
    assert(!dispatcher.isEventStopped());
    dispatcher.setDebugEnabled(true);
    assert(dispatcher.debugEnabled());
}

void testDebugFoundation() {
    Breakpoint breakpoint = Breakpoint::simple(4, "mouseUp", 12);
    assert(breakpoint.scriptId == 4);
    assert(breakpoint.handlerName == "mouseUp");
    assert(breakpoint.offset == 12);
    assert(breakpoint.enabled);
    assert(breakpoint.key() == "4:mouseUp:12");
    assert(breakpoint.toString() == "Breakpoint[4:mouseUp:12]");

    const auto disabled = breakpoint.withEnabled(false);
    assert(!disabled.enabled);
    assert(disabled.toString() == "Breakpoint[4:mouseUp:12, disabled]");
    assert(BreakpointKey::of(disabled).toString() == "4:mouseUp:12");
    assert(BreakpointKey::of(4, "mouseUp", 12) == BreakpointKey::of(disabled));

    BreakpointManager manager;
    assert(!manager.hasBreakpoint(4, "mouseUp", 12));
    assert(!manager.getBreakpoint(4, "mouseUp", 12).has_value());
    const auto added = manager.addBreakpoint(4, "mouseUp", 12);
    assert(added == breakpoint);
    assert(manager.hasBreakpoint(4, "mouseUp", 12));
    assert(manager.getBreakpoint(4, "mouseUp", 12) == breakpoint);

    const auto toggledDisabled = manager.toggleEnabled(4, "mouseUp", 12);
    assert(toggledDisabled.has_value());
    assert(!toggledDisabled->enabled);
    assert(manager.getBreakpoint(4, "mouseUp", 12)->enabled == false);
    assert(!manager.toggleEnabled(4, "missing", 12).has_value());

    manager.setBreakpoint(Breakpoint::simple(4, "enterFrame", 20));
    manager.setBreakpoint(Breakpoint::simple(5, "startMovie", 1));
    assert(manager.getAllBreakpoints().size() == 3);
    assert(manager.getBreakpointsForScript(4).size() == 2);
    assert((manager.getOffsetsForScript(4) == std::set<int>{12, 20}));

    const auto offsetMap = manager.toOffsetMap();
    assert(offsetMap.at(4) == (std::set<int>{12, 20}));
    assert(offsetMap.at(5) == (std::set<int>{1}));
    assert(manager.serializeLegacy() == "4:12,20;5:1");

    const auto removed = manager.removeBreakpoint(4, "enterFrame", 20);
    assert(removed.has_value());
    assert(removed->offset == 20);
    assert(!manager.removeBreakpoint(4, "enterFrame", 20).has_value());

    assert(!manager.toggleBreakpoint(4, "mouseUp", 12).has_value());
    assert(!manager.hasBreakpoint(4, "mouseUp", 12));
    assert(manager.toggleBreakpoint(4, "mouseUp", 12).has_value());
    assert(manager.hasBreakpoint(4, "mouseUp", 12));

    manager.clearAll();
    assert(manager.serialize().empty());
    manager.setFromOffsetMap({{2, {3, 7}}, {1, {9}}});
    assert(manager.hasBreakpoint(2, "", 3));
    assert(manager.hasBreakpoint(1, "", 9));
    assert(manager.serializeLegacy() == "1:9;2:3,7");

    manager.deserialize("7:1,2;8:3");
    assert(manager.hasBreakpoint(7, "", 1));
    assert(manager.hasBreakpoint(7, "", 2));
    assert(manager.hasBreakpoint(8, "", 3));

    manager.clearAll();
    manager.setBreakpoint(Breakpoint{9, "line\nhandler", 44, false});
    const auto json = manager.serialize();
    assert(json == "{\"version\":3,\"breakpoints\":[{\"scriptId\":9,\"handlerName\":\"line\\nhandler\",\"offset\":44,\"enabled\":false}]}");
    manager.deserialize(json);
    const auto parsed = manager.getBreakpoint(9, "line\nhandler", 44);
    assert(parsed.has_value());
    assert(!parsed->enabled);

    auto watch = WatchExpression::create("watch-id", "the mouseH");
    assert(!watch.hasError());
    assert(!watch.isEvaluated());
    assert(watch.getResultDisplay() == "<not evaluated>");
    assert(watch.getTypeName() == "-");
    assert(watch.toString() == "Watch[the mouseH = <not evaluated>]");

    auto watchWithValue = watch.withValue(Datum::of(42));
    assert(watchWithValue.isEvaluated());
    assert(!watchWithValue.hasError());
    assert(watchWithValue.getResultDisplay() == "42");
    assert(watchWithValue.getTypeName() == "int");

    auto watchWithError = watch.withError("boom");
    assert(watchWithError.hasError());
    assert(watchWithError.getResultDisplay() == "<boom>");
    assert(watchWithError.getTypeName() == "Error");
    assert(watch.withExpression("the mouseV").expression == "the mouseV");
    assert(!WatchExpression::create("random expression").id.empty());

    DebugSnapshot snapshot{
        4,
        "Movie Script",
        "mouseUp",
        12,
        2,
        "pushInt",
        42,
        "literal",
        {InstructionDisplay{12, 2, "pushInt", 42, "literal", true}},
        {Datum::of(1), Datum::of("two")},
        {{"localVar", Datum::of(3)}},
        {{"globalVar", Datum::of(4)}},
        {Datum::of(5)},
        Datum::of(6),
        {CallFrame{4, "Movie Script", "mouseUp", {Datum::of(7)}, Datum::of(8)}},
        {watchWithValue}
    };
    assert(snapshot.scriptId == 4);
    assert(snapshot.allInstructions[0].hasBreakpoint);
    assert(snapshot.stack.size() == 2);
    assert(snapshot.locals.at("localVar").intValue() == 3);
    assert(snapshot.callStack[0].receiver->intValue() == 8);
    assert(snapshot.watchResults[0].getResultDisplay() == "42");
}

void testRenderPipelineFoundation() {
    RenderPipelineStepTrace traceStep{"bake", "2 sprites", 2};
    RenderPipelineTrace trace({traceStep});
    assert(trace.steps().size() == 1);
    assert(trace.steps()[0] == traceStep);
    assert(RenderPipelineTrace::empty().steps().empty());
    assert(libreshockwave::player::render::pipeline::name(SpriteType::Bitmap) == "BITMAP");
    assert(libreshockwave::player::render::pipeline::name(SpriteType::FilmLoop) == "FILM_LOOP");

    auto staticChunk = std::make_shared<CastMemberChunk>(nullptr,
                                                         ChunkId(400),
                                                         MemberType::Bitmap,
                                                         0,
                                                         0,
                                                         std::vector<std::uint8_t>{},
                                                         std::vector<std::uint8_t>{},
                                                         "StaticBitmap",
                                                         0,
                                                         0,
                                                         0);
    RenderSprite simpleSprite(1, 10, 20, 30, 40, true, SpriteType::Bitmap, staticChunk, 0x111111, 0x222222, 41, 75);
    assert(simpleSprite.channel() == 1);
    assert(simpleSprite.x() == 10);
    assert(simpleSprite.y() == 20);
    assert(simpleSprite.width() == 30);
    assert(simpleSprite.height() == 40);
    assert(simpleSprite.locZ() == 0);
    assert(simpleSprite.isVisible());
    assert(simpleSprite.type() == SpriteType::Bitmap);
    assert(simpleSprite.castMember() == staticChunk);
    assert(simpleSprite.dynamicMember() == nullptr);
    assert(simpleSprite.foreColor() == 0x111111);
    assert(simpleSprite.backColor() == 0x222222);
    assert(!simpleSprite.hasForeColor());
    assert(!simpleSprite.hasBackColor());
    assert(simpleSprite.inkMode() == InkMode::DARKEN);
    assert(simpleSprite.ink() == 41);
    assert(simpleSprite.blend() == 75);
    assert(!simpleSprite.isFlipH());
    assert(!simpleSprite.isFlipV());
    assert(!simpleSprite.hasBehaviors());
    assert(simpleSprite.castMemberId() == 400);
    assert(simpleSprite.memberName().has_value());
    assert(simpleSprite.memberName().value() == "StaticBitmap");
    assert(!simpleSprite.hasDirectorHorizontalMirror());

    auto dynamicChunk = std::make_shared<CastMemberChunk>(nullptr,
                                                          ChunkId(401),
                                                          MemberType::Bitmap,
                                                          0,
                                                          0,
                                                          std::vector<std::uint8_t>{},
                                                          std::vector<std::uint8_t>{},
                                                          "DynamicBitmap",
                                                          0,
                                                          0,
                                                          0);
    auto dynamicMember = std::make_shared<CastMember>(99, 2, 33, dynamicChunk);
    auto baked = std::make_shared<Bitmap>(2, 2, 32);
    RenderSprite fullSprite(3,
                            1,
                            2,
                            3,
                            4,
                            5,
                            true,
                            SpriteType::Text,
                            nullptr,
                            dynamicMember,
                            0x333333,
                            0x444444,
                            true,
                            true,
                            32,
                            50,
                            true,
                            false,
                            540.0,
                            -180.0,
                            baked,
                            true);
    assert(fullSprite.channelId().value() == 3);
    assert(fullSprite.locZ() == 5);
    assert(fullSprite.hasForeColor());
    assert(fullSprite.hasBackColor());
    assert(fullSprite.inkMode() == InkMode::BLEND);
    assert(fullSprite.isFlipH());
    assert(!fullSprite.isFlipV());
    assert(fullSprite.rotation() == 540.0);
    assert(fullSprite.skew() == -180.0);
    assert(fullSprite.bakedBitmap() == baked);
    assert(fullSprite.hasBehaviors());
    assert(fullSprite.hasDirectorHorizontalMirror());
    assert(fullSprite.castMemberId() == 33);
    assert(fullSprite.memberName().value() == "DynamicBitmap");

    auto replacementBitmap = std::make_shared<Bitmap>(4, 4, 32);
    auto withBaked = fullSprite.withBakedBitmap(replacementBitmap);
    assert(withBaked.bakedBitmap() == replacementBitmap);
    assert(withBaked.width() == fullSprite.width());
    assert(withBaked.height() == fullSprite.height());
    auto withSize = fullSprite.withBakedBitmapAndSize(replacementBitmap, 10, 11);
    assert(withSize.bakedBitmap() == replacementBitmap);
    assert(withSize.width() == 10);
    assert(withSize.height() == 11);
    assert(withSize.hasDirectorHorizontalMirror());

    RenderSprite memberless(4, 0, 0, 1, 1, true, SpriteType::Unknown, nullptr, 0, 0, 0, 0);
    assert(memberless.castMemberId() == -1);
    assert(!memberless.memberName().has_value());

    FrameSnapshot snapshot{
        12,
        640,
        480,
        0x00ABCDEF,
        {simpleSprite, fullSprite},
        "frame 12",
        baked,
        7,
        trace
    };
    assert(snapshot.frameNumber == 12);
    assert(snapshot.stageWidth == 640);
    assert(snapshot.stageHeight == 480);
    assert(snapshot.backgroundColor == 0x00ABCDEF);
    assert(snapshot.sprites.size() == 2);
    assert(snapshot.stageImage == baked);
    assert(snapshot.bakeTick == 7);
    assert(snapshot.pipelineTrace.steps()[0].stepName == "bake");

    auto stageImage = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFF010203U});
    FrameRenderPipelineContext context(8, 320, 240, 0x00445566, stageImage, "debug");
    assert(context.frameNumber() == 8);
    assert(context.stageWidth() == 320);
    assert(context.stageHeight() == 240);
    assert(context.backgroundColor() == 0x00445566);
    assert(context.stageImage() == stageImage);
    assert(context.debugInfo() == "debug");
    assert(context.sprites().empty());
    assert(context.renderedChannels().empty());
    assert(context.buildTrace().steps().empty());
    assert(!context.snapshot().has_value());

    class CountingStep final : public FrameRenderPipelineStep {
    public:
        std::string_view name() const override { return "counting"; }
        void execute(FrameRenderPipelineContext& context) override {
            context.sprites().push_back(RenderSprite(
                9, 1, 2, 3, 4, true, SpriteType::Shape, nullptr, 0, 0, 0, 100));
            context.renderedChannels().insert(9);
            context.addTrace(std::string(name()), "added one sprite");
        }
    };

    CountingStep step;
    assert(step.name() == "counting");
    step.execute(context);
    assert(context.sprites().size() == 1);
    assert(context.sprites()[0].channel() == 9);
    assert((context.renderedChannels() == std::set<int>{9}));
    auto contextTrace = context.buildTrace();
    assert(contextTrace.steps().size() == 1);
    assert(contextTrace.steps()[0].stepName == "counting");
    assert(contextTrace.steps()[0].summary == "added one sprite");
    assert(contextTrace.steps()[0].spriteCount == 1);

    context.setSnapshot(FrameSnapshot{
        8,
        320,
        240,
        0x00445566,
        context.sprites(),
        context.debugInfo(),
        context.stageImage(),
        99,
        contextTrace
    });
    assert(context.snapshot().has_value());
    assert(context.snapshot()->frameNumber == 8);
    assert(context.snapshot()->sprites.size() == 1);
    assert(context.snapshot()->pipelineTrace.steps()[0].spriteCount == 1);

    class TraceOnlyStep final : public FrameRenderPipelineStep {
    public:
        explicit TraceOnlyStep(std::string stepName) : stepName_(std::move(stepName)) {}
        std::string_view name() const override { return stepName_; }
        void execute(FrameRenderPipelineContext& context) override {
            context.addTrace(stepName_, "ran " + stepName_);
        }

    private:
        std::string stepName_;
    };

    class SnapshotStep final : public FrameRenderPipelineStep {
    public:
        std::string_view name() const override { return "build-frame-snapshot"; }
        void execute(FrameRenderPipelineContext& context) override {
            context.addTrace(std::string(name()), "built snapshot");
            context.setSnapshot(FrameSnapshot{
                context.frameNumber(),
                context.stageWidth(),
                context.stageHeight(),
                context.backgroundColor(),
                context.sprites(),
                context.debugInfo(),
                context.stageImage(),
                0,
                context.buildTrace()
            });
        }
    };

    FrameRenderPipeline pipeline;
    pipeline.registerStep(std::make_shared<TraceOnlyStep>("first"));
    pipeline.registerStep(std::make_shared<TraceOnlyStep>("second"));
    pipeline.registerStep(std::make_shared<SnapshotStep>());
    assert(pipeline.stepCount() == 3);
    assert(pipeline.steps()[0]->name() == "first");
    auto pipelineSnapshot = pipeline.renderFrame(22, 100, 50, 0x000A0B0C, stageImage, "Frame 22");
    assert(pipelineSnapshot.frameNumber == 22);
    assert(pipelineSnapshot.stageWidth == 100);
    assert(pipelineSnapshot.stageHeight == 50);
    assert(pipelineSnapshot.backgroundColor == 0x000A0B0C);
    assert(pipelineSnapshot.debugInfo == "Frame 22");
    assert(pipelineSnapshot.stageImage == stageImage);
    assert(pipelineSnapshot.pipelineTrace.steps().size() == 3);
    assert(pipelineSnapshot.pipelineTrace.steps()[0].stepName == "first");
    assert(pipelineSnapshot.pipelineTrace.steps()[1].stepName == "second");
    assert(pipelineSnapshot.pipelineTrace.steps()[2].stepName == "build-frame-snapshot");

    bool nullStepThrew = false;
    try {
        pipeline.registerStep(nullptr);
    } catch (const std::invalid_argument&) {
        nullStepThrew = true;
    }
    assert(nullStepThrew);

    FrameRenderPipeline missingSnapshotPipeline;
    missingSnapshotPipeline.registerStep(std::make_shared<TraceOnlyStep>("trace-only"));
    bool missingSnapshotThrew = false;
    try {
        (void)missingSnapshotPipeline.renderFrame(1, 10, 10, 0, nullptr, "Frame 1");
    } catch (const std::runtime_error&) {
        missingSnapshotThrew = true;
    }
    assert(missingSnapshotThrew);
}

void testBitmapCacheAndInkProcessorFoundation() {
    assert(InkProcessor::shouldProcessInk(InkMode::MASK));
    assert(InkProcessor::shouldProcessInk(InkMode::BACKGROUND_TRANSPARENT));
    assert(!InkProcessor::shouldProcessInk(InkMode::COPY));
    assert(InkProcessor::allowsColorize(0));
    assert(!InkProcessor::allowsColorize(InkMode::MATTE));

    Bitmap raw(3, 1, 8, {0xFFFFFFFFU, 0xFF7B5005U, 0xFF000000U});
    raw.setPaletteIndices({0, 128, 255});

    auto defaultRemap = BitmapCache::resolveIndexedMatteColorRemap(
        &raw, libreshockwave::id::code(InkMode::MATTE), 0x000000, 0xFFFFFF, true, true, nullptr);
    assert(!defaultRemap.has_value());

    Palette remapPalette({0xFFFFFFFFU, 0xFF33CC66U}, "test-remap");
    auto matteRemap = BitmapCache::resolveIndexedMatteColorRemap(
        &raw, libreshockwave::id::code(InkMode::MATTE), 0x000000, 1, true, true, &remapPalette);
    assert(matteRemap.has_value());
    assert(matteRemap->foreColor == 0x000000U);
    assert(matteRemap->backColor == 0x33CC66U);

    Palette backgroundPalette({0xFFFFFFFFU, 0xFF6699FFU}, "test-background-remap");
    auto backgroundRemap = BitmapCache::resolveIndexedMatteColorRemap(
        &raw, libreshockwave::id::code(InkMode::BACKGROUND_TRANSPARENT), 0x000000, 1, true, true, &backgroundPalette);
    assert(backgroundRemap.has_value());
    assert(backgroundRemap->foreColor == 0x000000U);
    assert(backgroundRemap->backColor == 0x6699FFU);

    Bitmap masked(3, 1, 8, {0x00000000U, 0xFF7B5005U, 0xFF000000U});
    Bitmap indexedRemapped = InkProcessor::applyIndexedColorRemap(raw, masked, 0x000000, 0x33CC66);
    assert(indexedRemapped.getPixel(0, 0) == 0x00000000U);
    assert(indexedRemapped.getPixel(1, 0) == 0xFF196633U);
    assert(indexedRemapped.getPixel(2, 0) == 0xFF000000U);

    Bitmap remappedThroughCache = BitmapCache::applyIndexedMatteColorRemap(&raw, masked, matteRemap);
    assert(remappedThroughCache.getPixel(1, 0) == 0xFF196633U);

    Bitmap darkenSource(2, 1, 8, {0xFF010203U, 0x00000000U});
    darkenSource.setPaletteIndices({64, 128});
    Bitmap darkenOffset = BitmapCache::applyIndexedMatteColorRemapIfNeeded(
        &darkenSource, darkenSource, libreshockwave::id::code(InkMode::DARKEN), 0x102030, 0, true, false, nullptr);
    assert(darkenOffset.getPixel(0, 0) == 0xFF112233U);
    assert(darkenOffset.getPixel(1, 0) == 0x00000000U);

    Bitmap transparent32(1, 1, 32, {0x00F0F0F0U});
    Bitmap coerced = BitmapCache::coerceNonNativeAlphaToOpaque(transparent32, false);
    assert(coerced.getPixel(0, 0) == 0xFFF0F0F0U);
    auto nativeAlpha = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0x00F0F0F0U});
    nativeAlpha->setNativeAlpha(true);
    auto preserved = BitmapCache::coerceNonNativeAlphaToOpaque(nativeAlpha, true);
    assert(preserved == nativeAlpha);
    assert(preserved->getPixel(0, 0) == 0x00F0F0F0U);

    Bitmap grayscale32(3, 1, 32, {0xFF000000U, 0xFF808080U, 0xFFFFFFFFU});
    Bitmap foreRemap = InkProcessor::applyForeColorRemap(grayscale32, 0xFF0000U, 0x0000FFU);
    assert(foreRemap.getPixel(0, 0) == 0xFFFF0000U);
    assert(foreRemap.getPixel(1, 0) == 0xFF7F0080U);
    assert(foreRemap.getPixel(2, 0) == 0xFF0000FFU);

    CastMemberChunk member(nullptr,
                           ChunkId(501),
                           MemberType::Bitmap,
                           0,
                           0,
                           std::vector<std::uint8_t>{},
                           std::vector<std::uint8_t>{},
                           "cached",
                           0,
                           0,
                           0);
    BitmapCache cache;
    auto cachedBitmap = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFF010203U});
    cache.putProcessed(member, 8, 0xFFFFFF, 0x000000, true, true, cachedBitmap);
    assert(cache.cachedBitmapCount() == 1);
    assert(cache.getCachedProcessed(member, 8, 0xFFFFFF, 0x000000, true, true) == cachedBitmap);
    assert(cache.getCachedProcessed(member, 8, 0xFFFFFF, 0x111111, true, true) == nullptr);
    cache.markDecodeFailed(member);
    assert(cache.hasDecodeFailed(member));
    assert(cache.decodeFailedCount() == 1);
    assert(cache.invalidateIfPaletteChanged(member, 1));
    assert(cache.cachedBitmapCount() == 0);
    assert(!cache.hasDecodeFailed(member));
    assert(cache.trackedPaletteVersionCount() == 1);
    assert(!cache.invalidateIfPaletteChanged(member, 1));
    cache.clear();
    assert(cache.cachedBitmapCount() == 0);
    assert(cache.decodeFailedCount() == 0);
    assert(cache.trackedPaletteVersionCount() == 0);
}

void testSoftwareFrameRenderer() {
    FrameSnapshot backgroundSnapshot{
        1,
        3,
        2,
        0x00010203,
        {},
        "",
        nullptr,
        0,
        RenderPipelineTrace::empty()
    };
    auto background = backgroundSnapshot.renderFrame();
    assert(background.width() == 3);
    assert(background.height() == 2);
    for (auto pixel : background.pixels()) {
        assert(pixel == 0xFF010203U);
    }

    auto stageImage = std::make_shared<Bitmap>(2, 1, 32, std::vector<std::uint32_t>{
        0xFF111111U,
        0x80123456U
    });
    FrameSnapshot stageImageSnapshot{
        2,
        3,
        2,
        0x00FFFFFF,
        {},
        "",
        stageImage,
        0,
        RenderPipelineTrace::empty()
    };
    auto stageImageRender = SoftwareFrameRenderer::renderFrame(stageImageSnapshot, 3, 2);
    assert(stageImageRender.getPixel(0, 0) == 0xFF111111U);
    assert(stageImageRender.getPixel(1, 0) == 0x80123456U);
    assert(stageImageRender.getPixel(2, 0) == 0x00000000U);
    assert(stageImageRender.getPixel(0, 1) == 0x00000000U);

    auto translucentGreen = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0x8000FF00U});
    RenderSprite alphaSprite(1,
                             0,
                             0,
                             1,
                             1,
                             0,
                             true,
                             SpriteType::Bitmap,
                             nullptr,
                             nullptr,
                             0,
                             0,
                             false,
                             false,
                             0,
                             100,
                             false,
                             false,
                             translucentGreen,
                             false);
    FrameSnapshot alphaSnapshot{3, 1, 1, 0x00000000, {alphaSprite}, "", nullptr, 0, RenderPipelineTrace::empty()};
    assert(alphaSnapshot.renderFrame().getPixel(0, 0) == 0xFF008000U);

    auto opaqueRed = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFFFF0000U});
    RenderSprite blendSprite(1,
                             0,
                             0,
                             1,
                             1,
                             0,
                             true,
                             SpriteType::Bitmap,
                             nullptr,
                             nullptr,
                             0,
                             0,
                             false,
                             false,
                             0,
                             50,
                             false,
                             false,
                             opaqueRed,
                             false);
    FrameSnapshot blendSnapshot{4, 1, 1, 0x00000000, {blendSprite}, "", nullptr, 0, RenderPipelineTrace::empty()};
    assert(blendSnapshot.renderFrame().getPixel(0, 0) == 0xFF7E0000U);

    auto redBlue = std::make_shared<Bitmap>(2, 1, 32, std::vector<std::uint32_t>{
        0xFFFF0000U,
        0xFF0000FFU
    });
    RenderSprite scaledFlipSprite(1,
                                  0,
                                  0,
                                  4,
                                  1,
                                  0,
                                  true,
                                  SpriteType::Bitmap,
                                  nullptr,
                                  nullptr,
                                  0,
                                  0,
                                  false,
                                  false,
                                  0,
                                  100,
                                  true,
                                  false,
                                  redBlue,
                                  false);
    FrameSnapshot scaledFlipSnapshot{5, 4, 1, 0x00000000, {scaledFlipSprite}, "", nullptr, 0, RenderPipelineTrace::empty()};
    auto scaledFlip = scaledFlipSnapshot.renderFrame();
    assert(scaledFlip.getPixel(0, 0) == 0xFF0000FFU);
    assert(scaledFlip.getPixel(1, 0) == 0xFF0000FFU);
    assert(scaledFlip.getPixel(2, 0) == 0xFFFF0000U);
    assert(scaledFlip.getPixel(3, 0) == 0xFFFF0000U);

    RenderSprite directorMirrorSprite(1,
                                      0,
                                      0,
                                      2,
                                      1,
                                      0,
                                      true,
                                      SpriteType::Bitmap,
                                      nullptr,
                                      nullptr,
                                      0,
                                      0,
                                      false,
                                      false,
                                      0,
                                      100,
                                      false,
                                      false,
                                      180.0,
                                      180.0,
                                      redBlue,
                                      false);
    FrameSnapshot mirrorSnapshot{6, 2, 1, 0x00000000, {directorMirrorSprite}, "", nullptr, 0, RenderPipelineTrace::empty()};
    auto mirror = mirrorSnapshot.renderFrame();
    assert(mirror.getPixel(0, 0) == 0xFF0000FFU);
    assert(mirror.getPixel(1, 0) == 0xFFFF0000U);

    auto inkSource = std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFF010203U});
    RenderSprite addInkSprite(1,
                              0,
                              0,
                              1,
                              1,
                              0,
                              true,
                              SpriteType::Bitmap,
                              nullptr,
                              nullptr,
                              0,
                              0,
                              false,
                              false,
                              34,
                              100,
                              false,
                              false,
                              inkSource,
                              false);
    FrameSnapshot addInkSnapshot{7, 1, 1, 0x00102030, {addInkSprite}, "", nullptr, 0, RenderPipelineTrace::empty()};
    assert(addInkSnapshot.renderFrame().getPixel(0, 0) == 0xFF112233U);
}

void testTextRendererFoundation() {
    assert((TextRenderer::splitLines("") == std::vector<std::string>{""}));
    assert((TextRenderer::splitLines("a\r\nb\rc\n") == std::vector<std::string>{"a", "b", "c", ""}));
    assert((TextRenderer::findCharLine("", 10) == std::vector<int>{0, 0}));
    assert((TextRenderer::findCharLine("ab\r\ncd\ne", 6) == std::vector<int>{1, 1}));
    assert(TextRenderer::lineStartIndex("", 3) == 0);
    assert(TextRenderer::lineStartIndex("ab\r\ncd\ne", 0) == 0);
    assert(TextRenderer::lineStartIndex("ab\r\ncd\ne", 1) == 4);
    assert(TextRenderer::lineStartIndex("ab\r\ncd\ne", 2) == 7);
    assert(TextRenderer::lineStartIndex("ab\r\ncd\ne", 5) == 8);

    auto measure = [](std::string_view value) {
        return static_cast<int>(value.size());
    };
    std::vector<std::string> wrapped;
    TextRenderer::wrapLine("", measure, 10, wrapped);
    assert((wrapped == std::vector<std::string>{""}));
    wrapped.clear();
    TextRenderer::wrapLine("alpha beta gamma", measure, 10, wrapped);
    assert((wrapped == std::vector<std::string>{"alpha beta", "gamma"}));
    wrapped.clear();
    TextRenderer::wrapLine("alpha beta-gamma", measure, 11, wrapped);
    assert((wrapped == std::vector<std::string>{"alpha beta-", "gamma"}));

    class RecordingTextRenderer final : public TextRenderer {
    public:
        std::string lastText;
        int lastWidth = 0;
        int lastHeight = 0;
        std::string lastFontName;
        int lastFontSize = 0;
        std::string lastFontStyle;
        std::string lastAlignment;
        int lastTextColor = 0;
        int lastBgColor = 0;
        bool lastWordWrap = false;
        bool lastAntialias = false;
        int lastFixedLineSpace = 0;
        int lastTopSpacing = -1;

        std::shared_ptr<Bitmap> renderText(std::string text,
                                           int width,
                                           int height,
                                           std::string fontName,
                                           int fontSize,
                                           std::string fontStyle,
                                           std::string alignment,
                                           int textColor,
                                           int bgColor,
                                           bool wordWrap,
                                           bool antialias,
                                           int fixedLineSpace,
                                           int topSpacing) override {
            lastText = std::move(text);
            lastWidth = width;
            lastHeight = height;
            lastFontName = std::move(fontName);
            lastFontSize = fontSize;
            lastFontStyle = std::move(fontStyle);
            lastAlignment = std::move(alignment);
            lastTextColor = textColor;
            lastBgColor = bgColor;
            lastWordWrap = wordWrap;
            lastAntialias = antialias;
            lastFixedLineSpace = fixedLineSpace;
            lastTopSpacing = topSpacing;
            return std::make_shared<Bitmap>(1, 1, 32, std::vector<std::uint32_t>{0xFF123456U});
        }

        std::vector<int> charPosToLoc(std::string,
                                      int,
                                      std::string,
                                      int,
                                      std::string,
                                      int,
                                      std::string,
                                      int) override {
            return {0, 0};
        }

        int locToCharPos(std::string, int, int, std::string, int, std::string, int, std::string, int) override {
            return 0;
        }

        int getLineHeight(std::string, int fontSize, std::string, int fixedLineSpace) override {
            return fixedLineSpace > 0 ? fixedLineSpace : fontSize;
        }
    };

    RecordingTextRenderer renderer;
    assert(renderer.renderXmedText(nullptr, 10, 20, 0xFF010203, 0xFF000000) == nullptr);
    XmedStyledText styled{};
    styled.text = "hello";
    styled.fontName = "Chicago";
    styled.fontSize = 12;
    styled.memberBold = true;
    styled.alignment = "center";
    styled.wordWrap = true;
    styled.antialias = true;
    styled.fixedLineSpace = 14;
    auto rendered = renderer.renderXmedText(&styled, 80, 30, 0xFF010203, 0xFFEEEEEE);
    assert(rendered != nullptr);
    assert(rendered->getPixel(0, 0) == 0xFF123456U);
    assert(renderer.lastText == "hello");
    assert(renderer.lastWidth == 80);
    assert(renderer.lastHeight == 30);
    assert(renderer.lastFontName == "Chicago");
    assert(renderer.lastFontSize == 12);
    assert(renderer.lastFontStyle == "bold");
    assert(renderer.lastAlignment == "center");
    assert(renderer.lastTextColor == static_cast<int>(0xFF010203U));
    assert(renderer.lastBgColor == static_cast<int>(0xFFEEEEEEU));
    assert(renderer.lastWordWrap);
    assert(renderer.lastAntialias);
    assert(renderer.lastFixedLineSpace == 14);
    assert(renderer.lastTopSpacing == 0);
}

void testNetTaskFoundation() {
    auto getTask = NetTask::get(1, "movie.dir", "https://example.invalid/movie.dir");
    assert(getTask.taskId() == 1);
    assert(getTask.originalUrl() == "movie.dir");
    assert(getTask.url() == "https://example.invalid/movie.dir");
    assert(getTask.method() == NetTaskMethod::Get);
    assert(!getTask.postData().has_value());
    assert(getTask.state() == NetTaskState::Pending);
    assert(!getTask.isDone());
    assert(!getTask.result().has_value());
    assert(getTask.resultAsString().empty());
    assert(getTask.errorCode() == 0);
    assert(!getTask.errorMessage().has_value());
    assert(getTask.streamStatus() == "Connecting");
    assert(getTask.toString() == "NetTask{id=1, util=https://example.invalid/movie.dir, method=GET, state=PENDING}");

    getTask.markInProgress();
    assert(getTask.state() == NetTaskState::InProgress);
    assert(getTask.streamStatus() == "Loading");
    assert(!getTask.isDone());
    getTask.complete(std::vector<std::uint8_t>{'O', 'K'});
    assert(getTask.state() == NetTaskState::Completed);
    assert(getTask.isDone());
    assert(getTask.result().has_value());
    assert(getTask.resultAsString() == "OK");
    assert(getTask.errorCode() == 0);
    assert(getTask.streamStatus() == "Complete");
    assert(getTask.toString() == "NetTask{id=1, util=https://example.invalid/movie.dir, method=GET, state=COMPLETED}");

    auto postTask = NetTask::post(2, "submit", "https://example.invalid/submit", "a=b");
    assert(postTask.method() == NetTaskMethod::Post);
    assert(postTask.postData().has_value());
    assert(postTask.postData().value() == "a=b");
    assert(postTask.streamStatus() == "Connecting");
    postTask.fail(404, "not found");
    assert(postTask.state() == NetTaskState::Failed);
    assert(postTask.isDone());
    assert(postTask.errorCode() == 404);
    assert(postTask.errorMessage().value() == "not found");
    assert(postTask.streamStatus() == "Error");
    assert(postTask.resultAsString().empty());
    assert(postTask.toString() == "NetTask{id=2, util=https://example.invalid/submit, method=POST, state=FAILED}");
    assert(libreshockwave::player::net::name(NetTaskMethod::Get) == "GET");
    assert(libreshockwave::player::net::name(NetTaskState::InProgress) == "IN_PROGRESS");
}

Datum statusProp(const Datum& props, const std::string& key) {
    return props.propListValue().get(Datum::of(key));
}

void testNetManagerFoundation() {
    assert(NetManager::resolveUrl("http://localhost/path/file.txt?cache=1") == "file.txt");
    assert(NetManager::resolveUrl("casts\\room.cct?x=1") == "room.cct");
    assert(NetManager::resolveUrl("") == "");
    assert(NetManager::cacheKeyForUrl("http://example.invalid/casts/room.cct?x=1") == "room.cct");
    assert(NetManager::extractUrlPath("http://localhost:8080/gamedata/file.txt?x=1") == "/gamedata/file.txt");
    assert(NetManager::extractUrlPath("http://localhost:8080") == "/");
    assert(NetManager::extractOrigin("https://example.invalid/path/file") == "https://example.invalid");
    assert(NetManager::extractOrigin("relative/file") == "");

    NetManager manager;
    assert(manager.getTask() == nullptr);
    assert(manager.getStreamStatus() == "Error");
    assert(!manager.netDone());
    assert(manager.netError() == 0);
    auto missingStatus = manager.getStreamStatusDatum();
    assert(statusProp(missingStatus, "URL").stringValue().empty());
    assert(statusProp(missingStatus, "state").stringValue() == "Error");
    assert(statusProp(missingStatus, "bytesSoFar").intValue() == 0);
    assert(statusProp(missingStatus, "error").stringValue() == "OK");

    manager.setBasePath("http://example.invalid/movie/");
    manager.setLocalHttpRoot("/tmp/www");
    assert(manager.getBasePath() == "http://example.invalid/movie/");
    assert(manager.basePath() == "http://example.invalid/movie/");
    assert(manager.localHttpRoot() == "/tmp/www");

    manager.cacheData("cached.cct", {'C', 'A'});
    assert(manager.getCachedData("cached.cct").value() == std::vector<std::uint8_t>({'C', 'A'}));
    assert(manager.getCachedData("cached.cst").value() == std::vector<std::uint8_t>({'C', 'A'}));
    assert(manager.getCachedData("cached").value() == std::vector<std::uint8_t>({'C', 'A'}));

    std::vector<std::string> completedUrls;
    std::vector<int> completedSizes;
    manager.setCompletionCallback([&](const std::string& url, const std::vector<std::uint8_t>& data) {
        completedUrls.push_back(url);
        completedSizes.push_back(static_cast<int>(data.size()));
    });

    const int cachedTaskId = manager.preloadNetThing("cached.cst");
    assert(cachedTaskId == 1);
    assert(manager.getTask(cachedTaskId)->state() == NetTaskState::Completed);
    assert(manager.netDone(cachedTaskId));
    assert(manager.netTextResult(cachedTaskId) == "CA");
    assert(manager.getNetBytes(cachedTaskId).value() == std::vector<std::uint8_t>({'C', 'A'}));
    assert(manager.getTask()->taskId() == cachedTaskId);
    assert(completedUrls == std::vector<std::string>{"cached.cst"});
    assert(completedSizes == std::vector<int>{2});

    int fetchCount = 0;
    manager.setFetchHandler([&](const NetTask& task) {
        ++fetchCount;
        if (task.originalUrl() == "submit") {
            assert(task.method() == NetTaskMethod::Post);
            assert(task.postData().has_value());
            assert(task.postData().value() == "a=b");
            return NetManager::LoadResult::success(std::vector<std::uint8_t>{'P', 'O', 'S', 'T'});
        }
        if (task.originalUrl() == "missing") {
            return NetManager::LoadResult::failure(-7, "bad url");
        }
        assert(task.method() == NetTaskMethod::Get);
        assert(task.url() == "movie.dir");
        return NetManager::LoadResult::success(std::vector<std::uint8_t>{'O', 'K'});
    });

    const int getTaskId = manager.preloadNetThing("http://example.invalid/path/movie.dir?random=1");
    assert(getTaskId == 2);
    assert(fetchCount == 1);
    const auto* getTask = manager.getTask(getTaskId);
    assert(getTask != nullptr);
    assert(getTask->originalUrl() == "http://example.invalid/path/movie.dir?random=1");
    assert(getTask->url() == "movie.dir");
    assert(getTask->state() == NetTaskState::Completed);
    assert(manager.netDone(getTaskId));
    assert(manager.netError(getTaskId) == 0);
    assert(manager.netTextResult(getTaskId) == "OK");
    assert(manager.getStreamStatus(getTaskId) == "Complete");
    assert(manager.getCachedData("movie.dir").value() == std::vector<std::uint8_t>({'O', 'K'}));
    assert(completedUrls.back() == "http://example.invalid/path/movie.dir?random=1");

    const auto statusById = manager.getStreamStatusDatum(getTaskId);
    assert(statusProp(statusById, "URL").stringValue() == "http://example.invalid/path/movie.dir?random=1");
    assert(statusProp(statusById, "state").stringValue() == "Complete");
    assert(statusProp(statusById, "bytesSoFar").intValue() == 2);
    assert(statusProp(statusById, "bytesTotal").intValue() == 2);
    assert(statusProp(statusById, "error").stringValue() == "OK");
    assert(statusProp(manager.getStreamStatusDatum(std::string_view("movie.dir")), "state").stringValue() == "Complete");
    assert(statusProp(manager.getStreamStatusDatum(std::string_view("http://example.invalid/path/movie.dir")), "state").stringValue() == "Complete");

    const int postTaskId = manager.postNetText("submit", "a=b");
    assert(postTaskId == 3);
    assert(fetchCount == 2);
    assert(manager.getTask(postTaskId)->method() == NetTaskMethod::Post);
    assert(manager.netTextResult(postTaskId) == "POST");
    assert(manager.getCachedData("submit").value() == std::vector<std::uint8_t>({'P', 'O', 'S', 'T'}));

    const int failedTaskId = manager.preloadNetThing("missing");
    assert(failedTaskId == 4);
    assert(fetchCount == 3);
    assert(manager.netDone());
    assert(manager.netError() == -7);
    assert(manager.netTextResult().empty());
    assert(manager.getStreamStatus() == "Error");
    const auto failedStatus = manager.getStreamStatusDatum(failedTaskId);
    assert(statusProp(failedStatus, "state").stringValue() == "Error");
    assert(statusProp(failedStatus, "bytesSoFar").intValue() == 0);
    assert(statusProp(failedStatus, "error").stringValue() == "-7");
    assert(!manager.getNetBytes(failedTaskId).has_value());

    NetManager noFetcher;
    const int noFetcherTask = noFetcher.preloadNetThing("no-handler");
    assert(noFetcher.netDone(noFetcherTask));
    assert(noFetcher.netError(noFetcherTask) == 404);
    assert(noFetcher.getTask(noFetcherTask)->errorMessage().value() == "No fetch handler configured");

    manager.shutdown();
    manager.clear();
    assert(manager.tasks().empty());
    assert(manager.urlCache().empty());
    assert(manager.getTask() == nullptr);
    const int resetTask = manager.preloadNetThing("after-clear");
    assert(resetTask == 1);
}

void testTimeoutManagerFoundation() {
    TimeoutManager manager;
    assert(manager.getTimeoutCount() == 0);
    assert(manager.getTimeoutNames().empty());
    assert(!manager.timeoutExists("timer"));
    assert(manager.getTimeoutProp("timer", "name").isVoid());
    assert(!manager.setTimeoutProp("timer", "period", Datum::of(1000)));

    auto ref = manager.createTimeout("timer", 1000, "onTimer", Datum::of(std::string("target-id")));
    assert(ref.type() == DatumType::TimeoutRef);
    assert(ref.asTimeoutRef() != nullptr);
    assert(ref.asTimeoutRef()->name == "timer");
    assert(ref.stringValue() == "timer");
    assert(manager.timeoutExists("timer"));
    assert(manager.getTimeoutCount() == 1);
    assert((manager.getTimeoutNames() == std::vector<std::string>{"timer"}));
    assert(manager.getTimeoutProp("timer", "NAME").stringValue() == "timer");
    assert(manager.getTimeoutProp("timer", "target").stringValue() == "target-id");
    assert(manager.getTimeoutProp("timer", "period").intValue() == 1000);
    assert(manager.getTimeoutProp("timer", "handler").asSymbol() != nullptr);
    assert(manager.getTimeoutProp("timer", "handler").asSymbol()->name == "onTimer");
    assert(!manager.getTimeoutProp("timer", "persistent").boolValue());
    assert(manager.getTimeoutProp("timer", "time").intValue() >= 0);
    assert(manager.getEntry("timer") != nullptr);
    assert(!manager.getEntry("timer")->oneShot);

    assert(manager.setTimeoutProp("timer", "period", Datum::of(250)));
    assert(manager.setTimeoutProp("timer", "handler", Datum::symbol("onOther")));
    assert(manager.setTimeoutProp("timer", "persistent", Datum::TRUE));
    assert(manager.setTimeoutProp("timer", "oneshot", Datum::TRUE));
    assert(manager.setTimeoutProp("timer", "target", Datum::of(42)));
    assert(!manager.setTimeoutProp("timer", "missing", Datum::of(1)));
    assert(manager.getTimeoutProp("timer", "period").intValue() == 250);
    assert(manager.getTimeoutProp("timer", "handler").asSymbol()->name == "onOther");
    assert(manager.getTimeoutProp("timer", "persistent").boolValue());
    assert(manager.getTimeoutProp("timer", "target").intValue() == 42);
    assert(manager.getEntry("timer")->oneShot);

    auto oneShotRef = manager.createTimeout("once", 1, "go", Datum::voidValue(), true);
    assert(oneShotRef.asTimeoutRef()->name == "once");
    assert(manager.getEntry("once")->oneShot);
    assert((manager.getTimeoutNames() == std::vector<std::string>{"once", "timer"}));
    manager.forgetTimeout("timer");
    assert(!manager.timeoutExists("timer"));
    assert(manager.timeoutExists("once"));
    manager.clear();
    assert(manager.getTimeoutCount() == 0);
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

void testBitmapDecoder() {
    const auto rle = BitmapDecoder::decompressRLE({0x02, 'a', 'b', 'c', 0xFE, 'x', 0x80, 0x00, 'z'}, 7);
    assert(std::string(rle.begin(), rle.end()) == "abcxxxz");
    assert(BitmapDecoder::decompressRLE({0x04, 'a'}, 5).size() == 1);
    assert(BitmapDecoder::calculateScanWidthPixels(3, 8, 0) == 4);
    assert(BitmapDecoder::calculateScanWidthPixels(3, 8, 3) == 3);
    assert(BitmapDecoder::calculateScanWidth(3, 8) == 4);

    Palette custom({0x110000U, 0x002200U, 0x000033U, 0xFFFFFFU}, "test");
    Bitmap indexed = BitmapDecoder::decode8Bit({0, 1, 2, 3}, 2, 2, 2, &custom);
    assert(indexed.getPixel(0, 0) == 0xFF110000U);
    assert(indexed.getPixel(1, 0) == 0xFF002200U);
    assert(indexed.getPixel(0, 1) == 0xFF000033U);
    assert(indexed.getPixel(1, 1) == 0xFFFFFFFFU);
    assert(indexed.paletteIndex(1, 1).value() == 3);

    Bitmap mono = BitmapDecoder::decode1Bit({0x80}, 2, 1, 8, nullptr);
    assert(mono.getPixel(0, 0) == 0xFF000000U);
    assert(mono.getPixel(1, 0) == 0xFFFFFFFFU);
    assert(mono.paletteIndex(0, 0).value() == 255);
    assert(mono.paletteIndex(1, 0).value() == 0);

    Bitmap twoBit = BitmapDecoder::decode2Bit({0b00011011}, 4, 1, 4, nullptr);
    assert(twoBit.paletteIndex(0, 0).value() == 0);
    assert(twoBit.paletteIndex(1, 0).value() == 85);
    assert(twoBit.paletteIndex(2, 0).value() == 170);
    assert(twoBit.paletteIndex(3, 0).value() == 255);

    Bitmap fourBit = BitmapDecoder::decode4Bit({0x0F}, 2, 1, 2, nullptr);
    assert(fourBit.paletteIndex(0, 0).value() == 0);
    assert(fourBit.paletteIndex(1, 0).value() == 255);

    Bitmap rgb555 = BitmapDecoder::decode16Bit({0x7C, 0x00}, 1, 1, 1, true);
    assert(rgb555.getPixel(0, 0) == 0xFFFF0000U);

    Bitmap argb = BitmapDecoder::decode32Bit({0x80, 0x11, 0x22, 0x33}, 1, 1, 1, true);
    assert(argb.getPixel(0, 0) == 0x80112233U);
    Bitmap interleaved = BitmapDecoder::decode32Bit({0x7F, 0x44, 0x55, 0x66}, 1, 1, 1, false);
    assert(interleaved.getPixel(0, 0) == 0x7F445566U);

    Bitmap automatic = BitmapDecoder::decode({0, 1}, 2, 1, 8, &custom, true, 1200, 0);
    assert(automatic.getPixel(1, 0) == 0xFF002200U);
    Bitmap empty = BitmapDecoder::decode({}, 0, 0, 8, nullptr);
    assert(empty.width() == 1);
    assert(empty.height() == 1);
}

void testBitmapColorizer() {
    Bitmap grayscale32(3, 1, 32, {0xFF000000U, 0xFF808080U, 0xFFFFFFFFU});
    Bitmap redToBlue = BitmapColorizer::colorize(grayscale32, 0xFF0000U, 0x0000FFU);
    assert(redToBlue.getPixel(0, 0) == 0xFFFF0000U);
    assert(redToBlue.getPixel(1, 0) == 0xFF7F0080U);
    assert(redToBlue.getPixel(2, 0) == 0xFF0000FFU);

    const ColorRef green(ColorRef::Rgb(0, 255, 0));
    Bitmap foregroundOnly = BitmapColorizer::colorize(grayscale32, green, nullptr);
    assert(foregroundOnly.getPixel(0, 0) == 0xFF00FF00U);
    assert(foregroundOnly.getPixel(1, 0) == 0xFF808080U);

    Bitmap indexed(4, 1, 2, {0xFF000000U, 0xFF555555U, 0xFFAAAAAAU, 0xFFFFFFFFU});
    Bitmap indexedColorized = BitmapColorizer::colorize(indexed, 0xFF0000U, 0x0000FFU);
    assert(indexedColorized.getPixel(0, 0) == 0xFFFF0000U);
    assert(indexedColorized.getPixel(1, 0) == 0xFFAA0055U);
    assert(indexedColorized.getPixel(2, 0) == 0xFF5500AAU);
    assert(indexedColorized.getPixel(3, 0) == 0xFF0000FFU);

    assert(BitmapColorizer::allowsColorization(8, 8));
    assert(BitmapColorizer::allowsColorization(32, 0));
    assert(!BitmapColorizer::allowsColorization(16, 8));
    assert(BitmapColorizer::usesBackColor(32, 0));
    assert(!BitmapColorizer::usesBackColor(8, 8));

    const ColorRef black(ColorRef::Rgb(0, 0, 0));
    const ColorRef white(ColorRef::Rgb(255, 255, 255));
    const auto unpacked = BitmapColorizer::colorizeIndexedData({0b00011011}, 2, &black, &white, nullptr);
    assert(unpacked.size() == 4);
    assert(unpacked[0] == 0xFF000000U);
    assert(unpacked[1] == 0xFF555555U);
    assert(unpacked[2] == 0xFFAAAAAAU);
    assert(unpacked[3] == 0xFFFFFFFFU);

    const auto grayscale = BitmapColorizer::colorizeIndexedData({0, 255}, 8, nullptr, nullptr, nullptr);
    assert(grayscale.size() == 2);
    assert(grayscale[0] == 0xFF000000U);
    assert(grayscale[1] == 0xFFFFFFFFU);
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

void testSoundConverter() {
    auto readU32LE = [](const std::vector<std::uint8_t>& data, std::size_t offset) {
        return static_cast<std::uint32_t>(data[offset]) |
               (static_cast<std::uint32_t>(data[offset + 1]) << 8) |
               (static_cast<std::uint32_t>(data[offset + 2]) << 16) |
               (static_cast<std::uint32_t>(data[offset + 3]) << 24);
    };

    const auto wav16 = SoundConverter::toWav({0x12, 0x34}, 8000, 16, 1, true);
    assert(wav16.size() == 46);
    assert(std::string(wav16.begin(), wav16.begin() + 4) == "RIFF");
    assert(std::string(wav16.begin() + 8, wav16.begin() + 12) == "WAVE");
    assert(readU32LE(wav16, 40) == 2);
    assert(wav16[44] == 0x34);
    assert(wav16[45] == 0x12);

    const auto wav8 = SoundConverter::toWav({0x80, 0x00}, 8000, 8, 1, true);
    assert(wav8[44] == 0x00);
    assert(wav8[45] == 0x80);

    std::vector<std::uint8_t> soundData(66, 0);
    soundData[64] = 0x12;
    soundData[65] = 0x34;
    SoundChunk rawSound(nullptr, ChunkId(130), 8000, 1, 16, 1, soundData, "raw_pcm");
    const auto soundWav = SoundConverter::toWav(rawSound);
    assert(soundWav.size() == 46);
    assert(soundWav[44] == 0x34);
    assert(soundWav[45] == 0x12);

    const auto adpcm = SoundConverter::decodeImaAdpcm({0x11}, 0, 0);
    assert((adpcm == std::vector<std::uint8_t>{0x01, 0x00, 0x02, 0x00}));
    const auto adpcmWav = SoundConverter::imaAdpcmToWav({0x11}, 8000, 1, 0, 0);
    assert(adpcmWav.size() == 48);
    assert(readU32LE(adpcmWav, 40) == 4);
    assert(std::fabs(SoundConverter::getDuration(4, 2, 16, 1) - 1.0) < 0.0001);
    assert(SoundConverter::getDuration(4, 0, 16, 1) == 0.0);
    assert(std::fabs(SoundConverter::getDuration(rawSound) - rawSound.durationSeconds()) < 0.0001);

    auto makeMp3Frame = []() {
        std::vector<std::uint8_t> frame(417, 0);
        frame[0] = 0xFF;
        frame[1] = 0xFB;
        frame[2] = 0x90;
        frame[3] = 0x64;
        return frame;
    };
    std::vector<std::uint8_t> mp3Data{0x00, 0x01};
    auto frame = makeMp3Frame();
    mp3Data.insert(mp3Data.end(), frame.begin(), frame.end());
    mp3Data.insert(mp3Data.end(), frame.begin(), frame.end());
    mp3Data.insert(mp3Data.end(), {0xFF, 0xFF});
    assert(SoundConverter::findMp3Start(mp3Data) == 2);
    assert(SoundConverter::isMp3(mp3Data));
    SoundChunk mp3Sound(nullptr, ChunkId(131), 44100, 0, 16, 2, mp3Data, "mp3");
    const auto extracted = SoundConverter::extractMp3(mp3Sound);
    assert(extracted.has_value());
    assert(extracted->size() == 834);
    assert((*extracted)[0] == 0xFF);
    assert((*extracted)[1] == 0xFB);
    SoundChunk nonMp3Sound(nullptr, ChunkId(132), 44100, 0, 16, 2, mp3Data, "raw_pcm");
    assert(!SoundConverter::extractMp3(nonMp3Sound).has_value());
}

class RecordingAudioBackend final : public AudioBackend {
public:
    void play(int channelNum,
              const std::vector<std::uint8_t>& audioData,
              std::string_view format,
              int loopCount) override {
        ++playCount;
        lastPlayChannel = channelNum;
        lastAudioData = audioData;
        lastFormat = std::string(format);
        lastLoopCount = loopCount;
        playing[channelNum] = true;
    }

    void stop(int channelNum) override {
        ++stopCount;
        lastStopChannel = channelNum;
        playing[channelNum] = false;
    }

    void stopAll() override {
        ++stopAllCount;
        playing.clear();
    }

    void setVolume(int channelNum, int volume) override {
        ++setVolumeCount;
        lastVolumeChannel = channelNum;
        lastVolume = volume;
        volumes[channelNum] = volume;
    }

    [[nodiscard]] bool isPlaying(int channelNum) const override {
        auto found = playing.find(channelNum);
        return found != playing.end() && found->second;
    }

    [[nodiscard]] int getElapsedTime(int channelNum) const override {
        auto found = elapsedTimes.find(channelNum);
        return found == elapsedTimes.end() ? 0 : found->second;
    }

    int playCount{0};
    int stopCount{0};
    int stopAllCount{0};
    int setVolumeCount{0};
    int lastPlayChannel{0};
    int lastStopChannel{0};
    int lastVolumeChannel{0};
    int lastVolume{0};
    int lastLoopCount{0};
    std::vector<std::uint8_t> lastAudioData;
    std::string lastFormat;
    std::map<int, bool> playing;
    std::map<int, int> volumes;
    std::map<int, int> elapsedTimes;
};

void testSoundManagerFoundation() {
    assert(SoundManager::MAX_CHANNELS == 8);
    assert(SoundManager::isValidChannel(1));
    assert(SoundManager::isValidChannel(8));
    assert(!SoundManager::isValidChannel(0));
    assert(!SoundManager::isValidChannel(9));
    assert(SoundManager::clampVolume(-10) == 0);
    assert(SoundManager::clampVolume(123) == 123);
    assert(SoundManager::clampVolume(300) == 255);
    assert(SoundManager::detectFormat({'R', 'I', 'F', 'F'}) == "wav");
    assert(SoundManager::detectFormat({0xFF, 0xFB, 0x90}) == "mp3");

    SoundManager manager;
    assert(manager.backend() == nullptr);
    assert(manager.getVolume(1) == 255);
    assert(manager.getVolume(9) == 255);
    manager.setVolume(2, -1);
    assert(manager.getVolume(2) == 0);
    manager.setVolume(2, 300);
    assert(manager.getVolume(2) == 255);
    assert(!manager.isPlaying(2));
    assert(manager.getElapsedTime(2) == 0);

    RecordingAudioBackend backend;
    backend.elapsedTimes[2] = 345;
    manager.setBackend(&backend);
    assert(manager.backend() == &backend);
    manager.setVolume(2, 128);
    assert(manager.getVolume(2) == 128);
    assert(backend.setVolumeCount == 1);
    assert(backend.lastVolumeChannel == 2);
    assert(backend.lastVolume == 128);

    bool resolverCalled = false;
    manager.setAudioResolver([&](const Datum::CastMemberRef& ref) -> std::optional<std::vector<std::uint8_t>> {
        resolverCalled = true;
        if (ref.castLib == 2 && ref.memberNum() == 5) {
            return std::vector<std::uint8_t>{'R', 'I', 'F', 'F', 'w', 'a', 'v'};
        }
        if (ref.castLib == 2 && ref.memberNum() == 6) {
            return std::vector<std::uint8_t>{0xFF, 0xFB, 0x90, 0x64};
        }
        return std::nullopt;
    });

    manager.play(0, Datum::castMemberRef(CastLibId(2), MemberId(5)));
    assert(backend.playCount == 0);
    manager.play(2, Datum::voidValue());
    assert(backend.playCount == 0);

    manager.play(2, Datum::castMemberRef(CastLibId(2), MemberId(5)));
    assert(resolverCalled);
    assert(backend.playCount == 1);
    assert(backend.lastPlayChannel == 2);
    assert(backend.lastAudioData == std::vector<std::uint8_t>({'R', 'I', 'F', 'F', 'w', 'a', 'v'}));
    assert(backend.lastFormat == "wav");
    assert(backend.lastLoopCount == 1);
    assert(backend.lastVolumeChannel == 2);
    assert(backend.lastVolume == 128);
    assert(manager.isPlaying(2));
    assert(manager.getElapsedTime(2) == 345);

    auto args = Datum::propList();
    args.propListValue().put(Datum::symbol("member"), Datum::castMemberRef(CastLibId(2), MemberId(6)));
    args.propListValue().put(Datum::symbol("loopCount"), Datum::of(0));
    manager.play(3, args);
    assert(backend.playCount == 2);
    assert(backend.lastPlayChannel == 3);
    assert(backend.lastFormat == "mp3");
    assert(backend.lastLoopCount == 0);
    assert(backend.lastVolume == 255);

    auto stringKeyArgs = Datum::propList();
    stringKeyArgs.propListValue().put(Datum::of(std::string("member")), Datum::castMemberRef(CastLibId(2), MemberId(5)));
    stringKeyArgs.propListValue().put(Datum::of(std::string("loopCount")), Datum::of(2));
    manager.play(4, Datum::list({stringKeyArgs}));
    assert(backend.playCount == 3);
    assert(backend.lastPlayChannel == 4);
    assert(backend.lastLoopCount == 2);

    auto unresolved = manager.resolveAudioData(Datum::castMemberRef(CastLibId(99), MemberId(1)));
    assert(!unresolved.has_value());
    auto invalidDatum = manager.resolveAudioData(Datum::of(12));
    assert(!invalidDatum.has_value());

    manager.stop(9);
    assert(backend.stopCount == 0);
    manager.stop(2);
    assert(backend.stopCount == 1);
    assert(backend.lastStopChannel == 2);
    assert(!manager.isPlaying(2));
    manager.stopAll();
    assert(backend.stopAllCount == 1);

    std::vector<std::uint8_t> rawData(66, 0);
    rawData[64] = 0x12;
    rawData[65] = 0x34;
    SoundChunk rawSound(nullptr, ChunkId(140), 8000, 1, 16, 1, rawData, "raw_pcm");
    auto rawPlayable = SoundManager::convertSoundToPlayable(rawSound);
    assert(rawPlayable.has_value());
    assert(SoundManager::detectFormat(*rawPlayable) == "wav");

    SoundChunk adpcmSound(nullptr, ChunkId(141), 8000, 1, 16, 1, {0x11}, "ima_adpcm");
    auto adpcmPlayable = SoundManager::convertSoundToPlayable(adpcmSound);
    assert(adpcmPlayable.has_value());
    assert(SoundManager::detectFormat(*adpcmPlayable) == "wav");

    auto makeMp3Frame = []() {
        std::vector<std::uint8_t> frame(417, 0);
        frame[0] = 0xFF;
        frame[1] = 0xFB;
        frame[2] = 0x90;
        frame[3] = 0x64;
        return frame;
    };
    std::vector<std::uint8_t> mp3Data{0, 1};
    auto frame = makeMp3Frame();
    mp3Data.insert(mp3Data.end(), frame.begin(), frame.end());
    mp3Data.insert(mp3Data.end(), frame.begin(), frame.end());
    SoundChunk mp3Sound(nullptr, ChunkId(142), 44100, 0, 16, 2, mp3Data, "mp3");
    auto mp3Playable = SoundManager::convertSoundToPlayable(mp3Sound);
    assert(mp3Playable.has_value());
    assert(mp3Playable->size() == 834);
    assert((*mp3Playable)[0] == 0xFF);
    assert(SoundManager::detectFormat(*mp3Playable) == "mp3");
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

void testW3DFileParser() {
    auto appendU16LE = [](std::vector<std::uint8_t>& data, int value) {
        const auto raw = static_cast<std::uint16_t>(value);
        data.push_back(static_cast<std::uint8_t>(raw & 0xFF));
        data.push_back(static_cast<std::uint8_t>((raw >> 8) & 0xFF));
    };
    auto appendI32LE = [](std::vector<std::uint8_t>& data, std::uint32_t value) {
        data.push_back(static_cast<std::uint8_t>(value & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 8) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 16) & 0xFF));
        data.push_back(static_cast<std::uint8_t>((value >> 24) & 0xFF));
    };
    auto appendF32LE = [&](std::vector<std::uint8_t>& data, float value) {
        appendI32LE(data, std::bit_cast<std::uint32_t>(value));
    };
    auto appendPString16LE = [&](std::vector<std::uint8_t>& data, const std::string& value) {
        appendU16LE(data, static_cast<int>(value.size()));
        data.insert(data.end(), value.begin(), value.end());
    };
    auto appendEntry = [&](std::vector<std::uint8_t>& data,
                           int type,
                           int parentRef,
                           const std::vector<std::uint8_t>& payload) {
        appendU16LE(data, type);
        appendI32LE(data, static_cast<std::uint32_t>(payload.size()));
        appendI32LE(data, static_cast<std::uint32_t>(parentRef));
        data.insert(data.end(), payload.begin(), payload.end());
    };

    assert(libreshockwave::w3d::code(W3DEntryType::Node) == 0x72);
    assert(libreshockwave::w3d::w3dEntryTypeFromCode(0x1234) == W3DEntryType::Unknown);

    std::vector<std::uint8_t> data;

    std::vector<std::uint8_t> version;
    appendI32LE(version, 0x01020304);
    appendEntry(data, 0x02, 0, version);

    std::vector<std::uint8_t> node;
    appendPString16LE(node, "RootNode");
    appendPString16LE(node, "Scene");
    appendI32LE(node, 7);
    for (int index = 0; index < 16; ++index) {
        appendF32LE(node, static_cast<float>(index));
    }
    appendPString16LE(node, "Mesh01");
    appendPString16LE(node, "Ref01");
    appendPString16LE(node, "Shader01");
    appendEntry(data, 0x72, 1, node);

    std::vector<std::uint8_t> shape;
    appendPString16LE(shape, "Shape01");
    appendPString16LE(shape, "RootNode");
    appendI32LE(shape, 3);
    for (int index = 16; index < 32; ++index) {
        appendF32LE(shape, static_cast<float>(index));
    }
    shape.insert(shape.end(), {0xAA, 0xBB});
    appendEntry(data, 0x74, 2, shape);

    std::vector<std::uint8_t> mesh;
    appendPString16LE(mesh, "Mesh01");
    appendI32LE(mesh, 4);
    appendI32LE(mesh, 2);
    mesh.push_back(0xCC);
    appendEntry(data, 0x73, 2, mesh);

    std::vector<std::uint8_t> texture;
    appendPString16LE(texture, "Tex01");
    texture.push_back(0x01);
    texture.insert(texture.end(), {0xFF, 0xD8, 0x00});
    appendEntry(data, 0x21, 2, texture);

    std::vector<std::uint8_t> material;
    appendPString16LE(material, "Mat01");
    material.insert(material.end(), {0x10, 0x20});
    appendEntry(data, 0x49, 2, material);

    std::vector<std::uint8_t> resourceRef;
    appendPString16LE(resourceRef, "Ref01");
    appendI32LE(resourceRef, 9);
    resourceRef.push_back(0x44);
    appendEntry(data, 0x48, 2, resourceRef);

    appendEntry(data, 0x9999, 0, {0x55});

    W3DFile file = W3DFile::load(data);
    assert(file.version() == 0x01020304);
    assert(file.entries().size() == 8);
    assert(file.entries()[1].type == W3DEntryType::Node);
    assert(file.entries()[1].parentRef == 1);
    assert(file.entries()[7].type == W3DEntryType::Unknown);

    assert(file.nodes().size() == 1);
    assert(file.nodes()[0].name == "RootNode");
    assert(file.nodes()[0].parentName == "Scene");
    assert(file.nodes()[0].flags == 7);
    assert(file.nodes()[0].transform.has_value());
    assert(std::fabs(file.nodes()[0].posX() - 12.0F) < 0.0001F);
    assert(std::fabs(file.nodes()[0].posY() - 13.0F) < 0.0001F);
    assert(std::fabs(file.nodes()[0].posZ() - 14.0F) < 0.0001F);
    assert(file.nodes()[0].resourceName == "Mesh01");
    assert(file.nodes()[0].refName == "Ref01");
    assert(file.nodes()[0].shaderName == "Shader01");

    assert(file.shapes().size() == 1);
    assert(file.shapes()[0].name == "Shape01");
    assert(file.shapes()[0].shapeData.size() == 2);
    assert(file.meshResources().size() == 1);
    assert(file.meshResources()[0].vertexCount == 4);
    assert(file.meshResources()[0].faceCount == 2);
    assert(file.meshResources()[0].geometryData[0] == 0xCC);
    assert(file.textures().size() == 1);
    assert(file.textures()[0].format == "jpeg");
    assert(file.materials().size() == 1);
    assert(file.materials()[0].materialData.size() == 2);
    assert(file.resourceRefs().size() == 1);
    assert(file.resourceRefs()[0].refType == 9);

    assert(file.findNode("RootNode").has_value());
    assert(!file.findNode("Missing").has_value());
    assert(file.findTexture("Tex01")->format == "jpeg");
    assert(!file.findTexture("Missing").has_value());

    const auto tmpPath = std::filesystem::temp_directory_path() / "libreshockwave_w3d_file_parser_test.w3d";
    {
        std::ofstream output(tmpPath, std::ios::binary);
        output.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    }
    W3DFile pathLoaded = W3DFile::load(tmpPath);
    assert(pathLoaded.version() == file.version());
    assert(pathLoaded.entries().size() == file.entries().size());
    std::filesystem::remove(tmpPath);

    assert(libreshockwave::w3d::W3DNode::parse({1, 2, 3}).name.empty());
    assert(libreshockwave::w3d::W3DTexture::parse({1, 2, 3}).format == "raw");
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

    auto bitmapMemberPtr = std::make_shared<CastMemberChunk>(bitmapMember);
    CastMember parsedBitmap(30, 1, 1, bitmapMemberPtr);
    assert(parsedBitmap.id() == 30);
    assert(parsedBitmap.castLib() == 1);
    assert(parsedBitmap.memberNum() == 1);
    assert(parsedBitmap.memberType() == MemberType::Bitmap);
    assert(parsedBitmap.name() == "BitmapName");
    assert(parsedBitmap.scriptId() == 77);
    assert(parsedBitmap.rawChunk() == bitmapMemberPtr);
    assert(parsedBitmap.isBitmap());
    assert(!parsedBitmap.isScript());
    assert(parsedBitmap.bitmapInfo().has_value());
    assert(parsedBitmap.width() == 16);
    assert(parsedBitmap.height() == 16);
    assert(parsedBitmap.regX() == 8);
    assert(parsedBitmap.regY() == 7);
    assert(parsedBitmap.toString().find("BitmapName") != std::string::npos);

    auto scriptMemberPtr = std::make_shared<CastMemberChunk>(
        nullptr,
        ChunkId(120),
        MemberType::Script,
        0,
        2,
        std::vector<std::uint8_t>{},
        std::vector<std::uint8_t>{0x00, 0x03},
        "MovieScript",
        0,
        0,
        0);
    CastMember parsedScript(120, 1, 2, scriptMemberPtr);
    assert(parsedScript.isScript());
    assert(parsedScript.scriptType().has_value());
    assert(parsedScript.scriptType().value() == ScriptType::Movie);
    assert(parsedScript.width() == 0);
    assert(parsedScript.toString().find("scriptType=movie") != std::string::npos);

    const std::vector<std::uint8_t> shapeSpecific{
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
    auto shapeMemberPtr = std::make_shared<CastMemberChunk>(
        nullptr,
        ChunkId(121),
        MemberType::Shape,
        0,
        static_cast<int>(shapeSpecific.size()),
        std::vector<std::uint8_t>{},
        shapeSpecific,
        "Shape",
        0,
        0,
        0);
    CastMember parsedShape(121, 1, 3, shapeMemberPtr);
    assert(parsedShape.isShape());
    assert(parsedShape.shapeInfo().has_value());
    assert(parsedShape.width() == 57);
    assert(parsedShape.height() == 57);
    assert(parsedShape.regX() == 0);
    assert(parsedShape.regY() == 0);
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

    std::vector<std::string> names(15);
    names[5] = "mouseUp";
    names[9] = "localName";
    names[12] = "firstProperty";
    names[13] = "secondProperty";
    names[14] = "sharedGlobal";
    ScriptNamesChunk scriptNames(nullptr, ChunkId(35), names);
    assert(script.getHandlerName(handler, &scriptNames) == "mouseUp");
    assert(script.getHandlerName(handler, nullptr) == "handler#5");
    assert(script.resolveName(9, &scriptNames) == "localName");
    assert(script.resolveName(9, nullptr) == "#9");
    assert(script.findHandler("MOUSEUP", &scriptNames).has_value());
    assert(!script.findHandler("missing", &scriptNames).has_value());
    const auto propertyNames = script.getPropertyNames(&scriptNames);
    assert(propertyNames.size() == 2);
    assert(propertyNames[0] == "firstProperty");
    assert(propertyNames[1] == "secondProperty");
    const auto globalNames = script.getGlobalNames(&scriptNames);
    assert(globalNames.size() == 1);
    assert(globalNames[0] == "sharedGlobal");
    assert(script.getPropertyNames(nullptr).empty());
    assert(script.getGlobalNames(nullptr).empty());
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
    appendI32(keyData, 4);
    appendI32(keyData, 4);
    appendI32(keyData, 4);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("STXT"));
    appendI32(keyData, 5);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("VWSC"));
    appendI32(keyData, 6);
    appendI32(keyData, 9);
    appendI32(keyData, BinaryReader::fourCC("BITD"));
    appendI32(keyData, 7);
    appendI32(keyData, 7);
    appendI32(keyData, BinaryReader::fourCC("snd "));
    std::vector<std::uint8_t> textData;
    appendI32(textData, 8);
    appendI32(textData, 5);
    textData.insert(textData.end(), {'H', 'e', 'l', 'l', 'o'});
    const std::vector<std::uint8_t> scoreData;
    const std::vector<std::uint8_t> bitdData{0, 1};
    std::vector<std::uint8_t> soundChunkData(66, 0);
    soundChunkData[0x16] = 0x56;
    soundChunkData[0x17] = 0x22;
    soundChunkData[64] = 0x12;
    soundChunkData[65] = 0x34;

    constexpr int mmapOffset = 32;
    constexpr int mmapDataStart = mmapOffset + 8 + 24 + 160;
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
    const int bitdDataStart = scoreDataStart + static_cast<int>(scoreData.size());
    const int bitdOffset = bitdDataStart - 8;
    const int soundDataStart = bitdDataStart + static_cast<int>(bitdData.size());
    const int soundOffset = soundDataStart - 8;

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
    appendI32(fileData, 24 + 160);
    appendI16(fileData, 24);
    appendI16(fileData, 20);
    appendI32(fileData, 8);
    appendI32(fileData, 8);
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
    appendI32(fileData, BinaryReader::fourCC("BITD"));
    appendI32(fileData, static_cast<std::uint32_t>(bitdData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(bitdOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    appendI32(fileData, BinaryReader::fourCC("snd "));
    appendI32(fileData, static_cast<std::uint32_t>(soundChunkData.size()));
    appendI32(fileData, static_cast<std::uint32_t>(soundOffset));
    appendI16(fileData, 0);
    appendI16(fileData, 0);
    appendI32(fileData, 0);
    fileData.insert(fileData.end(), configData.begin(), configData.end());
    fileData.insert(fileData.end(), namesData.begin(), namesData.end());
    fileData.insert(fileData.end(), rawData.begin(), rawData.end());
    fileData.insert(fileData.end(), keyData.begin(), keyData.end());
    fileData.insert(fileData.end(), textData.begin(), textData.end());
    fileData.insert(fileData.end(), scoreData.begin(), scoreData.end());
    fileData.insert(fileData.end(), bitdData.begin(), bitdData.end());
    fileData.insert(fileData.end(), soundChunkData.begin(), soundChunkData.end());
    putI32(fileData, 4, static_cast<std::uint32_t>(fileData.size() - 8));

    auto file = DirectorFile::load(fileData);
    assert(file->endian() == ByteOrder::BigEndian);
    assert(!file->isAfterburner());
    assert(file->movieType() == ChunkType::MV93);
    assert(file->version() == 0x0207);
    assert(file->chunkInfo().size() == 8);
    assert(file->chunks().size() == 8);
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
    assert(!file->hasScore());
    assert(file->basePath().empty());
    file->setBasePath("casts");
    assert(file->basePath() == "casts");
    assert(file->getChunkInfo(ChunkId(0))->type() == ChunkType::DRCF);
    assert(file->getChunk(ChunkId(1))->type() == ChunkType::Lnam);
    assert(file->getChunk(ChunkId(2))->type() == ChunkType::JUNK);
    assert(file->getChunk(ChunkId(3))->type() == ChunkType::KEYp);
    assert(file->getChunk(ChunkId(4))->type() == ChunkType::STXT);
    assert(file->getChunk(ChunkId(5))->type() == ChunkType::VWSC);
    assert(file->getChunk(ChunkId(6))->type() == ChunkType::BITD);
    assert(file->getChunk(ChunkId(7))->type() == ChunkType::snd_);
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
    auto memberSound = SoundManager::findSoundForMember(*file, member7);
    assert(memberSound != nullptr);
    assert(memberSound->sampleRate() == 22050);
    auto memberPlayable = SoundManager::convertSoundToPlayable(*memberSound);
    assert(memberPlayable.has_value());
    assert(SoundManager::detectFormat(*memberPlayable) == "wav");
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
    std::vector<std::uint8_t> bitmapSpecific;
    appendI16(bitmapSpecific, 2);
    appendI16(bitmapSpecific, 0);
    appendI16(bitmapSpecific, 0);
    appendI16(bitmapSpecific, 1);
    appendI16(bitmapSpecific, 2);
    bitmapSpecific.insert(bitmapSpecific.end(), 8, 0);
    appendI16(bitmapSpecific, 0);
    appendI16(bitmapSpecific, 0);
    bitmapSpecific.push_back(0);
    bitmapSpecific.push_back(8);
    appendI16(bitmapSpecific, 1);
    auto bitmapMember = std::make_shared<CastMemberChunk>(file.get(),
                                                          ChunkId(9),
                                                          MemberType::Bitmap,
                                                          0,
                                                          static_cast<int>(bitmapSpecific.size()),
                                                          std::vector<std::uint8_t>{},
                                                          bitmapSpecific,
                                                          "Bitmap",
                                                          0,
                                                          0,
                                                          0);
    Palette decodePalette({0x010203U, 0xA0B0C0U}, "decode");
    auto decodedBitmap = file->decodeBitmap(bitmapMember, &decodePalette);
    assert(decodedBitmap.has_value());
    assert(decodedBitmap->width() == 2);
    assert(decodedBitmap->height() == 1);
    assert(decodedBitmap->bitDepth() == 8);
    assert(decodedBitmap->getPixel(0, 0) == 0xFF010203U);
    assert(decodedBitmap->getPixel(1, 0) == 0xFFA0B0C0U);
    assert(decodedBitmap->paletteIndex(1, 0).value() == 1);
    assert(decodedBitmap->imagePalette().get() == &decodePalette);
    file->releaseNonEssentialChunks();
    assert(file->chunks().size() == 6);
    auto reparsedRaw = file->getChunk(ChunkId(2));
    assert(reparsedRaw.get() != nullptr);
    assert(reparsedRaw->type() == ChunkType::JUNK);
    assert(file->chunks().size() == 7);
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
    assert(emptyFile.getScriptForCastMember(nullptr).get() == nullptr);
    assert(!emptyFile.getScriptType(nullptr).has_value());
    assert(emptyFile.getScriptName(nullptr).empty());
    assert(emptyFile.getScriptNamesById(ChunkId(1)).get() == nullptr);
    assert(emptyFile.getScriptNamesForScript(nullptr).get() == nullptr);
    assert(emptyFile.getAllGlobalNames().empty());
    assert(emptyFile.getAllPropertyNames().empty());
    assert(emptyFile.getScriptGlobals(nullptr).empty());
    assert(emptyFile.getScriptProperties(nullptr).empty());
    assert(emptyFile.getScriptInfoList().empty());
    assert(emptyFile.getExternalCastPaths().empty());
    assert(!emptyFile.hasExternalCasts());
    assert(!emptyFile.hasScore());
    assert(!emptyFile.getFontNameForId(1).has_value());
    assert(emptyFile.basePath().empty());
    emptyFile.setBasePath("movie-dir");
    assert(emptyFile.basePath() == "movie-dir");
    assert(emptyFile.resolvePalette(Palette::SYSTEM_WIN).get() == &Palette::systemWinPalette());
    assert(emptyFile.resolvePaletteExact(999).get() == nullptr);
    assert(emptyFile.resolvePaletteByMemberNumber(42).get() == &Palette::systemMacPalette());
}

int main() {
    testBinaryReaderEndianAndBounds();
    testBinaryReaderStringsAndFourCC();
    testBinaryReaderNumbers();
    testBinaryReaderZlib();
    testFontDataDecoder();
    testPfrBitReader();
    testIdsAndEnums();
    testFormatTypes();
    testUtilityFormatting();
    testLingoDatumTypes();
    testLingoOpcodeHelpers();
    testPlayerCoreFoundation();
    testPlayerInputFoundation();
    testMoviePropertiesFoundation();
    testBuiltinRegistryFoundation();
    testLingoVmScopeAndExecutionContextFoundation();
    testHitTesterFoundation();
    testCursorManagerFoundation();
    testScoreNavigationFoundation();
    testSpriteStateFoundation();
    testSpriteRegistryFoundation();
    testSpritePropertiesFoundation();
    testBehaviorFoundation();
    testEventDispatcherFoundation();
    testDebugFoundation();
    testRenderPipelineFoundation();
    testBitmapCacheAndInkProcessorFoundation();
    testSoftwareFrameRenderer();
    testTextRendererFoundation();
    testNetTaskFoundation();
    testNetManagerFoundation();
    testTimeoutManagerFoundation();
    testPaletteAndColorRefs();
    testBitmapAlphaAndPaletteBehavior();
    testBitmapRegionsAndMetadata();
    testBitmapDecoder();
    testBitmapColorizer();
    testBasicChunks();
    testAudioAndMediaChunks();
    testSoundConverter();
    testSoundManagerFoundation();
    testCastMetadataTypes();
    testCastInfoParsers();
    testShockwave3DInfoParser();
    testW3DFileParser();
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
