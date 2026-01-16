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

## Acknowledgments

This work could not have been done without the following projects and people:

- **[dirplayer-rs](https://github.com/igorlira/dirplayer-rs)** by **Igor Lira** - A Rust implementation of a Shockwave player
- **[ProjectorRays](https://github.com/ProjectorRays/ProjectorRays)** by **Debby Servilla** - A Director movie decompiler
- **The ScummVM Director Engine Team** - Research on the Director file format

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
