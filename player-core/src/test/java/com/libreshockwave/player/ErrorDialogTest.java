package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
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
 * Diagnostic test for the error dialog window rendering.
 * Steps frame-by-frame until the error dialog appears, captures sprites,
 * and validates that window backgrounds are visible (not transparent/invisible).
 *
 * Detects the key bugs:
 * 1. sprite.blend = VOID causing blend=0 (invisible)
 * 2. Window background bitmaps being transparent (all-white after pattern rendering)
 * 3. Modal overlay missing or invisible
 *
 * Run: ./gradlew :player-core:runErrorDialogTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class ErrorDialogTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;

    // Frame tracking
    private static volatile int currentFrame = 0;
    private static volatile boolean showErrorDialogReached = false;
    private static int showErrorDialogFrame = -1;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        System.out.println("=== ErrorDialogTest: Loading habbo.dcr ===");
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

        PrintStream out = System.out;
        boolean[] frameProxyCreated = {false};

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                if (!showErrorDialogReached && info.handlerName().equals("showErrorDialog")) {
                    showErrorDialogReached = true;
                    showErrorDialogFrame = currentFrame;
                    out.println("  [milestone] showErrorDialog at frame " + currentFrame);
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

        // Diagnostic: search for error/bg pattern members across all casts
        System.out.println("=== Searching for pattern members ===");
        var castLibs = player.getCastLibManager().getCastLibs();
        for (var entry : castLibs.entrySet()) {
            var castLib = entry.getValue();
            if (!castLib.isLoaded()) continue;
            int castNum = castLib.getNumber();
            for (var memberEntry : castLib.getMemberChunks().entrySet()) {
                String name = memberEntry.getValue().name();
                if (name != null && !name.isEmpty()) {
                    String nl = name.toLowerCase();
                    if (nl.contains("error") || nl.contains("modal") || nl.contains("bg_")) {
                        System.out.printf("  cast%d member%d: '%s' type=%s%n",
                            castNum, memberEntry.getKey(), name, memberEntry.getValue().memberType());
                    }
                }
            }
        }

        // Print error.window text content
        System.out.println("=== error.window definition ===");
        var errWindowRef = player.getCastLibManager().getMemberByName(0, "error.window");
        if (errWindowRef instanceof com.libreshockwave.vm.Datum.CastMemberRef cmr) {
            var castLib2 = player.getCastLibManager().getCastLib(cmr.castLib());
            if (castLib2 != null) {
                var member2 = castLib2.getMember(cmr.member());
                if (member2 != null) System.out.println(member2.getTextContent());
                else {
                    var chunk = castLib2.findMemberByNumber(cmr.member());
                    if (chunk != null) System.out.println("  (file text member found, type=" + chunk.memberType() + ")");
                }
            }
        }
        System.out.println("=== modal.window definition ===");
        var modalRef = player.getCastLibManager().getMemberByName(0, "modal.window");
        if (modalRef instanceof com.libreshockwave.vm.Datum.CastMemberRef cmr) {
            var castLib2 = player.getCastLibManager().getCastLib(cmr.castLib());
            if (castLib2 != null) {
                var member2 = castLib2.getMember(cmr.member());
                if (member2 != null) System.out.println(member2.getTextContent());
            }
        }

        System.out.println("=== Starting playback ===");
        player.play();

        // Suppress VM noise
        System.setOut(new PrintStream(out) {
            @Override public void println(String x) {
                if (x != null && (x.startsWith("  [milestone]") || x.startsWith("[ALERT]"))) {
                    super.println(x);
                }
            }
        });

        // Step frames until error dialog and 50 frames after
        int framesAfterError = 0;
        FrameSnapshot dialogSnapshot = null;
        List<FrameSnapshot> progressSnapshots = new ArrayList<>();

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                out.println("Error at frame " + frame + ": " + e.getMessage());
                break;
            }

            if (showErrorDialogReached) {
                framesAfterError++;

                // Capture snapshots every 5 frames after dialog
                if (framesAfterError % 5 == 0 && framesAfterError <= 30) {
                    try {
                        FrameSnapshot snap = player.getFrameSnapshot();
                        if (snap != null) progressSnapshots.add(snap);
                    } catch (Exception ignored) {}
                }

                if (framesAfterError == 50) {
                    try {
                        dialogSnapshot = player.getFrameSnapshot();
                    } catch (Exception ignored) {}
                    break;
                }
            }
        }

        System.setOut(out);

        // Use last progress snapshot if dialogSnapshot is null
        if (dialogSnapshot == null && !progressSnapshots.isEmpty()) {
            dialogSnapshot = progressSnapshots.get(progressSnapshots.size() - 1);
        }

        System.out.println("\n=== DIAGNOSTIC ANALYSIS ===\n");

        // ---- VALIDATION ----
        int passed = 0, failed = 0;

        // Test 1: showErrorDialog must be reached
        if (showErrorDialogReached) {
            System.out.println("  PASS: showErrorDialog reached at frame " + showErrorDialogFrame);
            passed++;
        } else {
            System.out.println("  FAIL: showErrorDialog never reached");
            failed++;
            System.out.println("\n=== SUMMARY: " + passed + " passed, " + failed + " failed ===");
            System.exit(1);
        }

        if (dialogSnapshot == null) {
            System.out.println("  FAIL: No dialog snapshot captured");
            System.exit(1);
        }

        // Dump sprite details
        System.out.println("\n--- Error dialog sprites ---");
        List<RenderSprite> errorSprites = new ArrayList<>();
        List<RenderSprite> allSprites = dialogSnapshot.sprites();

        for (RenderSprite s : allSprites) {
            String name = s.getMemberName();
            if (name != null && (name.contains("error_") || name.contains("modal"))) {
                errorSprites.add(s);
                System.out.printf("  ch%d: name='%s' type=%s pos=(%d,%d) size=%dx%d ink=%d blend=%d visible=%s%n",
                    s.getChannel(), name, s.getType(), s.getX(), s.getY(),
                    s.getWidth(), s.getHeight(), s.getInk(), s.getBlend(), s.isVisible());

                if (s.getBakedBitmap() != null) {
                    Bitmap bmp = s.getBakedBitmap();
                    int nonTransparent = countNonTransparent(bmp);
                    int total = bmp.getWidth() * bmp.getHeight();
                    System.out.printf("     bakedBitmap: %dx%d, %d/%d non-transparent pixels (%.1f%%)%n",
                        bmp.getWidth(), bmp.getHeight(), nonTransparent, total,
                        total > 0 ? 100.0 * nonTransparent / total : 0);
                } else {
                    System.out.println("     bakedBitmap: (null)");
                }
            }
        }

        // Test 2: Blend values must be correct (not 0 for main elements)
        System.out.println("\n--- Blend validation ---");
        boolean allBlendsOk = true;
        for (RenderSprite s : errorSprites) {
            String name = s.getMemberName();
            if (name == null) continue;
            // Main window background should have blend=100 (or at least > 0)
            if (name.contains("error_bg") || name.contains("error_title") || name.contains("error_text")) {
                if (s.getBlend() == 0) {
                    System.out.println("  FAIL: " + name + " has blend=0 (invisible!) - sprite.blend = VOID bug");
                    allBlendsOk = false;
                    failed++;
                } else if (s.getBlend() < 50) {
                    System.out.println("  WARN: " + name + " has blend=" + s.getBlend() + " (very transparent)");
                } else {
                    System.out.println("  PASS: " + name + " has blend=" + s.getBlend());
                    passed++;
                }
            }
            // Modal should have blend=40
            if (name.contains("modal")) {
                if (s.getBlend() == 0) {
                    System.out.println("  FAIL: " + name + " has blend=0 (invisible!) - should be 40");
                    allBlendsOk = false;
                    failed++;
                } else {
                    System.out.println("  PASS: " + name + " has blend=" + s.getBlend() + " (modal overlay)");
                    passed++;
                }
            }
        }

        if (allBlendsOk && !errorSprites.isEmpty()) {
            System.out.println("  PASS: All error dialog sprites have correct blend values");
        }

        // Test 3: Background bitmaps must have visible content (not all white/transparent)
        System.out.println("\n--- Bitmap content validation ---");
        boolean bgContentOk = true;
        for (RenderSprite s : errorSprites) {
            String name = s.getMemberName();
            if (name == null || !name.contains("error_bg")) continue;

            Bitmap bmp = s.getBakedBitmap();
            if (bmp == null) {
                System.out.println("  FAIL: " + name + " has no baked bitmap");
                bgContentOk = false;
                failed++;
                continue;
            }

            int total = bmp.getWidth() * bmp.getHeight();
            int nonTransparent = countNonTransparent(bmp);
            int nonWhite = countNonWhite(bmp);
            double visiblePercent = total > 0 ? 100.0 * nonTransparent / total : 0;

            if (nonTransparent == 0) {
                System.out.println("  FAIL: " + name + " bitmap is fully transparent (matte removed everything)");
                bgContentOk = false;
                failed++;
            } else if (visiblePercent < 30) {
                System.out.printf("  FAIL: %s bitmap is mostly transparent (%.1f%% visible) - pattern rendering issue%n",
                    name, visiblePercent);
                bgContentOk = false;
                failed++;
            } else {
                System.out.printf("  PASS: %s bitmap has %.1f%% visible pixels (%d non-white)%n",
                    name, visiblePercent, nonWhite);
                passed++;
            }
        }

        // Test 4: Text elements must have text content
        System.out.println("\n--- Text content validation ---");
        boolean titleFound = false, textFound = false;
        for (RenderSprite s : errorSprites) {
            String name = s.getMemberName();
            if (name == null) continue;
            if (name.contains("error_title") || name.contains("title")) titleFound = true;
            if (name.contains("error_text") || name.contains("text")) textFound = true;
        }

        if (titleFound) {
            System.out.println("  PASS: error_title element present");
            passed++;
        } else {
            System.out.println("  FAIL: error_title element missing");
            failed++;
        }
        if (textFound) {
            System.out.println("  PASS: error_text element present");
            passed++;
        } else {
            System.out.println("  FAIL: error_text element missing");
            failed++;
        }

        // Test 5: Total sprites must be >= 30 with window
        int total = allSprites.size();
        if (total >= 30) {
            System.out.println("\n  PASS: " + total + " total sprites (including window sprites)");
            passed++;
        } else {
            System.out.println("\n  FAIL: Only " + total + " sprites (expected >= 30)");
            failed++;
        }

        // Render composite PNG for visual inspection
        System.out.println("\n=== Rendering composite to build/render-error-dialog.png ===");
        renderToPng(dialogSnapshot, "build/render-error-dialog.png", out);

        // Render individual sprite PNGs for debugging
        renderIndividualSprites(errorSprites, "build/sprites/", out);

        // Summary
        System.out.println("\n=== SUMMARY: " + passed + " passed, " + failed + " failed ===");

        if (failed > 0) {
            System.out.println("\nFAILED - Error dialog rendering is broken");
            System.exit(1);
        } else {
            System.out.println("\nALL TESTS PASSED - Error dialog displays correctly");
        }
    }

    private static int countNonTransparent(Bitmap bmp) {
        if (bmp == null) return 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                if ((pixel >>> 24) > 10) count++;  // Alpha > 10 = visible
            }
        }
        return count;
    }

    private static int countNonWhite(Bitmap bmp) {
        if (bmp == null) return 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (r < 240 || g < 240 || b < 240) count++;
            }
        }
        return count;
    }

    private static void renderToPng(FrameSnapshot snapshot, String outputPath, PrintStream out) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 720;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 540;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background
        g.setColor(new Color(snapshot.backgroundColor()));
        g.fillRect(0, 0, w, h);
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        }

        // Draw all sprites sorted by z-order (already sorted in snapshot)
        int drawn = 0;
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;
            drawn += drawSprite(g, sprite);
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

    private static int drawSprite(Graphics2D g, RenderSprite sprite) {
        int x = sprite.getX(), y = sprite.getY();
        int sw = sprite.getWidth(), sh = sprite.getHeight();

        Composite savedComposite = null;
        int blend = sprite.getBlend();
        if (blend >= 0 && blend < 100) {
            savedComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
        }

        int result = 0;

        if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
            Bitmap baked = sprite.getBakedBitmap();
            if (baked != null) {
                BufferedImage img = baked.toBufferedImage();
                int iw = sw > 0 ? sw : img.getWidth();
                int ih = sh > 0 ? sh : img.getHeight();
                g.drawImage(img, x, y, iw, ih, null);
                result = 1;
            }
        } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
            if (sw > 0 && sh > 0) {
                int fc = sprite.getForeColor();
                g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                g.fillRect(x, y, sw, sh);
                result = 1;
            }
        } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
            // For text sprites, render the baked bitmap if available, else draw text
            Bitmap baked = sprite.getBakedBitmap();
            if (baked != null) {
                BufferedImage img = baked.toBufferedImage();
                int iw = sw > 0 ? sw : img.getWidth();
                int ih = sh > 0 ? sh : img.getHeight();
                g.drawImage(img, x, y, iw, ih, null);
            } else {
                // Fallback: render text with simple AWT
                String text = sprite.getDynamicMember() != null
                    ? sprite.getDynamicMember().getTextContent() : null;
                if (sw <= 0) sw = 200;
                if (sh <= 0) sh = 20;
                g.setColor(new Color(245, 245, 245));
                g.fillRect(x, y, sw, sh);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                if (text != null && !text.isEmpty()) {
                    // Simple word wrap
                    String[] words = text.split("\\s+");
                    StringBuilder line = new StringBuilder();
                    int lineY = y + 13;
                    for (String word : words) {
                        if (line.length() > 0 && (line.length() + word.length()) * 6 > sw) {
                            g.drawString(line.toString().trim(), x + 2, lineY);
                            lineY += 14;
                            line = new StringBuilder();
                        }
                        line.append(word).append(" ");
                    }
                    if (line.length() > 0) {
                        g.drawString(line.toString().trim(), x + 2, lineY);
                    }
                }
            }
            result = 1;
        }

        if (savedComposite != null) {
            g.setComposite(savedComposite);
        }

        return result;
    }

    private static void renderIndividualSprites(List<RenderSprite> sprites, String dirPath, PrintStream out) {
        try {
            Files.createDirectories(Path.of(dirPath));
        } catch (Exception e) {
            return;
        }

        for (RenderSprite sprite : sprites) {
            String name = sprite.getMemberName();
            if (name == null) continue;

            Bitmap baked = sprite.getBakedBitmap();
            if (baked == null || baked.getWidth() == 0 || baked.getHeight() == 0) continue;

            try {
                BufferedImage img = baked.toBufferedImage();
                String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path pngPath = Path.of(dirPath, safeName + ".png");
                javax.imageio.ImageIO.write(img, "PNG", pngPath.toFile());
                out.println("  Saved sprite '" + name + "' to " + pngPath.getFileName());
            } catch (Exception e) {
                out.println("  Failed to save sprite " + name + ": " + e.getMessage());
            }
        }
    }
}
