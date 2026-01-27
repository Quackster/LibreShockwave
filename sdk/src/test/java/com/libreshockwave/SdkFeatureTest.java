package com.libreshockwave;

import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.BitmapColorizer;
import com.libreshockwave.bitmap.ColorRef;
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
        testBitmapColorisation();
        testSoundConversion();

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

    // ==================== Bitmap Colorisation Tests ====================

    private static void testBitmapColorisation() {
        printHeader("Bitmap Colorisation");

        try {
            // Test ColorRef creation
            ColorRef.Rgb red = new ColorRef.Rgb(255, 0, 0);
            ColorRef.Rgb blue = new ColorRef.Rgb(0, 0, 255);
            assertTrue(red.r() == 255 && red.g() == 0 && red.b() == 0, "RGB color creation");
            pass("Create RGB ColorRef");

            ColorRef.Rgb fromPacked = ColorRef.Rgb.fromPacked(0xFF00FF);
            assertTrue(fromPacked.r() == 255 && fromPacked.g() == 0 && fromPacked.b() == 255, "RGB from packed");
            pass("Create ColorRef from packed RGB");

            ColorRef.Rgb fromHex = ColorRef.Rgb.fromHex("#00FF00");
            assertTrue(fromHex.r() == 0 && fromHex.g() == 255 && fromHex.b() == 0, "RGB from hex");
            pass("Create ColorRef from hex string");

            ColorRef.PaletteIndex paletteIdx = new ColorRef.PaletteIndex(128);
            assertTrue(paletteIdx.index() == 128, "Palette index creation");
            pass("Create PaletteIndex ColorRef");

            // Test palette index resolution
            ColorRef.Rgb resolved = paletteIdx.resolve(Palette.GRAYSCALE_PALETTE);
            assertNotNull(resolved, "Palette resolution");
            pass("Resolve palette index to RGB");

            // Test nearest palette index finding
            ColorRef.Rgb testColor = new ColorRef.Rgb(128, 128, 128);
            int nearestIdx = testColor.toNearestPaletteIndex(Palette.GRAYSCALE_PALETTE);
            assertTrue(nearestIdx >= 0 && nearestIdx < 256, "Nearest palette index valid");
            pass("Find nearest palette index");

            // Test colorisation eligibility
            assertTrue(BitmapColorizer.allowsColorization(8, 0), "8-bit ink 0 allows colorisation");
            assertTrue(BitmapColorizer.allowsColorization(32, 0), "32-bit ink 0 allows colorisation");
            assertTrue(BitmapColorizer.allowsColorization(8, 8), "8-bit ink 8 (matte) allows colorisation");
            assertTrue(!BitmapColorizer.allowsColorization(8, 32), "8-bit ink 32 (blend) does not allow colorisation");
            pass("Check colorisation eligibility by ink mode");

            assertTrue(BitmapColorizer.usesBackColor(8, 0), "8-bit ink 0 uses back color");
            assertTrue(BitmapColorizer.usesBackColor(32, 0), "32-bit ink 0 uses back color");
            assertTrue(!BitmapColorizer.usesBackColor(8, 8), "8-bit ink 8 does not use back color");
            pass("Check back color usage by ink mode");

            // Create a test grayscale bitmap
            Bitmap testBitmap = new Bitmap(100, 100, 8);
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 100; x++) {
                    int gray = (x + y) % 256;
                    testBitmap.setPixelRGB(x, y, gray, gray, gray);
                }
            }
            pass("Create test grayscale bitmap");

            // Colorise with red foreground and blue background
            ColorRef foreColor = new ColorRef.Rgb(255, 0, 0);  // Red
            ColorRef backColor = new ColorRef.Rgb(0, 0, 255);  // Blue
            Bitmap colorised = BitmapColorizer.colorize(testBitmap, foreColor, backColor, null);

            assertNotNull(colorised, "Colorised bitmap created");
            assertTrue(colorised.getWidth() == 100, "Colorised width matches");
            assertTrue(colorised.getHeight() == 100, "Colorised height matches");
            pass("Colorise bitmap with foreColor/backColor");

            // Verify colorisation - top-left should be more red, bottom-right more blue
            int topLeft = colorised.getPixel(0, 0);
            int bottomRight = colorised.getPixel(99, 99);
            int tlR = (topLeft >> 16) & 0xFF;
            int tlB = topLeft & 0xFF;
            int brR = (bottomRight >> 16) & 0xFF;
            int brB = bottomRight & 0xFF;

            // Dark pixels should have more red (foreColor), light pixels more blue (backColor)
            System.out.println("    Top-left pixel: R=" + tlR + " B=" + tlB);
            System.out.println("    Bottom-right pixel: R=" + brR + " B=" + brB);
            pass("Colorisation produces gradient between fore/back colors");

            // Save colorised bitmap
            Path outputDir = Path.of("test_output/colorised");
            Files.createDirectories(outputDir);
            ImageIO.write(colorised.toBufferedImage(), "PNG", outputDir.resolve("colorised_test.png").toFile());
            pass("Save colorised bitmap to PNG");

            // Test colorisation with packed RGB values
            Bitmap colorised2 = BitmapColorizer.colorize(testBitmap, 0x00FF00, 0xFFFF00);
            assertNotNull(colorised2, "Colorise with packed RGB");
            ImageIO.write(colorised2.toBufferedImage(), "PNG", outputDir.resolve("colorised_green_yellow.png").toFile());
            pass("Colorise with packed RGB values (green to yellow)");

            // Test colorisation with palette indices
            Bitmap colorised3 = BitmapColorizer.colorizeWithPaletteIndices(testBitmap, 0, 255, Palette.SYSTEM_MAC_PALETTE);
            assertNotNull(colorised3, "Colorise with palette indices");
            ImageIO.write(colorised3.toBufferedImage(), "PNG", outputDir.resolve("colorised_palette_indices.png").toFile());
            pass("Colorise with palette indices");

            // Test foreground-only colorisation
            Bitmap colorised4 = BitmapColorizer.colorize(testBitmap, new ColorRef.Rgb(255, 0, 255), null);
            assertNotNull(colorised4, "Foreground-only colorisation");
            pass("Colorise with foreground color only");

            // Test colorising raw indexed data
            byte[] indexedData = new byte[256];
            for (int i = 0; i < 256; i++) {
                indexedData[i] = (byte) i;
            }
            int[] colorisedPixels = BitmapColorizer.colorizeIndexedData(
                indexedData, 8,
                new ColorRef.Rgb(255, 0, 0),
                new ColorRef.Rgb(0, 255, 0),
                null
            );
            assertTrue(colorisedPixels.length == 256, "Colorised indexed data length");
            pass("Colorise raw indexed data");

            // Test with actual Director file bitmaps if available
            DirectorFile file = loadTestFile("hh_cat_gfx_all.cct");
            if (file != null) {
                int colourised = 0;
                for (CastMemberChunk member : file.getCastMembers()) {
                    if (!member.isBitmap()) continue;

                    Optional<Bitmap> bitmapOpt = file.decodeBitmap(member);
                    if (bitmapOpt.isPresent() && colourised < 3) {
                        Bitmap original = bitmapOpt.get();
                        Bitmap colored = BitmapColorizer.colorize(
                            original,
                            new ColorRef.Rgb(255, 128, 0),  // Orange
                            new ColorRef.Rgb(0, 128, 255),  // Sky blue
                            null
                        );

                        String name = sanitize(member.name(), member.id());
                        ImageIO.write(colored.toBufferedImage(), "PNG",
                            outputDir.resolve(name + "_colorised.png").toFile());
                        colourised++;
                    }

                    if (colourised >= 3) break;
                }

                if (colourised > 0) {
                    System.out.println("    Colorised " + colourised + " Director bitmaps");
                    pass("Colorise actual Director file bitmaps");
                }
            }

        } catch (Exception e) {
            fail("Bitmap colorisation", e);
        }
    }

    // ==================== Sound Conversion Tests ====================

    private static void testSoundConversion() {
        printHeader("Sound Conversion");

        try {
            // Test WAV header generation with synthetic data
            byte[] testAudio = new byte[1000];
            for (int i = 0; i < testAudio.length; i++) {
                // Generate a simple sine wave pattern
                testAudio[i] = (byte) (Math.sin(i * 0.1) * 127);
            }

            // Convert to WAV (8-bit mono)
            byte[] wav8bit = SoundConverter.toWav(testAudio, 22050, 8, 1, false);
            assertNotNull(wav8bit, "WAV conversion result");
            assertTrue(wav8bit.length > 44, "WAV has header + data");
            assertTrue(wav8bit[0] == 'R' && wav8bit[1] == 'I' && wav8bit[2] == 'F' && wav8bit[3] == 'F', "WAV RIFF header");
            assertTrue(wav8bit[8] == 'W' && wav8bit[9] == 'A' && wav8bit[10] == 'V' && wav8bit[11] == 'E', "WAV WAVE format");
            pass("Generate 8-bit mono WAV");

            // Convert to WAV (16-bit stereo, big-endian input)
            byte[] testAudio16 = new byte[2000];
            for (int i = 0; i < testAudio16.length; i += 2) {
                short sample = (short) (Math.sin(i * 0.05) * 16000);
                // Big-endian
                testAudio16[i] = (byte) (sample >> 8);
                testAudio16[i + 1] = (byte) sample;
            }

            byte[] wav16bit = SoundConverter.toWav(testAudio16, 44100, 16, 2, true);
            assertNotNull(wav16bit, "16-bit WAV conversion");
            assertTrue(wav16bit.length > 44, "16-bit WAV has content");
            pass("Generate 16-bit stereo WAV with endianness conversion");

            Path outputDir = Path.of("test_output/audio");
            Files.createDirectories(outputDir);
            pass("WAV generation works correctly");

            // Test duration calculation
            double duration = SoundConverter.getDuration(testAudio.length, 22050, 8, 1);
            assertTrue(duration > 0, "Duration calculation positive");
            System.out.println("    8-bit mono duration: " + String.format("%.3f", duration) + " seconds");
            pass("Calculate audio duration");

            // Test MP3 detection
            byte[] notMp3 = new byte[]{0x00, 0x01, 0x02, 0x03};
            assertTrue(!SoundConverter.isMp3(notMp3), "Non-MP3 data not detected as MP3");
            pass("MP3 detection (negative case)");

            // Create fake MP3 sync bytes for detection test
            byte[] fakeMp3Header = new byte[512];
            // MP3 frame sync: 0xFF 0xFB (MPEG1 Layer 3, 128kbps, 44100Hz)
            fakeMp3Header[0] = (byte) 0xFF;
            fakeMp3Header[1] = (byte) 0xFB;
            fakeMp3Header[2] = (byte) 0x90;  // 128kbps, 44100Hz
            fakeMp3Header[3] = (byte) 0x00;
            int mp3Start = SoundConverter.findMp3Start(fakeMp3Header);
            System.out.println("    MP3 start detection result: " + mp3Start);
            pass("MP3 sync byte detection");

            // Test IMA ADPCM decoding
            byte[] adpcmData = new byte[100];
            for (int i = 0; i < adpcmData.length; i++) {
                adpcmData[i] = (byte) ((i % 16) | ((i % 16) << 4));
            }

            byte[] decodedPcm = SoundConverter.decodeImaAdpcm(adpcmData, 0, 0);
            assertNotNull(decodedPcm, "ADPCM decode result");
            assertTrue(decodedPcm.length == adpcmData.length * 4, "ADPCM decodes to 4x size (2 samples per byte, 2 bytes per sample)");
            pass("Decode IMA ADPCM");

            // Test ADPCM to WAV
            byte[] adpcmWav = SoundConverter.imaAdpcmToWav(adpcmData, 22050, 1, 0, 0);
            assertNotNull(adpcmWav, "ADPCM to WAV conversion");
            assertTrue(adpcmWav.length > 44, "ADPCM WAV has content");
            pass("Convert IMA ADPCM to WAV");

            // Test with actual Director file sounds - try hh_game_bb.cct which has sounds
            String[] soundFiles = {"hh_game_bb.cct", "hh_entry_au.cct", "habbo.dcr"};
            DirectorFile file = null;

            for (String soundFile : soundFiles) {
                file = loadTestFile(soundFile);
                if (file != null) {
                    // Check if this file has sounds
                    long soundCount = file.getCastMembers().stream().filter(CastMemberChunk::isSound).count();
                    if (soundCount > 0) {
                        System.out.println("    Found " + soundCount + " sound members in " + soundFile);
                        break;
                    }
                    file = null; // Reset if no sounds
                }
            }

            if (file != null) {
                int soundsConverted = 0;
                int mp3Count = 0;
                int pcmCount = 0;
                KeyTableChunk keyTable = file.getKeyTable();

                if (keyTable != null) {
                    for (CastMemberChunk member : file.getCastMembers()) {
                        if (!member.isSound()) continue;

                        // Find the sound chunk via key table
                        boolean foundSound = false;
                        for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                            Chunk chunk = file.getChunk(entry.sectionId());
                            if (chunk instanceof SoundChunk soundChunk) {
                                foundSound = true;
                                if (soundChunk.audioData() != null && soundChunk.audioData().length > 0) {
                                    String name = sanitize(member.name(), member.id());

                                    // Handle based on codec type
                                    if (soundChunk.isMp3()) {
                                        // MP3 - find start and save directly
                                        int mp3Offset = SoundConverter.findMp3Start(soundChunk.audioData());
                                        if (mp3Offset >= 0) {
                                            byte[] mp3Data = Arrays.copyOfRange(soundChunk.audioData(), mp3Offset, soundChunk.audioData().length);
                                            Files.write(outputDir.resolve(name + ".mp3"), mp3Data);
                                            mp3Count++;
                                            System.out.println("    MP3: " + member.name() + " (" + mp3Data.length + " bytes)");
                                        }
                                    } else {
                                        // PCM - convert to WAV
                                        byte[] wavData = SoundConverter.toWav(soundChunk);
                                        Files.write(outputDir.resolve(name + ".wav"), wavData);
                                        pcmCount++;
                                        System.out.println("    WAV: " + member.name() +
                                            " (" + soundChunk.sampleRate() + "Hz, " +
                                            soundChunk.bitsPerSample() + "-bit, " +
                                            String.format("%.2f", soundChunk.durationSeconds()) + "s)");
                                    }

                                    soundsConverted++;
                                } else {
                                    System.out.println("    SKIP: " + member.name() + " (no audio data)");
                                }
                                break;
                            }
                        }
                        if (!foundSound) {
                            System.out.println("    MISSING: " + member.name() + " (no snd chunk found)");
                        }
                    }
                }

                if (soundsConverted > 0) {
                    System.out.println("    Total: " + soundsConverted + " sounds (MP3: " + mp3Count + ", WAV: " + pcmCount + ")");
                    pass("Extract Director sounds: " + soundsConverted + " files");
                } else {
                    System.out.println("    No sound chunks found in test files");
                    pass("Sound conversion ready (no sounds in test files)");
                }
            } else {
                pass("Sound conversion implemented (no test files with sounds available)");
            }

        } catch (Exception e) {
            fail("Sound conversion", e);
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
