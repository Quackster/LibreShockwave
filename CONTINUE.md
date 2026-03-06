# Continue: Bold font resolution in text renderers

## Status: FIXED

## What was fixed
Both AwtTextRenderer and SimpleTextRenderer now correctly resolve bold/italic font variants using Director's naming convention (suffix "b" for bold, "i" for italic, "bi" for bold-italic).

### Changes
1. **SimpleTextRenderer.resolveBitmapFont()**: Tries suffixed names FIRST when fontStyle requests bold/italic (e.g. "v" + bold → tries "vb" before "v")
2. **SimpleTextRenderer synthetic bold**: When no dedicated bold font variant exists, renders each glyph twice at +1px offset (double-strike)
3. **AwtTextRenderer.resolvePfrAwtFont()**: Same suffix lookup — tries "vb" TTF before falling back to synthetic Font.BOLD on "v" TTF
4. **RendererCompareTest**: Now loads hh_interface.cct to register PFR fonts; test cases match Director conventions

### Test results
- RendererCompareTest: 11/11 PASS at 100% pixel-perfect match
- Unit tests: all pass (sdk, vm, player-core)
- WASM build: successful
