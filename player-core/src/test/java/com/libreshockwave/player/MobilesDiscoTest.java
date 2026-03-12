package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import com.libreshockwave.player.render.pipeline.RenderSprite;
import com.libreshockwave.player.render.pipeline.SpriteBaker;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.vm.DebugConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Mobiles Disco DCR test: load the movie, advance past splash to login,
 * render screenshots, and produce pixel diff against reference.
 *
 * Run: ./gradlew :player-core:runMobilesDiscoTest
 * Output: player-core/build/mobiles-disco-test/
 */
public class MobilesDiscoTest {

    // Load from the htdocs path (same file served at http://localhost/mobiles/dcr_0519b_e/...)
    private static final String MOVIE_PATH = "C:/xampp/htdocs/mobiles/dcr_0519b_e/20000201_mobiles_disco.dcr";
    private static final String OUTPUT_DIR = "build/mobiles-disco-test";
    private static final String REFERENCE_PATH = "docs/mobiles-disco-reference.png";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        System.out.println("=== Loading Mobiles Disco DCR ===");
        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        System.out.println("Director Version: " + file.getConfig().directorVersion());
        System.out.println("Stage: " + file.getStageWidth() + "x" + file.getStageHeight());

        Player player = new Player(file);
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.getNetManager().setBasePath("http://localhost/mobiles/dcr_0519b_e/");

        // Enable debug on frame context and behavior manager
        player.getFrameContext().setDebugEnabled(true);

        // Track errors
        List<String> errors = new ArrayList<>();
        player.setErrorListener((msg, ex) -> {
            String err = "ERROR: " + msg;
            if (ex != null) err += " - " + ex.getMessage();
            errors.add(err);
            System.err.println(err);
        });

        player.preloadAllCasts();

        // Dump cast lib info
        CastLibManager clm = player.getCastLibManager();
        System.out.println("\n=== Cast Libraries ===");
        for (int i = 1; i <= 10; i++) {
            var cl = clm.getCastLib(i);
            if (cl != null) {
                System.out.printf("  castLib %d '%s': loaded=%s external=%s scripts=%d%n",
                        i, cl.getName(), cl.isLoaded(), cl.isExternal(),
                        cl.isLoaded() ? cl.getAllScripts().size() : -1);
            }
        }

        System.out.println("\n=== Starting playback ===");
        player.play();
        System.out.println("Frame after play(): " + player.getCurrentFrame());

        StageRenderer renderer = player.getStageRenderer();
        SpriteBaker baker = new SpriteBaker(player.getBitmapCache(), clm, player);

        // Track frame transitions
        int maxTicks = 300;
        Map<Integer, Integer> frameVisits = new LinkedHashMap<>();
        int lastFrame = -1;

        for (int tick = 0; tick < maxTicks; tick++) {
            int frameBefore = player.getCurrentFrame();
            boolean alive = player.tick();
            if (!alive) {
                System.out.println("Player stopped at tick " + tick);
                break;
            }
            int frameAfter = player.getCurrentFrame();

            // Track frame transitions
            if (frameBefore != frameAfter) {
                System.out.printf("FRAME CHANGE: %d -> %d at tick %d%n", frameBefore, frameAfter, tick);
            }
            frameVisits.merge(frameAfter, 1, Integer::sum);

            // Log periodically
            if (tick < 10 || tick % 50 == 0) {
                List<RenderSprite> sprites = renderer.getSpritesForFrame(frameAfter);
                System.out.printf("Tick %d: frame=%d sprites=%d%n", tick, frameAfter, sprites.size());
            }

            Thread.sleep(5);
        }

        int finalFrame = player.getCurrentFrame();
        System.out.println("\n=== Frame Visit Summary ===");
        for (var entry : frameVisits.entrySet()) {
            System.out.printf("  Frame %d: %d ticks%n", entry.getKey(), entry.getValue());
        }
        System.out.println("Final frame: " + finalFrame);

        // Capture screenshot at current frame
        System.out.println("\n=== Rendering ===");
        int frame = player.getCurrentFrame();
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        System.out.printf("Frame %d: %d sprites%n", frame, sprites.size());

        // Dump all sprites
        for (RenderSprite s : sprites) {
            String memberInfo = s.getMemberName() != null ? s.getMemberName() : "null";
            if (s.getCastMember() != null) {
                memberInfo += " [" + s.getCastMember().memberType() + "]";
            }
            System.out.printf("  ch=%d z=%d type=%s pos=(%d,%d) %dx%d ink=%d(%s) blend=%d visible=%s member=%s%n",
                    s.getChannel(), s.getLocZ(), s.getType(),
                    s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                    s.getInk(), s.getInkMode(), s.getBlend(), s.isVisible(),
                    memberInfo);
        }

        // Warmup bake (trigger bitmap decoding), wait, then real bake
        captureSnapshot(renderer, frame, "warmup", baker);
        Thread.sleep(3000);
        FrameSnapshot snapshot = captureSnapshot(renderer, frame, "final", baker);

        // Bake diagnostic
        System.out.println("\n=== Bake Diagnostic ===");
        for (RenderSprite bs : snapshot.sprites()) {
            var bk = bs.getBakedBitmap();
            String bakeInfo = bk != null ? bk.getWidth() + "x" + bk.getHeight() : "NULL";
            String fileInfo = "noMember";
            if (bs.getCastMember() != null) {
                var mf = bs.getCastMember().file();
                fileInfo = "file=" + (mf != null ? "yes" : "NULL") +
                        " isBitmap=" + bs.getCastMember().isBitmap() +
                        " id=" + bs.getCastMember().id().value();
                // Try manual decode
                if (mf != null && bs.getCastMember().isBitmap()) {
                    try {
                        var decoded = mf.decodeBitmap(bs.getCastMember());
                        fileInfo += " decode=" + (decoded.isPresent() ? decoded.get().getWidth() + "x" + decoded.get().getHeight() : "EMPTY");
                    } catch (Exception e) {
                        fileInfo += " decodeErr=" + e.getMessage();
                    }
                }
            }
            System.out.printf("  ch=%d baked=%s type=%s member=%s [%s]%n",
                    bs.getChannel(), bakeInfo, bs.getType(), bs.getMemberName(), fileInfo);
        }

        // Render and save
        Bitmap rendered = snapshot.renderFrame();
        BufferedImage renderedImg = rendered.toBufferedImage();
        File outputFile = new File(OUTPUT_DIR + "/mobiles_disco.png");
        ImageIO.write(renderedImg, "PNG", outputFile);
        System.out.println("Rendered image saved: " + outputFile.getAbsolutePath());
        System.out.printf("Image size: %dx%d%n", renderedImg.getWidth(), renderedImg.getHeight());

        // Also capture splash frame if we visited it
        // (already captured as final if we're still on splash)

        // Pixel diff against reference
        File refFile = new File(REFERENCE_PATH);
        if (refFile.exists()) {
            System.out.println("\n=== Pixel Comparison vs Reference ===");
            BufferedImage refImg = ImageIO.read(refFile);
            pixelDiff(renderedImg, refImg, OUTPUT_DIR);
        } else {
            System.out.println("\nNo reference image at " + refFile.getAbsolutePath());
            System.out.println("To enable pixel comparison, place a reference screenshot at: " + REFERENCE_PATH);
        }

        // Error summary
        System.out.println("\n=== Error Summary ===");
        System.out.println("Total errors: " + errors.size());
        // Deduplicate errors
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        for (String err : errors) {
            // Truncate long error messages for grouping
            String key = err.length() > 120 ? err.substring(0, 120) : err;
            errorCounts.merge(key, 1, Integer::sum);
        }
        for (var entry : errorCounts.entrySet()) {
            System.out.printf("  [x%d] %s%n", entry.getValue(), entry.getKey());
        }

        player.shutdown();
    }

    private static FrameSnapshot captureSnapshot(StageRenderer renderer, int frame, String state, SpriteBaker baker) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        List<RenderSprite> baked = baker.bakeSprites(sprites);
        renderer.setLastBakedSprites(baked);
        String debug = String.format("Frame %d | %s", frame, state);
        return new FrameSnapshot(frame, renderer.getStageWidth(), renderer.getStageHeight(),
                renderer.getBackgroundColor(), List.copyOf(baked), debug,
                renderer.hasStageImage() ? renderer.getStageImage() : null);
    }

    private static void pixelDiff(BufferedImage rendered, BufferedImage reference, String outputDir) throws Exception {
        int w = Math.min(rendered.getWidth(), reference.getWidth());
        int h = Math.min(rendered.getHeight(), reference.getHeight());

        System.out.printf("Rendered:  %dx%d%n", rendered.getWidth(), rendered.getHeight());
        System.out.printf("Reference: %dx%d%n", reference.getWidth(), reference.getHeight());

        int totalPixels = w * h;
        int exactMatch = 0;
        int closeMatch = 0;
        int farOff = 0;
        long totalDelta = 0;

        int gridW = 20, gridH = 15;
        int cellW = Math.max(1, w / gridW), cellH = Math.max(1, h / gridH);
        int[][] gridDiff = new int[gridH][gridW];
        int[][] gridTotal = new int[gridH][gridW];

        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p1 = rendered.getRGB(x, y);
                int p2 = reference.getRGB(x, y);

                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;

                int dr = Math.abs(r1 - r2);
                int dg = Math.abs(g1 - g2);
                int db = Math.abs(b1 - b2);
                int maxDiff = Math.max(dr, Math.max(dg, db));
                totalDelta += dr + dg + db;

                int gx = Math.min(x / cellW, gridW - 1);
                int gy = Math.min(y / cellH, gridH - 1);
                gridTotal[gy][gx]++;

                if (maxDiff == 0) {
                    exactMatch++;
                    diff.setRGB(x, y, 0x000000);
                } else if (maxDiff <= 5) {
                    closeMatch++;
                    diff.setRGB(x, y, 0x003300);
                } else {
                    farOff++;
                    gridDiff[gy][gx]++;
                    int vis = Math.min(255, maxDiff * 3);
                    diff.setRGB(x, y, (vis << 16) | (vis / 3 << 8));
                }
            }
        }

        System.out.printf("Total pixels:   %d%n", totalPixels);
        System.out.printf("Exact match:    %d (%.2f%%)%n", exactMatch, 100.0 * exactMatch / totalPixels);
        System.out.printf("Close (±5):     %d (%.2f%%)%n", closeMatch, 100.0 * closeMatch / totalPixels);
        System.out.printf("Different (>5): %d (%.2f%%)%n", farOff, 100.0 * farOff / totalPixels);
        System.out.printf("Avg delta/px:   %.2f%n", (double) totalDelta / totalPixels);

        // Grid heatmap
        System.out.println("\nDifference Heatmap:");
        for (int gy = 0; gy < gridH; gy++) {
            StringBuilder sb = new StringBuilder();
            for (int gx = 0; gx < gridW; gx++) {
                int total = gridTotal[gy][gx];
                int bad = gridDiff[gy][gx];
                double pct = total > 0 ? 100.0 * bad / total : 0;
                if (pct == 0) sb.append("  .  ");
                else if (pct < 1) sb.append("  o  ");
                else if (pct < 5) sb.append("  *  ");
                else if (pct < 20) sb.append(" **  ");
                else if (pct < 50) sb.append(" *** ");
                else sb.append("*****");
            }
            System.out.printf("Row %2d: %s%n", gy, sb);
        }

        // Save diff
        File diffFile = new File(outputDir + "/pixel_diff.png");
        ImageIO.write(diff, "PNG", diffFile);
        System.out.println("Diff image saved: " + diffFile.getAbsolutePath());

        // Sample differing pixels
        System.out.println("\nSample differing pixels (first 20 with diff>20):");
        int sampled = 0;
        for (int y = 0; y < h && sampled < 20; y++) {
            for (int x = 0; x < w && sampled < 20; x++) {
                int p1 = rendered.getRGB(x, y);
                int p2 = reference.getRGB(x, y);
                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;
                int maxD = Math.max(Math.abs(r1 - r2), Math.max(Math.abs(g1 - g2), Math.abs(b1 - b2)));
                if (maxD > 20) {
                    System.out.printf("  (%3d,%3d): rendered=(%3d,%3d,%3d) ref=(%3d,%3d,%3d)%n",
                            x, y, r1, g1, b1, r2, g2, b2);
                    sampled++;
                }
            }
        }
    }
}
