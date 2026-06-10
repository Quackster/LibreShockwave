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
- `lingo::Datum::deepCopy()` now mirrors Java mutable-container copy semantics for nested lists, property lists, arg lists, points, rectangles, media, image refs, and string chunks while preserving script-instance reference identity.
- `lingo::vm::datum::DatumFormatter` ports Java scalar, typed, brief, expanded, detailed, and display type-name datum formatting, including escaped control-character display and recursion guards for mutable datum containers.

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
- `cast::XmedTextParser` foundation for Director text-Xtra XMED chunks, including text/font/color/alignment/dimension parsing, section `0004` style-run ranges, section `0006` style-record font/size resolution, section `0007` paragraph alignment records, and primary span font selection into `XmedStyledText`.
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
- `DirectorFile` now supports `ediM` JPEG RGB sidecar bitmap decoding through a pluggable JPEG decoder hook, optional `ALFA` RLE alpha-mask application, and Java-compatible JPEG decode pending flags.

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
- `util::StringUtils` ports Java display-string truncation, escaped control-character display, and HTML text escaping helpers.
- `lingo::vm::util::StringChunkUtils` ports Java string chunk counting, last/specific/ranged chunk extraction, direct item/word/line range scanning, char splitting, whitespace-word detection, item delimiters, and Director line-delimiter selection.
- `lingo::vm::util::AncestorChainWalker` ports Java script-instance property lookup, owner discovery, property mutation, ancestor-depth reads, depth-limited traversal, and non-script ancestor assignment rejection for opcode and method dispatch paths.
- `lingo::vm::datum::DatumFormatter` ports Java stack/display formatting helpers for scalars, nested lists, prop-lists, arg-lists, script instances, refs, control-character escaping, and JSON-style detailed output.
- `format::ScriptFormatUtils` ports literal type names, literal formatting, script type display names, name/handler fallback resolution, shared truncation, and line-ending normalization.

### Bitmap Colorizer

- `bitmap::BitmapColorizer` ports Director foreColor/backColor bitmap recoloring for 32-bit and indexed bitmap surfaces.
- Raw indexed-data colorization handles 1-bit, 2-bit, 4-bit, and 8-bit packed palette indices before bitmap decode.
- Ink-mode helper predicates for colorization and backColor usage are available in C++.

### PFR Font Bit Reader

- `font::PfrBitReader` ports byte-aligned big-endian reads and MSB-first bit reads used by the Java PFR1 font parser.
- Signed 8-bit, 16-bit, and 24-bit reads preserve Java sign-extension behavior.
- Bit reads preserve partial EOF behavior and byte-alignment reset on byte-level reads, position changes, and skips.

### Bitmap Font and Registry Foundation

- `font::BitmapFont` ports the Java bitmap-font grid container, advance widths, draw offsets, overflow glyph storage, ARGB glyph drawing, and Java-compatible alpha blending.
- `font::BdfParser` ports BDF bitmap-font parsing into `BitmapFont` instances, including font metrics, glyph bounding boxes, BDF row bit placement, default widths, and extended overflow glyphs.
- `font::Pfr1Font` ports PFR1 magic/header validation, logical font matrix parsing, physical-font metrics, FontID extra-item extraction, delta-encoded character records, simple outline nibble-command parsing including curve opcode control-point decoding, compound glyph component expansion, bitmap glyph payload extraction, uppercase-to-lowercase outline fallback, and lenient partial-parse behavior.
- `font::BitmapFont::fromPfr1` ports Java's direct PFR bitmap-font rasterization path, including target-size scaling, matrix-aware outline scanline fill with cubic curve flattening, overflow glyph cells, embedded bitmap-glyph copy, lowercase fallback cell copying, and PFR ascent/line-height metrics.
- `font::Pfr1TtfConverter` ports the Java minimal TrueType table writer for parsed PFR1 outlines, including `cmap`, `glyf`, `head`, `hhea`, `hmtx`, `loca`, `maxp`, `name`, `OS/2`, and `post` table construction.
- `font::TtfBitmapRasterizer` ports Java's pure TrueType table parser and bitmap rasterizer for simple glyph outlines, including `cmap` format 4 parsing, `loca`/`glyf` glyph decoding, repeated flags, quadratic-curve flattening, overflow glyph cells, metrics, and blank-font rejection for PFR fallback.
- `player::cast::FontRegistry` ports prebuilt bitmap-font cache lookup, PFR1 font registration, converted PFR-to-TTF byte caching, pure TTF bitmap rasterization/cache population, direct PFR bitmap-font rasterization fallback, Director font aliases, canonical font-name normalization, and basic alias resolution for the future C++ simple text renderer path.
- Renderer-side PFR anti-alias fidelity, platform text shaping, and bundled platform font-family selection remain deferred to later font-rendering slices.

### Sound Converter

- `audio::SoundConverter` ports WAV construction for raw PCM and `SoundChunk` data, including Director big-endian 16-bit swapping and signed 8-bit conversion.
- MP3 start detection, multi-frame validation, frame-boundary end detection, and MP3 payload extraction are available in C++.
- IMA ADPCM decoding and ADPCM-to-WAV conversion are available with Java-compatible step/index table behavior.

### Cast Member Metadata Wrapper

- `cast::CastMember` ports the Java high-level cast member wrapper around `CastMemberChunk`.
- Type-specific bitmap, shape, film-loop, script, and Shockwave 3D metadata are parsed lazily at wrapper construction.
- Type checks, dimensions, registration point helpers, raw chunk access, and display string formatting are available in C++.
- Runtime bitmap image assignment now stores a copied, script-modified member bitmap and updates member width/height/registration metadata for downstream sprite and property providers.
- Runtime bitmap replacements now preserve existing `member.image` references by updating the live runtime bitmap object in place when one already exists, matching Java's live image-ref behavior across image/media/dimension mutation.
- Runtime-created bitmap members now lazily create a 1x1 white, unmodified image on first `member.image` access, matching Java's default dynamic bitmap behavior while preserving live image refs for later replacement.
- Authored bitmap members now decode or synthesize an unmodified live bitmap on `member.image` reads, including runtime palette-override metadata, palette-version re-decode after `paletteRef` changes, and registration-point synchronization.
- Bitmap member alpha-threshold state now initializes from parsed metadata, clamps runtime mutation like Java, and resets with erased/reused dynamic payloads.
- Bitmap member `width`/`height` setters now create white script-modified replacement bitmaps, preserve depth and runtime registration metadata, and no-op successfully for non-positive dimensions.
- Runtime-created cast members can now be constructed without a raw chunk, carry mutable names, and expose their runtime type through the same property surface.
- Runtime-created cast members can now erase their runtime payload, expose `#empty`, and be reused in place as a new dynamic member type.
- Runtime text content can now be stored on cast-member wrappers and survives through the text/field property surface until the runtime payload is erased or reused.
- Runtime palette data can now be stored on cast-member wrappers and clears with the rest of the runtime payload on erase/reuse.
- Runtime script payloads can now be stored on cast-member wrappers and clear with the rest of the runtime payload on erase/reuse.

### Cast Library Manager Foundation

- `player::cast::CastLib` ports lazy cast-library metadata, authored/external file binding state, stable registry binding checks, member chunk maps, script maps, source-prefixed member-name fallback, font-alias/PFR XMED scanning, and Java-compatible cast/member property fallbacks.
- Common cast-member properties now distinguish encoded `number` from raw `memberNum`, expose `castLib` as a cast-lib ref, provide Java-compatible `script`, `scriptText`, and `mediaReady` defaults, and allow Java-compatible authored/runtime `member.name` mutation through the wrapper property surface.
- Common cast-member `type` now reports Director's public `#field` symbol for text, button, and text-Xtra members while preserving internal enum names for non-text member types.
- Bitmap cast-member `paletteRef`/`palette` getters now expose runtime palette metadata first, then fall back to embedded BitmapInfo palette references and built-in palette symbols.
- Bitmap cast-member `paletteRef`/`palette` setters now resolve built-in symbols, palette member refs, and named runtime palette members through the CastLibManager palette surface while storing the member-level override, applying it to script-modified runtime bitmaps, and deferring unmodified authored bitmap changes until the next image read.
- `player::cast::CastLibManager` ports DirectorFile-backed cast-library initialization from MCsL/CAS* chunks, castLib/member number and name lookup, registry-visible member filtering, external-cast cache keys, pending external load tracking, preload-mode loading, and builtin callback installation.
- Runtime bitmap member image mutation, authored bitmap image reads, live `member.image` reference preservation, runtime bitmap default-image creation, text-like image-ref assignment, image-ref bitmap media assignment, bitmap dimension mutation, direct raw-media bitmap assignment, cached imported-image assignment, Director BITD imported-media assignment, dynamic member creation/reuse, dynamic member `erase`, dynamic field text mutation, dynamic palette storage and media copy, script member type properties and script-to-script media copy, field provider lookup/setter callbacks, cast member count callbacks, text-like member media copy, mutable bitmap alpha-threshold properties, mutable and pinned registration-point properties, dynamic bitmap member sprite rendering, dynamic text sprite baking, common text styling property mutation, editable field input mutation, and editable field overlay/clipboard helper state are available for existing cast libraries; non-bitmap imported media payload decoding, platform overlay drawing, and remaining CastLibProvider edge cases remain deferred to later player runtime slices.

### Bitmap Resolver Foundation

- `player::BitmapResolver` ports Player-owned bitmap cast-member decoding across the main movie and loaded external cast sources, including palette override dispatch and a SpriteBaker-compatible decode-provider adapter.
- Bitmap decoding now honors stored cast-member `paletteRef`/`palette` overrides for authored bitmap members, preserving palette-reference metadata on decoded images.
- Movie palette lookup now follows score palette channel, built-in palette, config default palette, cast-library palette member, and DirectorFile fallback paths with frame-based caching.
- `CastLibManager::resolvePaletteByMember` exposes cast-aware authored and dynamic palette member resolution for player/runtime callers, and `resolvePaletteByName` resolves named runtime palette members; broader dynamic member resolver coverage remains deferred to later Player integration slices.

### Player Event and Render Configuration Foundation

- `player::PlayerEvent` ports the Java player event enum and handler-name mapping used by script dispatch.
- `PlayerEventInfo`, `FrameEvent`, and `ExternalCastLoadEvent` port the lightweight Java record payloads used by listeners.
- `render::RenderType` and `render::RenderConfig` port the software-renderer enum and global antialias flag.

### Player Input State Foundation

- `player::PlayerState` ports the stopped/paused/playing playback enum.
- `input::InputEvent` ports queued mouse, right-mouse, and keyboard input payload factories.
- `input::DirectorKeyCodes` ports Java/browser key-code conversion to Director Macintosh virtual key codes.
- `input::InputState` ports mouse, keyboard, selection, caret blink, double-click, rollover, and event-queue state tracking.

### Player Input Handler Foundation

- `player::InputHandler` ports host mouse/key entry points, queued input processing, rollover `mouseEnter`/`mouseLeave`/`mouseWithin` dispatch, mouse-down/up sprite targeting, `mouseUpOutSide` fallback routing, right-mouse and key event routing, and sprite-registry revision bumps after queued input.
- Hit selection uses baked or supplied `RenderSprite` vectors plus `EventDispatcher::isSpriteMouseInteractive`, matching the Java interactive-hit filter without requiring the full C++ `Player` object yet.
- Built-in editable text field focus, selection start/end placement, selection drag extension, printable character insertion, selected-text replacement, backspace, left/right arrow movement, and tab/shift-tab field cycling now mutate runtime text through `CastLibManager` while preserving normal mouse/key event routing.
- `getCaretInfo`, `getSelectionInfo`, `onPasteText`, `getSelectedText`, `cutSelectedText`, and `selectAll` now expose Java-compatible editable field overlay geometry and clipboard editing helpers over baked/supplied `RenderSprite` state.
- Platform overlay drawing and StageRenderer-owning overloads remain deferred; the lower-level C++ runtime cast-member text mutation, text styling, and sprite baking paths are available for those input slices.

### Player Facade Foundation

- `player::Player` now owns and wires the C++ frame context, stage renderer, cast library manager, movie/sprite properties, bitmap resolver, cursor manager, input handler, network manager, timeout manager, sound manager, Xtra manager, sprite baker, bitmap cache, and frame render pipeline.
- Playback control ports the Java state transitions for play, pause, resume, stop, manual step, tick, direct frame/label navigation, event-listener forwarding, base/puppet/score tempo lookup, debug flag propagation, and render snapshot creation with player-state debug metadata.
- External-cast cached-load completion now ports Player-owned synchronous load handling, bitmap-cache and VM handler-cache invalidation, sprite-registry revision bumping, loaded-cast behavior rebinding, typed `ExternalCastLoadEvent` listeners, compatibility cast-loaded callbacks, and `ExternalCastLoadHandler` fanout.
- Full VM provider setup, asynchronous playback, non-bitmap imported-media mutation, and platform external-cast fetching remain deferred to later player runtime slices.

### Player Builtin Context Foundation

- `player::Player` now owns a `lingo::vm::LingoVM` and exposes the VM-owned builtin registry/context through the existing Player accessors.
- The Player VM builtin context wires movie, sprite, net, sound, timeout, cast-library, cast-member, and palette-resolution callbacks to Player-owned managers.
- Tests now exercise Player-backed builtin calls for puppet tempo, cached network preloading, sound channel references, cast member lookup, and palette-aware image construction.

### Player VM Event Dispatch Foundation

- `player::Player` now wires its `EventDispatcher` to VM-backed script handler lookup and execution for behavior and movie-script targets.
- Event dispatch resolves `ScriptChunk` handler names through target-provided `ScriptNamesChunk` metadata or VM fallback lookup, passes behavior receivers as script-instance `me`, and maps VM `pass`/`stopEvent` runtime builtins back to dispatcher propagation state.
- `EventDispatcher` can now merge manually registered movie-script targets with a dynamic movie-script supplier; Player supplies main-movie scripts from `DirectorFile` and already loaded external-cast movie scripts without forcing external cast loads.
- Script-instance event targets can resolve bytecode from their cast-member script references, enabling timeout targets and sprite-attached script instances to use the same VM invoker as score behaviors.
- `Player::prepareMovieFoundation` now dispatches timeout-backed `prepareMovie`, movie-script `prepareMovie`, preload-mode-1 casts, first-frame sprite initialization, timeout/global `prepareFrame`, movie-script `startMovie`, timeout-backed `startMovie`, global `enterFrame`, timeout-backed `exitFrame`, sprite/movie `exitFrame`, and a final preload-mode-1 cast pass; `stop()` dispatches timeout-backed `stopMovie` before movie scripts.
- Player now snapshots `_movie.actorList` script instances during frame cycles and dispatches `stepFrame`, `prepareFrame`, `enterFrame`, and `exitFrame` through the VM with the actor instance argument.
- Elapsed timeout processing now runs after `executeFrame()` and before `advanceFrame()` for `tick()` and `stepFrame()`, invoking script-instance timeout targets through the VM and falling back to global movie handlers for non-instance targets.
- Tests cover file-backed Player dispatcher movie-script discovery, explicit movie-script bytecode dispatch, startup `prepareMovie`/`prepareFrame`/`startMovie`/`enterFrame`/`exitFrame` movie-script and timeout-target bytecode dispatch, actorList frame-event dispatch, elapsed timeout dispatch, `stopMovie` timeout/movie dispatch, plus VM-backed Player builtin and preference storage access.

### Hit Testing Foundation

- `input::HitTester` ports front-to-back visible sprite hit selection over baked `RenderSprite` lists or direct `StageRenderer` frame lookup, hit type lookup, and all-hit collection with channel filter/forced bounding-box support.
- Bounds fallback, scaled bitmap sampling, flip/mirror coordinate adjustment, static native-alpha bitmap thresholds, dynamic transparency-ink alpha checks, last-baked-sprite preference, and `StageRenderer::getSpritesForFrame` fallback are available in C++.

### Cursor Manager Foundation

- `player::CursorManager` ports cursor-code selection for hit sprites, editable text fields, button members, explicit sprite cursors, interactive sprite fallback, global cursor values, and custom bitmap cursors.
- Custom cursor bitmap/mask resolution, cursor hotspot lookup, cursor member encoding, and Director-style white-mask transparency are available through provider callbacks until the C++ stage/cast managers are ported.
- Navigator-whitespace suppression over matte, behavior-backed baked sprites is ported to keep interactive cursors from appearing over near-white masked UI regions.

### Sprite Runtime State Foundation

- `sprite::SpriteState` ports score-backed and dynamic sprite runtime state, including position, dimensions, visibility, puppet state, ink/blend/trails/stretch, colors, flip flags, rotation/skew, cursor members, script-instance lists, and dynamic member overrides.
- Score synchronization preserves explicit Lingo overrides, score-default application uses Director's inverted blend-byte mapping, and score rebinding can either clear or preserve attached script instances.
- StageRenderer collection now consumes sprite state for score-backed and dynamic/puppeted render sprite metadata; SpriteBaker rasterization integration remains deferred.

### Sprite Registry Foundation

- `render::SpriteRegistry` ports runtime sprite-state ownership, score-driven creation, dynamic sprite creation, per-channel lookup, and score-behavior channel tracking.
- Score updates preserve explicit sprite overrides, rebind changed score identities while keeping attached script instances, and bump revisions for cache-visible identity changes.
- Retired dynamic member bindings are cleared for both dynamic channels and score-backed sprites, matching the Java cleanup path used when member slots are recycled.

### Behavior Instance Manager Foundation

- `behavior::BehaviorInstance` ports behavior IDs, script references, score behavior references, sprite/frame identity, persistent script-instance receiver properties, and begin/end sprite lifecycle flags.
- `behavior::BehaviorManager` ports instance registration, property parameter application from behavior refs, per-channel lookup, frame-script caching, instance removal, sprite-instance ordering, and clear state.
- Dynamic `ScriptInstanceRef`-to-script resolution and richer behavior lifecycle VM coverage remain deferred to later C++ event dispatcher and Lingo VM runtime slices.

### Event Dispatcher Foundation

- `event::EventDispatcher` ports Director event ordering for sprite behaviors, frame behavior, movie scripts, sprite-only dispatch, behavior-only dispatch, and movie-script-only dispatch.
- Handler lookup uses real `ScriptChunk`/`ScriptNamesChunk` metadata where available, with callback hooks for handler invocation and dynamic script-instance response checks until the C++ Lingo VM is ported.
- Pass propagation, stopEvent state, mouse-handler detection, and sprite mouse-interactivity checks across score behaviors and dynamic script instances are available in C++.

### Frame Context Foundation

- `player::frame::FrameContext` ports frame navigation state, pending go-to-frame behavior, first-frame initialization, active/entered channel tracking, beginSprite/endSprite lifecycle dispatch, frame script setup, actor/timeout callback hooks, event listener notifications, and reset behavior.
- Frame transitions coordinate `ScoreNavigator`, `BehaviorManager`, `EventDispatcher`, and `SpriteRegistry`, including puppeted sprite persistence across score-span exits and external-cast behavior rebinding hooks.
- Startup Flags behavior quirks and full Player render-loop integration remain deferred to later C++ VM/player slices.

### Sprite Properties Foundation

- `player::SpriteProperties` ports Lingo sprite property get/set behavior over `SpriteState` and `SpriteRegistry`, including missing-sprite defaults, dynamic sprite auto-creation, revision bumps, loc/rect/visibility/puppet/ink/blend/stretch/trails/color/transform/cursor/script-instance properties, and no-op acceptance for Director compatibility properties.
- Member assignment supports cast member refs, encoded slot numbers, name-resolution callbacks, empty-member release resets, intrinsic/runtime autosizing, and synthetic sprite-event broker retention during empty-channel release.
- Sprite event-broker method dispatch now mirrors the Java support for `registerProcedure`, `removeProcedure`, `setID`/`getID`, `setLink`/`getLink`, `setCursor`/`getCursor`, and `setMember`/`getMember`, including synthetic broker creation and pProcList template/expansion semantics.
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
- `put` is gated by either the C++ VM context debug-playback flag or the global `lingo::vm::DebugConfig` debug-playback toggle, and writes through a VM-owned output callback before falling back to stdout.
- `alert` uses a suppressible alert callback first, then writes through the same output callback/default stdout path for Java-compatible alert fallback behavior.

### List Builtins Foundation

- List builtins now register `count`, `getAt`, `setAt`, `addAt`, `deleteAt`, `append`/`add`, prop-list accessors/mutators, `findPos`, `getOne`/`getPos`, `deleteOne`, `sort`, `listp`, `list`, and `getLast`.
- Linear list mutation, proplist positional/key lookup, duplicate `addProp`, type-aware symbol/string key handling, case-insensitive key searches, Lingo-style list equality, and list sorting are covered in C++.
- Point and rectangle datums now share mutable backing across copies, allowing `setAt(point, index, value)` and `setAt(rect, index, value)` to mutate components like the Java runtime.
- `getAt(castLib.member, key)` now resolves cast members by number or name through the C++ cast-member provider hooks.

### Timeout Builtins Foundation

- Timeout builtins now register Java-compatible `timeout`, returning named or factory-mode `TimeoutRef` values.
- `TimeoutBuiltins` exposes method dispatch helpers for `new`, `forget`, and property-style timeout lookup, backed by the ported C++ `TimeoutManager`.
- Timeout property lookup and mutation now route through VM object-property opcodes, while unregistered Java helper functions remain deferred to movie scripts as in the Java baseline.

### Network and External Parameter Builtins Foundation

- Network builtins now register preload/get/post aliases, `netDone`, `netTextResult`, `netError`, `getStreamStatus`, `tellStreamStatus`, `gotoNetPage`, and `gotoNetMovie`.
- Builtins delegate through the C++ `NetManager` and `MovieProperties` navigation callbacks, preserving Java missing-provider defaults and stream-status PropList shape.
- External parameter builtins now resolve ordered context parameters by case-insensitive name or 1-based index.

### Sound Builtins Foundation

- Sound builtins now register Java-compatible `sound(channel)` and `soundEnabled`, returning valid 1-8 sound-channel datums and true availability by default.
- `SoundBuiltins` exposes sound-channel method dispatch helpers for play/queue/stop/status/time/volume/list/ilk behavior backed by the C++ `SoundManager`.
- Sound-channel property get/set helpers cover Java-compatible defaults and no-op accepted properties, with VM object-property opcodes wired to the helper surface.

### Cast Library Builtins Foundation

- Cast library builtins now register `castLib`, `member`, `field`, and `createMember` while intentionally leaving Java's unregistered `getMemNum`/`memberExists` helpers unavailable as builtins.
- Cast and member lookup behavior is exposed through VM/player-owned callback hooks for cast-library resolution, member number/name resolution, member existence, field text lookup, and dynamic member creation.
- Java-compatible fallback behavior is covered for missing cast providers, encoded member slot numbers, search-all member lookup, and empty field defaults.

### Xtra Builtins Foundation

- Xtra builtins now register Java-compatible `xtra(name)` and expose C++ Xtra/XtraInstance datum factories/accessors.
- `new(xtraRef, ...)` now delegates through an Xtra instance-creation callback before falling back to generic object construction.
- Xtra instance handler calls and property get/set behavior are exposed through VM-owned callbacks and can now be backed by the C++ Xtra manager.
- `lingo::xtra::XmlParserXtra` now ports the Java XML Parser Xtra's lightweight `parseString`, `getError`, `count`, `getProp`/`getPropRef`/`getAProp`/`getProperty`, direct property read, empty-document fallback, entity decoding, and instance lifecycle behavior.
- `lingo::xtra::ScriptCallback` ports the Java standalone callback contract used by Xtras to invoke Lingo handlers on script-instance targets.
- `lingo::xtra::MultiuserNetBridge` ports the Java standalone Multiuser transport bridge contract, including `NetMessage`, connect/send/disconnect/isConnected/poll/destroy operations for platform-owned network I/O.
- `lingo::xtra::MultiuserXtra` now ports the Java Multiuser Xtra's transport-abstract bridge surface, including buffer-limit setup, message handler registration/clearing, `connectToNetServer`, `sendNetMessage`, queued `checkNetMessages`, callback-visible `getNetMessage`, waiting-message counts, error strings, destroy cleanup, and frame-tick auto-callback polling.
- `player::xtra::SocketMultiuserBridge` now ports the Java desktop Multiuser socket bridge with background TCP connect, raw UTF-8 send serialization, nonblocking available-data polling into `NetMessage` content, connection-state checks, disconnect, and destroy cleanup.
- `lingo::xtra::XtraManager` now ports registered-Xtra lookup/replacement, Director-facing `xtraList` names including the `Multiuser`/`Multiusr` alias, instance creation, handler/property dispatch, destroy, and tick fanout; `Player` registers `XmlParserXtra` plus the socket-backed `MultiuserXtra`, exposes custom `registerMultiuserXtra(...)` bridge injection, wires Xtra builtins through the manager, exposes registered Xtras through `MovieProperties`, and ticks registered Xtras during frame processing.

### Control Flow Builtins Foundation

- Control-flow builtins now register Java-compatible `return`, `halt`, `abort`, `nothing`, `param`, `go`, and `call`, while leaving `receiveUpdate`/`removeUpdate` unregistered for movie-script routing.
- `return`, `abort`, and `param` now operate over C++ VM-owned handler state in `BuiltinContext`.
- `go` delegates through `MovieProperties` frame/label navigation and `call` iterates single targets, lists, and prop-list values through a VM-owned dispatch callback.

### Constructor Builtins Foundation

- Constructor builtins now register `point`, `rect`, `union`, `intersect`, `color`, `rgb`, `paletteIndex`, `sprite`, and `new` in the C++ builtin registry.
- Point/rect geometry constructors, rectangle union/intersection, RGB and palette-index color construction, palette-index color identity/display, color pass-through, and trimmed hex-string RGB parsing match the Java constructor helpers.
- Point, rect, color, numeric RGB, palette-index, and sprite constructors now share Java-style integer coercion for strings, field text, floats, cast-library refs, sprite refs, and colors.
- Direct `new(scriptRef, ...)` now creates C++ script instances with their cast-member reference and preinitializes declared script properties through provider hooks, while `NEW_OBJ` keeps the VM-aware path for authored `new` handler execution.
- `new(#memberType, castLib)` and generic object construction are exposed through callback hooks until the C++ cast provider, Xtra support, and script-instance VM runtime are ported.

### Type Builtins Foundation

- Type builtins now register `objectp`, `voidp`, `value`, `script`, `ilk`, `listp`, `stringp`, `integerp`, `floatp`, `symbolp`, `symbol`, and `callAncestor`.
- Java-compatible type predicates, symbol conversion, `ilk` type names including field-text-as-string, and Director alias checks such as `#list`, `#linearList`, `#number`, and `#object` are covered in C++.
- `value` now evaluates string literals through the C++ `LingoValueParser`, keeps the VM callback hook for provider-owned evaluation, and preserves resolver-confirmed multi-word member names for downstream script construction.
- `value` now passes VM-backed identifier resolution into nested list/proplist literal parsing so globals and zero-argument handlers embedded inside parsed values match Java's parser fallback.
- `script` now resolves direct cast-member refs, strings, symbols, raw member numbers, encoded slot numbers, and cast-library scoped lookups through C++ provider hooks before falling back to the generic script resolver.
- `script` list-candidate lookup now mirrors Java by evaluating each candidate as an unscoped standalone identifier even when the outer call has a cast-library scope argument.
- `callAncestor` now rejects non-instance `me` arguments and fans out over list targets, invoking the ancestor callback only for script-instance entries and returning the last result like Java.
- VM/provider-dependent script resolution and provider-owned `callAncestor` handler lookup remain exposed through callback hooks until the C++ Lingo VM runtime is ported.

### Score Navigation Foundation

- `score::ScoreBehaviorRef` ports behavior cast-member references with saved parameter storage.
- `score::SpriteSpan` ports frame-span/channel tracking for frame scripts and sprite behaviors.
- `score::ScoreNavigator` ports score span construction, frame-label lookup, active sprite/channel queries, marker resolution, and frame counts.
- `lingo::LingoValueParser` ports Java-style literal parsing for saved behavior property lists, nested list/proplist values, symbols, strings, numbers, colors, points, rectangles, and partial expression fallback.
- `ScoreNavigator` now decodes saved behavior parameter score entries through the C++ value parser and attaches parsed PropList parameters to behavior refs.

### Debug Breakpoint Foundation

- `debug::Breakpoint` and `BreakpointKey` port immutable breakpoint records and stable lookup keys.
- `debug::BreakpointManager` ports add/remove/toggle, enable toggling, script offset maps, JSON serialization, JSON deserialization, and legacy serialization/deserialization.
- `debug::WatchExpression` ports watch expression result/error state and display helpers.
- `debug::ExpressionEvaluator` ports debugger watch/condition expression parsing and evaluation, including variables, literals, arithmetic, comparisons, logical operators with short-circuiting, prop-list/script-instance property access, and log-message interpolation.
- `debug::LifecycleDiagnostics` ports the runtime lifecycle trace toggle, interesting-handler detection, handler/cast/sprite/error log formatting, argument formatting, and printable datum sanitization.
- `debug::DebugSnapshot`, `InstructionDisplay`, and `CallFrame` port immutable debugger UI state payloads without pulling in the full debug controller.

### Render Pipeline Data Foundation

- `render::pipeline::RenderPipelineStepTrace` and `RenderPipelineTrace` port immutable render-pipeline trace records.
- `render::pipeline::RenderSprite` ports renderable sprite metadata, ink-mode decoding, transform mirror detection, baked-bitmap copy helpers, and member identity/name fallbacks.
- `render::pipeline::FrameSnapshot` ports the immutable frame render payload without invoking the not-yet-ported software frame renderer.

### Stage Renderer Foundation

- `render::pipeline::StageRenderer` ports stage size/background state, lazy opaque stage-image creation, script-modified renderable stage-image exposure, visual reset/discard behavior, last-baked sprite storage, and sprite-registry ownership.
- Score-frame sprite collection, dynamic/puppeted sprite collection, locZ/channel ordering, score RGB555 expansion, score color palette fallback, registration-point scaling/mirroring, sprite type inference, score update on frame entry, and sprite-end cleanup are available in C++.
- Cast-member resolver hooks now let Player-backed `StageRenderer` attach high-level runtime `CastMember` wrappers to render sprites, including dynamic slots without raw CASt chunks.
- Broader external-cast fetch completion and non-bitmap dynamic render providers remain deferred to later player render-pipeline slices.

### Sprite Baker Foundation

- `render::pipeline::SpriteBaker` ports ordered sprite bake steps, tick counting for batch bakes, custom bake-step registration, immutable `RenderSprite` baked-bitmap attachment, and text/button size replacement when baked output dimensions differ from score dimensions.
- Bitmap sprites can now bake through an injected decode provider, consume provider-backed live script-modified bitmap buffers before cache/decode, reuse `BitmapCache`, record decode failures, apply Copy-ink 1-bit foreColor/backColor remapping, apply live COPY exact-white backColor remapping, run shared `InkProcessor.applyInk`, and preserve indexed matte/background-transparent remap, native-alpha background-border keying, rectangular DARKEN/LIGHTEN media behavior, Java ink edge-regression parity, and live indexed DARKEN foreColor/backColor ramping.
- Bitmap sprite cache reuse now invalidates on provider-supplied palette-version changes, letting member-level palette overrides re-decode authored bitmap sprites instead of reusing stale processed cache entries.
- Text/button sprites can bake through an injected text provider, file-backed STXT chunks, or file-backed XMED text-Xtra chunks using an injected `TextRenderer`, film-loop sprites can bake from file-backed embedded score chunks or through an injected tick-aware raw-composite provider with parent sprite ink processing, shape sprites bake solid and authored shape bitmaps through shared ink processing, and unsupported sprites pass through without a baked bitmap until their backing resolvers are ported.
- Runtime CastLibManager lookup and broader end-to-end script-modified bitmap scenarios beyond the covered live bitmap provider paths remain deferred.

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
- Default StageRenderer/SpriteBaker frame rendering now ports score collection, dynamic collection, locZ/channel ordering, sprite baking, baked-sprite publication for hit testing, and immutable snapshot building.
- Runtime cast-library backed asset resolution inside the default pipeline remains limited by the currently ported StageRenderer/SpriteBaker providers.

### Text Renderer Interface Foundation

- `render::output::TextRenderer` ports the platform-neutral text rendering and measurement interface.
- Shared line splitting, character-line lookup, line-start lookup, and word-wrap helpers are available in C++.
- The default XMED rendering path delegates to `renderText` using parsed `XmedStyledText` fields, and `DirectorFile` can resolve file-backed XMED chunks for text-Xtra members through KEY* ownership.

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
- `timeout::TimeoutManager` ports timeout creation, one-shot flag storage, forgetting, existence checks, property get/set behavior, timeout names/count, elapsed-time processing with one-shot removal-before-fire behavior, system-event target snapshot dispatch for script-instance targets, and clear state management.
- String/symbol timeout-target `getObject` resolution is handled at the Player VM dispatch layer; richer timeout trace/error reporting remains deferred.

### Bitmap Cache and Ink Helper Foundation

- `render::pipeline::InkProcessor` ports ink-processing predicates, Copy-ink colorization eligibility, backColor resolution, MATTE color fallback for decoded RGB data with active palettes, explicit indexed MATTE palette-index selection, default indexed MATTE slot-0 black/white fallback, foreground/backColor grayscale remapping, indexed color remapping, Darken foreColor offsets, exact-color remapping helpers, shared `applyInk` dispatch, background-transparent keying including exact-match and native-alpha opaque-border fallback behavior, indexed default matte keying, mask alpha, matte flood fill, ADD/ADD_PIN indexed flood-fill isolation, outlined-white body preservation, DARKEN/LIGHTEN rectangular-media matte skipping, Darken tint multiply, and opaque-white conversion.
- `render::pipeline::BitmapCache` ports processed-bitmap cache keys, decode-failure tracking, palette-version invalidation, non-native 32-bit alpha coercion, and indexed matte/background-transparent color remap selection.
- Player-owned live runtime bitmap lookup is wired into `SpriteBaker` through `CastLibManager::findRuntimeMember`; full dynamic member sprite lookup and broader player-resolver decoding remain deferred to the larger render-pipeline port.

### Image Builtins Foundation

- `lingo::Datum` now exposes first-party image references backed by shared `bitmap::Bitmap` instances.
- `ImageBuiltins` ports `image(width, height, bitDepth [, paletteRef])` with white-filled bitmap creation, built-in palette symbol resolution, provider-backed palette metadata, and Java-compatible image display/ilk behavior.
- `importFileInto` delegates platform-specific media import through an injectable context callback and preserves Director-style TRUE/FALSE results.

### Runtime Member Image Import Foundation

- `CastLib::setMemberProp("image", imageRef)` now copies the source bitmap into the runtime member, marks it script-modified, preserves anchor-based registration points, and exposes updated image/width/height/depth/rect/regPoint properties.
- `CastLibManager::importFileIntoMember` now resolves cached external payloads, decodes the Java-compatible `LSWI` imported-image format, assigns it through the member image path, and wires `BuiltinContext::importFileIntoHandler` during callback installation.
- Director `DTIB`/BITD imported bitmap media now falls back through `BitmapInfo` metadata parsing, little-endian BITD payload length handling, `BitmapDecoder` dispatch, cast-aware palette lookup for indexed media, and anchor-point propagation.
- `lingo::Datum::media` now carries raw Director media bytes in C++, bitmap `member.media = imageRef` reuses the script-modified runtime image assignment path, and raw bitmap `member.media` assignment routes bytes through the shared `LSWI`/Director BITD decode path with the same script-modified copy and registration-point behavior as `importFileInto`.
- `player::Player` now wires `SpriteBaker::setLiveBitmapProvider` to runtime cast-member lookup so authored bitmap members whose `image` was mutated render from the live script-modified bitmap before stale decode/cache fallback.
- DirectorFile-backed `ediM` JPEG/`ALFA` sidecar bitmap decode is available through the pluggable decoder hook; non-bitmap imported media remain deferred.

### Runtime Dynamic Member Creation Foundation

- `CastLib` now allocates runtime member slots from 10000, skips authored/dynamic collisions, maps Director type names (`field`/`text`, bitmap, palette, script, button, shape, sound) to C++ `MemberType`, and leaves authored member counts unchanged.
- `CastLibManager` wires `new(#type, castLib)` through the cast-member creator callback and `createMember(name, #type)` through the named creator callback; named creation returns the Java-compatible encoded slot integer.
- Dynamic members resolve by number and name through the existing cast-member lookup and property paths, including runtime name/type property reflection.
- Dynamic member `erase` now clears runtime payloads, marks the slot `#empty`, keeps the slot addressable, and lets the next `createDynamicMember` reuse the first erased high slot in place.
- Player wires member-slot retirement notifications to `SpriteRegistry::clearDynamicMemberBindings`, preventing stale dynamic sprites from holding reused slots.

### Runtime Dynamic Member Render Pipeline

- `StageRenderer` now accepts a high-level cast-member resolver and preserves the prior DirectorFile chunk fallback when no provider resolves a member.
- Dynamic sprite collection now resolves runtime-created cast members, infers sprite type from the high-level member, applies runtime bitmap registration points, and attaches the `CastMember` wrapper to `RenderSprite::dynamicMember`.
- `Player` wires the StageRenderer resolver to `CastLibManager::resolveMember`, so runtime-created bitmap members with script-assigned images flow through the default frame pipeline and SpriteBaker live-bitmap path.
- Dynamic text members with runtime text now bake through the platform text-renderer path before file-backed STXT/XMED fallback.
- Non-bitmap dynamic member baking beyond text remains deferred.

### Runtime Field Text Foundation

- `CastMember` now tracks Java-style dynamic text separately from file-backed text content and clears it as part of dynamic payload erase/reuse.
- `CastLib::getMemberProp("text")` resolves dynamic text first, then associated STXT text with Director-style carriage-return line endings; `setMemberProp("text")`, `setMemberProp("html")`, and text-like string/symbol `media` assignment update runtime text.
- `CastLibManager` now wires `BuiltinContext::fieldResolver` and `fieldSetter` to cast-member text lookup and mutation for member names, scoped member numbers, and encoded cast/member identifiers.
- Editable field UI remains deferred.

### Runtime Dynamic Text Render Foundation

- `SpriteBaker` now renders attached dynamic text members before file-backed text fallback, using the runtime member's current font, font size, font style, alignment, text color, wrapping, antialias, fixed-line-space, and top-spacing properties.
- Dynamic text baking follows the existing text transparent-ink behavior and marks transparent-background results as native-alpha media.
- Editable-field caret/selection rendering remains deferred.

### Runtime Text Style Property Foundation

- `CastMember` now stores Java-compatible runtime text defaults for font, font size, font style, alignment, text color, background color, word wrap, antialias, box type, text rect, fixed line space, top spacing, and editable state.
- `CastLib::getMemberProp` and `setMemberProp` now expose those text-like member properties, including list-valued `fontStyle`, symbol/string `alignment`, Director-style color coercion, and rect/width/height text geometry mutation.
- `CastLibManager::getMemberProp` now routes text-like `image`, boxType-adjusted `height`, and boxType-adjusted `rect` through the installed `TextRenderer`, including Java-style non-wrapped auto-width measurement and native-alpha marking when rendered text contains transparent pixels.
- Text-like `member.image` assignment now copies the source image into member runtime state without forcing the script-modified flag, matching Java's text-member path instead of the bitmap-member mutation path.
- Dynamic text baking now consumes the runtime text style state while retaining sprite-driven transparent/background color handling.
- Editable selection/caret rendering remains deferred.

### Runtime Text Media Copy Foundation

- `CastLibManager::setMemberProp("media", castMemberRef)` now resolves source and target members and copies text content plus runtime text style state when both members are text-like.
- Bitmap-to-bitmap media copy now handles live runtime bitmaps and file-backed authored bitmap sources through the same manager-owned member-resolution path, including source runtime palette override metadata so later target bitmap mutations retain `paletteRef` parity.
- Raw bitmap media byte assignment now handles Java-compatible `LSWI` imported images and Director `DTIB`/BITD payloads directly on bitmap members while rejecting invalid bytes and non-bitmap targets.
- Imported non-bitmap media payload decoding remains deferred.

### Runtime Palette Media Copy Foundation

- `CastMember` now stores runtime palette payloads for dynamic palette members.
- `CastLib::getMemberProp("color")` exposes palette entries as color datums for runtime and file-backed palette members.
- `CastLibManager::setMemberProp("media", castMemberRef)` now clones palette member colors into palette targets, while palette resolution by member/name prefers runtime payloads before file-backed CLUT fallback.
- `CastLibManager::callMemberMethod("duplicate")` now shares palette data between palette members, accepts cast-member refs, encoded slot targets, and raw member-number targets, and supports copying an argument palette into an empty receiver.

### Runtime Script Media Copy Foundation

- `CastMember` now stores runtime script payloads for dynamic script members.
- `CastLib::getMemberProp("scriptType")` exposes runtime/file-backed script chunk types and `member.text` reports Director-compatible empty text for script members.
- `CastLibManager::setMemberProp("media", castMemberRef)` now copies script payloads between script members while rejecting incompatible targets.

### Runtime Cast Member RegPoint Foundation

- `cast::CastMember` now tracks Java-style pinned registration-point state: authored members preserve their member regPoint across image/media assignments, dynamic/reused members adopt assigned bitmap anchors until explicitly pinned, and copied bitmap media inherits the source member's pin state.
- Runtime registration-point mutation synchronizes the anchor metadata on any live runtime bitmap.
- `CastLib::setMemberProp("regPoint", point)` now ports the Java property setter path while rejecting non-point values.

### Runtime Text Member Method Foundation

- `CastLibManager::callMemberMethod` now dispatches text-like `charPosToLoc` and `locToCharPos` through an injected `TextRenderer`, using runtime text content, font, style, alignment, fixed-line-space, and rect width with Java-compatible empty/no-renderer fallbacks.
- `Player::setTextRenderer` now installs a platform text renderer into both `SpriteBaker` and `CastLibManager`, keeping dynamic text sprite baking and Director member-method measurement on the same renderer surface.
- Editable selection/caret rendering remains deferred.

### Cast Member Utility Method Foundation

- `CastLib` now exposes Java-compatible text `lineCount` and `line` member properties over runtime/file-backed text content.
- `CastLibManager::callMemberMethod` now ports Java's simple member `getProp` and text `count(#char/#word/#line/#item)` helper methods, including point/rect/list sub-property extraction through the same builtin callback surface used by VM object calls.
- Editable selection/caret rendering remains deferred.

### Runtime Field Datum Foundation

- `lingo::Datum` now exposes Java-compatible string-like `FieldText` values that preserve source cast/member identity while retaining normal string, truthiness, and numeric coercion behavior.
- `CastLibManager` now returns member-backed `FieldText` datums from the `field(...)` builtin path and provides cached parsed field values for `value(field(...))` through a `BuiltinContext` parsed-field callback.
- Editable field UI remains deferred.

### Lingo VM Scope and Execution Context Foundation

- `lingo::vm::Scope` ports handler stack-frame state, including bytecode position, stack operations, local variables, mutable parameters, receiver-aware display arguments, return state, and loop-return tracking.
- `lingo::vm::ExecutionContext` ports the opcode-facing context layer for stack/local/param/global access, return/error state callbacks, jump-target lookup, local/global handler callback plumbing, builtin invocation, and argument popping.
- Higher-level debug UI/export wiring remains deferred to later VM/player integration slices.

### Lingo VM Name Resolution Foundation

- `ExecutionContext` now accepts a VM-owned name resolver callback for bytecode name IDs.
- Opcode-facing name resolution falls back to script-local `#id` placeholders when no resolver is installed.
- Variable/global opcode tests now exercise resolved names through the same `ExecutionContext::resolveName` path needed by property and call opcodes.

### Lingo VM Runtime Foundation

- `lingo::vm::LingoVM` now owns globals, prefs, a builtin registry/context, an opcode registry, call-stack state, pass/stopEvent runtime builtins, Java-compatible seeded random numbers, step limits, error state, and handler cache invalidation.
- The VM can execute `ScriptChunk::Handler` bytecode end to end through reusable `Scope` and `ExecutionContext` callbacks, including global get/set opcodes, parameter reads, return values, direct builtin calls, builtin fallback from `callHandler`, and formatted call-stack frames.
- VM-backed `value(...)` evaluation now mirrors Java's identifier fallback by resolving active globals and zero-argument global handlers through the C++ VM before returning void for unresolved identifiers.
- Handler execution now mirrors Java's effective-receiver path for script instances passed as the first `me` argument, and same-script/same-receiver `deconstruct` reentry is skipped while ancestor-script cleanup remains allowed.
- `lingo::vm::DebugConfig` ports the Java global debug-playback toggle; new VM contexts sample it into their builtin context while the existing player-owned debug flag remains available through the VM builtin context.
- Tests cover direct handler execution, VM-backed globals/prefs/random, pass and stopEvent callbacks, step-limit cleanup, and visible call-stack state during opcode execution.

### Lingo VM Safepoint Foundation

- `lingo::vm::LingoVM` now ports Java's long-handler safepoint checks, including configurable tick deadlines, a static GC/safepoint callback, and the 60-second per-handler timeout.
- `player::Player` now arms and clears the VM tick deadline around `tick()` and `stepFrame()` frame execution.
- Tests cover deterministic safepoint callback firing, tick-deadline exceptions, handler-timeout exceptions, and call-stack cleanup through injected VM time providers.

### Lingo VM Trace Listener Foundation

- `lingo::vm::TraceListener` ports Java's handler, instruction, variable-set, error, debug-message, and instruction-trace opt-out callback surface.
- `lingo::vm::LingoVM` now exposes trace-listener setters/getters, Java-compatible trace argument formatting, handler enter/exit notifications, instruction snapshots with stack/local/global state, and error notifications before alertHook suppression.
- `lingo::vm::ExecutionContext` now emits local, parameter, global, and script-instance property variable-set trace callbacks from the normal bytecode mutation path.
- `lingo::vm::trace::InstructionAnnotator` ports Java's shared bytecode annotation utility for literal pushes, symbols, locals, params, globals, properties, local/external/object calls, and jump targets.
- `lingo::vm::trace::TracingHelper` ports Java's reusable trace payload builder for handler info, instruction info, stack snapshots, named local/parameter capture, globals snapshots, and annotation construction; `LingoVM` now delegates trace payload construction through it.

### Lingo VM Console Trace Foundation

- `lingo::vm::LingoVM` now ports Java's console trace controls, including targeted `addTraceHandler`/`removeTraceHandler`/`clearTraceHandlers`, random-call tracing, and full `setTraceEnabled` handler/instruction/return output.
- Console trace output defaults to stdout and can be redirected through an injectable trace-output handler for tests and future embedding surfaces.
- Instruction trace output mirrors Java's loop-suppression behavior for repeated bytecode offsets within the same script trace context.
- VM instruction trace snapshots now use the shared instruction annotator instead of raw instruction strings, matching Java's debugger/trace annotation path.
- `lingo::vm::trace::ConsoleTracePrinter` ports Java's reusable console trace listener, handler/instruction formatting helpers, non-void return formatting, output redirection, and per-handler loop suppression state.
- Higher-level debug UI/export wiring remains deferred to later VM/player integration slices.

### Lingo VM Deferred Dispatch Foundation

- `lingo::vm::LingoVM` ports Java-compatible deferred script-instance call queuing, active-call-stack checks, flush-state guards, invalid-target filtering, queued-call ordering, and automatic flushing when the outermost handler exits.
- Deferred VM tasks can now be queued and explicitly flushed at safe call-stack-empty boundaries, including tasks queued by tasks during a flush.
- `player::Player` now wires the builtin `callTargetHandler` provider to Player event dispatch for direct script-instance targets and sprite-channel `scriptInstanceList` targets, and flushes VM deferred tasks after frame/timeout execution in `tick()` and `stepFrame()`.
- Player-backed `call(...)` target dispatch now returns direct script-instance handler results and the last sprite `scriptInstanceList` handler result instead of discarding them.
- Script-instance object calls now port Java's numeric `closeThread` defer path, queuing the call through the VM task boundary only while a handler is active and no deferred flush is already in progress.
- `call(...)` now snapshots Java message-struct prop-list arguments per target dispatch, deep-copying nested content while preserving connection script-instance references and leaving non-message prop lists forwarded by reference.
- Additional provider-specific script-instance method dispatch quirks remain deferred to later focused VM/player slices.

### Lingo VM AlertHook Foundation

- `lingo::BuiltinContext` now exposes an alertHook callback used by `alert()` before fallback alert/output handling.
- `lingo::vm::LingoVM` now exposes `fireAlertHook`, guards recursive alertHook execution, and suppresses script errors when the hook returns truthy while preserving call-stack unwind and deferred-call flushing.
- `lingo::vm::AlertHookHandler` ports Java alertHook recursion-depth tracking, error-handler recognition, skip diagnostics, and guarded hook invocation; `LingoVM` delegates alertHook depth checks through it.
- `player::Player` now wires the movie `alertHook` script-instance property through the existing VM event dispatch path and uses the handler return value to decide whether the alert/error was handled.
- Higher-level debug UI/export wiring remains deferred to later VM/player integration slices.

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
- String chunk reads and counts now route through the shared C++ `StringChunkUtils` port while mutation helpers retain byte-range updates for chunk refs.
- String chunk mutation is covered by the focused `PUT_CHUNK`/`DELETE_CHUNK` opcode slices.

### Lingo Opcode Basic Property Foundation

- Property opcode handlers now cover receiver script-instance get/set, object property get/set for script instances and property lists, object property reads for lists, strings, points, rectangles, colors, and images, and image object writes for `useAlpha`/`paletteRef`.
- String and FieldText object property reads now expose Java-compatible `lineCount` and `line` values through the shared opcode property path.
- Chained property reads now reuse the same data-owned object property path, and top-level `_player`/`_movie` property reads produce C++ reference datums.
- Legacy property ID opcodes now cover string chunk counts, last-chunk reads, provider-backed sprite/sound properties, and `number of castMembers of castLib N` through the C++ cast-member count callback.
- `lingo::vm::PropertyIdMappings` ports Java's movie/sprite/animation/animation2/sound property ID tables, including the Java fallback name for unknown sound properties.
- `GET_FIELD` now consumes field identifier/cast operands and returns the Java-compatible empty string fallback when no field provider is wired.
- Built-in movie constants and basic `the paramCount`/`the result` lookups are available without a provider.
- Cast-member object property reads/writes now delegate unknown member properties through C++ provider hooks after built-in metadata handling.
- Remaining specialized provider-backed property cases remain deferred to later runtime integration slices.

### Lingo Opcode Local and External Call Foundation

- Local call opcodes now dispatch to script-local handlers through `ExecutionContext` and honor no-return arg lists.
- External call opcodes now resolve handler names, dispatch to VM handler callbacks or registered builtins, fall back to built-in constants for zero-argument calls, and honor no-return arg lists.
- Handler execution errors are caught at opcode dispatch, set the VM error-state callback, and return VOID like the Java runtime path.

### Lingo Opcode Object Method Foundation

- `OBJ_CALL` now dispatches data-owned list, property-list, string, point, rectangle, and script-instance methods through the C++ opcode registry.
- Receiver-style external calls now fall back to the same data-owned method dispatch path after handler and builtin lookup.
- List and property-list method dispatch covers Java-compatible mutation and lookup helpers such as `getAt`, `setAt`, `append`, `addProp`, `getProp`, `count`, `sort`, and duplicate-preserving property insertion.
- `lingo::vm::dispatch::ImageMethodDispatcher` ports Java image receiver method/property dispatch through the existing C++ image helper surface, including null-image fallbacks, `fill`/`draw`/`copyPixels`/`setAlpha` mutation, image duplication/cropping/trimming/matte-mask creation, indexed palette fill/getPixel color identity, pixel access, `useAlpha`, and palette-reference property routing.
- `lingo::vm::dispatch::ListMethodDispatcher` ports Java linear-list receiver methods, including padded `setAt`, clamped `addAt`, value search/delete, `join`, case-insensitive sort, deep-copy `duplicate`, and out-of-range `getAt` diagnostics for `OBJ_CALL`.
- `lingo::vm::dispatch::MemberRegistryMethodDispatcher` ports Java script-instance member-registry receiver prefill/fallback dispatch over the existing C++ alias registry logic, including `getMemNum`, `exists`/`memberExists`, `getMember`, `readAliasIndexesFromField`, stale slot cleanup, bootstrap script-member visibility, and persistent alias lookup.
- `lingo::vm::dispatch::PropListMethodDispatcher` ports Java property-list receiver methods, including sub-list indexed `getProp`, typed `getAt`/`setAt`, duplicate-preserving `addProp`, `getOne`/`findPos`, positional key reads/deletes, first/last reads, and deep-copy `duplicate`.
- `lingo::vm::dispatch::ScriptInstanceMethodDispatcher` ports Java script-instance receiver dispatch through the existing C++ handler/property/member-registry surface, covering property/list-like accessors, ancestor-aware setters, nested list/proplist mutation, counts, `ilk`, deferred numeric `closeThread`, authored handler lookup, member-registry prefill/fallbacks, and property fallback reads.
- `lingo::vm::dispatch::SoundChannelMethodDispatcher` ports Java sound-channel method/property dispatch through the C++ `SoundBuiltins`/`SoundManager` surface, covering playback controls, status/time reads, playlist stubs, volume get/set, loop/time no-op properties, and null-context VM fallback behavior.
- Property-list object methods now preserve Java's symbol/string key namespace rules for `getAt`, `setAt`, `setProp`, and `deleteProp`, including exact-case cross-type fallback and numeric out-of-range `setAt` string-key insertion.
- List and property-list `duplicate` object methods now use Java-style deep copies so nested mutable containers are independent of the source value.
- String receiver dispatch now covers direct `getProp`/`getPropRef` chunk extraction, and VarRef receiver dispatch resolves referenced context variables for string-like `getProp`, `getPropRef`, `char`, and `count` object methods.
- `lingo::vm::dispatch::StringMethodDispatcher` ports Java string receiver `length`, `char`, `count`, `getProp`, and `getPropRef` methods, including symbol-only chunk type arguments, end-parameter resolution, and movie item-delimiter support for `OBJ_CALL`.
- Mutable ChunkRef creation and char-range deletion now use a dedicated C++ datum; broader mutable chunk-ref operations remain deferred.
- VarRef char `getProp` and mutable ChunkRef char deletion now match Java's inverted-range behavior, returning an empty chunk or leaving the source string unchanged when the requested range is empty.
- ScriptRef receiver dispatch now supports Java-compatible `new` method calls through the registered constructor builtin.
- SpriteRef receiver dispatch now walks provider-backed `scriptInstanceList` script instances before falling through to the sprite method handler, matching Java's sprite object-call behavior/broker dispatch order.
- ScriptInstance receiver dispatch now supports Java-compatible property/list-like `getAt`, `setAt`, `getAProp`, `setAProp`, `getProp`, `getPropRef`, `setProp`, `addProp`, `deleteProp`, `count`, `ilk`, `addAt`, handler-existence/dispatch methods, and prefilled `pAllMemNumList` member-registry lookups over C++ script-instance datums.
- ScriptInstance VM property writes now match Java's ancestor-chain walker guard by ignoring non-instance `ancestor` assignments through `SET_PROP`, `SET_OBJ_PROP`, and receiver `setAt`/`setAProp`/`setProp` while preserving valid script-instance replacement.
- ScriptInstance property traversal now uses the Java VM's bounded ancestor-chain depth so cyclic/self-referential ancestors return VOID/false for missing properties instead of recursing indefinitely.
- ScriptInstance member-registry dispatch now lazily seeds stable provider-backed member names, rejects hidden broad member lookups, allows script-member bootstrap lookup, and pre-fills registry entries before authored handlers run.
- ScriptInstance `readAliasIndexesFromField` now imports Java-compatible `memberalias.index` mappings into `pAllMemNumList`, including mirrored negative aliases and transient source-cast aliases that avoid publishing hidden raw targets.
- ScriptInstance member-registry lookups now validate pre-indexed registry slots against provider visibility and remove stale hidden positive or mirrored entries before falling back to lazy resolution.
- ScriptInstance member-alias text is now remembered per registry owner and refreshed from available `memberalias.index` fields during lookup so aliases can be lazily restored after `pAllMemNumList` entries are cleared.
- CastMemberRef receiver dispatch now delegates methods through a provider-backed C++ builtin context hook, matching Java's cast-member method provider path.
- CastLib member-accessor receiver dispatch now supports Java-compatible `castLib(n).member.getAt(key)` member lookup by number or name.
- SpriteRef receiver dispatch now delegates methods through a provider-backed C++ builtin context hook for behavior/broker integration.
- Image receiver dispatch now supports Java-compatible `fill`, `draw`, `setAlpha`, `createMatte`, `createMask`, `copyPixels`, `duplicate`, `crop`, `trimWhiteSpace`, `getAt`, `getPixel`, and `setPixel` methods over C++ bitmap refs.
- Image `fill` and `draw` now resolve small integer colors and `paletteIndex(...)` colors through the target bitmap palette when one is attached, matching Java's bitmap-aware color conversion.
- Image `createMatte` and `createMask` cover native-alpha matte extraction, RGB/indexed flood-fill matte extraction, and direct grayscale mask output.
- Image `copyPixels` covers default rectangular copy, nearest-neighbor scaling, COPY source-alpha/global-blend compositing, maskImage clipping, transparent/background-transparent keying including native-alpha source fallback and inverse white alpha-mask ink conversion, arithmetic ink modes, grayscale #color/#bgColor remaps including source-palette `paletteIndex(...)` remap resolution, DARKEN #bgColor source tinting for grayscale, indexed-shade, and custom-palette sources, quad destination transforms, transformed maskImage props, palette-index metadata, palette metadata, and anchor propagation.

### Lingo Opcode Object Construction Foundation

- `NEW_OBJ` now handles script object construction from arg lists through the C++ opcode registry.
- Script construction delegates to the registered `new` builtin/new-instance callback when available, then falls back to named C++ script-instance datums.
- Fallback script construction now resolves named scripts through the C++ script resolver hook, preinitializes declared provider properties to VOID, invokes a resolved `new` handler for side effects, and still pushes the constructed instance like the Java opcode path.
- Full cast/VM runtime integration for provider-owned script dispatch remains deferred to later slices.

### Lingo Opcode String Chunk Extraction Foundation

- `GET_CHUNK` now extracts string char, word, item, and line chunks through the C++ opcode registry.
- Chunk extraction mirrors Java stack order, sequential line/item/word/char narrowing, negative-index last-chunk behavior, and out-of-range empty-string results.
- String chunk mutation opcodes are covered by the focused `PUT_CHUNK`/`DELETE_CHUNK` opcode slices.

### Lingo Opcode Chunk Variable Reference Foundation

- C++ `Datum::VarRef` now carries the Java-compatible variable type and raw index needed by chunk mutation opcodes.
- `PUSH_CHUNK_VAR_REF` now pops the raw variable index and pushes a typed variable reference datum through the C++ opcode registry.
- VarRef resolution and mutation now share C++ context get/set helpers across locals, params, globals, receiver properties, and provider-backed fields for `PUT`, `DELETE_CHUNK`, `PUT_CHUNK`, and string receiver methods.

### Lingo Opcode Put Variable Foundation

- `PUT` now handles Java-compatible into, before, and after writes for local, parameter, global, and receiver-property variables.
- Variable IDs are decoded from the stack and scaled by the active VM variable multiplier for locals and params, matching the existing get/set opcode behavior.
- Field-provider-backed `PUT` writes now route through the C++ field setter hook with Java-compatible identifier normalization and cast-lib propagation.

### Lingo Opcode Delete Chunk Foundation

- `DELETE_CHUNK` now deletes char, word, item, and line chunks from context variables through the C++ opcode registry.
- Chunk deletion resolves Java-compatible chunk type precedence, negative last-index bounds, delimiter consumption for word/item/line chunks, and out-of-range no-op behavior.
- Field-provider-backed chunk deletion now reads through the field resolver and writes through the field setter hook while preserving the field cast-lib operand.

### Lingo Opcode Put Chunk Foundation

- `PUT_CHUNK` now handles into, before, and after mutations for char, word, item, and line chunks through the C++ opcode registry.
- Chunk insertion/replacement mirrors Java chunk type precedence, char replacement no-op behavior, missing-boundary insertion clamping, and negative char/item/word/line target handling.
- Field-provider-backed chunk replacement now reads through the field resolver and writes through the field setter hook while preserving the field cast-lib operand.
- Movie-property-backed item delimiter integration is now covered for chunk deletion and replacement.

## Verification

Last verified: 2026-06-10

Commands:

```bash
cmake --build cmake-build-debug --parallel
ctest --test-dir cmake-build-debug --output-on-failure
git diff --check
./gradlew test
```

Result:

- `libreshockwave_cpp_tests`: passed.
- `./gradlew test`: passed, including the script-modified indexed DARKEN fixed-point baseline and script-bootstrap registry prefill behavior.
- `git diff --check`: passed.
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
- DirectorFile BITD bitmap decode integration plus pluggable `ediM` JPEG/`ALFA` sidecar bitmap decode passed through the RIFX loader fixture in the same CTest executable.
- XMED text parsing for multi-span style runs, underline ranges, referenced style-run font-size selection, per-span font records, paragraph alignment codes, and paragraph record counts passed through the same CTest executable.
- W3D entry, typed resource, transform, texture format, and lookup tests passed through the same CTest executable.
- Generated font Base64/zlib decode, wrong-length, and invalid-deflate tests passed through the same CTest executable.
- File/path fallback utilities, shared string display/HTML escaping helpers, string chunk counting/extraction helpers, Java-compatible ImageMethodDispatcher/StringMethodDispatcher/ListMethodDispatcher/MemberRegistryMethodDispatcher/PropListMethodDispatcher/ScriptInstanceMethodDispatcher/SoundChannelMethodDispatcher receiver methods, and script formatting utilities passed through the same CTest executable.
- BitmapColorizer 32-bit, indexed, foreground-only, packed-index, and ink predicate tests passed through the same CTest executable.
- PfrBitReader byte, signed, skip, alignment, bit-buffer, and partial-EOF tests passed through the same CTest executable.
- BitmapFont glyph drawing, overflow metrics, BDF parsing, direct PFR outline/bitmap/curve glyph rasterization, PFR1 metadata/character-record/simple/compound/curve-outline parsing, PFR-to-TTF table generation/cache registration, pure TTF bitmap rasterization with the bundled Verdana fixture, and FontRegistry prebuilt-cache/PFR-registration/rasterization/alias behavior passed through the same CTest executable.
- SoundConverter WAV layout, SoundChunk header stripping, signed/endianness conversion, MP3 extraction, IMA ADPCM, and duration tests passed through the same CTest executable.
- CastMember bitmap, script, shape, dimension, type-check, raw chunk, and display string tests passed through the same CTest executable.
- CastLib and CastLibManager lazy MCsL/CAS* initialization, member count/name lookup, source-prefixed lookup fallback, member metadata properties, registry filtering, builtin callback installation, external-cache keys, pending external-load bookkeeping, and Player external-cast cached-load callbacks passed through the same CTest executable.
- PlayerEvent handler names, event payload records, ExternalCastLoadHandler callback fanout, RenderType, and RenderConfig tests passed through the same CTest executable.
- PlayerState, InputEvent factories, DirectorKeyCodes, and InputState mutation/queue tests passed through the same CTest executable.
- InputHandler mouse/key queueing, interactive hit filtering, rollover dispatch, mouse-up-outside fallback, focused key dispatch, blur synthesis, dispatcher/sprite supplier hooks, and sprite-registry revision bumps passed through the same CTest executable.
- ScoreBehaviorRef, SpriteSpan, ScoreNavigator labels, marker resolution, active sprites/channels, parsed behavior parameters, and frame-count tests passed through the same CTest executable.
- Breakpoint, BreakpointManager, WatchExpression, ExpressionEvaluator, LifecycleDiagnostics, and DebugSnapshot tests passed through the same CTest executable.
- RenderPipelineTrace, RenderSprite, transform mirror, baked bitmap helpers, and FrameSnapshot tests passed through the same CTest executable.
- StageRenderer stage-image lifecycle, dynamic/puppeted sprite collection, locZ/channel sorting, last-baked sprite storage, sprite-end cleanup, reset behavior, and RGB555 expansion tests passed through the same CTest executable.
- SpriteBaker tick counting, default/custom bake-step dispatch, bitmap decode-provider caching, palette-version cache invalidation, provider-backed live script-modified bitmap priority, live COPY exact-white backColor remapping, live DARKEN white-canvas neutralization, live indexed DARKEN foreColor/backColor ramping, 1-bit Copy-ink color remap, shared bitmap ink processing, text baked-size replacement, file-backed STXT renderer dispatch, file-backed XMED parser/renderer dispatch, provider-backed film-loop parent ink processing, file-backed film-loop sub-score compositing, shape baking, transparency-key shape ink, unsupported pass-through, and external BitmapCache ownership tests passed through the same CTest executable.
- FrameRenderPipeline default StageRenderer/SpriteBaker path, default step names/order, score/dynamic trace summaries, bake tick propagation, baked-sprite publication, snapshot generation, and software-rendered default output tests passed through the same CTest executable.
- SoftwareFrameRenderer background, stage-image, alpha, blend, scaling, flip, Director mirror, and special-ink tests passed through the same CTest executable.
- FrameRenderPipelineContext mutation, trace building, snapshot storage, and FrameRenderPipelineStep tests passed through the same CTest executable.
- FrameRenderPipeline step ordering, snapshot return, null-step rejection, and missing-snapshot failure tests passed through the same CTest executable.
- TextRenderer split-line, character-line, line-start, wrapping, and default XMED delegation tests passed through the same CTest executable.
- NetTask GET/POST construction, state transitions, result/error storage, stream status, and display formatting tests passed through the same CTest executable.
- NetManager URL resolution, cache fallback, GET/POST registration, handler-backed completion/failure, latest-task lookup, stream-status prop lists, raw byte/text results, callbacks, shutdown, and clear tests passed through the same CTest executable.
- SoundManager channel validation, volume clamping, backend delegation, Lingo play argument parsing, resolver lookup, format detection, KEY-owned member lookup, and SoundChunk playable conversion tests passed through the same CTest executable.
- TimeoutManager creation, property access/mutation, one-shot/persistent flags, timeout references, names/count, elapsed-time processing, one-shot removal-before-fire recreation, system-event script-instance target filtering, forget, and clear tests passed through the same CTest executable.
- BitmapCache cache-keying, palette invalidation, non-native alpha coercion, indexed matte remap selection/application, and InkProcessor color remap/applyInk helpers, exact background-transparent keying, native-alpha background-border keying, native-alpha MATTE skip, 32-bit MATTE fallback preservation, MATTE palette fallback, duplicate-RGB explicit indexed MATTE selection, black/white default indexed MATTE fallback, indexed window-shadow MATTE selection, ADD/ADD_PIN flood-fill isolation, RGB ADD_PIN preservation, outlined-white body matte preservation, and DARKEN/LIGHTEN rectangular-media matte skipping passed through the same CTest executable.
- SpriteState score construction, Director blend-byte mapping, explicit override preservation, dynamic defaults, cursor state, script-instance rebinding, and release resets passed through the same CTest executable.
- SpriteRegistry score/dynamic creation, lookup, score-behavior channel tracking, score updates, identity rebinding, dynamic-member cleanup, revision tracking, removal, and clear tests passed through the same CTest executable.
- HitTester front-to-back bounds hits, static native-alpha thresholds, dynamic transparency-ink alpha hits, forced bounding-box hits, all-hit ordering, type lookup, flip/scale source sampling, direct StageRenderer last-baked lookup, and StageRenderer frame fallback passed through the same CTest executable.
- CursorManager editable text, button, explicit sprite cursor, interactive fallback, custom bitmap cursor, global cursor, mask application, hotspot, cursor member encoding, near-white, and navigator-whitespace suppression tests passed through the same CTest executable.
- BehaviorInstance and BehaviorManager ID/property state, behavior-ref parameters, frame-script caching, channel lookup/removal, sprite-instance ordering, and clear tests passed through the same CTest executable.
- EventDispatcher global, frame/movie, sprite/movie, sprite-only, behavior-only, and movie-only dispatch ordering, pass propagation, dynamic script-instance dispatch, sprite handler lookup, mouse interactivity, mouse-handler recognition, debug flag, and stopEvent state tests passed through the same CTest executable.
- FrameContext first-frame setup, pending frame navigation, begin/end sprite dispatch, frame events, actor/timeout hooks, puppeted sprite persistence, force navigation, reset, and BehaviorManager script-resolver hooks passed through the same CTest executable.
- BitmapResolver RIFX-backed BITD bitmap decode, `ediM` JPEG/`ALFA` sidecar decode, explicit and member-stored palette override decode, SpriteBaker provider adapter, movie palette config fallback, null fallback behavior, and CastLibManager palette-member lookup passed through the same CTest executable.
- SpriteProperties missing defaults, property get/set, revision bumps, cast member assignment, autosizing, registration-aware bounds, cursor lists, script-instance sprite numbers, release cleanup, color refs, image callbacks, sprite event broker synthetic registration, per-event expansion, removeProcedure reset, id/link/cursor/member helpers, and Player sprite method hook wiring passed through the same CTest executable.
- Lingo `GET_CHUNK` char/word/item/line extraction, range, negative last-index, sequential narrowing, and provider-backed item delimiters passed through the same CTest executable.
- Lingo `PUSH_CHUNK_VAR_REF` typed raw-index varref creation tests passed through the same CTest executable.
- Lingo `PUT` local, parameter, global, receiver-property, before, and after mutation tests passed through the same CTest executable.
- Lingo `DELETE_CHUNK` char, word, item, line, negative last-index, local/param/global/property/field targets, provider-backed item delimiter, and out-of-range tests passed through the same CTest executable.
- Lingo `PUT_CHUNK` char replacement/insertion, local/param/global/property/field targets, provider-backed item replacement, word/line insertion, negative target, and out-of-range no-op tests passed through the same CTest executable.
- Lingo `GET_CHAINED_PROP` list, string, point, property-list, and script-instance reads plus `GET_TOP_LEVEL_PROP` `_player`/`_movie` refs passed through the same CTest executable.
- Lingo image object-property width, height, rect, depth, useAlpha, ilk, image, and paletteRef reads passed through the same CTest executable.
- Lingo legacy `GET` last-chunk/count chunk reads, provider-backed movie/sprite/sound property mappings, and provider-backed `SET` mutations passed through the same CTest executable.
- Lingo `GET_FIELD` provider-backed field lookup, cast-library lookup, provider-missing empty-string fallback, and stack-consumption tests passed through the same CTest executable.
- Lingo direct-string and VarRef object-call string chunk extraction, mutable char chunk-ref deletion, ScriptRef `new`, string method delegation, and provider-backed item counting tests passed through the same CTest executable.
- Lingo CastMemberRef object-method provider dispatch tests passed through the same CTest executable.
- Lingo SpriteRef object-method provider dispatch tests passed through the same CTest executable.
- Lingo image object-method fill, draw, setAlpha, createMatte, createMask, copyPixels source-palette `paletteIndex(...)` remaps, DARKEN #bgColor source tinting, background-transparent native-alpha source fallback, and inverse white alpha-mask ink conversion, duplicate, crop, trimWhiteSpace, indexed palette fill/getPixel color identity, getAt, getPixel, setPixel, and null-image fallback tests passed through the same CTest executable.
- MovieProperties movie/stage property reads and writes, file/input-backed values, xtra lists, item delimiters, timers, stage background color, random seed, navigation callbacks, and net navigation callbacks passed through the same CTest executable.
- BuiltinRegistry case-insensitive lookup, custom registration, movie label/marker builtins, sprite puppet/cursor/spriteBox builtins, puppetPalette hooks, and Java-compatible no-op sprite builtins passed through the same CTest executable.
- MathBuiltins numeric coercion, integer/float conversion, bit operations, trig, power, min/max, list min/max, and random callback hooks passed through the same CTest executable.
- StringBuiltins string coercion, length, chars, charToNum, numToChar, offset, and getPref/setPref callback hooks passed through the same CTest executable.
- OutputBuiltins context/global-debug-gated `put`, Java-style argument joining, default alert output, and alert-hook suppression passed through the same CTest executable.
- CastLibBuiltins castLib/member/field/createMember registration, missing-provider fallback, cast/member provider callbacks, encoded member numbers, search-all lookup, and omitted helper builtins passed through the same CTest executable.
- XtraBuiltins registration, missing-manager behavior, registered-Xtra lookup, `new(xtraRef, ...)` instance creation, handler dispatch, property get/set callbacks, Java-style display strings, XML Parser Xtra parse/error/property/count lifecycle behavior, standalone ScriptCallback wiring, standalone MultiuserNetBridge fake/socket implementations, Multiuser Xtra bridge/connect/send/message/callback/tick/error/destroy behavior, socket Multiuser bridge loopback connect/send/poll/disconnect behavior, XtraManager lookup/list/alias/replacement/lifecycle/tick dispatch, and Player-owned XML plus socket Multiuser Xtra builtin wiring passed through the same CTest executable.
- ControlFlowBuiltins return/abort state, param lookup, frame/label `go`, call-target dispatch, list/proplist call snapshots, and omitted update builtins passed through the same CTest executable.
- ListBuiltins list/proplist counts, access, mutation, searches, sorting, constructors, key namespace behavior, and aliases passed through the same CTest executable.
- TimeoutBuiltins `timeout` creation, factory-mode `.new`, named `.new`, `.forget`, property get/set helpers, VM object-property get/set dispatch, and missing-provider behavior passed through the same CTest executable.
- NetBuiltins preload/get/post aliases, task result/error/status lookups, stream-status toggling, navigation callbacks, and ExternalParamBuiltins ordered parameter lookup passed through the same CTest executable.
- ImageBuiltins image creation, invalid-dimension handling, white fill defaults, built-in/system palette metadata, provider-resolved member palette metadata, string/ilk behavior, and `importFileInto` callback delegation passed through the same CTest executable.
- Runtime member image assignment, authored bitmap `member.image` decode and palette-change re-decode, live `member.image` reference preservation across replacement, dynamic bitmap default `member.image` creation, image-ref bitmap `media` assignment, bitmap `width`/`height` mutation with non-positive no-op behavior, direct raw-media `LSWI`/Director `DTIB`/BITD bitmap assignment, cached `LSWI` and Director `DTIB`/BITD `importFileInto` assignment, imported-image alpha preservation, imported anchor-point propagation, indexed imported-media palette metadata, runtime member property reflection, authored bitmap palette override decoding, and Player SpriteBaker live runtime bitmap rendering passed through the same CTest executable.
- Runtime dynamic member creation, named encoded slot creation, `new(#type, castLib)` callback creation, stable authored member counts, authored/runtime member name mutation, and dynamic member name/type lookup passed through the same CTest executable.
- Common cast-member `number`, `memberNum`, `castLibNum`, `castLib`, `script`, `scriptText`, `scriptType`, script-member empty `text`, `mediaReady`, Director-facing `type`, and bitmap `alphaThreshold`/`paletteRef`/`palette` property getters/setters passed through the same CTest executable.
- Runtime-created bitmap member render sprites, runtime registration-point placement, Player StageRenderer-to-CastLibManager resolver wiring, SpriteBaker live dynamic bitmap baking, and rendered frame pixels for dynamic bitmap sprites passed through the same CTest executable.
- Dynamic member `erase`, `#empty` type reflection, first erased-slot reuse, cast-member method callback routing, and Player sprite binding cleanup on slot retirement passed through the same CTest executable.
- Runtime field text storage, `member.text`/`member.html`/text-like `member.media` mutation, field lookup by name/number/encoded reference, builtin `field`, and field setter callback routing passed through the same CTest executable.
- Runtime dynamic text member baking, default renderer arguments, transparent-text native-alpha marking, and dynamic text sprite sizing passed through the same CTest executable.
- Runtime text member style property get/set, Director-style text color coercion, rect/width/height geometry mutation, renderer-backed text `image` reads with transparent-pixel native-alpha marking, text-like copied-image assignment, boxType-adjusted text height/rect reads, and SpriteBaker dynamic text style propagation passed through the same CTest executable.
- Runtime text-like `media` assignment from another cast member, including copied text content and style state through the cast-member property callback, passed through the same CTest executable.
- Runtime and file-backed bitmap `media` assignment from another cast member, including decoded BITD pixels, script-modified runtime copies, alpha-threshold propagation, registration-point propagation, and copied runtime palette override metadata, passed through the same CTest executable.
- Runtime palette payload storage, `member.color` list exposure, palette member lookup by member/name, palette-to-palette `media` copying, and palette-member `duplicate` target handling passed through the same CTest executable.
- Runtime script payload storage plus script-to-script `media` copying and incompatible target rejection passed through the same CTest executable.
- Runtime cast-member `regPoint` property mutation, non-point rejection, member-method sub-property reads, live runtime-bitmap anchor synchronization, authored-member pinned image/import assignment, copied-media pin inheritance, and dynamic-member unpinned image assignment passed through the same CTest executable.
- Runtime text-like member `charPosToLoc`/`locToCharPos` method dispatch, no-renderer fallbacks, builtin callback routing, and Player text-renderer wiring passed through the same CTest executable.
- Runtime text `lineCount`/`line` properties plus cast-member `getProp` and `count(#char/#word/#line/#item)` method helpers passed through the same CTest executable.
- FieldText datum identity, field builtin member identity preservation, and `value(field(...))` parsed-field callback behavior passed through the same CTest executable.
- Editable text field no-handler click focus, drag selection extension, focus clearing, printable insertion, selected-text replacement, backspace, left/right arrow caret movement, and tab/shift-tab field cycling passed through the same CTest executable.
- Editable text field caret geometry, single-line and multi-line selection rectangles, paste replacement, selected-text extraction, cut mutation, select-all, no-focus fallbacks, and sprite-registry revision bumps passed through the same CTest executable.
- ListBuiltins point/rect positional reads, shared mutable point/rect datum copies, and `setAt` component mutation passed through the same CTest executable.
- SoundBuiltins channel creation, availability, SoundChannelMethodDispatcher-backed sound-channel method/property dispatch, VM object-property defaults/mutation, and SoundManager playback delegation passed through the same CTest executable.
- ConstructorBuiltins point/rect/union/intersect/color/rgb/paletteIndex/sprite/new registration, Java-style constructor argument coercion, palette-index color identity/display, callback hooks, direct script-instance fallback, and `NEW_OBJ` script-ref handler dispatch passed through the same CTest executable.
- TypeBuiltins object/void/type predicates, `value` literal parsing/provider fallback, direct `script` lookup/scoping/unscoped list fallback, `script`/`callAncestor` callback hooks and list fanout, symbol conversion, and `ilk` alias/field-text checks passed through the same CTest executable.
- Lingo VM Scope and ExecutionContext stack, param, local, return, loop, jump, global callback, handler callback, builtin invocation, global debug-config propagation, and call-stack formatting behavior passed through the same CTest executable.
- Lingo VM ExecutionContext name resolver callback and resolver-backed global opcode behavior passed through the same CTest executable.
- Lingo VM long-handler safepoint callback, tick deadline, handler timeout, and cleanup behavior passed through the same CTest executable.
- Lingo VM console trace controls, targeted handler trace output, random-call trace seed output, trace-enabled handler/instruction/return output, reusable ConsoleTracePrinter formatting/loop suppression, and output redirection passed through the same CTest executable.
- Lingo VM startup random seed override, invalid seed fallback, and Java-compatible seed-4096 random sequence passed through the same CTest executable.
- Lingo VM-backed `value(...)` global lookup, partial identifier lookup, and zero-argument movie-handler fallback passed through the same CTest executable.
- Lingo VM-backed nested `value(...)` list/proplist global identifier resolution passed through the same CTest executable.
- Lingo VM trace listener handler enter/exit, optional instruction tracing, stack/global snapshots, reusable TracingHelper payload building, shared instruction annotations, local/param/global/script-instance-property variable-set callbacks, error callbacks, and trace argument formatting passed through the same CTest executable.
- Lingo VM deferred script-instance call ordering, automatic outer-handler flush, deferred task explicit flushing, flush-state guards, Player call-target provider wiring, and numeric `closeThread` task deferral passed through the same CTest executable.
- Lingo VM AlertHookHandler skip/depth diagnostics, guarded hook invocation, alertHook manual firing, `alert()` suppression, script-error suppression/rethrow behavior, and Player no-hook fallback passed through the same CTest executable.
- Lingo Datum deep-copy behavior, DatumFormatter scalar/brief/expanded/detailed/recursive output, AncestorChainWalker property/depth traversal, `call(...)` message-struct argument snapshots, non-message prop-list forwarding, and per-target call snapshot freshness passed through the same CTest executable.
- Player-owned LingoVM builtin delegation, file-backed dispatcher movie-script discovery/bytecode invocation, direct and sprite `call(...)` target return propagation, startup movie-script frame lifecycle and timeout-target dispatch, actorList frame-event dispatch, elapsed timeout target/global dispatch, `stopMovie` timeout/movie dispatch, and VM preference storage passed through the same CTest executable.
- OpcodeRegistry stack/control handler registration, custom handler registration, literal/symbol pushes, stack manipulation, return/factory return, and jump opcodes passed through the same CTest executable.
- OpcodeRegistry arithmetic, comparison, and logical handlers passed through the same CTest executable.
- OpcodeRegistry variable, arg-list, linear-list, and property-list handlers passed through the same CTest executable.
- OpcodeRegistry simple string concatenation, containment, and shared string-chunk helper routing passed through the same CTest executable.
- OpcodeRegistry basic property handlers, object property reads/writes, string/FieldText `lineCount`/`line` properties, Java-compatible PropertyIdMappings tables, legacy cast-member count property IDs, built-in constants, and simple `the` lookups passed through the same CTest executable.
- OpcodeRegistry movie-property provider reads/writes and provider-backed `the` lookups passed through the same CTest executable.
- OpcodeRegistry provider-backed object property gets/sets for movie, player, stage, sprite, integer-as-sprite refs, cast-member metadata/provider properties, timeout refs, sound channels, and ImageMethodDispatcher-backed image `useAlpha`/`paletteRef` setters passed through the same CTest executable.
- OpcodeRegistry local/external call handlers, builtin dispatch, no-return calls, constant fallback, and error-state handling passed through the same CTest executable.
- OpcodeRegistry object method calls and receiver-style external method calls for ImageMethodDispatcher-backed images, ListMethodDispatcher-backed lists, MemberRegistryMethodDispatcher-backed script-instance registry prefill/fallbacks, PropListMethodDispatcher-backed property-list typed symbol/string namespaces and nested list/proplist deep-copy `duplicate`, ScriptInstanceMethodDispatcher-backed script-instance receivers and SpriteRef scriptInstanceList dispatch before provider fallback, StringMethodDispatcher-backed string receiver methods, SoundChannelMethodDispatcher-backed sound channels, VarRef inverted char ranges, mutable ChunkRef inverted delete ranges, points, rectangles, script-instance ancestor assignment guards/bounded cyclic ancestor traversal/registry bootstrap/prefill/alias import/stale cleanup/persistent alias refresh, cast library member lookups/accessors, timeouts, and Xtra instances passed through the same CTest executable.
- OpcodeRegistry `NEW_OBJ` script construction delegation, provider-resolved fallback construction, declared property preinitialization, automatic `new` handler invocation, and non-script rejection passed through the same CTest executable.

## Remaining Major Work

- Higher-level media integration.
- Remaining sound, renderer-side PFR anti-alias fidelity, platform font-family loading, text, score, and script chunk decoders.
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
- `c2223b5b Port C++ opcode legacy property provider foundation`
- `ca5a2a3b Port C++ opcode cast member property foundation`
- `0f0a7ca0 Port C++ opcode cast library method foundation`
- `f6600078 Port C++ opcode item delimiter foundation`
- `3bbcf4a4 Port C++ string item count delimiter foundation`
- `5ff40363 Port C++ chunk mutation delimiter coverage`
- `c8ae0bef Port C++ direct string chunk methods`
- `c9b5edcb Port C++ mutable chunk ref deletion foundation`
- `a98f45b6 Port C++ script ref new method foundation`
- `6d4f746f Port C++ image object property foundation`
- `e5916113 Port C++ image object method foundation`
- `187bfd5d Port C++ image fill method foundation`
- `f90c61c2 Port C++ image crop method foundation`
- `f3516afe Port C++ image alpha method foundation`
- `dc32dd5d Port C++ image draw method foundation`
- `c9c11ee5 Port C++ image matte mask method foundation`
- `dd6a1a7c Port C++ image copyPixels foundation`
- `3d899bd0 Port C++ cast member method provider foundation`
- `d49257a5 Port C++ sprite method provider foundation`
- `e490d500 Port C++ image copyPixels blend foundation`
- `fb2b36d3 Port C++ image copyPixels mask foundation`
- `ecc37203 Port C++ image copyPixels transparent ink foundation`
- `39560416 Port C++ image copyPixels ink modes foundation`
- `e703ec0d Port C++ image copyPixels remap foundation`
- `90d0d1cf Port C++ image copyPixels quad foundation`
- `25a06812 Port C++ script instance methods foundation`
- `b3bc50b9 Port C++ member registry method foundation`
- `b3e7470f Port C++ script instance handler dispatch foundation`
- `64362c2f Port C++ field chunk mutation foundation`
- `58e7dee7 Port C++ image property setter foundation`
- `881040e1 Port C++ castLib member accessor foundation`
- `99a43fa8 Port C++ cast member property provider foundation`
- `19b640a3 Port C++ script construction provider fallback`
- `654802e7 Port C++ timeout object property dispatch`
- `c879164f Port C++ sound object property dispatch`
- `938442ce Port C++ behavior parameter value parser`
- `3410a54f Port C++ value builtin parser`
- `dc9a7820 Integrate C++ PFR TTF registry cache`
- `cee0bb98 Port C++ cast library manager foundation`
- `4326a2c7 Port C++ frame context foundation`
- `0b0c2afa Port C++ bitmap resolver foundation`
- `eb545ccc Port C++ input handler foundation`
- `6f78a995 Port C++ player foundation`
- `fd62f0e1 Port C++ player builtin context`
- `c4370a8e Port C++ Lingo VM runtime foundation`
- `1d0b61d1 Port C++ player VM event dispatch`
- `c1bb9eba Port C++ movie script source dispatch`
- `26cbcfe0 Port C++ player startup movie scripts`
- `1fd8f7e3 Port C++ timeout system events`
- `cbdaab71 Port C++ actorList VM dispatch`
- `b9ed9848 Port C++ periodic timeout VM dispatch`
- `4b5c70b2 Port C++ startup frame VM dispatch`
- `8470c98b Port C++ HitTester StageRenderer overloads`
- `18ab849e Port C++ VM deferred dispatch`
- `2e946940 Port C++ closeThread deferral`
- `4ca9f827 Port C++ alertHook handling`
- `cbf26ab1 Port C++ trace listener callbacks`
- `824d7a49 Port C++ property trace callbacks`
- `f77adf3f Port C++ VM safepoints`
- `90b02a58 Port C++ console trace hooks`
- `3d7a59cf Port C++ runtime member image import`
- `d1463491 Port C++ Director BITD media import`
- `1b81514f Port C++ dynamic member creation`
- `ee3200d8 Port C++ dynamic member rendering`
- `a113570a Port C++ dynamic member lifecycle`
- `d9297b79 Port C++ runtime field text`
- `ec988333 Port C++ dynamic text rendering`
- `1e761636 Port C++ runtime text styling`
- `f686fc32 Port C++ text media copy`
- `942d700f Port C++ text member methods`
- `360ac5a7 Port C++ cast member utility methods`
- `1434fb05 Port C++ field datum identity`
- `3759c22a Port C++ editable field input`
- `7cf00e5f Port C++ editable field helpers`
- `36bac722 Port C++ ediM bitmap sidecars`
- `f4d38f93 Port C++ file-backed bitmap media copy`
- `f840eaf4 Port C++ cast member regPoint mutation`
- `4aa8cd03 Port C++ pinned regPoint image assignment`
- `81e330b3 Port C++ bitmap media datum assignment`
- `6fe96d81 Port C++ palette member media copy`
- `b4cc0925 Port C++ palette member duplicate`
- `8a18de57 Port C++ member common properties`
- `37b19cb3 Port C++ member type surface`
- `3f81ef7b Port C++ bitmap member palette refs`
- `21ff6605 Port C++ bitmap member palette setters`
- `b951b032 Port C++ bitmap palette override decoding`
- `e7caae90 Port C++ script member media copy`
- `f4f1f8a8 Port C++ bitmap media image refs`
- `76557307 Port C++ authored member name mutation`
- `5baab1f3 Port C++ bitmap alphaThreshold property`
- `556fc23f Port C++ bitmap dimension setters`
- `5cd2b9b4 Port C++ live member image refs`
- `bb8903ba Port C++ dynamic bitmap default image`
- `7352d9b3 Port C++ authored bitmap image reads`
- `034fea56 Port C++ authored bitmap palette redecode`
- `f0ebc6ee Port C++ text member auto image props`
- `dd2674e5 Port C++ copied bitmap palette refs`
- `b9bad38e Port C++ text image native alpha`
- `212f84d3 Port C++ text image assignment copy`
- `070d0852 Port C++ XMED style spans`
- `2983be9f Port C++ XMED paragraph records`
- `3c96a286 Port C++ ink rectangular media matte skip`
- `9342375e Port C++ ink Java parity regressions`
- `b1982eb9 Port C++ live indexed darken bake`
- `04735e69 Fix Java verification parity`
- `54333609 Port C++ script registry prefill`
- `43831aa9 Port C++ member alias registry import`
- `07a1b311 Port C++ registry stale slot cleanup`
- `4183cf59 Port C++ persistent member aliases`
- `53949afa Port C++ callAncestor list fanout`
- `8fc7526c Port C++ direct script lookup`
- `288d5c4d Port C++ FieldText ilk parity`
- `8b63f843 Port C++ script list scope parity`
- `2f1bf76a Port C++ script constructor fallback`
- `1c8b39dd Port C++ constructor argument coercion`
- `97288df5 Port C++ mutable point rect setAt`
- `58b08250 Port C++ ancestor assignment guard`
- `72fc2f3a Port C++ bounded ancestor traversal`
- `3b15a1e5 Port C++ random seed trace parity`
- `7c27afaf Port C++ VM value identifier fallback`
- `1aafff66 Port C++ nested value identifier fallback`
- `2601667f Port C++ alert hook handler`
- `53aa113d Port C++ property ID mappings`
- `27c22fc3 Port C++ string method dispatcher`
- `2a420a0d Port C++ list method dispatcher`
- `508cbf3d Port C++ property list method dispatcher`
- `21ae244d Port C++ sound channel method dispatcher`
- `4933fc03 Port C++ image method dispatcher`
- `b6bfe6bc Port C++ member registry method dispatcher`
- `1eaf5cfd Port C++ script instance method dispatcher`
- `b8da51ba Port C++ sprite object script dispatch`
- `99438bf3 Port C++ call target return values`
- `a6ce1b79 Port C++ paletteIndex color identity`
- `f0788e46 Port C++ copyPixels paletteIndex remaps`
- `d0d212b3 Port C++ copyPixels darken tint`
- `2ab60083 Port C++ copyPixels native alpha keying`
- Current checkpoint commit message: `Port C++ copyPixels inverse alpha masks`
