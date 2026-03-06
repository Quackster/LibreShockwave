# Font Fallback Investigation: Verdana vs Volter in Error Dialog

## The Problem

The error dialog window ("Oops, error!") renders text 1 size too big in WASM because the layout hardcodes Verdana size 10, but we fall back to Volter (pixel font) which is designed for size 9.

## How Fonts Flow Through the System

### 1. error.window Layout (baked into fuse_client.cct)

The "error.window" cast member in fuse_client.cct is a **text member** whose body contains Lingo prop lists (pseudo-XML layout). Each line is parsed by `value()` in the Layout Parser Class.

The actual text content of the error.window member specifies:

```
error_title: [#id: "error_title", #type: "text", #font: "Verdana", #fontStyle: "bold", #fontSize: 10, #lineHeight: 13, ...]
error_text:  [#id: "error_text",  #type: "text", #font: "Verdana", #fontStyle: "plain", #fontSize: 10, #lineHeight: 13, ...]
error_close: [#id: "error_close", #type: "text", #font: "Verdana", #fontStyle: "bold", #fontSize: 10, #lineHeight: 13, ...]
```

**Key point**: `#font: "Verdana"` and `#fontSize: 10` are hardcoded directly in the layout text, NOT coming from struct.font variables.

### 2. Layout Parser Class (Script 53 in fuse_client.cct)

File: `habbo_src/14.1_b8/fuse_client/Cast External ParentScript 53 - Layout Parser Class.ls`

The `parse_window` method reads the text member, then for each element of type "text" (lines 103-129):

```lingo
if tElem[#type] = "text" then
  tFontStruct = getStructVariable("struct.font.plain")
  ...
  if voidp(tElem[#font]) then          -- ONLY if font is MISSING
    tElem[#font] = tFontStruct.getaProp(#font)
  end if
  if voidp(tElem[#fontSize]) then       -- ONLY if fontSize is MISSING
    tElem[#fontSize] = tFontStruct.getaProp(#fontSize)
  end if
  if voidp(tElem[#fontStyle]) then      -- ONLY if fontStyle is MISSING
    tElem[#fontStyle] = tFontStruct.getaProp(#fontStyle)
  end if
```

**Critical**: struct.font defaults are ONLY applied when the property is `voidp` (missing/undefined). Since error.window explicitly sets `#font`, `#fontSize`, and `#fontStyle`, the struct.font values are NEVER used for the error dialog.

### 3. struct.font Variables

**Default values** (from "System Props" text member in fuse_client.cct):
```
struct.font.plain = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#plain]]
struct.font.bold  = [#font:"Courier", #fontSize:9, #lineHeight:10, #color:rgb("#000000"), #ilk:#struct, #fontStyle:[#bold]]
```

**After external_variables.txt loads** (overrides from server):
```
struct.font.plain = [#font:"v",  #fontSize:9, #lineHeight:10, ...]
struct.font.bold  = [#font:"vb", #fontSize:9, #lineHeight:10, ...]
```

So struct.font uses font "v"/"vb" at **size 9**, while error.window hardcodes "Verdana" at **size 10**.

### 4. Font "v" and "vb" (Volter)

- Cast member "v" in hh_interface.cct = Volter_400_0 (regular weight)
- Cast member "vb" in hh_interface.cct = Volter_700_0 (bold weight)
- MemberType: OLE (not FONT) — the PFR1 data is in XMED chunks
- Both have 212 outline glyphs
- Volter (by Goldfish) is a **pixel font** designed for small sizes
- Works best at size 9; at size 10 it's noticeably larger/blockier

### 5. How the Original Shockwave Player Handled This

In the original browser plugin:
- "Verdana" resolved to the **system font** Verdana (available on all Windows/Mac systems)
- Verdana at size 10 rendered with system font rasterizer (ClearType/TrueType hinting)
- Volter ("v"/"vb") was only used by elements that explicitly referenced it via struct.font
- The error dialog ALWAYS rendered in system Verdana, never in Volter

### 6. What Happens in LibreShockwave

**Desktop (AWT)**: `AwtTextRenderer.resolveDirectorFont()` maps "Verdana" to the system Verdana font. Works correctly — renders at size 10 using Java AWT with the real Verdana font.

**WASM (SimpleTextRenderer)**: No system fonts available. Before the fix, "Verdana" wasn't found as a PFR font, so it fell through to the built-in 5x7 pixel font (terrible).

**Current fix**: `SimpleTextRenderer.resolveBitmapFont()` falls back to the default PFR font (shortest registered name = "v") with style suffixes ("vb" for bold). Uses `fontSize - 1` to compensate for the system-to-pixel-font size difference (Verdana 10 -> Volter 9).

## Current State of the Fix

### Files Changed

1. **`player-core/.../cast/FontRegistry.java`**
   - Added `defaultFontName` volatile field (tracked during `registerPfr1Font()`)
   - Added `getDefaultFontName()` — returns shortest registered PFR font name
   - Uses volatile field instead of iterating `parsedFonts.keySet()` (TeaVM doesn't support `ConcurrentHashMap.keySet()` or `.keys()`)

2. **`player-core/.../render/SimpleTextRenderer.java`**
   - `resolveBitmapFont()` fallback: when font not found as PFR, tries default PFR font with style suffix
   - Uses `fontSize - 1` in fallback (Verdana 10 -> Volter 9)

### The fontSize - 1 Hack

The `fontSize - 1` is a pragmatic workaround, not a proper fix. It works for the Verdana 10 -> Volter 9 case but is fragile. Potential better approaches:

1. **Store struct.font size in FontRegistry**: When external_variables.txt loads, register the struct.font.plain fontSize (9) alongside the font name. Use that instead of `fontSize - 1`.

2. **Apply struct.font in the layout parser**: Our Java layout parser implementation could override `#font`/`#fontSize` when the font isn't available as PFR, replacing them with struct.font values. This would match what the original client effectively did (Verdana was always available, so this path was never needed).

3. **Register a "Verdana" -> "v" alias in FontRegistry**: When PFR fonts load, also register common system font names as aliases. "Verdana" -> "v", "Verdana Bold" -> "vb". Then the fallback would resolve naturally.

## Open Questions

- Does the original Habbo client ever render error dialog text in Volter? Or is it always system Verdana?
- Are there other window layouts that hardcode system font names besides error.window?
- Should the WASM renderer try to match Verdana's metrics (wider characters, different spacing) or is Volter "close enough"?
- The `#lineHeight: 13` in the layout was designed for Verdana 10. Volter at size 9 has a different natural line height. Should we also adjust lineHeight?

## File Locations for Reference

| What | Path |
|------|------|
| Layout Parser Lingo source | `habbo_src/14.1_b8/fuse_client/Cast External ParentScript 53 - Layout Parser Class.ls` |
| Error Manager Lingo source | `habbo_src/14.1_b8/fuse_client/Cast External ParentScript 28 - Error Manager Class.ls` |
| Window Instance Lingo source | `habbo_src/14.1_b8/fuse_client/Cast External ParentScript 55 - Window Instance Class.ls` |
| Window API Lingo source | `habbo_src/14.1_b8/fuse_client/Cast External MovieScript 18 - Window API.ls` |
| SimpleTextRenderer (WASM) | `player-core/src/main/java/com/libreshockwave/player/render/SimpleTextRenderer.java` |
| AwtTextRenderer (desktop) | `player-core/src/main/java/com/libreshockwave/player/render/AwtTextRenderer.java` |
| FontRegistry | `player-core/src/main/java/com/libreshockwave/player/cast/FontRegistry.java` |
| TtfBitmapRasterizer | `sdk/src/main/java/com/libreshockwave/font/TtfBitmapRasterizer.java` |
| Pfr1TtfConverter | `sdk/src/main/java/com/libreshockwave/font/Pfr1TtfConverter.java` |
| RendererCompareTest | `player-core/src/test/java/com/libreshockwave/player/RendererCompareTest.java` |
| ErrorWindowDumpTest | `player-core/src/test/java/com/libreshockwave/player/ErrorWindowDumpTest.java` |

## Call Chain: Error Dialog Rendering

```
alertHook (Lingo) -> Error Manager Class.showErrorDialog()
  -> createWindow(#error, "error.window", 0, 0, #modal)
    -> Window Manager.create()
      -> Window Instance Class.buildVisual("error.window")
        -> Layout Parser.parse("error.window")
          -> parse_window("error.window")
            -> reads text from "error.window" cast member
            -> value() parses each line into prop lists
            -> applies struct.font.plain defaults for MISSING properties only
            -> returns layout with [#font:"Verdana", #fontSize:10, ...]
        -> creates dynamic bitmap members for each element
        -> createElement() for each element (text/image types)
          -> Writer Class renders text into bitmap member
            -> new(#text, castLib) -> sets font/fontSize/text -> member.image
              -> CastMember.renderTextToImage()
                -> TextRenderer.renderText(font="Verdana", fontSize=10, fontStyle="bold", ...)
                  -> AWT: resolves system Verdana, renders at size 10
                  -> WASM: "Verdana" not found as PFR -> falls back to "vb" at size 9
```
