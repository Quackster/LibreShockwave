# LibreShockwave - Java SDK for Director/Shockwave Files

## Project Overview

LibreShockwave is a Java SDK for reading, parsing, and executing Macromedia/Adobe Director and Shockwave files (.dir, .dxr, .dcr, .cst). This project is a port of the [dirplayer-rs](https://github.com/user/dirplayer-rs) Rust implementation to Java, providing a native JVM solution for Director file manipulation.

## Goals

### Primary Objectives
1. **Complete file format support** - Parse all Director file formats including uncompressed (.dir/.dxr) and Afterburner-compressed (.dcr) files
2. **Full Lingo execution** - Interpret and execute Lingo bytecode with complete opcode coverage
3. **Asset extraction** - Extract bitmaps, sounds, text, and other cast members
4. **Script analysis** - Decompile and analyze Lingo scripts for research/preservation

### Use Cases
- Digital preservation of legacy Director content
- Asset extraction from Shockwave games/applications
- Educational tools for understanding Director internals
- Building modern players/viewers for Director content

## Architecture

### Package Structure

```
com.libreshockwave
├── io/                     # Binary I/O utilities
│   └── BinaryReader        # Endian-aware binary reading, zlib, FourCC
├── format/                 # File format definitions
│   └── ChunkType           # FourCC chunk type enumeration
├── chunks/                 # Chunk parsers (15 types)
│   ├── Chunk               # Sealed interface for all chunks
│   ├── ConfigChunk         # Movie configuration (DRCF/VWCF)
│   ├── KeyTableChunk       # Resource mapping (KEY*)
│   ├── CastListChunk       # Cast library list (MCsL)
│   ├── CastChunk           # Cast member index (CAS*)
│   ├── CastMemberChunk     # Individual cast member (CASt)
│   ├── ScriptContextChunk  # Script context (Lctx)
│   ├── ScriptNamesChunk    # Script name table (Lnam)
│   ├── ScriptChunk         # Bytecode + handlers (Lscr)
│   ├── ScoreChunk          # Timeline data (VWSC)
│   ├── BitmapChunk         # Bitmap data (BITD)
│   ├── PaletteChunk        # Color palette (CLUT)
│   ├── TextChunk           # Rich text (STXT)
│   ├── SoundChunk          # Audio data (snd)
│   └── RawChunk            # Unknown/raw chunks
├── lingo/                  # Lingo language types
│   ├── Opcode              # 84 bytecode opcodes
│   ├── Datum               # Sealed interface (35+ value types)
│   ├── DatumType           # Type enumeration
│   ├── StringChunkType     # char/word/line/item
│   └── LingoException      # Runtime errors
├── cast/                   # Cast member definitions
│   ├── MemberType          # 18 member types
│   ├── CastMember          # Unified member container
│   ├── BitmapInfo          # Bitmap-specific data
│   ├── ShapeInfo           # Vector shape data
│   ├── FilmLoopInfo        # Film loop properties
│   └── ScriptType          # movie/behavior/parent
├── player/                 # Runtime player
│   ├── Sprite              # Runtime sprite state
│   ├── Score               # Frame/channel management
│   ├── Palette             # Built-in palettes + InkMode
│   └── bitmap/             # Bitmap processing
│       ├── Bitmap          # RGBA pixel buffer
│       ├── BitmapDecoder   # RLE decompression
│       └── Drawing         # Ink mode blending
├── vm/                     # Virtual machine
│   ├── LingoVM             # Bytecode interpreter
│   └── Scope               # Execution scope
├── handlers/               # Built-in Lingo functions
│   ├── HandlerRegistry     # Unified registration
│   ├── MathHandlers        # abs, sqrt, sin, cos, random...
│   ├── StringHandlers      # length, chars, word, offset...
│   └── ListHandlers        # count, getAt, add, sort, getProp...
└── DirectorFile            # Main entry point
```

### Key Design Decisions

#### 1. Sealed Interfaces for Type Safety
We use Java 17+ sealed interfaces for `Datum` and `Chunk` types, providing:
- Exhaustive pattern matching in switch expressions
- Compile-time verification of type handling
- Clean record-based implementations

```java
public sealed interface Datum permits
    Datum.DInt, Datum.DFloat, Datum.Str, Datum.Symbol,
    Datum.DList, Datum.PropList, Datum.IntPoint, Datum.IntRect,
    Datum.CastMemberRef, Datum.ScriptInstanceRef, ... {

    record DInt(int value) implements Datum { ... }
    record Str(String value) implements Datum { ... }
    // ...35+ types
}
```

#### 2. Endian-Aware Binary Reading
Director files can be big-endian (Mac) or little-endian (Windows). `BinaryReader` handles this transparently:

```java
BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);
int value = reader.readInt();      // Respects byte order
String fourcc = reader.readFourCC(); // Always big-endian for tags
```

#### 3. Chunk-Based Parsing
Files are parsed chunk-by-chunk, allowing lazy loading and streaming:

```java
DirectorFile file = DirectorFile.open(path);
for (Chunk chunk : file.getChunks()) {
    switch (chunk) {
        case ScriptChunk script -> analyzeScript(script);
        case BitmapChunk bitmap -> extractBitmap(bitmap);
        // ...
    }
}
```

## Current Implementation Status

### Completed (v0.1)

| Feature | Status | Notes |
|---------|--------|-------|
| RIFX container parsing | Done | Big/little endian detection |
| Config chunk (DRCF/VWCF) | Done | Stage size, FPS, version |
| Key table (KEY*) | Done | Resource ID mapping |
| Cast chunks | Done | CAS*, CASt, MCsL |
| Script parsing | Done | Lctx, Lnam, Lscr with bytecode |
| Score parsing | Done | VWSC frame/sprite data |
| Bitmap parsing | Done | BITD with RLE decompression |
| Palette handling | Done | Built-in + custom palettes |
| VM core (40+ opcodes) | Done | Stack ops, arithmetic, flow control |
| Built-in handlers (70+) | Done | Math, string, list operations |
| Ink mode blending | Done | 20 ink modes implemented |
| Bit depth conversion | Done | 1, 2, 4, 8, 16, 32-bit |

### In Progress (v0.2)

| Feature | Status | Notes |
|---------|--------|-------|
| Afterburner (.dcr) | In Progress | Zlib decompression, ILS/ABMP parsing |
| Remaining VM opcodes | Pending | ~34 advanced opcodes |
| Film loop rendering | Pending | Recursive sprite compositing |
| Sound members | Pending | Audio extraction/playback |
| Integration tests | Pending | Real file testing |

### Planned (v0.3+)

| Feature | Notes |
|---------|-------|
| Video members | Digital video cast members |
| Xtra stubs | Common Xtra function stubs |
| Script decompiler | Bytecode to Lingo source |
| Projector support | Standalone .exe/.app files |

## File Format Reference

### RIFX Container Structure

```
RIFX/XFIR (4 bytes) - Format identifier
Length (4 bytes)    - Total file length
Codec (4 bytes)     - "MV93" (uncompressed) or "FGDM" (Afterburner)

[Chunks...]
  FourCC (4 bytes)  - Chunk type
  Length (4 bytes)  - Chunk data length
  Data (N bytes)    - Chunk content
```

### Afterburner (.dcr) Structure

Afterburner files use zlib compression with this structure:

```
RIFX header (as above, codec = "FGDM" or "FGDC")

Fver chunk - File version metadata
  - Director version
  - imap version
  - Version string (optional)

Fcdr chunk - Compression directory (zlib compressed)
  - Array of MoaID compression type GUIDs
  - Compression type description strings

ABMP chunk - Resource map (zlib compressed)
  - Resource count
  - For each resource:
    - Resource ID (variable-length int)
    - Offset in ILS segment
    - Compressed size
    - Uncompressed size
    - Compression type index
    - FourCC chunk type

FGEI/ILS chunk - Initial Load Segment
  - Concatenated zlib-compressed chunk data
  - Resources accessed by offset from ABMP
```

### Compression Types

| GUID | Name | Description |
|------|------|-------------|
| AC99E904-0070-0B36-... | ZLIB | Standard zlib compression |
| 7204A889-AFD0-11CF-... | SND | Sound-specific encoding |
| AC99982E-005D-0D50-... | NULL | No compression (raw data) |
| 8A4679A1-3720-11D0-... | FONTMAP | Font mapping (not implemented) |

## Lingo VM Architecture

### Execution Model

The Lingo VM is a stack-based interpreter:

```
┌─────────────────────────────────────────┐
│                LingoVM                   │
├─────────────────────────────────────────┤
│  Stack: [Datum...]                      │  ← Operand stack
│  Globals: Map<String, Datum>            │  ← Global variables
│  Scopes: Stack<Scope>                   │  ← Call stack
│  Builtins: Map<String, Handler>         │  ← Built-in functions
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│                 Scope                    │
├─────────────────────────────────────────┤
│  Locals: Map<String, Datum>             │  ← Local variables
│  Args: List<Datum>                      │  ← Handler arguments
│  ReturnValue: Datum                     │  ← Return value
│  PC: int                                │  ← Program counter
└─────────────────────────────────────────┘
```

### Opcode Categories

| Category | Opcodes | Examples |
|----------|---------|----------|
| Stack | 15 | PUSH_INT, PUSH_FLOAT, PUSH_STRING, POP |
| Arithmetic | 8 | ADD, SUB, MUL, DIV, MOD, NEGATE |
| Comparison | 10 | EQ, NEQ, LT, GT, AND, OR, NOT, CONTAINS |
| Flow Control | 8 | JMP, JMP_IF_ZERO, CALL, RETURN |
| Variables | 12 | GET_GLOBAL, SET_GLOBAL, GET_LOCAL, GET_PROPERTY |
| Objects | 10 | GET_MEMBER, SET_MEMBER, NEW, CALL_METHOD |
| Strings | 6 | CONCAT, GET_CHUNK, HILITE_CHUNK |
| Lists | 8 | GET_AT, SET_AT, APPEND, DELETE_AT |
| Special | 7 | THE_ENTITY, SPRITE_REF, TELL, END_TELL |

## Building

### Requirements
- Java 24 (for sealed types, records, pattern matching)
- Gradle 8.x (wrapper included)

### Compile
```bash
# Build with Gradle
./gradlew build

# Or on Windows
gradlew.bat build
```

### Run Tests
```bash
./gradlew runTests
```

### Run with a Director File
```bash
./gradlew runPlayer -Pfile=path/to/movie.dir
```

### Create JAR
```bash
./gradlew jar
# Output: build/libs/libreshockwave-0.1.0.jar
```

### Usage Example

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.vm.LingoVM;

// Load a Director file
DirectorFile file = DirectorFile.open("movie.dir");

// Access configuration
ConfigChunk config = file.getConfig();
System.out.println("Stage: " + config.stageWidth() + "x" + config.stageHeight());
System.out.println("FPS: " + config.tempo());

// Extract scripts
for (ScriptChunk script : file.getScripts()) {
    System.out.println("Script: " + script.getName());
    for (var handler : script.getHandlers()) {
        System.out.println("  Handler: " + handler.name());
    }
}

// Execute Lingo
LingoVM vm = new LingoVM(file);
vm.callHandler("startMovie");
```

## Contributing

### Areas Needing Work
1. **Afterburner support** - Complete the .dcr file parsing
2. **VM opcodes** - Implement remaining ~34 opcodes
3. **Testing** - Add unit tests with real Director files
4. **Documentation** - Document chunk formats and opcodes

### Code Style
- Use Java 21 features (records, sealed types, pattern matching)
- Prefer immutable data structures
- Follow existing naming conventions
- Add Javadoc for public APIs

## References

- [dirplayer-rs](https://github.com/user/dirplayer-rs) - Original Rust implementation
- [Shockwave Reversing](https://github.com/user/shockwave-reversing) - Format documentation
- [ScummVM Director Engine](https://github.com/scummvm/scummvm/tree/master/engines/director) - C++ reference
- [OpenShockwave](https://github.com/user/openshockwave) - Another open-source effort

## License

This project is licensed under the MIT License. See LICENSE file for details.

---

*LibreShockwave is not affiliated with Adobe, Macromedia, or any original Director/Shockwave developers. Director and Shockwave are trademarks of Adobe Inc.*
