package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step-through render test that verifies window rendering works correctly.
 * Steps frame-by-frame and validates that the error dialog sprites appear
 * on channels 38-44 after showErrorDialog is called.
 *
 * Run: ./gradlew :player-core:runWindowStepTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class WindowStepTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;

    // Milestones
    private static volatile int currentFrame = 0;
    private static volatile boolean createWindowReached = false;
    private static int createWindowFrame = -1;
    private static volatile boolean showErrorDialogReached = false;
    private static int showErrorDialogFrame = -1;

    // Captured snapshots at key frames
    private static final List<FrameCapture> captures = new ArrayList<>();

    record FrameCapture(int frame, String label, FrameSnapshot snapshot) {}

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        System.out.println("=== WindowStepTest: Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        if (file.getConfig() != null) {
            int stageColor = file.getConfig().stageColor();
            int rgb = (stageColor & 0xFF) | ((stageColor & 0xFF) << 8) | ((stageColor & 0xFF) << 16);
            player.getStageRenderer().setBackgroundColor(rgb);
        }

        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        PrintStream originalOut = System.out;
        boolean[] frameProxyCreated = {false};

        // Track milestones
        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                if (!createWindowReached && info.handlerName().equals("createWindow")) {
                    createWindowReached = true;
                    createWindowFrame = currentFrame;
                    originalOut.println("  [milestone] createWindow handler at frame " + currentFrame);
                }
                if (!showErrorDialogReached && info.handlerName().equals("showErrorDialog")) {
                    showErrorDialogReached = true;
                    showErrorDialogFrame = currentFrame;
                    originalOut.println("  [milestone] showErrorDialog handler at frame " + currentFrame);
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

        player.setEventListener(info -> {
            if (info.event() == PlayerEvent.ENTER_FRAME) {
                currentFrame = info.frame();
            }
        });

        // Preload external casts
        System.out.println("=== Preloading external casts ===");
        player.preloadAllCasts();
        for (int i = 0; i < 50; i++) {
            int loaded = 0, external = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal()) {
                    external++;
                    if (castLib.isLoaded()) loaded++;
                }
            }
            if (loaded > 0 && i >= 10) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // Start playback
        System.out.println("=== Starting playback ===\n");
        player.play();

        // Suppress VM noise
        System.setOut(new PrintStream(originalOut) {
            @Override public void println(String x) {
                if (x != null && (x.startsWith("[ALERT]") || x.startsWith("[milestone]"))) {
                    super.println(x);
                }
            }
        });

        // Step through frames, capturing at key points
        int framesAfterError = 0;
        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                originalOut.println("Error at frame " + frame + ": " + e.getMessage());
                break;
            }

            // Capture snapshots at milestone boundaries
            if (createWindowReached && createWindowFrame == currentFrame) {
                captureSnapshot(player, "AFTER-CREATE-WINDOW");
            }
            if (showErrorDialogReached && showErrorDialogFrame == currentFrame) {
                captureSnapshot(player, "AFTER-SHOW-ERROR-DIALOG");
            }

            // Capture a few frames after the error dialog to let sprites settle
            if (showErrorDialogReached) {
                framesAfterError++;
                if (framesAfterError == 5) {
                    captureSnapshot(player, "ERROR-DIALOG-SETTLED");
                }
                if (framesAfterError == 50) {
                    captureSnapshot(player, "ERROR-DIALOG-FINAL");
                }
                if (framesAfterError >= 100) {
                    break;
                }
            }
        }

        System.setOut(originalOut);

        // === VALIDATION ===
        System.out.println("\n=== VALIDATION RESULTS ===\n");
        int passed = 0, failed = 0;

        // Test 1: createWindow handler must be called (not intercepted by builtin)
        if (createWindowReached) {
            System.out.println("  PASS: createWindow handler was called (frame " + createWindowFrame + ")");
            passed++;
        } else {
            System.out.println("  FAIL: createWindow handler was NEVER called (builtin intercepted it?)");
            failed++;
        }

        // Test 2: showErrorDialog must be reached
        if (showErrorDialogReached) {
            System.out.println("  PASS: showErrorDialog reached (frame " + showErrorDialogFrame + ")");
            passed++;
        } else {
            System.out.println("  FAIL: showErrorDialog was never reached");
            failed++;
        }

        // Test 3: Error dialog sprites must exist
        FrameSnapshot finalSnapshot = null;
        for (int i = captures.size() - 1; i >= 0; i--) {
            if (captures.get(i).label.contains("ERROR-DIALOG")) {
                finalSnapshot = captures.get(i).snapshot;
                break;
            }
        }

        if (finalSnapshot != null) {
            List<RenderSprite> sprites = finalSnapshot.sprites();
            boolean hasErrorBgB = false, hasErrorBgC = false;
            boolean hasTitle = false, hasText = false, hasClose = false, hasModal = false;

            for (RenderSprite s : sprites) {
                String name = s.getMemberName();
                if (name == null) continue;
                if (name.contains("error_bg_b")) hasErrorBgB = true;
                if (name.contains("error_bg_c")) hasErrorBgC = true;
                if (name.contains("error_title")) hasTitle = true;
                if (name.contains("error_text")) hasText = true;
                if (name.contains("error_close")) hasClose = true;
                if (name.contains("modal")) hasModal = true;
            }

            // Test 3a: bg_b (main background)
            if (hasErrorBgB) {
                System.out.println("  PASS: error_bg_b sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: error_bg_b sprite MISSING");
                failed++;
            }

            // Test 3b: bg_c (inner background)
            if (hasErrorBgC) {
                System.out.println("  PASS: error_bg_c sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: error_bg_c sprite MISSING");
                failed++;
            }

            // Test 3c: title
            if (hasTitle) {
                System.out.println("  PASS: error_title sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: error_title sprite MISSING");
                failed++;
            }

            // Test 3d: text
            if (hasText) {
                System.out.println("  PASS: error_text sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: error_text sprite MISSING");
                failed++;
            }

            // Test 3e: close button
            if (hasClose) {
                System.out.println("  PASS: error_close sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: error_close sprite MISSING");
                failed++;
            }

            // Test 3f: modal overlay
            if (hasModal) {
                System.out.println("  PASS: modal overlay sprite present");
                passed++;
            } else {
                System.out.println("  FAIL: modal overlay sprite MISSING");
                failed++;
            }

            // Test 4: Error dialog sprites should have baked bitmaps
            int bakedCount = 0;
            for (RenderSprite s : sprites) {
                String name = s.getMemberName();
                if (name != null && name.startsWith("error_") && s.getBakedBitmap() != null) {
                    bakedCount++;
                }
            }
            if (bakedCount >= 4) {
                System.out.println("  PASS: " + bakedCount + " error dialog sprites have baked bitmaps");
                passed++;
            } else {
                System.out.println("  FAIL: Only " + bakedCount + " error dialog sprites have baked bitmaps (expected >= 4)");
                failed++;
            }

            // Test 5: Total sprite count should be significantly more than without windows
            if (sprites.size() >= 30) {
                System.out.println("  PASS: " + sprites.size() + " total sprites (windows add ~8 sprites)");
                passed++;
            } else {
                System.out.println("  FAIL: Only " + sprites.size() + " sprites (expected >= 30 with window sprites)");
                failed++;
            }

        } else {
            System.out.println("  FAIL: No error dialog snapshot captured");
            failed += 7;
        }

        // Render the best snapshot
        FrameSnapshot renderTarget = finalSnapshot;
        if (renderTarget == null && !captures.isEmpty()) {
            renderTarget = captures.get(captures.size() - 1).snapshot;
        }

        if (renderTarget != null) {
            System.out.println("\n=== Rendering to build/render-window-step.png ===");
            renderToPng(renderTarget, player, "build/render-window-step.png");
        }

        // Summary
        System.out.println("\n=== SUMMARY: " + passed + " passed, " + failed + " failed ===");

        if (failed > 0) {
            System.out.println("\nFAILED - Window rendering is broken");
            System.exit(1);
        } else {
            System.out.println("\nALL TESTS PASSED - Windows display correctly");
        }
    }

    private static void captureSnapshot(Player player, String label) {
        try {
            FrameSnapshot snapshot = player.getFrameSnapshot();
            if (snapshot != null) {
                captures.add(new FrameCapture(currentFrame, label, snapshot));
            }
        } catch (Exception e) {
            // Ignore capture errors
        }
    }

    private static void renderToPng(FrameSnapshot snapshot, Player player, String outputPath) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 640;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 480;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        } else {
            g.setColor(new Color(snapshot.backgroundColor()));
            g.fillRect(0, 0, w, h);
        }

        int spritesDrawn = 0;
        if (snapshot.sprites() != null) {
            for (RenderSprite sprite : snapshot.sprites()) {
                if (!sprite.isVisible()) continue;
                int x = sprite.getX(), y = sprite.getY();

                if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                    Bitmap baked = sprite.getBakedBitmap();
                    if (baked != null) {
                        BufferedImage img = baked.toBufferedImage();
                        int sw = sprite.getWidth() > 0 ? sprite.getWidth() : img.getWidth();
                        int sh = sprite.getHeight() > 0 ? sprite.getHeight() : img.getHeight();

                        int blend = sprite.getBlend();
                        Composite oldComposite = null;
                        if (blend >= 0 && blend < 100) {
                            oldComposite = g.getComposite();
                            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
                        }

                        g.drawImage(img, x, y, sw, sh, null);

                        if (oldComposite != null) {
                            g.setComposite(oldComposite);
                        }
                        spritesDrawn++;
                    }
                } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                    int sw = sprite.getWidth() > 0 ? sprite.getWidth() : 50;
                    int sh = sprite.getHeight() > 0 ? sprite.getHeight() : 50;
                    int fc = sprite.getForeColor();
                    g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                    g.fillRect(x, y, sw, sh);
                    spritesDrawn++;
                } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                        || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
                    String text = null;
                    if (sprite.getDynamicMember() != null) {
                        text = sprite.getDynamicMember().getTextContent();
                    }
                    int sw = sprite.getWidth() > 0 ? sprite.getWidth() : 200;
                    int sh = sprite.getHeight() > 0 ? sprite.getHeight() : 20;
                    g.setColor(new Color(240, 240, 240));
                    g.fillRect(x, y, sw, sh);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    if (text != null && !text.isEmpty()) {
                        g.drawString(text, x + 2, y + 14);
                    }
                    spritesDrawn++;
                }
            }
        }

        g.dispose();
        System.out.println("  Sprites drawn: " + spritesDrawn);

        try {
            Path pngPath = Path.of(outputPath);
            javax.imageio.ImageIO.write(canvas, "PNG", pngPath.toFile());
            System.out.println("  Saved " + w + "x" + h + " to: " + pngPath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("  Failed to save PNG: " + e.getMessage());
        }
    }
}
