# The Book of LibreShockwave

> *A Complete Reference for the Java SDK & Runtime*

---

## Table of Contents

1. [The Essence](#the-essence)
2. [Installation & Build](#installation--build)
3. [Core Concepts](#core-concepts)
4. [Loading Director Files](#loading-director-files)
5. [The Datum Type System](#the-datum-type-system)
6. [Executing Lingo](#executing-lingo)
7. [Cast Management](#cast-management)
8. [Score & Sprites](#score--sprites)
9. [Network Operations](#network-operations)
10. [The Web Player](#the-web-player)
11. [Built-in Handlers](#built-in-handlers)
12. [File Format Wisdom](#file-format-wisdom)
13. [Advanced Topics](#advanced-topics)

---

## The Essence

LibreShockwave is a Java 21+ SDK for reading, parsing, and executing Macromedia/Adobe Director & Shockwave files. It breathes life into `.dir`, `.dxr`, `.dcr`, and `.cst` files.

```
+------------------------------------------------------------------+
|                       LibreShockwave                              |
+------------------------------------------------------------------+
|  SDK (sdk/)           |  Runtime (runtime/)                       |
|  -----------------    |  ------------------------------------     |
|  File Parsing         |  Movie Playback (DirPlayer)               |
|  Chunk Decoding       |  Web Player (WebPlayer)                   |
|  LingoVM              |  Frame Navigation                         |
|  Built-in Handlers    |  Event Dispatching                        |
|  Network Manager      |  Sprite Management                        |
+------------------------------------------------------------------+
```

---

## Installation & Build

### Prerequisites

- **Java 21+** (required)
- **Gradle** (wrapper included)

### Build Commands

```bash
# Build everything
./gradlew buildAll

# Build individual projects
./gradlew :sdk:build
./gradlew :runtime:build

# Run tests
./gradlew runTests              # SDK integration tests
./gradlew runDcrTests           # DCR/Afterburner tests
./gradlew runBytecodeTests      # Bytecode execution tests
./gradlew runRuntimeTests       # Runtime tests

# Run with a specific file
./gradlew runDcrTests -Pfile=path/to/movie.dcr

# Run the player
./gradlew runPlayer -Pfile=path/to/movie.dcr
./gradlew runPlayer -Pfile=http://localhost:8080/movie.dcr

# Run the web player
./gradlew :runtime:runWebPlayer
./gradlew :runtime:runWebPlayer -Pport=3000
```

---

## Core Concepts

### The Hierarchy

```
DirectorFile
├── Chunks (parsed file segments)
│   ├── Container: RIFX, imap, mmap, KEY*, CAS*
│   └── Content: Lctx, Lnam, Lscr, BITD, CLUT, snd, STXT
│
├── CastManager
│   └── CastLib[]
│       └── CastMember[] (bitmaps, scripts, text, sounds)
│
├── Score
│   └── ScoreFrame[]
│       └── Sprite[] (24 channels per frame)
│
└── LingoVM (bytecode execution engine)
    ├── Stack
    ├── Globals
    └── Handlers (60+ built-in)
```

### The Two Projects

| Project | Purpose | Key Classes |
|---------|---------|-------------|
| **sdk/** | File parsing, bytecode VM, data structures | `DirectorFile`, `LingoVM`, `Datum`, `CastManager` |
| **runtime/** | Movie playback, events, player UI | `DirPlayer`, `WebPlayer`, `ExecutionScope` |

---

## Loading Director Files

### From Local Path

```java
import com.libreshockwave.DirectorFile;
import java.nio.file.Path;

// Load any Director file (.dir, .dxr, .dcr, .cst)
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Access metadata
System.out.println("Version: " + file.getVersion());
System.out.println("Chunk count: " + file.getChunks().size());
```

### From Byte Array

```java
byte[] data = Files.readAllBytes(Path.of("movie.dcr"));
DirectorFile file = DirectorFile.load(data);
```

### Supported Extensions

| Extension | Type | Compressed |
|-----------|------|------------|
| `.dir` | Director Movie | No |
| `.dxr` | Protected Director Movie | No |
| `.dcr` | Shockwave Movie (Afterburner) | Yes (zlib) |
| `.cst` | Cast Library | No |

### Detection Logic

```java
// The SDK auto-detects format:
// - RIFX header (bytes 0-3) -> Uncompressed
// - XFIR header -> Big-endian uncompressed
// - IFws/FXws -> Afterburner compressed
```

---

## The Datum Type System

Datum is the universal value type in Lingo. Every value in the VM is a Datum.

### Types

| Type | Description | Example |
|------|-------------|---------|
| `INT` | 32-bit signed integer | `Datum.of(42)` |
| `FLOAT` | 64-bit double precision | `Datum.of(3.14)` |
| `STRING` | UTF-8 string | `Datum.of("hello")` |
| `SYMBOL` | Interned symbol | `Datum.symbol("mySymbol")` |
| `LIST` | Linear list | `Datum.list(a, b, c)` |
| `PROP_LIST` | Property list | `Datum.propList(...)` |
| `VOID` | Null/undefined | `Datum.VOID` |
| `OBJECT` | Script/sprite instance | (internal) |

### Creating Datums

```java
import com.libreshockwave.lingo.Datum;

// Primitives
Datum intVal = Datum.of(100);
Datum floatVal = Datum.of(3.14159);
Datum strVal = Datum.of("Hello, Director!");
Datum sym = Datum.symbol("buttonClicked");

// Void
Datum nothing = Datum.VOID;

// Lists
Datum list = Datum.list(
    Datum.of(1),
    Datum.of(2),
    Datum.of(3)
);

// Property Lists (like dictionaries)
Datum props = Datum.propList()
    .put(Datum.symbol("x"), Datum.of(100))
    .put(Datum.symbol("y"), Datum.of(200));
```

### Type Checking

```java
Datum value = Datum.of(42);

if (value.isInt()) {
    int n = value.intValue();
}

if (value.isString()) {
    String s = value.stringValue();
}

// Get type name (matches Lingo's ilk())
DatumType type = value.getType();  // INT, FLOAT, STRING, etc.
```

### Type Coercion

```java
// To string
String s = datum.toStringValue();

// To integer (truncates floats)
int i = datum.toInt();

// To float
double d = datum.toFloat();
```

---

## Executing Lingo

### The LingoVM

LingoVM is the bytecode interpreter. It's the single source of truth for Lingo execution.

```java
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.DirectorFile;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
LingoVM vm = new LingoVM(file);

// Execute a handler by name
Datum result = vm.callHandler("myHandler", Datum.of(1), Datum.of(2));
```

### Using DirPlayer for Movie Execution

For full movie playback with events and frame navigation:

```java
import com.libreshockwave.runtime.DirPlayer;

DirPlayer player = new DirPlayer();
player.loadMovie(Path.of("movie.dcr"));

// Dispatch movie events
player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
player.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);

// Start playback
player.play();

// Game loop
while (player.isPlaying()) {
    player.tick();
    Thread.sleep(1000 / player.getTempo());
}
```

### Movie Events

| Event | When Fired |
|-------|------------|
| `PREPARE_MOVIE` | Before movie starts |
| `START_MOVIE` | Movie begins playing |
| `STOP_MOVIE` | Movie stops |
| `PREPARE_FRAME` | Before each frame |
| `ENTER_FRAME` | Frame becomes active |
| `EXIT_FRAME` | Frame is about to change |
| `IDLE` | During idle time |

### Frame Navigation

```java
player.goToFrame(10);           // Jump to frame 10
player.goToLabel("intro");      // Jump to frame label
player.nextFrame();             // Advance one frame
player.prevFrame();             // Go back one frame
player.getCurrentFrame();       // Get current frame number
player.getLastFrame();          // Get total frame count
```

---

## Cast Management

### The Cast Hierarchy

```
CastManager
├── CastLib (internal, number=1)
│   ├── CastMember #1 (bitmap)
│   ├── CastMember #2 (script)
│   └── CastMember #3 (sound)
│
├── CastLib (external, number=2, path="sprites.cct")
│   └── (loaded on demand)
│
└── CastLib (external, number=3, path="sounds.cct")
```

### Accessing Casts

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
CastManager casts = file.createCastManager();

// Get all cast libraries
List<CastLib> libs = casts.getCasts();

// Get specific cast by number
CastLib cast1 = casts.getCast(1);

// Get member from cast
CastMemberChunk member = cast1.getMember(5);
```

### External Cast Detection

```java
// Check if movie has external casts
if (file.hasExternalCasts()) {
    List<String> paths = file.getExternalCastPaths();
    // ["sprites.cct", "sounds.cct"]
}
```

### External Cast Loading

```java
// Load from filesystem (relative to movie)
casts.preloadCasts(CastManager.PreloadReason.MOVIE_LOADED);

// Load asynchronously over HTTP
casts.preloadCastsAsync(CastManager.PreloadReason.MOVIE_LOADED)
    .get(120, TimeUnit.SECONDS);
```

### Preload Modes

| Mode | Behavior |
|------|----------|
| When Needed | Lazy load on first member access |
| After Frame One | Load after first frame displays |
| Before Frame One | Load before movie starts |

---

## Score & Sprites

### The Score Structure

The Score contains all frame and sprite data for a movie.

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
Score score = file.createScore();

// Frame info
int frameCount = score.getFrameCount();
ScoreFrame frame = score.getFrame(1);

// Frame labels
String label = score.getLabelAt(10);          // Get label for frame 10
int frameNum = score.getFrameByLabel("menu"); // Get frame number for label
```

### Channel Layout

Director uses reserved channels for special purposes:

| Channel | Purpose |
|---------|---------|
| 0 | Frame script |
| 1 | Palette |
| 2 | Transition |
| 3-4 | Sound |
| 5 | Tempo |
| 6+ | Sprite channels |

### Accessing Sprites

```java
ScoreFrame frame = score.getFrame(currentFrame);

for (Sprite sprite : frame.getSprites()) {
    int channel = sprite.getChannel();
    int castLib = sprite.getCastLib();
    int castMember = sprite.getCastMember();
    int x = sprite.getLocH();
    int y = sprite.getLocV();
    int width = sprite.getWidth();
    int height = sprite.getHeight();
    int ink = sprite.getInk();
}
```

### Sprite Properties

| Property | Description |
|----------|-------------|
| `locH`, `locV` | Position (horizontal, vertical) |
| `width`, `height` | Dimensions |
| `castLib`, `castMember` | Source cast member |
| `ink` | Ink effect (copy, matte, blend, etc.) |
| `foreColor`, `backColor` | Colors |
| `spriteType` | Type of sprite |

---

## Network Operations

### The NetManager

NetManager handles asynchronous HTTP operations.

```java
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;

NetManager net = new NetManager();
net.setBaseUrl("http://example.com/");

// Start async fetch
int taskId = net.preloadNetThing("data.txt");

// Check completion
while (!net.isTaskDone(taskId)) {
    Thread.sleep(100);
}

// Get result
NetResult result = net.getTaskResult(taskId);
if (result instanceof NetResult.Success success) {
    byte[] data = success.data();
    String text = new String(data, StandardCharsets.UTF_8);
}
```

### Loading Movies from HTTP

```java
DirPlayer player = new DirPlayer();

// Load movie from URL
player.loadMovieFromUrl("http://localhost:8080/habbo.dcr")
    .get(60, TimeUnit.SECONDS);

// External casts resolve relative to movie URL
// "fuse_client.cct" -> "http://localhost:8080/fuse_client.cct"

// Preload external casts
player.getCastManager()
    .preloadCastsAsync(CastManager.PreloadReason.MOVIE_LOADED)
    .get(120, TimeUnit.SECONDS);
```

### Lingo Network Handlers

These work automatically when NetManager is available:

| Handler | Purpose |
|---------|---------|
| `preloadNetThing(url)` | Start async fetch, returns task ID |
| `netDone(taskId)` | Check if task is complete |
| `netTextResult(taskId)` | Get downloaded content as string |
| `netError(taskId)` | Get error status ("OK" or error code) |
| `netStatus(taskId)` | Get status ("Complete" or "InProgress") |
| `getStreamStatus(taskId)` | Get detailed status as propList |
| `postNetText(url, data)` | POST request, returns task ID |

---

## The Web Player

### Starting the Web Player

```bash
./gradlew :runtime:runWebPlayer              # http://localhost:8080
./gradlew :runtime:runWebPlayer -Pport=3000  # Custom port
```

### Architecture

```
Browser
├── index.html          # Player UI
├── style.css           # Dark theme styling
├── player.js           # UI controller, canvas rendering
└── libreshockwave-api.js
    ├── WASM Mode       # Loads libreshockwave.wasm
    └── Stub Mode       # Fallback for testing
```

### Controls

- **Play/Stop/Pause** - Playback control
- **Prev/Next** - Frame navigation
- **Frame input** - Jump to specific frame
- **File upload** - Load local .dcr files
- **URL input** - Load from HTTP

### JavaScript API

```javascript
// Initialize
await LibreShockwave.init();

// Load movie
await LibreShockwave.loadMovieFromData(arrayBuffer);

// Playback
LibreShockwave.play();
LibreShockwave.pause();
LibreShockwave.stop();

// Navigation
LibreShockwave.goToFrame(10);
LibreShockwave.nextFrame();
LibreShockwave.prevFrame();

// State
const frame = LibreShockwave.getCurrentFrame();
const lastFrame = LibreShockwave.getLastFrame();
const isPlaying = LibreShockwave.isPlaying();

// Stage info
const width = LibreShockwave.getStageWidth();
const height = LibreShockwave.getStageHeight();

// Sprites
const sprites = LibreShockwave.getSprites();
// Returns array of {channel, locH, locV, width, height, ...}
```

---

## Built-in Handlers

### Math Functions

| Handler | Description |
|---------|-------------|
| `abs(n)` | Absolute value |
| `sqrt(n)` | Square root |
| `sin(n)`, `cos(n)`, `tan(n)` | Trigonometry (radians) |
| `atan(n)` | Arctangent |
| `power(base, exp)` | Exponentiation |
| `random(max)` | Random integer 1 to max |
| `min(a, b)` | Minimum value |
| `max(a, b)` | Maximum value |
| `pi()` | Pi constant |

### Type Conversion

| Handler | Description |
|---------|-------------|
| `integer(v)` | Convert to integer |
| `float(v)` | Convert to float |
| `string(v)` | Convert to string |
| `symbol(s)` | Convert to symbol |
| `value(s)` | Parse string to value |
| `ilk(v)` | Get type name |

### Lists

| Handler | Description |
|---------|-------------|
| `list(...)` | Create list |
| `count(list)` | Number of items |
| `getAt(list, i)` | Get item at index |
| `setAt(list, i, v)` | Set item at index |
| `add(list, v)` | Append item |
| `append(list, v)` | Append item |
| `addAt(list, i, v)` | Insert at index |
| `deleteAt(list, i)` | Remove at index |
| `sort(list)` | Sort in place |
| `getPos(list, v)` | Find index of value |

### Property Lists

| Handler | Description |
|---------|-------------|
| `getProp(plist, key)` | Get property value |
| `setProp(plist, key, v)` | Set property value |
| `addProp(plist, key, v)` | Add property |
| `deleteProp(plist, key)` | Remove property |
| `findPos(plist, key)` | Find property index |

### Strings

| Handler | Description |
|---------|-------------|
| `length(s)` | String length |
| `chars(s, start, end)` | Substring |
| `offset(needle, haystack)` | Find substring |
| `charToNum(c)` | Character to ASCII |
| `numToChar(n)` | ASCII to character |

### Points & Rects

| Handler | Description |
|---------|-------------|
| `point(x, y)` | Create point |
| `rect(l, t, r, b)` | Create rectangle |

### References

| Handler | Description |
|---------|-------------|
| `castLib(n)` | Cast library reference |
| `member(n)` | Cast member reference |
| `sprite(n)` | Sprite reference |
| `sound(n)` | Sound reference |

### Navigation

| Handler | Description |
|---------|-------------|
| `go(frame)` | Go to frame |
| `play(frame)` | Play from frame |
| `updateStage()` | Force stage update |
| `puppetTempo(fps)` | Set tempo |

### Bitwise Operations

| Handler | Description |
|---------|-------------|
| `bitAnd(a, b)` | Bitwise AND |
| `bitOr(a, b)` | Bitwise OR |
| `bitXor(a, b)` | Bitwise XOR |
| `bitNot(a)` | Bitwise NOT |

### System

| Handler | Description |
|---------|-------------|
| `put(...)` | Output to message window |
| `halt()` | Stop execution |
| `alert(msg)` | Display alert |
| `beep()` | System beep |
| `cursor(n)` | Set cursor |

---

## File Format Wisdom

### RIFX Container

All Director files use the RIFX container format (like RIFF, but big-endian capable).

```
RIFX (or XFIR for little-endian)
├── Size (4 bytes)
├── Type (4 bytes: MV93, MC95, etc.)
└── Chunks...
    ├── FourCC (4 bytes)
    ├── Size (4 bytes)
    └── Data (Size bytes)
```

### Afterburner (DCR) Pipeline

Compressed Shockwave files use Afterburner compression:

```
1. Read Fver (format version)
2. Read Fcdr (compressed data reference)
3. Read ABMP (Afterburner map)
4. Read ILS (internal linked segments)
5. Decompress zlib segments via ABMP offsets
6. Expose chunks through standard APIs
```

### Critical Chunks

| Chunk | Purpose |
|-------|---------|
| `RIFX` | Main container |
| `imap` | Initial map (chunk index) |
| `mmap` | Memory map |
| `KEY*` | Key table (chunk associations) |
| `CAS*` | Cast association |
| `Lctx` | Lingo context |
| `Lnam` | Lingo names |
| `Lscr` | Lingo script |
| `VWSC` | Score data |
| `VWLB` | Frame labels |
| `BITD` | Bitmap data |
| `CLUT` | Color lookup table |

---

## Advanced Topics

### ExecutionScope

For direct VM manipulation:

```java
import com.libreshockwave.runtime.ExecutionScope;

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

### HandlerExecutionResult

Results from handler execution:

| Result | Meaning |
|--------|---------|
| `ADVANCE` | Continue to next instruction |
| `JUMP` | Jump to different bytecode position |
| `STOP` | Stop execution |
| `ERROR` | Execution error occurred |

### Native Image / WASM Compilation

For standalone executables or browser deployment:

```bash
# Prerequisites: GraalVM 24.x with native-image

# Build native executable
./gradlew :runtime:nativeCompile

# Copy to player resources
./gradlew :runtime:copyWasmToPlayer
```

### WasmEntry Points (for native/WASM builds)

| Function | Description |
|----------|-------------|
| `init()` | Initialize runtime |
| `loadMovieFromData(ptr, len)` | Load movie bytes |
| `play()`, `stop()`, `pause()` | Playback control |
| `nextFrame()`, `prevFrame()` | Navigation |
| `goToFrame(n)` | Jump to frame |
| `tick()` | Execute one frame |
| `getCurrentFrame()`, `getLastFrame()` | Frame info |
| `getTempo()` | Get FPS |
| `getStageWidth()`, `getStageHeight()` | Stage size |
| `isPlaying()`, `isPaused()` | State queries |
| `getSpriteCount()` | Sprite count |
| `getSpriteData(ptr)` | Get packed sprite data |

---

## Quick Reference Card

```java
// Load a movie
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Create player
DirPlayer player = new DirPlayer();
player.loadMovie(Path.of("movie.dcr"));

// Play the movie
player.play();
while (player.isPlaying()) {
    player.tick();
    Thread.sleep(1000 / player.getTempo());
}

// Access casts
CastManager casts = file.createCastManager();
CastMemberChunk member = casts.getCast(1).getMember(5);

// Access score
Score score = file.createScore();
int frameCount = score.getFrameCount();

// Network loading
player.loadMovieFromUrl("http://example.com/movie.dcr").get();

// Events
player.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);

// Navigation
player.goToFrame(10);
player.goToLabel("menu");
```

---

*May your Shockwave files play forever.*

*LibreShockwave - January 2026*
