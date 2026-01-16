# LibreShockwave

**Warning: This project is under active development and is not ready for use.**

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

## Overview

LibreShockwave is an open-source implementation of a Macromedia/Adobe Director and Shockwave player. It aims to preserve and play legacy Director content (.dir, .dxr, .dcr files) that would otherwise be lost as official support has ended.

## Project Structure

- **sdk/** - Core library for parsing Director file formats, Lingo bytecode VM, cast/score management
- **runtime/** - TeaVM-based WebAssembly runtime for browser playback
- **player/** - Standalone Java Swing player for debugging and testing
- **xtras/** - Director Xtras (extensions) such as NetLingo for network operations

## Current Status

Major components in development:
- Director file format parsing (RIFX/IFF chunks)
- Lingo bytecode virtual machine
- Cast and score rendering
- External cast library loading
- Basic Xtra support

## Building

Requires Java 21+.

```
./gradlew build
```

Run the Swing player:
```
./gradlew :player:run
```
