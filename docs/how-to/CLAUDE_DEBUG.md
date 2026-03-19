# How to Debug Visual Rendering Issues

This guide explains the debugging methodology for fixing visual/rendering bugs in LibreShockwave. It covers how to use pixel comparison tests, add targeted debug logging, trace the rendering pipeline, and isolate root causes.

## Overview

LibreShockwave is a Director/Shockwave player. Visual bugs typically fall into these categories:

1. **Missing elements** - text, images, or shapes that should appear but don't
2. **Wrong colors** - palette resolution, ink effects, or color remapping issues
3. **Wrong positioning** - sprites/elements placed at incorrect coordinates
4. **Wrong sizing** - text members or bitmaps with incorrect dimensions

## Step 1: Identify the Problem with Pixel Comparison

We maintain reference screenshots from the original Shockwave plugin. These live in `/docs/` (e.g., `docs/habbo-reference.png`).

### Running the pixel comparison test

```bash
./gradlew :player-core:runNavigatorSSOTest
```

**Note:** The Habbo proxy (localhost:30001) is only required when running from `player-wasm`. When running from `player-core` (as above), no proxy is needed — the test downloads everything over HTTP directly.

This test:
1. Downloads a DCR from the test server
2. Ticks frames until the UI appears
3. Renders the final frame to PNG
4. Creates pixel diffs against the reference image

### Output files

All output goes to `player-core/build/navigator-sso/`:

| File | Purpose |
|------|---------|
| `02_our_output.png` | Our rendered frame |
| `05a_nav_region_ours.png` | Our rendering, cropped to region of interest |
| `05b_nav_region_ref.png` | Reference image, cropped to same region |
| `05_nav_region_diff.png` | Pixel difference (matching=black, mismatches=amplified color) |
| `06_nav_side_by_side.png` | Three-panel comparison: ours, reference, diff |

### Reading the pixel stats

The test prints a summary line:

```
Total: 388800 | Identical: 249207 (64.1%) | Close: 8103 (2.1%) | Different: 131490 (33.8%)
```

**Always record the baseline numbers before making changes.** After each change, re-run and compare. If Identical% goes down or Different% goes up, revert immediately.

### How the test works (key pattern)

The core pattern for a pixel comparison test is:

```java
// 1. Load the DCR and create a player
DirectorFile dirFile = DirectorFile.load(dcrBytes);
Player player = new Player(dirFile);

// 2. Set external params (URLs, SSO tickets, server config)
Map<String, String> params = new LinkedHashMap<>();
params.put("sw1", "site.url=http://example.com");
params.put("sw6", "use.sso.ticket=1;sso.ticket=123");
player.setExternalParams(params);

// 3. Start playback, preload casts, tick frames
player.play();
player.preloadAllCasts();
Thread.sleep(8000); // wait for HTTP cast downloads

for (int i = 0; i < 3000; i++) {
    player.tick();
    Thread.sleep(10);
}

// 4. Capture the frame
FrameSnapshot snap = player.getFrameSnapshot();
Bitmap frame = snap.renderFrame();

// 5. Save and compare
ImageIO.write(frame.toBufferedImage(), "PNG", new File("our_output.png"));
BufferedImage ref = ImageIO.read(new File("docs/reference.png"));

// 6. Create diff image (matching pixels = black, mismatches = amplified)
int w = Math.min(ours.getWidth(), ref.getWidth());
int h = Math.min(ours.getHeight(), ref.getHeight());
BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
        int pa = ours.getRGB(x, y), pb = ref.getRGB(x, y);
        if (pa == pb) {
            diff.setRGB(x, y, 0xFF000000); // black = match
        } else {
            int dr = Math.min(255, Math.abs(((pa>>16)&0xFF) - ((pb>>16)&0xFF)) * 4);
            int dg = Math.min(255, Math.abs(((pa>>8)&0xFF) - ((pb>>8)&0xFF)) * 4);
            int db = Math.min(255, Math.abs((pa&0xFF) - (pb&0xFF)) * 4);
            diff.setRGB(x, y, 0xFF000000 | (dr<<16) | (dg<<8) | db);
        }
    }
}

// 7. Create side-by-side (ours | reference | diff)
BufferedImage sbs = new BufferedImage(
    ours.getWidth() + ref.getWidth() + diff.getWidth() + 4, h,
    BufferedImage.TYPE_INT_ARGB);
Graphics2D g = sbs.createGraphics();
g.drawImage(ours, 0, 0, null);
g.drawImage(ref, ours.getWidth() + 2, 0, null);
g.drawImage(diff, ours.getWidth() + ref.getWidth() + 4, 0, null);
g.dispose();

// 8. Count identical/close/different pixels
int identical = 0, close = 0, different = 0;
for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
        int pa = ours.getRGB(x, y), pb = ref.getRGB(x, y);
        if (pa == pb) { identical++; }
        else {
            int dr = Math.abs(((pa>>16)&0xFF) - ((pb>>16)&0xFF));
            int dg = Math.abs(((pa>>8)&0xFF) - ((pb>>8)&0xFF));
            int db = Math.abs((pa&0xFF) - (pb&0xFF));
            if (dr <= 5 && dg <= 5 && db <= 5) close++; else different++;
        }
    }
}
System.out.printf("Identical: %d (%.1f%%) | Close: %d (%.1f%%) | Different: %d (%.1f%%)%n",
    identical, 100.0*identical/(w*h), close, 100.0*close/(w*h), different, 100.0*different/(w*h));
```

## Step 2: Visually Compare Our Output vs Reference

**Read both images** using the Read tool:
- `player-core/build/navigator-sso/05a_nav_region_ours.png` (ours)
- `player-core/build/navigator-sso/05b_nav_region_ref.png` (reference)
- `player-core/build/navigator-sso/06_nav_side_by_side.png` (side-by-side)

Identify specifically what's different:
- Is an element completely missing? (rendering pipeline issue)
- Is an element there but wrong color? (palette/ink issue)
- Is an element positioned wrong? (coordinate/rect issue)
- Is text there but cut off? (clipping/height issue)

## Step 3: Add Targeted Debug Logging

The rendering pipeline has multiple layers. Add `System.err.println()` debug logging to trace the issue. **Use `System.err`** because Gradle forwards stderr from forked JVMs but may not forward stdout.

### Key locations for debug logging

#### Text rendering (CastMember.java)

The `renderTextToImage(int width, int height, int bgColor)` method is where all text member images are produced. Add logging here to see what text is being rendered and with what parameters:

```java
// In CastMember.renderTextToImage(int width, int height, int bgColor):
System.err.println("[DEBUG] name=" + name
    + " text='" + (text.length() > 80 ? text.substring(0, 80) + "..." : text)
    + "' w=" + width + " h=" + height
    + " font=" + textFont + " size=" + textFontSize
    + " wordWrap=" + textWordWrap
    + " color=0x" + Integer.toHexString(textColor)
    + " boxType=" + textBoxType);
```

This tells you:
- **Which member** is being rendered (name)
- **What text** it contains (is it empty? wrong content?)
- **Dimensions** (is width/height 0? unreasonably large?)
- **Font/style** (correct font? correct size?)
- **Word wrap** (should it wrap? is it wrapping?)
- **Color** (is the text color visible against the background?)
- **Box type** (0=adjust to fit, 1=fixed)

#### copyPixels compositing (ImageMethodDispatcher.java)

The `copyPixels(Bitmap dest, List<Datum> args)` method handles all Lingo `image.copyPixels()` calls. Add conditional logging to trace specific bitmaps:

```java
// In ImageMethodDispatcher.copyPixels(), after computing srcW/srcH/destW/destH:
// Filter by bitmap dimensions to avoid flooding output
if (src.getWidth() == 230 && src.getHeight() == 40) {
    System.err.println("[DEBUG-CP] srcRect=" + srcRect + " destRect=" + destRect
        + " srcBmp=" + src.getWidth() + "x" + src.getHeight()
        + " destBmp=" + dest.getWidth() + "x" + dest.getHeight()
        + " ink=" + ink);
}
```

This tells you:
- **Source/dest rects** (is something being copied with height=0? is the dest rect off-screen?)
- **Bitmap dimensions** (does the source bitmap actually contain content?)
- **Ink mode** (COPY, BACKGROUND_TRANSPARENT, MATTE, etc.)

#### Sprite baking (SpriteBaker.java)

The `bakeText()` method resolves text sprites. The `bakeTextFromFile()` and `bakeTextFromXmed()` methods handle score-placed text. Add logging to see which path is taken and whether the member is found.

### Filtering debug output

When grepping test output, filter by your debug prefix:

```bash
./gradlew :player-core:runNavigatorSSOTest 2>&1 | grep -E "DEBUG"
```

Search for specific content:

```bash
./gradlew :player-core:runNavigatorSSOTest 2>&1 | grep -iE "DEBUG.*habborella"
```

## Step 4: Trace the Root Cause

### The rendering pipeline (top to bottom)

```
Lingo script (compiled in DCR)
  |
  v
VM property access: member("foo").text = "hello"   [CastMember.setTextProp]
VM property access: member("foo").image             [CastMember.getTextProp → renderTextToImage]
VM method call: destImg.copyPixels(srcImg, ...)     [ImageMethodDispatcher.copyPixels]
  |
  v
Score rendering: SpriteBaker.bake(sprite)           [SpriteBaker.bakeText / bakeBitmap]
  |
  v
Text rendering: SimpleTextRenderer.renderText(...)  [SimpleTextRenderer.renderWithBitmapFont]
  |
  v
Frame compositing: FrameSnapshot.renderFrame()      [final pixel output]
```

### Common root causes

**Missing text — wrong height/positioning:**
Text members in Director have a `boxType` property: 0=adjust to fit (auto-size), 1=fixed. With boxType=adjust, the member's rect should auto-shrink/expand to fit the text content. If the stored rect has a stale height (e.g., 480px default), downstream code that positions elements relative to `member.height` or `member.rect` will place them too far down, pushing them off-screen. Fix: ensure `renderTextToImage()` uses auto-sized height for boxType=adjust.

**Missing text — wrong color:**
Text rendered as black (0xFF000000) on white (0xFFFFFFFF) is then composited via `copyPixels` with `#color`/`#bgColor` remapping. If the remap makes both foreground and background the same color, the text becomes invisible. Check the `colorRemap` and `bgColorRemap` values in copyPixels debug output. Note: `-1` means no remap (printed as `0xffffffff` by `Integer.toHexString(-1)`).

**Missing text — word wrap off:**
If text should wrap but `wordWrap=false`, the text renders as a single line extending beyond the bitmap width. The beginning of the line is visible but most content is clipped. Check the `wordWrap` flag in renderTextToImage debug output.

**Wrong colors — palette resolution:**
8-bit paletted images use `paletteIndex(N)` which must resolve through the image's own palette, not the system palette. If colors look wrong on paletted elements, check `Bitmap.resolvePaletteColor()` and whether the correct palette is being used.

**Missing elements — sprite type mismatch:**
`SpriteBaker.bake()` dispatches by `sprite.getType()`. If a sprite's type isn't recognized (falls to `default -> null`), it produces no bitmap. Check what type the sprite has and whether it's handled.

## Step 5: Verify the Fix

1. **Run the test again** and check Identical% went up:
   ```bash
   ./gradlew :player-core:runNavigatorSSOTest
   ```

2. **Read the side-by-side image** to visually confirm the fix:
   - `player-core/build/navigator-sso/06_nav_side_by_side.png`

3. **Remove all debug logging** before committing.

4. **Check for regressions** — make sure other elements didn't break. The pixel stats should show improvement (or at worst, stay the same) in all categories.

## Example: Debugging the Habborella Body Text

This is a real debugging session that found and fixed missing body text in the Habbo Hotel navigator.

### Symptom
The "Habborella – what's that?" title displayed correctly, but the body text "In the run up to Valentine's Day..." below it was completely missing.

### Investigation

1. **Pixel comparison** showed the body text was present in the reference but absent in our output.

2. **Debug logging in `CastMember.renderTextToImage()`** revealed two text members:
   - Title: `w=163 h=480 text='Habborella - what's that?'`
   - Body: `w=230 h=0 text='In the run up to Valentine's Day...'`

   The title had height=480 (default `textRectBottom`), the body had height=0.

3. **Debug logging in `ImageMethodDispatcher.copyPixels()`** showed the Lingo writer class building a 230x522 composite:
   - Title copied to `destRect=rect(0, 0, 163, 480)` (fills first 480 rows)
   - Body copied to `destRect=rect(0, 482, 230, 522)` (starts at row 482)
   - But the composite was then used as a source with `srcRect=rect(0, 0, 230, 56)` — only the top 56 pixels were read!

4. **Root cause**: The title member had `boxType=0` (adjust to fit) but its stored rect height was 480 (the default). The writer class positioned the body after the title's height (480 + 2 = 482). But the actual title text only occupied ~10 pixels. The caller only read the top 56 pixels of the composite, so the body at y=482 was never visible.

### Fix

In `CastMember.renderTextToImage()` (no-arg version), pass `height=0` for `boxType=0` instead of the stored rect height. This lets `SimpleTextRenderer` auto-size the bitmap to fit the actual text content. The title then renders as ~20px tall, placing the body at y≈22 — within the visible 56px region.

```java
public Bitmap renderTextToImage() {
    int width = textRectRight - textRectLeft;
    int height = textBoxType == 0 ? 0 : (textRectBottom - textRectTop);
    return renderTextToImage(width, height, textBgColor);
}
```

### Result
Identical pixels: 63.8% → 64.1%, body text now renders correctly.

## Reference Documentation

Director scripting references are available in `/docs/`:
- `drmx2004_scripting_ref.pdf` — Director MX 2004 Scripting Reference
- `drmx2004_getting_started.pdf` — Director MX 2004 Getting Started Guide

These are the authoritative references for how Director properties, methods, and behaviors should work. Consult them when unsure about the expected behavior of a Director API (e.g., what `boxType`, `wordWrap`, `fixedLineSpace`, `member.rect` etc. should do).
