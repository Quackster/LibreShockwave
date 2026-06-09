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

### Lingo Opcode and Script Context Foundation

- `lingo::Opcode` enum and helpers for opcode code lookup, mnemonic lookup, argument byte width, and push/jump/call/return classification.
- `chunks::ScriptContextChunk` Lctx parser for script table metadata, Lnam section references, valid counts, flags, free pointer, and script entries.
- Script context entry parsing clamps free/empty negative script chunk IDs to `ChunkId(0)` like the Java baseline.

### Script Bytecode Chunk Parser

- `chunks::ScriptChunk` Lscr parser for script type, behavior flags, properties, globals, literals, handler metadata, and handler name tables.
- Literal parsing covers string, inline int, float numeric value, and raw unknown literal payloads.
- Handler bytecode instruction decoding normalizes multi-byte opcodes, preserves raw opcode bytes, decodes signed push-int arguments, and builds bytecode-offset lookup maps.

### Score Chunk Parser

- `chunks::ScoreChunk` VWSC parser for score headers, entry tables, frame data, and behavior interval entries.
- Delta-compressed score frame data expansion with D5 packed-main-channel handling and D6+ uniform channel handling.
- Sprite channel parsing for ink, cast references, geometry, blend, flip flags, and indexed/RGB color resolution.
- Tempo and palette channel extraction with frame lookup helpers.

### Director File Loader Foundation

- `DirectorFile` C++ model with stable shared ownership for parsed chunks.
- In-memory RIFX/XFIR loading for non-Afterburner Director files.
- imap/mmap parsing, chunk-info table construction, version detection from config chunks, and chunk dispatch through the ported chunk readers.
- Chunk categorization for config, key table, casts, cast members, scripts, script names/contexts, score, frame labels, palettes, and font maps.

### Afterburner Reader Foundation

- `format::AfterburnerReader` parser for Fver, Fcdr, ABMP, and FGEI/ILS sections.
- Compression type directory parsing with MoaID-based zlib/null compression detection.
- ABMP resource map parsing and ILS chunk cache extraction.
- Chunk data lookup by resource ID and FourCC with zlib decompression where required.

### Director File Afterburner Integration

- `DirectorFile::load` now accepts FGDM/FGDC Afterburner movie types.
- Afterburner chunk maps are translated into `DirectorChunkInfo` records and dispatched through the same ported chunk readers as RIFX/XFIR files.
- Config chunks are parsed early to establish Director version before the full chunk pass.
- Afterburner chunks are categorized into the same config/script/cast/score/media collections as uncompressed Director files.

### D3 RIFF Loader Foundation

- `DirectorFile::load` now accepts RIFF/FFIR containers with RMMP markers.
- CFTC resource table parsing maps named RIFF resources to chunk payload offsets.
- RIFF chunks reuse the existing C++ chunk dispatch and categorization path.

### Lazy Chunk Reparse Foundation

- `DirectorFile::getChunk` reparses evicted chunks from stored file bytes for RIFX/XFIR/RIFF/FFIR files.
- Afterburner-loaded files retain their parsed `AfterburnerReader` for evicted chunk reloads.
- `DirectorFile::releaseNonEssentialChunks` evicts sound, media, and raw chunks to match the Java memory-management path.

### Cast and Script Lookup Foundation

- `lookup::CastMemberLookup` maps cast libraries to CAS* chunks and resolves cast members by score index or authored member number.
- `lookup::ScriptLookup` resolves scripts by Lctx context ID, including overlapping context spaces across multiple script contexts.
- `DirectorFile` exposes cast member, mapped cast, script-by-context, and script-type lookup helpers backed by lazy lookup caches.

### Palette Resolution Integration

- `lookup::PaletteResolver` resolves built-in palettes, custom palette cast members, direct CLUT chunk references, indexed palette members, and first-palette/System Mac fallbacks.
- Custom 256-color palette resolution inherits the System Mac trailing grayscale ramp for black placeholder entries in indices 246-255.
- `DirectorFile` exposes `resolvePalette`, `resolvePaletteExact`, and `resolvePaletteByMemberNumber` helpers backed by a lazy palette resolver cache.

### DirectorFile Member Resource Integration

- `DirectorFile` exposes stage width, stage height, default tempo, score tempo, and score palette channel helpers.
- KEY* backed member resource lookup now resolves embedded film-loop score chunks for cast members.
- KEY* backed text lookup now returns all associated STXT chunks, chooses the first non-empty text chunk, and falls back to direct member-id STXT lookup.

### Script Name and Info Helpers

- `chunks::ScriptChunk` resolves handler, property, global, and arbitrary name IDs through `ScriptNamesChunk`.
- Script handler lookup by case-insensitive authored name is available in the C++ SDK.
- `DirectorFile` resolves per-script Lnam chunks, script cast-member ownership, script member display names, global/property name lists, and script info summaries.

### DirectorFile Metadata Helpers

- `DirectorFile` tracks mutable base-path metadata for external cast resolution.
- External cast path listing, external-cast presence checks, score-presence checks, and font-name lookup across Fmap chunks are exposed in C++.

### Bitmap Decoder Foundation

- `bitmap::BitmapDecoder` ports Director PackBits/RLE decompression and scan-width calculations.
- Indexed bitmap decoding covers 1-bit, 2-bit, 4-bit, and 8-bit sources with palette-index metadata preservation.
- 16-bit RGB555 and 32-bit separated/interleaved channel decoders are available, plus the automatic compression/bit-depth dispatch path.

### DirectorFile Bitmap Decode Integration

- `DirectorFile::decodeBitmap` resolves BITD chunks through KEY* owner mappings for bitmap cast members.
- Bitmap member specific data is parsed through `cast::BitmapInfo`, palettes are resolved or overridden, and decoded bitmaps preserve native-alpha and palette metadata.
- ediM/JPEG and ALFA sidecar decoding remain deferred to the higher-level media integration slice.

### W3D File Parser Foundation

- `W3DFile` ports the Java W3D byte-vector and path loading API for little-endian Shockwave 3D resource streams.
- Raw W3D entries preserve type, parent reference, and payload bytes with Java-compatible unknown-type handling.
- Typed W3D accessors now parse nodes/light data, shapes, mesh resources, textures, materials, and resource references.
- W3D node transforms expose position helpers, and texture parsing detects JPEG payloads like the Java SDK.

### Generated Font Data Decoder

- `fonts::FontDataDecoder` ports the Java generated-font helper used by embedded font classes.
- Base64 font chunks are decoded into a fixed compressed buffer before inflation, matching the Java generated source contract.
- zlib inflation returns decoded font bytes only when the exact expected uncompressed length is produced, otherwise it returns an empty vector.

### Utility Formatting Helpers

- `util::getFileName`, `getFileNameWithoutExtension`, and `getUrlsWithFallbacks` port the Java path/URL fallback helper behavior for movie and cast files.
- `format::ScriptFormatUtils` ports literal type names, literal formatting, script type display names, name/handler fallback resolution, truncation, and line-ending normalization.

### Bitmap Colorizer

- `bitmap::BitmapColorizer` ports Director foreColor/backColor bitmap recoloring for 32-bit and indexed bitmap surfaces.
- Raw indexed-data colorization handles 1-bit, 2-bit, 4-bit, and 8-bit packed palette indices before bitmap decode.
- Ink-mode helper predicates for colorization and backColor usage are available in C++.

### PFR Font Bit Reader

- `font::PfrBitReader` ports byte-aligned big-endian reads and MSB-first bit reads used by the Java PFR1 font parser.
- Signed 8-bit, 16-bit, and 24-bit reads preserve Java sign-extension behavior.
- Bit reads preserve partial EOF behavior and byte-alignment reset on byte-level reads, position changes, and skips.

### Sound Converter

- `audio::SoundConverter` ports WAV construction for raw PCM and `SoundChunk` data, including Director big-endian 16-bit swapping and signed 8-bit conversion.
- MP3 start detection, multi-frame validation, frame-boundary end detection, and MP3 payload extraction are available in C++.
- IMA ADPCM decoding and ADPCM-to-WAV conversion are available with Java-compatible step/index table behavior.

### Cast Member Metadata Wrapper

- `cast::CastMember` ports the Java high-level cast member wrapper around `CastMemberChunk`.
- Type-specific bitmap, shape, film-loop, script, and Shockwave 3D metadata are parsed lazily at wrapper construction.
- Type checks, dimensions, registration point helpers, raw chunk access, and display string formatting are available in C++.

### Player Event and Render Configuration Foundation

- `player::PlayerEvent` ports the Java player event enum and handler-name mapping used by script dispatch.
- `PlayerEventInfo`, `FrameEvent`, and `ExternalCastLoadEvent` port the lightweight Java record payloads used by listeners.
- `render::RenderType` and `render::RenderConfig` port the software-renderer enum and global antialias flag.

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
- Lingo opcode and script context parser tests passed through the same CTest executable.
- Script bytecode chunk parser tests passed through the same CTest executable.
- Score chunk parser tests passed through the same CTest executable.
- Director file RIFX loader tests passed through the same CTest executable.
- Afterburner reader tests passed through the same CTest executable.
- Director file Afterburner loader tests passed through the same CTest executable.
- Director file RIFF loader tests passed through the same CTest executable.
- Lazy chunk reparse and non-essential release tests passed through the same CTest executable.
- Cast member and script lookup tests passed through the same CTest executable.
- Palette resolver and DirectorFile palette lookup tests passed through the same CTest executable.
- DirectorFile stage/tempo, associated text, and associated score lookup tests passed through the same CTest executable.
- ScriptChunk name resolution and DirectorFile script-helper empty fallback tests passed through the same CTest executable.
- DirectorFile base path, score presence, external cast, and font lookup fallback tests passed through the same CTest executable.
- Bitmap decoder RLE, scan-width, indexed, RGB555, 32-bit channel, and automatic dispatch tests passed through the same CTest executable.
- DirectorFile BITD bitmap decode integration passed through the RIFX loader fixture in the same CTest executable.
- W3D entry, typed resource, transform, texture format, and lookup tests passed through the same CTest executable.
- Generated font Base64/zlib decode, wrong-length, and invalid-deflate tests passed through the same CTest executable.
- File/path fallback utilities and script formatting utilities passed through the same CTest executable.
- BitmapColorizer 32-bit, indexed, foreground-only, packed-index, and ink predicate tests passed through the same CTest executable.
- PfrBitReader byte, signed, skip, alignment, bit-buffer, and partial-EOF tests passed through the same CTest executable.
- SoundConverter WAV layout, SoundChunk header stripping, signed/endianness conversion, MP3 extraction, IMA ADPCM, and duration tests passed through the same CTest executable.
- CastMember bitmap, script, shape, dimension, type-check, raw chunk, and display string tests passed through the same CTest executable.
- PlayerEvent handler names, event payload records, RenderType, and RenderConfig tests passed through the same CTest executable.
- Full Gradle Java test baseline is not green at this checkpoint: `:player-core:test` fails in `ScriptModifiedBitmapTest.scriptModifiedIndexedDarkenUsesPaletteIndicesForSpriteColorRamp` with `expected 0xFF903F20`, actual `0xFF903E1F`. No Java files are changed in this checkpoint.

## Remaining Major Work

- Higher-level media integration.
- Remaining ediM/ALFA bitmap integration, sound, font, text, score, and script chunk decoders.
- Detailed W3D geometry/material decoding and rendering integration.
- Lingo decompiler, VM runtime values, dispatchers, and builtins.
- Player core, rendering pipeline, input, networking, audio, cast management, and debugging.
- WASM/web player replacement strategy in C++.
- Editor replacement strategy in C++.
- Port parity tests against current Java fixtures and integration scenarios.

## Commit Log

- `b1d5f49 Add initial C++ port foundation`
- `4034c08 Port C++ chunk audio foundation`
- `7364e28 Port C++ cast metadata foundation`
- `d86665c Port C++ compact chunk parsers`
- `9f506a1 Port C++ cast list and member chunks`
- `4e3e971 Port C++ script context and opcode foundation`
- `c160ac3 Port C++ script bytecode chunk parser`
- `1e8cfbf Port C++ score chunk parser`
- `d4bcca5 Port C++ director file loader foundation`
- `d0ddeff Port C++ Afterburner reader foundation`
- `cbe591d Integrate C++ Afterburner file loading`
- `7a2276f Port C++ D3 RIFF file loading`
- `cc3203d Port C++ lazy chunk reparsing`
- `ea9e01f Port C++ cast and script lookup`
- `afbfbcb Port C++ palette resolver`
- `d71ac1d Port C++ DirectorFile member resources`
- `89d38fc Port C++ script name helpers`
- `f59edc1 Port C++ DirectorFile metadata helpers`
- `de796a3 Port C++ bitmap decoder foundation`
- `3afd372 Port C++ DirectorFile bitmap decode`
- `b870598 Port C++ W3D file parser`
- `382f342 Port C++ generated font decoder`
- `e4ebbae Port C++ utility formatting helpers`
- `4636ee7 Port C++ bitmap colorizer`
- `7e84729 Port C++ PFR bit reader`
- `59bf47d Port C++ sound converter`
- `f0ac5ed Port C++ cast member wrapper`
- Current checkpoint commit message: `Port C++ player event foundation`
