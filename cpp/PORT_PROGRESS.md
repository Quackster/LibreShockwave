# LibreShockwave C++ Port Progress

Objective: port the entire LibreShockwave project to C++ while keeping the C++ tree buildable and tested after each slice.

## Current Status

Started. The Java/Gradle project remains the authoritative implementation for most behavior. A first-party CMake/C++ port now exists under `cpp/` and is being expanded from reusable SDK foundations upward.

## Completed C++ Slices

### Build Skeleton

- Added root `CMakeLists.txt`.
- Added `cpp/CMakeLists.txt`.
- Added `LibreShockwave::libreshockwave` static library target.
- Added `libreshockwave_cpp_tests` CTest executable.
- Added optional zlib integration through CMake.

### SDK Foundation

- Endian-aware `io::BinaryReader`.
- FourCC helpers.
- `format::ChunkType`, `format::ChunkInfo`, and `format::MoaID`.
- Typed IDs and enums in `id::Ids`.
- SDK Lingo datum foundation in `lingo::Datum`.

### Bitmap Foundation

- `bitmap::Palette` with System Mac, Rainbow, Grayscale, Metallic, and System Windows built-in palette data.
- `bitmap::ColorRef`.
- `bitmap::Bitmap` with ARGB pixel storage, palette index metadata, palette remapping/quantization, alpha helpers, region extraction, anchor metadata, and swatch generation.

### Chunk and Audio Foundation

- `chunks::Chunk` base interface with a forward-declared `DirectorFile` owner pointer.
- `chunks::RawChunk`.
- `chunks::PaletteChunk` CLUT reader.
- `chunks::BitmapChunk` BITD raw data reader and dimension metadata copy helper.
- `chunks::SoundChunk` reader with sample-rate detection, MP3 detection, sample count, and duration helper.
- `chunks::MediaChunk` reader with raw MP3 detection, header parsing, GUID-based IMA ADPCM detection, endian restoration, and `toSoundChunk`.
- `util::containsMp3SyncFrame`.

### Cast Metadata Foundation

- `cast::MemberType` and `cast::ScriptType`.
- `cast::StyledSpan` and `cast::XmedStyledText` helpers.
- `cast::BitmapInfo` parser for D4/D5 and D6+ bitmap specific data.
- `cast::TextInfo` parser for compact and D7+ text specific data.
- `cast::ShapeInfo` parser and shape helpers.
- `cast::FilmLoopInfo` parser and dimension/registration helpers.
- `cast::Shockwave3DInfo` parser for basic camera, color, flag, and Pascal string metadata.

### Compact Chunk Parsers

- `chunks::ConfigChunk` DRCF/VWCF parser for stage dimensions, stage color, tempo, platform, and default palette metadata.
- `chunks::FrameLabelsChunk` VWLB parser and label lookup helpers.
- `chunks::ScriptNamesChunk` Lnam parser and case-insensitive name lookup.
- `chunks::TextChunk` STXT parser with legacy and modern text-run layouts.
- `chunks::FontMapChunk` Fmap parser and font-id lookup.
- `chunks::CastChunk` CAS* cast member ID array parser.
- `chunks::KeyTableChunk` KEY* parser with owner/section lookups.

### Cast List and Member Chunks

- `chunks::CastListChunk` MCsL parser for cast library names, paths, preload settings, member ranges, and cast IDs.
- `chunks::CastMemberChunk` CASt parser for D5+ and D4 member layouts.
- Cast member helpers for bitmap/script/text/sound/Shockwave 3D type checks, text Xtra signature detection, and script type decoding.
- Member info list parsing for script ID and Pascal member name extraction.
- Bitmap member registration point extraction through `cast::BitmapInfo`.

## Verification

Last verified: 2026-06-10

Commands:

```bash
cmake --build cmake-build-debug --parallel
ctest --test-dir cmake-build-debug --output-on-failure
./gradlew test
```

Result:

- `libreshockwave_cpp_tests`: passed.
- zlib support was detected in the local CMake build and the zlib decompression path is covered by the C++ tests.
- Chunk/audio foundation tests passed through the same CTest executable.
- Cast metadata parser tests passed through the same CTest executable.
- Compact chunk parser tests passed through the same CTest executable.
- Cast list/member chunk parser tests passed through the same CTest executable.
- Full Gradle Java test baseline is not green at this checkpoint: `:player-core:test` fails in `ScriptModifiedBitmapTest.scriptModifiedIndexedDarkenUsesPaletteIndicesForSpriteColorRamp` with `expected 0xFF903F20`, actual `0xFF903E1F`. No Java files are changed in this checkpoint.

## Remaining Major Work

- Remaining SDK chunk model and chunk parsers.
- Director/Afterburner file loading.
- Remaining bitmap, sound, font, text, score, and script chunk decoders.
- Cast member resolution.
- Lingo opcode model, decompiler, VM runtime values, opcodes, dispatchers, and builtins.
- Player core, rendering pipeline, input, networking, audio, cast management, and debugging.
- WASM/web player replacement strategy in C++.
- Editor replacement strategy in C++.
- Port parity tests against current Java fixtures and integration scenarios.

## Commit Log

- `b1d5f49 Add initial C++ port foundation`
- `4034c08 Port C++ chunk audio foundation`
- `7364e28 Port C++ cast metadata foundation`
- `d86665c Port C++ compact chunk parsers`
- Current checkpoint commit message: `Port C++ cast list and member chunks`
