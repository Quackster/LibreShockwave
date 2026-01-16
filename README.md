# LibreShockwave

**Warning: This project is under active development and is not ready for use.**

## Overview

LibreShockwave is an open-source implementation of a Macromedia/Adobe Director and Shockwave player. It aims to preserve and play legacy Director content (.dir, .dxr, .dcr files) that would otherwise be lost as official support has ended.

## Current Status

Major components in development:
- Director file format parsing (RIFX/IFF chunks)
- Lingo bytecode virtual machine
- Cast and score rendering
- External cast library loading
- Basic Xtra support

## Project Structure

- **sdk/** - Core library for parsing Director file formats, Lingo bytecode VM, cast/score management
- **runtime/** - TeaVM-based WebAssembly runtime for browser playback
- **player/** - Standalone Java Swing player for debugging and testing
- **xtras/** - Director Xtras (extensions) such as NetLingo for network operations

## Building

Requires Java 21+.

```
./gradlew build
```

Run the Swing player:
```
./gradlew :player:run
```

## Example: Dumping All Bitmaps

```java
import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.*;
import com.libreshockwave.player.*;
import com.libreshockwave.player.bitmap.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteOrder;
import java.nio.file.*;

public class DumpBitmaps {
    public static void main(String[] args) throws Exception {
        DirectorFile file = DirectorFile.load(Path.of("movie.dcr"));
        CastManager castManager = file.createCastManager();
        KeyTableChunk keyTable = file.getKeyTable();
        ConfigChunk config = file.getConfig();

        boolean bigEndian = file.getEndian() == ByteOrder.BIG_ENDIAN;
        int version = config != null ? config.directorVersion() : 500;

        Files.createDirectories(Path.of("output"));

        for (CastLib cast : castManager.getCasts()) {
            for (CastMemberChunk member : cast.getAllMembers()) {
                if (!member.isBitmap()) continue;

                // Parse bitmap metadata
                BitmapInfo info = BitmapInfo.parse(member.specificData());

                // Find BITD chunk via key table
                BitmapChunk bitmapChunk = null;
                for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                    if (entry.fourccString().equals("BITD")) {
                        Chunk chunk = file.getChunk(entry.sectionId());
                        if (chunk instanceof BitmapChunk bc) {
                            bitmapChunk = bc;
                            break;
                        }
                    }
                }
                if (bitmapChunk == null) continue;

                // Get palette
                Palette palette = info.paletteId() < 0
                    ? Palette.getBuiltIn(info.paletteId())
                    : Palette.getBuiltIn(Palette.SYSTEM_MAC);

                // Decode bitmap
                Bitmap bitmap = BitmapDecoder.decode(
                    bitmapChunk.data(),
                    info.width(), info.height(), info.bitDepth(),
                    palette, true, bigEndian, version
                );

                // Save as PNG
                int[] pixels = bitmap.getPixels();
                BufferedImage image = new BufferedImage(
                    bitmap.getWidth(), bitmap.getHeight(), BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());

                String name = member.name().isEmpty() ? "member_" + member.id() : member.name();
                ImageIO.write(image, "PNG", new File("output/" + name + ".png"));
                System.out.println("Saved: " + name + ".png");
            }
        }
    }
}
```

## Acknowledgments

This work could not have been done without the following projects and people:

- **[dirplayer-rs](https://github.com/igorlira/dirplayer-rs)** by **Igor Lira** - A Rust implementation of a Shockwave player
- **[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays)** by **Debby Servilla** - A Director movie decompiler
- **The ScummVM Director Engine Team** - Research on the Director file format

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
