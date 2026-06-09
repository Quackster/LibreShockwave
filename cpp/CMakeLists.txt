add_library(libreshockwave
    src/bitmap/Bitmap.cpp
    src/bitmap/ColorRef.cpp
    src/bitmap/Palette.cpp
    src/chunks/BitmapChunk.cpp
    src/chunks/MediaChunk.cpp
    src/chunks/PaletteChunk.cpp
    src/chunks/RawChunk.cpp
    src/chunks/SoundChunk.cpp
    src/format/ChunkType.cpp
    src/format/MoaID.cpp
    src/io/BinaryReader.cpp
    src/lingo/Datum.cpp
    src/util/AudioCodecUtils.cpp
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
