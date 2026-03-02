package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ConfigChunk;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic: dump raw config chunk bytes to verify D7+ stage color parsing.
 */
public class ConfigDumpTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        DirectorFile file = DirectorFile.load(path);
        ConfigChunk config = file.getConfig();

        System.out.println("=== Config Chunk Analysis ===");
        System.out.printf("Director version (raw): 0x%04X%n", config.directorVersion());
        System.out.printf("D7+ threshold: 0x0208 (%d)%n", 0x208);
        System.out.printf("Is D7+: %s%n", config.directorVersion() >= 0x208);
        System.out.println();

        System.out.printf("Stage: %d,%d to %d,%d = %dx%d%n",
            config.stageLeft(), config.stageTop(),
            config.stageRight(), config.stageBottom(),
            config.stageWidth(), config.stageHeight());
        System.out.println();

        System.out.printf("stageColor (raw I16): 0x%04X (%d)%n", config.stageColor() & 0xFFFF, config.stageColor());
        System.out.printf("stageColorRGB (resolved): 0x%06X%n", config.stageColorRGB());
        System.out.println();

        // Break down the raw stageColor for D7+
        int raw = config.stageColor();
        int highByte = (raw >> 8) & 0xFF;
        int lowByte = raw & 0xFF;
        System.out.printf("D7+ interpretation:%n");
        System.out.printf("  byte 26 (isRgb flag): %d%n", highByte);
        System.out.printf("  byte 27 (R component): %d (0x%02X)%n", lowByte, lowByte);
        System.out.println();

        System.out.printf("bgColor (bitDepth): %d%n", config.bgColor());
        System.out.printf("tempo: %d fps%n", config.tempo());
        System.out.printf("platform: %d%n", config.platform());

        // Now also try to directly read the raw bytes from the DRCF chunk
        // to cross-check our parsing
        System.out.println("\n=== Raw byte verification ===");
        System.out.println("(Checking if stageColorRGB() correctly decoded from G/B at offset 18-19)");

        // The stageColorRGB should be:
        // D7+ RGB mode: (R << 16) | (G << 8) | B
        // where R=byte27, G=byte18, B=byte19
        int r = (config.stageColorRGB() >> 16) & 0xFF;
        int g = (config.stageColorRGB() >> 8) & 0xFF;
        int b = config.stageColorRGB() & 0xFF;
        System.out.printf("  Resolved R=%d G=%d B=%d%n", r, g, b);

        if (config.stageColorRGB() == 0xFFFFFF) {
            System.out.println("  → WHITE background ✓");
        } else if (config.stageColorRGB() == 0x000000) {
            System.out.println("  → BLACK background");
            System.out.println("  WARNING: Black background might be correct for this DCR,");
            System.out.println("  or there might be a parsing issue with G/B bytes at offset 18-19.");
        } else {
            System.out.printf("  → Custom color: rgb(%d, %d, %d)%n", r, g, b);
        }
    }
}
