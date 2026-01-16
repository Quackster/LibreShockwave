# LibreShockwave Development Guide

A Java 21+ SDK for reading, parsing, and executing Macromedia/Adobe Director & Shockwave files (`.dir`, `.dxr`, `.dcr`, `.cst`).

---

## Overview

LibreShockwave is a port of the Rust project **dirplayer-rs** and must match its behavior exactly. This document serves as the canonical development guide for contributors.

## Sources of Truth

| Priority | Project | Path | Purpose |
|----------|---------|------|---------|
| **Primary** | dirplayer-rs | `C:\SourceControl\dirplayer-rs\vm-rust\src` | Defines semantics, behavior, edge cases |
| **Secondary** | ProjectorRays | `C:\SourceControl\ProjectorRays` | Disambiguation when tests fail or Rust is unclear |

> **Important:** Only consult ProjectorRays when `dirplayer-rs` alone cannot resolve behavior.

### dirplayer-rs Structure

```
vm-rust/src/
├── director/           # File/chunk parsing
│   ├── file.rs         # Main file parser
│   ├── chunks/         # Individual chunk parsers
│   ├── lingo/          # Lingo script structures
│   ├── cast.rs         # Cast handling
│   └── enums.rs        # Enumerations
├── player/             # Runtime/VM
├── io/                 # Binary reading utilities
├── js_api.rs           # WASM/JS API
└── rendering.rs        # Stage rendering
```

---

## Project Objectives

LibreShockwave must achieve full parity with dirplayer-rs across these capabilities:

1. **Container Parsing** - Fully parse Director containers and chunks (including Afterburner `.dcr`)
2. **Resource Mapping** - Correctly map resources via `KEY*`, `ABMP`, and `ILS`
3. **Lingo Execution** - Execute Lingo bytecode with complete opcode coverage
4. **Asset Extraction** - Support bitmaps, palettes, sounds, text, and other assets
5. **Test Parity** - Passing Java tests with verified behavior matching Rust

---

## Non-Negotiables

| Rule | Rationale |
|------|-----------|
| `dirplayer-rs` defines semantics | Port behavior, edge cases, and error handling exactly |
| Keep Java idiomatic | Use records, sealed types, pattern matching - but never change meaning |
| Deterministic behavior | Prefer well-documented behavior over guesswork |
| Document mappings | When Rust patterns don't translate directly, implement closest Java equivalent and document |

---

## Work Plan

Execute in small, verifiable steps:

### Step 1: Gap Analysis
Identify incomplete areas in LibreShockwave vs dirplayer-rs:
- [ ] Missing chunk types
- [ ] Afterburner gaps
- [ ] Opcode gaps
- [ ] Cast member coverage
- [ ] Sound support
- [ ] Film loops
- [ ] Other assets

### Step 2: Prioritize High-Leverage Areas

Select the most blocking incomplete feature first:

| Area | Priority | Status |
|------|----------|--------|
| Afterburner `.dcr` pipeline | High | Fcdr/ABMP/ILS decoding |
| Remaining VM opcodes (~34) | High | Stack/arithmetic/flow/vars |
| Integration tests | Medium | Real file validation |

### Step 3: Implementation Loop

For each item:

```
1. Locate equivalent Rust module/function in dirplayer-rs
2. Port logic line-for-line where reasonable
3. Add/extend Java tests to lock behavior
4. Validate against known files/fixtures
```

---

## Afterburner Support Rules (v0.2 Focus)

Implement `.dcr` support to match Rust parsing precisely:

### Required Components

```
+-----------------------------------------------------+
|                    DCR Pipeline                      |
+-----------------------------------------------------+
|  1. Read Fver/Fcdr/ABMP                             |
|  2. Decompress zlib segments                        |
|  3. Resolve resource entries via ABMP offset -> ILS |
|  4. Expose chunks through same APIs as uncompressed |
+-----------------------------------------------------+
```

### Critical Implementation Details

| Area | Consideration |
|------|---------------|
| **Variable-length ints** | Match Rust parsing exactly |
| **Endian rules** | FourCC vs numeric fields have different rules |
| **Offset/size accounting** | Off-by-one errors are common pitfalls |
| **Compression type GUID** | Map GUIDs correctly |
| **Lazy vs eager decompression** | Match Rust behavior first, optimize later |

---

## VM / Opcode Rules

Maintain the VM as a stack-based interpreter matching Rust:

### Core Requirements

| Aspect | Requirement |
|--------|-------------|
| **Stack effects** | Match Rust push/pop semantics exactly |
| **Control flow** | Identical branching and call semantics |
| **Datum coercions** | int/float/string/symbol/list/propList |
| **Runtime errors** | Same exception types and conditions |

### Datum Type System

```
+------------------------------------------+
|              Datum Types                 |
+------------------------------------------+
|  int       | 32-bit signed integer       |
|  float     | 64-bit double precision     |
|  string    | UTF-8 string value          |
|  symbol    | Interned symbol reference   |
|  list      | Linear list [a, b, c]       |
|  propList  | Property list [#a: 1, ...]  |
|  void      | Null/undefined value        |
|  object    | Script/sprite instance      |
+------------------------------------------+
```

---

## "Stuck" Policy

When Java tests won't pass and `dirplayer-rs` alone cannot resolve behavior:

### Resolution Steps

```
1. Consult ProjectorRays at C:\SourceControl\ProjectorRays
2. Confirm intent/edge cases
3. Break ties in ambiguous areas
4. Document any divergence explicitly
```

### Valid Divergence Conditions

Only diverge from `dirplayer-rs` when ProjectorRays clearly demonstrates:
- The intended behavior differs
- Rust implementation appears incomplete
- Rust implementation is underspecified
- Rust implementation is buggy

> **Always document divergences explicitly with rationale**

---

## Output Expectations

When making changes, include:

### Required Documentation

| Element | Description |
|---------|-------------|
| **Summary** | What changed and why |
| **Rust Reference** | Link to corresponding Rust code (file/class/function) |
| **Tests** | Include tests or reproduction path |
| **TODOs** | Call out known incompatibilities or remaining work |

### Change Template

```markdown
## Change: [Feature/Fix Name]

### Summary
Brief description of what was changed and the motivation.

### Rust Reference
- File: `vm-rust/src/director/file.rs`
- Function: `parse_abmp()`
- Lines: 142-198

### Tests Added
- `AfterburnerParserTest.testAbmpParsing()`
- `AfterburnerParserTest.testIlsResolution()`

### Known Issues / TODOs
- [ ] Edge case X not yet handled
- [ ] Performance optimization pending
```

---

## File Format Reference

### Supported Extensions

| Extension | Type | Compressed |
|-----------|------|------------|
| `.dir` | Director Movie | No |
| `.dxr` | Protected Director Movie | No |
| `.dcr` | Shockwave Movie (Afterburner) | Yes |
| `.cst` | Cast Library | No |

### Chunk Types Overview

```
Container Chunks:
+-- RIFX      (Main container)
+-- imap      (Initial map)
+-- mmap      (Memory map)
+-- KEY*      (Key table)
+-- CAS*      (Cast association)

Content Chunks:
+-- Lctx      (Lingo context)
+-- Lnam      (Lingo names)
+-- Lscr      (Lingo script)
+-- BITD      (Bitmap data)
+-- CLUT      (Color lookup table)
+-- snd       (Sound data)
+-- STXT      (Styled text)

Afterburner Chunks:
+-- Fver      (Format version)
+-- Fcdr      (Compressed data reference)
+-- ABMP      (Afterburner map)
+-- ILS       (Internal linked segments)
```

---

## Development Setup

### Prerequisites

- Java 21+
- Gradle
- Access to test Director files
- Reference repositories cloned locally

### Project Structure

```
libreshockwave/
+-- sdk/                      # SDK subproject (file parsing, VM)
|   +-- src/
|   |   +-- main/java/com/libreshockwave/
|   |   |   +-- chunks/           # Chunk record types
|   |   |   +-- format/           # File format handling
|   |   |   +-- io/               # Binary reading
|   |   |   +-- lingo/            # Lingo types (Datum, Opcode)
|   |   |   +-- player/           # Player components
|   |   |   |   +-- bitmap/       # Bitmap handling
|   |   |   |   +-- CastLib.java  # Cast library management
|   |   |   |   +-- CastManager.java # Multi-cast management
|   |   |   |   +-- DirPlayer.java # Movie playback controller
|   |   |   +-- vm/               # Lingo VM
|   |   |   |   +-- LingoVM.java  # Bytecode executor
|   |   |   +-- DirectorFile.java # Main entry point
|   |   +-- test/java/com/libreshockwave/
|   |       +-- DirectorFileTest.java      # Integration tests
|   |       +-- DcrFileTest.java           # DCR/external cast tests
|   |       +-- BytecodeExecutionTest.java # Bytecode execution tests
|   +-- build.gradle
+-- runtime/                  # Runtime subproject (depends on sdk)
|   +-- src/
|   |   +-- main/java/com/libreshockwave/runtime/
|   |   |   +-- ExecutionScope.java  # Execution scope for handler calls
|   |   |   +-- DirPlayer.java       # Movie playback controller
|   |   +-- test/java/com/libreshockwave/runtime/
|   |       +-- RuntimeTest.java     # Runtime unit tests
|   +-- build.gradle
+-- build.gradle              # Root project (coordinates subprojects)
+-- settings.gradle           # includes 'sdk', 'runtime'
+-- ARCHITECTURE.md
```

---

## Testing Strategy

### Unit Tests
- One test class per chunk type
- One test class per opcode category
- Edge case coverage matching Rust tests

### Integration Tests
- Real `.dir` files parsing
- Real `.dcr` files parsing
- Full movie playback scenarios

### Validation Approach

```java
// Compare output against dirplayer-rs for:
// 1. Parsed chunk data structures
// 2. VM execution traces
// 3. Extracted asset bytes
// 4. Error conditions and messages
```

---

## External Cast Support

LibreShockwave supports external cast libraries (`.cst`, `.cxt`, `.cct` files) that can be referenced by Director movies. This matches dirplayer-rs's cast management system.

### Architecture

```
+----------------------------------------------------------+
|                    Cast Management                        |
+----------------------------------------------------------+
|  DirectorFile                                             |
|    +-- createCastManager() -> CastManager                 |
|    +-- hasExternalCasts() -> boolean                      |
|    +-- getExternalCastPaths() -> List<String>             |
+----------------------------------------------------------+
|  CastManager                                              |
|    +-- getCasts() -> List<CastLib>                        |
|    +-- getCast(number) -> CastLib                         |
|    +-- preloadCasts(PreloadReason)                        |
|    +-- loadExternalCast(CastLib)                          |
+----------------------------------------------------------+
|  CastLib                                                  |
|    +-- State: NONE | LOADING | LOADED                     |
|    +-- PreloadMode: WHEN_NEEDED | AFTER_FRAME_ONE | ...   |
|    +-- members: Map<slot, CastMemberChunk>                |
|    +-- scripts: Map<id, ScriptChunk>                      |
+----------------------------------------------------------+
```

### External Cast Detection

External casts are identified from the MCsL (cast list) chunk:

```java
// MCsL entry structure
CastListEntry {
    String name;      // Cast library name
    String path;      // Path to external .cst/.cxt file (empty if internal)
    int minMember;    // First member slot
    int maxMember;    // Last member slot
    int id;           // Cast ID for KEY* lookup
}

// A cast is external if:
// 1. MCsL entry has a non-empty path, AND
// 2. No matching CAS* chunk exists in main file
```

### Path Normalization

External cast paths are normalized to `.cct` format:

```java
// Original: "assets:casts:sprites.cst"
// Normalized: "sprites.cct"

// Resolution order:
// 1. basePath + normalized.cct
// 2. basePath + normalized.cst
// 3. basePath + normalized.cxt
```

### Preload Modes

External casts support three loading strategies:

| Mode | Code | Behavior |
|------|------|----------|
| When Needed | 0 | Lazy load on first member access |
| After Frame One | 1 | Load after first frame displays |
| Before Frame One | 2 | Load before movie starts |

### Usage Example

```java
// Load a Director file
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Create cast manager
CastManager castManager = file.createCastManager();

// Check for external casts
if (file.hasExternalCasts()) {
    // Preload external casts before frame 1
    castManager.preloadCasts(CastManager.PreloadReason.MOVIE_LOADED);
}

// Access cast members
CastLib cast = castManager.getCast(1);
CastMemberChunk member = cast.getMember(10);
```

### Rust Reference

- `vm-rust/src/player/cast_manager.rs` - CastManager implementation
- `vm-rust/src/player/cast_lib.rs` - CastLib structure and loading
- `vm-rust/src/director/chunks/cast_list.rs` - MCsL parsing

---

## Bytecode Execution

LibreShockwave includes a complete Lingo bytecode executor that matches dirplayer-rs behavior.

### Architecture

```
+----------------------------------------------------------+
|                    Bytecode Execution                     |
+----------------------------------------------------------+
|  LingoVM                                                  |
|    +-- execute(script, handler, args) -> Datum            |
|    +-- call(handlerName, args) -> Datum                   |
|    +-- registerBuiltin(name, handler)                     |
+----------------------------------------------------------+
|  DirPlayer                                                |
|    +-- loadMovie(path)                                    |
|    +-- play() / stop() / pause()                          |
|    +-- goToFrame(frame)                                   |
|    +-- dispatchEvent(MovieEvent)                          |
+----------------------------------------------------------+
```

### Opcode Categories

| Category | Opcodes | Status |
|----------|---------|--------|
| Stack | PUSH_INT, PUSH_FLOAT, POP, SWAP, PEEK | Complete |
| Arithmetic | ADD, SUB, MUL, DIV, MOD, INV | Complete |
| Comparison | LT, GT, EQ, NT_EQ, AND, OR, NOT | Complete |
| Control Flow | JMP, JMP_IF_Z, END_REPEAT, RET | Complete |
| Variables | GET_LOCAL, SET_LOCAL, GET_GLOBAL, SET_GLOBAL | Complete |
| Properties | GET_PROP, SET_PROP, GET_OBJ_PROP | Complete |
| Functions | EXT_CALL, LOCAL_CALL, OBJ_CALL | Complete |
| Lists | PUSH_LIST, PUSH_PROP_LIST | Complete |
| Strings | JOIN_STR, CONTAINS_STR, GET_CHUNK | Complete |

### Built-in Handlers

Over 60 built-in handlers are implemented:

- **Math**: abs, sqrt, sin, cos, tan, atan, power, random, min, max, pi
- **Type**: integer, float, string, symbol, value, ilk
- **Lists**: list, count, getAt, setAt, add, append, addAt, deleteAt, sort, getPos
- **PropLists**: getProp, setProp, addProp, deleteProp, findPos
- **Strings**: length, chars, offset, charToNum, numToChar
- **Points/Rects**: point, rect
- **References**: castLib, member, sprite, sound
- **Navigation**: go, play, updateStage, puppetTempo
- **Network**: netDone, preloadNetThing, getNetText
- **Bitwise**: bitAnd, bitOr, bitXor, bitNot
- **System**: put, halt, alert, beep, cursor

### Movie Events

```java
DirPlayer player = new DirPlayer();
player.loadMovie(Path.of("movie.dcr"));

// Event handlers are called automatically
player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
player.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);
player.dispatchEvent(DirPlayer.MovieEvent.EXIT_FRAME);
```

Event types: PREPARE_MOVIE, START_MOVIE, STOP_MOVIE, PREPARE_FRAME, ENTER_FRAME, EXIT_FRAME, IDLE

### Rust Reference

- `vm-rust/src/player/bytecode/handler_manager.rs` - Opcode dispatch
- `vm-rust/src/player/bytecode/stack.rs` - Stack operations
- `vm-rust/src/player/bytecode/arithmetics.rs` - Arithmetic ops
- `vm-rust/src/player/bytecode/flow_control.rs` - Control flow
- `vm-rust/src/player/bytecode/get_set.rs` - Variable access

**Test:** Run `./gradlew runBytecodeTests` to verify bytecode execution

---

## Runtime Subproject

The `runtime/` subproject contains the execution runtime components that depend on LibreShockwave.

### Purpose

- Separates parsing (LibreShockwave) from execution (runtime)
- Allows runtime to be developed and tested independently
- Provides clean dependency structure: runtime depends on LibreShockwave

### Components

| Class | Purpose |
|-------|---------|
| `ExecutionScope` | Manages stack, locals, args for handler execution |
| `DirPlayer` | Movie playback controller with scope management |
| `RuntimeTest` | Unit tests for runtime components |

### ExecutionScope

The execution scope manages state for a single handler call:

```java
ExecutionScope scope = new ExecutionScope(args);

// Stack operations
scope.push(Datum.of(42));
Datum value = scope.pop();
scope.swap();

// Local variables
scope.setLocal("x", Datum.of(10));
Datum x = scope.getLocal("x");

// Arguments
Datum arg0 = scope.getArg(0);
scope.setArg(1, Datum.of(99));

// Bytecode position
scope.setBytecodeIndex(10);
scope.advanceBytecodeIndex();

// Return value
scope.setReturnValue(Datum.of("result"));
```

### Building and Testing

```bash
# Build all subprojects
./gradlew buildAll

# Build SDK only
./gradlew :sdk:build

# Build runtime only
./gradlew :runtime:build

# Run SDK tests
./gradlew runTests
./gradlew runDcrTests
./gradlew runBytecodeTests

# Run runtime tests
./gradlew runRuntimeTests

# Run DirPlayer with a movie file
./gradlew runPlayer -Pfile=path/to/movie.dcr
```

### Rust Reference

- `vm-rust/src/player/scope.rs` - Scope structure
- `vm-rust/src/player/mod.rs` - DirPlayer implementation

---

## Recent Changes

### 2026-01-16: Added Runtime Subproject

**Summary:** Created separate `runtime/` subproject for execution components.

**Changes:**
1. `runtime/src/main/java/.../ExecutionScope.java` - Execution scope with stack, locals, args
2. `runtime/src/main/java/.../DirPlayer.java` - Movie player with scope management
3. `runtime/src/test/java/.../RuntimeTest.java` - Unit tests for runtime components
4. `runtime/build.gradle` - Subproject build configuration
5. `settings.gradle` - Added `include 'runtime'`

**Features:**
- Stack operations: push, pop, peek, swap, popN, clearStack
- Local variables: get/set/has locals
- Arguments: get/set args by index
- Bytecode position tracking
- Return value management
- Receiver (me) reference for object methods
- Loop return index stack for control flow

**Test:** Run `./gradlew :runtime:runTests` to verify runtime functionality

---

### 2026-01-16: Added Bytecode Execution

**Summary:** Added complete Lingo bytecode execution matching dirplayer-rs.

**Rust Reference:**
- `vm-rust/src/player/bytecode/*.rs` - Bytecode handlers
- `vm-rust/src/player/mod.rs` - Main execution loop

**Changes:**
1. `LingoVM.java` - Enhanced with 60+ built-in handlers
2. `DirPlayer.java` - New player class for movie playback
3. `ExecutionScope.java` - Execution scope management
4. `BytecodeExecutionTest.java` - Bytecode execution tests

**Features:**
- All major opcodes implemented (stack, arithmetic, comparison, control flow, variables)
- Movie event dispatching (prepareMovie, exitFrame, etc.)
- Frame navigation (go, play, stop)
- Built-in function library matching dirplayer-rs

---

### 2026-01-16: Added External Cast Support

**Summary:** Added support for external cast libraries like dirplayer-rs.

**Rust Reference:**
- `vm-rust/src/player/cast_manager.rs` - CastManager implementation
- `vm-rust/src/player/cast_lib.rs` - CastLib structure

**Changes:**
1. `CastLib.java` - New class for individual cast library management
2. `CastManager.java` - New class for managing all casts (internal + external)
3. `DirectorFile.java` - Added createCastManager(), hasExternalCasts(), getExternalCastPaths()
4. `DcrFileTest.java` - New test for DCR files with external cast support

**Features:**
- Detects external casts from MCsL chunk entries (external = has file path)
- Normalizes cast paths to .cct format
- Supports three preload modes (when needed, after frame 1, before frame 1)
- Caches loaded external cast files
- Provides member and script access by cast number
- Loads internal cast members when no CAS* chunk mapping found

**Test:** Run `./gradlew runDcrTests` to test DCR loading and external cast support

---

### 2025-01-16: Fixed Script/Handler Name Reading

**Summary:** Fixed reading of script names and handler names from Director files.

**Rust Reference:**
- `vm-rust/src/director/lingo/handler.rs` - Handler record structure
- `vm-rust/src/director/chunks/cast_member.rs` - Cast member info parsing

**Changes:**
1. `ScriptNamesChunk.java` - Removed overly cautious bounds checks
2. `ScriptChunk.java` - Fixed handler record structure (42/46 bytes)
3. `CastMemberChunk.java` - Set big-endian, parse ListChunk for name
4. `ScriptContextChunk.java` - Added lnamSectionId field
5. `DirectorFile.java` - Two-pass Afterburner loading, keep non-empty chunks

**Result:** Scripts now display correct names (e.g., "Initialization", "Init", "Loop") and handlers show correct method names (e.g., "prepareMovie", "stopMovie", "exitFrame").

---

## Resources

- [dirplayer-rs](C:\SourceControl\dirplayer-rs\vm-rust\src) - Primary reference
- [ProjectorRays](C:\SourceControl\ProjectorRays) - Secondary reference
- [Director File Format](http://fileformats.archiveteam.org/wiki/Shockwave) - Format documentation

---

*Last updated: January 2026*
