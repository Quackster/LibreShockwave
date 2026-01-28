# LibreShockwave SDK

A pure Java library for reading and parsing Macromedia/Adobe Director and Shockwave files (.dir, .dxr, .dcr, .cct, .cst).

## Overview

LibreShockwave SDK parses Director files and provides programmatic access to all content:
- **Cast members** - Bitmaps, text, scripts, sounds, shapes, etc.
- **Scripts** - Handler names, arguments, local variables, and raw bytecode
- **Score data** - Timeline frames, sprite channels, frame labels
- **Metadata** - Stage dimensions, tempo, Director version

This is a **read-only SDK** - it does not execute Lingo bytecode. Use this library to extract assets, analyze bytecode, or build your own interpreter.

## Features

### File Format Support
- **RIFX container** - Both big-endian (Mac) and little-endian (Windows) files
- **Afterburner compression** - Zlib-compressed Shockwave files (.dcr, .cct)
- **Director versions** - D4 through D12

### Asset Extraction
- **Bitmaps** - Decode to PNG with full palette support (1/2/4/8/16/32-bit depths)
- **Text** - Extract styled text content (STXT chunks)
- **Sounds** - Multiple format support:
  - PCM audio to WAV (automatic endian conversion, header detection)
  - MP3 extraction (finds sync bytes, parses frame boundaries, strips padding)
  - IMA ADPCM decoding to 16-bit PCM
- **Palettes** - Built-in Director palettes (System Mac/Win, Grayscale, Rainbow, etc.) plus custom CLUT extraction

### Script Analysis
- **Bytecode disassembly** - Full Lingo VM opcode support with symbol resolution
- **Lingo decompilation** - Reconstruct source code from bytecode
- **Symbol tables** - Handler names, arguments, local variables, globals, properties

### Score/Timeline Data
- Frame count and channel count
- Frame labels
- Behavior/script intervals

### File Writing
- **Save to RIFX** - Write standard Director files
- **Unprotect** - Remove protection from protected movies
- **Script restoration** - Decompile and embed Lingo source into cast members

## Building

Requires Java 21+.

```bash
./gradlew build
```

## Quick Start

```java
import com.libreshockwave.DirectorFile;
import java.nio.file.Path;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
file.printSummary();  // Print overview of file contents
```

## Complete API Examples

### Loading a File

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import java.nio.file.Path;

// Load from file path
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Or load from byte array
byte[] data = Files.readAllBytes(Path.of("movie.dcr"));
DirectorFile file = DirectorFile.load(data);
```

### Reading File Metadata

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Basic info
System.out.println("Afterburner (compressed): " + file.isAfterburner());
System.out.println("Endian: " + file.getEndian());
System.out.println("Movie type: " + file.getMovieType());

// Stage configuration
ConfigChunk config = file.getConfig();
System.out.println("Stage size: " + file.getStageWidth() + "x" + file.getStageHeight());
System.out.println("Tempo: " + file.getTempo() + " fps");
System.out.println("Director version: " + config.directorVersion());
System.out.println("Sprite channels: " + file.getChannelCount());

// External cast references
if (file.hasExternalCasts()) {
    System.out.println("External casts:");
    for (String path : file.getExternalCastPaths()) {
        System.out.println("  - " + path);
    }
}
```

### Listing All Cast Members

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

for (CastMemberChunk member : file.getCastMembers()) {
    System.out.printf("ID: %d, Name: '%s', Type: %s%n",
        member.id(),
        member.name(),
        member.type()
    );

    // Check specific types
    if (member.isBitmap()) {
        System.out.println("  -> Bitmap member");
    } else if (member.isScript()) {
        System.out.println("  -> Script member, scriptId: " + member.scriptId());
    } else if (member.isText()) {
        System.out.println("  -> Text member");
    }
}
```

### Extracting Bitmaps

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.bitmap.Bitmap;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.*;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
Files.createDirectories(Path.of("output"));

for (CastMemberChunk member : file.getCastMembers()) {
    if (!member.isBitmap()) continue;

    file.decodeBitmap(member).ifPresent(bitmap -> {
        String name = member.name().isEmpty()
            ? "member_" + member.id()
            : member.name().replaceAll("[^a-zA-Z0-9]", "_");

        try {
            // Save as PNG
            ImageIO.write(bitmap.toBufferedImage(), "PNG",
                new File("output/" + name + ".png"));

            System.out.printf("Saved %s (%dx%d, %d-bit)%n",
                name, bitmap.getWidth(), bitmap.getHeight(), bitmap.getBitDepth());
        } catch (Exception e) {
            System.err.println("Failed to save " + name + ": " + e.getMessage());
        }
    });
}
```

### Extracting Text Content

Director has two types of text cast members:
- **Field** (type 3) - Old-style editable text, simpler formatting
- **Text** (type 12) - Rich text with anti-aliasing and advanced formatting

Both store content in STXT chunks. Use `hasTextContent()` to find both, or `isField()`/`isText()` to distinguish.

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import java.nio.file.*;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
KeyTableChunk keyTable = file.getKeyTable();

for (CastMemberChunk member : file.getCastMembers()) {
    // Check for any text content (Field or Text)
    if (!member.hasTextContent()) continue;

    // Distinguish between Field and Text
    String textType = member.isField() ? "Field" : "Text";

    // Find the STXT chunk for this member via key table
    for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
        if (entry.fourccString().equals("STXT")) {
            Chunk chunk = file.getChunk(entry.sectionId());
            if (chunk instanceof TextChunk textChunk) {
                System.out.printf("=== %s (%s) ===%n", member.name(), textType);
                System.out.println(textChunk.text());
                System.out.println();

                // Save to file with type prefix
                String filename = member.name().isEmpty()
                    ? textType.toLowerCase() + "_" + member.id() + ".txt"
                    : member.name() + ".txt";
                Files.writeString(Path.of("output", filename), textChunk.text());
            }
            break;
        }
    }
}
```

### Extracting Palettes

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.PaletteChunk;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Access built-in Director palettes
Palette systemMac = Palette.getBuiltIn(Palette.SYSTEM_MAC);
Palette grayscale = Palette.getBuiltIn(Palette.GRAYSCALE);
Palette systemWin = Palette.getBuiltIn(Palette.SYSTEM_WIN);

System.out.println("System Mac palette: " + systemMac.size() + " colors");

// Get individual colors from a palette
int firstColor = systemMac.getColor(0);  // Returns 0xRRGGBB
int[] rgb = systemMac.getRGB(0);         // Returns [R, G, B] array

// Extract custom palettes (CLUT chunks) from the file
for (PaletteChunk palette : file.getPalettes()) {
    System.out.printf("Palette %d: %d colors%n", palette.id(), palette.colorCount());

    // Access individual colors
    for (int i = 0; i < palette.colorCount(); i++) {
        int color = palette.getColor(i);
        System.out.printf("  Color %d: #%06X%n", i, color);
    }
}

// Create a visual swatch image from a palette
Bitmap swatch = Bitmap.createPaletteSwatch(systemMac, 16);  // 16x16 pixel swatches
ImageIO.write(swatch.toBufferedImage(), "PNG", new File("palette_swatch.png"));

// Create swatch from custom palette colors
if (!file.getPalettes().isEmpty()) {
    PaletteChunk customPalette = file.getPalettes().get(0);
    Bitmap customSwatch = Bitmap.createPaletteSwatch(customPalette.colors(), 16, 16);
    ImageIO.write(customSwatch.toBufferedImage(), "PNG",
        new File("custom_palette_" + customPalette.id() + ".png"));
}
```

### Extracting Sounds

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.audio.SoundConverter;
import java.nio.file.*;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
KeyTableChunk keyTable = file.getKeyTable();
Files.createDirectories(Path.of("output/sounds"));

for (CastMemberChunk member : file.getCastMembers()) {
    if (!member.isSound()) continue;

    // Find the snd chunk via key table
    for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
        if (entry.fourccString().equals("snd ")) {
            Chunk chunk = file.getChunk(entry.sectionId());
            if (chunk instanceof SoundChunk sound) {
                String name = member.name().isEmpty()
                    ? "sound_" + member.id()
                    : member.name().replaceAll("[^a-zA-Z0-9]", "_");

                if (sound.isMp3()) {
                    // Extract MP3 data directly
                    byte[] mp3Data = SoundConverter.extractMp3(sound);
                    if (mp3Data != null) {
                        Files.write(Path.of("output/sounds/" + name + ".mp3"), mp3Data);
                        System.out.println("Extracted MP3: " + name);
                    }
                } else {
                    // Convert PCM to WAV
                    byte[] wavData = SoundConverter.toWav(sound);
                    Files.write(Path.of("output/sounds/" + name + ".wav"), wavData);
                    System.out.printf("Extracted WAV: %s (%d Hz, %d-bit)%n",
                        name, sound.sampleRate(), sound.bitsPerSample());
                }
            }
            break;
        }
    }
}
```

### Reading Scripts and Bytecode

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Opcode;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
ScriptNamesChunk names = file.getScriptNames();

System.out.println("=== Scripts ===");
System.out.println("Total scripts: " + file.getScripts().size());

for (ScriptChunk script : file.getScripts()) {
    System.out.println("\nScript ID: " + script.id());

    for (ScriptChunk.Handler handler : script.handlers()) {
        // Get handler name
        String handlerName = names.getName(handler.nameId());

        // Get argument names
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < handler.argNameIds().size(); i++) {
            if (i > 0) args.append(", ");
            args.append(names.getName(handler.argNameIds().get(i)));
        }

        System.out.printf("  on %s(%s)%n", handlerName, args);
        System.out.printf("    Args: %d, Locals: %d, Instructions: %d%n",
            handler.argCount(), handler.localCount(), handler.instructions().size());

        // Print local variable names
        if (!handler.localNameIds().isEmpty()) {
            System.out.print("    Locals: ");
            for (int i = 0; i < handler.localNameIds().size(); i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(names.getName(handler.localNameIds().get(i)));
            }
            System.out.println();
        }

        // Print bytecode
        System.out.println("    Bytecode:");
        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            Opcode op = instr.opcode();
            long arg = instr.argument();

            System.out.printf("      [%04d] %-20s", instr.offset(), op.getMnemonic());

            // Decode argument based on opcode type
            if (op.isNameBased()) {
                // Opcodes that reference names (symbols, properties, etc.)
                System.out.print(" " + names.getName((int) arg));
            } else if (op == Opcode.PUSH_INT8 || op == Opcode.PUSH_INT16 || op == Opcode.PUSH_INT32) {
                System.out.print(" " + arg);
            } else if (op == Opcode.PUSH_FLOAT32) {
                System.out.print(" " + Float.intBitsToFloat((int) arg));
            } else if (op == Opcode.JMP || op == Opcode.JMP_IF_Z) {
                System.out.print(" -> [" + (instr.offset() + arg) + "]");
            } else if (instr.rawOpcode() >= 0x40) {
                System.out.print(" " + arg);
            }
            System.out.println();
        }
    }
}
```

### Using the Built-in Disassembler

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Disassemble all scripts with formatted output
for (ScriptChunk script : file.getScripts()) {
    file.disassembleScript(script);
}
```

### Reading Score (Timeline) Data

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;

DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

if (file.hasScore()) {
    ScoreChunk score = file.getScoreChunk();
    System.out.println("Frames: " + score.getFrameCount());
    System.out.println("Channels: " + score.getChannelCount());

    // Frame labels
    FrameLabelsChunk labels = file.getFrameLabelsChunk();
    if (labels != null) {
        System.out.println("\nFrame Labels:");
        for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
            System.out.printf("  Frame %d: '%s'%n", label.frameNum(), label.label());
        }
    }

    // Frame script intervals (behaviors attached to frame ranges)
    System.out.println("\nFrame Script Intervals:");
    for (ScoreChunk.FrameInterval interval : score.frameIntervals()) {
        System.out.printf("  Frames %d-%d: scriptId=%d, channel=%d%n",
            interval.startFrame(), interval.endFrame(),
            interval.scriptId(), interval.channel());
    }
}
```

### Accessing Raw Chunks

```java
DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));

// Get all chunk metadata
for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
    System.out.printf("Chunk %d: %s, offset=%d, length=%d%n",
        info.id(), info.type(), info.offset(), info.length());
}

// Get a specific chunk by ID
Chunk chunk = file.getChunk(42);

// Get a chunk with type checking
file.getChunk(42, BitmapChunk.class).ifPresent(bitmapChunk -> {
    System.out.println("Raw bitmap data length: " + bitmapChunk.data().length);
});
```

### Working with External Cast Files

```java
import com.libreshockwave.DirectorFile;
import java.nio.file.*;

// Load main movie
DirectorFile movie = DirectorFile.load(Path.of("movie.dcr"));
Path baseDir = Path.of("movie.dcr").getParent();

// Check for external casts
for (String castPath : movie.getExternalCastPaths()) {
    System.out.println("External cast: " + castPath);

    // Normalize path and load
    String fileName = Path.of(castPath).getFileName().toString();
    // Convert .cst to .cct if needed
    if (fileName.endsWith(".cst")) {
        fileName = fileName.replace(".cst", ".cct");
    }

    Path fullPath = baseDir.resolve(fileName);
    if (Files.exists(fullPath)) {
        DirectorFile castFile = DirectorFile.load(fullPath);
        System.out.println("  Loaded " + castFile.getCastMembers().size() + " members");

        // Process cast members from external cast...
        for (CastMemberChunk member : castFile.getCastMembers()) {
            System.out.println("    - " + member.name() + " (" + member.type() + ")");
        }
    }
}
```

### Saving and Unprotecting Files

```java
import com.libreshockwave.DirectorFile;
import java.nio.file.Path;

// Load a protected/compressed DCR file
DirectorFile file = DirectorFile.load(Path.of("protected_movie.dcr"));

// Save as unprotected RIFX file
// This automatically:
// 1. Removes protection flags
// 2. Decompiles Lingo bytecode to source text
// 3. Embeds the source into cast members
file.save(Path.of("unprotected_movie.dir"));

// Or get the bytes directly
byte[] rifxData = file.saveToBytes();
```

### Complete Asset Dump Example

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.bitmap.Bitmap;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;

public class AssetDumper {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: AssetDumper <file.dcr>");
            return;
        }

        DirectorFile file = DirectorFile.load(Path.of(args[0]));
        Path outputDir = Path.of("extracted");
        Files.createDirectories(outputDir);
        Files.createDirectories(outputDir.resolve("bitmaps"));
        Files.createDirectories(outputDir.resolve("text"));
        Files.createDirectories(outputDir.resolve("scripts"));

        KeyTableChunk keyTable = file.getKeyTable();
        ScriptNamesChunk names = file.getScriptNames();

        int bitmapCount = 0, textCount = 0, scriptCount = 0;

        for (CastMemberChunk member : file.getCastMembers()) {
            String safeName = sanitize(member.name(), member.id());

            if (member.isBitmap()) {
                file.decodeBitmap(member).ifPresent(bitmap -> {
                    try {
                        ImageIO.write(bitmap.toBufferedImage(), "PNG",
                            outputDir.resolve("bitmaps/" + safeName + ".png").toFile());
                    } catch (IOException e) { e.printStackTrace(); }
                });
                bitmapCount++;
            }
            else if (member.isText() && keyTable != null) {
                for (var entry : keyTable.getEntriesForOwner(member.id())) {
                    if (entry.fourccString().equals("STXT")) {
                        Chunk chunk = file.getChunk(entry.sectionId());
                        if (chunk instanceof TextChunk tc && !tc.text().isEmpty()) {
                            Files.writeString(
                                outputDir.resolve("text/" + safeName + ".txt"),
                                tc.text());
                            textCount++;
                        }
                        break;
                    }
                }
            }
        }

        // Dump scripts
        try (PrintWriter pw = new PrintWriter(
                outputDir.resolve("scripts/all_scripts.txt").toFile())) {
            for (ScriptChunk script : file.getScripts()) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    pw.println("on " + names.getName(h.nameId()));
                    for (var instr : h.instructions()) {
                        pw.printf("  [%04d] %s %d%n",
                            instr.offset(), instr.opcode().getMnemonic(), instr.argument());
                    }
                    pw.println("end\n");
                    scriptCount++;
                }
            }
        }

        System.out.printf("Extracted: %d bitmaps, %d text files, %d handlers%n",
            bitmapCount, textCount, scriptCount);
    }

    static String sanitize(String name, int id) {
        if (name == null || name.isBlank()) return "member_" + id;
        return name.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + id;
    }
}
```

## Cast Extractor GUI Tool

A built-in GUI tool for batch extracting assets from .cct cast files:

```bash
./gradlew :sdk:extractCasts
```

## Acknowledgments

- **[dirplayer-rs](https://github.com/igorlira/dirplayer-rs)** by **Igor Lira** - A Rust Shockwave player implementation
- **[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays)** by **Debby Servilla** - A Director movie decompiler
- **The ScummVM Director Engine Team** - Director file format research

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
