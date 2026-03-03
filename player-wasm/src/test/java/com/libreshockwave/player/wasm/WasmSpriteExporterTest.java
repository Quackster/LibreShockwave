package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.player.wasm.SpriteDataExporter;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Integration test for the WASM sprite export pipeline.
 * Loads habbo.dcr, plays a few frames, exports sprite JSON, decodes bitmaps,
 * and renders a PNG to validate the rendering bridge works end-to-end.
 *
 * Run: ./gradlew :player-wasm:runWasmSpriteExporterTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class WasmSpriteExporterTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_PNG = "player-wasm/build/wasm-sprite-test.png";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        System.out.println("=== WasmSpriteExporterTest: Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        SpriteDataExporter exporter = new SpriteDataExporter(player);

        System.out.println("=== Playing and stepping frames ===");
        player.play();
        for (int i = 0; i < 10; i++) {
            player.stepFrame();
            Thread.sleep(100);  // Allow async bitmap decoding
        }

        System.out.println("=== Exporting frame data ===");
        String json = exporter.exportFrameData();
        System.out.println("[Test] JSON length: " + json.length());
        System.out.println("[Test] JSON preview: " + json.substring(0, Math.min(200, json.length())));

        // Verify JSON structure
        if (!json.startsWith("{\"bg\":")) {
            System.err.println("FAIL: Malformed JSON header: " + json.substring(0, Math.min(100, json.length())));
            System.exit(1);
        }
        if (!json.contains("\"sprites\":[")) {
            System.err.println("FAIL: Missing sprites array");
            System.exit(1);
        }

        // Validate JSON is parseable (no control char corruption) by checking balance
        int braceDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{' || c == '[') braceDepth++;
                else if (c == '}' || c == ']') braceDepth--;
            }
        }
        if (braceDepth != 0) {
            System.err.println("FAIL: JSON is unbalanced (depth=" + braceDepth + ") — likely malformed");
            System.exit(1);
        }
        System.out.println("[Test] JSON structure: valid (balanced braces)");

        // Count visible sprites and decoded bitmaps
        FrameSnapshot snap = player.getFrameSnapshot();
        int bitmapCount = 0, decodedCount = 0, totalVisible = 0;
        for (RenderSprite sprite : snap.sprites()) {
            if (!sprite.isVisible()) continue;
            totalVisible++;
            if (sprite.getType() == RenderSprite.SpriteType.BITMAP && sprite.getCastMemberId() > 0) {
                bitmapCount++;
                byte[] rgba = exporter.getBitmapRGBA(sprite.getCastMemberId());
                if (rgba != null) decodedCount++;
            }
        }
        System.out.println("[Test] Total visible sprites: " + totalVisible);
        System.out.println("[Test] Bitmap sprites: " + bitmapCount + ", decoded: " + decodedCount);

        if (totalVisible == 0) {
            System.err.println("WARN: No visible sprites in first 10 frames — check player startup");
        }

        // Render snapshot to PNG
        renderToPng(snap, exporter, Path.of(OUTPUT_PNG));
        System.out.println("[Test] PASSED — PNG saved to " + OUTPUT_PNG);
    }

    private static void renderToPng(FrameSnapshot snap, SpriteDataExporter exporter, Path output) throws Exception {
        int w = 720, h = 540;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Background
        int bg = snap.backgroundColor();
        g.setColor(new Color(bg & 0xFFFFFF));
        g.fillRect(0, 0, w, h);

        // Draw sprites in order
        for (RenderSprite sprite : snap.sprites()) {
            if (!sprite.isVisible()) continue;

            float alpha = Math.min(1f, sprite.getBlend() / 100.0f);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

            if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                int mid = sprite.getCastMemberId();
                byte[] rgba = exporter.getBitmapRGBA(mid);
                if (rgba == null) continue;
                int bw = exporter.getBitmapWidth(mid);
                int bh = exporter.getBitmapHeight(mid);
                if (bw <= 0 || bh <= 0) continue;
                BufferedImage bmp = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < bw * bh; i++) {
                    int r = rgba[i * 4] & 0xFF;
                    int gr = rgba[i * 4 + 1] & 0xFF;
                    int b = rgba[i * 4 + 2] & 0xFF;
                    int a = rgba[i * 4 + 3] & 0xFF;
                    bmp.setRGB(i % bw, i / bw, (a << 24) | (r << 16) | (gr << 8) | b);
                }
                g.drawImage(bmp, sprite.getX(), sprite.getY(), null);
            } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                int fc = sprite.getForeColor();
                g.setColor(new Color(fc & 0xFFFFFF));
                g.fillRect(sprite.getX(), sprite.getY(),
                    Math.max(1, sprite.getWidth()), Math.max(1, sprite.getHeight()));
            }
        }

        g.dispose();
        Files.createDirectories(output.getParent());
        ImageIO.write(img, "png", output.toFile());
    }
}
