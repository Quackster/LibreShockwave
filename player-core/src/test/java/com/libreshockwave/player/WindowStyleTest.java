package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.BitmapCache;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.player.render.InkProcessor;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Diagnostic test that traces through the exact rendering pipeline for the
 * error dialog window elements to identify why styling doesn't match Director 1:1.
 *
 * Checks raw bitmap state, colorization flags, and baked bitmap results for
 * each window element (modal, bg_a, bg_b, bg_c, title, text, close).
 *
 * Run: ./gradlew :player-core:runWindowStyleTest
 */
public class WindowStyleTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;

    private static volatile int currentFrame = 0;
    private static volatile boolean showErrorDialogReached = false;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        System.out.println("=== WindowStyleTest: Diagnostic analysis of window element rendering ===\n");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        if (file.getConfig() != null) {
            player.getStageRenderer().setBackgroundColor(file.getConfig().stageColorRGB());
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
                    out.println("[milestone] showErrorDialog reached at frame " + currentFrame);
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

        player.play();

        // Suppress VM noise during playback
        System.setOut(new PrintStream(out) {
            @Override public void println(String x) {
                if (x != null && x.startsWith("[milestone]")) super.println(x);
            }
        });

        FrameSnapshot dialogSnapshot = null;
        int framesAfterError = 0;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try { player.stepFrame(); } catch (Exception e) { break; }
            if (showErrorDialogReached) {
                framesAfterError++;
                if (framesAfterError == 50) {
                    try { dialogSnapshot = player.getFrameSnapshot(); } catch (Exception ignored) {}
                    break;
                }
            }
        }

        System.setOut(out);

        if (!showErrorDialogReached || dialogSnapshot == null) {
            System.out.println("FAIL: Error dialog not reached or no snapshot captured");
            System.exit(1);
        }

        // ============= DIAGNOSTIC ANALYSIS =============
        System.out.println("\n=== WINDOW ELEMENT DIAGNOSTICS ===\n");

        int passed = 0;
        int failed = 0;

        String[] targetNames = {
            "modal_modal", "error_bg_a", "error_bg_b", "error_bg_c",
            "error_title", "error_text", "error_close"
        };

        for (RenderSprite s : dialogSnapshot.sprites()) {
            String name = s.getMemberName();
            if (name == null) continue;

            boolean isTarget = false;
            for (String t : targetNames) {
                if (name.contains(t)) { isTarget = true; break; }
            }
            if (!isTarget) continue;

            System.out.printf("--- %s (ch %d) ---%n", name, s.getChannel());
            System.out.printf("  Type:       %s%n", s.getType());
            System.out.printf("  Position:   (%d, %d) %dx%d%n", s.getX(), s.getY(), s.getWidth(), s.getHeight());
            System.out.printf("  Ink:        %d%n", s.getInk());
            System.out.printf("  Blend:      %d%n", s.getBlend());
            System.out.printf("  ForeColor:  0x%06X (hasForeColor=%b)%n", s.getForeColor(), s.hasForeColor());
            System.out.printf("  BackColor:  0x%06X (hasBackColor=%b)%n", s.getBackColor(), s.hasBackColor());

            // Check raw dynamic member bitmap (before colorization)
            CastMember dm = s.getDynamicMember();
            if (dm != null) {
                Bitmap rawBmp = dm.getBitmap();
                if (rawBmp != null) {
                    int rawW = rawBmp.getWidth();
                    int rawH = rawBmp.getHeight();
                    int topLeft = rawBmp.getPixel(0, 0);
                    int centerX = rawW / 2, centerY = rawH / 2;
                    int center = rawW > 0 && rawH > 0 ? rawBmp.getPixel(Math.min(centerX, rawW-1), Math.min(centerY, rawH-1)) : 0;
                    System.out.printf("  RAW bitmap: %dx%d, depth=%d%n", rawW, rawH, rawBmp.getBitDepth());
                    System.out.printf("  RAW topLeft: 0x%08X (a=%d r=%d g=%d b=%d)%n",
                        topLeft, (topLeft>>>24), (topLeft>>16)&0xFF, (topLeft>>8)&0xFF, topLeft&0xFF);
                    System.out.printf("  RAW center:  0x%08X (a=%d r=%d g=%d b=%d)%n",
                        center, (center>>>24), (center>>16)&0xFF, (center>>8)&0xFF, center&0xFF);
                    System.out.printf("  RAW avgBrightness: %d%n", averageBrightness(rawBmp));
                } else {
                    System.out.println("  RAW bitmap: null");
                }
            } else {
                System.out.println("  DynamicMember: null (file-loaded)");
            }

            // Check baked bitmap (after colorization)
            Bitmap baked = s.getBakedBitmap();
            if (baked != null) {
                int bkdW = baked.getWidth();
                int bkdH = baked.getHeight();
                int topLeft = baked.getPixel(0, 0);
                int centerX = bkdW / 2, centerY = bkdH / 2;
                int center = bkdW > 0 && bkdH > 0 ? baked.getPixel(Math.min(centerX, bkdW-1), Math.min(centerY, bkdH-1)) : 0;
                System.out.printf("  BAKED bitmap: %dx%d%n", bkdW, bkdH);
                System.out.printf("  BAKED topLeft: 0x%08X (a=%d r=%d g=%d b=%d)%n",
                    topLeft, (topLeft>>>24), (topLeft>>16)&0xFF, (topLeft>>8)&0xFF, topLeft&0xFF);
                System.out.printf("  BAKED center:  0x%08X (a=%d r=%d g=%d b=%d)%n",
                    center, (center>>>24), (center>>16)&0xFF, (center>>8)&0xFF, center&0xFF);
                System.out.printf("  BAKED avgBrightness: %d%n", averageBrightness(baked));

                // Check transparency
                int transparentCount = 0;
                int opaqueCount = 0;
                for (int y = 0; y < bkdH; y++) {
                    for (int x = 0; x < bkdW; x++) {
                        int p = baked.getPixel(x, y);
                        if ((p >>> 24) == 0) transparentCount++;
                        else if ((p >>> 24) == 255) opaqueCount++;
                    }
                }
                System.out.printf("  BAKED transparent: %d, opaque: %d, other: %d%n",
                    transparentCount, opaqueCount, (bkdW * bkdH) - transparentCount - opaqueCount);
            } else {
                System.out.println("  BAKED bitmap: null");
            }

            // Colorization analysis
            boolean wouldColorize = (s.hasForeColor() || s.hasBackColor())
                    && InkProcessor.allowsColorize(s.getInk());
            System.out.printf("  Colorization: wouldApply=%b (hasFore=%b hasBack=%b allowsInk=%b)%n",
                wouldColorize, s.hasForeColor(), s.hasBackColor(), InkProcessor.allowsColorize(s.getInk()));

            System.out.println();
        }

        // ============= VALIDATION TESTS =============
        System.out.println("=== VALIDATION TESTS ===\n");

        // Find key sprites
        RenderSprite modal = null, bgB = null, bgC = null;
        for (RenderSprite s : dialogSnapshot.sprites()) {
            String name = s.getMemberName();
            if (name == null) continue;
            if (name.contains("modal_modal")) modal = s;
            else if (name.contains("error_bg_b")) bgB = s;
            else if (name.contains("error_bg_c")) bgC = s;
        }

        // Test 1: bg_b must render DARK (border)
        if (bgB != null && bgB.getBakedBitmap() != null) {
            int brightness = averageBrightness(bgB.getBakedBitmap());
            int opaquePixels = countOpaquePixels(bgB.getBakedBitmap());
            int totalPixels = bgB.getBakedBitmap().getWidth() * bgB.getBakedBitmap().getHeight();
            if (brightness < 30 && opaquePixels > totalPixels * 0.5) {
                System.out.printf("  PASS: bg_b border is dark (brightness=%d, opaque=%d/%d)%n", brightness, opaquePixels, totalPixels);
                passed++;
            } else {
                System.out.printf("  FAIL: bg_b border should be dark but brightness=%d, opaque=%d/%d%n", brightness, opaquePixels, totalPixels);
                failed++;
            }
        } else {
            System.out.println("  FAIL: bg_b sprite or bitmap not found");
            failed++;
        }

        // Test 2: bg_c must render WHITE (content area)
        if (bgC != null && bgC.getBakedBitmap() != null) {
            int brightness = averageBrightness(bgC.getBakedBitmap());
            int opaquePixels = countOpaquePixels(bgC.getBakedBitmap());
            int totalPixels = bgC.getBakedBitmap().getWidth() * bgC.getBakedBitmap().getHeight();
            if (brightness > 240 && opaquePixels > totalPixels * 0.5) {
                System.out.printf("  PASS: bg_c content is white (brightness=%d, opaque=%d/%d)%n", brightness, opaquePixels, totalPixels);
                passed++;
            } else {
                System.out.printf("  FAIL: bg_c content should be white but brightness=%d, opaque=%d/%d)%n", brightness, opaquePixels, totalPixels);
                failed++;
            }
        } else {
            System.out.println("  FAIL: bg_c sprite or bitmap not found");
            failed++;
        }

        // Test 3: modal must render DARK
        if (modal != null && modal.getBakedBitmap() != null) {
            int brightness = averageBrightness(modal.getBakedBitmap());
            int opaquePixels = countOpaquePixels(modal.getBakedBitmap());
            int totalPixels = modal.getBakedBitmap().getWidth() * modal.getBakedBitmap().getHeight();
            if (brightness < 30 && opaquePixels > totalPixels * 0.5) {
                System.out.printf("  PASS: modal is dark (brightness=%d, opaque=%d/%d)%n", brightness, opaquePixels, totalPixels);
                passed++;
            } else {
                System.out.printf("  FAIL: modal should be dark but brightness=%d, opaque=%d/%d%n", brightness, opaquePixels, totalPixels);
                failed++;
            }
        } else {
            System.out.println("  FAIL: modal sprite or bitmap not found");
            failed++;
        }

        // Test 4: Composite - dialog area brighter than modal
        int stageW = dialogSnapshot.stageWidth() > 0 ? dialogSnapshot.stageWidth() : 720;
        int stageH = dialogSnapshot.stageHeight() > 0 ? dialogSnapshot.stageHeight() : 540;
        BufferedImage composite = renderComposite(dialogSnapshot, stageW, stageH);

        int dialogCenterX = bgC != null ? bgC.getX() + bgC.getWidth() / 2 : 360;
        int dialogCenterY = bgC != null ? bgC.getY() + bgC.getHeight() / 2 : 270;
        if (dialogCenterX >= 0 && dialogCenterX < stageW && dialogCenterY >= 0 && dialogCenterY < stageH) {
            int dialogPixel = composite.getRGB(dialogCenterX, dialogCenterY);
            int dialogBrightness = brightness(dialogPixel);
            int outsidePixel = composite.getRGB(50, 50);
            int outsideBrightness = brightness(outsidePixel);
            if (dialogBrightness > outsideBrightness + 30) {
                System.out.printf("  PASS: Dialog area (%d) is brighter than modal area (%d) - delta=%d%n",
                    dialogBrightness, outsideBrightness, dialogBrightness - outsideBrightness);
                passed++;
            } else {
                System.out.printf("  FAIL: Dialog area (%d) should be brighter than modal area (%d)%n",
                    dialogBrightness, outsideBrightness);
                failed++;
            }
        } else {
            System.out.println("  FAIL: Dialog center out of bounds");
            failed++;
        }

        // Save composite
        Path pngPath = Path.of("player-core/build/render-window-style.png");
        Files.createDirectories(pngPath.getParent());
        javax.imageio.ImageIO.write(composite, "PNG", pngPath.toFile());
        System.out.println("\n  Saved composite to: " + pngPath.toAbsolutePath());

        // Summary
        System.out.printf("%n=== SUMMARY: %d passed, %d failed ===%n%n", passed, failed);
        if (failed > 0) {
            System.out.println("FAILED - Window styling needs fixes");
            System.exit(1);
        } else {
            System.out.println("ALL TESTS PASSED");
        }
    }

    private static int averageBrightness(Bitmap bmp) {
        if (bmp == null) return 0;
        long total = 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                int alpha = (pixel >>> 24);
                if (alpha > 10) {
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    total += (r + g + b) / 3;
                    count++;
                }
            }
        }
        return count > 0 ? (int)(total / count) : 0;
    }

    private static int countOpaquePixels(Bitmap bmp) {
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                if ((bmp.getPixel(x, y) >>> 24) > 10) count++;
            }
        }
        return count;
    }

    private static int brightness(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3;
    }

    private static BufferedImage renderComposite(FrameSnapshot snapshot, int w, int h) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(snapshot.backgroundColor()));
        g.fillRect(0, 0, w, h);
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        }
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;
            drawSprite(g, sprite);
        }
        g.dispose();
        return canvas;
    }

    private static void drawSprite(Graphics2D g, RenderSprite sprite) {
        int x = sprite.getX(), y = sprite.getY();
        int sw = sprite.getWidth(), sh = sprite.getHeight();
        Composite saved = null;
        int blend = sprite.getBlend();
        if (blend >= 0 && blend < 100) {
            saved = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
        }
        if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
            Bitmap baked = sprite.getBakedBitmap();
            if (baked != null) {
                BufferedImage img = baked.toBufferedImage();
                int iw = sw > 0 ? sw : img.getWidth();
                int ih = sh > 0 ? sh : img.getHeight();
                g.drawImage(img, x, y, iw, ih, null);
            }
        } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
            if (sw > 0 && sh > 0) {
                int fc = sprite.getForeColor();
                g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                g.fillRect(x, y, sw, sh);
            }
        } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
            Bitmap baked = sprite.getBakedBitmap();
            if (baked != null) {
                BufferedImage img = baked.toBufferedImage();
                int iw = sw > 0 ? sw : img.getWidth();
                int ih = sh > 0 ? sh : img.getHeight();
                g.drawImage(img, x, y, iw, ih, null);
            }
        }
        if (saved != null) {
            g.setComposite(saved);
        }
    }
}
