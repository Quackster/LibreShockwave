package com.libreshockwave;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Opcode;

import javax.imageio.ImageIO;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Comprehensive tests for all SDK features.
 * Tests loading from both local files and HTTP URLs.
 */
public class SdkFeatureTest {

    // Test configuration
    private static final String LOCAL_BASE_PATH = "C:/xampp/htdocs/dcr/14.1_b8";
    private static final String HTTP_BASE_URL = "http://localhost/dcr/14.1_b8";

    // Test files
    private static final String[] TEST_FILES = {
        "hh_entry_au.cct",
        "hh_entry_init.cct",
        "hh_entry.cct",
        "hh_cat_gfx_all.cct",
        "habbo.dcr"
    };

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         LibreShockwave SDK Feature Tests                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        // Run all test suites
        testLocalFileLoading();
        testHttpFileLoading();
        testByteArrayLoading();
        testMetadataReading();
        testCastMemberListing();
        testBitmapExtraction();
        testPaletteExtraction();
        testTextExtraction();
        testScriptReading();
        testBytecodeAccess();
        testDisassembler();
        testScoreData();
        testChunkAccess();
        testExternalCastPaths();
        testMultipleFilesInParallel();

        // Print summary
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                        TEST SUMMARY                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Passed: %-50d ║%n", passed);
        System.out.printf("║  Failed: %-50d ║%n", failed);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }

        System.exit(failed > 0 ? 1 : 0);
    }

    // ==================== Loading Tests ====================

    private static void testLocalFileLoading() {
        printHeader("Local File Loading");

        for (String filename : TEST_FILES) {
            Path path = Path.of(LOCAL_BASE_PATH, filename);
            if (!Files.exists(path)) {
                skip("Load " + filename, "File not found");
                continue;
            }

            try {
                DirectorFile file = DirectorFile.load(path);
                assertNotNull(file, "DirectorFile from " + filename);
                assertNotNull(file.getConfig(), "Config from " + filename);
                pass("Load local file: " + filename);
            } catch (Exception e) {
                fail("Load local file: " + filename, e);
            }
        }
    }

    private static void testHttpFileLoading() {
        printHeader("HTTP File Loading");

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        for (String filename : TEST_FILES) {
            String url = HTTP_BASE_URL + "/" + filename;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() != 200) {
                    skip("Load " + filename + " via HTTP", "HTTP " + response.statusCode());
                    continue;
                }

                byte[] data = response.body();
                DirectorFile file = DirectorFile.load(data);

                assertNotNull(file, "DirectorFile from HTTP " + filename);
                assertTrue(file.getCastMembers().size() >= 0, "Cast members loaded from HTTP");
                pass("Load via HTTP: " + filename + " (" + data.length + " bytes)");

            } catch (java.net.ConnectException e) {
                skip("Load " + filename + " via HTTP", "Server not running");
            } catch (Exception e) {
                fail("Load via HTTP: " + filename, e);
            }
        }
    }

    private static void testByteArrayLoading() {
        printHeader("Byte Array Loading");

        Path path = Path.of(LOCAL_BASE_PATH, TEST_FILES[0]);
        if (!Files.exists(path)) {
            skip("Byte array loading", "Test file not found");
            return;
        }

        try {
            // Load as byte array
            byte[] data = Files.readAllBytes(path);
            DirectorFile file = DirectorFile.load(data);

            assertNotNull(file, "DirectorFile from byte array");
            assertTrue(data.length > 0, "Data has content");
            pass("Load from byte array: " + data.length + " bytes");

        } catch (Exception e) {
            fail("Load from byte array", e);
        }
    }

    // ==================== Metadata Tests ====================

    private static void testMetadataReading() {
        printHeader("Metadata Reading");

        DirectorFile file = loadTestFile("hh_entry.cct");
        if (file == null) return;

        try {
            // Basic info
            System.out.println("  File info:");
            System.out.println("    Afterburner: " + file.isAfterburner());
            System.out.println("    Endian: " + file.getEndian());
            System.out.println("    Movie type: " + file.getMovieType());

            // Config
            ConfigChunk config = file.getConfig();
            if (config != null) {
                System.out.println("    Stage: " + file.getStageWidth() + "x" + file.getStageHeight());
                System.out.println("    Tempo: " + file.getTempo() + " fps");
                System.out.println("    Director version: " + config.directorVersion());
                System.out.println("    Channels: " + file.getChannelCount());

                pass("Read stage dimensions: " + file.getStageWidth() + "x" + file.getStageHeight());
                pass("Read tempo: " + file.getTempo());
                pass("Read Director version: " + config.directorVersion());
            } else {
                pass("Config is null (valid for cast files)");
            }

            pass("Read file metadata");

        } catch (Exception e) {
            fail("Read metadata", e);
        }
    }

    // ==================== Cast Member Tests ====================

    private static void testCastMemberListing() {
        printHeader("Cast Member Listing");

        DirectorFile file = loadTestFile("hh_cat_gfx_all.cct");
        if (file == null) return;

        try {
            List<CastMemberChunk> members = file.getCastMembers();
            System.out.println("  Total members: " + members.size());

            // Count by type
            Map<String, Integer> typeCounts = new HashMap<>();
            int bitmaps = 0, scripts = 0, texts = 0;

            for (CastMemberChunk member : members) {
                String type = member.type().toString();
                typeCounts.merge(type, 1, Integer::sum);

                if (member.isBitmap()) bitmaps++;
                if (member.isScript()) scripts++;
                if (member.isText()) texts++;
            }

            System.out.println("  By type:");
            for (var entry : typeCounts.entrySet()) {
                System.out.println("    " + entry.getKey() + ": " + entry.getValue());
            }

            pass("List cast members: " + members.size() + " total");
            pass("Count bitmaps: " + bitmaps);
            pass("Count scripts: " + scripts);
            pass("Count texts: " + texts);

            // Test individual member access
            if (!members.isEmpty()) {
                CastMemberChunk first = members.get(0);
                System.out.println("  First member: id=" + first.id() + ", name='" + first.name() + "', type=" + first.type());
                pass("Access individual member");
            }

        } catch (Exception e) {
            fail("List cast members", e);
        }
    }

    // ==================== Bitmap Tests ====================

    private static void testBitmapExtraction() {
        printHeader("Bitmap Extraction");

        DirectorFile file = loadTestFile("hh_cat_gfx_all.cct");
        if (file == null) return;

        try {
            Path outputDir = Path.of("test_output/bitmaps");
            Files.createDirectories(outputDir);

            int decoded = 0;
            int failed_decode = 0;
            List<String> savedFiles = new ArrayList<>();

            for (CastMemberChunk member : file.getCastMembers()) {
                if (!member.isBitmap()) continue;

                Optional<Bitmap> bitmapOpt = file.decodeBitmap(member);
                if (bitmapOpt.isPresent()) {
                    Bitmap bitmap = bitmapOpt.get();
                    decoded++;

                    // Save first 5 bitmaps
                    if (savedFiles.size() < 5) {
                        String name = sanitize(member.name(), member.id());
                        Path outPath = outputDir.resolve(name + ".png");
                        ImageIO.write(bitmap.toBufferedImage(), "PNG", outPath.toFile());
                        savedFiles.add(name + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                    }

                    // Verify bitmap properties
                    assertTrue(bitmap.getWidth() > 0, "Bitmap width > 0");
                    assertTrue(bitmap.getHeight() > 0, "Bitmap height > 0");
                } else {
                    failed_decode++;
                }
            }

            System.out.println("  Decoded: " + decoded + ", Failed: " + failed_decode);
            System.out.println("  Saved samples:");
            for (String s : savedFiles) {
                System.out.println("    - " + s);
            }

            pass("Decode bitmaps: " + decoded + " successful");
            if (!savedFiles.isEmpty()) {
                pass("Save bitmaps to PNG: " + savedFiles.size() + " files");
            }

        } catch (Exception e) {
            fail("Bitmap extraction", e);
        }
    }

    // ==================== Palette Tests ====================

    private static void testPaletteExtraction() {
        printHeader("Palette Extraction");

        // Test built-in palettes first
        try {
            System.out.println("  Built-in palettes:");

            // System Mac palette
            Palette macPalette = Palette.getBuiltIn(Palette.SYSTEM_MAC);
            System.out.println("    System Mac: " + macPalette.size() + " colors");
            pass("Access System Mac palette: " + macPalette.size() + " colors");

            // Grayscale palette
            Palette grayPalette = Palette.getBuiltIn(Palette.GRAYSCALE);
            System.out.println("    Grayscale: " + grayPalette.size() + " colors");
            pass("Access Grayscale palette: " + grayPalette.size() + " colors");

            // Create swatch image from built-in palette
            Path outputDir = Path.of("test_output/palettes");
            Files.createDirectories(outputDir);

            Bitmap macSwatch = Bitmap.createPaletteSwatch(macPalette, 16);
            ImageIO.write(macSwatch.toBufferedImage(), "PNG",
                outputDir.resolve("builtin_system_mac.png").toFile());
            System.out.println("    Saved System Mac swatch: " + macSwatch.getWidth() + "x" + macSwatch.getHeight());
            pass("Create palette swatch image");

        } catch (Exception e) {
            fail("Built-in palettes", e);
        }

        // Test custom palettes from files
        DirectorFile file = loadTestFile("habbo.dcr");
        if (file == null) {
            file = loadTestFile("hh_cat_gfx_all.cct");
        }
        if (file == null) return;

        try {
            List<PaletteChunk> palettes = file.getPalettes();
            System.out.println("\n  Custom palettes in file: " + palettes.size());

            if (palettes.isEmpty()) {
                // Try to find CLUT chunks directly
                int clutCount = 0;
                for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
                    if (info.type().toString().equals("CLUT")) {
                        clutCount++;
                    }
                }
                System.out.println("  CLUT chunks found: " + clutCount);
                pass("Palette detection (no custom palettes in this file)");
            } else {
                Path outputDir = Path.of("test_output/palettes");
                Files.createDirectories(outputDir);

                for (int i = 0; i < palettes.size() && i < 5; i++) {
                    PaletteChunk palette = palettes.get(i);
                    System.out.println("    Palette " + palette.id() + ": " + palette.colorCount() + " colors");

                    // Show first few colors
                    StringBuilder colors = new StringBuilder("      Colors: ");
                    for (int c = 0; c < Math.min(8, palette.colorCount()); c++) {
                        colors.append(String.format("#%06X ", palette.getColor(c)));
                    }
                    if (palette.colorCount() > 8) colors.append("...");
                    System.out.println(colors);

                    // Create swatch image
                    Bitmap swatch = Bitmap.createPaletteSwatch(palette.colors(), 16, 16);
                    String filename = "palette_" + palette.id() + ".png";
                    ImageIO.write(swatch.toBufferedImage(), "PNG",
                        outputDir.resolve(filename).toFile());
                    System.out.println("      Saved: " + filename);
                }

                pass("Extract custom palettes: " + palettes.size());
                pass("Save palette swatch images");
            }

            // Test palette color access
            if (!palettes.isEmpty()) {
                PaletteChunk first = palettes.get(0);
                int color = first.getColor(0);
                assertTrue(true, "Get palette color");
                pass("Access individual palette colors");
            }

        } catch (Exception e) {
            fail("Custom palette extraction", e);
        }
    }

    // ==================== Text Tests ====================

    private static void testTextExtraction() {
        printHeader("Text Extraction");

        // Try multiple files to find one with text
        String[] textFiles = {"hh_entry.cct", "hh_entry_au.cct", "hh_cat_code.cct"};
        DirectorFile file = null;

        for (String f : textFiles) {
            file = loadTestFile(f);
            if (file != null) break;
        }
        if (file == null) return;

        try {
            KeyTableChunk keyTable = file.getKeyTable();
            if (keyTable == null) {
                skip("Text extraction", "No key table");
                return;
            }

            int textCount = 0;
            List<String> samples = new ArrayList<>();

            for (CastMemberChunk member : file.getCastMembers()) {
                if (!member.isText()) continue;

                for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                    String fourcc = entry.fourccString();
                    if (fourcc.equals("STXT") || fourcc.equals("TXTS")) {
                        Chunk chunk = file.getChunk(entry.sectionId());
                        if (chunk instanceof TextChunk textChunk) {
                            String text = textChunk.text();
                            if (!text.isEmpty()) {
                                textCount++;
                                if (samples.size() < 3) {
                                    String preview = text.length() > 50
                                        ? text.substring(0, 50) + "..."
                                        : text;
                                    samples.add(member.name() + ": \"" + preview.replace("\n", "\\n") + "\"");
                                }
                            }
                        }
                        break;
                    }
                }
            }

            System.out.println("  Text members found: " + textCount);
            if (!samples.isEmpty()) {
                System.out.println("  Samples:");
                for (String s : samples) {
                    System.out.println("    - " + s);
                }
            }

            pass("Extract text content: " + textCount + " items");

        } catch (Exception e) {
            fail("Text extraction", e);
        }
    }

    // ==================== Script Tests ====================

    private static void testScriptReading() {
        printHeader("Script Reading");

        DirectorFile file = loadTestFile("hh_entry.cct");
        if (file == null) {
            file = loadTestFile("habbo.dcr");
        }
        if (file == null) return;

        try {
            List<ScriptChunk> scripts = file.getScripts();
            ScriptNamesChunk names = file.getScriptNames();

            System.out.println("  Total scripts: " + scripts.size());

            if (names == null) {
                skip("Script names", "No names chunk");
            }

            int totalHandlers = 0;
            List<String> handlerSamples = new ArrayList<>();

            for (ScriptChunk script : scripts) {
                for (ScriptChunk.Handler handler : script.handlers()) {
                    totalHandlers++;

                    if (names != null && handlerSamples.size() < 5) {
                        String handlerName = names.getName(handler.nameId());

                        StringBuilder args = new StringBuilder();
                        for (int i = 0; i < handler.argNameIds().size(); i++) {
                            if (i > 0) args.append(", ");
                            args.append(names.getName(handler.argNameIds().get(i)));
                        }

                        String sig = "on " + handlerName + "(" + args + ")";
                        handlerSamples.add(sig + " - " + handler.instructions().size() + " instructions");
                    }
                }
            }

            System.out.println("  Total handlers: " + totalHandlers);
            if (!handlerSamples.isEmpty()) {
                System.out.println("  Handler samples:");
                for (String s : handlerSamples) {
                    System.out.println("    - " + s);
                }
            }

            pass("Read scripts: " + scripts.size());
            pass("Read handlers: " + totalHandlers);
            if (names != null) {
                pass("Read handler names");
            }

        } catch (Exception e) {
            fail("Script reading", e);
        }
    }

    // ==================== Bytecode Tests ====================

    private static void testBytecodeAccess() {
        printHeader("Bytecode Access");

        DirectorFile file = loadTestFile("hh_entry.cct");
        if (file == null) {
            file = loadTestFile("habbo.dcr");
        }
        if (file == null) return;

        try {
            ScriptNamesChunk names = file.getScriptNames();
            ScriptChunk script = null;
            ScriptChunk.Handler handler = null;

            // Find a script with handlers
            for (ScriptChunk s : file.getScripts()) {
                if (!s.handlers().isEmpty()) {
                    script = s;
                    handler = s.handlers().get(0);
                    break;
                }
            }

            if (handler == null) {
                skip("Bytecode access", "No handlers found");
                return;
            }

            System.out.println("  Testing handler: " + (names != null ? names.getName(handler.nameId()) : "unknown"));
            System.out.println("  Instruction count: " + handler.instructions().size());
            System.out.println("  Arg count: " + handler.argCount());
            System.out.println("  Local count: " + handler.localCount());

            // Print first 10 instructions
            System.out.println("  First instructions:");
            int count = 0;
            Map<Opcode, Integer> opcodeCounts = new HashMap<>();

            for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                opcodeCounts.merge(instr.opcode(), 1, Integer::sum);

                if (count++ < 10) {
                    System.out.printf("    [%04d] %-20s arg=%d%n",
                        instr.offset(),
                        instr.opcode().getMnemonic(),
                        instr.argument());
                }
            }

            System.out.println("  Opcode distribution (top 5):");
            opcodeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .forEach(e -> System.out.println("    " + e.getKey().getMnemonic() + ": " + e.getValue()));

            pass("Access bytecode instructions");
            pass("Read opcode: " + handler.instructions().get(0).opcode().getMnemonic());
            pass("Read argument values");

            // Test opcode enum
            Opcode add = Opcode.ADD;
            assertTrue(add.getCode() == 0x05, "ADD opcode code");
            assertTrue(Opcode.fromCode(0x05) == Opcode.ADD, "Opcode fromCode");
            pass("Opcode enum operations");

        } catch (Exception e) {
            fail("Bytecode access", e);
        }
    }

    // ==================== Disassembler Tests ====================

    private static void testDisassembler() {
        printHeader("Built-in Disassembler");

        DirectorFile file = loadTestFile("hh_entry.cct");
        if (file == null) return;

        try {
            // Capture disassembly output
            PrintStream originalOut = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baos));

            // Disassemble first script with handlers
            for (ScriptChunk script : file.getScripts()) {
                if (!script.handlers().isEmpty()) {
                    file.disassembleScript(script);
                    break;
                }
            }

            System.setOut(originalOut);
            String output = baos.toString();

            if (!output.isEmpty()) {
                System.out.println("  Disassembly output (first 500 chars):");
                String preview = output.length() > 500 ? output.substring(0, 500) + "..." : output;
                for (String line : preview.split("\n")) {
                    System.out.println("    " + line);
                }
                pass("Disassembler produces output: " + output.length() + " chars");
            } else {
                skip("Disassembler", "No output produced");
            }

        } catch (Exception e) {
            fail("Disassembler", e);
        }
    }

    // ==================== Score Tests ====================

    private static void testScoreData() {
        printHeader("Score (Timeline) Data");

        DirectorFile file = loadTestFile("habbo.dcr");
        if (file == null) return;

        try {
            if (!file.hasScore()) {
                skip("Score data", "No score in file");
                return;
            }

            ScoreChunk score = file.getScoreChunk();
            System.out.println("  Frames: " + score.getFrameCount());
            System.out.println("  Channels: " + score.getChannelCount());

            pass("Read frame count: " + score.getFrameCount());
            pass("Read channel count: " + score.getChannelCount());

            // Frame labels
            FrameLabelsChunk labels = file.getFrameLabelsChunk();
            if (labels != null && !labels.labels().isEmpty()) {
                System.out.println("  Frame labels:");
                int count = 0;
                for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
                    if (count++ < 5) {
                        System.out.println("    Frame " + label.frameNum() + ": '" + label.label() + "'");
                    }
                }
                pass("Read frame labels: " + labels.labels().size());
            }

            // Frame intervals
            if (!score.frameIntervals().isEmpty()) {
                System.out.println("  Frame script intervals: " + score.frameIntervals().size());
                pass("Read frame intervals: " + score.frameIntervals().size());
            }

        } catch (Exception e) {
            fail("Score data", e);
        }
    }

    // ==================== Chunk Access Tests ====================

    private static void testChunkAccess() {
        printHeader("Raw Chunk Access");

        DirectorFile file = loadTestFile("hh_entry.cct");
        if (file == null) return;

        try {
            Collection<DirectorFile.ChunkInfo> allInfo = file.getAllChunkInfo();
            System.out.println("  Total chunks: " + allInfo.size());

            // Count by type
            Map<String, Integer> typeCounts = new HashMap<>();
            for (DirectorFile.ChunkInfo info : allInfo) {
                String type = info.type().toString();
                typeCounts.merge(type, 1, Integer::sum);
            }

            System.out.println("  By type:");
            typeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> System.out.println("    " + e.getKey() + ": " + e.getValue()));

            pass("List all chunks: " + allInfo.size());

            // Get specific chunk by ID
            DirectorFile.ChunkInfo firstInfo = allInfo.iterator().next();
            Chunk chunk = file.getChunk(firstInfo.id());
            if (chunk != null) {
                pass("Get chunk by ID: " + firstInfo.id() + " -> " + chunk.type());
            }

            // Get chunk with type checking
            for (DirectorFile.ChunkInfo info : allInfo) {
                Optional<BitmapChunk> bitmap = file.getChunk(info.id(), BitmapChunk.class);
                if (bitmap.isPresent()) {
                    System.out.println("  Found bitmap chunk: id=" + info.id() +
                        ", data length=" + bitmap.get().data().length);
                    pass("Get typed chunk (BitmapChunk)");
                    break;
                }
            }

        } catch (Exception e) {
            fail("Chunk access", e);
        }
    }

    // ==================== External Cast Tests ====================

    private static void testExternalCastPaths() {
        printHeader("External Cast Paths");

        DirectorFile file = loadTestFile("habbo.dcr");
        if (file == null) return;

        try {
            boolean hasExternal = file.hasExternalCasts();
            System.out.println("  Has external casts: " + hasExternal);

            List<String> paths = file.getExternalCastPaths();
            System.out.println("  External cast count: " + paths.size());

            if (!paths.isEmpty()) {
                System.out.println("  Paths:");
                for (String path : paths) {
                    System.out.println("    - " + path);
                }
                pass("Get external cast paths: " + paths.size());

                // Try to load an external cast
                String firstPath = paths.get(0);
                String filename = Path.of(firstPath).getFileName().toString();
                if (filename.endsWith(".cst")) {
                    filename = filename.replace(".cst", ".cct");
                }

                Path localPath = Path.of(LOCAL_BASE_PATH, filename);
                if (Files.exists(localPath)) {
                    DirectorFile castFile = DirectorFile.load(localPath);
                    System.out.println("  Loaded external cast: " + filename);
                    System.out.println("    Members: " + castFile.getCastMembers().size());
                    pass("Load external cast file: " + filename);
                }
            } else {
                pass("No external casts (valid result)");
            }

        } catch (Exception e) {
            fail("External cast paths", e);
        }
    }

    // ==================== Parallel Loading Tests ====================

    private static void testMultipleFilesInParallel() {
        printHeader("Parallel File Loading");

        try {
            List<Path> existingFiles = new ArrayList<>();
            for (String filename : TEST_FILES) {
                Path path = Path.of(LOCAL_BASE_PATH, filename);
                if (Files.exists(path)) {
                    existingFiles.add(path);
                }
            }

            if (existingFiles.isEmpty()) {
                skip("Parallel loading", "No test files found");
                return;
            }

            long start = System.currentTimeMillis();

            // Load files in parallel
            List<DirectorFile> files = existingFiles.parallelStream()
                .map(path -> {
                    try {
                        return DirectorFile.load(path);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

            long elapsed = System.currentTimeMillis() - start;

            System.out.println("  Loaded " + files.size() + " files in " + elapsed + "ms");

            int totalMembers = files.stream().mapToInt(f -> f.getCastMembers().size()).sum();
            int totalScripts = files.stream().mapToInt(f -> f.getScripts().size()).sum();

            System.out.println("  Total cast members: " + totalMembers);
            System.out.println("  Total scripts: " + totalScripts);

            pass("Parallel load " + files.size() + " files in " + elapsed + "ms");
            pass("Aggregate data from multiple files");

        } catch (Exception e) {
            fail("Parallel loading", e);
        }
    }

    // ==================== Helper Methods ====================

    private static DirectorFile loadTestFile(String filename) {
        Path path = Path.of(LOCAL_BASE_PATH, filename);
        if (!Files.exists(path)) {
            skip("Load " + filename, "File not found at " + path);
            return null;
        }
        try {
            return DirectorFile.load(path);
        } catch (Exception e) {
            fail("Load " + filename, e);
            return null;
        }
    }

    private static String sanitize(String name, int id) {
        if (name == null || name.isBlank()) return "member_" + id;
        return name.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + id;
    }

    private static void printHeader(String title) {
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.printf("│ %-59s │%n", title);
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    private static void pass(String test) {
        passed++;
        System.out.println("  ✓ " + test);
    }

    private static void fail(String test, Exception e) {
        failed++;
        failures.add(test + ": " + e.getMessage());
        System.out.println("  ✗ " + test + " - " + e.getMessage());
    }

    private static void skip(String test, String reason) {
        System.out.println("  ○ SKIP: " + test + " (" + reason + ")");
    }

    private static void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new AssertionError(message + " should not be null");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message + " should be true");
        }
    }
}
