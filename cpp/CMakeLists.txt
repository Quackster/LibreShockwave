add_library(libreshockwave
    src/DirectorFile.cpp
    src/W3DFile.cpp
    src/audio/SoundConverter.cpp
    src/bitmap/Bitmap.cpp
    src/bitmap/BitmapColorizer.cpp
    src/bitmap/BitmapDecoder.cpp
    src/bitmap/ColorRef.cpp
    src/bitmap/Palette.cpp
    src/cast/BitmapInfo.cpp
    src/cast/CastMember.cpp
    src/cast/FilmLoopInfo.cpp
    src/cast/MemberType.cpp
    src/cast/ScriptType.cpp
    src/cast/ShapeInfo.cpp
    src/cast/Shockwave3DInfo.cpp
    src/cast/TextInfo.cpp
    src/cast/XmedStyledText.cpp
    src/chunks/BitmapChunk.cpp
    src/chunks/CastChunk.cpp
    src/chunks/CastListChunk.cpp
    src/chunks/CastMemberChunk.cpp
    src/chunks/ConfigChunk.cpp
    src/chunks/FontMapChunk.cpp
    src/chunks/FrameLabelsChunk.cpp
    src/chunks/KeyTableChunk.cpp
    src/chunks/MediaChunk.cpp
    src/chunks/PaletteChunk.cpp
    src/chunks/RawChunk.cpp
    src/chunks/ScriptContextChunk.cpp
    src/chunks/ScriptChunk.cpp
    src/chunks/ScriptNamesChunk.cpp
    src/chunks/ScoreChunk.cpp
    src/chunks/SoundChunk.cpp
    src/chunks/TextChunk.cpp
    src/format/AfterburnerReader.cpp
    src/format/ChunkType.cpp
    src/format/MoaID.cpp
    src/format/ScriptFormatUtils.cpp
    src/font/PfrBitReader.cpp
    src/fonts/FontDataDecoder.cpp
    src/io/BinaryReader.cpp
    src/lingo/Datum.cpp
    src/lingo/Opcode.cpp
    src/lingo/builtin/BuiltinRegistry.cpp
    src/lingo/vm/ExecutionContext.cpp
    src/lingo/vm/OpcodeRegistry.cpp
    src/lingo/vm/Scope.cpp
    src/lookup/CastMemberLookup.cpp
    src/lookup/PaletteResolver.cpp
    src/lookup/ScriptLookup.cpp
    src/player/PlayerEvent.cpp
    src/player/PlayerState.cpp
    src/player/CursorManager.cpp
    src/player/MovieProperties.cpp
    src/player/SpriteProperties.cpp
    src/player/audio/SoundManager.cpp
    src/player/behavior/BehaviorInstance.cpp
    src/player/behavior/BehaviorManager.cpp
    src/player/input/DirectorKeyCodes.cpp
    src/player/input/HitTester.cpp
    src/player/input/InputEvent.cpp
    src/player/input/InputState.cpp
    src/player/net/NetManager.cpp
    src/player/net/NetTask.cpp
    src/player/debug/Breakpoint.cpp
    src/player/debug/BreakpointManager.cpp
    src/player/debug/WatchExpression.cpp
    src/player/event/EventDispatcher.cpp
    src/player/render/RenderConfig.cpp
    src/player/render/RenderType.cpp
    src/player/render/SpriteRegistry.cpp
    src/player/render/output/SoftwareFrameRenderer.cpp
    src/player/render/output/TextRenderer.cpp
    src/player/render/pipeline/BitmapCache.cpp
    src/player/render/pipeline/FrameRenderPipeline.cpp
    src/player/render/pipeline/FrameSnapshot.cpp
    src/player/render/pipeline/FrameRenderPipelineContext.cpp
    src/player/render/pipeline/InkProcessor.cpp
    src/player/render/pipeline/RenderPipelineTrace.cpp
    src/player/render/pipeline/RenderSprite.cpp
    src/player/score/ScoreBehaviorRef.cpp
    src/player/score/ScoreNavigator.cpp
    src/player/score/SpriteSpan.cpp
    src/player/sprite/SpriteState.cpp
    src/player/timeout/TimeoutManager.cpp
    src/util/AudioCodecUtils.cpp
    src/util/FileUtil.cpp
    src/w3d/W3DEntry.cpp
    src/w3d/W3DEntryType.cpp
    src/w3d/W3DMaterial.cpp
    src/w3d/W3DMeshResource.cpp
    src/w3d/W3DNode.cpp
    src/w3d/W3DResourceRef.cpp
    src/w3d/W3DShape.cpp
    src/w3d/W3DTexture.cpp
)

add_library(LibreShockwave::libreshockwave ALIAS libreshockwave)

target_include_directories(libreshockwave
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

target_compile_features(libreshockwave PUBLIC cxx_std_20)

find_package(ZLIB QUIET)
if(ZLIB_FOUND)
    target_link_libraries(libreshockwave PUBLIC ZLIB::ZLIB)
    target_compile_definitions(libreshockwave PUBLIC LIBRESHOCKWAVE_HAVE_ZLIB=1)
endif()

if(BUILD_TESTING)
    add_executable(libreshockwave_tests
        tests/sdk_foundation_test.cpp
    )
    target_link_libraries(libreshockwave_tests PRIVATE LibreShockwave::libreshockwave)
    add_test(NAME libreshockwave_cpp_tests COMMAND libreshockwave_tests)
endif()
