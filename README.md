# LibreShockwave SDK

A Java library for parsing Macromedia/Adobe Director and Shockwave files (.dir, .dxr, .dcr, .cct, .cst).

## Requirements

- Java 21 or later

## Building

```bash
./gradlew build
```

## Supported Formats

- RIFX container (big-endian and little-endian)
- Afterburner-compressed files (.dcr, .cct)
- Director versions 4 through 12

## Capabilities

### Reading
- Cast members (bitmaps, text, scripts, sounds, shapes, palettes, fonts)
- Lingo bytecode with symbol resolution
- Score/timeline data (frames, channels, labels, behaviour intervals)
- File metadata (stage dimensions, tempo, version)

### Asset Extraction
- Bitmaps: 1/2/4/8/16/32-bit depths, palette support, PNG export
- Text: Field (type 3) and Text (type 12) cast members via STXT chunks
- Sound: PCM to WAV conversion, MP3 extraction, IMA ADPCM decoding
- Palettes: Built-in Director palettes and custom CLUT chunks

### Writing
- Save to uncompressed RIFX format
- Remove protection from protected files
- Decompile and embed Lingo source into cast members

## Player & Lingo VM

LibreShockwave includes a Lingo bytecode virtual machine and player that can load and run Director movies. The VM executes compiled Lingo scripts, handles score playback, sprite rendering, and external cast loading — bringing `.dcr` and `.dir` files back to life.

<p align="center">
  <img src="https://i.imgur.com/9AcSQQt.gif" alt="LibreShockwave player running a Director movie" />
</p>

The player is available in two forms:
- **Desktop** (`player`) — Swing-based UI with an integrated Lingo debugger
- **Web** (`player-wasm`) — Compiled to WebAssembly via TeaVM, runs in any modern browser

All player functionality is decoupled from the SDK and VM via the `player-core` module, which provides platform-independent playback logic (score traversal, event dispatch, sprite management, bitmap decoding).

<img width="2174" height="1048" alt="Desktop player and web player side by side" src="https://github.com/user-attachments/assets/3c8f0357-0941-4dd4-9795-525a45216644" />

## Screenshots

### Cast Extractor

A GUI tool for browsing and extracting assets from Director files (available on the releases page).

<img width="1127" height="749" alt="Cast Extractor" src="https://github.com/user-attachments/assets/de4f99d2-87ed-4c78-8422-a84bcf9faeca" />

## Usage

### Loading a File

```java
import com.libreshockwave.DirectorFile;
import java.nio.file.Path;

// From file path
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// From byte array
DirectorFile file = DirectorFile.load(bytes);
```

### Accessing Metadata

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

file.isAfterburner();                    // true if compressed
file.getEndian();                        // BIG_ENDIAN (Mac) or LITTLE_ENDIAN (Windows)
file.getStageWidth();                    // stage width in pixels
file.getStageHeight();                   // stage height in pixels
file.getTempo();                         // frames per second
file.getConfig().directorVersion();      // internal version number
file.getChannelCount();                  // sprite channels (48-1000 depending on version)
```

### Iterating Cast Members

```java
for (CastMemberChunk member : file.getCastMembers()) {
    int id = member.id();
    String name = member.name();

    if (member.isBitmap()) { /* ... */ }
    if (member.isScript()) { /* ... */ }
    if (member.isSound()) { /* ... */ }
    if (member.isField()) { /* old-style text */ }
    if (member.isText()) { /* rich text */ }
    if (member.hasTextContent()) { /* either field or text */ }
}
```

### Extracting Bitmaps

```java
for (CastMemberChunk member : file.getCastMembers()) {
    if (!member.isBitmap()) continue;

    file.decodeBitmap(member).ifPresent(bitmap -> {
        BufferedImage image = bitmap.toBufferedImage();
        ImageIO.write(image, "PNG", new File(member.name() + ".png"));
    });
}
```

### Extracting Text

```java
KeyTableChunk keyTable = file.getKeyTable();

for (CastMemberChunk member : file.getCastMembers()) {
    if (!member.hasTextContent()) continue;

    for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
        if (entry.fourccString().equals("STXT")) {
            Chunk chunk = file.getChunk(entry.sectionId());
            if (chunk instanceof TextChunk textChunk) {
                String text = textChunk.text();
            }
            break;
        }
    }
}
```

### Extracting Sounds

```java
import com.libreshockwave.audio.SoundConverter;

for (CastMemberChunk member : file.getCastMembers()) {
    if (!member.isSound()) continue;

    for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
        if (entry.fourccString().equals("snd ")) {
            SoundChunk sound = (SoundChunk) file.getChunk(entry.sectionId());

            if (sound.isMp3()) {
                byte[] mp3 = SoundConverter.extractMp3(sound);
            } else {
                byte[] wav = SoundConverter.toWav(sound);
            }
            break;
        }
    }
}
```

### Accessing Scripts and Bytecode

```java
ScriptNamesChunk names = file.getScriptNames();

for (ScriptChunk script : file.getScripts()) {
    // Script-level declarations
    List<String> globals = script.getGlobalNames(names);
    List<String> properties = script.getPropertyNames(names);

    for (ScriptChunk.Handler handler : script.handlers()) {
        String handlerName = names.getName(handler.nameId());
        int argCount = handler.argCount();
        int localCount = handler.localCount();

        // Argument and local variable names
        for (int id : handler.argNameIds()) {
            String argName = names.getName(id);
        }
        for (int id : handler.localNameIds()) {
            String localName = names.getName(id);
        }

        // Bytecode instructions
        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            int offset = instr.offset();
            Opcode opcode = instr.opcode();
            int argument = instr.argument();
        }
    }
}
```

### Aggregating Globals and Properties

```java
// All unique globals across all scripts
Set<String> allGlobals = file.getAllGlobalNames();

// All unique properties across all scripts
Set<String> allProperties = file.getAllPropertyNames();

// Detailed info per script
for (DirectorFile.ScriptInfo info : file.getScriptInfoList()) {
    info.scriptId();
    info.scriptName();
    info.scriptType();
    info.globals();
    info.properties();
    info.handlers();
}
```

### Reading Score Data

```java
if (file.hasScore()) {
    ScoreChunk score = file.getScoreChunk();
    int frames = score.getFrameCount();
    int channels = score.getChannelCount();

    // Frame labels
    FrameLabelsChunk labels = file.getFrameLabelsChunk();
    if (labels != null) {
        for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
            int frameNum = label.frameNum();
            String labelName = label.label();
        }
    }

    // Behaviour intervals
    for (ScoreChunk.FrameInterval interval : score.frameIntervals()) {
        int start = interval.startFrame();
        int end = interval.endFrame();
        int scriptId = interval.scriptId();
    }
}
```

### Accessing Raw Chunks

```java
// All chunk metadata
for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
    int id = info.id();
    ChunkType type = info.type();
    int offset = info.offset();
    int length = info.length();
}

// Specific chunk by ID
Chunk chunk = file.getChunk(42);

// Type-safe chunk access
file.getChunk(42, BitmapChunk.class).ifPresent(bitmap -> {
    byte[] data = bitmap.data();
});
```

### External Cast Files

```java
for (String castPath : file.getExternalCastPaths()) {
    Path resolved = baseDir.resolve(castPath);
    if (Files.exists(resolved)) {
        DirectorFile castFile = DirectorFile.load(resolved);
    }
}
```

### Saving Files

```java
// Load compressed/protected file
DirectorFile file = DirectorFile.load(Path.of("protected.dcr"));

// Save as unprotected RIFX (decompiles scripts automatically)
file.save(Path.of("unprotected.dir"));

// Or get bytes
byte[] rifxData = file.saveToBytes();
```

## Web Player (player-wasm)

The `player-wasm` module compiles the player for the browser using [TeaVM](https://teavm.org/) v0.13's standard WebAssembly backend. It produces a `.wasm` file with a JS runtime and bridge that runs in all modern browsers.

### Building

```bash
./gradlew :player-wasm:generateWasm
```

### Running

Serve the output directory with any static HTTP server:

```bash
cd player-wasm/build/generated/teavm/wasm/
npx serve .
# Open the URL printed by serve (usually http://localhost:3000)
```

Two player pages are available:
- `http://localhost:8080/` — Full player with debug panel (script browser, bytecode viewer, breakpoints, stack/variable inspection)
- `http://localhost:8080/basic/` — Basic player with just the stage and transport controls

The web player provides:
- File picker for local `.dcr`/`.dir` files
- URL input for remote movies
- Play/Pause/Stop controls
- HTML5 Canvas 2D rendering of bitmaps, shapes, and placeholders
- Browser `fetch()` API for loading external cast libraries
- Lingo debugger with breakpoints and instruction stepping (debug player)

### Module Structure

```
player-wasm/
  build.gradle                          # TeaVM plugin config (standard WASM target)
  src/main/java/.../wasm/
    WasmPlayerApp.java                  # Entry point + @Export API
    WasmPlayer.java                     # Player wrapper (no browser deps)
    render/SoftwareRenderer.java        # RGBA pixel buffer renderer
    net/WasmNetManager.java             # @Import-based fetch NetProvider
  src/main/resources/web/
    index.html                          # Debug player page
    basic/index.html                    # Basic player page (no debug panel)
    player-bridge.js                    # JS bridge (Canvas, rAF, fetch, WASM I/O)
    libreshockwave.css                  # Styling
    worker.js                           # WebWorker (runs WASM VM)
```

### Testing the Web Player

1. **Build the WASM output:**
   ```bash
   ./gradlew :player-wasm:generateWasm
   ```

2. **Start any HTTP server** (plain `file://` won't work due to worker/fetch restrictions):
   ```bash
   cd player-wasm/build/generated/teavm/wasm/
   npx serve .
   ```

3. **Load a movie:**
   - **Local file** — click **Choose File** or press `Ctrl+O` and pick a `.dcr`/`.dir`/`.dxr` file.
   - **URL** — paste a URL into the text box and click **Load URL** (or press `Ctrl+U` then `Enter`).

4. **Playback controls:**
   | Action | Button | Shortcut |
   |--------|--------|----------|
   | Play / Pause | ▶ / ❚❚ | `Space` |
   | Stop | ■ | `Escape` |
   | Step forward | >&#124; | `Right` |
   | Step backward | &#124;< | `Left` |
   | First frame | — | `Home` |
   | Last frame | — | `End` |

5. **Set external parameters** (Shockwave `<PARAM>` tags):
   - Open **Movie > External Params** (or press `Ctrl+E`).
   - Add key/value rows. For Habbo movies click **Habbo Preset** to auto-fill `sw1` with the standard `external_variables.txt` and `external_texts.txt` URLs.
   - Click **Apply**. Parameters are saved in `localStorage` and re-applied on every movie load.

6. **Debugger** (main player page only):
   - Press `Ctrl+D` to toggle the debug panel.
   - Set breakpoints by clicking the gutter in the bytecode view.
   - `F5` Continue, `F10` Step Over, `F11` Step Into, `Shift+F11` Step Out.

All settings (last opened movie URL, external parameters) persist across sessions via `localStorage`.

### Known Limitations

- No mouse/keyboard event forwarding to Lingo VM (planned)
- 32-bit JPEG-based bitmaps (ediM+ALFA) render as placeholders

## Tools

### Cast Extractor GUI

```bash
./gradlew :sdk:extractCasts
```

### Running Tests

```bash
# Unit tests per module
./gradlew :sdk:test
./gradlew :vm:test
./gradlew :player-core:test

# SDK integration / feature tests
./gradlew :sdk:runTests
./gradlew :sdk:runFeatureTests

# Compile the WASM player (no runtime tests — verify in browser)
./gradlew :player-wasm:generateWasm
```

## Architecture

### Modules

| Module | Description |
|--------|-------------|
| `sdk` | Core library for parsing Director/Shockwave files |
| `vm` | Lingo bytecode virtual machine |
| `player-core` | Platform-independent playback engine (score, events, rendering data) |
| `player` | Desktop player with Swing UI and debugger |
| `player-wasm` | Browser player compiled to WebAssembly via TeaVM |
| `cast-extractor` | GUI tool for extracting assets from Director files |

### SDK Packages

- `com.libreshockwave` - Main `DirectorFile` class
- `com.libreshockwave.chunks` - Chunk type parsers (CASt, Lscr, BITD, STXT, etc.)
- `com.libreshockwave.bitmap` - Bitmap decoding and palette handling
- `com.libreshockwave.audio` - Sound conversion utilities
- `com.libreshockwave.lingo` - Opcode definitions and decompiler
- `com.libreshockwave.io` - Binary readers/writers
- `com.libreshockwave.format` - File format utilities (Afterburner, chunk types)
- `com.libreshockwave.cast` - Cast member type definitions

## References

This implementation draws from:

- [dirplayer-rs](https://github.com/igorlira/dirplayer-rs) by Igor Lira
- [ProjectorRays](https://github.com/ProjectorRays/ProjectorRays) by Debby Servilla
- ScummVM Director engine documentation

## Licence

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
