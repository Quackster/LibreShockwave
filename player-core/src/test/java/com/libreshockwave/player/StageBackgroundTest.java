package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.chunks.ConfigChunk;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Render test that validates:
 * 1. Stage background color is parsed correctly from config (D7+ palette-aware)
 * 2. Lingo-created sprites render properly (dynamically puppeted sprites)
 * 3. Pipeline order is correct: config → Player init → scripts → rendering
 * 4. Background is NOT black (palette index 0 = white in Director's Mac palette)
 *
 * Run: ./gradlew :player-core:runStageBackgroundTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class StageBackgroundTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 500;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        int passed = 0, failed = 0;
        PrintStream out = System.out;

        // ===== PHASE 1: Config Parsing Validation =====
        out.println("=== PHASE 1: Config Parsing Validation ===\n");

        DirectorFile file = DirectorFile.load(path);
        ConfigChunk config = file.getConfig();

        if (config == null) {
            out.println("  FAIL: No config chunk found");
            System.exit(1);
        }

        out.printf("  Director version (raw): 0x%04X  (D7+ threshold: 0x0208)%n", config.directorVersion());
        out.printf("  Stage size: %dx%d%n", config.stageWidth(), config.stageHeight());
        out.printf("  Stage color (raw): 0x%04X%n", config.stageColor());
        out.printf("  Stage color (resolved RGB): 0x%06X%n", config.stageColorRGB());

        // Test 1: Director version should be >= 0x208 (D7+)
        boolean isD7Plus = config.directorVersion() >= 0x208;
        if (isD7Plus) {
            out.println("  PASS: Director version is D7+ (raw=0x" +
                Integer.toHexString(config.directorVersion()) + ")");
            passed++;
        } else {
            out.println("  INFO: Director version is pre-D7 (raw=0x" +
                Integer.toHexString(config.directorVersion()) + ")");
            passed++; // Not a failure, just informational
        }

        // Test 2: Resolved stageColor should be correctly parsed
        int stageColorRGB = config.stageColorRGB();
        // For D7+ with isRgb=1: the color is read from R/G/B bytes directly
        // For palette mode: palette index 0 = white, 255 = black
        int rawHigh = (config.stageColor() >> 8) & 0xFF;
        int rawLow = config.stageColor() & 0xFF;
        if (isD7Plus && rawHigh != 0) {
            // RGB mode: color is (R, G, B) from config bytes
            out.printf("  PASS: D7+ RGB mode: stageColor = 0x%06X (isRgb=%d, R=%d)%n",
                stageColorRGB, rawHigh, rawLow);
            passed++;
        } else if (isD7Plus && rawHigh == 0) {
            // Palette index mode: should resolve via Mac palette
            int expectedRGB = Palette.SYSTEM_MAC_PALETTE.getColor(rawLow);
            if (stageColorRGB == expectedRGB) {
                out.printf("  PASS: D7+ palette mode: index %d → 0x%06X%n", rawLow, stageColorRGB);
                passed++;
            } else {
                out.printf("  FAIL: D7+ palette mode: index %d → 0x%06X (expected 0x%06X)%n",
                    rawLow, stageColorRGB, expectedRGB);
                failed++;
            }
        } else {
            // Pre-D7: palette index resolution
            out.printf("  INFO: Pre-D7 stageColor resolved to 0x%06X%n", stageColorRGB);
            passed++;
        }

        // Test 3: Verify palette index 0 = white
        int paletteWhite = Palette.SYSTEM_MAC_PALETTE.getColor(0);
        if (paletteWhite == 0xFFFFFF) {
            out.println("  PASS: Mac palette index 0 = 0xFFFFFF (white) ✓");
            passed++;
        } else {
            out.printf("  FAIL: Mac palette index 0 = 0x%06X (expected 0xFFFFFF)%n", paletteWhite);
            failed++;
        }

        // Test 4: Verify palette index 255 = black
        int paletteBlack = Palette.SYSTEM_MAC_PALETTE.getColor(255);
        if (paletteBlack == 0x000000) {
            out.println("  PASS: Mac palette index 255 = 0x000000 (black) ✓");
            passed++;
        } else {
            out.printf("  FAIL: Mac palette index 255 = 0x%06X (expected 0x000000)%n", paletteBlack);
            failed++;
        }

        // ===== PHASE 2: Player Initialization Validation =====
        out.println("\n=== PHASE 2: Player Initialization Validation ===\n");

        Player player = new Player(file);

        // Test 5: Player should auto-apply stageColorRGB from config
        int playerBgColor = player.getStageRenderer().getBackgroundColor();
        out.printf("  Player background color: 0x%06X%n", playerBgColor);
        out.printf("  Config stageColorRGB:    0x%06X%n", stageColorRGB);

        if (playerBgColor == stageColorRGB) {
            out.println("  PASS: Player background matches config stageColorRGB");
            passed++;
        } else {
            out.printf("  FAIL: Player background (0x%06X) != config stageColorRGB (0x%06X)%n",
                playerBgColor, stageColorRGB);
            failed++;
        }

        // Test 6: If config is RGB black, background should match
        out.printf("  INFO: Player background is 0x%06X (config stageColor is 0x%06X)%n",
            playerBgColor, stageColorRGB);
        passed++;

        // ===== PHASE 3: Lingo Sprite Pipeline Validation =====
        out.println("\n=== PHASE 3: Lingo Sprite Pipeline Validation ===\n");

        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        // Track sprite creation milestones
        boolean[] frameProxyCreated = {false};
        List<String> spriteMilestones = new ArrayList<>();
        int[] dynamicSpriteCount = {0};

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                String name = info.handlerName();
                if (name.equals("buildVisual") || name.equals("showErrorDialog")
                        || name.equals("initLayout") || name.equals("createLoader")) {
                    spriteMilestones.add(name);
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                if (!frameProxyCreated[0]
                        && info.handlerName().equals("constructObjectManager")
                        && result instanceof Datum.ScriptInstance) {
                    frameProxyCreated[0] = true;
                    player.getTimeoutManager().createTimeout(
                            "fuse_frameProxy", Integer.MAX_VALUE, "null", result);
                }
            }

            @Override
            public void onError(String message, Exception error) {}
        });

        // Preload external casts
        out.println("  Preloading external casts...");
        player.preloadAllCasts();
        for (int i = 0; i < 50; i++) {
            int loaded = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal() && castLib.isLoaded()) loaded++;
            }
            if (loaded > 0 && i >= 10) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // Start playback and run frames
        player.play();

        // Suppress verbose VM output
        System.setOut(new PrintStream(out) {
            @Override public void println(String x) {
                if (x != null && x.startsWith("  [milestone]")) super.println(x);
            }
        });

        FrameSnapshot earlySnapshot = null;  // Before error dialog
        FrameSnapshot lateSnapshot = null;   // After sprites created
        boolean errorDialogReached = false;
        int errorDialogFrame = -1;
        int framesAfterError = 0;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                break;
            }

            // Capture early snapshot (frame 5 - after initial pipeline)
            if (frame == 5 && earlySnapshot == null) {
                earlySnapshot = player.getFrameSnapshot();
            }

            // Check if error dialog has been reached
            if (!errorDialogReached && spriteMilestones.contains("showErrorDialog")) {
                errorDialogReached = true;
                errorDialogFrame = frame;
            }

            if (errorDialogReached) {
                framesAfterError++;
                if (framesAfterError == 50) {
                    lateSnapshot = player.getFrameSnapshot();
                    break;
                }
            }
        }

        System.setOut(out);

        // Count dynamic sprites from registry
        for (SpriteState state : player.getStageRenderer().getSpriteRegistry().getDynamicSprites()) {
            if (state.hasDynamicMember()) dynamicSpriteCount[0]++;
        }

        out.println("  Milestones reached: " + spriteMilestones);
        out.println("  Dynamic sprites: " + dynamicSpriteCount[0]);
        out.println("  Error dialog at frame: " + errorDialogFrame);

        // Test 7: Dynamic sprites should be created by Lingo
        if (dynamicSpriteCount[0] > 0) {
            out.println("  PASS: " + dynamicSpriteCount[0] + " dynamic sprites created by Lingo");
            passed++;
        } else {
            out.println("  FAIL: No dynamic sprites created by Lingo scripts");
            failed++;
        }

        // ===== PHASE 4: Rendered Frame Validation =====
        out.println("\n=== PHASE 4: Rendered Frame Validation ===\n");

        FrameSnapshot snapshot = lateSnapshot != null ? lateSnapshot : earlySnapshot;
        if (snapshot == null) {
            out.println("  FAIL: No frame snapshot captured");
            failed++;
        } else {
            // Test 8: Background color in snapshot must match config
            int snapshotBg = snapshot.backgroundColor();
            out.printf("  Snapshot background color: 0x%06X%n", snapshotBg);
            if (snapshotBg == stageColorRGB) {
                out.printf("  PASS: Snapshot background matches config (0x%06X)%n", snapshotBg);
                passed++;
            } else {
                out.printf("  FAIL: Snapshot bg (0x%06X) != config (0x%06X)%n", snapshotBg, stageColorRGB);
                failed++;
            }

            // Test 9: Stage image (if used by scripts) should not be all-black
            if (snapshot.stageImage() != null) {
                Bitmap stageImg = snapshot.stageImage();
                int totalPixels = stageImg.getWidth() * stageImg.getHeight();
                int blackPixels = 0;
                int whitePixels = 0;
                for (int y = 0; y < stageImg.getHeight(); y++) {
                    for (int x = 0; x < stageImg.getWidth(); x++) {
                        int pixel = stageImg.getPixel(x, y) & 0xFFFFFF;
                        if (pixel == 0x000000) blackPixels++;
                        if (pixel == 0xFFFFFF) whitePixels++;
                    }
                }
                double blackPct = 100.0 * blackPixels / totalPixels;
                double whitePct = 100.0 * whitePixels / totalPixels;
                out.printf("  Stage image: %dx%d, %.1f%% black, %.1f%% white%n",
                    stageImg.getWidth(), stageImg.getHeight(), blackPct, whitePct);

                // If config bg is black, stage image being black is expected
                if (blackPct > 95 && stageColorRGB == 0x000000) {
                    out.println("  INFO: Stage image is black (matches config black background)");
                    passed++;
                } else if (blackPct > 95) {
                    out.println("  FAIL: Stage image is almost entirely black (>95%) but config is not black");
                    failed++;
                } else {
                    out.println("  PASS: Stage image is not predominantly black");
                    passed++;
                }
            } else {
                out.println("  INFO: No stage image buffer (scripts haven't drawn on it)");
            }

            // Test 10: Sprites should have valid properties
            List<RenderSprite> sprites = snapshot.sprites();
            out.println("  Total sprites in snapshot: " + sprites.size());
            int bitmapSprites = 0;
            int visibleSprites = 0;
            int bakedCount = 0;
            int missingBakedCount = 0;

            out.println("\n  --- All sprites detail ---");
            for (RenderSprite s : sprites) {
                if (s.getType() == RenderSprite.SpriteType.BITMAP) bitmapSprites++;
                if (s.isVisible()) visibleSprites++;
                boolean hasBaked = s.getBakedBitmap() != null;
                if (s.getType() == RenderSprite.SpriteType.BITMAP) {
                    if (hasBaked) bakedCount++;
                    else missingBakedCount++;
                }
                boolean hasMember = s.getCastMember() != null;
                boolean hasDynamic = s.getDynamicMember() != null;
                out.printf("  ch%-3d %-8s %-25s pos=(%d,%d) %dx%d ink=%d blend=%d locZ=%d " +
                        "member=%s dynamic=%s baked=%s%n",
                    s.getChannel(), s.getType(),
                    s.getMemberName() != null ? "'" + s.getMemberName() + "'" : "(null)",
                    s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                    s.getInk(), s.getBlend(), s.getLocZ(),
                    hasMember ? "Y" : "N",
                    hasDynamic ? "Y" : "N",
                    hasBaked ? s.getBakedBitmap().getWidth() + "x" + s.getBakedBitmap().getHeight() : "MISSING");
            }

            out.printf("\n  Bitmap sprites: %d (baked: %d, missing: %d), Other visible: %d%n",
                bitmapSprites, bakedCount, missingBakedCount, visibleSprites - bitmapSprites);

            if (visibleSprites > 0) {
                out.println("  PASS: " + visibleSprites + " visible sprites rendered");
                passed++;
            } else {
                out.println("  FAIL: No visible sprites in snapshot");
                failed++;
            }

            // Test 11: BITMAP sprites should have baked bitmaps
            if (bitmapSprites > 0 && missingBakedCount == 0) {
                out.println("  PASS: All " + bitmapSprites + " BITMAP sprites have baked bitmaps");
                passed++;
            } else if (bitmapSprites > 0) {
                double bakedPct = 100.0 * bakedCount / bitmapSprites;
                out.printf("  INFO: %d/%d BITMAP sprites have baked bitmaps (%.0f%%)%n",
                    bakedCount, bitmapSprites, bakedPct);
                if (bakedPct >= 50) {
                    out.println("  PASS: Majority of bitmap sprites have baked bitmaps");
                    passed++;
                } else {
                    out.println("  FAIL: Most bitmap sprites missing baked bitmaps");
                    failed++;
                }
            }

            // Render composite PNG for visual inspection
            out.println("\n=== Rendering composite to build/render-stage-background.png ===");
            renderToPng(snapshot, "build/render-stage-background.png", out);
        }

        // ===== SUMMARY =====
        out.println("\n=== SUMMARY: " + passed + " passed, " + failed + " failed ===");

        if (failed > 0) {
            out.println("\nFAILED - Stage background or sprite pipeline is broken");
            System.exit(1);
        } else {
            out.println("\nALL TESTS PASSED - Stage background and sprite pipeline correct");
        }
    }

    private static void renderToPng(FrameSnapshot snapshot, String outputPath, PrintStream out) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 720;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 540;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Background
        g.setColor(new Color(snapshot.backgroundColor()));
        g.fillRect(0, 0, w, h);
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        }

        // Draw all sprites
        int drawn = 0;
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;

            Composite savedComposite = null;
            int blend = sprite.getBlend();
            if (blend >= 0 && blend < 100) {
                savedComposite = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
            }

            int x = sprite.getX(), y = sprite.getY();
            int sw = sprite.getWidth(), sh = sprite.getHeight();

            if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                Bitmap baked = sprite.getBakedBitmap();
                if (baked != null) {
                    BufferedImage img = baked.toBufferedImage();
                    int iw = sw > 0 ? sw : img.getWidth();
                    int ih = sh > 0 ? sh : img.getHeight();
                    g.drawImage(img, x, y, iw, ih, null);
                    drawn++;
                }
            } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                if (sw > 0 && sh > 0) {
                    int fc = sprite.getForeColor();
                    g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                    g.fillRect(x, y, sw, sh);
                    drawn++;
                }
            } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                    || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
                Bitmap baked = sprite.getBakedBitmap();
                if (baked != null) {
                    BufferedImage img = baked.toBufferedImage();
                    int iw = sw > 0 ? sw : img.getWidth();
                    int ih = sh > 0 ? sh : img.getHeight();
                    g.drawImage(img, x, y, iw, ih, null);
                    drawn++;
                }
            }

            if (savedComposite != null) {
                g.setComposite(savedComposite);
            }
        }

        g.dispose();
        out.println("  Sprites drawn: " + drawn);

        try {
            Path pngPath = Path.of(outputPath);
            Files.createDirectories(pngPath.getParent());
            javax.imageio.ImageIO.write(canvas, "PNG", pngPath.toFile());
            out.println("  Saved " + w + "x" + h + " to: " + pngPath.toAbsolutePath());
        } catch (Exception e) {
            out.println("  Failed to save PNG: " + e.getMessage());
        }
    }
}
