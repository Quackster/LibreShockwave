# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LibreShockwave is an open-source implementation of a Macromedia/Adobe Director and Shockwave player. It parses Director files (.dir, .dxr, .dcr) and executes Lingo bytecode.

## Build Commands

Requires Java 21+. Uses Gradle with a multi-module structure.

```bash
# Build all modules
./gradlew build

# Build everything including WebAssembly
./gradlew buildAll

# Run the Swing player (for debugging)
./gradlew :player:run

# Run with a specific file
./gradlew :runtime:runPlayer -Pfile=path/to/movie.dcr

# Run SDK unit tests
./gradlew :sdk:test

# Run integration tests
./gradlew runTests           # SDK integration tests
./gradlew runDcrTests        # DCR file tests (add -Pfile=path for specific file)
./gradlew runBytecodeTests   # Bytecode execution tests
./gradlew runRuntimeTests    # Runtime tests

# Build and serve WebAssembly player
./gradlew :runtime:runWasmPlayer
```

## Project Structure

Four Gradle subprojects:

- **sdk/** - Core library: file parsing, Lingo VM, cast management. No external dependencies.
- **runtime/** - TeaVM-based WebAssembly runtime for browser playback. Compiles to WASM via TeaVM.
- **player/** - Standalone Java Swing player for debugging
- **xtras/** - Director Xtras (extensions), e.g., NetLingo for network operations

## SDK Architecture

The SDK in `sdk/src/main/java/com/libreshockwave/` is organized as:

### File Parsing (`format/`, `chunks/`)
- **DirectorFile** - Main entry point for loading .dir/.dcr/.dxr files
- **ChunkType** - FourCC identifiers for all chunk types (RIFX, MCsL, CASt, Lscr, etc.)
- **AfterburnerReader** - Handles compressed DCR (Afterburner) format
- Chunk types are a sealed interface hierarchy with specific implementations for each Director chunk

### Key Chunks
- **ConfigChunk (DRCF/VWCF)** - Movie settings, stage dimensions, tempo
- **MCsL** - Cast library list (internal + external references)
- **CAS*/CASt** - Cast member array and individual member definitions
- **KEY*** - Maps resource IDs to chunk locations
- **Lctx/LctX** - Script context (index of all scripts)
- **Lnam** - Script name table for handlers and variables
- **Lscr** - Compiled Lingo bytecode
- **VWSC/SCVW** - Score (timeline) data

### Lingo VM (`vm/`, `lingo/`)
- **LingoVM** - Bytecode execution engine with stack-based evaluation
- **Datum** - Sealed interface for runtime values (Int, DFloat, Str, DList, PropList, etc.)
- **Opcode** - All bytecode instructions (stack ops, arithmetic, jumps, calls)
- **ExecutionScope** - Call stack frame management

### Player Runtime (`player/`, `execution/`)
- **DirPlayer** - Movie playback state machine (frame navigation, event dispatch)
- **CastManager** - Manages internal and external cast libraries
- **Score** - Timeline with frames, sprite channels, and frame scripts
- **Sprite** - Per-channel sprite state (position, member, ink, visibility)
- **BitmapDecoder** - Decodes compressed bitmap data from BITD chunks

### Built-in Handlers (`handlers/`)
Lingo functions registered in **HandlerRegistry**: math, string, list operations, geometry, sound, network.

## Xtras System

Xtras extend Director functionality. The `Xtra` interface requires:
- `getName()` - Xtra identifier
- `register(LingoVM vm)` - Register handlers with VM

Use `XtraManager.createWithStandardXtras()` for default Xtra set (includes NetLingoXtra).

## WebAssembly Runtime

The `runtime/` module compiles to WASM via TeaVM. Entry point is `TeaVMEntry.java` which exports functions prefixed with `lsw_` (e.g., `lsw_init`, `loadMovieFromBuffer`, `tick`, `getSpriteDataValue`).

Key exports for JS integration:
- Movie loading: `allocateMovieBuffer`, `setMovieDataByte`, `loadMovieFromBuffer`
- Playback: `play`, `stop`, `tick`, `getCurrentFrame`, `getLastFrame`
- Sprites: `prepareSpriteData`, `getSpriteDataValue`, `getSpriteCount`
- Bitmaps: `prepareBitmap`, `getBitmapPixel`, `getBitmapWidth`, `getBitmapHeight`
- External casts: `getPendingExternalCastCount`, `loadExternalCastFromBuffer`

## External Cast Loading

Director movies reference external cast libraries (.cct/.cst files). The CastManager tracks pending external casts via `MCsL` entries. In WASM, JavaScript fetches external casts and passes data via `allocateExternalCastBuffer`/`loadExternalCastFromBuffer`.
