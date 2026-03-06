# LibreShockwave - Current Work Items

Reference screenshots: `C:\SourceControl\HOTEL_VIEW_BR\` (both PNGs are from the original Shockwave plugin in Basilisk)

---

## Issue 1: Logo Shadow Rendering as Dithered Black Box

**What's wrong:** The "HABBO HOTEL" logo has a black dithered/stippled pattern underneath it instead of a proper shadow. It follows the logo text diagonally (offset shadow shape), not a rectangular box.

**Expected:** A solid dark shadow offset diagonally behind the logo text, as seen in the reference screenshots.

**Likely cause:** The shadow is the cast member `"brassivesiputousb"`. It's a bitmap sprite that probably uses an ink mode not yet fully implemented in the rendering pipeline. Currently unimplemented ink modes that could cause this:
- **Ink 1 (Transparent)**: Should make white pixels transparent — if not implemented, white pixels render opaque, creating a checkerboard/dithered look from a 1-bit bitmap pattern
- **Ink 3 (Ghost)**: Inverse transparency effect
- **Ink 2 (Reverse)**, **Ink 4-6 (Not Copy/Transparent/Reverse)**: Other unimplemented inks

The `InkProcessor.shouldProcessInk()` only handles inks 7, 8, 33, 35, 36, 40, 41. Inks 1-6 fall through to COPY mode (no transparency), which would explain why a 1-bit shadow bitmap with white background renders as black-and-white dithered pattern instead of a clean shadow.

**Key files:**
- `player-core/.../render/InkProcessor.java` — ink processing (needs Transparent ink support)
- `player-core/.../render/SpriteBaker.java` — bitmap baking pipeline
- `player-core/.../render/SoftwareFrameRenderer.java` — compositing
- `sdk/.../id/InkMode.java` — ink mode enum (all modes defined, not all implemented)
- `docs/ink-processing.txt` — documentation of current ink implementation
- `docs/Scripting_Reference.txt` (line ~57225) — Director ink mode reference

**Investigation steps:**
1. Run `./gradlew :player-core:runHotelViewDiagnosticTest` and check what ink mode the shadow sprite uses
2. Dump the shadow bitmap to see if it's 1-bit with a pattern
3. Implement the missing ink mode (likely ink 1 = Transparent: white pixels become transparent)

---

## Issue 2: Missing Spotlight/Sun Effect (Upper-Right Sky)

**What's wrong:** There should be a bright radial glow/spotlight effect in the upper-right sky area. It's visible in the original after the Sulake loading logo disappears. Currently missing entirely.

**Expected:** A gradient overlay sprite — white/bright fading to transparent — creating a sun/spotlight effect in the upper-right quadrant of the stage.

**Likely cause:** The spotlight is a **bitmap cast member** in one of the .CCT files (NOT a Flash/SWF member). If it's not rendering, possible reasons:
- The member's ink mode isn't handled (same class of bug as Issue 1)
- The member failed to load or decode
- The sprite isn't being created by the Lingo scripts (a VM execution issue)
- The bitmap decoding produces wrong pixels (e.g., gradient data lost)

**Key files:**
- The .CCT cast files in `C:\xampp\htdocs\dcr\14.1_b8\` — source of the spotlight member
- `player-core/.../render/SpriteBaker.java` — where bitmaps are baked
- Lingo source: `C:\SourceControl\habbo_src\14.1_b8\fuse_client\` — scripts that create the spotlight sprite

**Investigation steps:**
1. Run the hotel view diagnostic test and dump ALL sprites — find which member is the spotlight
2. Search the Lingo source for "spotlight", "sun", "light", "glow" to find the script that creates it
3. Check if the member exists in a loaded cast and whether its sprite is being created
4. If the sprite exists but isn't visible, check its ink mode and bitmap content

---

## Issue 3: Login Dialog Box Border Offsets and Layering

**What's wrong:** The "First time here?" and "What's your Habbo called?" dialog boxes have:
- **Wrong offsets** — border pieces (bg_a, bg_b, bg_c, corners) are misaligned by some pixels
- **Wrong layering order** — some border pieces render in front of/behind the wrong elements
- Text inputs and OK button are fine — only the outer dialog frame/border and speech bubble pointer are affected

**Expected:** Pixel-perfect rounded dialog borders with proper speech bubble pointer, matching the reference screenshots.

**Likely cause:** The window system creates dynamic bitmap sprites for each border piece. Positioning is calculated in Lingo:
```lingo
tsprite.locH = tElemRect[1] + pClientRect[1]
tsprite.locV = tElemRect[2] + pClientRect[2]
```
The cumulative `pClientRect` offset may be computed incorrectly in the VM, or the registration point subtraction in `StageRenderer` may be off.

**Key files:**
- Lingo: `Cast External ParentScript 55 - Window Instance Class.ls` — `buildVisual()` method (lines 404-579) positions border pieces
- Lingo: `Cast External ParentScript 53 - Layout Parser Class.ls` — parses window XML layouts
- Lingo: `Cast External ParentScript 56 - Element Wrapper Class.ls` — per-element sprite handling
- `player-core/.../render/StageRenderer.java` (lines 168-171) — registration point offset: `x -= member.regPointX(); y -= member.regPointY()`
- `player-core/.../render/InkProcessor.java` — foreColor/backColor remapping for border colorization
- `player-core/.../render/SpriteBaker.java` — sprite baking

**Investigation steps:**
1. Run diagnostic test, dump sprite positions for all login window border pieces
2. Compare sprite x,y positions against expected values from the Lingo layout calculations
3. Check if registration points (regPoint) are being read correctly from cast members
4. Check sprite locZ/layer ordering — are border pieces sorted correctly?
5. Check if `pClientRect` border accumulation works correctly in the VM

---

## General Debugging Approach

1. **Generate current render:** `./gradlew :player-core:runHotelViewDiagnosticTest` (500 ticks, 5ms delay, dumps sprites)
2. **Compare against reference:** Side-by-side with `C:\SourceControl\HOTEL_VIEW_BR\` screenshots
3. **Dump specific sprites:** Modify the diagnostic test to export individual sprite bitmaps for the shadow, spotlight, and border pieces
4. **Check ink modes:** Log each sprite's ink mode during rendering to identify unimplemented inks
5. **Lingo reference:** `C:\SourceControl\habbo_src\14.1_b8\fuse_client\` has the original scripts

## Priority Order
1. Logo shadow (likely a single missing ink mode implementation — high impact, likely quick fix)
2. Dialog borders (offset/layering bugs — medium complexity)
3. Spotlight (may require deeper investigation into which member it is — unknown scope)
