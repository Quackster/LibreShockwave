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

## Recent Fixes

### External Cast Loading (Fixed)
- External casts are loaded via `preloadNetThing()` which runs asynchronously in NetManager
- The completion callback calls `CastLibManager.setExternalCastDataByUrl()` to load the cast data
- URL matching compares the filename (without extension) to the cast library name
- Fixed: Made `ensureInitialized()` synchronized to prevent race conditions
- Fixed: Added case-insensitive matching in `getCastLibNumberByUrl()`

### THE_BUILTIN Stack Leak (Fixed)
**Issue**: `exitFrame` handler in habbo.dcr caused "Step limit exceeded" error
**Root Cause**: `THE_BUILTIN` opcode wasn't popping the arglist marker from the stack

The `repeat with i = 2 to the paramCount` loop compiled to:
```
PUSH_ARG_LIST  0    ; Push empty arglist for paramCount
THE_BUILTIN   121   ; the paramCount (should pop arglist, push result)
LT_EQ               ; Compare loop counter
```

Each loop iteration pushed a new arglist but `THE_BUILTIN` never popped it, causing stack to grow indefinitely.

**Fix**: Modified `PropertyOpcodes.theBuiltin()` to pop the arglist before processing (matching how `EXT_CALL` handles arglists).

### Script Instance Handler Dispatch (Fixed)
**Issue**: Method calls on script instances (via `OBJ_CALL`) weren't finding handlers like `create()`, `dump()`, etc.

**Root Cause**: Two issues:
1. `ScriptInstance.scriptId` contained an auto-incremented instance ID (1, 2, 3...) instead of the actual script member number
2. `findHandlerInScript` compared against `script.id()` (chunk ID) instead of using member number lookup

**Fix**:
1. Modified `handleScriptInstanceMethod()` to extract the member number from the `__scriptRef__` property stored on the instance
2. Modified `CastLibManager.findHandlerInScript()` to look up scripts by member number using `CastLib.getScript(memberNumber)` instead of iterating through all scripts

### Script Instance Handler Dispatch Improvements (Partial)
**Issue**: `handleScriptInstanceMethod()` was not using cast lib number for precise handler lookup.

**Changes**:
1. Added `getScriptRefFromInstance()` to extract the full ScriptRef (castLib + member) from `__scriptRef__`
2. Updated `handleScriptInstanceMethod()` to use `findHandlerInScript(castLib, member, name)` when ScriptRef is available
3. Added 3-argument overload of `findHandlerInScript(int castLibNumber, int memberNumber, String handlerName)` to `CastLibProvider` interface

**Note**: Automatic constructor calling was tested but caused infinite recursion when constructors create other instances. Constructors are now called explicitly by Lingo bytecode (e.g., `instance.create()`).

### Class Hierarchy and Ancestor Chain (Fixed)
**Issue**: `dump()` returns VOID in habbo.dcr because the `ancestor` chain wasn't set up properly.

**Root Cause**: Two issues prevented proper class hierarchy setup:
1. **Script Instance Property Access**: `handleScriptInstanceMethod()` didn't handle property-setting methods like `setAt(#ancestor, value)`. When Lingo code called `obj.setAt(#ancestor, previousInstance)`, it returned VOID without actually setting the property.
2. **Slot Number Decoding**: `script()` builtin didn't properly decode slot numbers. When Lingo code calls `member("ClassName").number` and then `script(slotNumber)`, the number is a slot number `(castLib << 16) | memberNum`, not a raw member number.

**Fix**:
1. Added built-in property access handlers to `handleScriptInstanceMethod()`:
   - `setAt(#propName, value)` - sets property on instance
   - `setaProp(#propName, value)` - sets property on instance
   - `getAt(#propName)`, `getaProp(#propName)`, `getProp(#propName)` - gets property from ancestor chain
   - `addProp`, `deleteProp`, `count`, `ilk` - other property methods

2. Updated `script()` builtin to decode slot numbers:
   ```java
   if (value > 65535) {
       castLib = value >> 16;
       memberNum = value & 0xFFFF;
   }
   ```

**Result**: The `dump()` handler in Variable Container Class is now found and executed via the ancestor chain.

### Key Files
- `vm/src/main/java/com/libreshockwave/vm/opcode/PropertyOpcodes.java` - `theBuiltin()` method
- `vm/src/main/java/com/libreshockwave/vm/opcode/CallOpcodes.java` - `handleScriptInstanceMethod()`, `getScriptRefFromInstance()`, property access on instances
- `vm/src/main/java/com/libreshockwave/vm/builtin/ConstructorBuiltins.java` - `createScriptInstance()`, `new()` builtin
- `vm/src/main/java/com/libreshockwave/vm/builtin/TypeBuiltins.java` - `script()` builtin with slot number decoding
- `vm/src/main/java/com/libreshockwave/vm/builtin/CastLibProvider.java` - `findHandlerInScript()` interface
- `player-core/src/main/java/com/libreshockwave/player/cast/CastLibManager.java` - `findHandlerInScript()` implementations
- `player-core/src/main/java/com/libreshockwave/player/cast/CastLib.java` - `getScript(memberNumber)`
- `vm/src/main/java/com/libreshockwave/vm/opcode/ControlFlowOpcodes.java` - Jump/loop opcodes
- `sdk/src/main/java/com/libreshockwave/chunks/ScriptChunk.java` - `getInstructionIndex()` for offset→index mapping
