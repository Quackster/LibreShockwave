package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastMember;
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
 * Comprehensive window style rendering test that validates the error dialog
 * window matches Director's expected 1:1 rendering output.
 *
 * Validates three critical rendering fixes:
 * 1. image.setPixel() support — "null" member must have BLACK pixel
 * 2. Correct colorization direction — BLACK→foreColor, WHITE→backColor
 * 3. copyPixels #color/#bgColor param support — bg_c gets WHITE via #color remap
 *
 * Expected Director layout for error.window:
 * - bg_a: shadow (1x1 BLACK, ink=0, blend=30, foreColor=BLACK)
 * - bg_b: border (340x140 BLACK, ink=0, blend=60, foreColor=BLACK)
 * - bg_c: content (338x138, ink=0, blend=60, foreColor=WHITE → WHITE via #color remap)
 * - error_title: text (ink=36, blend=100)
 * - error_text: text (ink=36, blend=100)
 * - error_close: text (ink=36, blend=100)
 * - modal: overlay (1x1 BLACK, ink=0, blend=40, foreColor=BLACK)
 *
 * Run: ./gradlew :player-core:runWindowStyleTest
 */
public class WindowStyleTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;

    private static volatile int currentFrame = 0;
    private static volatile boolean showErrorDialogReached = false;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        System.out.println("=== WindowStyleTest: Comprehensive window rendering validation ===\n");
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

        // ============= TEST 1: "null" member setPixel fix =============
        System.out.println("\n--- Test 1: image.setPixel() fix - 'null' member must be BLACK ---");
        CastMember nullMember = null;
        for (var entry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (!castLib.isLoaded()) continue;
            var found = castLib.getMemberByName("null");
            if (found != null) { nullMember = found; break; }
        }
        if (nullMember != null && nullMember.getBitmap() != null) {
            Bitmap nullBmp = nullMember.getBitmap();
            int pixel = nullBmp.getPixel(0, 0);
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            check("null member is 1x1", nullBmp.getWidth() == 1 && nullBmp.getHeight() == 1);
            check("null member pixel is BLACK (0,0,0)", r == 0 && g == 0 && b == 0);
        } else {
            check("null member exists with bitmap", false);
        }

        // Collect key sprites
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

        // ============= TEST 2: Colorization direction =============
        System.out.println("\n--- Test 2: Colorization direction - BLACK->foreColor, WHITE->backColor ---");
        // Verify by checking bg_b: BLACK buffer + foreColor=BLACK → should stay BLACK (identity)
        if (bgB != null && bgB.getBakedBitmap() != null) {
            CastMember bgBMember = bgB.getDynamicMember();
            if (bgBMember != null && bgBMember.getBitmap() != null) {
                int rawPixel = bgBMember.getBitmap().getPixel(0, 0) & 0xFFFFFF;
                int bakedPixel = bgB.getBakedBitmap().getPixel(0, 0) & 0xFFFFFF;
                check("bg_b RAW buffer is BLACK (from 'null' member)", rawPixel == 0x000000);
                check("bg_b BAKED stays BLACK after colorization (identity: BLACK->foreColor=BLACK)",
                    bakedPixel == 0x000000);
            }
        }
        // Verify bg_c: buffer gets WHITE via copyPixels #color, foreColor=WHITE → identity
        if (bgC != null && bgC.getBakedBitmap() != null) {
            CastMember bgCMember = bgC.getDynamicMember();
            if (bgCMember != null && bgCMember.getBitmap() != null) {
                int rawPixel = bgCMember.getBitmap().getPixel(0, 0) & 0xFFFFFF;
                int bakedPixel = bgC.getBakedBitmap().getPixel(0, 0) & 0xFFFFFF;
                check("bg_c RAW buffer is WHITE (via copyPixels #color remap)", rawPixel == 0xFFFFFF);
                check("bg_c BAKED stays WHITE (foreColor=WHITE, identity)", bakedPixel == 0xFFFFFF);
            }
        }

        // ============= TEST 3: Sprite properties match Director layout =============
        System.out.println("\n--- Test 3: Sprite properties match Director error.window layout ---");

        // bg_b: border - ink=0, blend=60, foreColor=BLACK
        if (bgB != null) {
            check("bg_b ink=0 (Copy)", bgB.getInk() == 0);
            check("bg_b blend=60", bgB.getBlend() == 60);
            check("bg_b foreColor=BLACK (0x000000)", bgB.getForeColor() == 0x000000);
            check("bg_b size=340x140", bgB.getWidth() == 340 && bgB.getHeight() == 140);
        } else {
            check("bg_b sprite found", false);
        }

        // bg_c: content - ink=0, blend=60, foreColor=WHITE
        if (bgC != null) {
            check("bg_c ink=0 (Copy)", bgC.getInk() == 0);
            check("bg_c blend=60", bgC.getBlend() == 60);
            check("bg_c foreColor=WHITE (0xFFFFFF)", bgC.getForeColor() == 0xFFFFFF);
            check("bg_c size=338x138", bgC.getWidth() == 338 && bgC.getHeight() == 138);
        } else {
            check("bg_c sprite found", false);
        }

        // ============= TEST 4: Text elements =============
        System.out.println("\n--- Test 4: Text elements use ink=36 (Background Transparent) ---");
        check("error_title exists with ink=36", title != null && title.getInk() == 36);
        check("error_text exists with ink=36", text != null && text.getInk() == 36);
        check("error_close exists with ink=36", close != null && close.getInk() == 36);
        check("error_title blend=100", title != null && title.getBlend() == 100);
        check("error_text blend=100", text != null && text.getBlend() == 100);
        check("error_close blend=100", close != null && close.getBlend() == 100);

        // Text bitmaps should have visible text content (not all transparent)
        if (title != null && title.getBakedBitmap() != null) {
            int opaquePixels = countOpaquePixels(title.getBakedBitmap());
            check("error_title has visible text pixels (>50 opaque)", opaquePixels > 50);
        }
        if (text != null && text.getBakedBitmap() != null) {
            int opaquePixels = countOpaquePixels(text.getBakedBitmap());
            check("error_text has visible text pixels (>100 opaque)", opaquePixels > 100);
        }

        // ============= TEST 5: Modal overlay =============
        System.out.println("\n--- Test 5: Modal overlay ---");
        if (modal != null) {
            check("modal ink=0 (Copy)", modal.getInk() == 0);
            check("modal blend=40", modal.getBlend() == 40);
            check("modal foreColor=BLACK (0x000000)", modal.getForeColor() == 0x000000);
            if (modal.getBakedBitmap() != null) {
                int brightness = averageBrightness(modal.getBakedBitmap());
                check("modal bitmap is dark (brightness < 30)", brightness < 30);
            }
        } else {
            check("modal sprite found", false);
        }

        // ============= TEST 6: bg_b renders DARK, bg_c renders WHITE =============
        System.out.println("\n--- Test 6: Final rendered pixel colors ---");
        if (bgB != null && bgB.getBakedBitmap() != null) {
            int brightness = averageBrightness(bgB.getBakedBitmap());
            int opaquePixels = countOpaquePixels(bgB.getBakedBitmap());
            int totalPixels = bgB.getBakedBitmap().getWidth() * bgB.getBakedBitmap().getHeight();
            check("bg_b border is dark (brightness<30)", brightness < 30);
            check("bg_b fully opaque", opaquePixels > totalPixels * 0.99);
        }
        if (bgC != null && bgC.getBakedBitmap() != null) {
            int brightness = averageBrightness(bgC.getBakedBitmap());
            int opaquePixels = countOpaquePixels(bgC.getBakedBitmap());
            int totalPixels = bgC.getBakedBitmap().getWidth() * bgC.getBakedBitmap().getHeight();
            check("bg_c content is white (brightness>240)", brightness > 240);
            check("bg_c fully opaque", opaquePixels > totalPixels * 0.99);
        }

        // ============= TEST 7: Composite pixel sampling =============
        System.out.println("\n--- Test 7: Composite rendering validation ---");
        int stageW = dialogSnapshot.stageWidth() > 0 ? dialogSnapshot.stageWidth() : 720;
        int stageH = dialogSnapshot.stageHeight() > 0 ? dialogSnapshot.stageHeight() : 540;
        BufferedImage composite = renderComposite(dialogSnapshot, stageW, stageH);

        // Dialog content center (should be bright: white content at blend=60 over dark)
        int dialogCX = bgC != null ? bgC.getX() + bgC.getWidth() / 2 : 360;
        int dialogCY = bgC != null ? bgC.getY() + bgC.getHeight() / 2 : 270;
        if (dialogCX >= 0 && dialogCX < stageW && dialogCY >= 0 && dialogCY < stageH) {
            int dialogBrightness = brightness(composite.getRGB(dialogCX, dialogCY));
            check("Dialog content area is bright (>100)", dialogBrightness > 100);
        }

        // Modal overlay area (should be darker than content)
        int outsideBrightness = brightness(composite.getRGB(50, 50));
        int dialogBrightness = dialogCX >= 0 && dialogCX < stageW ?
            brightness(composite.getRGB(dialogCX, dialogCY)) : 0;
        check("Dialog content brighter than modal area", dialogBrightness > outsideBrightness + 20);

        // Border area (between bg_b and bg_c edges): should be darker than content
        if (bgB != null && bgC != null) {
            int borderX = bgB.getX() + 1;
            int borderY = bgB.getY() + bgB.getHeight() / 2;
            if (borderX >= 0 && borderX < stageW && borderY >= 0 && borderY < stageH) {
                int borderBrightness = brightness(composite.getRGB(borderX, borderY));
                check("Border area is darker than content area", borderBrightness < dialogBrightness);
            }
        }

        // Save composite PNG
        Path pngPath = Path.of("player-core/build/render-window-style.png");
        Files.createDirectories(pngPath.getParent());
        javax.imageio.ImageIO.write(composite, "PNG", pngPath.toFile());
        System.out.println("\n  Saved composite to: " + pngPath.toAbsolutePath());

        // ============= SUMMARY =============
        System.out.printf("%n=== SUMMARY: %d passed, %d failed ===%n%n", passed, failed);
        if (failed > 0) {
            System.out.println("FAILED - Window styling needs fixes");
            System.exit(1);
        } else {
            System.out.println("ALL TESTS PASSED - Window styling matches Director 1:1");
        }
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + description);
            passed++;
        } else {
            System.out.println("  FAIL: " + description);
            failed++;
        }
    }

    private static int averageBrightness(Bitmap bmp) {
        if (bmp == null) return 0;
        long total = 0;
        int count = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                if ((pixel >>> 24) > 10) {
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
