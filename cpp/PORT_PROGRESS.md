# LibreShockwave C++ Port Progress

Objective: port the entire LibreShockwave project to C++ while keeping the C++ tree buildable and tested after each slice.

## Current Status

Started. The Java/Gradle project remains the authoritative implementation for most behavior. A first-party CMake/C++ port now exists under `cpp/` and is being expanded from reusable SDK foundations upward.

## Completed C++ Slices

### Build Skeleton

- Added root `CMakeLists.txt`.
- Added `cpp/CMakeLists.txt`.
- Added `LibreShockwave::libreshockwave` static library target.
- Added `libreshockwave_cpp_tests` CTest executable.
- Added optional zlib integration through CMake.

### SDK Foundation

- Endian-aware `io::BinaryReader`.
- FourCC helpers.
- `format::ChunkType`, `format::ChunkInfo`, and `format::MoaID`.
- Typed IDs and enums in `id::Ids`.
- SDK Lingo datum foundation in `lingo::Datum`.

### Bitmap Foundation

- `bitmap::Palette` with System Mac, Rainbow, Grayscale, Metallic, and System Windows built-in palette data.
- `bitmap::ColorRef`.
- `bitmap::Bitmap` with ARGB pixel storage, palette index metadata, palette remapping/quantization, alpha helpers, region extraction, anchor metadata, and swatch generation.

### Chunk and Audio Foundation

- `chunks::Chunk` base interface with a forward-declared `DirectorFile` owner pointer.
- `chunks::RawChunk`.
- `chunks::PaletteChunk` CLUT reader.
- `chunks::BitmapChunk` BITD raw data reader and dimension metadata copy helper.
- `chunks::SoundChunk` reader with sample-rate detection, MP3 detection, sample count, and duration helper.
- `chunks::MediaChunk` reader with raw MP3 detection, header parsing, GUID-based IMA ADPCM detection, endian restoration, and `toSoundChunk`.
- `util::containsMp3SyncFrame`.

### Cast Metadata Foundation

- `cast::MemberType` and `cast::ScriptType`.
- `cast::StyledSpan` and `cast::XmedStyledText` helpers.
- `cast::BitmapInfo` parser for D4/D5 and D6+ bitmap specific data.
- `cast::TextInfo` parser for compact and D7+ text specific data.
- `cast::ShapeInfo` parser and shape helpers.
- `cast::FilmLoopInfo` parser and dimension/registration helpers.
- `cast::Shockwave3DInfo` parser for basic camera, color, flag, and Pascal string metadata.

### Compact Chunk Parsers

- `chunks::ConfigChunk` DRCF/VWCF parser for stage dimensions, stage color, tempo, platform, and default palette metadata.
- `chunks::FrameLabelsChunk` VWLB parser and label lookup helpers.
- `chunks::ScriptNamesChunk` Lnam parser and case-insensitive name lookup.
- `chunks::TextChunk` STXT parser with legacy and modern text-run layouts.
- `chunks::FontMapChunk` Fmap parser and font-id lookup.
- `chunks::CastChunk` CAS* cast member ID array parser.
- `chunks::KeyTableChunk` KEY* parser with owner/section lookups.

### Cast List and Member Chunks

- `chunks::CastListChunk` MCsL parser for cast library names, paths, preload settings, member ranges, and cast IDs.
- `chunks::CastMemberChunk` CASt parser for D5+ and D4 member layouts.
- Cast member helpers for bitmap/script/text/sound/Shockwave 3D type checks, text Xtra signature detection, and script type decoding.
- Member info list parsing for script ID and Pascal member name extraction.
- Bitmap member registration point extraction through `cast::BitmapInfo`.

### Lingo Opcode and Script Context Foundation

- `lingo::Opcode` enum and helpers for opcode code lookup, mnemonic lookup, argument byte width, and push/jump/call/return classification.
- `chunks::ScriptContextChunk` Lctx parser for script table metadata, Lnam section references, valid counts, flags, free pointer, and script entries.
- Script context entry parsing clamps free/empty negative script chunk IDs to `ChunkId(0)` like the Java baseline.

### Script Bytecode Chunk Parser

- `chunks::ScriptChunk` Lscr parser for script type, behavior flags, properties, globals, literals, handler metadata, and handler name tables.
- Literal parsing covers string, inline int, float numeric value, and raw unknown literal payloads.
- Handler bytecode instruction decoding normalizes multi-byte opcodes, preserves raw opcode bytes, decodes signed push-int arguments, and builds bytecode-offset lookup maps.

### Score Chunk Parser

- `chunks::ScoreChunk` VWSC parser for score headers, entry tables, frame data, and behavior interval entries.
- Delta-compressed score frame data expansion with D5 packed-main-channel handling and D6+ uniform channel handling.
- Sprite channel parsing for ink, cast references, geometry, blend, flip flags, and indexed/RGB color resolution.
- Tempo and palette channel extraction with frame lookup helpers.

### Director File Loader Foundation

- `DirectorFile` C++ model with stable shared ownership for parsed chunks.
- In-memory RIFX/XFIR loading for non-Afterburner Director files.
- imap/mmap parsing, chunk-info table construction, version detection from config chunks, and chunk dispatch through the ported chunk readers.
- Chunk categorization for config, key table, casts, cast members, scripts, script names/contexts, score, frame labels, palettes, and font maps.

### Afterburner Reader Foundation

- `format::AfterburnerReader` parser for Fver, Fcdr, ABMP, and FGEI/ILS sections.
- Compression type directory parsing with MoaID-based zlib/null compression detection.
- ABMP resource map parsing and ILS chunk cache extraction.
- Chunk data lookup by resource ID and FourCC with zlib decompression where required.

### Director File Afterburner Integration

- `DirectorFile::load` now accepts FGDM/FGDC Afterburner movie types.
- Afterburner chunk maps are translated into `DirectorChunkInfo` records and dispatched through the same ported chunk readers as RIFX/XFIR files.
- Config chunks are parsed early to establish Director version before the full chunk pass.
- Afterburner chunks are categorized into the same config/script/cast/score/media collections as uncompressed Director files.

### D3 RIFF Loader Foundation

- `DirectorFile::load` now accepts RIFF/FFIR containers with RMMP markers.
- CFTC resource table parsing maps named RIFF resources to chunk payload offsets.
- RIFF chunks reuse the existing C++ chunk dispatch and categorization path.

### Lazy Chunk Reparse Foundation

- `DirectorFile::getChunk` reparses evicted chunks from stored file bytes for RIFX/XFIR/RIFF/FFIR files.
- Afterburner-loaded files retain their parsed `AfterburnerReader` for evicted chunk reloads.
- `DirectorFile::releaseNonEssentialChunks` evicts sound, media, and raw chunks to match the Java memory-management path.

### Cast and Script Lookup Foundation

- `lookup::CastMemberLookup` maps cast libraries to CAS* chunks and resolves cast members by score index or authored member number.
- `lookup::ScriptLookup` resolves scripts by Lctx context ID, including overlapping context spaces across multiple script contexts.
- `DirectorFile` exposes cast member, mapped cast, script-by-context, and script-type lookup helpers backed by lazy lookup caches.

### Palette Resolution Integration

- `lookup::PaletteResolver` resolves built-in palettes, custom palette cast members, direct CLUT chunk references, indexed palette members, and first-palette/System Mac fallbacks.
- Custom 256-color palette resolution inherits the System Mac trailing grayscale ramp for black placeholder entries in indices 246-255.
- `DirectorFile` exposes `resolvePalette`, `resolvePaletteExact`, and `resolvePaletteByMemberNumber` helpers backed by a lazy palette resolver cache.

### DirectorFile Member Resource Integration

- `DirectorFile` exposes stage width, stage height, default tempo, score tempo, and score palette channel helpers.
- KEY* backed member resource lookup now resolves embedded film-loop score chunks for cast members.
- KEY* backed text lookup now returns all associated STXT chunks, chooses the first non-empty text chunk, and falls back to direct member-id STXT lookup.

### Script Name and Info Helpers

- `chunks::ScriptChunk` resolves handler, property, global, and arbitrary name IDs through `ScriptNamesChunk`.
- Script handler lookup by case-insensitive authored name is available in the C++ SDK.
- `DirectorFile` resolves per-script Lnam chunks, script cast-member ownership, script member display names, global/property name lists, and script info summaries.

### DirectorFile Metadata Helpers

- `DirectorFile` tracks mutable base-path metadata for external cast resolution.
- External cast path listing, external-cast presence checks, score-presence checks, and font-name lookup across Fmap chunks are exposed in C++.

### Bitmap Decoder Foundation

- `bitmap::BitmapDecoder` ports Director PackBits/RLE decompression and scan-width calculations.
- Indexed bitmap decoding covers 1-bit, 2-bit, 4-bit, and 8-bit sources with palette-index metadata preservation.
- 16-bit RGB555 and 32-bit separated/interleaved channel decoders are available, plus the automatic compression/bit-depth dispatch path.

### DirectorFile Bitmap Decode Integration

- `DirectorFile::decodeBitmap` resolves BITD chunks through KEY* owner mappings for bitmap cast members.
- Bitmap member specific data is parsed through `cast::BitmapInfo`, palettes are resolved or overridden, and decoded bitmaps preserve native-alpha and palette metadata.
- ediM/JPEG and ALFA sidecar decoding remain deferred to the higher-level media integration slice.

### W3D File Parser Foundation

- `W3DFile` ports the Java W3D byte-vector and path loading API for little-endian Shockwave 3D resource streams.
- Raw W3D entries preserve type, parent reference, and payload bytes with Java-compatible unknown-type handling.
- Typed W3D accessors now parse nodes/light data, shapes, mesh resources, textures, materials, and resource references.
- W3D node transforms expose position helpers, and texture parsing detects JPEG payloads like the Java SDK.

### Generated Font Data Decoder

- `fonts::FontDataDecoder` ports the Java generated-font helper used by embedded font classes.
- Base64 font chunks are decoded into a fixed compressed buffer before inflation, matching the Java generated source contract.
- zlib inflation returns decoded font bytes only when the exact expected uncompressed length is produced, otherwise it returns an empty vector.

### Utility Formatting Helpers

- `util::getFileName`, `getFileNameWithoutExtension`, and `getUrlsWithFallbacks` port the Java path/URL fallback helper behavior for movie and cast files.
- `format::ScriptFormatUtils` ports literal type names, literal formatting, script type display names, name/handler fallback resolution, truncation, and line-ending normalization.

### Bitmap Colorizer

- `bitmap::BitmapColorizer` ports Director foreColor/backColor bitmap recoloring for 32-bit and indexed bitmap surfaces.
- Raw indexed-data colorization handles 1-bit, 2-bit, 4-bit, and 8-bit packed palette indices before bitmap decode.
- Ink-mode helper predicates for colorization and backColor usage are available in C++.

### PFR Font Bit Reader

- `font::PfrBitReader` ports byte-aligned big-endian reads and MSB-first bit reads used by the Java PFR1 font parser.
- Signed 8-bit, 16-bit, and 24-bit reads preserve Java sign-extension behavior.
- Bit reads preserve partial EOF behavior and byte-alignment reset on byte-level reads, position changes, and skips.

### Sound Converter

- `audio::SoundConverter` ports WAV construction for raw PCM and `SoundChunk` data, including Director big-endian 16-bit swapping and signed 8-bit conversion.
- MP3 start detection, multi-frame validation, frame-boundary end detection, and MP3 payload extraction are available in C++.
- IMA ADPCM decoding and ADPCM-to-WAV conversion are available with Java-compatible step/index table behavior.

### Cast Member Metadata Wrapper

- `cast::CastMember` ports the Java high-level cast member wrapper around `CastMemberChunk`.
- Type-specific bitmap, shape, film-loop, script, and Shockwave 3D metadata are parsed lazily at wrapper construction.
- Type checks, dimensions, registration point helpers, raw chunk access, and display string formatting are available in C++.

### Player Event and Render Configuration Foundation

- `player::PlayerEvent` ports the Java player event enum and handler-name mapping used by script dispatch.
- `PlayerEventInfo`, `FrameEvent`, and `ExternalCastLoadEvent` port the lightweight Java record payloads used by listeners.
- `render::RenderType` and `render::RenderConfig` port the software-renderer enum and global antialias flag.

### Player Input State Foundation

- `player::PlayerState` ports the stopped/paused/playing playback enum.
- `input::InputEvent` ports queued mouse, right-mouse, and keyboard input payload factories.
- `input::DirectorKeyCodes` ports Java/browser key-code conversion to Director Macintosh virtual key codes.
- `input::InputState` ports mouse, keyboard, selection, caret blink, double-click, rollover, and event-queue state tracking.

### Hit Testing Foundation

- `input::HitTester` ports front-to-back visible sprite hit selection over baked `RenderSprite` lists, hit type lookup, and all-hit collection with channel filter/forced bounding-box support.
- Bounds fallback, scaled bitmap sampling, flip/mirror coordinate adjustment, static native-alpha bitmap thresholds, and dynamic transparency-ink alpha checks are available in C++.
- Direct `StageRenderer` overloads remain deferred until the C++ stage renderer object is ported.

### Cursor Manager Foundation

- `player::CursorManager` ports cursor-code selection for hit sprites, editable text fields, button members, explicit sprite cursors, interactive sprite fallback, global cursor values, and custom bitmap cursors.
- Custom cursor bitmap/mask resolution, cursor hotspot lookup, cursor member encoding, and Director-style white-mask transparency are available through provider callbacks until the C++ stage/cast managers are ported.
- Navigator-whitespace suppression over matte, behavior-backed baked sprites is ported to keep interactive cursors from appearing over near-white masked UI regions.

### Sprite Runtime State Foundation

- `sprite::SpriteState` ports score-backed and dynamic sprite runtime state, including position, dimensions, visibility, puppet state, ink/blend/trails/stretch, colors, flip flags, rotation/skew, cursor members, script-instance lists, and dynamic member overrides.
- Score synchronization preserves explicit Lingo overrides, score-default application uses Director's inverted blend-byte mapping, and score rebinding can either clear or preserve attached script instances.
- Rendering integration through StageRenderer/SpriteBaker remains deferred until those C++ pipeline stages are ported.

### Sprite Registry Foundation

- `render::SpriteRegistry` ports runtime sprite-state ownership, score-driven creation, dynamic sprite creation, per-channel lookup, and score-behavior channel tracking.
- Score updates preserve explicit sprite overrides, rebind changed score identities while keeping attached script instances, and bump revisions for cache-visible identity changes.
- Retired dynamic member bindings are cleared for both dynamic channels and score-backed sprites, matching the Java cleanup path used when member slots are recycled.

### Behavior Instance Manager Foundation

- `behavior::BehaviorInstance` ports behavior IDs, script references, score behavior references, sprite/frame identity, persistent script-instance receiver properties, and begin/end sprite lifecycle flags.
- `behavior::BehaviorManager` ports instance registration, property parameter application from behavior refs, per-channel lookup, frame-script caching, instance removal, sprite-instance ordering, and clear state.
- Handler invocation and VM/event dispatch remain deferred to the C++ event dispatcher and Lingo VM runtime slices.

### Event Dispatcher Foundation

- `event::EventDispatcher` ports Director event ordering for sprite behaviors, frame behavior, movie scripts, sprite-only dispatch, behavior-only dispatch, and movie-script-only dispatch.
- Handler lookup uses real `ScriptChunk`/`ScriptNamesChunk` metadata where available, with callback hooks for handler invocation and dynamic script-instance response checks until the C++ Lingo VM is ported.
- Pass propagation, stopEvent state, mouse-handler detection, and sprite mouse-interactivity checks across score behaviors and dynamic script instances are available in C++.

### Sprite Properties Foundation

- `player::SpriteProperties` ports Lingo sprite property get/set behavior over `SpriteState` and `SpriteRegistry`, including missing-sprite defaults, dynamic sprite auto-creation, revision bumps, loc/rect/visibility/puppet/ink/blend/stretch/trails/color/transform/cursor/script-instance properties, and no-op acceptance for Director compatibility properties.
- Member assignment supports cast member refs, encoded slot numbers, name-resolution callbacks, empty-member release resets, intrinsic/runtime autosizing, and synthetic sprite-event broker retention during empty-channel release.
- Registration-aware sprite bounds, bitmap registration scaling, flip/mirror handling, member image get/set callbacks, and cursor member encoding are available through provider hooks until the C++ cast manager is ported.

### Movie Properties Foundation

- `player::MovieProperties` ports movie-level `the` property access for frame metadata, stage dimensions, movie path/name helpers, Java-compatible system/date/time constants, elapsed timer values, writable runtime flags, actor lists, cursor, alert hook, float precision, random seed, xtra listing, and miscellaneous Lingo constants.
- Input-backed mouse, key, focus, and selection properties are wired to the C++ `InputState`; file-backed counts, stage size, tempo, base path, and color depth use `DirectorFile` when available.
- Stage object properties, background-color mutation, item delimiter caching, frame/label/marker navigation, and net-page/net-movie requests are exposed through provider callbacks until the full C++ `Player` and stage renderer are ported.

### Builtin Registry and Movie/Sprite Builtins Foundation

- `lingo::builtin::BuiltinRegistry` ports the Java builtin registry's case-insensitive lookup, custom registration, direct lookup, missing-builtin handling, and optional invocation semantics.
- Movie `label`/`marker` builtins route through `MovieProperties` callbacks, including movie-reference leading arguments and Java-compatible non-negative frame results.
- Sprite builtins port `puppetTempo`, `puppetSprite`, `cursor`/`setCursor`, `spriteBox`, update/move no-ops, and a `puppetPalette` callback hook for the future C++ cast/palette provider.

### Math Builtins Foundation

- Math builtins now register `abs`, `sqrt`, `sin`, `cos`, `random`, `integer`, `float`, bit operations, `power`, `min`, and `max`.
- Numeric coercion covers Java-compatible string parsing, packed RGB color integer conversion, invalid-string handling, Java-style rounding for `integer`, and degree-based trig functions.
- `random(max)` is exposed through a VM-owned callback hook while preserving the Java fallback result of `1` for missing or non-positive ranges.

### String Builtins Foundation

- String builtins now register `string`, `length`, `chars`, `charToNum`, `numToChar`, `offset`, `getPref`, and `setPref`.
- Java-compatible string coercion covers raw strings/symbols, common reference display strings, list/proplist display formatting, 1-based character slices, byte-code character conversion, and case-insensitive offsets.
- Preference read/write builtins are exposed through VM-owned callback hooks until the C++ Lingo VM owns preference storage.

### Output Builtins Foundation

- Output builtins now register Java-compatible `put` and `alert`.
- `put` is gated by a C++ debug-playback flag and writes through a VM-owned output callback before falling back to stdout.
- `alert` uses a suppressible alert callback first, then writes through the same output callback/default stdout path for Java-compatible alert fallback behavior.

### List Builtins Foundation

- List builtins now register `count`, `getAt`, `setAt`, `addAt`, `deleteAt`, `append`/`add`, prop-list accessors/mutators, `findPos`, `getOne`/`getPos`, `deleteOne`, `sort`, `listp`, `list`, and `getLast`.
- Linear list mutation, proplist positional/key lookup, duplicate `addProp`, type-aware symbol/string key handling, case-insensitive key searches, Lingo-style list equality, and list sorting are covered in C++.
- `getAt(castLib.member, key)` remains deferred until the C++ cast-library member accessor datum/provider surface exists.

### Timeout Builtins Foundation

- Timeout builtins now register Java-compatible `timeout`, returning named or factory-mode `TimeoutRef` values.
- `TimeoutBuiltins` exposes method dispatch helpers for `new`, `forget`, and property-style timeout lookup, backed by the ported C++ `TimeoutManager`.
- Timeout property mutation is exposed through a static helper for future object-property dispatch, while unregistered Java helper functions remain deferred to movie scripts as in the Java baseline.

### Network and External Parameter Builtins Foundation

- Network builtins now register preload/get/post aliases, `netDone`, `netTextResult`, `netError`, `getStreamStatus`, `tellStreamStatus`, `gotoNetPage`, and `gotoNetMovie`.
- Builtins delegate through the C++ `NetManager` and `MovieProperties` navigation callbacks, preserving Java missing-provider defaults and stream-status PropList shape.
- External parameter builtins now resolve ordered context parameters by case-insensitive name or 1-based index.

### Sound Builtins Foundation

- Sound builtins now register Java-compatible `sound(channel)` and `soundEnabled`, returning valid 1-8 sound-channel datums and true availability by default.
- `SoundBuiltins` exposes sound-channel method dispatch helpers for play/queue/stop/status/time/volume/list/ilk behavior backed by the C++ `SoundManager`.
- Sound-channel property get/set helpers cover Java-compatible defaults and no-op accepted properties until C++ opcode property dispatch is wired to the helper surface.

### Cast Library Builtins Foundation

- Cast library builtins now register `castLib`, `member`, `field`, and `createMember` while intentionally leaving Java's unregistered `getMemNum`/`memberExists` helpers unavailable as builtins.
- Cast and member lookup behavior is exposed through VM/player-owned callback hooks for cast-library resolution, member number/name resolution, member existence, field text lookup, and dynamic member creation.
- Java-compatible fallback behavior is covered for missing cast providers, encoded member slot numbers, search-all member lookup, and empty field defaults.

### Xtra Builtins Foundation

- Xtra builtins now register Java-compatible `xtra(name)` and expose C++ Xtra/XtraInstance datum factories/accessors.
- `new(xtraRef, ...)` now delegates through an Xtra instance-creation callback before falling back to generic object construction.
- Xtra instance handler calls and property get/set behavior are exposed through VM-owned callbacks until the C++ Xtra manager is ported.

### Control Flow Builtins Foundation

- Control-flow builtins now register Java-compatible `return`, `halt`, `abort`, `nothing`, `param`, `go`, and `call`, while leaving `receiveUpdate`/`removeUpdate` unregistered for movie-script routing.
- `return`, `abort`, and `param` now operate over C++ VM-owned handler state in `BuiltinContext`.
- `go` delegates through `MovieProperties` frame/label navigation and `call` iterates single targets, lists, and prop-list values through a VM-owned dispatch callback.

### Constructor Builtins Foundation

- Constructor builtins now register `point`, `rect`, `union`, `intersect`, `color`, `rgb`, `paletteIndex`, `sprite`, and `new` in the C++ builtin registry.
- Point/rect geometry constructors, rectangle union/intersection, RGB and palette-index color construction, color pass-through, and trimmed hex-string RGB parsing match the Java constructor helpers.
- `new(#memberType, castLib)` and generic object construction are exposed through callback hooks until the C++ cast provider, Xtra support, and script-instance VM runtime are ported.

### Type Builtins Foundation

- Type builtins now register `objectp`, `voidp`, `value`, `script`, `ilk`, `listp`, `stringp`, `integerp`, `floatp`, `symbolp`, `symbol`, and `callAncestor`.
- Java-compatible type predicates, symbol conversion, `ilk` type names, and Director alias checks such as `#list`, `#linearList`, `#number`, and `#object` are covered in C++.
- VM/provider-dependent `value`, script resolution, and `callAncestor` dispatch are exposed through callback hooks until the C++ Lingo VM runtime is ported.

### Score Navigation Foundation

- `score::ScoreBehaviorRef` ports behavior cast-member references with saved parameter storage.
- `score::SpriteSpan` ports frame-span/channel tracking for frame scripts and sprite behaviors.
- `score::ScoreNavigator` ports score span construction, frame-label lookup, active sprite/channel queries, marker resolution, and frame counts.
- Behavior parameter literal parsing is wired as a deferred empty result until the VM `LingoValueParser` equivalent is ported.

### Debug Breakpoint Foundation

- `debug::Breakpoint` and `BreakpointKey` port immutable breakpoint records and stable lookup keys.
- `debug::BreakpointManager` ports add/remove/toggle, enable toggling, script offset maps, JSON serialization, JSON deserialization, and legacy serialization/deserialization.
- `debug::WatchExpression` ports watch expression result/error state and display helpers.
- `debug::DebugSnapshot`, `InstructionDisplay`, and `CallFrame` port immutable debugger UI state payloads without pulling in the full debug controller.

### Render Pipeline Data Foundation

- `render::pipeline::RenderPipelineStepTrace` and `RenderPipelineTrace` port immutable render-pipeline trace records.
- `render::pipeline::RenderSprite` ports renderable sprite metadata, ink-mode decoding, transform mirror detection, baked-bitmap copy helpers, and member identity/name fallbacks.
- `render::pipeline::FrameSnapshot` ports the immutable frame render payload without invoking the not-yet-ported software frame renderer.

### Software Frame Renderer

- `render::output::SoftwareFrameRenderer` ports pure ARGB frame compositing for `FrameSnapshot` data.
- Stage-image replacement, opaque background fill, sprite visibility filtering, clipping, nearest-neighbor scaling, flip handling, Director mirror transforms, alpha compositing, blend percentages, and special ink compositing are available in C++.
- `FrameSnapshot::renderFrame()` now delegates to the C++ software renderer like the Java record helper.

### Render Pipeline Context Foundation

- `render::pipeline::FrameRenderPipelineContext` ports mutable per-frame render pipeline state for sprites, rendered channels, trace entries, and completed snapshots.
- `render::pipeline::FrameRenderPipelineStep` ports the polymorphic pipeline step interface used by the Java frame render pipeline.

### Frame Render Pipeline Runner Foundation

- `render::pipeline::FrameRenderPipeline` ports ordered step registration and linear step execution over `FrameRenderPipelineContext`.
- Rendering requires a step-produced immutable `FrameSnapshot`, matching the Java pipeline failure behavior when no snapshot is published.
- Default StageRenderer/SpriteBaker collection, ordering, baking, publishing, and snapshot steps remain deferred until the C++ stage renderer and sprite baker are ported.

### Text Renderer Interface Foundation

- `render::output::TextRenderer` ports the platform-neutral text rendering and measurement interface.
- Shared line splitting, character-line lookup, line-start lookup, and word-wrap helpers are available in C++.
- The default XMED rendering path delegates to `renderText` using parsed `XmedStyledText` fields, matching the Java interface contract.

### Network Task Foundation

- `net::NetTask` ports individual GET/POST request task state, result/error storage, state transitions, stream status strings, and display formatting.

### Network Manager Foundation

- `net::NetManager` ports task ID allocation, latest-task lookup, GET/POST task registration, cache hits, completion callbacks, task byte/text/error/status accessors, and Java-compatible stream-status prop-list payloads.
- URL resolution, origin/path extraction, cache key calculation, URL-based stream-status lookup, and `.cct`/`.cst` cache fallback matching are available in C++.
- Actual platform HTTP/filesystem loading is exposed through an injectable fetch handler until the C++ platform networking backend is ported.

### Sound Manager Foundation

- `audio::AudioBackend` ports the platform-neutral playback contract for 1-based Director sound channels.
- `audio::SoundManager` ports channel validation, per-channel volume state, backend delegation, Lingo play-argument parsing, resolver-backed member audio lookup, and direct DirectorFile cast-library source registration.
- SoundChunk conversion to playable WAV/MP3 data is wired through `SoundConverter`, including MP3 extraction, IMA ADPCM conversion, raw PCM WAV wrapping, and KEY-owned `snd `/`ediM` member lookup helpers.

### Timeout Manager State Foundation

- `lingo::Datum` now exposes timeout reference construction and inspection helpers for runtime timeout APIs.
- `timeout::TimeoutManager` ports timeout creation, one-shot flag storage, forgetting, existence checks, property get/set behavior, timeout names/count, and clear state management.
- VM handler firing and system-event dispatch remain deferred until the C++ Lingo VM dispatch layer is ported.

### Bitmap Cache and Ink Helper Foundation

- `render::pipeline::InkProcessor` ports ink-processing predicates, Copy-ink colorization eligibility, backColor resolution, foreground/backColor grayscale remapping, indexed color remapping, Darken foreColor offsets, and exact-color remapping helpers.
- `render::pipeline::BitmapCache` ports processed-bitmap cache keys, decode-failure tracking, palette-version invalidation, non-native 32-bit alpha coercion, and indexed matte/background-transparent color remap selection.
- Full matte/flood-fill ink processing, bitmap decoding through the player resolver, and SpriteBaker integration remain deferred to the larger render-pipeline port.

### Image Builtins Foundation

- `lingo::Datum` now exposes first-party image references backed by shared `bitmap::Bitmap` instances.
- `ImageBuiltins` ports `image(width, height, bitDepth [, paletteRef])` with white-filled bitmap creation, built-in palette symbol resolution, provider-backed palette metadata, and Java-compatible image display/ilk behavior.
- `importFileInto` delegates platform-specific media import through an injectable context callback and preserves Director-style TRUE/FALSE results.

### Lingo VM Scope and Execution Context Foundation

- `lingo::vm::Scope` ports handler stack-frame state, including bytecode position, stack operations, local variables, mutable parameters, receiver-aware display arguments, return state, and loop-return tracking.
- `lingo::vm::ExecutionContext` ports the opcode-facing context layer for stack/local/param/global access, return/error state callbacks, jump-target lookup, local/global handler callback plumbing, builtin invocation, and argument popping.
- Full opcode-handler registration and bytecode execution remain deferred to the next VM runtime slices.

### Lingo VM Name Resolution Foundation

- `ExecutionContext` now accepts a VM-owned name resolver callback for bytecode name IDs.
- Opcode-facing name resolution falls back to script-local `#id` placeholders when no resolver is installed.
- Variable/global opcode tests now exercise resolved names through the same `ExecutionContext::resolveName` path needed by property and call opcodes.

### Lingo Opcode Registry and Stack/Control Foundation

- `lingo::vm::OpcodeRegistry` now maps opcode enums to executable C++ handler callbacks with post-construction registration hooks.
- Stack opcode handlers cover zero/int/float/literal/symbol pushes, chunk variable reference pushes, swap, pop, and peek behavior over `ExecutionContext`.
- Control-flow opcode handlers cover return, factory return, absolute jump target lookup, conditional zero jump, and repeat-loop back jumps.
- Provider-backed object construction/method calls, string chunk mutation, and provider-backed property opcodes remain deferred to later focused VM slices.

### Lingo Opcode Arithmetic, Comparison, and Logical Foundation

- Arithmetic opcode handlers now cover add, subtract, multiply, divide, modulo, and unary inverse over `ExecutionContext`.
- Numeric, point, rectangle, linear-list, and RGB color arithmetic mirrors the Java opcode foundation, including Java-style integer truncation and color-channel clamping.
- Comparison and logical opcode handlers now cover numeric ordering, Lingo equality/inequality, and truthy `and`/`or`/`not` behavior.

### Lingo Opcode Variable and List Foundation

- Variable opcode handlers now cover local, parameter, and global get/set behavior through `ExecutionContext`.
- Global opcodes resolve script name IDs before delegating to VM-owned global callbacks, matching the Java registry path.
- List opcode handlers now cover arg-list and no-return arg-list packing, linear-list construction, and duplicate-preserving property-list construction.

### Lingo Opcode Simple String Foundation

- Simple string opcode handlers now cover concatenation, padded concatenation, case-insensitive contains, and case-insensitive starts-with checks.
- String coercion follows the Java datum `toStr` path for void, strings/string chunks, symbols, references, lists, and property lists.
- Field-provider-backed string chunk mutation and movie-property-backed item delimiter integration remain deferred to later VM runtime integration slices.

### Lingo Opcode Basic Property Foundation

- Property opcode handlers now cover receiver script-instance get/set, object property get/set for script instances and property lists, and object property reads for lists, strings, points, rectangles, and colors.
- Chained property reads now reuse the same data-owned object property path, and top-level `_player`/`_movie` property reads produce C++ reference datums.
- Legacy property ID opcodes now cover string chunk counts and last-chunk reads, with provider-backed targets consuming their stack operands as safe no-ops until runtime providers are wired.
- `GET_FIELD` now consumes field identifier/cast operands and returns the Java-compatible empty string fallback when no field provider is wired.
- Built-in movie constants and basic `the paramCount`/`the result` lookups are available without a provider.
- Sprite, cast member/library, Xtra, timeout, sound, image, and full movie/stage provider-backed property dispatch remain deferred to later runtime integration slices.

### Lingo Opcode Local and External Call Foundation

- Local call opcodes now dispatch to script-local handlers through `ExecutionContext` and honor no-return arg lists.
- External call opcodes now resolve handler names, dispatch to VM handler callbacks or registered builtins, fall back to built-in constants for zero-argument calls, and honor no-return arg lists.
- Handler execution errors are caught at opcode dispatch, set the VM error-state callback, and return VOID like the Java runtime path.

### Lingo Opcode Object Method Foundation

- `OBJ_CALL` now dispatches data-owned list, property-list, string, point, rectangle, and script-instance methods through the C++ opcode registry.
- Receiver-style external calls now fall back to the same data-owned method dispatch path after handler and builtin lookup.
- List and property-list method dispatch covers Java-compatible mutation and lookup helpers such as `getAt`, `setAt`, `append`, `addProp`, `getProp`, `count`, `sort`, and duplicate-preserving property insertion.
- VarRef receiver dispatch now resolves referenced context variables for string-like `getProp`, `char`, and `count` object methods.
- Mutable ChunkRef creation/deletion remains deferred until a dedicated mutable chunk-reference datum is ported.

### Lingo Opcode Object Construction Foundation

- `NEW_OBJ` now handles script object construction from arg lists through the C++ opcode registry.
- Script construction delegates to the registered `new` builtin/new-instance callback when available, then falls back to named C++ script-instance datums.
- Cast-provider-backed script lookup, declared property preinitialization, and automatic `new` handler invocation remain deferred to the full C++ cast/VM runtime integration.

### Lingo Opcode String Chunk Extraction Foundation

- `GET_CHUNK` now extracts string char, word, item, and line chunks through the C++ opcode registry.
- Chunk extraction mirrors Java stack order, sequential line/item/word/char narrowing, negative-index last-chunk behavior, and out-of-range empty-string results.
- String chunk mutation opcodes and movie-property-backed item delimiter integration remain deferred to the next focused string opcode slice.

### Lingo Opcode Chunk Variable Reference Foundation

- C++ `Datum::VarRef` now carries the Java-compatible variable type and raw index needed by chunk mutation opcodes.
- `PUSH_CHUNK_VAR_REF` now pops the raw variable index and pushes a typed variable reference datum through the C++ opcode registry.
- Resolving and mutating referenced locals, params, properties, globals, and fields remains deferred to the `PUT_CHUNK`/`DELETE_CHUNK` slice.

### Lingo Opcode Put Variable Foundation

- `PUT` now handles Java-compatible into, before, and after writes for local, parameter, global, and receiver-property variables.
- Variable IDs are decoded from the stack and scaled by the active VM variable multiplier for locals and params, matching the existing get/set opcode behavior.
- Field-provider-backed `PUT` remains a no-op until C++ field/member text mutation is wired into the VM runtime.

### Lingo Opcode Delete Chunk Foundation

- `DELETE_CHUNK` now deletes char, word, item, and line chunks from context variables through the C++ opcode registry.
- Chunk deletion resolves Java-compatible chunk type precedence, negative last-index bounds, delimiter consumption for word/item/line chunks, and out-of-range no-op behavior.
- Field-provider-backed chunk deletion remains deferred until C++ field/member text mutation is wired into the VM runtime.

### Lingo Opcode Put Chunk Foundation

- `PUT_CHUNK` now handles into, before, and after mutations for char, word, item, and line chunks through the C++ opcode registry.
- Chunk insertion/replacement mirrors Java chunk type precedence, char replacement no-op behavior, missing-boundary insertion clamping, and negative char/item/word/line target handling.
- Field-provider-backed chunk replacement and movie-property-backed item delimiter integration remain deferred to the full VM runtime integration.

## Verification

Last verified: 2026-06-10

Commands:

```bash
cmake --build cmake-build-debug --parallel
ctest --test-dir cmake-build-debug --output-on-failure
./gradlew test
```

Result:

- `libreshockwave_cpp_tests`: passed.
- zlib support was detected in the local CMake build and the zlib decompression path is covered by the C++ tests.
- Chunk/audio foundation tests passed through the same CTest executable.
- Cast metadata parser tests passed through the same CTest executable.
- Compact chunk parser tests passed through the same CTest executable.
- Cast list/member chunk parser tests passed through the same CTest executable.
- Lingo opcode and script context parser tests passed through the same CTest executable.
- Script bytecode chunk parser tests passed through the same CTest executable.
- Score chunk parser tests passed through the same CTest executable.
- Director file RIFX loader tests passed through the same CTest executable.
- Afterburner reader tests passed through the same CTest executable.
- Director file Afterburner loader tests passed through the same CTest executable.
- Director file RIFF loader tests passed through the same CTest executable.
- Lazy chunk reparse and non-essential release tests passed through the same CTest executable.
- Cast member and script lookup tests passed through the same CTest executable.
- Palette resolver and DirectorFile palette lookup tests passed through the same CTest executable.
- DirectorFile stage/tempo, associated text, and associated score lookup tests passed through the same CTest executable.
- ScriptChunk name resolution and DirectorFile script-helper empty fallback tests passed through the same CTest executable.
- DirectorFile base path, score presence, external cast, and font lookup fallback tests passed through the same CTest executable.
- Bitmap decoder RLE, scan-width, indexed, RGB555, 32-bit channel, and automatic dispatch tests passed through the same CTest executable.
- DirectorFile BITD bitmap decode integration passed through the RIFX loader fixture in the same CTest executable.
- W3D entry, typed resource, transform, texture format, and lookup tests passed through the same CTest executable.
- Generated font Base64/zlib decode, wrong-length, and invalid-deflate tests passed through the same CTest executable.
- File/path fallback utilities and script formatting utilities passed through the same CTest executable.
- BitmapColorizer 32-bit, indexed, foreground-only, packed-index, and ink predicate tests passed through the same CTest executable.
- PfrBitReader byte, signed, skip, alignment, bit-buffer, and partial-EOF tests passed through the same CTest executable.
- SoundConverter WAV layout, SoundChunk header stripping, signed/endianness conversion, MP3 extraction, IMA ADPCM, and duration tests passed through the same CTest executable.
- CastMember bitmap, script, shape, dimension, type-check, raw chunk, and display string tests passed through the same CTest executable.
- PlayerEvent handler names, event payload records, RenderType, and RenderConfig tests passed through the same CTest executable.
- PlayerState, InputEvent factories, DirectorKeyCodes, and InputState mutation/queue tests passed through the same CTest executable.
- ScoreBehaviorRef, SpriteSpan, ScoreNavigator labels, marker resolution, active sprites/channels, and frame-count tests passed through the same CTest executable.
- Breakpoint, BreakpointManager, WatchExpression, and DebugSnapshot tests passed through the same CTest executable.
- RenderPipelineTrace, RenderSprite, transform mirror, baked bitmap helpers, and FrameSnapshot tests passed through the same CTest executable.
- SoftwareFrameRenderer background, stage-image, alpha, blend, scaling, flip, Director mirror, and special-ink tests passed through the same CTest executable.
- FrameRenderPipelineContext mutation, trace building, snapshot storage, and FrameRenderPipelineStep tests passed through the same CTest executable.
- FrameRenderPipeline step ordering, snapshot return, null-step rejection, and missing-snapshot failure tests passed through the same CTest executable.
- TextRenderer split-line, character-line, line-start, wrapping, and default XMED delegation tests passed through the same CTest executable.
- NetTask GET/POST construction, state transitions, result/error storage, stream status, and display formatting tests passed through the same CTest executable.
- NetManager URL resolution, cache fallback, GET/POST registration, handler-backed completion/failure, latest-task lookup, stream-status prop lists, raw byte/text results, callbacks, shutdown, and clear tests passed through the same CTest executable.
- SoundManager channel validation, volume clamping, backend delegation, Lingo play argument parsing, resolver lookup, format detection, KEY-owned member lookup, and SoundChunk playable conversion tests passed through the same CTest executable.
- TimeoutManager creation, property access/mutation, one-shot/persistent flags, timeout references, names/count, forget, and clear tests passed through the same CTest executable.
- BitmapCache cache-keying, palette invalidation, non-native alpha coercion, indexed matte remap selection/application, and InkProcessor color remap helpers passed through the same CTest executable.
- SpriteState score construction, Director blend-byte mapping, explicit override preservation, dynamic defaults, cursor state, script-instance rebinding, and release resets passed through the same CTest executable.
- SpriteRegistry score/dynamic creation, lookup, score-behavior channel tracking, score updates, identity rebinding, dynamic-member cleanup, revision tracking, removal, and clear tests passed through the same CTest executable.
- HitTester front-to-back bounds hits, static native-alpha thresholds, dynamic transparency-ink alpha hits, forced bounding-box hits, all-hit ordering, type lookup, and flip/scale source sampling passed through the same CTest executable.
- CursorManager editable text, button, explicit sprite cursor, interactive fallback, custom bitmap cursor, global cursor, mask application, hotspot, cursor member encoding, near-white, and navigator-whitespace suppression tests passed through the same CTest executable.
- BehaviorInstance and BehaviorManager ID/property state, behavior-ref parameters, frame-script caching, channel lookup/removal, sprite-instance ordering, and clear tests passed through the same CTest executable.
- EventDispatcher global, frame/movie, sprite/movie, sprite-only, behavior-only, and movie-only dispatch ordering, pass propagation, dynamic script-instance dispatch, sprite handler lookup, mouse interactivity, mouse-handler recognition, debug flag, and stopEvent state tests passed through the same CTest executable.
- SpriteProperties missing defaults, property get/set, revision bumps, cast member assignment, autosizing, registration-aware bounds, cursor lists, script-instance sprite numbers, release cleanup, color refs, and image callbacks passed through the same CTest executable.
- Lingo `GET_CHUNK` char/word/item/line extraction, range, negative last-index, and sequential narrowing tests passed through the same CTest executable.
- Lingo `PUSH_CHUNK_VAR_REF` typed raw-index varref creation tests passed through the same CTest executable.
- Lingo `PUT` local, parameter, global, receiver-property, before, and after mutation tests passed through the same CTest executable.
- Lingo `DELETE_CHUNK` char, word, item, line, negative last-index, delimiter, and out-of-range tests passed through the same CTest executable.
- Lingo `PUT_CHUNK` char replacement/insertion, item replacement, word/line insertion, negative target, and out-of-range no-op tests passed through the same CTest executable.
- Lingo `GET_CHAINED_PROP` list, string, point, property-list, and script-instance reads plus `GET_TOP_LEVEL_PROP` `_player`/`_movie` refs passed through the same CTest executable.
- Lingo legacy `GET` last-chunk/count chunk reads, provider-backed movie/sprite/sound property mappings, and provider-backed `SET` mutations passed through the same CTest executable.
- Lingo `GET_FIELD` provider-backed field lookup, cast-library lookup, provider-missing empty-string fallback, and stack-consumption tests passed through the same CTest executable.
- Lingo VarRef object-call string chunk extraction and string method delegation tests passed through the same CTest executable.
- MovieProperties movie/stage property reads and writes, file/input-backed values, xtra lists, item delimiters, timers, stage background color, random seed, navigation callbacks, and net navigation callbacks passed through the same CTest executable.
- BuiltinRegistry case-insensitive lookup, custom registration, movie label/marker builtins, sprite puppet/cursor/spriteBox builtins, puppetPalette hooks, and Java-compatible no-op sprite builtins passed through the same CTest executable.
- MathBuiltins numeric coercion, integer/float conversion, bit operations, trig, power, min/max, list min/max, and random callback hooks passed through the same CTest executable.
- StringBuiltins string coercion, length, chars, charToNum, numToChar, offset, and getPref/setPref callback hooks passed through the same CTest executable.
- OutputBuiltins debug-gated `put`, Java-style argument joining, default alert output, and alert-hook suppression passed through the same CTest executable.
- CastLibBuiltins castLib/member/field/createMember registration, missing-provider fallback, cast/member provider callbacks, encoded member numbers, search-all lookup, and omitted helper builtins passed through the same CTest executable.
- XtraBuiltins registration, missing-manager behavior, registered-Xtra lookup, `new(xtraRef, ...)` instance creation, handler dispatch, property get/set callbacks, and Java-style display strings passed through the same CTest executable.
- ControlFlowBuiltins return/abort state, param lookup, frame/label `go`, call-target dispatch, list/proplist call snapshots, and omitted update builtins passed through the same CTest executable.
- ListBuiltins list/proplist counts, access, mutation, searches, sorting, constructors, key namespace behavior, and aliases passed through the same CTest executable.
- TimeoutBuiltins `timeout` creation, factory-mode `.new`, named `.new`, `.forget`, property get/set helpers, and missing-provider behavior passed through the same CTest executable.
- NetBuiltins preload/get/post aliases, task result/error/status lookups, stream-status toggling, navigation callbacks, and ExternalParamBuiltins ordered parameter lookup passed through the same CTest executable.
- ImageBuiltins image creation, invalid-dimension handling, white fill defaults, built-in/system palette metadata, provider-resolved member palette metadata, string/ilk behavior, and `importFileInto` callback delegation passed through the same CTest executable.
- SoundBuiltins channel creation, availability, sound-channel method dispatch, property defaults/mutation, and SoundManager playback delegation passed through the same CTest executable.
- ConstructorBuiltins point/rect/union/intersect/color/rgb/paletteIndex/sprite/new registration and callback hooks passed through the same CTest executable.
- TypeBuiltins object/void/type predicates, `value`/`script`/`callAncestor` callback hooks, symbol conversion, and `ilk` alias checks passed through the same CTest executable.
- Lingo VM Scope and ExecutionContext stack, param, local, return, loop, jump, global callback, handler callback, builtin invocation, and call-stack formatting behavior passed through the same CTest executable.
- Lingo VM ExecutionContext name resolver callback and resolver-backed global opcode behavior passed through the same CTest executable.
- OpcodeRegistry stack/control handler registration, custom handler registration, literal/symbol pushes, stack manipulation, return/factory return, and jump opcodes passed through the same CTest executable.
- OpcodeRegistry arithmetic, comparison, and logical handlers passed through the same CTest executable.
- OpcodeRegistry variable, arg-list, linear-list, and property-list handlers passed through the same CTest executable.
- OpcodeRegistry simple string concatenation and containment handlers passed through the same CTest executable.
- OpcodeRegistry basic property handlers, object property reads/writes, built-in constants, and simple `the` lookups passed through the same CTest executable.
- OpcodeRegistry movie-property provider reads/writes and provider-backed `the` lookups passed through the same CTest executable.
- OpcodeRegistry provider-backed object property gets/sets for movie, player, stage, sprite, and integer-as-sprite refs passed through the same CTest executable.
- OpcodeRegistry local/external call handlers, builtin dispatch, no-return calls, constant fallback, and error-state handling passed through the same CTest executable.
- OpcodeRegistry object method calls and receiver-style external method calls for lists, property lists, strings, points, rectangles, timeouts, sound channels, and Xtra instances passed through the same CTest executable.
- OpcodeRegistry `NEW_OBJ` script construction delegation, fallback construction, and non-script rejection passed through the same CTest executable.
- Full Gradle Java test baseline is not green at this checkpoint: `:player-core:test` fails in `ScriptModifiedBitmapTest.scriptModifiedIndexedDarkenUsesPaletteIndicesForSpriteColorRamp` with `expected 0xFF903F20`, actual `0xFF903E1F`. No Java files are changed in this checkpoint.

## Remaining Major Work

- Higher-level media integration.
- Remaining ediM/ALFA bitmap integration, sound, font, text, score, and script chunk decoders.
- Detailed W3D geometry/material decoding and rendering integration.
- Lingo decompiler, VM runtime values, dispatchers, and builtins.
- Player core, rendering pipeline, input, networking, audio, cast management, and debugging.
- WASM/web player replacement strategy in C++.
- Editor replacement strategy in C++.
- Port parity tests against current Java fixtures and integration scenarios.

## Commit Log

- `b1d5f49 Add initial C++ port foundation`
- `4034c08 Port C++ chunk audio foundation`
- `7364e28 Port C++ cast metadata foundation`
- `d86665c Port C++ compact chunk parsers`
- `9f506a1 Port C++ cast list and member chunks`
- `4e3e971 Port C++ script context and opcode foundation`
- `c160ac3 Port C++ script bytecode chunk parser`
- `1e8cfbf Port C++ score chunk parser`
- `d4bcca5 Port C++ director file loader foundation`
- `d0ddeff Port C++ Afterburner reader foundation`
- `cbe591d Integrate C++ Afterburner file loading`
- `7a2276f Port C++ D3 RIFF file loading`
- `cc3203d Port C++ lazy chunk reparsing`
- `ea9e01f Port C++ cast and script lookup`
- `afbfbcb Port C++ palette resolver`
- `d71ac1d Port C++ DirectorFile member resources`
- `89d38fc Port C++ script name helpers`
- `f59edc1 Port C++ DirectorFile metadata helpers`
- `de796a3 Port C++ bitmap decoder foundation`
- `3afd372 Port C++ DirectorFile bitmap decode`
- `b870598 Port C++ W3D file parser`
- `382f342 Port C++ generated font decoder`
- `e4ebbae Port C++ utility formatting helpers`
- `4636ee7 Port C++ bitmap colorizer`
- `7e84729 Port C++ PFR bit reader`
- `59bf47d Port C++ sound converter`
- `f0ac5ed Port C++ cast member wrapper`
- `7196f52 Port C++ player event foundation`
- `25bdb9d Port C++ player input foundation`
- `a7f4a2e Port C++ score navigation foundation`
- `3eb1f34 Port C++ debug breakpoint foundation`
- `7b57f14 Port C++ render pipeline data foundation`
- `9f10f49 Port C++ software frame renderer`
- `183c22e Port C++ render pipeline context`
- `c93152b Port C++ text renderer interface`
- `d104c40 Port C++ network task foundation`
- `2aa7222 Port C++ timeout manager foundation`
- `c8e0755 Port C++ bitmap cache and ink foundation`
- `402b179 Port C++ sprite state foundation`
- `9f00ca8 Port C++ hit tester foundation`
- `1afd911 Port C++ frame render pipeline foundation`
- `2b6b93a Port C++ behavior manager foundation`
- `652eadf Port C++ sprite registry foundation`
- `1e50517 Port C++ sound manager foundation`
- `205a5d2 Port C++ net manager foundation`
- `4e98478 Port C++ cursor manager foundation`
- `55728d1 Port C++ event dispatcher foundation`
- `737ad4b Port C++ sprite properties foundation`
- `754d4bc Port C++ movie properties foundation`
- `0450558 Port C++ builtin registry foundation`
- `9dd2ddf Port C++ constructor builtins foundation`
- `ca6105f Port C++ type builtins foundation`
- `ec745ee Port C++ math builtins foundation`
- `af6294f Port C++ string builtins foundation`
- `4e7a9fe Port C++ list builtins foundation`
- `9892975 Port C++ timeout builtins foundation`
- `933317b Port C++ net builtins foundation`
- `8e78d3c Port C++ sound builtins foundation`
- `500e548 Port C++ output builtins foundation`
- `a7963de Port C++ cast builtins foundation`
- `c1374bd Port C++ xtra builtins foundation`
- `dbc9542 Port C++ control flow builtins foundation`
- `c0cea9e Port C++ image builtins foundation`
- `8e7b9b5 Port C++ Lingo VM scope foundation`
- `1b666a9c Port C++ opcode stack foundation`
- `4d98a05f Port C++ opcode arithmetic foundation`
- `c799873c Port C++ opcode variable list foundation`
- `4fa1851a Port C++ opcode string foundation`
- `b3c08a29 Port C++ VM name resolver foundation`
- `753e8bcb Port C++ opcode property foundation`
- `66292160 Port C++ opcode call foundation`
- `c9be4391 Port C++ opcode object call foundation`
- `10193311 Port C++ opcode object construction foundation`
- `0487784f Port C++ opcode string chunk foundation`
- `87e32e1d Port C++ opcode chunk varref foundation`
- `c9ed19bd Port C++ opcode put foundation`
- `a533d8ff Port C++ opcode delete chunk foundation`
- `6be71365 Port C++ opcode put chunk foundation`
- `cd997d5e Port C++ opcode chained property foundation`
- `c78210ba Port C++ opcode legacy property foundation`
- `9ac23faa Port C++ opcode field fallback foundation`
- `03d07cd5 Port C++ opcode field resolver foundation`
- `3996ec79 Port C++ opcode movie property provider foundation`
- `afc0c541 Port C++ opcode object property provider foundation`
- `d3e3fbc1 Port C++ opcode object method provider foundation`
- Current checkpoint commit message: `Port C++ opcode legacy property provider foundation`
