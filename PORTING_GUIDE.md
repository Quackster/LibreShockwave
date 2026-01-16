# LibreShockwave

A Java 21+ SDK for reading, parsing, and executing Macromedia/Adobe Director & Shockwave files (`.dir`, `.dxr`, `.dcr`, `.cst`).

LibreShockwave is a port of the Rust project **dirplayer-rs** and must match its behavior exactly.

---

## Build Commands

```bash
# Build all subprojects
./gradlew buildAll

# Build SDK only
./gradlew :sdk:build

# Build runtime only
./gradlew :runtime:build

# Run all tests
./gradlew runTests              # SDK integration tests
./gradlew runDcrTests           # DCR/Afterburner tests
./gradlew runBytecodeTests      # Bytecode execution tests
./gradlew runRuntimeTests       # Runtime tests

# Run a specific test with a Director file
./gradlew runDcrTests -Pfile=path/to/movie.dcr

# Run DirPlayer with a movie file (local or HTTP)
./gradlew runPlayer -Pfile=path/to/movie.dcr
./gradlew runPlayer -Pfile=http://localhost:8080/movie.dcr

# Run network loading tests
./gradlew :runtime:runNetworkTest -Purl=http://localhost:8080/habbo.dcr
```

---

## Project Structure

```
libreshockwave/
├── sdk/                          # Core SDK (file parsing, VM)
│   └── src/main/java/com/libreshockwave/
│       ├── DirectorFile.java     # Main entry point - loads .dir/.dcr files
│       ├── chunks/               # Chunk parsers (CASt, Lscr, BITD, etc.)
│       ├── format/               # File format (AfterburnerReader, ChunkType)
│       ├── io/                   # BinaryReader for parsing
│       ├── lingo/                # Datum, Opcode, DatumType
│       ├── vm/                   # LingoVM bytecode executor (single source of truth)
│       ├── net/                  # Network loading (NetManager, NetTask, NetResult)
│       ├── player/               # CastLib, CastManager, Score
│       │   └── bitmap/           # Bitmap handling
│       └── handlers/             # Built-in Lingo handlers
│
├── runtime/                      # Execution runtime (depends on SDK)
│   └── src/main/java/com/libreshockwave/runtime/
│       ├── DirPlayer.java        # Movie playback (uses SDK's LingoVM, supports HTTP)
│       ├── ExecutionScope.java   # Stack, locals, args utility
│       ├── HandlerExecutionResult.java  # ADVANCE, JUMP, STOP, ERROR
│       └── BytecodeHandlerContext.java  # Context for player extensions
│
├── build.gradle                  # Root project (coordinates subprojects)
├── settings.gradle               # includes 'sdk', 'runtime'
└── README.md
```

**Key Architecture Decision:** SDK's `LingoVM` is the single source of truth for bytecode execution. Runtime's `DirPlayer` uses LingoVM and extends it with player-specific handlers (go, play, stop, frame navigation).

---

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
│   └── bytecode/       # Opcode handlers
├── io/                 # Binary reading utilities
└── rendering.rs        # Stage rendering
```

---

## Architecture

### File Loading Pipeline

1. `DirectorFile.load(path)` detects RIFX (uncompressed) vs Afterburner (DCR/compressed)
2. For Afterburner: `AfterburnerReader` decompresses zlib segments via ABMP/ILS mapping
3. Chunks are parsed based on FourCC type (KEYp, CASt, Lscr, BITD, etc.)
4. `CastManager` handles internal + external cast libraries

### Lingo Execution

1. `LingoVM` is a stack-based bytecode interpreter
2. `Scope` tracks locals, args, and bytecode position per handler call
3. Opcodes match dirplayer-rs semantics exactly (push/pop effects, control flow)
4. 60+ built-in handlers registered for standard Lingo functions

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
├── RIFX      (Main container)
├── imap      (Initial map)
├── mmap      (Memory map)
├── KEY*      (Key table)
└── CAS*      (Cast association)

Content Chunks:
├── Lctx      (Lingo context)
├── Lnam      (Lingo names)
├── Lscr      (Lingo script)
├── BITD      (Bitmap data)
├── CLUT      (Color lookup table)
├── snd       (Sound data)
└── STXT      (Styled text)

Afterburner Chunks:
├── Fver      (Format version)
├── Fcdr      (Compressed data reference)
├── ABMP      (Afterburner map)
└── ILS       (Internal linked segments)
```

---

## Afterburner Support (DCR Files)

### Pipeline

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

## Bytecode Execution

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

Event types: PREPARE_MOVIE, START_MOVIE, STOP_MOVIE, PREPARE_FRAME, ENTER_FRAME, EXIT_FRAME, IDLE

```java
DirPlayer player = new DirPlayer();
player.loadMovie(Path.of("movie.dcr"));

// Event handlers are called automatically
player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
player.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);
player.dispatchEvent(DirPlayer.MovieEvent.EXIT_FRAME);
```

---

## External Cast Support

External cast libraries (`.cst`, `.cxt`, `.cct` files) can be referenced by Director movies.

### Architecture

```
+----------------------------------------------------------+
|                    Cast Management                        |
+----------------------------------------------------------+
|  DirectorFile                                             |
|    ├── createCastManager() -> CastManager                 |
|    ├── hasExternalCasts() -> boolean                      |
|    └── getExternalCastPaths() -> List<String>             |
+----------------------------------------------------------+
|  CastManager                                              |
|    ├── getCasts() -> List<CastLib>                        |
|    ├── getCast(number) -> CastLib                         |
|    ├── preloadCasts(PreloadReason)                        |
|    └── loadExternalCast(CastLib)                          |
+----------------------------------------------------------+
|  CastLib                                                  |
|    ├── State: NONE | LOADING | LOADED                     |
|    ├── PreloadMode: WHEN_NEEDED | AFTER_FRAME_ONE | ...   |
|    ├── members: Map<slot, CastMemberChunk>                |
|    └── scripts: Map<id, ScriptChunk>                      |
+----------------------------------------------------------+
```

### External Cast Detection

A cast is external if:
1. MCsL entry has a non-empty path, AND
2. No matching CAS* chunk exists in main file

### Path Normalization

```java
// Original: "assets:casts:sprites.cst"
// Normalized: "sprites.cct"

// Resolution order:
// 1. basePath + normalized.cct
// 2. basePath + normalized.cst
// 3. basePath + normalized.cxt
```

### Preload Modes

| Mode | Code | Behavior |
|------|------|----------|
| When Needed | 0 | Lazy load on first member access |
| After Frame One | 1 | Load after first frame displays |
| Before Frame One | 2 | Load before movie starts |

---

## Runtime Subproject

The `runtime/` subproject contains execution components that depend on SDK.

### ExecutionScope

Manages state for a single handler call:

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

---

## Implementation Rules

| Rule | Rationale |
|------|-----------|
| `dirplayer-rs` defines semantics | Port behavior, edge cases, and error handling exactly |
| Keep Java idiomatic | Use records, sealed types, pattern matching - but never change meaning |
| Deterministic behavior | Prefer well-documented behavior over guesswork |
| Document mappings | When Rust patterns don't translate directly, implement closest Java equivalent and document |

### "Stuck" Policy

When Java tests won't pass and `dirplayer-rs` alone cannot resolve behavior:

1. Consult ProjectorRays at `C:\SourceControl\ProjectorRays`
2. Confirm intent/edge cases
3. Break ties in ambiguous areas
4. Document any divergence explicitly

Only diverge from `dirplayer-rs` when ProjectorRays clearly demonstrates the intended behavior differs or Rust implementation appears incomplete/buggy.

---

## Current Porting Progress

### Completed Features

| Feature | Status | Notes |
|---------|--------|-------|
| Container Parsing (RIFX) | ✅ Complete | Uncompressed .dir files |
| Afterburner Parsing (DCR) | ✅ Complete | Compressed .dcr files via ABMP/ILS |
| Chunk Parsing | ✅ Complete | CASt, Lscr, BITD, KEY*, CAS*, etc. |
| LingoVM Bytecode Execution | ✅ Complete | All major opcodes implemented |
| Built-in Handlers (60+) | ✅ Complete | Math, lists, strings, etc. |
| External Cast Support | ✅ Complete | File-based loading |
| **HTTP Network Loading** | ✅ Complete | Movies and casts over HTTP |
| Score Frame Data | 🔄 Partial | Basic parsing, needs delta decompression |
| Sprite Behaviors | ❌ Not Started | beginSprite/endSprite events |

### TODO - Score Implementation

The score is the timeline that controls sprite placement and behavior attachment per frame.

#### Pending Tasks

1. **Enhance ScoreChunk Parsing**
   - Parse Entry[0] frame data (delta-compressed sprite positions)
   - Parse Entry[2+] behavior intervals (FrameIntervalPrimary + Secondary)
   - Reference: `vm-rust/src/director/chunks/score.rs`

2. **Score Delta Decompression**
   - Implement delta decompression for sprite channel data
   - 24-byte ScoreFrameChannelData structure per channel
   - Reference: `vm-rust/src/director/chunks/score.rs:parse_frame_data()`

3. **Create SpriteChannel Class**
   - Runtime state for each sprite channel
   - Properties: member, locH, locV, width, height, ink, blend, visible
   - Attached behaviors (scriptInstanceList)

4. **Implement Frame Execution Cycle**
   - `beginSprites(frame)` - initialize sprites, attach behaviors, dispatch beginSprite
   - `endSprites(prevFrame, nextFrame)` - dispatch endSprite, detach behaviors
   - `executeFrameScript(frame)` - run frame script from channel 0
   - Reference: `vm-rust/src/player/score.rs`

5. **Reserved Channel Indices**
   - Channel 0: Frame script
   - Channel 1: Palette
   - Channel 2: Transition
   - Channel 3-4: Sound
   - Channel 5: Tempo
   - Channel 6+: Sprite channels

### TODO - Additional Features

| Feature | Priority | Reference |
|---------|----------|-----------|
| Film Loops | Medium | `vm-rust/src/player/film_loop.rs` |
| Sound Playback | Medium | `vm-rust/src/player/sound.rs` |
| Bitmap Rendering | Low | `vm-rust/src/rendering.rs` |
| Transitions | Low | `vm-rust/src/player/transition.rs` |

---

## Network Loading Support

### Architecture

```
+----------------------------------------------------------+
|                    Network Loading                        |
+----------------------------------------------------------+
|  NetManager (sdk/net/)                                    |
|    ├── preloadNetThing(url) -> taskId                     |
|    ├── isTaskDone(taskId) -> boolean                      |
|    ├── getTaskResult(taskId) -> NetResult                 |
|    ├── awaitTask(taskId) -> CompletableFuture             |
|    └── postNetText(url, data) -> taskId                   |
+----------------------------------------------------------+
|  NetTask                                                  |
|    ├── id, url, resolvedUri                               |
|    ├── method: GET | POST                                 |
|    └── postData (for POST requests)                       |
+----------------------------------------------------------+
|  NetResult                                                |
|    ├── Success(byte[] data)                               |
|    └── Error(int errorCode)                               |
+----------------------------------------------------------+
```

### Lingo Network Handlers

All network handlers now use `NetManager` when available:

| Handler | Behavior |
|---------|----------|
| `preloadNetThing(url)` | Starts async HTTP fetch, returns task ID |
| `netDone(taskId)` | Returns TRUE if task is complete |
| `netTextResult(taskId)` | Returns downloaded content as string |
| `netError(taskId)` | Returns "OK" or error code |
| `netStatus(taskId)` | Returns "Complete" or "InProgress" |
| `getStreamStatus(taskId)` | Returns propList with URL, state, bytes, error |
| `postNetText(url, data)` | POST request, returns task ID |

### HTTP Movie Loading

```java
DirPlayer player = new DirPlayer();

// Load movie from HTTP URL
player.loadMovieFromUrl("http://localhost:8080/habbo.dcr")
    .get(60, TimeUnit.SECONDS);

// External casts are resolved relative to movie URL
// e.g., "fuse_client.cct" -> "http://localhost:8080/fuse_client.cct"

// Preload external casts asynchronously
player.getCastManager()
    .preloadCastsAsync(CastManager.PreloadReason.MOVIE_LOADED)
    .get(120, TimeUnit.SECONDS);
```

### Testing HTTP Loading

```bash
# Start a local HTTP server with your Director files
cd /path/to/director/files
python -m http.server 8080

# Run the network test
./gradlew :runtime:runNetworkTest -Purl=http://localhost:8080/habbo.dcr
```

---

## Score Implementation Details

### VWSC Chunk Structure

The score is stored in the VWSC chunk with this layout:

```
Header (24 bytes):
  total_length (u32)
  unk1 (u32)
  unk2 (u32)
  entry_count (u32)
  unk3 (u32)
  entry_size_sum (u32)

Offset Table:
  (entry_count + 1) offsets pointing to entries

Entries:
  Entry[0]: Frame delta-compressed data (ScoreFrameData)
  Entry[1]: Unknown/reserved
  Entry[2+]: Behavior intervals (FrameIntervalPrimary + Secondary)
```

### Frame Data Delta Compression

Each frame's sprite data is delta-compressed:

```
For each frame:
  length (u16) - frame segment length

  For each channel with changes:
    channel_size (u16)
    channel_offset (u16)
    delta_data (bytes) - applied to channel buffer
```

### ScoreFrameChannelData (24 bytes per channel)

```
sprite_type (u8)
ink (u8)
fore_color (u8)
back_color (u8)
cast_lib (u16)
cast_member (u16)
pos_y (u16)
pos_x (u16)
height (u16)
width (u16)
color_flag (u8)
fore_color_g (u8)
back_color_g (u8)
fore_color_b (u8)
back_color_b (u8)
... (remaining bytes)
```

### Behavior Interval Records

**FrameIntervalPrimary (44 bytes):**
- start_frame, end_frame, channel_index
- Defines when a behavior is active

**FrameIntervalSecondary (8+ bytes):**
- cast_lib, cast_member, param_index
- References the behavior script to attach

### Frame Execution Cycle (dirplayer-rs pattern)

```
1. Check if frame changed
2. Call score.end_sprites(prev_frame, next_frame)
   - Dispatch endSprite events
   - Detach exiting behaviors
3. Call score.begin_sprites(current_frame)
   - Load sprite properties from score
   - Attach behaviors from intervals
   - Dispatch beginSprite events
4. Execute frame script (channel 0)
5. Process events, render frame
6. Determine next frame
7. Loop
```

---

## Change Documentation Template

When making changes, document in this file:

```markdown
## Change: [Feature/Fix Name]

### Summary
Brief description of what was changed and the motivation.

### Rust Reference
- File: `vm-rust/src/director/file.rs`
- Function: `parse_abmp()`

### Tests Added
- `FeatureTest.testNewBehavior()`

### Known Issues / TODOs
- [ ] Edge case X not yet handled
```

---

## Recent Changes

### 2026-01-16: HTTP Network Loading Support

**Summary:** Added full HTTP network loading support for movies and external casts, matching dirplayer-rs NetManager behavior.

**Rust Reference:**
- `vm-rust/src/player/net_manager.rs` - NetManager implementation
- `vm-rust/src/player/net_task.rs` - NetTask structure
- `vm-rust/src/player/handlers/net.rs` - Network handlers

**New Files:**
1. `sdk/src/main/java/com/libreshockwave/net/NetTask.java` - Network task record
2. `sdk/src/main/java/com/libreshockwave/net/NetResult.java` - Success/Error result
3. `sdk/src/main/java/com/libreshockwave/net/NetManager.java` - HTTP task manager

**Modified Files:**
1. `sdk/.../vm/LingoVM.java` - Real network handler implementations using NetManager
2. `sdk/.../player/CastManager.java` - Added async HTTP cast loading
3. `runtime/.../DirPlayer.java` - Added loadMovieFromUrl() for HTTP loading
4. `runtime/build.gradle` - Added runNetworkTest task

**Features:**
- Async HTTP fetching via Java HttpClient
- Task tracking by ID (matches dirplayer-rs)
- Base URL resolution for relative paths
- External cast loading over HTTP
- All Lingo network handlers implemented (preloadNetThing, netDone, etc.)

**Test:** Run `./gradlew :runtime:runNetworkTest -Purl=http://localhost:8080/habbo.dcr`

---

### 2026-01-16: Runtime Uses SDK's LingoVM

**Summary:** Refactored runtime to use SDK's LingoVM for bytecode execution instead of duplicating opcode handling.

**Rust Reference:**
- `vm-rust/src/player/bytecode/handler_manager.rs` - Opcode dispatch pattern
- `vm-rust/src/player/scope.rs` - Scope management

**Changes:**
1. `DirPlayer.java` - Now uses `LingoVM` from SDK for bytecode execution
2. `HandlerExecutionResult.java` - New enum for handler execution results (ADVANCE, JUMP, STOP, ERROR)
3. `BytecodeHandlerContext.java` - Simplified context class for runtime extensions
4. Removed duplicated `BytecodeExecutor.java` - SDK's LingoVM is the single source of truth

**Architecture:**
```
SDK (libreshockwave)
├── LingoVM            <- Single source of truth for bytecode execution
├── Scope              <- Execution scope used internally by LingoVM
└── 60+ built-in handlers (math, lists, strings, etc.)

Runtime
├── DirPlayer          <- Uses LingoVM, adds player-specific handlers (go, play, stop)
├── ExecutionScope     <- Utility class for runtime-specific scope tracking
├── HandlerExecutionResult <- Result enum matching dirplayer-rs
└── BytecodeHandlerContext <- Context for player extensions
```

---

### 2026-01-16: Added External Cast Support

**Summary:** Added support for external cast libraries like dirplayer-rs.

**Rust Reference:**
- `vm-rust/src/player/cast_manager.rs` - CastManager implementation
- `vm-rust/src/player/cast_lib.rs` - CastLib structure

**Features:**
- Detects external casts from MCsL chunk entries
- Normalizes cast paths to .cct format
- Supports three preload modes
- Caches loaded external cast files

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

---

## dirplayer-rs Key Files Reference

| Area | Rust File | Purpose |
|------|-----------|---------|
| File Parsing | `director/file.rs` | Main file parser |
| Chunk Parsing | `director/chunks/*.rs` | Individual chunk parsers |
| Score | `director/chunks/score.rs` | VWSC chunk parsing |
| Score Runtime | `player/score.rs` | begin/endSprites, frame execution |
| NetManager | `player/net_manager.rs` | Network task management |
| Net Handlers | `player/handlers/net.rs` | preloadNetThing, netDone, etc. |
| Cast Manager | `player/cast_manager.rs` | Cast library management |
| Cast Lib | `player/cast_lib.rs` | Individual cast loading |
| Bytecode | `player/bytecode/*.rs` | Opcode handlers |

---

*Last updated: January 2026*
