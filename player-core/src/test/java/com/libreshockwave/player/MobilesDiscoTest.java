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
    private static final String REFERENCE_PATH = "../docs/mobiles-disco-reference.png";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        System.out.println("=== Loading Mobiles Disco DCR ===");
        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        System.out.println("Director Version: " + file.getConfig().directorVersion());
        System.out.println("Stage: " + file.getStageWidth() + "x" + file.getStageHeight());

        Player player = new Player(file);
        // Use SimpleTextRenderer (TeaVM-compatible) — AWT is not available in WASM
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.getNetManager().setBasePath("http://localhost/mobiles/dcr_0519b_e/");

        // Track errors
        List<String> errors = new ArrayList<>();
        player.setErrorListener((msg, ex) -> {
            String err = "ERROR: " + msg;
            if (ex != null) err += " - " + ex.getMessage();
            errors.add(err);
            System.err.println(err);
        });

        player.preloadAllCasts();

        // Verify Mac font bundle loaded
        var genevaFont = com.libreshockwave.player.cast.FontRegistry.getBitmapFont("Geneva", 12);
        System.out.println("Mac Geneva 12pt: " + (genevaFont != null
                ? genevaFont.getLineHeight() + "px lineHeight, space=" + genevaFont.getCharWidth(' ')
                : "NOT LOADED"));

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

        // === TEXT MEMBER DIAGNOSTIC ===
        System.out.println("\n=== TEXT MEMBER DIAGNOSTIC ===");
        int[] textMemberNums = {256, 257, 258, 259, 260, 261, 262, 263, 264, 265};
        for (int castLibNum = 1; castLibNum <= 2; castLibNum++) {
            for (int memberNum : textMemberNums) {
                var member = file.getCastMemberByNumber(castLibNum, memberNum);
                if (member == null) member = file.getCastMemberByIndex(castLibNum, memberNum);
                if (member == null) continue;
                boolean isText = member.isTextXtra() || member.isText();
                if (!isText) continue;
                // Parse dimensions from specificData @48=H(u32), @52=W(u32)
                byte[] sd = member.specificData();
                int sdH = 0, sdW = 0;
                if (sd != null && sd.length >= 56) {
                    sdH = ((sd[48]&0xFF)<<24)|((sd[49]&0xFF)<<16)|((sd[50]&0xFF)<<8)|(sd[51]&0xFF);
                    sdW = ((sd[52]&0xFF)<<24)|((sd[53]&0xFF)<<16)|((sd[54]&0xFF)<<8)|(sd[55]&0xFF);
                }
                // Parse XMED text
                var xmedText = file.getXmedTextForMember(member);
                String textPreview = xmedText != null && xmedText.text() != null
                        ? (xmedText.text().length() > 40 ? xmedText.text().substring(0, 40) + "..." : xmedText.text())
                        : "null";
                System.out.printf("  CL%d M%d id=%d: %dx%d font='%s' size=%d style=%d align='%s' text='%s'%n",
                        castLibNum, memberNum, member.id().value(), sdW, sdH,
                        xmedText != null ? xmedText.fontName() : "?",
                        xmedText != null ? xmedText.fontSize() : -1,
                        xmedText != null ? xmedText.fontStyle() : -1,
                        xmedText != null ? xmedText.alignment() : "?",
                        textPreview);
            }
        }
        System.out.println("=== END TEXT MEMBER DIAGNOSTIC ===\n");

        // === RAW SCORE BYTE DUMP for height investigation ===
        System.out.println("\n=== RAW SCORE CHANNEL BYTES (frame 97) ===");
        var scoreChunk = file.getScoreChunk();
        if (scoreChunk != null && scoreChunk.getRawChannelData() != null) {
            byte[] rawData = scoreChunk.getRawChannelData();
            int sprRecSize = scoreChunk.getSpriteRecordSize();
            int numCh = scoreChunk.getChannelCount();
            int frameSize = numCh * sprRecSize;
            int frameIdx = 97; // 0-indexed frame 97 = Director frame 98
            System.out.printf("  spriteRecordSize=%d numChannels=%d frameSize=%d totalDataLen=%d%n",
                    sprRecSize, numCh, frameSize, rawData.length);
            int[] channels = {17, 19}; // WELCOME (ch=17) and "Not registered" (ch=19)
            for (int ch : channels) {
                int pos = frameIdx * frameSize + ch * sprRecSize;
                if (pos + sprRecSize <= rawData.length) {
                    StringBuilder hex = new StringBuilder();
                    for (int b = 0; b < Math.min(sprRecSize, 48); b++) {
                        hex.append(String.format("%02X ", rawData[pos + b] & 0xFF));
                        if (b == 3 || b == 7 || b == 11 || b == 15 || b == 19 || b == 23 || b == 27) hex.append("| ");
                    }
                    // Parse the fields manually
                    int sprType = rawData[pos] & 0xFF;
                    int ink = rawData[pos+1] & 0x3F;
                    int fg = rawData[pos+2] & 0xFF;
                    int bg = rawData[pos+3] & 0xFF;
                    int castLib = ((rawData[pos+4]&0xFF)<<8) | (rawData[pos+5]&0xFF);
                    int castMem = ((rawData[pos+6]&0xFF)<<8) | (rawData[pos+7]&0xFF);
                    int posY = ((rawData[pos+12]&0xFF)<<8) | (rawData[pos+13]&0xFF);
                    int posX = ((rawData[pos+14]&0xFF)<<8) | (rawData[pos+15]&0xFF);
                    int height = ((rawData[pos+16]&0xFF)<<8) | (rawData[pos+17]&0xFF);
                    int width = ((rawData[pos+18]&0xFF)<<8) | (rawData[pos+19]&0xFF);
                    System.out.printf("  ch=%d raw: %s%n", ch, hex);
                    System.out.printf("  ch=%d parsed: sprType=%d ink=%d fg=%d bg=%d castLib=%d castMem=%d posY=%d posX=%d height=%d width=%d%n",
                            ch, sprType, ink, fg, bg, castLib, castMem, posY, posX, height, width);
                    // Also show bytes 8-11 (unk1, unk2) to check if height is at a different offset
                    int unk1 = ((rawData[pos+8]&0xFF)<<8) | (rawData[pos+9]&0xFF);
                    int unk2 = ((rawData[pos+10]&0xFF)<<8) | (rawData[pos+11]&0xFF);
                    System.out.printf("  ch=%d unk1=%d unk2=%d bytes[20..27]: %02X %02X %02X %02X %02X %02X %02X %02X%n",
                            ch, unk1, unk2,
                            rawData[pos+20]&0xFF, rawData[pos+21]&0xFF, rawData[pos+22]&0xFF, rawData[pos+23]&0xFF,
                            rawData[pos+24]&0xFF, rawData[pos+25]&0xFF, rawData[pos+26]&0xFF, rawData[pos+27]&0xFF);
                }
            }
        }
        System.out.println("=== END RAW SCORE BYTES ===\n");

System.out.println("\n=== Starting playback ===");
        player.play();
        System.out.println("Frame after play(): " + player.getCurrentFrame());

        StageRenderer renderer = player.getStageRenderer();
        SpriteBaker baker = new SpriteBaker(player.getBitmapCache(), clm, player);

        // Track frame transitions
        int maxTicks = 300;
        Map<Integer, Integer> frameVisits = new LinkedHashMap<>();

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

        // Dump all sprites with color info
        for (RenderSprite s : sprites) {
            String memberInfo = s.getMemberName() != null ? s.getMemberName() : "null";
            if (s.getCastMember() != null) {
                memberInfo += " [" + s.getCastMember().memberType() + "]";
            }
            System.out.printf("  ch=%d type=%s pos=(%d,%d) %dx%d ink=%s member=%s fg=0x%06X bg=0x%06X hasFg=%s hasBg=%s%n",
                    s.getChannel(), s.getType(),
                    s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                    s.getInkMode(), memberInfo,
                    s.getForeColor() & 0xFFFFFF, s.getBackColor() & 0xFFFFFF,
                    s.hasForeColor(), s.hasBackColor());
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

        // Sample differing pixels from multiple regions
        System.out.println("\nSample differing pixels (banner area y=33-137):");
        int sampled = 0;
        for (int y = 33; y < Math.min(137, h) && sampled < 10; y++) {
            for (int x = 0; x < w && sampled < 10; x++) {
                int p1 = rendered.getRGB(x, y);
                int p2 = reference.getRGB(x, y);
                int maxD = maxChannelDiff(p1, p2);
                if (maxD > 20) {
                    System.out.printf("  (%3d,%3d): rendered=0x%06X ref=0x%06X%n",
                            x, y, p1 & 0xFFFFFF, p2 & 0xFFFFFF);
                    sampled++;
                }
            }
        }
        // Text field area (right side, y=260-500)
        System.out.println("Sample differing pixels (text area y=260-500):");
        sampled = 0;
        for (int y = 260; y < Math.min(500, h) && sampled < 15; y++) {
            for (int x = 300; x < w && sampled < 15; x++) {
                int p1 = rendered.getRGB(x, y);
                int p2 = reference.getRGB(x, y);
                int maxD = maxChannelDiff(p1, p2);
                if (maxD > 20) {
                    System.out.printf("  (%3d,%3d): rendered=0x%06X ref=0x%06X%n",
                            x, y, p1 & 0xFFFFFF, p2 & 0xFFFFFF);
                    sampled++;
                }
            }
        }
        // Count pixels per diff type in text area
        int textWhiteVsGray = 0, textGrayVsWhite = 0, textOther = 0;
        for (int y = 260; y < Math.min(500, h); y++) {
            for (int x = 300; x < w; x++) {
                int p1 = rendered.getRGB(x, y);
                int p2 = reference.getRGB(x, y);
                if (maxChannelDiff(p1, p2) > 5) {
                    int r1 = (p1 >> 16) & 0xFF, r2 = (p2 >> 16) & 0xFF;
                    if (r2 > 200 && r1 < 100) textWhiteVsGray++; // ref=white, rendered=dark
                    else if (r1 > 200 && r2 < 100) textGrayVsWhite++; // rendered=white, ref=dark
                    else textOther++;
                }
            }
        }
        System.out.printf("Text area diff breakdown: ref=white/us=dark=%d ref=dark/us=white=%d other=%d%n",
                textWhiteVsGray, textGrayVsWhite, textOther);

        // Per-sprite text alignment analysis: find first/last white pixel X in each text sprite region
        int[][] textSprites = {
            {481, 482, 81, 22, 8},   // CREDITS <<
            {432, 295, 34, 11, 15},  // User :
            {409, 320, 58, 11, 16},  // Password :
            {189, 418, 374, 66, 17}, // WELCOME !!!
            {451, 265, 116, 22, 18}, // ENTER THE DISCO:
            {316, 367, 253, 22, 19}, // Not registered yet?
            {236, 385, 349, 22, 21}, // Want to modify?
            {513, 345, 73, 17, 25},  // Login!! >>
        };
        System.out.println("\nPer-sprite text alignment (first/last white pixel X offset within sprite):");
        for (int[] sp : textSprites) {
            int sx = sp[0], sy = sp[1], sw = sp[2], sh = sp[3], ch = sp[4];
            int rendFirstX = -1, rendLastX = -1, refFirstX = -1, refLastX = -1;
            for (int y = sy; y < Math.min(sy + sh, h); y++) {
                for (int x = sx; x < Math.min(sx + sw, w); x++) {
                    int rend = rendered.getRGB(x, y) & 0xFFFFFF;
                    int ref = reference.getRGB(x, y) & 0xFFFFFF;
                    if (rend > 0xC0C0C0) { // white-ish pixel (text)
                        int xOff = x - sx;
                        if (rendFirstX < 0) rendFirstX = xOff;
                        rendLastX = xOff;
                    }
                    if (ref > 0xC0C0C0) {
                        int xOff = x - sx;
                        if (refFirstX < 0) refFirstX = xOff;
                        refLastX = xOff;
                    }
                }
            }
            System.out.printf("  ch=%d (%dx%d): rend=[%d..%d] ref=[%d..%d] shift=%d%n",
                    ch, sw, sh, rendFirstX, rendLastX, refFirstX, refLastX,
                    rendFirstX >= 0 && refFirstX >= 0 ? rendFirstX - refFirstX : 0);
        }
    }

    private static int maxChannelDiff(int p1, int p2) {
        int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
        int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;
        return Math.max(Math.abs(r1 - r2), Math.max(Math.abs(g1 - g2), Math.abs(b1 - b2)));
    }

}
