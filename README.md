# LibreShockwave

[![Website](https://img.shields.io/badge/Website-libreshockwave.net-orange?logo=googlechrome&logoColor=white)](https://libreshockwave.net)

LibreShockwave is a C++20 project for parsing Macromedia/Adobe Director and Shockwave files (`.dir`, `.dxr`, `.dcr`, `.cct`, `.cst`).

It **won't** *just* be an emulator: the goal is to eventually become a full software suite and ecosystem, with a Director player, alongside a replacement for Director MX (2004, 11.5, etc.) as an open source replacement for Macromedia/Adobe Shockwave.

## Requirements

- CMake 3.20 or newer
- A C++20 compiler
- zlib development headers, or a zlib-compatible zlib-ng package
- Optional: Ninja for faster incremental builds
- Optional: Emscripten for the browser/WASM target
- Optional: Node.js and npm for browser fixture verification

## Linux Package Setup

Install the native build dependencies for your distribution before running CMake.

### Debian, Ubuntu, Linux Mint, Pop!_OS

```bash
sudo apt update
sudo apt install build-essential cmake ninja-build zlib1g-dev
```

### Fedora

```bash
sudo dnf install cmake gcc-c++ ninja-build zlib-ng-compat-devel
```

If your Fedora release does not provide `zlib-ng-compat-devel`, use `zlib-devel`.

### RHEL, Rocky Linux, AlmaLinux, CentOS Stream

```bash
sudo dnf install cmake gcc-c++ ninja-build zlib-devel
```

On older releases, make sure the installed CMake is at least 3.20.

### Arch Linux, Manjaro

```bash
sudo pacman -S --needed base-devel cmake ninja zlib
```

### openSUSE Leap, openSUSE Tumbleweed

```bash
sudo zypper install cmake gcc-c++ ninja zlib-devel
```

### Alpine Linux

```bash
sudo apk add build-base cmake ninja zlib-dev linux-headers
```

## Native Build

```bash
cmake -S . -B cmake-build-debug -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug --target libreshockwave_tests libreshockwave_probe libreshockwave_render_probe libreshockwave_wasm_bridge_probe --parallel
ctest --test-dir cmake-build-debug --output-on-failure
```

The main library target is `LibreShockwave::libreshockwave`.

If you installed Ninja and want to use it explicitly:

```bash
cmake -S . -B cmake-build-debug -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug --parallel
```

## Supported Formats

- RIFX, XFIR, RIFF, and FFIR containers
- Big-endian and little-endian Director files
- Afterburner-compressed files (`.dcr`, `.cct`)
- Director versions 4 through 12

## Capabilities

### Reading

- Cast members: bitmaps, text, scripts, sounds, shapes, palettes, fonts, and Shockwave3D metadata
- Lingo bytecode with symbol, global, property, and handler resolution
- Score and timeline data, including frames, channels, labels, palettes, tempos, and behavior intervals
- File metadata, including stage dimensions, tempo, version, movie type, endian mode, and external cast paths

### Asset Extraction

- Bitmaps: 1/2/4/8/16/32-bit depths, palette support, native alpha, matte data, and ARGB output buffers
- Text: field and rich text cast members through STXT and XMED data
- Sound: MP3 extraction, PCM WAV conversion, and IMA ADPCM decoding
- Palettes: built-in Director palettes and custom CLUT chunks
- Fonts: PFR1 font parsing and conversion to TrueType (`.ttf`) bytes

## Player And Lingo VM

The Lingo VM, native player runtime, and browser/WASM player are under active development and are not production-ready. Expect missing features, incomplete Lingo coverage, and breaking changes.

LibreShockwave includes a Lingo bytecode VM and player that can load and run Director movies. The runtime handles score playback, sprite rendering, input state, external cast loading, networking queues, audio queues, debugging hooks, and software frame rendering.

The player can be used in two ways:

- Native C++: link against `LibreShockwave::libreshockwave` and drive `libreshockwave::player::Player` directly.
- Browser/WASM: build the Emscripten target and use the assets in `cpp/web/`.

## Using LibreShockwave As A Library

When vendoring the repository inside another CMake project:

```cmake
add_subdirectory(path/to/LibreShockwave)
target_link_libraries(my_tool PRIVATE LibreShockwave::libreshockwave)
target_compile_features(my_tool PRIVATE cxx_std_20)
```

### Loading A File

The C++ API currently loads from memory. Read the file bytes, then call `DirectorFile::load`.

```cpp
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"

std::vector<std::uint8_t> readFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Unable to open file");
    }
    return {
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>()
    };
}

int main(int argc, char** argv) {
    const std::filesystem::path moviePath = argc > 1 ? argv[1] : "movie.dcr";
    auto file = libreshockwave::DirectorFile::load(readFile(moviePath));
    file->setBasePath(moviePath.parent_path().string());

    std::cout << file->stageWidth() << "x" << file->stageHeight()
              << " tempo=" << file->tempo()
              << " cast members=" << file->castMembers().size()
              << '\n';
}
```

### Iterating Cast Members

```cpp
for (const auto& member : file->castMembers()) {
    if (member->isBitmap()) {
        // Decode with file->decodeBitmap(member).
    }
    if (member->isScript()) {
        // Resolve with file->getScriptForCastMember(member).
    }
    if (member->isSound()) {
        // Read linked snd chunks.
    }
    if (member->isText()) {
        // Read linked STXT/XMED text.
    }
}
```

### Extracting Bitmaps

```cpp
for (const auto& member : file->castMembers()) {
    if (!member->isBitmap()) {
        continue;
    }

    if (auto bitmap = file->decodeBitmap(member)) {
        const int width = bitmap->width();
        const int height = bitmap->height();
        const auto& argbPixels = bitmap->pixels();
        // Encode or inspect argbPixels in your own tool.
    }
}
```

### Extracting Text

```cpp
for (const auto& member : file->castMembers()) {
    auto text = file->getTextForMember(member);
    if (text && !text->text().empty()) {
        std::cout << member->name() << ": " << text->text() << '\n';
    }
}
```

### Extracting Sounds

```cpp
#include <filesystem>
#include <fstream>
#include <memory>

#include "libreshockwave/audio/SoundConverter.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/format/ChunkType.hpp"

for (const auto& member : file->castMembers()) {
    if (!member->isSound()) {
        continue;
    }

    for (const auto& chunk : file->getLinkedChunksForMember(
             member,
             libreshockwave::format::fourCC(libreshockwave::format::ChunkType::snd_))) {
        auto sound = std::dynamic_pointer_cast<libreshockwave::chunks::SoundChunk>(chunk);
        if (!sound) {
            continue;
        }

        const auto bytes = sound->isMp3()
            ? libreshockwave::audio::SoundConverter::extractMp3(*sound).value_or(std::vector<std::uint8_t>{})
            : libreshockwave::audio::SoundConverter::toWav(*sound);

        std::ofstream output(member->name() + (sound->isMp3() ? ".mp3" : ".wav"), std::ios::binary);
        output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    }
}
```

### Extracting Fonts

Director files can embed PFR1 font data inside XMED chunks. LibreShockwave can parse PFR1 data and convert it to standard TrueType bytes.

```cpp
#include <filesystem>
#include <fstream>
#include <memory>

#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/font/Pfr1Font.hpp"
#include "libreshockwave/font/Pfr1TtfConverter.hpp"
#include "libreshockwave/format/ChunkType.hpp"

for (const auto& member : file->castMembers()) {
    for (const auto& chunk : file->getLinkedChunksForMember(
             member,
             libreshockwave::format::fourCC(libreshockwave::format::ChunkType::XMED))) {
        auto raw = std::dynamic_pointer_cast<libreshockwave::chunks::RawChunk>(chunk);
        if (!raw || raw->data().size() < 4) {
            continue;
        }

        const auto& data = raw->data();
        if (data[0] != 'P' || data[1] != 'F' || data[2] != 'R' || data[3] != '1') {
            continue;
        }

        auto font = libreshockwave::font::Pfr1Font::parse(data);
        auto ttf = libreshockwave::font::Pfr1TtfConverter::convert(*font, font->fontName);

        std::ofstream output(member->name() + ".ttf", std::ios::binary);
        output.write(reinterpret_cast<const char*>(ttf.data()), static_cast<std::streamsize>(ttf.size()));
    }
}
```

### Accessing Scripts And Bytecode

```cpp
for (const auto& script : file->scripts()) {
    auto names = file->getScriptNamesForScript(script);

    for (const auto& global : script->getGlobalNames(names.get())) {
        std::cout << "global " << global << '\n';
    }

    for (const auto& handler : script->handlers()) {
        std::cout << "handler " << script->getHandlerName(handler, names.get()) << '\n';

        for (const auto& instruction : handler.instructions) {
            std::cout << "  " << instruction.offset << ": "
                      << instruction.toString() << '\n';
        }
    }
}
```

### Reading Score Data

```cpp
if (auto score = file->scoreChunk()) {
    std::cout << "frames=" << score->getFrameCount()
              << " channels=" << score->getChannelCount()
              << '\n';

    for (const auto& interval : score->frameIntervals()) {
        std::cout << "interval "
                  << interval.primary.startFrame
                  << "-"
                  << interval.primary.endFrame
                  << '\n';
    }
}

if (auto labels = file->frameLabelsChunk()) {
    for (const auto& label : labels->labels()) {
        std::cout << label.frameNum.value() << ": " << label.label << '\n';
    }
}
```

### Rendering Frames

```cpp
#include "libreshockwave/player/Player.hpp"

libreshockwave::player::Player player(file);
player.setExternalParams({
    {"sw1", "external.variables.txt=http://example.com/vars.txt"},
    {"sw2", "connection.info.host=127.0.0.1"}
});
player.play();

for (int frame = 0; frame < 10 && player.tick(); ++frame) {
    auto rendered = player.frameSnapshot().renderFrame();
    const auto& argbPixels = rendered.pixels();
    // rendered.width(), rendered.height(), and argbPixels describe the frame.
}

player.shutdown();
```

### Error Handling And Debug Playback

```cpp
player.setDebugEnabled(true);
player.setErrorListener([](std::string_view message, std::string_view detail) {
    std::cerr << "Lingo error: " << message << '\n' << detail << '\n';
});

auto callStack = player.formatLingoCallStack();
```

For bytecode-level debugging, attach a `libreshockwave::player::debug::DebugControllerApi` implementation with `player.setDebugController(...)`.

## Native Tools

The C++ tools scan local Director fixture roots. With no path argument, the probes use `/var/html` when present and fall back to `/var/www/html`.

```bash
./cmake-build-debug/cpp/libreshockwave_probe /var/www/html
./cmake-build-debug/cpp/libreshockwave_render_probe /var/www/html
./cmake-build-debug/cpp/libreshockwave_wasm_bridge_probe /var/www/html
```

`libreshockwave_probe` validates file loading, cast-member discovery, script metadata, score metadata, and external cast paths.

`libreshockwave_render_probe` drives the player renderer and can preload local external casts. Useful options include `--play`, `--ticks N`, `--scan-frames N`, `--no-preload-casts`, `--show-current`, and `--verbose`.

`libreshockwave_wasm_bridge_probe` exercises the browser-facing C ABI exports without requiring a browser.

## Browser/WASM Player

Build the Emscripten target from an Emscripten-enabled shell:

```bash
emcmake cmake -S . -B cmake-build-wasm -DCMAKE_BUILD_TYPE=Release
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm --parallel
```

The generated browser player is assembled under:

```text
cmake-build-wasm/cpp/wasm-dist/
```

Serve that directory with cross-origin isolation headers when loading it manually:

```http
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
```

The included browser fixture checker starts an isolated local server, serves the WASM player plus the fixture root, loads a requested movie, waits for nonblank rendering, and runs generic WebSocket and SMUS bridge self-tests:

```bash
npm --prefix cpp/tools install
node cpp/tools/browser_fixture_check.js --movie /dcr0910/loader.dcr --screenshot cmake-build-wasm/browser-smoke.png
```

The same checker can batch movies while reusing one browser/server setup:

```bash
node cpp/tools/browser_fixture_check.js --discover-root dcr0910
node cpp/tools/browser_fixture_check.js --fixtures /opt/git/v1_assets/projectorrays_lingo --discover-root .
```

Use `--movies /a.dcr,/b.dcr`, `--movie-list movies.txt`, `--limit N`, `--summary-only`, and `--screenshot-dir path` for smaller targeted runs.

### Embedding

The browser target exposes `LibreShockwaveCppPlayer` from `libreshockwave-cpp-player.js`.

```html
<canvas id="stage" width="640" height="480"></canvas>
<script src="libreshockwave-cpp-player.js"></script>
<script>
  const player = LibreShockwaveCppPlayer.create("stage", {
    params: {
      sw1: "external.variables.txt=http://example.com/vars.txt"
    },
    debugPlayback: true,
    onLoad(info) {
      console.log(info.width + "x" + info.height);
    },
    onError(message) {
      console.error(message);
    }
  });

  player.load("movie.dcr");
</script>
```

The following files must be served from the same directory unless `basePath` is supplied:

| File | Purpose |
|------|---------|
| `index.html` | Ready-made browser player page |
| `libreshockwave-cpp-player.js` | Main browser player API |
| `libreshockwave-cpp-worker.js` | Worker wrapper for the WASM runtime |
| `libreshockwave-cpp-wasm.js` | Emscripten module loader |
| `libreshockwave-cpp-wasm.wasm` | Compiled runtime |

## Repository Layout

```text
cpp/
  CMakeLists.txt
  include/libreshockwave/       Public C++ headers
  src/                          Runtime, SDK, VM, and player sources
  resources/fonts/              Bundled runtime font assets
  tests/                        C++ regression and contract tests
  tools/                        Native probes and browser fixture checker
  web/                          Browser player and worker assets
docs/
  rendering-rules.md            Renderer behavior notes
  inks.txt                      Director ink behavior reference
```

## Verification Before Committing

```bash
node --check cpp/web/libreshockwave-cpp-player.js
node --check cpp/web/libreshockwave-cpp-worker.js
node --check cpp/tools/browser_fixture_check.js
cmake -S . -B cmake-build-debug -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug --target libreshockwave_tests libreshockwave_probe libreshockwave_render_probe libreshockwave_wasm_bridge_probe --parallel
ctest --test-dir cmake-build-debug --output-on-failure
git diff --check
```

Run fixture probes that match the risk of the change before saving a C++ port slice.

## License

See [LICENCE](LICENCE).
