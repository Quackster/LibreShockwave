set(LIBRESHOCKWAVE_VOLTER_DATA_CPP "${CMAKE_CURRENT_BINARY_DIR}/generated/src/fonts/volter/VolterData.cpp")
set(LIBRESHOCKWAVE_VOLTER_RESOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/resources/fonts/volter")
add_custom_command(
    OUTPUT "${LIBRESHOCKWAVE_VOLTER_DATA_CPP}"
    COMMAND "${CMAKE_COMMAND}"
        "-DINPUT_REGULAR=${LIBRESHOCKWAVE_VOLTER_RESOURCE_DIR}/volter.ttf"
        "-DINPUT_BOLD=${LIBRESHOCKWAVE_VOLTER_RESOURCE_DIR}/volter_bold.ttf"
        "-DOUTPUT_FILE=${LIBRESHOCKWAVE_VOLTER_DATA_CPP}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateVolterData.cmake"
    DEPENDS
        "${LIBRESHOCKWAVE_VOLTER_RESOURCE_DIR}/volter.ttf"
        "${LIBRESHOCKWAVE_VOLTER_RESOURCE_DIR}/volter_bold.ttf"
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateVolterData.cmake"
    VERBATIM
)
set_source_files_properties("${LIBRESHOCKWAVE_VOLTER_DATA_CPP}" PROPERTIES GENERATED TRUE)

set(LIBRESHOCKWAVE_PLATFORM_FONT_DATA_CPP "${CMAKE_CURRENT_BINARY_DIR}/generated/src/fonts/PlatformFontData.cpp")
set(LIBRESHOCKWAVE_PLATFORM_FONT_RESOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/resources/fonts")
file(GLOB LIBRESHOCKWAVE_PLATFORM_FONT_FILES CONFIGURE_DEPENDS
    "${LIBRESHOCKWAVE_PLATFORM_FONT_RESOURCE_DIR}/mac/*.ttf"
    "${LIBRESHOCKWAVE_PLATFORM_FONT_RESOURCE_DIR}/windows/*.ttf"
)
add_custom_command(
    OUTPUT "${LIBRESHOCKWAVE_PLATFORM_FONT_DATA_CPP}"
    COMMAND "${CMAKE_COMMAND}"
        "-DMAC_FONT_DIR=${LIBRESHOCKWAVE_PLATFORM_FONT_RESOURCE_DIR}/mac"
        "-DWINDOWS_FONT_DIR=${LIBRESHOCKWAVE_PLATFORM_FONT_RESOURCE_DIR}/windows"
        "-DOUTPUT_FILE=${LIBRESHOCKWAVE_PLATFORM_FONT_DATA_CPP}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GeneratePlatformFontData.cmake"
    DEPENDS
        ${LIBRESHOCKWAVE_PLATFORM_FONT_FILES}
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GeneratePlatformFontData.cmake"
    VERBATIM
)
set_source_files_properties("${LIBRESHOCKWAVE_PLATFORM_FONT_DATA_CPP}" PROPERTIES GENERATED TRUE)

option(LIBRESHOCKWAVE_BUILD_EDITOR "Build experimental editor-only models and GTK shell" OFF)

set(LIBRESHOCKWAVE_EDITOR_SOURCES
    src/editor/AppModels.cpp
    src/editor/EditorContextModels.cpp
    src/editor/EditorFrameModels.cpp
    src/editor/EditorShellModels.cpp
    src/editor/audio/EditorAudioModels.cpp
    src/editor/cast/CastBrowserModels.cpp
    src/editor/debug/DebugBrowserModels.cpp
    src/editor/debug/DebugCommandModels.cpp
    src/editor/debug/DebugDisplayItems.cpp
    src/editor/debug/DebugInspectionModels.cpp
    src/editor/debug/DebugTableModels.cpp
    src/editor/docking/DockingModels.cpp
    src/editor/extraction/AssetExtractor.cpp
    src/editor/extraction/ExportHandlerModels.cpp
    src/editor/format/ChannelNames.cpp
    src/editor/format/InstructionFormatter.cpp
    src/editor/format/PaletteDescriptions.cpp
    src/editor/gtk/EditorGtkModels.cpp
    src/editor/model/BitmapKey.cpp
    src/editor/model/FileNode.cpp
    src/editor/model/MemberNodeData.cpp
    src/editor/model/ScoreCellData.cpp
    src/editor/panels/EditorPanelCatalog.cpp
    src/editor/panels/EditorPanelModels.cpp
    src/editor/preview/GenericPreview.cpp
    src/editor/preview/PalettePreview.cpp
    src/editor/preview/PreviewContextModels.cpp
    src/editor/preview/PreviewFormatUtils.cpp
    src/editor/preview/ScriptPreview.cpp
    src/editor/preview/SoundPreview.cpp
    src/editor/preview/TextPreview.cpp
    src/editor/property/PropertyInspectorModels.cpp
    src/editor/selection/SelectionEvent.cpp
    src/editor/selection/SelectionManager.cpp
    src/editor/score/FrameAppearanceFinder.cpp
    src/editor/score/PlaybackHead.cpp
    src/editor/score/ScoreDataBuilder.cpp
    src/editor/score/ScoreColors.cpp
    src/editor/score/ScoreModel.cpp
    src/editor/score/ScoreViewModels.cpp
    src/editor/scanning/FileProcessor.cpp
    src/editor/scanning/MemberResolver.cpp
    src/editor/script/LingoKeywords.cpp
    src/editor/script/ScriptEditorModels.cpp
    src/editor/script/LingoTokenizer.cpp
    src/editor/stage/StageViewModels.cpp
    src/editor/ui/EditorRendererModels.cpp
)

add_library(libreshockwave
    src/DirectorFile.cpp
    src/W3DFile.cpp
    src/audio/SoundConverter.cpp
    src/bitmap/Bitmap.cpp
    src/bitmap/BitmapColorizer.cpp
    src/bitmap/BitmapDecoder.cpp
    src/bitmap/ColorRef.cpp
    src/bitmap/Drawing.cpp
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
    src/cast/XmedTextParser.cpp
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
    src/font/BdfParser.cpp
    src/font/BitmapFont.cpp
    src/font/PfrBitReader.cpp
    src/font/Pfr1Font.cpp
    src/font/Pfr1TtfConverter.cpp
    src/font/TtfBitmapRasterizer.cpp
    src/fonts/FontDataDecoder.cpp
    "${LIBRESHOCKWAVE_VOLTER_DATA_CPP}"
    "${LIBRESHOCKWAVE_PLATFORM_FONT_DATA_CPP}"
    src/io/BinaryReader.cpp
    src/lingo/Datum.cpp
    src/lingo/LingoValueParser.cpp
    src/lingo/Opcode.cpp
    src/lingo/builtin/BuiltinRegistry.cpp
    src/lingo/decompiler/LingoDecompiler.cpp
    src/lingo/decompiler/LingoNode.cpp
    src/lingo/decompiler/LingoProperties.cpp
    src/lingo/vm/AlertHookHandler.cpp
    src/lingo/vm/DebugConfig.cpp
    src/lingo/vm/datum/DatumFormatter.cpp
    src/lingo/vm/ExecutionContext.cpp
    src/lingo/vm/LingoVM.cpp
    src/lingo/vm/OpcodeRegistry.cpp
    src/lingo/vm/PropertyIdMappings.cpp
    src/lingo/vm/Scope.cpp
    src/lingo/vm/dispatch/ListMethodDispatcher.cpp
    src/lingo/vm/dispatch/PropListMethodDispatcher.cpp
    src/lingo/vm/dispatch/SoundChannelMethodDispatcher.cpp
    src/lingo/vm/dispatch/StringMethodDispatcher.cpp
    src/lingo/vm/parse/LingoExpressionParser.cpp
    src/lingo/vm/trace/ConsoleTracePrinter.cpp
    src/lingo/vm/trace/InstructionAnnotator.cpp
    src/lingo/vm/trace/TracingHelper.cpp
    src/lingo/vm/util/AncestorChainWalker.cpp
    src/lingo/vm/util/StringChunkUtils.cpp
    src/lingo/xtra/MultiuserXtra.cpp
    src/lingo/xtra/XtraManager.cpp
    src/lingo/xtra/XmlParserXtra.cpp
    src/lookup/CastMemberLookup.cpp
    src/lookup/PaletteResolver.cpp
    src/lookup/ScriptLookup.cpp
    src/player/PlayerEvent.cpp
    src/player/PlayerState.cpp
    src/player/Player.cpp
    src/player/BitmapResolver.cpp
    src/player/CursorManager.cpp
    src/player/InputHandler.cpp
    src/player/MovieProperties.cpp
    src/player/SpriteProperties.cpp
    src/player/audio/QueuedAudioBackend.cpp
    src/player/audio/SoundManager.cpp
    src/player/media/QueuedJpegDecoder.cpp
    src/player/behavior/BehaviorInstance.cpp
    src/player/behavior/BehaviorManager.cpp
    src/player/cast/CastLib.cpp
    src/player/cast/CastLibManager.cpp
    src/player/cast/FontRegistry.cpp
    src/player/cast/MacFontBundle.cpp
    src/player/cast/WindowsFontBundle.cpp
    src/player/input/DirectorKeyCodes.cpp
    src/player/input/HitTester.cpp
    src/player/input/InputEvent.cpp
    src/player/input/InputState.cpp
    src/player/net/NetManager.cpp
    src/player/net/NetTask.cpp
    src/player/net/QueuedNetProvider.cpp
    src/player/debug/Breakpoint.cpp
    src/player/debug/BreakpointManager.cpp
    src/player/debug/DebugController.cpp
    src/player/debug/ExpressionEvaluator.cpp
    src/player/debug/LifecycleDiagnostics.cpp
    src/player/debug/WatchExpression.cpp
    src/player/event/EventDispatcher.cpp
    src/player/frame/FrameContext.cpp
    src/player/render/RenderConfig.cpp
    src/player/render/RenderType.cpp
    src/player/render/SpriteRegistry.cpp
    src/player/render/output/SimpleTextRenderer.cpp
    src/player/render/output/SoftwareFrameRenderer.cpp
    src/player/render/output/TextRenderer.cpp
    src/player/render/pipeline/BitmapCache.cpp
    src/player/render/pipeline/FrameRenderPipeline.cpp
    src/player/render/pipeline/FrameSnapshot.cpp
    src/player/render/pipeline/FrameRenderPipelineContext.cpp
    src/player/render/pipeline/InkProcessor.cpp
    src/player/render/pipeline/RenderPipelineTrace.cpp
    src/player/render/pipeline/RenderSprite.cpp
    src/player/render/pipeline/SpriteBaker.cpp
    src/player/render/pipeline/StageRenderer.cpp
    src/player/score/ScoreBehaviorRef.cpp
    src/player/score/ScoreNavigator.cpp
    src/player/score/SpriteSpan.cpp
    src/player/sprite/SpriteState.cpp
    src/player/timeout/TimeoutManager.cpp
    src/player/web/WasmExports.cpp
    src/player/web/WasmPlayer.cpp
    src/player/web/WasmRuntime.cpp
    src/player/xtra/QueuedMultiuserBridge.cpp
    src/player/xtra/SocketMultiuserBridge.cpp
    src/util/AudioCodecUtils.cpp
    src/util/FileUtil.cpp
    src/util/StringUtils.cpp
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

if(LIBRESHOCKWAVE_BUILD_EDITOR)
    target_sources(libreshockwave PRIVATE ${LIBRESHOCKWAVE_EDITOR_SOURCES})
    target_compile_definitions(libreshockwave PUBLIC LIBRESHOCKWAVE_BUILD_EDITOR=1)
endif()

if(EMSCRIPTEN)
    target_compile_options(libreshockwave PUBLIC "-fexceptions" "-sUSE_ZLIB=1")
    target_compile_definitions(libreshockwave PUBLIC LIBRESHOCKWAVE_HAVE_ZLIB=1)
    target_link_options(libreshockwave INTERFACE "-fexceptions" "-sUSE_ZLIB=1")
endif()

add_executable(libreshockwave_probe
    tools/DirectorProbe.cpp
)
target_link_libraries(libreshockwave_probe PRIVATE LibreShockwave::libreshockwave)
target_compile_features(libreshockwave_probe PRIVATE cxx_std_20)

add_executable(libreshockwave_render_probe
    tools/RenderProbe.cpp
)
target_link_libraries(libreshockwave_render_probe PRIVATE LibreShockwave::libreshockwave)
target_compile_features(libreshockwave_render_probe PRIVATE cxx_std_20)

add_executable(libreshockwave_wasm_bridge_probe
    tools/WasmBridgeProbe.cpp
)
target_link_libraries(libreshockwave_wasm_bridge_probe PRIVATE LibreShockwave::libreshockwave)
target_compile_features(libreshockwave_wasm_bridge_probe PRIVATE cxx_std_20)

find_package(Threads REQUIRED)
target_link_libraries(libreshockwave PUBLIC Threads::Threads)

find_package(ZLIB QUIET)
if(ZLIB_FOUND)
    target_link_libraries(libreshockwave PUBLIC ZLIB::ZLIB)
    target_compile_definitions(libreshockwave PUBLIC LIBRESHOCKWAVE_HAVE_ZLIB=1)
endif()

if(LIBRESHOCKWAVE_BUILD_EDITOR)
    find_package(PkgConfig QUIET)
    if(PkgConfig_FOUND)
        pkg_check_modules(GTK4 QUIET IMPORTED_TARGET gtk4)
    endif()

    if(GTK4_FOUND)
        add_executable(libreshockwave_editor_gtk
            src/editor/gtk/EditorGtkMain.cpp
        )
        target_link_libraries(libreshockwave_editor_gtk
            PRIVATE
                LibreShockwave::libreshockwave
                PkgConfig::GTK4
        )
        target_compile_features(libreshockwave_editor_gtk PRIVATE cxx_std_20)
    else()
        message(STATUS "GTK4 not found; libreshockwave_editor_gtk target disabled. Install gtk4-devel to build it.")
    endif()
endif()

if(EMSCRIPTEN)
    set(LIBRESHOCKWAVE_CPP_WASM_WEB_ASSETS
        "${CMAKE_CURRENT_SOURCE_DIR}/web/index.html"
        "${CMAKE_CURRENT_SOURCE_DIR}/web/libreshockwave-cpp-player.js"
        "${CMAKE_CURRENT_SOURCE_DIR}/web/libreshockwave-cpp-worker.js"
    )

    add_executable(libreshockwave_cpp_wasm
        src/player/web/WasmModule.cpp
    )
    target_link_libraries(libreshockwave_cpp_wasm
        PRIVATE
            LibreShockwave::libreshockwave
    )
    target_compile_features(libreshockwave_cpp_wasm PRIVATE cxx_std_20)
    set_target_properties(libreshockwave_cpp_wasm PROPERTIES
        OUTPUT_NAME "libreshockwave-cpp-wasm"
        SUFFIX ".js"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/wasm-dist"
    )
    target_link_options(libreshockwave_cpp_wasm PRIVATE
        "--no-entry"
        "-sWASM=1"
        "-sMODULARIZE=1"
        "-sEXPORT_NAME=createLibreShockwaveCppWasm"
        "-sENVIRONMENT=worker"
        "-sALLOW_MEMORY_GROWTH=1"
        "-sSTACK_SIZE=8388608"
        "-sEXPORTED_FUNCTIONS=@${CMAKE_CURRENT_SOURCE_DIR}/cmake/libreshockwave-wasm-exports.json"
    )

    set(LIBRESHOCKWAVE_CPP_WASM_WEB_OUTPUTS)
    foreach(LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET IN LISTS LIBRESHOCKWAVE_CPP_WASM_WEB_ASSETS)
        get_filename_component(LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_NAME
            "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET}"
            NAME
        )
        set(LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_OUTPUT
            "${CMAKE_CURRENT_BINARY_DIR}/wasm-dist/${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_NAME}"
        )
        add_custom_command(
            OUTPUT "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_OUTPUT}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory
                "${CMAKE_CURRENT_BINARY_DIR}/wasm-dist"
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET}"
                "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_OUTPUT}"
            DEPENDS "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET}"
            VERBATIM
        )
        list(APPEND LIBRESHOCKWAVE_CPP_WASM_WEB_OUTPUTS
            "${LIBRESHOCKWAVE_CPP_WASM_WEB_ASSET_OUTPUT}"
        )
    endforeach()
    add_custom_target(libreshockwave_cpp_wasm_web_assets
        DEPENDS ${LIBRESHOCKWAVE_CPP_WASM_WEB_OUTPUTS}
    )
    add_dependencies(libreshockwave_cpp_wasm libreshockwave_cpp_wasm_web_assets)
endif()

if(BUILD_TESTING)
    add_executable(libreshockwave_tests
        tests/sdk_foundation_test.cpp
    )
    target_link_libraries(libreshockwave_tests PRIVATE LibreShockwave::libreshockwave)
    if(LIBRESHOCKWAVE_BUILD_EDITOR)
        target_compile_definitions(libreshockwave_tests PRIVATE LIBRESHOCKWAVE_BUILD_EDITOR=1)
    endif()
    add_test(NAME libreshockwave_cpp_tests COMMAND libreshockwave_tests)
endif()
