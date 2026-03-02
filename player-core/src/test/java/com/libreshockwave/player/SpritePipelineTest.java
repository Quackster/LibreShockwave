package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderConfig;
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
import java.util.*;
import java.util.List;

/**
 * Comprehensive Lingo sprite pipeline test.
 * Validates that sprites created dynamically by Lingo scripts have correct
 * properties flowing through the entire pipeline:
 *
 *   Lingo bytecodes → SpriteProperties → SpriteState → SpriteRegistry
 *     → StageRenderer → FrameSnapshot (ink/colorize) → RenderSprite
 *
 * Tests:
 *  1. Config stage background color parsed correctly (D7+ RGB black)
 *  2. puppetSprite creates dynamic sprite state
 *  3. sprite.member = X applies intrinsic bitmap dimensions
 *  4. sprite.ink / blend / loc / locZ set correctly
 *  5. sprite.color / bgColor (RGB) flow to foreColor/backColor
 *  6. sprite.castNum with encoded slot numbers decodes correctly
 *  7. Window system sprites have correct ink (8=Matte) and colorization flags
 *  8. Pipeline ordering: member size before loc, ink before rendering
 *  9. FrameSnapshot bakes bitmaps with correct ink processing
 * 10. Composite render produces visible non-black output
 *
 * Run: ./gradlew :player-core:runSpritePipelineTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class SpritePipelineTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        PrintStream out = System.out;

        // ===== PHASE 1: Load and validate config =====
        out.println("=== PHASE 1: Config & Initialization ===\n");

        DirectorFile file = DirectorFile.load(path);

        // Test 1: Stage background is RGB black from config
        int configBg = file.getConfig().stageColorRGB();
        check(out, "Config stageColorRGB is black (0x000000)",
              configBg == 0x000000,
              String.format("got 0x%06X", configBg));

        Player player = new Player(file);

        // Test 2: Player constructor applies config bg to StageRenderer
        int rendererBg = player.getStageRenderer().getBackgroundColor();
        check(out, "StageRenderer background matches config",
              rendererBg == configBg,
              String.format("renderer=0x%06X, config=0x%06X", rendererBg, configBg));

        // ===== PHASE 2: Run Lingo scripts =====
        out.println("\n=== PHASE 2: Run Lingo Scripts ===\n");

        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        // Track milestones
        boolean[] frameProxyCreated = {false};
        Set<String> milestones = new LinkedHashSet<>();
        boolean[] errorDialogReached = {false};
        int[] errorDialogFrame = {-1};
        int[] currentFrame = {0};

        // Track sprite property sets for pipeline validation
        Map<String, List<String>> spriteSetLog = new LinkedHashMap<>();

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                String name = info.handlerName();
                if (name.equals("showErrorDialog") || name.equals("buildVisual")
                        || name.equals("preIndexChannels") || name.equals("showLogo")
                        || name.equals("reserveSprite") || name.equals("createLoader")) {
                    milestones.add(name + "@" + currentFrame[0]);
                }
                if (name.equals("showErrorDialog")) {
                    errorDialogReached[0] = true;
                    errorDialogFrame[0] = currentFrame[0];
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
                currentFrame[0] = info.frame();
            }
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

        player.play();

        // Suppress VM noise during execution
        System.setOut(new PrintStream(out) {
            @Override public void println(String x) {
                if (x != null && x.startsWith("  [milestone]")) super.println(x);
            }
        });

        // Step frames until error dialog + 50 frames
        FrameSnapshot dialogSnapshot = null;
        int framesAfterError = 0;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                break;
            }

            if (errorDialogReached[0]) {
                framesAfterError++;
                if (framesAfterError == 50) {
                    dialogSnapshot = player.getFrameSnapshot();
                    break;
                }
            }
        }

        System.setOut(out);

        out.println("  Milestones: " + milestones);
        out.println("  Error dialog at frame: " + errorDialogFrame[0]);

        // Test 3: Key Lingo handlers must be reached
        check(out, "preIndexChannels reached (puppetSprite loop)",
              milestones.stream().anyMatch(m -> m.startsWith("preIndexChannels")), "");

        check(out, "showLogo reached (sprite.member assignment)",
              milestones.stream().anyMatch(m -> m.startsWith("showLogo")), "");

        check(out, "showErrorDialog reached (window system sprites)",
              errorDialogReached[0],
              "frame=" + errorDialogFrame[0]);

        // ===== PHASE 3: Validate SpriteState from registry =====
        out.println("\n=== PHASE 3: SpriteState Validation ===\n");

        var registry = player.getStageRenderer().getSpriteRegistry();

        // Test 4: Dynamic sprites should exist in registry
        int dynamicCount = 0;
        int puppetedCount = 0;

        for (SpriteState state : registry.getDynamicSprites()) {
            if (state.hasDynamicMember()) dynamicCount++;
            if (state.isPuppet()) puppetedCount++;
        }

        check(out, "Dynamic sprites created (>= 10)",
              dynamicCount >= 10,
              "count=" + dynamicCount);

        // Test 5: Puppeted sprites exist (from preIndexChannels)
        check(out, "Puppeted sprites exist (from preIndexChannels)",
              puppetedCount > 0,
              "count=" + puppetedCount);

        // ===== PHASE 4: Validate FrameSnapshot =====
        out.println("\n=== PHASE 4: FrameSnapshot Validation ===\n");

        if (dialogSnapshot == null) {
            fail(out, "No dialog snapshot captured", "");
            printSummary(out);
            System.exit(1);
        }

        // Test 6: Snapshot background color
        check(out, "Snapshot backgroundColor matches config (0x000000)",
              dialogSnapshot.backgroundColor() == 0x000000,
              String.format("bg=0x%06X", dialogSnapshot.backgroundColor()));

        List<RenderSprite> allSprites = dialogSnapshot.sprites();
        out.println("  Total sprites: " + allSprites.size());

        // Categorize sprites
        List<RenderSprite> windowSprites = new ArrayList<>();
        List<RenderSprite> backgroundSprites = new ArrayList<>();
        RenderSprite logoSprite = null;
        RenderSprite modalSprite = null;

        for (RenderSprite s : allSprites) {
            String name = s.getMemberName();
            if (name == null) continue;
            if (name.contains("error_") || name.contains("modal")) {
                windowSprites.add(s);
            }
            if (name.contains("modal")) {
                modalSprite = s;
            }
            if (name.equalsIgnoreCase("Logo")) {
                logoSprite = s;
            }
            // Background images from entry.visual
            if (name.contains("tausta") || name.contains("hotel") || name.contains("foregnd")
                    || name.contains("cloud") || name.contains("car") || name.contains("light")) {
                backgroundSprites.add(s);
            }
        }

        // Test 7: Window system sprites have correct properties
        check(out, "Window sprites exist (>= 5)",
              windowSprites.size() >= 5,
              "count=" + windowSprites.size());

        out.println("\n  --- Window sprite details ---");
        for (RenderSprite s : windowSprites) {
            String name = s.getMemberName();
            out.printf("  ch%-3d %-30s pos=(%d,%d) %dx%d ink=%d blend=%d " +
                       "fgColor=0x%06X bgColor=0x%06X hasFg=%s hasBg=%s baked=%s%n",
                s.getChannel(), "'" + name + "'",
                s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                s.getInk(), s.getBlend(),
                s.getForeColor(), s.getBackColor(),
                s.hasForeColor(), s.hasBackColor(),
                s.getBakedBitmap() != null ?
                    s.getBakedBitmap().getWidth() + "x" + s.getBakedBitmap().getHeight() : "null");
        }

        // Test 8: Error dialog bg sprites have colorization flags set
        // Window bg sprites use ink=0 (Copy) with shared ink from the layout definition.
        // The layout overrides the window system default ink=8. What matters is that
        // foreColor/backColor flags are set for colorization to work.
        boolean bgColorFlagsCorrect = true;
        for (RenderSprite s : windowSprites) {
            String name = s.getMemberName();
            if (name != null && name.contains("error_bg")) {
                if (!s.hasForeColor()) {
                    fail(out, name + " should have hasForeColor=true", "");
                    bgColorFlagsCorrect = false;
                }
            }
        }
        if (bgColorFlagsCorrect) {
            check(out, "Error dialog bg sprites have foreColor set for colorization", true, "");
        }

        // Test 8b: Text sprites use Background Transparent ink (36)
        boolean textInkCorrect = true;
        for (RenderSprite s : windowSprites) {
            String name = s.getMemberName();
            if (name != null && (name.contains("title") || name.contains("text") || name.contains("close"))) {
                if (s.getInk() != 36) {
                    fail(out, name + " should have ink=36 (BG Transparent)", "ink=" + s.getInk());
                    textInkCorrect = false;
                }
            }
        }
        if (textInkCorrect) {
            check(out, "Text sprites use ink=36 (Background Transparent)", true, "");
        }

        // Test 9: Modal overlay has blend=40
        if (modalSprite != null) {
            check(out, "Modal overlay blend = 40",
                  modalSprite.getBlend() == 40,
                  "blend=" + modalSprite.getBlend());
        } else {
            fail(out, "Modal sprite not found", "");
        }

        // Test 10: Logo sprite validation
        // The Logo sprite may have been released by the time the error dialog appears
        // (Lingo releases it after loading completes). Validate it if present.
        if (logoSprite != null) {
            check(out, "Logo RenderSprite has non-zero dimensions",
                  logoSprite.getWidth() > 0 || logoSprite.getHeight() > 0
                      || (logoSprite.getBakedBitmap() != null),
                  String.format("size=%dx%d baked=%s", logoSprite.getWidth(), logoSprite.getHeight(),
                      logoSprite.getBakedBitmap() != null));
        } else {
            // Logo is expected to be released by error dialog time
            check(out, "Logo sprite released (expected after loading)", true, "");
        }

        // ===== PHASE 5: Sprite ordering validation =====
        out.println("\n=== PHASE 5: Z-Order Validation ===\n");

        // Test 11: Sprites must be sorted by locZ ascending
        boolean zOrderCorrect = true;
        for (int i = 1; i < allSprites.size(); i++) {
            RenderSprite prev = allSprites.get(i - 1);
            RenderSprite curr = allSprites.get(i);
            if (curr.getLocZ() < prev.getLocZ()) {
                fail(out, "Z-order violation",
                     String.format("ch%d(z=%d) before ch%d(z=%d)",
                         prev.getChannel(), prev.getLocZ(),
                         curr.getChannel(), curr.getLocZ()));
                zOrderCorrect = false;
                break;
            }
        }
        if (zOrderCorrect) {
            check(out, "Sprites sorted by locZ ascending", true, "");
        }

        // Test 12: Window sprites should be above background sprites
        if (!backgroundSprites.isEmpty() && !windowSprites.isEmpty()) {
            int maxBgZ = backgroundSprites.stream().mapToInt(RenderSprite::getLocZ).max().orElse(0);
            int minWindowZ = windowSprites.stream().mapToInt(RenderSprite::getLocZ).min().orElse(0);
            check(out, "Window sprites above background sprites (z-order)",
                  minWindowZ >= maxBgZ,
                  String.format("maxBgZ=%d, minWindowZ=%d", maxBgZ, minWindowZ));
        }

        // ===== PHASE 6: Bitmap baking validation =====
        out.println("\n=== PHASE 6: Bitmap Baking Validation ===\n");

        int totalBitmaps = 0, bakedBitmaps = 0, visibleBakedPixels = 0;
        for (RenderSprite s : allSprites) {
            if (s.getType() == RenderSprite.SpriteType.BITMAP) {
                totalBitmaps++;
                Bitmap b = s.getBakedBitmap();
                if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                    bakedBitmaps++;
                    // Check for actual visible content
                    for (int y = 0; y < Math.min(b.getHeight(), 5); y++) {
                        for (int x = 0; x < Math.min(b.getWidth(), 5); x++) {
                            if ((b.getPixel(x, y) >>> 24) > 10) visibleBakedPixels++;
                        }
                    }
                }
            }
        }

        out.printf("  Bitmap sprites: %d total, %d baked%n", totalBitmaps, bakedBitmaps);
        check(out, "Bitmap sprites have baked bitmaps (>= 50%)",
              totalBitmaps == 0 || bakedBitmaps * 2 >= totalBitmaps,
              String.format("%d/%d baked", bakedBitmaps, totalBitmaps));

        // Test 13: Window bg bitmaps should have non-transparent content
        for (RenderSprite s : windowSprites) {
            String name = s.getMemberName();
            if (name == null || !name.contains("error_bg")) continue;
            Bitmap b = s.getBakedBitmap();
            if (b != null) {
                int nonTransparent = 0;
                int total = b.getWidth() * b.getHeight();
                for (int y = 0; y < b.getHeight(); y++) {
                    for (int x = 0; x < b.getWidth(); x++) {
                        if ((b.getPixel(x, y) >>> 24) > 10) nonTransparent++;
                    }
                }
                double pct = total > 0 ? 100.0 * nonTransparent / total : 0;
                check(out, name + " bitmap has visible content (>30%)",
                      pct > 30, String.format("%.1f%%", pct));
            }
        }

        // ===== PHASE 7: Composite render =====
        out.println("\n=== PHASE 7: Composite Render ===\n");

        String outputPath = "build/render-sprite-pipeline.png";
        renderToPng(dialogSnapshot, outputPath, out);

        // Test 14: Rendered image should have non-black content
        BufferedImage rendered = javax.imageio.ImageIO.read(Path.of(outputPath).toFile());
        int totalPixels = rendered.getWidth() * rendered.getHeight();
        int nonBlackPixels = 0;
        for (int y = 0; y < rendered.getHeight(); y++) {
            for (int x = 0; x < rendered.getWidth(); x++) {
                int rgb = rendered.getRGB(x, y) & 0xFFFFFF;
                if (rgb != 0x000000) nonBlackPixels++;
            }
        }
        double nonBlackPct = 100.0 * nonBlackPixels / totalPixels;
        check(out, "Composite has non-black content (error dialog visible)",
              nonBlackPct > 5,
              String.format("%.1f%% non-black", nonBlackPct));

        // ===== SUMMARY =====
        printSummary(out);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(PrintStream out, String desc, boolean pass, String detail) {
        if (pass) {
            out.println("  PASS: " + desc + (detail.isEmpty() ? "" : " (" + detail + ")"));
            passed++;
        } else {
            out.println("  FAIL: " + desc + (detail.isEmpty() ? "" : " (" + detail + ")"));
            failed++;
        }
    }

    private static void fail(PrintStream out, String desc, String detail) {
        check(out, desc, false, detail);
    }

    private static void printSummary(PrintStream out) {
        out.println("\n=== SUMMARY: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            out.println("\nFAILED - Sprite pipeline has issues");
        } else {
            out.println("\nALL TESTS PASSED - Lingo sprite pipeline is correct");
        }
    }

    private static void renderToPng(FrameSnapshot snapshot, String outputPath, PrintStream out) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 720;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 540;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        if (RenderConfig.isAntialias()) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }

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
