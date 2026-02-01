# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LibreShockwave is a Java library for parsing and playing Macromedia/Adobe Director and Shockwave files (.dir, .dxr, .dcr, .cct, .cst). It supports Director versions 4 through 12, including Afterburner-compressed files.

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :sdk:build
./gradlew :vm:build
./gradlew :player-core:build
./gradlew :player:build

# Run tests
./gradlew :sdk:test                    # SDK unit tests
./gradlew :sdk:runTests                # SDK integration tests
./gradlew :sdk:runFeatureTests         # SDK feature tests (includes HTTP)
./gradlew :sdk:runDcrTests             # DCR file tests
./gradlew :vm:runVmTests               # VM unit tests
./gradlew :player:runPlayerTests       # Player tests
./gradlew :player-core:runPlayerCoreTests

# Run applications
./gradlew :player:run                  # Launch player GUI
./gradlew :sdk:extractCasts            # Launch cast extractor GUI

# Build fat JARs
./gradlew :player:fatJar
./gradlew :cast-extractor:fatJar
```

## Architecture

The project is organized into 5 Gradle modules with clear layering:

```
sdk → vm → player-core → player
              ↘
           cast-extractor
```

### Module Responsibilities

- **sdk**: Core parsing library with zero external dependencies. Handles RIFX container parsing, chunk decoding, bitmap/audio extraction, and Lingo bytecode disassembly. Entry point: `DirectorFile.java`

- **vm**: Lingo bytecode interpreter. Stack-based execution with opcode handlers and built-in functions. Key classes: `LingoVM`, `Datum` (sealed interface for runtime values), `OpcodeRegistry`, `BuiltinRegistry`

- **player-core**: Playback orchestration. Manages frame execution, event dispatching, score navigation, and rendering. Key classes: `Player`, `FrameContext`, `EventDispatcher`, `ScoreNavigator`, `StageRenderer`

- **player**: Swing GUI application. Entry point: `PlayerApp.java`

- **cast-extractor**: Standalone GUI tool for extracting assets from Director files

### Key Patterns

- **Chunk-based parsing**: All file data organized as chunks (4-byte FourCC + length + data). See `ChunkType` enum and `chunks/` package
- **Sealed interface type system**: `Datum` sealed interface with variants for Int, Float, Str, Symbol, List, PropList, SpriteRef, CastRef, ObjRef
- **Opcode/builtin registries**: Handler dispatch via pattern matching in `OpcodeRegistry` and `BuiltinRegistry`
- **Event-driven playback**: `EventDispatcher` routes script triggers through frame-level propagation

## Requirements

- Java 21 or later (Gradle toolchain auto-downloads if needed)
- No external runtime dependencies in SDK module
