# LibreShockwave C++ Port Progress

Objective: port the entire LibreShockwave project to C++ while keeping the C++ tree buildable and tested after each slice.

## Current Status

Started. The Java/Gradle project remains the authoritative implementation for behavior that has not yet reached C++ parity. A first-party CMake/C++ port exists under `cpp/` and is being expanded from reusable SDK foundations upward.

This file tracks the current state and remaining work only. Detailed chronological changes live in git.

## Current C++ Coverage

- Build: root CMake integration, `LibreShockwave::libreshockwave`, CTest coverage, optional zlib support, and optional GTK4 editor target.
- Core file parsing: endian-aware binary IO, FourCC/MoaID helpers, Director chunk metadata, RIFX/XFIR loading including little-endian XFIR memory maps, D3 RIFF loading, Afterburner map/decompression support, lazy chunk reparse, and chunk categorization.
- Director chunks and metadata: config, palette, bitmap, sound/media, text, font maps, cast lists, cast members, key tables, score, frame labels, script names, script contexts, and script bytecode.
- Cast/media SDK: cast-member wrappers, cast-library lookup, palette resolution, bitmap decoding, bitmap drawing/color/ink helpers, sound conversion, W3D parsing, generated font decoding, bitmap font/PFR/TTF helpers, and utility formatting/string helpers.
- Lingo and VM foundations: datum model/formatting, opcode metadata, decompiler nodes/translation, value parsing, VM scope/execution context/runtime/safepoints/tracing/alert hooks/deferred dispatch, opcode registry handlers, builtin registry, and major builtins for math, strings, lists, output, control flow, constructors, types, casts, images, networking, sound, Xtras, and timeouts.
- Runtime/player foundations: player facade, builtin context, VM event dispatch, sprite and movie properties, score navigation, input state/handler, hit testing, cursor manager, sprite registry/state, behavior instances, event dispatching, frame context, timeout manager, sound manager, net manager, queued browser-facing net/audio/JPEG/Multiuser bridges, and player-owned Xtra manager wiring.
- Rendering pipeline: stage renderer, sprite baker, render pipeline context/runner, software frame renderer, bitmap cache/ink processor, text renderer interface, and simple text renderer.
- Dynamic/runtime member support: runtime bitmap/text/palette/script payloads, dynamic member creation/reuse/erase, runtime member media copying, imported bitmap media, runtime registration points, editable field state/overlay helpers, and dynamic bitmap/text rendering through the player pipeline.
- Debug/editor foundations: debugger data/control models, breakpoint/watch/expression helpers, lifecycle diagnostics, GTK-neutral editor models, optional GTK shell scaffolding, menu/action/dialog/open-file/workbench/start-screen metadata, dock/floating pane state, GTK4 right-click pane menus, drag-snap hooks, fixed-position floating pane rendering, and GTK4 action/menu/open-file scaffolding.
- Browser/WASM foundations: C++ WASM player wrapper, runtime bridge, prefixed native-testable C ABI export facade, browser-side export adapter for the worker's TeaVM-style method names, and conditional Emscripten CMake packaging, covering movie loading, frame rendering, cursor/caret/selection overlays, debug/trace controls, diagnostics, playback/input controls, navigation polling, external params, fetch/audio/Multiuser/JPEG queues, host result delivery, and last-error retrieval.
- Fixture validation: native `libreshockwave_probe` scans Director `.cct`, `.cst`, `.dcr`, `.dir`, and `.dxr` files through C++ load, editor member-scan, and score-grid paths while skipping extension-matched non-Director placeholder files.

## Verification

- C++ coverage is verified through the `libreshockwave_cpp_tests` CTest executable.
- Fixture coverage is verified with `libreshockwave_probe` against local Director fixture roots. `/var/www/html/dcr0910` currently passes with 64 files loaded, 0 skipped, and 0 failed.
- `/var/html` is not present in the current environment; available local web fixtures are under `/var/www/html`.
- Before saving C++ port slices, run the CMake build, CTest, `git diff --check`, and relevant fixture probes.

## Remaining Major Work

- Higher-level media integration.
- Remaining sound, renderer-side PFR anti-alias fidelity, platform text shaping, and text/score/script chunk decoder edge cases.
- Detailed W3D geometry/material decoding and rendering integration.
- Remaining Lingo decompiler, VM runtime value, dispatcher, and builtin parity gaps.
- Remaining player core, rendering pipeline, input, networking, audio, cast management, and debugging parity gaps.
- C++ browser runtime bootstrap, Emscripten-built C++ WASM browser verification, and parity testing against the existing TeaVM web-player scenarios.
- Editor replacement strategy in C++.
- Full `/var/html` `.cct`/`.dcr` read/handling verification once that requested fixture root exists or is mapped.
- Port parity tests against current fixtures and integration scenarios.
