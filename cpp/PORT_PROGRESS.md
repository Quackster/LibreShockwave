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

## Verification

Last verified: 2026-06-10

Commands:

```bash
cmake --build cmake-build-debug --parallel
ctest --test-dir cmake-build-debug --output-on-failure
```

Result:

- `libreshockwave_cpp_tests`: passed.
- zlib support was detected in the local CMake build and the zlib decompression path is covered by the C++ tests.

## Remaining Major Work

- SDK chunk model and chunk parsers.
- Director/Afterburner file loading.
- Bitmap, sound, font, text, cast, score, and script chunk decoders.
- Lingo opcode model, decompiler, VM runtime values, opcodes, dispatchers, and builtins.
- Player core, rendering pipeline, input, networking, audio, cast management, and debugging.
- WASM/web player replacement strategy in C++.
- Editor replacement strategy in C++.
- Port parity tests against current Java fixtures and integration scenarios.

## Commit Log

- Pending commit: initial C++ foundation and bitmap foundation.
