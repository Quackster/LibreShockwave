package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.FontRegistry;
import com.libreshockwave.player.render.*;

import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.Chunk;
import com.libreshockwave.chunks.RawChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.chunks.CastMemberChunk;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Pixel-by-pixel comparison of AwtTextRenderer vs SimpleTextRenderer,
 * and AwtFrameRenderer vs SoftwareFrameRenderer.
 * Goal: SoftwareRenderer output must match AwtRenderer output PERFECTLY.
 */
public class RendererCompareTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/renderer-compare";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();

        // Load game to register PFR fonts
        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt"
        ));
        player.preloadAllCasts();

        // Manually load hh_interface.cct to register PFR fonts (v, vb)
        // preloadAllCasts only loads casts referenced in the main file,
        // hh_interface.cct is loaded dynamically by Lingo during gameplay
        loadPfrFontsFromCct("C:/xampp/htdocs/dcr/14.1_b8/hh_interface.cct");

        AwtTextRenderer awtText = new AwtTextRenderer();
        SimpleTextRenderer simpleText = new SimpleTextRenderer();

        int totalTests = 0;
        int passedTests = 0;

        // Test text rendering with Volter fonts at different configs
        String[][] testCases = {
            // {text, fontName, fontSize, fontStyle, alignment, description}
            // Director struct.font.bold: font="vb", fontStyle=[#plain]
            {"First time here?", "vb", "9", "", "center", "login-title-bold"},
            {"Haven't got a Habbo yet?\nYou can create one here.", "v", "9", "", "center", "login-body"},
            {"Oops, error!", "vb", "9", "", "left", "error-title-bold"},
            {"Error occured, press 'OK' to restart program.", "v", "9", "", "left", "error-body"},
            {"OK->", "vb", "9", "", "right", "error-ok-bold"},
            {"Forgotten your password?", "v", "9", "", "center", "login-forgot"},
            {"What's your Habbo called?", "vb", "9", "", "left", "login-label-bold"},
            {"Name", "v", "9", "", "left", "login-input-label"},
            {"Password", "v", "9", "", "left", "login-password-label"},
            // Test fontStyle="bold" resolving "v" → "vb" via suffix lookup
            {"Bold via style", "v", "9", "bold", "left", "style-bold-via-fontstyle"},
            // Test synthetic bold when no bold variant exists (uses base font + double-strike)
            {"Synthetic bold", "v", "9", "bold", "left", "synthetic-bold-test"},
        };

        for (String[] tc : testCases) {
            String text = tc[0];
            String fontName = tc[1];
            int fontSize = Integer.parseInt(tc[2]);
            String fontStyle = tc[3];
            String alignment = tc[4];
            String desc = tc[5];

            int width = 250;
            int height = 40;
            int textColor = 0xFF000000;
            int bgColor = 0xFFFFFFFF;

            Bitmap awtBmp = awtText.renderText(text, width, height,
                    fontName, fontSize, fontStyle, alignment, textColor, bgColor,
                    true, false, 0, 0);
            Bitmap swBmp = simpleText.renderText(text, width, height,
                    fontName, fontSize, fontStyle, alignment, textColor, bgColor,
                    true, false, 0, 0);

            // Save both
            ImageIO.write(awtBmp.toBufferedImage(), "png", new File(OUTPUT_DIR + "/" + desc + "_awt.png"));
            ImageIO.write(swBmp.toBufferedImage(), "png", new File(OUTPUT_DIR + "/" + desc + "_sw.png"));

            // Compare
            totalTests++;
            CompareResult result = comparePixels(awtBmp, swBmp);

            // Create diff image
            Bitmap diffBmp = createDiffImage(awtBmp, swBmp);
            ImageIO.write(diffBmp.toBufferedImage(), "png", new File(OUTPUT_DIR + "/" + desc + "_diff.png"));

            boolean pass = result.significant == 0;
            if (pass) passedTests++;

            System.out.printf("%s %s: exact=%.1f%% off1=%.1f%% sig=%.1f%% (%dx%d vs %dx%d)%n",
                    pass ? "PASS" : "FAIL", desc,
                    result.exactPct, result.offByOnePct, result.significantPct,
                    awtBmp.getWidth(), awtBmp.getHeight(),
                    swBmp.getWidth(), swBmp.getHeight());

            if (!pass && result.significant > 0) {
                showFirstDiffs(awtBmp, swBmp, 5);
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.printf("Passed: %d/%d%n", passedTests, totalTests);
        System.out.println("Output: " + OUTPUT_DIR);
        System.out.println(passedTests == totalTests ? "\nALL TESTS PASSED" : "\nSOME TESTS FAILED");

        player.shutdown();
    }

    /**
     * Load PFR1 fonts from a .cct external cast file and register them in FontRegistry.
     */
    static void loadPfrFontsFromCct(String path) throws Exception {
        DirectorFile cctFile = DirectorFile.load(Path.of(path));
        KeyTableChunk keyTable = cctFile.getKeyTable();
        if (keyTable == null) return;
        int xmedFourcc = ChunkType.XMED.getFourCC();
        for (CastMemberChunk member : cctFile.getCastMembers()) {
            var entry = keyTable.findEntry(member.id(), xmedFourcc);
            if (entry == null) continue;
            Chunk chunk = cctFile.getChunk(entry.sectionId());
            if (!(chunk instanceof RawChunk raw)) continue;
            byte[] data = raw.data();
            if (data == null || data.length < 4) continue;
            if (data[0] == 'P' && data[1] == 'F' && data[2] == 'R' && data[3] == '1') {
                String memberName = member.name();
                if (memberName != null && !memberName.isEmpty()) {
                    FontRegistry.registerPfr1Font(memberName, data);
                    System.out.println("Registered PFR font: " + memberName);
                }
            }
        }
    }

    record CompareResult(int exact, int offByOne, int significant, int total,
                          double exactPct, double offByOnePct, double significantPct) {}

    static CompareResult comparePixels(Bitmap a, Bitmap b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int[] ap = a.getPixels();
        int[] bp = b.getPixels();
        int aw = a.getWidth();
        int bw = b.getWidth();

        int exact = 0, offByOne = 0, sig = 0;
        int total = w * h;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixA = ap[y * aw + x];
                int pixB = bp[y * bw + x];
                if (pixA == pixB) {
                    exact++;
                } else {
                    int maxDiff = maxChannelDiff(pixA, pixB);
                    if (maxDiff <= 1) offByOne++;
                    else sig++;
                }
            }
        }

        return new CompareResult(exact, offByOne, sig, total,
                100.0 * exact / total, 100.0 * offByOne / total, 100.0 * sig / total);
    }

    static int maxChannelDiff(int a, int b) {
        int da = Math.abs(((a >> 24) & 0xFF) - ((b >> 24) & 0xFF));
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(Math.max(da, dr), Math.max(dg, db));
    }

    static Bitmap createDiffImage(Bitmap a, Bitmap b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int[] ap = a.getPixels();
        int[] bp = b.getPixels();
        int aw = a.getWidth();
        int bw = b.getWidth();
        int[] diff = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixA = ap[y * aw + x];
                int pixB = bp[y * bw + x];
                if (pixA == pixB) {
                    diff[y * w + x] = 0xFF000000;
                } else {
                    int maxDiff = maxChannelDiff(pixA, pixB);
                    int intensity = Math.min(255, maxDiff * 4);
                    diff[y * w + x] = 0xFF000000 | (intensity << 16) | (intensity << 8); // yellow
                }
            }
        }

        return new Bitmap(w, h, 32, diff);
    }

    static void showFirstDiffs(Bitmap a, Bitmap b, int count) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int[] ap = a.getPixels();
        int[] bp = b.getPixels();
        int aw = a.getWidth();
        int bw = b.getWidth();
        int shown = 0;

        for (int y = 0; y < h && shown < count; y++) {
            for (int x = 0; x < w && shown < count; x++) {
                int pixA = ap[y * aw + x];
                int pixB = bp[y * bw + x];
                int maxDiff = maxChannelDiff(pixA, pixB);
                if (maxDiff > 1) {
                    System.out.printf("    (%d,%d): AWT=%08X SW=%08X%n", x, y, pixA, pixB);
                    shown++;
                }
            }
        }
    }
}
