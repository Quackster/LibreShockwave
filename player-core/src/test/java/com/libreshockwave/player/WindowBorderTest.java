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
 * Render test that steps through frames and validates the error dialog
 * window renders with correct borders (dark border, white content, dark modal).
 *
 * Validates the foreColor/backColor colorization fix:
 * - modal overlay: BLACK at 40% blend (dimming effect)
 * - bg_b (border): BLACK at 60% blend (dark border)
 * - bg_c (content): WHITE at 60% blend (content area)
 * - text elements use ink=36 (no colorization, transparent background)
 *
 * Run: ./gradlew :player-core:runWindowBorderTest
 */
public class WindowBorderTest {

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

        System.out.println("=== WindowBorderTest: Validating error dialog border rendering ===\n");
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

        // Preload external casts
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

        // Step frames until error dialog appears + 50 frames for rendering to settle
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

        // ============= RUN VALIDATION =============
        System.out.println("\n=== BORDER RENDERING VALIDATION ===\n");
        int passed = 0, failed = 0;

        // Collect sprites by name
        RenderSprite modal = null, bgA = null, bgB = null, bgC = null;
        RenderSprite title = null, text = null, close = null;

        for (RenderSprite s : dialogSnapshot.sprites()) {
            String name = s.getMemberName();
            if (name == null) continue;
            if (name.contains("modal_modal")) modal = s;
            else if (name.contains("error_bg_a")) bgA = s;
            else if (name.contains("error_bg_b")) bgB = s;
            else if (name.contains("error_bg_c")) bgC = s;
            else if (name.contains("error_title")) title = s;
            else if (name.contains("error_text")) text = s;
            else if (name.contains("error_close")) close = s;
        }

        // --- Test 1: Modal overlay must be DARK (foreColor-tinted to black) ---
        if (modal != null && modal.getBakedBitmap() != null) {
            int avgColor = averageColor(modal.getBakedBitmap());
            int brightness = brightness(avgColor);
            if (brightness < 30) {
                System.out.printf("  PASS: Modal overlay is dark (brightness=%d, blend=%d)%n", brightness, modal.getBlend());
                passed++;
            } else {
                System.out.printf("  FAIL: Modal overlay should be dark but brightness=%d (expected <30)%n", brightness);
                failed++;
            }
        } else {
            System.out.println("  FAIL: Modal overlay sprite not found");
            failed++;
        }

        // --- Test 2: bg_b (border) must be DARK ---
        if (bgB != null && bgB.getBakedBitmap() != null) {
            int avgColor = averageColor(bgB.getBakedBitmap());
            int brightness = brightness(avgColor);
            int nonWhite = countNonWhite(bgB.getBakedBitmap());
            int total = bgB.getBakedBitmap().getWidth() * bgB.getBakedBitmap().getHeight();
            if (brightness < 30 && nonWhite > total * 0.9) {
                System.out.printf("  PASS: Border (bg_b) is dark (brightness=%d, %d/%d non-white, blend=%d)%n",
                    brightness, nonWhite, total, bgB.getBlend());
                passed++;
            } else {
                System.out.printf("  FAIL: Border (bg_b) should be dark but brightness=%d, %d/%d non-white%n",
                    brightness, nonWhite, total);
                failed++;
            }
        } else {
            System.out.println("  FAIL: Border sprite (bg_b) not found");
            failed++;
        }

        // --- Test 3: bg_c (content area) must be WHITE ---
        if (bgC != null && bgC.getBakedBitmap() != null) {
            int avgColor = averageColor(bgC.getBakedBitmap());
            int brightness = brightness(avgColor);
            if (brightness > 240) {
                System.out.printf("  PASS: Content area (bg_c) is white (brightness=%d, blend=%d)%n",
                    brightness, bgC.getBlend());
                passed++;
            } else {
                System.out.printf("  FAIL: Content area (bg_c) should be white but brightness=%d%n", brightness);
                failed++;
            }
        } else {
            System.out.println("  FAIL: Content area sprite (bg_c) not found");
            failed++;
        }

        // --- Test 4: Text elements must exist and use ink=36 (no colorization) ---
        if (title != null && title.getInk() == 36) {
            System.out.printf("  PASS: Title uses ink=36 (Background Transparent), blend=%d%n", title.getBlend());
            passed++;
        } else {
            System.out.printf("  FAIL: Title missing or wrong ink (expected ink=36, got %s)%n",
                title != null ? title.getInk() : "null");
            failed++;
        }

        if (text != null && text.getInk() == 36) {
            System.out.printf("  PASS: Text uses ink=36 (Background Transparent), blend=%d%n", text.getBlend());
            passed++;
        } else {
            System.out.printf("  FAIL: Text missing or wrong ink (expected ink=36, got %s)%n",
                text != null ? text.getInk() : "null");
            failed++;
        }

        if (close != null) {
            System.out.printf("  PASS: Close button present (ink=%d, blend=%d)%n", close.getInk(), close.getBlend());
            passed++;
        } else {
            System.out.println("  FAIL: Close button (error_close) not found");
            failed++;
        }

        // --- Test 5: Composite pixel check - dialog area must be lighter than modal area ---
        System.out.println("\n--- Composite rendering check ---");
        int stageW = dialogSnapshot.stageWidth() > 0 ? dialogSnapshot.stageWidth() : 720;
        int stageH = dialogSnapshot.stageHeight() > 0 ? dialogSnapshot.stageHeight() : 540;
        BufferedImage composite = renderComposite(dialogSnapshot, stageW, stageH);

        // Check a point inside the dialog content area (should be bright/white)
        int dialogCenterX = bgC != null ? bgC.getX() + bgC.getWidth() / 2 : 360;
        int dialogCenterY = bgC != null ? bgC.getY() + bgC.getHeight() / 2 : 270;
        int dialogPixel = composite.getRGB(dialogCenterX, dialogCenterY);
        int dialogBrightness = brightness(dialogPixel);

        // Check a point outside the dialog but inside the modal (should be dark)
        int outsideX = 50, outsideY = 50;
        int outsidePixel = composite.getRGB(outsideX, outsideY);
        int outsideBrightness = brightness(outsidePixel);

        if (dialogBrightness > outsideBrightness + 30) {
            System.out.printf("  PASS: Dialog area (%d) is brighter than modal area (%d) - Δ=%d%n",
                dialogBrightness, outsideBrightness, dialogBrightness - outsideBrightness);
            passed++;
        } else {
            System.out.printf("  FAIL: Dialog area (%d) should be much brighter than modal area (%d)%n",
                dialogBrightness, outsideBrightness);
            failed++;
        }

        // Save composite PNG
        Path pngPath = Path.of("player-core/build/render-window-border.png");
        Files.createDirectories(pngPath.getParent());
        javax.imageio.ImageIO.write(composite, "PNG", pngPath.toFile());
        System.out.println("\n  Saved composite to: " + pngPath.toAbsolutePath());

        // --- Summary ---
        System.out.printf("%n=== SUMMARY: %d passed, %d failed ===%n%n", passed, failed);

        if (failed > 0) {
            System.out.println("FAILED - Window border rendering is broken");
            System.exit(1);
        } else {
            System.out.println("ALL TESTS PASSED - Window borders render correctly");
        }
    }

    private static int averageColor(Bitmap bmp) {
        if (bmp == null) return 0xFFFFFF;
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                if ((pixel >>> 24) > 10) {
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }
            }
        }
        if (count == 0) return 0;
        return (int)(r / count) << 16 | (int)(g / count) << 8 | (int)(b / count);
    }

    private static int brightness(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3;
    }

    private static int countNonWhite(Bitmap bmp) {
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

    private static BufferedImage renderComposite(FrameSnapshot snapshot, int w, int h) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

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
