# LibreShockwave

[![Website](https://img.shields.io/badge/Website-libreshockwave.net-orange?logo=googlechrome&logoColor=white)](https://libreshockwave.net)

LibreShockwave is a C++20 runtime/player stack for Macromedia/Adobe Director and Shockwave files (`.dir`, `.dxr`, `.dcr`, `.cct`, `.cst`). The active tree is CMake-based and focuses on the non-editor runtime: Director file parsing, Lingo/VM foundations, score playback, software rendering, probes, and an Emscripten browser player.

The old Java/Gradle implementation was the historical reference for this port and is no longer the repository-facing source tree.

## Requirements

- CMake 3.20 or newer
- A C++20 compiler
- zlib or zlib-ng development headers
- Optional: GTK4 development headers for the experimental native editor shell target
- Optional: Emscripten for the browser/WASM target
- Optional: Node.js plus `ws` and either `puppeteer` or `playwright` for browser fixture verification

On Fedora, the native runtime dependencies are typically:

```bash
sudo dnf install cmake gcc-c++ zlib-ng-devel
```

For the optional GTK target:

```bash
sudo dnf install gtk4-devel
```

## Build

```bash
cmake -S . -B cmake-build-debug -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug --target libreshockwave_tests libreshockwave_probe libreshockwave_render_probe libreshockwave_wasm_bridge_probe -j 6
ctest --test-dir cmake-build-debug --output-on-failure
```

The main library target is `LibreShockwave::libreshockwave`.

## Native Tools

The C++ tools scan local Director fixture roots. With no path argument, the probes use `/var/html` when present and fall back to `/var/www/html`.

```bash
./cmake-build-debug/cpp/libreshockwave_probe /var/www/html
./cmake-build-debug/cpp/libreshockwave_render_probe /var/www/html
./cmake-build-debug/cpp/libreshockwave_wasm_bridge_probe /var/www/html
```

`libreshockwave_probe` validates file loading, cast-member discovery, and score metadata. `libreshockwave_render_probe` drives the player renderer and can preload local external casts. `libreshockwave_wasm_bridge_probe` exercises the browser-facing C ABI exports without requiring a browser.

Additional parity assets are available locally under `/opt/git/v1_assets/`; those assets worked in the previous Java implementation and are useful runtime/player reference fixtures for the C++ port.

## Browser/WASM Player

Build the Emscripten target from an Emscripten-enabled shell:

```bash
emcmake cmake -S . -B cmake-build-wasm -DCMAKE_BUILD_TYPE=Release
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm -j 6
```

The generated browser player is assembled under:

```text
cmake-build-wasm/cpp/wasm-dist/
```

Serve that directory with cross-origin isolation headers when loading it manually. The included browser fixture checker starts its own isolated local server, serves the WASM player plus the fixture root, loads a requested movie, waits for nonblank rendering, and runs generic WebSocket and SMUS bridge self-tests:

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

The `/dcr0910/loader.dcr` movie was used only as a completed load/render/networking proof. Reusable browser assets should remain movie-agnostic and should not depend on one movie's globals or handlers.

## Current Scope

- Director container loading, chunk parsing, linked media discovery, and Afterburner/zlib decompression
- Cast members, palettes, bitmaps, text, sound payloads, score metadata, scripts, and font resources
- Lingo datum/decompiler/VM foundations and a growing set of builtins
- Runtime player state, events, score navigation, sprite state, input, net/audio/JPEG/Multiuser queues, and software rendering
- Browser C ABI exports, JS player/worker assets, canvas blitting, fetch relay, audio command forwarding, WebSocket/SMUS bridge diagnostics, and Emscripten packaging

Shockwave3D/W3D can be ignored for the current parity goal because it was never completed in the historical LibreShockwave runtime. Existing C++ W3D parsing and preliminary bake coverage can remain opportunistic.

The C++ editor scaffolding is not the active port objective. Keep runtime/player work decoupled from editor expansion unless a later task explicitly changes that scope.

## Repository Layout

```text
cpp/
  CMakeLists.txt
  include/libreshockwave/       Public C++ headers
  src/                          Runtime, SDK, VM, player, and optional editor sources
  resources/fonts/              Bundled runtime font assets
  tests/                        C++ regression and contract tests
  tools/                        Native probes and browser fixture checker
  web/                          Browser player and worker assets
```

## Verification Before Committing

```bash
node --check cpp/web/libreshockwave-cpp-player.js
node --check cpp/web/libreshockwave-cpp-worker.js
node --check cpp/tools/browser_fixture_check.js
cmake -S . -B cmake-build-debug -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug --target libreshockwave_tests libreshockwave_probe libreshockwave_render_probe libreshockwave_wasm_bridge_probe -j 6
ctest --test-dir cmake-build-debug --output-on-failure
git diff --check
```

Run the fixture probes that match the change's risk before saving a C++ port slice.

## License

See [LICENCE](LICENCE).
